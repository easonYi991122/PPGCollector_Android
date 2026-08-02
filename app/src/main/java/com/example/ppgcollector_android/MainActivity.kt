package com.example.ppgcollector_android

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.example.ppgcollector_android.core.ble.BleCoordinatorAction
import com.example.ppgcollector_android.core.ble.BleCoordinatorSnapshot
import com.example.ppgcollector_android.core.ble.BleConnectionPhase
import com.example.ppgcollector_android.core.ble.BlePreviewSnapshot
import com.example.ppgcollector_android.core.ble.DiscoveredBleDevice
import com.example.ppgcollector_android.core.signal.LiveMetricSnapshot
import com.example.ppgcollector_android.core.signal.LiveWaveformPlotMath
import com.example.ppgcollector_android.core.signal.LiveWaveformSnapshot
import com.example.ppgcollector_android.core.signal.MetricResult
import com.example.ppgcollector_android.core.signal.StreamFreshness
import com.example.ppgcollector_android.data.session.CaptureRecordingState
import com.example.ppgcollector_android.data.session.CaptureNotificationPermissionPolicy
import com.example.ppgcollector_android.data.session.CupRawReplayReport
import com.example.ppgcollector_android.data.session.ReplayWaveformViewport
import com.example.ppgcollector_android.ui.theme.PPGCollector_AndroidTheme
import java.util.Locale

private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"

class MainActivity : ComponentActivity() {
    private val captureViewModel: CaptureViewModel by viewModels()
    private val sessionsViewModel: SessionsViewModel by viewModels()

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
        } else {
            sessionsViewModel.exportSelectedTo(destination)
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
                val captureGate by captureViewModel.captureGate.collectAsStateWithLifecycle()
                val preview by captureViewModel.previewState.collectAsStateWithLifecycle()
                val sessions by sessionsViewModel.state.collectAsStateWithLifecycle()
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background,
                ) { innerPadding ->
                    BleHome(
                        snapshot = snapshot,
                        capture = capture,
                        preview = preview,
                        sessions = sessions,
                        onRefreshSessions = sessionsViewModel::refresh,
                        onSelectSession = sessionsViewModel::select,
                        onClearSessionSelection = sessionsViewModel::clearSelection,
                        onCancelSessionInspection = sessionsViewModel::cancelInspection,
                        onRequestExport = ::requestSessionExport,
                        onRecoverSession = sessionsViewModel::recoverSelected,
                        onCancelSessionAction = sessionsViewModel::cancelAction,
                        onClearSessionAction = sessionsViewModel::clearAction,
                        sessionName = sessionName,
                        captureGate = captureGate,
                        onSessionNameChange = captureViewModel::setSessionName,
                        onStartCapture = ::requestCaptureStart,
                        onStopCapture = captureViewModel::stopRecording,
                        onScan = ::requestScan,
                        onStopScan = bleCoordinator::stopScanning,
                        onConnect = bleCoordinator::connect,
                        onDisconnect = bleCoordinator::disconnect,
                        modifier = Modifier.padding(innerPadding),
                    )
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
        exportLauncher.launch("${item.baseName}.zip")
    }
}

@androidx.compose.runtime.Composable
private fun BleHome(
    snapshot: BleCoordinatorSnapshot,
    capture: CaptureServiceObservation,
    preview: BlePreviewSnapshot,
    sessions: SessionsUiState,
    onRefreshSessions: () -> Unit,
    onSelectSession: (SessionListItemUi) -> Unit,
    onClearSessionSelection: () -> Unit,
    onCancelSessionInspection: () -> Unit,
    onRequestExport: (SessionListItemUi) -> Unit,
    onRecoverSession: () -> Unit,
    onCancelSessionAction: () -> Unit,
    onClearSessionAction: () -> Unit,
    sessionName: String,
    captureGate: CaptureGateUiState,
    onSessionNameChange: (String) -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (String) -> BleCoordinatorAction,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recordingActive = capture.recording.state == CaptureRecordingState.RECORDING ||
        capture.recording.state == CaptureRecordingState.STOPPING
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
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
                    FreshnessPill(snapshot.freshness)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "权限 ${snapshot.permission.gateState} · 录制 ${capture.recording.state} · 服务 ${capture.binding}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

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
                    Text("实时原始波形", style = MaterialTheme.typography.titleMedium)
                    FreshnessPill(snapshot.freshness)
                }
                LiveWaveformAndMetrics(waveform = waveform, metrics = metrics)
            }
        }

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
                        supportingText = { Text("仅支持字母、数字、下划线和短横线") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
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
                    Button(
                        onClick = onStopCapture,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Text("停止并保存")
                    }
                }
                capture.error?.let { Text("服务：$it", color = MaterialTheme.colorScheme.error) }
            }
        }

        SessionsPanel(
            state = sessions,
            onRefresh = onRefreshSessions,
            onSelect = onSelectSession,
            onClearSelection = onClearSessionSelection,
            onCancelInspection = onCancelSessionInspection,
            onRequestExport = onRequestExport,
            onRecover = onRecoverSession,
            onCancelAction = onCancelSessionAction,
            onClearAction = onClearSessionAction,
        )
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
        "记录 ${replay.rawRecordCount} · 解码帧 ${replay.decodedFrames} · 接受帧 ${replay.acceptedFrames}",
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
    val red = replay.recentSamples.map { it.sample.red.toDouble() }.toDoubleArray()
    val ir = replay.recentSamples.map { it.sample.ir.toDouble() }.toDoubleArray()
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
) {
    WaveformPanel("RED", Color(0xFFD32F2F), waveform.red)
    WaveformPanel("IR", Color(0xFF1565C0), waveform.ir)
    Text(
        "最近 ${waveform.red.size}/800 个样本 · 通道独立纵向缩放 · " +
            "源 ${waveform.sourceSampleStartIndex ?: "—"}–${waveform.sourceSampleEndIndex ?: "—"}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    LiveMetricsPanel(metrics)
}

@androidx.compose.runtime.Composable
private fun WaveformPanel(label: String, color: Color, values: DoubleArray) {
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
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp)
            .semantics {
                contentDescription = waveformContentDescription(label, values.size)
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
                val maximumPointCount = maxOf(2, (size.width * 2f).toInt())
                val plot = LiveWaveformPlotMath.plot(values, maximumPointCount)
                if (plot.points.isEmpty()) return@Canvas
                val range = plot.maximum - plot.minimum
                val verticalPadding = if (range.isFinite() && range > 0.0) {
                    range * 0.08
                } else {
                    maxOf(kotlin.math.abs(plot.maximum) * 0.08, 1.0)
                }
                val lower = plot.minimum - verticalPadding
                val upper = plot.maximum + verticalPadding
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

internal fun waveformContentDescription(label: String, sampleCount: Int): String =
    "$label 波形，$sampleCount 个样本"

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
