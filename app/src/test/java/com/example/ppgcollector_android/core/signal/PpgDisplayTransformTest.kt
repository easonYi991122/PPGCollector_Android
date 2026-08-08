package com.example.ppgcollector_android.core.signal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PpgDisplayTransformTest {
    @Test
    fun rawDisplayNegatesFiniteSamplesWithoutMutatingStorageArray() {
        val stored = doubleArrayOf(100.0, -2.0, Double.NaN, Double.POSITIVE_INFINITY)
        val displayed = PpgDisplayTransform.rawPeakUp(stored)
        assertArrayEquals(doubleArrayOf(-100.0, 2.0, Double.NaN, Double.POSITIVE_INFINITY), displayed, 0.0)
        assertEquals(100.0, stored[0], 0.0)
    }
}

