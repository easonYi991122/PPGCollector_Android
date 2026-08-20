import Testing
@testable import PPGCollector

struct CUPStreamFreshnessTrackerTests {
    @Test
    func waitsForTheFirstValidFrameThenTimesOut() {
        var tracker = CUPStreamFreshnessTracker(timeout: 2)

        #expect(tracker.freshness(at: 10) == .unavailable)
        tracker.start(at: 10)
        #expect(tracker.freshness(at: 10) == .waiting)
        #expect(tracker.freshness(at: 12) == .waiting)
        #expect(tracker.freshness(at: 12.001) == .stale)
    }

    @Test
    func validFramesRefreshTheDeadline() {
        var tracker = CUPStreamFreshnessTracker(timeout: 2)
        tracker.start(at: 10)
        tracker.observeValidFrame(at: 11)

        #expect(tracker.freshness(at: 11) == .fresh)
        #expect(tracker.freshness(at: 13) == .fresh)
        #expect(tracker.freshness(at: 13.001) == .stale)

        tracker.observeValidFrame(at: 14)
        #expect(tracker.freshness(at: 14) == .fresh)
    }

    @Test
    func resetReturnsToUnavailable() {
        var tracker = CUPStreamFreshnessTracker(timeout: 2)
        tracker.start(at: 10)
        tracker.observeValidFrame(at: 11)
        tracker.reset()

        #expect(tracker.freshness(at: 11) == .unavailable)
    }
}
