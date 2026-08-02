package com.example.ppgcollector_android.core.signal

import com.example.ppgcollector_android.core.protocol.CupBatchFrame
import com.example.ppgcollector_android.core.protocol.CupDecodedFrameEvent
import com.example.ppgcollector_android.core.protocol.CupPpgSample
import com.example.ppgcollector_android.core.protocol.CupSequenceEvent
import java.time.Instant
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePpgSignalRuntimeTest {
    private val measuredAt = Instant.ofEpochSecond(1_800_000_000L)

    @Test
    fun rawCausalWaveformAndMetricRequestShareOneBoundedState() {
        val runtime = LivePpgSignalRuntime()
        var latest: LivePpgIngestResult? = null
        repeat(16) { frameIndex ->
            latest = runtime.ingest(
                decodedFrames = listOf(frame(frameIndex, frameIndex * 50)),
                acceptedSampleStartIndex = frameIndex * 50L,
                measuredAt = measuredAt,
                nowNanos = frameIndex * 500_000_000L,
            )
        }

        val waveform = latest!!.waveform
        val request = latest!!.metricRequest
        assertNotNull(waveform)
        assertNotNull(request)
        assertEquals(800, waveform!!.red.size)
        assertEquals(800, waveform.causalRed.size)
        assertEquals(0L, waveform.sourceSampleStartIndex)
        assertEquals(799L, waveform.sourceSampleEndIndex)
        assertEquals("ios_baseline_0.1", waveform.preprocessProfile)
        assertEquals(200, waveform.settlingSampleCount)
        assertEquals(800L, waveform.continuousSampleCount)
        assertArrayEquals(waveform.red, request!!.rawRed.toDoubleArray(), 0.0)
        assertArrayEquals(waveform.ir, request.rawIr.toDoubleArray(), 0.0)
        assertArrayEquals(waveform.causalRed, request.bandpassedRed.toDoubleArray(), 0.0)
        assertArrayEquals(waveform.causalIr, request.bandpassedIr.toDoubleArray(), 0.0)

        val expectedRed = PpgPreprocessor().process(waveform.red.asList())
            .map { it.sample!!.bandpassed }
            .toDoubleArray()
        val expectedIr = PpgPreprocessor().process(waveform.ir.asList())
            .map { it.sample!!.bandpassed }
            .toDoubleArray()
        assertArrayEquals(expectedRed, waveform.causalRed, 0.0)
        assertArrayEquals(expectedIr, waveform.causalIr, 0.0)

        repeat(4) { offset ->
            val frameIndex = 16 + offset
            latest = runtime.ingest(
                decodedFrames = listOf(frame(frameIndex, frameIndex * 50)),
                acceptedSampleStartIndex = frameIndex * 50L,
                measuredAt = measuredAt,
                nowNanos = frameIndex * 500_000_000L,
            )
        }
        val rolled = latest!!.waveform!!
        assertEquals(800, rolled.red.size)
        assertEquals(800, rolled.causalRed.size)
        assertEquals(200L, rolled.sourceSampleStartIndex)
        assertEquals(999L, rolled.sourceSampleEndIndex)
        assertEquals(0, rolled.settlingSampleCount)
    }

    @Test
    fun gapResetsRawCausalMetricAndSettlingStateAtomically() {
        val runtime = LivePpgSignalRuntime()
        var beforeGap: LiveMetricAnalysisRequest? = null
        repeat(16) { frameIndex ->
            beforeGap = runtime.ingest(
                decodedFrames = listOf(frame(frameIndex, frameIndex * 50)),
                acceptedSampleStartIndex = frameIndex * 50L,
                measuredAt = measuredAt,
                nowNanos = frameIndex * 500_000_000L,
            ).metricRequest ?: beforeGap
        }
        assertNotNull(beforeGap)
        assertTrue(runtime.isCurrent(beforeGap!!))

        val gap = runtime.ingest(
            decodedFrames = listOf(frame(18, 800, CupSequenceEvent.Gap(2))),
            acceptedSampleStartIndex = 800L,
            measuredAt = measuredAt,
            nowNanos = 8_000_000_000L,
        )

        assertNull(gap.metricRequest)
        assertNotNull(gap.waveform)
        assertFalse(runtime.isCurrent(beforeGap!!))
        assertEquals(50L, runtime.continuousSamples)
        assertEquals(50, runtime.bufferedSampleCount)
        assertEquals(50, gap.waveform!!.red.size)
        assertEquals(50, gap.waveform.causalRed.size)
        assertEquals(50, gap.waveform.settlingSampleCount)
        assertEquals(0.0, gap.waveform.causalRed.first(), 0.0)
        assertEquals(0.0, gap.waveform.causalIr.first(), 0.0)
        assertEquals(800L, gap.waveform.sourceSampleStartIndex)
        assertEquals(849L, gap.waveform.sourceSampleEndIndex)
    }

    @Test
    fun rejectedFramesAreIgnoredAndSampleIndexDiscontinuityStartsANewGeneration() {
        val runtime = LivePpgSignalRuntime()
        val initial = runtime.ingest(
            decodedFrames = listOf(frame(0, 0)),
            acceptedSampleStartIndex = 0L,
            measuredAt = measuredAt,
            nowNanos = 0L,
        ).waveform!!
        val rejected = frame(
            sequence = 0,
            sampleOffset = 0,
            sequenceEvent = CupSequenceEvent.Duplicate,
        ).copy(isAccepted = false)

        runtime.ingest(
            decodedFrames = listOf(rejected),
            acceptedSampleStartIndex = 50L,
            measuredAt = measuredAt,
            nowNanos = 100_000_000L,
        )

        assertEquals(50L, runtime.continuousSamples)
        assertEquals(50, runtime.bufferedSampleCount)
        assertEquals(initial.generation, runtime.generation)

        val discontinuous = runtime.ingest(
            decodedFrames = listOf(frame(2, 100)),
            acceptedSampleStartIndex = 100L,
            measuredAt = measuredAt,
            nowNanos = 200_000_000L,
        ).waveform!!

        assertEquals(initial.generation + 1L, discontinuous.generation)
        assertEquals(50L, runtime.continuousSamples)
        assertEquals(50, discontinuous.red.size)
        assertEquals(50, discontinuous.causalRed.size)
        assertEquals(100L, discontinuous.sourceSampleStartIndex)
        assertEquals(149L, discontinuous.sourceSampleEndIndex)
        assertEquals(0.0, discontinuous.causalRed.first(), 0.0)
    }

    @Test
    fun fiveHertzPollingDoesNotBurstAfterDelayedTick() {
        val runtime = LivePpgSignalRuntime()
        val first = runtime.ingest(
            decodedFrames = listOf(frame(0, 0)),
            acceptedSampleStartIndex = 0L,
            measuredAt = measuredAt,
            nowNanos = 0L,
        ).waveform
        assertNotNull(first)
        assertEquals(1L, first!!.publicationSequence)
        assertNull(runtime.poll(199_000_000L, measuredAt))
        assertEquals(2L, runtime.poll(200_000_000L, measuredAt)!!.publicationSequence)
        assertEquals(3L, runtime.poll(650_000_000L, measuredAt)!!.publicationSequence)
        assertNull(runtime.poll(799_000_000L, measuredAt))
        assertEquals(4L, runtime.poll(800_000_000L, measuredAt)!!.publicationSequence)
    }

    private fun frame(
        sequence: Int,
        sampleOffset: Int,
        sequenceEvent: CupSequenceEvent = if (sequence == 0) {
            CupSequenceEvent.First
        } else {
            CupSequenceEvent.Continuous
        },
    ) = CupDecodedFrameEvent(
        frame = CupBatchFrame(
            sequence = sequence.toUByte(),
            samples = List(50) { localIndex ->
                val sampleIndex = sampleOffset + localIndex
                val phase = 2.0 * PI * 1.2 * sampleIndex / 100.0
                CupPpgSample(
                    red = (100_000.0 + 1_100.0 * sin(phase)).toUInt(),
                    ir = (120_000.0 + 1_500.0 * sin(phase)).toUInt(),
                )
            },
        ),
        sequenceEvent = sequenceEvent,
        isAccepted = true,
    )
}
