package com.example.ppgcollector_android

import org.junit.Assert.assertEquals
import org.junit.Test

class WaveformAccessibilityTest {
    @Test
    fun waveformSemanticsExposeChannelAndBoundedSampleCount() {
        assertEquals(
            "RED 波形，800 个样本",
            waveformContentDescription("RED", 800),
        )
        assertEquals(
            "REPLAY IR 波形，200 个样本",
            waveformContentDescription("REPLAY IR", 200),
        )
    }
}
