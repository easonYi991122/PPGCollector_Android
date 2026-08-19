package com.example.ppgcollector_android.data.session

data class OfflineBloodPressurePreviewPoint(
    val sourceSampleIndex: Long,
    val sourceTimeSeconds: Double,
    val systolicMmHg: Double,
    val diastolicMmHg: Double,
)

data class OfflineBloodPressurePreview(
    val points: List<OfflineBloodPressurePreviewPoint>,
    val modelAvailable: Boolean = false,
    val isPlaceholder: Boolean = true,
    val cadenceSamples: Int = 100,
    val warmupSamples: Int = 800,
)

/**
 * UI-only placeholder for validating BP/reference alignment while no calibrated
 * model exists. Values are never persisted or exposed by the live metric model.
 */
object OfflineBloodPressurePreviewFactory {
    const val placeholderSystolicMmHg = 120.0
    const val placeholderDiastolicMmHg = 80.0

    fun create(
        timeSeconds: DoubleArray,
        breakIndices: IntArray = intArrayOf(),
        metricTimeline: List<CaptureMetricTimelinePoint> = emptyList(),
        warmupSamples: Int = 800,
        cadenceSamples: Int = 100,
    ): OfflineBloodPressurePreview {
        require(warmupSamples > 0 && cadenceSamples > 0)
        val metricPoints = metricTimeline
            .filter { point ->
                point.sourceTimeSeconds.isFinite() &&
                    point.sourceTimeSeconds >= (timeSeconds.firstOrNull() ?: 0.0) &&
                    point.sourceTimeSeconds <= (timeSeconds.lastOrNull() ?: -1.0)
            }
            .distinctBy(CaptureMetricTimelinePoint::sourceTimeSeconds)
            .map(::placeholderPoint)
        if (metricPoints.isNotEmpty()) {
            return OfflineBloodPressurePreview(
                points = metricPoints,
                cadenceSamples = cadenceSamples,
                warmupSamples = warmupSamples,
            )
        }
        if (timeSeconds.isEmpty()) {
            return OfflineBloodPressurePreview(
                points = emptyList(),
                cadenceSamples = cadenceSamples,
                warmupSamples = warmupSamples,
            )
        }

        val breaks = BooleanArray(timeSeconds.size)
        breakIndices.filter { it in 1 until timeSeconds.size }.forEach { breaks[it] = true }
        for (index in 1 until timeSeconds.size) {
            val delta = timeSeconds[index] - timeSeconds[index - 1]
            if (!delta.isFinite() || delta <= 0.0 || delta > 0.015) breaks[index] = true
        }
        val points = buildList {
            var runStart = 0
            for (index in 1..timeSeconds.size) {
                val boundary = index == timeSeconds.size || breaks[index]
                if (!boundary) continue
                var sourceIndex = runStart + warmupSamples - 1
                while (sourceIndex < index) {
                    val time = timeSeconds[sourceIndex]
                    if (time.isFinite()) {
                        add(
                            OfflineBloodPressurePreviewPoint(
                                sourceSampleIndex = sourceIndex.toLong(),
                                sourceTimeSeconds = time,
                                systolicMmHg = placeholderSystolicMmHg,
                                diastolicMmHg = placeholderDiastolicMmHg,
                            ),
                        )
                    }
                    sourceIndex += cadenceSamples
                }
                runStart = index
            }
        }
        return OfflineBloodPressurePreview(
            points = points,
            cadenceSamples = cadenceSamples,
            warmupSamples = warmupSamples,
        )
    }

    private fun placeholderPoint(point: CaptureMetricTimelinePoint) =
        OfflineBloodPressurePreviewPoint(
            sourceSampleIndex = point.sourceSampleIndex,
            sourceTimeSeconds = point.sourceTimeSeconds,
            systolicMmHg = placeholderSystolicMmHg,
            diastolicMmHg = placeholderDiastolicMmHg,
        )
}
