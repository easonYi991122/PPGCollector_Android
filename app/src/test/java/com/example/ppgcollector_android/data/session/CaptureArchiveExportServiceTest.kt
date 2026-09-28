package com.example.ppgcollector_android.data.session

import java.nio.file.Files
import java.time.Instant
import java.util.zip.ZipInputStream
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureArchiveExportServiceTest {
    @Test
    fun snapshotHashLocksInitialEofEvenWhenExternalSourceKeepsAppending() {
        val path = Files.createTempFile("snapshot-eof", ".bin")
        try {
            Files.write(path, ByteArray(64 * 1024))
            var checks = 0
            org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                CaptureExportSource.freeze(path, "source", cancellationCheck = {
                    checks++
                    if (checks > 1) Files.write(path, ByteArray(64 * 1024), java.nio.file.StandardOpenOption.APPEND)
                    check(checks < 10) { "hash followed a growing EOF" }
                })
            }
            assertEquals(3, checks)
            assertEquals(3L * 64 * 1024, Files.size(path))
        } finally { Files.deleteIfExists(path) }
    }

    @Test
    fun mutationAfterSnapshotFailsAndDeletesOutputForAppendReplaceAndSameSizeRewrite() {
        for (mutation in listOf("append", "replace", "same-size")) {
            val root = Files.createTempDirectory("archive-$mutation")
            try {
                val sessions = root.resolve("sessions")
                val session = ReviewSessionFixtures.writeProtocols(sessions).first()
                val files = CaptureSessionRepository.expectedFiles(session.directory)
                val destination = root.resolve("changed.zip")
                var changed = false
                org.junit.Assert.assertThrows(CaptureSessionExportException.CannotExport::class.java) {
                    CaptureArchiveExportService.export(sessions, root.resolve("profiles"),
                        CaptureArchiveSelection(sessionDirectories = setOf(session.directory)), destination,
                        onProgress = {
                            if (!changed) {
                                changed = true
                                when (mutation) {
                                    "append" -> Files.write(files.raw, byteArrayOf(1, 2, 3), java.nio.file.StandardOpenOption.APPEND)
                                    "replace" -> {
                                        val replacement = root.resolve("replacement.json")
                                        Files.copy(files.metadata, replacement)
                                        Files.move(replacement, files.metadata, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                                            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                                    }
                                    else -> {
                                        val modified = Files.getLastModifiedTime(files.csv)
                                        val bytes = Files.readAllBytes(files.csv)
                                        bytes[bytes.lastIndex - 2] = if (bytes[bytes.lastIndex - 2] == 48.toByte()) 49 else 48
                                        Files.write(files.csv, bytes)
                                        Files.setLastModifiedTime(files.csv, modified) // Hash must catch this too.
                                    }
                                }
                            }
                        })
                }
                assertTrue(changed)
                org.junit.Assert.assertFalse(Files.exists(destination))
                org.junit.Assert.assertFalse(Files.exists(root.resolve(".changed.zip.tmp")))
            } finally { root.toFile().deleteRecursively() }
        }
    }

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
