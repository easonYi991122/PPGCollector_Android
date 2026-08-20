import Foundation

nonisolated struct CaptureStorageCapacityProvider: Sendable {
    private let resolve:
        @Sendable (URL) throws -> Int64?

    init(
        _ resolve:
            @escaping @Sendable (URL) throws -> Int64?
    ) {
        self.resolve = resolve
    }

    func availableCapacityForImportantUsage(
        at directoryURL: URL
    ) throws -> Int64? {
        try resolve(directoryURL)
    }

    static let live = CaptureStorageCapacityProvider { directoryURL in
        try directoryURL.resourceValues(
            forKeys: [.volumeAvailableCapacityForImportantUsageKey]
        ).volumeAvailableCapacityForImportantUsage
    }
}

nonisolated enum CaptureSessionWriterError: LocalizedError {
    case sessionAlreadyExists(String)
    case insufficientStorage
    case chunkTooLarge(Int)
    case writerClosed
    case cannotCreate(String)

    var errorDescription: String? {
        switch self {
        case let .sessionAlreadyExists(name):
            "记录“\(name)”已经存在，请更换名称。"
        case .insufficientStorage:
            "设备可用空间不足，无法安全开始记录。"
        case let .chunkTooLarge(length):
            "收到异常大的 BLE chunk（\(length) bytes），记录已停止。"
        case .writerClosed:
            "当前记录已经结束。"
        case let .cannotCreate(message):
            "无法创建记录文件：\(message)"
        }
    }
}

actor CaptureSessionWriter {
    static let sampleSchemaVersion = "ppgcollector_samples_v1"
    static let sessionSchemaVersion = "ppgcollector_session_v1"
    static let minimumAvailableCapacityForStartBytes: Int64 =
        20 * 1024 * 1024

    let configuration: CaptureSessionConfiguration
    let directoryURL: URL
    let rawURL: URL
    let csvURL: URL
    let metadataURL: URL

    private let rawHandle: FileHandle
    private let csvHandle: FileHandle
    private var snapshot = CaptureWriterSnapshot(
        rawFileBytes: CUPRawFileReader.magic.count
    )
    private var nextSampleIndex: UInt64 = 0
    private var firstStreamSampleIndex: UInt64?
    private var lastFlushUptime = ProcessInfo.processInfo.systemUptime
    private var finalSummary: CaptureSessionSummary?
    private var handlesAreClosed = false

    init(
        configuration: CaptureSessionConfiguration,
        sessionsRootURL: URL? = nil,
        storageCapacityProvider:
            CaptureStorageCapacityProvider = .live
    ) throws {
        self.configuration = configuration

        let rootURL: URL
        if let sessionsRootURL {
            rootURL = sessionsRootURL
        } else {
            do {
                rootURL = try CaptureSessionRepository.defaultSessionsRootURL()
            } catch {
                throw CaptureSessionWriterError.cannotCreate(
                    error.localizedDescription
                )
            }
        }

        do {
            try FileManager.default.createDirectory(
                at: rootURL,
                withIntermediateDirectories: true
            )
        } catch {
            throw CaptureSessionWriterError.cannotCreate(
                error.localizedDescription
            )
        }

        if let available = try? storageCapacityProvider
            .availableCapacityForImportantUsage(at: rootURL),
           available < Self.minimumAvailableCapacityForStartBytes {
            throw CaptureSessionWriterError.insufficientStorage
        }

        let directoryURL = rootURL.appendingPathComponent(
            configuration.baseName,
            isDirectory: true
        )
        self.directoryURL = directoryURL
        rawURL = directoryURL.appendingPathComponent(
            "\(configuration.baseName).cupraw"
        )
        csvURL = directoryURL.appendingPathComponent(
            "\(configuration.baseName).csv"
        )
        metadataURL = directoryURL.appendingPathComponent(
            "\(configuration.baseName).session.json"
        )

        do {
            try FileManager.default.createDirectory(
                at: directoryURL,
                withIntermediateDirectories: false
            )
        } catch CocoaError.fileWriteFileExists {
            throw CaptureSessionWriterError.sessionAlreadyExists(
                configuration.baseName
            )
        } catch {
            throw CaptureSessionWriterError.cannotCreate(
                error.localizedDescription
            )
        }

        do {
            try CUPRawFileReader.magic.write(
                to: rawURL,
                options: .withoutOverwriting
            )
            try Data(CaptureCSVSchema.header.utf8).write(
                to: csvURL,
                options: .withoutOverwriting
            )
            rawHandle = try FileHandle(forWritingTo: rawURL)
            csvHandle = try FileHandle(forWritingTo: csvURL)
            try rawHandle.seekToEnd()
            try csvHandle.seekToEnd()
            try Self.writeMetadata(
                Self.metadata(
                    configuration: configuration,
                    snapshot: snapshot,
                    endedUTC: nil,
                    stopReason: nil,
                    complete: false,
                    integrity: CaptureIntegritySnapshot(),
                    error: nil
                ),
                to: metadataURL
            )
        } catch {
            try? FileManager.default.removeItem(at: directoryURL)
            throw CaptureSessionWriterError.cannotCreate(
                error.localizedDescription
            )
        }
    }

    func append(_ event: CUPStreamChunkEvent) throws -> CaptureWriterSnapshot {
        guard !handlesAreClosed else {
            throw CaptureSessionWriterError.writerClosed
        }
        guard !event.data.isEmpty else {
            return snapshot
        }
        guard event.data.count <= CUPRawFileReader.maximumChunkLength else {
            throw CaptureSessionWriterError.chunkTooLarge(event.data.count)
        }

        var rawRecord = Data()
        rawRecord.reserveCapacity(12 + event.data.count)
        Self.appendUInt64LE(event.hostMonotonicNanoseconds, to: &rawRecord)
        Self.appendUInt32LE(UInt32(event.data.count), to: &rawRecord)
        rawRecord.append(event.data)

        // Raw acknowledgement intentionally precedes every derived CSV row.
        try rawHandle.write(contentsOf: rawRecord)
        snapshot.rawChunkCount += 1
        snapshot.rawPayloadBytes += event.data.count
        snapshot.rawFileBytes += rawRecord.count

        var csvBytes = Data()
        var streamSampleIndex = event.acceptedSampleStartIndex
        for decoded in event.decodedFrames {
            switch decoded.sequenceEvent {
            case let .gap(missingFrames):
                snapshot.missingFrames += missingFrames
            case .duplicate:
                snapshot.duplicateFrames += 1
            case .outOfOrder:
                snapshot.outOfOrderFrames += 1
            case .first, .continuous:
                break
            }

            guard decoded.isAccepted else {
                continue
            }
            snapshot.acceptedFrames += 1
            if firstStreamSampleIndex == nil {
                firstStreamSampleIndex =
                    streamSampleIndex ?? nextSampleIndex
            }
            for (sampleInFrame, sample) in decoded.frame.samples.enumerated() {
                let row = Self.csvRow(
                    configuration: configuration,
                    sampleIndex: nextSampleIndex,
                    hostFrameTimeNanoseconds: event.hostMonotonicNanoseconds,
                    frameSequence: decoded.frame.sequence,
                    sampleInFrame: sampleInFrame,
                    sample: sample,
                    metrics: event.metrics,
                    firstStreamSampleIndex: firstStreamSampleIndex
                )
                csvBytes.append(contentsOf: row.utf8)
                nextSampleIndex += 1
                if let currentStreamSampleIndex = streamSampleIndex {
                    streamSampleIndex = currentStreamSampleIndex + 1
                }
                snapshot.csvRows += 1
                snapshot.acceptedSamples += 1
            }
        }
        if !csvBytes.isEmpty {
            try csvHandle.write(contentsOf: csvBytes)
        }

        if ProcessInfo.processInfo.systemUptime - lastFlushUptime >= 1 {
            try flushIncompleteMetadata()
        }
        return snapshot
    }

    func discardIfEmptyBeforeRecording() throws -> Bool {
        guard finalSummary == nil,
              !handlesAreClosed,
              snapshot.rawChunkCount == 0,
              snapshot.csvRows == 0 else {
            return false
        }

        try rawHandle.close()
        try csvHandle.close()
        handlesAreClosed = true
        try FileManager.default.removeItem(at: directoryURL)
        return true
    }

    func finish(
        reason: CaptureStopReason,
        integrity: CaptureIntegritySnapshot,
        errorMessage: String? = nil
    ) throws -> CaptureSessionSummary {
        if let finalSummary {
            return finalSummary
        }

        let endedUTC = Date()
        do {
            try rawHandle.synchronize()
            try csvHandle.synchronize()
            snapshot.lastFlushUTC = endedUTC
            try rawHandle.close()
            try csvHandle.close()
            handlesAreClosed = true
        } catch {
            handlesAreClosed = true
            throw error
        }

        let complete = errorMessage == nil && Self.isNormalStop(reason)
        try Self.writeMetadata(
            Self.metadata(
                configuration: configuration,
                snapshot: snapshot,
                endedUTC: endedUTC,
                stopReason: reason,
                complete: complete,
                integrity: integrity,
                error: errorMessage
            ),
            to: metadataURL
        )

        let summary = CaptureSessionSummary(
            sessionID: configuration.sessionID,
            baseName: configuration.baseName,
            directoryURL: directoryURL,
            startedUTC: configuration.startedUTC,
            endedUTC: endedUTC,
            stopReason: reason,
            complete: complete,
            writer: snapshot
        )
        finalSummary = summary
        return summary
    }

    private func flushIncompleteMetadata() throws {
        try rawHandle.synchronize()
        try csvHandle.synchronize()
        snapshot.lastFlushUTC = Date()
        lastFlushUptime = ProcessInfo.processInfo.systemUptime
        try Self.writeMetadata(
            Self.metadata(
                configuration: configuration,
                snapshot: snapshot,
                endedUTC: nil,
                stopReason: nil,
                complete: false,
                integrity: CaptureIntegritySnapshot(),
                error: nil
            ),
            to: metadataURL
        )
    }

    private static func csvRow(
        configuration: CaptureSessionConfiguration,
        sampleIndex: UInt64,
        hostFrameTimeNanoseconds: UInt64,
        frameSequence: UInt8,
        sampleInFrame: Int,
        sample: CUPPPGSample,
        metrics: LiveMetricSnapshot,
        firstStreamSampleIndex: UInt64?
    ) -> String {
        let deviceTime = String(
            format: "%.6f",
            locale: Locale(identifier: "en_US_POSIX"),
            Double(sampleIndex) / Double(CUPBatchProtocolV1.sampleRateHz)
        )
        let heartRateFields = scalarMetricFields(
            metrics.heartRateBPM,
            firstStreamSampleIndex: firstStreamSampleIndex,
            invalidValue: ""
        )
        let oxygenSaturationFields = scalarMetricFields(
            metrics.oxygenSaturationPercent,
            firstStreamSampleIndex: firstStreamSampleIndex,
            invalidValue: ""
        )
        let signalQualityFields = scalarMetricFields(
            metrics.signalQuality,
            firstStreamSampleIndex: firstStreamSampleIndex,
            invalidValue: "0"
        )
        let ratioOfRatiosFields = scalarMetricFields(
            metrics.ratioOfRatios,
            firstStreamSampleIndex: firstStreamSampleIndex,
            invalidValue: ""
        )
        let fields = [
            sampleSchemaVersion,
            configuration.sessionID.uuidString,
            String(sampleIndex),
            deviceTime,
            String(hostFrameTimeNanoseconds),
            String(frameSequence),
            String(sampleInFrame),
            String(sample.red),
            String(sample.ir),
            heartRateFields.value,
            heartRateFields.valid,
            heartRateFields.time,
            oxygenSaturationFields.value,
            oxygenSaturationFields.valid,
            oxygenSaturationFields.time,
            signalQualityFields.value,
            signalQualityFields.valid,
            signalQualityFields.time,
            configuration.softVersion,
            configuration.algorithmVersion,
            configuration.preprocessProfile,
            configuration.protocolProfile,
            ratioOfRatiosFields.value,
            ratioOfRatiosFields.valid,
            ratioOfRatiosFields.time
        ]
        return fields.map(csvField).joined(separator: ",") + "\n"
    }

    private static func scalarMetricFields(
        _ metric: MetricResult<Double>,
        firstStreamSampleIndex: UInt64?,
        invalidValue: String
    ) -> (value: String, valid: String, time: String) {
        guard metric.isValid,
              let value = metric.value,
              value.isFinite,
              let metricSampleIndex = metric.sourceSampleIndex,
              let firstStreamSampleIndex,
              metricSampleIndex >= firstStreamSampleIndex else {
            return (invalidValue, "false", "")
        }
        let timeSeconds =
            Double(metricSampleIndex - firstStreamSampleIndex)
            / Double(CUPBatchProtocolV1.sampleRateHz)
        return (
            String(
                format: "%.6f",
                locale: Locale(identifier: "en_US_POSIX"),
                value
            ),
            "true",
            String(
                format: "%.6f",
                locale: Locale(identifier: "en_US_POSIX"),
                timeSeconds
            )
        )
    }

    private static func csvField(_ value: String) -> String {
        guard value.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" })
        else {
            return value
        }
        return "\"\(value.replacingOccurrences(of: "\"", with: "\"\""))\""
    }

    private static func appendUInt32LE(_ value: UInt32, to data: inout Data) {
        data.append(UInt8(value & 0xFF))
        data.append(UInt8((value >> 8) & 0xFF))
        data.append(UInt8((value >> 16) & 0xFF))
        data.append(UInt8((value >> 24) & 0xFF))
    }

    private static func appendUInt64LE(_ value: UInt64, to data: inout Data) {
        for shift in stride(from: 0, through: 56, by: 8) {
            data.append(UInt8((value >> UInt64(shift)) & 0xFF))
        }
    }

    private static func isNormalStop(_ reason: CaptureStopReason) -> Bool {
        switch reason {
        case .user,
             .viewExit,
             .sceneBackground,
             .deviceDisconnect,
             .dataTimeout:
            true
        case .crashRecovery,
             .writeError,
             .protocolError,
             .resourcePressure,
             .unknown:
            false
        }
    }

    private static func writeMetadata(
        _ metadata: CaptureSessionMetadata,
        to url: URL
    ) throws {
        try CaptureSessionMetadataCodec.write(metadata, to: url)
    }

    private static func metadata(
        configuration: CaptureSessionConfiguration,
        snapshot: CaptureWriterSnapshot,
        endedUTC: Date?,
        stopReason: CaptureStopReason?,
        complete: Bool,
        integrity: CaptureIntegritySnapshot,
        error: String?
    ) -> CaptureSessionMetadata {
        CaptureSessionMetadata(
            schemaVersion: sessionSchemaVersion,
            sessionID: configuration.sessionID.uuidString,
            baseName: configuration.baseName,
            startedUTC: configuration.startedUTC,
            endedUTC: endedUTC,
            softVersion: configuration.softVersion,
            algVersion: configuration.algorithmVersion,
            preprocessProfile: configuration.preprocessProfile,
            protocolProfile: configuration.protocolProfile,
            transportProfile: configuration.transportProfile,
            sampleRateHz: CUPBatchProtocolV1.sampleRateHz,
            samplesPerFrame: CUPBatchProtocolV1.samplesPerFrame,
            device: CaptureSessionDeviceMetadata(
                name: configuration.device.name,
                identifier: configuration.device.identifier.uuidString,
                serviceUUID: configuration.device.serviceUUID,
                notifyCharacteristicUUID: configuration.device.notifyCharacteristicUUID,
                firmwareVersion: nil,
                calibrationID: nil
            ),
            complete: complete,
            stopReason: stopReason,
            frameCount: snapshot.acceptedFrames,
            sampleCount: snapshot.acceptedSamples,
            rawChunkCount: snapshot.rawChunkCount,
            missingFrames: snapshot.missingFrames,
            duplicateFrames: snapshot.duplicateFrames,
            outOfOrderFrames: snapshot.outOfOrderFrames,
            invalidFrames: integrity.invalidFrames,
            discardedBytes: integrity.discardedBytes,
            writer: CaptureSessionWriterMetadata(
                lastFlushUTC: snapshot.lastFlushUTC,
                rawBytes: snapshot.rawFileBytes,
                csvRows: snapshot.csvRows,
                error: error
            ),
            files: CaptureSessionFilesMetadata(
                raw: "\(configuration.baseName).cupraw",
                samples: "\(configuration.baseName).csv"
            ),
            recovery: nil
        )
    }
}
