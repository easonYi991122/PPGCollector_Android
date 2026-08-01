import Foundation

/// Migration draft derived from the supplied CUP frame screenshot.
///
/// Endianness, length semantics, sample ordering and checksum behavior must be
/// confirmed against a real device log before this profile is marked production.
nonisolated enum CUPBatchProtocolV1 {
    static let header: [UInt8] = [0xAB, 0xBA]
    static let tail: [UInt8] = [0xCD, 0xDC]
    static let batchFunction: UInt8 = 0x15
    static let sampleRateHz = 100
    static let samplesPerFrame = 50
    static let sampleWireLength = 8
    static let dataLength = 1 + samplesPerFrame * sampleWireLength
    static let frameLength = 2 + 1 + 2 + dataLength + 2
}

nonisolated struct CUPPPGSample: Equatable, Sendable {
    let red: UInt32
    let ir: UInt32
}

nonisolated struct CUPBatchFrame: Equatable, Sendable {
    let sequence: UInt8
    let samples: [CUPPPGSample]
}

nonisolated struct CUPDecoderStats: Equatable, Sendable {
    var frames = 0
    var bytesDiscarded = 0
    var invalidFunction = 0
    var invalidLength = 0
    var invalidTail = 0
}
