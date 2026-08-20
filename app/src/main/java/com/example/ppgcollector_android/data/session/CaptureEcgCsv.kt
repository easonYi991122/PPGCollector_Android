package com.example.ppgcollector_android.data.session

import java.nio.file.Path
import java.util.Locale

object CaptureEcgCsv {
    const val schemaVersion = "ppgcollector_ecg_v1"
    const val sampleRateHz = 500
    val header = "schema_version,session_id,sample_index,device_time_s,frame_seq_no,ecg\n"

    fun format(
        sessionId: String,
        sampleIndex: Long,
        frameSequence: UInt,
        ecg: UInt,
    ): String = listOf(
        schemaVersion,
        sessionId,
        sampleIndex.toString(),
        String.format(Locale.ROOT, "%.6f", sampleIndex.toDouble() / sampleRateHz),
        frameSequence.toString(),
        ecg.toString(),
    ).joinToString(",") + "\n"

    fun scan(path: Path): CaptureSidecarScanReport =
        scanSessionSidecar(path, header) { fields, previous ->
            if (fields.size != 6) return@scanSessionSidecar "expected 6 ECG fields"
            if (fields[0] != schemaVersion) return@scanSessionSidecar "unsupported ECG schema_version"
            if (fields[1].isBlank()) return@scanSessionSidecar "session_id is blank"
            val sampleIndex = fields[2].toLongOrNull()
                ?: return@scanSessionSidecar "invalid ECG sample_index"
            if (sampleIndex < 0L) return@scanSessionSidecar "ECG sample_index is negative"
            val deviceTime = fields[3].toDoubleOrNull()
                ?: return@scanSessionSidecar "invalid ECG device_time_s"
            if (!deviceTime.isFinite()) return@scanSessionSidecar "non-finite ECG device_time_s"
            val expectedTime = sampleIndex.toDouble() / sampleRateHz.toDouble()
            if (kotlin.math.abs(deviceTime - expectedTime) > 0.000001) {
                return@scanSessionSidecar "ECG device_time_s does not match sample_index"
            }
            fields[4].toUIntOrNull() ?: return@scanSessionSidecar "invalid ECG frame_seq_no"
            fields[5].toUIntOrNull() ?: return@scanSessionSidecar "invalid ECG value"
            if (previous != null) {
                val previousIndex = previous[2].toLongOrNull() ?: -1L
                if (sampleIndex <= previousIndex) {
                    return@scanSessionSidecar "ECG sample_index is not increasing"
                }
                val previousTime = previous[3].toDoubleOrNull() ?: Double.NEGATIVE_INFINITY
                if (deviceTime <= previousTime) {
                    return@scanSessionSidecar "ECG device_time_s is not increasing"
                }
                if (fields[1] != previous[1]) {
                    return@scanSessionSidecar "session_id changed within ECG sidecar"
                }
            }
            null
        }
}
