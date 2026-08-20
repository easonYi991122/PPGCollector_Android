import Combine
import Foundation

/// The recording coordinator consumes this small, observable boundary instead
/// of depending directly on CoreBluetooth. Production uses
/// `BLECentralService`; tests can inject deterministic connection and stream
/// events without constructing `CBCentralManager` or `CBPeripheral`.
@MainActor
protocol CaptureStreamSource: AnyObject {
    var captureStreamEvents:
        AnyPublisher<CUPStreamChunkEvent, Never> { get }
    var captureConnectionPhases:
        AnyPublisher<BLEConnectionPhase, Never> { get }
    var captureStreamFreshness:
        AnyPublisher<StreamFreshness, Never> { get }

    var connectionPhase: BLEConnectionPhase { get }
    var streamFreshness: StreamFreshness { get }
    var activeDevice: DiscoveredBLEDevice? { get }
    var profile: CUPDeviceProfile { get }
    var streamingDiagnostics: CUPStreamingDiagnostics { get }
}

/// Actor-backed production and test writers share this async surface so the
/// coordinator can exercise append/finalize failures without touching the
/// user's Documents directory.
nonisolated protocol CaptureSessionWriting: Sendable {
    func append(
        _ event: CUPStreamChunkEvent
    ) async throws -> CaptureWriterSnapshot

    func discardIfEmptyBeforeRecording() async throws -> Bool

    func finish(
        reason: CaptureStopReason,
        integrity: CaptureIntegritySnapshot,
        errorMessage: String?
    ) async throws -> CaptureSessionSummary
}

nonisolated struct CaptureSessionWriterFactory: Sendable {
    let makeWriter:
        @Sendable (CaptureSessionConfiguration) async throws
            -> any CaptureSessionWriting

    init(
        makeWriter:
            @escaping @Sendable (CaptureSessionConfiguration) async throws
                -> any CaptureSessionWriting
    ) {
        self.makeWriter = makeWriter
    }

    static let live = CaptureSessionWriterFactory { configuration in
        try await Task.detached {
            try CaptureSessionWriter(configuration: configuration)
        }.value
    }
}

extension CaptureSessionWriter: CaptureSessionWriting {}

extension BLECentralService: CaptureStreamSource {
    var captureStreamEvents:
        AnyPublisher<CUPStreamChunkEvent, Never>
    {
        streamEvents.eraseToAnyPublisher()
    }

    var captureConnectionPhases:
        AnyPublisher<BLEConnectionPhase, Never>
    {
        $connectionPhase.eraseToAnyPublisher()
    }

    var captureStreamFreshness:
        AnyPublisher<StreamFreshness, Never>
    {
        $streamFreshness.eraseToAnyPublisher()
    }
}
