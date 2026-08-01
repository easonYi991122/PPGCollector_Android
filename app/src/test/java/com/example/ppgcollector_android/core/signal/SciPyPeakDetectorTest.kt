package com.example.ppgcollector_android.core.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SciPyPeakDetectorTest {
    @Test
    fun plateauUsesItsMidpointAndReportsBases() {
        val result = SciPyPeakDetector.findPeaks(
            values = listOf(0.0, 1.0, 1.0, 1.0, 0.0),
            distance = 1,
            minimumProminence = 0.5,
        )

        assertEquals(listOf(2), result.indices)
        val peak = result.peaks.single()
        assertEquals(1.0, peak.height, 0.0)
        assertEquals(1.0, peak.prominence, 0.0)
        assertEquals(0, peak.leftBaseIndex)
        assertEquals(4, peak.rightBaseIndex)
        assertEquals(null, peak.width)
    }

    @Test
    fun distancePruningKeepsHigherPeakAndTiePrefersLaterCandidate() {
        val higher = SciPyPeakDetector.findPeaks(
            values = listOf(0.0, 2.0, 0.0, 3.0, 0.0),
            distance = 3,
            minimumProminence = 0.0,
        )
        assertEquals(listOf(3), higher.indices)

        val tie = SciPyPeakDetector.findPeaks(
            values = listOf(0.0, 2.0, 0.0, 2.0, 0.0),
            distance = 3,
            minimumProminence = 0.0,
        )
        assertEquals(listOf(3), tie.indices)
    }

    @Test
    fun prominenceAndHalfProminenceWidthUseLinearIntersections() {
        val result = SciPyPeakDetector.findPeaks(
            values = listOf(0.0, 1.0, 0.0, 2.0, 0.0),
            distance = 1,
            minimumProminence = 0.5,
            minimumWidth = 1.0,
        )

        assertEquals(listOf(1, 3), result.indices)
        result.peaks.forEachIndexed { index, peak ->
            assertEquals(peak.prominence, peak.height, 0.0)
            assertEquals(1.0, peak.width!!, 0.0)
            if (index == 0) {
                assertEquals(0.5, peak.widthHeight!!, 0.0)
                assertEquals(0.5, peak.leftIntersection!!, 0.0)
                assertEquals(1.5, peak.rightIntersection!!, 0.0)
            } else {
                assertEquals(1.0, peak.widthHeight!!, 0.0)
                assertEquals(2.5, peak.leftIntersection!!, 0.0)
                assertEquals(3.5, peak.rightIntersection!!, 0.0)
            }
        }
    }

    @Test
    fun filtersHeightProminenceWidthAndShortInputWithoutInventingPeaks() {
        assertTrue(
            SciPyPeakDetector.findPeaks(
                values = listOf(1.0, 2.0),
                distance = 1,
                minimumProminence = 0.0,
            ).peaks.isEmpty(),
        )
        assertEquals(
            listOf(3),
            SciPyPeakDetector.findPeaks(
                values = listOf(0.0, 1.0, 0.0, 2.0, 0.0),
                distance = 1,
                minimumHeight = 1.5,
                minimumProminence = 0.5,
            ).indices,
        )
        assertTrue(
            SciPyPeakDetector.findPeaks(
                values = listOf(0.0, 1.0, 0.0),
                distance = 1,
                minimumProminence = 1.1,
            ).peaks.isEmpty(),
        )
    }
}
