package com.example.ppgcollector_android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
internal fun SavedSessionsScreen(
    state: SessionsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (SessionListItemUi) -> Unit,
    onCancelAnalysis: (NioPath) -> Unit,
    onOpenCompare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        PageHeader(
            title = "已保存会话",
            subtitle = "文件系统记录、完整性复核与版本化离线分析",
            onBack = onBack,
            trailing = {
                OutlinedButton(onClick = onRefresh, enabled = !state.isLoading) {
                    Text("刷新")
                }
            },
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                NoticeCard(
                    "会话保存在本应用内部，卸载应用会删除未导出的数据。" +
                        "进入详情可导出 ZIP；检查与分析均不会改写 raw、CSV 或 metadata。",
                )
            }
            val running = state.analysisTasks.filterValues {
                it.status == SessionAnalysisTaskStatus.RUNNING
            }
            if (running.isNotEmpty()) {
                item { SectionTitle("离线分析任务") }
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
                item {
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
                item { StatusMessage(message, isError = true) }
            }
            item {
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
                item {
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
                    onClick = { onSelect(item) },
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun SessionSummaryCard(
    item: SessionListItemUi,
    analysisCount: Int,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
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
            SectionCard("会话概览", Modifier.padding(horizontal = 16.dp)) {
                DetailGrid(
                    listOf(
                        "样本" to (item.sampleCount?.toString() ?: "—"),
                        "帧" to (item.frameCount?.toString() ?: "—"),
                        "raw chunks" to (item.rawChunkCount?.toString() ?: "—"),
                        "文件大小" to formatBytes(item.totalBytes),
                        "被试" to (item.participant?.subjectId ?: "未归档"),
                        "seq" to (item.participant?.sequence?.toString() ?: "—"),
                        "参考血压" to "${item.bloodPressureCount} 组",
                        "停止原因" to (item.stopReason ?: "—"),
                        "设备" to (item.deviceName ?: "—"),
                        "开始" to formatInstant(item.startedUtc),
                        "结束" to formatInstant(item.endedUtc),
                    ),
                )
            }
        }
        item {
            SectionCard("版本与来源", Modifier.padding(horizontal = 16.dp)) {
                LabeledValue("Session ID", item.sessionId ?: "—")
                LabeledValue("App", item.softVersion ?: "—")
                LabeledValue("采集算法", item.algorithmVersion ?: "—")
                LabeledValue("预处理", item.preprocessProfile ?: "—")
                LabeledValue("协议", item.protocolProfile ?: "—")
                LabeledValue("传输 profile", item.transportProfile ?: "—")
                Text(
                    "这些值在录制开始时固化；检查、重放与 M6 离线分析不会回写源会话。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item.participant?.let { participant ->
            item {
                SectionCard("被试资料快照", Modifier.padding(horizontal = 16.dp)) {
                    LabeledValue("subject", participant.subjectId ?: "—")
                    LabeledValue("profile revision", participant.profileRevisionId ?: "—")
                    LabeledValue("性别", participant.sex ?: "—")
                    LabeledValue("年龄", participant.ageYears?.toString() ?: "—")
                    LabeledValue("身高 / 体重", "${participant.heightCm ?: "—"} cm / ${participant.weightKg ?: "—"} kg")
                    LabeledValue("资料状态", if (participant.profileComplete) "完整" else "待补齐")
                    Text(
                        "该资料是录制时的不可变 snapshot；后续 subject profile revision 不会改写本会话。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (item.bloodPressureCount > 0) {
            item {
                SectionCard("参考血压", Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        "本会话保存了 ${item.bloodPressureCount} 组手工参考血压；详情波形以 sidecar 的 dialog-open source cursor 对齐。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item {
            SectionCard("完整性复核", Modifier.padding(horizontal = 16.dp)) {
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
            SectionCard("raw 重放波形", Modifier.padding(horizontal = 16.dp)) {
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
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        item {
            SectionCard("导出与恢复", Modifier.padding(horizontal = 16.dp)) {
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

private enum class AnalysisPage { OVERVIEW, WORKBENCH, HISTORY }
private enum class WorkbenchStage { DIAGNOSTICS, PPG, SPECTRUM, CYCLE }

@Composable
private fun AnalysisWorkbenchCard(
    item: SessionListItemUi,
    artifacts: List<CaptureSessionAnalysisArtifact>,
    task: SessionAnalysisTaskUi?,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    signal: com.example.ppgcollector_android.data.session.CaptureSessionSignalTrace?,
    onOpenFullscreenWorkbench: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by remember(item.directory) { mutableStateOf(AnalysisPage.OVERVIEW) }
    var stage by remember(item.directory) { mutableStateOf(WorkbenchStage.DIAGNOSTICS) }
    var selectedPath by remember(artifacts) { mutableStateOf(artifacts.firstOrNull()?.path) }
    val artifact = artifacts.firstOrNull { it.path == selectedPath } ?: artifacts.firstOrNull()
    SectionCard("M6 离线分析", modifier) {
        Text(
            "从 raw 重放生成独立 JSON：稳定段、8 s / 2 s 窗口、频谱、峰与平均周期。",
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
            return@SectionCard
        }
        SegmentedControls(
            values = AnalysisPage.entries,
            selected = page,
            label = {
                when (it) {
                    AnalysisPage.OVERVIEW -> "概览"
                    AnalysisPage.WORKBENCH -> "工作台"
                    AnalysisPage.HISTORY -> "历史 ${artifacts.size}"
                }
            },
            onSelect = { page = it },
        )
        when (page) {
            AnalysisPage.OVERVIEW -> AnalysisOverview(artifact)
            AnalysisPage.WORKBENCH -> {
                SegmentedControls(
                    values = WorkbenchStage.entries,
                    selected = stage,
                    label = {
                        when (it) {
                            WorkbenchStage.DIAGNOSTICS -> "诊断"
                            WorkbenchStage.PPG -> "PPG"
                            WorkbenchStage.SPECTRUM -> "频谱"
                            WorkbenchStage.CYCLE -> "周期"
                        }
                    },
                    onSelect = { stage = it },
                )
                when (stage) {
                    WorkbenchStage.DIAGNOSTICS -> AnalysisDiagnostics(artifact)
                    WorkbenchStage.PPG -> AnalysisPpg(artifact, signal)
                    WorkbenchStage.SPECTRUM -> AnalysisSpectrum(artifact)
                    WorkbenchStage.CYCLE -> AnalysisCycle(artifact)
                }
            }
            AnalysisPage.HISTORY -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                artifacts.forEach { historyArtifact ->
                    Card(
                        onClick = { selectedPath = historyArtifact.path },
                        colors = CardDefaults.cardColors(
                            containerColor = if (historyArtifact.path == artifact.path) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                            },
                        ),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(historyArtifact.path.fileName.toString(), style = MaterialTheme.typography.labelLarge)
                            Text(
                                "${historyArtifact.report.analysisProfile} · ${formatInstant(historyArtifact.report.endedUtc)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "${historyArtifact.report.metrics.acceptedWindowCount}/${historyArtifact.report.metrics.windowCount} 接受窗口 · " +
                                    bpmText(historyArtifact.report.metrics.heartRateBpm),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
        Text(
            "analysis_profile=${artifact.report.analysisProfile} · ${artifact.report.preprocessProfile}。" +
                "短任务由 app scope 执行；进程终止不会伪装为完成。IMU 当前不支持。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
            item { EmptyState("至少需要两个分析会话", "先分别进入两个会话生成 M6 离线分析。") }
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
    trailing: @Composable () -> Unit = {},
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
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
private fun <T> SegmentedControls(
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { value ->
            FilledTonalButton(
                onClick = { onSelect(value) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (value == selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp),
            ) { Text(label(value), style = MaterialTheme.typography.labelMedium) }
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
