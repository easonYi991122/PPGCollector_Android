package com.example.ppgcollector_android

import com.example.ppgcollector_android.data.session.CanonicalSessionIdentity
import com.example.ppgcollector_android.data.session.StoredCaptureSession
import com.example.ppgcollector_android.data.session.SubjectArchiveGroup
import com.example.ppgcollector_android.data.session.SubjectArchiveSession
import com.example.ppgcollector_android.data.session.SubjectArchiveSnapshot
import com.example.ppgcollector_android.data.session.SubjectArchiveSummary
import java.nio.file.Path
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedSessionsUiPolicyTest {
    @Test
    fun subjectAndExplicitSessionSelectionResolvesToDistinctSessionCount() {
        val first = stored("PPG-A-1")
        val second = stored("PPG-A-2")
        val snapshot = SubjectArchiveSnapshot(
            groups = listOf(
                SubjectArchiveGroup(
                    summary = SubjectArchiveSummary(
                        subject = "A",
                        recordingCount = 2,
                        maxSequence = 2,
                        firstStartedUtc = null,
                        lastStartedUtc = null,
                        completeCount = 0,
                        anomalyCount = 2,
                        bloodPressureGroupCount = 0,
                        heartRateBpm = null,
                        profileMissing = true,
                    ),
                    sessions = listOf(
                        SubjectArchiveSession(first, CanonicalSessionIdentity("A", 1)),
                        SubjectArchiveSession(second, CanonicalSessionIdentity("A", 2)),
                    ),
                ),
            ),
        )

        val selected = SavedSessionsUiPolicy.selectedSessionDirectories(
            archive = snapshot,
            selectedDirectories = setOf(first.directory),
            selectedSubjects = setOf("A"),
        )

        assertEquals(setOf(first.directory, second.directory), selected)
    }

    @Test
    fun viewLabelsDescribeTheDestinationRatherThanTheCurrentMode() {
        assertEquals("逐文件", SavedSessionsUiPolicy.switchLabel(SavedSessionsViewMode.ARCHIVE))
        assertEquals("被试档案", SavedSessionsUiPolicy.switchLabel(SavedSessionsViewMode.FLAT_FILES))
        assertEquals(
            "已选择 3 个会话",
            SavedSessionsUiPolicy.subtitle(SavedSessionsViewMode.ARCHIVE, selectionMode = true, selectedCount = 3),
        )
    }

    private fun stored(name: String): StoredCaptureSession = StoredCaptureSession(
        directory = Path.of("/sessions", name),
        baseName = name,
        modifiedAt = Instant.EPOCH,
        metadata = null,
        metadataIsReadable = false,
        hasAllExpectedFiles = false,
        totalBytes = 0,
    )
}
