package com.example.ppgcollector_android

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
    fun decimalInputAllowsOnlyOneDecimalSeparator() {
        assertEquals("170.25", decimalInput("170..2cm5"))
    }
}
