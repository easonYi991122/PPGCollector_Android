package com.example.ppgcollector_android.core.signal.ratio

import com.example.ppgcollector_android.core.signal.RatioOfRatiosEstimator
import com.example.ppgcollector_android.core.signal.RatioOfRatiosUnavailableReason
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RatioOfRatiosEstimatorTest {
    @Test
    fun computesTrimmedDiagnosticRatioAndKeepsItProvisional() {
        val sampleRate = 100.0
        val redRaw = (0 until 800).map { 1_000.0 + 50.0 * sin(2.0 * PI * 1.2 * it / sampleRate) }
        val irRaw = (0 until 800).map { 2_000.0 + 100.0 * sin(2.0 * PI * 1.2 * it / sampleRate) }
        val redBand = (0 until 800).map { 20.0 * sin(2.0 * PI * 1.2 * it / sampleRate) }
        val irBand = (0 until 800).map { 40.0 * sin(2.0 * PI * 1.2 * it / sampleRate) }

        val result = RatioOfRatiosEstimator.estimate(redBand, irBand, redRaw, irRaw)

        assertTrue(result.isValid)
        assertTrue(result.isProvisional)
        assertEquals(RatioOfRatiosEstimator.algorithmVersion, result.algorithmVersion)
        assertNotNull(result.value)
        assertTrue(abs(result.value!! - 1.0) < 0.03)
        val expectedAcDc = 100.0 * (20.0 / kotlin.math.sqrt(2.0)) / 1_000.0
        assertEquals(expectedAcDc, result.redAcDcPercent!!, 0.1)
        assertEquals(expectedAcDc, result.irAcDcPercent!!, 0.1)
    }

    @Test
    fun trimsTenPercentEdgesBeforeComputing() {
        val count = 500
        val redRaw = MutableList(count) { 1_000.0 }
        val irRaw = MutableList(count) { 1_000.0 }
        val redBand = MutableList(count) { 10.0 }
        val irBand = MutableList(count) { 20.0 }
        for (index in 0 until 50) {
            redRaw[index] = 0.0
            irRaw[index] = 0.0
            redBand[index] = 10_000.0
            irBand[index] = 10_000.0
            redRaw[count - 1 - index] = 0.0
            irRaw[count - 1 - index] = 0.0
            redBand[count - 1 - index] = 10_000.0
            irBand[count - 1 - index] = 10_000.0
        }

        val result = RatioOfRatiosEstimator.estimate(redBand, irBand, redRaw, irRaw)

        assertEquals(0.5, result.value!!, 1e-12)
        assertEquals(1.0, result.redAcDcPercent!!, 1e-12)
        assertEquals(2.0, result.irAcDcPercent!!, 1e-12)
    }

    @Test
    fun rejectsInputAndNumericalFailureReasons() {
        val base = List(400) { 1.0 }
        assertReason(
            RatioOfRatiosUnavailableReason.INPUT_LENGTH_MISMATCH,
            RatioOfRatiosEstimator.estimate(base, base.drop(1), List(400) { 1_000.0 }, List(400) { 1_000.0 }),
        )
        assertReason(
            RatioOfRatiosUnavailableReason.INSUFFICIENT_SAMPLES,
            RatioOfRatiosEstimator.estimate(List(399) { 1.0 }, List(399) { 1.0 }, List(399) { 1_000.0 }, List(399) { 1_000.0 }),
        )
        assertReason(
            RatioOfRatiosUnavailableReason.NON_FINITE_INPUT,
            RatioOfRatiosEstimator.estimate(List(400) { if (it == 200) Double.NaN else 1.0 }, base, List(400) { 1_000.0 }, List(400) { 1_000.0 }),
        )
        assertReason(
            RatioOfRatiosUnavailableReason.INSUFFICIENT_DC,
            RatioOfRatiosEstimator.estimate(base, base, List(400) { 1.0 }, List(400) { 2.0 }),
        )
        assertReason(
            RatioOfRatiosUnavailableReason.INSUFFICIENT_AC,
            RatioOfRatiosEstimator.estimate(List(400) { 0.0 }, List(400) { 1.0 }, List(400) { 1_000.0 }, List(400) { 1_000.0 }),
        )
        val overflow = List(400) { Double.MAX_VALUE }
        assertReason(
            RatioOfRatiosUnavailableReason.NON_FINITE_RESULT,
            RatioOfRatiosEstimator.estimate(overflow, overflow, List(400) { 1_000.0 }, List(400) { 1_000.0 }),
        )
    }

    private fun assertReason(
        reason: RatioOfRatiosUnavailableReason,
        actual: com.example.ppgcollector_android.core.signal.RatioOfRatiosEstimate,
    ) {
        assertFalse(actual.isValid)
        assertNull(actual.value)
        assertNull(actual.redAcDcPercent)
        assertNull(actual.irAcDcPercent)
        assertEquals(reason, actual.unavailableReason)
    }
}
