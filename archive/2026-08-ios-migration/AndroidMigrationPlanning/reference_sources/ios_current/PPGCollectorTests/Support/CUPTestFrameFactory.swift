import Foundation
@testable import PPGCollector

func makeCUPTestFrame(sequence: UInt8) -> Data {
    var bytes = Data(CUPBatchProtocolV1.header)
    bytes.append(CUPBatchProtocolV1.batchFunction)
    appendUInt16LE(UInt16(CUPBatchProtocolV1.dataLength), to: &bytes)
    bytes.append(sequence)

    for index in 0..<CUPBatchProtocolV1.samplesPerFrame {
        appendUInt32LE(UInt32(100_000 + index * 17), to: &bytes)
        appendUInt32LE(UInt32(120_000 + index * 23), to: &bytes)
    }

    bytes.append(contentsOf: CUPBatchProtocolV1.tail)
    return bytes
}

private func appendUInt16LE(_ value: UInt16, to data: inout Data) {
    data.append(UInt8(value & 0x00FF))
    data.append(UInt8((value >> 8) & 0x00FF))
}

private func appendUInt32LE(_ value: UInt32, to data: inout Data) {
    data.append(UInt8(value & 0x000000FF))
    data.append(UInt8((value >> 8) & 0x000000FF))
    data.append(UInt8((value >> 16) & 0x000000FF))
    data.append(UInt8((value >> 24) & 0x000000FF))
}
