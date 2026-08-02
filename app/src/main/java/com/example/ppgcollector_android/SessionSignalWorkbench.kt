package com.example.ppgcollector_android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ppgcollector_android.core.signal.LiveWaveformPlotMath
import com.example.ppgcollector_android.core.signal.OfflineDisplaySpectrum
import com.example.ppgcollector_android.core.signal.OfflinePulseWindow
import com.example.ppgcollector_android.core.signal.OfflineSignalSegment
import com.example.ppgcollector_android.data.session.CaptureSessionAnalysisArtifact
import com.example.ppgcollector_android.data.session.CaptureSessionSignalTrace
import com.example.ppgcollector_android.data.session.ReplayWaveformViewport
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

private enum class ReplaySignalStage { RAW, ZERO_PHASE }
private enum class WorkbenchPane { SIGNAL, WINDOWS, SPECTRUM, CYCLE, DIAGNOSTICS }
private enum class WorkbenchChannel { SELECTED, RED, IR }
private enum class WorkbenchSignalStage { RAW, BANDPASS, PEAKS }

private data class CompleteSignalSeries(
    val label: String,
    val color: Color,
    val values: DoubleArray,
)

@Composable
internal fun CompleteSignalReplayPanel(
    trace: CaptureSessionSignalTrace,
    artifact: CaptureSessionAnalysisArtifact?,
) {
    val total = trace.timeSeconds.size
    var stage by remember(trace) { mutableStateOf(ReplaySignalStage.RAW) }
    var viewport by remember(trace) {
        mutableStateOf(
            ReplayWaveformViewport().apply {
                showWindow(0, 800, total)
            },
        )
    }
    val range = viewport.visibleRange(total)
    val gesture = Modifier.pointerInput(total) {
        detectTransformGestures { centroid, pan, zoom, _ ->
            val next = viewport.copyViewport()
            next.applyGesture(
                zoomChange = zoom.toDouble(),
                horizontalPanPixels = pan.x.toDouble(),
                viewportWidthPixels = size.width.toDouble().coerceAtLeast(1.0),
                centroidXPixels = centroid.x.toDouble(),
                totalSampleCount = total,
            )
            viewport = next
        }
    }
    Text(
        "完整 accepted signal · 默认 8 s · 双指缩放 · 单指横向拖动",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ChoiceRow(
        values = ReplaySignalStage.entries,
        selected = stage,
        label = { if (it == ReplaySignalStage.RAW) "RAW" else "ZERO-PHASE" },
        onSelect = { stage = it },
    )
    ViewportControls(
        viewport = viewport,
        total = total,
        visibleRange = range,
        timeSeconds = trace.timeSeconds,
        onChange = { viewport = it },
        onDefaultWindow = {
            viewport = viewport.copyViewport().apply { showWindow(0, 800, total) }
        },
    )
    val red = if (stage == ReplaySignalStage.RAW) trace.rawRed else trace.filteredRed
    val ir = if (stage == ReplaySignalStage.RAW) trace.rawIr else trace.filteredIr
    val prefix = if (stage == ReplaySignalStage.RAW) "RAW" else "ZERO-PHASE"
    CompleteSignalChart(
        series = listOf(CompleteSignalSeries("$prefix RED", Color(0xFFD74747), red)),
        timeSeconds = trace.timeSeconds,
        visibleRange = range,
        breakIndices = trace.breakIndices,
        stableSegments = artifact?.report?.segments.orEmpty(),
        showStableSegments = true,
        modifier = gesture.height(136.dp),
    )
    CompleteSignalChart(
        series = listOf(CompleteSignalSeries("$prefix IR", Color(0xFF3478C8), ir)),
        timeSeconds = trace.timeSeconds,
        visibleRange = range,
        breakIndices = trace.breakIndices,
        stableSegments = artifact?.report?.segments.orEmpty(),
        showStableSegments = true,
        modifier = gesture.height(136.dp),
    )
    Text(
        "${trace.preprocessProfile}：对每个连续数据段执行整段 0.6–4 Hz forward/backward SOS；" +
            "gap 不跨越，稳定段仍只控制分析接受，不裁掉可视化信号。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun CompletePpgAnalysisPanel(
    artifact: CaptureSessionAnalysisArtifact,
    trace: CaptureSessionSignalTrace?,
) {
    if (trace == null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Text("正在重放完整 raw 并生成全程 zero-phase 波形…")
        }
        return
    }
    var channel by remember(artifact.path) { mutableStateOf(WorkbenchChannel.SELECTED) }
    var stage by remember(artifact.path) { mutableStateOf(WorkbenchSignalStage.BANDPASS) }
    val total = trace.timeSeconds.size
    val defaultStart = artifact.report.windows
        .filter(OfflinePulseWindow::accepted)
        .maxByOrNull(OfflinePulseWindow::confidence)
        ?.startIndex ?: 0
    var viewport by remember(trace, artifact.path) {
        mutableStateOf(
            ReplayWaveformViewport().apply { showWindow(defaultStart, 800, total) },
        )
    }
    val range = viewport.visibleRange(total)
    ChoiceRow(WorkbenchChannel.entries, channel, ::channelLabel) { channel = it }
    ChoiceRow(WorkbenchSignalStage.entries, stage, ::signalStageLabel) { stage = it }
    ViewportControls(
        viewport, total, range, trace.timeSeconds, { viewport = it },
        onDefaultWindow = {
            viewport = viewport.copyViewport().apply { showWindow(defaultStart, 800, total) }
        },
    )
    val resolved = resolveChannel(channel, artifact)
    val values = signalValues(trace, resolved, stage)
    val gesture = Modifier.pointerInput(total) {
        detectTransformGestures { centroid, pan, zoom, _ ->
            viewport = viewport.copyViewport().apply {
                applyGesture(
                    zoom.toDouble(), pan.x.toDouble(), size.width.toDouble().coerceAtLeast(1.0),
                    centroid.x.toDouble(), total,
                )
            }
        }
    }
    CompleteSignalChart(
        series = listOf(
            CompleteSignalSeries(
                "${signalStageLabel(stage)} $resolved",
                if (resolved == "RED") Color(0xFFD74747) else Color(0xFF3478C8),
                values,
            ),
        ),
        timeSeconds = trace.timeSeconds,
        visibleRange = range,
        breakIndices = trace.breakIndices,
        stableSegments = artifact.report.segments,
        peaks = if (stage == WorkbenchSignalStage.PEAKS && resolved == artifact.report.metrics.selectedChannel) {
            artifact.report.peaks.map { it.sampleIndex }.toIntArray()
        } else {
            intArrayOf()
        },
        showStableSegments = true,
        modifier = gesture.height(180.dp),
    )
    Text(
        "全程 ${trace.timeSeconds.size} 点；当前仅改变视窗与显示 stage，不改变已保存 detector 输出。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun FullscreenSessionWorkbenchScreen(
    state: SessionsUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val detail = state.selected
    if (detail == null) {
        WorkbenchHeader("分析工作台", "会话已不可用", onBack)
        return
    }
    val artifacts = state.artifactsBySession[detail.item.directory].orEmpty()
    var selectedPath by remember(artifacts) { mutableStateOf(artifacts.firstOrNull()?.path) }
    val artifact = artifacts.firstOrNull { it.path == selectedPath } ?: artifacts.firstOrNull()
    val trace = detail.signal
    if (artifact == null || trace == null) {
        Column(modifier.fillMaxSize()) {
            WorkbenchHeader(detail.item.baseName, "完整信号工作台", onBack)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (detail.isLoadingSignal) CircularProgressIndicator()
                    Text(detail.signalError ?: "请先完成一次 M6 离线分析并等待完整信号重放。")
                }
            }
        }
        return
    }

    val total = trace.timeSeconds.size
    val defaultWindow = artifact.report.windows.filter(OfflinePulseWindow::accepted)
        .maxByOrNull(OfflinePulseWindow::confidence)
    var pane by remember(artifact.path) { mutableStateOf(WorkbenchPane.SIGNAL) }
    var channel by remember(artifact.path) { mutableStateOf(WorkbenchChannel.SELECTED) }
    var signalStage by remember(artifact.path) { mutableStateOf(WorkbenchSignalStage.BANDPASS) }
    var showPeaks by remember(artifact.path) { mutableStateOf(true) }
    var showSegments by remember(artifact.path) { mutableStateOf(true) }
    var invert by remember(artifact.path) { mutableStateOf(false) }
    var selectedWindowIndex by remember(artifact.path) {
        mutableStateOf(artifact.report.windows.indexOf(defaultWindow).takeIf { it >= 0 })
    }
    var viewport by remember(trace, artifact.path) {
        mutableStateOf(
            ReplayWaveformViewport().apply {
                showWindow(defaultWindow?.startIndex ?: 0, 800, total)
            },
        )
    }
    val range = viewport.visibleRange(total)

    Column(modifier.fillMaxSize()) {
        WorkbenchHeader(
            title = detail.item.baseName,
            subtitle = "横屏完整信号工作台 · ${artifact.report.analysisProfile}",
            onBack = onBack,
        )
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val sidebarWidth = (maxWidth * 0.31f).coerceIn(220.dp, 340.dp)
            Row(Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.width(sidebarWidth).fillMaxHeight().padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        WorkbenchControlCard("信号工作台") {
                            ChoiceRow(WorkbenchChannel.entries, channel, ::channelLabel) { channel = it }
                            ChoiceRow(WorkbenchSignalStage.entries, signalStage, ::signalStageLabel) {
                                signalStage = it
                            }
                            ToggleButton("接受峰", showPeaks) { showPeaks = !showPeaks }
                            ToggleButton("稳定段", showSegments) { showSegments = !showSegments }
                            ToggleButton("显示反相", invert) { invert = !invert }
                        }
                    }
                    item {
                        WorkbenchControlCard("Review range") {
                            ViewportControls(
                                viewport, total, range, trace.timeSeconds, { viewport = it },
                                onDefaultWindow = {
                                    viewport = viewport.copyViewport().apply {
                                        showWindow(defaultWindow?.startIndex ?: 0, 800, total)
                                    }
                                },
                            )
                        }
                    }
                    item {
                        WorkbenchControlCard("稳定段") {
                            artifact.report.segments.take(16).forEach { segment ->
                                OutlinedButton(
                                    onClick = {
                                        viewport = viewport.copyViewport().apply {
                                            showWindow(
                                                segment.startIndex,
                                                segment.stopIndex - segment.startIndex,
                                                total,
                                            )
                                        }
                                        pane = WorkbenchPane.SIGNAL
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("${segment.index + 1} · ${formatRange(segment.startSeconds, segment.stopSeconds)}")
                                }
                            }
                            if (artifact.report.segments.size > 16) {
                                Text("其余稳定段见诊断页。", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (artifacts.size > 1) {
                        item {
                            WorkbenchControlCard("分析历史") {
                                artifacts.forEach { history ->
                                    OutlinedButton(
                                        onClick = { selectedPath = history.path },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(if (history.path == artifact.path) "✓ ${history.path.fileName}" else history.path.fileName.toString())
                                    }
                                }
                            }
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight().padding(10.dp)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(WorkbenchPane.entries.size) { index ->
                            val value = WorkbenchPane.entries[index]
                            FilledTonalButton(
                                onClick = { pane = value },
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (pane == value) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                ),
                            ) { Text(paneLabel(value)) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    when (pane) {
                        WorkbenchPane.SIGNAL -> {
                            val resolved = resolveChannel(channel, artifact)
                            val values = signalValues(trace, resolved, signalStage)
                            val gesture = Modifier.pointerInput(total) {
                                detectTransformGestures { centroid, pan, zoom, _ ->
                                    viewport = viewport.copyViewport().apply {
                                        applyGesture(
                                            zoom.toDouble(), pan.x.toDouble(),
                                            size.width.toDouble().coerceAtLeast(1.0), centroid.x.toDouble(), total,
                                        )
                                    }
                                }
                            }
                            CompleteSignalChart(
                                series = listOf(
                                    CompleteSignalSeries(
                                        "${signalStageLabel(signalStage)} $resolved",
                                        if (resolved == "RED") Color(0xFFFF5C70) else Color(0xFF45CAFF),
                                        values,
                                    ),
                                ),
                                timeSeconds = trace.timeSeconds,
                                visibleRange = range,
                                breakIndices = trace.breakIndices,
                                stableSegments = artifact.report.segments,
                                peaks = if (showPeaks && signalStage != WorkbenchSignalStage.RAW &&
                                    resolved == artifact.report.metrics.selectedChannel
                                ) artifact.report.peaks.map { it.sampleIndex }.toIntArray() else intArrayOf(),
                                showStableSegments = showSegments,
                                highlightedWindow = selectedWindowIndex?.let(artifact.report.windows::getOrNull),
                                invert = invert,
                                modifier = gesture.weight(1f),
                            )
                            Text(
                                "${formatVisibleRange(trace.timeSeconds, range)} · ${range.count()} / $total 点 · " +
                                    "显示控制不会重写 raw 或 analysis JSON",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        WorkbenchPane.WINDOWS -> WindowAuditPane(
                            artifact = artifact,
                            selectedIndex = selectedWindowIndex,
                            onSelect = { index, window ->
                                selectedWindowIndex = index
                                viewport = viewport.copyViewport().apply {
                                    showWindow(window.startIndex, window.stopIndex - window.startIndex, total)
                                }
                                pane = WorkbenchPane.SIGNAL
                            },
                            modifier = Modifier.weight(1f),
                        )
                        WorkbenchPane.SPECTRUM -> {
                            val resolved = resolveChannel(channel, artifact)
                            val spectrum = OfflineDisplaySpectrum.estimate(
                                if (resolved == "RED") trace.filteredRed else trace.filteredIr,
                                range,
                            )
                            SpectrumPane(spectrum.frequenciesHz, spectrum.power, range, modifier = Modifier.weight(1f))
                        }
                        WorkbenchPane.CYCLE -> CyclePane(artifact, Modifier.weight(1f))
                        WorkbenchPane.DIAGNOSTICS -> DiagnosticsPane(artifact, trace, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun WindowAuditPane(
    artifact: CaptureSessionAnalysisArtifact,
    selectedIndex: Int?,
    onSelect: (Int, OfflinePulseWindow) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        itemsIndexed(artifact.report.windows) { index, window ->
            Card(
                onClick = { onSelect(index, window) },
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        index == selectedIndex -> MaterialTheme.colorScheme.primaryContainer
                        window.accepted -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    },
                ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("#${index + 1}", fontWeight = FontWeight.Bold)
                    Column(Modifier.weight(1f)) {
                        Text("段 ${window.segmentIndex + 1} · ${formatRange(window.startSeconds, window.stopSeconds)}")
                        Text(
                            "${window.usedChannel ?: window.bestChannel} / ${window.polarity ?: "—"} · " +
                                "peak ${formatBpm(window.peakBpm)} · spectral ${formatBpm(window.spectralBpm)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(if (window.accepted) "ACCEPT" else "REJECT", fontWeight = FontWeight.Bold)
                        Text(
                            window.rejectionReason ?: "confidence ${(window.confidence * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpectrumPane(
    frequencies: DoubleArray,
    power: DoubleArray,
    range: IntRange,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("当前可见范围 · Welch-style PSD", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (power.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("当前范围不足 32 个连续滤波样本") }
        } else {
            SimpleMultiLineChart(
                x = frequencies,
                series = listOf(CompleteSignalSeries("PSD", Color(0xFF45CAFF), power)),
                modifier = Modifier.weight(1f),
            )
            val peak = power.indices.maxByOrNull(power::get)
            Text(
                "0.3–8 Hz · 最多 32 段、每段至多 8 s · 当前样本 ${range.first}–${range.last} · " +
                    "主峰 ${peak?.let { "%.3f Hz".format(Locale.ROOT, frequencies[it]) } ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CyclePane(artifact: CaptureSessionAnalysisArtifact, modifier: Modifier = Modifier) {
    val cycle = artifact.report.averageCycle
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("平均 PPG 周期与 95% CI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (cycle.mean.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("没有足够的同段一致周期") }
        } else {
            val upper = DoubleArray(cycle.mean.size) { cycle.mean[it] + cycle.ci95[it] }
            val lower = DoubleArray(cycle.mean.size) { cycle.mean[it] - cycle.ci95[it] }
            SimpleMultiLineChart(
                x = cycle.phase,
                series = listOf(
                    CompleteSignalSeries("mean", Color(0xFF45CAFF), cycle.mean),
                    CompleteSignalSeries("+95% CI", Color(0xFF8BBEFF), upper),
                    CompleteSignalSeries("−95% CI", Color(0xFF8BBEFF), lower),
                ),
                modifier = Modifier.weight(1f),
            )
            Text("${cycle.cycleCount} 个接受周期 · normalized phase 0–1 · CI=1.96×SEM")
        }
    }
}

@Composable
private fun DiagnosticsPane(
    artifact: CaptureSessionAnalysisArtifact,
    trace: CaptureSessionSignalTrace,
    modifier: Modifier = Modifier,
) {
    val report = artifact.report
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { DiagnosticGroup("版本与不可变来源", listOf(
            "raw SHA-256" to report.sourceRawSha256,
            "analysis profile" to report.analysisProfile,
            "algorithm" to report.algorithmVersion,
            "preprocess" to report.preprocessProfile,
            "artifact" to artifact.path.fileName.toString(),
        )) }
        item { DiagnosticGroup("输入完整性", listOf(
            "accepted samples" to report.input.acceptedSampleCount.toString(),
            "raw records" to report.input.rawRecordCount.toString(),
            "decoded / accepted frames" to "${report.input.decodedFrameCount} / ${report.input.acceptedFrameCount}",
            "missing / duplicate / out-of-order" to "${report.input.missingFrameCount} / ${report.input.duplicateFrameCount} / ${report.input.outOfOrderFrameCount}",
            "alignment / pending" to "${report.input.leadingAlignmentByteCount} / ${report.input.pendingDecoderByteCount} B",
            "continuity breaks" to trace.breakIndices.size.toString(),
        )) }
        item { DiagnosticGroup("分析证据", listOf(
            "stable segments" to report.metrics.segmentCount.toString(),
            "accepted windows" to "${report.metrics.acceptedWindowCount} / ${report.metrics.windowCount}",
            "channel / polarity" to "${report.metrics.selectedChannel ?: "—"} / ${report.metrics.selectedPolarity ?: "—"}",
            "HR / spectral" to "${formatBpm(report.metrics.heartRateBpm)} / ${formatBpm(report.metrics.spectralHeartRateBpm)}",
            "confidence / SNR" to "${(report.metrics.confidence * 100).toInt()}% / ${report.metrics.snrDb?.let { "%.2f dB".format(Locale.ROOT, it) } ?: "—"}",
            "rejections" to report.metrics.rejectionCounts.entries.joinToString { "${it.key}=${it.value}" }.ifEmpty { "none" },
        )) }
        items(report.warnings.size) { index ->
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)) {
                Text(report.warnings[index], Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Text(
                "解释边界：峰和稳定段是算法输出，不是医疗标注；缩放、反相、可见性与 stage 只改变显示。IMU 无数据契约，因此不移植。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiagnosticGroup(title: String, values: List<Pair<String, String>>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            values.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun CompleteSignalChart(
    series: List<CompleteSignalSeries>,
    timeSeconds: DoubleArray,
    visibleRange: IntRange,
    modifier: Modifier = Modifier,
    breakIndices: IntArray = intArrayOf(),
    stableSegments: List<OfflineSignalSegment> = emptyList(),
    peaks: IntArray = intArrayOf(),
    showStableSegments: Boolean = false,
    highlightedWindow: OfflinePulseWindow? = null,
    invert: Boolean = false,
) {
    val label = series.joinToString { it.label }
    val background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$label 完整信号，可拖动缩放" },
        shape = RoundedCornerShape(14.dp),
        color = background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Box(Modifier.fillMaxSize().padding(8.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                if (visibleRange.isEmpty() || series.isEmpty()) return@Canvas
                val first = visibleRange.first
                val last = visibleRange.last
                val denominator = max(1, last - first).toFloat()
                fun xFor(index: Int): Float = (index - first).toFloat() / denominator * size.width

                if (showStableSegments) {
                    stableSegments.forEach { segment ->
                        val left = max(first, segment.startIndex)
                        val right = minOf(last + 1, segment.stopIndex)
                        if (right > left) {
                            drawRect(
                                Color(0xFF42BE77).copy(alpha = 0.12f),
                                topLeft = Offset(xFor(left), 0f),
                                size = androidx.compose.ui.geometry.Size(
                                    (xFor(right.coerceAtMost(last)) - xFor(left)).coerceAtLeast(2f),
                                    size.height,
                                ),
                            )
                        }
                    }
                }
                highlightedWindow?.let { window ->
                    val left = max(first, window.startIndex)
                    val right = minOf(last + 1, window.stopIndex)
                    if (right > left) {
                        drawRect(
                            Color(0xFFFFC857).copy(alpha = 0.10f),
                            topLeft = Offset(xFor(left), 0f),
                            size = androidx.compose.ui.geometry.Size(
                                (xFor(right.coerceAtMost(last)) - xFor(left)).coerceAtLeast(2f),
                                size.height,
                            ),
                        )
                    }
                }
                repeat(3) { row ->
                    val y = size.height * (row + 1) / 4f
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
                val plots = series.map { item ->
                    item to LiveWaveformPlotMath.plotRange(
                        values = item.values,
                        visibleRange = visibleRange,
                        maximumPointCount = maxOf(2, (size.width * 2).toInt()),
                    )
                }
                val allMin = plots.minOfOrNull { (_, plot) -> if (invert) -plot.maximum else plot.minimum } ?: -1.0
                val allMax = plots.maxOfOrNull { (_, plot) -> if (invert) -plot.minimum else plot.maximum } ?: 1.0
                val rawSpan = allMax - allMin
                val padding = if (rawSpan > 0.0) rawSpan * 0.08 else max(abs(allMax) * 0.08, 1.0)
                val lower = allMin - padding
                val upper = allMax + padding
                val span = (upper - lower).coerceAtLeast(1e-9)
                fun yFor(value: Double): Float {
                    val shown = if (invert) -value else value
                    return ((upper - shown) / span * size.height).toFloat().coerceIn(0f, size.height)
                }
                plots.forEach { (item, plot) ->
                    if (plot.points.isEmpty()) return@forEach
                    val path = Path()
                    var previousAbsolute = -1
                    var started = false
                    plot.points.forEach { point ->
                        val absolute = point.offset
                        val crossesBreak = previousAbsolute >= 0 && breakIndices.any {
                            it > previousAbsolute && it <= absolute
                        }
                        val x = xFor(absolute)
                        val y = yFor(point.value)
                        if (!started || crossesBreak) {
                            path.moveTo(x, y)
                            started = true
                        } else {
                            path.lineTo(x, y)
                        }
                        previousAbsolute = absolute
                    }
                    drawPath(
                        path,
                        item.color,
                        style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
                val markerValues = series.first().values
                peaks.filter { it in visibleRange && markerValues[it].isFinite() }.forEach { peak ->
                    drawCircle(series.first().color, 3.dp.toPx(), Offset(xFor(peak), yFor(markerValues[peak])))
                }
                breakIndices.filter { it in visibleRange }.forEach { gap ->
                    drawLine(
                        Color(0xFFFF8A65).copy(alpha = 0.75f),
                        Offset(xFor(gap), 0f), Offset(xFor(gap), size.height), 1.dp.toPx(),
                    )
                }
            }
            Column(Modifier.padding(4.dp)) {
                series.forEach { Text(it.label, color = it.color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun SimpleMultiLineChart(
    x: DoubleArray,
    series: List<CompleteSignalSeries>,
    modifier: Modifier = Modifier,
) {
    val values = series.flatMap { it.values.asList() }.filter(Double::isFinite)
    val minimum = values.minOrNull() ?: -1.0
    val maximum = values.maxOrNull() ?: 1.0
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)) {
        Box(Modifier.fillMaxSize().padding(10.dp)) {
            Canvas(Modifier.fillMaxSize()) {
                val span = (maximum - minimum).coerceAtLeast(1e-9)
                series.forEach { item ->
                    val count = minOf(x.size, item.values.size)
                    if (count < 2) return@forEach
                    val path = Path()
                    repeat(count) { index ->
                        val xOffset = index.toFloat() / (count - 1) * size.width
                        val yOffset = ((maximum - item.values[index]) / span * size.height).toFloat()
                        if (index == 0) path.moveTo(xOffset, yOffset) else path.lineTo(xOffset, yOffset)
                    }
                    drawPath(path, item.color, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                series.forEach { Text(it.label, color = it.color, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
private fun ViewportControls(
    viewport: ReplayWaveformViewport,
    total: Int,
    visibleRange: IntRange,
    timeSeconds: DoubleArray,
    onChange: (ReplayWaveformViewport) -> Unit,
    onDefaultWindow: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = {
            onChange(viewport.copyViewport().apply { setZoom(zoomScale * 2.0, total) })
        }) { Text("＋") }
        FilledTonalButton(onClick = {
            onChange(viewport.copyViewport().apply { setZoom(zoomScale / 2.0, total) })
        }) { Text("－") }
        OutlinedButton(onClick = onDefaultWindow) { Text("8 s") }
        OutlinedButton(onClick = { onChange(viewport.copyViewport().apply { reset() }) }) { Text("全幅") }
    }
    Text(
        "×${"%.1f".format(Locale.ROOT, viewport.zoomScale)} · ${formatVisibleRange(timeSeconds, visibleRange)}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun <T> ChoiceRow(
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        values.forEach { value ->
            FilledTonalButton(
                onClick = { onSelect(value) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (value == selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 7.dp),
            ) { Text(label(value), style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun ToggleButton(label: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(if (selected) "✓ $label" else label)
    }
}

@Composable
private fun WorkbenchControlCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun WorkbenchHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = onBack) { Text("‹ 退出全屏") }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("RAW → ZERO-PHASE → PEAKS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun ReplayWaveformViewport.copyViewport() = ReplayWaveformViewport(zoomScale, visibleStart)

private fun resolveChannel(
    channel: WorkbenchChannel,
    artifact: CaptureSessionAnalysisArtifact,
): String = when (channel) {
    WorkbenchChannel.SELECTED -> artifact.report.metrics.selectedChannel ?: "RED"
    WorkbenchChannel.RED -> "RED"
    WorkbenchChannel.IR -> "IR"
}

private fun signalValues(
    trace: CaptureSessionSignalTrace,
    channel: String,
    stage: WorkbenchSignalStage,
): DoubleArray = when {
    stage == WorkbenchSignalStage.RAW && channel == "RED" -> trace.rawRed
    stage == WorkbenchSignalStage.RAW -> trace.rawIr
    channel == "RED" -> trace.filteredRed
    else -> trace.filteredIr
}

private fun channelLabel(value: WorkbenchChannel): String = when (value) {
    WorkbenchChannel.SELECTED -> "已选"
    WorkbenchChannel.RED -> "RED"
    WorkbenchChannel.IR -> "IR"
}

private fun signalStageLabel(value: WorkbenchSignalStage): String = when (value) {
    WorkbenchSignalStage.RAW -> "RAW"
    WorkbenchSignalStage.BANDPASS -> "ZERO-PHASE"
    WorkbenchSignalStage.PEAKS -> "PEAKS"
}

private fun paneLabel(value: WorkbenchPane): String = when (value) {
    WorkbenchPane.SIGNAL -> "Workbench"
    WorkbenchPane.WINDOWS -> "PPG windows"
    WorkbenchPane.SPECTRUM -> "Spectrum"
    WorkbenchPane.CYCLE -> "Cycle"
    WorkbenchPane.DIAGNOSTICS -> "Diagnostics"
}

private fun formatVisibleRange(time: DoubleArray, range: IntRange): String {
    if (range.isEmpty() || time.isEmpty()) return "—"
    return formatRange(time[range.first], time[range.last])
}

private fun formatRange(start: Double, stop: Double): String =
    "%.2f–%.2f s".format(Locale.ROOT, start, stop)

private fun formatBpm(value: Double?): String =
    value?.let { "%.1f bpm".format(Locale.ROOT, it) } ?: "—"
