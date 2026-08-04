package com.example.ppgcollector_android.core.signal

import com.example.ppgcollector_android.core.protocol.CupBatchFrame
import com.example.ppgcollector_android.core.protocol.CupBatchProtocolV1
import com.example.ppgcollector_android.core.protocol.CupDecodedFrameEvent
import com.example.ppgcollector_android.core.protocol.CupPpgSample
import com.example.ppgcollector_android.core.protocol.CupSequenceEvent
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveWaveformRuntimeTest {
    @Test
    fun publishesImmediatelyThenAtFiveHertzWithoutBurstingDelayedTicks() {
        val scheduler = LiveWaveformSnapshotScheduler()
        val first = scheduler.ingest(
            decodedFrames = listOf(event(sequence = 1u, start = 0)),
            acceptedSampleStartIndex = 0,
            measuredAt = Instant.EPOCH,
            nowNanos = 0,
        )
        assertNotNull(first)
        assertEquals(1L, first!!.publicationSequence)
        assertEquals(CupBatchProtocolV1.samplesPerFrame, first.red.size)

        assertEquals(null, scheduler.poll(199_000_000L, Instant.EPOCH))
        assertEquals(2L, scheduler.poll(200_000_000L, Instant.EPOCH)!!.publicationSequence)
        assertEquals(3L, scheduler.poll(650_000_000L, Instant.EPOCH)!!.publicationSequence)
        assertEquals(null, scheduler.poll(799_000_000L, Instant.EPOCH))
        assertEquals(4L, scheduler.poll(800_000_000L, Instant.EPOCH)!!.publicationSequence)
    }

    @Test
    fun ringIsBoundedAndBucketMathPreservesPerPixelExtrema() {
        val scheduler = LiveWaveformSnapshotScheduler()
        var snapshot: LiveWaveformSnapshot? = null
        repeat(900 / CupBatchProtocolV1.samplesPerFrame) { frameIndex ->
            snapshot = scheduler.ingest(
                decodedFrames = listOf(event(frameIndex.toUByte(), frameIndex * CupBatchProtocolV1.samplesPerFrame)),
                acceptedSampleStartIndex = frameIndex * CupBatchProtocolV1.samplesPerFrame.toLong(),
                measuredAt = Instant.EPOCH,
                nowNanos = frameIndex * 200_000_000L,
            ) ?: snapshot
        }
        assertNotNull(snapshot)
        assertEquals(800, snapshot!!.red.size)
        assertEquals(900L, snapshot!!.acceptedSampleCount)
        assertEquals(100L, snapshot!!.sourceSampleStartIndex)
        assertEquals(899L, snapshot!!.sourceSampleEndIndex)

        val buckets = LiveWaveformBucketMath.bucket(doubleArrayOf(1.0, 9.0, 3.0, 7.0), 2)
        assertEquals(2, buckets.size)
        assertEquals(1.0, buckets[0].minimum, 0.0)
        assertEquals(9.0, buckets[0].maximum, 0.0)
        assertEquals(3.0, buckets[1].minimum, 0.0)
        assertEquals(7.0, buckets[1].maximum, 0.0)
    }

    @Test
    fun orderedPlotConnectsSingleSampleBucketsAndPreservesDownsampledExtrema() {
        val direct = LiveWaveformPlotMath.plot(
            values = doubleArrayOf(10.0, 15.0, 12.0, 18.0),
            maximumPointCount = 8,
        )
        assertEquals(listOf(0, 1, 2, 3), direct.points.map { it.offset })
        assertEquals(10.0, direct.minimum, 0.0)
        assertEquals(18.0, direct.maximum, 0.0)

        val downsampled = LiveWaveformPlotMath.plot(
            values = DoubleArray(800) { index ->
                when (index % 40) {
                    10 -> 100.0
                    20 -> -50.0
                    else -> index.toDouble() / 800.0
                }
            },
            maximumPointCount = 40,
        )
        assertTrue(downsampled.points.size <= 40)
        assertTrue(downsampled.points.zipWithNext().all { (left, right) -> left.offset <= right.offset })
        assertEquals(-50.0, downsampled.minimum, 0.0)
        assertEquals(100.0, downsampled.maximum, 0.0)

        val ranged = LiveWaveformPlotMath.plotRange(
            values = doubleArrayOf(-500.0, 9.0, 2.0, 8.0, 600.0),
            visibleRange = 1..3,
            maximumPointCount = 8,
        )
        assertEquals(listOf(1, 2, 3), ranged.points.map { it.offset })
        assertEquals(2.0, ranged.minimum, 0.0)
        assertEquals(9.0, ranged.maximum, 0.0)
    }

    @Test
    fun causalAutoscaleDrawsButExcludesOnlyVisibleSettlingPrefix() {
        val values = DoubleArray(800) { 5.0 }.also {
            it[0] = 1_000.0
        }

        val rawRange = LiveWaveformScaleMath.verticalRange(values)!!
        val causalRange = LiveWaveformScaleMath.verticalRange(
            values = values,
            excludedLeadingSampleCount = 200,
        )!!

        assertTrue(rawRange.upper > 900.0)
        assertTrue(causalRange.upper < 10.0)
        assertEquals(4.92, causalRange.lower, 1e-12)
        assertEquals(5.08, causalRange.upper, 1e-12)
        assertEquals(null, LiveWaveformScaleMath.verticalRange(doubleArrayOf()))
    }

    private fun event(sequence: UByte, start: Int): CupDecodedFrameEvent =
        CupDecodedFrameEvent(
            frame = CupBatchFrame(
                sequence = sequence,
                samples = List(CupBatchProtocolV1.samplesPerFrame) { offset ->
                    CupPpgSample(
                        red = (10_000 + start + offset).toUInt(),
                        ir = (20_000 + start + offset).toUInt(),
                    )
                },
            ),
            sequenceEvent = if (sequence == 1u.toUByte()) CupSequenceEvent.First
            else CupSequenceEvent.Continuous,
            isAccepted = true,
        )
}
