import Foundation
import Testing
@testable import PPGCollector

struct CaptureSessionAnalysisServiceTests {
    @Test
    func versionedAnalysisPreservesAcquisitionFilesAndNeverOverwrites() async throws {
        let rootURL = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: rootURL) }
        let session = try await makeStoredSession(rootURL: rootURL)
        let rawURL = CaptureSessionAnalysisService.rawFileURL(for: session)
        let csvURL = session.directoryURL.appendingPathComponent("offline.csv")
        let metadataURL = session.directoryURL.appendingPathComponent("offline.session.json")
        let originalRaw = try Data(contentsOf: rawURL)
        let originalCSV = try Data(contentsOf: csvURL)
        let originalMetadata = try Data(contentsOf: metadataURL)
        let createdUTC = Date(timeIntervalSince1970: 1_700_100_000)

        let report = try CaptureSessionAnalysisService.analyze(
            session: session,
            createdUTC: createdUTC
        )
        let firstURL = try CaptureSessionAnalysisService.save(
            report,
            for: session
        )
        let secondURL = try CaptureSessionAnalysisService.save(
            report,
            for: session
        )

        #expect(report.schemaVersion == "ppgcollector.analysis.v1")
        #expect(report.sourceSessionID == "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")
        #expect(report.sourceRawByteCount == originalRaw.count)
        #expect(report.sourceRawSHA256.count == 64)
        #expect(report.runtimeProfile == "ppg-ios-live-0.1")
        #expect(report.preprocessProfile == "ios_baseline_0.1")
        #expect(!report.signalQualityIsApproved)
        #expect(report.input.acceptedSampleCount == 900)
        #expect(report.input.rawRecordCount == 18)
        #expect(report.input.acceptedFrameCount == 18)
        #expect(report.input.duplicateFrameCount == 0)
        #expect(report.input.outOfOrderFrameCount == 0)
        #expect(report.input.structurallyInvalidFrameCount == 0)
        #expect(report.input.discardedByteCount == 0)
        #expect(report.input.trailingRawByteCount == 0)
        #expect(report.summary.analysisWindowCount == 2)
        #expect(firstURL.lastPathComponent.hasSuffix(".analysis.json"))
        #expect(secondURL.lastPathComponent.hasSuffix("_2.analysis.json"))
        #expect(
            CaptureSessionAnalysisService.listArtifacts(for: session)
                .map(\.url) == [firstURL, secondURL]
        )
        let artifacts = CaptureSessionAnalysisService.listArtifacts(for: session)
        #expect(
            CaptureSessionAnalysisHistoryFilter.runtimeProfiles(
                in: artifacts
            ) == ["ppg-ios-live-0.1"]
        )
        #expect(
            CaptureSessionAnalysisHistoryFilter.filter(
                artifacts,
                runtimeProfile: "missing-profile"
            ).isEmpty
        )
        #expect(try Data(contentsOf: rawURL) == originalRaw)
        #expect(try Data(contentsOf: csvURL) == originalCSV)
        #expect(try Data(contentsOf: metadataURL) == originalMetadata)

        let data = try Data(contentsOf: firstURL)
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        #expect(try decoder.decode(CaptureSessionAnalysisReport.self, from: data) == report)
    }

    @Test
    func missingFrameSplitsOfflineAnalysisContinuity() async throws {
        let rootURL = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: rootURL) }
        let session = try await makeStoredSession(
            rootURL: rootURL,
            sequences: Array(1...16) + Array(18...34)
        )

        let report = try CaptureSessionAnalysisService.analyze(session: session)

        #expect(report.input.missingFrameCount == 1)
        #expect(report.summary.analysisWindowCount == 2)
        #expect(report.windows.map(\.continuityGeneration) == [0, 1])
        #expect(report.warnings.contains { $0.contains("缺失帧") })
    }

    @Test
    func cancellationStopsStreamingAnalysisBeforeWritingAnArtifact() async throws {
        let rootURL = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: rootURL) }
        let session = try await makeStoredSession(rootURL: rootURL)
        var cancellationChecks = 0

        #expect(throws: CancellationError.self) {
            try CaptureSessionAnalysisService.analyzeAndSave(
                session: session,
                cancellationCheck: {
                    cancellationChecks += 1
                    if cancellationChecks == 4 {
                        throw CancellationError()
                    }
                }
            )
        }
        #expect(CaptureSessionAnalysisService.listArtifacts(for: session).isEmpty)
    }

    @Test
    func progressReportsRawScanAndFinalAnalysisTotals() async throws {
        let rootURL = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: rootURL) }
        let session = try await makeStoredSession(
            rootURL: rootURL,
            sequences: Array(1...60)
        )
        var updates: [CaptureSessionAnalysisProgress] = []

        let report = try CaptureSessionAnalysisService.analyze(
            session: session,
            progress: { updates.append($0) }
        )

        let first = try #require(updates.first)
        let last = try #require(updates.last)
        #expect(first.processedRecordCount == 0)
        #expect(first.acceptedSampleCount == 0)
        #expect(updates.contains { $0.processedRecordCount == 4 })
        #expect(updates.contains { $0.processedRecordCount == 60 })
        #expect(last.processedRecordCount == 60)
        #expect(last.acceptedSampleCount == 3_000)
        #expect(last.completedWindowCount == report.windows.count)
        #expect(last.fractionCompleted == 1)
    }

    @Test @MainActor
    func applicationScopedTaskStoreFinishesAnAnalysisAfterTheCallerReturns() async throws {
        let rootURL = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: rootURL) }
        let session = try await makeStoredSession(rootURL: rootURL)
        let store = CaptureSessionAnalysisTaskStore()
        store.start(session)

        var snapshot: CaptureSessionAnalysisTaskStore.Snapshot?
        for _ in 0..<100 {
            snapshot = store.snapshot(for: session)
            if snapshot?.isRunning == false {
                break
            }
            try await Task.sleep(for: .milliseconds(10))
        }

        #expect(snapshot?.isRunning == false)
        guard case .completed? = snapshot?.status else {
            Issue.record("分析任务未完成：\(String(describing: snapshot?.status))")
            return
        }
        #expect(CaptureSessionAnalysisService.listArtifacts(for: session).count == 1)
    }

    @Test
    func offlineWindowsMatchTheSameProfileLiveRuntimeReplay() async throws {
        let rootURL = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: rootURL) }
        let session = try await makeStoredSession(rootURL: rootURL)
        let rawRecords = try CUPRawFileReader.read(
            url: CaptureSessionAnalysisService.rawFileURL(for: session)
        )
        var pipeline = CUPStreamingPipeline(recentSampleCapacity: 0)
        var scheduler = PPGLiveMetricWindowScheduler()
        var acceptedSampleCount = 0
        var liveResults: [PPGLiveMetricAnalysisResult] = []

        for record in rawRecords {
            let frames = pipeline.receive(record.chunk)
            let request = scheduler.ingest(
                decodedFrames: frames,
                acceptedSampleStartIndex: UInt64(acceptedSampleCount),
                measuredAt: Date(
                    timeIntervalSince1970: Double(acceptedSampleCount) / 100
                )
            )
            acceptedSampleCount += frames.reduce(into: 0) { count, frame in
                if frame.isAccepted { count += frame.frame.samples.count }
            }
            if let request {
                liveResults.append(PPGLiveMetricAnalyzer.analyze(request))
            }
        }

        let offline = try CaptureSessionAnalysisService.analyze(session: session)
        #expect(offline.windows.map(\.endSampleIndex) == liveResults.map(\.request.windowEndSampleIndex))
        #expect(offline.windows.map(\.heartRateBPM) == liveResults.map { result in
            result.snapshot.heartRateBPM.isValid
                ? result.snapshot.heartRateBPM.value
                : nil
        })
        #expect(offline.windows.map(\.provisionalSQI) == liveResults.map { result in
            result.provisionalSignalQuality?.isValid == true
                ? result.provisionalSignalQuality?.sqi
                : nil
        })
    }

    private func makeStoredSession(
        rootURL: URL,
        sequences: [Int] = Array(1...18)
    ) async throws -> StoredCaptureSession {
        let writer = try CaptureSessionWriter(
            configuration: CaptureSessionConfiguration(
                sessionID: UUID(uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")!,
                baseName: "offline",
                startedUTC: Date(timeIntervalSince1970: 1_700_000_000),
                softVersion: "1.0+1",
                algorithmVersion: "unavailable",
                preprocessProfile: "raw-only-0.1",
                protocolProfile: "cup_batch_v1_draft",
                transportProfile: "cup-nus-bringup-0.1",
                device: CaptureDeviceContext(
                    name: "CUP-SIM",
                    identifier: UUID(uuidString: "11111111-2222-3333-4444-555555555555")!,
                    serviceUUID: "6E400001-B5A3-F393-E0A9-E50E24DCCA9E",
                    notifyCharacteristicUUID: "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
                )
            ),
            sessionsRootURL: rootURL
        )
        var pipeline = CUPStreamingPipeline()
        for sequence in sequences {
            let chunk = makeCUPTestFrame(sequence: UInt8(sequence))
            _ = try await writer.append(
                CUPStreamChunkEvent(
                    hostMonotonicNanoseconds: UInt64(sequence) * 1_000_000,
                    data: chunk,
                    decodedFrames: pipeline.receive(chunk)
                )
            )
        }
        _ = try await writer.finish(
            reason: .user,
            integrity: CaptureIntegritySnapshot()
        )
        return try #require(
            CaptureSessionRepository.listSessions(rootURL: rootURL).first
        )
    }

    private func temporaryRoot() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("PPGAnalysisTests-\(UUID().uuidString)")
    }
}
