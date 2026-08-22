package com.example.ppgcollector_android.data.session

import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectProfileTest {
    @Test
    fun revisionsAreAtomicAndLatestSnapshotIsReadable() {
        val root = Files.createTempDirectory("subject-profile")
        try {
            val store = SubjectProfileStore(root)
            val saved = store.saveRevision(
                subject = "subject_01",
                sex = "女",
                ageYears = 32,
                heightCm = 165.5,
                weightKg = 58.0,
                additionalFields = mapOf("note" to "baseline"),
                revisionId = "rev-1",
                now = Instant.parse("2026-08-08T00:00:00Z"),
            )
            assertEquals("rev-1", saved.currentRevision)
            assertTrue(saved.latest?.isComplete == true)
            val read = store.read("subject_01")
            assertEquals(saved, read)
            assertEquals("subject_01", read?.latest?.asParticipantSnapshot("subject_01")?.subjectId)
            assertEquals("baseline", read?.latest?.additionalFields?.get("note"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun sessionNotesAreNeverPersistedOrPrefilledFromSubjectProfile() {
        val root = Files.createTempDirectory("subject-profile-session-fields")
        try {
            val saved = SubjectProfileStore(root).saveRevision(
                subject = "subject_02",
                sex = "男",
                ageYears = 28,
                heightCm = 175.0,
                weightKg = 70.0,
                additionalFields = mapOf("notes" to "one recording only", "site" to "lab-a"),
            )
            assertEquals(mapOf("site" to "lab-a"), saved.latest?.additionalFields)
            val legacySnapshot = saved.latest?.asParticipantSnapshot("subject_02")?.copy(
                additionalFields = mapOf("notes" to "legacy", "site" to "lab-a"),
            )
            val draft = CaptureParticipantDraft.fromSnapshot(legacySnapshot)
            assertEquals(mapOf("site" to "lab-a"), draft.additionalFields)
            assertEquals("", draft.systolicBp)
            assertEquals("", draft.diastolicBp)
            val cleared = draft.copy(
                systolicBp = "120",
                diastolicBp = "80",
                additionalFields = draft.additionalFields + ("notes" to "temporary"),
            ).clearSessionScopedFields()
            assertEquals("", cleared.systolicBp)
            assertEquals("", cleared.diastolicBp)
            assertEquals(mapOf("site" to "lab-a"), cleared.additionalFields)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
