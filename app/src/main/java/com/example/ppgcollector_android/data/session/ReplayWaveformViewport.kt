package com.example.ppgcollector_android.data.session

/** Pure viewport state for bounded replay samples; no Compose or Android dependency. */
class ReplayWaveformViewport(
    zoomScale: Double = minimumZoom,
    visibleStart: Double = 0.0,
) {
    var zoomScale: Double = clampZoom(zoomScale)
        private set
    var visibleStart: Double = visibleStart.coerceAtLeast(0.0).takeIf(Double::isFinite) ?: 0.0
        private set

    fun visibleSampleCount(totalSampleCount: Int): Int {
        if (totalSampleCount <= 0) return 0
        if (totalSampleCount == 1) return 1
        return minOf(
            totalSampleCount,
            maxOf(2, kotlin.math.ceil(totalSampleCount.toDouble() / zoomScale).toInt()),
        )
    }

    fun visibleRange(totalSampleCount: Int): IntRange {
        val count = visibleSampleCount(totalSampleCount)
        if (count == 0) return IntRange.EMPTY
        val maximumStart = maxOf(0, totalSampleCount - count)
        val start = visibleStart
            .coerceAtLeast(0.0)
            .toInt()
            .coerceAtMost(maximumStart)
        return start until (start + count)
    }

    fun setZoom(
        requestedZoom: Double,
        totalSampleCount: Int,
        anchorFraction: Double = 0.5,
    ) {
        val anchor = anchorFraction.coerceIn(0.0, 1.0)
        val oldCount = visibleSampleCount(totalSampleCount)
        val anchorSample = visibleStart + maxOf(0, oldCount - 1).toDouble() * anchor
        zoomScale = clampZoom(requestedZoom)
        val newCount = visibleSampleCount(totalSampleCount)
        visibleStart = anchorSample - maxOf(0, newCount - 1).toDouble() * anchor
        clamp(totalSampleCount)
    }

    fun pan(sampleDelta: Double, totalSampleCount: Int) {
        visibleStart += sampleDelta
        clamp(totalSampleCount)
    }

    fun clamp(totalSampleCount: Int) {
        val count = visibleSampleCount(totalSampleCount)
        val maximumStart = maxOf(0, totalSampleCount - count)
        visibleStart = visibleStart.coerceIn(0.0, maximumStart.toDouble())
    }

    fun reset() {
        zoomScale = minimumZoom
        visibleStart = 0.0
    }

    override fun equals(other: Any?): Boolean =
        other is ReplayWaveformViewport &&
            zoomScale == other.zoomScale &&
            visibleStart == other.visibleStart

    override fun hashCode(): Int = 31 * zoomScale.hashCode() + visibleStart.hashCode()

    companion object {
        const val minimumZoom = 1.0
        const val maximumZoom = 80.0

        private fun clampZoom(value: Double): Double = when {
            value.isNaN() -> minimumZoom
            value < minimumZoom -> minimumZoom
            value > maximumZoom -> maximumZoom
            else -> value
        }
    }
}
