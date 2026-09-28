package com.example.ppgcollector_android

internal enum class CaptureContentDensity {
    COMPACT,
    DETAILED,
}

internal enum class CaptureUiEvent {
    RECORDING_REQUESTED,
    RECORDING_STOPPED,
    TOGGLE_DENSITY,
}

/** Reducer keeps recording-density transitions event-driven and testable. */
internal object CaptureUiPolicy {
    fun needsNotificationPermission(gate: CaptureGateUiState): Boolean =
        com.example.ppgcollector_android.data.session.CaptureStartFailure.NotificationPermissionDenied in gate.failures

    fun recordingTransitionMessage(state: com.example.ppgcollector_android.data.session.CaptureRecordingState): String? =
        when (state) {
            com.example.ppgcollector_android.data.session.CaptureRecordingState.STARTING -> "正在准备录制…"
            com.example.ppgcollector_android.data.session.CaptureRecordingState.STOPPING -> "上一会话仍在收尾"
            else -> null
        }

    fun compactMetricColumns(widthDp: Float, fontScale: Float): Int {
        // Text area >= 54 scaled dp plus the existing 12 dp tile padding.
        val minimumTile = 54f * fontScale.coerceAtLeast(1f) + 12f
        return listOf(5, 3, 2, 1).first { columns ->
            (widthDp - (columns - 1) * 5f) / columns >= minimumTile || columns == 1
        }
    }

    fun reduce(current: CaptureContentDensity, event: CaptureUiEvent): CaptureContentDensity =
        when (event) {
            CaptureUiEvent.RECORDING_REQUESTED -> CaptureContentDensity.COMPACT
            CaptureUiEvent.RECORDING_STOPPED -> CaptureContentDensity.DETAILED
            CaptureUiEvent.TOGGLE_DENSITY -> when (current) {
                CaptureContentDensity.COMPACT -> CaptureContentDensity.DETAILED
                CaptureContentDensity.DETAILED -> CaptureContentDensity.COMPACT
            }
        }
}

internal enum class LiveWaveformDisplayMode(val compactLabel: String) {
    RAW("RAW"),
    CAUSAL("CAUSAL"),
    FIXED_LAG("FIXED"),
}

internal data class LiveWaveformModeResolution(
    val requested: LiveWaveformDisplayMode,
    val effective: LiveWaveformDisplayMode,
    val isWarming: Boolean,
)

/** Keep the user's mode selection stable while a delayed filter is warming. */
internal fun resolveLiveWaveformMode(
    requested: LiveWaveformDisplayMode,
    causalAvailable: Boolean,
    fixedLagAvailable: Boolean,
): LiveWaveformModeResolution {
    val effective = when (requested) {
        LiveWaveformDisplayMode.FIXED_LAG -> when {
            fixedLagAvailable -> LiveWaveformDisplayMode.FIXED_LAG
            causalAvailable -> LiveWaveformDisplayMode.CAUSAL
            else -> LiveWaveformDisplayMode.RAW
        }
        LiveWaveformDisplayMode.CAUSAL -> if (causalAvailable) {
            LiveWaveformDisplayMode.CAUSAL
        } else {
            LiveWaveformDisplayMode.RAW
        }
        LiveWaveformDisplayMode.RAW -> LiveWaveformDisplayMode.RAW
    }
    return LiveWaveformModeResolution(
        requested = requested,
        effective = effective,
        isWarming = requested == LiveWaveformDisplayMode.FIXED_LAG && !fixedLagAvailable,
    )
}

internal fun shouldComposeLiveSignalDetails(redSampleCount: Int, irSampleCount: Int): Boolean =
    redSampleCount > 0 && irSampleCount > 0

internal val liveMetricCompactOrder = listOf("HR", "RR", "PI", "SQI", "BP")
