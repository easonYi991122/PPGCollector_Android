import Foundation

nonisolated struct CUPRawRecord: Equatable, Sendable {
    let hostMonotonicNanoseconds: UInt64
    let chunk: Data
}

nonisolated enum CUPRawTailIssue: Equatable, Sendable {
    case truncatedRecordHeader(offset: Int, available: Int)
    case chunkTooLarge(offset: Int, length: UInt32)
    case truncatedChunk(offset: Int, expected: Int, available: Int)

    var description: String {
        switch self {
        case let .truncatedRecordHeader(offset, available):
            "偏移 \(offset) 的记录头不完整，仅剩 \(available) 字节。"
        case let .chunkTooLarge(offset, length):
            "偏移 \(offset) 声明了异常 chunk 长度 \(length)。"
        case let .truncatedChunk(offset, expected, available):
            "偏移 \(offset) 的 chunk 截断，需要 \(expected) 字节，实际 \(available) 字节。"
        }
    }
}

nonisolated struct CUPRawScanResult: Equatable, Sendable {
    let records: [CUPRawRecord]
    let validByteCount: Int
    let totalByteCount: Int
    let tailIssue: CUPRawTailIssue?

    var trailingByteCount: Int {
        max(0, totalByteCount - validByteCount)
    }
}

nonisolated struct CUPRawStreamingScanSummary: Equatable, Sendable {
    let recordCount: Int
    let validByteCount: Int
    let totalByteCount: Int
    let tailIssue: CUPRawTailIssue?
    let peakRecordBufferBytes: Int

    var trailingByteCount: Int {
        max(0, totalByteCount - validByteCount)
    }
}

nonisolated enum CUPRawFileError: LocalizedError, Equatable {
    case invalidMagic
    case truncatedRecordHeader(offset: Int)
    case chunkTooLarge(length: UInt32)
    case truncatedChunk(offset: Int, expected: Int, available: Int)

    var errorDescription: String? {
        switch self {
        case .invalidMagic:
            "不是 CUPRAW1 文件。"
        case let .truncatedRecordHeader(offset):
            "raw 文件在偏移 \(offset) 处的记录头不完整。"
        case let .chunkTooLarge(length):
            "raw 记录声明了异常长度 \(length)。"
        case let .truncatedChunk(offset, expected, available):
            "raw 文件在偏移 \(offset) 处截断：需要 \(expected) 字节，实际 \(available) 字节。"
        }
    }
}

nonisolated enum CUPRawFileReader {
    nonisolated static let magic = Data(
        [0x43, 0x55, 0x50, 0x52, 0x41, 0x57, 0x31, 0x00]
    )
    nonisolated static let maximumChunkLength = 64 * 1024

    static func read(url: URL) throws -> [CUPRawRecord] {
        try read(data: Data(contentsOf: url, options: .mappedIfSafe))
    }

    static func read(data: Data) throws -> [CUPRawRecord] {
        let result = try scan(data: data)
        if let tailIssue = result.tailIssue {
            switch tailIssue {
            case let .truncatedRecordHeader(offset, _):
                throw CUPRawFileError.truncatedRecordHeader(offset: offset)
            case let .chunkTooLarge(_, length):
                throw CUPRawFileError.chunkTooLarge(length: length)
            case let .truncatedChunk(offset, expected, available):
                throw CUPRawFileError.truncatedChunk(
                    offset: offset,
                    expected: expected,
                    available: available
                )
            }
        }
        return result.records
    }

    static func scan(url: URL) throws -> CUPRawScanResult {
        try scan(data: Data(contentsOf: url, options: .mappedIfSafe))
    }

    static func scanRecords(
        url: URL,
        cancellationCheck: () throws -> Void = {},
        visit: (CUPRawRecord) throws -> Void
    ) throws -> CUPRawStreamingScanSummary {
        let handle = try FileHandle(forReadingFrom: url)
        defer {
            try? handle.close()
        }

        let totalByteCount = try checkedFileSize(handle)
        try handle.seek(toOffset: 0)
        let header = try readUpTo(magic.count, from: handle)
        guard header.count == magic.count, header == magic else {
            throw CUPRawFileError.invalidMagic
        }

        var recordCount = 0
        var peakRecordBufferBytes = 0
        var offset = magic.count
        while offset < totalByteCount {
            try cancellationCheck()
            let recordOffset = offset
            let recordHeader = try readUpTo(12, from: handle)
            guard recordHeader.count == 12 else {
                return CUPRawStreamingScanSummary(
                    recordCount: recordCount,
                    validByteCount: recordOffset,
                    totalByteCount: totalByteCount,
                    tailIssue: .truncatedRecordHeader(
                        offset: recordOffset,
                        available: recordHeader.count
                    ),
                    peakRecordBufferBytes: peakRecordBufferBytes
                )
            }

            let timestamp = readUInt64LE(recordHeader, offset: 0)
            let length = readUInt32LE(recordHeader, offset: 8)
            guard length <= maximumChunkLength else {
                return CUPRawStreamingScanSummary(
                    recordCount: recordCount,
                    validByteCount: recordOffset,
                    totalByteCount: totalByteCount,
                    tailIssue: .chunkTooLarge(
                        offset: recordOffset,
                        length: length
                    ),
                    peakRecordBufferBytes: peakRecordBufferBytes
                )
            }

            let chunkLength = Int(length)
            let chunk = try readUpTo(chunkLength, from: handle)
            guard chunk.count == chunkLength else {
                return CUPRawStreamingScanSummary(
                    recordCount: recordCount,
                    validByteCount: recordOffset,
                    totalByteCount: totalByteCount,
                    tailIssue: .truncatedChunk(
                        offset: recordOffset,
                        expected: chunkLength,
                        available: chunk.count
                    ),
                    peakRecordBufferBytes: peakRecordBufferBytes
                )
            }

            peakRecordBufferBytes = max(
                peakRecordBufferBytes,
                recordHeader.count + chunk.count
            )
            try visit(
                CUPRawRecord(
                    hostMonotonicNanoseconds: timestamp,
                    chunk: chunk
                )
            )
            recordCount += 1
            offset += recordHeader.count + chunk.count
        }

        return CUPRawStreamingScanSummary(
            recordCount: recordCount,
            validByteCount: offset,
            totalByteCount: totalByteCount,
            tailIssue: nil,
            peakRecordBufferBytes: peakRecordBufferBytes
        )
    }

    static func scan(data: Data) throws -> CUPRawScanResult {
        guard data.count >= magic.count, data.prefix(magic.count) == magic else {
            throw CUPRawFileError.invalidMagic
        }

        var records: [CUPRawRecord] = []
        var offset = magic.count
        while offset < data.count {
            let recordOffset = offset
            guard data.count - offset >= 12 else {
                return CUPRawScanResult(
                    records: records,
                    validByteCount: recordOffset,
                    totalByteCount: data.count,
                    tailIssue: .truncatedRecordHeader(
                        offset: recordOffset,
                        available: data.count - recordOffset
                    )
                )
            }

            let timestamp = readUInt64LE(data, offset: offset)
            offset += 8
            let length = readUInt32LE(data, offset: offset)
            offset += 4

            guard length <= maximumChunkLength else {
                return CUPRawScanResult(
                    records: records,
                    validByteCount: recordOffset,
                    totalByteCount: data.count,
                    tailIssue: .chunkTooLarge(
                        offset: recordOffset,
                        length: length
                    )
                )
            }
            let chunkLength = Int(length)
            let available = data.count - offset
            guard available >= chunkLength else {
                return CUPRawScanResult(
                    records: records,
                    validByteCount: recordOffset,
                    totalByteCount: data.count,
                    tailIssue: .truncatedChunk(
                        offset: recordOffset,
                        expected: chunkLength,
                        available: available
                    )
                )
            }

            records.append(
                CUPRawRecord(
                    hostMonotonicNanoseconds: timestamp,
                    chunk: data.subdata(in: offset..<(offset + chunkLength))
                )
            )
            offset += chunkLength
        }
        return CUPRawScanResult(
            records: records,
            validByteCount: offset,
            totalByteCount: data.count,
            tailIssue: nil
        )
    }

    private static func checkedFileSize(_ handle: FileHandle) throws -> Int {
        let size = try handle.seekToEnd()
        guard size <= UInt64(Int.max) else {
            throw CocoaError(.fileReadTooLarge)
        }
        return Int(size)
    }

    private static func readUpTo(
        _ requestedCount: Int,
        from handle: FileHandle
    ) throws -> Data {
        guard requestedCount > 0 else {
            return Data()
        }

        var result = Data()
        result.reserveCapacity(requestedCount)
        while result.count < requestedCount {
            let remaining = requestedCount - result.count
            guard let next = try handle.read(upToCount: remaining),
                  !next.isEmpty else {
                break
            }
            result.append(next)
        }
        return result
    }

    private static func readUInt32LE(_ data: Data, offset: Int) -> UInt32 {
        UInt32(data[offset])
            | UInt32(data[offset + 1]) << 8
            | UInt32(data[offset + 2]) << 16
            | UInt32(data[offset + 3]) << 24
    }

    private static func readUInt64LE(_ data: Data, offset: Int) -> UInt64 {
        UInt64(data[offset])
            | UInt64(data[offset + 1]) << 8
            | UInt64(data[offset + 2]) << 16
            | UInt64(data[offset + 3]) << 24
            | UInt64(data[offset + 4]) << 32
            | UInt64(data[offset + 5]) << 40
            | UInt64(data[offset + 6]) << 48
            | UInt64(data[offset + 7]) << 56
    }
}
