import Foundation
import Testing
@testable import PPGCollector

struct CUPRawReplayEngineTests {
    @Test
    func replaysFragmentedFramesAndPreservesHostTiming() throws {
        let firstFrame = makeCUPTestFrame(sequence: 254)
        let secondFrame = makeCUPTestFrame(sequence: 255)
        var raw = CUPRawFileReader.magic
        appendRecord(
            timestamp: 1_000_000_000,
            chunk: firstFrame.prefix(244),
            to: &raw
        )
        appendRecord(
            timestamp: 1_001_000_000,
            chunk: firstFrame.dropFirst(244),
            to: &raw
        )
        appendRecord(
            timestamp: 1_500_000_000,
            chunk: secondFrame.prefix(244),
            to: &raw
        )
        appendRecord(
            timestamp: 1_501_000_000,
            chunk: secondFrame.dropFirst(244),
            to: &raw
        )

        let report = try CUPRawReplayEngine.replay(data: raw)
        #expect(report.rawRecordCount == 4)
        #expect(report.rawPayloadBytes == 816)
        #expect(report.chunkLengthCounts == [244: 2, 164: 2])
        #expect(report.decodedFrames == 2)
        #expect(report.acceptedFrames == 2)
        #expect(report.acceptedSamples == 100)
        #expect(report.missingFrames == 0)
        #expect(report.duplicateFrames == 0)
        #expect(report.outOfOrderFrames == 0)
        #expect(report.firstFrameHostNanoseconds == 1_001_000_000)
        #expect(report.lastFrameHostNanoseconds == 1_501_000_000)
        #expect(report.hostDurationSeconds == 0.5)
        #expect(report.isStructurallyClean)
        #expect(report.samples.count == 100)
        #expect(report.recentSamples.count == 100)
    }

    @Test
    func replayRetainsTheCompleteSignalBeyondTheLivePreviewCapacity() throws {
        var raw = CUPRawFileReader.magic
        for sequence in 0..<20 {
            appendRecord(
                timestamp: UInt64(sequence) * 500_000_000,
                chunk: makeCUPTestFrame(sequence: UInt8(sequence)),
                to: &raw
            )
        }

        let report = try CUPRawReplayEngine.replay(data: raw)

        #expect(report.acceptedSamples == 1_000)
        #expect(report.samples.count == 1_000)
        #expect(report.recentSamples.count == 800)
        #expect(report.samples.suffix(800).elementsEqual(report.recentSamples))
    }

    @Test
    func streamsTwoHoursOfExactSamplesWithinTheAutomatedBudget() throws {
        let frameCount = 2 * 60 * 60
            * CUPBatchProtocolV1.sampleRateHz
            / CUPBatchProtocolV1.samplesPerFrame
        let expectedSampleCount =
            frameCount * CUPBatchProtocolV1.samplesPerFrame
        let frames = (0...UInt8.max).map(makeCUPTestFrame(sequence:))
        let url = temporaryURL()
        defer { try? FileManager.default.removeItem(at: url) }

        try Data().write(to: url)
        do {
            let handle = try FileHandle(forWritingTo: url)
            defer { try? handle.close() }
            try handle.write(contentsOf: CUPRawFileReader.magic)
            for frameIndex in 0..<frameCount {
                let timestamp =
                    UInt64(frameIndex) * 500_000_000
                var record = Data()
                record.reserveCapacity(
                    12 + CUPBatchProtocolV1.frameLength
                )
                appendUInt64LE(timestamp, to: &record)
                appendUInt32LE(
                    UInt32(CUPBatchProtocolV1.frameLength),
                    to: &record
                )
                record.append(frames[frameIndex % frames.count])
                try handle.write(contentsOf: record)
            }
            try handle.synchronize()
        }

        let clock = ContinuousClock()
        let started = clock.now
        let report = try CUPRawReplayEngine.replay(url: url)
        let elapsed = started.duration(to: clock.now)

        #expect(report.readMode == .streaming)
        #expect(report.rawRecordCount == frameCount)
        #expect(report.decodedFrames == frameCount)
        #expect(report.acceptedFrames == frameCount)
        #expect(report.acceptedSamples == expectedSampleCount)
        #expect(report.samples.count == expectedSampleCount)
        #expect(report.retainedSampleBytes == expectedSampleCount * 8)
        #expect(
            report.peakRawRecordBufferBytes
                == 12 + CUPBatchProtocolV1.frameLength
        )
        #expect(report.missingFrames == 0)
        #expect(report.duplicateFrames == 0)
        #expect(report.outOfOrderFrames == 0)
        #expect(report.isStructurallyClean)
        #expect(report.hostDurationSeconds == 7_199.5)
        #expect(elapsed < .seconds(15))
    }

    @Test
    func streamingReplayStopsAtTheInjectedCancellationBoundary() throws {
        let url = temporaryURL()
        defer { try? FileManager.default.removeItem(at: url) }
        var raw = CUPRawFileReader.magic
        for frameIndex in 0..<300 {
            appendRecord(
                timestamp: UInt64(frameIndex) * 500_000_000,
                chunk: makeCUPTestFrame(
                    sequence: UInt8(truncatingIfNeeded: frameIndex)
                ),
                to: &raw
            )
        }
        try raw.write(to: url)

        var cancellationChecks = 0
        #expect(throws: CancellationError.self) {
            try CUPRawReplayEngine.replay(
                url: url,
                cancellationCheck: {
                    cancellationChecks += 1
                    if cancellationChecks == 200 {
                        throw CancellationError()
                    }
                }
            )
        }
        #expect(cancellationChecks == 200)
    }

    private func appendRecord(
        timestamp: UInt64,
        chunk: some DataProtocol,
        to data: inout Data
    ) {
        let chunkData = Data(chunk)
        appendUInt64LE(timestamp, to: &data)
        appendUInt32LE(UInt32(chunkData.count), to: &data)
        data.append(chunkData)
    }

    private func appendUInt32LE(_ value: UInt32, to data: inout Data) {
        for shift in stride(from: 0, through: 24, by: 8) {
            data.append(UInt8((value >> UInt32(shift)) & 0xFF))
        }
    }

    private func appendUInt64LE(_ value: UInt64, to data: inout Data) {
        for shift in stride(from: 0, through: 56, by: 8) {
            data.append(UInt8((value >> UInt64(shift)) & 0xFF))
        }
    }

    private func temporaryURL() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent(
            "CUPRawReplayEngineTests-\(UUID().uuidString).cupraw"
        )
    }
}
