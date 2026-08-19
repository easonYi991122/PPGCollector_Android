package com.example.ppgcollector_android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ppgcollector_android.core.ble.BleCoordinatorAction
import com.example.ppgcollector_android.core.ble.BleCoordinatorSnapshot
import com.example.ppgcollector_android.core.ble.BleConnectionPhase
import com.example.ppgcollector_android.core.ble.BlePreviewSnapshot
import com.example.ppgcollector_android.core.protocol.CupStreamProtocolMode
import com.example.ppgcollector_android.core.ble.DiscoveredBleDevice
import com.example.ppgcollector_android.core.signal.LiveWaveformSnapshot
import com.example.ppgcollector_android.core.signal.StreamFreshness
import com.example.ppgcollector_android.data.session.CaptureAnalysisSnapshot
import com.example.ppgcollector_android.data.session.CaptureRecordingSnapshot
import com.example.ppgcollector_android.data.session.CaptureParticipantDraft
import com.example.ppgcollector_android.data.session.CaptureRecordingState
import com.example.ppgcollector_android.data.session.CaptureRecordMode
import com.example.ppgcollector_android.data.session.CaptureRecordModePolicy
import com.example.ppgcollector_android.data.session.bloodPressureValidationError
import com.example.ppgcollector_android.data.session.SubjectProfileStore
import com.example.ppgcollector_android.data.session.SessionNamePrefix
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@Composable
internal fun LiveCaptureScreen(
    snapshot: BleCoordinatorSnapshot,
    captureStatus: CaptureServiceStatusObservation,
    captureWaveformState: StateFlow<LiveWaveformSnapshot>,
    captureAnalysisState: StateFlow<CaptureAnalysisSnapshot>,
    previewState: StateFlow<BlePreviewSnapshot>,
    sessionName: String,
    participantDraft: CaptureParticipantDraft,
    sessionPrefix: SessionNamePrefix,
    recordMode: CaptureRecordMode,
    plannedDurationText: String,
    sessionNameIsValid: Boolean,
    captureGate: CaptureGateUiState,
    onSessionNameChange: (String) -> Unit,
    onSessionPrefixChange: (SessionNamePrefix) -> Unit,
    onRecordModeChange: (CaptureRecordMode) -> Unit,
    onPlannedDurationChange: (String) -> Unit,
    onParticipantDraftChange: (CaptureParticipantDraft) -> Unit,
    onUseSuggestedName: () -> Unit,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onOpenBloodPressure: () -> Unit,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (String) -> BleCoordinatorAction,
    onDisconnect: () -> Unit,
    onSelectNordicProtocol: (CupStreamProtocolMode) -> Unit,
    onOpenSessions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var displayMode by rememberSaveable { mutableStateOf(LiveWaveformDisplayMode.FIXED_LAG) }
    var recordingDensity by rememberSaveable { mutableStateOf(CaptureContentDensity.COMPACT) }
    val recordingActive = captureStatus.recording.state == CaptureRecordingState.RECORDING ||
        captureStatus.recording.state == CaptureRecordingState.STOPPING
    val effectiveDensity = if (recordingActive) recordingDensity else CaptureContentDensity.DETAILED

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            if (recordingActive) {
                RecordingActionBar(
                    recording = captureStatus.recording,
                    onStopCapture = onStopCapture,
                )
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 12.dp,
                end = 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 18.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(if (recordingActive) 10.dp else 16.dp),
        ) {
            item(key = "capture-header") {
                CaptureHeader(
                    compact = recordingActive && effectiveDensity == CaptureContentDensity.COMPACT,
                    onOpenSessions = onOpenSessions,
                )
            }
            item(key = "connection-summary") {
                ConnectionSummaryCard(
                    snapshot = snapshot,
                    captureStatus = captureStatus,
                    recordingActive = recordingActive,
                    density = effectiveDensity,
                    onToggleDensity = {
                        recordingDensity = CaptureUiPolicy.reduce(
                            recordingDensity,
                            CaptureUiEvent.TOGGLE_DENSITY,
                        )
                    },
                    onDisconnect = onDisconnect,
                    onSelectNordicProtocol = onSelectNordicProtocol,
                )
            }
            if (!recordingActive || effectiveDensity == CaptureContentDensity.DETAILED) {
                item(key = "nearby-devices") {
                    NearbyDevicesCard(
                        snapshot = snapshot,
                        onScan = onScan,
                        onStopScan = onStopScan,
                        onConnect = onConnect,
                        onDisconnect = onDisconnect,
                    )
                }
            }
            item(key = "live-waveform") {
                LiveSignalCard(
                    recordingActive = recordingActive,
                    freshness = snapshot.freshness,
                    density = effectiveDensity,
                    displayMode = displayMode,
                    captureWaveformState = captureWaveformState,
                    captureAnalysisState = captureAnalysisState,
                    previewState = previewState,
                    onDisplayModeChange = { displayMode = it },
                )
            }
            if (!recordingActive) {
                item(key = "capture-setup") {
                    CaptureSetupCard(
                        captureStatus = captureStatus,
                        sessionName = sessionName,
                        participantDraft = participantDraft,
                        sessionPrefix = sessionPrefix,
                        recordMode = recordMode,
                        plannedDurationText = plannedDurationText,
                        sessionNameIsValid = sessionNameIsValid,
                        captureGate = captureGate,
                        onSessionNameChange = onSessionNameChange,
                        onSessionPrefixChange = onSessionPrefixChange,
                        onRecordModeChange = onRecordModeChange,
                        onPlannedDurationChange = onPlannedDurationChange,
                        onParticipantDraftChange = onParticipantDraftChange,
                        onUseSuggestedName = onUseSuggestedName,
                        onStartCapture = {
                            recordingDensity = CaptureUiPolicy.reduce(
                                recordingDensity,
                                CaptureUiEvent.RECORDING_REQUESTED,
                            )
                            onStartCapture()
                        },
                    )
                }
            }
            captureStatus.error?.let { error ->
                item(key = "capture-error") {
                    Text("服务：$error", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun CaptureHeader(
    compact: Boolean,
    onOpenSessions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "CUPCollector",
                style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            if (!compact) {
                Text(
                    "PPG 实时采集与完整数据记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedButton(
            onClick = onOpenSessions,
            modifier = Modifier.sizeIn(minHeight = 48.dp),
        ) { Text("已保存会话") }
    }
}

@Composable
private fun ConnectionSummaryCard(
    snapshot: BleCoordinatorSnapshot,
    captureStatus: CaptureServiceStatusObservation,
    recordingActive: Boolean,
    density: CaptureContentDensity,
    onToggleDensity: () -> Unit,
    onDisconnect: () -> Unit,
    onSelectNordicProtocol: (CupStreamProtocolMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(if (recordingActive) 12.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusDot(
                    if (snapshot.phase.isReadyToDisconnect) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.outline,
                )
                Column(Modifier.weight(1f)) {
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
                FreshnessPill(snapshot.freshness)
            }
            if (recordingActive) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = onToggleDensity,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Text(if (density == CaptureContentDensity.COMPACT) "显示详情" else "简洁模式")
                    }
                    if (snapshot.phase.isReadyToDisconnect) {
                        OutlinedButton(
                            onClick = onDisconnect,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) { Text("断开设备") }
                    }
                }
            }
            if (snapshot.protocolProbePending) {
                Text(
                    if (snapshot.protocolProbeTimedOut) "无法识别数据协议" else "正在识别数据协议…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onSelectNordicProtocol(CupStreamProtocolMode.ADS1292R_120) },
                        modifier = Modifier.weight(1f),
                    ) { Text("腕部 ECG (120)") }
                    OutlinedButton(
                        onClick = { onSelectNordicProtocol(CupStreamProtocolMode.SENSOR_PACKET_168) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Nordic PPG (168)") }
                }
            }
            if (!recordingActive || density == CaptureContentDensity.DETAILED) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "权限 ${snapshot.permission.gateState} · 录制 ${captureStatus.recording.state} · " +
                        "服务 ${captureStatus.binding}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            snapshot.lastError?.let { Text("蓝牙：$it", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun NearbyDevicesCard(
    snapshot: BleCoordinatorSnapshot,
    onScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (String) -> BleCoordinatorAction,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("附近设备", style = MaterialTheme.typography.titleMedium)
                if (snapshot.isScanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onScan, enabled = !snapshot.isScanning, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("扫描 CUP")
                }
                OutlinedButton(
                    onClick = onStopScan,
                    enabled = snapshot.isScanning,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("停止扫描") }
            }
            if (snapshot.discoveredDevices.isEmpty()) {
                Text(
                    if (snapshot.isScanning) "正在查找名称以 CUP 开头的设备…" else "暂无 CUP 设备。",
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
        }
    }
}

@Composable
private fun LiveSignalCard(
    recordingActive: Boolean,
    freshness: StreamFreshness,
    density: CaptureContentDensity,
    displayMode: LiveWaveformDisplayMode,
    captureWaveformState: StateFlow<LiveWaveformSnapshot>,
    captureAnalysisState: StateFlow<CaptureAnalysisSnapshot>,
    previewState: StateFlow<BlePreviewSnapshot>,
    onDisplayModeChange: (LiveWaveformDisplayMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val captureWaveform by captureWaveformState.collectAsStateWithLifecycle()
    val captureAnalysis by captureAnalysisState.collectAsStateWithLifecycle()
    val preview by previewState.collectAsStateWithLifecycle()
    val waveform = if (recordingActive && captureWaveform.acceptedSampleCount > 0) {
        captureWaveform
    } else {
        preview.waveform
    }
    val metrics = if (recordingActive) captureAnalysis.lastResult?.snapshot else preview.lastAnalysis?.snapshot
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(if (density == CaptureContentDensity.COMPACT) 12.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (density == CaptureContentDensity.DETAILED) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("实时信号波形", style = MaterialTheme.typography.titleMedium)
                    FreshnessPill(freshness)
                }
            }
            LiveWaveformAndMetrics(
                waveform = waveform,
                metrics = metrics,
                displayMode = displayMode,
                density = density,
                onDisplayModeChange = onDisplayModeChange,
            )
        }
    }
}

@Composable
private fun RecordingActionBar(
    recording: CaptureRecordingSnapshot,
    onStopCapture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onStopCapture,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                val label = if (recording.recordMode == CaptureRecordMode.TIMED) {
                    val remaining = recording.remainingDurationSeconds ?: 0
                    "停止（剩 ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}）"
                } else {
                    "手动停止"
                }
                Text(label)
            }
        }
    }
}

@Composable
private fun CaptureSetupCard(
    captureStatus: CaptureServiceStatusObservation,
    sessionName: String,
    participantDraft: CaptureParticipantDraft,
    sessionPrefix: SessionNamePrefix,
    recordMode: CaptureRecordMode,
    plannedDurationText: String,
    sessionNameIsValid: Boolean,
    captureGate: CaptureGateUiState,
    onSessionNameChange: (String) -> Unit,
    onSessionPrefixChange: (SessionNamePrefix) -> Unit,
    onRecordModeChange: (CaptureRecordMode) -> Unit,
    onPlannedDurationChange: (String) -> Unit,
    onParticipantDraftChange: (CaptureParticipantDraft) -> Unit,
    onUseSuggestedName: () -> Unit,
    onStartCapture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val sexFocus = remember { FocusRequester() }
    val ageFocus = remember { FocusRequester() }
    val heightFocus = remember { FocusRequester() }
    val weightFocus = remember { FocusRequester() }
    val notesFocus = remember { FocusRequester() }
    var smokingMenuExpanded by remember { mutableStateOf(false) }
    var drinkingMenuExpanded by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("数据记录", style = MaterialTheme.typography.titleMedium)
                Text(
                    captureStatus.recording.state.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("设备类型", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SessionNamePrefix.entries.forEach { prefix ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.semantics {
                            contentDescription = prefix.displayName
                        },
                    ) {
                        RadioButton(
                            selected = sessionPrefix == prefix,
                            onClick = { onSessionPrefixChange(prefix) },
                            enabled = !captureStatus.recording.state.isRecordingState(),
                        )
                        Text(prefix.displayName)
                    }
                }
            }
            Text("录制模式", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CaptureRecordMode.entries.forEach { mode ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = recordMode == mode,
                            onClick = { onRecordModeChange(mode) },
                        )
                        Text(if (mode == CaptureRecordMode.TIMED) "定时录制" else "手动录制")
                    }
                }
            }
            if (recordMode == CaptureRecordMode.TIMED) {
                val durationError = CaptureRecordModePolicy.durationError(
                    recordMode,
                    plannedDurationText,
                )
                BringIntoViewTextField(
                    value = plannedDurationText,
                    onValueChange = onPlannedDurationChange,
                    label = "录制时长（秒）",
                    supportingText = durationError ?: "范围 10–3600 秒，默认 60 秒",
                    isError = durationError != null,
                    enabled = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                )
            }
            BringIntoViewTextField(
                value = sessionName,
                onValueChange = onSessionNameChange,
                label = "录制名称",
                supportingText = "仅支持字母、数字、下划线和短横线",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { sexFocus.requestFocus() }),
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
                TextButton(onClick = onUseSuggestedName, modifier = Modifier.sizeIn(minHeight = 48.dp)) {
                    Text("使用建议名")
                }
            }
            Text(
                if (sessionNameIsValid) "被试资料（可在停止前继续修改）" else "名称合法且不重复后可填写被试资料",
                style = MaterialTheme.typography.titleSmall,
            )
            Text("性别（必选）", style = MaterialTheme.typography.labelLarge)
            Row {
                CaptureParticipantDraft.genderOptions.forEach { gender ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = participantDraft.sex == gender,
                            onClick = {
                                onParticipantDraftChange(participantDraft.copy(sex = gender))
                            },
                            enabled = sessionNameIsValid,
                        )
                        Text(gender)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BringIntoViewTextField(
                    value = participantDraft.ageYears,
                    onValueChange = {
                        onParticipantDraftChange(participantDraft.copy(ageYears = it.filter(Char::isDigit)))
                    },
                    label = "年龄",
                    enabled = sessionNameIsValid,
                    focusRequester = ageFocus,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { heightFocus.requestFocus() }),
                    modifier = Modifier.weight(1f),
                )
                BringIntoViewTextField(
                    value = participantDraft.heightCm,
                    onValueChange = {
                        onParticipantDraftChange(participantDraft.copy(heightCm = decimalInput(it)))
                    },
                    label = "身高 cm",
                    enabled = sessionNameIsValid,
                    focusRequester = heightFocus,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { weightFocus.requestFocus() }),
                    modifier = Modifier.weight(1f),
                )
                BringIntoViewTextField(
                    value = participantDraft.weightKg,
                    onValueChange = {
                        onParticipantDraftChange(participantDraft.copy(weightKg = decimalInput(it)))
                    },
                    label = "体重 kg",
                    enabled = sessionNameIsValid,
                    focusRequester = weightFocus,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { notesFocus.requestFocus() }),
                    modifier = Modifier.weight(1f),
                )
            }
            FrequencyMenu(
                label = "吸烟频率（可选）",
                value = participantDraft.smokingFreq,
                options = CaptureParticipantDraft.smokingOptions,
                expanded = smokingMenuExpanded,
                onExpandedChange = { smokingMenuExpanded = it },
                enabled = sessionNameIsValid,
                onSelected = {
                    onParticipantDraftChange(participantDraft.copy(smokingFreq = it))
                },
            )
            FrequencyMenu(
                label = "饮酒频率（可选）",
                value = participantDraft.drinkingFreq,
                options = CaptureParticipantDraft.drinkingOptions,
                expanded = drinkingMenuExpanded,
                onExpandedChange = { drinkingMenuExpanded = it },
                enabled = sessionNameIsValid,
                onSelected = {
                    onParticipantDraftChange(participantDraft.copy(drinkingFreq = it))
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BringIntoViewTextField(
                    value = participantDraft.systolicBp,
                    onValueChange = {
                        onParticipantDraftChange(participantDraft.copy(systolicBp = it.filter(Char::isDigit)))
                    },
                    label = "收缩压 mmHg（可选）",
                    enabled = sessionNameIsValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                BringIntoViewTextField(
                    value = participantDraft.diastolicBp,
                    onValueChange = {
                        onParticipantDraftChange(participantDraft.copy(diastolicBp = it.filter(Char::isDigit)))
                    },
                    label = "舒张压 mmHg（可选）",
                    enabled = sessionNameIsValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            bloodPressureValidationError(
                participantDraft.systolicBp,
                participantDraft.diastolicBp,
            )?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            BringIntoViewTextField(
                value = participantDraft.additionalFields["notes"].orEmpty(),
                onValueChange = { value ->
                    val boundedValue = value.take(SubjectProfileStore.maximumFieldValueLength)
                    val fields = participantDraft.additionalFields.toMutableMap().apply {
                        if (boundedValue.isBlank()) remove("notes") else put("notes", boundedValue)
                    }
                    onParticipantDraftChange(participantDraft.copy(additionalFields = fields))
                },
                label = "其它信息（可选）",
                enabled = sessionNameIsValid,
                focusRequester = notesFocus,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            )
            Button(
                onClick = onStartCapture,
                enabled = captureGate.canStart,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text("开始录制") }
            if (captureGate.canStart) {
                Text("✓ 可以开始录制", color = MaterialTheme.colorScheme.tertiary)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("开始录制前还需满足：")
                    captureGate.messages.forEach { Text("• $it") }
                }
            }
        }
    }
}

@Composable
private fun FrequencyMenu(
    label: String,
    value: String,
    options: List<String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    Box {
        OutlinedButton(
            onClick = { onExpandedChange(true) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("$label：${value.ifBlank { "请选择" }}")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.ifBlank { "请选择" }) },
                    onClick = {
                        onSelected(option)
                        onExpandedChange(false)
                    },
                )
            }
        }
    }
}

private fun CaptureRecordingState.isRecordingState(): Boolean =
    this == CaptureRecordingState.RECORDING || this == CaptureRecordingState.STOPPING

@Composable
private fun BringIntoViewTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    focusRequester: FocusRequester? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val supportingContent: (@Composable () -> Unit)? = supportingText?.let { message ->
        { Text(message) }
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = supportingContent,
        enabled = enabled,
        isError = isError,
        singleLine = true,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .bringIntoViewRequester(bringIntoViewRequester)
            .onFocusChanged { state ->
                if (state.isFocused) scope.launch { bringIntoViewRequester.bringIntoView() }
            },
    )
}

internal fun decimalInput(value: String): String {
    var decimalSeen = false
    return buildString {
        value.forEach { character ->
            when {
                character.isDigit() -> append(character)
                character == '.' && !decimalSeen -> {
                    decimalSeen = true
                    append(character)
                }
            }
        }
    }
}

@Composable
internal fun CupDeviceList(
    devices: List<DiscoveredBleDevice>,
    phase: BleConnectionPhase,
    onConnect: (String) -> BleCoordinatorAction,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
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
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
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
                            Text(device.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("连接中…", style = MaterialTheme.typography.labelMedium)
                        }
                        isConnected -> Button(
                            onClick = onDisconnect,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("断开") }
                        else -> Button(
                            onClick = { onConnect(device.id) },
                            enabled = device.isConnectable && !anotherDeviceIsActive,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("连接") }
                    }
                }
                if (index != devices.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.size(10.dp), shape = RoundedCornerShape(50), color = color) {}
}

@Composable
private fun FreshnessPill(freshness: StreamFreshness, modifier: Modifier = Modifier) {
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
        modifier = modifier.semantics { contentDescription = "数据状态：$label" },
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.12f),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
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
