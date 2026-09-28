package com.example.ppgcollector_android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricTimelinePolicyTest {
    @Test fun offlineTwoSecondCadenceDrawsAContinuousLine() {
        val points = listOf(600 to 70.0, 800 to 71.0, 1000 to 72.0)
        org.junit.Assert.assertEquals(listOf(points), metricSegments(points, intArrayOf(), 200))
    }

    @Test fun rejectedWindowLeavesTwoVisibleSingletonsAndMetricsAreIndependent() {
        val points = listOf(600 to 70.0, 1000 to 72.0)
        org.junit.Assert.assertEquals(listOf(listOf(points[0]), listOf(points[1])),
            metricSegments(points, intArrayOf(800), 200))
        val complete = listOf(600 to 1.0, 800 to 2.0, 1000 to 3.0)
        org.junit.Assert.assertEquals(1, metricSegments(complete, intArrayOf(), 200).size)
        org.junit.Assert.assertEquals(2, metricSegments(complete, intArrayOf(800), 200).size)
    }

    @Test
    fun metricPathBreaksAtUnavailableEpochOrLargeCursorJump() {
        assertFalse(metricPathBreakBefore(100, 200, intArrayOf()))
        assertTrue(metricPathBreakBefore(100, 300, intArrayOf()))
        assertTrue(metricPathBreakBefore(100, 300, intArrayOf(200)))
        assertTrue(metricPathBreakBefore(-1, 100, intArrayOf()))
    }
}
