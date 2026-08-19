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
}
