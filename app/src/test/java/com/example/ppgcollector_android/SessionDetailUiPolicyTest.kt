package com.example.ppgcollector_android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDetailUiPolicyTest {
    @Test
    fun everyDetailSectionStartsCollapsedAndTogglesIndependently() {
        var expanded = SessionDetailUiPolicy.defaultExpandedSections
        assertTrue(expanded.isEmpty())

        expanded = SessionDetailUiPolicy.toggle(expanded, SessionDetailSection.REPLAY)
        assertEquals(setOf(SessionDetailSection.REPLAY), expanded)
        expanded = SessionDetailUiPolicy.toggle(expanded, SessionDetailSection.BLOOD_PRESSURE)
        assertEquals(setOf(SessionDetailSection.REPLAY, SessionDetailSection.BLOOD_PRESSURE), expanded)
        expanded = SessionDetailUiPolicy.toggle(expanded, SessionDetailSection.REPLAY)
        assertEquals(setOf(SessionDetailSection.BLOOD_PRESSURE), expanded)
    }

    @Test
    fun artifactSelectorOnlyAppearsWhenThereIsAChoice() {
        assertFalse(SessionDetailUiPolicy.showsArtifactSelector(0))
        assertFalse(SessionDetailUiPolicy.showsArtifactSelector(1))
        assertTrue(SessionDetailUiPolicy.showsArtifactSelector(2))
    }

    @Test
    fun recordModeAndDurationLabelsDoNotGuessOldMetadata() {
        assertEquals("未记录", SessionDetailUiPolicy.recordModeLabel(null))
        assertEquals(
            "定时录制",
            SessionDetailUiPolicy.recordModeLabel(
                com.example.ppgcollector_android.data.session.CaptureRecordMode.TIMED,
            ),
        )
        assertEquals("—", SessionDetailUiPolicy.durationLabel(null))
        assertEquals("42 秒", SessionDetailUiPolicy.durationLabel(42L))
    }
}
