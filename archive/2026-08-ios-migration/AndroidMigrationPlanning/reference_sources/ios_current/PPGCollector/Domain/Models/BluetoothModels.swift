import CoreBluetooth
import Foundation

nonisolated enum BluetoothAvailability: Equatable, Sendable {
    case unknown
    case resetting
    case unsupported
    case unauthorized
    case poweredOff
    case poweredOn

    init(_ state: CBManagerState) {
        switch state {
        case .unknown:
            self = .unknown
        case .resetting:
            self = .resetting
        case .unsupported:
            self = .unsupported
        case .unauthorized:
            self = .unauthorized
        case .poweredOff:
            self = .poweredOff
        case .poweredOn:
            self = .poweredOn
        @unknown default:
            self = .unknown
        }
    }

    var title: String {
        switch self {
        case .unknown:
            "正在检查蓝牙"
        case .resetting:
            "蓝牙正在重置"
        case .unsupported:
            "此设备不支持蓝牙低功耗"
        case .unauthorized:
            "未获得蓝牙权限"
        case .poweredOff:
            "蓝牙已关闭"
        case .poweredOn:
            "蓝牙可用"
        }
    }

    var systemImage: String {
        switch self {
        case .poweredOn:
            "bluetooth"
        case .unknown, .resetting:
            "hourglass"
        case .unsupported, .unauthorized, .poweredOff:
            "exclamationmark.triangle"
        }
    }
}

enum BLEConnectionPhase: Equatable, Sendable {
    case idle
    case connecting(UUID)
    case discoveringServices(UUID)
    case discoveringCharacteristics(UUID)
    case subscribing(UUID)
    case subscribed(UUID)
    case receiving(UUID)
    case disconnecting(UUID)
    case failed(String)

    var deviceID: UUID? {
        switch self {
        case let .connecting(id),
             let .discoveringServices(id),
             let .discoveringCharacteristics(id),
             let .subscribing(id),
             let .subscribed(id),
             let .receiving(id),
             let .disconnecting(id):
            id
        case .idle, .failed:
            nil
        }
    }

    var isBusy: Bool {
        switch self {
        case .connecting,
             .discoveringServices,
             .discoveringCharacteristics,
             .subscribing,
             .disconnecting:
            true
        case .idle, .subscribed, .receiving, .failed:
            false
        }
    }

    var isReadyToDisconnect: Bool {
        switch self {
        case .subscribed, .receiving:
            true
        case .idle,
             .connecting,
             .discoveringServices,
             .discoveringCharacteristics,
             .subscribing,
             .disconnecting,
             .failed:
            false
        }
    }
}

struct DiscoveredBLEDevice: Identifiable, Equatable, Sendable {
    let id: UUID
    var name: String
    var rssi: Int?
    var isConnectable: Bool
    var lastSeen: Date
}

struct BLECharacteristicDiagnostic: Identifiable, Equatable, Sendable {
    let uuid: String
    let properties: [String]
    let role: String?
    var isNotifying: Bool

    var id: String {
        uuid
    }

    var propertiesText: String {
        properties.joined(separator: ", ")
    }
}

nonisolated struct BLERuntimeResourceSnapshot:
    Equatable,
    Sendable
{
    let connectionDeadlineTaskActive: Bool
    let freshnessTaskActive: Bool
    let waveformSnapshotTaskActive: Bool
    let metricComputationTaskActive: Bool
    let metricCompletionTaskActive: Bool
    let decoderPendingBytes: Int
    let recentSampleCount: Int
    let metricBufferedSampleCount: Int

    var activeTaskCount: Int {
        [
            connectionDeadlineTaskActive,
            freshnessTaskActive,
            waveformSnapshotTaskActive,
            metricComputationTaskActive,
            metricCompletionTaskActive
        ].reduce(into: 0) {
            if $1 {
                $0 += 1
            }
        }
    }
}

nonisolated enum StreamFreshness: Equatable, Sendable {
    case unavailable
    case waiting
    case fresh
    case stale

    var title: String {
        switch self {
        case .unavailable:
            "不可用"
        case .waiting:
            "等待合法帧"
        case .fresh:
            "新鲜"
        case .stale:
            "已超时"
        }
    }
}
