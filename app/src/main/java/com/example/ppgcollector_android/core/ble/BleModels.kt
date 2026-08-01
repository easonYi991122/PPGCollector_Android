package com.example.ppgcollector_android.core.ble

import java.time.Instant

data class CupBleDeviceProfile(
    val identifier: String,
    val advertisedNamePrefix: String,
    val serviceUuid: String,
    val notifyCharacteristicUuid: String,
    val controlCharacteristicUuid: String,
    val isPassiveStream: Boolean,
) {
    fun acceptsAdvertisedName(name: String?): Boolean =
        name?.startsWith(advertisedNamePrefix) == true

    companion object {
        val cupNusBringUp = CupBleDeviceProfile(
            identifier = "cup-nus-bringup-0.1",
            advertisedNamePrefix = "CUP",
            serviceUuid = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E",
            notifyCharacteristicUuid = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E",
            controlCharacteristicUuid = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E",
            isPassiveStream = true,
        )
    }
}

enum class BluetoothAvailability(val title: String) {
    UNKNOWN("正在检查蓝牙"),
    RESETTING("蓝牙正在重置"),
    UNSUPPORTED("此设备不支持蓝牙低功耗"),
    UNAUTHORIZED("未获得蓝牙权限"),
    POWERED_OFF("蓝牙已关闭"),
    POWERED_ON("蓝牙可用"),
}

sealed interface BleConnectionPhase {
    val deviceId: String?
    val isBusy: Boolean
    val isReadyToDisconnect: Boolean

    data object Idle : BleConnectionPhase {
        override val deviceId = null
        override val isBusy = false
        override val isReadyToDisconnect = false
    }

    data class Connecting(override val deviceId: String) : BleConnectionPhase {
        override val isBusy = true
        override val isReadyToDisconnect = false
    }

    data class DiscoveringServices(override val deviceId: String) : BleConnectionPhase {
        override val isBusy = true
        override val isReadyToDisconnect = false
    }

    data class DiscoveringCharacteristics(override val deviceId: String) : BleConnectionPhase {
        override val isBusy = true
        override val isReadyToDisconnect = false
    }

    data class Subscribing(override val deviceId: String) : BleConnectionPhase {
        override val isBusy = true
        override val isReadyToDisconnect = false
    }

    data class Subscribed(override val deviceId: String) : BleConnectionPhase {
        override val isBusy = false
        override val isReadyToDisconnect = true
    }

    data class Receiving(override val deviceId: String) : BleConnectionPhase {
        override val isBusy = false
        override val isReadyToDisconnect = true
    }

    data class Disconnecting(override val deviceId: String) : BleConnectionPhase {
        override val isBusy = true
        override val isReadyToDisconnect = false
    }

    data class Failed(val message: String) : BleConnectionPhase {
        override val deviceId = null
        override val isBusy = false
        override val isReadyToDisconnect = false
    }
}

data class DiscoveredBleDevice(
    val id: String,
    val name: String,
    val rssi: Int?,
    val isConnectable: Boolean,
    val lastSeen: Instant,
)

data class BleCharacteristicDiagnostic(
    val uuid: String,
    val properties: List<String>,
    val role: String?,
    val isNotifying: Boolean,
) {
    val propertiesText: String
        get() = properties.joinToString(", ")
}

enum class BlePermission(val manifestName: String) {
    BLUETOOTH_SCAN("android.permission.BLUETOOTH_SCAN"),
    BLUETOOTH_CONNECT("android.permission.BLUETOOTH_CONNECT"),
    ACCESS_FINE_LOCATION("android.permission.ACCESS_FINE_LOCATION"),
}

object BlePermissionPolicy {
    fun runtimePermissions(apiLevel: Int, neverForLocation: Boolean = true): Set<BlePermission> =
        if (apiLevel >= 31) {
            buildSet {
                add(BlePermission.BLUETOOTH_SCAN)
                add(BlePermission.BLUETOOTH_CONNECT)
                if (!neverForLocation) add(BlePermission.ACCESS_FINE_LOCATION)
            }
        } else {
            setOf(BlePermission.ACCESS_FINE_LOCATION)
        }

    fun needsLocationForScan(apiLevel: Int, neverForLocation: Boolean = true): Boolean =
        apiLevel <= 30 || !neverForLocation
}
