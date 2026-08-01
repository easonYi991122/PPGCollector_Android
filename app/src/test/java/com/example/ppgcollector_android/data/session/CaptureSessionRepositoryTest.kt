package com.example.ppgcollector_android.data.session

import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSessionRepositoryTest {
    @Test
    fun listsFilesystemSessionsAndFindsIncompleteOrUnreadableCandidates() {
        val root = Files.createTempDirectory("session-repository")
        try {
            val complete = CaptureSessionWriter(configuration("complete_001"), root) { Long.MAX_VALUE }
            complete.finish(CaptureStopReason.USER)

            val incomplete = CaptureSessionWriter(configuration("incomplete_001"), root) { Long.MAX_VALUE }
            incomplete.finish(CaptureStopReason.WRITE_ERROR, "synthetic")

            val malformed = root.resolve("malformed_001")
            Files.createDirectory(malformed)
            Files.writeString(malformed.resolve("malformed_001.session.json"), "not-json")

            val sessions = CaptureSessionRepository.listSessions(root)
            assertEquals(3, sessions.size)
            assertTrue(sessions.any { it.baseName == "complete_001" && it.isVerifiedComplete })
            assertTrue(sessions.any { it.baseName == "incomplete_001" && it.isRecoveryCandidate })
            assertTrue(sessions.any { it.baseName == "malformed_001" && !it.metadataIsReadable })

            val candidates = CaptureSessionRepository.incompleteSessions(root)
            assertEquals(setOf("incomplete_001", "malformed_001"), candidates.map { it.baseName }.toSet())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun metadataCheckpointLeavesNoTemporaryFileAndInspectionDoesNotModifySource() {
        val root = Files.createTempDirectory("session-checkpoint")
        try {
            val writer = CaptureSessionWriter(configuration("checkpoint_001"), root) { Long.MAX_VALUE }
            val metadata = writer.metadataPath
            writer.appendRawThenDerive(99u, byteArrayOf(1, 2, 3)) { emptyList() }
            assertTrue(Files.isRegularFile(metadata))
            assertFalse(Files.exists(metadata.resolveSibling(".${metadata.fileName}.tmp")))

            val rawBefore = Files.readAllBytes(writer.rawPath).contentHashCode()
            CaptureSessionRepository.inspect(writer.directory)
            assertEquals(rawBefore, Files.readAllBytes(writer.rawPath).contentHashCode())
            writer.finish(CaptureStopReason.WRITE_ERROR, "synthetic")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun configuration(name: String) = CaptureSessionConfiguration(
        sessionId = "session-$name",
        baseName = name,
        startedUtc = Instant.parse("2026-08-02T00:00:00Z"),
        softVersion = "android-test",
        algorithmVersion = "unavailable",
        preprocessProfile = "ios_v1",
        protocolProfile = "cup_v1",
        transportProfile = "ble_gatt_v1",
        device = CaptureDeviceContext("CUP", "device-id", "service", "notify"),
    )
}
