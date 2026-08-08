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

    /**
     * Presentation-only raw waveform for a finite viewport.
     *
     * The stored ADC level contains a large DC component and, on some sensors,
     * a slow ramp. Negating that level alone makes the ramp look like a false
     * physiological baseline change. Remove only the best-fit finite linear
     * trend after the polarity flip; the source array and all persisted values
     * remain untouched. This is deliberately separate from [rawPeakUp], which
     * is the exact polarity contract used when a caller needs the unmodified
     * display transform.
     */
    fun rawPeakUpForPlot(values: DoubleArray): DoubleArray {
        val inverted = rawPeakUp(values)
        val finite = inverted.indices.filter { inverted[it].isFinite() }
        if (finite.size < 2) return inverted

        val meanX = finite.average()
        val meanY = finite.sumOf { inverted[it] } / finite.size.toDouble()
        var covariance = 0.0
        var variance = 0.0
        finite.forEach { index ->
            val dx = index.toDouble() - meanX
            covariance += dx * (inverted[index] - meanY)
            variance += dx * dx
        }
        val slope = if (variance > 0.0) covariance / variance else 0.0
        val intercept = meanY - slope * meanX
        return DoubleArray(inverted.size) { index ->
            val value = inverted[index]
            if (value.isFinite()) value - (slope * index + intercept) else value
        }
    }

    fun rawPeakUpForPlot(values: List<Double>): List<Double> =
        rawPeakUpForPlot(values.toDoubleArray()).asList()
}
