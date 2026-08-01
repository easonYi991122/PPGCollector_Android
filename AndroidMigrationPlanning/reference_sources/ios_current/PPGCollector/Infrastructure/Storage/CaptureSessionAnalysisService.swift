import CryptoKit
import Foundation

/// A versioned, immutable result generated from a session's raw transport
/// records.  It is deliberately separate from the acquisition CSV: rerunning
/// an analyzer must never rewrite historical online observations.
nonisolated struct CaptureSessionAnalysisReport: Codable, Equatable, Sendable {
    let schemaVersion: String
    let sourceSessionID: String?
    let sourceBaseName: String
    let sourceRawSHA256: String
    let sourceRawByteCount: Int
    let createdUTC: Date
    let runtimeProfile: String
    let preprocessProfile: String
    let heartRateAlgorithmVersion: String
    let signalQualityAlgorithmVersion: String
    let signalQualityIsApproved: Bool
    let input: CaptureSessionAnalysisInput
    let summary: CaptureSessionAnalysisSummary
    let windows: [CaptureSessionAnalysisWindow]
    let warnings: [String]
}

nonisolated struct CaptureSessionAnalysisInput: Codable, Equatable, Sendable {
    let rawRecordCount: Int
    let acceptedFrameCount: Int
    let acceptedSampleCount: Int
    let missingFrameCount: Int
    let duplicateFrameCount: Int
    let outOfOrderFrameCount: Int
    let structurallyInvalidFrameCount: Int
    let discardedByteCount: Int
    let trailingRawByteCount: Int
}

nonisolated struct CaptureSessionAnalysisSummary: Codable, Equatable, Sendable {
    let analysisWindowCount: Int
    let validHeartRateWindowCount: Int
    let provisionalSQIWindowCount: Int
    let heartRateBPMMean: Double?
    let heartRateBPMMinimum: Double?
    let heartRateBPMMaximum: Double?
}

nonisolated struct CaptureSessionAnalysisWindow: Codable, Equatable, Sendable {
    let endSampleIndex: UInt64
    let endTimeSeconds: Double
    let continuityGeneration: UInt64
    let heartRateBPM: Double?
    let heartRateReason: String?
    let provisionalSQI: Double?
    let provisionalSQIGrade: String?
    let provisionalSQIReason: String?
}

nonisolated struct CaptureSessionAnalysisProgress: Equatable, Sendable {
    let processedRawByteCount: Int
    let totalRawByteCount: Int
    let processedRecordCount: Int
    let acceptedSampleCount: Int
    let completedWindowCount: Int

    var fractionCompleted: Double {
        guard totalRawByteCount > 0 else {
            return 0
        }
        return min(
            1,
            max(0, Double(processedRawByteCount) / Double(totalRawByteCount))
        )
    }
}

nonisolated struct CaptureSessionAnalysisArtifact: Equatable, Sendable {
    let url: URL
    let report: CaptureSessionAnalysisReport
}

nonisolated enum CaptureSessionAnalysisError: LocalizedError, Equatable {
    case rawFileMissing(String)

    var errorDescription: String? {
        switch self {
        case let .rawFileMissing(name):
            "找不到会话原始文件：\(name)。"
        }
    }
}

/// Offline analysis uses the same causal runtime scheduler as live capture,
/// including resets at accepted sequence gaps.  SQI remains explicitly marked
/// provisional until the reference preprocessing/CPE path is approved.
nonisolated enum CaptureSessionAnalysisService {
    static let analysisDirectoryName = "analysis"
    static let schemaVersion = "ppgcollector.analysis.v1"

    static func analyze(
        session: StoredCaptureSession,
        profile: PPGLiveMetricRuntimeProfile = .iosBaseline01,
        createdUTC: Date = Date(),
        cancellationCheck: () throws -> Void = {
            try Task.checkCancellation()
        },
        progress: (CaptureSessionAnalysisProgress) -> Void = { _ in }
    ) throws -> CaptureSessionAnalysisReport {
        let rawURL = rawFileURL(for: session)
        guard FileManager.default.fileExists(atPath: rawURL.path) else {
            throw CaptureSessionAnalysisError.rawFileMissing(rawURL.lastPathComponent)
        }

        let rawData = try Data(contentsOf: rawURL, options: .mappedIfSafe)
        let rawSHA256 = SHA256.hash(data: rawData)
            .map { String(format: "%02x", $0) }
            .joined()

        var pipeline = CUPStreamingPipeline(recentSampleCapacity: 0)
        var scheduler = PPGLiveMetricWindowScheduler(profile: profile)
        var acceptedSampleCount = 0
        var acceptedFrameCount = 0
        var windows: [CaptureSessionAnalysisWindow] = []
        var processedRawByteCount = CUPRawFileReader.magic.count
        var processedRecordCount = 0

        progress(
            CaptureSessionAnalysisProgress(
                processedRawByteCount: processedRawByteCount,
                totalRawByteCount: rawData.count,
                processedRecordCount: 0,
                acceptedSampleCount: 0,
                completedWindowCount: 0
            )
        )

        let scan = try CUPRawFileReader.scanRecords(
            url: rawURL,
            cancellationCheck: cancellationCheck
        ) { record in
            try cancellationCheck()
            let decodedFrames = pipeline.receive(record.chunk)
            let request = scheduler.ingest(
                decodedFrames: decodedFrames,
                acceptedSampleStartIndex: UInt64(acceptedSampleCount),
                measuredAt: Date(
                    timeIntervalSince1970: Double(acceptedSampleCount)
                        / Double(profile.sampleRateHz)
                )
            )
            acceptedSampleCount += decodedFrames.reduce(into: 0) {
                count,
                decoded in
                if decoded.isAccepted {
                    count += decoded.frame.samples.count
                    acceptedFrameCount += 1
                }
            }

            processedRecordCount += 1
            processedRawByteCount += 12 + record.chunk.count

            if let request {
                let result = PPGLiveMetricAnalyzer.analyze(request, profile: profile)
                windows.append(window(from: result))
            }

            if processedRecordCount.isMultiple(of: 4) {
                progress(
                    CaptureSessionAnalysisProgress(
                        processedRawByteCount: min(
                            processedRawByteCount,
                            rawData.count
                        ),
                        totalRawByteCount: rawData.count,
                        processedRecordCount: processedRecordCount,
                        acceptedSampleCount: acceptedSampleCount,
                        completedWindowCount: windows.count
                    )
                )
            }
        }
        try cancellationCheck()

        progress(
            CaptureSessionAnalysisProgress(
                processedRawByteCount: scan.validByteCount,
                totalRawByteCount: rawData.count,
                processedRecordCount: scan.recordCount,
                acceptedSampleCount: acceptedSampleCount,
                completedWindowCount: windows.count
            )
        )

        let diagnostics = pipeline.diagnostics
        let heartRates = windows.compactMap(\.heartRateBPM)
        let provisionalSQIs = windows.compactMap(\.provisionalSQI)
        var warnings: [String] = []
        if let tailIssue = scan.tailIssue {
            warnings.append("raw 尾部未纳入分析：\(tailIssue.description)")
        }
        if diagnostics.sequenceStats.missingFrames > 0 {
            warnings.append("检测到 \(diagnostics.sequenceStats.missingFrames) 个缺失帧；间断前后分别分析。")
        }
        if !profile.signalQualityIsApproved {
            warnings.append("SQI 为暂定结果，尚未通过参考预处理/CPE 准入，不可用于临床判断。")
        }

        return CaptureSessionAnalysisReport(
            schemaVersion: schemaVersion,
            sourceSessionID: session.sessionID,
            sourceBaseName: session.baseName,
            sourceRawSHA256: rawSHA256,
            sourceRawByteCount: rawData.count,
            createdUTC: createdUTC,
            runtimeProfile: profile.identifier,
            preprocessProfile: profile.preprocessingProfile.identifier,
            heartRateAlgorithmVersion: profile.heartRateConfiguration.algorithmVersion,
            signalQualityAlgorithmVersion: profile.signalQualityConfiguration.algorithmVersion,
            signalQualityIsApproved: profile.signalQualityIsApproved,
            input: CaptureSessionAnalysisInput(
                rawRecordCount: scan.recordCount,
                acceptedFrameCount: acceptedFrameCount,
                acceptedSampleCount: diagnostics.acceptedSamples,
                missingFrameCount: diagnostics.sequenceStats.missingFrames,
                duplicateFrameCount: diagnostics.sequenceStats.duplicateFrames,
                outOfOrderFrameCount: diagnostics.sequenceStats.outOfOrderFrames,
                structurallyInvalidFrameCount: diagnostics.structurallyInvalidFrames,
                discardedByteCount: diagnostics.decoderStats.bytesDiscarded,
                trailingRawByteCount: scan.trailingByteCount
            ),
            summary: CaptureSessionAnalysisSummary(
                analysisWindowCount: windows.count,
                validHeartRateWindowCount: heartRates.count,
                provisionalSQIWindowCount: provisionalSQIs.count,
                heartRateBPMMean: mean(heartRates),
                heartRateBPMMinimum: heartRates.min(),
                heartRateBPMMaximum: heartRates.max()
            ),
            windows: windows,
            warnings: warnings
        )
    }

    @discardableResult
    static func save(
        _ report: CaptureSessionAnalysisReport,
        for session: StoredCaptureSession,
        fileManager: FileManager = .default
    ) throws -> URL {
        let directoryURL = session.directoryURL.appendingPathComponent(
            analysisDirectoryName,
            isDirectory: true
        )
        try fileManager.createDirectory(
            at: directoryURL,
            withIntermediateDirectories: true
        )
        let baseName = "\(safeFileComponent(report.runtimeProfile))_\(utcFileTimestamp(report.createdUTC))"
        var suffix = 1
        var destinationURL = directoryURL.appendingPathComponent(
            "\(baseName).analysis.json"
        )
        while fileManager.fileExists(atPath: destinationURL.path) {
            suffix += 1
            destinationURL = directoryURL.appendingPathComponent(
                "\(baseName)_\(suffix).analysis.json"
            )
        }

        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try encoder.encode(report).write(to: destinationURL, options: .atomic)
        return destinationURL
    }

    @discardableResult
    static func analyzeAndSave(
        session: StoredCaptureSession,
        profile: PPGLiveMetricRuntimeProfile = .iosBaseline01,
        createdUTC: Date = Date(),
        cancellationCheck: () throws -> Void = {
            try Task.checkCancellation()
        },
        progress: (CaptureSessionAnalysisProgress) -> Void = { _ in }
    ) throws -> URL {
        let report = try analyze(
            session: session,
            profile: profile,
            createdUTC: createdUTC,
            cancellationCheck: cancellationCheck,
            progress: progress
        )
        return try save(report, for: session)
    }

    static func rawFileURL(for session: StoredCaptureSession) -> URL {
        session.directoryURL.appendingPathComponent("\(session.baseName).cupraw")
    }

    static func listArtifacts(
        for session: StoredCaptureSession,
        fileManager: FileManager = .default
    ) -> [CaptureSessionAnalysisArtifact] {
        let directoryURL = session.directoryURL.appendingPathComponent(
            analysisDirectoryName,
            isDirectory: true
        )
        let urls = (try? fileManager.contentsOfDirectory(
            at: directoryURL,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        )) ?? []
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return urls.compactMap { url in
            guard url.lastPathComponent.hasSuffix(".analysis.json"),
                  let data = try? Data(contentsOf: url),
                  let report = try? decoder.decode(
                    CaptureSessionAnalysisReport.self,
                    from: data
                  ) else {
                return nil
            }
            return CaptureSessionAnalysisArtifact(url: url, report: report)
        }
        .sorted {
            if $0.report.createdUTC == $1.report.createdUTC {
                return $0.url.lastPathComponent < $1.url.lastPathComponent
            }
            return $0.report.createdUTC > $1.report.createdUTC
        }
    }

    private static func window(
        from result: PPGLiveMetricAnalysisResult
    ) -> CaptureSessionAnalysisWindow {
        let heartRate = result.snapshot.heartRateBPM
        let sqi = result.provisionalSignalQuality
        return CaptureSessionAnalysisWindow(
            endSampleIndex: result.request.windowEndSampleIndex,
            endTimeSeconds: result.request.windowEndTimeSeconds,
            continuityGeneration: result.request.generation,
            heartRateBPM: heartRate.isValid ? heartRate.value : nil,
            heartRateReason: heartRate.unavailableReason?.rawValue,
            provisionalSQI: sqi?.isValid == true ? sqi?.sqi : nil,
            provisionalSQIGrade: sqi?.grade.rawValue,
            provisionalSQIReason: sqi?.reasonCode
        )
    }

    private static func mean(_ values: [Double]) -> Double? {
        guard !values.isEmpty else {
            return nil
        }
        return values.reduce(0, +) / Double(values.count)
    }

    private static func safeFileComponent(_ value: String) -> String {
        value.replacingOccurrences(
            of: "[^A-Za-z0-9._-]",
            with: "-",
            options: .regularExpression
        )
    }

    private static func utcFileTimestamp(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyyyMMdd'T'HHmmss'Z'"
        return formatter.string(from: date)
    }
}
