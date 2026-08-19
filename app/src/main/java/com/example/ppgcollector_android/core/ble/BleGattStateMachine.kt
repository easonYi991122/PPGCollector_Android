package com.example.ppgcollector_android.core.ble

import com.example.ppgcollector_android.core.protocol.CupStreamProtocolMode
import com.example.ppgcollector_android.core.protocol.NordicWireProbe
import java.time.Instant

data class BleRawNotificationChunk(
    val connectionGeneration: Long,
    val hostMonotonicNanos: Long,
    val bytes: ByteArray,
    val streamProtocolMode: CupStreamProtocolMode = CupStreamProtocolMode.BATCH_COMPATIBLE,
) {
    fun copyOfBytes(): BleRawNotificationChunk = copy(bytes = bytes.copyOf())
}

data class BleGattDiagnostics(
    val ignoredStaleCallbackCount: Int = 0,
    val notificationErrorCount: Int = 0,
    val emptyNotificationCount: Int = 0,
    val receivedNotificationCount: Int = 0,
    val rawChunkCount: Int = 0,
)

/**
 * Pure ordered BLE owner seam. Android BluetoothGatt callbacks are adapted to
 * BleTransportEvent and delivered here on one serialized executor.
 */
class CupBleGattStateMachine(
    private val transport: BleTransport,
    profiles: List<CupBleDeviceProfile> = CupBleDeviceProfile.supportedBringUpProfiles,
    private val timeoutPolicy: BleConnectionTimeoutPolicy = BleConnectionTimeoutPolicy.iosDefault,
    private val uptimeSeconds: () -> Double = { 0.0 },
    private val monotonicNanos: () -> Long = { 0L },
) {
    private val profiles = profiles.toList()

    init {
        require(this.profiles.isNotEmpty()) { "at least one CUP BLE profile is required" }
        require(this.profiles.map { it.serviceUuid.lowercase() }.distinct().size == this.profiles.size) {
            "CUP BLE profile service UUIDs must be unique"
        }
    }

    var availability: BluetoothAvailability = BluetoothAvailability.UNKNOWN
        private set
    val discoveredDevices = mutableListOf<DiscoveredBleDevice>()
    var phase: BleConnectionPhase = BleConnectionPhase.Idle
        private set
    var isScanning: Boolean = false
        private set
    var lastError: String? = null
        private set
    var freshness: com.example.ppgcollector_android.core.signal.StreamFreshness =
        com.example.ppgcollector_android.core.signal.StreamFreshness.UNAVAILABLE
        private set
    var diagnostics: BleGattDiagnostics = BleGattDiagnostics()
        private set
    var attemptDiagnostics: BleConnectionAttemptDiagnostics = BleConnectionAttemptDiagnostics()
        private set
    var connectionGeneration: Long = 0
        private set
    var activeProfile: CupBleDeviceProfile? = null
        private set
    var activeStreamProtocolMode: CupStreamProtocolMode? = null
        private set
    var protocolProbePending: Boolean = false
        private set
    var protocolProbeTimedOut: Boolean = false
        private set
    var discoveredServiceUuids: List<String> = emptyList()
        private set
    var discoveredCharacteristics: List<BleCharacteristicDiagnostic> = emptyList()
        private set

    var onRawChunk: ((BleRawNotificationChunk) -> Unit)? = null

    private val deadlineTracker = BleConnectionDeadlineTracker()
    private val freshnessTracker = CupStreamFreshnessTracker(2.0)
    private var shouldScanWhenReady = false
    private var activeDeviceId: String? = null
    private var activeDeviceName: String? = null
    private var lastRequestedDeviceId: String? = null
    private var notifyCharacteristicUuid: String? = null
    private var controlCharacteristicUuid: String? = null
    private var activeDeadline: BleConnectionDeadline? = null
    private var nordicWireProbe: NordicWireProbe? = null
    private val pendingNordicChunks = ArrayList<BleRawNotificationChunk>()
    private var protocolProbeStartedAt: Double? = null

    init {
        transport.eventHandler = { event ->
            handle(event, uptimeSeconds(), monotonicNanos())
        }
    }

    fun startScanning(clearPreviousResults: Boolean = false) {
        shouldScanWhenReady = true
        lastError = null
        if (clearPreviousResults) discoveredDevices.clear()
        if (availability == BluetoothAvailability.POWERED_ON && !isScanning) {
            isScanning = true
            transport.startScanning()
        }
    }

    fun stopScanning() {
        shouldScanWhenReady = false
        val wasScanning = isScanning
        isScanning = false
        if (wasScanning) transport.stopScanning()
    }

    fun connect(deviceId: String): Boolean {
        if (availability != BluetoothAvailability.POWERED_ON ||
            discoveredDevices.none { it.id == deviceId } || phase.isBusy
        ) return false
        stopScanning()
        val discovered = discoveredDevices.first { it.id == deviceId }
        lastRequestedDeviceId = deviceId
        activeDeviceId = deviceId
        activeDeviceName = discovered.name
        activeStreamProtocolMode = discovered.streamProtocolMode
        nordicWireProbe = if (discovered.name == "Nordic_UART_Service") NordicWireProbe() else null
        protocolProbePending = nordicWireProbe != null
        protocolProbeTimedOut = false
        protocolProbeStartedAt = null
        pendingNordicChunks.clear()
        activeProfile = null
        notifyCharacteristicUuid = null
        controlCharacteristicUuid = null
        discoveredServiceUuids = emptyList()
        discoveredCharacteristics = emptyList()
        connectionGeneration++
        attemptDiagnostics = attemptDiagnostics.copy(
            attemptCount = attemptDiagnostics.attemptCount + 1,
            activeOperation = BleConnectionOperation.CONNECT,
            lastTimedOutOperation = null,
        )
        phase = BleConnectionPhase.Connecting(deviceId)
        armDeadline(BleConnectionOperation.CONNECT, deviceId, 0.0)
        transport.connect(deviceId)
        return true
    }

    fun retryLastConnection(): Boolean = lastRequestedDeviceId?.let(::connect) == true

    fun disconnect(): Boolean {
        val deviceId = activeDeviceId ?: return false.also { cancelDeadline() }
        cancelDeadline()
        phase = BleConnectionPhase.Disconnecting(deviceId)
        notifyCharacteristicUuid?.let {
            transport.setNotificationsEnabled(false, it, deviceId)
        }
        transport.disconnect(deviceId)
        return true
    }

    /** Delivers one serialized callback. `callbackGeneration` rejects late callbacks. */
    fun handle(
        event: BleTransportEvent,
        nowUptimeSeconds: Double,
        hostMonotonicNanos: Long = 0L,
        callbackGeneration: Long = connectionGeneration,
    ) {
        if (callbackGeneration != connectionGeneration && event !is BleTransportEvent.AvailabilityChanged &&
            event !is BleTransportEvent.Discovered && event !is BleTransportEvent.ScanStopped
        ) {
            incrementStaleCallback()
            return
        }
        when (event) {
            is BleTransportEvent.AvailabilityChanged -> handleAvailability(event.availability)
            is BleTransportEvent.Discovered -> updateDiscovered(event.discovery)
            is BleTransportEvent.ScanStopped -> handleScanStopped(event)
            is BleTransportEvent.Connected -> handleConnected(event.deviceId, nowUptimeSeconds)
            is BleTransportEvent.FailedToConnect -> handleFailedToConnect(event.deviceId, event.message)
            is BleTransportEvent.Disconnected -> handleDisconnected(event.deviceId, event.message)
            is BleTransportEvent.ServicesDiscovered -> handleServices(event, nowUptimeSeconds)
            is BleTransportEvent.CharacteristicsDiscovered -> handleCharacteristics(event, nowUptimeSeconds)
            is BleTransportEvent.NotificationStateChanged -> handleNotificationState(event, nowUptimeSeconds)
            is BleTransportEvent.ValueReceived -> handleValue(event, hostMonotonicNanos, nowUptimeSeconds)
        }
    }

    fun markValidFrame(atUptimeSeconds: Double): Boolean {
        if (phase !is BleConnectionPhase.Subscribed && phase !is BleConnectionPhase.Receiving) {
            return false
        }
        freshnessTracker.observeValidFrame(atUptimeSeconds)
        freshness = freshnessTracker.freshness(atUptimeSeconds)
        return true
    }

    fun refreshFreshness(atUptimeSeconds: Double): com.example.ppgcollector_android.core.signal.StreamFreshness {
        freshness = freshnessTracker.freshness(atUptimeSeconds)
        return freshness
    }

    fun pollDeadline(nowUptimeSeconds: Double): Boolean {
        val deadline = activeDeadline ?: return false
        if (!deadlineTracker.consumeExpiration(deadline, nowUptimeSeconds)) return false
        activeDeadline = null
        attemptDiagnostics = attemptDiagnostics.copy(
            timeoutCount = attemptDiagnostics.timeoutCount + 1,
            activeOperation = null,
            lastTimedOutOperation = deadline.operation,
        )
        fail("${deadline.operation.title}超时，请确认设备仍在附近后重试。", activeDeviceId)
        return true
    }

    private fun handleAvailability(value: BluetoothAvailability) {
        availability = value
        if (value == BluetoothAvailability.POWERED_ON) {
            if (shouldScanWhenReady && !isScanning) {
                isScanning = true
                transport.startScanning()
            }
            return
        }
        stopScanning()
        if (activeDeviceId != null) {
            cancelDeadline()
            lastError = "${value.title}，当前连接已结束。"
            phase = BleConnectionPhase.Failed(lastError!!)
            activeDeviceId = null
            activeDeviceName = null
            activeStreamProtocolMode = null
        }
        freshnessTracker.reset()
        freshness = com.example.ppgcollector_android.core.signal.StreamFreshness.UNAVAILABLE
    }

    private fun handleScanStopped(event: BleTransportEvent.ScanStopped) {
        isScanning = false
        shouldScanWhenReady = false
        lastError = when (event.reason) {
            BleScanStopReason.TIMEOUT -> if (discoveredDevices.isEmpty()) {
                "扫描超时，未发现 CUP 设备。"
            } else {
                null
            }
            BleScanStopReason.PLATFORM_FAILURE -> event.message ?: "蓝牙扫描失败，请重试。"
        }
    }

    private fun updateDiscovered(discovery: BleTransportDiscovery) {
        val name = discovery.name ?: return
        val matchingProfile = profiles.firstOrNull { it.acceptsAdvertisedName(name) } ?: return
        val streamProtocolMode = matchingProfile.streamProtocolModeForAdvertisedName(name) ?: return
        val device = DiscoveredBleDevice(
            id = discovery.deviceId,
            name = name,
            rssi = discovery.rssi,
            isConnectable = discovery.isConnectable,
            lastSeen = discovery.seenAt,
            streamProtocolMode = streamProtocolMode,
        )
        val index = discoveredDevices.indexOfFirst { it.id == device.id }
        if (index >= 0) discoveredDevices[index] = device else discoveredDevices += device
        discoveredDevices.sortWith(compareBy<DiscoveredBleDevice> { it.name }.thenByDescending { it.rssi ?: Int.MIN_VALUE })
    }

    private fun handleConnected(deviceId: String, now: Double) {
        if (!accepts(deviceId, BleConnectionPhase.Connecting(deviceId))) {
            transport.disconnect(deviceId)
            return
        }
        phase = BleConnectionPhase.DiscoveringServices(deviceId)
        armDeadline(BleConnectionOperation.SERVICE_DISCOVERY, deviceId, now)
        transport.discoverServices(deviceId)
    }

    private fun handleFailedToConnect(deviceId: String, message: String?) {
        if (activeDeviceId != deviceId) {
            incrementStaleCallback()
            return
        }
        cancelDeadline()
        activeDeviceId = null
        activeDeviceName = null
        nordicWireProbe = null
        pendingNordicChunks.clear()
        activeStreamProtocolMode = null
        phase = BleConnectionPhase.Failed(message ?: "无法连接设备。")
        lastError = phase.let { (it as BleConnectionPhase.Failed).message }
    }

    private fun handleDisconnected(deviceId: String, message: String?) {
        if (activeDeviceId != deviceId) {
            incrementStaleCallback()
            return
        }
        cancelDeadline()
        activeDeviceId = null
        activeDeviceName = null
        activeStreamProtocolMode = null
        activeProfile = null
        notifyCharacteristicUuid = null
        controlCharacteristicUuid = null
        discoveredServiceUuids = emptyList()
        discoveredCharacteristics = emptyList()
        freshnessTracker.reset()
        freshness = com.example.ppgcollector_android.core.signal.StreamFreshness.UNAVAILABLE
        if (phase !is BleConnectionPhase.Failed) {
            if (message == null) phase = BleConnectionPhase.Idle
            else fail("设备连接已中断：$message", null)
        }
    }

    private fun handleServices(event: BleTransportEvent.ServicesDiscovered, now: Double) {
        if (!accepts(event.deviceId, BleConnectionPhase.DiscoveringServices(event.deviceId))) return
        if (event.errorMessage != null) {
            fail("服务发现失败：${event.errorMessage}", event.deviceId)
            return
        }
        discoveredServiceUuids = event.serviceUuids.sorted()
        val selectedProfile = profiles.firstOrNull { candidate ->
            candidate.acceptsAdvertisedName(activeDeviceName) &&
                event.serviceUuids.any { it.equals(candidate.serviceUuid, ignoreCase = true) }
        }
        if (selectedProfile == null) {
            val expected = profiles.joinToString { it.serviceUuid }
            val discovered = discoveredServiceUuids.joinToString().ifEmpty { "无" }
            fail("设备未提供受支持的 CUP 服务。期望：$expected；发现：$discovered。", event.deviceId)
            return
        }
        activeProfile = selectedProfile
        val target = event.serviceUuids.first {
            it.equals(selectedProfile.serviceUuid, ignoreCase = true)
        }
        phase = BleConnectionPhase.DiscoveringCharacteristics(event.deviceId)
        armDeadline(BleConnectionOperation.CHARACTERISTIC_DISCOVERY, event.deviceId, now)
        transport.discoverCharacteristics(
            listOf(selectedProfile.notifyCharacteristicUuid, selectedProfile.controlCharacteristicUuid),
            target,
            event.deviceId,
        )
    }

    private fun handleCharacteristics(event: BleTransportEvent.CharacteristicsDiscovered, now: Double) {
        if (!accepts(event.deviceId, BleConnectionPhase.DiscoveringCharacteristics(event.deviceId))) return
        val profile = activeProfile ?: return fail("未选择 CUP BLE profile。", event.deviceId)
        if (!event.serviceUuid.equals(profile.serviceUuid, ignoreCase = true)) {
            incrementStaleCallback()
            return
        }
        if (event.errorMessage != null) {
            fail("特征发现失败：${event.errorMessage}", event.deviceId)
            return
        }
        discoveredCharacteristics = event.characteristics.map { characteristic ->
            BleCharacteristicDiagnostic(
                uuid = characteristic.uuid,
                properties = characteristic.properties,
                role = when {
                    characteristic.uuid.equals(profile.notifyCharacteristicUuid, ignoreCase = true) -> "TX / notify"
                    characteristic.uuid.equals(profile.controlCharacteristicUuid, ignoreCase = true) -> "RX / control"
                    else -> null
                },
                isNotifying = characteristic.isNotifying,
            )
        }.sortedBy { it.uuid }
        val notify = event.characteristics.firstOrNull { it.uuid.equals(profile.notifyCharacteristicUuid, ignoreCase = true) }
        if (notify == null) {
            fail("未找到 TX notify 特征 ${profile.notifyCharacteristicUuid}。", event.deviceId)
            return
        }
        if (!notify.supportsNotifications) {
            fail("TX 特征不支持 notify/indicate。", event.deviceId)
            return
        }
        notifyCharacteristicUuid = notify.uuid
        controlCharacteristicUuid = event.characteristics.firstOrNull {
            it.uuid.equals(profile.controlCharacteristicUuid, ignoreCase = true)
        }?.uuid
        phase = BleConnectionPhase.Subscribing(event.deviceId)
        armDeadline(BleConnectionOperation.NOTIFICATION_SUBSCRIPTION, event.deviceId, now)
        transport.setNotificationsEnabled(true, notify.uuid, event.deviceId)
    }

    private fun handleNotificationState(event: BleTransportEvent.NotificationStateChanged, now: Double) {
        val profile = activeProfile ?: return
        if (!event.characteristicUuid.equals(profile.notifyCharacteristicUuid, ignoreCase = true)) return
        if (!accepts(event.deviceId, BleConnectionPhase.Subscribing(event.deviceId))) return
        if (event.errorMessage != null) {
            fail("通知订阅失败：${event.errorMessage}", event.deviceId)
            return
        }
        if (!event.isNotifying) {
            fail("设备未启用 TX 通知。", event.deviceId)
            return
        }
        cancelDeadline()
        phase = BleConnectionPhase.Subscribed(event.deviceId)
        freshnessTracker.start(now)
        freshness = freshnessTracker.freshness(now)
    }

    private fun handleValue(event: BleTransportEvent.ValueReceived, hostNanos: Long, now: Double) {
        val profile = activeProfile
        if (profile == null) {
            if (profiles.any { candidate ->
                    event.characteristicUuid.equals(candidate.notifyCharacteristicUuid, ignoreCase = true)
                }
            ) {
                incrementStaleCallback()
            }
            return
        }
        if (!event.characteristicUuid.equals(profile.notifyCharacteristicUuid, ignoreCase = true)) return
        if (activeDeviceId != event.deviceId ||
            (phase != BleConnectionPhase.Subscribed(event.deviceId) && phase != BleConnectionPhase.Receiving(event.deviceId))
        ) {
            incrementStaleCallback()
            return
        }
        if (event.errorMessage != null) {
            diagnostics = diagnostics.copy(notificationErrorCount = diagnostics.notificationErrorCount + 1)
            lastError = "通知接收错误：${event.errorMessage}"
            return
        }
        val data = event.data
        if (data == null || data.isEmpty()) {
            diagnostics = diagnostics.copy(emptyNotificationCount = diagnostics.emptyNotificationCount + 1)
            return
        }
        diagnostics = diagnostics.copy(
            receivedNotificationCount = diagnostics.receivedNotificationCount + 1,
            rawChunkCount = diagnostics.rawChunkCount + 1,
        )
        if (nordicWireProbe != null && activeStreamProtocolMode == CupStreamProtocolMode.SENSOR_PACKET_168) {
            if (protocolProbeStartedAt == null) protocolProbeStartedAt = now
            if (now - protocolProbeStartedAt!! >= 2.0) protocolProbeTimedOut = true
            pendingNordicChunks += BleRawNotificationChunk(
                connectionGeneration = connectionGeneration,
                hostMonotonicNanos = event.hostMonotonicNanos ?: hostNanos,
                bytes = data.copyOf(),
                streamProtocolMode = CupStreamProtocolMode.SENSOR_PACKET_168,
            )
            val probe = nordicWireProbe!!.feed(data)
            val locked = probe.locked
            if (locked == null) {
                freshness = com.example.ppgcollector_android.core.signal.StreamFreshness.WAITING
                phase = BleConnectionPhase.Receiving(event.deviceId)
                return
            }
            activeStreamProtocolMode = locked
            protocolProbePending = false
            protocolProbeTimedOut = false
            val replay = pendingNordicChunks.toList()
            pendingNordicChunks.clear()
            replay.forEach { chunk ->
                onRawChunk?.invoke(chunk.copy(streamProtocolMode = locked))
            }
            nordicWireProbe = null
            phase = BleConnectionPhase.Receiving(event.deviceId)
            freshnessTracker.start(now)
            freshness = freshnessTracker.freshness(now)
            return
        }
        onRawChunk?.invoke(
            BleRawNotificationChunk(
                connectionGeneration = connectionGeneration,
                hostMonotonicNanos = event.hostMonotonicNanos ?: hostNanos,
                bytes = data.copyOf(),
                streamProtocolMode = activeStreamProtocolMode
                    ?: CupStreamProtocolMode.BATCH_COMPATIBLE,
            ),
        )
        phase = BleConnectionPhase.Receiving(event.deviceId)
        refreshFreshness(now)
    }

    fun selectNordicProtocol(mode: CupStreamProtocolMode): Boolean {
        if (activeDeviceName != "Nordic_UART_Service" ||
            mode !in setOf(CupStreamProtocolMode.ADS1292R_120, CupStreamProtocolMode.SENSOR_PACKET_168)
        ) return false
        val probe = NordicWireProbe(framesToLock = 1)
        pendingNordicChunks.forEach { probe.feed(it.bytes) }
        if (probe.result.locked != mode) return false
        activeStreamProtocolMode = mode
        nordicWireProbe = null
        protocolProbePending = false
        protocolProbeTimedOut = false
        val replay = pendingNordicChunks.toList()
        pendingNordicChunks.clear()
        replay.forEach { onRawChunk?.invoke(it.copy(streamProtocolMode = mode)) }
        return true
    }

    private fun accepts(deviceId: String, expected: BleConnectionPhase): Boolean {
        if (activeDeviceId == deviceId && phase == expected) return true
        incrementStaleCallback()
        return false
    }

    private fun armDeadline(operation: BleConnectionOperation, deviceId: String, now: Double) {
        cancelDeadline()
        val deadline = deadlineTracker.arm(operation, deviceId, now, timeoutPolicy.timeout(operation))
        activeDeadline = deadline
        attemptDiagnostics = attemptDiagnostics.copy(activeOperation = operation)
    }

    private fun cancelDeadline() {
        if (activeDeadline != null) deadlineTracker.cancel()
        activeDeadline = null
        attemptDiagnostics = attemptDiagnostics.copy(activeOperation = null)
    }

    private fun fail(message: String, disconnectDeviceId: String?) {
        cancelDeadline()
        lastError = message
        phase = BleConnectionPhase.Failed(message)
        disconnectDeviceId?.let(transport::disconnect)
    }

    private fun incrementStaleCallback() {
        diagnostics = diagnostics.copy(ignoredStaleCallbackCount = diagnostics.ignoredStaleCallbackCount + 1)
        attemptDiagnostics = attemptDiagnostics.copy(
            ignoredStaleCallbackCount = attemptDiagnostics.ignoredStaleCallbackCount + 1,
        )
    }
}
