nonisolated enum CUPSequenceEvent: Equatable, Sendable {
    case first
    case continuous
    case gap(missingFrames: Int)
    case duplicate
    case outOfOrder
}

nonisolated struct CUPSequenceStats: Equatable, Sendable {
    var previous: UInt8?
    var receivedFrames = 0
    var missingFrames = 0
    var duplicateFrames = 0
    var outOfOrderFrames = 0

    var missingSamples: Int {
        missingFrames * CUPBatchProtocolV1.samplesPerFrame
    }
}

nonisolated struct CUPFrameSequenceTracker: Sendable {
    private(set) var stats = CUPSequenceStats()

    mutating func observe(_ sequence: UInt8) -> CUPSequenceEvent {
        stats.receivedFrames += 1

        guard let previous = stats.previous else {
            stats.previous = sequence
            return .first
        }

        let delta = Int(sequence &- previous)
        switch delta {
        case 0:
            stats.duplicateFrames += 1
            return .duplicate
        case 1:
            stats.previous = sequence
            return .continuous
        case 2..<128:
            let missing = delta - 1
            stats.missingFrames += missing
            stats.previous = sequence
            return .gap(missingFrames: missing)
        default:
            stats.outOfOrderFrames += 1
            return .outOfOrder
        }
    }

    mutating func reset() {
        stats = CUPSequenceStats()
    }
}
