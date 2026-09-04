package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.protocol.CupBatchFrame
import com.example.ppgcollector_android.core.protocol.CupBatchProtocolV1
import com.example.ppgcollector_android.core.protocol.CupPpgSample
import com.example.ppgcollector_android.core.protocol.Ads1292rPacket
import com.example.ppgcollector_android.core.protocol.Ads1292rPacketProtocol
import com.example.ppgcollector_android.core.protocol.encodeCupBatchFrame
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSessionRecoveryServiceTest {
    @Test
    fun recoveryCopiesOnlySafePrefixesAndPreservesSource() {
        val root = Files.createTempDirectory("session-recovery")
        try {
            val writer = CaptureSessionWriter(configuration("source_001"), root) { Long.MAX_VALUE }
            writer.append(
                CaptureStreamChunkEvent(
                    hostMonotonicNanoseconds = 1_000u,
                    data = frameWire(1u),
                    decodedFrames = emptyList(),
                ),
            )
            writer.finish(CaptureStopReason.WRITE_ERROR, "simulated crash")
            val sourceFiles = CaptureSessionRepository.expectedFiles(writer.directory)
            val rawBefore = Files.readAllBytes(sourceFiles.raw)
            val csvBefore = Files.readAllBytes(sourceFiles.csv)
            Files.write(sourceFiles.raw, byteArrayOf(1, 2, 3), java.nio.file.StandardOpenOption.APPEND)
            Files.writeString(sourceFiles.csv, "partial", Charsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND)
            val sourceRawWithTail = Files.readAllBytes(sourceFiles.raw)
            val sourceCsvWithTail = Files.readAllBytes(sourceFiles.csv)

            val stored = CaptureSessionRepository.listSessions(root).single()
            val assessment = CaptureSessionRecoveryService.assess(stored)
            assertTrue(assessment.canCreateRecoveryCopy)
            assertTrue(assessment.requiresRecovery)
            assertTrue(assessment.rawTrailingBytes > 0)
            assertTrue(assessment.csvTrailingBytes > 0)

            val result = CaptureSessionRecoveryService.recover(
                session = stored,
                requestedBaseName = "source_001_recovered",
                recoveredAt = Instant.parse("2026-08-02T01:00:00Z"),
                recoverySessionId = "recovery-id",
                recoverySoftVersion = "android-test+recovery",
            )
            assertEquals(rawBefore.size.toLong(), result.rawCopiedBytes)
            assertEquals(csvBefore.size.toLong(), result.csvCopiedBytes)
            assertArrayEquals(rawBefore, Files.readAllBytes(result.rawPath))
            assertArrayEquals(csvBefore, Files.readAllBytes(result.csvPath))
            assertArrayEquals(sourceRawWithTail, Files.readAllBytes(sourceFiles.raw))
            assertArrayEquals(sourceCsvWithTail, Files.readAllBytes(sourceFiles.csv))

            val recoveredMetadata = CaptureSessionMetadataCodec.decode(
                Files.readAllBytes(result.metadataPath),
            )
            assertEquals("recovery-id", recoveredMetadata.sessionId)
            assertNotEquals(stored.metadata?.sessionId, recoveredMetadata.sessionId)
            assertEquals(CaptureStopReason.CRASH_RECOVERY, recoveredMetadata.stopReason)
            assertFalse(recoveredMetadata.complete)
            assertEquals(CaptureSessionRecoveryService.strategy,
                recoveredMetadata.recovery?.strategy)
            assertEquals("source_001", recoveredMetadata.recovery?.sourceDirectoryName)
            assertTrue(recoveredMetadata.recovery?.csvPreservesSourceSessionId == true)
            assertEquals(sha256(sourceRawWithTail), recoveredMetadata.recovery?.sourceRawSha256)
            assertEquals(sha256(sourceCsvWithTail), recoveredMetadata.recovery?.sourceCsvSha256)
            assertTrue(Files.notExists(root.resolve(".recovery-recovery-id")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun recoveryRefusesDestinationOverwriteAndInvalidCsvHeader() {
        val root = Files.createTempDirectory("session-recovery-errors")
        try {
            val writer = CaptureSessionWriter(configuration("source_002"), root) { Long.MAX_VALUE }
            writer.finish(CaptureStopReason.WRITE_ERROR, "synthetic")
            val sourceFiles = CaptureSessionRepository.expectedFiles(writer.directory)
            Files.writeString(sourceFiles.csv, "bad-header\n",
                Charsets.UTF_8,
                java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)
            val stored = CaptureSessionRepository.listSessions(root).single()
            try {
                CaptureSessionRecoveryService.recover(stored, "recovered")
                error("expected invalid header")
            } catch (_: CaptureSessionRecoveryException.SourceCsvHeaderInvalid) {
                // expected
            }
            Files.writeString(sourceFiles.csv, CaptureCsvSchema.header)
            Files.createDirectory(root.resolve("recovered"))
            try {
                CaptureSessionRecoveryService.recover(stored, "recovered")
                error("expected destination collision")
            } catch (_: CaptureSessionRecoveryException.DestinationAlreadyExists) {
                // expected
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun recoveryPreservesBpEcgMetadataAndAcceptsSourceSessionIdsInCopiedEcg() {
        val root = Files.createTempDirectory("session-recovery-ecg")
        try {
            val configuration = configuration("ads_source").copy(
                protocolProfile = Ads1292rPacketProtocol.profileIdentifier,
                systolicBp = 126,
                diastolicBp = 82,
                recordMode = CaptureRecordMode.TIMED,
                plannedDurationSeconds = 30,
            )
            val writer = CaptureSessionWriter(configuration, root) { Long.MAX_VALUE }
            val packet = Ads1292rPacket(
                sequenceNumber = 4u,
                ecg = List(20) { (30_000 + it).toUInt() },
                red = List(4) { (10_000 + it).toUInt() },
                ir = List(4) { (20_000 + it).toUInt() },
            )
            val wire = Ads1292rPacketProtocol.encode(packet)
            writer.appendRawThenDerive(1_000u, wire) { emptyList() }
            writer.appendAds1292rPacket(1_000u, packet)
            writer.appendBloodPressure(
                ManualBloodPressureEvent(
                    reference = CaptureReferenceTimestamp(
                        sessionId = configuration.sessionId,
                        connectionGeneration = 1,
                        eventIndex = 0,
                        sourceSampleIndex = 0,
                        sourceTimeSeconds = 0.0,
                        dialogOpenHostMonotonicNanoseconds = 1u,
                        dialogOpenUtc = configuration.startedUtc,
                    ),
                    savedUtc = configuration.startedUtc,
                    systolicMmHg = 126,
                    diastolicMmHg = 82,
                ),
            )
            writer.finish(CaptureStopReason.WRITE_ERROR, "synthetic incomplete ADS session")

            val stored = CaptureSessionRepository.listSessions(root).single()
            val result = CaptureSessionRecoveryService.recover(
                stored,
                requestedBaseName = "ads_source_recovered",
                recoverySessionId = "ads-recovery-id",
                recoveredAt = Instant.parse("2026-08-02T01:00:00Z"),
            )
            val recovered = CaptureSessionMetadataCodec.decode(result.metadataPath)
            assertEquals(126, recovered.systolicBp)
            assertEquals(82, recovered.diastolicBp)
            assertEquals(CaptureRecordMode.TIMED, recovered.recordMode)
            assertEquals(30, recovered.plannedDurationSeconds)
            assertEquals(500, recovered.ecgSampleRateHz)
            assertEquals("ads_source_recovered_ecg.csv", recovered.files.ecg)
            assertEquals(20L, CaptureEcgCsv.scan(result.ecgPath!!).completeDataRowCount)

            val inspection = CaptureSessionInspectionService.inspect(result.directory)
            assertTrue(inspection.findings.none { it.id == "ecg-session-id" })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun configuration(name: String) = CaptureSessionConfiguration(
        sessionId = "source-session-$name",
        baseName = name,
        startedUtc = Instant.parse("2026-08-02T00:00:00Z"),
        softVersion = "android-test",
        algorithmVersion = "unavailable",
        preprocessProfile = "ios_v1",
        protocolProfile = "cup_v1",
        transportProfile = "ble_gatt_v1",
        device = CaptureDeviceContext("CUP", "device-id", "service", "notify"),
    )

    private fun frameWire(sequence: UByte): ByteArray = encodeCupBatchFrame(
        CupBatchFrame(
            sequence,
            List(CupBatchProtocolV1.samplesPerFrame) { index ->
                CupPpgSample((100 + index).toUInt(), (200 + index).toUInt())
            },
        ),
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
