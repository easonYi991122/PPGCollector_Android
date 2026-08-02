package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.protocol.CupBatchFrame
import com.example.ppgcollector_android.core.protocol.CupBatchProtocolV1
import com.example.ppgcollector_android.core.protocol.CupPpgSample
import com.example.ppgcollector_android.core.protocol.encodeCupBatchFrame
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSessionInspectionTest {
    @Test
    fun replayUsesOnePipelineAndKeepsOnlyRecentSamples() {
        val first = referenceFrame(42u)
        val second = referenceFrame(44u)
        val bytes = rawBytes(
            listOf(
                1_000uL to first.copyOfRange(0, 100),
                2_000uL to first.copyOfRange(100, first.size),
                3_000uL to second,
            ),
        )

        val report = CupRawReplayEngine.replay(bytes)

        assertEquals(3L, report.rawRecordCount)
        assertEquals(2, report.decodedFrames)
        assertEquals(2, report.acceptedFrames)
        assertEquals(100L, report.acceptedSamples)
        assertEquals(1, report.missingFrames)
        assertEquals(0, report.duplicateFrames)
        assertEquals(0, report.outOfOrderFrames)
        assertEquals(100, report.recentSamples.size)
        assertEquals(99L, report.recentSamples.last().sampleIndex)
        assertEquals(2_000uL, report.firstFrameHostNanoseconds)
        assertEquals(3_000uL, report.lastFrameHostNanoseconds)
        assertTrue(report.isStructurallyClean)
    }

    @Test
    fun replayReportsSafeRawTailWithoutDiscardingCompleteRecords() {
        val complete = rawBytes(1u, referenceFrame(1u))
        val report = CupRawReplayEngine.replay(complete + byteArrayOf(1, 2, 3))

        assertEquals(1L, report.rawRecordCount)
        assertEquals(50L, report.acceptedSamples)
        assertNotNull(report.tailIssue)
        assertEquals(complete.size.toLong(), report.validRawBytes)
        assertEquals(3L, report.trailingRawBytes)
        assertTrue(report.hasRecoverableTail())
    }

    @Test
    fun replayTreatsMidFrameRecordingPrefixAsAuditedAlignmentNotCorruption() {
        val previousFrame = referenceFrame(41u)
        val firstCompleteFrame = referenceFrame(42u)
        val secondCompleteFrame = referenceFrame(43u)
        val prefix = previousFrame.copyOfRange(previousFrame.size - 28, previousFrame.size)
        val bytes = rawBytes(
            listOf(
                1_000uL to prefix.copyOfRange(0, 20),
                2_000uL to prefix.copyOfRange(20, prefix.size),
                3_000uL to firstCompleteFrame,
                4_000uL to secondCompleteFrame,
            ),
        )

        val report = CupRawReplayEngine.replay(bytes)

        assertEquals(28, report.leadingAlignmentBytes)
        assertEquals(28, report.discardedBytes)
        assertEquals(0, report.structuralDiscardedBytes)
        assertEquals(2, report.decodedFrames)
        assertEquals(100L, report.acceptedSamples)
        assertTrue(report.isStructurallyClean)
    }

    @Test
    fun replayTreatsIncompleteProtocolFrameAtRecordingStopAsAuditedSuffix() {
        val frame = referenceFrame(42u)
        val bytes = rawBytes(
            listOf(
                1_000uL to frame,
                2_000uL to referenceFrame(43u).copyOfRange(0, 220),
            ),
        )

        val report = CupRawReplayEngine.replay(bytes)

        assertEquals(220, report.pendingDecoderBytes)
        assertEquals(1, report.decodedFrames)
        assertEquals(50L, report.acceptedSamples)
        assertTrue(report.isStructurallyClean)
    }

    @Test
    fun inspectionExplainsMidFramePrefixWithoutGenericStructureError() {
        withSessionDirectory { directory ->
            val base = directory.fileName.toString()
            val previousFrame = referenceFrame(41u)
            val frame = referenceFrame(42u)
            CupRawWriter(directory.resolve("$base.cupraw")).use { writer ->
                writer.append(1_000u, previousFrame.copyOfRange(previousFrame.size - 28, previousFrame.size))
                writer.append(2_000u, frame)
            }
            Files.writeString(directory.resolve("$base.csv"), buildString {
                append(CaptureCsvSchema.header)
                repeat(CupBatchProtocolV1.samplesPerFrame) { index ->
                    append(CaptureCsvFormatter.format(csvRow(frame, index), 0))
                }
            })
            Files.writeString(
                directory.resolve("$base.session.json"),
                CaptureSessionMetadataCodec.encode(sampleMetadata().copy(rawChunkCount = 2)),
            )

            val inspection = CaptureSessionInspectionService.inspect(directory)
            val findingIds = inspection.findings.map { it.id }

            assertTrue("raw-alignment-prefix" in findingIds)
            assertFalse("raw-structure" in findingIds)
            assertEquals(CaptureInspectionSeverity.WARNING, inspection.findings.single().severity)
        }
    }

    @Test
    fun csvScanIsStreamingAndClassifiesNonNewlineTerminatedTail() {
        val complete = CaptureCsvSchema.header + "row,1\n"
        val path = Files.createTempFile("capture-scan", ".csv")
        try {
            Files.writeString(path, complete + "partial")
            val report = CaptureSessionInspectionService.scanCsv(path)

            assertEquals((complete + "partial").toByteArray().size.toLong(), report.totalBytes)
            assertEquals(complete.toByteArray().size.toLong(), report.validByteCount)
            assertEquals(1L, report.completeDataRowCount)
            assertTrue(report.hasExpectedHeader)
            assertTrue(report.hasTruncatedFinalLine)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun inspectionCrossChecksRawCsvAndMetadataWithoutChangingSource() {
        withSessionDirectory { directory ->
            val base = directory.fileName.toString()
            val rawPath = directory.resolve("$base.cupraw")
            val csvPath = directory.resolve("$base.csv")
            val metadataPath = directory.resolve("$base.session.json")
            val frame = referenceFrame(42u)

            CupRawWriter(rawPath).use { writer ->
                writer.append(1_000u, frame)
            }
            val csv = buildString {
                append(CaptureCsvSchema.header)
                repeat(CupBatchProtocolV1.samplesPerFrame) { index ->
                    append(
                        CaptureCsvFormatter.format(
                            row = csvRow(frame, index),
                            firstStreamSampleIndex = 0,
                        ),
                    )
                }
            }
            Files.writeString(csvPath, csv)
            Files.writeString(
                metadataPath,
                CaptureSessionMetadataCodec.encode(sampleMetadata()),
            )

            val before = Files.readAllBytes(rawPath).contentHashCode()
            val inspection = CaptureSessionInspectionService.inspect(directory)
            val after = Files.readAllBytes(rawPath).contentHashCode()

            assertTrue(inspection.isVerifiedConsistent)
            assertTrue(inspection.findings.isEmpty())
            assertEquals(before, after)
            assertEquals(50L, inspection.replay!!.acceptedSamples)
            assertEquals(50L, inspection.csv!!.completeDataRowCount)
        }
    }

    @Test
    fun inspectionClassifiesMissingFilesAndMetadataCountMismatch() {
        withSessionDirectory { directory ->
            val base = directory.fileName.toString()
            val rawPath = directory.resolve("$base.cupraw")
            val metadataPath = directory.resolve("$base.session.json")
            CupRawWriter(rawPath).use { it.append(1u, referenceFrame(1u)) }
            Files.writeString(
                metadataPath,
                CaptureSessionMetadataCodec.encode(sampleMetadata().copy(sampleCount = 49)),
            )

            val inspection = CaptureSessionInspectionService.inspect(directory)
            val findingIds = inspection.findings.map { it.id }

            assertFalse(inspection.isVerifiedConsistent)
            assertTrue("csv-missing" in findingIds)
            assertTrue("raw-sample-count" in findingIds)
        }
    }

    private fun referenceFrame(sequence: UByte): ByteArray = encodeCupBatchFrame(
        CupBatchFrame(
            sequence,
            List(CupBatchProtocolV1.samplesPerFrame) { index ->
                CupPpgSample((100_000 + index).toUInt(), (120_000 + index).toUInt())
            },
        ),
    )

    private fun rawBytes(timestamp: ULong, chunk: ByteArray): ByteArray {
        return rawBytes(listOf(timestamp to chunk))
    }

    private fun rawBytes(records: List<Pair<ULong, ByteArray>>): ByteArray {
        val directory = Files.createTempDirectory("replay-raw")
        val path = directory.resolve("sample.cupraw")
        try {
            CupRawWriter(path).use { writer ->
                records.forEach { (timestamp, chunk) ->
                    writer.append(timestamp, chunk)
                }
            }
            return Files.readAllBytes(path)
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    private fun csvRow(frame: ByteArray, index: Int): CaptureCsvRow = CaptureCsvRow(
        schemaVersion = "capture_csv_v1",
        sessionId = "session",
        sampleIndex = index.toLong(),
        hostFrameTimeNanoseconds = 1_000u,
        frameSequence = frame[5].toUByte(),
        sampleInFrame = index,
        red = (100_000 + index).toUInt(),
        ir = (120_000 + index).toUInt(),
        heartRateBpm = CsvMetricCell(null, false, null),
        oxygenSaturationPercent = CsvMetricCell(null, false, null),
        signalQuality = CsvMetricCell(null, false, null),
        softVersion = "soft",
        algorithmVersion = "alg",
        preprocessProfile = "raw-only",
        protocolProfile = "cup-draft",
        ratioOfRatios = CsvMetricCell(null, false, null),
    )

    private fun sampleMetadata() = CaptureSessionMetadata(
        schemaVersion = "ppgcollector_session_v1",
        sessionId = "session",
        baseName = "capture",
        startedUtc = Instant.parse("2023-11-14T22:13:20Z"),
        endedUtc = Instant.parse("2023-11-14T22:13:21Z"),
        softVersion = "soft",
        algVersion = "alg",
        preprocessProfile = "raw-only",
        protocolProfile = "cup-draft",
        transportProfile = "cup-nus",
        sampleRateHz = 100,
        samplesPerFrame = 50,
        device = CaptureSessionDeviceMetadata("CUP", "id", "service", "notify", null, null),
        complete = true,
        stopReason = CaptureStopReason.USER,
        frameCount = 1,
        sampleCount = 50,
        rawChunkCount = 1,
        missingFrames = 0,
        duplicateFrames = 0,
        outOfOrderFrames = 0,
        invalidFrames = 0,
        discardedBytes = 0,
        writer = CaptureSessionWriterMetadata(null, 428, 50, null),
        files = CaptureSessionFilesMetadata("capture.cupraw", "capture.csv"),
        recovery = null,
    )

    private fun withSessionDirectory(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("session-inspection")
        val directory = root.resolve("capture")
        Files.createDirectory(directory)
        try {
            block(directory)
        } finally {
            Files.list(directory).use { paths -> paths.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(directory)
            Files.deleteIfExists(root)
        }
    }

    private fun CupRawReplayReport.hasRecoverableTail(): Boolean = tailIssue != null
}
