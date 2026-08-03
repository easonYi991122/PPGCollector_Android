package com.example.ppgcollector_android.core.ble

import com.example.ppgcollector_android.core.signal.StreamFreshness
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleCoreTest {
    private val deviceId = "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"

    @Test
    fun bringUpProfileAndPermissionBranchesMatchContract() {
        val profile = CupBleDeviceProfile.cupNusBringUp
        val fff0Profile = CupBleDeviceProfile.cupFff0BringUp
        assertEquals("CUP", profile.advertisedNamePrefix)
        assertEquals("6E400001-B5A3-F393-E0A9-E50E24DCCA9E", profile.serviceUuid)
        assertEquals("6E400002-B5A3-F393-E0A9-E50E24DCCA9E", profile.controlCharacteristicUuid)
        assertEquals("6E400003-B5A3-F393-E0A9-E50E24DCCA9E", profile.notifyCharacteristicUuid)
        assertTrue(profile.isPassiveStream)
        assertTrue(profile.acceptsAdvertisedName("CUP-SIM"))
        assertFalse(profile.acceptsAdvertisedName("OTHER-CUP"))
        assertFalse(profile.acceptsAdvertisedName(null))
        assertEquals("CUP", fff0Profile.advertisedNamePrefix)
        assertEquals("0000FFF0-0000-1000-8000-00805F9B34FB", fff0Profile.serviceUuid)
        assertEquals("0000FFF1-0000-1000-8000-00805F9B34FB", fff0Profile.notifyCharacteristicUuid)
        assertEquals("0000FFF2-0000-1000-8000-00805F9B34FB", fff0Profile.controlCharacteristicUuid)
        assertTrue(fff0Profile.isPassiveStream)
        assertEquals(
            listOf(profile, fff0Profile),
            CupBleDeviceProfile.supportedBringUpProfiles,
        )

        assertEquals(
            setOf(BlePermission.BLUETOOTH_SCAN, BlePermission.BLUETOOTH_CONNECT),
            BlePermissionPolicy.runtimePermissions(31),
        )
        assertEquals(
            setOf(BlePermission.BLUETOOTH_SCAN, BlePermission.BLUETOOTH_CONNECT, BlePermission.ACCESS_FINE_LOCATION),
            BlePermissionPolicy.runtimePermissions(33, neverForLocation = false),
        )
        assertEquals(setOf(BlePermission.ACCESS_FINE_LOCATION), BlePermissionPolicy.runtimePermissions(30))
        assertTrue(BlePermissionPolicy.needsLocationForScan(30))
        assertFalse(BlePermissionPolicy.needsLocationForScan(33))
    }

    @Test
    fun permissionPolicyCoversAllReleaseApiBoundaries() {
        val legacy = setOf(BlePermission.ACCESS_FINE_LOCATION)
        val nearby = setOf(BlePermission.BLUETOOTH_SCAN, BlePermission.BLUETOOTH_CONNECT)

        listOf(26, 30).forEach { apiLevel ->
            assertEquals(legacy, BlePermissionPolicy.runtimePermissions(apiLevel))
            assertTrue(BlePermissionPolicy.needsLocationForScan(apiLevel))
        }
        listOf(31, 33, 34, 35, 36, 37, 38).forEach { apiLevel ->
            assertEquals(nearby, BlePermissionPolicy.runtimePermissions(apiLevel))
            assertFalse(BlePermissionPolicy.needsLocationForScan(apiLevel))
            assertEquals(
                nearby + BlePermission.ACCESS_FINE_LOCATION,
                BlePermissionPolicy.runtimePermissions(apiLevel, neverForLocation = false),
            )
            assertTrue(BlePermissionPolicy.needsLocationForScan(apiLevel, neverForLocation = false))
        }
    }

    @Test
    fun freshnessWaitsForFirstValidFrameAndUsesInclusiveTwoSecondBoundary() {
        val tracker = CupStreamFreshnessTracker(2.0)
        assertEquals(StreamFreshness.UNAVAILABLE, tracker.freshness(10.0))
        tracker.start(10.0)
        assertEquals(StreamFreshness.WAITING, tracker.freshness(12.0))
        assertEquals(StreamFreshness.STALE, tracker.freshness(12.001))
        tracker.observeValidFrame(13.0)
        assertEquals(StreamFreshness.FRESH, tracker.freshness(15.0))
        assertEquals(StreamFreshness.STALE, tracker.freshness(15.001))
        tracker.reset()
        assertEquals(StreamFreshness.UNAVAILABLE, tracker.freshness(13.0))
    }

    @Test
    fun deadlineTrackerRejectsObsoleteStagesDevicesAndDoubleExpiration() {
        val tracker = BleConnectionDeadlineTracker()
        val first = tracker.arm(BleConnectionOperation.CONNECT, deviceId, 10.0, 12.0)
        val second = tracker.arm(BleConnectionOperation.SERVICE_DISCOVERY, deviceId, 11.0, 8.0)
        assertFalse(tracker.isCurrent(first))
        assertTrue(tracker.isCurrent(second))
        assertFalse(tracker.consumeExpiration(first, first.deadlineUptimeSeconds))
        assertFalse(tracker.consumeExpiration(second, second.deadlineUptimeSeconds - 0.001))
        assertTrue(tracker.consumeExpiration(second, second.deadlineUptimeSeconds))
        assertFalse(tracker.consumeExpiration(second, second.deadlineUptimeSeconds + 1.0))

        val otherDevice = tracker.arm(BleConnectionOperation.CONNECT, "11111111-2222-3333-4444-555555555555", 0.0, 12.0)
        tracker.cancel()
        assertFalse(tracker.isCurrent(otherDevice))
        assertFalse(tracker.consumeExpiration(otherDevice, 100.0))
    }

    @Test
    fun timeoutPolicyClampsOnlyInvalidDurationsAndFakeTransportPreservesCommandOrder() {
        val defaults = BleConnectionTimeoutPolicy.iosDefault
        assertEquals(12.0, defaults.timeout(BleConnectionOperation.CONNECT), 0.0)
        assertEquals(8.0, defaults.timeout(BleConnectionOperation.NOTIFICATION_SUBSCRIPTION), 0.0)
        val invalid = BleConnectionTimeoutPolicy(0.0, -1.0, 0.05, 0.1)
        assertEquals(0.1, invalid.timeout(BleConnectionOperation.CONNECT), 0.0)
        assertEquals(0.1, invalid.timeout(BleConnectionOperation.SERVICE_DISCOVERY), 0.0)
        assertEquals(0.1, invalid.timeout(BleConnectionOperation.CHARACTERISTIC_DISCOVERY), 0.0)

        val transport = FakeBleTransport()
        val received = mutableListOf<BleTransportEvent>()
        transport.eventHandler = received::add
        transport.activate()
        transport.startScanning()
        transport.connect(deviceId)
        transport.discoverServices(deviceId)
        transport.setNotificationsEnabled(true, CupBleDeviceProfile.cupNusBringUp.notifyCharacteristicUuid, deviceId)
        transport.emit(BleTransportEvent.Connected(deviceId))
        transport.emit(
            BleTransportEvent.Discovered(
                BleTransportDiscovery(deviceId, "CUP-SIM", -42, true, Instant.ofEpochSecond(1_800_000_000)),
            ),
        )
        assertEquals(5, transport.commands.size)
        assertEquals(FakeBleCommand.Activate, transport.commands.first())
        assertEquals(FakeBleCommand.SetNotifications(true, CupBleDeviceProfile.cupNusBringUp.notifyCharacteristicUuid, deviceId), transport.commands.last())
        assertEquals(2, received.size)
    }
}
