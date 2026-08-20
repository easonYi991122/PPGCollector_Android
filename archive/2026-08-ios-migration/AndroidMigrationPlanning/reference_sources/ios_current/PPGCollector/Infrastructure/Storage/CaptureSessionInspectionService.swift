import Foundation

nonisolated struct CaptureCSVScanReport: Equatable, Sendable {
    let totalBytes: Int
    let validByteCount: Int
    let completeDataRowCount: Int
    let hasExpectedHeader: Bool
    let hasTruncatedFinalLine: Bool

    var trailingByteCount: Int {
        max(0, totalBytes - validByteCount)
    }
}

nonisolated enum CaptureInspectionSeverity: String, Equatable, Sendable {
    case warning
    case error
}

nonisolated struct CaptureInspectionFinding:
    Identifiable,
    Equatable,
    Sendable
{
    let id: String
    let severity: CaptureInspectionSeverity
    let message: String
}

nonisolated struct CaptureSessionInspection: Equatable, Sendable {
    let replay: CUPRawReplayReport?
    let csv: CaptureCSVScanReport?
    let findings: [CaptureInspectionFinding]

    var isVerifiedConsistent: Bool {
        findings.isEmpty
            && replay?.isStructurallyClean == true
            && csv?.hasExpectedHeader == true
            && csv?.hasTruncatedFinalLine == false
    }

    var hasRecoverableTail: Bool {
        replay?.tailIssue != nil || csv?.hasTruncatedFinalLine == true
    }
}

nonisolated enum CaptureSessionInspectionService {
    static func inspect(
        _ session: StoredCaptureSession,
        fileManager: FileManager = .default
    ) -> CaptureSessionInspection {
        do {
            return try inspect(
                session,
                fileManager: fileManager,
                cancellationCheck: {}
            )
        } catch {
            return CaptureSessionInspection(
                replay: nil,
                csv: nil,
                findings: [
                    CaptureInspectionFinding(
                        id: "inspection-unexpected",
                        severity: .error,
                        message:
                            "检查意外中止：\(error.localizedDescription)"
                    )
                ]
            )
        }
    }

    static func inspectCancellable(
        _ session: StoredCaptureSession
    ) async throws -> CaptureSessionInspection {
        let worker = Task.detached {
            try inspect(
                session,
                fileManager: .default,
                cancellationCheck: {
                    try Task.checkCancellation()
                }
            )
        }
        return try await withTaskCancellationHandler {
            try await worker.value
        } onCancel: {
            worker.cancel()
        }
    }

    private static func inspect(
        _ session: StoredCaptureSession,
        fileManager: FileManager,
        cancellationCheck: () throws -> Void
    ) throws -> CaptureSessionInspection {
        try cancellationCheck()
        let expectedURLs = CaptureSessionRepository.expectedFileURLs(
            in: session.directoryURL
        )
        let rawURL = expectedURLs[0]
        let csvURL = expectedURLs[1]
        var findings: [CaptureInspectionFinding] = []

        let replay: CUPRawReplayReport?
        if fileManager.fileExists(atPath: rawURL.path) {
            do {
                let report = try CUPRawReplayEngine.replay(
                    url: rawURL,
                    cancellationCheck: cancellationCheck
                )
                replay = report
                appendReplayFindings(report, to: &findings)
            } catch {
                if error is CancellationError {
                    throw error
                }
                replay = nil
                findings.append(
                    CaptureInspectionFinding(
                        id: "raw-unreadable",
                        severity: .error,
                        message: "raw 无法解析：\(error.localizedDescription)"
                    )
                )
            }
        } else {
            replay = nil
            findings.append(
                CaptureInspectionFinding(
                    id: "raw-missing",
                    severity: .error,
                    message: "缺少 .cupraw 文件。"
                )
            )
        }

        try cancellationCheck()
        let csv: CaptureCSVScanReport?
        if fileManager.fileExists(atPath: csvURL.path) {
            do {
                let report = try scanCSV(
                    url: csvURL,
                    cancellationCheck: cancellationCheck
                )
                csv = report
                appendCSVFindings(report, to: &findings)
            } catch {
                if error is CancellationError {
                    throw error
                }
                csv = nil
                findings.append(
                    CaptureInspectionFinding(
                        id: "csv-unreadable",
                        severity: .error,
                        message: "CSV 无法读取：\(error.localizedDescription)"
                    )
                )
            }
        } else {
            csv = nil
            findings.append(
                CaptureInspectionFinding(
                    id: "csv-missing",
                    severity: .error,
                    message: "缺少 .csv 文件。"
                )
            )
        }

        try cancellationCheck()
        if !session.metadataIsReadable {
            findings.append(
                CaptureInspectionFinding(
                    id: "metadata-unreadable",
                    severity: .error,
                    message: "metadata 缺失或无法解析。"
                )
            )
        } else if session.complete != true {
            findings.append(
                CaptureInspectionFinding(
                    id: "metadata-incomplete",
                    severity: .warning,
                    message: "metadata 标记为未完整结束。"
                )
            )
        }

        if let replay, let expected = session.rawChunkCount,
           replay.rawRecordCount != expected {
            findings.append(
                CaptureInspectionFinding(
                    id: "raw-chunk-count",
                    severity: .error,
                    message:
                        "raw chunk 数 \(replay.rawRecordCount) 与 metadata \(expected) 不一致。"
                )
            )
        }
        if let replay, let expected = session.sampleCount,
           replay.acceptedSamples != expected {
            findings.append(
                CaptureInspectionFinding(
                    id: "raw-sample-count",
                    severity: .error,
                    message:
                        "raw 重放样本 \(replay.acceptedSamples) 与 metadata \(expected) 不一致。"
                )
            )
        }
        if let csv, let expected = session.sampleCount,
           csv.completeDataRowCount != expected {
            findings.append(
                CaptureInspectionFinding(
                    id: "csv-sample-count",
                    severity: .error,
                    message:
                        "CSV 完整行 \(csv.completeDataRowCount) 与 metadata \(expected) 不一致。"
                )
            )
        }
        if let replay, let csv,
           replay.acceptedSamples != csv.completeDataRowCount {
            findings.append(
                CaptureInspectionFinding(
                    id: "raw-csv-sample-count",
                    severity: .error,
                    message:
                        "raw 重放样本 \(replay.acceptedSamples) 与 CSV 完整行 \(csv.completeDataRowCount) 不一致。"
                )
            )
        }

        return CaptureSessionInspection(
            replay: replay,
            csv: csv,
            findings: findings
        )
    }

    static func scanCSV(url: URL) throws -> CaptureCSVScanReport {
        try scanCSV(url: url, cancellationCheck: {})
    }

    static func scanCSV(data: Data) -> CaptureCSVScanReport {
        let expectedHeader = Data(CaptureCSVSchema.header.utf8)
        let hasExpectedHeader = data.starts(with: expectedHeader)
        let newlineCount = data.reduce(into: 0) { count, byte in
            if byte == 0x0A {
                count += 1
            }
        }
        let hasTruncatedFinalLine = !data.isEmpty && data.last != 0x0A
        let validByteCount: Int
        if hasTruncatedFinalLine, let lastNewline = data.lastIndex(of: 0x0A) {
            validByteCount = data.distance(
                from: data.startIndex,
                to: data.index(after: lastNewline)
            )
        } else {
            validByteCount = data.count
        }

        return CaptureCSVScanReport(
            totalBytes: data.count,
            validByteCount: validByteCount,
            completeDataRowCount: max(0, newlineCount - 1),
            hasExpectedHeader: hasExpectedHeader,
            hasTruncatedFinalLine: hasTruncatedFinalLine
        )
    }

    static func scanCSV(
        url: URL,
        cancellationCheck: () throws -> Void
    ) throws -> CaptureCSVScanReport {
        let handle = try FileHandle(forReadingFrom: url)
        defer {
            try? handle.close()
        }

        let expectedHeader = Data(CaptureCSVSchema.header.utf8)
        var observedHeader = Data()
        observedHeader.reserveCapacity(expectedHeader.count)
        var totalBytes = 0
        var newlineCount = 0
        var lastNewlineEnd: Int?
        var lastByte: UInt8?

        while let block = try handle.read(upToCount: 64 * 1024),
              !block.isEmpty {
            try cancellationCheck()
            if observedHeader.count < expectedHeader.count {
                let missing = expectedHeader.count - observedHeader.count
                observedHeader.append(block.prefix(missing))
            }
            for (offset, byte) in block.enumerated() {
                if byte == 0x0A {
                    newlineCount += 1
                    lastNewlineEnd = totalBytes + offset + 1
                }
            }
            totalBytes += block.count
            lastByte = block.last
        }
        try cancellationCheck()

        let hasTruncatedFinalLine = totalBytes > 0 && lastByte != 0x0A
        let validByteCount: Int
        if hasTruncatedFinalLine, let lastNewlineEnd {
            validByteCount = lastNewlineEnd
        } else {
            validByteCount = totalBytes
        }

        return CaptureCSVScanReport(
            totalBytes: totalBytes,
            validByteCount: validByteCount,
            completeDataRowCount: max(0, newlineCount - 1),
            hasExpectedHeader: observedHeader == expectedHeader,
            hasTruncatedFinalLine: hasTruncatedFinalLine
        )
    }

    private static func appendReplayFindings(
        _ replay: CUPRawReplayReport,
        to findings: inout [CaptureInspectionFinding]
    ) {
        if let tailIssue = replay.tailIssue {
            findings.append(
                CaptureInspectionFinding(
                    id: "raw-tail",
                    severity: .warning,
                    message:
                        "raw 尾部可恢复：\(tailIssue.description) 最后完整偏移为 \(replay.validRawBytes)。"
                )
            )
        }
        if replay.structurallyInvalidFrames > 0
            || replay.discardedBytes > 0
            || replay.pendingDecoderBytes > 0 {
            findings.append(
                CaptureInspectionFinding(
                    id: "raw-structure",
                    severity: .error,
                    message:
                        "raw 重放存在结构异常：坏帧 \(replay.structurallyInvalidFrames)，丢弃 \(replay.discardedBytes) bytes，待组帧 \(replay.pendingDecoderBytes) bytes。"
                )
            )
        }
        if replay.missingFrames > 0
            || replay.duplicateFrames > 0
            || replay.outOfOrderFrames > 0 {
            findings.append(
                CaptureInspectionFinding(
                    id: "raw-sequence",
                    severity: .warning,
                    message:
                        "序号异常：缺失 \(replay.missingFrames)，重复 \(replay.duplicateFrames)，乱序 \(replay.outOfOrderFrames)。"
                )
            )
        }
    }

    private static func appendCSVFindings(
        _ csv: CaptureCSVScanReport,
        to findings: inout [CaptureInspectionFinding]
    ) {
        if !csv.hasExpectedHeader {
            findings.append(
                CaptureInspectionFinding(
                    id: "csv-header",
                    severity: .error,
                    message: "CSV header 与当前 v1 schema 不一致。"
                )
            )
        }
        if csv.hasTruncatedFinalLine {
            findings.append(
                CaptureInspectionFinding(
                    id: "csv-tail",
                    severity: .warning,
                    message:
                        "CSV 最后一行未完整换行；可安全保留前 \(csv.validByteCount) bytes。"
                )
            )
        }
    }
}
