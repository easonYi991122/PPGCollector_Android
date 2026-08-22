package com.example.ppgcollector_android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ppgcollector_android.core.signal.LiveWaveformPlotMath
import com.example.ppgcollector_android.data.session.CaptureInspectionSeverity
import com.example.ppgcollector_android.data.session.CaptureSessionAnalysisArtifact
import com.example.ppgcollector_android.data.session.CupRawReplayReport
import java.nio.file.Path as NioPath
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

private val uiDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault())

@Composable
internal fun FlatSessionsContent(
    state: SessionsUiState,
    onSelect: (SessionListItemUi) -> Unit,
    onToggleSelection: (NioPath) -> Unit,
    onCancelAnalysis: (NioPath) -> Unit,
    onOpenCompare: () -> Unit,
    modifier: Modifier = Modifier,
) {
        val running = remember(state.analysisTasks) {
            state.analysisTasks.filterValues { it.status == SessionAnalysisTaskStatus.RUNNING }
        }
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "flat-notice") {
                NoticeCard(
                    "会话保存在本应用内部，卸载应用会删除未导出的数据。" +
                        "进入详情可导出 ZIP；检查与分析均不会改写 raw、CSV 或 metadata。",
                )
            }
            if (running.isNotEmpty()) {
                item(key = "flat-analysis-title") { SectionTitle("离线分析任务") }
                items(running.entries.toList(), key = { it.key.toString() }) { (directory, task) ->
                    SectionCard(title = task.sessionBaseName) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(
                                progress = { task.progress?.fractionCompleted?.toFloat() ?: 0f },
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 3.dp,
                            )
                            Column(Modifier.weight(1f)) {
                                Text(task.progress?.stage?.title ?: "正在准备 raw 重放")
                                Text(
                                    task.progress?.let {
                                        "${(it.fractionCompleted * 100).toInt()}% · " +
                                            "${it.processedSampleCount} 样本 · ${it.completedWindowCount}/${it.totalWindowCount} 窗口"
                                    } ?: "等待分析进度",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedButton(onClick = { onCancelAnalysis(directory) }) {
                                Text("取消")
                            }
                        }
                    }
                }
            }
            if (state.artifactsBySession.count { it.value.isNotEmpty() } >= 2) {
                item(key = "flat-compare") {
                    SectionCard(title = "会话对比") {
                        Text(
                            "选择两个已生成离线结果的会话，对照稳定段指标、平均周期与统一归一化曲线。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FilledTonalButton(onClick = onOpenCompare, modifier = Modifier.fillMaxWidth()) {
                            Text("打开双会话对比")
                        }
                    }
                }
            }
            state.error?.let { message ->
                item(key = "flat-error") { StatusMessage(message, isError = true) }
            }
            item(key = "flat-session-title") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionTitle("本机会话（${state.sessions.size}）")
                    if (state.isLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            if (!state.isLoading && state.sessions.isEmpty()) {
                item(key = "flat-empty") {
                    EmptyState(
                        title = "尚无本地会话",
                        detail = "完成一次录制后，会话将在这里独立展示。",
                    )
                }
            }
            items(state.sessions, key = { it.directory.toString() }) { item ->
                SessionSummaryCard(
                    item = item,
                    analysisCount = state.artifactsBySession[item.directory]?.size ?: 0,
                    selectionMode = state.sessionSelectionMode,
                    selected = item.directory in state.archiveSelectedDirectories,
                    onClick = { if (state.sessionSelectionMode) onToggleSelection(item.directory) else onSelect(item) },
                    onToggleSelection = { onToggleSelection(item.directory) },
                )
            }
            item(key = "flat-bottom-space") { Spacer(Modifier.height(8.dp)) }
        }
}

@Composable
private fun SessionSummaryCard(
    item: SessionListItemUi,
    analysisCount: Int,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().semantics {
            if (selectionMode) {
                stateDescription = if (selected) "已选择" else "未选择"
            }
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                if (selectionMode) {
                    androidx.compose.material3.Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggleSelection() },
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(item.baseName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        formatInstant(item.modifiedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SessionStatusPill(item)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                SmallStat("样本", item.sampleCount?.toString() ?: "—")
                SmallStat("文件", formatBytes(item.totalBytes))
                SmallStat("分析", analysisCount.toString())
            }
            Text(
                "${item.deviceName ?: "未知设备"} · stop ${item.stopReason ?: "—"} · ${item.algorithmVersion ?: "未知算法"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (item.findings.isNotEmpty()) {
                Text(
                    item.findings.joinToString("；"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SessionStatusPill(item: SessionListItemUi) {
    val (label, color) = when {
        item.verifiedComplete -> "完整" to MaterialTheme.colorScheme.tertiary
        item.recoveryCandidate -> "待恢复" to MaterialTheme.colorScheme.error
        else -> "待检查" to MaterialTheme.colorScheme.primary
    }
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.12f)) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

@Composable
internal fun SavedSessionDetailScreen(
    state: SessionsUiState,
    onBack: () -> Unit,
    onCancelInspection: () -> Unit,
    onRequestExport: (SessionListItemUi) -> Unit,
    onRecover: () -> Unit,
    onCancelAction: () -> Unit,
    onClearAction: () -> Unit,
    onStartAnalysis: (SessionListItemUi) -> Unit,
    onCancelAnalysis: (NioPath) -> Unit,
    onOpenFullscreenWorkbench: () -> Unit,
    onUpdateBloodPressure: (Int?, Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val detail = state.selected
    if (detail == null) {
        Column(modifier.fillMaxSize()) {
            PageHeader("会话详情", "当前会话已不可用", onBack)
            EmptyState("无法打开会话", "返回列表并刷新后重试。")
        }
        return
    }
    val item = detail.item
    val artifacts = state.artifactsBySession[item.directory].orEmpty()
    val task = state.analysisTasks[item.directory]
    var expandedSections by remember(item.directory) {
        mutableStateOf(SessionDetailUiPolicy.defaultExpandedSections)
    }
    fun isExpanded(section: SessionDetailSection): Boolean = section in expandedSections
    fun toggle(section: SessionDetailSection) {
        expandedSections = SessionDetailUiPolicy.toggle(expandedSections, section)
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PageHeader(
                title = item.baseName,
                subtitle = "会话详情 · 只读检查与重放",
                onBack = onBack,
            )
        }
        item {
            CollapsibleSectionCard(
                title = "会话概览",
                summary = "${item.sampleCount ?: 0} samples · ${item.participant?.subjectId ?: "未归档"} · BP ${item.bloodPressureCount} 组",
                expanded = isExpanded(SessionDetailSection.OVERVIEW),
                onToggle = { toggle(SessionDetailSection.OVERVIEW) },
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                DetailGrid(
                    listOf(
                        "样本" to (item.sampleCount?.toString() ?: "—"),
                        "帧" to (item.frameCount?.toString() ?: "—"),
                        "raw chunks" to (item.rawChunkCount?.toString() ?: "—"),
                        "文件大小" to formatBytes(item.totalBytes),
                        "被试" to (item.participant?.subjectId ?: "未归档"),
                        "seq" to (item.participant?.sequence?.toString() ?: "—"),
                        "录制类型" to (item.sessionPrefix?.displayName ?: "未记录"),
                        "录制模式" to SessionDetailUiPolicy.recordModeLabel(item.recordMode),
                        "计划时长" to SessionDetailUiPolicy.durationLabel(
                            item.plannedDurationSeconds?.toLong(),
                        ),
                        "实际时长" to SessionDetailUiPolicy.durationLabel(item.actualDurationSeconds),
                        "录前参考血压" to if (item.systolicBp != null && item.diastolicBp != null) {
                            "${item.systolicBp}/${item.diastolicBp} mmHg"
                        } else {
                            "—"
                        },
                        "参考事件" to "${item.bloodPressureCount} 组",
                        "停止原因" to (item.stopReason ?: "—"),
                        "设备" to (item.deviceName ?: "—"),
                        "开始" to formatInstant(item.startedUtc),
                        "结束" to formatInstant(item.endedUtc),
                    ),
                )
            }
        }
        item {
            CollapsibleSectionCard(
                title = "版本与来源",
                summary = "${item.preprocessProfile ?: "未知预处理"} · ${item.protocolProfile ?: "未知协议"}",
                expanded = isExpanded(SessionDetailSection.SOURCE),
                onToggle = { toggle(SessionDetailSection.SOURCE) },
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                LabeledValue("Session ID", item.sessionId ?: "—")
                LabeledValue("App", item.softVersion ?: "—")
                LabeledValue("采集算法", item.algorithmVersion ?: "—")
                LabeledValue("预处理", item.preprocessProfile ?: "—")
                LabeledValue("协议", item.protocolProfile ?: "—")
                LabeledValue("传输 profile", item.transportProfile ?: "—")
                Text(
                    "这些值在录制开始时固化；检查、重放与离线分析不会回写源会话。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            val participant = item.participant
            CollapsibleSectionCard(
                title = "被试资料快照",
                summary = participant?.let {
                    "${it.subjectId ?: "未归档"} · ${it.sex ?: "性别—"} · ${it.ageYears?.let { age -> "$age 岁" } ?: "年龄—"}"
                } ?: "当前会话没有 participant snapshot",
                expanded = isExpanded(SessionDetailSection.PARTICIPANT),
                onToggle = { toggle(SessionDetailSection.PARTICIPANT) },
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                if (participant == null) {
                    Text(
                        "该会话未保存被试资料；源会话保持只读。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LabeledValue("subject", participant.subjectId ?: "—")
                    LabeledValue("profile revision", participant.profileRevisionId ?: "—")
                    LabeledValue("性别", participant.sex ?: "—")
                    LabeledValue("年龄", participant.ageYears?.toString() ?: "—")
                    LabeledValue("身高 / 体重", "${participant.heightCm ?: "—"} cm / ${participant.weightKg ?: "—"} kg")
                    LabeledValue("吸烟频率", participant.smokingFreq.ifBlank { "—" })
                    LabeledValue("饮酒频率", participant.drinkingFreq.ifBlank { "—" })
                    LabeledValue("会话备注", participant.additionalFields["notes"].orEmpty().ifBlank { "—" })
                    participant.additionalFields
                        .filterKeys { it != "notes" }
                        .toSortedMap()
                        .forEach { (key, value) -> LabeledValue(key, value.ifBlank { "—" }) }
                    LabeledValue("资料状态", if (participant.profileComplete) "完整" else "待补齐")
                    Text(
                        "该资料是录制时的不可变 snapshot；后续 subject profile revision 不会改写本会话。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            CollapsibleSectionCard(
                title = "参考血压",
                summary = "录前 ${if (item.systolicBp != null && item.diastolicBp != null) "${item.systolicBp}/${item.diastolicBp}" else "未登记"} · 时间轴 ${item.bloodPressureCount} 组",
                expanded = isExpanded(SessionDetailSection.BLOOD_PRESSURE),
                onToggle = { toggle(SessionDetailSection.BLOOD_PRESSURE) },
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                LabeledValue(
                    "录前 / 会话级参考",
                    if (item.systolicBp != null && item.diastolicBp != null) {
                        "${item.systolicBp}/${item.diastolicBp} mmHg"
                    } else {
                        "—"
                    },
                )
                LabeledValue("录制时间轴手工事件", "${item.bloodPressureCount} 组")
                Text(
                    "新会话的录前登记同时作为第 0 条时间轴事件；在此页修改只更正会话级参考，不伪造历史事件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                var systolicText by remember(item.directory, item.systolicBp) {
                    mutableStateOf(item.systolicBp?.toString().orEmpty())
                }
                var diastolicText by remember(item.directory, item.diastolicBp) {
                    mutableStateOf(item.diastolicBp?.toString().orEmpty())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = systolicText,
                        onValueChange = { systolicText = it.filter(Char::isDigit) },
                        label = { Text("SBP") },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = diastolicText,
                        onValueChange = { diastolicText = it.filter(Char::isDigit) },
                        label = { Text("DBP") },
                        modifier = Modifier.weight(1f),
                    )
                }
                Button(
                    onClick = {
                        onUpdateBloodPressure(
                            systolicText.toIntOrNull(),
                            diastolicText.toIntOrNull(),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存会话级参考血压") }
                when {
                    detail.signal != null -> ReferenceBloodPressureComparisonPanel(
                        trace = detail.signal,
                        artifact = artifacts.firstOrNull(),
                    )
                    detail.isLoadingSignal -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text("正在加载 PPG、参考 BP 与 1 Hz 对齐时间轴…")
                    }
                    detail.signalError != null -> StatusMessage("时间轴加载失败：${detail.signalError}", isError = true)
                    else -> Text("当前 raw 没有可接受的完整样本。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            val inspectionSummary = when {
                detail.isInspecting -> "正在检查 raw / CSV / metadata"
                detail.error != null -> "检查失败：${detail.error}"
                detail.inspection == null -> "等待只读检查结果"
                detail.inspection.findings.any { it.severity == CaptureInspectionSeverity.ERROR } -> "发现结构或计数错误"
                detail.inspection.findings.isNotEmpty() -> "计数一致 · 有边界提示"
                else -> "raw、CSV 与 metadata 一致"
            }
            CollapsibleSectionCard(
                title = "完整性复核",
                summary = inspectionSummary,
                expanded = isExpanded(SessionDetailSection.INTEGRITY),
                onToggle = { toggle(SessionDetailSection.INTEGRITY) },
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                when {
                    detail.isInspecting -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            Text("正在从 raw 重新解码并核对 CSV…")
                        }
                        OutlinedButton(onClick = onCancelInspection) { Text("取消检查") }
                    }
                    detail.error != null -> StatusMessage("检查失败：${detail.error}", isError = true)
                    detail.inspection != null -> {
                        val inspection = detail.inspection
                        val errors = inspection.findings.count { it.severity == CaptureInspectionSeverity.ERROR }
                        val warnings = inspection.findings.size - errors
                        StatusMessage(
                            when {
                                errors > 0 -> "发现 $errors 个结构或计数错误；源文件未修改"
                                warnings > 0 -> "raw、CSV 与 metadata 计数一致；有 $warnings 条采集边界提示"
                                else -> "raw、CSV 与 metadata 已核对一致"
                            },
                            isError = errors > 0,
                            isWarning = errors == 0 && warnings > 0,
                        )
                        inspection.findings.forEach { finding ->
                            StatusMessage(
                                finding.message,
                                isError = finding.severity == CaptureInspectionSeverity.ERROR,
                                isWarning = finding.severity == CaptureInspectionSeverity.WARNING,
                            )
                        }
                        inspection.replay?.let { ReplaySummary(it) }
                    }
                }
            }
        }
        item {
            CollapsibleSectionCard(
                title = "波形重放",
                summary = detail.signal?.let {
                    "${it.timeSeconds.size} 点 · RAW / ZERO / FIXED 0.5–12 Hz"
                } ?: if (detail.isLoadingSignal) "正在加载完整 signal" else "暂无可重放 signal",
                expanded = isExpanded(SessionDetailSection.REPLAY),
                onToggle = { toggle(SessionDetailSection.REPLAY) },
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                when {
                    detail.isLoadingSignal -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            Text("正在加载完整 accepted signal 与全程 zero-phase 波形…")
                        }
                    }
                    detail.signalError != null -> StatusMessage("完整信号加载失败：${detail.signalError}", isError = true)
                    detail.signal != null -> CompleteSignalReplayPanel(
                        trace = detail.signal,
                        artifact = artifacts.firstOrNull(),
                    )
                    else -> EmptyState("无可重放信号", "raw 中没有可接受的完整样本。")
                }
            }
        }
        item {
            AnalysisWorkbenchCard(
                item = item,
                artifacts = artifacts,
                task = task,
                onStart = { onStartAnalysis(item) },
                onCancel = { onCancelAnalysis(item.directory) },
                signal = detail.signal,
                onOpenFullscreenWorkbench = onOpenFullscreenWorkbench,
                expanded = isExpanded(SessionDetailSection.ANALYSIS),
                onToggleExpanded = { toggle(SessionDetailSection.ANALYSIS) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        item {
            CollapsibleSectionCard(
                title = "导出与恢复",
                summary = if (item.recoveryCandidate) "可导出 · 可创建恢复副本" else "只读源会话 ZIP 导出",
                expanded = isExpanded(SessionDetailSection.EXPORT),
                onToggle = { toggle(SessionDetailSection.EXPORT) },
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Button(
                    onClick = { onRequestExport(item) },
                    enabled = !state.action.isRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("导出源会话 ZIP") }
                if (item.recoveryCandidate) {
                    OutlinedButton(
                        onClick = onRecover,
                        enabled = !state.action.isRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("创建非破坏性恢复副本") }
                }
                state.action.message?.let { StatusMessage(it) }
                state.action.error?.let { StatusMessage("操作失败：$it", isError = true) }
                if (state.action.isRunning) {
                    val progressText = if (state.action.totalBytes > 0) {
                        "${(state.action.bytesCopied * 100 / state.action.totalBytes).coerceIn(0, 100)}%"
                    } else {
                        "正在处理"
                    }
                    Text(progressText, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onCancelAction) { Text("取消操作") }
                } else if (state.action.message != null || state.action.error != null) {
                    OutlinedButton(onClick = onClearAction) { Text("清除提示") }
                }
                Text(
                    "导出只写入用户选择的位置；恢复只创建新目录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReplaySummary(replay: CupRawReplayReport) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    DetailGrid(
        listOf(
            "raw records" to replay.rawRecordCount.toString(),
            "解码 / 接受帧" to "${replay.decodedFrames} / ${replay.acceptedFrames}",
            "辅助帧" to replay.auxiliaryFrames.toString(),
            "接受样本" to replay.acceptedSamples.toString(),
            "缺失 / 重复 / 乱序" to "${replay.missingFrames} / ${replay.duplicateFrames} / ${replay.outOfOrderFrames}",
            "前导对齐" to "${replay.leadingAlignmentBytes} B",
            "停止边界尾部" to "${replay.pendingDecoderBytes} B",
            "对齐后丢弃" to "${replay.structuralDiscardedBytes} B",
            "峰值 record buffer" to formatBytes(replay.peakRawRecordBufferBytes.toLong()),
        ),
    )
}

private enum class AnalysisSection { SUMMARY, SIGNAL, SPECTRUM, CYCLE, DIAGNOSTICS }

@Composable
private fun AnalysisWorkbenchCard(
    item: SessionListItemUi,
    artifacts: List<CaptureSessionAnalysisArtifact>,
    task: SessionAnalysisTaskUi?,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    signal: com.example.ppgcollector_android.data.session.CaptureSessionSignalTrace?,
    onOpenFullscreenWorkbench: () -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var section by remember(item.directory) { mutableStateOf(AnalysisSection.SUMMARY) }
    var selectedPath by remember(artifacts) { mutableStateOf(artifacts.firstOrNull()?.path) }
    val artifact = artifacts.firstOrNull { it.path == selectedPath } ?: artifacts.firstOrNull()
    val summary = when (task?.status) {
        SessionAnalysisTaskStatus.RUNNING -> "分析运行中 · ${task.progress?.let { "${(it.fractionCompleted * 100).toInt()}%" } ?: "准备中"}"
        SessionAnalysisTaskStatus.FAILED -> "分析失败 · ${task.error ?: "未知原因"}"
        else -> if (artifact == null) {
            "尚无结果 · 可生成 0.5–12 Hz ZERO / FIXED"
        } else {
            "${artifacts.size} 个版本化结果 · ${artifact.report.preprocessProfile}"
        }
    }
    CollapsibleSectionCard(
        title = "离线分析",
        summary = summary,
        expanded = expanded,
        onToggle = onToggleExpanded,
        modifier = modifier,
    ) {
        Text(
            "从 raw 重放生成独立 JSON：稳定段、8 s / 2 s 窗口、0.5–12 Hz ZERO、频谱、峰与平均周期。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onOpenFullscreenWorkbench,
            enabled = artifact != null && signal != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (signal == null) "完整信号加载中…" else "全屏横屏工作台")
        }
        if (task?.status == SessionAnalysisTaskStatus.RUNNING) {
            val progress = task.progress
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(
                    progress = { progress?.fractionCompleted?.toFloat() ?: 0f },
                    modifier = Modifier.size(28.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(progress?.stage?.title ?: "正在准备")
                    Text(
                        progress?.let { "${(it.fractionCompleted * 100).toInt()}% · ${it.completedWindowCount}/${it.totalWindowCount} 窗口" }
                            ?: "等待进度",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(onClick = onCancel) { Text("取消") }
            }
        } else {
            Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                Text(if (artifacts.isEmpty()) "生成离线分析" else "使用当前 profile 重新分析")
            }
        }
        when (task?.status) {
            SessionAnalysisTaskStatus.COMPLETED -> StatusMessage("已生成 ${task.artifactName}")
            SessionAnalysisTaskStatus.CANCELLED -> StatusMessage("分析已取消，未留下不完整 JSON", isWarning = true)
            SessionAnalysisTaskStatus.FAILED -> StatusMessage("分析失败：${task.error}", isError = true)
            else -> Unit
        }
        if (artifact == null) {
            Text("尚无分析结果。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@CollapsibleSectionCard
        }
        if (SessionDetailUiPolicy.showsArtifactSelector(artifacts.size)) {
            Text("分析结果", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                artifacts.forEachIndexed { index, historyArtifact ->
                    FilledTonalButton(
                        onClick = { selectedPath = historyArtifact.path },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = if (historyArtifact.path == artifact.path) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                    ) {
                        Text("结果 ${index + 1} · ${formatInstant(historyArtifact.report.endedUtc)}")
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AnalysisSection.entries.forEach { value ->
                FilledTonalButton(
                    onClick = { section = value },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (value == section) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) { Text(analysisSectionLabel(value)) }
            }
        }
        when (section) {
            AnalysisSection.SUMMARY -> AnalysisOverview(artifact)
            AnalysisSection.SIGNAL -> AnalysisPpg(artifact, signal)
            AnalysisSection.SPECTRUM -> AnalysisSpectrum(artifact)
            AnalysisSection.CYCLE -> AnalysisCycle(artifact)
            AnalysisSection.DIAGNOSTICS -> AnalysisDiagnostics(artifact)
        }
        Text(
            "analysis_profile=${artifact.report.analysisProfile} · ${artifact.report.preprocessProfile}。" +
                "短任务由 app scope 执行；进程终止不会伪装为完成。IMU 当前不支持。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun analysisSectionLabel(value: AnalysisSection): String = when (value) {
    AnalysisSection.SUMMARY -> "摘要"
    AnalysisSection.SIGNAL -> "信号与指标"
    AnalysisSection.SPECTRUM -> "频谱"
    AnalysisSection.CYCLE -> "周期"
    AnalysisSection.DIAGNOSTICS -> "诊断"
}

@Composable
private fun AnalysisOverview(artifact: CaptureSessionAnalysisArtifact) {
    val report = artifact.report
    val metrics = report.metrics
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AnalysisMetric("会话 HR", bpmText(metrics.heartRateBpm), Modifier.weight(1f))
        AnalysisMetric("置信度", "${(metrics.confidence * 100).toInt()}%", Modifier.weight(1f))
        AnalysisMetric("稳定占比", "${(metrics.stableSampleRatio * 100).toInt()}%", Modifier.weight(1f))
    }
    DetailGrid(
        listOf(
            "稳定段" to metrics.segmentCount.toString(),
            "接受窗口" to "${metrics.acceptedWindowCount} / ${metrics.windowCount}",
            "通道 / 极性" to "${metrics.selectedChannel ?: "—"} / ${metrics.selectedPolarity ?: "—"}",
            "峰 / 平均周期" to "${metrics.peakCount} / ${metrics.averageCycleCount}",
            "频谱 HR" to bpmText(metrics.spectralHeartRateBpm),
            "RR MAD" to (metrics.rrMadSeconds?.let { "%.3f s".format(Locale.ROOT, it) } ?: "—"),
        ),
    )
    if (report.warnings.isNotEmpty()) {
        report.warnings.forEach { StatusMessage(it, isWarning = true) }
    }
}

@Composable
private fun AnalysisDiagnostics(artifact: CaptureSessionAnalysisArtifact) {
    val report = artifact.report
    LabeledValue("raw SHA-256", report.sourceRawSha256)
    LabeledValue("输入样本", report.input.acceptedSampleCount.toString())
    LabeledValue("前导 / 停止尾部", "${report.input.leadingAlignmentByteCount} / ${report.input.pendingDecoderByteCount} B")
    LabeledValue("结构无效 / 对齐后丢弃", "${report.input.structurallyInvalidFrameCount} / ${report.input.structuralDiscardedByteCount}")
    LabeledValue("拒绝原因", report.metrics.rejectionCounts.entries.joinToString { "${it.key}=${it.value}" }.ifEmpty { "无" })
    report.segments.forEach { segment ->
        Text(
            "稳定段 ${segment.index + 1} · ${"%.2f".format(Locale.ROOT, segment.startSeconds)}–" +
                "${"%.2f".format(Locale.ROOT, segment.stopSeconds)} s · ${"%.1f".format(Locale.ROOT, segment.durationSeconds)} s",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun AnalysisPpg(
    artifact: CaptureSessionAnalysisArtifact,
    signal: com.example.ppgcollector_android.data.session.CaptureSessionSignalTrace?,
) {
    CompletePpgAnalysisPanel(artifact, signal)
}

@Composable
private fun AnalysisSpectrum(artifact: CaptureSessionAnalysisArtifact) {
    val spectrum = artifact.report.spectrum
    if (spectrum.power.isEmpty()) {
        EmptyState("无可用频谱", "没有接受窗口可用于频域展示。")
        return
    }
    val cardiac = spectrum.frequenciesHz.indices.filter {
        spectrum.frequenciesHz[it] in (35.0 / 60.0)..(200.0 / 60.0)
    }
    val values = cardiac.map { spectrum.power[it] }.toDoubleArray()
    SessionWaveformChart("WELCH / DFT POWER", MaterialTheme.colorScheme.secondary, values)
    Text(
        "心率频带 0.58–3.33 Hz · 主峰 ${bpmText(artifact.report.metrics.spectralHeartRateBpm)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AnalysisCycle(artifact: CaptureSessionAnalysisArtifact) {
    val cycle = artifact.report.averageCycle
    if (cycle.mean.isEmpty()) {
        EmptyState("平均周期不可用", "至少需要两个同稳定段、形态一致的接受周期。")
        return
    }
    SessionWaveformChart("MEAN CYCLE", MaterialTheme.colorScheme.primary, cycle.mean)
    SessionWaveformChart("95% CI", MaterialTheme.colorScheme.tertiary, cycle.ci95)
    Text(
        "${cycle.cycleCount} 个接受周期 · 相位归一化 0–1 · CI=1.96×SEM",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun SessionComparisonScreen(
    state: SessionsUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val candidates = state.sessions.mapNotNull { item ->
        state.artifactsBySession[item.directory]?.firstOrNull()?.let { item to it }
    }
    var baselineName by remember(candidates) { mutableStateOf(candidates.getOrNull(0)?.first?.directory?.toString()) }
    var candidateName by remember(candidates) { mutableStateOf(candidates.getOrNull(1)?.first?.directory?.toString()) }
    val baseline = candidates.firstOrNull { it.first.directory.toString() == baselineName }
    val candidate = candidates.firstOrNull { it.first.directory.toString() == candidateName }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { PageHeader("双会话对比", "最新版本化结果 · 原始幅值与归一化语义分离", onBack) }
        if (candidates.size < 2) {
            item { EmptyState("至少需要两个分析会话", "先分别进入两个会话生成离线分析。") }
            return@LazyColumn
        }
        item {
            SectionCard("比较对象", Modifier.padding(horizontal = 16.dp)) {
                candidates.forEach { (item, artifact) ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(item.baseName, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${artifact.report.analysisProfile} · ${bpmText(artifact.report.metrics.heartRateBpm)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(onClick = { baselineName = item.directory.toString() }) {
                                    Text(if (baselineName == item.directory.toString()) "A 已选" else "设为 A")
                                }
                                FilledTonalButton(onClick = { candidateName = item.directory.toString() }) {
                                    Text(if (candidateName == item.directory.toString()) "B 已选" else "设为 B")
                                }
                            }
                        }
                    }
                }
            }
        }
        if (baseline != null && candidate != null) {
            item {
                SectionCard("全幅数值对照", Modifier.padding(horizontal = 16.dp)) {
                    ComparisonRow("会话", baseline.first.baseName, candidate.first.baseName)
                    ComparisonRow("HR", bpmText(baseline.second.report.metrics.heartRateBpm), bpmText(candidate.second.report.metrics.heartRateBpm))
                    ComparisonRow("稳定占比", percentText(baseline.second.report.metrics.stableSampleRatio), percentText(candidate.second.report.metrics.stableSampleRatio))
                    ComparisonRow("接受窗口", baseline.second.report.metrics.acceptedWindowCount.toString(), candidate.second.report.metrics.acceptedWindowCount.toString())
                    ComparisonRow("通道", baseline.second.report.metrics.selectedChannel ?: "—", candidate.second.report.metrics.selectedChannel ?: "—")
                    ComparisonRow("raw SHA", baseline.second.report.sourceRawSha256.take(12), candidate.second.report.sourceRawSha256.take(12))
                }
            }
            item {
                SectionCard("Stacked cycles", Modifier.padding(horizontal = 16.dp)) {
                    SessionWaveformChart("A · ${baseline.first.baseName}", Color(0xFFD74747), normalized(baseline.second.report.averageCycle.mean))
                    SessionWaveformChart("B · ${candidate.first.baseName}", Color(0xFF3478C8), normalized(candidate.second.report.averageCycle.mean))
                    Text("每条曲线独立 z-score，仅比较周期形态。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                SectionCard("Unified cycle overlay", Modifier.padding(horizontal = 16.dp)) {
                    MultiLineChart(
                        first = normalized(baseline.second.report.averageCycle.mean),
                        second = normalized(candidate.second.report.averageCycle.mean),
                        firstLabel = "A ${baseline.first.baseName}",
                        secondLabel = "B ${candidate.first.baseName}",
                    )
                    Text("统一相位 0–1、统一归一化纵轴；不比较原始 DC/AC 绝对幅值。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MultiLineChart(
    first: DoubleArray,
    second: DoubleArray,
    firstLabel: String,
    secondLabel: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(firstLabel, color = Color(0xFFD74747), style = MaterialTheme.typography.labelMedium)
        Text(secondLabel, color = Color(0xFF3478C8), style = MaterialTheme.typography.labelMedium)
    }
    val values = first + second
    val minimum = values.minOrNull() ?: -1.0
    val maximum = values.maxOrNull() ?: 1.0
    Surface(
        modifier = Modifier.fillMaxWidth().height(150.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    ) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            fun draw(valuesToDraw: DoubleArray, color: Color) {
                if (valuesToDraw.size < 2) return
                val span = (maximum - minimum).coerceAtLeast(1e-9)
                val path = Path()
                valuesToDraw.forEachIndexed { index, value ->
                    val x = index.toFloat() / valuesToDraw.lastIndex * size.width
                    val y = ((maximum - value) / span * size.height).toFloat()
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            draw(first, Color(0xFFD74747))
            draw(second, Color(0xFF3478C8))
        }
    }
}

@Composable
private fun SessionWaveformChart(
    label: String,
    color: Color,
    values: DoubleArray,
    modifier: Modifier = Modifier,
    peakIndices: IntArray = intArrayOf(),
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.Bold)
        Text("${values.size} pts", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(112.dp)
            .semantics { contentDescription = "$label 波形，${values.size} 个点，可拖动缩放" },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
    ) {
        Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                repeat(3) { row ->
                    val y = size.height * (row + 1) / 4f
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
                if (values.isEmpty()) return@Canvas
                val plot = LiveWaveformPlotMath.plot(values, maxOf(2, (size.width * 2).toInt()))
                if (plot.points.isEmpty()) return@Canvas
                val rawSpan = plot.maximum - plot.minimum
                val padding = if (rawSpan > 0.0) rawSpan * 0.08 else max(abs(plot.maximum) * 0.08, 1.0)
                val lower = plot.minimum - padding
                val upper = plot.maximum + padding
                val span = (upper - lower).coerceAtLeast(1e-9)
                fun offset(pointIndex: Int): Offset {
                    val point = plot.points[pointIndex]
                    val x = if (values.size <= 1) size.width / 2 else point.offset.toFloat() / values.lastIndex * size.width
                    val y = ((upper - point.value) / span * size.height).toFloat().coerceIn(0f, size.height)
                    return Offset(x, y)
                }
                if (plot.points.size == 1) {
                    drawCircle(color, 2.5.dp.toPx(), offset(0))
                } else {
                    val path = Path().apply {
                        offset(0).let { moveTo(it.x, it.y) }
                        for (index in 1 until plot.points.size) offset(index).let { lineTo(it.x, it.y) }
                    }
                    drawPath(path, color, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                peakIndices.filter { it in values.indices }.forEach { peak ->
                    val x = if (values.size <= 1) size.width / 2 else peak.toFloat() / values.lastIndex * size.width
                    val y = ((upper - values[peak]) / span * size.height).toFloat().coerceIn(0f, size.height)
                    drawCircle(color, 3.dp.toPx(), Offset(x, y))
                }
            }
            if (values.isEmpty()) Text("暂无数据", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PageHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = onBack) { Text("‹ 返回") }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            trailing()
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun CollapsibleSectionCard(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth().semantics {
            stateDescription = if (expanded) "已展开" else "已收起"
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                FilledTonalButton(onClick = onToggle) {
                    Text(if (expanded) "收起" else "展开")
                }
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                content()
            }
        }
    }
}

@Composable
private fun NoticeCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
    ) {
        Text(message, Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SectionTitle(value: String) {
    Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun EmptyState(title: String, detail: String) {
    Column(
        Modifier.fillMaxWidth().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusMessage(
    message: String,
    isError: Boolean = false,
    isWarning: Boolean = false,
) {
    val color = when {
        isError -> MaterialTheme.colorScheme.error
        isWarning -> Color(0xFF9A6700)
        else -> MaterialTheme.colorScheme.tertiary
    }
    Surface(shape = RoundedCornerShape(10.dp), color = color.copy(alpha = 0.10f)) {
        Text(
            message,
            Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

@Composable
private fun DetailGrid(values: List<Pair<String, String>>) {
    values.chunked(2).forEach { row ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (label, value) ->
                Surface(
                    Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f).padding(start = 16.dp), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SmallStat(label: String, value: String) {
    Column {
        Text(value, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AnalysisMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ComparisonRow(label: String, baseline: String, candidate: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("A · $baseline", style = MaterialTheme.typography.bodySmall)
        Text("B · $candidate", style = MaterialTheme.typography.bodySmall)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

private fun normalized(values: DoubleArray): DoubleArray {
    if (values.isEmpty()) return values
    val mean = values.average()
    val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
    val standardDeviation = kotlin.math.sqrt(variance)
    return if (standardDeviation < 1e-12) DoubleArray(values.size) else {
        DoubleArray(values.size) { (values[it] - mean) / standardDeviation }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(Locale.ROOT, bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(Locale.ROOT, bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatInstant(value: Instant?): String = value?.let(uiDateFormatter::format) ?: "—"
private fun bpmText(value: Double?): String = value?.let { "%.1f bpm".format(Locale.ROOT, it) } ?: "不可用"
private fun percentText(value: Double): String = "${(value * 100).toInt()}%"
