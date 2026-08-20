import CryptoKit
import Foundation

nonisolated struct CaptureSessionRecoveryResult: Equatable, Sendable {
    let sessionID: UUID
    let baseName: String
    let directoryURL: URL
    let rawURL: URL
    let csvURL: URL
    let metadataURL: URL
    let rawCopiedBytes: Int
    let csvCopiedBytes: Int
    let rawDiscardedTailBytes: Int
    let csvDiscardedTailBytes: Int

    var shareableFileURLs: [URL] {
        [rawURL, csvURL, metadataURL]
    }
}

nonisolated struct CaptureSessionRecoveryAssessment:
    Equatable,
    Sendable
{
    let canCreateRecoveryCopy: Bool
    let requiresRecovery: Bool
    let rawTrailingBytes: Int
    let csvTrailingBytes: Int
    let reason: String?

    var shouldPrompt: Bool {
        canCreateRecoveryCopy && requiresRecovery
    }
}

nonisolated enum CaptureSessionRecoveryError:
    LocalizedError,
    Equatable
{
    case sourceRawMissing
    case sourceCSVMissing
    case sourceCSVHeaderInvalid
    case destinationAlreadyExists(String)
    case cannotCreate(String)

    var errorDescription: String? {
        switch self {
        case .sourceRawMissing:
            "源会话缺少可恢复的 .cupraw 文件。"
        case .sourceCSVMissing:
            "源会话缺少可恢复的 .csv 文件。"
        case .sourceCSVHeaderInvalid:
            "源 CSV header 与当前 v1 schema 不匹配，不能安全另存恢复副本。"
        case let .destinationAlreadyExists(name):
            "记录“\(name)”已经存在，请更换恢复副本名称。"
        case let .cannotCreate(message):
            "无法创建恢复副本：\(message)"
        }
    }
}

nonisolated enum CaptureSessionRecoveryService {
    static let strategy = "copy_safe_prefix_v1"

    static func assess(
        _ session: StoredCaptureSession,
        fileManager: FileManager = .default
    ) -> CaptureSessionRecoveryAssessment {
        guard !session.isRecoveryCopy else {
            return CaptureSessionRecoveryAssessment(
                canCreateRecoveryCopy: false,
                requiresRecovery: false,
                rawTrailingBytes: 0,
                csvTrailingBytes: 0,
                reason: nil
            )
        }

        let sourceURLs = CaptureSessionRepository.expectedFileURLs(
            in: session.directoryURL
        )
        guard fileManager.fileExists(atPath: sourceURLs[0].path),
              fileManager.fileExists(atPath: sourceURLs[1].path) else {
            return CaptureSessionRecoveryAssessment(
                canCreateRecoveryCopy: false,
                requiresRecovery: !session.isVerifiedComplete,
                rawTrailingBytes: 0,
                csvTrailingBytes: 0,
                reason: "缺少 raw 或 CSV，不能创建完整的恢复副本。"
            )
        }

        do {
            let raw = try CUPRawFileReader.scan(url: sourceURLs[0])
            let csv = try CaptureSessionInspectionService.scanCSV(
                url: sourceURLs[1]
            )
            guard csv.hasExpectedHeader else {
                return CaptureSessionRecoveryAssessment(
                    canCreateRecoveryCopy: false,
                    requiresRecovery: true,
                    rawTrailingBytes: raw.trailingByteCount,
                    csvTrailingBytes: csv.trailingByteCount,
                    reason: "CSV header 与当前 v1 schema 不匹配。"
                )
            }

            let rawCountMismatch = session.rawChunkCount.map {
                $0 != raw.records.count
            } ?? false
            let sampleCountMismatch = session.sampleCount.map {
                $0 != csv.completeDataRowCount
            } ?? false
            let metadataCountMismatch =
                rawCountMismatch || sampleCountMismatch
            let requiresRecovery = !session.isVerifiedComplete
                || raw.tailIssue != nil
                || csv.hasTruncatedFinalLine
                || metadataCountMismatch
            return CaptureSessionRecoveryAssessment(
                canCreateRecoveryCopy: true,
                requiresRecovery: requiresRecovery,
                rawTrailingBytes: raw.trailingByteCount,
                csvTrailingBytes: csv.trailingByteCount,
                reason: requiresRecovery
                    ? assessmentReason(
                        session: session,
                        rawTrailingBytes: raw.trailingByteCount,
                        csvTrailingBytes: csv.trailingByteCount,
                        metadataCountMismatch: metadataCountMismatch
                    )
                    : nil
            )
        } catch {
            return CaptureSessionRecoveryAssessment(
                canCreateRecoveryCopy: false,
                requiresRecovery: true,
                rawTrailingBytes: 0,
                csvTrailingBytes: 0,
                reason: error.localizedDescription
            )
        }
    }

    static func recoveryCandidates(
        in sessions: [StoredCaptureSession],
        fileManager: FileManager = .default
    ) -> [StoredCaptureSession] {
        sessions.filter {
            assess($0, fileManager: fileManager).shouldPrompt
        }
    }

    static func suggestedBaseName(
        for session: StoredCaptureSession,
        fileManager: FileManager = .default
    ) -> String {
        let rootURL = session.directoryURL.deletingLastPathComponent()
        let sanitized = sanitizedBaseName(session.baseName)
        var attempt = 1

        while attempt < 10_000 {
            let suffix = attempt == 1
                ? "_recovered"
                : "_recovered_\(attempt)"
            let prefixLength = max(1, 64 - suffix.count)
            let prefix = String(sanitized.prefix(prefixLength))
            let candidate = prefix + suffix
            let url = rootURL.appendingPathComponent(
                candidate,
                isDirectory: true
            )
            if !fileManager.fileExists(atPath: url.path) {
                return candidate
            }
            attempt += 1
        }

        return "recovered_\(UUID().uuidString.prefix(8))"
    }

    static func recover(
        _ session: StoredCaptureSession,
        as requestedBaseName: String,
        recoveredAt: Date = Date(),
        recoverySessionID: UUID = UUID(),
        fileManager: FileManager = .default
    ) throws -> CaptureSessionRecoveryResult {
        let baseName = try SessionNameValidator.validate(
            requestedBaseName
        )
        let sourceURLs = CaptureSessionRepository.expectedFileURLs(
            in: session.directoryURL
        )
        let sourceRawURL = sourceURLs[0]
        let sourceCSVURL = sourceURLs[1]
        let sourceMetadataURL = sourceURLs[2]

        guard fileManager.fileExists(atPath: sourceRawURL.path) else {
            throw CaptureSessionRecoveryError.sourceRawMissing
        }
        guard fileManager.fileExists(atPath: sourceCSVURL.path) else {
            throw CaptureSessionRecoveryError.sourceCSVMissing
        }

        let rawData: Data
        let csvData: Data
        do {
            rawData = try Data(
                contentsOf: sourceRawURL,
                options: .mappedIfSafe
            )
            csvData = try Data(
                contentsOf: sourceCSVURL,
                options: .mappedIfSafe
            )
        } catch {
            throw CaptureSessionRecoveryError.cannotCreate(
                error.localizedDescription
            )
        }

        let rawScan: CUPRawScanResult
        do {
            rawScan = try CUPRawFileReader.scan(data: rawData)
        } catch {
            throw CaptureSessionRecoveryError.cannotCreate(
                error.localizedDescription
            )
        }
        let csvScan = CaptureSessionInspectionService.scanCSV(
            data: csvData
        )
        guard csvScan.hasExpectedHeader else {
            throw CaptureSessionRecoveryError.sourceCSVHeaderInvalid
        }

        let replay = CUPRawReplayEngine.replay(scanResult: rawScan)
        let sourceMetadataData = try? Data(
            contentsOf: sourceMetadataURL,
            options: .mappedIfSafe
        )
        let sourceMetadata = sourceMetadataData.flatMap {
            try? CaptureSessionMetadataCodec.decode($0)
        }

        let rootURL = session.directoryURL.deletingLastPathComponent()
        let destinationURL = rootURL.appendingPathComponent(
            baseName,
            isDirectory: true
        )
        guard !fileManager.fileExists(atPath: destinationURL.path) else {
            throw CaptureSessionRecoveryError.destinationAlreadyExists(
                baseName
            )
        }

        let stagingURL = rootURL.appendingPathComponent(
            ".recovery-\(recoverySessionID.uuidString)",
            isDirectory: true
        )
        var committed = false
        defer {
            if !committed {
                try? fileManager.removeItem(at: stagingURL)
            }
        }

        do {
            try fileManager.createDirectory(
                at: stagingURL,
                withIntermediateDirectories: false
            )
            let rawURL = stagingURL.appendingPathComponent(
                "\(baseName).cupraw"
            )
            let csvURL = stagingURL.appendingPathComponent(
                "\(baseName).csv"
            )
            let metadataURL = stagingURL.appendingPathComponent(
                "\(baseName).session.json"
            )

            try writePrefix(
                rawData,
                count: rawScan.validByteCount,
                to: rawURL
            )
            try writePrefix(
                csvData,
                count: csvScan.validByteCount,
                to: csvURL
            )

            let recovery = CaptureSessionRecoveryMetadata(
                strategy: strategy,
                recoveredUTC: recoveredAt,
                recoverySoftVersion: currentSoftVersion,
                sourceDirectoryName: session.baseName,
                sourceSessionID: sourceMetadata?.sessionID
                    ?? session.sessionID,
                sourceRawSHA256: sha256(rawData),
                sourceCSVSHA256: sha256(csvData),
                sourceMetadataSHA256: sourceMetadataData.map(sha256),
                sourceRawTotalBytes: rawData.count,
                sourceRawCopiedBytes: rawScan.validByteCount,
                sourceCSVTotalBytes: csvData.count,
                sourceCSVCopiedBytes: csvScan.validByteCount,
                csvPreservesSourceSessionID: true
            )
            let metadata = recoveredMetadata(
                source: sourceMetadata,
                sourceSession: session,
                recoverySessionID: recoverySessionID,
                destinationBaseName: baseName,
                recoveredAt: recoveredAt,
                replay: replay,
                csv: csvScan,
                recovery: recovery
            )
            try CaptureSessionMetadataCodec.write(
                metadata,
                to: metadataURL
            )

            try fileManager.moveItem(
                at: stagingURL,
                to: destinationURL
            )
            committed = true

            return CaptureSessionRecoveryResult(
                sessionID: recoverySessionID,
                baseName: baseName,
                directoryURL: destinationURL,
                rawURL: destinationURL.appendingPathComponent(
                    "\(baseName).cupraw"
                ),
                csvURL: destinationURL.appendingPathComponent(
                    "\(baseName).csv"
                ),
                metadataURL: destinationURL.appendingPathComponent(
                    "\(baseName).session.json"
                ),
                rawCopiedBytes: rawScan.validByteCount,
                csvCopiedBytes: csvScan.validByteCount,
                rawDiscardedTailBytes: rawScan.trailingByteCount,
                csvDiscardedTailBytes: csvScan.trailingByteCount
            )
        } catch CocoaError.fileWriteFileExists {
            throw CaptureSessionRecoveryError.destinationAlreadyExists(
                baseName
            )
        } catch let error as CaptureSessionRecoveryError {
            throw error
        } catch {
            throw CaptureSessionRecoveryError.cannotCreate(
                error.localizedDescription
            )
        }
    }

    private static func recoveredMetadata(
        source: CaptureSessionMetadata?,
        sourceSession: StoredCaptureSession,
        recoverySessionID: UUID,
        destinationBaseName: String,
        recoveredAt: Date,
        replay: CUPRawReplayReport,
        csv: CaptureCSVScanReport,
        recovery: CaptureSessionRecoveryMetadata
    ) -> CaptureSessionMetadata {
        CaptureSessionMetadata(
            schemaVersion: CaptureSessionWriter.sessionSchemaVersion,
            sessionID: recoverySessionID.uuidString,
            baseName: destinationBaseName,
            startedUTC: source?.startedUTC
                ?? sourceSession.startedUTC
                ?? sourceSession.modifiedAt,
            endedUTC: recoveredAt,
            softVersion: source?.softVersion
                ?? sourceSession.softVersion
                ?? "unknown",
            algVersion: source?.algVersion
                ?? sourceSession.algorithmVersion
                ?? "unknown",
            preprocessProfile: source?.preprocessProfile
                ?? sourceSession.preprocessProfile
                ?? "unknown",
            protocolProfile: source?.protocolProfile
                ?? sourceSession.protocolProfile
                ?? "unknown",
            transportProfile: source?.transportProfile
                ?? sourceSession.transportProfile
                ?? "unknown",
            sampleRateHz: source?.sampleRateHz
                ?? CUPBatchProtocolV1.sampleRateHz,
            samplesPerFrame: source?.samplesPerFrame
                ?? CUPBatchProtocolV1.samplesPerFrame,
            device: source?.device ?? CaptureSessionDeviceMetadata(
                name: sourceSession.deviceName ?? "unknown",
                identifier: "unknown",
                serviceUUID: "",
                notifyCharacteristicUUID: "",
                firmwareVersion: nil,
                calibrationID: nil
            ),
            complete: false,
            stopReason: .crashRecovery,
            frameCount: replay.acceptedFrames,
            sampleCount: replay.acceptedSamples,
            rawChunkCount: replay.rawRecordCount,
            missingFrames: replay.missingFrames,
            duplicateFrames: replay.duplicateFrames,
            outOfOrderFrames: replay.outOfOrderFrames,
            invalidFrames: replay.structurallyInvalidFrames,
            discardedBytes: replay.discardedBytes,
            writer: CaptureSessionWriterMetadata(
                lastFlushUTC: source?.writer.lastFlushUTC,
                rawBytes: replay.validRawBytes,
                csvRows: csv.completeDataRowCount,
                error:
                    "Recovered copy; the source session was preserved unchanged."
            ),
            files: CaptureSessionFilesMetadata(
                raw: "\(destinationBaseName).cupraw",
                samples: "\(destinationBaseName).csv"
            ),
            recovery: recovery
        )
    }

    private static func writePrefix(
        _ data: Data,
        count: Int,
        to url: URL
    ) throws {
        try Data().write(to: url, options: .withoutOverwriting)
        let handle = try FileHandle(forWritingTo: url)
        defer {
            try? handle.close()
        }

        var offset = 0
        let blockSize = 64 * 1024
        while offset < count {
            let end = min(offset + blockSize, count)
            try handle.write(contentsOf: data.subdata(in: offset..<end))
            offset = end
        }
        try handle.synchronize()
    }

    private static func sha256(_ data: Data) -> String {
        SHA256.hash(data: data).map {
            String(format: "%02x", $0)
        }
        .joined()
    }

    private static func sanitizedBaseName(_ value: String) -> String {
        let allowed = CharacterSet(
            charactersIn:
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
        )
        let scalars = value.unicodeScalars.map { scalar -> Character in
            allowed.contains(scalar) ? Character(String(scalar)) : "_"
        }
        let result = String(scalars)
        return result.isEmpty ? "session" : result
    }

    private static func assessmentReason(
        session: StoredCaptureSession,
        rawTrailingBytes: Int,
        csvTrailingBytes: Int,
        metadataCountMismatch: Bool
    ) -> String {
        if rawTrailingBytes > 0 || csvTrailingBytes > 0 {
            return "检测到未完成文件尾：raw \(rawTrailingBytes) bytes，CSV \(csvTrailingBytes) bytes。"
        }
        if metadataCountMismatch {
            return "metadata 中的 chunk 或 sample 计数与文件不一致。"
        }
        if !session.metadataIsReadable {
            return "metadata 缺失或无法读取。"
        }
        if session.complete != true {
            return "metadata 标记为未完整结束。"
        }
        return "会话需要恢复复核。"
    }

    private static var currentSoftVersion: String {
        let short = Bundle.main.object(
            forInfoDictionaryKey: "CFBundleShortVersionString"
        ) as? String ?? "unknown"
        let build = Bundle.main.object(
            forInfoDictionaryKey: "CFBundleVersion"
        ) as? String ?? "unknown"
        return "\(short)+\(build)"
    }
}
