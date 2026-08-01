import CoreBluetooth
import Foundation

/// Bring-up profile identified from the supplied CUP-SIM service screenshot.
///
/// The UUID layout matches the NUS transport in the imported firmware and
/// Python references. Stream control remains passive until the target board's
/// START/STOP contract is captured and confirmed.
struct CUPDeviceProfile: Equatable, Sendable {
    let identifier: String
    let advertisedNamePrefix: String
    let serviceUUIDString: String
    let notifyCharacteristicUUIDString: String
    let controlCharacteristicUUIDString: String

    var serviceUUID: CBUUID {
        CBUUID(string: serviceUUIDString)
    }

    var notifyCharacteristicUUID: CBUUID {
        CBUUID(string: notifyCharacteristicUUIDString)
    }

    var controlCharacteristicUUID: CBUUID {
        CBUUID(string: controlCharacteristicUUIDString)
    }

    nonisolated static let cupNUSBringUp = CUPDeviceProfile(
        identifier: "cup-nus-bringup-0.1",
        advertisedNamePrefix: "CUP",
        serviceUUIDString: "6E400001-B5A3-F393-E0A9-E50E24DCCA9E",
        notifyCharacteristicUUIDString: "6E400003-B5A3-F393-E0A9-E50E24DCCA9E",
        controlCharacteristicUUIDString: "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
    )
}
