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
import com.example.ppgcollector_android.core.signal.PpgDisplayTransform
import com.example.ppgcollector_android.core.signal.OfflineDisplaySpectrum
import com.example.ppgcollector_android.core.signal.OfflinePulseWindow
import com.example.ppgcollector_android.core.signal.OfflineSignalSegment
import com.example.ppgcollector_android.data.session.CaptureSessionAnalysisArtifact
import com.example.ppgcollector_android.data.session.CaptureSessionSignalTrace
import com.example.ppgcollector_android.data.session.CaptureMetricTimelinePoint
import com.example.ppgcollector_android.data.session.CaptureMetricTimelineSource
import com.example.ppgcollector_android.data.session.OfflineBloodPressurePreviewFactory
import com.example.ppgcollector_android.data.session.OfflineBloodPressurePreviewPoint
import com.example.ppgcollector_android.data.session.ReplayWaveformViewport
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

private enum class ReplaySignalStage { RAW, ZERO_PHASE, FIXED }
private enum class WorkbenchPane { SIGNAL, WINDOWS, SPECTRUM, CYCLE, DIAGNOSTICS }
private enum class WorkbenchChannel { SELECTED, RED, IR }
private enum class WorkbenchSignalStage { RAW, ZERO_PHASE, FIXED, PEAKS }

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
    var showGapMarkers by remember(trace) {
        mutableStateOf(SessionGapMarkerPolicy.defaultVisible(trace.breakIndices.size, total))
    }
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
    if (trace.bloodPressureEvents.isNotEmpty()) {
        Text(
            "参考血压：${trace.bloodPressureEvents.size} 组 · marker 使用 dialog-open PPG source time",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
    ChoiceRow(
        values = ReplaySignalStage.entries,
        selected = stage,
        label = {
            when (it) {
                ReplaySignalStage.RAW -> "RAW"
                ReplaySignalStage.ZERO_PHASE -> "ZERO"
                ReplaySignalStage.FIXED -> "FIXED"
            }
        },
        onSelect = { stage = it },
    )
    ToggleButton("显示缺帧标记", showGapMarkers) { showGapMarkers = !showGapMarkers }
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
    val red = remember(trace, stage) {
        when (stage) {
            ReplaySignalStage.RAW -> PpgDisplayTransform.rawPeakUp(trace.rawRed)
            ReplaySignalStage.ZERO_PHASE -> trace.filteredRed
            ReplaySignalStage.FIXED -> trace.fixedLagRed
        }
    }
    val ir = remember(trace, stage) {
        when (stage) {
            ReplaySignalStage.RAW -> PpgDisplayTransform.rawPeakUp(trace.rawIr)
            ReplaySignalStage.ZERO_PHASE -> trace.filteredIr
            ReplaySignalStage.FIXED -> trace.fixedLagIr
        }
    }
    val prefix = when (stage) {
        ReplaySignalStage.RAW -> "RAW"
        ReplaySignalStage.ZERO_PHASE -> "ZERO 0.5–12"
        ReplaySignalStage.FIXED -> "FIXED 0.5–12"
    }
    CompleteSignalChart(
        series = listOf(CompleteSignalSeries("$prefix RED", Color(0xFFD74747), red)),
        timeSeconds = trace.timeSeconds,
        visibleRange = range,
        pathBreakIndices = SessionGapMarkerPolicy.pathBreaksForRawStage(
            stage == ReplaySignalStage.RAW,
            trace.breakIndices,
        ),
        gapMarkerIndices = if (showGapMarkers) trace.breakIndices else intArrayOf(),
        stableSegments = artifact?.report?.segments.orEmpty(),
        showStableSegments = true,
        modifier = gesture.height(136.dp),
    )
    CompleteSignalChart(
        series = listOf(CompleteSignalSeries("$prefix IR", Color(0xFF3478C8), ir)),
        timeSeconds = trace.timeSeconds,
        visibleRange = range,
        pathBreakIndices = SessionGapMarkerPolicy.pathBreaksForRawStage(
            stage == ReplaySignalStage.RAW,
            trace.breakIndices,
        ),
        gapMarkerIndices = if (showGapMarkers) trace.breakIndices else intArrayOf(),
        stableSegments = artifact?.report?.segments.orEmpty(),
        showStableSegments = true,
        modifier = gesture.height(136.dp),
    )
    AlignedMetricTimelineChart(
        trace = trace,
        visibleRange = range,
        modifier = Modifier.height(146.dp),
    )
    Text(
        "ZERO=${trace.preprocessProfile}；FIXED=${trace.fixedLagProfile}，约 1 s 实时延迟、" +
            "离线按 accepted source cursor 对齐。二者均为 0.5–12 Hz，缺口压缩但不插值；RAW 保留断点证据。",
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
    var stage by remember(artifact.path) { mutableStateOf(WorkbenchSignalStage.ZERO_PHASE) }
    val total = trace.timeSeconds.size
    var showGapMarkers by remember(trace, artifact.path) {
        mutableStateOf(SessionGapMarkerPolicy.defaultVisible(trace.breakIndices.size, total))
    }
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
    ToggleButton("显示缺帧标记", showGapMarkers) { showGapMarkers = !showGapMarkers }
    ViewportControls(
        viewport, total, range, trace.timeSeconds, { viewport = it },
        onDefaultWindow = {
            viewport = viewport.copyViewport().apply { showWindow(defaultStart, 800, total) }
        },
    )
    val resolved = resolveChannel(channel, artifact)
    val values = remember(trace, resolved, stage) { signalValues(trace, resolved, stage) }
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
        pathBreakIndices = SessionGapMarkerPolicy.pathBreaksForRawStage(
            stage == WorkbenchSignalStage.RAW,
            trace.breakIndices,
        ),
        gapMarkerIndices = if (showGapMarkers) trace.breakIndices else intArrayOf(),
        stableSegments = artifact.report.segments,
        peaks = if (stage == WorkbenchSignalStage.PEAKS && resolved == artifact.report.metrics.selectedChannel) {
            artifact.report.peaks.map { it.sampleIndex }.toIntArray()
        } else {
            intArrayOf()
        },
        showStableSegments = true,
        modifier = gesture.height(180.dp),
    )
    AlignedMetricTimelineChart(
        trace = trace,
        visibleRange = range,
        modifier = Modifier.height(146.dp),
    )
    Text(
        "PPG 与指标共享 source 时间窗；全程 ${trace.timeSeconds.size} 点。切换 stage/视窗不会改写 sidecar 或 detector 输出。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private data class AlignedMetricSeries(
    val label: String,
    val color: Color,
    val points: List<Pair<Int, Double>>,
    val unavailableSourceIndices: IntArray = intArrayOf(),
)

@Composable
private fun AlignedMetricTimelineChart(
    trace: CaptureSessionSignalTrace,
    visibleRange: IntRange,
    modifier: Modifier = Modifier,
) {
    val dividerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
    val series = remember(
        trace.metricTimeline,
        trace.timeSeconds,
        trace.metricUnavailableSourceIndices,
    ) {
        buildPersistedMetricSeries(
            trace.metricTimeline,
            trace.timeSeconds,
            trace.metricUnavailableSourceIndices,
        )
    }
    val visibleSeries = remember(series, visibleRange) {
        series.map { item ->
            item.copy(
                points = item.points.pointsIn(visibleRange),
                unavailableSourceIndices = item.unavailableSourceIndices
                    .filter { it in visibleRange }
                    .toIntArray(),
            )
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(
            when (trace.metricTimelineEvidence.source) {
                CaptureMetricTimelineSource.RECORDED_1_HZ -> "录制期 1 Hz 指标 · 与 PPG 共享 accepted cursor"
                CaptureMetricTimelineSource.OFFLINE_RECOMPUTED -> "离线重算指标 · 使用 accepted-order 修复信号"
                CaptureMetricTimelineSource.UNAVAILABLE -> "指标不可用"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            trace.metricTimelineEvidence.detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (series.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("当前会话没有可对齐的有效指标", style = MaterialTheme.typography.bodySmall)
            }
            return@Column
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .semantics {
                    contentDescription = "${series.joinToString { it.label }} 指标时间轴，与 PPG 共享缩放范围"
                },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        ) {
            Box(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 5.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val laneHeight = size.height / series.size.toFloat()
                    val first = visibleRange.first
                    val last = visibleRange.last
                    val denominator = max(1, last - first).toFloat()
                    fun xFor(index: Int): Float = (index - first).toFloat() / denominator * size.width
                    visibleSeries.forEachIndexed { lane, item ->
                        val top = lane * laneHeight
                        val bottom = top + laneHeight
                        if (lane > 0) {
                            drawLine(
                                dividerColor,
                                Offset(0f, top),
                                Offset(size.width, top),
                                1.dp.toPx(),
                            )
                        }
                        val visible = item.points
                        if (visible.isEmpty()) return@forEachIndexed
                        val minimum = visible.minOf { it.second }
                        val maximum = visible.maxOf { it.second }
                        val padding = max(abs(maximum - minimum) * 0.12, max(abs(maximum), 1.0) * 0.04)
                        val lower = minimum - padding
                        val upper = maximum + padding
                        val span = (upper - lower).coerceAtLeast(1e-9)
                        val path = Path()
                        var previousSourceIndex = -1
                        visible.forEach { point ->
                            val x = xFor(point.first)
                            val y = bottom - 4.dp.toPx() -
                                ((point.second - lower) / span * (laneHeight - 8.dp.toPx())).toFloat()
                            if (metricPathBreakBefore(
                                    previousSourceIndex,
                                    point.first,
                                    item.unavailableSourceIndices,
                                )
                            ) {
                                path.moveTo(x, y)
                            } else {
                                path.lineTo(x, y)
                            }
                            previousSourceIndex = point.first
                        }
                        drawPath(
                            path,
                            item.color,
                            style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                        if (visible.size == 1) {
                            val point = visible.single()
                            val x = xFor(point.first)
                            val y = bottom - 4.dp.toPx() -
                                ((point.second - lower) / span * (laneHeight - 8.dp.toPx())).toFloat()
                            drawCircle(item.color, 3.dp.toPx(), Offset(x, y))
                        }
                    }
                }
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                    series.forEach { item ->
                        Text(item.label, color = item.color, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

private fun buildPersistedMetricSeries(
    points: List<CaptureMetricTimelinePoint>,
    timeSeconds: DoubleArray,
    globalUnavailableSourceIndices: IntArray = intArrayOf(),
): List<AlignedMetricSeries> {
    fun sourceIndex(point: CaptureMetricTimelinePoint): Int? =
        point.sourceSampleIndex.takeIf { it in 0 until timeSeconds.size.toLong() }?.toInt()

    fun values(selector: (CaptureMetricTimelinePoint) -> Double?): List<Pair<Int, Double>> =
        points.mapNotNull { point ->
            val value = selector(point)?.takeIf(Double::isFinite) ?: return@mapNotNull null
            sourceIndex(point)?.let { it to value }
        }
    fun unavailable(selector: (CaptureMetricTimelinePoint) -> Double?): IntArray =
        (globalUnavailableSourceIndices.asSequence().asIterable() + points.mapNotNull { point ->
            if (selector(point)?.takeIf(Double::isFinite) != null) null else sourceIndex(point)
        }).distinct().sorted().toIntArray()
    return listOf(
        AlignedMetricSeries(
            "HR bpm",
            Color(0xFFD74747),
            values(CaptureMetricTimelinePoint::heartRateBpm),
            unavailable(CaptureMetricTimelinePoint::heartRateBpm),
        ),
        AlignedMetricSeries(
            "PI %",
            Color(0xFF7B61C9),
            values(CaptureMetricTimelinePoint::perfusionIndexPercent),
            unavailable(CaptureMetricTimelinePoint::perfusionIndexPercent),
        ),
        AlignedMetricSeries(
            "录制 SQI",
            Color(0xFF2E8B57),
            values(CaptureMetricTimelinePoint::signalQuality),
            unavailable(CaptureMetricTimelinePoint::signalQuality),
        ),
        AlignedMetricSeries(
            "RR",
            Color(0xFFB26A00),
            values(CaptureMetricTimelinePoint::ratioOfRatios),
            unavailable(CaptureMetricTimelinePoint::ratioOfRatios),
        ),
    ).filter { it.points.isNotEmpty() }
}

/** Keeps metric lines from implying continuity across rejected or missing epochs. */
internal fun metricPathBreakBefore(
    previousSourceIndex: Int,
    currentSourceIndex: Int,
    unavailableSourceIndices: IntArray,
): Boolean {
    if (previousSourceIndex < 0 || currentSourceIndex <= previousSourceIndex) return true
    if (currentSourceIndex - previousSourceIndex > 150) return true
    return unavailableSourceIndices.any { it > previousSourceIndex && it <= currentSourceIndex }
}

@Composable
internal fun ReferenceBloodPressureComparisonPanel(
    trace: CaptureSessionSignalTrace,
    artifact: CaptureSessionAnalysisArtifact?,
    modifier: Modifier = Modifier,
) {
    val preview = remember(trace.timeSeconds, trace.metricTimeline) {
        OfflineBloodPressurePreviewFactory.create(
            timeSeconds = trace.timeSeconds,
            breakIndices = intArrayOf(),
            metricTimeline = trace.metricTimeline,
        )
    }
    val total = trace.timeSeconds.size
    val anchorTime = trace.bloodPressureEvents.firstOrNull()?.reference?.sourceTimeSeconds
        ?: preview.points.firstOrNull()?.sourceTimeSeconds
    val anchorIndex = anchorTime?.let { nearestTimeIndex(trace.timeSeconds, it) } ?: 0
    var viewport by remember(trace) {
        mutableStateOf(
            ReplayWaveformViewport().apply {
                showWindow((anchorIndex - 400).coerceAtLeast(0), 800, total)
            },
        )
    }
    val range = viewport.visibleRange(total)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "参考 BP ${trace.bloodPressureEvents.size} 组 · 占位序列 ${preview.points.size} 点 · shared source time",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ViewportControls(
            viewport = viewport,
            total = total,
            visibleRange = range,
            timeSeconds = trace.timeSeconds,
            onChange = { viewport = it },
            onDefaultWindow = {
                viewport = viewport.copyViewport().apply {
                    showWindow((anchorIndex - 400).coerceAtLeast(0), 800, total)
                }
            },
        )
        CompleteSignalChart(
            series = listOf(CompleteSignalSeries("ZERO RED 0.5–12", Color(0xFFD74747), trace.filteredRed)),
            timeSeconds = trace.timeSeconds,
            visibleRange = range,
            pathBreakIndices = intArrayOf(),
            stableSegments = artifact?.report?.segments.orEmpty(),
            showStableSegments = true,
            modifier = Modifier.height(92.dp),
        )
        BloodPressureTimelineChart(
            referenceEvents = trace.bloodPressureEvents,
            placeholderPoints = preview.points,
            timeSeconds = trace.timeSeconds,
            visibleRange = range,
            breakIndices = intArrayOf(),
            modifier = Modifier.height(132.dp),
        )
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
        ) {
            Text(
                "未接入血压预测算法：图中的 120/80 mmHg 为 1 Hz 对齐占位序列，不是模型输出，" +
                    "不写入会话、不参与误差或临床评估。实时页继续显示横杠。",
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun BloodPressureTimelineChart(
    referenceEvents: List<com.example.ppgcollector_android.data.session.ManualBloodPressureEvent>,
    placeholderPoints: List<OfflineBloodPressurePreviewPoint>,
    timeSeconds: DoubleArray,
    visibleRange: IntRange,
    breakIndices: IntArray,
    modifier: Modifier = Modifier,
) {
    val reference = remember(referenceEvents, timeSeconds) {
        referenceEvents.mapNotNull { event ->
            nearestTimeIndex(timeSeconds, event.reference.sourceTimeSeconds)?.let { index -> index to event }
        }.sortedBy { it.first }
    }
    val placeholder = remember(placeholderPoints, timeSeconds) {
        placeholderPoints.mapNotNull { point ->
            nearestTimeIndex(timeSeconds, point.sourceTimeSeconds)?.let { index -> index to point }
        }.sortedBy { it.first }
    }
    val visibleReference = remember(reference, visibleRange) { reference.pointsIn(visibleRange) }
    val visiblePlaceholder = remember(placeholder, visibleRange) { placeholder.pointsIn(visibleRange) }
    val predictedSbp = Color(0xFF7B61C9)
    val predictedDbp = Color(0xFF00897B)
    val referenceSbp = Color(0xFFD32F2F)
    val referenceDbp = Color(0xFFF57C00)
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("参考 SBP", color = referenceSbp, style = MaterialTheme.typography.labelSmall)
            Text("参考 DBP", color = referenceDbp, style = MaterialTheme.typography.labelSmall)
            Text("占位 SBP", color = predictedSbp, style = MaterialTheme.typography.labelSmall)
            Text("占位 DBP", color = predictedDbp, style = MaterialTheme.typography.labelSmall)
        }
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "参考血压与未接入模型的占位血压时间轴，共享 PPG source time"
                },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        ) {
            Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                if (visibleRange.isEmpty()) return@Canvas
                val first = visibleRange.first
                val last = visibleRange.last
                val denominator = max(1, last - first).toFloat()
                fun xFor(index: Int): Float = (index - first).toFloat() / denominator * size.width
                val values = buildList {
                    visiblePlaceholder.forEach { add(it.second.systolicMmHg); add(it.second.diastolicMmHg) }
                    visibleReference.forEach { add(it.second.systolicMmHg.toDouble()); add(it.second.diastolicMmHg.toDouble()) }
                }
                val minimum = (values.minOrNull() ?: 60.0) - 10.0
                val maximum = (values.maxOrNull() ?: 140.0) + 10.0
                val span = (maximum - minimum).coerceAtLeast(1.0)
                fun yFor(value: Double): Float =
                    ((maximum - value) / span * size.height).toFloat().coerceIn(0f, size.height)
                repeat(3) { row ->
                    val y = size.height * (row + 1) / 4f
                    drawLine(
                        gridColor,
                        Offset(0f, y),
                        Offset(size.width, y),
                        1.dp.toPx(),
                    )
                }
                fun drawPlaceholder(selector: (OfflineBloodPressurePreviewPoint) -> Double, color: Color) {
                    val path = Path()
                    var started = false
                    var previousIndex = -1
                    visiblePlaceholder.forEach { (index, point) ->
                        val crossesGap = previousIndex >= 0 && breakIndices.any { it > previousIndex && it <= index }
                        val x = xFor(index)
                        val y = yFor(selector(point))
                        if (!started || crossesGap) path.moveTo(x, y) else path.lineTo(x, y)
                        started = true
                        previousIndex = index
                    }
                    drawPath(path, color, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
                }
                drawPlaceholder(OfflineBloodPressurePreviewPoint::systolicMmHg, predictedSbp)
                drawPlaceholder(OfflineBloodPressurePreviewPoint::diastolicMmHg, predictedDbp)
                visibleReference.forEach { (index, event) ->
                    val x = xFor(index)
                    val systolicY = yFor(event.systolicMmHg.toDouble())
                    val diastolicY = yFor(event.diastolicMmHg.toDouble())
                    drawLine(referenceSbp.copy(alpha = 0.5f), Offset(x, systolicY), Offset(x, diastolicY), 1.dp.toPx())
                    drawCircle(referenceSbp, 4.dp.toPx(), Offset(x, systolicY))
                    drawCircle(referenceDbp, 4.dp.toPx(), Offset(x, diastolicY))
                }
            }
        }
    }
}

private fun nearestTimeIndex(timeSeconds: DoubleArray, target: Double): Int? {
    if (timeSeconds.isEmpty() || !target.isFinite()) return null
    var low = 0
    var high = timeSeconds.lastIndex
    while (low <= high) {
        val middle = (low + high) ushr 1
        when {
            timeSeconds[middle] < target -> low = middle + 1
            timeSeconds[middle] > target -> high = middle - 1
            else -> return middle
        }
    }
    val right = low.coerceIn(0, timeSeconds.lastIndex)
    val left = (right - 1).coerceAtLeast(0)
    return if (abs(timeSeconds[left] - target) <= abs(timeSeconds[right] - target)) left else right
}

/** Returns a bounded, allocation-free view over already source-sorted points. */
private fun <T> List<Pair<Int, T>>.pointsIn(range: IntRange): List<Pair<Int, T>> {
    if (isEmpty() || range.isEmpty()) return emptyList()
    fun lowerBound(target: Int): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (this[middle].first < target) low = middle + 1 else high = middle
        }
        return low
    }
    val start = lowerBound(range.first)
    val stop = if (range.last == Int.MAX_VALUE) size else lowerBound(range.last + 1)
    return if (start >= stop) emptyList() else subList(start, stop)
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
                    Text(detail.signalError ?: "请先生成一次离线分析并等待完整信号重放。")
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
    var signalStage by remember(artifact.path) { mutableStateOf(WorkbenchSignalStage.ZERO_PHASE) }
    var showPeaks by remember(artifact.path) { mutableStateOf(true) }
    var showSegments by remember(artifact.path) { mutableStateOf(true) }
    var showGapMarkers by remember(trace, artifact.path) {
        mutableStateOf(SessionGapMarkerPolicy.defaultVisible(trace.breakIndices.size, total))
    }
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
                            ToggleButton("显示缺帧标记", showGapMarkers) { showGapMarkers = !showGapMarkers }
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
                            val values = remember(trace, resolved, signalStage) {
                                signalValues(trace, resolved, signalStage)
                            }
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
                                pathBreakIndices = SessionGapMarkerPolicy.pathBreaksForRawStage(
                                    signalStage == WorkbenchSignalStage.RAW,
                                    trace.breakIndices,
                                ),
                                gapMarkerIndices = if (showGapMarkers) trace.breakIndices else intArrayOf(),
                                stableSegments = artifact.report.segments,
                                peaks = if (showPeaks && signalStage != WorkbenchSignalStage.RAW &&
                                    resolved == artifact.report.metrics.selectedChannel
                                ) artifact.report.peaks.map { it.sampleIndex }.toIntArray() else intArrayOf(),
                                showStableSegments = showSegments,
                                highlightedWindow = selectedWindowIndex?.let(artifact.report.windows::getOrNull),
                                invert = invert && signalStage != WorkbenchSignalStage.RAW,
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
                            val spectrumSignal = remember(trace, resolved, signalStage) {
                                signalValues(trace, resolved, signalStage)
                            }
                            val spectrum = remember(spectrumSignal, range) {
                                OfflineDisplaySpectrum.estimate(spectrumSignal, range)
                            }
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
            "analysis signal" to (report.input.analysisSignalProfile ?: "legacy / 未记录"),
            "repair gaps / samples" to "${report.input.repairGapCount ?: "—"} / ${report.input.repairInputSampleCount ?: "—"}",
            "metric source" to trace.metricTimelineEvidence.source.name,
            "metric sidecar" to trace.metricTimelineEvidence.persistedState.name,
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
    pathBreakIndices: IntArray = intArrayOf(),
    gapMarkerIndices: IntArray = intArrayOf(),
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
                if (gapMarkerIndices.isNotEmpty()) {
                    var markerIndex = gapMarkerIndices.lowerBound(first)
                    var previousX = Float.NEGATIVE_INFINITY
                    while (markerIndex < gapMarkerIndices.size) {
                        val gap = gapMarkerIndices[markerIndex]
                        if (gap > last) break
                        val x = xFor(gap)
                        if (x - previousX >= 1.5f) {
                            drawLine(
                                Color(0xFFFF8A65).copy(alpha = 0.22f),
                                Offset(x, 0f), Offset(x, size.height), 1.dp.toPx(),
                            )
                            previousX = x
                        }
                        markerIndex += 1
                    }
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
                        val crossesBreak = previousAbsolute >= 0 &&
                            pathBreakIndices.hasValueIn(previousAbsolute + 1, absolute)
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
            }
            Column(Modifier.padding(4.dp)) {
                series.forEach { Text(it.label, color = it.color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

private fun IntArray.lowerBound(value: Int): Int {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (this[middle] < value) low = middle + 1 else high = middle
    }
    return low
}

private fun IntArray.hasValueIn(first: Int, last: Int): Boolean {
    if (isEmpty() || last < first) return false
    val index = lowerBound(first)
    return index < size && this[index] <= last
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
            Text("RAW → ZERO / FIXED 0.5–12 → PEAKS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
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
    stage == WorkbenchSignalStage.RAW && channel == "RED" ->
        PpgDisplayTransform.rawPeakUp(trace.rawRed)
    stage == WorkbenchSignalStage.RAW -> PpgDisplayTransform.rawPeakUp(trace.rawIr)
    stage == WorkbenchSignalStage.FIXED && channel == "RED" -> trace.fixedLagRed
    stage == WorkbenchSignalStage.FIXED -> trace.fixedLagIr
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
    WorkbenchSignalStage.ZERO_PHASE -> "ZERO"
    WorkbenchSignalStage.FIXED -> "FIXED"
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
