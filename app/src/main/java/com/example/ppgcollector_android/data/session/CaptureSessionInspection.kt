package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.protocol.CupStreamProtocolMode
import java.nio.file.Files
import java.nio.file.Path

data class CaptureCsvScanReport(
    val totalBytes: Long,
    val validByteCount: Long,
    val completeDataRowCount: Long,
    val hasExpectedHeader: Boolean,
    val hasTruncatedFinalLine: Boolean,
) {
    val trailingByteCount: Long
        get() = maxOf(0L, totalBytes - validByteCount)
}

enum class CaptureInspectionSeverity { WARNING, ERROR }

data class CaptureInspectionFinding(
    val id: String,
    val severity: CaptureInspectionSeverity,
    val message: String,
)

data class CaptureSessionInspection(
    val replay: CupRawReplayReport?,
    val csv: CaptureCsvScanReport?,
    val metadata: CaptureSessionMetadata?,
    val findings: List<CaptureInspectionFinding>,
) {
    val isVerifiedConsistent: Boolean
        get() = findings.isEmpty() &&
            replay?.isStructurallyClean == true &&
            csv?.hasExpectedHeader == true &&
            csv.hasTruncatedFinalLine == false

    val hasRecoverableTail: Boolean
        get() = replay?.tailIssue != null || csv?.hasTruncatedFinalLine == true
}

object CaptureSessionInspectionService {
    fun inspect(directory: Path): CaptureSessionInspection {
        val baseName = directory.fileName.toString()
        val rawPath = directory.resolve("$baseName.cupraw")
        val csvPath = directory.resolve("$baseName.csv")
        val metadataPath = directory.resolve("$baseName.session.json")
        val findings = ArrayList<CaptureInspectionFinding>()

        // Decode metadata first because the NUS transport now supports two
        // same-length but structurally different wire profiles.
        val metadata = if (Files.exists(metadataPath)) {
            try {
                CaptureSessionMetadataCodec.decode(metadataPath)
            } catch (error: Exception) {
                findings += finding("metadata-unreadable", CaptureInspectionSeverity.ERROR,
                    "metadata cannot be parsed: ${error.message ?: error::class.simpleName}")
                null
            }
        } else {
            findings += finding("metadata-missing", CaptureInspectionSeverity.ERROR,
                "missing session metadata")
            null
        }
        val protocolMode = CupStreamProtocolMode.fromProtocolProfileIdentifier(
            metadata?.protocolProfile,
        )

        val replay = if (Files.exists(rawPath)) {
            try {
                CupRawReplayEngine.replay(rawPath, protocolMode).also {
                    appendReplayFindings(it, findings)
                }
            } catch (error: Exception) {
                findings += finding("raw-unreadable", CaptureInspectionSeverity.ERROR,
                    "raw cannot be parsed: ${error.message ?: error::class.simpleName}")
                null
            }
        } else {
            findings += finding("raw-missing", CaptureInspectionSeverity.ERROR,
                "missing .cupraw file")
            null
        }

        val csv = if (Files.exists(csvPath)) {
            try {
                scanCsv(csvPath).also {
                    appendCsvFindings(it, findings)
                }
            } catch (error: Exception) {
                findings += finding("csv-unreadable", CaptureInspectionSeverity.ERROR,
                    "CSV cannot be read: ${error.message ?: error::class.simpleName}")
                null
            }
        } else {
            findings += finding("csv-missing", CaptureInspectionSeverity.ERROR,
                "missing .csv file")
            null
        }

        if (metadata != null) {
            if (!metadata.complete) {
                findings += finding("metadata-incomplete", CaptureInspectionSeverity.WARNING,
                    "metadata is marked incomplete")
            }
            if (replay != null && replay.rawRecordCount != metadata.rawChunkCount) {
                findings += finding("raw-chunk-count", CaptureInspectionSeverity.ERROR,
                    "raw chunks ${replay.rawRecordCount} do not match metadata ${metadata.rawChunkCount}")
            }
            if (replay != null && replay.acceptedSamples != metadata.sampleCount) {
                findings += finding("raw-sample-count", CaptureInspectionSeverity.ERROR,
                    "replayed samples ${replay.acceptedSamples} do not match metadata ${metadata.sampleCount}")
            }
            if (csv != null && csv.completeDataRowCount != metadata.sampleCount) {
                findings += finding("csv-sample-count", CaptureInspectionSeverity.ERROR,
                    "CSV rows ${csv.completeDataRowCount} do not match metadata ${metadata.sampleCount}")
            }
        }
        if (replay != null && csv != null && replay.acceptedSamples != csv.completeDataRowCount) {
            findings += finding("raw-csv-sample-count", CaptureInspectionSeverity.ERROR,
                "replayed samples ${replay.acceptedSamples} do not match CSV rows ${csv.completeDataRowCount}")
        }

        return CaptureSessionInspection(replay, csv, metadata, findings)
    }

    fun scanCsv(path: Path): CaptureCsvScanReport {
        val expectedHeader = CaptureCsvSchema.header.toByteArray(Charsets.UTF_8)
        Files.newInputStream(path).use { input ->
            val observedHeader = ByteArray(expectedHeader.size)
            var observedHeaderBytes = 0
            var totalBytes = 0L
            var newlineCount = 0L
            var lastNewlineEnd = 0L
            var lastByte: Int? = null
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                val headerBytesToCopy = minOf(read, expectedHeader.size - observedHeaderBytes)
                if (headerBytesToCopy > 0) {
                    buffer.copyInto(observedHeader, observedHeaderBytes, 0, headerBytesToCopy)
                    observedHeaderBytes += headerBytesToCopy
                }
                for (index in 0 until read) {
                    if (buffer[index].toInt() == '\n'.code) {
                        newlineCount += 1
                        lastNewlineEnd = totalBytes + index + 1
                    }
                }
                totalBytes += read
                lastByte = buffer[read - 1].toInt() and 0xFF
            }
            val truncated = totalBytes > 0 && lastByte != '\n'.code
            val validBytes = if (truncated) lastNewlineEnd else totalBytes
            return CaptureCsvScanReport(
                totalBytes = totalBytes,
                validByteCount = validBytes,
                completeDataRowCount = maxOf(0L, newlineCount - 1),
                hasExpectedHeader = observedHeaderBytes == expectedHeader.size &&
                    observedHeader.contentEquals(expectedHeader),
                hasTruncatedFinalLine = truncated,
            )
        }
    }

    private fun appendReplayFindings(
        replay: CupRawReplayReport,
        findings: MutableList<CaptureInspectionFinding>,
    ) {
        if (replay.tailIssue != null) {
            findings += finding("raw-tail", CaptureInspectionSeverity.WARNING,
                "raw has a recoverable tail; safe prefix is ${replay.validRawBytes} bytes")
        }
        if (replay.leadingAlignmentBytes > 0) {
            findings += finding(
                "raw-alignment-prefix",
                CaptureInspectionSeverity.WARNING,
                "录制从通知帧中途开始；重放已跳过 ${replay.leadingAlignmentBytes} 个前导字节并成功对齐，源文件未修改",
            )
        }
        if (replay.pendingDecoderBytes > 0) {
            findings += finding(
                "raw-frame-suffix",
                CaptureInspectionSeverity.WARNING,
                "录制结束时下一协议帧尚未收满，保留 ${replay.pendingDecoderBytes} 个尾部字节；" +
                    "完整 raw record 与已解码样本不受影响，源文件未修改",
            )
        }
        if (!replay.isStructurallyClean && replay.tailIssue == null) {
            findings += finding("raw-structure", CaptureInspectionSeverity.ERROR,
                "raw 重放存在结构错误：无效帧=${replay.structurallyInvalidFrames}，" +
                    "对齐后丢弃字节=${replay.structuralDiscardedBytes}")
        }
        if (replay.missingFrames > 0 || replay.duplicateFrames > 0 || replay.outOfOrderFrames > 0) {
            findings += finding("raw-sequence", CaptureInspectionSeverity.WARNING,
                "sequence anomalies: missing=${replay.missingFrames}, duplicate=${replay.duplicateFrames}, out_of_order=${replay.outOfOrderFrames}")
        }
    }

    private fun appendCsvFindings(
        csv: CaptureCsvScanReport,
        findings: MutableList<CaptureInspectionFinding>,
    ) {
        if (!csv.hasExpectedHeader) {
            findings += finding("csv-header", CaptureInspectionSeverity.ERROR,
                "CSV header does not match the supported schema")
        }
        if (csv.hasTruncatedFinalLine) {
            findings += finding("csv-tail", CaptureInspectionSeverity.WARNING,
                "CSV final line is not newline-terminated; safe prefix is ${csv.validByteCount} bytes")
        }
    }

    private fun finding(
        id: String,
        severity: CaptureInspectionSeverity,
        message: String,
    ) = CaptureInspectionFinding(id, severity, message)
}
