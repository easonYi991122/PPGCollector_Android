package com.example.ppgcollector_android.core.ble

import com.example.ppgcollector_android.core.protocol.Ads1292rPacketProtocol
import com.example.ppgcollector_android.core.protocol.CupStreamProtocolMode
import com.example.ppgcollector_android.core.protocol.NordicWireProbe
import com.example.ppgcollector_android.core.protocol.WireFrameGeometry

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
    var discoveredDevicesEpoch: Long = 0
        private set
    val advertisedName: String?
        get() = activeDeviceName
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
    private var wireGeometryProbe: NordicWireProbe? = null
    private val pendingProbeChunks = ArrayList<BleRawNotificationChunk>()
    private var pendingReplayChunks: List<BleRawNotificationChunk> = emptyList()
    private var protocolProbeStartedAt: Double? = null
    private var acceptedBatchFrame: Boolean = false

    init {
        transport.eventHandler = { event ->
            handle(event, uptimeSeconds(), monotonicNanos())
        }
    }

    fun startScanning(clearPreviousResults: Boolean = false) {
        shouldScanWhenReady = true
        lastError = null
        if (clearPreviousResults) {
            discoveredDevices.clear()
            discoveredDevicesEpoch++
        }
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
        wireGeometryProbe = NordicWireProbe()
        protocolProbePending = BleAdvertisedIdentity.isNordic(discovered.name)
        protocolProbeTimedOut = false
        protocolProbeStartedAt = null
        acceptedBatchFrame = false
        pendingProbeChunks.clear()
        pendingReplayChunks = emptyList()
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
        val previousFreshness = freshness
        val previousPending = protocolProbePending
        val previousTimedOut = protocolProbeTimedOut
        freshnessTracker.observeValidFrame(atUptimeSeconds)
        freshness = freshnessTracker.freshness(atUptimeSeconds)
        if (activeStreamProtocolMode == CupStreamProtocolMode.BATCH_COMPATIBLE &&
            wireGeometryProbe != null
        ) {
            acceptedBatchFrame = true
            cancelProbe()
        }
        return freshness != previousFreshness ||
            protocolProbePending != previousPending ||
            protocolProbeTimedOut != previousTimedOut
    }

    fun pollProtocolProbe(atUptimeSeconds: Double): Boolean {
        val previousPending = protocolProbePending
        val previousTimedOut = protocolProbeTimedOut
        applyProtocolProbeTimeout(atUptimeSeconds)
        return protocolProbePending != previousPending || protocolProbeTimedOut != previousTimedOut
    }

    fun takeProtocolReplay(): List<BleRawNotificationChunk> {
        val chunks = pendingReplayChunks
        pendingReplayChunks = emptyList()
        return chunks
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
            clearProbeState()
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
        discoveredDevicesEpoch++
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
        clearProbeState()
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
        clearProbeState()
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
        protocolProbeStartedAt = now
        applyProtocolProbeTimeout(now)
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
        val chunk = BleRawNotificationChunk(
            connectionGeneration = connectionGeneration,
            hostMonotonicNanos = event.hostMonotonicNanos ?: hostNanos,
            bytes = data.copyOf(),
            streamProtocolMode = activeStreamProtocolMode
                ?: CupStreamProtocolMode.BATCH_COMPATIBLE,
        )
        val cupIdentity = BleAdvertisedIdentity.isCup(activeDeviceName)
        val nordicIdentity = BleAdvertisedIdentity.isNordic(activeDeviceName)
        val probe = wireGeometryProbe
        if (probe != null) {
            bufferPendingChunk(chunk)
            val probeResult = probe.feed(data)
            applyProtocolProbeTimeout(now)
            if (cupIdentity && !acceptedBatchFrame) {
                onRawChunk?.invoke(chunk.copy(streamProtocolMode = CupStreamProtocolMode.BATCH_COMPATIBLE))
                if (probeResult.locked == CupStreamProtocolMode.ADS1292R_120) {
                    lockStreamProtocol(CupStreamProtocolMode.ADS1292R_120, now)
                }
                phase = BleConnectionPhase.Receiving(event.deviceId)
                // A CUP name is provisionally batch-compatible. Do not let a
                // recording start race the asynchronous preview decode before
                // the probe either accepts a batch frame or locks ADS 120.
                if (activeStreamProtocolMode == CupStreamProtocolMode.BATCH_COMPATIBLE) {
                    freshness = com.example.ppgcollector_android.core.signal.StreamFreshness.WAITING
                } else {
                    refreshFreshness(now)
                }
                return
            }
            if (nordicIdentity &&
                activeStreamProtocolMode == CupStreamProtocolMode.SENSOR_PACKET_168
            ) {
                val locked = probeResult.locked
                if (locked == null) {
                    freshness = com.example.ppgcollector_android.core.signal.StreamFreshness.WAITING
                    phase = BleConnectionPhase.Receiving(event.deviceId)
                    return
                }
                lockStreamProtocol(locked, now)
                return
            }
        }
        onRawChunk?.invoke(chunk)
        phase = BleConnectionPhase.Receiving(event.deviceId)
        refreshFreshness(now)
    }

    fun selectStreamProtocol(mode: CupStreamProtocolMode): Boolean {
        val name = activeDeviceName ?: return false
        val allowed = when {
            BleAdvertisedIdentity.isNordic(name) -> setOf(
                CupStreamProtocolMode.ADS1292R_120,
                CupStreamProtocolMode.SENSOR_PACKET_168,
            )
            BleAdvertisedIdentity.isCup(name) -> setOf(
                CupStreamProtocolMode.ADS1292R_120,
                CupStreamProtocolMode.BATCH_COMPATIBLE,
            )
            else -> return false
        }
        if (mode !in allowed) return false
        if (!overrideGeometryMatches(mode)) return false
        lockStreamProtocol(mode, uptimeSeconds())
        return true
    }

    fun selectNordicProtocol(mode: CupStreamProtocolMode): Boolean = selectStreamProtocol(mode)

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

    private fun lockStreamProtocol(mode: CupStreamProtocolMode, now: Double) {
        activeStreamProtocolMode = mode
        protocolProbePending = false
        protocolProbeTimedOut = false
        pendingReplayChunks = pendingProbeChunks.map { chunk ->
            chunk.copy(streamProtocolMode = mode)
        }
        pendingProbeChunks.clear()
        wireGeometryProbe = null
        val deviceId = activeDeviceId
        if (deviceId != null &&
            (phase is BleConnectionPhase.Subscribed || phase is BleConnectionPhase.Receiving)
        ) {
            phase = BleConnectionPhase.Receiving(deviceId)
        }
        freshnessTracker.start(now)
        freshness = freshnessTracker.freshness(now)
    }

    private fun cancelProbe() {
        wireGeometryProbe = null
        protocolProbePending = false
        protocolProbeTimedOut = false
        pendingProbeChunks.clear()
    }

    private fun clearProbeState() {
        wireGeometryProbe = null
        protocolProbePending = false
        protocolProbeTimedOut = false
        protocolProbeStartedAt = null
        acceptedBatchFrame = false
        pendingProbeChunks.clear()
        pendingReplayChunks = emptyList()
    }

    private fun bufferPendingChunk(chunk: BleRawNotificationChunk) {
        pendingProbeChunks += chunk
        val maxChunks = 48
        if (pendingProbeChunks.size > maxChunks) {
            pendingProbeChunks.subList(0, pendingProbeChunks.size - maxChunks).clear()
        }
    }

    private fun applyProtocolProbeTimeout(now: Double) {
        if (wireGeometryProbe == null || acceptedBatchFrame) return
        val started = protocolProbeStartedAt ?: return
        if (now - started < 2.0) return
        if (activeStreamProtocolMode == CupStreamProtocolMode.ADS1292R_120) return
        protocolProbeTimedOut = true
        protocolProbePending = true
    }

    private fun overrideGeometryMatches(mode: CupStreamProtocolMode): Boolean {
        val probe = NordicWireProbe(framesToLock = 1)
        pendingProbeChunks.forEach { probe.feed(it.bytes) }
        val hasExact120 = pendingProbeChunks.any(::isExactAds120Frame)
        val hasExact168 = pendingProbeChunks.any { chunk ->
            chunk.bytes.size == 168 &&
                chunk.bytes[0] == Ads1292rPacketProtocol.header[0] &&
                chunk.bytes[1] == Ads1292rPacketProtocol.header[1] &&
                chunk.bytes[166] == Ads1292rPacketProtocol.footer[0] &&
                chunk.bytes[167] == Ads1292rPacketProtocol.footer[1]
        }
        return when (mode) {
            CupStreamProtocolMode.ADS1292R_120 ->
                probe.result.locked == CupStreamProtocolMode.ADS1292R_120 ||
                    probe.result.votes120 > 0 ||
                    hasExact120
            CupStreamProtocolMode.SENSOR_PACKET_168 ->
                probe.result.locked == CupStreamProtocolMode.SENSOR_PACKET_168 ||
                    probe.result.votes168 > 0 ||
                    hasExact168
            CupStreamProtocolMode.BATCH_COMPATIBLE ->
                probe.result.locked == CupStreamProtocolMode.SENSOR_PACKET_168 ||
                    probe.result.votes168 > 0 ||
                    hasExact168 ||
                    probe.result.lockedGeometry == WireFrameGeometry.BYTES_168
        }
    }

    private fun isExactAds120Frame(chunk: BleRawNotificationChunk): Boolean {
        val bytes = chunk.bytes
        return bytes.size == Ads1292rPacketProtocol.frameLength &&
            bytes[0] == Ads1292rPacketProtocol.header[0] &&
            bytes[1] == Ads1292rPacketProtocol.header[1] &&
            bytes[118] == Ads1292rPacketProtocol.footer[0] &&
            bytes[119] == Ads1292rPacketProtocol.footer[1]
    }

    private fun incrementStaleCallback() {
        diagnostics = diagnostics.copy(ignoredStaleCallbackCount = diagnostics.ignoredStaleCallbackCount + 1)
        attemptDiagnostics = attemptDiagnostics.copy(
            ignoredStaleCallbackCount = attemptDiagnostics.ignoredStaleCallbackCount + 1,
        )
    }
}
