package com.example.ppgcollector_android.core.signal

import com.example.ppgcollector_android.core.protocol.CupDecodedFrameEvent
import com.example.ppgcollector_android.core.protocol.CupSequenceEvent
import java.time.Instant
import kotlin.math.min

data class LiveWaveformSnapshot(
    val generation: Long = 0,
    val publicationSequence: Long = 0,
    val acceptedSampleCount: Long = 0,
    val sourceSampleStartIndex: Long? = null,
    val sourceSampleEndIndex: Long? = null,
    val measuredAt: Instant? = null,
    val red: DoubleArray = doubleArrayOf(),
    val ir: DoubleArray = doubleArrayOf(),
)

data class WaveformBucket(
    val minimum: Double,
    val maximum: Double,
)

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
