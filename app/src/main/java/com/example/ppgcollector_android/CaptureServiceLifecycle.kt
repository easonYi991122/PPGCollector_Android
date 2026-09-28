package com.example.ppgcollector_android

import com.example.ppgcollector_android.core.ble.BleConnectionPhase
import com.example.ppgcollector_android.core.ble.BleCoordinatorSnapshot
import com.example.ppgcollector_android.core.signal.StreamFreshness
import com.example.ppgcollector_android.data.session.CaptureParticipantSnapshot
import com.example.ppgcollector_android.data.session.CaptureRecordingController
import com.example.ppgcollector_android.data.session.CaptureRecordingSnapshot
import com.example.ppgcollector_android.data.session.CaptureRecordingStartResult
import com.example.ppgcollector_android.data.session.CaptureRecordingState
import com.example.ppgcollector_android.data.session.CaptureStartFailure
import com.example.ppgcollector_android.data.session.CaptureStopReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The service's production transaction lane. Entry points and effects run on the
 * owner dispatcher (Main in Android); only blocking initialization runs on IO.
 * Destroy requests a stop, but never cancels an accepted start or its finalizer.
 */
class CaptureServiceLifecycle(
    private val controller: CaptureRecordingController,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val claimRecordingOwner: (Any) -> Boolean,
    private val releaseRecordingOwner: (Any) -> Unit,
    private val attachSink: (Any) -> Unit,
    private val detachSink: (Any) -> Unit,
    private val removeForeground: () -> Unit,
    private val stopSelf: (Int) -> Unit,
    private val onFailure: (CaptureStartFailure) -> Unit,
    private val onTerminal: (CaptureRecordingSnapshot) -> Unit = {},
) {
    private class Transaction(val sessionId: String, var startId: Int) {
        val token = Any()
        var handoffComplete = false
        var sinkAttached = false
        var stopReason: CaptureStopReason? = null
    }

    private var active: Transaction? = null
    private var destroyed = false
    private val _state = MutableStateFlow(CaptureRecordingState.IDLE)
    val state: StateFlow<CaptureRecordingState> = _state
    private val _recording = MutableStateFlow(controller.snapshot)
    val recording: StateFlow<CaptureRecordingSnapshot> = _recording
    private val terminalObserver: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        controller.snapshotFlow.collect(::observe)
    }

    /** A duplicate command belongs to the existing transaction, including its startId. */
    fun acknowledgeStartId(startId: Int): Boolean {
        val transaction = active ?: return false
        transaction.startId = startId
        return true
    }

    fun start(
        startId: Int,
        sessionId: String,
        prepareForeground: () -> Unit,
        initialize: () -> CaptureRecordingStartResult,
        onAccepted: () -> Unit,
        baseName: String? = null,
    ): Boolean {
        if (destroyed || acknowledgeStartId(startId)) return false
        val transaction = Transaction(sessionId, startId)
        if (!claimRecordingOwner(transaction.token)) {
            _state.value = CaptureRecordingState.FAILED
            _recording.value = CaptureRecordingSnapshot(state = CaptureRecordingState.FAILED,
                sessionId = sessionId, baseName = baseName)
            onFailure(CaptureStartFailure.AlreadyRecording)
            stopSelf(startId)
            return false
        }
        active = transaction
        _state.value = CaptureRecordingState.STARTING
        _recording.value = CaptureRecordingSnapshot(state = CaptureRecordingState.STARTING,
            sessionId = sessionId, baseName = baseName)
        try {
            prepareForeground()
        } catch (error: Exception) {
            onFailure(mapCaptureServiceStartFailure(error))
            finish(transaction)
            return false
        }
        scope.launch {
            val result = withContext(ioDispatcher) {
                try { initialize() } catch (error: Exception) {
                    CaptureRecordingStartResult.Failed(error.message ?: "recording start failed")
                }
            }
            // This job is deliberately retained through destroy and double-start.
            if (result == CaptureRecordingStartResult.Started) {
                attachSink(transaction.token)
                transaction.sinkAttached = true
                transaction.handoffComplete = true
                transaction.stopReason?.let(controller::stop)
                if (!destroyed && transaction.stopReason == null) onAccepted()
                observe(controller.snapshot)
            } else {
                when (result) {
                    is CaptureRecordingStartResult.Rejected -> onFailure(result.failure)
                    is CaptureRecordingStartResult.Failed ->
                        onFailure(CaptureStartFailure.RecordingStartFailed(result.message))
                    else -> Unit
                }
                finish(transaction)
            }
        }
        return true
    }

    /** Profile I/O can outlive a recording; both handoff and writer recheck its identity. */
    fun persistParticipant(
        sessionId: String,
        save: () -> CaptureParticipantSnapshot?,
    ): Job? {
        val transaction = active?.takeIf { it.sessionId == sessionId } ?: return null
        return scope.launch(ioDispatcher) {
            val participant = try {
                save()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return@launch
            } ?: return@launch
            withContext(scope.coroutineContext.minusKey(Job)) {
                if (active === transaction) controller.updateParticipantProfile(sessionId, participant)
            }
        }
    }

    fun stop(reason: CaptureStopReason, startId: Int? = null): Boolean {
        val transaction = active ?: return false
        if (startId != null) transaction.startId = startId
        if (transaction.stopReason == null) transaction.stopReason = reason
        _state.value = CaptureRecordingState.STOPPING
        _recording.value = _recording.value.copy(state = CaptureRecordingState.STOPPING)
        controller.stop(reason)
        return true
    }

    fun destroy() {
        destroyed = true
        stop(CaptureStopReason.UNKNOWN)
        if (active == null) releaseOwner()
    }

    private fun observe(snapshot: CaptureRecordingSnapshot) {
        val transaction = active ?: return
        if (snapshot.sessionId != transaction.sessionId) return
        if (!transaction.handoffComplete) {
            if (snapshot.state == CaptureRecordingState.STARTING || snapshot.state == CaptureRecordingState.STOPPING) {
                _recording.value = snapshot.copy(state = if (transaction.stopReason != null)
                    CaptureRecordingState.STOPPING else snapshot.state)
            }
            return
        }
        _recording.value = snapshot
        _state.value = snapshot.state
        if (snapshot.state == CaptureRecordingState.FINALIZED || snapshot.state == CaptureRecordingState.FAILED) {
            finish(transaction)
        }
    }

    private fun finish(transaction: Transaction) {
        if (active !== transaction) return
        active = null
        val terminal = controller.snapshot
        if (terminal.sessionId == transaction.sessionId && terminal.state in setOf(
                CaptureRecordingState.FINALIZED, CaptureRecordingState.FAILED)) {
            _state.value = terminal.state
            _recording.value = terminal
            onTerminal(terminal)
        } else {
            _state.value = CaptureRecordingState.FAILED
            _recording.value = _recording.value.copy(state = CaptureRecordingState.FAILED)
        }
        if (transaction.sinkAttached) detachSink(transaction.token)
        removeForeground()
        stopSelf(transaction.startId)
        releaseRecordingOwner(transaction.token)
        if (destroyed) releaseOwner()
    }

    private fun releaseOwner() {
        terminalObserver.cancel()
        scope.cancel()
    }
}


/** Session-scoped monotonic timeout policy shared by the service and JVM tests. */
class CaptureStreamHealthMonitor(
    private val monotonicMillis: () -> Long,
    private val staleGraceMillis: Long = 5_000L,
) {
    private var session: Pair<String?, Long>? = null
    private var staleSince: Long? = null

    fun stopReason(
        recording: CaptureRecordingSnapshot,
        ble: BleCoordinatorSnapshot,
    ): CaptureStopReason? {
        val token = recording.sessionId to recording.sessionToken
        if (session != token || recording.state != CaptureRecordingState.RECORDING) {
            session = token
            staleSince = null
        }
        if (recording.state != CaptureRecordingState.RECORDING) return null
        if (ble.connectionGeneration != recording.connectionGeneration ||
            (ble.phase !is BleConnectionPhase.Subscribed &&
                ble.phase !is BleConnectionPhase.Receiving)) {
            return CaptureStopReason.DEVICE_DISCONNECT
        }
        if (ble.freshness != StreamFreshness.STALE) {
            staleSince = null
            return null
        }
        val now = monotonicMillis()
        val since = staleSince ?: now.also { staleSince = it }
        return CaptureStopReason.DATA_TIMEOUT.takeIf { now - since >= staleGraceMillis }
    }
}
