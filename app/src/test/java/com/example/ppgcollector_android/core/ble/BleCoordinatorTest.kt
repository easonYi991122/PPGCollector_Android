package com.example.ppgcollector_android.core.ble

import com.example.ppgcollector_android.core.protocol.CupBatchFrame
import com.example.ppgcollector_android.core.protocol.CupPpgSample
import com.example.ppgcollector_android.core.protocol.encodeCupBatchFrame
import com.example.ppgcollector_android.core.signal.StreamFreshness
import com.example.ppgcollector_android.data.session.CaptureStartContext
import com.example.ppgcollector_android.data.session.CaptureStartGate
import java.time.Instant
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleCoordinatorTest {
    private val deviceId = "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"

    @Test
    fun permissionResultSeamRequestsMissingPermissionsAndRecoversAfterGrant() {
        val seam = BlePermissionResultSeam(apiLevel = 33)
        assertEquals(
            setOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"),
            seam.permissionsToRequest(),
        )
        assertEquals(BlePermissionGateState.REQUEST_REQUIRED, seam.snapshot.gateState)
        val denied = seam.applyResult(mapOf("android.permission.BLUETOOTH_SCAN" to true))
        assertEquals(BlePermissionGateState.DENIED, denied.gateState)
        assertFalse(denied.canUseBle)
        val granted = seam.applyResult(
            mapOf(
                "android.permission.BLUETOOTH_SCAN" to true,
                "android.permission.BLUETOOTH_CONNECT" to true,
            ),
        )
        assertEquals(BlePermissionGateState.GRANTED, granted.gateState)
        assertTrue(granted.canUseBle)
        assertTrue(granted.missing.isEmpty())
    }

    @Test
    fun permissionResultSeamPreservesEarlierGrantWhenCallbackOnlyContainsMissingPermission() {
        val seam = BlePermissionResultSeam(apiLevel = 33)
        seam.permissionsToRequest()

        val firstGrant = seam.applyResult(
            mapOf("android.permission.BLUETOOTH_SCAN" to true),
        )
        assertEquals(setOf(BlePermission.BLUETOOTH_CONNECT), firstGrant.missing)
        assertFalse(firstGrant.canUseBle)

        val recovered = seam.applyResult(
            mapOf("android.permission.BLUETOOTH_CONNECT" to true),
        )
        assertEquals(BlePermissionGateState.GRANTED, recovered.gateState)
        assertTrue(recovered.canUseBle)
        assertTrue(recovered.missing.isEmpty())
    }

    @Test
    fun coordinatorGatesScanningAndConnectionAndPublishesOwnerSnapshot() {
        val transport = FakeBleTransport()
        val coordinator = BleCoordinator(transport, apiLevel = 33)
        assertEquals(BleCoordinatorAction.PERMISSION_REQUIRED, coordinator.startScanning())
        assertEquals(listOf(FakeBleCommand.Activate), transport.commands)
        assertTrue(coordinator.snapshot.permission.gateState == BlePermissionGateState.REQUEST_REQUIRED)

        coordinator.applyPermissionResult(
            mapOf(
                "android.permission.BLUETOOTH_SCAN" to true,
                "android.permission.BLUETOOTH_CONNECT" to true,
            ),
        )
        transport.emit(BleTransportEvent.AvailabilityChanged(BluetoothAvailability.POWERED_ON))
        assertEquals(BleCoordinatorAction.STARTED, coordinator.startScanning(clearPreviousResults = true))
        assertTrue(coordinator.snapshot.isScanning)
        transport.emit(
            BleTransportEvent.Discovered(
                BleTransportDiscovery(deviceId, "CUP-SIM", -40, true, Instant.EPOCH),
            ),
        )
        assertEquals(1, coordinator.snapshot.discoveredDevices.size)
        assertEquals(BleCoordinatorAction.STARTED, coordinator.connect(deviceId))
        assertEquals(BleConnectionPhase.Connecting(deviceId), coordinator.snapshot.phase)
        assertEquals(FakeBleCommand.Connect(deviceId), transport.commands.last())
    }

    @Test
    fun coordinatorReactivatesAdapterAfterPermissionsAreGranted() {
        val transport = FakeBleTransport()
        val coordinator = BleCoordinator(transport, apiLevel = 33)
        assertEquals(listOf(FakeBleCommand.Activate), transport.commands)

        assertEquals(BleCoordinatorAction.PERMISSION_REQUIRED, coordinator.startScanning())
        coordinator.applyPermissionResult(
            mapOf(
                "android.permission.BLUETOOTH_SCAN" to true,
                "android.permission.BLUETOOTH_CONNECT" to true,
            ),
        )
        assertEquals(BleCoordinatorAction.STARTED, coordinator.startScanning(clearPreviousResults = true))
        assertEquals(2, transport.commands.count { it == FakeBleCommand.Activate })

        transport.emit(BleTransportEvent.AvailabilityChanged(BluetoothAvailability.POWERED_ON))
        assertTrue(coordinator.snapshot.isScanning)
        assertEquals(1, transport.commands.count { it == FakeBleCommand.StartScanning })
    }

    @Test
    fun coordinatorPublishesScanTimeoutSoUiCanRetry() {
        val transport = FakeBleTransport()
        val coordinator = BleCoordinator(transport, apiLevel = 33)
        coordinator.applyPermissionResult(
            mapOf(
                "android.permission.BLUETOOTH_SCAN" to true,
                "android.permission.BLUETOOTH_CONNECT" to true,
            ),
        )
        transport.emit(BleTransportEvent.AvailabilityChanged(BluetoothAvailability.POWERED_ON))
        coordinator.startScanning(clearPreviousResults = true)
        assertTrue(coordinator.snapshot.isScanning)

        transport.emit(BleTransportEvent.ScanStopped(BleScanStopReason.TIMEOUT))

        assertFalse(coordinator.snapshot.isScanning)
        assertEquals("扫描超时，未发现 CUP 设备。", coordinator.snapshot.lastError)
    }

    @Test
    fun coordinatorForwardsRawChunkAndKeepsPermissionStateAfterRevocation() {
        val transport = FakeBleTransport()
        val coordinator = BleCoordinator(transport, apiLevel = 30)
        val chunks = mutableListOf<BleRawNotificationChunk>()
        coordinator.onRawChunk = chunks::add
        assertEquals(
            setOf("android.permission.ACCESS_FINE_LOCATION"),
            coordinator.permissionRequest(),
        )
        coordinator.applyPermissionResult(mapOf("android.permission.ACCESS_FINE_LOCATION" to true))
        assertTrue(coordinator.snapshot.permission.canUseBle)
        coordinator.applyPermissionResult(mapOf("android.permission.ACCESS_FINE_LOCATION" to false))
        assertFalse(coordinator.snapshot.permission.canUseBle)
        assertEquals(BlePermissionGateState.DENIED, coordinator.snapshot.permission.gateState)
    }

    @Test
    fun decodedPreviewFrameMakesCaptureFreshThenClockTickMarksItStale() {
        val transport = FakeBleTransport()
        var uptime = 10.0
        val coordinator = BleCoordinator(
            transport = transport,
            apiLevel = 33,
            uptimeSeconds = { uptime },
            hostMonotonicNanos = { (uptime * 1_000_000_000.0).toLong() },
        )
        try {
            coordinator.applyPermissionResult(
                mapOf(
                    "android.permission.BLUETOOTH_SCAN" to true,
                    "android.permission.BLUETOOTH_CONNECT" to true,
                ),
            )
            transport.emit(BleTransportEvent.AvailabilityChanged(BluetoothAvailability.POWERED_ON))
            transport.emit(
                BleTransportEvent.Discovered(
                    BleTransportDiscovery(deviceId, "CUP-SIM", -40, true, Instant.EPOCH),
                ),
            )
            assertEquals(BleCoordinatorAction.STARTED, coordinator.connect(deviceId))
            transport.emit(BleTransportEvent.Connected(deviceId))
            val profile = CupBleDeviceProfile.cupNusBringUp
            transport.emit(
                BleTransportEvent.ServicesDiscovered(deviceId, listOf(profile.serviceUuid), null),
            )
            assertEquals(profile, coordinator.snapshot.activeProfile)
            assertEquals(listOf(profile.serviceUuid), coordinator.snapshot.discoveredServiceUuids)
            transport.emit(
                BleTransportEvent.CharacteristicsDiscovered(
                    deviceId = deviceId,
                    serviceUuid = profile.serviceUuid,
                    characteristics = listOf(
                        BleTransportCharacteristic(
                            uuid = profile.notifyCharacteristicUuid,
                            properties = listOf("notify"),
                            supportsNotifications = true,
                            isNotifying = false,
                        ),
                    ),
                    errorMessage = null,
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
            assertEquals(StreamFreshness.WAITING, coordinator.snapshot.freshness)

            transport.emit(
                BleTransportEvent.ValueReceived(
                    deviceId = deviceId,
                    characteristicUuid = profile.notifyCharacteristicUuid,
                    data = encodeCupBatchFrame(
                        CupBatchFrame(
                            sequence = 1u,
                            samples = List(50) { index ->
                                CupPpgSample(
                                    red = (10_000 + index).toUInt(),
                                    ir = (20_000 + index).toUInt(),
                                )
                            },
                        ),
                    ),
                    errorMessage = null,
                ),
            )
            awaitTrue { coordinator.snapshot.freshness == StreamFreshness.FRESH }
            val sessionsRoot = Files.createTempDirectory("capture-gate-fresh")
            try {
                assertEquals(
                    null,
                    CaptureStartGate.validate(
                        CaptureStartContext(
                            isRecording = false,
                            phase = coordinator.snapshot.phase,
                            freshness = coordinator.snapshot.freshness,
                            sessionsRoot = sessionsRoot,
                            sessionName = "session_001",
                            availableBytes = Long.MAX_VALUE,
                        ),
                    ),
                )
            } finally {
                Files.deleteIfExists(sessionsRoot)
            }

            uptime = 12.1
            awaitTrue { coordinator.snapshot.freshness == StreamFreshness.STALE }
        } finally {
            coordinator.close()
        }
    }

    private fun awaitTrue(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline && !predicate()) {
            Thread.sleep(1)
        }
        assertTrue(predicate())
    }
}
