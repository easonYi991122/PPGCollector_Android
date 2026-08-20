import Foundation

nonisolated struct CUPDecodedFrameEvent: Equatable, Sendable {
    let frame: CUPBatchFrame
    let sequenceEvent: CUPSequenceEvent
    let isAccepted: Bool
}

nonisolated struct CUPStreamChunkEvent: Equatable, Sendable {
    let hostMonotonicNanoseconds: UInt64
    let data: Data
    let decodedFrames: [CUPDecodedFrameEvent]
    let acceptedSampleStartIndex: UInt64?
    let metrics: LiveMetricSnapshot

    init(
        hostMonotonicNanoseconds: UInt64,
        data: Data,
        decodedFrames: [CUPDecodedFrameEvent],
        acceptedSampleStartIndex: UInt64? = nil,
        metrics: LiveMetricSnapshot = .unavailable(
            hasConnectedDevice: true,
            freshness: .fresh
        )
    ) {
        self.hostMonotonicNanoseconds = hostMonotonicNanoseconds
        self.data = data
        self.decodedFrames = decodedFrames
        self.acceptedSampleStartIndex = acceptedSampleStartIndex
        self.metrics = metrics
    }
}

nonisolated enum CaptureStopReason: String, Codable, Equatable, Sendable {
    case user
    case viewExit
    case sceneBackground
    case deviceDisconnect
    case dataTimeout
    case crashRecovery
    case writeError
    case protocolError
    case resourcePressure
    case unknown
}

nonisolated struct CaptureLifecycleGate: Equatable, Sendable {
    private(set) var preflightStopReason: CaptureStopReason?
    private(set) var finalizationReason: CaptureStopReason?

    mutating func requestPreflightStop(
        _ reason: CaptureStopReason
    ) -> Bool {
        guard preflightStopReason == nil else {
            return false
        }
        preflightStopReason = reason
        return true
    }

    mutating func beginFinalization(
        _ reason: CaptureStopReason
    ) -> Bool {
        guard finalizationReason == nil else {
            return false
        }
        finalizationReason = reason
        return true
    }

    mutating func reset() {
        preflightStopReason = nil
        finalizationReason = nil
    }
}

nonisolated struct CaptureDeviceContext: Equatable, Sendable {
    let name: String
    let identifier: UUID
    let serviceUUID: String
    let notifyCharacteristicUUID: String
}

nonisolated struct CaptureSessionConfiguration: Equatable, Sendable {
    let sessionID: UUID
    let baseName: String
    let startedUTC: Date
    let softVersion: String
    let algorithmVersion: String
    let preprocessProfile: String
    let protocolProfile: String
    let transportProfile: String
    let device: CaptureDeviceContext
}

nonisolated struct CaptureWriterSnapshot: Equatable, Sendable {
    var rawChunkCount = 0
    var rawPayloadBytes = 0
    var rawFileBytes = 0
    var csvRows = 0
    var acceptedFrames = 0
    var acceptedSamples = 0
    var missingFrames = 0
    var duplicateFrames = 0
    var outOfOrderFrames = 0
    var lastFlushUTC: Date?
}

/// The minimum recording diagnostics required by V1. These values are based
/// on writer acknowledgements and production stream counters, rather than on
/// writes merely queued by the UI.
nonisolated struct CaptureRecordingDiagnostics: Equatable, Sendable {
    let writer: CaptureWriterSnapshot
    let pendingWriteCount: Int
    let invalidFrames: Int
    let discardedBytes: Int
    let writeError: String?
}

nonisolated struct CaptureIntegritySnapshot: Equatable, Sendable {
    var invalidFrames = 0
    var discardedBytes = 0

    nonisolated init(invalidFrames: Int = 0, discardedBytes: Int = 0) {
        self.invalidFrames = invalidFrames
        self.discardedBytes = discardedBytes
    }
}

nonisolated struct CaptureSessionSummary: Equatable, Sendable {
    let sessionID: UUID
    let baseName: String
    let directoryURL: URL
    let startedUTC: Date
    let endedUTC: Date
    let stopReason: CaptureStopReason
    let complete: Bool
    let writer: CaptureWriterSnapshot
}

nonisolated enum CaptureState: Equatable, Sendable {
    case idle
    case preflighting
    case recording
    case stopping(CaptureStopReason)
    case finalized(CaptureSessionSummary)
    case failed(String)

    var isRecording: Bool {
        if case .recording = self {
            return true
        }
        return false
    }

    var isBusy: Bool {
        switch self {
        case .preflighting, .stopping:
            true
        case .idle, .recording, .finalized, .failed:
            false
        }
    }
}
