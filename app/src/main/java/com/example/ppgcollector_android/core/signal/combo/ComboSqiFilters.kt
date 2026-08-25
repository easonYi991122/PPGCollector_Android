package com.example.ppgcollector_android.core.signal.combo

import com.example.ppgcollector_android.core.signal.PpgSecondOrderSection
import kotlin.math.max
import kotlin.math.min

/**
 * Combo SQI input filters aligned with
 * `complete_preprocessing_pipeline.moving_average_filter_fixed` and
 * `bandpass_filter(..., 0.5, 12, fs, order=2)` / `scipy.signal.sosfiltfilt`.
 *
 * Coefficients frozen from SciPy 1.13.1:
 * `butter(2, [0.5, 12], fs=100, btype='band', output='sos')`.
 */
internal object ComboSqiFilters {
    private val bandpassSos = listOf(
        PpgSecondOrderSection(
            b0 = 0.0851341808921322,
            b1 = 0.1702683617842645,
            b2 = 0.0851341808921322,
            a0 = 1.0,
            a1 = -1.0290573885218655,
            a2 = 0.3791160802284439,
        ),
        PpgSecondOrderSection(
            b0 = 1.0,
            b1 = -2.0,
            b2 = 1.0,
            a0 = 1.0,
            a1 = -1.9557958523097407,
            a2 = 0.9568371134480591,
        ),
    )

    fun movingAverageFixed(values: DoubleArray, windowSize: Int): DoubleArray {
        val window = max(1, windowSize)
        if (values.size <= window) return values.copyOf()
        val filtered = DoubleArray(values.size)
        for (index in 0 until window - 1) {
            filtered[index] = values[index]
        }
        for (index in window - 1 until values.size) {
            var sum = 0.0
            for (offset in 0 until window) {
                sum += values[index - window + 1 + offset]
            }
            filtered[index] = sum / window
        }
        return filtered
    }

    fun smoothThree(values: DoubleArray): DoubleArray {
        var current = movingAverageFixed(values, 2)
        current = movingAverageFixed(current, 2)
        return movingAverageFixed(current, 10)
    }

    fun bandpass05To12Hz(values: DoubleArray): DoubleArray = sosFiltFilt(values, bandpassSos)

    fun templateMatchInput(rawIr: DoubleArray): DoubleArray {
        val negated = DoubleArray(rawIr.size) { -rawIr[it] }
        return bandpass05To12Hz(smoothThree(negated))
    }

    fun correlationInput(rawIr: DoubleArray): DoubleArray {
        val negated = DoubleArray(rawIr.size) { -rawIr[it] }
        return smoothThree(negated)
    }

    fun overpressureInput(rawIr: DoubleArray): DoubleArray {
        val negated = DoubleArray(rawIr.size) { -rawIr[it] }
        return bandpass05To12Hz(negated)
    }

    /**
     * SciPy `sosfiltfilt` padlen default for two biquads with nonzero b2/a2:
     * `3 * (2 * n_sections + 1) = 15`.
     */
    private fun sosFiltFilt(
        values: DoubleArray,
        sections: List<PpgSecondOrderSection>,
        padlen: Int = 15,
    ): DoubleArray {
        if (values.size < 32) return DoubleArray(values.size)
        val edge = min(values.lastIndex, max(1, padlen))
        val extended = oddExtension(values, edge)
        val forward = filterOneDirection(extended, sections)
        val backward = filterOneDirection(forward.reversedArray(), sections).reversedArray()
        return backward.copyOfRange(edge, edge + values.size)
    }

    private fun oddExtension(values: DoubleArray, edge: Int): DoubleArray {
        val result = DoubleArray(values.size + edge * 2)
        for (index in 0 until edge) {
            result[index] = 2.0 * values.first() - values[edge - index]
        }
        values.copyInto(result, edge)
        for (index in 0 until edge) {
            result[edge + values.size + index] =
                2.0 * values.last() - values[values.lastIndex - 1 - index]
        }
        return result
    }

    private fun filterOneDirection(
        values: DoubleArray,
        sections: List<PpgSecondOrderSection>,
    ): DoubleArray {
        if (values.isEmpty()) return values
        var scale = 1.0
        val states = sections.map { section ->
            val normalized = PpgSecondOrderSection(
                b0 = section.b0 / section.a0,
                b1 = section.b1 / section.a0,
                b2 = section.b2 / section.a0,
                a0 = 1.0,
                a1 = section.a1 / section.a0,
                a2 = section.a2 / section.a0,
            )
            val denominator = 1.0 + normalized.a1 + normalized.a2
            val zi0 = (
                normalized.b1 + normalized.b2 -
                    (normalized.a1 + normalized.a2) * normalized.b0
                ) / denominator
            val zi1 = normalized.b2 - normalized.a2 * normalized.b0 - normalized.a2 * zi0
            SosState(
                normalized,
                delay1 = zi0 * scale * values.first(),
                delay2 = zi1 * scale * values.first(),
            ).also {
                scale *= (normalized.b0 + normalized.b1 + normalized.b2) / denominator
            }
        }
        return DoubleArray(values.size) { index ->
            var current = values[index]
            states.forEach { state -> current = state.process(current) }
            current
        }
    }

    private class SosState(
        private val section: PpgSecondOrderSection,
        private var delay1: Double,
        private var delay2: Double,
    ) {
        fun process(input: Double): Double {
            val output = section.b0 * input + delay1
            delay1 = section.b1 * input - section.a1 * output + delay2
            delay2 = section.b2 * input - section.a2 * output
            return output
        }
    }
}
