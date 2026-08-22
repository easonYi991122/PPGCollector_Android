package com.example.ppgcollector_android.core.signal.live

import com.example.ppgcollector_android.core.protocol.CupBatchFrame
import com.example.ppgcollector_android.core.protocol.CupBatchProtocolV1
import com.example.ppgcollector_android.core.protocol.CupDecodedFrameEvent
import com.example.ppgcollector_android.core.protocol.CupPpgSample
import com.example.ppgcollector_android.core.protocol.CupSequenceEvent
import com.example.ppgcollector_android.core.signal.LiveMetricAnalysisRequest
import com.example.ppgcollector_android.core.signal.LiveMetricAnalyzer
import com.example.ppgcollector_android.core.signal.LiveMetricSnapshot
import com.example.ppgcollector_android.core.signal.LiveMetricWindowScheduler
import com.example.ppgcollector_android.core.signal.MetricResult
import com.example.ppgcollector_android.core.signal.MetricUnavailableReason
import com.example.ppgcollector_android.core.signal.StreamFreshness
import java.time.Instant
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMetricRuntimeTest {
    private val measuredAt = Instant.ofEpochSecond(1_800_000_000)

    @Test
    fun waitsForEightSecondsThenSchedulesEveryOneSecondWithBoundedWindow() {
        val scheduler = LiveMetricWindowScheduler()
        for (frameIndex in 0 until 39) {
            assertNull(scheduler.ingest(listOf(frame(frameIndex, frameIndex * 20)), measuredAt))
        }
        val first = scheduler.ingest(listOf(frame(39, 780)), measuredAt)
        assertNotNull(first)
        assertEquals(799L, first!!.windowEndSampleIndex)
        assertEquals(0.0, first.timeSeconds.first(), 0.0)
        assertEquals(7.99, first.timeSeconds.last(), 1e-12)
        assertEquals(1L, first.requestSequence)
        assertTrue(scheduler.isCurrent(first))

        for (frameIndex in 40 until 44) {
            assertNull(scheduler.ingest(listOf(frame(frameIndex, frameIndex * 20)), measuredAt))
        }
        val second = scheduler.ingest(listOf(frame(44, 880)), measuredAt)
        assertNotNull(second)
        assertEquals(899L, second!!.windowEndSampleIndex)
        assertEquals(1.0, second.timeSeconds.first(), 1e-12)
        assertEquals(8.99, second.timeSeconds.last(), 1e-12)
        assertEquals(2L, second.requestSequence)
        assertFalse(scheduler.isCurrent(first))
        assertTrue(scheduler.isCurrent(second))
        assertEquals(800, scheduler.bufferedSampleCount)
    }

    @Test
    fun wireGapKeepsAcceptedSampleWindowAndNextCadenceRemainsAvailable() {
        val scheduler = LiveMetricWindowScheduler()
        var first: com.example.ppgcollector_android.core.signal.LiveMetricAnalysisRequest? = null
        for (frameIndex in 0 until 40) {
            first = scheduler.ingest(
                listOf(frame(frameIndex, frameIndex * 20, if (frameIndex == 0) CupSequenceEvent.First else CupSequenceEvent.Continuous)),
                measuredAt,
            ) ?: first
        }
        val beforeGap = first!!
        val generationBeforeGap = beforeGap.generation

        val gap = scheduler.ingest(
            listOf(frame(42, 800, CupSequenceEvent.Gap(2))),
            measuredAt,
        )
        assertNull(gap)
        assertEquals(820, scheduler.continuousSamples)
        assertEquals(800, scheduler.bufferedSampleCount)
        assertTrue(scheduler.isCurrent(beforeGap))
        assertEquals(generationBeforeGap, scheduler.generation)

        var next: com.example.ppgcollector_android.core.signal.LiveMetricAnalysisRequest? = null
        for (frameIndex in 1..4) {
            next = scheduler.ingest(
                listOf(frame(42 + frameIndex, 800 + frameIndex * 20)),
                measuredAt,
            ) ?: next
        }
        assertNotNull(next)
        assertEquals(899L, next!!.windowEndSampleIndex)
        assertEquals(1.0, next.timeSeconds.first(), 1e-12)
        assertEquals(8.99, next.timeSeconds.last(), 1e-12)
        assertTrue(scheduler.isCurrent(next))
    }

    @Test
    fun rejectedFramesDoNotAdvanceMetricTimeline() {
        val scheduler = LiveMetricWindowScheduler()
        val rejected = CupDecodedFrameEvent(
            frame = CupBatchFrame(4u, List(CupBatchProtocolV1.samplesPerFrame) { CupPpgSample(1u, 2u) }),
            sequenceEvent = CupSequenceEvent.Duplicate,
            isAccepted = false,
        )
        assertNull(scheduler.ingest(listOf(rejected), measuredAt))
        assertEquals(0, scheduler.continuousSamples)
        assertEquals(0, scheduler.bufferedSampleCount)
    }

    @Test
    fun metricResultAndSnapshotKeepValidityReasonsAndCalibrationSeparate() {
        val valid = MetricResult.valid(
            value = 72.0,
            measuredAt = measuredAt,
            algorithmVersion = "hr-test-0.1",
            sourceSampleIndex = 799,
            sourceTimeSeconds = 7.99,
        )
        assertTrue(valid.isValid)
        assertFalse(valid.isProvisional)
        assertNull(valid.unavailableReason)
        assertEquals(799L, valid.sourceSampleIndex)

        val unavailable = MetricResult.unavailable<Double>(
            reason = MetricUnavailableReason.CALIBRATION_UNAVAILABLE,
            algorithmVersion = "ppg-ios-rr-0.1",
            sourceSampleIndex = 799,
            sourceTimeSeconds = 7.99,
        )
        assertFalse(unavailable.isValid)
        assertNull(unavailable.value)
        assertEquals(MetricUnavailableReason.CALIBRATION_UNAVAILABLE, unavailable.unavailableReason)

        val warming = LiveMetricSnapshot.warmingUp()
        assertEquals(MetricUnavailableReason.INSUFFICIENT_DATA, warming.ratioOfRatios.unavailableReason)
        assertEquals(MetricUnavailableReason.CALIBRATION_UNAVAILABLE, warming.oxygenSaturationPercent.unavailableReason)
        assertEquals(
            MetricUnavailableReason.NO_DEVICE,
            LiveMetricSnapshot.unavailable(false, StreamFreshness.FRESH).heartRateBpm.unavailableReason,
        )
    }

    @Test
    fun analyzerPreservesWindowSourceAndMarksDiagnosticMetricsProvisional() {
        val sampleRate = 100.0
        val values = (0 until 800).map { index ->
            val time = index / sampleRate
            1_000.0 * sin(2.0 * PI * 1.2 * time) + 220.0 * sin(2.0 * PI * 2.4 * time)
        }
        val request = LiveMetricAnalysisRequest(
            generation = 4,
            requestSequence = 9,
            windowEndSampleIndex = 1_599,
            windowEndTimeSeconds = 15.99,
            measuredAt = measuredAt,
            rawRed = (0 until 800).map { 250_000.0 + 12_000.0 * sin(2.0 * PI * 1.2 * it / sampleRate) },
            rawIr = (0 until 800).map { 500_000.0 + 20_000.0 * sin(2.0 * PI * 1.2 * it / sampleRate) },
            bandpassedRed = values.map { it * 0.6 },
            bandpassedIr = values,
            timeSeconds = (800 until 1_600).map { it / sampleRate },
        )

        val result = LiveMetricAnalyzer.analyze(request)
        assertEquals(1_599L, result.snapshot.heartRateBpm.sourceSampleIndex)
        assertEquals(15.99, result.snapshot.heartRateBpm.sourceTimeSeconds!!, 1e-12)
        assertEquals(measuredAt, result.snapshot.heartRateBpm.measuredAt)
        assertTrue(result.snapshot.heartRateBpm.isValid)
        assertTrue(result.snapshot.signalQuality.isValid)
        assertTrue(result.snapshot.signalQuality.isProvisional)
        assertNotNull(result.provisionalSignalQuality)
        assertTrue(result.snapshot.ratioOfRatios.isValid)
        assertTrue(result.snapshot.ratioOfRatios.isProvisional)
        assertTrue((result.snapshot.ratioOfRatios.value ?: 0.0) > 0.0)
        assertFalse(result.snapshot.oxygenSaturationPercent.isValid)
        assertEquals(
            MetricUnavailableReason.CALIBRATION_UNAVAILABLE,
            result.snapshot.oxygenSaturationPercent.unavailableReason,
        )
    }

    private fun frame(
        sequence: Int,
        sampleOffset: Int,
        sequenceEvent: CupSequenceEvent = if (sequence == 0) CupSequenceEvent.First else CupSequenceEvent.Continuous,
    ) = CupDecodedFrameEvent(
        frame = CupBatchFrame(
            sequence = (sequence and 0xFF).toUByte(),
            samples = (0 until CupBatchProtocolV1.samplesPerFrame).map { localIndex ->
                val index = sampleOffset + localIndex
                val phase = index / 100.0
                val ir = (500_000.0 + 20_000.0 * sin(2.0 * PI * 1.2 * phase)).toUInt()
                CupPpgSample(red = ir + 1_000u, ir = ir)
            },
        ),
        sequenceEvent = sequenceEvent,
        isAccepted = true,
    )
}
