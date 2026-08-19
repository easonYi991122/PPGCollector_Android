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

internal val liveMetricCompactOrder = listOf("HR", "RR", "PI", "SQI", "BP")
