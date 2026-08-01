import Foundation
import Testing
@testable import PPGCollector

struct CUPWaveformSnapshotSchedulerTests {
    @Test
    func defaultCadencePublishesAtFiveHertz() {
        var scheduler = CUPWaveformSnapshotScheduler()

        let first = scheduler.consumeTick(at: 10)
        let early = scheduler.consumeTick(at: 10.199)
        let second = scheduler.consumeTick(at: 10.2)

        #expect(first)
        #expect(!early)
        #expect(second)
        #expect(scheduler.refreshRateHz == 5)
        #expect(abs(scheduler.refreshInterval - 0.2) < 1e-9)
        #expect(abs((scheduler.nextPublishUptime ?? 0) - 10.4) < 1e-9)
        #expect(scheduler.publicationCount == 2)
    }

    @Test
    func delayedTickSkipsMissedIntervalsWithoutBursting() {
        var scheduler = CUPWaveformSnapshotScheduler()
        _ = scheduler.consumeTick(at: 0)

        let delayed = scheduler.consumeTick(at: 0.65)
        let prematureCatchUp = scheduler.consumeTick(at: 0.79)
        let next = scheduler.consumeTick(at: 0.8)

        #expect(delayed)
        #expect(!prematureCatchUp)
        #expect(next)
        #expect(abs((scheduler.nextPublishUptime ?? 0) - 1.0) < 1e-9)
        #expect(scheduler.publicationCount == 3)
    }

    @Test
    func customRateAndResetAreDeterministic() {
        var scheduler = CUPWaveformSnapshotScheduler(refreshRateHz: 2)

        _ = scheduler.consumeTick(at: 4)
        let early = scheduler.consumeTick(at: 4.49)
        let due = scheduler.consumeTick(at: 4.5)
        scheduler.reset()

        #expect(!early)
        #expect(due)
        #expect(scheduler.refreshInterval == 0.5)
        #expect(scheduler.nextPublishUptime == nil)
        #expect(scheduler.publicationCount == 0)

        let invalid = CUPWaveformSnapshotScheduler(
            refreshRateHz: .infinity
        )
        #expect(
            invalid.refreshRateHz
                == CUPWaveformSnapshotScheduler.defaultRefreshRateHz
        )
    }

    @Test
    func snapshotOwnsAnImmutableSampleValue() {
        var source = [
            CUPPPGSample(red: 10, ir: 20),
            CUPPPGSample(red: 30, ir: 40)
        ]
        let snapshot = CUPWaveformSnapshot(
            samples: source,
            acceptedSampleCount: 2,
            publishedAtUptime: 1,
            publicationSequence: 1
        )

        source.removeAll()

        #expect(snapshot.samples.count == 2)
        #expect(snapshot.samples[1].ir == 40)
        #expect(snapshot.acceptedSampleCount == 2)
    }
}
