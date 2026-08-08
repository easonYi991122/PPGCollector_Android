package com.example.ppgcollector_android.core.signal

import kotlin.math.PI

/** Explicit causal display profile. Metrics continue to use their versioned
 * 0.6–4 Hz preprocessing contract; this profile is only for the waveform UI. */
data class CausalPpgDisplayFilterProfile(
    val identifier: String,
    val sampleRateHz: Double,
    val lowCutoffHz: Double,
    val highCutoffHz: Double,
) {
    init {
        require(sampleRateHz > 0.0)
        require(lowCutoffHz > 0.0 && lowCutoffHz < highCutoffHz)
        require(highCutoffHz < sampleRateHz / 2.0)
    }

    companion object {
        val bandpass05To12Hz01 = CausalPpgDisplayFilterProfile(
            identifier = "causal-display-0.5-12hz-0.1",
            sampleRateHz = 100.0,
            lowCutoffHz = 0.5,
            highCutoffHz = 12.0,
        )
    }
}

/**
 * A bounded, zero-look-ahead display filter. A first-order high-pass followed
 * by a first-order low-pass gives a stable 0.5–12 Hz causal band-pass without
 * the large transient/phase distortion of the previous narrow 0.6–4 Hz SOS
 * display path. It is intentionally not used for persisted metrics.
 */
class CausalPpgDisplayFilterRuntime(
    val profile: CausalPpgDisplayFilterProfile =
        CausalPpgDisplayFilterProfile.bandpass05To12Hz01,
) {
    private val highPassAlpha = run {
        val rc = 1.0 / (2.0 * PI * profile.lowCutoffHz)
        rc / (rc + 1.0 / profile.sampleRateHz)
    }
    private val lowPassAlpha = run {
        val rc = 1.0 / (2.0 * PI * profile.highCutoffHz)
        (1.0 / profile.sampleRateHz) / (rc + 1.0 / profile.sampleRateHz)
    }
    private var previousInput = 0.0
    private var previousHighPass = 0.0
    private var previousLowPass = 0.0

    fun process(value: Double): Double {
        if (!value.isFinite()) {
            reset()
            return 0.0
        }
        val highPass = highPassAlpha * (previousHighPass + value - previousInput)
        val lowPass = previousLowPass + lowPassAlpha * (highPass - previousLowPass)
        previousInput = value
        previousHighPass = highPass
        previousLowPass = lowPass
        return lowPass
    }

    fun reset() {
        previousInput = 0.0
        previousHighPass = 0.0
        previousLowPass = 0.0
    }
}
