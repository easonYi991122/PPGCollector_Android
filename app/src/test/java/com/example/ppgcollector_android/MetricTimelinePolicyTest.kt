package com.example.ppgcollector_android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricTimelinePolicyTest {
    @Test
    fun metricPathBreaksAtUnavailableEpochOrLargeCursorJump() {
        assertFalse(metricPathBreakBefore(100, 200, intArrayOf()))
        assertTrue(metricPathBreakBefore(100, 300, intArrayOf()))
        assertTrue(metricPathBreakBefore(100, 300, intArrayOf(200)))
        assertTrue(metricPathBreakBefore(-1, 100, intArrayOf()))
    }
}
