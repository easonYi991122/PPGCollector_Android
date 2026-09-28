package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.ble.BleConnectionPhase
import com.example.ppgcollector_android.core.protocol.CupBatchFrame
import com.example.ppgcollector_android.core.protocol.CupBatchProtocolV1
import com.example.ppgcollector_android.core.protocol.CupDecodedFrameEvent
import com.example.ppgcollector_android.core.protocol.CupPpgSample
import com.example.ppgcollector_android.core.protocol.CupSequenceEvent
import com.example.ppgcollector_android.core.protocol.decodeCupBatchFrame
import com.example.ppgcollector_android.core.signal.StreamFreshness
import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSessionWriterTest {
    @Test
    fun createsIncompleteSessionAndAppendsRawBeforeDerivedRows() {
        val root = Files.createTempDirectory("capture-writer")
        try {
            val writer = CaptureSessionWriter(configuration(), root) { Long.MAX_VALUE }
            val event = CaptureStreamChunkEvent(
                hostMonotonicNanoseconds = 123u,
                data = byteArrayOf(1, 2, 3),
                decodedFrames = listOf(
                    CupDecodedFrameEvent(frame(), CupSequenceEvent.First, true),
                ),
            )
            val snapshot = writer.append(event)
            assertEquals(1, snapshot.rawChunkCount)
            assertEquals(20, snapshot.csvRows)
            assertTrue(Files.readAllBytes(writer.rawPath).size > CupRawFormat.magic.size)
            assertEquals(false, CaptureSessionMetadataCodec.decode(Files.readString(writer.metadataPath)).complete)

            val summary = writer.finish(CaptureStopReason.USER)
            assertTrue(summary.complete)
            assertEquals(summary, writer.finish(CaptureStopReason.WRITE_ERROR))
            val metadata = CaptureSessionMetadataCodec.decode(Files.readString(writer.metadataPath))
            assertTrue(metadata.complete)
            assertEquals(CaptureStopReason.USER, metadata.stopReason)
            assertEquals(20, metadata.sampleCount)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsOverwriteAndAppliesStartGateBeforeWriterCreation() {
        val root = Files.createTempDirectory("capture-gate")
        try {
            val valid = CaptureStartContext(
                isRecording = false,
                phase = BleConnectionPhase.Receiving("device"),
                freshness = StreamFreshness.FRESH,
                sessionsRoot = root,
                sessionName = "session_001",
                availableBytes = Long.MAX_VALUE,
            )
            assertNull(CaptureStartGate.validate(valid))
            CaptureSessionWriter(configuration(), root) { Long.MAX_VALUE }.close()
            assertEquals(
                CaptureStartFailure.SessionAlreadyExists,
                CaptureStartGate.validate(valid),
            )
            assertEquals(
                CaptureStartFailure.StreamNotFresh,
                CaptureStartGate.validate(valid.copy(freshness = StreamFreshness.STALE)),
            )
            assertEquals(
                CaptureStartFailure.InvalidSessionName,
                CaptureStartGate.validate(valid.copy(sessionName = "bad/name")),
            )
            assertEquals(
                CaptureStartFailure.InsufficientStorage,
                CaptureStartGate.validate(
                    valid.copy(
                        sessionName = "low_storage",
                        availableBytes = CaptureSessionWriterPolicy.minimumAvailableCapacityBytes - 1,
                    ),
                ),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun decoderFailureAfterRawAcknowledgementLeavesAuditableIncompleteRaw() {
        val root = Files.createTempDirectory("capture-raw-first")
        try {
            val writer = CaptureSessionWriter(configuration(), root) { Long.MAX_VALUE }
            try {
                writer.appendRawThenDerive(
                    hostMonotonicNanoseconds = 77u,
                    data = byteArrayOf(9, 8, 7),
                ) {
                    error("synthetic decoder failure")
                }
            } catch (_: IllegalStateException) {
                // The raw record must remain even though derivation failed.
            }
            assertEquals(1, CupRawReader.read(writer.rawPath).size)
            val summary = writer.finish(CaptureStopReason.WRITE_ERROR, "decoder failure")
            assertFalse(summary.complete)
            assertEquals(1, summary.writer.rawChunkCount)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun legacyReplayFramesRetainTheirObservedProtocolMetadata() {
        val root = Files.createTempDirectory("capture-legacy-writer")
        try {
            val wire = loadHexFixture("protocol/legacy_golden_seq42.hex")
            val frame = decodeCupBatchFrame(wire)
            val writer = CaptureSessionWriter(configuration(), root) { Long.MAX_VALUE }

            val snapshot = writer.append(
                CaptureStreamChunkEvent(
                    hostMonotonicNanoseconds = 123u,
                    data = wire,
                    decodedFrames = listOf(
                        CupDecodedFrameEvent(frame, CupSequenceEvent.First, true),
                    ),
                ),
            )
            writer.finish(CaptureStopReason.USER)

            assertEquals(CupBatchProtocolV1.legacySamplesPerFrame.toLong(), snapshot.csvRows)
            val metadata = CaptureSessionMetadataCodec.decode(Files.readString(writer.metadataPath))
            assertEquals(CupBatchProtocolV1.legacyProfileIdentifier, metadata.protocolProfile)
            assertEquals(CupBatchProtocolV1.legacySamplesPerFrame, metadata.samplesPerFrame)
            assertTrue(
                Files.readAllLines(writer.csvPath).drop(1).all {
                    CupBatchProtocolV1.legacyProfileIdentifier in it
                },
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun finalizationForcesRawSamplesAndEveryCreatedSidecarBeforeCompleteMetadata() {
        val root = Files.createTempDirectory("capture-force-order")
        val registry = CaptureSessionAccessRegistry()
        val forced = mutableListOf<String>()
        var finalizing = false
        val config = configuration().copy(protocolProfile = com.example.ppgcollector_android.core.protocol.Ads1292rPacketProtocol.profileIdentifier)
        val writer = CaptureSessionWriter(config, root, CaptureStorageCapacityProvider { Long.MAX_VALUE }, registry) { path ->
            if (finalizing) {
                forced += path.fileName.toString()
                assertFalse(CaptureSessionMetadataCodec.decode(Files.readString(root.resolve(config.baseName).resolve("${config.baseName}.session.json"))).complete)
                assertNull(registry.tryAcquire(path.parent, CaptureSessionAccessRegistry.Access.READ_SNAPSHOT))
            }
        }
        try {
            appendEverySidecar(writer)
            writer.updateDecoderDiagnostics(3, 504)
            finalizing = true
            assertTrue(writer.finish(CaptureStopReason.USER).complete)
            assertEquals(listOf("session_001.cupraw", "session_001.csv", "session_001.metrics.csv",
                "session_001.blood-pressure.csv", "session_001_ecg.csv", "session_001.session.json"), forced)
            val metadata = CaptureSessionMetadataCodec.decode(Files.readString(writer.metadataPath))
            assertTrue(metadata.complete)
            assertEquals(3L, metadata.invalidFrames)
            assertEquals(504L, metadata.discardedBytes)
            registry.tryAcquire(writer.directory, CaptureSessionAccessRegistry.Access.DESTRUCTIVE)!!.close()
        } finally {
            writer.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun eachFinalForceFailureKeepsIncompleteEvidenceAndCannotBecomeCompleteOnRetry() {
        for (suffix in listOf(".cupraw", ".csv", ".metrics.csv", ".blood-pressure.csv", "_ecg.csv", ".session.json")) {
            val root = Files.createTempDirectory("capture-force-failure")
            val registry = CaptureSessionAccessRegistry()
            var finalizing = false
            val config = configuration().copy(protocolProfile = com.example.ppgcollector_android.core.protocol.Ads1292rPacketProtocol.profileIdentifier)
            val writer = CaptureSessionWriter(config, root, CaptureStorageCapacityProvider { Long.MAX_VALUE }, registry) { path ->
                if (finalizing && path.fileName.toString() == "session_001$suffix") throw java.io.IOException("injected force: $suffix")
            }
            try {
                appendEverySidecar(writer)
                finalizing = true
                repeat(2) {
                    val failure = runCatching { writer.finish(CaptureStopReason.USER) }.exceptionOrNull()
                    assertNotNull("$suffix must fail", failure)
                    assertTrue(failure!!.message!!.contains("injected force"))
                    val metadata = CaptureSessionMetadataCodec.decode(Files.readString(writer.metadataPath))
                    assertFalse("$suffix must remain incomplete", metadata.complete)
                    if (suffix != ".session.json") assertTrue(metadata.writer.error!!.contains("injected force"))
                }
                registry.tryAcquire(writer.directory, CaptureSessionAccessRegistry.Access.READ_SNAPSHOT)!!.close()
            } finally {
                writer.close()
                root.toFile().deleteRecursively()
            }
        }
    }

    private fun appendEverySidecar(writer: CaptureSessionWriter) {
        val packet = com.example.ppgcollector_android.core.protocol.Ads1292rPacket(
            sequenceNumber = 1u, ecg = List(20) { it.toUInt() }, red = List(4) { 100u }, ir = List(4) { 200u })
        writer.appendRawThenDerive(1u, com.example.ppgcollector_android.core.protocol.Ads1292rPacketProtocol.encode(packet)) { emptyList() }
        writer.appendAds1292rPacket(1u, packet)
        writer.appendMetricEpoch(CaptureMetricEpoch(writer.configuration.sessionId, 1, 1, 3, 0.03,
            Instant.EPOCH, com.example.ppgcollector_android.core.signal.LiveMetricSnapshot.unavailable(true, StreamFreshness.FRESH)))
        writer.appendBloodPressure(ManualBloodPressureEvent(
            CaptureReferenceTimestamp(writer.configuration.sessionId, 1, 0, 3, 0.03, 1u, Instant.EPOCH),
            Instant.EPOCH, 120, 80))
    }

    private fun configuration() = CaptureSessionConfiguration(
        sessionId = "session-id",
        baseName = "session_001",
        startedUtc = Instant.parse("2026-08-02T00:00:00Z"),
        softVersion = "android-test",
        algorithmVersion = "unavailable",
        preprocessProfile = "ios_v1",
        protocolProfile = "cup_v1",
        transportProfile = "ble_gatt_v1",
        device = CaptureDeviceContext("CUP", "device-id", "service", "notify"),
    )

    private fun frame() = CupBatchFrame(
        sequence = 1u,
        samples = List(CupBatchProtocolV1.samplesPerFrame) {
            CupPpgSample(100u + it.toUInt(), 200u + it.toUInt())
        },
    )

    private fun loadHexFixture(resourceName: String): ByteArray = javaClass.classLoader!!
        .getResourceAsStream(resourceName)!!
        .bufferedReader()
        .readText()
        .filterNot(Char::isWhitespace)
        .chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}
