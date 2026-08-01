package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.ble.BleConnectionPhase
import com.example.ppgcollector_android.core.signal.StreamFreshness
import java.nio.file.Files
import java.nio.file.Path

sealed interface CaptureStartFailure {
    data object AlreadyRecording : CaptureStartFailure
    data object InvalidSessionName : CaptureStartFailure
    data object StreamNotFresh : CaptureStartFailure
    data object DeviceNotReady : CaptureStartFailure
    data object ForegroundServiceStartRejected : CaptureStartFailure
    data object NotificationPermissionDenied : CaptureStartFailure
    data object SessionAlreadyExists : CaptureStartFailure
    data object InsufficientStorage : CaptureStartFailure
}

object CaptureNotificationPermissionPolicy {
    fun isRuntimePermissionRequired(apiLevel: Int): Boolean = apiLevel >= 33

    fun failureFor(apiLevel: Int, granted: Boolean): CaptureStartFailure? =
        if (isRuntimePermissionRequired(apiLevel) && !granted) {
            CaptureStartFailure.NotificationPermissionDenied
        } else {
            null
        }
}

data class CaptureStartContext(
    val isRecording: Boolean,
    val phase: BleConnectionPhase,
    val freshness: StreamFreshness,
    val sessionsRoot: Path,
    val sessionName: String,
    val availableBytes: Long?,
)

object CaptureStartGate {
    fun validate(context: CaptureStartContext): CaptureStartFailure? {
        if (context.isRecording) return CaptureStartFailure.AlreadyRecording
        if (!CaptureSessionWriterPolicy.isValidBaseName(context.sessionName)) {
            return CaptureStartFailure.InvalidSessionName
        }
        if (context.freshness != StreamFreshness.FRESH) return CaptureStartFailure.StreamNotFresh
        if (context.phase !is BleConnectionPhase.Subscribed &&
            context.phase !is BleConnectionPhase.Receiving
        ) return CaptureStartFailure.DeviceNotReady
        if (Files.exists(context.sessionsRoot.resolve(context.sessionName))) {
            return CaptureStartFailure.SessionAlreadyExists
        }
        if (context.availableBytes != null &&
            context.availableBytes < CaptureSessionWriterPolicy.minimumAvailableCapacityBytes
        ) return CaptureStartFailure.InsufficientStorage
        return null
    }
}
