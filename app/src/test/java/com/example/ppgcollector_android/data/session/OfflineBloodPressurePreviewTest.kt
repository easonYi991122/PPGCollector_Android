package com.example.ppgcollector_android.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineBloodPressurePreviewTest {
    @Test
    fun fallbackUsesEightSecondWarmupOneHertzCadenceAndResetsAtGap() {
        val time = DoubleArray(2_400) { index -> index / 100.0 + if (index >= 1_200) 2.0 else 0.0 }

        val preview = OfflineBloodPressurePreviewFactory.create(
            timeSeconds = time,
            breakIndices = intArrayOf(1_200),
        )

        assertFalse(preview.modelAvailable)
        assertTrue(preview.isPlaceholder)
        assertEquals(listOf(799L, 899L, 999L, 1_099L, 1_999L, 2_099L, 2_199L, 2_299L), preview.points.map { it.sourceSampleIndex })
        assertTrue(preview.points.all { it.systolicMmHg == 120.0 && it.diastolicMmHg == 80.0 })
    }

    @Test
    fun persistedMetricEpochsDefinePlaceholderPredictionTimes() {
        val metrics = listOf(
            CaptureMetricTimelinePoint(1, 800, 8.0, 72.0, 0.9, 0.5, 1.2),
            CaptureMetricTimelinePoint(2, 900, 9.0, 73.0, 0.8, 0.5, 1.3),
        )

        val preview = OfflineBloodPressurePreviewFactory.create(
            timeSeconds = DoubleArray(1_100) { it / 100.0 },
            metricTimeline = metrics,
        )

        assertEquals(listOf(800L, 900L), preview.points.map { it.sourceSampleIndex })
        assertEquals(listOf(8.0, 9.0), preview.points.map { it.sourceTimeSeconds })
    }
}
