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
                    setOf("export_source.cupraw", "export_source.csv", "export_source.session.json"),
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
