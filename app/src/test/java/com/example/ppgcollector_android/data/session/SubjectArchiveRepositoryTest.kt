package com.example.ppgcollector_android.data.session

import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubjectArchiveRepositoryTest {
    @Test
    fun canonicalSessionsGroupBySubjectAndSortSequencesNumerically() {
        val root = Files.createTempDirectory("subject-archive")
        val sessions = root.resolve("sessions")
        val subjects = root.resolve("subjects")
        try {
            writeSession(sessions, "PPG-A-10", "a10", withRaw = true)
            writeSession(sessions, "PPG-A-2", "a2", withRaw = true)
            writeSession(sessions, "free_name", "free", withRaw = true)
            SubjectProfileStore(subjects).saveRevision(
                subject = "A",
                sex = "F",
                ageYears = 30,
                heightCm = 165.0,
                weightKg = 55.0,
                now = Instant.parse("2026-08-08T00:00:00Z"),
            )

            val snapshot = SubjectArchiveRepository.rebuild(sessions, subjects)
            val group = snapshot.groups.single()
            assertEquals("A", group.summary.subject)
            assertEquals(listOf(2L, 10L), group.sessions.map { it.identity.sequence })
            assertEquals(10L, group.summary.maxSequence)
            assertEquals(1, snapshot.unclassified.size)
            assertTrue(group.profile?.latest?.isComplete == true)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun lowercaseCanonicalPrefixIsNormalizedAndArchived() {
        val root = Files.createTempDirectory("subject-archive-lower")
        val sessions = root.resolve("sessions")
        val subjects = root.resolve("subjects")
        try {
            writeSession(sessions, "ppg-subject-1", "lower", withRaw = true)
            val snapshot = SubjectArchiveRepository.rebuild(sessions, subjects)
            assertEquals(listOf("subject"), snapshot.groups.map { it.summary.subject })
            assertEquals("PPG-subject-1", snapshot.groups.single().sessions.single().session.baseName)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun writeSession(root: java.nio.file.Path, baseName: String, id: String, withRaw: Boolean) {
        val writer = CaptureSessionWriter(
            CaptureSessionConfiguration(
                sessionId = id,
                baseName = baseName,
                startedUtc = Instant.parse("2026-08-08T00:00:00Z"),
                softVersion = "test",
                algorithmVersion = "test",
                preprocessProfile = "test",
                protocolProfile = "cup_v1",
                transportProfile = "test",
                device = CaptureDeviceContext("CUP", "id", "service", "notify"),
            ),
            root,
        )
        if (withRaw) writer.appendRawThenDerive(1u, byteArrayOf(1, 2, 3)) { emptyList() }
        writer.finish(CaptureStopReason.USER)
    }
}
