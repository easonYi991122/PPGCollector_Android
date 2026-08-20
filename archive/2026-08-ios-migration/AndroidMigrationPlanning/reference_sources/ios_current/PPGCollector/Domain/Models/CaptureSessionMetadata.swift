import Foundation

nonisolated struct CaptureSessionMetadata: Codable, Equatable, Sendable {
    let schemaVersion: String
    let sessionID: String
    let baseName: String
    let startedUTC: Date
    let endedUTC: Date?
    let softVersion: String
    let algVersion: String
    let preprocessProfile: String
    let protocolProfile: String
    let transportProfile: String
    let sampleRateHz: Int
    let samplesPerFrame: Int
    let device: CaptureSessionDeviceMetadata
    let complete: Bool
    let stopReason: CaptureStopReason?
    let frameCount: Int
    let sampleCount: Int
    let rawChunkCount: Int
    let missingFrames: Int
    let duplicateFrames: Int
    let outOfOrderFrames: Int
    let invalidFrames: Int
    let discardedBytes: Int
    let writer: CaptureSessionWriterMetadata
    let files: CaptureSessionFilesMetadata
    let recovery: CaptureSessionRecoveryMetadata?

    enum CodingKeys: String, CodingKey {
        case device, complete, writer, files, recovery
        case schemaVersion = "schema_version"
        case sessionID = "session_id"
        case baseName = "base_name"
        case startedUTC = "started_utc"
        case endedUTC = "ended_utc"
        case softVersion = "soft_version"
        case algVersion = "alg_version"
        case preprocessProfile = "preprocess_profile"
        case protocolProfile = "protocol_profile"
        case transportProfile = "transport_profile"
        case sampleRateHz = "sample_rate_hz"
        case samplesPerFrame = "samples_per_frame"
        case stopReason = "stop_reason"
        case frameCount = "frame_count"
        case sampleCount = "sample_count"
        case rawChunkCount = "raw_chunk_count"
        case missingFrames = "missing_frames"
        case duplicateFrames = "duplicate_frames"
        case outOfOrderFrames = "out_of_order_frames"
        case invalidFrames = "invalid_frames"
        case discardedBytes = "discarded_bytes"
    }
}

nonisolated struct CaptureSessionDeviceMetadata:
    Codable,
    Equatable,
    Sendable
{
    let name: String
    let identifier: String
    let serviceUUID: String
    let notifyCharacteristicUUID: String
    let firmwareVersion: String?
    let calibrationID: String?

    enum CodingKeys: String, CodingKey {
        case name, identifier
        case serviceUUID = "service_uuid"
        case notifyCharacteristicUUID = "notify_characteristic_uuid"
        case firmwareVersion = "firmware_version"
        case calibrationID = "calibration_id"
    }
}

nonisolated struct CaptureSessionWriterMetadata:
    Codable,
    Equatable,
    Sendable
{
    let lastFlushUTC: Date?
    let rawBytes: Int
    let csvRows: Int
    let error: String?

    enum CodingKeys: String, CodingKey {
        case error
        case lastFlushUTC = "last_flush_utc"
        case rawBytes = "raw_bytes"
        case csvRows = "csv_rows"
    }
}

nonisolated struct CaptureSessionFilesMetadata:
    Codable,
    Equatable,
    Sendable
{
    let raw: String
    let samples: String
}

nonisolated struct CaptureSessionRecoveryMetadata:
    Codable,
    Equatable,
    Sendable
{
    let strategy: String
    let recoveredUTC: Date
    let recoverySoftVersion: String
    let sourceDirectoryName: String
    let sourceSessionID: String?
    let sourceRawSHA256: String
    let sourceCSVSHA256: String
    let sourceMetadataSHA256: String?
    let sourceRawTotalBytes: Int
    let sourceRawCopiedBytes: Int
    let sourceCSVTotalBytes: Int
    let sourceCSVCopiedBytes: Int
    let csvPreservesSourceSessionID: Bool

    enum CodingKeys: String, CodingKey {
        case strategy
        case recoveredUTC = "recovered_utc"
        case recoverySoftVersion = "recovery_soft_version"
        case sourceDirectoryName = "source_directory_name"
        case sourceSessionID = "source_session_id"
        case sourceRawSHA256 = "source_raw_sha256"
        case sourceCSVSHA256 = "source_csv_sha256"
        case sourceMetadataSHA256 = "source_metadata_sha256"
        case sourceRawTotalBytes = "source_raw_total_bytes"
        case sourceRawCopiedBytes = "source_raw_copied_bytes"
        case sourceCSVTotalBytes = "source_csv_total_bytes"
        case sourceCSVCopiedBytes = "source_csv_copied_bytes"
        case csvPreservesSourceSessionID = "csv_preserves_source_session_id"
    }
}

nonisolated enum CaptureSessionMetadataCodec {
    static func encode(_ metadata: CaptureSessionMetadata) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [
            .prettyPrinted,
            .sortedKeys,
            .withoutEscapingSlashes
        ]
        encoder.dateEncodingStrategy = .iso8601
        return try encoder.encode(metadata)
    }

    static func decode(_ data: Data) throws -> CaptureSessionMetadata {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return try decoder.decode(CaptureSessionMetadata.self, from: data)
    }

    static func write(
        _ metadata: CaptureSessionMetadata,
        to url: URL
    ) throws {
        try encode(metadata).write(to: url, options: .atomic)
    }
}
