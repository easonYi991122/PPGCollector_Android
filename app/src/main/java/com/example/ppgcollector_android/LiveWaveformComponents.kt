package com.example.ppgcollector_android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ppgcollector_android.core.ble.LiveStreamDiagnostics
import com.example.ppgcollector_android.core.signal.LiveMetricSnapshot
import com.example.ppgcollector_android.core.signal.LiveRawWaveformAxisRuntime
import com.example.ppgcollector_android.core.signal.LiveWaveformPlotMath
import com.example.ppgcollector_android.core.signal.LiveWaveformScaleMath
import com.example.ppgcollector_android.core.signal.LiveWaveformSnapshot
import com.example.ppgcollector_android.core.signal.MetricResult
import com.example.ppgcollector_android.core.signal.PpgDisplayTransform
import java.util.Locale

@Composable
internal fun LiveWaveformAndMetrics(
    waveform: LiveWaveformSnapshot,
    metrics: LiveMetricSnapshot?,
    streamDiagnostics: LiveStreamDiagnostics,
    axisSourceToken: String,
    displayMode: LiveWaveformDisplayMode,
    density: CaptureContentDensity,
    onDisplayModeChange: (LiveWaveformDisplayMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val displayCausal = if (
        waveform.displayCausalRed.size == waveform.red.size &&
        waveform.displayCausalIr.size == waveform.ir.size &&
        waveform.displayCausalRed.isNotEmpty()
    ) {
        waveform.displayCausalRed to waveform.displayCausalIr
    } else {
        waveform.causalRed to waveform.causalIr
    }
    val causalAvailable = displayCausal.first.size == waveform.red.size &&
        displayCausal.second.size == waveform.ir.size && displayCausal.first.isNotEmpty()
    val fixedLagAvailable = waveform.fixedLagRed.size == waveform.fixedLagIr.size &&
        waveform.fixedLagRed.isNotEmpty()
    val modeResolution = resolveLiveWaveformMode(
        requested = displayMode,
        causalAvailable = causalAvailable,
        fixedLagAvailable = fixedLagAvailable,
    )
    val effectiveMode = modeResolution.effective
    val rawDisplay = remember(
        waveform.generation,
        waveform.publicationSequence,
        waveform.red,
        waveform.ir,
    ) {
        // Live RAW must be time-invariant: recomputing a fitted trend for every
        // growing/sliding viewport makes already received samples visibly move.
        PpgDisplayTransform.liveRawPeakUp(waveform.red) to
            PpgDisplayTransform.liveRawPeakUp(waveform.ir)
    }
    val red = when (effectiveMode) {
        LiveWaveformDisplayMode.CAUSAL -> displayCausal.first
        LiveWaveformDisplayMode.FIXED_LAG -> waveform.fixedLagRed
        LiveWaveformDisplayMode.RAW -> rawDisplay.first
    }
    val ir = when (effectiveMode) {
        LiveWaveformDisplayMode.CAUSAL -> displayCausal.second
        LiveWaveformDisplayMode.FIXED_LAG -> waveform.fixedLagIr
        LiveWaveformDisplayMode.RAW -> rawDisplay.second
    }
    val settlingSamples = if (effectiveMode == LiveWaveformDisplayMode.CAUSAL) {
        waveform.settlingSampleCount
    } else {
        0
    }
    val ppgSegmentBreaks = displaySegmentBreakIndices(waveform, effectiveMode)
    val axisResetToken = "$axisSourceToken:${effectiveMode.name}"
    val modeDescription = when (effectiveMode) {
        LiveWaveformDisplayMode.CAUSAL -> "因果滤波 0.5–12 Hz，取负 raw 后滤波"
        LiveWaveformDisplayMode.FIXED_LAG ->
            "fixed-lag 0.5–12 Hz，约 ${waveform.fixedLagLatencySamples / 100.0} 秒延迟"
        LiveWaveformDisplayMode.RAW -> "原始 ADC 仅显示取负"
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(if (density == CaptureContentDensity.COMPACT) 8.dp else 12.dp)) {
        WaveformModeSelector(
            selected = modeResolution.requested,
            causalAvailable = causalAvailable,
            fixedLagAvailable = fixedLagAvailable,
            onSelected = onDisplayModeChange,
        )
        if (modeResolution.isWarming) {
            Text(
                "FIXED 正在建立约 ${waveform.fixedLagLatencySamples / 100.0} 秒的右侧上下文；" +
                    "准备完成后自动切换。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        WaveformPanel(
            label = "RED",
            color = Color(0xFFD32F2F),
            values = red,
            publicationSequence = waveform.publicationSequence,
            excludedLeadingSampleCount = settlingSamples,
            segmentBreakSampleIndices = ppgSegmentBreaks,
            stableRawAxis = effectiveMode == LiveWaveformDisplayMode.RAW,
            axisResetToken = axisResetToken,
            semanticsDetail = modeDescription,
            compact = density == CaptureContentDensity.COMPACT,
        )
        WaveformPanel(
            label = "IR",
            color = Color(0xFF1565C0),
            values = ir,
            publicationSequence = waveform.publicationSequence,
            excludedLeadingSampleCount = settlingSamples,
            segmentBreakSampleIndices = ppgSegmentBreaks,
            stableRawAxis = effectiveMode == LiveWaveformDisplayMode.RAW,
            axisResetToken = axisResetToken,
            semanticsDetail = modeDescription,
            compact = density == CaptureContentDensity.COMPACT,
        )
        if (waveform.ecg.isNotEmpty()) {
            WaveformPanel(
                label = "ECG",
                color = Color(0xFF6A1B9A),
                values = waveform.ecg,
                publicationSequence = waveform.publicationSequence,
                excludedLeadingSampleCount = 0,
                segmentBreakSampleIndices = waveform.segmentBreakSampleIndices.map { breakIndex ->
                    if (waveform.red.isEmpty()) breakIndex
                    else (breakIndex.toDouble() * waveform.ecg.size / waveform.red.size)
                        .toInt()
                },
                stableRawAxis = false,
                axisResetToken = "$axisSourceToken:ECG",
                semanticsDetail = "ADS1292R 每 5 点均值显示，未取负；落盘仍为原始 ADC",
                compact = density == CaptureContentDensity.COMPACT,
            )
        }
        if (density == CaptureContentDensity.DETAILED) {
            Text(
                waveformDetailText(waveform, effectiveMode, settlingSamples),
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
            if (streamDiagnostics.decodedFrameCount > 0L) {
                Text(
                    liveStreamDiagnosticText(streamDiagnostics),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LiveMetricsPanel(metrics = metrics, density = density)
    }
}

internal fun displaySegmentBreakIndices(
    waveform: LiveWaveformSnapshot,
    mode: LiveWaveformDisplayMode,
): List<Int> {
    if (mode != LiveWaveformDisplayMode.FIXED_LAG) {
        return waveform.segmentBreakSampleIndices.toList()
    }
    val rawStart = waveform.sourceSampleStartIndex ?: return emptyList()
    val fixedStart = waveform.fixedLagSourceSampleStartIndex ?: return emptyList()
    val fixedEnd = waveform.fixedLagSourceSampleEndIndex ?: return emptyList()
    return buildList {
        waveform.segmentBreakSampleIndices.forEach { rawOffset ->
            val sourceIndex = rawStart + rawOffset
            if (sourceIndex in fixedStart..fixedEnd) add((sourceIndex - fixedStart).toInt())
        }
    }
}

@Composable
private fun WaveformModeSelector(
    selected: LiveWaveformDisplayMode,
    causalAvailable: Boolean,
    fixedLagAvailable: Boolean,
    onSelected: (LiveWaveformDisplayMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().semantics { contentDescription = "实时波形滤波方式" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LiveWaveformDisplayMode.entries.forEach { mode ->
            val enabled = when (mode) {
                LiveWaveformDisplayMode.RAW -> true
                LiveWaveformDisplayMode.CAUSAL -> causalAvailable
                // FIXED is a valid user choice during its initial right-context
                // warmup; availability controls the plotted fallback, not input.
                LiveWaveformDisplayMode.FIXED_LAG -> true
            }
            val buttonModifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .semantics {
                    this.selected = mode == selected
                    role = androidx.compose.ui.semantics.Role.RadioButton
                    contentDescription = when (mode) {
                        LiveWaveformDisplayMode.RAW -> "RAW 原始显示"
                        LiveWaveformDisplayMode.CAUSAL -> "CAUSAL 因果 0.5 到 12 赫兹"
                        LiveWaveformDisplayMode.FIXED_LAG -> if (fixedLagAvailable) {
                            "FIXED 固定延迟 0.5 到 12 赫兹"
                        } else {
                            "FIXED 固定延迟 0.5 到 12 赫兹，正在准备"
                        }
                    }
                }
            if (mode == selected) {
                FilledTonalButton(
                    onClick = { onSelected(mode) },
                    enabled = enabled,
                    modifier = buttonModifier,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) { Text(mode.compactLabel, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
            } else {
                OutlinedButton(
                    onClick = { onSelected(mode) },
                    enabled = enabled,
                    modifier = buttonModifier,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) { Text(mode.compactLabel, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@Composable
private fun WaveformPanel(
    label: String,
    color: Color,
    values: DoubleArray,
    publicationSequence: Long,
    excludedLeadingSampleCount: Int,
    segmentBreakSampleIndices: List<Int>,
    stableRawAxis: Boolean,
    axisResetToken: String,
    semanticsDetail: String,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val candidateVerticalRange = remember(values, publicationSequence, excludedLeadingSampleCount) {
        LiveWaveformScaleMath.verticalRange(values, excludedLeadingSampleCount)
    }
    val rawAxisRuntime = remember(axisResetToken, stableRawAxis) {
        LiveRawWaveformAxisRuntime()
    }
    val verticalRange = remember(
        candidateVerticalRange,
        publicationSequence,
        rawAxisRuntime,
        stableRawAxis,
    ) {
        if (stableRawAxis) rawAxisRuntime.update(candidateVerticalRange) else candidateVerticalRange
    }
    val plot = remember(values, publicationSequence) {
        LiveWaveformPlotMath.plot(values, maximumPointCount = 320)
    }
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val settlingColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.Bold)
            Text(
                values.lastOrNull()?.let { "%.0f".format(Locale.ROOT, it) } ?: "—",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (compact) 78.dp else 92.dp)
                .semantics {
                    contentDescription = waveformContentDescription(label, values.size, semanticsDetail)
                },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Spacer(
                        Modifier.matchParentSize().drawWithCache {
                            val scale = verticalRange
                            val path = if (scale != null && plot.points.isNotEmpty()) {
                                val span = (scale.upper - scale.lower).coerceAtLeast(1e-9)
                                Path().apply {
                                    plot.points.forEachIndexed { index, point ->
                                        val x = if (values.size <= 1) size.width / 2f else {
                                            point.offset.toFloat() /
                                                (values.size - 1).toFloat() * size.width
                                        }
                                        val y = ((scale.upper - point.value) / span * size.height)
                                            .toFloat().coerceIn(0f, size.height)
                                        if (index == 0) moveTo(x, y) else lineTo(x, y)
                                    }
                                }
                            } else null
                            onDrawBehind {
                                repeat(3) { index ->
                                    val y = size.height * (index + 1) / 4f
                                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                                }
                                if (excludedLeadingSampleCount > 0 && values.isNotEmpty()) {
                                    drawRect(
                                        settlingColor,
                                        size = Size(
                                            width = size.width * excludedLeadingSampleCount
                                                .coerceAtMost(values.size).toFloat() / values.size.toFloat(),
                                            height = size.height,
                                        ),
                                    )
                                }
                                if (path != null) {
                                    drawPath(
                                        path,
                                        color,
                                        style = Stroke(
                                            1.75.dp.toPx(),
                                            cap = StrokeCap.Round,
                                            join = StrokeJoin.Round,
                                        ),
                                    )
                                }
                            }
                        },
                    )
                    if (values.isEmpty()) {
                        Text(
                            "等待 CUP 样本",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val markerFractions = remember(values.size, segmentBreakSampleIndices) {
                    segmentBreakMarkerFractions(segmentBreakSampleIndices, values.size)
                }
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .semantics {
                            contentDescription = if (markerFractions.isEmpty()) {
                                "$label 无序号中断"
                            } else {
                                "$label 序号中断 ${markerFractions.size} 处，标记位于波形下方"
                            }
                        }
                        .drawWithCache {
                            onDrawBehind {
                                markerFractions.forEach { fraction ->
                                    val x = fraction * size.width
                                    drawLine(
                                        color.copy(alpha = 0.55f),
                                        Offset(x, 0f),
                                        Offset(x, size.height),
                                        strokeWidth = 1.dp.toPx(),
                                    )
                                }
                            }
                        },
                )
            }
        }
    }
}

private fun waveformDetailText(
    waveform: LiveWaveformSnapshot,
    effectiveMode: LiveWaveformDisplayMode,
    settlingSamples: Int,
): String = when (effectiveMode) {
    LiveWaveformDisplayMode.CAUSAL ->
        "${waveform.displayCausalProfile ?: "causal-display-0.5-12hz-0.1"} · 序号中断在图下标记 · " +
            if (settlingSamples > 0) "浅色区为滤波 settling" else "滤波状态稳定"
    LiveWaveformDisplayMode.FIXED_LAG ->
        "${waveform.fixedLagProfile ?: "fixed-lag-fir-0.5-12hz-0.1"} · 源窗口 " +
            "${waveform.fixedLagSourceSampleStartIndex ?: "—"}–${waveform.fixedLagSourceSampleEndIndex ?: "—"} · " +
            "约 ${"%.2f".format(Locale.ROOT, waveform.fixedLagLatencySamples / 100.0)} 秒延迟"
    LiveWaveformDisplayMode.RAW ->
        "100 Hz accepted RAW · 显示仅取负；落盘仍为原始 ADC"
}

internal fun segmentBreakMarkerFractions(
    segmentBreakSampleIndices: List<Int>,
    sampleCount: Int,
): List<Float> {
    if (sampleCount <= 1) return emptyList()
    return segmentBreakSampleIndices.distinct().sorted().map { breakIndex ->
        breakIndex.coerceIn(0, sampleCount - 1).toFloat() / (sampleCount - 1).toFloat()
    }
}

internal data class LiveStreamDiagnosticRates(
    val missingFrameRate: Double?,
    val abnormalFrameRate: Double?,
)

internal fun liveStreamDiagnosticRates(diagnostics: LiveStreamDiagnostics): LiveStreamDiagnosticRates {
    val decoded = diagnostics.decodedFrameCount.coerceAtLeast(0L).toDouble()
    val missing = diagnostics.estimatedMissingFrameCount.coerceAtLeast(0L).toDouble()
    val invalid = diagnostics.decoderInvalidFrameCount.coerceAtLeast(0L).toDouble()
    val duplicate = diagnostics.duplicateFrameCount.coerceAtLeast(0L).toDouble()
    val outOfOrder = diagnostics.outOfOrderFrameCount.coerceAtLeast(0L).toDouble()
    val missingDenominator = decoded + missing
    val abnormalDenominator = decoded + missing + invalid
    return LiveStreamDiagnosticRates(
        missingFrameRate = if (missingDenominator > 0.0) {
            (missing / missingDenominator).coerceIn(0.0, 1.0)
        } else {
            null
        },
        abnormalFrameRate = if (abnormalDenominator > 0.0) {
            ((missing + duplicate + outOfOrder + invalid) / abnormalDenominator)
                .coerceIn(0.0, 1.0)
        } else {
            null
        },
    )
}

private fun formatDiagnosticRate(value: Double?): String =
    value?.let { String.format(Locale.ROOT, "%.2f%%", it * 100.0) } ?: "—"

internal fun liveStreamDiagnosticText(diagnostics: LiveStreamDiagnostics): String =
    liveStreamDiagnosticRates(diagnostics).let { rates ->
        "链路：帧 ${diagnostics.decodedFrameCount} · seq ${diagnostics.lastSequenceNumber ?: "—"}" +
        "/Δ${diagnostics.lastSequenceStep ?: "—"} · gap ${diagnostics.gapEventCount}" +
        "/缺 ${diagnostics.estimatedMissingFrameCount} · 重 ${diagnostics.duplicateFrameCount}" +
        "/乱 ${diagnostics.outOfOrderFrameCount} · 解码弃 ${diagnostics.decoderDiscardedByteCount} B" +
        "/坏 ${diagnostics.decoderInvalidFrameCount} · 缺帧率 ${formatDiagnosticRate(rates.missingFrameRate)}" +
        "/异常帧率 ${formatDiagnosticRate(rates.abnormalFrameRate)}" +
        " · App 丢块 ${diagnostics.appDroppedChunkCount}"
    }

internal fun waveformContentDescription(label: String, sampleCount: Int, detail: String? = null): String =
    buildString {
        append("$label 波形，$sampleCount 个样本")
        if (!detail.isNullOrBlank()) append("，$detail")
    }

/** Compact SQI captions omit the trailing score already shown as the tile value. */
internal fun compactMetricCaption(text: String): String =
    text.replace(TRAILING_SCORE_IN_PARENS, "").trim()

internal fun compactMetricCaptionStyle(base: androidx.compose.ui.text.TextStyle): androidx.compose.ui.text.TextStyle =
    base.copy(fontSize = 10.sp, lineHeight = 12.sp)

private val TRAILING_SCORE_IN_PARENS = Regex("""\s*\(\d+\.\d{2}\)\s*$""")

@Immutable
private data class MetricTileModel(
    val compactLabel: String,
    val detailedLabel: String,
    val value: String,
    val state: String,
    val detail: String,
    val valid: Boolean,
    val provisional: Boolean,
    val accentColorHex: String? = null,
)

@Composable
private fun LiveMetricsPanel(
    metrics: LiveMetricSnapshot?,
    density: CaptureContentDensity,
    modifier: Modifier = Modifier,
) {
    val tiles = remember(metrics) { metricTiles(metrics) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (density == CaptureContentDensity.DETAILED) {
            Text("实时指标", style = MaterialTheme.typography.titleMedium)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val fontScale = LocalDensity.current.fontScale
            val columns = CaptureUiPolicy.compactMetricColumns(maxWidth.value, fontScale)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tiles.chunked(columns).forEach { row -> MetricTileRow(row) }
            }
        }
        if (density == CaptureContentDensity.DETAILED) {
            Text(
                tiles.joinToString(" · ") { "${it.compactLabel} ${it.state}" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                tiles.joinToString("\n") { "${it.compactLabel}：${it.detail}" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 5,
            )
            Text(
                "综合 SQI 仅用于实时提示，CSV sqi 仍保持原有口径；SpO₂ 与计算血压缺少正式标定。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetricTileRow(tiles: List<MetricTileModel>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        tiles.forEach { tile ->
            MetricTile(
                model = tile,
                compact = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MetricTile(model: MetricTileModel, compact: Boolean, modifier: Modifier = Modifier) {
    val accent = model.accentColorHex?.let { hex ->
        runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()
    }
    val statusColor = when {
        accent != null -> accent
        !model.valid -> MaterialTheme.colorScheme.onSurfaceVariant
        model.provisional -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.tertiary
    }
    Surface(
        modifier = modifier
            .heightIn(min = if (compact) 68.dp else 118.dp)
            .semantics {
                contentDescription = "${model.detailedLabel} ${model.value}，${model.state}"
            },
        shape = RoundedCornerShape(12.dp),
        color = if (accent != null) {
            accent.copy(alpha = 0.18f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        },
    ) {
        Column(
            modifier = Modifier
                .then(if (compact) Modifier.fillMaxSize() else Modifier)
                .padding(if (compact) 6.dp else 12.dp),
            verticalArrangement = if (compact) {
                Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
            } else {
                Arrangement.spacedBy(3.dp)
            },
            horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            Text(
                if (compact) model.compactLabel else model.detailedLabel,
                modifier = if (compact) Modifier.fillMaxWidth() else Modifier,
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                textAlign = if (compact) TextAlign.Center else TextAlign.Start,
                maxLines = 1,
            )
            Text(
                model.value,
                modifier = if (compact) Modifier.fillMaxWidth() else Modifier,
                style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (accent != null) statusColor else Color.Unspecified,
                textAlign = if (compact) TextAlign.Center else TextAlign.Start,
                maxLines = 1,
            )
            if (compact && accent != null) {
                Text(
                    compactMetricCaption(model.state),
                    modifier = Modifier.fillMaxWidth(),
                    style = compactMetricCaptionStyle(MaterialTheme.typography.labelSmall),
                    color = statusColor,
                    textAlign = TextAlign.Center,
                    softWrap = true,
                )
            }
            if (!compact) {
                Text(model.state, style = MaterialTheme.typography.bodySmall, color = statusColor)
                Text(
                    model.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

private fun metricTiles(metrics: LiveMetricSnapshot?): List<MetricTileModel> {
    if (metrics == null) {
        return liveMetricCompactOrder.zip(
            listOf("心率", "RR（Red/IR）", "PI（RED AC/DC）", "综合 SQI", "计算血压"),
        )
            .map { (compact, detailed) ->
                MetricTileModel(compact, detailed, "—", "等待 8 秒窗口", "暂无来源", false, false)
            }
    }
    return listOf(
        metricTile("HR", "心率", metrics.heartRateBpm, "bpm") { "%.0f".format(Locale.ROOT, it) },
        metricTile("RR", "RR（Red/IR）", metrics.ratioOfRatios, "") { "%.3f".format(Locale.ROOT, it) },
        metricTile("PI", "PI（RED AC/DC）", metrics.perfusionIndex, "%") { "%.2f".format(Locale.ROOT, it) },
        MetricTileModel(
            compactLabel = "SQI",
            detailedLabel = "综合 SQI",
            value = metrics.comboSqi.score?.let { "%.2f".format(Locale.ROOT, it) } ?: "—",
            state = metrics.comboSqi.text,
            detail = "状态 ${metrics.comboSqi.state.name.lowercase(Locale.ROOT)} · combo_sqi_v4.4.2",
            valid = metrics.comboSqi.score != null,
            provisional = false,
            accentColorHex = metrics.comboSqi.colorHex,
        ),
        MetricTileModel(
            compactLabel = "BP",
            detailedLabel = "计算血压",
            value = "—",
            state = metrics.bloodPressure.unavailableReason?.message ?: "未提供模型",
            detail = "未校准，不输出估计值",
            valid = false,
            provisional = false,
        ),
    )
}

private fun metricTile(
    compactLabel: String,
    detailedLabel: String,
    metric: MetricResult<Double>,
    suffix: String,
    formatter: (Double) -> String,
): MetricTileModel {
    val valid = metric.value != null && metric.isValid
    val value = if (valid) {
        formatter(metric.value!!) + if (suffix.isBlank()) "" else " $suffix"
    } else {
        "—"
    }
    val state = when {
        !valid -> metric.unavailableReason?.message ?: "无效"
        metric.isProvisional -> "暂定评分"
        else -> "有效"
    }
    return MetricTileModel(
        compactLabel = compactLabel,
        detailedLabel = detailedLabel,
        value = value,
        state = state,
        detail = "源 ${metric.sourceSampleIndex ?: "—"} · ${metric.algorithmVersion}",
        valid = valid,
        provisional = metric.isProvisional,
    )
}
