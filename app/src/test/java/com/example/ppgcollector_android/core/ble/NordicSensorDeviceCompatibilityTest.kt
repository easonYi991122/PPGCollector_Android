package com.example.ppgcollector_android.core.ble

import com.example.ppgcollector_android.core.protocol.CupBatchFrame
import com.example.ppgcollector_android.core.protocol.CupPpgSample
import com.example.ppgcollector_android.core.protocol.CupSensorPacketProtocolV1
import com.example.ppgcollector_android.core.protocol.CupStreamProtocolMode
import com.example.ppgcollector_android.core.protocol.CupWireFrameProfile
import com.example.ppgcollector_android.core.protocol.encodeCupSensorPacketFrame
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NordicSensorDeviceCompatibilityTest {
    private val profile = CupBleDeviceProfile.cupNusBringUp
    private val deviceId = "new-sensor-device"

    @Test
    fun exactNordicNameSelectsNusTransportAndSensorWireMode() {
        val transport = FakeBleTransport()
        val owner = CupBleGattStateMachine(transport)
        val chunks = mutableListOf<BleRawNotificationChunk>()
        owner.onRawChunk = chunks::add
        owner.handle(BleTransportEvent.AvailabilityChanged(BluetoothAvailability.POWERED_ON), 0.0)
        owner.startScanning(clearPreviousResults = true)

        transport.emit(discovery("Nordic_UART_Service_demo", "unrelated"))
        transport.emit(discovery("Nordic_UART_Service", deviceId))

        assertEquals(1, owner.discoveredDevices.size)
        assertEquals(
            CupStreamProtocolMode.SENSOR_PACKET_168,
            owner.discoveredDevices.single().streamProtocolMode,
        )
        assertTrue(owner.connect(deviceId))
        transport.emit(BleTransportEvent.Connected(deviceId))
        transport.emit(BleTransportEvent.ServicesDiscovered(deviceId, listOf(profile.serviceUuid), null))
        assertEquals(profile, owner.activeProfile)
        assertEquals(CupStreamProtocolMode.SENSOR_PACKET_168, owner.activeStreamProtocolMode)
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
        transport.emit(
            BleTransportEvent.NotificationStateChanged(
                deviceId,
                profile.notifyCharacteristicUuid,
                true,
                null,
            ),
        )
        transport.emit(
            BleTransportEvent.ValueReceived(
                deviceId,
                profile.notifyCharacteristicUuid,
                encodeCupSensorPacketFrame(sensorFrame(0x1234_5678u)),
                null,
                hostMonotonicNanos = 123L,
            ),
        )

        assertEquals(1, chunks.size)
        assertEquals(CupStreamProtocolMode.SENSOR_PACKET_168, chunks.single().streamProtocolMode)
        assertEquals(168, chunks.single().bytes.size)
    }

    @Test
    fun previewDecodesSensorModeWithoutChangingBatchDefault() {
        val callbacks = AtomicInteger()
        val runtime = BlePreviewRuntime(onAcceptedFrame = { callbacks.incrementAndGet() })
        try {
            runtime.reset(9, CupStreamProtocolMode.SENSOR_PACKET_168)
            assertTrue(
                runtime.offer(
                    BleRawNotificationChunk(
                        9,
                        123L,
                        encodeCupSensorPacketFrame(sensorFrame(0x1234_5678u)),
                        CupStreamProtocolMode.SENSOR_PACKET_168,
                    ),
                ),
            )
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline && callbacks.get() == 0) Thread.sleep(1)
            assertEquals(1, callbacks.get())
            assertEquals(20L, runtime.snapshot.value.processedSampleCount)
            assertEquals(null, runtime.snapshot.value.lastError)

        } finally {
            runtime.close()
        }
    }

    private fun discovery(name: String, id: String) = BleTransportEvent.Discovered(
        BleTransportDiscovery(id, name, -40, true, Instant.EPOCH),
    )

    private fun sensorFrame(sequence: UInt) = CupBatchFrame(
        sequence = sequence.toUByte(),
        sequenceNumber = sequence,
        wireProfile = CupWireFrameProfile.SENSOR_PACKET_168,
        samples = List(CupSensorPacketProtocolV1.samplesPerFrame) { index ->
            CupPpgSample(45_000u + index.toUInt(), 52_000u + index.toUInt())
        },
    )
}
