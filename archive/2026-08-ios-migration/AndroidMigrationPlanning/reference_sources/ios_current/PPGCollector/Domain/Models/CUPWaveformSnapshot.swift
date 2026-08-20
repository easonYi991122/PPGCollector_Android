import Foundation

nonisolated struct CUPWaveformSnapshot: Equatable, Sendable {
    let samples: [CUPPPGSample]
    let acceptedSampleCount: Int
    let publishedAtUptime: TimeInterval?
    let publicationSequence: UInt64

    static let empty = CUPWaveformSnapshot(
        samples: [],
        acceptedSampleCount: 0,
        publishedAtUptime: nil,
        publicationSequence: 0
    )
}

nonisolated struct CUPWaveformSnapshotScheduler:
    Equatable,
    Sendable
{
    static let defaultRefreshRateHz = 5.0
    static let supportedRefreshRateHz = 1.0...60.0

    let refreshRateHz: Double
    private(set) var nextPublishUptime: TimeInterval?
    private(set) var publicationCount: UInt64 = 0

    var refreshInterval: TimeInterval {
        1 / refreshRateHz
    }

    init(refreshRateHz: Double = Self.defaultRefreshRateHz) {
        let requestedRate = refreshRateHz.isFinite
            ? refreshRateHz
            : Self.defaultRefreshRateHz
        self.refreshRateHz = min(
            max(
                requestedRate,
                Self.supportedRefreshRateHz.lowerBound
            ),
            Self.supportedRefreshRateHz.upperBound
        )
    }

    mutating func consumeTick(at uptime: TimeInterval) -> Bool {
        guard uptime.isFinite else {
            return false
        }

        guard let nextPublishUptime else {
            self.nextPublishUptime = uptime + refreshInterval
            publicationCount += 1
            return true
        }
        guard uptime + 1e-9 >= nextPublishUptime else {
            return false
        }

        let elapsed = max(0, uptime - nextPublishUptime)
        let skippedIntervals = floor(elapsed / refreshInterval)
        self.nextPublishUptime = nextPublishUptime
            + (skippedIntervals + 1) * refreshInterval
        publicationCount += 1
        return true
    }

    func timeUntilNextTick(at uptime: TimeInterval) -> TimeInterval {
        guard uptime.isFinite, let nextPublishUptime else {
            return 0
        }
        return max(0, nextPublishUptime - uptime)
    }

    mutating func reset() {
        nextPublishUptime = nil
        publicationCount = 0
    }
}
