package com.example.ppgcollector_android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import com.example.ppgcollector_android.data.session.CaptureReferenceTimestamp
import com.example.ppgcollector_android.data.session.ManualBloodPressureEvent
import java.time.Instant

@Composable
internal fun ManualBloodPressureDialog(
    reference: CaptureReferenceTimestamp,
    onDismiss: () -> Unit,
    onSave: (ManualBloodPressureEvent) -> Boolean,
) {
    var systolic by rememberSaveable { mutableStateOf("") }
    var diastolic by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var unusualConfirmed by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记录血压") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("时间锚点：PPG sample ${reference.sourceSampleIndex} · ${"%.2f".format(reference.sourceTimeSeconds)} s")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = systolic,
                        onValueChange = {
                            systolic = it.filter(Char::isDigit)
                            unusualConfirmed = false
                            error = null
                        },
                        label = { Text("收缩压 mmHg") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = diastolic,
                        onValueChange = {
                            diastolic = it.filter(Char::isDigit)
                            unusualConfirmed = false
                            error = null
                        },
                        label = { Text("舒张压 mmHg") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                error?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val sbp = systolic.toIntOrNull()
                val dbp = diastolic.toIntOrNull()
                when {
                    sbp == null || dbp == null || sbp <= 0 || dbp <= 0 ->
                        error = "请输入正整数血压"
                    sbp !in 20..300 || dbp !in 10..250 ->
                        error = "数值超出技术范围，请检查"
                    sbp <= dbp && !unusualConfirmed -> {
                        unusualConfirmed = true
                        error = "收缩压通常应高于舒张压；再次点击“存储”确认原始输入"
                    }
                    else -> {
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
                }
            }) { Text("存储") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
