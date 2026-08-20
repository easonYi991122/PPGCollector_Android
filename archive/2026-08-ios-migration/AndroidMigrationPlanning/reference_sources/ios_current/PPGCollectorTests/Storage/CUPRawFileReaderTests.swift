import Foundation
import Testing
@testable import PPGCollector

struct CUPRawFileReaderTests {
    @Test
    func parsesMultipleRecords() throws {
        var data = CUPRawFileReader.magic
        appendRecord(timestamp: 123, chunk: Data([1, 2, 3]), to: &data)
        appendRecord(timestamp: UInt64.max, chunk: Data([4, 5]), to: &data)

        let records = try CUPRawFileReader.read(data: data)
        #expect(records == [
            CUPRawRecord(hostMonotonicNanoseconds: 123, chunk: Data([1, 2, 3])),
            CUPRawRecord(hostMonotonicNanoseconds: UInt64.max, chunk: Data([4, 5]))
        ])
    }

    @Test
    func rejectsInvalidAndTruncatedInput() {
        #expect(throws: CUPRawFileError.invalidMagic) {
            try CUPRawFileReader.read(data: Data("not-raw".utf8))
        }

        var truncatedHeader = CUPRawFileReader.magic
        truncatedHeader.append(contentsOf: [0, 1, 2])
        #expect(
            throws: CUPRawFileError.truncatedRecordHeader(
                offset: CUPRawFileReader.magic.count
            )
        ) {
            try CUPRawFileReader.read(data: truncatedHeader)
        }

        var truncatedChunk = CUPRawFileReader.magic
        appendUInt64LE(1, to: &truncatedChunk)
        appendUInt32LE(4, to: &truncatedChunk)
        truncatedChunk.append(contentsOf: [1, 2])
        #expect(
            throws: CUPRawFileError.truncatedChunk(
                offset: CUPRawFileReader.magic.count,
                expected: 4,
                available: 2
            )
        ) {
            try CUPRawFileReader.read(data: truncatedChunk)
        }
    }

    @Test
    func tolerantScanReturnsTheLastCompleteRecordAndTailBoundary() throws {
        var data = CUPRawFileReader.magic
        appendRecord(timestamp: 123, chunk: Data([1, 2, 3]), to: &data)
        let validByteCount = data.count
        appendUInt64LE(456, to: &data)
        appendUInt32LE(4, to: &data)
        data.append(contentsOf: [8, 9])

        let result = try CUPRawFileReader.scan(data: data)
        #expect(result.records.count == 1)
        #expect(result.validByteCount == validByteCount)
        #expect(result.trailingByteCount == 14)
        #expect(
            result.tailIssue == .truncatedChunk(
                offset: validByteCount,
                expected: 4,
                available: 2
            )
        )
    }

    @Test
    func streamingScanMatchesTheInMemoryTailBoundary() throws {
        var data = CUPRawFileReader.magic
        appendRecord(timestamp: 123, chunk: Data([1, 2, 3]), to: &data)
        let validByteCount = data.count
        appendUInt64LE(456, to: &data)
        appendUInt32LE(4, to: &data)
        data.append(contentsOf: [8, 9])
        let url = temporaryURL()
        defer { try? FileManager.default.removeItem(at: url) }
        try data.write(to: url)

        var records: [CUPRawRecord] = []
        let summary = try CUPRawFileReader.scanRecords(url: url) {
            records.append($0)
        }

        #expect(records == [
            CUPRawRecord(
                hostMonotonicNanoseconds: 123,
                chunk: Data([1, 2, 3])
            )
        ])
        #expect(summary.recordCount == 1)
        #expect(summary.validByteCount == validByteCount)
        #expect(summary.totalByteCount == data.count)
        #expect(summary.trailingByteCount == 14)
        #expect(summary.peakRecordBufferBytes == 15)
        #expect(
            summary.tailIssue == .truncatedChunk(
                offset: validByteCount,
                expected: 4,
                available: 2
            )
        )
    }

    private func appendRecord(
        timestamp: UInt64,
        chunk: Data,
        to data: inout Data
    ) {
        appendUInt64LE(timestamp, to: &data)
        appendUInt32LE(UInt32(chunk.count), to: &data)
        data.append(chunk)
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
            "CUPRawFileReaderTests-\(UUID().uuidString).cupraw"
        )
    }
}
