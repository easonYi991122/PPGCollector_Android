import Foundation

nonisolated enum CUPRawReplayReadMode: String, Equatable, Sendable {
    case inMemory
    case streaming
}

nonisolated struct CUPRawReplayReport: Equatable, Sendable {
    let readMode: CUPRawReplayReadMode
    let peakRawRecordBufferBytes: Int
    let rawRecordCount: Int
    let rawPayloadBytes: Int
    let validRawBytes: Int
    let totalRawBytes: Int
    let tailIssue: CUPRawTailIssue?
    let chunkLengthCounts: [Int: Int]
    let decodedFrames: Int
    let acceptedFrames: Int
    let acceptedSamples: Int
    let missingFrames: Int
    let duplicateFrames: Int
    let outOfOrderFrames: Int
    let structurallyInvalidFrames: Int
    let discardedBytes: Int
    let pendingDecoderBytes: Int
    let firstFrameHostNanoseconds: UInt64?
    let lastFrameHostNanoseconds: UInt64?
    let samples: [CUPPPGSample]

    var retainedSampleBytes: Int {
        samples.count * MemoryLayout<CUPPPGSample>.stride
    }

    var recentSamples: [CUPPPGSample] {
        Array(
            samples.suffix(CUPBatchProtocolV1.sampleRateHz * 8)
        )
    }

    var trailingRawBytes: Int {
        max(0, totalRawBytes - validRawBytes)
    }

    var hostDurationSeconds: Double? {
        guard let firstFrameHostNanoseconds,
              let lastFrameHostNanoseconds,
              lastFrameHostNanoseconds >= firstFrameHostNanoseconds else {
            return nil
        }
        return Double(
            lastFrameHostNanoseconds - firstFrameHostNanoseconds
        ) / 1_000_000_000
    }

    var isStructurallyClean: Bool {
        tailIssue == nil
            && structurallyInvalidFrames == 0
            && discardedBytes == 0
            && pendingDecoderBytes == 0
    }
}

nonisolated enum CUPRawReplayEngine {
    static func replay(
        url: URL,
        cancellationCheck: () throws -> Void = {
            try Task.checkCancellation()
        }
    ) throws -> CUPRawReplayReport {
        var accumulator = CUPRawReplayAccumulator()
        let summary = try CUPRawFileReader.scanRecords(
            url: url,
            cancellationCheck: cancellationCheck
        ) { record in
            try cancellationCheck()
            accumulator.receive(record)
        }
        try cancellationCheck()
        return accumulator.report(
            readMode: .streaming,
            peakRawRecordBufferBytes: summary.peakRecordBufferBytes,
            rawRecordCount: summary.recordCount,
            validRawBytes: summary.validByteCount,
            totalRawBytes: summary.totalByteCount,
            tailIssue: summary.tailIssue
        )
    }

    static func replay(data: Data) throws -> CUPRawReplayReport {
        try replay(scanResult: CUPRawFileReader.scan(data: data))
    }

    static func replay(
        scanResult: CUPRawScanResult
    ) -> CUPRawReplayReport {
        var accumulator = CUPRawReplayAccumulator()
        for record in scanResult.records {
            accumulator.receive(record)
        }
        return accumulator.report(
            readMode: .inMemory,
            peakRawRecordBufferBytes: scanResult.totalByteCount,
            rawRecordCount: scanResult.records.count,
            validRawBytes: scanResult.validByteCount,
            totalRawBytes: scanResult.totalByteCount,
            tailIssue: scanResult.tailIssue
        )
    }
}

nonisolated private struct CUPRawReplayAccumulator {
    private var pipeline = CUPStreamingPipeline(recentSampleCapacity: 0)
    private var rawPayloadBytes = 0
    private var chunkLengthCounts: [Int: Int] = [:]
    private var acceptedFrames = 0
    private var samples: [CUPPPGSample] = []
    private var firstFrameHostNanoseconds: UInt64?
    private var lastFrameHostNanoseconds: UInt64?

    mutating func receive(_ record: CUPRawRecord) {
        rawPayloadBytes += record.chunk.count
        chunkLengthCounts[record.chunk.count, default: 0] += 1
        let events = pipeline.receive(record.chunk)
        let acceptedInRecord = events.reduce(into: 0) { count, event in
            if event.isAccepted {
                count += 1
                samples.append(contentsOf: event.frame.samples)
            }
        }
        if acceptedInRecord > 0 {
            acceptedFrames += acceptedInRecord
            if firstFrameHostNanoseconds == nil {
                firstFrameHostNanoseconds =
                    record.hostMonotonicNanoseconds
            }
            lastFrameHostNanoseconds = record.hostMonotonicNanoseconds
        }
    }

    func report(
        readMode: CUPRawReplayReadMode,
        peakRawRecordBufferBytes: Int,
        rawRecordCount: Int,
        validRawBytes: Int,
        totalRawBytes: Int,
        tailIssue: CUPRawTailIssue?
    ) -> CUPRawReplayReport {
        let diagnostics = pipeline.diagnostics
        return CUPRawReplayReport(
            readMode: readMode,
            peakRawRecordBufferBytes: peakRawRecordBufferBytes,
            rawRecordCount: rawRecordCount,
            rawPayloadBytes: rawPayloadBytes,
            validRawBytes: validRawBytes,
            totalRawBytes: totalRawBytes,
            tailIssue: tailIssue,
            chunkLengthCounts: chunkLengthCounts,
            decodedFrames: diagnostics.decodedFrames,
            acceptedFrames: acceptedFrames,
            acceptedSamples: diagnostics.acceptedSamples,
            missingFrames: diagnostics.sequenceStats.missingFrames,
            duplicateFrames: diagnostics.sequenceStats.duplicateFrames,
            outOfOrderFrames: diagnostics.sequenceStats.outOfOrderFrames,
            structurallyInvalidFrames:
                diagnostics.structurallyInvalidFrames,
            discardedBytes: diagnostics.decoderStats.bytesDiscarded,
            pendingDecoderBytes: diagnostics.pendingDecoderBytes,
            firstFrameHostNanoseconds: firstFrameHostNanoseconds,
            lastFrameHostNanoseconds: lastFrameHostNanoseconds,
            samples: samples
        )
    }
}
