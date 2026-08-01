import Foundation
import Testing
@testable import PPGCollector

struct PPGLiveMetricRuntimeTests {
    @Test
    func waitsForEightContinuousSecondsThenRunsAtOneHertz() throws {
        var scheduler = PPGLiveMetricWindowScheduler()
        let measuredAt = Date(timeIntervalSince1970: 1_800_000_000)

        for frameIndex in 0..<15 {
            let request = scheduler.ingest(
                decodedFrames: [
                    frame(
                        sequence: UInt8(frameIndex),
                        sequenceEvent:
                            frameIndex == 0 ? .first : .continuous,
                        sampleOffset: frameIndex * 50
                    )
                ],
                measuredAt: measuredAt
            )
            #expect(request == nil)
        }

        let firstCandidate = scheduler.ingest(
            decodedFrames: [
                frame(
                    sequence: 15,
                    sequenceEvent: .continuous,
                    sampleOffset: 750
                )
            ],
            measuredAt: measuredAt
        )
        let first = try #require(firstCandidate)
        #expect(first.bandpassedIR.count == 800)
        #expect(first.timeSeconds.first == 0)
        #expect(first.timeSeconds.last == 7.99)
        #expect(first.windowEndSampleIndex == 799)
        #expect(first.requestSequence == 1)
        #expect(scheduler.isCurrent(first))

        #expect(
            scheduler.ingest(
                decodedFrames: [
                    frame(
                        sequence: 16,
                        sequenceEvent: .continuous,
                        sampleOffset: 800
                    )
                ],
                measuredAt: measuredAt
            ) == nil
        )
        let secondCandidate = scheduler.ingest(
            decodedFrames: [
                frame(
                    sequence: 17,
                    sequenceEvent: .continuous,
                    sampleOffset: 850
                )
            ],
            measuredAt: measuredAt
        )
        let second = try #require(secondCandidate)
        #expect(second.windowEndSampleIndex == 899)
        #expect(second.timeSeconds.first == 1)
        #expect(second.timeSeconds.last == 8.99)
        #expect(second.requestSequence == 2)
        #expect(!scheduler.isCurrent(first))
        #expect(scheduler.isCurrent(second))
        #expect(scheduler.bufferedSampleCount == 800)
    }

    @Test
    func gapClearsWindowAndMakesEarlierRequestsObsolete() throws {
        var scheduler = PPGLiveMetricWindowScheduler()
        let measuredAt = Date(timeIntervalSince1970: 1_800_000_000)
        var firstRequest: PPGLiveMetricAnalysisRequest?
        for frameIndex in 0..<16 {
            firstRequest = scheduler.ingest(
                decodedFrames: [
                    frame(
                        sequence: UInt8(frameIndex),
                        sequenceEvent:
                            frameIndex == 0 ? .first : .continuous,
                        sampleOffset: frameIndex * 50
                    )
                ],
                measuredAt: measuredAt
            ) ?? firstRequest
        }
        let beforeGap = try #require(firstRequest)
        let generationBeforeGap = beforeGap.generation

        let gapRequest = scheduler.ingest(
            decodedFrames: [
                frame(
                    sequence: 18,
                    sequenceEvent: .gap(missingFrames: 2),
                    sampleOffset: 800
                )
            ],
            measuredAt: measuredAt
        )
        #expect(gapRequest == nil)
        #expect(scheduler.continuousSamples == 50)
        #expect(scheduler.bufferedSampleCount == 50)
        #expect(!scheduler.isCurrent(beforeGap))
        #expect(scheduler.generation == generationBeforeGap + 1)

        var afterGap: PPGLiveMetricAnalysisRequest?
        for frameIndex in 1..<16 {
            afterGap = scheduler.ingest(
                decodedFrames: [
                    frame(
                        sequence: UInt8(18 + frameIndex),
                        sequenceEvent: .continuous,
                        sampleOffset: 800 + frameIndex * 50
                    )
                ],
                measuredAt: measuredAt
            ) ?? afterGap
        }
        let rebuilt = try #require(afterGap)
        #expect(rebuilt.windowEndSampleIndex == 1_599)
        #expect(rebuilt.timeSeconds.first == 8)
        #expect(rebuilt.timeSeconds.last == 15.99)
        #expect(scheduler.isCurrent(rebuilt))
    }

    @Test
    func rejectedFramesNeverAdvanceTheMetricTimeline() {
        var scheduler = PPGLiveMetricWindowScheduler()
        let measuredAt = Date(timeIntervalSince1970: 1_800_000_000)
        let rejected = CUPDecodedFrameEvent(
            frame: CUPBatchFrame(
                sequence: 4,
                samples: Array(
                    repeating: CUPPPGSample(red: 1, ir: 2),
                    count: 50
                )
            ),
            sequenceEvent: .duplicate,
            isAccepted: false
        )

        #expect(
            scheduler.ingest(
                decodedFrames: [rejected],
                measuredAt: measuredAt
            ) == nil
        )
        #expect(scheduler.continuousSamples == 0)
        #expect(scheduler.bufferedSampleCount == 0)
    }

    @Test
    func analyzerPublishesHeartRateAndWritesProvisionalSQI() throws {
        let sampleRate = 100.0
        let values = (0..<800).map { index in
            let time = Double(index) / sampleRate
            return 1_000 * sin(2 * .pi * 1.2 * time)
                + 220 * sin(2 * .pi * 2.4 * time)
        }
        let measuredAt = Date(timeIntervalSince1970: 1_800_000_000)
        let rawIR = (0..<800).map { index in
            500_000 + 20_000 * sin(2 * .pi * 1.2 * Double(index) / sampleRate)
        }
        let rawRED = (0..<800).map { index in
            250_000 + 12_000 * sin(2 * .pi * 1.2 * Double(index) / sampleRate)
        }
        let request = PPGLiveMetricAnalysisRequest(
            generation: 4,
            requestSequence: 9,
            windowEndSampleIndex: 1_599,
            windowEndTimeSeconds: 15.99,
            measuredAt: measuredAt,
            rawRED: rawRED,
            rawIR: rawIR,
            bandpassedRED: values.map { $0 * 0.6 },
            bandpassedIR: values,
            timeSeconds: (800..<1_600).map {
                Double($0) / sampleRate
            }
        )

        let result = PPGLiveMetricAnalyzer.analyze(request)
        let heartRate = result.snapshot.heartRateBPM
        let signalQuality = result.snapshot.signalQuality

        #expect(heartRate.isValid)
        #expect((heartRate.value ?? 0) > 70)
        #expect((heartRate.value ?? 0) < 74)
        #expect(heartRate.sourceSampleIndex == 1_599)
        #expect(heartRate.sourceTimeSeconds == 15.99)
        #expect(heartRate.measuredAt == measuredAt)
        #expect(signalQuality.isValid)
        #expect(signalQuality.value != nil)
        #expect((signalQuality.value ?? -1) >= 0)
        #expect((signalQuality.value ?? 2) <= 1)
        #expect(signalQuality.isProvisional)
        #expect(signalQuality.unavailableReason == nil)
        #expect(result.provisionalSignalQuality != nil)
        #expect(result.snapshot.ratioOfRatios.isValid)
        #expect(result.snapshot.ratioOfRatios.isProvisional)
        #expect((result.snapshot.ratioOfRatios.value ?? 0) > 0)
    }

    private func frame(
        sequence: UInt8,
        sequenceEvent: CUPSequenceEvent,
        sampleOffset: Int
    ) -> CUPDecodedFrameEvent {
        let samples = (0..<50).map { localIndex in
            let index = sampleOffset + localIndex
            let phase = Double(index) / 100
            let ir = UInt32(
                500_000
                    + Int(20_000 * sin(2 * .pi * 1.2 * phase))
            )
            return CUPPPGSample(red: ir + 1_000, ir: ir)
        }
        return CUPDecodedFrameEvent(
            frame: CUPBatchFrame(
                sequence: sequence,
                samples: samples
            ),
            sequenceEvent: sequenceEvent,
            isAccepted: true
        )
    }
}
