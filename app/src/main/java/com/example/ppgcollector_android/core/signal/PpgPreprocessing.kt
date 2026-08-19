package com.example.ppgcollector_android.core.signal

import kotlin.math.sqrt

enum class PpgPolarityTransform(val wireValue: String) {
    PRESERVE("preserve"),
    INVERT("invert"),
    ;

    fun apply(value: Double): Double = when (this) {
        PRESERVE -> value
        INVERT -> -value
    }
}

enum class PpgStreamBoundary { CONTINUOUS, GAP }

enum class PpgPreprocessingUnavailableReason(val wireValue: String) {
    EMPTY_WINDOW("emptyWindow"),
    NON_FINITE_INPUT("nonFiniteInput"),
    CONSTANT_SIGNAL("constantSignal"),
}

data class PpgSecondOrderSection(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a0: Double,
    val a1: Double,
    val a2: Double,
)

data class PpgPreprocessingProfile(
    val identifier: String,
    val sampleRateHz: Double,
    val dcTimeConstantSeconds: Double,
    val dcAlpha: Double,
    val lowCutoffHz: Double,
    val highCutoffHz: Double,
    val filterOrder: Int,
    val sections: List<PpgSecondOrderSection>,
    val sqiPolarityTransform: PpgPolarityTransform,
    val standardDeviationEpsilon: Double,
) {
    companion object {
        /** Fixed coefficients from SciPy 1.17.1 butter(3, [0.6, 4], fs=100). */
        val iosBaseline01 = PpgPreprocessingProfile(
            identifier = "ios_baseline_0.1",
            sampleRateHz = 100.0,
            dcTimeConstantSeconds = 0.5,
            dcAlpha = 0.019801326693244747,
            lowCutoffHz = 0.6,
            highCutoffHz = 4.0,
            filterOrder = 3,
            sections = listOf(
                PpgSecondOrderSection(
                    b0 = 0.0009951735615644872,
                    b1 = 0.0019903471231289744,
                    b2 = 0.0009951735615644872,
                    a0 = 1.0,
                    a1 = -1.7806577292895467,
                    a2 = 0.8331525078192623,
                ),
                PpgSecondOrderSection(
                    b0 = 1.0,
                    b1 = 0.0,
                    b2 = -1.0,
                    a0 = 1.0,
                    a1 = -1.7977389079873187,
                    a2 = 0.8063221045221778,
                ),
                PpgSecondOrderSection(
                    b0 = 1.0,
                    b1 = -2.0,
                    b2 = 1.0,
                    a0 = 1.0,
                    a1 = -1.968647554073201,
                    a2 = 0.9701854631165024,
                ),
            ),
            sqiPolarityTransform = PpgPolarityTransform.INVERT,
            standardDeviationEpsilon = 1e-8,
        )

        /**
         * Display/offline analysis profile for the requested 0.5–12 Hz band.
         *
         * The two normalized biquads are a second-order Butterworth high-pass
         * followed by a second-order Butterworth low-pass at 100 Hz. Offline
         * callers run them forward/backward, so this profile is deliberately
         * separate from the live-metric iOS parity profile above.
         */
        val offlineBiquad05To12Hz01 = PpgPreprocessingProfile(
            identifier = "offline-biquad-filtfilt-0.5-12hz-0.1",
            sampleRateHz = 100.0,
            dcTimeConstantSeconds = 0.5,
            dcAlpha = 0.019801326693244747,
            lowCutoffHz = 0.5,
            highCutoffHz = 12.0,
            filterOrder = 4,
            sections = listOf(
                PpgSecondOrderSection(
                    b0 = 0.9780304792065596,
                    b1 = -1.9560609584131192,
                    b2 = 0.9780304792065596,
                    a0 = 1.0,
                    a1 = -1.9555782403150352,
                    a2 = 0.9565436765112032,
                ),
                PpgSecondOrderSection(
                    b0 = 0.09131490043583199,
                    b1 = 0.18262980087166397,
                    b2 = 0.09131490043583199,
                    a0 = 1.0,
                    a1 = -0.9824057931083954,
                    a2 = 0.3476653948517233,
                ),
            ),
            sqiPolarityTransform = PpgPolarityTransform.INVERT,
            standardDeviationEpsilon = 1e-8,
        )
    }
}

data class PpgPreprocessedSample(
    val raw: Double,
    val dc: Double,
    val ac: Double,
    val bandpassed: Double,
    val didResetAtBoundary: Boolean,
    val profileIdentifier: String,
)

data class PpgSamplePreprocessingResult(
    val sample: PpgPreprocessedSample?,
    val unavailableReason: PpgPreprocessingUnavailableReason?,
) {
    val isValid: Boolean
        get() = sample != null && unavailableReason == null

    companion object {
        fun valid(sample: PpgPreprocessedSample) =
            PpgSamplePreprocessingResult(sample, null)

        fun unavailable(reason: PpgPreprocessingUnavailableReason) =
            PpgSamplePreprocessingResult(null, reason)
    }
}

data class PpgNormalizedWindow(
    val polarityAdjustedValues: List<Double>,
    val normalizedValues: List<Double>?,
    val mean: Double?,
    val standardDeviation: Double?,
    val polarityTransform: PpgPolarityTransform,
    val profileIdentifier: String,
    val unavailableReason: PpgPreprocessingUnavailableReason?,
) {
    val isValid: Boolean
        get() = normalizedValues != null && unavailableReason == null
}

object PpgWindowNormalizer {
    fun normalize(
        values: List<Double>,
        profile: PpgPreprocessingProfile = PpgPreprocessingProfile.iosBaseline01,
        polarityTransform: PpgPolarityTransform? = null,
    ): PpgNormalizedWindow {
        val transform = polarityTransform ?: profile.sqiPolarityTransform
        if (values.isEmpty()) {
            return unavailable(
                PpgPreprocessingUnavailableReason.EMPTY_WINDOW,
                transform,
                profile,
            )
        }
        if (values.any { !it.isFinite() }) {
            return unavailable(
                PpgPreprocessingUnavailableReason.NON_FINITE_INPUT,
                transform,
                profile,
            )
        }

        val adjusted = values.map(transform::apply)
        val mean = adjusted.sum() / adjusted.size.toDouble()
        val squaredError = adjusted.sumOf { value ->
            val centered = value - mean
            centered * centered
        }
        val standardDeviation = sqrt(squaredError / adjusted.size.toDouble())
        if (!standardDeviation.isFinite() ||
            standardDeviation < profile.standardDeviationEpsilon
        ) {
            return PpgNormalizedWindow(
                polarityAdjustedValues = adjusted,
                normalizedValues = null,
                mean = mean,
                standardDeviation = standardDeviation,
                polarityTransform = transform,
                profileIdentifier = profile.identifier,
                unavailableReason = PpgPreprocessingUnavailableReason.CONSTANT_SIGNAL,
            )
        }

        return PpgNormalizedWindow(
            polarityAdjustedValues = adjusted,
            normalizedValues = adjusted.map { (it - mean) / standardDeviation },
            mean = mean,
            standardDeviation = standardDeviation,
            polarityTransform = transform,
            profileIdentifier = profile.identifier,
            unavailableReason = null,
        )
    }

    private fun unavailable(
        reason: PpgPreprocessingUnavailableReason,
        transform: PpgPolarityTransform,
        profile: PpgPreprocessingProfile,
    ) = PpgNormalizedWindow(
        polarityAdjustedValues = emptyList(),
        normalizedValues = null,
        mean = null,
        standardDeviation = null,
        polarityTransform = transform,
        profileIdentifier = profile.identifier,
        unavailableReason = reason,
    )
}

class PpgPreprocessor(
    val profile: PpgPreprocessingProfile = PpgPreprocessingProfile.iosBaseline01,
) {
    private var dc: Double? = null
    private var sectionStates = profile.sections.map(::PpgSecondOrderSectionState)

    fun process(
        raw: Double,
        boundary: PpgStreamBoundary = PpgStreamBoundary.CONTINUOUS,
    ): PpgSamplePreprocessingResult {
        val resetsAtBoundary = boundary == PpgStreamBoundary.GAP
        if (resetsAtBoundary) reset()
        if (!raw.isFinite()) {
            reset()
            return PpgSamplePreprocessingResult.unavailable(
                PpgPreprocessingUnavailableReason.NON_FINITE_INPUT,
            )
        }

        dc = dc?.let { previous ->
            previous + profile.dcAlpha * (raw - previous)
        } ?: raw
        val dcValue = dc ?: raw
        val ac = raw - dcValue
        var filtered = ac
        sectionStates = sectionStates.map { state ->
            state.process(filtered).also { filtered = it }
            state
        }

        return PpgSamplePreprocessingResult.valid(
            PpgPreprocessedSample(
                raw = raw,
                dc = dcValue,
                ac = ac,
                bandpassed = filtered,
                didResetAtBoundary = resetsAtBoundary,
                profileIdentifier = profile.identifier,
            ),
        )
    }

    fun process(
        rawValues: List<Double>,
        boundaryBeforeFirst: PpgStreamBoundary = PpgStreamBoundary.CONTINUOUS,
    ): List<PpgSamplePreprocessingResult> = rawValues.mapIndexed { index, raw ->
        process(
            raw = raw,
            boundary = if (index == 0) boundaryBeforeFirst else PpgStreamBoundary.CONTINUOUS,
        )
    }

    fun reset() {
        dc = null
        sectionStates = profile.sections.map(::PpgSecondOrderSectionState)
    }
}

private class PpgSecondOrderSectionState(coefficients: PpgSecondOrderSection) {
    private val b0: Double
    private val b1: Double
    private val b2: Double
    private val a1: Double
    private val a2: Double
    private var delay1 = 0.0
    private var delay2 = 0.0

    init {
        require(coefficients.a0.isFinite() && coefficients.a0 != 0.0) {
            "SOS a0 must be finite and non-zero"
        }
        b0 = coefficients.b0 / coefficients.a0
        b1 = coefficients.b1 / coefficients.a0
        b2 = coefficients.b2 / coefficients.a0
        a1 = coefficients.a1 / coefficients.a0
        a2 = coefficients.a2 / coefficients.a0
    }

    fun process(input: Double): Double {
        val output = b0 * input + delay1
        delay1 = b1 * input - a1 * output + delay2
        delay2 = b2 * input - a2 * output
        return output
    }
}
