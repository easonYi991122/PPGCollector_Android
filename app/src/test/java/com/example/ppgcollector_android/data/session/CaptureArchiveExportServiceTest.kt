package com.example.ppgcollector_android.data.session

import java.nio.file.Files
import java.time.Instant
import java.util.zip.ZipInputStream
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureArchiveExportServiceTest {
    @Test
    fun subjectSelectionWritesManifestProfilesAndSafeSessionPaths() {
        val root = Files.createTempDirectory("archive-export")
        val sessions = root.resolve("sessions")
        val subjects = root.resolve("subjects")
        try {
            writeSession(sessions, "PPG-A-1", "first")
            SubjectProfileStore(subjects).saveRevision(
                subject = "A",
                sex = "F",
                ageYears = 31,
                heightCm = 166.0,
                weightKg = 56.0,
            )
            val destination = root.resolve("out.zip")
            val report = CaptureArchiveExportService.export(
                sessionsRoot = sessions,
                subjectsRoot = subjects,
                selection = CaptureArchiveSelection(subjectIds = setOf("A")),
                destination = destination,
            )
            assertTrue(report.entryNames.any { it.startsWith("subjects/A/PPG/PPG-A-1/") })
            assertTrue(Files.size(destination) > 0L)
            val names = ZipInputStream(Files.newInputStream(destination)).use { zip ->
                buildList {
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        add(entry.name)
                        while (zip.read() >= 0) Unit
                    }
                }
            }
            assertTrue("export_manifest.json" in names)
            assertTrue(names.any { it.startsWith("subject_profiles/A.profile.json") })
            assertEquals(names.count { it.contains("..") }, 0)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun writeSession(root: java.nio.file.Path, baseName: String, id: String) {
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
        writer.appendRawThenDerive(1u, byteArrayOf(1, 2, 3)) { emptyList() }
        writer.finish(CaptureStopReason.USER)
    }
}
