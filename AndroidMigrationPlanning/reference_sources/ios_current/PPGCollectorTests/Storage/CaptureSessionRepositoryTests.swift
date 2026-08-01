import Foundation
import Testing
@testable import PPGCollector

struct CaptureSessionRepositoryTests {
    @Test
    func missingRootReturnsAnEmptyList() throws {
        let rootURL = temporaryRoot()
        #expect(
            try CaptureSessionRepository.listSessions(rootURL: rootURL)
                .isEmpty
        )
    }

    @Test
    func finalizedWriterSessionIsDiscoveredAndVerified() async throws {
        let rootURL = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: rootURL) }

        let configuration = makeConfiguration(baseName: "discover_me")
        let writer = try CaptureSessionWriter(
            configuration: configuration,
            sessionsRootURL: rootURL
        )
        var pipeline = CUPStreamingPipeline()
        let frame = makeCUPTestFrame(sequence: 8)
        _ = try await writer.append(
            CUPStreamChunkEvent(
                hostMonotonicNanoseconds: 99_000,
                data: frame,
                decodedFrames: pipeline.receive(frame)
            )
        )
        _ = try await writer.finish(
            reason: .user,
            integrity: CaptureIntegritySnapshot()
        )

        let sessions = try CaptureSessionRepository.listSessions(
            rootURL: rootURL
        )
        let session = try #require(sessions.first)
        #expect(sessions.count == 1)
        #expect(session.baseName == "discover_me")
        #expect(session.isVerifiedComplete)
        #expect(session.sampleCount == 50)
        #expect(session.rawChunkCount == 1)
        #expect(session.sessionID == "BBBBBBBB-CCCC-DDDD-EEEE-FFFFFFFFFFFF")
        #expect(session.softVersion == "1.0+1")
        #expect(session.algorithmVersion == "unavailable")
        #expect(session.preprocessProfile == "raw-only-0.1")
        #expect(session.protocolProfile == "cup_batch_v1_draft")
        #expect(session.transportProfile == "cup-nus-bringup-0.1")
        #expect(session.deviceName == "CUP-SIM")
        #expect(session.startedUTC == configuration.startedUTC)
        #expect(session.endedUTC != nil)
        #expect(session.shareableFileURLs.count == 3)
        #expect(session.totalBytes > 0)

        let inspection = CaptureSessionInspectionService.inspect(session)
        #expect(inspection.isVerifiedConsistent)
        #expect(inspection.findings.isEmpty)
        #expect(inspection.replay?.readMode == .streaming)
        #expect(
            inspection.replay?.peakRawRecordBufferBytes
                == 12 + CUPBatchProtocolV1.frameLength
        )
        #expect(inspection.replay?.acceptedSamples == 50)
        #expect(inspection.csv?.completeDataRowCount == 50)
    }

    @Test
    func missingExpectedFileCannotBeReportedAsVerifiedComplete() throws {
        let rootURL = temporaryRoot()
        let directoryURL = rootURL.appendingPathComponent(
            "partial",
            isDirectory: true
        )
        defer { try? FileManager.default.removeItem(at: rootURL) }
        try FileManager.default.createDirectory(
            at: directoryURL,
            withIntermediateDirectories: true
        )
        try CUPRawFileReader.magic.write(
            to: directoryURL.appendingPathComponent("partial.cupraw")
        )
        let metadata = Data(
            """
            {
              "complete": true,
              "sample_count": 12,
              "raw_chunk_count": 1,
              "stop_reason": "user"
            }
            """.utf8
        )
        try metadata.write(
            to: directoryURL.appendingPathComponent(
                "partial.session.json"
            )
        )

        let session = try #require(
            CaptureSessionRepository.listSessions(rootURL: rootURL).first
        )
        #expect(session.complete == true)
        #expect(!session.hasAllExpectedFiles)
        #expect(!session.isVerifiedComplete)
        #expect(session.shareableFileURLs.count == 2)
    }

    private func temporaryRoot() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent(
                "PPGCollectorRepositoryTests-\(UUID().uuidString)"
            )
    }

    private func makeConfiguration(
        baseName: String
    ) -> CaptureSessionConfiguration {
        CaptureSessionConfiguration(
            sessionID: UUID(
                uuidString: "BBBBBBBB-CCCC-DDDD-EEEE-FFFFFFFFFFFF"
            )!,
            baseName: baseName,
            startedUTC: Date(timeIntervalSince1970: 1_700_000_000),
            softVersion: "1.0+1",
            algorithmVersion: "unavailable",
            preprocessProfile: "raw-only-0.1",
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
