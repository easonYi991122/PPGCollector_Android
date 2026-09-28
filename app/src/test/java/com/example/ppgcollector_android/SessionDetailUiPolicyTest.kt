package com.example.ppgcollector_android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDetailUiPolicyTest {
    @org.junit.Test fun failedSignalIsNotLoadingAndBpValidationLivesBesideTheEditor() {
        org.junit.Assert.assertEquals(SessionSignalUiState.FAILED, SessionDetailUiPolicy.signalState(false, "raw missing", false))
        org.junit.Assert.assertEquals(SessionSignalUiState.EMPTY, SessionDetailUiPolicy.signalState(false, null, false))
        org.junit.Assert.assertEquals(SessionSignalUiState.READY, SessionDetailUiPolicy.signalState(true, null, true))
        org.junit.Assert.assertNotNull(SessionDetailUiPolicy.bloodPressureTextError("80", "120"))
        org.junit.Assert.assertNotNull(SessionDetailUiPolicy.bloodPressureTextError("120", ""))
        org.junit.Assert.assertNotNull(SessionDetailUiPolicy.bloodPressureTextError("9999999999999", "80"))
        org.junit.Assert.assertNull(SessionDetailUiPolicy.bloodPressureTextError("120", "80"))
        org.junit.Assert.assertNull(SessionDetailUiPolicy.bloodPressureTextError("", ""))
    }

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

    @Test
    fun gapMarkersDefaultOnOnlyForLowDensityEvidence() {
        assertFalse(SessionGapMarkerPolicy.defaultVisible(0, 3_000))
        assertTrue(SessionGapMarkerPolicy.defaultVisible(1, 3_000))
        assertFalse(SessionGapMarkerPolicy.defaultVisible(20, 3_000))
        assertFalse(SessionGapMarkerPolicy.defaultVisible(10, 800))
    }

    @Test
    fun repairedStagesStayContinuousWhileRawRetainsBreaks() {
        val breaks = intArrayOf(20, 80)
        assertTrue(SessionGapMarkerPolicy.pathBreaksForRawStage(true, breaks) === breaks)
        assertTrue(SessionGapMarkerPolicy.pathBreaksForRawStage(false, breaks).isEmpty())
    }

    @Test
    fun markerLayerAlwaysPrecedesPhysiologicalWaveform() {
        assertEquals(
            listOf(SessionSignalDrawLayer.GAP_MARKERS, SessionSignalDrawLayer.WAVEFORM),
            SessionGapMarkerPolicy.drawLayers(showMarkers = true),
        )
        assertEquals(
            listOf(SessionSignalDrawLayer.WAVEFORM),
            SessionGapMarkerPolicy.drawLayers(showMarkers = false),
        )
    }
}
