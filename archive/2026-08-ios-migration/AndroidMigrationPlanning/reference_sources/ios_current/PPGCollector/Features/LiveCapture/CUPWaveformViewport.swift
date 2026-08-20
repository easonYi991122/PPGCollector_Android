import Foundation

nonisolated struct CUPWaveformViewport: Equatable, Sendable {
    static let minimumZoom = 1.0
    static let maximumZoom = 80.0

    private(set) var zoomScale: Double
    private(set) var visibleStart: Double

    init(zoomScale: Double = 1, visibleStart: Double = 0) {
        self.zoomScale = min(
            max(Self.minimumZoom, zoomScale),
            Self.maximumZoom
        )
        self.visibleStart = max(0, visibleStart)
    }

    func visibleSampleCount(totalSampleCount: Int) -> Int {
        guard totalSampleCount > 0 else {
            return 0
        }
        guard totalSampleCount > 1 else {
            return 1
        }
        return min(
            totalSampleCount,
            max(2, Int(ceil(Double(totalSampleCount) / zoomScale)))
        )
    }

    func visibleRange(totalSampleCount: Int) -> Range<Int> {
        let count = visibleSampleCount(totalSampleCount: totalSampleCount)
        guard count > 0 else {
            return 0..<0
        }
        let maximumStart = max(0, totalSampleCount - count)
        let start = min(max(0, Int(visibleStart.rounded(.down))), maximumStart)
        return start..<(start + count)
    }

    mutating func setZoom(
        _ requestedZoom: Double,
        totalSampleCount: Int,
        anchorFraction: Double = 0.5
    ) {
        let anchor = min(max(0, anchorFraction), 1)
        let oldCount = visibleSampleCount(totalSampleCount: totalSampleCount)
        let anchorSample = visibleStart
            + Double(max(0, oldCount - 1)) * anchor

        zoomScale = min(
            max(Self.minimumZoom, requestedZoom),
            Self.maximumZoom
        )

        let newCount = visibleSampleCount(totalSampleCount: totalSampleCount)
        visibleStart = anchorSample
            - Double(max(0, newCount - 1)) * anchor
        clamp(totalSampleCount: totalSampleCount)
    }

    mutating func pan(
        sampleDelta: Double,
        totalSampleCount: Int
    ) {
        visibleStart += sampleDelta
        clamp(totalSampleCount: totalSampleCount)
    }

    mutating func clamp(totalSampleCount: Int) {
        let count = visibleSampleCount(totalSampleCount: totalSampleCount)
        let maximumStart = max(0, totalSampleCount - count)
        visibleStart = min(max(0, visibleStart), Double(maximumStart))
    }

    mutating func reset() {
        zoomScale = Self.minimumZoom
        visibleStart = 0
    }
}
