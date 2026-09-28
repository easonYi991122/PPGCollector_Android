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
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import com.example.ppgcollector_android.data.session.CaptureRecordingStartResult
import com.example.ppgcollector_android.data.session.CaptureSessionConfiguration
import com.example.ppgcollector_android.data.session.CaptureStorageCapacityProvider
import com.example.ppgcollector_android.data.session.CaptureStopReason
import com.example.ppgcollector_android.data.session.CaptureReferenceTimestamp
import com.example.ppgcollector_android.data.session.ManualBloodPressureEvent
import com.example.ppgcollector_android.data.session.sessionScopedParticipantFields
import com.example.ppgcollector_android.data.session.CaptureParticipantSnapshot
import com.example.ppgcollector_android.data.session.CaptureRecordMode
import com.example.ppgcollector_android.data.session.SubjectProfileStore
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
    private lateinit var lifecycle: CaptureServiceLifecycle
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var healthJob: Job? = null
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
        fun clearRuntimeFailure(): Unit = this@CaptureForegroundService.clearRuntimeFailure()
        fun stop(): Unit = this@CaptureForegroundService.stopRecording(CaptureStopReason.USER)
        fun captureReferenceTimestamp(
            dialogOpenUtc: Instant = Instant.now(),
            dialogOpenHostMonotonicNanoseconds: ULong = System.nanoTime().toULong(),
        ): CaptureReferenceTimestamp? = this@CaptureForegroundService
            .captureReferenceTimestamp(dialogOpenUtc, dialogOpenHostMonotonicNanoseconds)
        fun commitManualBloodPressure(event: ManualBloodPressureEvent): Boolean =
            this@CaptureForegroundService.commitManualBloodPressure(event)
        fun updateParticipantProfile(sessionId: String, participant: CaptureParticipantSnapshot?): Boolean =
            this@CaptureForegroundService.updateParticipantProfile(sessionId, participant)
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
        lifecycle = CaptureServiceLifecycle(
            controller = recordingController,
            scope = serviceScope,
            claimRecordingOwner = bleCoordinator::tryAcquireRecordingOwner,
            releaseRecordingOwner = bleCoordinator::releaseRecordingOwner,
            attachSink = { token -> bleCoordinator.attachRecordingSink(token, recordingController::onRawChunk) },
            detachSink = bleCoordinator::detachRecordingSink,
            removeForeground = {
                healthJob?.cancel()
                healthJob = null
                stopForeground(STOP_FOREGROUND_REMOVE)
            },
            stopSelf = { startId -> stopSelfResult(startId) },
            onFailure = { _runtimeFailure.value = it },
            onTerminal = (application as PpgCollectorApplication)::recordCaptureTerminal,
        )
    }

    private fun startHealthMonitoring() {
        healthJob?.cancel()
        healthJob = serviceScope.launch {
            val health = CaptureStreamHealthMonitor(android.os.SystemClock::elapsedRealtime, STREAM_STALE_GRACE_MILLIS)
            while (isActive) {
                kotlinx.coroutines.delay(STREAM_HEALTH_POLL_MILLIS)
                health.stopReason(recordingController.snapshot, bleCoordinator.snapshot)?.let(::stopRecording)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent, startId)
            ACTION_STOP -> {
                if (!lifecycle.stop(CaptureStopReason.USER, startId)) {
                    stopSelfResult(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = localBinder

    override fun onDestroy() {
        healthJob?.cancel()
        healthJob = null
        // The transaction retains its scope until controller terminal publication.
        lifecycle.destroy()
        super.onDestroy()
    }

    fun snapshot(): CaptureRecordingSnapshot = lifecycle.recording.value

    fun recordingFlow(): StateFlow<CaptureRecordingSnapshot> = lifecycle.recording

    fun analysisSnapshot(): CaptureAnalysisSnapshot = recordingController.analysisSnapshot.value

    fun analysisFlow(): StateFlow<CaptureAnalysisSnapshot> = recordingController.analysisSnapshot

    fun waveformSnapshot(): LiveWaveformSnapshot = recordingController.waveformSnapshot.value

    fun waveformFlow(): StateFlow<LiveWaveformSnapshot> = recordingController.waveformSnapshot

    fun runtimeFailureFlow(): StateFlow<CaptureStartFailure?> = _runtimeFailure

    private fun clearRuntimeFailure() {
        _runtimeFailure.value = null
    }

    fun captureReferenceTimestamp(
        dialogOpenUtc: Instant = Instant.now(),
        dialogOpenHostMonotonicNanoseconds: ULong = System.nanoTime().toULong(),
    ): CaptureReferenceTimestamp? = recordingController.captureReferenceTimestamp(
        dialogOpenUtc,
        dialogOpenHostMonotonicNanoseconds,
    )

    fun commitManualBloodPressure(event: ManualBloodPressureEvent): Boolean =
        recordingController.commitManualBloodPressure(event)

    fun updateParticipantProfile(sessionId: String, participant: CaptureParticipantSnapshot?): Boolean =
        recordingController.updateParticipantProfile(sessionId, participant)

    fun stopRecording(reason: CaptureStopReason) {
        lifecycle.stop(reason)
    }

    private fun startRecording(intent: Intent, startId: Int) {
        if (lifecycle.acknowledgeStartId(startId)) return
        _runtimeFailure.value = null
        val snapshot = bleCoordinator.snapshot
        val deviceId = snapshot.phase.deviceId
        val baseName = intent.getStringExtra(EXTRA_SESSION_NAME)
        val profile = snapshot.activeProfile
        val streamProtocolMode = snapshot.activeStreamProtocolMode
        val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?:
            snapshot.discoveredDevices.firstOrNull { it.id == deviceId }?.name ?: "CUP"
        val participant = participantFromIntent(intent)
        val configuration = CaptureSessionConfiguration(
            sessionId = UUID.randomUUID().toString(),
            baseName = baseName.orEmpty(),
            startedUtc = Instant.now(),
            softVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown",
            algorithmVersion = "unavailable",
            preprocessProfile = "ios-baseline-0.1",
            protocolProfile = streamProtocolMode?.configuredProfileIdentifier.orEmpty(),
            transportProfile = profile?.identifier.orEmpty(),
            device = CaptureDeviceContext(
                name = deviceName,
                identifier = deviceId.orEmpty(),
                serviceUuid = profile?.serviceUuid.orEmpty(),
                notifyCharacteristicUuid = profile?.notifyCharacteristicUuid.orEmpty(),
            ),
            participant = participant,
            systolicBp = intent.getIntExtra(EXTRA_SBP, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
            diastolicBp = intent.getIntExtra(EXTRA_DBP, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
            recordMode = intent.getStringExtra(EXTRA_RECORD_MODE)
                ?.let { runCatching { CaptureRecordMode.valueOf(it) }.getOrNull() }
                ?: CaptureRecordMode.MANUAL,
            plannedDurationSeconds = intent.getIntExtra(EXTRA_PLANNED_DURATION_SECONDS, Int.MIN_VALUE)
                .takeIf { it != Int.MIN_VALUE },
        )
        lifecycle.start(
            startId = startId,
            sessionId = configuration.sessionId,
            baseName = configuration.baseName,
            prepareForeground = {
                val notification = buildNotification(deviceName, "准备录制")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            },
            initialize = {
                val current = bleCoordinator.snapshot
                val failure = when {
                    deviceId == null || baseName == null || profile == null || streamProtocolMode == null ->
                        CaptureStartFailure.DeviceNotReady
                    current.connectionGeneration != snapshot.connectionGeneration ||
                        current.phase.deviceId != deviceId ||
                        current.activeProfile?.identifier != configuration.transportProfile ->
                        CaptureStartFailure.DeviceNotReady
                    current.phase !is com.example.ppgcollector_android.core.ble.BleConnectionPhase.Subscribed &&
                        current.phase !is com.example.ppgcollector_android.core.ble.BleConnectionPhase.Receiving ->
                        CaptureStartFailure.DeviceNotReady
                    current.freshness != com.example.ppgcollector_android.core.signal.StreamFreshness.FRESH ->
                        CaptureStartFailure.StreamNotFresh
                    current.activeStreamProtocolMode?.configuredProfileIdentifier != configuration.protocolProfile ->
                        CaptureStartFailure.DeviceNotReady
                    else -> null
                }
                if (failure != null) CaptureRecordingStartResult.Rejected(failure) else {
                    recordingController.start(
                        configuration = configuration,
                        phase = current.phase,
                        freshness = current.freshness,
                        connectionGeneration = current.connectionGeneration,
                        availableBytes = runCatching {
                            Files.getFileStore((application as PpgCollectorApplication).sessionsRoot.parent).usableSpace
                        }.getOrNull(),
                        participant = participant,
                    )
                }
            },
            onAccepted = {
                updateNotification(deviceName, "录制中")
                startHealthMonitoring()
                persistAcceptedParticipant(configuration.sessionId, participant)
            },
        )
    }

    private fun persistAcceptedParticipant(sessionId: String, participant: CaptureParticipantSnapshot?) {
        val subject = participant?.subjectId ?: return
        if (!participant.profileComplete) return
        lifecycle.persistParticipant(sessionId) {
            val saved =
                SubjectProfileStore((application as PpgCollectorApplication).subjectsRoot).saveRevision(
                    subject = subject,
                    sex = participant.sex,
                    ageYears = participant.ageYears,
                    heightCm = participant.heightCm,
                    weightKg = participant.weightKg,
                    smokingFreq = participant.smokingFreq,
                    drinkingFreq = participant.drinkingFreq,
                    additionalFields = participant.additionalFields,
                )
            val latest = saved.latest ?: return@persistParticipant null
            latest.asParticipantSnapshot(subject).copy(
                sequence = participant.sequence,
                additionalFields = latest.additionalFields + participant.additionalFields.sessionScopedParticipantFields(),
            )
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
        const val EXTRA_PARTICIPANT_PRESENT = "capture_participant_present"
        const val EXTRA_PARTICIPANT_SUBJECT = "capture_participant_subject"
        const val EXTRA_PARTICIPANT_SEQUENCE = "capture_participant_sequence"
        const val EXTRA_PARTICIPANT_REVISION = "capture_participant_revision"
        const val EXTRA_PARTICIPANT_COMPLETE = "capture_participant_complete"
        const val EXTRA_PARTICIPANT_SEX = "capture_participant_sex"
        const val EXTRA_PARTICIPANT_GENDER_CODE = "capture_participant_gender_code"
        const val EXTRA_PARTICIPANT_AGE = "capture_participant_age"
        const val EXTRA_PARTICIPANT_HEIGHT = "capture_participant_height"
        const val EXTRA_PARTICIPANT_WEIGHT = "capture_participant_weight"
        const val EXTRA_PARTICIPANT_SMOKING = "capture_participant_smoking"
        const val EXTRA_PARTICIPANT_DRINKING = "capture_participant_drinking"
        const val EXTRA_SBP = "capture_sbp"
        const val EXTRA_DBP = "capture_dbp"
        const val EXTRA_RECORD_MODE = "capture_record_mode"
        const val EXTRA_PLANNED_DURATION_SECONDS = "capture_planned_duration_seconds"
        const val EXTRA_PARTICIPANT_KEYS = "capture_participant_keys"
        const val EXTRA_PARTICIPANT_VALUES = "capture_participant_values"
        const val NOTIFICATION_CHANNEL_ID = "capture_recording"
        const val NOTIFICATION_ID = 4101
        const val STOP_REQUEST_CODE = 4102
        const val SESSIONS_DIRECTORY = "sessions"
        const val STREAM_HEALTH_POLL_MILLIS = 1_000L
        const val STREAM_STALE_GRACE_MILLIS = 5_000L

        fun startIntent(
            context: Context,
            sessionName: String,
            deviceName: String? = null,
            participant: CaptureParticipantSnapshot? = null,
            systolicBp: Int? = null,
            diastolicBp: Int? = null,
            recordMode: CaptureRecordMode = CaptureRecordMode.MANUAL,
            plannedDurationSeconds: Int? = null,
        ): Intent =
            Intent(context, CaptureForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SESSION_NAME, sessionName)
                if (deviceName != null) putExtra(EXTRA_DEVICE_NAME, deviceName)
                systolicBp?.let { putExtra(EXTRA_SBP, it) }
                diastolicBp?.let { putExtra(EXTRA_DBP, it) }
                putExtra(EXTRA_RECORD_MODE, recordMode.name)
                plannedDurationSeconds?.let { putExtra(EXTRA_PLANNED_DURATION_SECONDS, it) }
                if (participant != null) {
                    putExtra(EXTRA_PARTICIPANT_PRESENT, true)
                    participant.subjectId?.let { putExtra(EXTRA_PARTICIPANT_SUBJECT, it) }
                    participant.sequence?.let { putExtra(EXTRA_PARTICIPANT_SEQUENCE, it) }
                    participant.profileRevisionId?.let { putExtra(EXTRA_PARTICIPANT_REVISION, it) }
                    putExtra(EXTRA_PARTICIPANT_COMPLETE, participant.profileComplete)
                    participant.sex?.let { putExtra(EXTRA_PARTICIPANT_SEX, it) }
                    participant.genderCode?.let { putExtra(EXTRA_PARTICIPANT_GENDER_CODE, it) }
                    participant.ageYears?.let { putExtra(EXTRA_PARTICIPANT_AGE, it) }
                    participant.heightCm?.let { putExtra(EXTRA_PARTICIPANT_HEIGHT, it) }
                    participant.weightKg?.let { putExtra(EXTRA_PARTICIPANT_WEIGHT, it) }
                    putExtra(EXTRA_PARTICIPANT_SMOKING, participant.smokingFreq)
                    putExtra(EXTRA_PARTICIPANT_DRINKING, participant.drinkingFreq)
                    putStringArrayListExtra(EXTRA_PARTICIPANT_KEYS, ArrayList(participant.additionalFields.keys))
                    putStringArrayListExtra(EXTRA_PARTICIPANT_VALUES, ArrayList(participant.additionalFields.values))
                }
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, CaptureForegroundService::class.java).setAction(ACTION_STOP)
    }

    private fun participantFromIntent(intent: Intent): CaptureParticipantSnapshot? {
        if (!intent.getBooleanExtra(EXTRA_PARTICIPANT_PRESENT, false)) return null
        val keys = intent.getStringArrayListExtra(EXTRA_PARTICIPANT_KEYS).orEmpty()
        val values = intent.getStringArrayListExtra(EXTRA_PARTICIPANT_VALUES).orEmpty()
        val additional = keys.zip(values).toMap()
        val sequence = intent.getLongExtra(EXTRA_PARTICIPANT_SEQUENCE, Long.MIN_VALUE)
            .takeIf { it != Long.MIN_VALUE }
        val age = intent.getIntExtra(EXTRA_PARTICIPANT_AGE, Int.MIN_VALUE)
            .takeIf { it != Int.MIN_VALUE }
        val height = intent.getDoubleExtra(EXTRA_PARTICIPANT_HEIGHT, Double.NaN)
            .takeIf(Double::isFinite)
        val weight = intent.getDoubleExtra(EXTRA_PARTICIPANT_WEIGHT, Double.NaN)
            .takeIf(Double::isFinite)
        return CaptureParticipantSnapshot(
            subjectId = intent.getStringExtra(EXTRA_PARTICIPANT_SUBJECT),
            sequence = sequence,
            profileRevisionId = intent.getStringExtra(EXTRA_PARTICIPANT_REVISION),
            profileComplete = intent.getBooleanExtra(EXTRA_PARTICIPANT_COMPLETE, false),
            sex = intent.getStringExtra(EXTRA_PARTICIPANT_SEX),
            genderCode = intent.getIntExtra(EXTRA_PARTICIPANT_GENDER_CODE, Int.MIN_VALUE)
                .takeIf { it != Int.MIN_VALUE },
            ageYears = age,
            heightCm = height,
            weightKg = weight,
            smokingFreq = intent.getStringExtra(EXTRA_PARTICIPANT_SMOKING).orEmpty(),
            drinkingFreq = intent.getStringExtra(EXTRA_PARTICIPANT_DRINKING).orEmpty(),
            additionalFields = additional,
        )
    }
}
