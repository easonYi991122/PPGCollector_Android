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
    val sessionNameIsDuplicate: Boolean? = null,
    val sessionNameIsLogicalDuplicate: Boolean? = null,
)

object CaptureStartGate {
    fun validateAll(context: CaptureStartContext): List<CaptureStartFailure> = buildList {
        if (context.isRecording) add(CaptureStartFailure.AlreadyRecording)
        if (!CaptureSessionWriterPolicy.isValidBaseName(context.sessionName)) {
            add(CaptureStartFailure.InvalidSessionName)
        } else {
            val duplicate = context.sessionNameIsDuplicate
                ?: SessionNamePolicy.isDuplicate(context.sessionName, context.sessionsRoot)
            if (duplicate) {
                add(CaptureStartFailure.SessionAlreadyExists)
            }
            val logicalDuplicate = context.sessionNameIsLogicalDuplicate
                ?: SessionNamePolicy.isLogicalDuplicate(context.sessionName, context.sessionsRoot)
            if (logicalDuplicate) {
                add(CaptureStartFailure.LogicalSessionAlreadyExists)
            }
        }
        if (context.freshness != StreamFreshness.FRESH) add(CaptureStartFailure.StreamNotFresh)
        if (context.phase !is BleConnectionPhase.Subscribed &&
            context.phase !is BleConnectionPhase.Receiving
        ) add(CaptureStartFailure.DeviceNotReady)
        context.participant?.blockingValidationErrors()
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
        if (context.sessionNameIsDuplicate
            ?: SessionNamePolicy.isDuplicate(context.sessionName, context.sessionsRoot)
        ) {
            return CaptureStartFailure.SessionAlreadyExists
        }
        if (context.sessionNameIsLogicalDuplicate
            ?: SessionNamePolicy.isLogicalDuplicate(context.sessionName, context.sessionsRoot)
        ) {
            return CaptureStartFailure.LogicalSessionAlreadyExists
        }
        context.participant?.blockingValidationErrors()
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

internal data class CaptureGateDiskSnapshot(
    val duplicate: Boolean,
    val logicalDuplicate: Boolean,
    val availableBytes: Long?,
)

internal class CaptureGateDiskCache(
    private val sessionsRoot: () -> Path,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val minIntervalMs: Long = 1_000L,
) {
    var diskWalkCount: Int = 0
        private set
    private var lastName: String? = null
    private var lastAtMs: Long = Long.MIN_VALUE / 4
    private var lastSnapshot = CaptureGateDiskSnapshot(false, false, null)

    fun invalidate() {
        lastAtMs = Long.MIN_VALUE / 4
    }

    fun snapshot(name: String): CaptureGateDiskSnapshot {
        val now = nowMs()
        if (name != lastName || now - lastAtMs >= minIntervalMs) {
            val root = sessionsRoot()
            diskWalkCount++
            val capacityRoot = root.parent ?: root
            lastSnapshot = CaptureGateDiskSnapshot(
                duplicate = SessionNamePolicy.isDuplicate(name, root),
                logicalDuplicate = SessionNamePolicy.isLogicalDuplicate(name, root),
                availableBytes = runCatching { Files.getFileStore(capacityRoot).usableSpace }.getOrNull(),
            )
            lastName = name
            lastAtMs = now
        }
        return lastSnapshot
    }
}
