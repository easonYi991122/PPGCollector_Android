import Combine
import Foundation

nonisolated struct StoredCaptureSession: Identifiable, Equatable, Sendable {
    let directoryURL: URL
    let baseName: String
    let modifiedAt: Date
    let sessionID: String?
    let startedUTC: Date?
    let endedUTC: Date?
    let complete: Bool?
    let sampleCount: Int?
    let rawChunkCount: Int?
    let stopReason: String?
    let softVersion: String?
    let algorithmVersion: String?
    let preprocessProfile: String?
    let protocolProfile: String?
    let transportProfile: String?
    let deviceName: String?
    let recoverySourceBaseName: String?
    let recoverySourceSessionID: String?
    let totalBytes: Int64
    let shareableFileURLs: [URL]
    let hasAllExpectedFiles: Bool
    let metadataIsReadable: Bool

    var id: String {
        directoryURL.path
    }

    var isVerifiedComplete: Bool {
        complete == true && hasAllExpectedFiles && metadataIsReadable
    }

    var isRecoveryCopy: Bool {
        recoverySourceBaseName != nil
            || stopReason == CaptureStopReason.crashRecovery.rawValue
    }

    var durationSeconds: TimeInterval? {
        guard let startedUTC, let endedUTC, endedUTC >= startedUTC else {
            return nil
        }
        return endedUTC.timeIntervalSince(startedUTC)
    }
}

nonisolated enum CaptureSessionRepositoryError: LocalizedError {
    case documentsUnavailable

    var errorDescription: String? {
        switch self {
        case .documentsUnavailable:
            "无法定位 App 的 Documents 目录。"
        }
    }
}

nonisolated enum CaptureSessionRepository {
    static let sessionsDirectoryName = "PPGCollector"

    static func defaultSessionsRootURL(
        fileManager: FileManager = .default
    ) throws -> URL {
        guard let documentsURL = fileManager.urls(
            for: .documentDirectory,
            in: .userDomainMask
        ).first else {
            throw CaptureSessionRepositoryError.documentsUnavailable
        }
        return documentsURL.appendingPathComponent(
            sessionsDirectoryName,
            isDirectory: true
        )
    }

    static func listSessions(
        rootURL: URL? = nil,
        fileManager: FileManager = .default
    ) throws -> [StoredCaptureSession] {
        let rootURL = try rootURL ?? defaultSessionsRootURL(
            fileManager: fileManager
        )
        var isDirectory: ObjCBool = false
        guard fileManager.fileExists(
            atPath: rootURL.path,
            isDirectory: &isDirectory
        ), isDirectory.boolValue else {
            return []
        }

        let directoryURLs = try fileManager.contentsOfDirectory(
            at: rootURL,
            includingPropertiesForKeys: [
                .isDirectoryKey,
                .contentModificationDateKey
            ],
            options: [.skipsHiddenFiles]
        )

        return directoryURLs.compactMap { directoryURL in
            guard (
                try? directoryURL.resourceValues(
                    forKeys: [.isDirectoryKey]
                ).isDirectory
            ) == true else {
                return nil
            }
            return inspectSession(
                directoryURL: directoryURL,
                fileManager: fileManager
            )
        }
        .sorted { lhs, rhs in
            if lhs.modifiedAt == rhs.modifiedAt {
                return lhs.baseName > rhs.baseName
            }
            return lhs.modifiedAt > rhs.modifiedAt
        }
    }

    static func expectedFileURLs(in directoryURL: URL) -> [URL] {
        let baseName = directoryURL.lastPathComponent
        return [
            directoryURL.appendingPathComponent("\(baseName).cupraw"),
            directoryURL.appendingPathComponent("\(baseName).csv"),
            directoryURL.appendingPathComponent("\(baseName).session.json")
        ]
    }

    private static func inspectSession(
        directoryURL: URL,
        fileManager: FileManager
    ) -> StoredCaptureSession {
        let baseName = directoryURL.lastPathComponent
        let expectedURLs = expectedFileURLs(in: directoryURL)
        let existingURLs = expectedURLs.filter {
            fileManager.fileExists(atPath: $0.path)
        }
        let metadataURL = expectedURLs[2]
        let metadata = readMetadata(at: metadataURL)

        let fileDates = existingURLs.compactMap {
            try? $0.resourceValues(
                forKeys: [.contentModificationDateKey]
            ).contentModificationDate
        }
        let directoryDate = (
            try? directoryURL.resourceValues(
                forKeys: [.contentModificationDateKey]
            ).contentModificationDate
        ) ?? .distantPast
        let modifiedAt = fileDates.max() ?? directoryDate

        let totalBytes = existingURLs.reduce(Int64(0)) { result, url in
            let size = (
                try? url.resourceValues(
                    forKeys: [.fileSizeKey]
                ).fileSize
            ) ?? 0
            return result + Int64(size)
        }

        return StoredCaptureSession(
            directoryURL: directoryURL,
            baseName: baseName,
            modifiedAt: modifiedAt,
            sessionID: metadata.sessionID,
            startedUTC: metadata.startedUTC,
            endedUTC: metadata.endedUTC,
            complete: metadata.complete,
            sampleCount: metadata.sampleCount,
            rawChunkCount: metadata.rawChunkCount,
            stopReason: metadata.stopReason,
            softVersion: metadata.softVersion,
            algorithmVersion: metadata.algorithmVersion,
            preprocessProfile: metadata.preprocessProfile,
            protocolProfile: metadata.protocolProfile,
            transportProfile: metadata.transportProfile,
            deviceName: metadata.deviceName,
            recoverySourceBaseName: metadata.recoverySourceBaseName,
            recoverySourceSessionID: metadata.recoverySourceSessionID,
            totalBytes: totalBytes,
            shareableFileURLs: existingURLs,
            hasAllExpectedFiles: existingURLs.count == expectedURLs.count,
            metadataIsReadable: metadata.isReadable
        )
    }

    private static func readMetadata(at url: URL) -> MetadataSnapshot {
        guard let data = try? Data(contentsOf: url),
              let dictionary = try? JSONSerialization.jsonObject(with: data)
                as? [String: Any] else {
            return MetadataSnapshot()
        }

        return MetadataSnapshot(
            sessionID: dictionary["session_id"] as? String,
            startedUTC: parseDate(dictionary["started_utc"]),
            endedUTC: parseDate(dictionary["ended_utc"]),
            complete: dictionary["complete"] as? Bool,
            sampleCount: dictionary["sample_count"] as? Int,
            rawChunkCount: dictionary["raw_chunk_count"] as? Int,
            stopReason: dictionary["stop_reason"] as? String,
            softVersion: dictionary["soft_version"] as? String,
            algorithmVersion: dictionary["alg_version"] as? String,
            preprocessProfile:
                dictionary["preprocess_profile"] as? String,
            protocolProfile: dictionary["protocol_profile"] as? String,
            transportProfile: dictionary["transport_profile"] as? String,
            deviceName: (
                dictionary["device"] as? [String: Any]
            )?["name"] as? String,
            recoverySourceBaseName: (
                dictionary["recovery"] as? [String: Any]
            )?["source_directory_name"] as? String,
            recoverySourceSessionID: (
                dictionary["recovery"] as? [String: Any]
            )?["source_session_id"] as? String,
            isReadable: true
        )
    }

    private static func parseDate(_ value: Any?) -> Date? {
        guard let value = value as? String else {
            return nil
        }
        return ISO8601DateFormatter().date(from: value)
    }
}

nonisolated private struct MetadataSnapshot {
    var sessionID: String? = nil
    var startedUTC: Date? = nil
    var endedUTC: Date? = nil
    var complete: Bool? = nil
    var sampleCount: Int? = nil
    var rawChunkCount: Int? = nil
    var stopReason: String? = nil
    var softVersion: String? = nil
    var algorithmVersion: String? = nil
    var preprocessProfile: String? = nil
    var protocolProfile: String? = nil
    var transportProfile: String? = nil
    var deviceName: String? = nil
    var recoverySourceBaseName: String? = nil
    var recoverySourceSessionID: String? = nil
    var isReadable = false
}

@MainActor
final class CaptureSessionStore: ObservableObject {
    @Published private(set) var sessions: [StoredCaptureSession] = []
    @Published private(set) var recoveryCandidates:
        [StoredCaptureSession] = []
    @Published private(set) var isAuditingRecovery = false
    @Published private(set) var lastError: String?

    private let rootURL: URL?
    private var recoveryAuditTask: Task<Void, Never>?

    init(rootURL: URL? = nil) {
        self.rootURL = rootURL
    }

    func refresh() {
        do {
            sessions = try CaptureSessionRepository.listSessions(
                rootURL: rootURL
            )
            lastError = nil
            auditRecoveryCandidates(in: sessions)
        } catch {
            recoveryAuditTask?.cancel()
            sessions = []
            recoveryCandidates = []
            isAuditingRecovery = false
            lastError = error.localizedDescription
        }
    }

    private func auditRecoveryCandidates(
        in sessions: [StoredCaptureSession]
    ) {
        recoveryAuditTask?.cancel()
        recoveryCandidates = []
        isAuditingRecovery = !sessions.isEmpty
        guard !sessions.isEmpty else {
            return
        }

        recoveryAuditTask = Task { [weak self] in
            let candidates = await Task.detached {
                CaptureSessionRecoveryService.recoveryCandidates(
                    in: sessions
                )
            }.value
            guard !Task.isCancelled else {
                return
            }
            self?.recoveryCandidates = candidates
            self?.isAuditingRecovery = false
        }
    }
}
