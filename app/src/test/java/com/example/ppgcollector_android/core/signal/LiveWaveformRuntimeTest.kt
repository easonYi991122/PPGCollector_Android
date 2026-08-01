package com.example.ppgcollector_android.core.signal

import com.example.ppgcollector_android.core.protocol.CupBatchFrame
import com.example.ppgcollector_android.core.protocol.CupDecodedFrameEvent
import com.example.ppgcollector_android.core.protocol.CupPpgSample
import com.example.ppgcollector_android.core.protocol.CupSequenceEvent
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        assertEquals(50, first.red.size)

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
        repeat(18) { frameIndex ->
            snapshot = scheduler.ingest(
                decodedFrames = listOf(event(frameIndex.toUByte(), frameIndex * 50)),
                acceptedSampleStartIndex = frameIndex * 50L,
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

    private fun event(sequence: UByte, start: Int): CupDecodedFrameEvent =
        CupDecodedFrameEvent(
            frame = CupBatchFrame(
                sequence = sequence,
                samples = List(50) { offset ->
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
