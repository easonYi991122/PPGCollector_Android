package com.example.ppgcollector_android.core.signal

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class FixedLagPpgFilterProfile(
    val identifier: String,
    val sampleRateHz: Double,
    val lowCutoffHz: Double,
    val highCutoffHz: Double,
    val tapCount: Int,
    val rightContextSamples: Int,
    val publishBlockSamples: Int,
    val taps: DoubleArray,
) {
    init {
        require(sampleRateHz > 0.0)
        require(lowCutoffHz > 0.0 && lowCutoffHz < highCutoffHz)
        require(highCutoffHz < sampleRateHz / 2.0)
        require(tapCount >= 3 && tapCount % 2 == 1)
        require(rightContextSamples == tapCount / 2)
        require(publishBlockSamples > 0)
        require(taps.size == tapCount)
        require(taps.all(Double::isFinite))
    }

    companion object {
        /** A bounded symmetric FIR approximation of 0.5–12 Hz zero-phase. */
        val bandpass05To12Hz01 = create(
            identifier = "fixed-lag-fir-0.5-12hz-0.1",
            sampleRateHz = 100.0,
            lowCutoffHz = 0.5,
            highCutoffHz = 12.0,
            tapCount = 201,
            publishBlockSamples = 20,
        )

        private fun create(
            identifier: String,
            sampleRateHz: Double,
            lowCutoffHz: Double,
            highCutoffHz: Double,
            tapCount: Int,
            publishBlockSamples: Int,
        ): FixedLagPpgFilterProfile {
            val center = tapCount / 2
            val taps = DoubleArray(tapCount) { index ->
                val offset = index - center
                val high = lowpass(highCutoffHz, offset, sampleRateHz)
                val low = lowpass(lowCutoffHz, offset, sampleRateHz)
                val window = 0.54 - 0.46 * cos(2.0 * PI * index / (tapCount - 1))
                (high - low) * window
            }
            // Normalize the centre of the passband to unity. This keeps the
            // candidate visually comparable with its zero-phase reference;
            // it is still display-only until a fixture comparison admits it.
            val centreFrequency = (lowCutoffHz + highCutoffHz) / 2.0
            val angular = 2.0 * PI * centreFrequency / sampleRateHz
            var real = 0.0
            var imaginary = 0.0
            taps.forEachIndexed { index, tap ->
                val phase = angular * (index - center)
                real += tap * cos(phase)
                imaginary += tap * sin(phase)
            }
            val gain = kotlin.math.sqrt(real * real + imaginary * imaginary)
                .takeIf { it.isFinite() && it > 1e-12 } ?: 1.0
            return FixedLagPpgFilterProfile(
                identifier = identifier,
                sampleRateHz = sampleRateHz,
                lowCutoffHz = lowCutoffHz,
                highCutoffHz = highCutoffHz,
                tapCount = tapCount,
                rightContextSamples = center,
                publishBlockSamples = publishBlockSamples,
                taps = taps.map { it / gain }.toDoubleArray(),
            )
        }

        private fun lowpass(cutoffHz: Double, offset: Int, sampleRateHz: Double): Double {
            val normalized = 2.0 * cutoffHz / sampleRateHz
            val x = normalized * offset
            val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
            return normalized * sinc
        }
    }
}

data class FixedLagPpgSample(
    val sourceSampleIndex: Long,
    val red: Double,
    val ir: Double,
)

/**
 * Bounded, deterministic fixed-lag filter. A sample is emitted only after
 * [profile.rightContextSamples] future samples are available, so the output
 * has an explicit source cursor and never claims zero latency.
 */
class FixedLagPpgFilterRuntime(
    val profile: FixedLagPpgFilterProfile = FixedLagPpgFilterProfile.bandpass05To12Hz01,
) {
    private val buffer = ArrayDeque<FixedLagPpgSample>(profile.tapCount)
    private var nextInputIndex = 0L
    private var nextOutputIndex = 0L

    val latencySamples: Int get() = profile.rightContextSamples
    val latencySeconds: Double get() = latencySamples / profile.sampleRateHz

    fun reset(nextSourceSampleIndex: Long = 0L) {
        buffer.clear()
        nextInputIndex = nextSourceSampleIndex
        nextOutputIndex = nextSourceSampleIndex
    }

    fun ingest(sample: FixedLagPpgSample): List<FixedLagPpgSample> {
        if (sample.sourceSampleIndex != nextInputIndex) reset(sample.sourceSampleIndex)
        require(sample.red.isFinite() && sample.ir.isFinite()) { "fixed-lag input must be finite" }
        buffer.addLast(sample)
        nextInputIndex = sample.sourceSampleIndex + 1L
        val output = ArrayList<FixedLagPpgSample>()
        while (buffer.size >= profile.tapCount) {
            val center = buffer[profile.rightContextSamples]
            if (center.sourceSampleIndex != nextOutputIndex) {
                nextOutputIndex = center.sourceSampleIndex
            }
            var red = 0.0
            var ir = 0.0
            profile.taps.forEachIndexed { index, tap ->
                red += tap * buffer[index].red
                ir += tap * buffer[index].ir
            }
            output += FixedLagPpgSample(center.sourceSampleIndex, red, ir)
            buffer.removeFirst()
            nextOutputIndex = center.sourceSampleIndex + 1L
        }
        return output
    }

    fun ingest(
        sourceSampleIndex: Long,
        red: Double,
        ir: Double,
    ): List<FixedLagPpgSample> = ingest(FixedLagPpgSample(sourceSampleIndex, red, ir))
}
