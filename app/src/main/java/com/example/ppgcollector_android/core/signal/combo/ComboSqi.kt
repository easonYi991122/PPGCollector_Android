package com.example.ppgcollector_android.core.signal.combo

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

    const val goodColor = "#2E7D32"
    const val fairColor = "#EF6C00"
    const val poorColor = "#C62828"
    const val unknownColor = "#888888"

    fun evaluate(rawIr: List<Double>, sampleRateHz: Int = 100): ComboSqiResult {
        if (rawIr.size < minimumSamples) return unknown()
        if (rawIr.any { !it.isFinite() } || sampleRateHz <= 0) return unknown()

        val mean = rawIr.average()
        val centered = rawIr.map { it - mean }
        val standardDeviation = sqrt(centered.map { it * it }.average())
        val relativePeakToPeak = (rawIr.maxOrNull()!! - rawIr.minOrNull()!!) /
            (abs(mean) + 1e-9)
        val flat = standardDeviation / (abs(mean) + 1e-9) < flatRelativeThreshold ||
            relativePeakToPeak < flatRelativeThreshold
        val correlation = autocorrelation(rawIr, sampleRateHz)
        val beatCount = estimateBeatCount(rawIr, sampleRateHz)
        val templateMatch = templateMatchScore(rawIr, sampleRateHz)
        val pressure = estimatePressure(rawIr, sampleRateHz, beatCount)
        return arbitrate(
            ComboSqiInputs(
                flat = flat,
                sqiCorrelation = correlation,
                templateMatch = templateMatch,
                pressureSeverity = pressure.first,
                pressureHeight = pressure.second,
                initialSteepness = pressure.third,
                beatCount = beatCount,
            ),
        )
    }

    fun arbitrate(inputs: ComboSqiInputs): ComboSqiResult {
        val hasRealBeats = inputs.beatCount >= minimumPressureBeats &&
            (inputs.sqiCorrelation ?: 0.0) >= flatCorrelationExemption
        if (inputs.flat == true && !hasRealBeats) {
            return ComboSqiResult(ComboSqiState.FLAT, "⚠ 信号平直 (0.00)", poorColor, 0.0)
        }

        val pressure = inputs.pressureSeverity
        val pressureTriggered = pressure != null &&
            pressure >= pressureSeverityFloor &&
            inputs.beatCount >= minimumPressureBeats &&
            (inputs.pressureHeight ?: 1.0) <= pressureHeightMaximum &&
            (inputs.initialSteepness ?: 0.0) >= steepnessMinimum
        if (pressureTriggered) {
            val score = ((inputs.templateMatch ?: 0.0) *
                (1.0 - pressurePenalty * pressure!!)).coerceIn(0.0, 1.0)
            val suffix = if (pressure >= pressureSevereThreshold) "（严重）" else ""
            return ComboSqiResult(
                ComboSqiState.PRESSURE,
                "⚠ 压力过大$suffix (${formatScore(score)})",
                poorColor,
                score,
            )
        }

        inputs.templateMatch?.let { score ->
            val bounded = score.coerceIn(0.0, 1.0)
            return if (bounded >= goodThreshold) {
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

    private fun autocorrelation(values: List<Double>, fs: Int): Double? {
        val mean = values.average()
        val centered = values.map { it - mean }
        val denominator = centered.sumOf { it * it }
        if (denominator <= 1e-9) return 0.0
        val minimumLag = max(1, (fs * 60.0 / 150.0).toInt())
        val maximumLag = min(values.lastIndex, (fs * 60.0 / 40.0).toInt())
        if (maximumLag <= minimumLag) return null
        return (minimumLag..maximumLag).maxOfOrNull { lag ->
            var product = 0.0
            for (index in 0 until values.size - lag) {
                product += centered[index] * centered[index + lag]
            }
            product / denominator
        }?.coerceIn(-1.0, 1.0)
    }

    private fun estimateBeatCount(values: List<Double>, fs: Int): Int {
        val mean = values.average()
        val deviation = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        if (deviation <= 1e-9) return 0
        val threshold = mean + deviation * 0.5
        val minimumDistance = max(1, (fs * 60.0 / 180.0).toInt())
        var lastPeak = -minimumDistance
        var count = 0
        for (index in 1 until values.lastIndex) {
            if (values[index] >= threshold &&
                values[index] >= values[index - 1] &&
                values[index] >= values[index + 1] &&
                index - lastPeak >= minimumDistance
            ) {
                count++
                lastPeak = index
            }
        }
        return count
    }

    private fun templateMatchScore(values: List<Double>, fs: Int): Double? {
        val beatCount = estimateBeatCount(values, fs)
        if (beatCount < 2) return null
        val lag = max(1, (values.size / beatCount.toDouble()).roundToInt())
        val pairs = values.drop(lag).zip(values)
        if (pairs.isEmpty()) return null
        val a = pairs.map { it.first }
        val b = pairs.map { it.second }
        val meanA = a.average()
        val meanB = b.average()
        val covariance = a.indices.sumOf { (a[it] - meanA) * (b[it] - meanB) }
        val scale = sqrt(
            a.sumOf { (it - meanA) * (it - meanA) } *
                b.sumOf { (it - meanB) * (it - meanB) },
        )
        return if (scale <= 1e-9) null else (covariance / scale).coerceIn(0.0, 1.0)
    }

    private fun estimatePressure(values: List<Double>, fs: Int, beatCount: Int): Triple<Double?, Double?, Double?> {
        if (beatCount < minimumPressureBeats) return Triple(null, null, null)
        val amplitude = values.maxOrNull()!! - values.minOrNull()!!
        if (amplitude <= 1e-9) return Triple(null, null, null)
        val derivative = values.zipWithNext().map { (a, b) -> abs(b - a) / amplitude * 100.0 }
        val steepness = derivative.take(max(1, (fs * 0.08).toInt())).maxOrNull() ?: 0.0
        val peaks = pressurePeaks(values, fs)
        val height = pressurePeakHeight(values, peaks, fs)
        val severity = ((steepness - 2.4) / 3.6).coerceIn(0.0, 1.0)
        return Triple(severity, height, steepness)
    }

    /**
     * Estimates the reference implementation's P2/P1 height ratio.  The old
     * placeholder used `amplitude * 0.5 / amplitude`, making the pressure
     * gate permanently true whenever the other three gates happened to pass.
     * This bounded local implementation uses the same 80 ms–0.6 s
     * physiological window and normalizes against the beat's own trough.
     */
    private fun pressurePeakHeight(values: List<Double>, peaks: List<Int>, fs: Int): Double {
        if (peaks.size < 2) return 1.0
        val ratios = ArrayList<Double>()
        peaks.zipWithNext().forEach { (peak, nextPeak) ->
            val beatLength = nextPeak - peak
            if (beatLength <= 0) return@forEach
            val end = min(nextPeak, min(values.lastIndex, peak + min((beatLength * 0.8).toInt(), (fs * 0.6).toInt())))
            val start = min(values.lastIndex, peak + max(1, (fs * 0.08).toInt()))
            if (end <= start) return@forEach
            val trough = values.subList(peak, nextPeak + 1).minOrNull() ?: return@forEach
            val p1Height = values[peak] - trough
            if (p1Height <= 1e-9) return@forEach
            val candidate = (start until end).maxByOrNull { values[it] }
            val p2Height = candidate?.let { (values[it] - trough) / p1Height } ?: 0.0
            ratios += p2Height.coerceIn(0.0, 1.0)
        }
        return ratios.sorted().let { sorted ->
            if (sorted.isEmpty()) 0.0 else sorted[sorted.size / 2]
        }
    }

    private fun pressurePeaks(values: List<Double>, fs: Int): List<Int> {
        if (values.size < 3) return emptyList()
        val mean = values.average()
        val deviation = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        if (deviation <= 1e-9) return emptyList()
        val threshold = mean + deviation * 0.5
        val minimumDistance = max(1, (fs * 60.0 / 180.0).toInt())
        var lastPeak = -minimumDistance
        return buildList {
            for (index in 1 until values.lastIndex) {
                if (values[index] >= threshold &&
                    values[index] >= values[index - 1] &&
                    values[index] >= values[index + 1] &&
                    index - lastPeak >= minimumDistance
                ) {
                    add(index)
                    lastPeak = index
                }
            }
        }
    }

    private fun formatScore(value: Double) = "%.2f".format(java.util.Locale.ROOT, value)
    private fun Double.roundToInt() = kotlin.math.round(this).toInt()
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
