import Foundation

nonisolated struct CUPStreamFreshnessTracker: Sendable {
    let timeout: TimeInterval

    private var subscriptionStartedUptime: TimeInterval?
    private var lastValidFrameUptime: TimeInterval?

    init(timeout: TimeInterval) {
        self.timeout = timeout
    }

    mutating func start(at uptime: TimeInterval) {
        subscriptionStartedUptime = uptime
        lastValidFrameUptime = nil
    }

    mutating func observeValidFrame(at uptime: TimeInterval) {
        guard subscriptionStartedUptime != nil else {
            return
        }
        lastValidFrameUptime = uptime
    }

    mutating func reset() {
        subscriptionStartedUptime = nil
        lastValidFrameUptime = nil
    }

    func freshness(at uptime: TimeInterval) -> StreamFreshness {
        guard let subscriptionStartedUptime else {
            return .unavailable
        }
        if let lastValidFrameUptime {
            return uptime - lastValidFrameUptime <= timeout ? .fresh : .stale
        }
        return uptime - subscriptionStartedUptime <= timeout ? .waiting : .stale
    }
}
