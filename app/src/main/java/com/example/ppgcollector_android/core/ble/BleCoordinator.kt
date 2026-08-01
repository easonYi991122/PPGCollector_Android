package com.example.ppgcollector_android.core.ble

import com.example.ppgcollector_android.core.signal.StreamFreshness
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
        grantedPermissions = requiredPermissions.filter { permission ->
            grantsByManifestName[permission.manifestName] == true
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
    val isScanning: Boolean,
    val discoveredDevices: List<DiscoveredBleDevice>,
    val freshness: StreamFreshness,
    val lastError: String?,
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
    private val uptimeSeconds: () -> Double = { 0.0 },
    private val hostMonotonicNanos: () -> Long = { 0L },
    profile: CupBleDeviceProfile = CupBleDeviceProfile.cupNusBringUp,
) {
    private val permissions = BlePermissionResultSeam(apiLevel)
    private val owner = CupBleGattStateMachine(
        transport = transport,
        profile = profile,
        uptimeSeconds = uptimeSeconds,
        monotonicNanos = hostMonotonicNanos,
    )

    var snapshot: BleCoordinatorSnapshot = snapshotNow()
        private set

    private val _snapshotFlow = MutableStateFlow(snapshot)
    val snapshotFlow: StateFlow<BleCoordinatorSnapshot> = _snapshotFlow.asStateFlow()

    var onRawChunk: ((BleRawNotificationChunk) -> Unit)? = null
        set(value) {
            field = value
            owner.onRawChunk = value
        }

    init {
        // The coordinator is the sole event sink installed above the pure owner.
        transport.eventHandler = { event ->
            owner.handle(event, uptimeSeconds(), hostMonotonicNanos())
            publish()
        }
    }

    fun permissionRequest(): Set<String> = permissions.permissionsToRequest().also { publish() }

    fun applyPermissionResult(grantsByManifestName: Map<String, Boolean>): BlePermissionSnapshot =
        permissions.applyResult(grantsByManifestName).also { publish() }

    fun startScanning(clearPreviousResults: Boolean = false): BleCoordinatorAction {
        if (!permissions.snapshot.canUseBle) {
            permissionRequest()
            return BleCoordinatorAction.PERMISSION_REQUIRED
        }
        owner.startScanning(clearPreviousResults)
        publish()
        return BleCoordinatorAction.STARTED
    }

    fun stopScanning() {
        owner.stopScanning()
        publish()
    }

    fun connect(deviceId: String): BleCoordinatorAction {
        if (!permissions.snapshot.canUseBle) {
            permissionRequest()
            return BleCoordinatorAction.PERMISSION_REQUIRED
        }
        val started = owner.connect(deviceId)
        publish()
        return if (started) BleCoordinatorAction.STARTED else BleCoordinatorAction.REJECTED
    }

    fun retryLastConnection(): BleCoordinatorAction {
        if (!permissions.snapshot.canUseBle) {
            permissionRequest()
            return BleCoordinatorAction.PERMISSION_REQUIRED
        }
        val started = owner.retryLastConnection()
        publish()
        return if (started) BleCoordinatorAction.STARTED else BleCoordinatorAction.REJECTED
    }

    fun disconnect() {
        owner.disconnect()
        publish()
    }

    fun refreshFreshness() {
        owner.refreshFreshness(uptimeSeconds())
        publish()
    }

    fun pollDeadline(): Boolean {
        val expired = owner.pollDeadline(uptimeSeconds())
        publish()
        return expired
    }

    fun markValidFrame() {
        owner.markValidFrame(uptimeSeconds())
        publish()
    }

    private fun publish() {
        snapshot = snapshotNow()
        _snapshotFlow.value = snapshot
    }

    private fun snapshotNow() = BleCoordinatorSnapshot(
        permission = permissions.snapshot,
        availability = owner.availability,
        phase = owner.phase,
        isScanning = owner.isScanning,
        discoveredDevices = owner.discoveredDevices.toList(),
        freshness = owner.freshness,
        lastError = owner.lastError,
        diagnostics = owner.diagnostics,
        attemptDiagnostics = owner.attemptDiagnostics,
    )
}
