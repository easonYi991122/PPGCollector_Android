package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.protocol.CupBatchFrame
import com.example.ppgcollector_android.core.protocol.CupBatchProtocolV1
import com.example.ppgcollector_android.core.protocol.CupPpgSample
import com.example.ppgcollector_android.core.protocol.encodeCupBatchFrame
import java.nio.file.Files
import java.time.Instant
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSessionExportServiceTest {
    @Test
    fun exportCancellationStopsProgressAtNext64KiBBlock() {
        val root = Files.createTempDirectory("export-block-cancel")
        try {
            val writer = CaptureSessionWriter(configuration(), root)
            writer.finish(CaptureStopReason.USER)
            val raw = CaptureSessionRepository.expectedFiles(writer.directory).raw
            Files.write(raw, ByteArray(256 * 1024))
            val source = CaptureSessionRepository.listSessions(root).single()
            var copied = 0L
            val cancellation = java.util.concurrent.CancellationException("block")
            org.junit.Assert.assertSame(cancellation, org.junit.Assert.assertThrows(java.util.concurrent.CancellationException::class.java) {
                CaptureSessionExportService.exportZip(source, root.resolve("cancel.zip"),
                    onProgress = { copied = it.bytesCopied },
                    cancellation = CaptureExportCancellation { if (copied > 0) throw cancellation })
            })
            assertEquals(64L * 1024, copied)
            assertFalse(Files.exists(root.resolve("cancel.zip")))
            assertFalse(Files.exists(root.resolve(".cancel.zip.tmp")))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun activeWriterOwnsRawUntilStopAndSnapshotExcludesMetadataEditor() {
        val root = Files.createTempDirectory("active-session-lease")
        try {
            val writer = CaptureSessionWriter(configuration(), root)
            try {
                val session = CaptureSessionRepository.listSessions(root).single()
                val raw = CaptureSessionRepository.expectedFiles(session.directory).raw
                assertEquals(CaptureSessionDeleteResult.BUSY, CaptureSessionRepository.deleteSession(root, session.directory))
                val actions = listOf<() -> Unit>(
                    { CaptureSessionInspectionService.inspect(session.directory) },
                    { CaptureSessionOfflineAnalysisService.loadSignalTrace(session) },
                    { CaptureSessionOfflineAnalysisService.analyzeAndSave(session) },
                    { CaptureSessionExportService.exportZip(session, java.io.ByteArrayOutputStream()) },
                    { CaptureArchiveExportService.export(root, root.resolve("profiles"),
                        CaptureArchiveSelection(sessionDirectories = setOf(session.directory)), java.io.ByteArrayOutputStream()) },
                    { CaptureSessionMetadataEditor.updateReferenceBloodPressure(session.directory, 120, 80) },
                )
                actions.forEach { action -> org.junit.Assert.assertThrows(CaptureSessionBusyException::class.java) { action() } }
                val refused = CaptureSessionRepository.inspect(session.directory)
                assertFalse(refused.isVerifiedConsistent)
                org.junit.Assert.assertNull(refused.replay)
                org.junit.Assert.assertNull(refused.csv)
                assertEquals("session-in-use", refused.findings.single().id)
                assertTrue(Files.isRegularFile(raw))
                writer.appendRawThenDerive(1u, frameWire()) { emptyList() }
                writer.finish(CaptureStopReason.USER)
                CaptureSessionAccessRegistry.app.tryAcquire(session.directory, CaptureSessionAccessRegistry.Access.READ_SNAPSHOT)!!.use {
                    org.junit.Assert.assertThrows(CaptureSessionBusyException::class.java) {
                        CaptureSessionMetadataEditor.updateReferenceBloodPressure(session.directory, 120, 80)
                    }
                }
                CaptureSessionMetadataEditor.updateReferenceBloodPressure(session.directory, 120, 80)
                assertEquals(CaptureSessionDeleteResult.DELETED, CaptureSessionRepository.deleteSession(root, session.directory))
                assertFalse(Files.exists(raw))
            } finally { writer.close() }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun singleExportDetectsSameLengthRewritesAndPreservesCancellationIdentity() {
        val root = Files.createTempDirectory("single-export-consistency")
        try {
            val session = ReviewSessionFixtures.writeProtocols(root).first()
            val files = CaptureSessionRepository.expectedFiles(session.directory)
            val destination = root.resolve("changed.zip")
            var changed = false
            org.junit.Assert.assertThrows(CaptureSessionExportException.CannotExport::class.java) {
                CaptureSessionExportService.exportZip(session, destination, onProgress = {
                    if (!changed) {
                        changed = true
                        val bytes = Files.readAllBytes(files.csv)
                        bytes[bytes.lastIndex - 2] = if (bytes[bytes.lastIndex - 2] == 48.toByte()) 49 else 48
                        Files.write(files.csv, bytes)
                    }
                })
            }
            assertFalse(Files.exists(destination))
            assertFalse(Files.exists(root.resolve(".changed.zip.tmp")))
            val cancellation = java.util.concurrent.CancellationException("export")
            val thrown = org.junit.Assert.assertThrows(java.util.concurrent.CancellationException::class.java) {
                CaptureSessionExportService.exportZip(session, destination,
                    cancellation = CaptureExportCancellation { throw cancellation })
            }
            org.junit.Assert.assertSame(cancellation, thrown)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test
    fun zipExportStreamsThreeFilesWithRelativeNamesAndProgress() {
        val root = Files.createTempDirectory("session-export")
        try {
            val writer = CaptureSessionWriter(configuration(), root) { Long.MAX_VALUE }
            writer.append(
                CaptureStreamChunkEvent(
                    hostMonotonicNanoseconds = 123u,
                    data = frameWire(),
                    decodedFrames = emptyList(),
                ),
            )
            writer.finish(CaptureStopReason.WRITE_ERROR, "synthetic")
            val session = CaptureSessionRepository.listSessions(root).single()
            val destination = root.resolve("exports/session.zip")
            val progress = ArrayList<CaptureExportProgress>()

            val report = CaptureSessionExportService.exportZip(
                session,
                destination,
                onProgress = progress::add,
            )
            assertTrue(Files.isRegularFile(destination))
            assertEquals(3, report.entryNames.size)
            assertEquals(report.totalBytes, report.bytesCopied)
            assertTrue(progress.isNotEmpty())
            assertEquals(report.bytesCopied, progress.last().bytesCopied)
            ZipInputStream(Files.newInputStream(destination)).use { zip ->
                val names = ArrayList<String>()
                while (true) {
                    val entry = zip.nextEntry ?: break
                    names += entry.name
                    assertFalse(entry.name.startsWith("/"))
                    assertFalse(entry.name.contains(".."))
                    while (zip.read() >= 0) Unit
                }
                assertEquals(
                    setOf("export_source.cupraw", "export_source.csv", "export_source.session.json", "export_manifest.json"),
                    names.toSet(),
                )
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun cancellationRemovesTemporaryDestinationAndExistingDestinationIsProtected() {
        val root = Files.createTempDirectory("session-export-errors")
        try {
            val writer = CaptureSessionWriter(configuration(), root) { Long.MAX_VALUE }
            writer.finish(CaptureStopReason.WRITE_ERROR, "synthetic")
            val session = CaptureSessionRepository.listSessions(root).single()
            val destination = root.resolve("export.zip")
            try {
                CaptureSessionExportService.exportZip(
                    session,
                    destination,
                    cancellation = CaptureExportCancellation { throw CaptureSessionExportException.Cancelled },
                )
                error("expected cancellation")
            } catch (_: CaptureSessionExportException.Cancelled) {
                // expected
            }
            assertFalse(Files.exists(destination))
            assertFalse(Files.exists(root.resolve(".export.zip.tmp")))

            Files.writeString(destination, "existing")
            try {
                CaptureSessionExportService.exportZip(session, destination)
                error("expected destination collision")
            } catch (_: CaptureSessionExportException.DestinationAlreadyExists) {
                // expected
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun configuration() = CaptureSessionConfiguration(
        sessionId = "export-session-id",
        baseName = "export_source",
        startedUtc = Instant.parse("2026-08-02T00:00:00Z"),
        softVersion = "android-test",
        algorithmVersion = "unavailable",
        preprocessProfile = "ios_v1",
        protocolProfile = "cup_v1",
        transportProfile = "ble_gatt_v1",
        device = CaptureDeviceContext("CUP", "device-id", "service", "notify"),
    )

    private fun frameWire() = encodeCupBatchFrame(
        CupBatchFrame(
            sequence = 1u,
            samples = List(CupBatchProtocolV1.samplesPerFrame) {
                CupPpgSample(100u, 200u)
            },
        ),
    )
}
