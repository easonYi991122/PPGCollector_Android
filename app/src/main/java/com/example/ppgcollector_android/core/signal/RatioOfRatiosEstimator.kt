package com.example.ppgcollector_android.core.signal

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class RatioOfRatiosUnavailableReason(val wireValue: String) {
    INPUT_LENGTH_MISMATCH("inputLengthMismatch"),
    INSUFFICIENT_SAMPLES("insufficientSamples"),
    NON_FINITE_INPUT("nonFiniteInput"),
    INSUFFICIENT_DC("insufficientDC"),
    INSUFFICIENT_AC("insufficientAC"),
    NON_FINITE_RESULT("nonFiniteResult"),
}

data class RatioOfRatiosEstimate(
    val value: Double?,
    val redAcDcPercent: Double?,
    val irAcDcPercent: Double?,
    val unavailableReason: RatioOfRatiosUnavailableReason?,
    val algorithmVersion: String,
    val isProvisional: Boolean = true,
) {
    val isValid: Boolean
        get() = value != null && unavailableReason == null
}

/** Diagnostic-only RED/IR ratio-of-ratios; it never converts R to SpO2 or BP. */
object RatioOfRatiosEstimator {
    const val algorithmVersion = "ppg-ios-rr-0.1"

    fun estimate(
        redBandpassed: List<Double>,
        irBandpassed: List<Double>,
        redRaw: List<Double>,
        irRaw: List<Double>,
    ): RatioOfRatiosEstimate {
        val count = minOf(redBandpassed.size, irBandpassed.size, redRaw.size, irRaw.size)
        if (redBandpassed.size != irBandpassed.size ||
            redBandpassed.size != redRaw.size ||
            redBandpassed.size != irRaw.size
        ) {
            return unavailable(RatioOfRatiosUnavailableReason.INPUT_LENGTH_MISMATCH)
        }
        if (count < 400) return unavailable(RatioOfRatiosUnavailableReason.INSUFFICIENT_SAMPLES)

        val edge = min(count / 10, max(0, count / 2 - 1))
        val range = edge until (count - edge)
        val redBand = redBandpassed.slice(range)
        val irBand = irBandpassed.slice(range)
        val redBase = redRaw.slice(range)
        val irBase = irRaw.slice(range)
        if (listOf(redBand, irBand, redBase, irBase).any { values -> values.any { !it.isFinite() } }) {
            return unavailable(RatioOfRatiosUnavailableReason.NON_FINITE_INPUT)
        }

        val redDc = abs(mean(redBase))
        val irDc = abs(mean(irBase))
        if (redDc <= 1.0 || irDc <= 1.0) {
            return unavailable(RatioOfRatiosUnavailableReason.INSUFFICIENT_DC)
        }
        val redAc = rootMeanSquare(redBand)
        val irAc = rootMeanSquare(irBand)
        if (redAc <= 0.0 || irAc <= 0.0) {
            return unavailable(RatioOfRatiosUnavailableReason.INSUFFICIENT_AC)
        }
        val redAcDc = 100.0 * redAc / max(redDc, 1.0)
        val irAcDc = 100.0 * irAc / max(irDc, 1.0)
        val ratio = redAcDc / irAcDc
        if (!ratio.isFinite() || ratio < 0.0) {
            return unavailable(RatioOfRatiosUnavailableReason.NON_FINITE_RESULT)
        }
        return RatioOfRatiosEstimate(
            value = ratio,
            redAcDcPercent = redAcDc,
            irAcDcPercent = irAcDc,
            unavailableReason = null,
            algorithmVersion = algorithmVersion,
        )
    }

    private fun mean(values: List<Double>): Double = values.sum() / values.size.toDouble()

    private fun rootMeanSquare(values: List<Double>): Double = sqrt(
        values.sumOf { it * it } / values.size.toDouble(),
    )

    private fun unavailable(reason: RatioOfRatiosUnavailableReason) = RatioOfRatiosEstimate(
        value = null,
        redAcDcPercent = null,
        irAcDcPercent = null,
        unavailableReason = reason,
        algorithmVersion = algorithmVersion,
    )
}
