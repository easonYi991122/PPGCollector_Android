package com.example.ppgcollector_android.core.signal

/** Presentation-only transform for raw ADC, whose systolic peak points down. */
object PpgDisplayTransform {
    fun rawPeakUp(values: DoubleArray): DoubleArray = DoubleArray(values.size) { index ->
        val value = values[index]
        if (value.isFinite()) -value else value
    }

    fun rawPeakUp(values: List<Double>): List<Double> = values.map { value ->
        if (value.isFinite()) -value else value
    }
}

