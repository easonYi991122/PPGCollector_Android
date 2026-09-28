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
    val validationError: String? = null,
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
    val metrics: CaptureSidecarScanReport? = null,
    val bloodPressure: CaptureSidecarScanReport? = null,
    val ecg: CaptureSidecarScanReport? = null,
) {
    val isVerifiedConsistent: Boolean
        get() = findings.isEmpty() &&
            replay?.isStructurallyClean == true &&
            csv?.hasExpectedHeader == true &&
            csv.hasTruncatedFinalLine == false && csv.validationError == null &&
            (metrics == null || (metrics.isStructurallyValid && !metrics.hasTruncatedFinalLine)) &&
            (bloodPressure == null ||
                (bloodPressure.isStructurallyValid && !bloodPressure.hasTruncatedFinalLine)) &&
            (ecg == null || (ecg.isStructurallyValid && !ecg.hasTruncatedFinalLine))

    val hasRecoverableTail: Boolean
        get() = replay?.tailIssue != null ||
            csv?.hasTruncatedFinalLine == true ||
            metrics?.hasTruncatedFinalLine == true ||
            bloodPressure?.hasTruncatedFinalLine == true ||
            ecg?.hasTruncatedFinalLine == true
}

object CaptureSessionInspectionService {
    fun inspect(
        directory: Path,
        cancellationCheck: () -> Unit = {},
        accessRegistry: CaptureSessionAccessRegistry = CaptureSessionAccessRegistry.app,
    ): CaptureSessionInspection = requireSessionLease(directory, CaptureSessionAccessRegistry.Access.READ_SNAPSHOT, accessRegistry).use {
        inspectUnderLease(directory, cancellationCheck)
    }

    internal fun inspectUnderLease(directory: Path, cancellationCheck: () -> Unit): CaptureSessionInspection {
        cancellationCheck()
        val baseName = directory.fileName.toString()
        val files = CaptureSessionRepository.expectedFiles(directory, cancellationCheck)
        val rawPath = files.raw
        val csvPath = files.csv
        val metadataPath = files.metadata
        val findings = ArrayList<CaptureInspectionFinding>()

        // Decode metadata first because the NUS transport now supports two
        // same-length but structurally different wire profiles.
        val metadata = if (Files.exists(metadataPath)) {
            try {
                CaptureSessionMetadataCodec.decode(metadataPath, cancellationCheck)
            } catch (error: Exception) {
                if (error is java.util.concurrent.CancellationException) throw error
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
                CupRawReplayEngine.replay(rawPath, protocolMode, cancellationCheck) {}.also {
                    appendReplayFindings(it, findings)
                }
            } catch (error: Exception) {
                if (error is java.util.concurrent.CancellationException) throw error
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
                scanCsv(
                    csvPath, metadata.allowedRowSessionIds(),
                    rawPath.takeIf(Files::isRegularFile), protocolMode, cancellationCheck,
                ).also {
                    appendCsvFindings(it, findings)
                }
            } catch (error: Exception) {
                if (error is java.util.concurrent.CancellationException) throw error
                findings += finding("csv-unreadable", CaptureInspectionSeverity.ERROR,
                    "CSV cannot be read: ${error.message ?: error::class.simpleName}")
                null
            }
        } else {
            findings += finding("csv-missing", CaptureInspectionSeverity.ERROR,
                "missing .csv file")
            null
        }

        val metrics = scanOptionalSidecar(
            files.metrics,
            CaptureMetricSeries.header,
            findings,
            "metrics",
            cancellationCheck = cancellationCheck,
            acceptedSessionIds = metadata.allowedRowSessionIds(),
        )
        val bloodPressure = scanOptionalSidecar(
            files.bloodPressure,
            CaptureBloodPressureSeries.header,
            findings,
            "blood-pressure",
            cancellationCheck = cancellationCheck,
            acceptedSessionIds = metadata.allowedRowSessionIds(),
        )
        val ecg = scanOptionalSidecar(
            files.ecg,
            CaptureEcgCsv.header,
            findings,
            "ecg",
            cancellationCheck = cancellationCheck,
            acceptedSessionIds = metadata.allowedRowSessionIds(),
        )

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
            if (metrics != null && metrics.completeDataRowCount != metadata.writer.metricsRows) {
                findings += finding(
                    "metrics-row-count",
                    CaptureInspectionSeverity.ERROR,
                    "metrics rows ${metrics.completeDataRowCount} do not match metadata ${metadata.writer.metricsRows}",
                )
            }
            if (bloodPressure != null &&
                bloodPressure.completeDataRowCount != metadata.writer.bloodPressureRows
            ) {
                findings += finding(
                    "blood-pressure-row-count",
                    CaptureInspectionSeverity.ERROR,
                    "blood-pressure rows ${bloodPressure.completeDataRowCount} do not match metadata ${metadata.writer.bloodPressureRows}",
                )
            }
            if (ecg != null) {
                val allowedSidecarSessionIds = setOfNotNull(
                    metadata.sessionId,
                    metadata.recovery?.sourceSessionId,
                )
                if (ecg.sessionId != null && ecg.sessionId !in allowedSidecarSessionIds) {
                    findings += finding(
                        "ecg-session-id",
                        CaptureInspectionSeverity.ERROR,
                        "ECG session_id does not match metadata",
                    )
                }
                val ecgRate = metadata.ecgSampleRateHz
                val expectedRows = if (metadata.sampleRateHz > 0 &&
                    ecgRate != null && ecgRate % metadata.sampleRateHz == 0
                ) {
                    metadata.sampleCount * (ecgRate / metadata.sampleRateHz)
                } else null
                if (expectedRows != null && ecg.completeDataRowCount != expectedRows) {
                    findings += finding(
                        "ecg-row-count",
                        CaptureInspectionSeverity.ERROR,
                        "ECG rows ${ecg.completeDataRowCount} do not match metadata-derived $expectedRows",
                    )
                }
            } else if (protocolMode == CupStreamProtocolMode.ADS1292R_120) {
                findings += finding(
                    "ecg-missing",
                    CaptureInspectionSeverity.ERROR,
                    "ADS1292R session is missing its ECG sidecar",
                )
            }
        }
        if (replay != null && csv != null && replay.acceptedSamples != csv.completeDataRowCount) {
            findings += finding("raw-csv-sample-count", CaptureInspectionSeverity.ERROR,
                "replayed samples ${replay.acceptedSamples} do not match CSV rows ${csv.completeDataRowCount}")
        }

        return CaptureSessionInspection(replay, csv, metadata, findings, metrics, bloodPressure, ecg)
    }

    fun scanCsv(
        path: Path,
        acceptedSessionIds: Set<String> = emptySet(),
        rawPath: Path? = null,
        protocolMode: CupStreamProtocolMode = CupStreamProtocolMode.BATCH_COMPATIBLE,
        cancellationCheck: () -> Unit = {},
    ): CaptureCsvScanReport = CaptureCsvRowValidator.scan(
        path, acceptedSessionIds, rawPath, protocolMode, cancellationCheck,
    )

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
        csv.validationError?.let {
            findings += finding("csv-row", CaptureInspectionSeverity.ERROR, it)
        }
        if (!csv.hasExpectedHeader) {
            findings += finding("csv-header", CaptureInspectionSeverity.ERROR,
                "CSV header does not match the supported schema")
        }
        if (csv.hasTruncatedFinalLine) {
            findings += finding("csv-tail", CaptureInspectionSeverity.WARNING,
                "CSV final line is not newline-terminated; safe prefix is ${csv.validByteCount} bytes")
        }
    }

    private fun scanOptionalSidecar(
        path: Path?,
        expectedHeader: String,
        findings: MutableList<CaptureInspectionFinding>,
        label: String,
        cancellationCheck: () -> Unit,
        acceptedSessionIds: Set<String>,
    ): CaptureSidecarScanReport? {
        if (path == null) return null
        if (!Files.isRegularFile(path)) {
            findings += finding(
                "$label-missing",
                CaptureInspectionSeverity.ERROR,
                "metadata declares missing $label sidecar",
            )
            return null
        }
        return try {
            val report = when (label) {
                "metrics" -> CaptureMetricSeries.scan(path, acceptedSessionIds, cancellationCheck)
                "ecg" -> CaptureEcgCsv.scan(path, acceptedSessionIds, cancellationCheck)
                else -> CaptureBloodPressureSeries.scan(path, acceptedSessionIds, cancellationCheck)
            }
            report.also {
                if (!report.hasExpectedHeader) {
                    findings += finding(
                        "$label-header",
                        CaptureInspectionSeverity.ERROR,
                        "$label sidecar header does not match the supported schema",
                    )
                }
                if (report.hasTruncatedFinalLine) {
                    findings += finding(
                        "$label-tail",
                        CaptureInspectionSeverity.WARNING,
                        "$label sidecar final line is not newline-terminated",
                    )
                }
                report.monotonicityError?.let {
                    findings += finding("$label-structure", CaptureInspectionSeverity.ERROR, it)
                }
            }
        } catch (error: Exception) {
                if (error is java.util.concurrent.CancellationException) throw error
            findings += finding(
                "$label-unreadable",
                CaptureInspectionSeverity.ERROR,
                "$label sidecar cannot be read: ${error.message ?: error::class.simpleName}",
            )
            null
        }
    }

    private fun finding(
        id: String,
        severity: CaptureInspectionSeverity,
        message: String,
    ) = CaptureInspectionFinding(id, severity, message)
}
