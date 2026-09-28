package com.example.ppgcollector_android.core.ble

import com.example.ppgcollector_android.core.signal.StreamFreshness
import com.example.ppgcollector_android.core.protocol.CupStreamProtocolMode
import com.example.ppgcollector_android.core.protocol.CupBatchStreamDecoder
import com.example.ppgcollector_android.core.protocol.Ads1292rStreamDecoder
import com.example.ppgcollector_android.core.protocol.CupFrameSequenceTracker
import com.example.ppgcollector_android.core.protocol.CupSequenceEvent
import com.example.ppgcollector_android.core.protocol.CupBatchFrame
import com.example.ppgcollector_android.core.protocol.CupPpgSample
import com.example.ppgcollector_android.core.protocol.CupWireFrameProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class BlePermissionGateState {
    UNKNOWN,
    REQUEST_REQUIRED,
    GRANTED,
    DENIED,
}

data class BlePermissionSnapshot(
    val apiLevel: Int,
    val required: Set<BlePermission>,
    val granted: Set<BlePermission>,
    val gateState: BlePermissionGateState,
) {
    val missing: Set<BlePermission>
        get() = required - granted

    val canUseBle: Boolean
        get() = missing.isEmpty() && gateState == BlePermissionGateState.GRANTED
}

/** Pure permission-result reducer; the Activity owns the actual launcher. */
class BlePermissionResultSeam(
    val apiLevel: Int,
    neverForLocation: Boolean = true,
) {
    private val requiredPermissions =
        BlePermissionPolicy.runtimePermissions(apiLevel, neverForLocation)
    private var grantedPermissions = emptySet<BlePermission>()
    private var gateState = BlePermissionGateState.UNKNOWN

    val snapshot: BlePermissionSnapshot
        get() = BlePermissionSnapshot(apiLevel, requiredPermissions, grantedPermissions, gateState)

    fun permissionsToRequest(): Set<String> {
        val missing = requiredPermissions - grantedPermissions
        if (missing.isNotEmpty()) gateState = BlePermissionGateState.REQUEST_REQUIRED
        return missing.map(BlePermission::manifestName).toSet()
    }

    fun applyResult(grantsByManifestName: Map<String, Boolean>): BlePermissionSnapshot {
        val previouslyGranted = grantedPermissions
        grantedPermissions = requiredPermissions.filter { permission ->
            grantsByManifestName[permission.manifestName] ?: (permission in previouslyGranted)
        }.toSet()
        gateState = if (grantedPermissions.containsAll(requiredPermissions)) {
            BlePermissionGateState.GRANTED
        } else {
            BlePermissionGateState.DENIED
        }
        return snapshot
    }

    fun setGrantedPermissions(granted: Set<BlePermission>): BlePermissionSnapshot {
        grantedPermissions = granted.intersect(requiredPermissions)
        gateState = if (grantedPermissions.containsAll(requiredPermissions)) {
            BlePermissionGateState.GRANTED
        } else {
            BlePermissionGateState.DENIED
        }
        return snapshot
    }
}

data class BleCoordinatorSnapshot(
    val permission: BlePermissionSnapshot,
    val availability: BluetoothAvailability,
    val phase: BleConnectionPhase,
    val connectionGeneration: Long,
    val activeProfile: CupBleDeviceProfile?,
    val activeStreamProtocolMode: CupStreamProtocolMode?,
    val protocolProbePending: Boolean = false,
    val protocolProbeTimedOut: Boolean = false,
    val advertisedName: String? = null,
    val isScanning: Boolean,
    val discoveredDevices: List<DiscoveredBleDevice>,
    val discoveredServiceUuids: List<String>,
    val discoveredCharacteristics: List<BleCharacteristicDiagnostic>,
    val freshness: StreamFreshness,
    val lastError: String?,
    val mtu: BleMtuSnapshot,
    val diagnostics: BleGattDiagnostics,
    val attemptDiagnostics: BleConnectionAttemptDiagnostics,
)

enum class BleCoordinatorAction {
    STARTED,
    PERMISSION_REQUIRED,
    REJECTED,
}

/** App-scope owner seam. UI observes [snapshot] and never owns a GATT instance. */
class BleCoordinator(
    private val transport: BleTransport,
    apiLevel: Int,
    private val uptimeSeconds: () -> Double = { System.nanoTime() / 1_000_000_000.0 },
    private val hostMonotonicNanos: () -> Long = System::nanoTime,
    private val ownerDispatcher: ((() -> Unit) -> Unit) = { action -> action() },
    profiles: List<CupBleDeviceProfile> = CupBleDeviceProfile.supportedBringUpProfiles,
    private val ticker: BleOwnerTicker = BleOwnerTicker.system,
) : AutoCloseable {
    private val ownerLock = Any()
    private var closed = false
    private val permissions = BlePermissionResultSeam(apiLevel)
    private val owner = CupBleGattStateMachine(
        transport = transport,
        profiles = profiles,
        uptimeSeconds = uptimeSeconds,
        monotonicNanos = hostMonotonicNanos,
    )
    private val previewRuntime = BlePreviewRuntime()
    private var tickerHandle: AutoCloseable? = null
    private var tickerGeneration: Long? = null
    private var tickerEpoch: Any? = null
    private var healthGeneration: Long? = null
    private var healthMode: CupStreamProtocolMode? = null
    private var healthDecoder = CupBatchStreamDecoder()
    private var healthAdsDecoder = Ads1292rStreamDecoder()
    private var healthSequence = CupFrameSequenceTracker()
    private var copiedDiscoveredDevices: List<DiscoveredBleDevice> = emptyList()
    private var copiedDiscoveredDevicesEpoch: Long = Long.MIN_VALUE

    @Volatile
    var snapshot: BleCoordinatorSnapshot = snapshotNow()
        private set

    private val _snapshotFlow = MutableStateFlow(snapshot)
    val snapshotFlow: StateFlow<BleCoordinatorSnapshot> = _snapshotFlow.asStateFlow()
    val uiSnapshotFlow: Flow<BleCoordinatorSnapshot> =
        snapshotFlow
            .map { value ->
                value.copy(
                    diagnostics = BleGattDiagnostics(),
                    attemptDiagnostics = BleConnectionAttemptDiagnostics(),
                )
            }
            .distinctUntilChanged()

    val previewFlow: StateFlow<BlePreviewSnapshot> = previewRuntime.snapshot

    /** Debug/test-only counters; never collected by the root Compose tree. */
    val previewDiagnostics: BlePreviewRuntimeDiagnostics
        get() = previewRuntime.diagnostics()

    private var recordingSinkOwner: Any? = null
    private var recordingTransactionOwner: Any? = null
    private var recordingRawSink: ((BleRawNotificationChunk) -> Unit)? = null
    private var previewGeneration = snapshot.connectionGeneration
    private var previewWasActive = false
    private var previewProtocolMode = snapshot.activeStreamProtocolMode
    private var previewUiActive = true

    var onRawChunk: ((BleRawNotificationChunk) -> Unit)?
        get() = synchronized(ownerLock) { recordingRawSink }
        set(value) {
            synchronized(ownerLock) {
                recordingSinkOwner = null
                recordingRawSink = value
            }
        }

    fun attachRecordingSink(token: Any, sink: (BleRawNotificationChunk) -> Unit) {
        synchronized(ownerLock) {
            check(recordingTransactionOwner == null || recordingTransactionOwner === token)
            recordingSinkOwner = token
            recordingRawSink = sink
        }
    }

    /** Reserve across service instances, including initialization and destroy drain. */
    fun tryAcquireRecordingOwner(token: Any): Boolean = synchronized(ownerLock) {
        if (recordingTransactionOwner != null) return@synchronized false
        recordingTransactionOwner = token
        true
    }

    fun releaseRecordingOwner(token: Any) {
        synchronized(ownerLock) {
            if (recordingTransactionOwner === token) recordingTransactionOwner = null
        }
    }

    fun detachRecordingSink(token: Any) {
        synchronized(ownerLock) {
            if (recordingSinkOwner !== token) return
            recordingRawSink = null
            recordingSinkOwner = null
        }
    }

    init {
        // The coordinator is the sole event sink installed above the pure owner.
        owner.onRawChunk = ::dispatchRawChunk
        transport.eventHandler = { event ->
            synchronized(ownerLock) {
                if (closed) return@synchronized
                owner.handle(event, uptimeSeconds(), hostMonotonicNanos())
                val replay = owner.takeProtocolReplay()
                if (event !is BleTransportEvent.ValueReceived || uiSliceChanged() || replay.isNotEmpty()) {
                    publish()
                }
                // Probe bytes may already have been delivered once to the raw
                // recording sink under the provisional mode. Replay them to
                // the preview decoder only; a recording must never receive a
                // second copy of the same notification.
                replay.forEach(::dispatchReplayChunk)
                if (uiSliceChanged()) publish()
            }
        }
        // Match the platform transport contract: install the event sink before
        // activating the adapter so the initial availability event is observed.
        transport.activate()
    }

    fun permissionRequest(): Set<String> = synchronized(ownerLock) {
        permissions.permissionsToRequest().also { publish() }
    }

    fun applyPermissionResult(grantsByManifestName: Map<String, Boolean>): BlePermissionSnapshot =
        synchronized(ownerLock) {
            permissions.applyResult(grantsByManifestName).also { publish() }
        }

    fun startScanning(clearPreviousResults: Boolean = false): BleCoordinatorAction {
        return synchronized(ownerLock) {
            if (!permissions.snapshot.canUseBle) {
                permissions.permissionsToRequest()
                publish()
                return@synchronized BleCoordinatorAction.PERMISSION_REQUIRED
            }
            owner.startScanning(clearPreviousResults)
            if (owner.availability != BluetoothAvailability.POWERED_ON) {
                // Permission may have been granted after the initial activation;
                // retry the adapter state query before waiting for scan readiness.
                transport.activate()
            }
            publish()
            BleCoordinatorAction.STARTED
        }
    }

    fun stopScanning() {
        synchronized(ownerLock) {
            owner.stopScanning()
            publish()
        }
    }

    fun connect(deviceId: String): BleCoordinatorAction {
        return synchronized(ownerLock) {
            if (!permissions.snapshot.canUseBle) {
                permissions.permissionsToRequest()
                publish()
                return@synchronized BleCoordinatorAction.PERMISSION_REQUIRED
            }
            val started = owner.connect(deviceId)
            publish()
            if (started) BleCoordinatorAction.STARTED else BleCoordinatorAction.REJECTED
        }
    }

    fun retryLastConnection(): BleCoordinatorAction {
        return synchronized(ownerLock) {
            if (!permissions.snapshot.canUseBle) {
                permissions.permissionsToRequest()
                publish()
                return@synchronized BleCoordinatorAction.PERMISSION_REQUIRED
            }
            val started = owner.retryLastConnection()
            publish()
            if (started) BleCoordinatorAction.STARTED else BleCoordinatorAction.REJECTED
        }
    }

    fun disconnect() {
        synchronized(ownerLock) {
            owner.disconnect()
            publish()
        }
    }

    fun selectStreamProtocol(mode: CupStreamProtocolMode): Boolean {
        return synchronized(ownerLock) {
            val accepted = owner.selectStreamProtocol(mode)
            if (accepted) {
                val replay = owner.takeProtocolReplay()
                publish()
                replay.forEach(::dispatchReplayChunk)
                if (uiSliceChanged()) publish()
            }
            accepted
        }
    }

    fun selectNordicProtocol(mode: CupStreamProtocolMode): Boolean = selectStreamProtocol(mode)

    fun refreshFreshness() {
        synchronized(ownerLock) {
            owner.refreshFreshness(uptimeSeconds())
            publish()
        }
    }

    fun pollDeadline(): Boolean {
        return synchronized(ownerLock) {
            val expired = owner.pollDeadline(uptimeSeconds())
            publish()
            expired
        }
    }

    fun markValidFrame() {
        synchronized(ownerLock) {
            if (owner.markValidFrame(uptimeSeconds())) publish()
        }
    }

    /** Activity visibility seam; raw recording remains independent of preview/UI attachment. */
    fun setPreviewUiActive(active: Boolean) {
        synchronized(ownerLock) {
            if (closed || previewUiActive == active) return
            previewUiActive = active
            if (!active) previewRuntime.suspend()
        }
    }

    override fun close() {
        synchronized(ownerLock) {
            if (closed) return
            closed = true
            tickerHandle?.close()
            tickerHandle = null
            tickerEpoch = null
        }
        previewRuntime.close()
        transport.close()
    }

    private fun publish() {
        val next = snapshotNow()
        val previewActive = next.phase is BleConnectionPhase.Subscribed ||
            next.phase is BleConnectionPhase.Receiving
        val modeChanged = next.activeStreamProtocolMode != previewProtocolMode
        val becameInactive = !previewActive && previewWasActive
        if (next.connectionGeneration != previewGeneration || becameInactive) {
            previewRuntime.reset(
                next.connectionGeneration,
                next.activeStreamProtocolMode ?: CupStreamProtocolMode.BATCH_COMPATIBLE,
            )
            previewGeneration = next.connectionGeneration
        } else if (modeChanged) {
            previewRuntime.reset(
                next.connectionGeneration,
                next.activeStreamProtocolMode ?: CupStreamProtocolMode.BATCH_COMPATIBLE,
                clearQueuedChunks = true,
            )
        }
        if (becameInactive) previewRuntime.suspend()
        if (!previewUiActive) previewRuntime.suspend()
        previewProtocolMode = next.activeStreamProtocolMode
        previewWasActive = previewActive
        snapshot = next
        _snapshotFlow.value = snapshot
        updateTicker(next)
    }

    private fun dispatchRawChunk(chunk: BleRawNotificationChunk) {
        // Raw is acknowledged/enqueued before either disposable decoder.
        recordingRawSink?.invoke(chunk)
        confirmHealth(chunk)
        if (previewUiActive) previewRuntime.offer(chunk)
    }

    private fun dispatchReplayChunk(chunk: BleRawNotificationChunk) {
        confirmHealth(chunk)
        if (previewUiActive) previewRuntime.offer(chunk)
    }

    private fun confirmHealth(chunk: BleRawNotificationChunk) {
        if (healthGeneration != chunk.connectionGeneration || healthMode != chunk.streamProtocolMode) {
            healthGeneration = chunk.connectionGeneration
            healthMode = chunk.streamProtocolMode
            healthDecoder = CupBatchStreamDecoder(protocolMode = chunk.streamProtocolMode)
            healthAdsDecoder = Ads1292rStreamDecoder()
            healthSequence = CupFrameSequenceTracker()
        }
        val frames = if (chunk.streamProtocolMode == CupStreamProtocolMode.ADS1292R_120) {
            healthAdsDecoder.feed(chunk.bytes).map { packet ->
                CupBatchFrame(packet.sequenceNumber.toUByte(), packet.red.indices.map {
                    CupPpgSample(packet.red[it], packet.ir[it])
                }, packet.sequenceNumber, CupWireFrameProfile.SENSOR_PACKET_168)
            }
        } else healthDecoder.feed(chunk.bytes)
        val accepted = frames.map(healthSequence::observe).any {
            it !is CupSequenceEvent.Duplicate && it !is CupSequenceEvent.OutOfOrder
        }
        if (accepted) owner.markValidFrame(uptimeSeconds())
    }

    private fun updateTicker(value: BleCoordinatorSnapshot) {
        val active = !closed && value.phase.deviceId != null &&
            value.phase !is BleConnectionPhase.Disconnecting
        val generation = value.connectionGeneration.takeIf { active }
        if (generation == tickerGeneration) return
        tickerHandle?.close()
        tickerHandle = null
        tickerGeneration = generation
        val epoch = Any()
        tickerEpoch = epoch
        if (generation == null) return
        tickerHandle = ticker.start {
            ownerDispatcher {
                synchronized(ownerLock) {
                    if (closed || tickerEpoch !== epoch || owner.connectionGeneration != generation) return@synchronized
                    val now = uptimeSeconds()
                    val expired = owner.pollDeadline(now)
                    owner.refreshFreshness(now)
                    val probeChanged = owner.pollProtocolProbe(now)
                    if (expired || probeChanged || uiSliceChanged()) publish()
                }
            }
        }
    }

    private fun snapshotNow() = BleCoordinatorSnapshot(
        permission = permissions.snapshot,
        availability = owner.availability,
        phase = owner.phase,
        connectionGeneration = owner.connectionGeneration,
        activeProfile = owner.activeProfile,
        activeStreamProtocolMode = owner.activeStreamProtocolMode,
        protocolProbePending = owner.protocolProbePending,
        protocolProbeTimedOut = owner.protocolProbeTimedOut,
        advertisedName = owner.advertisedName,
        isScanning = owner.isScanning,
        discoveredDevices = devicesForSnapshot(),
        discoveredServiceUuids = owner.discoveredServiceUuids,
        discoveredCharacteristics = owner.discoveredCharacteristics,
        freshness = owner.freshness,
        lastError = owner.lastError,
        mtu = owner.mtu,
        diagnostics = owner.diagnostics,
        attemptDiagnostics = owner.attemptDiagnostics,
    )

    private fun devicesForSnapshot(): List<DiscoveredBleDevice> {
        val epoch = owner.discoveredDevicesEpoch
        if (epoch != copiedDiscoveredDevicesEpoch) {
            copiedDiscoveredDevices = owner.discoveredDevices.toList()
            copiedDiscoveredDevicesEpoch = epoch
        }
        return copiedDiscoveredDevices
    }

    private fun uiSliceChanged(): Boolean {
        val previous = snapshot
        return previous.phase != owner.phase ||
            previous.freshness != owner.freshness ||
            previous.activeStreamProtocolMode != owner.activeStreamProtocolMode ||
            previous.protocolProbePending != owner.protocolProbePending ||
            previous.protocolProbeTimedOut != owner.protocolProbeTimedOut ||
            previous.isScanning != owner.isScanning ||
            previous.lastError != owner.lastError ||
            previous.availability != owner.availability ||
            previous.advertisedName != owner.advertisedName ||
            previous.connectionGeneration != owner.connectionGeneration ||
            previous.mtu != owner.mtu ||
            previous.permission != permissions.snapshot ||
            previous.activeProfile != owner.activeProfile ||
            copiedDiscoveredDevicesEpoch != owner.discoveredDevicesEpoch
    }
}
