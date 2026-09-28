package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.protocol.CupBatchProtocolV1
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSessionMetadataTest {
    @Test
    fun recoveryParentAndOriginalPrefixAreOptionalAndRoundTrip() {
        val metadata = sampleMetadata(recovery = sampleRecovery().copy(
            parentSessionId = "direct-parent", originalCanonicalPrefix = "MB"))
        val encoded = CaptureSessionMetadataCodec.encode(metadata)
        assertEquals(metadata, CaptureSessionMetadataCodec.decode(encoded))
        val legacy = encoded.lineSequence().filterNot {
            it.contains("\"parent_session_id\"") || it.contains("\"original_canonical_prefix\"")
        }.joinToString("\n")
        val decoded = CaptureSessionMetadataCodec.decode(legacy)
        assertEquals(null, decoded.recovery!!.parentSessionId)
        assertEquals(null, decoded.recovery.originalCanonicalPrefix)
        assertEquals(metadata.recovery!!.sourceSessionId, decoded.recovery.sourceSessionId)
        assertDecodeFailure(encoded.replace("\"original_canonical_prefix\": \"MB\"", "\"original_canonical_prefix\": \"OTHER\""))
    }

    @Test
    fun encodeDecodeRoundTripsCompleteMetadataWithRecovery() {
        val metadata = sampleMetadata(
            endedUtc = Instant.parse("2023-11-14T22:13:21.123456Z"),
            stopReason = CaptureStopReason.USER,
            recovery = sampleRecovery(),
        )

        val encoded = CaptureSessionMetadataCodec.encode(metadata)
        val decoded = CaptureSessionMetadataCodec.decode(encoded)

        assertEquals(metadata, decoded)
        assertTrue(encoded.contains("\"schema_version\""))
        assertTrue(encoded.contains("\"service_uuid\""))
        assertTrue(encoded.contains("\"source_csv_copied_bytes\""))
        assertFalse(encoded.contains("schemaVersion"))
        assertFalse(encoded.contains("sessionID"))
        assertEquals(encoded, CaptureSessionMetadataCodec.encode(decoded))
    }

    @Test
    fun incompleteMetadataSupportsNullsAndMissingOptionalFields() {
        val metadata = sampleMetadata(
            endedUtc = null,
            stopReason = null,
            recovery = null,
        )
        val encoded = CaptureSessionMetadataCodec.encode(metadata)
        val withoutOptionals = encoded
            .replace("  \"ended_utc\": null,\n", "")
            .replace("  \"recovery\": null\n", "")

        val decoded = CaptureSessionMetadataCodec.decode(withoutOptionals)

        assertEquals(metadata, decoded)
        assertFalse(decoded.complete)
        assertEquals(null, decoded.endedUtc)
        assertEquals(null, decoded.stopReason)
        assertEquals(null, decoded.recovery)
    }

    @Test
    fun decoderIgnoresUnknownAdditiveFieldsAndMapsUnknownStopReason() {
        val encoded = CaptureSessionMetadataCodec.encode(sampleMetadata())
        val withUnknown = encoded.replaceFirst(
            "{\n",
            "{\n  \"future_field\": {\"enabled\": true},\n",
        ).replace("\"stop_reason\": \"user\"", "\"stop_reason\": \"future_reason\"")

        val decoded = CaptureSessionMetadataCodec.decode(withUnknown)

        assertEquals(CaptureStopReason.UNKNOWN, decoded.stopReason)
        assertEquals("capture_001", decoded.baseName)
    }

    @Test
    fun recordModeRoundTripsAndOldMetadataWithoutItRemainsUnknown() {
        val metadata = sampleMetadata().copy(
            recordMode = CaptureRecordMode.TIMED,
            plannedDurationSeconds = 45,
        )
        val encoded = CaptureSessionMetadataCodec.encode(metadata)
        assertEquals(metadata, CaptureSessionMetadataCodec.decode(encoded))

        val oldEncoded = encoded.replace("  \"record_mode\": \"timed\",\n", "")
        val decodedOld = CaptureSessionMetadataCodec.decode(oldEncoded)
        assertEquals(null, decodedOld.recordMode)
        assertEquals(45, decodedOld.plannedDurationSeconds)
    }

    @Test
    fun codecEscapesStringsAndRejectsMalformedOrMissingRequiredFields() {
        val metadata = sampleMetadata().copy(
            baseName = "line, \"quoted\"\nname",
            device = sampleMetadata().device.copy(name = "CUP\\SIM"),
        )
        val encoded = CaptureSessionMetadataCodec.encode(metadata)
        assertTrue(encoded.contains("line, \\\"quoted\\\"\\nname"))
        assertEquals(metadata, CaptureSessionMetadataCodec.decode(encoded))

        assertDecodeFailure("not json")
        assertDecodeFailure(encoded.replace("\"sample_count\": 20", "\"sample_count\": true"))
        assertDecodeFailure(encoded.replace("\"device\": {", "\"device_missing\": {"))
    }

    private fun sampleMetadata(
        endedUtc: Instant? = Instant.parse("2023-11-14T22:13:21Z"),
        stopReason: CaptureStopReason? = CaptureStopReason.USER,
        recovery: CaptureSessionRecoveryMetadata? = null,
    ) = CaptureSessionMetadata(
        schemaVersion = "ppgcollector_session_v1",
        sessionId = "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE",
        baseName = "capture_001",
        startedUtc = Instant.parse("2023-11-14T22:13:20Z"),
        endedUtc = endedUtc,
        softVersion = "1.0+1",
        algVersion = "unavailable",
        preprocessProfile = "raw-only-0.1",
        protocolProfile = CupBatchProtocolV1.profileIdentifier,
        transportProfile = "cup-nus-bringup-0.1",
        sampleRateHz = 100,
        samplesPerFrame = CupBatchProtocolV1.samplesPerFrame,
        device = CaptureSessionDeviceMetadata(
            name = "CUP-SIM",
            identifier = "11111111-2222-3333-4444-555555555555",
            serviceUuid = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E",
            notifyCharacteristicUuid = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E",
            firmwareVersion = null,
            calibrationId = null,
        ),
        complete = endedUtc != null,
        stopReason = stopReason,
        frameCount = 1,
        sampleCount = CupBatchProtocolV1.samplesPerFrame.toLong(),
        rawChunkCount = 1,
        missingFrames = 0,
        duplicateFrames = 0,
        outOfOrderFrames = 0,
        invalidFrames = 0,
        discardedBytes = 0,
        writer = CaptureSessionWriterMetadata(
            lastFlushUtc = endedUtc,
            rawBytes = 188,
            csvRows = CupBatchProtocolV1.samplesPerFrame.toLong(),
            error = null,
        ),
        files = CaptureSessionFilesMetadata(
            raw = "capture_001.cupraw",
            samples = "capture_001.csv",
        ),
        recovery = recovery,
    )

    private fun sampleRecovery() = CaptureSessionRecoveryMetadata(
        strategy = "copy_safe_prefix_v1",
        recoveredUtc = Instant.parse("2023-11-14T22:14:00Z"),
        recoverySoftVersion = "1.0+1",
        sourceDirectoryName = "damaged",
        sourceSessionId = "BBBBBBBB-CCCC-DDDD-EEEE-FFFFFFFFFFFF",
        sourceRawSha256 = "a".repeat(64),
        sourceCsvSha256 = "b".repeat(64),
        sourceMetadataSha256 = null,
        sourceRawTotalBytes = 1_000,
        sourceRawCopiedBytes = 900,
        sourceCsvTotalBytes = 2_000,
        sourceCsvCopiedBytes = 1_900,
        csvPreservesSourceSessionId = true,
    )

    private fun assertDecodeFailure(json: String) {
        try {
            CaptureSessionMetadataCodec.decode(json)
            throw AssertionError("expected metadata decode failure")
        } catch (_: CaptureSessionMetadataJsonException) {
            // expected
        }
    }
}
