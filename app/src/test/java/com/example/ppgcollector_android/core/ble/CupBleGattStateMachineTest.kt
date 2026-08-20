package com.example.ppgcollector_android.core.ble

import com.example.ppgcollector_android.core.protocol.Ads1292rPacket
import com.example.ppgcollector_android.core.protocol.Ads1292rPacketProtocol
import com.example.ppgcollector_android.core.protocol.CupBatchFrame
import com.example.ppgcollector_android.core.protocol.CupBatchProtocolV1
import com.example.ppgcollector_android.core.protocol.CupPpgSample
import com.example.ppgcollector_android.core.protocol.CupStreamProtocolMode
import com.example.ppgcollector_android.core.protocol.CupWireFrameProfile
import com.example.ppgcollector_android.core.protocol.encodeCupBatchFrame
import com.example.ppgcollector_android.core.protocol.encodeCupSensorPacketFrame
import com.example.ppgcollector_android.core.signal.StreamFreshness
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CupBleGattStateMachineTest {
    private val profile = CupBleDeviceProfile.cupNusBringUp
    private val fff0Profile = CupBleDeviceProfile.cupFff0BringUp
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
        val failureMessage = (owner.phase as BleConnectionPhase.Failed).message
        assertTrue(failureMessage.contains("0000180D"))
        assertTrue(failureMessage.contains(fff0Profile.serviceUuid))
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
    fun fff0HardwareSelectsMatchingProfileAndSubscribesWithoutControlWrite() {
        val transport = FakeBleTransport()
        val owner = readyToConnecting(transport)
        val chunks = mutableListOf<BleRawNotificationChunk>()
        owner.onRawChunk = chunks::add

        transport.emit(BleTransportEvent.Connected(deviceId))
        transport.emit(
            BleTransportEvent.ServicesDiscovered(
                deviceId,
                listOf("0000180A-0000-1000-8000-00805F9B34FB", fff0Profile.serviceUuid.lowercase()),
                null,
            ),
        )

        assertEquals(fff0Profile, owner.activeProfile)
        assertEquals(
            FakeBleCommand.DiscoverCharacteristics(
                listOf(fff0Profile.notifyCharacteristicUuid, fff0Profile.controlCharacteristicUuid),
                fff0Profile.serviceUuid.lowercase(),
                deviceId,
            ),
            transport.commands.last(),
        )

        transport.emit(
            BleTransportEvent.CharacteristicsDiscovered(
                deviceId,
                fff0Profile.serviceUuid,
                listOf(
                    BleTransportCharacteristic(
                        fff0Profile.notifyCharacteristicUuid,
                        listOf("notify"),
                        true,
                        false,
                    ),
                    BleTransportCharacteristic(
                        fff0Profile.controlCharacteristicUuid,
                        listOf("writeWithoutResponse"),
                        false,
                        false,
                    ),
                ),
                null,
            ),
        )
        assertEquals(
            FakeBleCommand.SetNotifications(true, fff0Profile.notifyCharacteristicUuid, deviceId),
            transport.commands.last(),
        )
        assertEquals(
            listOf("RX / control", "TX / notify"),
            owner.discoveredCharacteristics.mapNotNull(BleCharacteristicDiagnostic::role).sorted(),
        )
        assertTrue(
            transport.commands.none {
                it == FakeBleCommand.SetNotifications(true, fff0Profile.controlCharacteristicUuid, deviceId)
            },
        )

        transport.emit(
            BleTransportEvent.NotificationStateChanged(
                deviceId,
                fff0Profile.notifyCharacteristicUuid,
                true,
                null,
            ),
        )
        transport.emit(
            BleTransportEvent.ValueReceived(
                deviceId,
                fff0Profile.notifyCharacteristicUuid,
                byteArrayOf(1, 2, 3),
                null,
            ),
        )

        assertEquals(BleConnectionPhase.Receiving(deviceId), owner.phase)
        assertEquals(1, chunks.size)
        assertEquals(fff0Profile, owner.activeProfile)
    }

    @Test
    fun cccdFailureStopsSubscriptionAndDisconnectsWithoutEmittingRawData() {
        val transport = FakeBleTransport()
        val owner = readyToConnecting(transport)
        val chunks = mutableListOf<BleRawNotificationChunk>()
        owner.onRawChunk = chunks::add
        transport.emit(BleTransportEvent.Connected(deviceId))
        transport.emit(BleTransportEvent.ServicesDiscovered(deviceId, listOf(profile.serviceUuid), null))
        transport.emit(
            BleTransportEvent.CharacteristicsDiscovered(
                deviceId,
                profile.serviceUuid,
                listOf(BleTransportCharacteristic(profile.notifyCharacteristicUuid, listOf("notify"), true, false)),
                null,
            ),
        )
        transport.emit(
            BleTransportEvent.NotificationStateChanged(
                deviceId,
                profile.notifyCharacteristicUuid,
                isNotifying = false,
                errorMessage = "未获得蓝牙连接权限",
            ),
        )

        assertTrue(owner.phase is BleConnectionPhase.Failed)
        assertEquals(StreamFreshness.UNAVAILABLE, owner.freshness)
        assertTrue(transport.commands.contains(FakeBleCommand.Disconnect(deviceId)))
        transport.emit(
            BleTransportEvent.ValueReceived(
                deviceId,
                profile.notifyCharacteristicUuid,
                byteArrayOf(1),
                null,
            ),
        )
        assertTrue(chunks.isEmpty())
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
    fun twentyFakeGattLifecycleLoopsResetReceiverAndRejectLateCallbacks() {
        val transport = FakeBleTransport()
        val owner = CupBleGattStateMachine(transport)
        owner.handle(BleTransportEvent.AvailabilityChanged(BluetoothAvailability.POWERED_ON), 0.0)
        owner.startScanning(clearPreviousResults = true)

        repeat(20) { loopIndex ->
            owner.startScanning(clearPreviousResults = true)
            transport.emit(
                BleTransportEvent.Discovered(
                    BleTransportDiscovery(deviceId, "CUP-SIM", -42, true, Instant.EPOCH),
                ),
            )
            assertTrue("connect loop $loopIndex", owner.connect(deviceId))
            val generation = owner.connectionGeneration
            assertEquals((loopIndex + 1).toLong(), generation)

            transport.emit(BleTransportEvent.Connected(deviceId))
            transport.emit(BleTransportEvent.ServicesDiscovered(deviceId, listOf(profile.serviceUuid), null))
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
            transport.emit(BleTransportEvent.NotificationStateChanged(deviceId, profile.notifyCharacteristicUuid, true, null))
            assertEquals(BleConnectionPhase.Subscribed(deviceId), owner.phase)

            owner.handle(
                BleTransportEvent.ValueReceived(deviceId, profile.notifyCharacteristicUuid, byteArrayOf(loopIndex.toByte()), null),
                nowUptimeSeconds = loopIndex.toDouble(),
                callbackGeneration = generation,
            )
            assertEquals(BleConnectionPhase.Receiving(deviceId), owner.phase)

            assertTrue(owner.disconnect())
            transport.emit(BleTransportEvent.Disconnected(deviceId, null))
            assertEquals(BleConnectionPhase.Idle, owner.phase)
            assertEquals(StreamFreshness.UNAVAILABLE, owner.freshness)

            val staleBefore = owner.diagnostics.ignoredStaleCallbackCount
            owner.handle(
                BleTransportEvent.ValueReceived(deviceId, profile.notifyCharacteristicUuid, byteArrayOf(99), null),
                nowUptimeSeconds = loopIndex.toDouble(),
                callbackGeneration = generation - 1,
            )
            assertEquals(staleBefore + 1, owner.diagnostics.ignoredStaleCallbackCount)
            assertEquals(BleConnectionPhase.Idle, owner.phase)
        }

        assertEquals(20, transport.commands.count { it is FakeBleCommand.Connect })
        assertEquals(20, transport.commands.count { it is FakeBleCommand.Disconnect })
        assertEquals(20, transport.commands.count { it == FakeBleCommand.SetNotifications(true, profile.notifyCharacteristicUuid, deviceId) })
        assertEquals(20, transport.commands.count { it == FakeBleCommand.SetNotifications(false, profile.notifyCharacteristicUuid, deviceId) })
        assertTrue(transport.commands.none { it == FakeBleCommand.SetNotifications(true, profile.controlCharacteristicUuid, deviceId) })
        assertTrue(transport.commands.none { it is FakeBleCommand.Activate })
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

    @Test
    fun scanTimeoutStopsOwnerAndAllowsExplicitRetry() {
        val transport = FakeBleTransport()
        val owner = CupBleGattStateMachine(transport)
        owner.handle(BleTransportEvent.AvailabilityChanged(BluetoothAvailability.POWERED_ON), 0.0)

        owner.startScanning(clearPreviousResults = true)
        assertTrue(owner.isScanning)
        transport.emit(BleTransportEvent.ScanStopped(BleScanStopReason.TIMEOUT))

        assertFalse(owner.isScanning)
        assertEquals("扫描超时，未发现 CUP 设备。", owner.lastError)
        assertEquals(1, transport.commands.count { it == FakeBleCommand.StartScanning })

        owner.startScanning(clearPreviousResults = true)
        assertTrue(owner.isScanning)
        assertEquals(null, owner.lastError)
        assertEquals(2, transport.commands.count { it == FakeBleCommand.StartScanning })
    }

    @Test
    fun scanFailureStopsOwnerWithoutChangingAdapterAvailability() {
        val transport = FakeBleTransport()
        val owner = CupBleGattStateMachine(transport)
        owner.handle(BleTransportEvent.AvailabilityChanged(BluetoothAvailability.POWERED_ON), 0.0)
        owner.startScanning(clearPreviousResults = true)

        transport.emit(
            BleTransportEvent.ScanStopped(
                BleScanStopReason.PLATFORM_FAILURE,
                "蓝牙扫描失败（code=2），请重试。",
            ),
        )

        assertFalse(owner.isScanning)
        assertEquals(BluetoothAvailability.POWERED_ON, owner.availability)
        assertEquals("蓝牙扫描失败（code=2），请重试。", owner.lastError)
    }

    @Test
    fun cupAdvertised120FramesLockAdsWithoutWaitingForTimeout() {
        val transport = FakeBleTransport()
        val owner = subscribed(transport, "CUP-SIM")
        val delivered = mutableListOf<BleRawNotificationChunk>()
        owner.onRawChunk = delivered::add
        assertEquals(CupStreamProtocolMode.BATCH_COMPATIBLE, owner.activeStreamProtocolMode)
        assertFalse(owner.protocolProbePending)
        repeat(3) { index ->
            owner.handle(
                BleTransportEvent.ValueReceived(
                    deviceId,
                    profile.notifyCharacteristicUuid,
                    Ads1292rPacketProtocol.encode(adsPacket(index.toUInt())),
                    null,
                ),
                nowUptimeSeconds = index * 0.04,
            )
        }
        assertEquals(CupStreamProtocolMode.ADS1292R_120, owner.activeStreamProtocolMode)
        assertFalse(owner.protocolProbePending)
        assertFalse(owner.protocolProbeTimedOut)
        val replay = owner.takeProtocolReplay()
        assertEquals(3, replay.size)
        assertTrue(replay.all { it.streamProtocolMode == CupStreamProtocolMode.ADS1292R_120 })
        assertEquals(3, delivered.size)
        assertTrue(delivered.all { it.streamProtocolMode == CupStreamProtocolMode.BATCH_COMPATIBLE })
    }

    @Test
    fun cupAdvertisedBatchStaysBatchAndNeverShowsOverride() {
        val transport = FakeBleTransport()
        val owner = subscribed(transport, "CUP-SIM")
        val chunks = mutableListOf<BleRawNotificationChunk>()
        owner.onRawChunk = chunks::add
        owner.handle(
            BleTransportEvent.ValueReceived(
                deviceId,
                profile.notifyCharacteristicUuid,
                encodeCupBatchFrame(batchFrame(1u)),
                null,
            ),
            nowUptimeSeconds = 0.1,
        )
        assertEquals(CupStreamProtocolMode.BATCH_COMPATIBLE, owner.activeStreamProtocolMode)
        assertEquals(1, chunks.size)
        assertFalse(owner.protocolProbePending)
        assertFalse(owner.protocolProbeTimedOut)
        owner.markValidFrame(0.2)
        assertEquals(StreamFreshness.FRESH, owner.freshness)
        assertFalse(owner.protocolProbePending)
        owner.handle(
            BleTransportEvent.ValueReceived(
                deviceId,
                profile.notifyCharacteristicUuid,
                encodeCupBatchFrame(batchFrame(2u)),
                null,
            ),
            nowUptimeSeconds = 2.5,
        )
        assertEquals(CupStreamProtocolMode.BATCH_COMPATIBLE, owner.activeStreamProtocolMode)
        assertFalse(owner.protocolProbeTimedOut)
    }

    @Test
    fun cupProbeTimeoutShowsPendingWithoutLocking120() {
        val transport = FakeBleTransport()
        val owner = subscribed(transport, "CUP-SIM")
        owner.handle(
            BleTransportEvent.ValueReceived(
                deviceId,
                profile.notifyCharacteristicUuid,
                byteArrayOf(1, 2, 3),
                null,
            ),
            nowUptimeSeconds = 0.1,
        )
        assertFalse(owner.protocolProbePending)
        assertTrue(owner.pollProtocolProbe(2.0))
        assertTrue(owner.protocolProbePending)
        assertTrue(owner.protocolProbeTimedOut)
        assertEquals(CupStreamProtocolMode.BATCH_COMPATIBLE, owner.activeStreamProtocolMode)
        assertEquals(StreamFreshness.WAITING, owner.freshness)
    }

    @Test
    fun selectStreamProtocolHonorsIdentityMapping() {
        val cup = subscribed(FakeBleTransport(), "CUP-SIM")
        cup.handle(
            BleTransportEvent.ValueReceived(
                deviceId,
                profile.notifyCharacteristicUuid,
                Ads1292rPacketProtocol.encode(adsPacket(0u)),
                null,
            ),
            0.1,
        )
        cup.handle(
            BleTransportEvent.ValueReceived(
                deviceId,
                profile.notifyCharacteristicUuid,
                Ads1292rPacketProtocol.encode(adsPacket(1u)),
                null,
            ),
            0.2,
        )
        assertTrue(cup.pollProtocolProbe(2.0))
        assertFalse(cup.selectStreamProtocol(CupStreamProtocolMode.SENSOR_PACKET_168))
        assertTrue(cup.selectStreamProtocol(CupStreamProtocolMode.ADS1292R_120))
        assertEquals(CupStreamProtocolMode.ADS1292R_120, cup.activeStreamProtocolMode)

        val cupBatch = subscribed(FakeBleTransport(), "CUP-SIM")
        cupBatch.handle(
            BleTransportEvent.ValueReceived(
                deviceId,
                profile.notifyCharacteristicUuid,
                encodeCupBatchFrame(batchFrame(1u)),
                null,
            ),
            0.1,
        )
        assertTrue(cupBatch.pollProtocolProbe(2.0))
        assertTrue(cupBatch.selectStreamProtocol(CupStreamProtocolMode.BATCH_COMPATIBLE))
        assertEquals(CupStreamProtocolMode.BATCH_COMPATIBLE, cupBatch.activeStreamProtocolMode)

        val nordic = subscribed(FakeBleTransport(), "Nordic_UART_Service")
        nordic.handle(
            BleTransportEvent.ValueReceived(
                deviceId,
                profile.notifyCharacteristicUuid,
                encodeCupSensorPacketFrame(sensorFrame(1u)),
                null,
            ),
            0.1,
        )
        assertFalse(nordic.selectStreamProtocol(CupStreamProtocolMode.BATCH_COMPATIBLE))
        assertTrue(nordic.selectStreamProtocol(CupStreamProtocolMode.SENSOR_PACKET_168))
        assertEquals(CupStreamProtocolMode.SENSOR_PACKET_168, nordic.activeStreamProtocolMode)
    }

    private fun subscribed(transport: FakeBleTransport, advertisedName: String): CupBleGattStateMachine {
        val owner = CupBleGattStateMachine(transport)
        owner.handle(BleTransportEvent.AvailabilityChanged(BluetoothAvailability.POWERED_ON), 0.0)
        owner.handle(
            BleTransportEvent.Discovered(BleTransportDiscovery(deviceId, advertisedName, -42, true, Instant.EPOCH)),
            0.0,
        )
        assertTrue(owner.connect(deviceId))
        transport.emit(BleTransportEvent.Connected(deviceId))
        transport.emit(BleTransportEvent.ServicesDiscovered(deviceId, listOf(profile.serviceUuid), null))
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
        transport.emit(BleTransportEvent.NotificationStateChanged(deviceId, profile.notifyCharacteristicUuid, true, null))
        return owner
    }

    private fun adsPacket(sequence: UInt) = Ads1292rPacket(
        sequenceNumber = sequence,
        ecg = List(20) { it.toUInt() },
        red = List(4) { 100u + it.toUInt() },
        ir = List(4) { 200u + it.toUInt() },
    )

    private fun batchFrame(sequence: UByte) = CupBatchFrame(
        sequence = sequence,
        samples = List(CupBatchProtocolV1.samplesPerFrame) { index ->
            CupPpgSample((10_000 + index).toUInt(), (20_000 + index).toUInt())
        },
    )

    private fun sensorFrame(sequence: UInt) = CupBatchFrame(
        sequence = sequence.toUByte(),
        sequenceNumber = sequence,
        wireProfile = CupWireFrameProfile.SENSOR_PACKET_168,
        samples = List(20) { index -> CupPpgSample(45_000u + index.toUInt(), 52_000u + index.toUInt()) },
    )

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
