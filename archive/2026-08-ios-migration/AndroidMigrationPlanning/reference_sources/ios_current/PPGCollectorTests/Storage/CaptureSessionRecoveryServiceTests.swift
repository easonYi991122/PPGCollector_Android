import Foundation
import Testing
@testable import PPGCollector

struct CaptureSessionRecoveryServiceTests {
    @Test
    func truncatedTailsAreCopiedWithoutChangingTheSource() async throws {
        let rootURL = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: rootURL) }

        let sourceSessionID = try #require(
            UUID(
                uuidString:
                    "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"
            )
        )
        let writer = try CaptureSessionWriter(
            configuration: configuration(
                baseName: "damaged",
                sessionID: sourceSessionID
            ),
            sessionsRootURL: rootURL
        )
        var pipeline = CUPStreamingPipeline()
        let frame = makeCUPTestFrame(sequence: 9)
        _ = try await writer.append(
            CUPStreamChunkEvent(
                hostMonotonicNanoseconds: 99_000,
                data: frame,
                decodedFrames: pipeline.receive(frame)
            )
        )
        let summary = try await writer.finish(
            reason: .user,
            integrity: CaptureIntegritySnapshot()
        )

        let sourceURLs = CaptureSessionRepository.expectedFileURLs(
            in: summary.directoryURL
        )
        let validRawBytes = try Data(contentsOf: sourceURLs[0]).count
        let validCSVBytes = try Data(contentsOf: sourceURLs[1]).count
        try append(Data([0x01, 0x02, 0x03]), to: sourceURLs[0])
        try append(Data("partial,row".utf8), to: sourceURLs[1])

        let sourceRawBefore = try Data(contentsOf: sourceURLs[0])
        let sourceCSVBefore = try Data(contentsOf: sourceURLs[1])
        let sourceMetadataBefore = try Data(contentsOf: sourceURLs[2])
        let source = try #require(
            CaptureSessionRepository.listSessions(
                rootURL: rootURL
            ).first(where: { $0.baseName == "damaged" })
        )
        let recoveredAt = Date(timeIntervalSince1970: 1_800_000_000)
        let recoveryID = try #require(
            UUID(
                uuidString:
                    "11111111-2222-3333-4444-555555555555"
            )
        )

        let result = try CaptureSessionRecoveryService.recover(
            source,
            as: "damaged_recovered",
            recoveredAt: recoveredAt,
            recoverySessionID: recoveryID
        )

        #expect(try Data(contentsOf: sourceURLs[0]) == sourceRawBefore)
        #expect(try Data(contentsOf: sourceURLs[1]) == sourceCSVBefore)
        #expect(
            try Data(contentsOf: sourceURLs[2])
                == sourceMetadataBefore
        )
        #expect(result.rawCopiedBytes == validRawBytes)
        #expect(result.csvCopiedBytes == validCSVBytes)
        #expect(result.rawDiscardedTailBytes == 3)
        #expect(result.csvDiscardedTailBytes == 11)
        #expect(
            try Data(contentsOf: result.rawURL)
                == Data(sourceRawBefore.prefix(validRawBytes))
        )
        #expect(
            try Data(contentsOf: result.csvURL)
                == Data(sourceCSVBefore.prefix(validCSVBytes))
        )

        let metadata = try CaptureSessionMetadataCodec.decode(
            Data(contentsOf: result.metadataURL)
        )
        let recovery = try #require(metadata.recovery)
        #expect(metadata.sessionID == recoveryID.uuidString)
        #expect(metadata.sessionID != sourceSessionID.uuidString)
        #expect(metadata.baseName == "damaged_recovered")
        #expect(metadata.complete == false)
        #expect(metadata.stopReason == .crashRecovery)
        #expect(metadata.frameCount == 1)
        #expect(metadata.sampleCount == 50)
        #expect(metadata.rawChunkCount == 1)
        #expect(metadata.writer.csvRows == 50)
        #expect(recovery.strategy == "copy_safe_prefix_v1")
        #expect(recovery.sourceDirectoryName == "damaged")
        #expect(recovery.sourceSessionID == sourceSessionID.uuidString)
        #expect(recovery.sourceRawSHA256.count == 64)
        #expect(recovery.sourceCSVSHA256.count == 64)
        #expect(recovery.sourceMetadataSHA256?.count == 64)
        #expect(recovery.sourceRawCopiedBytes == validRawBytes)
        #expect(recovery.sourceCSVTotalBytes == sourceCSVBefore.count)
        #expect(recovery.csvPreservesSourceSessionID)

        let recovered = try #require(
            CaptureSessionRepository.listSessions(
                rootURL: rootURL
            ).first(where: { $0.baseName == "damaged_recovered" })
        )
        #expect(recovered.isRecoveryCopy)
        #expect(recovered.recoverySourceBaseName == "damaged")
        #expect(
            recovered.recoverySourceSessionID
                == sourceSessionID.uuidString
        )
        #expect(recovered.complete == false)
        #expect(recovered.stopReason == "crashRecovery")

        let inspection = CaptureSessionInspectionService.inspect(
            recovered
        )
        #expect(inspection.replay?.acceptedSamples == 50)
        #expect(inspection.csv?.completeDataRowCount == 50)
        #expect(!inspection.hasRecoverableTail)
    }

    @Test
    func destinationCollisionNeverOverwritesExistingData() async throws {
        let rootURL = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: rootURL) }
        let source = try await makeCompleteSession(
            baseName: "source",
            rootURL: rootURL
        )
        let destination = rootURL.appendingPathComponent(
            "taken",
            isDirectory: true
        )
        try FileManager.default.createDirectory(
            at: destination,
            withIntermediateDirectories: true
        )
        let sentinel = destination.appendingPathComponent("keep.txt")
        let sentinelData = Data("do not overwrite".utf8)
        try sentinelData.write(to: sentinel)

        #expect(
            throws:
                CaptureSessionRecoveryError.destinationAlreadyExists(
                    "taken"
                )
        ) {
            try CaptureSessionRecoveryService.recover(
                source,
                as: "taken"
            )
        }
        #expect(try Data(contentsOf: sentinel) == sentinelData)
    }

    @Test
    func invalidCSVHeaderDoesNotLeaveADestinationDirectory() async throws {
        let rootURL = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: rootURL) }
        let source = try await makeCompleteSession(
            baseName: "invalid_csv",
            rootURL: rootURL
        )
        let csvURL = CaptureSessionRepository.expectedFileURLs(
            in: source.directoryURL
        )[1]
        try Data("wrong,header\n".utf8).write(
            to: csvURL,
            options: .atomic
        )

        #expect(
            throws:
                CaptureSessionRecoveryError.sourceCSVHeaderInvalid
        ) {
            try CaptureSessionRecoveryService.recover(
                source,
                as: "should_not_exist"
            )
        }
        #expect(
            !FileManager.default.fileExists(
                atPath: rootURL.appendingPathComponent(
                    "should_not_exist"
                ).path
            )
        )
    }

    @Test
    func missingMetadataCanBeRecoveredWithExplicitUnknownProvenance()
        throws
    {
        let rootURL = temporaryRoot()
        let sourceURL = rootURL.appendingPathComponent(
            "orphan",
            isDirectory: true
        )
        defer { try? FileManager.default.removeItem(at: rootURL) }
        try FileManager.default.createDirectory(
            at: sourceURL,
            withIntermediateDirectories: true
        )
        try CUPRawFileReader.magic.write(
            to: sourceURL.appendingPathComponent("orphan.cupraw")
        )
        try Data(CaptureCSVSchema.header.utf8).write(
            to: sourceURL.appendingPathComponent("orphan.csv")
        )

        let source = try #require(
            CaptureSessionRepository.listSessions(
                rootURL: rootURL
            ).first
        )
        let result = try CaptureSessionRecoveryService.recover(
            source,
            as: "orphan_recovered"
        )
        let metadata = try CaptureSessionMetadataCodec.decode(
            Data(contentsOf: result.metadataURL)
        )

        #expect(metadata.complete == false)
        #expect(metadata.stopReason == .crashRecovery)
        #expect(metadata.softVersion == "unknown")
        #expect(metadata.algVersion == "unknown")
        #expect(metadata.recovery?.sourceSessionID == nil)
        #expect(metadata.recovery?.sourceDirectoryName == "orphan")
    }

    @Test
    func suggestedNameIsValidBoundedAndSkipsCollisions() throws {
        let rootURL = temporaryRoot()
        let longName = String(repeating: "a", count: 64)
        let sourceURL = rootURL.appendingPathComponent(
            longName,
            isDirectory: true
        )
        defer { try? FileManager.default.removeItem(at: rootURL) }
        try FileManager.default.createDirectory(
            at: sourceURL,
            withIntermediateDirectories: true
        )
        try CUPRawFileReader.magic.write(
            to: sourceURL.appendingPathComponent("\(longName).cupraw")
        )
        try Data(CaptureCSVSchema.header.utf8).write(
            to: sourceURL.appendingPathComponent("\(longName).csv")
        )

        let source = try #require(
            CaptureSessionRepository.listSessions(
                rootURL: rootURL
            ).first
        )
        let first = CaptureSessionRecoveryService.suggestedBaseName(
            for: source
        )
        #expect(first.count <= 64)
        #expect(try SessionNameValidator.validate(first) == first)
        try FileManager.default.createDirectory(
            at: rootURL.appendingPathComponent(first),
            withIntermediateDirectories: false
        )

        let second = CaptureSessionRecoveryService.suggestedBaseName(
            for: source
        )
        #expect(second != first)
        #expect(second.hasSuffix("_recovered_2"))
        #expect(second.count <= 64)
        #expect(try SessionNameValidator.validate(second) == second)
    }

    @Test
    func startupAssessmentPromptsOnlyForRecoverableSources()
        async throws
    {
        let rootURL = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: rootURL) }
        let complete = try await makeCompleteSession(
            baseName: "assessment",
            rootURL: rootURL
        )

        let cleanAssessment =
            CaptureSessionRecoveryService.assess(complete)
        #expect(cleanAssessment.canCreateRecoveryCopy)
        #expect(!cleanAssessment.requiresRecovery)
        #expect(!cleanAssessment.shouldPrompt)

        let metadataURL = CaptureSessionRepository.expectedFileURLs(
            in: complete.directoryURL
        )[2]
        var metadata = try JSONSerialization.jsonObject(
            with: Data(contentsOf: metadataURL)
        ) as? [String: Any] ?? [:]
        metadata["raw_chunk_count"] = 1
        try JSONSerialization.data(
            withJSONObject: metadata,
            options: [.sortedKeys]
        ).write(to: metadataURL)
        let mismatched = try #require(
            CaptureSessionRepository.listSessions(
                rootURL: rootURL
            ).first(where: { $0.baseName == "assessment" })
        )
        let mismatchAssessment =
            CaptureSessionRecoveryService.assess(mismatched)
        #expect(mismatchAssessment.shouldPrompt)
        #expect(
            mismatchAssessment.reason?.contains("metadata") == true
        )

        let rawURL = CaptureSessionRepository.expectedFileURLs(
            in: mismatched.directoryURL
        )[0]
        try append(Data([0x01, 0x02]), to: rawURL)
        let damaged = try #require(
            CaptureSessionRepository.listSessions(
                rootURL: rootURL
            ).first(where: { $0.baseName == "assessment" })
        )
        let damagedAssessment =
            CaptureSessionRecoveryService.assess(damaged)
        #expect(damagedAssessment.shouldPrompt)
        #expect(damagedAssessment.rawTrailingBytes == 2)

        _ = try CaptureSessionRecoveryService.recover(
            damaged,
            as: "assessment_recovered"
        )
        let sessions = try CaptureSessionRepository.listSessions(
            rootURL: rootURL
        )
        let candidates =
            CaptureSessionRecoveryService.recoveryCandidates(
                in: sessions
            )
        #expect(candidates.map(\.baseName) == ["assessment"])

        let recovered = try #require(
            sessions.first(
                where: { $0.baseName == "assessment_recovered" }
            )
        )
        let recoveredAssessment =
            CaptureSessionRecoveryService.assess(recovered)
        #expect(!recoveredAssessment.shouldPrompt)
    }

    private func makeCompleteSession(
        baseName: String,
        rootURL: URL
    ) async throws -> StoredCaptureSession {
        let writer = try CaptureSessionWriter(
            configuration: configuration(
                baseName: baseName,
                sessionID: UUID()
            ),
            sessionsRootURL: rootURL
        )
        _ = try await writer.finish(
            reason: .user,
            integrity: CaptureIntegritySnapshot()
        )
        return try #require(
            CaptureSessionRepository.listSessions(
                rootURL: rootURL
            ).first(where: { $0.baseName == baseName })
        )
    }

    private func configuration(
        baseName: String,
        sessionID: UUID
    ) -> CaptureSessionConfiguration {
        CaptureSessionConfiguration(
            sessionID: sessionID,
            baseName: baseName,
            startedUTC: Date(timeIntervalSince1970: 1_700_000_000),
            softVersion: "1.0+1",
            algorithmVersion: "unavailable",
            preprocessProfile: "raw-only-0.1",
            protocolProfile: "cup_batch_v1_draft",
            transportProfile: "cup-nus-bringup-0.1",
            device: CaptureDeviceContext(
                name: "CUP-SIM",
                identifier: UUID(),
                serviceUUID:
                    "6E400001-B5A3-F393-E0A9-E50E24DCCA9E",
                notifyCharacteristicUUID:
                    "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
            )
        )
    }

    private func append(_ data: Data, to url: URL) throws {
        let handle = try FileHandle(forWritingTo: url)
        defer { try? handle.close() }
        try handle.seekToEnd()
        try handle.write(contentsOf: data)
    }

    private func temporaryRoot() -> URL {
        FileManager.default.temporaryDirectory
            .appendingPathComponent(
                "PPGCollectorRecoveryTests-\(UUID().uuidString)"
            )
    }
}
