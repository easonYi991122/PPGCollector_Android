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
}
