import Foundation
import Testing
@testable import PPGCollector

struct PPGExpertDiagnosticsServiceTests {
    @Test
    func reusesProductionRawPipelineAndBuildsExpertWindow() async throws {
        let rootURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("PPGExpertDiagnosticsTests-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: rootURL) }

        let writer = try CaptureSessionWriter(
            configuration: CaptureSessionConfiguration(
                sessionID: UUID(),
                baseName: "expert",
                startedUTC: Date(timeIntervalSince1970: 1_700_000_000),
                softVersion: "test",
                algorithmVersion: "test",
                preprocessProfile: "ios_baseline_0.1",
                protocolProfile: "cup_batch_v1_draft",
                transportProfile: "test",
                device: CaptureDeviceContext(
                    name: "CUP-SIM",
                    identifier: UUID(),
                    serviceUUID: "service",
                    notifyCharacteristicUUID: "notify"
                )
            ),
            sessionsRootURL: rootURL
        )
        var pipeline = CUPStreamingPipeline()
        for sequence in 0..<16 {
            let chunk = makeCUPTestFrame(sequence: UInt8(sequence))
            _ = try await writer.append(
                CUPStreamChunkEvent(
                    hostMonotonicNanoseconds: UInt64(sequence),
                    data: chunk,
                    decodedFrames: pipeline.receive(chunk)
                )
            )
        }
        _ = try await writer.finish(
            reason: .user,
            integrity: CaptureIntegritySnapshot()
        )

        let session = try #require(
            CaptureSessionRepository.listSessions(rootURL: rootURL).first
        )
        let report = try PPGExpertDiagnosticsService.analyze(session: session)

        #expect(report.input.rawRecordCount == 16)
        #expect(report.input.acceptedFrameCount == 16)
        #expect(report.input.acceptedSampleCount == 800)
        #expect(report.rawRED.count == 800)
        #expect(report.preprocessedIR.count == 800)
        #expect(report.windows.count == 1)
        #expect(report.windows[0].startSampleIndex == 0)
        #expect(report.windows[0].endSampleIndex == 799)
    }
}
