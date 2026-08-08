package com.example.ppgcollector_android

import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.ppgcollector_android.core.ble.BleCoordinatorAction
import com.example.ppgcollector_android.core.ble.BleCoordinatorSnapshot
import com.example.ppgcollector_android.core.ble.BleConnectionPhase
import com.example.ppgcollector_android.core.ble.BlePreviewSnapshot
import com.example.ppgcollector_android.core.ble.DiscoveredBleDevice
import com.example.ppgcollector_android.core.signal.LiveMetricSnapshot
import com.example.ppgcollector_android.core.signal.LiveWaveformPlotMath
import com.example.ppgcollector_android.core.signal.LiveWaveformScaleMath
import com.example.ppgcollector_android.core.signal.LiveWaveformSnapshot
import com.example.ppgcollector_android.core.signal.MetricResult
import com.example.ppgcollector_android.core.signal.PpgDisplayTransform
import com.example.ppgcollector_android.core.signal.StreamFreshness
import com.example.ppgcollector_android.data.session.CaptureRecordingState
import com.example.ppgcollector_android.data.session.CaptureNotificationPermissionPolicy
import com.example.ppgcollector_android.data.session.CaptureParticipantDraft
import com.example.ppgcollector_android.data.session.CaptureReferenceTimestamp
import com.example.ppgcollector_android.data.session.ManualBloodPressureEvent
import com.example.ppgcollector_android.data.session.CupRawReplayReport
import com.example.ppgcollector_android.data.session.ReplayWaveformViewport
import com.example.ppgcollector_android.ui.theme.PPGCollector_AndroidTheme
import java.util.Locale

private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"

private enum class AppPage { LIVE, SESSIONS, SESSIONS_FLAT, SESSION_DETAIL, WORKBENCH, COMPARE }
private enum class LiveWaveformDisplayMode { RAW, CAUSAL, FIXED_LAG }

class MainActivity : ComponentActivity() {
    private val captureViewModel: CaptureViewModel by viewModels()
    private val sessionsViewModel: SessionsViewModel by viewModels()
    private var archiveExportMode = false

    private val bleCoordinator
        get() = (application as PpgCollectorApplication).bleCoordinator

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        bleCoordinator.applyPermissionResult(grants)
        if (bleCoordinator.snapshot.permission.canUseBle) {
            bleCoordinator.startScanning(clearPreviousResults = true)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        captureViewModel.setNotificationPermissionResult(granted)
        if (granted) captureViewModel.startRecording(notificationPermissionGranted = true)
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { destination ->
        if (destination == null) {
            sessionsViewModel.cancelExportPicker()
            archiveExportMode = false
        } else {
            if (archiveExportMode) sessionsViewModel.exportArchiveTo(destination)
            else sessionsViewModel.exportSelectedTo(destination)
            archiveExportMode = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PPGCollector_AndroidTheme {
                val snapshot by bleCoordinator.snapshotFlow.collectAsStateWithLifecycle()
                val capture by captureViewModel.serviceState.collectAsStateWithLifecycle()
                val sessionName by captureViewModel.sessionName.collectAsStateWithLifecycle()
                val participantDraft by captureViewModel.participantDraft.collectAsStateWithLifecycle()
                val captureGate by captureViewModel.captureGate.collectAsStateWithLifecycle()
                val sessionNameIsValid = remember(sessionName) {
                    captureViewModel.validateSessionName().isValid
                }
                val preview by captureViewModel.previewState.collectAsStateWithLifecycle()
                val sessions by sessionsViewModel.state.collectAsStateWithLifecycle()
                var page by rememberSaveable { mutableStateOf(AppPage.LIVE) }
                var bloodPressureReference by remember { mutableStateOf<CaptureReferenceTimestamp?>(null) }
                LaunchedEffect(page) {
                    val insets = WindowCompat.getInsetsController(window, window.decorView)
                    if (page == AppPage.WORKBENCH) {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        insets.systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        insets.hide(WindowInsetsCompat.Type.systemBars())
                    } else {
                        if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                        insets.show(WindowInsetsCompat.Type.systemBars())
                    }
                }
                BackHandler(enabled = page != AppPage.LIVE) {
                    page = when (page) {
                        AppPage.WORKBENCH -> AppPage.SESSION_DETAIL
                        AppPage.SESSION_DETAIL, AppPage.COMPARE -> AppPage.SESSIONS
                        AppPage.SESSIONS_FLAT -> {
                            sessionsViewModel.cancelSessionSelection()
                            AppPage.SESSIONS
                        }
                        AppPage.SESSIONS -> AppPage.LIVE
                        AppPage.LIVE -> AppPage.LIVE
                    }
                }
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background,
                ) { innerPadding ->
                    when (page) {
                        AppPage.LIVE -> BleHome(
                            snapshot = snapshot,
                            capture = capture,
                            preview = preview,
                            sessionName = sessionName,
                            participantDraft = participantDraft,
                            sessionNameIsValid = sessionNameIsValid,
                            captureGate = captureGate,
                            onSessionNameChange = captureViewModel::setSessionName,
                            onParticipantDraftChange = captureViewModel::setParticipantDraft,
                            onUseSuggestedName = captureViewModel::useSuggestedSessionName,
                            onStartCapture = ::requestCaptureStart,
                            onStopCapture = captureViewModel::stopRecording,
                            onOpenBloodPressure = {
                                bloodPressureReference = captureViewModel.captureReferenceTimestamp()
                            },
                            onScan = ::requestScan,
                            onStopScan = bleCoordinator::stopScanning,
                            onConnect = bleCoordinator::connect,
                            onDisconnect = bleCoordinator::disconnect,
                            onOpenSessions = {
                                sessionsViewModel.refresh()
                                page = AppPage.SESSIONS
                            },
                            modifier = Modifier.padding(innerPadding),
                        )
                        AppPage.SESSIONS -> SubjectArchiveScreen(
                            state = sessions,
                            onBack = {
                                sessionsViewModel.cancelSessionSelection()
                                page = AppPage.LIVE
                            },
                            onRefresh = sessionsViewModel::refresh,
                            onSelect = { item ->
                                sessionsViewModel.select(item)
                                page = AppPage.SESSION_DETAIL
                            },
                            onToggleSubject = sessionsViewModel::toggleArchiveSubject,
                            onToggleSession = sessionsViewModel::toggleArchiveSession,
                            onExport = ::requestArchiveExport,
                            onDelete = sessionsViewModel::deleteSelectedSessions,
                            onBeginSelection = sessionsViewModel::beginSessionSelection,
                            onSelectAll = sessionsViewModel::selectAllArchive,
                            onCancelSelection = sessionsViewModel::cancelSessionSelection,
                            onOpenFlat = { page = AppPage.SESSIONS_FLAT },
                            modifier = Modifier.padding(innerPadding),
                        )
                        AppPage.SESSIONS_FLAT -> SavedSessionsScreen(
                            state = sessions,
                            onBack = {
                                sessionsViewModel.cancelSessionSelection()
                                page = AppPage.SESSIONS
                            },
                            onRefresh = sessionsViewModel::refresh,
                            onSelect = { item ->
                                sessionsViewModel.select(item)
                                page = AppPage.SESSION_DETAIL
                            },
                            onToggleSelection = sessionsViewModel::toggleArchiveSession,
                            onBeginSelection = sessionsViewModel::beginSessionSelection,
                            onSelectAll = sessionsViewModel::selectAllSessions,
                            onCancelSelection = sessionsViewModel::cancelSessionSelection,
                            onExportSelection = ::requestArchiveExport,
                            onDeleteSelected = sessionsViewModel::deleteSelectedSessions,
                            onOpenArchive = { page = AppPage.SESSIONS },
                            onCancelAnalysis = sessionsViewModel::cancelAnalysis,
                            onOpenCompare = { page = AppPage.COMPARE },
                            modifier = Modifier.padding(innerPadding),
                        )
                        AppPage.SESSION_DETAIL -> SavedSessionDetailScreen(
                            state = sessions,
                            onBack = {
                                sessionsViewModel.clearSelection()
                                page = AppPage.SESSIONS
                            },
                            onCancelInspection = sessionsViewModel::cancelInspection,
                            onRequestExport = ::requestSessionExport,
                            onRecover = sessionsViewModel::recoverSelected,
                            onCancelAction = sessionsViewModel::cancelAction,
                            onClearAction = sessionsViewModel::clearAction,
                            onStartAnalysis = sessionsViewModel::startAnalysis,
                            onCancelAnalysis = sessionsViewModel::cancelAnalysis,
                            onOpenFullscreenWorkbench = { page = AppPage.WORKBENCH },
                            modifier = Modifier.padding(innerPadding),
                        )
                        AppPage.WORKBENCH -> FullscreenSessionWorkbenchScreen(
                            state = sessions,
                            onBack = { page = AppPage.SESSION_DETAIL },
                            modifier = Modifier.fillMaxSize(),
                        )
                        AppPage.COMPARE -> SessionComparisonScreen(
                            state = sessions,
                            onBack = { page = AppPage.SESSIONS },
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                    bloodPressureReference?.let { reference ->
                        ManualBloodPressureDialog(
                            reference = reference,
                            onDismiss = { bloodPressureReference = null },
                            onSave = captureViewModel::commitManualBloodPressure,
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        captureViewModel.onStart()
        sessionsViewModel.refresh()
    }

    override fun onStop() {
        captureViewModel.onStop()
        super.onStop()
    }

    private fun requestScan() {
        val missing = bleCoordinator.permissionRequest()
        if (missing.isEmpty()) {
            bleCoordinator.startScanning(clearPreviousResults = true)
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requestCaptureStart() {
        val requiresNotificationPermission =
            CaptureNotificationPermissionPolicy.isRuntimePermissionRequired(Build.VERSION.SDK_INT)
        if (!requiresNotificationPermission ||
            checkSelfPermission(POST_NOTIFICATIONS_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            captureViewModel.setNotificationPermissionResult(granted = true)
            captureViewModel.startRecording()
        } else {
            notificationPermissionLauncher.launch(POST_NOTIFICATIONS_PERMISSION)
        }
    }

    private fun requestSessionExport(item: SessionListItemUi) {
        archiveExportMode = false
        exportLauncher.launch("${item.baseName}.zip")
    }

    private fun requestArchiveExport() {
        archiveExportMode = true
        exportLauncher.launch("ppgcollector-archive.zip")
    }
}

@androidx.compose.runtime.Composable
private fun BleHome(
    snapshot: BleCoordinatorSnapshot,
    capture: CaptureServiceObservation,
    preview: BlePreviewSnapshot,
    sessionName: String,
    participantDraft: CaptureParticipantDraft,
    sessionNameIsValid: Boolean,
    captureGate: CaptureGateUiState,
    onSessionNameChange: (String) -> Unit,
    onParticipantDraftChange: (CaptureParticipantDraft) -> Unit,
    onUseSuggestedName: () -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onOpenBloodPressure: () -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (String) -> BleCoordinatorAction,
    onDisconnect: () -> Unit,
    onOpenSessions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var waveformDisplayMode by rememberSaveable { mutableStateOf(LiveWaveformDisplayMode.FIXED_LAG) }
    var recordingDetailsExpanded by rememberSaveable { mutableStateOf(false) }
    val recordingActive = capture.recording.state == CaptureRecordingState.RECORDING ||
        capture.recording.state == CaptureRecordingState.STOPPING
    LaunchedEffect(recordingActive) {
        if (!recordingActive) recordingDetailsExpanded = false
    }
    val waveform = if (recordingActive && capture.waveform.red.isNotEmpty()) {
        capture.waveform
    } else {
        preview.waveform
    }
    val metrics = if (recordingActive) {
        capture.analysis.lastResult?.snapshot
    } else {
        preview.lastAnalysis?.snapshot
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 18.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "CUPCollector",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "PPG 实时采集与完整数据记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onOpenSessions) {
                Text("已保存会话")
            }
            }
        }

        item {
            Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        StatusDot(
                            if (snapshot.phase.isReadyToDisconnect) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                        )
                        Column {
                            Text(
                                snapshot.availability.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                connectionStatusText(snapshot.phase, snapshot.isScanning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FreshnessPill(snapshot.freshness)
                        if (recordingActive && snapshot.phase.isReadyToDisconnect) {
                            Button(
                                onClick = onDisconnect,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                            ) { Text("断开") }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "权限 ${snapshot.permission.gateState} · 录制 ${capture.recording.state} · 服务 ${capture.binding}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (recordingActive) {
                    TextButton(onClick = { recordingDetailsExpanded = !recordingDetailsExpanded }) {
                        Text(if (recordingDetailsExpanded) "收起设备/诊断" else "展开设备/诊断")
                    }
                }
            }
            }
        }

        if (!recordingActive || recordingDetailsExpanded) {
            item {
                Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("附近设备", style = MaterialTheme.typography.titleMedium)
                    if (snapshot.isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onScan, enabled = !snapshot.isScanning) {
                        Text("扫描 CUP")
                    }
                    OutlinedButton(onClick = onStopScan, enabled = snapshot.isScanning) {
                        Text("停止扫描")
                    }
                }
                if (snapshot.discoveredDevices.isEmpty()) {
                    Text(
                        if (snapshot.isScanning) {
                            "正在查找名称以 CUP 开头的设备…"
                        } else {
                            "暂无 CUP 设备，扫描会在 10 秒后自动停止。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    CupDeviceList(
                        devices = snapshot.discoveredDevices,
                        phase = snapshot.phase,
                        onConnect = onConnect,
                        onDisconnect = onDisconnect,
                    )
                }
                    snapshot.lastError?.let {
                        Text("蓝牙：$it", color = MaterialTheme.colorScheme.error)
                    }
                }
                }
            }
        }

        if (recordingActive) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilledTonalButton(
                            onClick = onOpenBloodPressure,
                            modifier = Modifier.weight(1f),
                        ) { Text("血压记录") }
                        Button(
                            onClick = onStopCapture,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) { Text("停止并保存") }
                    }
                }
            }
        }

        item {
            Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("实时 PPG 波形", style = MaterialTheme.typography.titleMedium)
                    FreshnessPill(snapshot.freshness)
                }
                LiveWaveformAndMetrics(
                    waveform = waveform,
                    metrics = metrics,
                    displayMode = waveformDisplayMode,
                    onDisplayModeChange = { waveformDisplayMode = it },
                )
            }
            }
        }

        item {
            Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("数据记录", style = MaterialTheme.typography.titleMedium)
                    Text(
                        capture.recording.state.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (recordingActive) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (!recordingActive) {
                    OutlinedTextField(
                        value = sessionName,
                        onValueChange = onSessionNameChange,
                        label = { Text("录制名称") },
                        placeholder = { Text("PPG-subject-seq") },
                        supportingText = { Text("仅支持字母、数字、下划线和短横线；示例名需替换 seq") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "建议名可直接采用，也可自由修改",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onUseSuggestedName) { Text("使用建议名") }
                    }
                    val nameReady = sessionNameIsValid
                    Text(
                        if (nameReady) "被试资料（可先留空，停止前可补齐）" else "名称合法且不重复后可填写被试资料",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    OutlinedTextField(
                        value = participantDraft.sex,
                        onValueChange = { onParticipantDraftChange(participantDraft.copy(sex = it)) },
                        label = { Text("性别") },
                        enabled = nameReady,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = participantDraft.ageYears,
                            onValueChange = { onParticipantDraftChange(participantDraft.copy(ageYears = it.filter(Char::isDigit))) },
                            label = { Text("年龄") },
                            enabled = nameReady,
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = participantDraft.heightCm,
                            onValueChange = { onParticipantDraftChange(participantDraft.copy(heightCm = it)) },
                            label = { Text("身高 cm") },
                            enabled = nameReady,
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = participantDraft.weightKg,
                            onValueChange = { onParticipantDraftChange(participantDraft.copy(weightKg = it)) },
                            label = { Text("体重 kg") },
                            enabled = nameReady,
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Button(
                        onClick = onStartCapture,
                        enabled = captureGate.canStart,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("开始录制")
                    }
                    Text(
                        if (captureGate.canStart) {
                            "设备与数据流已就绪，可以开始录制。"
                        } else {
                            "开始条件：${captureGate.message ?: "正在检查"}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (captureGate.canStart) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                } else {
                    Text(
                        "录制操作已固定在页面上方；打开血压弹窗不会暂停数据流。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                capture.error?.let { Text("服务：$it", color = MaterialTheme.colorScheme.error) }
            }
            }
        }

    }
}

/**
 * The parent screen owns vertical scrolling, so this list must remain a
 * non-scrollable child. A nested unbounded LazyColumn crashes when the first
 * discovered device makes this conditional branch enter composition.
 */
@androidx.compose.runtime.Composable
internal fun CupDeviceList(
    devices: List<DiscoveredBleDevice>,
    phase: BleConnectionPhase,
    onConnect: (String) -> BleCoordinatorAction,
    onDisconnect: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        devices.forEachIndexed { index, device ->
            key(device.id) {
                val isActive = phase.deviceId == device.id
                val isBusy = isActive && phase.isBusy
                val isConnected = isActive && phase.isReadyToDisconnect
                val anotherDeviceIsActive = phase.deviceId != null && !isActive
                val signalColor = when {
                    device.rssi == null -> MaterialTheme.colorScheme.outline
                    device.rssi >= -60 -> MaterialTheme.colorScheme.tertiary
                    device.rssi >= -75 -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.error
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        StatusDot(signalColor)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                device.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${device.rssi?.let { "$it dBm" } ?: "RSSI —"} · ${device.id.take(8)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    when {
                        isBusy -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Text("连接中…", style = MaterialTheme.typography.labelMedium)
                        }
                        isConnected -> Button(
                            onClick = onDisconnect,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) {
                            Text("断开")
                        }
                        else -> Button(
                            onClick = { onConnect(device.id) },
                            enabled = device.isConnectable && !anotherDeviceIsActive,
                        ) {
                            Text("连接")
                        }
                    }
                }
                if (index != devices.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun StatusDot(color: Color) {
    Surface(
        modifier = Modifier.size(10.dp),
        shape = RoundedCornerShape(50),
        color = color,
    ) {}
}

@androidx.compose.runtime.Composable
private fun FreshnessPill(freshness: StreamFreshness) {
    val color = when (freshness) {
        StreamFreshness.FRESH -> MaterialTheme.colorScheme.tertiary
        StreamFreshness.WAITING -> MaterialTheme.colorScheme.primary
        StreamFreshness.STALE -> MaterialTheme.colorScheme.error
        StreamFreshness.UNAVAILABLE -> MaterialTheme.colorScheme.outline
    }
    val label = when (freshness) {
        StreamFreshness.FRESH -> "数据新鲜"
        StreamFreshness.WAITING -> "等待数据"
        StreamFreshness.STALE -> "数据超时"
        StreamFreshness.UNAVAILABLE -> "未连接"
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun connectionStatusText(phase: BleConnectionPhase, isScanning: Boolean): String =
    if (isScanning) {
        "正在扫描 CUP 设备"
    } else {
        when (phase) {
            BleConnectionPhase.Idle -> "等待扫描或连接"
            is BleConnectionPhase.Connecting -> "正在连接设备"
            is BleConnectionPhase.DiscoveringServices -> "正在发现服务"
            is BleConnectionPhase.DiscoveringCharacteristics -> "正在发现特征"
            is BleConnectionPhase.Subscribing -> "正在订阅通知"
            is BleConnectionPhase.Subscribed -> "通知已订阅，等待 CUP 数据"
            is BleConnectionPhase.Receiving -> "正在接收 CUP 通知"
            is BleConnectionPhase.Disconnecting -> "正在安全断开"
            is BleConnectionPhase.Failed -> "连接失败：${phase.message}"
        }
    }

@androidx.compose.runtime.Composable
private fun SessionsPanel(
    state: SessionsUiState,
    onRefresh: () -> Unit,
    onSelect: (SessionListItemUi) -> Unit,
    onClearSelection: () -> Unit,
    onCancelInspection: () -> Unit,
    onRequestExport: (SessionListItemUi) -> Unit,
    onRecover: () -> Unit,
    onCancelAction: () -> Unit,
    onClearAction: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text("已保存会话", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onRefresh, enabled = !state.isLoading) { Text("刷新") }
    }
    Text(
        "会话仅保存在本应用内部；卸载应用会删除未导出的会话。请选择会话后使用“导出 ZIP”保存副本。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    state.error?.let { Text("会话目录：$it", color = MaterialTheme.colorScheme.error) }
    if (state.isLoading) {
        Text("正在读取会话目录…")
    } else if (state.sessions.isEmpty()) {
        Text("暂无已保存会话")
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(state.sessions, key = { it.directory.toString() }) { item ->
                Card(
                    onClick = { onSelect(item) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(item.baseName, style = MaterialTheme.typography.titleSmall)
                        Text(
                            when {
                                item.verifiedComplete -> "complete · verified files"
                                item.recoveryCandidate -> "incomplete · recovery candidate"
                                else -> "incomplete · inspect"
                            },
                            color = if (item.verifiedComplete) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                        Text(
                            "stop=${item.stopReason ?: "—"} · soft=${item.softVersion ?: "—"} · alg=${item.algorithmVersion ?: "—"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (item.findings.isNotEmpty()) {
                            Text("发现：${item.findings.joinToString("；")}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    state.selected?.let { detail ->
        Spacer(Modifier.height(8.dp))
        Text("会话详情：${detail.item.baseName}", style = MaterialTheme.typography.titleSmall)
        detail.expectedFiles.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        when {
            detail.isInspecting -> {
                Text("正在执行只读完整性检查…")
                Button(onClick = onCancelInspection) { Text("取消检查") }
            }
            detail.error != null -> Text("检查失败：${detail.error}", color = MaterialTheme.colorScheme.error)
            detail.inspection != null -> {
                val inspection = detail.inspection
                Text(
                    if (inspection.isVerifiedConsistent) "完整性：verified consistent"
                    else "完整性：存在 findings（不会修改源文件）",
                    color = if (inspection.isVerifiedConsistent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                inspection.findings.forEach { finding ->
                    Text(
                        "${finding.severity}: ${finding.message}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                inspection.replay?.let { replay ->
                    ReplaySummary(replay)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onRequestExport(detail.item) },
                enabled = !state.action.isRunning,
            ) { Text("导出 ZIP") }
            if (detail.item.recoveryCandidate) {
                Button(
                    onClick = onRecover,
                    enabled = !state.action.isRunning,
                ) { Text("创建恢复副本") }
            }
        }
        state.action.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        state.action.error?.let {
            Text("操作失败：$it", color = MaterialTheme.colorScheme.error)
        }
        if (state.action.isRunning) {
            if (state.action.kind == SessionActionKind.EXPORT && state.action.totalBytes > 0) {
                val percent = (state.action.bytesCopied * 100 / state.action.totalBytes).coerceIn(0, 100)
                Text("导出进度：$percent%（${state.action.bytesCopied}/${state.action.totalBytes} bytes）")
            } else {
                Text("正在创建安全恢复副本…")
            }
            Button(onClick = onCancelAction) { Text("取消操作") }
        } else if (state.action.message != null || state.action.error != null) {
            Button(onClick = onClearAction) { Text("清除操作结果") }
        }
        Button(onClick = onClearSelection) { Text("关闭详情") }
        Text("导出只写入用户选择的目标；恢复只创建新目录，不修改源会话。", style = MaterialTheme.typography.bodySmall)
    }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ReplaySummary(replay: CupRawReplayReport) {
    Spacer(Modifier.height(8.dp))
    Text("raw 重放摘要", style = MaterialTheme.typography.titleSmall)
    Text(
        "记录 ${replay.rawRecordCount} · 数据帧 ${replay.decodedFrames} · " +
            "辅助帧 ${replay.auxiliaryFrames} · 接受帧 ${replay.acceptedFrames}",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "接受样本 ${replay.acceptedSamples} · raw ${replay.rawPayloadBytes} bytes · 峰值记录缓冲 ${replay.peakRawRecordBufferBytes} bytes",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "缺失 ${replay.missingFrames} · 重复 ${replay.duplicateFrames} · 乱序 ${replay.outOfOrderFrames} · 丢弃字节 ${replay.discardedBytes}",
        style = MaterialTheme.typography.bodySmall,
    )
    replay.hostDurationSeconds?.let {
        Text("host 帧跨度：${"%.3f".format(java.util.Locale.ROOT, it)} s", style = MaterialTheme.typography.bodySmall)
    }
    if (replay.recentSamples.isNotEmpty()) {
        ReplayWaveformPanel(replay)
    }
}

@androidx.compose.runtime.Composable
private fun ReplayWaveformPanel(replay: CupRawReplayReport) {
    val red = PpgDisplayTransform.rawPeakUpForPlot(
        replay.recentSamples.map { it.sample.red.toDouble() }.toDoubleArray(),
    )
    val ir = PpgDisplayTransform.rawPeakUpForPlot(
        replay.recentSamples.map { it.sample.ir.toDouble() }.toDoubleArray(),
    )
    var viewport by remember(replay.recentSamples.size) {
        mutableStateOf(ReplayWaveformViewport())
    }
    val visibleRange = viewport.visibleRange(red.size)
    fun updateViewport(update: ReplayWaveformViewport.() -> Unit) {
        val next = ReplayWaveformViewport(viewport.zoomScale, viewport.visibleStart)
        next.update()
        viewport = next
    }

    Text("最近样本波形（${replay.recentSamples.size}/800）", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = {
            updateViewport { setZoom(zoomScale * 2.0, red.size) }
        }) { Text("放大") }
        Button(onClick = {
            updateViewport { setZoom(zoomScale / 2.0, red.size) }
        }) { Text("缩小") }
        Button(onClick = { updateViewport { reset() } }) { Text("重置") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = {
            updateViewport { pan(-maxOf(1, visibleSampleCount(red.size) / 2).toDouble(), red.size) }
        }) { Text("← 平移") }
        Button(onClick = {
            updateViewport { pan(maxOf(1, visibleSampleCount(red.size) / 2).toDouble(), red.size) }
        }) { Text("平移 →") }
        Text(
            "视窗 ${visibleRange.first}–${visibleRange.last} / ${red.size}",
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    WaveformPanel(
        label = "REPLAY RED",
        color = Color(0xFFD32F2F),
        values = red.sliceVisible(visibleRange),
    )
    WaveformPanel(
        label = "REPLAY IR",
        color = Color(0xFF1565C0),
        values = ir.sliceVisible(visibleRange),
    )
}

private fun DoubleArray.sliceVisible(range: IntRange): DoubleArray {
    if (range.isEmpty()) return doubleArrayOf()
    return copyOfRange(range.first, range.last + 1)
}

@androidx.compose.runtime.Composable
private fun LiveWaveformAndMetrics(
    waveform: LiveWaveformSnapshot,
    metrics: LiveMetricSnapshot?,
    displayMode: LiveWaveformDisplayMode,
    onDisplayModeChange: (LiveWaveformDisplayMode) -> Unit,
) {
    val displayCausal = if (waveform.displayCausalRed.size == waveform.red.size &&
        waveform.displayCausalIr.size == waveform.ir.size && waveform.displayCausalRed.isNotEmpty()
    ) {
        waveform.displayCausalRed to waveform.displayCausalIr
    } else {
        waveform.causalRed to waveform.causalIr
    }
    val causalAvailable = displayCausal.first.size == waveform.red.size &&
        displayCausal.second.size == waveform.ir.size && displayCausal.first.isNotEmpty()
    val fixedLagAvailable = waveform.fixedLagRed.size == waveform.fixedLagIr.size &&
        waveform.fixedLagRed.isNotEmpty()
    val effectiveMode = when (displayMode) {
        LiveWaveformDisplayMode.FIXED_LAG -> when {
            fixedLagAvailable -> LiveWaveformDisplayMode.FIXED_LAG
            causalAvailable -> LiveWaveformDisplayMode.CAUSAL
            else -> LiveWaveformDisplayMode.RAW
        }
        LiveWaveformDisplayMode.CAUSAL -> if (causalAvailable) {
            LiveWaveformDisplayMode.CAUSAL
        } else {
            LiveWaveformDisplayMode.RAW
        }
        LiveWaveformDisplayMode.RAW -> LiveWaveformDisplayMode.RAW
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (displayMode == LiveWaveformDisplayMode.RAW) {
            FilledTonalButton(
                onClick = { onDisplayModeChange(LiveWaveformDisplayMode.RAW) },
                modifier = Modifier.weight(1f),
            ) { Text("RAW（峰向上）") }
        } else {
            OutlinedButton(
                onClick = { onDisplayModeChange(LiveWaveformDisplayMode.RAW) },
                modifier = Modifier.weight(1f),
            ) { Text("RAW（峰向上）") }
        }
        if (displayMode == LiveWaveformDisplayMode.CAUSAL) {
            FilledTonalButton(
                onClick = { onDisplayModeChange(LiveWaveformDisplayMode.CAUSAL) },
                enabled = causalAvailable,
                modifier = Modifier.weight(1f),
            ) { Text("CAUSAL 0.5–12 Hz") }
        } else {
            OutlinedButton(
                onClick = { onDisplayModeChange(LiveWaveformDisplayMode.CAUSAL) },
                enabled = causalAvailable,
                modifier = Modifier.weight(1f),
            ) { Text("CAUSAL 0.5–12 Hz") }
        }
        if (displayMode == LiveWaveformDisplayMode.FIXED_LAG) {
            FilledTonalButton(
                onClick = { onDisplayModeChange(LiveWaveformDisplayMode.FIXED_LAG) },
                enabled = fixedLagAvailable,
                modifier = Modifier.weight(1f),
            ) { Text("FIXED-LAG 0.5–12 Hz") }
        } else {
            OutlinedButton(
                onClick = { onDisplayModeChange(LiveWaveformDisplayMode.FIXED_LAG) },
                enabled = fixedLagAvailable,
                modifier = Modifier.weight(1f),
            ) { Text("FIXED-LAG 0.5–12 Hz") }
        }
    }
    val red = when (effectiveMode) {
        LiveWaveformDisplayMode.CAUSAL -> displayCausal.first
        LiveWaveformDisplayMode.FIXED_LAG -> waveform.fixedLagRed
        LiveWaveformDisplayMode.RAW -> PpgDisplayTransform.rawPeakUpForPlot(waveform.red)
    }
    val ir = when (effectiveMode) {
        LiveWaveformDisplayMode.CAUSAL -> displayCausal.second
        LiveWaveformDisplayMode.FIXED_LAG -> waveform.fixedLagIr
        LiveWaveformDisplayMode.RAW -> PpgDisplayTransform.rawPeakUpForPlot(waveform.ir)
    }
    val settlingSamples = if (effectiveMode == LiveWaveformDisplayMode.CAUSAL) {
        waveform.settlingSampleCount
    } else {
        0
    }
    val modeDescription = when (effectiveMode) {
        LiveWaveformDisplayMode.CAUSAL -> "因果滤波 0.5–12 Hz（取负 raw 后滤波）"
        LiveWaveformDisplayMode.FIXED_LAG -> "fixed-lag 0.5–12 Hz，约 ${waveform.fixedLagLatencySamples / 100.0} s 延迟"
        LiveWaveformDisplayMode.RAW -> "原始数据，显示取负并去除可视化线性基线趋势"
    }
    WaveformPanel(
        "RED",
        Color(0xFFD32F2F),
        red,
        excludedLeadingSampleCount = settlingSamples,
        semanticsDetail = modeDescription,
    )
    WaveformPanel(
        "IR",
        Color(0xFF1565C0),
        ir,
        excludedLeadingSampleCount = settlingSamples,
        semanticsDetail = modeDescription,
    )
    Text(
        if (effectiveMode == LiveWaveformDisplayMode.CAUSAL) {
            "${waveform.displayCausalProfile ?: "causal-display-0.5-12hz-0.1"} · 因果 0.5–12 Hz · 取负 raw 后滤波 · gap reset · " +
                if (settlingSamples > 0) "浅色区为滤波 settling" else "滤波状态稳定"
        } else if (effectiveMode == LiveWaveformDisplayMode.FIXED_LAG) {
            "${waveform.fixedLagProfile ?: "fixed-lag-fir-0.5-12hz-0.1"} · 源窗口 " +
                "${waveform.fixedLagSourceSampleStartIndex ?: "—"}–${waveform.fixedLagSourceSampleEndIndex ?: "—"} · " +
                "约 ${"%.2f".format(Locale.ROOT, waveform.fixedLagLatencySamples / 100.0)} s 延迟"
        } else {
            "100 Hz accepted RAW · 显示取负并去除可视化线性基线趋势，落盘仍为原始 ADC · 不插值、不重算"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "最近 ${red.size}/${waveform.metricWarmupSampleCount} 个样本 · " +
            "指标 ${minOf(waveform.continuousSampleCount, waveform.metricWarmupSampleCount.toLong())}/" +
            "${waveform.metricWarmupSampleCount} · 源 " +
            "${waveform.sourceSampleStartIndex ?: "—"}–${waveform.sourceSampleEndIndex ?: "—"}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    LiveMetricsPanel(metrics)
}

@androidx.compose.runtime.Composable
private fun WaveformPanel(
    label: String,
    color: Color,
    values: DoubleArray,
    excludedLeadingSampleCount: Int = 0,
    semanticsDetail: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = color,
            fontWeight = FontWeight.Bold,
        )
        Text(
            values.lastOrNull()?.let { "%.0f".format(Locale.ROOT, it) } ?: "—",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val settlingColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
    val verticalRange = remember(values, excludedLeadingSampleCount) {
        LiveWaveformScaleMath.verticalRange(values, excludedLeadingSampleCount)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp)
            .semantics {
                contentDescription = waveformContentDescription(label, values.size, semanticsDetail)
            },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                repeat(3) { index ->
                    val y = size.height * (index + 1) / 4f
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                if (excludedLeadingSampleCount > 0 && values.isNotEmpty()) {
                    drawRect(
                        color = settlingColor,
                        size = androidx.compose.ui.geometry.Size(
                            width = size.width *
                                excludedLeadingSampleCount.coerceAtMost(values.size).toFloat() /
                                values.size.toFloat(),
                            height = size.height,
                        ),
                    )
                }
                val maximumPointCount = maxOf(2, (size.width * 2f).toInt())
                val plot = LiveWaveformPlotMath.plot(values, maximumPointCount)
                if (plot.points.isEmpty()) return@Canvas
                val scale = verticalRange ?: return@Canvas
                val lower = scale.lower
                val upper = scale.upper
                val span = (upper - lower).coerceAtLeast(1e-9)
                fun pointOffset(pointIndex: Int): Offset {
                    val point = plot.points[pointIndex]
                    val x = if (values.size <= 1) {
                        size.width / 2f
                    } else {
                        point.offset.toFloat() / (values.size - 1).toFloat() * size.width
                    }
                    val y = ((upper - point.value) / span * size.height)
                        .toFloat()
                        .coerceIn(0f, size.height)
                    return Offset(x, y)
                }
                if (plot.points.size == 1) {
                    drawCircle(color = color, radius = 2.5.dp.toPx(), center = pointOffset(0))
                    return@Canvas
                }
                val path = Path().apply {
                    val first = pointOffset(0)
                    moveTo(first.x, first.y)
                    for (index in 1 until plot.points.size) {
                        val point = pointOffset(index)
                        lineTo(point.x, point.y)
                    }
                }
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(
                        width = 1.75.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
            if (values.isEmpty()) {
                Text(
                    "等待 CUP 样本",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun waveformContentDescription(
    label: String,
    sampleCount: Int,
    detail: String? = null,
): String = buildString {
    append("$label 波形，$sampleCount 个样本")
    if (!detail.isNullOrBlank()) append("，$detail")
}

@androidx.compose.runtime.Composable
private fun LiveMetricsPanel(metrics: LiveMetricSnapshot?) {
    Text("实时指标", style = MaterialTheme.typography.titleMedium)
    if (metrics == null) {
        Text(
            "等待完整 8 秒窗口；当前没有可用指标。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MetricValue(
            Modifier.weight(1f),
            "心率",
            metrics.heartRateBpm,
            "bpm",
        ) { "%.0f".format(Locale.ROOT, it) }
        MetricValue(
            Modifier.weight(1f),
            "RR（Red/IR）",
            metrics.ratioOfRatios,
            "",
        ) { "%.3f".format(Locale.ROOT, it) }
        MetricValue(
            Modifier.weight(1f),
            "PI（RED AC/DC）",
            metrics.perfusionIndex,
            "%",
        ) { "%.2f".format(Locale.ROOT, it) }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MetricValue(
            Modifier.weight(1f),
            "信号质量 SQI",
            metrics.signalQuality,
            "",
        ) { "%.2f".format(Locale.ROOT, it) }
        UnavailableMetricValue(
            modifier = Modifier.weight(1f),
            label = "血压",
            reason = metrics.bloodPressure.unavailableReason?.message ?: "未提供模型",
        )
    }
    Text(
        "RR 仅为 Red/IR 诊断比值；SQI 为暂定评分。SpO₂ 缺少正式标定，当前不可用。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@androidx.compose.runtime.Composable
private fun MetricValue(
    modifier: Modifier,
    label: String,
    metric: MetricResult<Double>,
    suffix: String,
    formatter: (Double) -> String,
) {
    val value = if (metric.value != null && metric.isValid) {
        "${formatter(metric.value)}${if (suffix.isEmpty()) "" else " $suffix"}"
    } else {
        "—"
    }
    val state = when {
        metric.value == null || !metric.isValid -> metric.unavailableReason?.message ?: "无效"
        metric.isProvisional -> "暂定评分"
        else -> "有效"
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                state,
                style = MaterialTheme.typography.bodySmall,
                color = if (metric.isValid) {
                    if (metric.isProvisional) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                "源 ${metric.sourceSampleIndex ?: "—"} · ${metric.algorithmVersion}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun UnavailableMetricValue(
    modifier: Modifier,
    label: String,
    reason: String,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text("—", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
