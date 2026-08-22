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
