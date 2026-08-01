package com.example.ppgcollector_android.core.ble

import java.time.Instant

data class BleTransportDiscovery(
    val deviceId: String,
    val name: String?,
    val rssi: Int?,
    val isConnectable: Boolean,
    val seenAt: Instant,
)

data class BleTransportCharacteristic(
    val uuid: String,
    val properties: List<String>,
    val supportsNotifications: Boolean,
    val isNotifying: Boolean,
)

sealed interface BleTransportEvent {
    data class AvailabilityChanged(val availability: BluetoothAvailability) : BleTransportEvent
    data class Discovered(val discovery: BleTransportDiscovery) : BleTransportEvent
    data class Connected(val deviceId: String) : BleTransportEvent
    data class FailedToConnect(val deviceId: String, val message: String?) : BleTransportEvent
    data class Disconnected(val deviceId: String, val message: String?) : BleTransportEvent
    data class ServicesDiscovered(
        val deviceId: String,
        val serviceUuids: List<String>,
        val errorMessage: String?,
    ) : BleTransportEvent
    data class CharacteristicsDiscovered(
        val deviceId: String,
        val serviceUuid: String,
        val characteristics: List<BleTransportCharacteristic>,
        val errorMessage: String?,
    ) : BleTransportEvent
    data class NotificationStateChanged(
        val deviceId: String,
        val characteristicUuid: String,
        val isNotifying: Boolean,
        val errorMessage: String?,
    ) : BleTransportEvent
    data class ValueReceived(
        val deviceId: String,
        val characteristicUuid: String,
        val data: ByteArray?,
        val errorMessage: String?,
        val hostMonotonicNanos: Long? = null,
    ) : BleTransportEvent {
        override fun equals(other: Any?): Boolean = other is ValueReceived &&
            deviceId == other.deviceId && characteristicUuid == other.characteristicUuid &&
            data.contentEquals(other.data) && errorMessage == other.errorMessage &&
            hostMonotonicNanos == other.hostMonotonicNanos

        override fun hashCode(): Int = 31 * (31 * deviceId.hashCode() + characteristicUuid.hashCode()) +
            (data?.contentHashCode() ?: 0) + (errorMessage?.hashCode() ?: 0) +
            (hostMonotonicNanos?.hashCode() ?: 0)
    }
}

sealed interface FakeBleCommand {
    data object Activate : FakeBleCommand
    data object StartScanning : FakeBleCommand
    data object StopScanning : FakeBleCommand
    data class Connect(val deviceId: String) : FakeBleCommand
    data class Disconnect(val deviceId: String) : FakeBleCommand
    data class DiscoverServices(val deviceId: String) : FakeBleCommand
    data class DiscoverCharacteristics(
        val characteristicUuids: List<String>,
        val serviceUuid: String,
        val deviceId: String,
    ) : FakeBleCommand
    data class SetNotifications(
        val enabled: Boolean,
        val characteristicUuid: String,
        val deviceId: String,
    ) : FakeBleCommand
}

interface BleTransport {
    var eventHandler: ((BleTransportEvent) -> Unit)?
    fun activate()
    fun startScanning()
    fun stopScanning()
    fun connect(deviceId: String)
    fun disconnect(deviceId: String)
    fun discoverServices(deviceId: String)
    fun discoverCharacteristics(characteristicUuids: List<String>, serviceUuid: String, deviceId: String)
    fun setNotificationsEnabled(enabled: Boolean, characteristicUuid: String, deviceId: String)
}

class FakeBleTransport : BleTransport {
    override var eventHandler: ((BleTransportEvent) -> Unit)? = null
    val commands = mutableListOf<FakeBleCommand>()

    override fun activate() { commands.add(FakeBleCommand.Activate) }
    override fun startScanning() { commands.add(FakeBleCommand.StartScanning) }
    override fun stopScanning() { commands.add(FakeBleCommand.StopScanning) }
    override fun connect(deviceId: String) { commands.add(FakeBleCommand.Connect(deviceId)) }
    override fun disconnect(deviceId: String) { commands.add(FakeBleCommand.Disconnect(deviceId)) }
    override fun discoverServices(deviceId: String) { commands.add(FakeBleCommand.DiscoverServices(deviceId)) }
    override fun discoverCharacteristics(characteristicUuids: List<String>, serviceUuid: String, deviceId: String) =
        Unit.also { commands.add(FakeBleCommand.DiscoverCharacteristics(characteristicUuids, serviceUuid, deviceId)) }
    override fun setNotificationsEnabled(enabled: Boolean, characteristicUuid: String, deviceId: String) =
        Unit.also { commands.add(FakeBleCommand.SetNotifications(enabled, characteristicUuid, deviceId)) }

    fun emit(event: BleTransportEvent) {
        eventHandler?.invoke(event)
    }
}
