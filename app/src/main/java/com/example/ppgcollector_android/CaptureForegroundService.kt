package com.example.ppgcollector_android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.ppgcollector_android.core.ble.BleCoordinator
import com.example.ppgcollector_android.data.session.CaptureDeviceContext
import com.example.ppgcollector_android.data.session.CaptureRecordingController
import com.example.ppgcollector_android.data.session.CaptureRecordingSnapshot
import com.example.ppgcollector_android.data.session.CaptureAnalysisSnapshot
import com.example.ppgcollector_android.data.session.CaptureStartFailure
import com.example.ppgcollector_android.core.signal.LiveWaveformSnapshot
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.example.ppgcollector_android.data.session.CaptureRecordingStartResult
import com.example.ppgcollector_android.data.session.CaptureSessionConfiguration
import com.example.ppgcollector_android.data.session.CaptureStorageCapacityProvider
import com.example.ppgcollector_android.data.session.CaptureStopReason
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

/**
 * Connected-device foreground owner seam. The service owns the recording
 * controller while the app-scope coordinator remains the single GATT owner.
 */
class CaptureForegroundService : Service() {
    private lateinit var recordingController: CaptureRecordingController
    private lateinit var bleCoordinator: BleCoordinator
    private var rawSinkInstalled = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var stopJob: Job? = null
    private val _runtimeFailure = MutableStateFlow<CaptureStartFailure?>(null)

    private val localBinder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun service(): CaptureForegroundService = this@CaptureForegroundService
        fun snapshot(): CaptureRecordingSnapshot = this@CaptureForegroundService.snapshot()
        fun recordingFlow(): StateFlow<CaptureRecordingSnapshot> =
            this@CaptureForegroundService.recordingFlow()
        fun analysisSnapshot(): CaptureAnalysisSnapshot =
            this@CaptureForegroundService.analysisSnapshot()
        fun analysisFlow(): StateFlow<CaptureAnalysisSnapshot> =
            this@CaptureForegroundService.analysisFlow()
        fun waveformSnapshot(): LiveWaveformSnapshot =
            this@CaptureForegroundService.waveformSnapshot()
        fun waveformFlow(): StateFlow<LiveWaveformSnapshot> =
            this@CaptureForegroundService.waveformFlow()
        fun runtimeFailureFlow(): StateFlow<CaptureStartFailure?> =
            this@CaptureForegroundService.runtimeFailureFlow()
        fun stop(): Unit = this@CaptureForegroundService.stopRecording(CaptureStopReason.USER)
    }

    override fun onCreate() {
        super.onCreate()
        val sessionsRoot = (application as PpgCollectorApplication).sessionsRoot
        recordingController = CaptureRecordingController(
            sessionsRoot = sessionsRoot,
            capacityProvider = CaptureStorageCapacityProvider { root ->
                runCatching { Files.getFileStore(root).usableSpace }.getOrNull()
            },
        )
        bleCoordinator = (application as PpgCollectorApplication).bleCoordinator
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> stopRecording(CaptureStopReason.USER)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = localBinder

    override fun onDestroy() {
        stopJob?.cancel()
        stopJob = null
        serviceScope.cancel()
        if (rawSinkInstalled) {
            bleCoordinator.onRawChunk = null
            rawSinkInstalled = false
        }
        recordingController.close()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    fun snapshot(): CaptureRecordingSnapshot = recordingController.snapshot

    fun recordingFlow(): StateFlow<CaptureRecordingSnapshot> = recordingController.snapshotFlow

    fun analysisSnapshot(): CaptureAnalysisSnapshot = recordingController.analysisSnapshot.value

    fun analysisFlow(): StateFlow<CaptureAnalysisSnapshot> = recordingController.analysisSnapshot

    fun waveformSnapshot(): LiveWaveformSnapshot = recordingController.waveformSnapshot.value

    fun waveformFlow(): StateFlow<LiveWaveformSnapshot> = recordingController.waveformSnapshot

    fun runtimeFailureFlow(): StateFlow<CaptureStartFailure?> = _runtimeFailure

    fun stopRecording(reason: CaptureStopReason) {
        recordingController.stop(reason)
        val state = recordingController.snapshot.state
        if (state != com.example.ppgcollector_android.data.session.CaptureRecordingState.RECORDING &&
            state != com.example.ppgcollector_android.data.session.CaptureRecordingState.STOPPING
        ) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        stopJob?.cancel()
        stopJob = serviceScope.launch {
            recordingController.snapshotFlow.first { snapshot ->
                snapshot.state == com.example.ppgcollector_android.data.session.CaptureRecordingState.FINALIZED ||
                    snapshot.state == com.example.ppgcollector_android.data.session.CaptureRecordingState.FAILED
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startRecording(intent: Intent) {
        _runtimeFailure.value = null
        if (recordingController.snapshot.state ==
            com.example.ppgcollector_android.data.session.CaptureRecordingState.RECORDING
        ) return

        val notification = buildNotification(
            deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "CUP",
            status = "准备录制",
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (error: SecurityException) {
            _runtimeFailure.value = CaptureStartFailure.ForegroundServiceStartRejected
            stopSelfResult(0)
            return
        }

        val snapshot = bleCoordinator.snapshot
        val phase = snapshot.phase
        val deviceId = phase.deviceId
        val baseName = intent.getStringExtra(EXTRA_SESSION_NAME)
        if (deviceId == null || baseName == null) {
            stopRecording(CaptureStopReason.PROTOCOL_ERROR)
            return
        }

        val profile = snapshot.activeProfile
        if (profile == null) {
            stopRecording(CaptureStopReason.PROTOCOL_ERROR)
            return
        }
        val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?:
            snapshot.discoveredDevices.firstOrNull { it.id == deviceId }?.name ?: "CUP"
        val result = recordingController.start(
            configuration = CaptureSessionConfiguration(
                sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: UUID.randomUUID().toString(),
                baseName = baseName,
                startedUtc = Instant.now(),
                softVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown",
                algorithmVersion = "unavailable",
                preprocessProfile = "ios-baseline-0.1",
                protocolProfile = profile.identifier,
                transportProfile = "android-ble-gatt-0.1",
                device = CaptureDeviceContext(
                    name = deviceName,
                    identifier = deviceId,
                    serviceUuid = profile.serviceUuid,
                    notifyCharacteristicUuid = profile.notifyCharacteristicUuid,
                ),
            ),
            phase = phase,
            freshness = snapshot.freshness,
            connectionGeneration = snapshot.connectionGeneration,
            availableBytes = runCatching {
                Files.getFileStore((application as PpgCollectorApplication).sessionsRoot.parent)
                    .usableSpace
            }.getOrNull(),
        )
        if (result is CaptureRecordingStartResult.Started) {
            bleCoordinator.onRawChunk = recordingController::onRawChunk
            rawSinkInstalled = true
            updateNotification(deviceName, "录制中")
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateNotification(deviceName: String, status: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(deviceName, status))
    }

    private fun buildNotification(deviceName: String, status: String): Notification =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("CUP 录制")
            .setContentText("$deviceName · $status")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                NotificationCompat.Action(
                    0,
                    "停止并保存",
                    PendingIntent.getService(
                        this,
                        STOP_REQUEST_CODE,
                        Intent(this, CaptureForegroundService::class.java).setAction(ACTION_STOP),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                ),
            )
            .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "CUP 录制",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "显示 CUP 原始数据录制状态和停止入口"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.example.ppgcollector_android.action.START_CAPTURE"
        const val ACTION_STOP = "com.example.ppgcollector_android.action.STOP_CAPTURE"
        const val EXTRA_SESSION_NAME = "capture_session_name"
        const val EXTRA_SESSION_ID = "capture_session_id"
        const val EXTRA_DEVICE_NAME = "capture_device_name"
        const val NOTIFICATION_CHANNEL_ID = "capture_recording"
        const val NOTIFICATION_ID = 4101
        const val STOP_REQUEST_CODE = 4102
        const val SESSIONS_DIRECTORY = "sessions"

        fun startIntent(context: Context, sessionName: String, deviceName: String? = null): Intent =
            Intent(context, CaptureForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SESSION_NAME, sessionName)
                if (deviceName != null) putExtra(EXTRA_DEVICE_NAME, deviceName)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, CaptureForegroundService::class.java).setAction(ACTION_STOP)
    }
}
