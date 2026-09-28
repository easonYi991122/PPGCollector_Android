package com.example.ppgcollector_android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.ppgcollector_android.data.session.CaptureParticipantDraft
import com.example.ppgcollector_android.data.session.CaptureRecordingState
import com.example.ppgcollector_android.data.session.CaptureRecordMode
import com.example.ppgcollector_android.data.session.CaptureRecordModePolicy
import com.example.ppgcollector_android.data.session.SessionNamePrefix
import com.example.ppgcollector_android.data.session.SubjectProfileStore
import com.example.ppgcollector_android.data.session.bloodPressureValidationError
import kotlinx.coroutines.launch

@Composable
internal fun CaptureSetupModeCard(
    captureStatus: CaptureServiceStatusObservation,
    sessionPrefix: SessionNamePrefix,
    recordMode: CaptureRecordMode,
    plannedDurationText: String,
    onSessionPrefixChange: (SessionNamePrefix) -> Unit,
    onRecordModeChange: (CaptureRecordMode) -> Unit,
    onPlannedDurationChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
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
                        modifier = Modifier.semantics { contentDescription = prefix.displayName },
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
                val durationError = CaptureRecordModePolicy.durationError(recordMode, plannedDurationText)
                BringIntoViewTextField(
                    value = plannedDurationText,
                    onValueChange = onPlannedDurationChange,
                    label = "录制时长（秒）",
                    supportingText = durationError ?: "范围 10–3600 秒，默认 60 秒",
                    isError = durationError != null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                )
            }
        }
    }
}

@Composable
internal fun CaptureSessionNameCard(
    sessionName: String,
    sessionNameIsValid: Boolean,
    onSessionNameChange: (String) -> Unit,
    onUseSuggestedName: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BringIntoViewTextField(
                value = sessionName,
                onValueChange = onSessionNameChange,
                label = "录制名称",
                supportingText = if (sessionNameIsValid) {
                    "仅支持字母、数字、下划线和短横线"
                } else {
                    "名称语法无效或已存在"
                },
                isError = !sessionNameIsValid,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
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
        }
    }
}

@Composable
internal fun CaptureParticipantCard(
    participantDraft: CaptureParticipantDraft,
    sessionNameIsValid: Boolean,
    onParticipantDraftChange: (CaptureParticipantDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ageFocus = remember { FocusRequester() }
    val heightFocus = remember { FocusRequester() }
    val weightFocus = remember { FocusRequester() }
    var smokingMenuExpanded by remember { mutableStateOf(false) }
    var drinkingMenuExpanded by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                            onClick = { onParticipantDraftChange(participantDraft.copy(sex = gender)) },
                            enabled = sessionNameIsValid,
                        )
                        Text(gender)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BringIntoViewTextField(
                    value = participantDraft.ageYears,
                    onValueChange = { onParticipantDraftChange(participantDraft.copy(ageYears = it.filter(Char::isDigit))) },
                    label = "年龄",
                    enabled = sessionNameIsValid,
                    focusRequester = ageFocus,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { heightFocus.requestFocus() }),
                    modifier = Modifier.weight(1f),
                )
                BringIntoViewTextField(
                    value = participantDraft.heightCm,
                    onValueChange = { onParticipantDraftChange(participantDraft.copy(heightCm = decimalInput(it))) },
                    label = "身高 cm",
                    enabled = sessionNameIsValid,
                    focusRequester = heightFocus,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { weightFocus.requestFocus() }),
                    modifier = Modifier.weight(1f),
                )
                BringIntoViewTextField(
                    value = participantDraft.weightKg,
                    onValueChange = { onParticipantDraftChange(participantDraft.copy(weightKg = decimalInput(it))) },
                    label = "体重 kg",
                    enabled = sessionNameIsValid,
                    focusRequester = weightFocus,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
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
                onSelected = { onParticipantDraftChange(participantDraft.copy(smokingFreq = it)) },
            )
            FrequencyMenu(
                label = "饮酒频率（可选）",
                value = participantDraft.drinkingFreq,
                options = CaptureParticipantDraft.drinkingOptions,
                expanded = drinkingMenuExpanded,
                onExpandedChange = { drinkingMenuExpanded = it },
                enabled = sessionNameIsValid,
                onSelected = { onParticipantDraftChange(participantDraft.copy(drinkingFreq = it)) },
            )
        }
    }
}

@Composable
internal fun CaptureReferenceCard(
    participantDraft: CaptureParticipantDraft,
    sessionNameIsValid: Boolean,
    onParticipantDraftChange: (CaptureParticipantDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val notesFocus = remember { FocusRequester() }
    val pressureError = bloodPressureValidationError(participantDraft.systolicBp, participantDraft.diastolicBp)
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("参考血压与备注（均为人工输入）", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BringIntoViewTextField(
                    value = participantDraft.systolicBp,
                    onValueChange = { onParticipantDraftChange(participantDraft.copy(systolicBp = it.filter(Char::isDigit))) },
                    label = "收缩压 mmHg（可选）",
                    enabled = sessionNameIsValid,
                    isError = pressureError != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                BringIntoViewTextField(
                    value = participantDraft.diastolicBp,
                    onValueChange = { onParticipantDraftChange(participantDraft.copy(diastolicBp = it.filter(Char::isDigit))) },
                    label = "舒张压 mmHg（可选）",
                    enabled = sessionNameIsValid,
                    isError = pressureError != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            pressureError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
        }
    }
}

@Composable
internal fun CaptureStartCard(
    captureGate: CaptureGateUiState,
    onStartCapture: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onStartCapture,
                enabled = captureGate.canStart,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text("开始录制") }
            if (CaptureUiPolicy.needsNotificationPermission(captureGate)) {
                OutlinedButton(onClick = onRequestNotificationPermission, modifier = Modifier.fillMaxWidth()) {
                    Text("授予通知权限")
                }
                OutlinedButton(onClick = onOpenNotificationSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("打开通知设置")
                }
            }
            if (captureGate.canStart) {
                Text("✓ 可以开始录制", color = MaterialTheme.colorScheme.tertiary)
            } else {
                Text("开始录制前还需满足：")
                captureGate.messages.forEach { Text("• $it") }
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
    this == CaptureRecordingState.STARTING || this == CaptureRecordingState.RECORDING || this == CaptureRecordingState.STOPPING

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
