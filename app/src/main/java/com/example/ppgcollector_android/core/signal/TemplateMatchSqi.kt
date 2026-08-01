package com.example.ppgcollector_android.core.signal

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class TemplateMatchSqiGrade(val wireValue: String, val colorHex: String) {
    GOOD("Good", "#2E7D32"),
    FAIR("Fair", "#EF6C00"),
    POOR("Poor", "#C62828"),
    UNAVAILABLE("—", "#888888"),
}

sealed interface TemplateMatchSqiUnavailableReason {
    val referenceCode: String

    data object InvalidConfiguration : TemplateMatchSqiUnavailableReason {
        override val referenceCode = "invalid_configuration"
    }
    data object NonFiniteInput : TemplateMatchSqiUnavailableReason {
        override val referenceCode = "non_finite_input"
    }
    data object SignalTooShort : TemplateMatchSqiUnavailableReason {
        override val referenceCode = "signal_too_short"
    }
    data object ConstantSignal : TemplateMatchSqiUnavailableReason {
        override val referenceCode = "constant_signal"
    }
    data class TemplateFailed(val detail: String) : TemplateMatchSqiUnavailableReason {
        override val referenceCode = "template_failed:$detail"
    }
    data object NoCycleQuality : TemplateMatchSqiUnavailableReason {
        override val referenceCode = "no_cycle_quality"
    }
}

enum class SqiPeakDetectionMode(val wireValue: String) { PRIMARY("primary"), FALLBACK("fallback") }

data class TemplateMatchSqiConfiguration(
    val algorithmVersion: String,
    val preprocessProfile: String,
    val sampleRateHz: Double,
    val ratioPre: Double,
    val maximumHeartRateBpm: Double,
    val minimumWindowSeconds: Double,
    val standardDeviationEpsilon: Double,
    val primaryHeightStandardDeviationFactor: Double,
    val primaryProminenceStandardDeviationFactor: Double,
    val primaryMinimumWidthSamples: Double,
    val fallbackHeightStandardDeviationFactor: Double,
    val fallbackProminenceStandardDeviationFactor: Double,
    val goodThreshold: Double,
    val fairThreshold: Double,
) {
    companion object {
        val iosBaseline01 = TemplateMatchSqiConfiguration(
            algorithmVersion = "ppg-ios-sqi-0.1",
            preprocessProfile = "ios_baseline_0.1",
            sampleRateHz = 100.0,
            ratioPre = 0.5,
            maximumHeartRateBpm = 180.0,
            minimumWindowSeconds = 4.0,
            standardDeviationEpsilon = 1e-8,
            primaryHeightStandardDeviationFactor = 0.5,
            primaryProminenceStandardDeviationFactor = 0.3,
            primaryMinimumWidthSamples = 5.0,
            fallbackHeightStandardDeviationFactor = 0.2,
            fallbackProminenceStandardDeviationFactor = 0.2,
            goodThreshold = 0.90,
            fairThreshold = 0.70,
        )
    }
}

data class SqiPeakPassTrace(
    val minimumHeight: Double,
    val minimumProminence: Double,
    val minimumWidthSamples: Double?,
    val peaks: List<SciPyPeak>,
    val wasEvaluated: Boolean? = null,
)

data class SqiPeakDetectionTrace(
    val mean: Double,
    val standardDeviation: Double,
    val minimumDistanceSamples: Int,
    val primary: SqiPeakPassTrace,
    val fallbackWasEvaluated: Boolean,
    val fallback: SqiPeakPassTrace,
    val selectedMode: SqiPeakDetectionMode,
    val selectedPeakIndices: List<Int>,
)

data class SqiCycleTrace(
    val error: String?,
    val rateBpm: Double?,
    val preSamples: Int?,
    val postSamples: Int?,
    val windowLength: Int?,
    val timeAxisSeconds: List<Double>,
    val cycleValidMask: List<Boolean>,
    val droppedPeakIndices: List<Int>,
    val validPeakIndices: List<Int>,
    val cycles: List<List<Double>>,
    val template: List<Double>,
    val qualityAnchorPeakIndices: List<Int>,
    val cycleQuality: List<Double>,
    val qualityTrace: List<Double>,
)

data class TemplateMatchSqiDebugTrace(
    val peakDetection: SqiPeakDetectionTrace?,
    val cycles: SqiCycleTrace,
)

data class TemplateMatchSqiEstimate(
    val sqi: Double?,
    val rawMeanQuality: Double?,
    val isValid: Boolean,
    val unavailableReason: TemplateMatchSqiUnavailableReason?,
    val grade: TemplateMatchSqiGrade,
    val cycleCount: Int,
    val peakCount: Int,
    val estimatedHeartRateBpm: Double?,
    val algorithmVersion: String,
    val preprocessProfile: String,
    val trace: TemplateMatchSqiDebugTrace,
) {
    val isProvisional: Boolean = true
    val reasonCode: String
        get() = if (isValid) "ok" else unavailableReason?.referenceCode ?: "no_cycle_quality"
}

object TemplateMatchSqi {
    fun compute(
        preprocessedPeakUpValues: List<Double>,
        configuration: TemplateMatchSqiConfiguration = TemplateMatchSqiConfiguration.iosBaseline01,
    ): TemplateMatchSqiEstimate {
        if (!configurationIsValid(configuration)) return unavailable(
            TemplateMatchSqiUnavailableReason.InvalidConfiguration, configuration,
        )
        if (preprocessedPeakUpValues.any { !it.isFinite() }) return unavailable(
            TemplateMatchSqiUnavailableReason.NonFiniteInput, configuration,
        )
        if (preprocessedPeakUpValues.size <
            (configuration.sampleRateHz * configuration.minimumWindowSeconds).toInt()
        ) return unavailable(TemplateMatchSqiUnavailableReason.SignalTooShort, configuration)

        val signalMean = mean(preprocessedPeakUpValues)
        val standardDeviation = populationStandardDeviation(preprocessedPeakUpValues, signalMean)
        if (standardDeviation < configuration.standardDeviationEpsilon) return unavailable(
            TemplateMatchSqiUnavailableReason.ConstantSignal, configuration,
        )

        val peakTrace = detectPeaks(preprocessedPeakUpValues, signalMean, standardDeviation, configuration)
        val selectedPeaks = peakTrace.selectedPeakIndices
        val heartRate = estimatedHeartRate(selectedPeaks, configuration.sampleRateHz)
        val cycleResult = buildCycleTrace(preprocessedPeakUpValues, selectedPeaks, configuration)
        val debugTrace = TemplateMatchSqiDebugTrace(peakTrace, cycleResult)
        cycleResult.error?.let { error ->
            return unavailable(
                TemplateMatchSqiUnavailableReason.TemplateFailed(error),
                configuration,
                peakCount = selectedPeaks.size,
                estimatedHeartRateBpm = heartRate,
                trace = debugTrace,
            )
        }
        if (cycleResult.cycleQuality.isEmpty()) return unavailable(
            TemplateMatchSqiUnavailableReason.NoCycleQuality,
            configuration,
            peakCount = selectedPeaks.size,
            estimatedHeartRateBpm = heartRate,
            trace = debugTrace,
        )

        val rawQuality = mean(cycleResult.cycleQuality)
        val displaySqi = min(1.0, max(0.0, rawQuality))
        return TemplateMatchSqiEstimate(
            sqi = displaySqi,
            rawMeanQuality = rawQuality,
            isValid = true,
            unavailableReason = null,
            grade = grade(rawQuality, configuration),
            cycleCount = cycleResult.cycleQuality.size,
            peakCount = selectedPeaks.size,
            estimatedHeartRateBpm = heartRate,
            algorithmVersion = configuration.algorithmVersion,
            preprocessProfile = configuration.preprocessProfile,
            trace = debugTrace,
        )
    }

    private fun configurationIsValid(configuration: TemplateMatchSqiConfiguration): Boolean =
        configuration.sampleRateHz.isFinite() && configuration.sampleRateHz > 0.0 &&
            configuration.ratioPre.isFinite() && configuration.ratioPre in 0.0..1.0 &&
            configuration.maximumHeartRateBpm.isFinite() && configuration.maximumHeartRateBpm > 0.0 &&
            configuration.minimumWindowSeconds.isFinite() && configuration.minimumWindowSeconds > 0.0 &&
            configuration.standardDeviationEpsilon.isFinite() && configuration.standardDeviationEpsilon >= 0.0 &&
            configuration.primaryHeightStandardDeviationFactor.isFinite() &&
            configuration.primaryProminenceStandardDeviationFactor.isFinite() &&
            configuration.primaryMinimumWidthSamples.isFinite() && configuration.primaryMinimumWidthSamples >= 0.0 &&
            configuration.fallbackHeightStandardDeviationFactor.isFinite() &&
            configuration.fallbackProminenceStandardDeviationFactor.isFinite() &&
            configuration.goodThreshold.isFinite() && configuration.fairThreshold.isFinite() &&
            configuration.goodThreshold >= configuration.fairThreshold

    private fun detectPeaks(
        values: List<Double>,
        mean: Double,
        standardDeviation: Double,
        configuration: TemplateMatchSqiConfiguration,
    ): SqiPeakDetectionTrace {
        val minimumDistance = maxOf(
            1,
            (configuration.sampleRateHz * 60.0 / configuration.maximumHeartRateBpm).toInt(),
        )
        val primaryHeight = mean + configuration.primaryHeightStandardDeviationFactor * standardDeviation
        val primaryProminence = configuration.primaryProminenceStandardDeviationFactor * standardDeviation
        val primaryPeaks = SciPyPeakDetector.findPeaks(
            values, minimumDistance, primaryHeight, primaryProminence,
            configuration.primaryMinimumWidthSamples,
        )
        val primaryTrace = SqiPeakPassTrace(
            primaryHeight, primaryProminence, configuration.primaryMinimumWidthSamples, primaryPeaks.peaks,
        )
        val fallbackHeight = mean + configuration.fallbackHeightStandardDeviationFactor * standardDeviation
        val fallbackProminence = configuration.fallbackProminenceStandardDeviationFactor * standardDeviation
        if (primaryPeaks.peaks.size >= 2) {
            return SqiPeakDetectionTrace(
                mean, standardDeviation, minimumDistance, primaryTrace, false,
                SqiPeakPassTrace(fallbackHeight, fallbackProminence, null, emptyList(), false),
                SqiPeakDetectionMode.PRIMARY, primaryPeaks.indices,
            )
        }
        val fallbackPeaks = SciPyPeakDetector.findPeaks(
            values, minimumDistance, fallbackHeight, fallbackProminence,
        )
        return SqiPeakDetectionTrace(
            mean, standardDeviation, minimumDistance, primaryTrace, true,
            SqiPeakPassTrace(fallbackHeight, fallbackProminence, null, fallbackPeaks.peaks, true),
            SqiPeakDetectionMode.FALLBACK, fallbackPeaks.indices,
        )
    }

    private fun estimatedHeartRate(peaks: List<Int>, sampleRateHz: Double): Double? {
        val differences = peaks.zipWithNext().map { it.second - it.first }.filter { it > 0 }
        if (differences.isEmpty()) return null
        return 60.0 * sampleRateHz / median(differences.map { it.toDouble() })
    }

    private fun buildCycleTrace(
        values: List<Double>,
        peaks: List<Int>,
        configuration: TemplateMatchSqiConfiguration,
    ): SqiCycleTrace {
        val sortedPeaks = peaks.toSet().sorted()
        if (sortedPeaks.size < 2) return emptyCycleTrace("at least two cycle indices required")
        val differences = sortedPeaks.zipWithNext().map { it.second - it.first }.filter { it > 0 }
        if (differences.isEmpty()) return emptyCycleTrace("cycle indices must be increasing")
        val meanDifference = differences.sum().toDouble() / differences.size.toDouble()
        val rateBpm = 60.0 * configuration.sampleRateHz / meanDifference
        val preSamples = Math.rint(configuration.ratioPre * meanDifference).toInt()
        val postSamples = Math.rint((1.0 - configuration.ratioPre) * meanDifference).toInt()
        val windowLength = preSamples + postSamples + 1
        val timeAxis = (0 until windowLength).map {
            (it - preSamples).toDouble() / configuration.sampleRateHz
        }
        val validMask = ArrayList<Boolean>()
        val droppedPeaks = ArrayList<Int>()
        val validPeaks = ArrayList<Int>()
        val cycles = ArrayList<List<Double>>()
        sortedPeaks.forEach { peak ->
            val start = peak - preSamples
            val end = peak + postSamples + 1
            val complete = start >= 0 && end <= values.size
            validMask += complete
            if (complete) {
                validPeaks += peak
                cycles += values.subList(start, end).toList()
            } else droppedPeaks += peak
        }
        if (cycles.isEmpty()) return emptyCycleTrace("no complete cycle after NaN filtering")
            .copy(
                rateBpm = rateBpm, preSamples = preSamples, postSamples = postSamples,
                windowLength = windowLength, timeAxisSeconds = timeAxis,
                cycleValidMask = validMask, droppedPeakIndices = droppedPeaks,
                validPeakIndices = validPeaks, cycles = cycles,
            )
        val template = (0 until windowLength).map { index ->
            cycles.sumOf { it[index] } / cycles.size.toDouble()
        }
        if (validPeaks.size < 2) return SqiCycleTrace(
            error = "need >= 2 valid cycles to build quality trace",
            rateBpm = rateBpm, preSamples = preSamples, postSamples = postSamples,
            windowLength = windowLength, timeAxisSeconds = timeAxis,
            cycleValidMask = validMask, droppedPeakIndices = droppedPeaks,
            validPeakIndices = validPeaks, cycles = cycles, template = template,
            qualityAnchorPeakIndices = emptyList(), cycleQuality = emptyList(), qualityTrace = emptyList(),
        )
        val cycleQuality = cycles.dropLast(1).map { pearson(it, template) }
        if (cycleQuality.isEmpty()) return SqiCycleTrace(
            error = null, rateBpm = rateBpm, preSamples = preSamples, postSamples = postSamples,
            windowLength = windowLength, timeAxisSeconds = timeAxis,
            cycleValidMask = validMask, droppedPeakIndices = droppedPeaks,
            validPeakIndices = validPeaks, cycles = cycles, template = template,
            qualityAnchorPeakIndices = emptyList(), cycleQuality = emptyList(), qualityTrace = emptyList(),
        )
        val anchors = validPeaks.dropLast(1)
        val qualityTrace = MutableList(values.size) { cycleQuality[0] }
        if (anchors.size > 1) {
            for (index in 0 until anchors.size - 1) {
                for (sampleIndex in anchors[index] until anchors[index + 1]) qualityTrace[sampleIndex] = cycleQuality[index]
            }
        }
        for (sampleIndex in anchors.last() until values.size) qualityTrace[sampleIndex] = cycleQuality.last()
        return SqiCycleTrace(
            error = null, rateBpm = rateBpm, preSamples = preSamples, postSamples = postSamples,
            windowLength = windowLength, timeAxisSeconds = timeAxis,
            cycleValidMask = validMask, droppedPeakIndices = droppedPeaks,
            validPeakIndices = validPeaks, cycles = cycles, template = template,
            qualityAnchorPeakIndices = anchors, cycleQuality = cycleQuality, qualityTrace = qualityTrace,
        )
    }

    private fun pearson(left: List<Double>, right: List<Double>): Double {
        val leftMean = mean(left)
        val rightMean = mean(right)
        var numerator = 0.0
        var leftSquares = 0.0
        var rightSquares = 0.0
        left.indices.forEach { index ->
            val centeredLeft = left[index] - leftMean
            val centeredRight = right[index] - rightMean
            numerator += centeredLeft * centeredRight
            leftSquares += centeredLeft * centeredLeft
            rightSquares += centeredRight * centeredRight
        }
        val denominator = sqrt(leftSquares * rightSquares)
        return if (denominator > 0.0) numerator / denominator else Double.NaN
    }

    private fun grade(rawQuality: Double, configuration: TemplateMatchSqiConfiguration) = when {
        rawQuality >= configuration.goodThreshold -> TemplateMatchSqiGrade.GOOD
        rawQuality >= configuration.fairThreshold -> TemplateMatchSqiGrade.FAIR
        else -> TemplateMatchSqiGrade.POOR
    }

    private fun mean(values: List<Double>) = values.sum() / values.size.toDouble()

    private fun populationStandardDeviation(values: List<Double>, mean: Double) = sqrt(
        values.sumOf { (it - mean) * (it - mean) } / values.size.toDouble(),
    )

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    private fun emptyCycleTrace(error: String? = null) = SqiCycleTrace(
        error, null, null, null, null, emptyList(), emptyList(), emptyList(), emptyList(),
        emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
    )

    private fun unavailable(
        reason: TemplateMatchSqiUnavailableReason,
        configuration: TemplateMatchSqiConfiguration,
        peakCount: Int = 0,
        estimatedHeartRateBpm: Double? = null,
        trace: TemplateMatchSqiDebugTrace? = null,
    ) = TemplateMatchSqiEstimate(
        sqi = null, rawMeanQuality = null, isValid = false,
        unavailableReason = reason, grade = TemplateMatchSqiGrade.UNAVAILABLE,
        cycleCount = 0, peakCount = peakCount, estimatedHeartRateBpm = estimatedHeartRateBpm,
        algorithmVersion = configuration.algorithmVersion,
        preprocessProfile = configuration.preprocessProfile,
        trace = trace ?: TemplateMatchSqiDebugTrace(null, emptyCycleTrace()),
    )
}
