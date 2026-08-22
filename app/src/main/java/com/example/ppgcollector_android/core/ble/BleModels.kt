package com.example.ppgcollector_android.core.ble

import com.example.ppgcollector_android.core.protocol.CupStreamProtocolMode
import java.time.Instant

/** Low-frequency counters used to classify live-stream faults without retaining per-frame logs. */
data class LiveStreamDiagnostics(
    val decodedFrameCount: Long = 0,
    val lastSequenceNumber: UInt? = null,
    val lastSequenceStep: Long? = null,
    val gapEventCount: Long = 0,
    val estimatedMissingFrameCount: Long = 0,
    val duplicateFrameCount: Long = 0,
    val outOfOrderFrameCount: Long = 0,
    val decoderDiscardedByteCount: Long = 0,
    val decoderInvalidFrameCount: Long = 0,
    val appDroppedChunkCount: Long = 0,
)

object BleAdvertisedIdentity {
    const val NORDIC_UART_SERVICE = "Nordic_UART_Service"

    fun isNordic(name: String?): Boolean = name == NORDIC_UART_SERVICE

    fun isCup(name: String?): Boolean = name != null && name.startsWith("CUP")
}

data class CupAdvertisedProtocolVariant(
    val exactAdvertisedName: String,
    val streamProtocolMode: CupStreamProtocolMode,
)

data class CupBleDeviceProfile(
    val identifier: String,
    val advertisedNamePrefix: String,
    val serviceUuid: String,
    val notifyCharacteristicUuid: String,
    val controlCharacteristicUuid: String,
    val isPassiveStream: Boolean,
    val defaultStreamProtocolMode: CupStreamProtocolMode =
        CupStreamProtocolMode.BATCH_COMPATIBLE,
    val additionalAdvertisedVariants: List<CupAdvertisedProtocolVariant> = emptyList(),
) {
    fun acceptsAdvertisedName(name: String?): Boolean =
        streamProtocolModeForAdvertisedName(name) != null

    fun streamProtocolModeForAdvertisedName(name: String?): CupStreamProtocolMode? {
        if (name == null) return null
        additionalAdvertisedVariants.firstOrNull { it.exactAdvertisedName == name }
            ?.let { return it.streamProtocolMode }
        return if (name.startsWith(advertisedNamePrefix)) defaultStreamProtocolMode else null
    }

    companion object {
        val cupNusBringUp = CupBleDeviceProfile(
            identifier = "cup-nus-bringup-0.1",
            advertisedNamePrefix = "CUP",
            serviceUuid = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E",
            notifyCharacteristicUuid = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E",
            controlCharacteristicUuid = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E",
            isPassiveStream = true,
            additionalAdvertisedVariants = listOf(
                CupAdvertisedProtocolVariant(
                    exactAdvertisedName = BleAdvertisedIdentity.NORDIC_UART_SERVICE,
                    streamProtocolMode = CupStreamProtocolMode.SENSOR_PACKET_168,
                ),
            ),
        )

        /**
         * Bring-up profile reported for the CUP_FEAE89AB24A9 hardware family.
         * FFF2 remains unused until a START/STOP command contract is captured.
         */
        val cupFff0BringUp = CupBleDeviceProfile(
            identifier = "cup-fff0-bringup-0.1",
            advertisedNamePrefix = "CUP",
            serviceUuid = "0000FFF0-0000-1000-8000-00805F9B34FB",
            notifyCharacteristicUuid = "0000FFF1-0000-1000-8000-00805F9B34FB",
            controlCharacteristicUuid = "0000FFF2-0000-1000-8000-00805F9B34FB",
            // This is the safe app policy for bring-up, not firmware proof that
            // the device will always begin streaming without a control write.
            isPassiveStream = true,
        )

        val supportedBringUpProfiles: List<CupBleDeviceProfile> = listOf(
            cupNusBringUp,
            cupFff0BringUp,
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

enum class BleMtuNegotiationStatus {
    NOT_REQUESTED,
    REQUESTING,
    NEGOTIATED,
    FALLBACK,
}

data class BleMtuSnapshot(
    val requestedMtu: Int = CupBleGattStateMachine.REQUESTED_ATT_MTU,
    val negotiatedMtu: Int? = null,
    val status: BleMtuNegotiationStatus = BleMtuNegotiationStatus.NOT_REQUESTED,
    val message: String? = null,
)

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

    data class NegotiatingMtu(override val deviceId: String) : BleConnectionPhase {
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
    val streamProtocolMode: CupStreamProtocolMode = CupStreamProtocolMode.BATCH_COMPATIBLE,
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
