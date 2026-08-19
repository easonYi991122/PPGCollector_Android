package com.example.ppgcollector_android.data.session

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
}
