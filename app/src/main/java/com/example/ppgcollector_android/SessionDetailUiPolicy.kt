package com.example.ppgcollector_android

internal enum class SessionDetailSection {
    OVERVIEW,
    SOURCE,
    PARTICIPANT,
    BLOOD_PRESSURE,
    INTEGRITY,
    REPLAY,
    ANALYSIS,
    EXPORT,
}

internal object SessionDetailUiPolicy {
    fun bloodPressureError(systolic: Int?, diastolic: Int?): String? = when {
        systolic == null && diastolic == null -> null
        systolic == null || diastolic == null -> "请同时填写 SBP 和 DBP"
        systolic !in 20..300 || diastolic !in 10..250 -> "SBP 须为 20–300，DBP 须为 10–250"
        systolic <= diastolic -> "SBP 必须高于 DBP"
        else -> null
    }

    fun bloodPressureTextError(systolic: String, diastolic: String): String? =
        if ((systolic.isNotBlank() && systolic.toIntOrNull() == null) ||
            (diastolic.isNotBlank() && diastolic.toIntOrNull() == null)) "请输入有效整数"
        else bloodPressureError(systolic.toIntOrNull(), diastolic.toIntOrNull())

    fun signalState(loading: Boolean, error: String?, hasSignal: Boolean): SessionSignalUiState = when {
        hasSignal -> SessionSignalUiState.READY
        error != null -> SessionSignalUiState.FAILED
        loading -> SessionSignalUiState.LOADING
        else -> SessionSignalUiState.EMPTY
    }
    val defaultExpandedSections: Set<SessionDetailSection> = emptySet()

    fun toggle(
        expanded: Set<SessionDetailSection>,
        section: SessionDetailSection,
    ): Set<SessionDetailSection> = if (section in expanded) expanded - section else expanded + section

    fun showsArtifactSelector(artifactCount: Int): Boolean = artifactCount > 1

    fun recordModeLabel(mode: com.example.ppgcollector_android.data.session.CaptureRecordMode?): String =
        when (mode) {
            com.example.ppgcollector_android.data.session.CaptureRecordMode.TIMED -> "定时录制"
            com.example.ppgcollector_android.data.session.CaptureRecordMode.MANUAL -> "手动录制"
            null -> "未记录"
        }

    fun durationLabel(seconds: Long?): String = seconds?.let { "$it 秒" } ?: "—"
}

internal enum class SessionSignalUiState { LOADING, FAILED, EMPTY, READY }

internal object ReviewAdaptiveLayoutPolicy {
    const val rangeColumns = 2
    const val rangeButtonMinimumHeightDp = 48
    fun sidebarWidthDp(widthDp: Float): Float = (widthDp * 0.31f).coerceIn(220f, 340f)
    fun rangeButtonWidthDp(widthDp: Float): Float = (sidebarWidthDp(widthDp) - 40f - 6f) / rangeColumns
}

internal enum class SessionSignalDrawLayer {
    GAP_MARKERS,
    WAVEFORM,
}

/** Pure policy shared by the detail replay and the landscape workbench. */
internal object SessionGapMarkerPolicy {
    private const val highDensityBreakCount = 20
    private const val highDensityBreaksPerSamples = 0.01

    fun isHighDensity(breakCount: Int, sampleCount: Int): Boolean =
        breakCount >= highDensityBreakCount ||
            (sampleCount > 0 && breakCount.toDouble() / sampleCount >= highDensityBreaksPerSamples)

    fun defaultVisible(breakCount: Int, sampleCount: Int): Boolean =
        breakCount > 0 && !isHighDensity(breakCount, sampleCount)

    fun pathBreaksForRawStage(isRawStage: Boolean, breakIndices: IntArray): IntArray =
        if (isRawStage) breakIndices else SessionRenderKey.EMPTY_INDICES

    fun drawLayers(showMarkers: Boolean): List<SessionSignalDrawLayer> = buildList {
        if (showMarkers) add(SessionSignalDrawLayer.GAP_MARKERS)
        add(SessionSignalDrawLayer.WAVEFORM)
    }
}
