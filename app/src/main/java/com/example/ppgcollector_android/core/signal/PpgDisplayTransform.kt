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
        var finiteCount = 0
        var sumX = 0.0
        var sumY = 0.0
        for (index in values.indices) {
            val value = values[index]
            if (!value.isFinite()) continue
            finiteCount++
            sumX += index.toDouble()
            sumY -= value
        }
        if (finiteCount < 2) return rawPeakUp(values)

        val meanX = sumX / finiteCount.toDouble()
        val meanY = sumY / finiteCount.toDouble()
        var covariance = 0.0
        var variance = 0.0
        for (index in values.indices) {
            val source = values[index]
            if (!source.isFinite()) continue
            val dx = index.toDouble() - meanX
            covariance += dx * (-source - meanY)
            variance += dx * dx
        }
        val slope = if (variance > 0.0) covariance / variance else 0.0
        val intercept = meanY - slope * meanX
        return DoubleArray(values.size) { index ->
            val value = values[index]
            if (value.isFinite()) -value - (slope * index + intercept) else value
        }
    }

    fun rawPeakUpForPlot(values: List<Double>): List<Double> =
        rawPeakUpForPlot(values.toDoubleArray()).asList()
}
