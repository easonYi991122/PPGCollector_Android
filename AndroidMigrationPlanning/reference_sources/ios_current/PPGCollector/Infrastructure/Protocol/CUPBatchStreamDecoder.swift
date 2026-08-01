import Foundation

nonisolated struct CUPBatchStreamDecoder: Sendable {
    private var buffer: [UInt8] = []
    private(set) var stats = CUPDecoderStats()

    var pendingByteCount: Int {
        buffer.count
    }

    mutating func feed(_ data: Data) -> [CUPBatchFrame] {
        buffer.append(contentsOf: data)
        var frames: [CUPBatchFrame] = []

        while true {
            guard let headerIndex = firstHeaderIndex() else {
                keepPossibleHeaderPrefix()
                break
            }

            if headerIndex > 0 {
                discardFirst(headerIndex)
            }

            guard buffer.count >= 5 else {
                break
            }

            guard buffer[2] == CUPBatchProtocolV1.batchFunction else {
                stats.invalidFunction += 1
                discardFirst(1)
                continue
            }

            let dataLength = Int(readUInt16LE(at: 3))
            guard dataLength == CUPBatchProtocolV1.dataLength else {
                stats.invalidLength += 1
                discardFirst(1)
                continue
            }

            let totalLength = 2 + 1 + 2 + dataLength + 2
            guard buffer.count >= totalLength else {
                break
            }

            guard buffer[totalLength - 2] == CUPBatchProtocolV1.tail[0],
                  buffer[totalLength - 1] == CUPBatchProtocolV1.tail[1] else {
                stats.invalidTail += 1
                discardFirst(1)
                continue
            }

            frames.append(decodeValidatedFrame())
            stats.frames += 1
            buffer.removeFirst(totalLength)
        }

        return frames
    }

    mutating func reset() {
        buffer.removeAll(keepingCapacity: true)
        stats = CUPDecoderStats()
    }

    private func firstHeaderIndex() -> Int? {
        guard buffer.count >= CUPBatchProtocolV1.header.count else {
            return nil
        }

        for index in 0..<(buffer.count - 1) {
            if buffer[index] == CUPBatchProtocolV1.header[0],
               buffer[index + 1] == CUPBatchProtocolV1.header[1] {
                return index
            }
        }
        return nil
    }

    private mutating func keepPossibleHeaderPrefix() {
        let keepCount = buffer.last == CUPBatchProtocolV1.header[0] ? 1 : 0
        let discardCount = buffer.count - keepCount
        discardFirst(discardCount)
    }

    private mutating func discardFirst(_ count: Int) {
        guard count > 0 else {
            return
        }
        buffer.removeFirst(count)
        stats.bytesDiscarded += count
    }

    private func decodeValidatedFrame() -> CUPBatchFrame {
        let sequence = buffer[5]
        var samples: [CUPPPGSample] = []
        samples.reserveCapacity(CUPBatchProtocolV1.samplesPerFrame)

        for sampleIndex in 0..<CUPBatchProtocolV1.samplesPerFrame {
            let offset = 6 + sampleIndex * CUPBatchProtocolV1.sampleWireLength
            samples.append(
                CUPPPGSample(
                    red: readUInt32LE(at: offset),
                    ir: readUInt32LE(at: offset + 4)
                )
            )
        }

        return CUPBatchFrame(sequence: sequence, samples: samples)
    }

    private func readUInt16LE(at offset: Int) -> UInt16 {
        UInt16(buffer[offset])
            | (UInt16(buffer[offset + 1]) << 8)
    }

    private func readUInt32LE(at offset: Int) -> UInt32 {
        UInt32(buffer[offset])
            | (UInt32(buffer[offset + 1]) << 8)
            | (UInt32(buffer[offset + 2]) << 16)
            | (UInt32(buffer[offset + 3]) << 24)
    }
}
