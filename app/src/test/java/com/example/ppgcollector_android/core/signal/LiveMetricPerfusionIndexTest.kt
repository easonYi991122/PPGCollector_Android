package com.example.ppgcollector_android.core.signal

import java.time.Instant
import kotlin.math.sin
import com.example.ppgcollector_android.data.session.CaptureMetricEpochFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMetricPerfusionIndexTest {
    @Test
    fun piReusesRedAcDcFromTheSameRatioEstimateAndSharesSourceCursor() {
        val values = (0 until 800).map { index -> sin(index / 14.0) }
        val request = LiveMetricAnalysisRequest(
            generation = 7,
            requestSequence = 3,
            windowEndSampleIndex = 799,
            windowEndTimeSeconds = 7.99,
            measuredAt = Instant.parse("2026-08-08T00:00:08Z"),
            rawRed = values.map { 1000.0 + it * 50.0 },
            rawIr = values.map { 2000.0 + it * 30.0 },
            bandpassedRed = values,
            bandpassedIr = values,
            timeSeconds = (0 until 800).map { it / 100.0 },
            metricEpoch = 11,
        )
        val result = LiveMetricAnalyzer.analyze(request)
        val ratio = RatioOfRatiosEstimator.estimate(
            request.bandpassedRed,
            request.bandpassedIr,
            request.rawRed,
            request.rawIr,
        )
        assertTrue(result.snapshot.ratioOfRatios.isValid)
        assertTrue(result.snapshot.perfusionIndex.isValid)
        assertEquals(
            result.snapshot.ratioOfRatios.sourceSampleIndex,
            result.snapshot.perfusionIndex.sourceSampleIndex,
        )
        assertEquals(
            result.snapshot.ratioOfRatios.sourceTimeSeconds,
            result.snapshot.perfusionIndex.sourceTimeSeconds,
        )
        assertEquals(
            ratio.redAcDcPercent!!,
            result.snapshot.perfusionIndex.value!!,
            1e-9,
        )
        assertEquals(11L, CaptureMetricEpochFactory.fromAnalysis("session", result).metricEpoch)
        assertEquals(result.snapshot.heartRateBpm.sourceSampleIndex, result.snapshot.signalQuality.sourceSampleIndex)
        assertEquals(result.snapshot.heartRateBpm.sourceSampleIndex, result.snapshot.ratioOfRatios.sourceSampleIndex)
        assertEquals(result.snapshot.heartRateBpm.sourceSampleIndex, result.snapshot.perfusionIndex.sourceSampleIndex)
        assertEquals(result.snapshot.heartRateBpm.sourceTimeSeconds, result.snapshot.signalQuality.sourceTimeSeconds)
        assertEquals(result.snapshot.heartRateBpm.sourceTimeSeconds, result.snapshot.ratioOfRatios.sourceTimeSeconds)
        assertEquals(result.snapshot.heartRateBpm.sourceTimeSeconds, result.snapshot.perfusionIndex.sourceTimeSeconds)
        assertEquals("ppg-pi-red-acdc-0.1", result.snapshot.perfusionIndex.algorithmVersion)
    }
}
