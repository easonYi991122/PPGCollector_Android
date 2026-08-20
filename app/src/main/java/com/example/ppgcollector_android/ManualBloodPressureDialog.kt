package com.example.ppgcollector_android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.ppgcollector_android.data.session.CaptureReferenceTimestamp
import com.example.ppgcollector_android.data.session.ManualBloodPressureEvent
import com.example.ppgcollector_android.data.session.bloodPressureValidationError
import java.time.Instant

@Composable
internal fun ManualBloodPressureDialog(
    reference: CaptureReferenceTimestamp,
    onDismiss: () -> Unit,
    onSave: (ManualBloodPressureEvent) -> Boolean,
    modifier: Modifier = Modifier,
) {
    var systolic by rememberSaveable { mutableStateOf("") }
    var diastolic by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val diastolicFocus = androidx.compose.runtime.remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismiss,
        title = { Text("记录血压") },
        text = {
            Column(
                modifier = Modifier.imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("时间锚点：PPG sample ${reference.sourceSampleIndex} · ${"%.2f".format(reference.sourceTimeSeconds)} s")
                OutlinedTextField(
                    value = systolic,
                    onValueChange = {
                        systolic = it.filter(Char::isDigit)
                        error = null
                    },
                    label = { Text("收缩压 mmHg") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(onNext = { diastolicFocus.requestFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = diastolic,
                    onValueChange = {
                        diastolic = it.filter(Char::isDigit)
                        error = null
                    },
                    label = { Text("舒张压 mmHg") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(diastolicFocus),
                )
                val inlineError = bloodPressureValidationError(systolic, diastolic)
                (error ?: inlineError)?.let {
                    Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            val sbp = systolic.toIntOrNull()
            val dbp = diastolic.toIntOrNull()
            val validationError = bloodPressureValidationError(systolic, diastolic)
            TextButton(
                enabled = sbp != null && dbp != null && validationError == null,
                onClick = {
                    if (sbp == null || dbp == null || validationError != null) {
                        error = validationError ?: "请输入正整数血压"
                    } else {
                        val accepted = onSave(
                            ManualBloodPressureEvent(
                                reference = reference,
                                savedUtc = Instant.now(),
                                systolicMmHg = sbp,
                                diastolicMmHg = dbp,
                            ),
                        )
                        if (accepted) onDismiss() else error = "当前录制已结束，未保存"
                    }
                },
            ) { Text("存储") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
