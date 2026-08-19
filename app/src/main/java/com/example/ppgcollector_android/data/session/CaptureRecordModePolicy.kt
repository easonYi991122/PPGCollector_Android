package com.example.ppgcollector_android.data.session

enum class CaptureRecordMode {
    TIMED,
    MANUAL,
}

object CaptureRecordModePolicy {
    const val defaultDurationSeconds = 60
    const val minimumDurationSeconds = 10
    const val maximumDurationSeconds = 3600

    fun durationError(mode: CaptureRecordMode, text: String): String? {
        if (mode == CaptureRecordMode.MANUAL) return null
        val value = text.toIntOrNull()
        return when {
            text.isBlank() -> "请输入定时录制时长（10–3600 秒）"
            value == null -> "录制时长必须是秒数"
            value !in minimumDurationSeconds..maximumDurationSeconds ->
                "录制时长范围：$minimumDurationSeconds–$maximumDurationSeconds 秒"
            else -> null
        }
    }

    fun effectiveDurationSeconds(mode: CaptureRecordMode, text: String): Int? =
        if (mode == CaptureRecordMode.MANUAL) {
            null
        } else {
            text.toIntOrNull()?.takeIf {
                it in minimumDurationSeconds..maximumDurationSeconds
            } ?: defaultDurationSeconds
        }

    fun remainingSeconds(
        mode: CaptureRecordMode,
        plannedDurationSeconds: Int?,
        acceptedSampleCount: Long,
        sampleRateHz: Int,
    ): Int? {
        if (mode == CaptureRecordMode.MANUAL || plannedDurationSeconds == null) return null
        val acceptedSeconds = acceptedSampleCount / sampleRateHz.toDouble()
        return (plannedDurationSeconds - acceptedSeconds).coerceAtLeast(0.0).toInt()
    }
}
