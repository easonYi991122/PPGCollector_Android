import CoreBluetooth
import Testing
@testable import PPGCollector

struct CUPDeviceProfileTests {
    @Test
    func bringUpProfileMatchesTheObservedNUSServiceAndReferenceCharacteristics() {
        let profile = CUPDeviceProfile.cupNUSBringUp

        #expect(profile.advertisedNamePrefix == "CUP")
        #expect(
            profile.serviceUUID.uuidString
                == "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
        )
        #expect(
            profile.controlCharacteristicUUID.uuidString
                == "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
        )
        #expect(
            profile.notifyCharacteristicUUID.uuidString
                == "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
        )
    }
}
