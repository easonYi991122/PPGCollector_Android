package com.example.ppgcollector_android.core.signal

import com.example.ppgcollector_android.core.protocol.CupDecodedFrameEvent
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
    val ecg: DoubleArray = doubleArrayOf(),
    val causalRed: DoubleArray = doubleArrayOf(),
    val causalIr: DoubleArray = doubleArrayOf(),
    val preprocessProfile: String? = null,
    /** Causal display path: polarity-flipped raw -> 0.5–12 Hz. */
    val displayCausalRed: DoubleArray = doubleArrayOf(),
    val displayCausalIr: DoubleArray = doubleArrayOf(),
    val displayCausalProfile: String? = null,
    /** Accepted analysis samples since the last local cursor/filter reset; wire gaps are diagnostic. */
    val continuousSampleCount: Long = 0,
    /** Local processing epoch; wire gaps are tracked separately and do not increment it. */
    val continuityEpoch: Long = 0,
    /** Number of sequence gaps observed in this live runtime. */
    val gapCount: Long = 0,
    /** Relative sample offsets where a new display segment starts. */
    val segmentBreakSampleIndices: IntArray = intArrayOf(),
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

/** Live autoscale uses a stable recent tail without hiding older plotted samples. */
object LiveWaveformScaleMath {
    private const val DISPLAY_WINDOW_SAMPLE_COUNT = 800
    private const val FILLING_REFERENCE_SAMPLE_COUNT = 200
    private const val FULL_REFERENCE_SAMPLE_COUNT = 600

    fun verticalRange(
        values: DoubleArray,
        excludedLeadingSampleCount: Int = 0,
        paddingRatio: Double = 0.08,
    ): WaveformVerticalRange? {
        if (values.isEmpty()) return null
        val referenceSampleCount = if (values.size < DISPLAY_WINDOW_SAMPLE_COUNT) {
            FILLING_REFERENCE_SAMPLE_COUNT
        } else {
            FULL_REFERENCE_SAMPLE_COUNT
        }
        val tailStart = (values.size - referenceSampleCount).coerceAtLeast(0)
        val stableStart = if (excludedLeadingSampleCount > 0 && values.size > excludedLeadingSampleCount) {
            excludedLeadingSampleCount.coerceAtMost(values.lastIndex)
        } else {
            0
        }
        val start = maxOf(tailStart, stableStart)
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
 * Display-only RAW axis holder. Its center is immutable for one UI source;
 * ranges expand symmetrically in bounded steps and never follow rolling extrema inward.
 */
class LiveRawWaveformAxisRuntime(
    private val safetyMarginRatio: Double = 0.15,
    private val minimumGrowthRatio: Double = 0.25,
) {
    init {
        require(safetyMarginRatio >= 0.0)
        require(minimumGrowthRatio > 0.0)
    }

    private var currentRange: WaveformVerticalRange? = null

    fun update(candidate: WaveformVerticalRange?): WaveformVerticalRange? {
        if (candidate == null) return currentRange
        if (!candidate.lower.isFinite() || !candidate.upper.isFinite() ||
            candidate.upper <= candidate.lower
        ) return currentRange

        val current = currentRange
        if (current == null) {
            val center = (candidate.lower + candidate.upper) / 2.0
            val halfSpan = (candidate.upper - candidate.lower) / 2.0
            return centeredRange(center, halfSpan * (1.0 + safetyMarginRatio)).also {
                currentRange = it
            }
        }

        val center = (current.lower + current.upper) / 2.0
        val currentHalfSpan = (current.upper - current.lower) / 2.0
        val requiredHalfSpan = maxOf(
            center - candidate.lower,
            candidate.upper - center,
        )
        if (requiredHalfSpan <= currentHalfSpan) return current

        val expandedHalfSpan = maxOf(
            requiredHalfSpan * (1.0 + safetyMarginRatio),
            currentHalfSpan * (1.0 + minimumGrowthRatio),
        )
        return centeredRange(center, expandedHalfSpan).also { currentRange = it }
    }

    private fun centeredRange(center: Double, halfSpan: Double): WaveformVerticalRange =
        WaveformVerticalRange(
            lower = center - halfSpan.coerceAtLeast(1e-9),
            upper = center + halfSpan.coerceAtLeast(1e-9),
        )
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
    private val ecgRing = DoubleArray(windowSampleCount)
    private val ecgDisplayDownsampler = EcgDisplayDownsampler()
    private var ecgRingStart = 0
    private var ecgRingSize = 0
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

    /** Adds ECG samples to the display-only 100 Hz view (500 Hz source, 5:1 boxcar). */
    fun ingestEcgDisplaySamples(samples: List<UInt>) {
        ecgDisplayDownsampler.ingest(samples).forEach(::appendEcg)
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
            ecg = copyEcgRing(),
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
            ecg = copyEcgRing(),
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
        ecgRingStart = 0
        ecgRingSize = 0
        ecgDisplayDownsampler.reset()
        nextWriteSampleIndex = nextIndex
        if (resetCount) acceptedSampleCount = 0L
        generation++
        nextPublishNanos = null
    }

    private fun appendEcg(value: Double) {
        val index = (ecgRingStart + ecgRingSize) % windowSampleCount
        ecgRing[index] = value
        if (ecgRingSize < windowSampleCount) {
            ecgRingSize++
        } else {
            ecgRingStart = (ecgRingStart + 1) % windowSampleCount
        }
    }

    private fun copyEcgRing(): DoubleArray {
        val result = DoubleArray(ecgRingSize)
        for (index in 0 until ecgRingSize) {
            result[index] = ecgRing[(ecgRingStart + index) % windowSampleCount]
        }
        return result
    }
}

object LiveWaveformBucketMath {
    fun bucket(values: DoubleArray, pixelWidth: Int): List<WaveformBucket> {
        if (values.isEmpty() || pixelWidth <= 0) return emptyList()
        val count = min(values.size, pixelWidth)
        return List(count) { bucketIndex ->
            val start = (bucketIndex.toLong() * values.size / count).toInt()
            val end = (((bucketIndex + 1L) * values.size / count).toInt()).coerceAtLeast(start + 1)
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
        cancellationCheck: () -> Unit = {},
        pointFactory: (Int, Double) -> WaveformPlotPoint = ::WaveformPlotPoint,
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
            points += pointFactory(offset, value)
        }

        if (valueCount <= maximumPointCount) {
            for (offset in start until stop) {
                if ((offset - start) % 4096 == 0) cancellationCheck()
                observe(offset, values[offset])
            }
        } else {
            val binCount = maxOf(1, maximumPointCount / 2)
            repeat(binCount) { bin ->
                cancellationCheck()
                val lower = start.toLong() + bin.toLong() * valueCount / binCount
                val upper = start.toLong() + (bin + 1L) * valueCount / binCount
                check(lower >= start && upper <= stop && lower < upper)
                val lowerOffset = lower.toInt()
                val upperOffset = upper.toInt()
                var minimumOffset = -1
                var maximumOffset = -1
                var minimumValue = Double.POSITIVE_INFINITY
                var maximumValue = Double.NEGATIVE_INFINITY
                for (offset in lowerOffset until upperOffset) {
                    if ((offset - lowerOffset) % 4096 == 0) cancellationCheck()
                    val value = values[offset]
                    if (!value.isFinite()) continue
                    if (minimumOffset < 0 || value < minimumValue) {
                        minimumOffset = offset
                        minimumValue = value
                    }
                    if (maximumOffset < 0 || value > maximumValue) {
                        maximumOffset = offset
                        maximumValue = value
                    }
                }
                if (minimumOffset >= 0) {
                    if (minimumOffset <= maximumOffset) {
                        observe(minimumOffset, minimumValue)
                        if (maximumOffset != minimumOffset) observe(maximumOffset, maximumValue)
                    } else {
                        observe(maximumOffset, maximumValue)
                        observe(minimumOffset, minimumValue)
                    }
                }
            }
        }

        if (points.isEmpty()) return WaveformPlot(emptyList(), 0.0, 1.0)
        return WaveformPlot(points, overallMinimum, overallMaximum)
    }
}
