import Testing
@testable import PPGCollector

struct CUPFrameSequenceTrackerTests {
    @Test
    func tracksContinuousFramesWrapGapDuplicateAndOutOfOrder() {
        var tracker = CUPFrameSequenceTracker()

        #expect(tracker.observe(254) == .first)
        #expect(tracker.observe(255) == .continuous)
        #expect(tracker.observe(0) == .continuous)
        #expect(tracker.observe(2) == .gap(missingFrames: 1))
        #expect(tracker.observe(2) == .duplicate)
        #expect(tracker.observe(1) == .outOfOrder)

        #expect(tracker.stats.receivedFrames == 6)
        #expect(tracker.stats.previous == 2)
        #expect(tracker.stats.missingFrames == 1)
        #expect(tracker.stats.missingSamples == 50)
        #expect(tracker.stats.duplicateFrames == 1)
        #expect(tracker.stats.outOfOrderFrames == 1)
    }

    @Test
    func resetStartsANewSequence() {
        var tracker = CUPFrameSequenceTracker()
        _ = tracker.observe(10)
        _ = tracker.observe(15)
        #expect(tracker.stats.missingFrames == 4)

        tracker.reset()
        #expect(tracker.stats == CUPSequenceStats())
        #expect(tracker.observe(200) == .first)
    }
}

