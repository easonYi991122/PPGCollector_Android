package com.example.ppgcollector_android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import com.example.ppgcollector_android.core.signal.LiveMetricSnapshot
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
    val effectiveMode = when (displayMode) {
        LiveWaveformDisplayMode.FIXED_LAG -> when {
            fixedLagAvailable -> LiveWaveformDisplayMode.FIXED_LAG
            causalAvailable -> LiveWaveformDisplayMode.CAUSAL
            else -> LiveWaveformDisplayMode.RAW
        }
        LiveWaveformDisplayMode.CAUSAL -> if (causalAvailable) {
            LiveWaveformDisplayMode.CAUSAL
        } else {
            LiveWaveformDisplayMode.RAW
        }
        LiveWaveformDisplayMode.RAW -> LiveWaveformDisplayMode.RAW
    }
    val rawDisplay = remember(
        waveform.generation,
        waveform.publicationSequence,
        waveform.red,
        waveform.ir,
    ) {
        PpgDisplayTransform.rawPeakUpForPlot(waveform.red) to
            PpgDisplayTransform.rawPeakUpForPlot(waveform.ir)
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
    val modeDescription = when (effectiveMode) {
        LiveWaveformDisplayMode.CAUSAL -> "因果滤波 0.5–12 Hz，取负 raw 后滤波"
        LiveWaveformDisplayMode.FIXED_LAG ->
            "fixed-lag 0.5–12 Hz，约 ${waveform.fixedLagLatencySamples / 100.0} 秒延迟"
        LiveWaveformDisplayMode.RAW -> "原始 ADC 仅显示取负并移除可视化线性趋势"
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(if (density == CaptureContentDensity.COMPACT) 8.dp else 12.dp)) {
        WaveformModeSelector(
            selected = effectiveMode,
            causalAvailable = causalAvailable,
            fixedLagAvailable = fixedLagAvailable,
            onSelected = onDisplayModeChange,
        )
        WaveformPanel(
            label = "RED",
            color = Color(0xFFD32F2F),
            values = red,
            publicationSequence = waveform.publicationSequence,
            excludedLeadingSampleCount = settlingSamples,
            semanticsDetail = modeDescription,
            compact = density == CaptureContentDensity.COMPACT,
        )
        WaveformPanel(
            label = "IR",
            color = Color(0xFF1565C0),
            values = ir,
            publicationSequence = waveform.publicationSequence,
            excludedLeadingSampleCount = settlingSamples,
            semanticsDetail = modeDescription,
            compact = density == CaptureContentDensity.COMPACT,
        )
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
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LiveMetricsPanel(metrics = metrics, density = density)
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
                LiveWaveformDisplayMode.FIXED_LAG -> fixedLagAvailable
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
                        LiveWaveformDisplayMode.FIXED_LAG -> "FIXED 固定延迟 0.5 到 12 赫兹"
                    }
                }
            if (mode == selected) {
                FilledTonalButton(
                    onClick = { onSelected(mode) },
                    enabled = enabled,
                    modifier = buttonModifier,
                ) { Text(mode.compactLabel, maxLines = 1, style = MaterialTheme.typography.labelMedium) }
            } else {
                OutlinedButton(
                    onClick = { onSelected(mode) },
                    enabled = enabled,
                    modifier = buttonModifier,
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
    semanticsDetail: String,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val verticalRange = remember(values, publicationSequence, excludedLeadingSampleCount) {
        LiveWaveformScaleMath.verticalRange(values, excludedLeadingSampleCount)
    }
    val plot = remember(values, publicationSequence) {
        LiveWaveformPlotMath.plot(values, maximumPointCount = 1_600)
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
            Box(modifier = Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                Spacer(
                    Modifier.matchParentSize().drawWithCache {
                        val scale = verticalRange
                        val path = if (scale != null && plot.points.isNotEmpty()) {
                            val span = (scale.upper - scale.lower).coerceAtLeast(1e-9)
                            Path().apply {
                                plot.points.forEachIndexed { index, point ->
                                    val x = if (values.size <= 1) size.width / 2f else {
                                        point.offset.toFloat() / (values.size - 1).toFloat() * size.width
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
                                    style = Stroke(1.75.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
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
        }
    }
}

private fun waveformDetailText(
    waveform: LiveWaveformSnapshot,
    effectiveMode: LiveWaveformDisplayMode,
    settlingSamples: Int,
): String = when (effectiveMode) {
    LiveWaveformDisplayMode.CAUSAL ->
        "${waveform.displayCausalProfile ?: "causal-display-0.5-12hz-0.1"} · gap reset · " +
            if (settlingSamples > 0) "浅色区为滤波 settling" else "滤波状态稳定"
    LiveWaveformDisplayMode.FIXED_LAG ->
        "${waveform.fixedLagProfile ?: "fixed-lag-fir-0.5-12hz-0.1"} · 源窗口 " +
            "${waveform.fixedLagSourceSampleStartIndex ?: "—"}–${waveform.fixedLagSourceSampleEndIndex ?: "—"} · " +
            "约 ${"%.2f".format(Locale.ROOT, waveform.fixedLagLatencySamples / 100.0)} 秒延迟"
    LiveWaveformDisplayMode.RAW ->
        "100 Hz accepted RAW · 显示取负并移除可视化线性趋势；落盘仍为原始 ADC"
}

internal fun waveformContentDescription(label: String, sampleCount: Int, detail: String? = null): String =
    buildString {
        append("$label 波形，$sampleCount 个样本")
        if (!detail.isNullOrBlank()) append("，$detail")
    }

@Immutable
private data class MetricTileModel(
    val compactLabel: String,
    val detailedLabel: String,
    val value: String,
    val state: String,
    val detail: String,
    val valid: Boolean,
    val provisional: Boolean,
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
            val fitsFiveColumns = maxWidth >= 340.dp && fontScale <= 1.3f
            if (fitsFiveColumns) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    tiles.forEach { tile ->
                        MetricTile(
                            model = tile,
                            compact = density == CaptureContentDensity.COMPACT,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    tiles.forEach { tile ->
                        MetricTile(
                            model = tile,
                            compact = density == CaptureContentDensity.COMPACT,
                            modifier = Modifier.width(if (density == CaptureContentDensity.COMPACT) 96.dp else 152.dp),
                        )
                    }
                }
            }
        }
        if (density == CaptureContentDensity.DETAILED) {
            Text(
                "RR 仅为 Red/IR 诊断比值；SQI 为暂定评分。SpO₂ 与计算血压缺少正式标定。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MetricTile(model: MetricTileModel, compact: Boolean, modifier: Modifier = Modifier) {
    val statusColor = when {
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
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier.padding(if (compact) 7.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            Text(
                if (compact) model.compactLabel else model.detailedLabel,
                style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
            Text(
                model.value,
                style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
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
        return listOf("HR" to "心率", "RR" to "RR（Red/IR）", "PI" to "PI（RED AC/DC）", "SQI" to "信号质量 SQI", "BP" to "计算血压")
            .map { (compact, detailed) ->
                MetricTileModel(compact, detailed, "—", "等待 8 秒窗口", "暂无来源", false, false)
            }
    }
    return listOf(
        metricTile("HR", "心率", metrics.heartRateBpm, "bpm") { "%.0f".format(Locale.ROOT, it) },
        metricTile("RR", "RR（Red/IR）", metrics.ratioOfRatios, "") { "%.3f".format(Locale.ROOT, it) },
        metricTile("PI", "PI（RED AC/DC）", metrics.perfusionIndex, "%") { "%.2f".format(Locale.ROOT, it) },
        metricTile("SQI", "信号质量 SQI", metrics.signalQuality, "") { "%.2f".format(Locale.ROOT, it) },
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
