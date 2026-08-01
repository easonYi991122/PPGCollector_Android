import Foundation
import Testing
@testable import PPGCollector

struct CaptureSessionWriterTests {
    @Test
    func rawFirstSessionRoundTripsAndFinalizesMetadata() async throws {
        let rootURL = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: rootURL) }

        let configuration = makeConfiguration(baseName: "capture_001")
        let writer = try CaptureSessionWriter(
            configuration: configuration,
            sessionsRootURL: rootURL
        )
        var pipeline = CUPStreamingPipeline()
        let firstChunk = makeCUPTestFrame(sequence: 1)
        let gapChunk = makeCUPTestFrame(sequence: 3)
        let duplicateChunk = makeCUPTestFrame(sequence: 3)

        let firstSnapshot = try await writer.append(
            CUPStreamChunkEvent(
                hostMonotonicNanoseconds: 1_000,
                data: firstChunk,
                decodedFrames: pipeline.receive(firstChunk)
            )
        )
        #expect(firstSnapshot.rawChunkCount == 1)
        #expect(firstSnapshot.csvRows == 50)

        _ = try await writer.append(
            CUPStreamChunkEvent(
                hostMonotonicNanoseconds: 2_000,
                data: gapChunk,
                decodedFrames: pipeline.receive(gapChunk)
            )
        )
        _ = try await writer.append(
            CUPStreamChunkEvent(
                hostMonotonicNanoseconds: 3_000,
                data: duplicateChunk,
                decodedFrames: pipeline.receive(duplicateChunk)
            )
        )

        let summary = try await writer.finish(
            reason: .user,
            integrity: CaptureIntegritySnapshot(
                invalidFrames: 2,
                discardedBytes: 17
            )
        )
        #expect(summary.complete)
        #expect(summary.writer.rawChunkCount == 3)
        #expect(summary.writer.csvRows == 100)
        #expect(summary.writer.acceptedFrames == 2)
        #expect(summary.writer.missingFrames == 1)
        #expect(summary.writer.duplicateFrames == 1)

        let rawURL = summary.directoryURL.appendingPathComponent(
            "capture_001.cupraw"
        )
        let records = try CUPRawFileReader.read(url: rawURL)
        #expect(records.map(\.hostMonotonicNanoseconds) == [1_000, 2_000, 3_000])
        #expect(records.map(\.chunk) == [firstChunk, gapChunk, duplicateChunk])

        let csvURL = summary.directoryURL.appendingPathComponent(
            "capture_001.csv"
        )
        let csv = try String(contentsOf: csvURL, encoding: .utf8)
        let lines = csv.split(separator: "\n", omittingEmptySubsequences: false)
        #expect(lines.count == 102)
        #expect(
            lines.first?.hasPrefix("schema_version,session_id,sample_index")
                == true
        )
        #expect(lines[1].contains(",0,0.000000,1000,1,0,100000,120000,"))
        #expect(lines[100].contains(",99,0.990000,2000,3,49,"))

        let metadataURL = summary.directoryURL.appendingPathComponent(
            "capture_001.session.json"
        )
        let metadataData = try Data(contentsOf: metadataURL)
        let metadata = try #require(
            JSONSerialization.jsonObject(with: metadataData)
                as? [String: Any]
        )
        #expect(metadata["schema_version"] as? String == "ppgcollector_session_v1")
        #expect(metadata["complete"] as? Bool == true)
        #expect(metadata["stop_reason"] as? String == "user")
        #expect(metadata["sample_count"] as? Int == 100)
        #expect(metadata["invalid_frames"] as? Int == 2)
        #expect(metadata["discarded_bytes"] as? Int == 17)
    }

    @Test
    func duplicateSessionDirectoryIsNeverOverwritten() async throws {
        let rootURL = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: rootURL) }
        let configuration = makeConfiguration(baseName: "same_name")
        let first = try CaptureSessionWriter(
            configuration: configuration,
            sessionsRootURL: rootURL
        )

        do {
            _ = try CaptureSessionWriter(
                configuration: configuration,
                sessionsRootURL: rootURL
            )
            Issue.record("同名 writer 不应创建成功。")
        } catch let error as CaptureSessionWriterError {
            guard case .sessionAlreadyExists("same_name") = error else {
                Issue.record("返回了错误的失败原因：\(error)")
                return
            }
        }

        _ = try await first.finish(
            reason: .user,
            integrity: CaptureIntegritySnapshot()
        )
    }

    @Test
    func insufficientStorageRejectsBeforeCreatingSessionFiles()
        async throws
    {
        let rootURL = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: rootURL) }
        let configuration = makeConfiguration(
            baseName: "low_storage"
        )
        let provider = CaptureStorageCapacityProvider { _ in
            CaptureSessionWriter
                .minimumAvailableCapacityForStartBytes - 1
        }

        do {
            _ = try CaptureSessionWriter(
                configuration: configuration,
                sessionsRootURL: rootURL,
                storageCapacityProvider: provider
            )
            Issue.record("低于安全空间门槛时不应创建 writer。")
        } catch let error as CaptureSessionWriterError {
            guard case .insufficientStorage = error else {
                Issue.record("返回了错误的失败原因：\(error)")
                return
            }
        }

        let sessionURL = rootURL.appendingPathComponent(
            configuration.baseName,
            isDirectory: true
        )
        #expect(
            !FileManager.default.fileExists(
                atPath: sessionURL.path
            )
        )
        let rootContents = try FileManager.default
            .contentsOfDirectory(atPath: rootURL.path)
        #expect(rootContents.isEmpty)
    }

    @Test
    func exactStorageThresholdAllowsAtomicPreflight() async throws {
        let rootURL = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: rootURL) }
        let configuration = makeConfiguration(
            baseName: "storage_boundary"
        )
        let provider = CaptureStorageCapacityProvider { _ in
            CaptureSessionWriter
                .minimumAvailableCapacityForStartBytes
        }
        let writer = try CaptureSessionWriter(
            configuration: configuration,
            sessionsRootURL: rootURL,
            storageCapacityProvider: provider
        )
        let directoryURL = await writer.directoryURL

        #expect(
            FileManager.default.fileExists(
                atPath: directoryURL.path
            )
        )
        #expect(
            try FileManager.default
                .contentsOfDirectory(atPath: directoryURL.path)
                .sorted()
                == [
                    "storage_boundary.csv",
                    "storage_boundary.cupraw",
                    "storage_boundary.session.json"
                ]
        )
        #expect(
            try await writer.discardIfEmptyBeforeRecording()
        )
        #expect(
            !FileManager.default.fileExists(
                atPath: directoryURL.path
            )
        )
    }

    @Test
    func unavailableCapacityEstimateDoesNotFakeLowStorage()
        async throws
    {
        let rootURL = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: rootURL) }
        let configuration = makeConfiguration(
            baseName: "capacity_unavailable"
        )
        let provider = CaptureStorageCapacityProvider { _ in
            throw CocoaError(.fileReadUnknown)
        }
        let writer = try CaptureSessionWriter(
            configuration: configuration,
            sessionsRootURL: rootURL,
            storageCapacityProvider: provider
        )
        let directoryURL = await writer.directoryURL

        #expect(
            FileManager.default.fileExists(
                atPath: directoryURL.path
            )
        )
        #expect(
            try await writer.discardIfEmptyBeforeRecording()
        )
    }

    @Test
    func concurrentFinishIsIdempotentAndRejectsLaterWrites()
        async throws
    {
        let rootURL = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: rootURL) }
        let writer = try CaptureSessionWriter(
            configuration: makeConfiguration(
                baseName: "idempotent_stop"
            ),
            sessionsRootURL: rootURL
        )
        var pipeline = CUPStreamingPipeline()
        let frame = makeCUPTestFrame(sequence: 5)
        let event = CUPStreamChunkEvent(
            hostMonotonicNanoseconds: 5_000,
            data: frame,
            decodedFrames: pipeline.receive(frame)
        )
        _ = try await writer.append(event)

        let firstTask = Task {
            try await writer.finish(
                reason: .user,
                integrity: CaptureIntegritySnapshot()
            )
        }
        let secondTask = Task {
            try await writer.finish(
                reason: .deviceDisconnect,
                integrity: CaptureIntegritySnapshot()
            )
        }
        let first = try await firstTask.value
        let second = try await secondTask.value

        #expect(first == second)
        #expect(
            first.stopReason == .user
                || first.stopReason == .deviceDisconnect
        )

        let urls = CaptureSessionRepository.expectedFileURLs(
            in: first.directoryURL
        )
        let sizesBefore = try urls.map {
            try Data(contentsOf: $0).count
        }
        do {
            _ = try await writer.append(event)
            Issue.record("finish 后 append 不应成功。")
        } catch let error as CaptureSessionWriterError {
            guard case .writerClosed = error else {
                Issue.record("返回了错误的失败原因：\(error)")
                return
            }
        }
        let third = try await writer.finish(
            reason: .dataTimeout,
            integrity: CaptureIntegritySnapshot()
        )
        let sizesAfter = try urls.map {
            try Data(contentsOf: $0).count
        }

        #expect(third == first)
        #expect(sizesAfter == sizesBefore)
    }

    @Test
    func viewExitProducesAnHonestCompleteFinalization() async throws {
        let rootURL = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: rootURL) }
        let writer = try CaptureSessionWriter(
            configuration: makeConfiguration(
                baseName: "view_exit"
            ),
            sessionsRootURL: rootURL
        )

        let summary = try await writer.finish(
            reason: .viewExit,
            integrity: CaptureIntegritySnapshot()
        )
        let metadata = try CaptureSessionMetadataCodec.decode(
            Data(
                contentsOf:
                    summary.directoryURL.appendingPathComponent(
                        "view_exit.session.json"
                    )
            )
        )

        #expect(summary.complete)
        #expect(summary.stopReason == .viewExit)
        #expect(metadata.complete)
        #expect(metadata.stopReason == .viewExit)
    }

    @Test
    func preflightCancellationRemovesOnlyTheEmptyNewSession()
        async throws
    {
        let rootURL = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: rootURL) }
        let writer = try CaptureSessionWriter(
            configuration: makeConfiguration(
                baseName: "preflight_cancel"
            ),
            sessionsRootURL: rootURL
        )
        let directoryURL = await writer.directoryURL

        let discarded = try await writer.discardIfEmptyBeforeRecording()

        #expect(discarded)
        #expect(!FileManager.default.fileExists(atPath: directoryURL.path))
        do {
            _ = try await writer.append(
                CUPStreamChunkEvent(
                    hostMonotonicNanoseconds: 1,
                    data: Data([0x01]),
                    decodedFrames: []
                )
            )
            Issue.record("preflight 撤销后 append 不应成功。")
        } catch let error as CaptureSessionWriterError {
            guard case .writerClosed = error else {
                Issue.record("返回了错误的失败原因：\(error)")
                return
            }
        }
    }

    @Test
    func completedMetricsUseSessionRelativeTimeAndPersistSQI()
        async throws
    {
        let rootURL = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: rootURL) }
        let profile = PPGLiveMetricRuntimeProfile.iosBaseline01
        let writer = try CaptureSessionWriter(
            configuration: makeConfiguration(
                baseName: "metric_rows",
                algorithmVersion: profile.identifier,
                preprocessProfile:
                    profile.preprocessingProfile.identifier
            ),
            sessionsRootURL: rootURL
        )
        var pipeline = CUPStreamingPipeline()
        let firstFrame = makeCUPTestFrame(sequence: 1)
        let secondFrame = makeCUPTestFrame(sequence: 2)
        _ = try await writer.append(
            CUPStreamChunkEvent(
                hostMonotonicNanoseconds: 1_000,
                data: firstFrame,
                decodedFrames: pipeline.receive(firstFrame),
                acceptedSampleStartIndex: 100,
                metrics: .warmingUp()
            )
        )

        let measuredAt = Date(timeIntervalSince1970: 1_800_000_000)
        let metrics = LiveMetricSnapshot.runtime(
            heartRateBPM: .valid(
                72.125,
                measuredAt: measuredAt,
                algorithmVersion: "ppg-ios-hr-0.1",
                sourceSampleIndex: 149,
                sourceTimeSeconds: 1.49
            ),
            signalQuality: .valid(
                0.875,
                measuredAt: measuredAt,
                algorithmVersion: "ppg-ios-sqi-0.1",
                sourceSampleIndex: 149,
                sourceTimeSeconds: 1.49,
                isProvisional: true
            )
        )
        _ = try await writer.append(
            CUPStreamChunkEvent(
                hostMonotonicNanoseconds: 2_000,
                data: secondFrame,
                decodedFrames: pipeline.receive(secondFrame),
                acceptedSampleStartIndex: 150,
                metrics: metrics
            )
        )
        let summary = try await writer.finish(
            reason: .user,
            integrity: CaptureIntegritySnapshot()
        )

        let csv = try String(
            contentsOf:
                summary.directoryURL.appendingPathComponent(
                    "metric_rows.csv"
                ),
            encoding: .utf8
        )
        let lines = csv.split(
            separator: "\n",
            omittingEmptySubsequences: true
        )
        let firstFields = lines[1].split(
            separator: ",",
            omittingEmptySubsequences: false
        )
        let secondEventFields = lines[51].split(
            separator: ",",
            omittingEmptySubsequences: false
        )
        #expect(firstFields[9].isEmpty)
        #expect(firstFields[10] == "false")
        #expect(secondEventFields[2] == "50")
        #expect(secondEventFields[9] == "72.125000")
        #expect(secondEventFields[10] == "true")
        #expect(secondEventFields[11] == "0.490000")
        #expect(secondEventFields[12].isEmpty)
        #expect(secondEventFields[13] == "false")
        #expect(secondEventFields[14].isEmpty)
        #expect(secondEventFields[15] == "0.875000")
        #expect(secondEventFields[16] == "true")
        #expect(secondEventFields[17] == "0.490000")
        #expect(secondEventFields[19] == profile.identifier)
        #expect(
            secondEventFields[20]
                == profile.preprocessingProfile.identifier
        )

        let metadata = try CaptureSessionMetadataCodec.decode(
            Data(
                contentsOf:
                    summary.directoryURL.appendingPathComponent(
                        "metric_rows.session.json"
                    )
            )
        )
        #expect(metadata.algVersion == "ppg-ios-live-0.1")
        #expect(metadata.preprocessProfile == "ios_baseline_0.1")
    }

    private func temporaryRoot() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent("PPGCollectorTests-\(UUID().uuidString)")
    }

    private func makeConfiguration(
        baseName: String,
        algorithmVersion: String = "unavailable",
        preprocessProfile: String = "raw-only-0.1"
    ) -> CaptureSessionConfiguration {
        CaptureSessionConfiguration(
            sessionID: UUID(uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")!,
            baseName: baseName,
            startedUTC: Date(timeIntervalSince1970: 1_700_000_000),
            softVersion: "1.0+1",
            algorithmVersion: algorithmVersion,
            preprocessProfile: preprocessProfile,
            protocolProfile: "cup_batch_v1_draft",
            transportProfile: "cup-nus-bringup-0.1",
            device: CaptureDeviceContext(
                name: "CUP-SIM",
                identifier: UUID(
                    uuidString: "11111111-2222-3333-4444-555555555555"
                )!,
                serviceUUID: "6E400001-B5A3-F393-E0A9-E50E24DCCA9E",
                notifyCharacteristicUUID:
                    "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
            )
        )
    }
}
