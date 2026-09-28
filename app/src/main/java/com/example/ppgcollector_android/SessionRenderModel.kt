package com.example.ppgcollector_android

import com.example.ppgcollector_android.core.signal.LiveWaveformPlotMath
import com.example.ppgcollector_android.core.signal.OfflineDisplaySpectrum
import com.example.ppgcollector_android.core.signal.OfflineSpectrum
import com.example.ppgcollector_android.data.session.CaptureSessionSignalTrace
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

internal data class SessionRenderSeries(val channelStage: String, val values: DoubleArray, val negate: Boolean = false)
internal data class SessionRenderKey(
    val source: Any,
    val series: List<SessionRenderSeries>,
    val sourceIndices: LongArray?,
    val range: IntRange,
    val pixelWidth: Int,
    val breaks: IntArray = EMPTY_INDICES,
    val gaps: IntArray = EMPTY_INDICES,
    val gapSources: LongArray? = null,
    val peaks: IntArray = EMPTY_INDICES,
) {
    companion object { val EMPTY_INDICES = intArrayOf() }
}
internal data class SessionRenderPoint(val sourceIndex: Long, val value: Double, val startsSegment: Boolean)
internal data class SessionRenderPlot(val points: List<SessionRenderPoint>, val minimum: Double, val maximum: Double)
internal data class SessionRenderSnapshot(
    val key: SessionRenderKey,
    val plots: List<SessionRenderPlot>,
    val gapSources: List<Long>,
    val peaks: List<SessionRenderPoint>,
)
internal data class SessionSpectrumKey(val source: Any, val channelStage: String, val values: DoubleArray, val range: IntRange)

/** One latest snapshot per visible chart; input arrays are immutable for their source generation. */
internal class SessionRenderModel(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onCompute: (String) -> Unit = {},
) {
    private val generation = AtomicLong()
    private var cached: SessionRenderSnapshot? = null
    private var spectrumCache: Pair<SessionSpectrumKey, OfflineSpectrum>? = null

    suspend fun render(key: SessionRenderKey): SessionRenderSnapshot? {
        val token = generation.incrementAndGet()
        synchronized(this) { cached?.takeIf { it.key == key } }?.let { return it }
        val result = withContext(dispatcher) {
            val context = currentCoroutineContext()
            fun check() = context.ensureActive()
            check()
            onCompute("plot")
            fun source(index: Int): Long = key.sourceIndices?.getOrNull(index) ?: index.toLong()
            val sourceRange = key.sourceIndices?.let {
                it.lowerBound(key.range.first.toLong()) until it.lowerBound(key.range.last.toLong() + 1)
            } ?: key.range
            val budget = (key.pixelWidth.coerceIn(1, 8192) * 2).coerceAtLeast(2)
            val plots = key.series.map { series ->
                check()
                val plot = LiveWaveformPlotMath.plotRange(series.values, sourceRange, budget, ::check)
                var previousOffset = -1
                val points = plot.points.map { point ->
                    val starts = previousOffset < 0 || key.breaks.containsBetween(previousOffset + 1, point.offset)
                    previousOffset = point.offset
                    SessionRenderPoint(source(point.offset), if (series.negate) -point.value else point.value, starts)
                }
                SessionRenderPlot(points, if (series.negate) -plot.maximum else plot.minimum,
                    if (series.negate) -plot.minimum else plot.maximum)
            }
            val gaps = ArrayList<Long>()
            var previousPixel = -1L
            val span = (key.range.last.toLong() - key.range.first).coerceAtLeast(1)
            fun addGap(cursor: Long) {
                if (cursor < key.range.first || cursor > key.range.last) return
                val pixel = (cursor - key.range.first) * key.pixelWidth / span
                if (pixel != previousPixel) { gaps += cursor; previousPixel = pixel }
            }
            if (key.gapSources != null) {
                val indices = key.gapSources
                var index = indices.lowerBound(key.range.first.toLong())
                while (index < indices.size && indices[index] <= key.range.last) { check(); addGap(indices[index++]) }
            } else {
                key.gaps.forEach { check(); addGap(source(it)) }
            }
            val peaks = ArrayList<SessionRenderPoint>()
            val firstSeries = key.series.firstOrNull()
            previousPixel = -1
            key.peaks.forEach { index ->
                check()
                val value = firstSeries?.values?.getOrNull(index)
                val cursor = source(index)
                if (value != null && value.isFinite() && cursor in key.range.first.toLong()..key.range.last.toLong()) {
                    val pixel = (cursor - key.range.first) * key.pixelWidth / span
                    if (pixel != previousPixel) {
                        peaks += SessionRenderPoint(cursor, if (firstSeries.negate) -value else value, true)
                        previousPixel = pixel
                    }
                }
            }
            check()
            SessionRenderSnapshot(key, plots, gaps, peaks)
        }
        currentCoroutineContext().ensureActive()
        return synchronized(this) {
            if (generation.get() != token) null else result.also { cached = it }
        }
    }

    suspend fun spectrum(key: SessionSpectrumKey): OfflineSpectrum? {
        val token = generation.incrementAndGet()
        synchronized(this) { spectrumCache?.takeIf { it.first == key }?.second }?.let { return it }
        val result = withContext(dispatcher) {
            val context = currentCoroutineContext()
            context.ensureActive()
            onCompute("spectrum")
            cancellableDisplaySpectrum(key.values, key.range) { context.ensureActive() }
        }
        currentCoroutineContext().ensureActive()
        return synchronized(this) {
            if (generation.get() != token) null else result.also { spectrumCache = key to it }
        }
    }
}

/** Same bounded Welch segments as OfflineDisplaySpectrum; cancellation between each short DFT. */
internal fun cancellableDisplaySpectrum(values: DoubleArray, range: IntRange, check: () -> Unit): OfflineSpectrum {
    if (values.isEmpty() || range.isEmpty()) return OfflineSpectrum()
    val start = range.first.coerceIn(0, values.lastIndex)
    val stop = (range.last.toLong() + 1).coerceIn(start + 1L, values.size.toLong()).toInt()
    val length = minOf(800, stop - start)
    if (length < 32) return OfflineSpectrum()
    val hop = maxOf(1, length / 2)
    val regularCount = (stop - start - length) / hop + 1
    val finalStart = stop - length
    val count = regularCount + if (start + (regularCount - 1) * hop == finalStart) 0 else 1
    val chosen = if (count <= 32) (0 until count).toList() else
        List(32) { (it * (count - 1).toDouble() / 31).toInt() }.distinct()
    var frequencies = doubleArrayOf()
    var power = doubleArrayOf()
    var accepted = 0
    for (candidate in chosen) {
        check()
        val offset = if (candidate < regularCount) start + candidate * hop else finalStart
        val spectrum = OfflineDisplaySpectrum.estimate(values, offset until offset + length)
        if (spectrum.power.isEmpty()) continue
        if (power.isEmpty()) { power = DoubleArray(spectrum.power.size); frequencies = spectrum.frequenciesHz }
        spectrum.power.forEachIndexed { index, value -> power[index] += value }
        accepted++
    }
    check()
    if (accepted == 0) return OfflineSpectrum()
    power.indices.forEach { power[it] /= accepted }
    return OfflineSpectrum(frequencies, power)
}

internal fun CaptureSessionSignalTrace.reviewSourceKey(): Any = sourceIdentity ?: rawRed
internal fun CaptureSessionSignalTrace.reviewSampleCount(): Int =
    totalAcceptedSamples.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

private fun LongArray.lowerBound(value: Long): Int {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (this[middle] < value) low = middle + 1 else high = middle
    }
    return low
}
private fun IntArray.containsBetween(first: Int, last: Int): Boolean {
    var low = 0
    var high = size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (this[middle] < first) low = middle + 1 else high = middle
    }
    return low < size && this[low] <= last
}

/** At most two extrema per pixel; each retained point still belongs to its original valid segment. */
internal fun boundedMetricSegments(
    points: List<Pair<Int, Double>>,
    unavailable: IntArray,
    cadence: Int,
    range: IntRange,
    pixelWidth: Int,
    check: () -> Unit = {},
): List<List<Pair<Int, Double>>> {
    if (points.isEmpty() || range.isEmpty()) return emptyList()
    val width = pixelWidth.coerceIn(1, 8192)
    val span = (range.last.toLong() - range.first + 1).coerceAtLeast(1)
    val output = mutableListOf<MutableList<Pair<Int, Double>>>()
    var lastOutputSegment = -1
    var segment = -1
    var previousSource = -1
    var previousPixel = -1L
    var minimum = -1
    var maximum = -1
    var minimumSegment = -1
    var maximumSegment = -1
    fun emit(index: Int, id: Int) {
        if (id != lastOutputSegment) { output += mutableListOf<Pair<Int, Double>>(); lastOutputSegment = id }
        output.last().add(points[index])
    }
    fun flush() {
        if (minimum < 0) return
        if (minimum <= maximum) {
            emit(minimum, minimumSegment)
            if (maximum != minimum) emit(maximum, maximumSegment)
        } else { emit(maximum, maximumSegment); emit(minimum, minimumSegment) }
        minimum = -1; maximum = -1
    }
    points.forEachIndexed { index, point ->
        check()
        if (metricPathBreakBefore(previousSource, point.first, unavailable, cadence)) segment++
        previousSource = point.first
        val pixel = (point.first.toLong() - range.first) * width / span
        if (pixel != previousPixel) { flush(); previousPixel = pixel }
        if (minimum < 0 || point.second < points[minimum].second) { minimum = index; minimumSegment = segment }
        if (maximum < 0 || point.second > points[maximum].second) { maximum = index; maximumSegment = segment }
    }
    flush()
    return output
}

internal class SessionReviewControls<T>(stage: T, viewport: com.example.ppgcollector_android.data.session.ReplayWaveformViewport,
    showGapMarkers: Boolean) {
    val stage = androidx.compose.runtime.mutableStateOf(stage)
    val viewport = androidx.compose.runtime.mutableStateOf(viewport)
    val showGapMarkers = androidx.compose.runtime.mutableStateOf(showGapMarkers)
}

@androidx.compose.runtime.Composable
internal fun <T> rememberSessionReviewControls(trace: CaptureSessionSignalTrace, initialStage: T,
    defaultStart: Int = 0, artifactKey: Any? = null): SessionReviewControls<T> =
    androidx.compose.runtime.remember(trace.reviewSourceKey(), artifactKey) {
        SessionReviewControls(initialStage,
            com.example.ppgcollector_android.data.session.ReplayWaveformViewport().apply {
                showWindow(defaultStart, 800, trace.reviewSampleCount())
            }, SessionGapMarkerPolicy.defaultVisible(trace.breakIndices.size, trace.reviewSampleCount()))
    }
