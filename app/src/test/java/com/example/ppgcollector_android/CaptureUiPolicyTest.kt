package com.example.ppgcollector_android

import com.example.ppgcollector_android.core.signal.LiveWaveformSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureUiPolicyTest {
    @Test
    fun recordingRequestAlwaysStartsInCompactDensity() {
        assertEquals(
            CaptureContentDensity.COMPACT,
            CaptureUiPolicy.reduce(CaptureContentDensity.DETAILED, CaptureUiEvent.RECORDING_REQUESTED),
        )
        assertEquals(
            CaptureContentDensity.DETAILED,
            CaptureUiPolicy.reduce(CaptureContentDensity.COMPACT, CaptureUiEvent.TOGGLE_DENSITY),
        )
        assertEquals(
            CaptureContentDensity.DETAILED,
            CaptureUiPolicy.reduce(CaptureContentDensity.COMPACT, CaptureUiEvent.RECORDING_STOPPED),
        )
    }

    @Test
    fun compactFilterLabelsRemainShortAndDistinct() {
        assertEquals(listOf("RAW", "CAUSAL", "FIXED"), LiveWaveformDisplayMode.entries.map { it.compactLabel })
        assertEquals(listOf("HR", "RR", "PI", "SQI", "BP"), liveMetricCompactOrder)
    }

    @Test
    fun fixedSelectionRemainsSelectedWhileItsRightContextWarms() {
        val warming = resolveLiveWaveformMode(
            requested = LiveWaveformDisplayMode.FIXED_LAG,
            causalAvailable = true,
            fixedLagAvailable = false,
        )
        assertEquals(LiveWaveformDisplayMode.FIXED_LAG, warming.requested)
        assertEquals(LiveWaveformDisplayMode.CAUSAL, warming.effective)
        assertEquals(true, warming.isWarming)

        val ready = resolveLiveWaveformMode(
            requested = LiveWaveformDisplayMode.FIXED_LAG,
            causalAvailable = true,
            fixedLagAvailable = true,
        )
        assertEquals(LiveWaveformDisplayMode.FIXED_LAG, ready.effective)
        assertEquals(false, ready.isWarming)
    }

    @Test
    fun emptySignalUsesTheLightweightDisconnectedContent() {
        assertEquals(false, shouldComposeLiveSignalDetails(0, 0))
        assertEquals(false, shouldComposeLiveSignalDetails(20, 0))
        assertEquals(true, shouldComposeLiveSignalDetails(20, 20))
    }

    @Test
    fun fixedLagGapMarkerUsesTheDelayedSourceCursor() {
        val waveform = LiveWaveformSnapshot(
            sourceSampleStartIndex = 20L,
            sourceSampleEndIndex = 819L,
            segmentBreakSampleIndices = intArrayOf(780),
            fixedLagSourceSampleStartIndex = 100L,
            fixedLagSourceSampleEndIndex = 819L,
        )
        assertEquals(
            listOf(700),
            displaySegmentBreakIndices(waveform, LiveWaveformDisplayMode.FIXED_LAG),
        )
        assertEquals(
            listOf(780),
            displaySegmentBreakIndices(waveform, LiveWaveformDisplayMode.CAUSAL),
        )
    }

    @Test
    fun decimalInputAllowsOnlyOneDecimalSeparator() {
        assertEquals("170.25", decimalInput("170..2cm5"))
    }
}
