package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.protocol.CupStreamProtocolMode
import java.nio.file.Files
import java.nio.file.Path

/** One physical row and one replayed sample at a time; never retains a session. */
internal object CaptureCsvRowValidator {
    fun scan(
        path: Path,
        acceptedSessionIds: Set<String> = emptySet(),
        rawPath: Path? = null,
        protocolMode: CupStreamProtocolMode = CupStreamProtocolMode.BATCH_COMPATIBLE,
        cancellationCheck: () -> Unit = {},
    ): CaptureCsvScanReport {
        val totalBytes = Files.size(path)
        var validBytes = 0L
        var rows = 0L
        var error: String? = null
        var header = false
        var truncated = false
        var rowSessionId: String? = null
        SessionCsvLineReader(path, cancellationCheck).use { reader ->
            fun consume(expected: CupReplaySample?): Boolean {
                if (error != null) return false
                try {
                    val line = reader.next() ?: return false
                    val row = CaptureCsvParser.parseRow(line)
                    require(row.sessionId.isNotBlank()) { "session_id is blank" }
                    require(acceptedSessionIds.isEmpty() || row.sessionId in acceptedSessionIds) {
                        "session_id does not match metadata"
                    }
                    require(rowSessionId == null || row.sessionId == rowSessionId) { "session_id changed" }
                    require(row.sampleIndex == rows) { "sample_index is not zero-based consecutive" }
                    val samplesPerFrame = expected?.samplesPerFrame ?: when (row.protocolProfile) {
                        com.example.ppgcollector_android.core.protocol.CupBatchProtocolV1.legacyProfileIdentifier -> 50
                        com.example.ppgcollector_android.core.protocol.Ads1292rPacketProtocol.profileIdentifier -> 4
                        else -> 20
                    }
                    require(row.sampleInFrame in 0 until samplesPerFrame) { "invalid sample_in_frame" }
                    if (expected != null) {
                        require(row.sampleIndex == expected.sampleIndex &&
                            row.sampleInFrame == expected.sampleInFrame &&
                            row.frameSequence == expected.frameSequence &&
                            row.hostFrameTimeNanoseconds == expected.hostMonotonicNanoseconds &&
                            row.red == expected.sample.red && row.ir == expected.sample.ir) {
                            "CSV sample differs from raw at source cursor ${expected.sampleIndex}"
                        }
                    }
                    rows++
                    rowSessionId = row.sessionId
                    validBytes = reader.consumedBytes
                    return true
                } catch (invalid: IllegalArgumentException) {
                    error = invalid.message ?: "invalid CSV row"
                    return false
                }
            }
            try {
                header = reader.next() == CaptureCsvSchema.header.trimEnd('\n')
                if (!header) error = "unexpected CSV header" else validBytes = reader.consumedBytes
                if (header && rawPath != null) {
                    CupRawReplayEngine.replay(rawPath, protocolMode, cancellationCheck) { sample ->
                        consume(sample)
                    }
                    if (error == null && reader.next() != null) error = "CSV contains samples absent from raw"
                } else if (header) {
                    while (consume(null)) Unit
                }
            } catch (invalid: IllegalArgumentException) {
                error = invalid.message ?: "invalid CSV row"
            }
            truncated = reader.truncated
        }
        return CaptureCsvScanReport(totalBytes, validBytes, rows, header, truncated, error)
    }
}
