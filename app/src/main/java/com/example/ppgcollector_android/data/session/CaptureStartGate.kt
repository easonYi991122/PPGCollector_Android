package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.ble.BleConnectionPhase
import com.example.ppgcollector_android.core.signal.StreamFreshness
import java.nio.file.Path

sealed interface CaptureStartFailure {
    data object AlreadyRecording : CaptureStartFailure
    data object InvalidSessionName : CaptureStartFailure
    data object StreamNotFresh : CaptureStartFailure
    data object DeviceNotReady : CaptureStartFailure
    data object ForegroundServiceStartRejected : CaptureStartFailure
    data object NotificationPermissionDenied : CaptureStartFailure
    data object SessionAlreadyExists : CaptureStartFailure
    data object LogicalSessionAlreadyExists : CaptureStartFailure
    data class ParticipantIncomplete(val fields: List<String>) : CaptureStartFailure
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
    val participant: CaptureParticipantDraft? = null,
)

object CaptureStartGate {
    fun validateAll(context: CaptureStartContext): List<CaptureStartFailure> = buildList {
        if (context.isRecording) add(CaptureStartFailure.AlreadyRecording)
        if (!CaptureSessionWriterPolicy.isValidBaseName(context.sessionName)) {
            add(CaptureStartFailure.InvalidSessionName)
        } else {
            if (SessionNamePolicy.isDuplicate(context.sessionName, context.sessionsRoot)) {
                add(CaptureStartFailure.SessionAlreadyExists)
            }
            if (SessionNamePolicy.isLogicalDuplicate(context.sessionName, context.sessionsRoot)) {
                add(CaptureStartFailure.LogicalSessionAlreadyExists)
            }
        }
        if (context.freshness != StreamFreshness.FRESH) add(CaptureStartFailure.StreamNotFresh)
        if (context.phase !is BleConnectionPhase.Subscribed &&
            context.phase !is BleConnectionPhase.Receiving
        ) add(CaptureStartFailure.DeviceNotReady)
        context.participant?.validationErrors()
            ?.filterNot { it.startsWith("血压") }
            ?.takeIf { it.isNotEmpty() }
            ?.let { add(CaptureStartFailure.ParticipantIncomplete(it)) }
        if (context.availableBytes != null &&
            context.availableBytes < CaptureSessionWriterPolicy.minimumAvailableCapacityBytes
        ) add(CaptureStartFailure.InsufficientStorage)
    }

    fun validate(context: CaptureStartContext): CaptureStartFailure? {
        if (context.isRecording) return CaptureStartFailure.AlreadyRecording
        if (!CaptureSessionWriterPolicy.isValidBaseName(context.sessionName)) {
            return CaptureStartFailure.InvalidSessionName
        }
        if (context.freshness != StreamFreshness.FRESH) {
            return CaptureStartFailure.StreamNotFresh
        }
        if (context.phase !is BleConnectionPhase.Subscribed &&
            context.phase !is BleConnectionPhase.Receiving
        ) {
            return CaptureStartFailure.DeviceNotReady
        }
        if (SessionNamePolicy.isDuplicate(context.sessionName, context.sessionsRoot)) {
            return CaptureStartFailure.SessionAlreadyExists
        }
        if (SessionNamePolicy.isLogicalDuplicate(context.sessionName, context.sessionsRoot)) {
            return CaptureStartFailure.LogicalSessionAlreadyExists
        }
        context.participant?.validationErrors()
            ?.filterNot { it.startsWith("血压") }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return CaptureStartFailure.ParticipantIncomplete(it) }
        if (context.availableBytes != null &&
            context.availableBytes < CaptureSessionWriterPolicy.minimumAvailableCapacityBytes
        ) {
            return CaptureStartFailure.InsufficientStorage
        }
        return null
    }
}
