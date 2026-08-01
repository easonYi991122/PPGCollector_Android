package com.example.ppgcollector_android.core.signal

data class SciPyPeak(
    val index: Int,
    val height: Double,
    val prominence: Double,
    val leftBaseIndex: Int,
    val rightBaseIndex: Int,
    val width: Double?,
    val widthHeight: Double?,
    val leftIntersection: Double?,
    val rightIntersection: Double?,
)

data class SciPyPeakDetectionResult(
    val peaks: List<SciPyPeak>,
) {
    val indices: List<Int>
        get() = peaks.map { it.index }
}

/** The subset of SciPy find_peaks frozen by the HR/SQI parity contract. */
object SciPyPeakDetector {
    fun findPeaks(
        values: List<Double>,
        distance: Int,
        minimumHeight: Double? = null,
        minimumProminence: Double,
        minimumWidth: Double? = null,
    ): SciPyPeakDetectionResult {
        var candidateIndices = localMaximumIndices(values)
        if (minimumHeight != null) {
            candidateIndices = candidateIndices.filter {
                values[it] >= minimumHeight
            }
        }
        candidateIndices = applyMinimumDistance(
            candidateIndices,
            values,
            maxOf(1, distance),
        )

        val peaks = ArrayList<SciPyPeak>()
        candidateIndices.forEach { index ->
            val prominence = peakProminence(values, index)
            if (prominence.value < minimumProminence) return@forEach
            val width = peakWidth(values, index, prominence)
            if (minimumWidth != null && width.value < minimumWidth) return@forEach
            peaks += SciPyPeak(
                index = index,
                height = values[index],
                prominence = prominence.value,
                leftBaseIndex = prominence.leftBaseIndex,
                rightBaseIndex = prominence.rightBaseIndex,
                width = minimumWidth?.let { width.value },
                widthHeight = minimumWidth?.let { width.height },
                leftIntersection = minimumWidth?.let { width.leftIntersection },
                rightIntersection = minimumWidth?.let { width.rightIntersection },
            )
        }
        return SciPyPeakDetectionResult(peaks)
    }

    private fun localMaximumIndices(values: List<Double>): List<Int> {
        if (values.size < 3) return emptyList()
        val peaks = ArrayList<Int>()
        var index = 1
        while (index < values.lastIndex) {
            if (values[index - 1] >= values[index]) {
                index += 1
                continue
            }
            var plateauEnd = index
            while (plateauEnd + 1 < values.size &&
                values[plateauEnd + 1] == values[index]
            ) {
                plateauEnd += 1
            }
            if (plateauEnd + 1 < values.size &&
                values[plateauEnd + 1] < values[index]
            ) {
                peaks += (index + plateauEnd) / 2
            }
            index = plateauEnd + 1
        }
        return peaks
    }

    private fun applyMinimumDistance(
        indices: List<Int>,
        values: List<Double>,
        distance: Int,
    ): List<Int> {
        if (indices.size <= 1) return indices
        val keep = BooleanArray(indices.size) { true }
        val priorityOrder = indices.indices.sortedWith { left, right ->
            val leftHeight = values[indices[left]]
            val rightHeight = values[indices[right]]
            when {
                leftHeight < rightHeight -> -1
                leftHeight > rightHeight -> 1
                else -> left.compareTo(right)
            }
        }
        priorityOrder.asReversed().forEach { position ->
            if (!keep[position]) return@forEach
            var neighbor = position - 1
            while (neighbor >= 0 &&
                indices[position] - indices[neighbor] < distance
            ) {
                keep[neighbor] = false
                neighbor -= 1
            }
            neighbor = position + 1
            while (neighbor < indices.size &&
                indices[neighbor] - indices[position] < distance
            ) {
                keep[neighbor] = false
                neighbor += 1
            }
        }
        return indices.indices.filter { keep[it] }.map { indices[it] }
    }

    private fun peakProminence(values: List<Double>, peakIndex: Int): Prominence {
        val peakValue = values[peakIndex]
        var leftBaseIndex = peakIndex
        var leftMinimum = peakValue
        var left = peakIndex
        while (left >= 0 && values[left] <= peakValue) {
            if (values[left] < leftMinimum) {
                leftMinimum = values[left]
                leftBaseIndex = left
            }
            left -= 1
        }

        var rightBaseIndex = peakIndex
        var rightMinimum = peakValue
        var right = peakIndex
        while (right < values.size && values[right] <= peakValue) {
            if (values[right] < rightMinimum) {
                rightMinimum = values[right]
                rightBaseIndex = right
            }
            right += 1
        }
        return Prominence(
            value = peakValue - maxOf(leftMinimum, rightMinimum),
            leftBaseIndex = leftBaseIndex,
            rightBaseIndex = rightBaseIndex,
        )
    }

    private fun peakWidth(
        values: List<Double>,
        peakIndex: Int,
        prominence: Prominence,
    ): Width {
        val height = values[peakIndex] - prominence.value * 0.5
        var left = peakIndex
        while (prominence.leftBaseIndex < left && height < values[left]) {
            left -= 1
        }
        var leftIntersection = left.toDouble()
        if (values[left] < height) {
            leftIntersection += (height - values[left]) /
                (values[left + 1] - values[left])
        }

        var right = peakIndex
        while (right < prominence.rightBaseIndex && height < values[right]) {
            right += 1
        }
        var rightIntersection = right.toDouble()
        if (values[right] < height) {
            rightIntersection -= (height - values[right]) /
                (values[right - 1] - values[right])
        }
        return Width(
            value = rightIntersection - leftIntersection,
            height = height,
            leftIntersection = leftIntersection,
            rightIntersection = rightIntersection,
        )
    }

    private data class Prominence(
        val value: Double,
        val leftBaseIndex: Int,
        val rightBaseIndex: Int,
    )

    private data class Width(
        val value: Double,
        val height: Double,
        val leftIntersection: Double,
        val rightIntersection: Double,
    )
}
