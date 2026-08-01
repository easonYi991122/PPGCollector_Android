import Foundation
import Testing
@testable import PPGCollector

struct CaptureSessionInspectionServiceTests {
    @Test
    func csvScanFindsTheLastSafeNewline() {
        let complete = Data(
            (
                CaptureCSVSchema.header
                    + "v1,id,0,0.0,1,1,0,10,20\n"
            ).utf8
        )
        let completeReport = CaptureSessionInspectionService.scanCSV(
            data: complete
        )
        #expect(completeReport.hasExpectedHeader)
        #expect(completeReport.completeDataRowCount == 1)
        #expect(!completeReport.hasTruncatedFinalLine)
        #expect(completeReport.trailingByteCount == 0)

        var truncated = complete
        truncated.append(contentsOf: Data("partial,row".utf8))
        let truncatedReport = CaptureSessionInspectionService.scanCSV(
            data: truncated
        )
        #expect(truncatedReport.hasExpectedHeader)
        #expect(truncatedReport.completeDataRowCount == 1)
        #expect(truncatedReport.hasTruncatedFinalLine)
        #expect(truncatedReport.validByteCount == complete.count)
        #expect(truncatedReport.trailingByteCount == 11)
    }

    @Test
    func streamingCSVScanMatchesInMemoryScan() throws {
        let data = Data(
            (
                CaptureCSVSchema.header
                    + "v1,id,0,0.0,1,1,0,10,20\n"
                    + "v1,id,1,0.01,1,1,1,11,21\n"
                    + "partial,row"
            ).utf8
        )
        let url = temporaryURL()
        defer { try? FileManager.default.removeItem(at: url) }
        try data.write(to: url)

        let streamed = try CaptureSessionInspectionService.scanCSV(
            url: url
        )
        let inMemory = CaptureSessionInspectionService.scanCSV(data: data)

        #expect(streamed == inMemory)
    }

    @Test
    func streamingCSVScanStopsAtTheInjectedCancellationBoundary() throws {
        let url = temporaryURL()
        defer { try? FileManager.default.removeItem(at: url) }
        try Data(repeating: 0x41, count: 192 * 1024).write(to: url)

        var cancellationChecks = 0
        #expect(throws: CancellationError.self) {
            try CaptureSessionInspectionService.scanCSV(
                url: url,
                cancellationCheck: {
                    cancellationChecks += 1
                    if cancellationChecks == 2 {
                        throw CancellationError()
                    }
                }
            )
        }
        #expect(cancellationChecks == 2)
    }

    private func temporaryURL() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent(
            "CaptureSessionInspectionTests-\(UUID().uuidString).csv"
        )
    }
}
