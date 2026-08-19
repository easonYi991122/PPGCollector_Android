package com.example.ppgcollector_android.core.signal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePpgAnalysisTest {
    @Test
    fun zeroPhaseProfilePreservesPulsePhaseAndRejectsDc() {
        val values = DoubleArray(1_600) { index ->
            100_000.0 + 900.0 * sin(2.0 * PI * 1.2 * index / 100.0)
        }

        val filtered = ZeroPhasePpgFilter.filter(
            values,
            PpgPreprocessingProfile.iosBaseline01,
        )

        val middle = 300 until 1_300
        val reference = middle.map { index -> sin(2.0 * PI * 1.2 * index / 100.0) }
        val observed = middle.map(filtered::get)
        assertTrue(observed.all(Double::isFinite))
        assertTrue(abs(observed.average()) < 2.0)
        assertTrue(pearson(reference, observed) > 0.995)
    }

    @Test
    fun requestedOfflineProfileIsZeroPhase05To12AndFixedMatchesLiveFirInterior() {
        val profile = PpgPreprocessingProfile.offlineBiquad05To12Hz01
        assertEquals(0.5, profile.lowCutoffHz, 0.0)
        assertEquals(12.0, profile.highCutoffHz, 0.0)
        assertEquals("offline-biquad-filtfilt-0.5-12hz-0.1", profile.identifier)

        val values = DoubleArray(600) { index ->
            700.0 * sin(2.0 * PI * 1.2 * index / 100.0) +
                120.0 * sin(2.0 * PI * 18.0 * index / 100.0)
        }
        val offlineFixed = OfflineFixedLagPpgFilter.filter(values)
        val runtime = FixedLagPpgFilterRuntime()
        repeat(values.size) { index ->
            runtime.ingest(index.toLong(), values[index], values[index]).forEach { output ->
                assertEquals(output.red, offlineFixed[output.sourceSampleIndex.toInt()], 1e-10)
            }
        }
        assertTrue(offlineFixed.take(100).all { it.isNaN() })
        assertTrue(offlineFixed.drop(500).all { it.isNaN() })
        assertEquals("fixed-lag-fir-0.5-12hz-0.1", OfflinePpgAnalyzer.fixedLagProfile)
    }

    @Test
    fun requestedOfflineZeroPhaseProfilePassesCardiacBandAndRejectsOutOfBandTones() {
        val values = DoubleArray(6_000) { index ->
            500.0 * sin(2.0 * PI * 0.2 * index / 100.0) +
                1_000.0 * sin(2.0 * PI * 2.0 * index / 100.0) +
                500.0 * sin(2.0 * PI * 20.0 * index / 100.0)
        }

        val filtered = ZeroPhasePpgFilter.filter(values)
        val interior = 1_000 until 5_000
        val cardiacAmplitude = sinusoidAmplitude(filtered, 2.0, interior)
        val driftAmplitude = sinusoidAmplitude(filtered, 0.2, interior)
        val noiseAmplitude = sinusoidAmplitude(filtered, 20.0, interior)

        assertTrue(cardiacAmplitude > 500.0)
        assertTrue(cardiacAmplitude > driftAmplitude * 4.0)
        assertTrue(cardiacAmplitude > noiseAmplitude * 4.0)
    }

    @Test
    fun zeroPhaseSosMatchesPythonScipyReferenceFields() {
        val values = DoubleArray(800) { index ->
            100_000.0 + 800.0 * sin(2.0 * PI * 1.2 * index / 100.0)
        }

        val filtered = ZeroPhasePpgFilter.filter(
            values,
            PpgPreprocessingProfile.iosBaseline01,
        )

        assertArrayEquals(
            doubleArrayOf(
                117.48069500232916, 172.31081392085133, 227.09408067754282,
                281.32796051941796, 334.54448273218094, 386.3135089032854,
                436.24413598841596, 483.984542333886,
            ),
            filtered.copyOfRange(0, 8),
            1e-8,
        )
        assertArrayEquals(
            doubleArrayOf(
                -801.3276417132455, -798.3632997121427, -790.8710750112258,
                -778.8938220176564, -762.4998739098303, -741.7826538939584,
                -716.8601438862117, -687.8742136398632,
            ),
            filtered.copyOfRange(396, 404),
            1e-8,
        )
    }

    @Test
    fun fullSignalFilterCoversEveryContinuityRunWithoutCrossingGap() {
        val count = 1_200
        val time = DoubleArray(count) { index ->
            index / 100.0 + if (index >= 600) 2.0 else 0.0
        }
        val red = DoubleArray(count) { index ->
            100_000.0 + 800.0 * sin(2.0 * PI * 1.2 * index / 100.0)
        }
        val ir = DoubleArray(count) { index ->
            120_000.0 + 1_000.0 * sin(2.0 * PI * 1.2 * index / 100.0)
        }

        val filtered = OfflinePpgAnalyzer.filterFullSignal(
            OfflinePpgInput(time, red, ir, breakIndices = intArrayOf(600)),
        )

        assertEquals(count, filtered.red.size)
        assertEquals(count, filtered.ir.size)
        assertTrue(filtered.red.all(Double::isFinite))
        assertTrue(filtered.ir.all(Double::isFinite))
        assertArrayEquals(
            ZeroPhasePpgFilter.filter(PpgDisplayTransform.rawPeakUp(red.copyOfRange(0, 600))),
            filtered.red.copyOfRange(0, 600),
            0.0,
        )
        assertArrayEquals(
            ZeroPhasePpgFilter.filter(PpgDisplayTransform.rawPeakUp(red.copyOfRange(600, count))),
            filtered.red.copyOfRange(600, count),
            0.0,
        )
    }

    @Test
    fun displaySpectrumTracksTheVisibleFullSignalRange() {
        val values = DoubleArray(4_000) { index ->
            800.0 * sin(2.0 * PI * 1.2 * index / 100.0)
        }

        val spectrum = OfflineDisplaySpectrum.estimate(values, 800 until 3_200)
        val peak = spectrum.power.indices.maxByOrNull(spectrum.power::get)

        assertNotNull(peak)
        assertEquals(1.25, spectrum.frequenciesHz[peak!!], 1e-10)
    }

    @Test
    fun stableSegmentsExcludeContactChangeAndRecoverDominantRate() {
        val count = 4_000
        val time = DoubleArray(count) { it / 100.0 }
        val red = DoubleArray(count) { index ->
            100_000.0 + 800.0 * sin(2.0 * PI * 1.2 * time[index])
        }
        val ir = DoubleArray(count) { index ->
            120_000.0 + 1_000.0 * sin(2.0 * PI * 1.2 * time[index])
        }
        for (index in 1_500 until 1_900) {
            red[index] = 8_000.0
            ir[index] = 10_000.0
        }
        red[3_000] += 35_000.0

        val result = OfflinePpgAnalyzer.analyze(OfflinePpgInput(time, red, ir))

        assertEquals(2, result.segments.size)
        assertEquals(2.0, result.segments[0].startSeconds, 0.01)
        assertEquals(13.25, result.segments[0].stopSeconds, 0.01)
        assertEquals(31.52, result.segments[1].startSeconds, 0.01)
        assertEquals(39.99, result.segments[1].stopSeconds, 0.01)
        assertEquals(0.4935, result.stableSampleRatio, 1e-10)
        assertEquals(4, result.windows.size)
        assertEquals("RED", result.selectedChannel)
        assertEquals("negative", result.selectedPolarity)
        assertEquals(22, result.peakIndices.size)
        assertNotNull(result.bpm)
        assertEquals(72.28915662650601, result.bpm!!, 1e-10)
        assertEquals(75.0, result.spectralBpm!!, 1e-10)
        assertTrue(result.confidence in 0.2..1.0)
        assertTrue(result.snrDb!!.isFinite())
        assertEquals(4.440892098500626e-16, result.rrMadSeconds!!, 1e-12)
        assertEquals(4, result.windows.count(OfflinePulseWindow::accepted))
        assertTrue(result.peakIndices.none { time[it] in 13.5..20.5 })
        assertTrue(result.peakIndices.none { time[it] in 28.5..31.5 })
        assertEquals(200, result.averageCycle.phase.size)
        assertTrue(result.averageCycle.cycleCount >= 2)
    }

    @Test
    fun integrityBreakCreatesGuardedSegmentsAndNoCrossGapCycle() {
        val count = 3_000
        val time = DoubleArray(count) { it / 100.0 }
        val red = DoubleArray(count) { index ->
            90_000.0 + 700.0 * sin(2.0 * PI * 1.1 * time[index])
        }
        val ir = DoubleArray(count) { index ->
            110_000.0 + 800.0 * sin(2.0 * PI * 1.1 * time[index])
        }

        val result = OfflinePpgAnalyzer.analyze(
            OfflinePpgInput(time, red, ir, breakIndices = intArrayOf(1_500)),
        )

        assertEquals(2, result.segments.size)
        assertTrue(result.segments[0].stopSeconds < 14.0)
        assertTrue(result.segments[1].startSeconds > 16.0)
        assertTrue(result.peakIndices.none { it in 1_350..1_650 })
    }

    @Test
    fun initialDriftIsExcludedUntilSustainedBaselineStability() {
        val count = 4_000
        val time = DoubleArray(count) { it / 100.0 }
        val red = DoubleArray(count) { index ->
            val settling = maxOf(0.0, 1.0 - time[index] / 12.0)
            100_000.0 + 9_000.0 * settling + 700.0 * sin(2.0 * PI * 1.2 * time[index])
        }
        val ir = DoubleArray(count) { index ->
            val settling = maxOf(0.0, 1.0 - time[index] / 12.0)
            125_000.0 + 16_000.0 * settling + 900.0 * sin(2.0 * PI * 1.2 * time[index])
        }

        val result = OfflinePpgAnalyzer.analyze(OfflinePpgInput(time, red, ir))

        assertTrue(result.segments.isNotEmpty())
        assertTrue(result.segments.first().startSeconds >= 10.0)
        assertNotNull(result.bpm)
        assertTrue(abs(result.bpm!! - 72.0) < 2.0)
    }

    @Test
    fun averageCycleRejectsInvertedOutlierAndReturnsConfidenceInterval() {
        val good = DoubleArray(101) { index -> sin(2.0 * PI * index / 100.0) }
        val values = good + good + good.map { -it }.toDoubleArray() + good
        val peaks = intArrayOf(0, 101, 202, 303, 403)

        val result = OfflinePpgAnalyzer.averageCycle(values, peaks)

        assertEquals(200, result.phase.size)
        assertEquals(200, result.mean.size)
        assertEquals(200, result.standardDeviation.size)
        assertEquals(200, result.ci95.size)
        assertTrue(result.cycleCount >= 2)
    }

    private fun pearson(left: List<Double>, right: List<Double>): Double {
        val leftMean = left.average()
        val rightMean = right.average()
        var numerator = 0.0
        var leftSquares = 0.0
        var rightSquares = 0.0
        left.indices.forEach { index ->
            val l = left[index] - leftMean
            val r = right[index] - rightMean
            numerator += l * r
            leftSquares += l * l
            rightSquares += r * r
        }
        return numerator / kotlin.math.sqrt(leftSquares * rightSquares)
    }

    private fun sinusoidAmplitude(values: DoubleArray, frequencyHz: Double, range: IntRange): Double {
        var sine = 0.0
        var cosine = 0.0
        range.forEach { index ->
            val angle = 2.0 * PI * frequencyHz * index / 100.0
            sine += values[index] * sin(angle)
            cosine += values[index] * cos(angle)
        }
        return 2.0 * sqrt(sine * sine + cosine * cosine) / range.count().toDouble()
    }
}
