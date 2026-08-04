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
