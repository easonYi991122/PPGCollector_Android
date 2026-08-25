package com.example.ppgcollector_android.core.signal.combo

import com.example.ppgcollector_android.core.signal.SciPyPeakDetector
import com.example.ppgcollector_android.core.signal.TemplateMatchSqi
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class ComboSqiState {
    UNKNOWN,
    GOOD,
    UNSTABLE,
    FLAT,
    PRESSURE,
}

data class ComboSqiResult(
    val state: ComboSqiState,
    val text: String,
    val colorHex: String,
    val score: Double?,
)

data class ComboSqiInputs(
    val flat: Boolean?,
    val sqiCorrelation: Double?,
    val templateMatch: Double?,
    val pressureSeverity: Double?,
    val pressureHeight: Double?,
    val initialSteepness: Double?,
    val beatCount: Int,
)

object ComboSqi {
    const val minimumSamples = 500
    const val goodThreshold = 0.88
    const val minimumPressureBeats = 2
    const val flatRelativeThreshold = 0.0003
    const val flatCorrelationExemption = 0.30
    const val pressureSeverityFloor = 0.40
    const val pressureSevereThreshold = 0.80
    const val pressurePenalty = 0.85
    const val pressureHeightMaximum = 0.32
    const val steepnessMinimum = 4.0
    const val debounceFrames = 2
    const val pressureExitDebounceFrames = 2
    const val enablePressureBranch = true

    const val goodColor = "#2E7D32"
    const val fairColor = "#EF6C00"
    const val poorColor = "#C62828"
    const val unknownColor = "#888888"

    fun evaluate(rawIr: List<Double>, sampleRateHz: Int = 100): ComboSqiResult {
        val inputs = computeInputs(rawIr, sampleRateHz) ?: return unknown()
        return arbitrate(inputs)
    }

    fun computeInputs(rawIr: List<Double>, sampleRateHz: Int = 100): ComboSqiInputs? {
        if (rawIr.size < minimumSamples) return null
        if (rawIr.any { !it.isFinite() } || sampleRateHz <= 0) return null
        val values = DoubleArray(rawIr.size) { rawIr[it] }
        val flat = computeFlat(values)
        val correlation = runCatching { computeSqiCorr(values, sampleRateHz) }.getOrNull()
        val templateMatch = runCatching { computeSqiTm(values) }.getOrNull()
        val overpressure = if (enablePressureBranch) {
            ComboOverpressure.detect(values, sampleRateHz.toDouble())
        } else {
            null
        }
        return ComboSqiInputs(
            flat = flat,
            sqiCorrelation = correlation,
            templateMatch = templateMatch,
            pressureSeverity = overpressure?.let(ComboOverpressure::pressureSeverity),
            pressureHeight = overpressure?.p2Height,
            initialSteepness = overpressure?.initSteep,
            beatCount = overpressure?.nBeats ?: 0,
        )
    }

    fun arbitrate(inputs: ComboSqiInputs): ComboSqiResult {
        val hasRealBeats = inputs.beatCount >= minimumPressureBeats &&
            inputs.sqiCorrelation != null &&
            inputs.sqiCorrelation >= flatCorrelationExemption
        if (inputs.flat == true && !hasRealBeats) {
            return ComboSqiResult(ComboSqiState.FLAT, "⚠ 信号平直 (0.00)", poorColor, 0.0)
        }

        val pressure = inputs.pressureSeverity
        val pressureTriggered = enablePressureBranch &&
            pressure != null &&
            pressure >= pressureSeverityFloor &&
            inputs.beatCount >= minimumPressureBeats &&
            (inputs.pressureHeight ?: 1.0) <= pressureHeightMaximum &&
            (inputs.initialSteepness ?: 0.0) >= steepnessMinimum
        if (pressureTriggered) {
            val score = roundScore(
                ((inputs.templateMatch ?: 0.0) * (1.0 - pressurePenalty * pressure!!))
                    .coerceAtLeast(0.0),
            )
            val suffix = if (pressure >= pressureSevereThreshold) "(严重)" else ""
            return ComboSqiResult(
                ComboSqiState.PRESSURE,
                "⚠ 压力过大$suffix (${formatScore(score)})",
                poorColor,
                score,
            )
        }

        inputs.templateMatch?.let { quality ->
            val bounded = roundScore(quality)
            return if (quality >= goodThreshold) {
                ComboSqiResult(ComboSqiState.GOOD, "信号良好 (${formatScore(bounded)})", goodColor, bounded)
            } else {
                ComboSqiResult(
                    ComboSqiState.UNSTABLE,
                    "信号不稳定 (${formatScore(bounded)})",
                    fairColor,
                    bounded,
                )
            }
        }
        if (inputs.flat == true && hasRealBeats) {
            return ComboSqiResult(ComboSqiState.UNSTABLE, "⚠ 信号幅度过低", fairColor, 0.0)
        }
        return unknown()
    }

    fun unknown() = ComboSqiResult(ComboSqiState.UNKNOWN, "综合SQI: --", unknownColor, null)

    internal fun computeFlat(values: DoubleArray): Boolean? {
        if (values.size < 2) return null
        val mean = values.average()
        val dc = abs(mean) + 1e-9
        val std = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        val peakToPeak = values.maxOrNull()!! - values.minOrNull()!!
        return std / dc < flatRelativeThreshold || peakToPeak / dc < flatRelativeThreshold
    }

    internal fun computeSqiCorr(rawIr: DoubleArray, sampleRateHz: Int): Double? {
        if (rawIr.size < sampleRateHz * 2) return null
        val smoothed = ComboSqiFilters.correlationInput(rawIr)
        return autocorrSqi(smoothed, sampleRateHz.toDouble())
    }

    internal fun computeSqiTm(rawIr: DoubleArray): Double? {
        val filtered = ComboSqiFilters.templateMatchInput(rawIr)
        val estimate = TemplateMatchSqi.compute(filtered.toList())
        return if (estimate.isValid) estimate.rawMeanQuality else null
    }

    /**
     * `ppg_metrics.autocorr_sqi`: demean, positive-lag autocorrelation, max peak
     * in the 40–150 bpm delay range with height ≥ 0.1.
     */
    internal fun autocorrSqi(signal: DoubleArray, fs: Double, hrMin: Double = 40.0, hrMax: Double = 150.0): Double {
        val mean = signal.average()
        val centered = DoubleArray(signal.size) { signal[it] - mean }
        val autocorr = DoubleArray(centered.size)
        for (lag in centered.indices) {
            var product = 0.0
            for (index in 0 until centered.size - lag) {
                product += centered[index] * centered[index + lag]
            }
            autocorr[lag] = product
        }
        if (autocorr.isEmpty() || autocorr[0] == 0.0) return 0.0
        val scale = autocorr[0]
        for (index in autocorr.indices) autocorr[index] /= scale
        val minIndex = (60.0 / hrMax * fs).toInt()
        val maxIndex = (60.0 / hrMin * fs).toInt()
        if (autocorr.size <= maxIndex) return 0.0
        val search = autocorr.copyOfRange(minIndex, maxIndex)
        val peaks = SciPyPeakDetector.findPeaks(
            values = search.toList(),
            distance = 1,
            minimumHeight = 0.1,
            minimumProminence = 0.0,
        ).indices
        if (peaks.isEmpty()) return 0.0
        val best = peaks.maxBy { search[it] }
        return autocorr[best + minIndex].coerceIn(-1.0, 1.0)
    }

    private fun formatScore(value: Double) = "%.2f".format(java.util.Locale.ROOT, value)

    private fun roundScore(value: Double) =
        "%.2f".format(java.util.Locale.ROOT, value).toDouble()
}

class ComboSqiDebounce {
    var current: ComboSqiResult = ComboSqi.unknown()
        private set

    private var candidateState: ComboSqiState? = null
    private var candidateFrames = 0

    fun update(candidate: ComboSqiResult): ComboSqiResult {
        if (candidate.state == current.state) {
            candidateState = null
            candidateFrames = 0
            current = candidate
            return current
        }
        if (candidateState == candidate.state) {
            candidateFrames++
        } else {
            candidateState = candidate.state
            candidateFrames = 1
        }
        val required = if (current.state == ComboSqiState.PRESSURE) {
            ComboSqi.pressureExitDebounceFrames
        } else {
            ComboSqi.debounceFrames
        }
        if (candidateFrames >= required) {
            current = candidate
            candidateState = null
            candidateFrames = 0
        }
        return current
    }

    fun reset() {
        current = ComboSqi.unknown()
        candidateState = null
        candidateFrames = 0
    }
}
