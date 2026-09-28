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
    @org.junit.Test
    fun millionPointPlotUsesLongBucketsAndKeepsAbsoluteExtrema() {
        val values = DoubleArray(1_500_000) { it.toDouble() }
        values[555_555] = -9_000_000.0
        values[1_234_567] = 9_000_000.0
        for (range in listOf(values.indices, 123_457..1_499_998)) {
            val plot = LiveWaveformPlotMath.plotRange(values, range, 4096)
            org.junit.Assert.assertTrue(plot.points.size <= 4096)
            org.junit.Assert.assertTrue(plot.points.all { it.offset in range && it.value == values[it.offset] })
            org.junit.Assert.assertEquals(-9_000_000.0, plot.minimum, 0.0)
            org.junit.Assert.assertEquals(9_000_000.0, plot.maximum, 0.0)
            org.junit.Assert.assertTrue(plot.points.zipWithNext().all { (a, b) -> a.offset < b.offset })
        }
    }

    @org.junit.Test
    fun rampCreatesPointsOnlyAtBucketOutputAndSupportsCancellation() {
        val values = DoubleArray(1_000_000) { it.toDouble() }
        var pointsCreated = 0
        val plot = LiveWaveformPlotMath.plotRange(values, values.indices, 800,
            pointFactory = { offset, value -> pointsCreated++; WaveformPlotPoint(offset, value) })
        org.junit.Assert.assertEquals(800, pointsCreated)
        org.junit.Assert.assertEquals(pointsCreated, plot.points.size)
        var checks = 0
        try {
            LiveWaveformPlotMath.plotRange(values, values.indices, 4096,
                cancellationCheck = { if (++checks == 4) throw java.util.concurrent.CancellationException() })
            org.junit.Assert.fail("expected cooperative cancellation")
        } catch (_: java.util.concurrent.CancellationException) {
            org.junit.Assert.assertEquals(4, checks)
        }
    }

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
            it[250] = 1_000.0
        }

        val rawRange = LiveWaveformScaleMath.verticalRange(values)!!
        val causalRange = LiveWaveformScaleMath.verticalRange(
            values = values,
            excludedLeadingSampleCount = 400,
        )!!

        assertTrue(rawRange.upper > 900.0)
        assertTrue(causalRange.upper < 10.0)
        assertEquals(4.92, causalRange.lower, 1e-12)
        assertEquals(5.08, causalRange.upper, 1e-12)
        assertEquals(null, LiveWaveformScaleMath.verticalRange(doubleArrayOf()))
    }

    @Test
    fun liveAutoscaleUsesTwoSecondTailUntilFullThenSixSecondTail() {
        val one = LiveWaveformScaleMath.verticalRange(doubleArrayOf(12.0))!!
        assertTrue(one.lower < 12.0)
        assertTrue(one.upper > 12.0)

        val filling = DoubleArray(799) { 5.0 }.also {
            it[0] = 1_000.0
            it[it.lastIndex] = 6.0
        }
        val fillingRange = LiveWaveformScaleMath.verticalRange(filling)!!
        assertTrue(fillingRange.upper < 10.0)

        val full = DoubleArray(800) { 5.0 }.also {
            it[199] = 1_000.0
            it[200] = 7.0
        }
        val fullRange = LiveWaveformScaleMath.verticalRange(full)!!
        assertTrue(fullRange.upper < 10.0)
        assertTrue(fullRange.upper > 7.0)

        val justOverFillingTail = DoubleArray(201) { 5.0 }.also {
            it[0] = 1_000.0
            it[1] = 6.0
        }
        assertTrue(LiveWaveformScaleMath.verticalRange(justOverFillingTail)!!.upper < 10.0)
    }

    @Test
    fun rawAxisHoldsItsCenterAndRangeUntilAThresholdIsActuallyExceeded() {
        val axis = LiveRawWaveformAxisRuntime()
        val initial = axis.update(WaveformVerticalRange(0.0, 10.0))!!

        assertEquals(initial, axis.update(WaveformVerticalRange(0.5, 10.5)))
        assertEquals(initial, axis.update(WaveformVerticalRange(-0.5, 9.5)))

        val expanded = axis.update(WaveformVerticalRange(0.0, 12.0))!!
        assertEquals(
            (initial.lower + initial.upper) / 2.0,
            (expanded.lower + expanded.upper) / 2.0,
            1e-12,
        )
        assertTrue(expanded.upper - expanded.lower >= (initial.upper - initial.lower) * 1.25)
        assertEquals(expanded, axis.update(WaveformVerticalRange(4.0, 6.0)))

        val newSourceAxis = LiveRawWaveformAxisRuntime()
        val recentered = newSourceAxis.update(WaveformVerticalRange(100.0, 110.0))!!
        assertEquals(105.0, (recentered.lower + recentered.upper) / 2.0, 1e-12)
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
