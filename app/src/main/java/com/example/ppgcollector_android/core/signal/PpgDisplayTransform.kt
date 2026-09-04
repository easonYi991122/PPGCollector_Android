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

    /** Realtime RAW contract: existing samples never change when the viewport grows. */
    fun liveRawPeakUp(values: DoubleArray): DoubleArray = rawPeakUp(values)

    /**
     * Compatibility name for callers that previously requested a finite raw
     * plot. RAW is evidence, so plotting must not remove its DC level or trend.
     */
    fun rawPeakUpForPlot(values: DoubleArray): DoubleArray = rawPeakUp(values)

    fun rawPeakUpForPlot(values: List<Double>): List<Double> =
        rawPeakUpForPlot(values.toDoubleArray()).asList()
}
