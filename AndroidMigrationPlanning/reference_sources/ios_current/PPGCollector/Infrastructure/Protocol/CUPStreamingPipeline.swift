import Foundation

nonisolated struct CUPStreamingDiagnostics: Equatable, Sendable {
    var notificationChunks = 0
    var notificationErrors = 0
    var receivedBytes = 0
    var decodedFrames = 0
    var decodedSamples = 0
    var acceptedSamples = 0
    var lastChunkByteCount = 0
    var pendingDecoderBytes = 0
    var lastSequenceEvent: CUPSequenceEvent?
    var decoderStats = CUPDecoderStats()
    var sequenceStats = CUPSequenceStats()
    var recentSamples: [CUPPPGSample] = []
    var capturedWirePrefix = Data()
    var lastChunk = Data()

    var structurallyInvalidFrames: Int {
        decoderStats.invalidFunction
            + decoderStats.invalidLength
            + decoderStats.invalidTail
    }

    var capturedWirePrefixHex: String {
        capturedWirePrefix.map { String(format: "%02X", $0) }
            .joined(separator: " ")
    }

    var lastChunkHex: String {
        lastChunk.map { String(format: "%02X", $0) }
            .joined(separator: " ")
    }
}

nonisolated struct CUPStreamingPipeline: Sendable {
    private static let diagnosticWirePrefixCapacity = 512

    private var decoder = CUPBatchStreamDecoder()
    private var sequenceTracker = CUPFrameSequenceTracker()
    private let recentSampleCapacity: Int
    private(set) var diagnostics = CUPStreamingDiagnostics()

    init(recentSampleCapacity: Int = CUPBatchProtocolV1.sampleRateHz * 8) {
        self.recentSampleCapacity = max(0, recentSampleCapacity)
    }

    @discardableResult
    mutating func receive(_ data: Data) -> [CUPDecodedFrameEvent] {
        guard !data.isEmpty else {
            return []
        }

        diagnostics.notificationChunks += 1
        diagnostics.receivedBytes += data.count
        diagnostics.lastChunkByteCount = data.count
        diagnostics.lastChunk = data
        let wirePrefixRemaining = Self.diagnosticWirePrefixCapacity
            - diagnostics.capturedWirePrefix.count
        if wirePrefixRemaining > 0 {
            diagnostics.capturedWirePrefix.append(
                contentsOf: data.prefix(wirePrefixRemaining)
            )
        }

        let frames = decoder.feed(data)
        var frameEvents: [CUPDecodedFrameEvent] = []
        frameEvents.reserveCapacity(frames.count)
        for frame in frames {
            diagnostics.decodedFrames += 1
            diagnostics.decodedSamples += frame.samples.count

            let event = sequenceTracker.observe(frame.sequence)
            diagnostics.lastSequenceEvent = event
            let isAccepted: Bool
            switch event {
            case .first, .continuous, .gap:
                isAccepted = true
                diagnostics.acceptedSamples += frame.samples.count
                appendRecentSamples(frame.samples)
            case .duplicate, .outOfOrder:
                isAccepted = false
                break
            }
            frameEvents.append(
                CUPDecodedFrameEvent(
                    frame: frame,
                    sequenceEvent: event,
                    isAccepted: isAccepted
                )
            )
        }

        diagnostics.decoderStats = decoder.stats
        diagnostics.pendingDecoderBytes = decoder.pendingByteCount
        diagnostics.sequenceStats = sequenceTracker.stats
        return frameEvents
    }

    mutating func recordNotificationError() {
        diagnostics.notificationErrors += 1
    }

    mutating func reset() {
        decoder.reset()
        sequenceTracker.reset()
        diagnostics = CUPStreamingDiagnostics()
    }

    private mutating func appendRecentSamples(_ samples: [CUPPPGSample]) {
        guard recentSampleCapacity > 0 else {
            return
        }

        if samples.count >= recentSampleCapacity {
            diagnostics.recentSamples = Array(samples.suffix(recentSampleCapacity))
            return
        }

        diagnostics.recentSamples.append(contentsOf: samples)
        let overflow = diagnostics.recentSamples.count - recentSampleCapacity
        if overflow > 0 {
            diagnostics.recentSamples.removeFirst(overflow)
        }
    }
}
