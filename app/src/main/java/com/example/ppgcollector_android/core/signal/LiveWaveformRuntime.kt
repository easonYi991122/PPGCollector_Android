package com.example.ppgcollector_android.core.signal

import com.example.ppgcollector_android.core.protocol.CupDecodedFrameEvent
import com.example.ppgcollector_android.core.protocol.CupSequenceEvent
import java.time.Instant
import kotlin.math.min

/**
 * Ownership-transferred read-only publication. Array instances are copied by the producer and
 * must never be mutated after construction; consumers may retain them until the next publication.
 */
data class LiveWaveformSnapshot(
    val generation: Long = 0,
    val publicationSequence: Long = 0,
    val acceptedSampleCount: Long = 0,
    val sourceSampleStartIndex: Long? = null,
    val sourceSampleEndIndex: Long? = null,
    val measuredAt: Instant? = null,
    val red: DoubleArray = doubleArrayOf(),
    val ir: DoubleArray = doubleArrayOf(),
    val causalRed: DoubleArray = doubleArrayOf(),
    val causalIr: DoubleArray = doubleArrayOf(),
    val preprocessProfile: String? = null,
    /** Causal display path: polarity-flipped raw -> 0.5–12 Hz. */
    val displayCausalRed: DoubleArray = doubleArrayOf(),
    val displayCausalIr: DoubleArray = doubleArrayOf(),
    val displayCausalProfile: String? = null,
    val continuousSampleCount: Long = 0,
    val metricWarmupSampleCount: Int = 800,
    val settlingSampleCount: Int = 0,
    val fixedLagRed: DoubleArray = doubleArrayOf(),
    val fixedLagIr: DoubleArray = doubleArrayOf(),
    val fixedLagSourceSampleStartIndex: Long? = null,
    val fixedLagSourceSampleEndIndex: Long? = null,
    val fixedLagLatencySamples: Int = 0,
    val fixedLagProfile: String? = null,
)

data class WaveformVerticalRange(
    val lower: Double,
    val upper: Double,
)

data class WaveformBucket(
    val minimum: Double,
    val maximum: Double,
)

data class WaveformPlotPoint(
    val offset: Int,
    val value: Double,
)

data class WaveformPlot(
    val points: List<WaveformPlotPoint>,
    val minimum: Double,
    val maximum: Double,
)

/** Python live-GUI parity for autoscaling without hiding settling samples. */
object LiveWaveformScaleMath {
    fun verticalRange(
        values: DoubleArray,
        excludedLeadingSampleCount: Int = 0,
        paddingRatio: Double = 0.08,
    ): WaveformVerticalRange? {
        if (values.isEmpty()) return null
        val start = if (excludedLeadingSampleCount > 0 && values.size > excludedLeadingSampleCount) {
            excludedLeadingSampleCount.coerceAtMost(values.lastIndex)
        } else {
            0
        }
        var minimum = Double.POSITIVE_INFINITY
        var maximum = Double.NEGATIVE_INFINITY
        for (index in start until values.size) {
            val value = values[index]
            if (!value.isFinite()) continue
            minimum = minOf(minimum, value)
            maximum = maxOf(maximum, value)
        }
        if (!minimum.isFinite() || !maximum.isFinite()) return null
        var span = maximum - minimum
        if (span <= 0.0) span = maxOf(kotlin.math.abs(minimum) * 0.05, 1.0)
        val padding = span * paddingRatio.coerceAtLeast(0.0)
        return WaveformVerticalRange(minimum - padding, maximum + padding)
    }
}

/**
 * Bounded live waveform ring and wall-clock publisher. The publisher emits
 * at most one snapshot per due poll; delayed polls advance from `now` so a
 * stalled worker never emits a burst of historical frames.
 */
class LiveWaveformSnapshotScheduler(
    private val sampleRateHz: Int = 100,
    private val windowSampleCount: Int = 800,
    refreshRateHz: Int = 5,
) {
    init {
        require(sampleRateHz > 0)
        require(windowSampleCount > 0)
        require(refreshRateHz in 1..60)
        require(sampleRateHz % refreshRateHz == 0)
    }

    private val redRing = DoubleArray(windowSampleCount)
    private val irRing = DoubleArray(windowSampleCount)
    private var ringStart = 0
    private var ringSize = 0
    private var nextWriteSampleIndex = 0L
    private var acceptedSampleCount = 0L
    private var generation = 0L
    private var publicationSequence = 0L
    private var nextPublishNanos: Long? = null
    private val refreshIntervalNanos = 1_000_000_000L / refreshRateHz.toLong()

    fun ingest(
        decodedFrames: List<CupDecodedFrameEvent>,
        acceptedSampleStartIndex: Long,
        measuredAt: Instant,
        nowNanos: Long,
    ): LiveWaveformSnapshot? {
        if (acceptedSampleStartIndex != nextWriteSampleIndex) {
            resetContinuity(acceptedSampleStartIndex)
        }
        decodedFrames.filter { it.isAccepted }.forEach { decoded ->
            if (decoded.sequenceEvent is CupSequenceEvent.Gap) {
                resetContinuity(nextWriteSampleIndex)
            }
            decoded.frame.samples.forEach { sample ->
                append(sample.red.toDouble(), sample.ir.toDouble())
                nextWriteSampleIndex++
                acceptedSampleCount++
            }
        }
        if (ringSize == 0) return null
        if (nextPublishNanos == null) nextPublishNanos = nowNanos
        return publishIfDue(nowNanos, measuredAt)
    }

    fun poll(nowNanos: Long, measuredAt: Instant): LiveWaveformSnapshot? {
        if (ringSize == 0 || nextPublishNanos == null) return null
        return publishIfDue(nowNanos, measuredAt)
    }

    fun publishNow(measuredAt: Instant): LiveWaveformSnapshot? {
        if (ringSize == 0) return null
        publicationSequence++
        nextPublishNanos = System.nanoTime() + refreshIntervalNanos
        return LiveWaveformSnapshot(
            generation = generation,
            publicationSequence = publicationSequence,
            acceptedSampleCount = acceptedSampleCount,
            sourceSampleStartIndex = nextWriteSampleIndex - ringSize,
            sourceSampleEndIndex = nextWriteSampleIndex - 1,
            measuredAt = measuredAt,
            red = copyRing(redRing),
            ir = copyRing(irRing),
        )
    }

    fun reset() {
        resetContinuity(0L, resetCount = true)
    }

    private fun publishIfDue(nowNanos: Long, measuredAt: Instant): LiveWaveformSnapshot? {
        val due = nextPublishNanos ?: return null
        if (nowNanos < due) return null
        val elapsedNanos = (nowNanos - due).coerceAtLeast(0L)
        val skippedIntervals = elapsedNanos / refreshIntervalNanos
        nextPublishNanos = due + (skippedIntervals + 1) * refreshIntervalNanos
        publicationSequence++
        val red = copyRing(redRing)
        val ir = copyRing(irRing)
        return LiveWaveformSnapshot(
            generation = generation,
            publicationSequence = publicationSequence,
            acceptedSampleCount = acceptedSampleCount,
            sourceSampleStartIndex = nextWriteSampleIndex - ringSize,
            sourceSampleEndIndex = nextWriteSampleIndex - 1,
            measuredAt = measuredAt,
            red = red,
            ir = ir,
        )
    }

    private fun append(red: Double, ir: Double) {
        val index = (ringStart + ringSize) % windowSampleCount
        redRing[index] = red
        irRing[index] = ir
        if (ringSize < windowSampleCount) {
            ringSize++
        } else {
            ringStart = (ringStart + 1) % windowSampleCount
        }
    }

    private fun copyRing(source: DoubleArray): DoubleArray {
        val result = DoubleArray(ringSize)
        for (index in 0 until ringSize) {
            result[index] = source[(ringStart + index) % windowSampleCount]
        }
        return result
    }

    private fun resetContinuity(nextIndex: Long, resetCount: Boolean = false) {
        ringStart = 0
        ringSize = 0
        nextWriteSampleIndex = nextIndex
        if (resetCount) acceptedSampleCount = 0L
        generation++
        nextPublishNanos = null
    }
}

object LiveWaveformBucketMath {
    fun bucket(values: DoubleArray, pixelWidth: Int): List<WaveformBucket> {
        if (values.isEmpty() || pixelWidth <= 0) return emptyList()
        val count = min(values.size, pixelWidth)
        return List(count) { bucketIndex ->
            val start = bucketIndex * values.size / count
            val end = ((bucketIndex + 1) * values.size / count).coerceAtLeast(start + 1)
            var minimum = values[start]
            var maximum = values[start]
            for (index in (start + 1) until end) {
                minimum = minOf(minimum, values[index])
                maximum = maxOf(maximum, values[index])
            }
            WaveformBucket(minimum, maximum)
        }
    }
}

/**
 * Builds an ordered waveform path while preserving each downsample bin's
 * extrema. Unlike min/max vertical bars, this remains visible when a bucket
 * contains one sample and preserves the temporal order of sharp pulses.
 */
object LiveWaveformPlotMath {
    fun plot(values: DoubleArray, maximumPointCount: Int): WaveformPlot {
        return plotRange(values, values.indices, maximumPointCount)
    }

    /**
     * Downsamples only the requested source range and keeps absolute sample
     * offsets. This avoids allocating a complete visible-range copy when an
     * offline replay fits a long recording into one viewport.
     */
    fun plotRange(
        values: DoubleArray,
        visibleRange: IntRange,
        maximumPointCount: Int,
    ): WaveformPlot {
        require(maximumPointCount >= 2)
        if (values.isEmpty() || visibleRange.isEmpty()) return WaveformPlot(emptyList(), 0.0, 1.0)
        val start = visibleRange.first.coerceIn(0, values.lastIndex)
        val stop = visibleRange.last.coerceIn(start, values.lastIndex) + 1
        val valueCount = stop - start

        val points = ArrayList<WaveformPlotPoint>(min(valueCount, maximumPointCount))
        var overallMinimum = Double.POSITIVE_INFINITY
        var overallMaximum = Double.NEGATIVE_INFINITY

        fun observe(offset: Int, value: Double) {
            if (!value.isFinite()) return
            overallMinimum = minOf(overallMinimum, value)
            overallMaximum = maxOf(overallMaximum, value)
            points += WaveformPlotPoint(offset, value)
        }

        if (valueCount <= maximumPointCount) {
            for (offset in start until stop) observe(offset, values[offset])
        } else {
            val binCount = maxOf(1, maximumPointCount / 2)
            repeat(binCount) { bin ->
                val lowerOffset = start + bin * valueCount / binCount
                val upperOffset = (start + (bin + 1) * valueCount / binCount).coerceAtMost(stop)
                var minimumPoint: WaveformPlotPoint? = null
                var maximumPoint: WaveformPlotPoint? = null
                for (offset in lowerOffset until upperOffset) {
                    val value = values[offset]
                    if (!value.isFinite()) continue
                    if (minimumPoint == null || value < minimumPoint!!.value) {
                        minimumPoint = WaveformPlotPoint(offset, value)
                    }
                    if (maximumPoint == null || value > maximumPoint!!.value) {
                        maximumPoint = WaveformPlotPoint(offset, value)
                    }
                    overallMinimum = minOf(overallMinimum, value)
                    overallMaximum = maxOf(overallMaximum, value)
                }
                val minimum = minimumPoint
                val maximum = maximumPoint
                if (minimum != null && maximum != null) {
                    if (minimum.offset <= maximum.offset) {
                        points += minimum
                        if (maximum.offset != minimum.offset) points += maximum
                    } else {
                        points += maximum
                        points += minimum
                    }
                }
            }
        }

        if (points.isEmpty()) return WaveformPlot(emptyList(), 0.0, 1.0)
        return WaveformPlot(points, overallMinimum, overallMaximum)
    }
}
