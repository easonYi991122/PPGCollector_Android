package com.example.ppgcollector_android

import com.example.ppgcollector_android.core.ble.LiveStreamDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveWaveformComponentsTest {
    @Test
    fun segmentBreakMarkersMapToBoundedFractionsForTheDedicatedStrip() {
        assertEquals(emptyList<Float>(), segmentBreakMarkerFractions(listOf(1), 1))
        assertEquals(
            listOf(0f, 0.5f, 1f),
            segmentBreakMarkerFractions(listOf(11, -2, 5, 5), 11),
        )
    }

    @Test
    fun diagnosticTextContainsTheMinimumBoardDecoderAndAppEvidence() {
        val text = liveStreamDiagnosticText(
            LiveStreamDiagnostics(
                decodedFrameCount = 40,
                lastSequenceNumber = 120u,
                lastSequenceStep = 4,
                gapEventCount = 12,
                estimatedMissingFrameCount = 36,
                decoderDiscardedByteCount = 8,
                decoderInvalidFrameCount = 2,
                appDroppedChunkCount = 3,
            ),
        )

        assertTrue(text.contains("seq 120/Δ4"))
        assertTrue(text.contains("gap 12/缺 36"))
        assertTrue(text.contains("解码弃 8 B/坏 2"))
        assertTrue(text.contains("缺帧率 47.37%/异常帧率 48.72%"))
        assertTrue(text.contains("App 丢块 3"))
    }

    @Test
    fun diagnosticRatesUseFrameComparableEvidenceOnly() {
        val empty = liveStreamDiagnosticRates(LiveStreamDiagnostics())
        assertNull(empty.missingFrameRate)
        assertNull(empty.abnormalFrameRate)

        val rates = liveStreamDiagnosticRates(
            LiveStreamDiagnostics(
                decodedFrameCount = 90,
                estimatedMissingFrameCount = 10,
                duplicateFrameCount = 2,
                outOfOrderFrameCount = 1,
                decoderInvalidFrameCount = 5,
                decoderDiscardedByteCount = 100,
                appDroppedChunkCount = 20,
            ),
        )
        assertEquals(0.10, rates.missingFrameRate!!, 1e-12)
        assertEquals(18.0 / 105.0, rates.abnormalFrameRate!!, 1e-12)
    }

    @Test
    fun diagnosticTextShowsUnknownRatesBeforeAnyFrameEvidence() {
        val text = liveStreamDiagnosticText(LiveStreamDiagnostics())
        assertTrue(text.contains("缺帧率 —/异常帧率 —"))
    }
}
