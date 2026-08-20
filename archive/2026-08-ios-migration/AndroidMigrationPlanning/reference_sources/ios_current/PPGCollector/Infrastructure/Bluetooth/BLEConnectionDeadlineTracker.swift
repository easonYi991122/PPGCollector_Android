import Foundation

nonisolated enum BLEConnectionOperation:
    String,
    Equatable,
    Sendable
{
    case connect
    case serviceDiscovery
    case characteristicDiscovery
    case notificationSubscription

    var title: String {
        switch self {
        case .connect:
            "连接设备"
        case .serviceDiscovery:
            "发现服务"
        case .characteristicDiscovery:
            "发现特征"
        case .notificationSubscription:
            "订阅通知"
        }
    }
}

nonisolated struct BLEConnectionTimeoutPolicy:
    Equatable,
    Sendable
{
    let connectSeconds: TimeInterval
    let serviceDiscoverySeconds: TimeInterval
    let characteristicDiscoverySeconds: TimeInterval
    let notificationSubscriptionSeconds: TimeInterval

    static let iosDefault = BLEConnectionTimeoutPolicy(
        connectSeconds: 12,
        serviceDiscoverySeconds: 8,
        characteristicDiscoverySeconds: 8,
        notificationSubscriptionSeconds: 8
    )

    func timeout(
        for operation: BLEConnectionOperation
    ) -> TimeInterval {
        let configured = switch operation {
        case .connect:
            connectSeconds
        case .serviceDiscovery:
            serviceDiscoverySeconds
        case .characteristicDiscovery:
            characteristicDiscoverySeconds
        case .notificationSubscription:
            notificationSubscriptionSeconds
        }
        return max(0.1, configured)
    }
}

nonisolated struct BLEConnectionDeadline:
    Equatable,
    Sendable
{
    let operation: BLEConnectionOperation
    let deviceID: UUID
    let generation: UInt64
    let deadlineUptime: TimeInterval
}

/// Pure monotonic deadline state used to prevent an obsolete timeout from
/// failing a newer connection attempt.
nonisolated struct BLEConnectionDeadlineTracker: Sendable {
    private(set) var generation: UInt64 = 0
    private(set) var activeDeadline: BLEConnectionDeadline?

    mutating func arm(
        operation: BLEConnectionOperation,
        deviceID: UUID,
        now: TimeInterval,
        timeout: TimeInterval
    ) -> BLEConnectionDeadline {
        generation &+= 1
        let deadline = BLEConnectionDeadline(
            operation: operation,
            deviceID: deviceID,
            generation: generation,
            deadlineUptime: now + max(0.1, timeout)
        )
        activeDeadline = deadline
        return deadline
    }

    func isCurrent(
        _ deadline: BLEConnectionDeadline
    ) -> Bool {
        activeDeadline == deadline
            && deadline.generation == generation
    }

    mutating func consumeExpiration(
        _ deadline: BLEConnectionDeadline,
        now: TimeInterval
    ) -> Bool {
        guard isCurrent(deadline),
              now >= deadline.deadlineUptime else {
            return false
        }
        activeDeadline = nil
        generation &+= 1
        return true
    }

    mutating func cancel() {
        activeDeadline = nil
        generation &+= 1
    }
}

nonisolated struct BLEConnectionAttemptDiagnostics:
    Equatable,
    Sendable
{
    var attemptCount = 0
    var timeoutCount = 0
    var ignoredStaleCallbackCount = 0
    var activeOperation: BLEConnectionOperation?
    var lastTimedOutOperation: BLEConnectionOperation?
}
