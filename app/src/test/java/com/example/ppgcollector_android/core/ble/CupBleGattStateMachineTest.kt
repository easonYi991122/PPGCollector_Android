package com.example.ppgcollector_android.core.ble

import com.example.ppgcollector_android.core.signal.StreamFreshness
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CupBleGattStateMachineTest {
    private val profile = CupBleDeviceProfile.cupNusBringUp
    private val deviceId = "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"

    @Test
    fun happyPathRequiresTargetServiceNotifyAndSuccessfulCccdBeforeReceiving() {
        val transport = FakeBleTransport()
        val owner = CupBleGattStateMachine(transport)
        val chunks = mutableListOf<BleRawNotificationChunk>()
        owner.onRawChunk = chunks::add
        owner.handle(BleTransportEvent.AvailabilityChanged(BluetoothAvailability.POWERED_ON), 1.0)
        owner.startScanning(clearPreviousResults = true)
        transport.emit(
            BleTransportEvent.Discovered(
                BleTransportDiscovery("OTHER", "OTHER", -40, true, Instant.EPOCH),
            ),
        )
        transport.emit(
            BleTransportEvent.Discovered(
                BleTransportDiscovery(deviceId, "CUP-SIM", -50, true, Instant.EPOCH),
            ),
        )
        assertEquals(1, owner.discoveredDevices.size)
        assertTrue(owner.connect(deviceId))
        val generation = owner.connectionGeneration
        transport.emit(BleTransportEvent.Connected(deviceId))
        assertEquals(BleConnectionPhase.DiscoveringServices(deviceId), owner.phase)
        transport.emit(
            BleTransportEvent.ServicesDiscovered(deviceId, listOf("0000180D", profile.serviceUuid), null),
        )
        assertEquals(BleConnectionPhase.DiscoveringCharacteristics(deviceId), owner.phase)
        transport.emit(
            BleTransportEvent.CharacteristicsDiscovered(
                deviceId,
                profile.serviceUuid,
                listOf(
                    BleTransportCharacteristic(profile.notifyCharacteristicUuid, listOf("notify"), true, false),
                    BleTransportCharacteristic(profile.controlCharacteristicUuid, listOf("write"), false, false),
                ),
                null,
            ),
        )
        assertEquals(BleConnectionPhase.Subscribing(deviceId), owner.phase)
        assertEquals(FakeBleCommand.SetNotifications(true, profile.notifyCharacteristicUuid, deviceId), transport.commands.last())
        transport.emit(BleTransportEvent.NotificationStateChanged(deviceId, profile.notifyCharacteristicUuid, true, null))
        assertEquals(BleConnectionPhase.Subscribed(deviceId), owner.phase)
        assertEquals(StreamFreshness.WAITING, owner.freshness)

        val original = byteArrayOf(1, 2, 3)
        transport.emit(
            BleTransportEvent.ValueReceived(
                deviceId,
                profile.notifyCharacteristicUuid,
                original,
                null,
                hostMonotonicNanos = 123_456L,
            ),
        )
        original[0] = 99
        assertEquals(BleConnectionPhase.Receiving(deviceId), owner.phase)
        assertEquals(1, chunks.size)
        assertEquals(1, chunks.single().bytes[0].toInt())
        assertEquals(generation, chunks.single().connectionGeneration)
        assertEquals(123_456L, chunks.single().hostMonotonicNanos)
        assertEquals(StreamFreshness.WAITING, owner.freshness)
        owner.markValidFrame(2.0)
        assertEquals(StreamFreshness.FRESH, owner.freshness)
    }

    @Test
    fun missingServiceOrNonNotifyingCharacteristicFailsAndDisconnectsWithoutControlWrite() {
        val transport = FakeBleTransport()
        val owner = readyToConnecting(transport)
        transport.emit(BleTransportEvent.Connected(deviceId))
        transport.emit(BleTransportEvent.ServicesDiscovered(deviceId, listOf("0000180D"), null))
        assertTrue(owner.phase is BleConnectionPhase.Failed)
        assertTrue(transport.commands.contains(FakeBleCommand.Disconnect(deviceId)))

        val secondTransport = FakeBleTransport()
        val second = readyToConnecting(secondTransport)
        secondTransport.emit(BleTransportEvent.Connected(deviceId))
        secondTransport.emit(BleTransportEvent.ServicesDiscovered(deviceId, listOf(profile.serviceUuid), null))
        secondTransport.emit(
            BleTransportEvent.CharacteristicsDiscovered(
                deviceId,
                profile.serviceUuid,
                listOf(BleTransportCharacteristic(profile.notifyCharacteristicUuid, listOf("read"), false, false)),
                null,
            ),
        )
        assertTrue(second.phase is BleConnectionPhase.Failed)
        assertTrue(secondTransport.commands.contains(FakeBleCommand.Disconnect(deviceId)))
        assertTrue(secondTransport.commands.none { it is FakeBleCommand.SetNotifications })
    }

    @Test
    fun oldConnectionGenerationAndWrongPhaseCallbacksAreIgnored() {
        val transport = FakeBleTransport()
        val owner = readyToConnecting(transport)
        val oldGeneration = owner.connectionGeneration
        transport.emit(BleTransportEvent.Connected(deviceId))
        val before = owner.diagnostics.ignoredStaleCallbackCount
        owner.handle(
            BleTransportEvent.CharacteristicsDiscovered(deviceId, profile.serviceUuid, emptyList(), null),
            nowUptimeSeconds = 2.0,
            callbackGeneration = oldGeneration - 1,
        )
        assertEquals(before + 1, owner.diagnostics.ignoredStaleCallbackCount)
        assertEquals(BleConnectionPhase.DiscoveringServices(deviceId), owner.phase)

        transport.emit(BleTransportEvent.ValueReceived(deviceId, profile.notifyCharacteristicUuid, byteArrayOf(1), null))
        assertEquals(before + 2, owner.diagnostics.ignoredStaleCallbackCount)
    }

    @Test
    fun deadlinePollFailsCurrentStageAtBoundaryAndLeavesOldDeadlineObsolete() {
        val transport = FakeBleTransport()
        val owner = CupBleGattStateMachine(
            transport,
            timeoutPolicy = BleConnectionTimeoutPolicy(0.1, 0.1, 0.1, 0.1),
        )
        owner.handle(BleTransportEvent.AvailabilityChanged(BluetoothAvailability.POWERED_ON), 10.0)
        owner.handle(
            BleTransportEvent.Discovered(BleTransportDiscovery(deviceId, "CUP", null, true, Instant.EPOCH)),
            10.0,
        )
        assertTrue(owner.connect(deviceId))
        assertFalse(owner.pollDeadline(0.099))
        assertTrue(owner.pollDeadline(0.1))
        assertTrue(owner.phase is BleConnectionPhase.Failed)
        assertEquals(BleConnectionOperation.CONNECT, owner.attemptDiagnostics.lastTimedOutOperation)
        assertEquals(1, owner.attemptDiagnostics.timeoutCount)
        assertFalse(owner.pollDeadline(100.0))
    }

    private fun readyToConnecting(transport: FakeBleTransport): CupBleGattStateMachine {
        val owner = CupBleGattStateMachine(transport)
        owner.handle(BleTransportEvent.AvailabilityChanged(BluetoothAvailability.POWERED_ON), 0.0)
        owner.handle(
            BleTransportEvent.Discovered(BleTransportDiscovery(deviceId, "CUP-SIM", -42, true, Instant.EPOCH)),
            0.0,
        )
        assertTrue(owner.connect(deviceId))
        assertNotNull(owner.phase)
        return owner
    }
}
