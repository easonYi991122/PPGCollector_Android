package com.example.ppgcollector_android.core.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.Locale
import java.util.UUID

/**
 * Android platform adapter for [BleTransport].
 *
 * It owns one ordered callback handler, copies notification bytes before
 * posting, and never writes CUP control commands. Runtime permission checks
 * remain the caller's responsibility; SecurityException is surfaced as an
 * unauthorized availability event rather than escaping a system callback.
 */
@SuppressLint("MissingPermission")
class AndroidBleTransport(
    context: Context,
    private val profile: CupBleDeviceProfile = CupBleDeviceProfile.cupNusBringUp,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val monotonicNanos: () -> Long = System::nanoTime,
) : BleTransport {
    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter
    private val devicesById = mutableMapOf<String, BluetoothDevice>()
    private val gattsById = mutableMapOf<String, BluetoothGatt>()
    private val connectedIds = mutableSetOf<String>()
    private val pendingDescriptors = mutableMapOf<BluetoothGatt, PendingDescriptorWrite>()
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false

    override var eventHandler: ((BleTransportEvent) -> Unit)? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = result.scanRecord?.deviceName ?: runCatching { device.name }.getOrNull()
            val rssi = result.rssi
            val isConnectable = if (Build.VERSION.SDK_INT >= 26) result.isConnectable else true
            post {
                devicesById[device.address] = device
                emit(
                    BleTransportEvent.Discovered(
                        BleTransportDiscovery(
                            deviceId = device.address,
                            name = name,
                            rssi = rssi,
                            isConnectable = isConnectable,
                            seenAt = java.time.Instant.now(),
                        ),
                    ),
                )
            }
        }

        override fun onScanFailed(errorCode: Int) {
            post {
                scanning = false
                emit(BleTransportEvent.AvailabilityChanged(BluetoothAvailability.UNKNOWN))
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceId = gatt.device.address
            post {
                if (gattsById[deviceId] !== gatt) {
                    // A replacement GATT may have won the slot before this
                    // callback arrived; still close the stale platform object.
                    releaseGatt(deviceId, gatt, disconnect = false)
                    return@post
                }
                if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED &&
                    status == BluetoothGatt.GATT_SUCCESS
                ) {
                    connectedIds += deviceId
                    emit(BleTransportEvent.Connected(deviceId))
                } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                    val wasConnected = connectedIds.remove(deviceId)
                    gattsById.remove(deviceId)
                    pendingDescriptors.remove(gatt)
                    if (wasConnected) {
                        emit(
                            BleTransportEvent.Disconnected(
                                deviceId,
                                "GATT status=$status",
                            ),
                        )
                    } else {
                        emit(
                            BleTransportEvent.FailedToConnect(
                                deviceId,
                                "GATT status=$status",
                            ),
                        )
                    }
                    releaseGatt(deviceId, gatt, disconnect = false)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            post {
                if (!isCurrentGatt(gatt)) return@post
                val services = if (status == BluetoothGatt.GATT_SUCCESS) {
                    gatt.services.map(BluetoothGattService::getUuid).map(UUID::toString)
                } else emptyList()
                emit(
                    BleTransportEvent.ServicesDiscovered(
                        deviceId = gatt.device.address,
                        serviceUuids = services,
                        errorMessage = if (status == BluetoothGatt.GATT_SUCCESS) null else "GATT status=$status",
                    ),
                )
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            post {
                if (!isCurrentGatt(gatt)) return@post
                val pending = pendingDescriptors.remove(gatt) ?: return@post
                emit(
                    BleTransportEvent.NotificationStateChanged(
                        deviceId = pending.deviceId,
                        characteristicUuid = pending.characteristicUuid,
                        isNotifying = pending.enabled && status == BluetoothGatt.GATT_SUCCESS,
                        errorMessage = if (status == BluetoothGatt.GATT_SUCCESS) null else "GATT status=$status",
                    ),
                )
            }
        }

        @Deprecated("Used for API < 33 compatibility")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            emitCharacteristicValue(gatt, characteristic, characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            emitCharacteristicValue(gatt, characteristic, value)
        }

        private fun emitCharacteristicValue(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray?,
        ) {
            val copied = value?.copyOf()
            val timestamp = monotonicNanos()
            post {
                if (!isCurrentGatt(gatt)) return@post
                emit(
                    BleTransportEvent.ValueReceived(
                        deviceId = gatt.device.address,
                        characteristicUuid = characteristic.uuid.toString(),
                        data = copied,
                        errorMessage = null,
                        hostMonotonicNanos = timestamp,
                    ),
                )
            }
        }
    }

    override fun activate() {
        post {
            try {
                val adapter = bluetoothAdapter
                emit(
                    BleTransportEvent.AvailabilityChanged(
                        when {
                            adapter == null -> BluetoothAvailability.UNSUPPORTED
                            adapter.isEnabled -> BluetoothAvailability.POWERED_ON
                            else -> BluetoothAvailability.POWERED_OFF
                        },
                    ),
                )
            } catch (_: SecurityException) {
                emit(BleTransportEvent.AvailabilityChanged(BluetoothAvailability.UNAUTHORIZED))
            }
        }
    }

    override fun startScanning() {
        post {
            if (scanning) return@post
            try {
                val activeScanner = bluetoothAdapter?.bluetoothLeScanner
                if (activeScanner == null) {
                    emit(BleTransportEvent.AvailabilityChanged(BluetoothAvailability.POWERED_OFF))
                    return@post
                }
                scanner = activeScanner
                activeScanner.startScan(
                    null,
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                    scanCallback,
                )
                scanning = true
            } catch (_: SecurityException) {
                emit(BleTransportEvent.AvailabilityChanged(BluetoothAvailability.UNAUTHORIZED))
            }
        }
    }

    override fun stopScanning() {
        post {
            if (!scanning) return@post
            try {
                scanner?.stopScan(scanCallback)
            } catch (_: SecurityException) {
                emit(BleTransportEvent.AvailabilityChanged(BluetoothAvailability.UNAUTHORIZED))
            } finally {
                scanning = false
            }
        }
    }

    override fun connect(deviceId: String) {
        post {
            try {
                val device = devicesById[deviceId] ?: bluetoothAdapter?.getRemoteDevice(deviceId)
                if (device == null) {
                    emit(BleTransportEvent.FailedToConnect(deviceId, "设备不可用"))
                    return@post
                }
                gattsById.values.filter { it.device.address != deviceId }.forEach {
                    releaseGatt(it.device.address, it, disconnect = true)
                }
                val gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                if (gatt == null) {
                    emit(BleTransportEvent.FailedToConnect(deviceId, "无法创建 GATT"))
                } else {
                    gattsById[deviceId] = gatt
                }
            } catch (_: SecurityException) {
                emit(BleTransportEvent.FailedToConnect(deviceId, "未获得蓝牙连接权限"))
            } catch (error: IllegalArgumentException) {
                emit(BleTransportEvent.FailedToConnect(deviceId, error.message))
            }
        }
    }

    override fun disconnect(deviceId: String) {
        post {
            val gatt = gattsById[deviceId]
            if (gatt == null) {
                emit(BleTransportEvent.Disconnected(deviceId, null))
                return@post
            }
            try {
                gatt.disconnect()
            } catch (_: SecurityException) {
                releaseGatt(deviceId, gatt, disconnect = false)
                emit(BleTransportEvent.Disconnected(deviceId, "未获得蓝牙连接权限"))
            }
        }
    }

    override fun discoverServices(deviceId: String) {
        post {
            val gatt = gattsById[deviceId]
            if (gatt == null || !runCatching { gatt.discoverServices() }.getOrDefault(false)) {
                emit(BleTransportEvent.ServicesDiscovered(deviceId, emptyList(), "GATT service discovery unavailable"))
            }
        }
    }

    override fun discoverCharacteristics(
        characteristicUuids: List<String>,
        serviceUuid: String,
        deviceId: String,
    ) {
        post {
            val gatt = gattsById[deviceId]
            val service = gatt?.getService(UUID.fromString(serviceUuid))
            if (gatt == null || service == null) {
                emit(BleTransportEvent.CharacteristicsDiscovered(deviceId, serviceUuid, emptyList(), "GATT service unavailable"))
                return@post
            }
            val descriptions = characteristicUuids.mapNotNull { uuidText ->
                val characteristic = runCatching { service.getCharacteristic(UUID.fromString(uuidText)) }.getOrNull()
                    ?: return@mapNotNull null
                BleTransportCharacteristic(
                    uuid = characteristic.uuid.toString(),
                    properties = characteristicProperties(characteristic),
                    supportsNotifications = characteristic.properties and
                        (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0,
                    isNotifying = false,
                )
            }
            emit(BleTransportEvent.CharacteristicsDiscovered(deviceId, serviceUuid, descriptions, null))
        }
    }

    override fun setNotificationsEnabled(enabled: Boolean, characteristicUuid: String, deviceId: String) {
        post {
            val gatt = gattsById[deviceId]
            val characteristic = gatt?.services.orEmpty().asSequence()
                .flatMap { it.characteristics.asSequence() }
                .firstOrNull { it.uuid.toString().equals(characteristicUuid, ignoreCase = true) }
            if (gatt == null || characteristic == null) {
                emit(BleTransportEvent.NotificationStateChanged(deviceId, characteristicUuid, false, "GATT characteristic unavailable"))
                return@post
            }
            try {
                val changed = runCatching {
                    gatt.setCharacteristicNotification(characteristic, enabled)
                }.getOrDefault(false)
                val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
                if (!changed || descriptor == null) {
                    emit(BleTransportEvent.NotificationStateChanged(deviceId, characteristicUuid, false, "CCCD unavailable"))
                    return@post
                }
                val value = when {
                    !enabled -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                    characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ->
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    else -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                }
                pendingDescriptors[gatt] = PendingDescriptorWrite(deviceId, characteristicUuid, enabled)
                val accepted = if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = value
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
                if (!accepted) {
                    pendingDescriptors.remove(gatt)
                    emit(BleTransportEvent.NotificationStateChanged(deviceId, characteristicUuid, false, "CCCD write rejected"))
                }
            } catch (_: SecurityException) {
                pendingDescriptors.remove(gatt)
                emit(BleTransportEvent.NotificationStateChanged(deviceId, characteristicUuid, false, "未获得蓝牙连接权限"))
            }
        }
    }

    fun close() {
        post {
            stopScanning()
            pendingDescriptors.clear()
            gattsById.values.toList().forEach {
                releaseGatt(it.device.address, it, disconnect = true)
            }
        }
    }

    /** Releases all per-GATT state exactly once before a new owner can use the slot. */
    private fun releaseGatt(deviceId: String, gatt: BluetoothGatt, disconnect: Boolean) {
        val ownsSlot = gattsById[deviceId] === gatt
        if (ownsSlot) {
            gattsById.remove(deviceId)
            connectedIds.remove(deviceId)
        }
        pendingDescriptors.remove(gatt)
        if (disconnect) runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == mainHandler.looper) block() else mainHandler.post(block)
    }

    private fun emit(event: BleTransportEvent) {
        eventHandler?.invoke(event)
    }

    private fun isCurrentGatt(gatt: BluetoothGatt): Boolean = gattsById[gatt.device.address] === gatt

    private fun characteristicProperties(characteristic: BluetoothGattCharacteristic): List<String> {
        val properties = characteristic.properties
        val names = buildList {
            if (properties and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) add("broadcast")
            if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
            if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("writeWithoutResponse")
            if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
            if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
            if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
        }
        return if (names.isEmpty()) listOf("none") else names
    }

    private data class PendingDescriptorWrite(
        val deviceId: String,
        val characteristicUuid: String,
        val enabled: Boolean,
    )

    private companion object {
        val CLIENT_CHARACTERISTIC_CONFIG: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }
}
