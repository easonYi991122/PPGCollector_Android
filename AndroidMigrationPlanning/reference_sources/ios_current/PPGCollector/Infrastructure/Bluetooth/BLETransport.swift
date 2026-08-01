@preconcurrency import CoreBluetooth
import Foundation

nonisolated struct BLETransportDiscovery: Equatable, Sendable {
    let deviceID: UUID
    let name: String?
    let rssi: Int?
    let isConnectable: Bool
    let seenAt: Date
}

nonisolated struct BLETransportCharacteristic:
    Equatable,
    Sendable
{
    let uuid: String
    let properties: [String]
    let supportsNotifications: Bool
    let isNotifying: Bool
}

nonisolated enum BLETransportEvent: Equatable, Sendable {
    case availabilityChanged(BluetoothAvailability)
    case discovered(BLETransportDiscovery)
    case connected(deviceID: UUID)
    case failedToConnect(deviceID: UUID, message: String?)
    case disconnected(deviceID: UUID, message: String?)
    case servicesDiscovered(
        deviceID: UUID,
        serviceUUIDs: [String],
        errorMessage: String?
    )
    case characteristicsDiscovered(
        deviceID: UUID,
        serviceUUID: String,
        characteristics: [BLETransportCharacteristic],
        errorMessage: String?
    )
    case notificationStateChanged(
        deviceID: UUID,
        characteristicUUID: String,
        isNotifying: Bool,
        errorMessage: String?
    )
    case valueReceived(
        deviceID: UUID,
        characteristicUUID: String,
        data: Data?,
        errorMessage: String?
    )
}

@MainActor
protocol BLETransporting: AnyObject {
    /// Installs the single ordered event sink before `activate()` is called.
    /// Implementations deliver events synchronously on the main actor.
    func setEventHandler(
        _ handler: @escaping @MainActor (BLETransportEvent) -> Void
    )
    func activate()
    func startScanning()
    func stopScanning()
    func connect(to deviceID: UUID)
    func disconnect(from deviceID: UUID)
    func discoverServices(for deviceID: UUID)
    func discoverCharacteristics(
        _ characteristicUUIDs: [String],
        for serviceUUID: String,
        deviceID: UUID
    )
    func setNotificationsEnabled(
        _ enabled: Bool,
        characteristicUUID: String,
        deviceID: UUID
    )
}

@MainActor
final class CoreBluetoothTransport:
    NSObject,
    BLETransporting
{
    private var eventHandler:
        (@MainActor (BLETransportEvent) -> Void)?
    private lazy var centralManager = CBCentralManager(
        delegate: self,
        queue: nil,
        options: [CBCentralManagerOptionShowPowerAlertKey: true]
    )
    private var peripheralsByID: [UUID: CBPeripheral] = [:]
    private var characteristicsByDeviceID:
        [UUID: [String: CBCharacteristic]] = [:]

    func setEventHandler(
        _ handler: @escaping @MainActor (BLETransportEvent) -> Void
    ) {
        eventHandler = handler
    }

    func activate() {
        _ = centralManager
    }

    func startScanning() {
        centralManager.scanForPeripherals(
            withServices: nil,
            options: [
                CBCentralManagerScanOptionAllowDuplicatesKey: false
            ]
        )
    }

    func stopScanning() {
        centralManager.stopScan()
    }

    func connect(to deviceID: UUID) {
        guard let peripheral = peripheralsByID[deviceID] else {
            emit(
                .failedToConnect(
                    deviceID: deviceID,
                    message: "设备已离开 CoreBluetooth 缓存。"
                )
            )
            return
        }
        peripheral.delegate = self
        characteristicsByDeviceID[deviceID] = nil
        if peripheral.state == .connected {
            emit(.connected(deviceID: deviceID))
        } else {
            centralManager.connect(peripheral, options: nil)
        }
    }

    func disconnect(from deviceID: UUID) {
        guard let peripheral = peripheralsByID[deviceID] else {
            emit(
                .disconnected(deviceID: deviceID, message: nil)
            )
            return
        }
        centralManager.cancelPeripheralConnection(peripheral)
    }

    func discoverServices(for deviceID: UUID) {
        guard let peripheral = peripheralsByID[deviceID] else {
            emit(
                .servicesDiscovered(
                    deviceID: deviceID,
                    serviceUUIDs: [],
                    errorMessage: "设备已离开 CoreBluetooth 缓存。"
                )
            )
            return
        }
        peripheral.discoverServices(nil)
    }

    func discoverCharacteristics(
        _ characteristicUUIDs: [String],
        for serviceUUID: String,
        deviceID: UUID
    ) {
        guard let peripheral = peripheralsByID[deviceID],
              let service = peripheral.services?.first(
                  where: {
                      $0.uuid
                          == CBUUID(string: serviceUUID)
                  }
              ) else {
            emit(
                .characteristicsDiscovered(
                    deviceID: deviceID,
                    serviceUUID: serviceUUID,
                    characteristics: [],
                    errorMessage: "目标服务已不可用。"
                )
            )
            return
        }
        peripheral.discoverCharacteristics(
            characteristicUUIDs.map(CBUUID.init(string:)),
            for: service
        )
    }

    func setNotificationsEnabled(
        _ enabled: Bool,
        characteristicUUID: String,
        deviceID: UUID
    ) {
        guard let peripheral = peripheralsByID[deviceID],
              let characteristic =
                  characteristicsByDeviceID[deviceID]?[
                      normalized(characteristicUUID)
                  ] else {
            emit(
                .notificationStateChanged(
                    deviceID: deviceID,
                    characteristicUUID: characteristicUUID,
                    isNotifying: false,
                    errorMessage: "目标通知特征已不可用。"
                )
            )
            return
        }
        peripheral.setNotifyValue(enabled, for: characteristic)
    }

    private func normalized(_ uuid: String) -> String {
        CBUUID(string: uuid).uuidString.uppercased()
    }

    private func emit(_ event: BLETransportEvent) {
        eventHandler?(event)
    }

    private func descriptor(
        for characteristic: CBCharacteristic
    ) -> BLETransportCharacteristic {
        BLETransportCharacteristic(
            uuid: characteristic.uuid.uuidString,
            properties: Self.propertyNames(
                characteristic.properties
            ),
            supportsNotifications:
                characteristic.properties.contains(.notify)
                || characteristic.properties.contains(.indicate),
            isNotifying: characteristic.isNotifying
        )
    }

    private static func propertyNames(
        _ properties: CBCharacteristicProperties
    ) -> [String] {
        let knownProperties: [
            (CBCharacteristicProperties, String)
        ] = [
            (.broadcast, "broadcast"),
            (.read, "read"),
            (.writeWithoutResponse, "writeWithoutResponse"),
            (.write, "write"),
            (.notify, "notify"),
            (.indicate, "indicate"),
            (.authenticatedSignedWrites, "signedWrite"),
            (.extendedProperties, "extended"),
            (.notifyEncryptionRequired, "notifyEncrypted"),
            (.indicateEncryptionRequired, "indicateEncrypted")
        ]
        let names = knownProperties.compactMap { property, name in
            properties.contains(property) ? name : nil
        }
        return names.isEmpty ? ["none"] : names
    }
}

extension CoreBluetoothTransport: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(
        _ central: CBCentralManager
    ) {
        if central.state != .poweredOn {
            characteristicsByDeviceID.removeAll()
        }
        emit(
            .availabilityChanged(
                BluetoothAvailability(central.state)
            )
        )
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        peripheralsByID[peripheral.identifier] = peripheral
        let advertisedName =
            advertisementData[CBAdvertisementDataLocalNameKey]
                as? String
        let isConnectable = (
            advertisementData[
                CBAdvertisementDataIsConnectable
            ] as? NSNumber
        )?.boolValue ?? true
        let rssi = RSSI.intValue == 127 ? nil : RSSI.intValue
        emit(
            .discovered(
                BLETransportDiscovery(
                    deviceID: peripheral.identifier,
                    name: advertisedName ?? peripheral.name,
                    rssi: rssi,
                    isConnectable: isConnectable,
                    seenAt: Date()
                )
            )
        )
    }

    func centralManager(
        _ central: CBCentralManager,
        didConnect peripheral: CBPeripheral
    ) {
        characteristicsByDeviceID[peripheral.identifier] = nil
        emit(
            .connected(deviceID: peripheral.identifier)
        )
    }

    func centralManager(
        _ central: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: Error?
    ) {
        characteristicsByDeviceID[peripheral.identifier] = nil
        emit(
            .failedToConnect(
                deviceID: peripheral.identifier,
                message: error?.localizedDescription
            )
        )
    }

    func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: Error?
    ) {
        characteristicsByDeviceID[peripheral.identifier] = nil
        emit(
            .disconnected(
                deviceID: peripheral.identifier,
                message: error?.localizedDescription
            )
        )
    }
}

extension CoreBluetoothTransport: CBPeripheralDelegate {
    func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverServices error: Error?
    ) {
        emit(
            .servicesDiscovered(
                deviceID: peripheral.identifier,
                serviceUUIDs:
                    (peripheral.services ?? [])
                    .map(\.uuid.uuidString)
                    .sorted(),
                errorMessage: error?.localizedDescription
            )
        )
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: Error?
    ) {
        let characteristics = service.characteristics ?? []
        var lookup =
            characteristicsByDeviceID[peripheral.identifier]
            ?? [:]
        for characteristic in characteristics {
            lookup[
                normalized(characteristic.uuid.uuidString)
            ] = characteristic
        }
        characteristicsByDeviceID[peripheral.identifier] = lookup
        emit(
            .characteristicsDiscovered(
                deviceID: peripheral.identifier,
                serviceUUID: service.uuid.uuidString,
                characteristics:
                    characteristics.map(descriptor(for:)),
                errorMessage: error?.localizedDescription
            )
        )
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateNotificationStateFor characteristic:
            CBCharacteristic,
        error: Error?
    ) {
        emit(
            .notificationStateChanged(
                deviceID: peripheral.identifier,
                characteristicUUID:
                    characteristic.uuid.uuidString,
                isNotifying: characteristic.isNotifying,
                errorMessage: error?.localizedDescription
            )
        )
    }

    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: Error?
    ) {
        emit(
            .valueReceived(
                deviceID: peripheral.identifier,
                characteristicUUID:
                    characteristic.uuid.uuidString,
                data: characteristic.value,
                errorMessage: error?.localizedDescription
            )
        )
    }
}
