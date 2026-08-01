import Foundation
import Testing
@testable import PPGCollector

@Suite(.serialized)
struct LongDurationDataPathTests {
    @Test
    func thirtyMinuteStreamingKeepsCadenceAndFilesExactlyAligned()
        async throws
    {
        try await verifyRecordedDataPath(
            durationSeconds: 30 * 60,
            elapsedBudget: .seconds(60)
        )
    }

    @Test
    func twoHourRecordingStreamsWriterReplayAndCSVWithinBudget()
        async throws
    {
        try await verifyRecordedDataPath(
            durationSeconds: 2 * 60 * 60,
            elapsedBudget: .seconds(240)
        )
    }

    private func verifyRecordedDataPath(
        durationSeconds: Int,
        elapsedBudget: Duration
    ) async throws {
        let frameCount = durationSeconds
            * CUPBatchProtocolV1.sampleRateHz
            / CUPBatchProtocolV1.samplesPerFrame
        let sampleCount =
            frameCount * CUPBatchProtocolV1.samplesPerFrame
        let rawChunkCount = frameCount * 2
        let expectedMetricRequestCount =
            (sampleCount
                - PPGLiveMetricRuntimeProfile.iosBaseline01
                    .windowSampleCount)
            / PPGLiveMetricRuntimeProfile.iosBaseline01
                .cadenceSampleCount
            + 1
        let expectedCSVMetricTransitionCount =
            expectedMetricRequestCount - 1
        let rootURL = temporaryRoot()
        defer { try? FileManager.default.removeItem(at: rootURL) }

        let profile = PPGLiveMetricRuntimeProfile.iosBaseline01
        let configuration = makeConfiguration(
            algorithmVersion: profile.identifier,
            preprocessProfile:
                profile.preprocessingProfile.identifier
        )
        let writer = try CaptureSessionWriter(
            configuration: configuration,
            sessionsRootURL: rootURL
        )
        let frames = (0...Int(UInt8.max)).map {
            makeCUPTestFrame(sequence: UInt8($0))
        }
        var pipeline = CUPStreamingPipeline()
        var metricScheduler = PPGLiveMetricWindowScheduler(
            profile: profile
        )
        var currentMetrics = LiveMetricSnapshot.warmingUp()
        var metricWindowEnds: [UInt64] = []
        metricWindowEnds.reserveCapacity(expectedMetricRequestCount)
        let clock = ContinuousClock()
        let started = clock.now

        for frameIndex in 0..<frameCount {
            let frame = frames[frameIndex % frames.count]
            let frameHostTime =
                UInt64(frameIndex) * 500_000_000
            let chunks = [
                Data(frame.prefix(244)),
                Data(frame.dropFirst(244))
            ]

            for (chunkIndex, chunk) in chunks.enumerated() {
                let decodedFrames = pipeline.receive(chunk)
                let acceptedInChunk = decodedFrames.reduce(into: 0) {
                    if $1.isAccepted {
                        $0 += $1.frame.samples.count
                    }
                }
                let acceptedSampleStartIndex: UInt64? =
                    acceptedInChunk > 0
                    ? UInt64(
                        pipeline.diagnostics.acceptedSamples
                            - acceptedInChunk
                    )
                    : nil
                let request = acceptedInChunk > 0
                    ? metricScheduler.ingest(
                        decodedFrames: decodedFrames,
                        acceptedSampleStartIndex:
                            acceptedSampleStartIndex,
                        measuredAt: configuration.startedUTC.addingTimeInterval(
                            Double(frameIndex)
                                * Double(
                                    CUPBatchProtocolV1.samplesPerFrame
                                )
                                / Double(
                                    CUPBatchProtocolV1.sampleRateHz
                                )
                        )
                    )
                    : nil

                _ = try await writer.append(
                    CUPStreamChunkEvent(
                        hostMonotonicNanoseconds:
                            frameHostTime + UInt64(chunkIndex) * 1_000_000,
                        data: chunk,
                        decodedFrames: decodedFrames,
                        acceptedSampleStartIndex:
                            acceptedSampleStartIndex,
                        metrics: currentMetrics
                    )
                )

                if let request {
                    metricWindowEnds.append(
                        request.windowEndSampleIndex
                    )
                    currentMetrics = completedMetrics(
                        for: request,
                        profile: profile
                    )
                }
            }
        }

        let diagnostics = pipeline.diagnostics
        let summary = try await writer.finish(
            reason: .user,
            integrity: CaptureIntegritySnapshot(
                invalidFrames: diagnostics.structurallyInvalidFrames,
                discardedBytes: diagnostics.decoderStats.bytesDiscarded
            )
        )

        var waveformScheduler = CUPWaveformSnapshotScheduler()
        let expectedWaveformPublications = durationSeconds * 5
        for tick in 0..<expectedWaveformPublications {
            let didPublish = waveformScheduler.consumeTick(
                at: Double(tick) / 5
            )
            #expect(didPublish)
        }

        #expect(diagnostics.notificationChunks == rawChunkCount)
        #expect(diagnostics.receivedBytes == frameCount * 408)
        #expect(diagnostics.decodedFrames == frameCount)
        #expect(diagnostics.decodedSamples == sampleCount)
        #expect(diagnostics.acceptedSamples == sampleCount)
        #expect(diagnostics.recentSamples.count == 800)
        #expect(diagnostics.pendingDecoderBytes == 0)
        #expect(diagnostics.structurallyInvalidFrames == 0)
        #expect(diagnostics.decoderStats.bytesDiscarded == 0)
        #expect(diagnostics.sequenceStats.missingFrames == 0)
        #expect(diagnostics.sequenceStats.duplicateFrames == 0)
        #expect(diagnostics.sequenceStats.outOfOrderFrames == 0)

        #expect(metricScheduler.continuousSamples == sampleCount)
        #expect(metricScheduler.bufferedSampleCount == 800)
        #expect(
            metricWindowEnds.count == expectedMetricRequestCount
        )
        #expect(metricWindowEnds.first == 799)
        #expect(metricWindowEnds.last == UInt64(sampleCount - 1))
        #expect(
            metricWindowEnds.enumerated().allSatisfy { offset, end in
                end == UInt64(
                    799 + offset * profile.cadenceSampleCount
                )
            }
        )
        #expect(
            waveformScheduler.publicationCount
                == UInt64(expectedWaveformPublications)
        )

        #expect(summary.complete)
        #expect(summary.writer.rawChunkCount == rawChunkCount)
        #expect(summary.writer.rawPayloadBytes == frameCount * 408)
        #expect(
            summary.writer.rawFileBytes
                == CUPRawFileReader.magic.count
                    + frameCount * (12 + 244 + 12 + 164)
        )
        #expect(summary.writer.csvRows == sampleCount)
        #expect(summary.writer.acceptedFrames == frameCount)
        #expect(summary.writer.acceptedSamples == sampleCount)
        #expect(summary.writer.missingFrames == 0)
        #expect(summary.writer.duplicateFrames == 0)
        #expect(summary.writer.outOfOrderFrames == 0)

        let sessions = try CaptureSessionRepository.listSessions(
            rootURL: rootURL
        )
        let session = try #require(sessions.first)
        #expect(sessions.count == 1)
        #expect(session.isVerifiedComplete)
        let inspection = CaptureSessionInspectionService.inspect(session)
        #expect(inspection.isVerifiedConsistent)
        #expect(inspection.findings.isEmpty)
        let replay = try #require(inspection.replay)
        #expect(replay.readMode == .streaming)
        #expect(replay.rawRecordCount == rawChunkCount)
        #expect(replay.chunkLengthCounts == [164: frameCount, 244: frameCount])
        #expect(replay.acceptedFrames == frameCount)
        #expect(replay.acceptedSamples == sampleCount)
        #expect(replay.samples.count == sampleCount)
        #expect(replay.retainedSampleBytes == sampleCount * 8)
        #expect(
            replay.totalRawBytes
                == CUPRawFileReader.magic.count
                    + frameCount * (12 + 244 + 12 + 164)
        )
        #expect(replay.peakRawRecordBufferBytes == 256)
        #expect(
            replay.hostDurationSeconds
                == Double(durationSeconds) - 0.5
        )
        #expect(replay.isStructurallyClean)
        #expect(
            diagnostics.recentSamples.elementsEqual(
                replay.samples.suffix(800)
            )
        )

        let csv = try auditCSV(
            url: summary.directoryURL.appendingPathComponent(
                "long_duration.csv"
            ),
            profile: profile
        )
        #expect(csv.rowCount == sampleCount)
        #expect(csv.firstHeartRateRow == 800)
        #expect(csv.firstHeartRateTime == "7.990000")
        #expect(
            csv.heartRateTransitionCount
                == expectedCSVMetricTransitionCount
        )
        #expect(csv.firstSQIRow == 800)
        #expect(csv.firstSQITime == "7.990000")
        #expect(
            csv.sqiTransitionCount
                == expectedCSVMetricTransitionCount
        )
        #expect(
            csv.lastHeartRateTime
                == formattedSeconds(
                    sampleIndex: sampleCount - 101
                )
        )
        #expect(
            csv.lastDeviceTime
                == formattedSeconds(
                    sampleIndex: sampleCount - 1
                )
        )
        #expect(csv.peakReadBufferBytes < 66 * 1024)
        #expect(
            inspection.csv?.completeDataRowCount == sampleCount
        )

        let metadata = try CaptureSessionMetadataCodec.decode(
            Data(contentsOf: summary.directoryURL.appendingPathComponent(
                "long_duration.session.json"
            ))
        )
        #expect(metadata.complete)
        #expect(metadata.frameCount == frameCount)
        #expect(metadata.sampleCount == sampleCount)
        #expect(metadata.rawChunkCount == rawChunkCount)
        #expect(metadata.algVersion == profile.identifier)
        #expect(
            metadata.preprocessProfile
                == profile.preprocessingProfile.identifier
        )

        let elapsed = started.duration(to: clock.now)
        #expect(elapsed < elapsedBudget)
    }

    private func completedMetrics(
        for request: PPGLiveMetricAnalysisRequest,
        profile: PPGLiveMetricRuntimeProfile
    ) -> LiveMetricSnapshot {
        LiveMetricSnapshot.runtime(
            heartRateBPM: .valid(
                72,
                measuredAt: request.measuredAt,
                algorithmVersion:
                    profile.heartRateConfiguration.algorithmVersion,
                sourceSampleIndex: request.windowEndSampleIndex,
                sourceTimeSeconds: request.windowEndTimeSeconds
            ),
            signalQuality: .valid(
                0.812,
                measuredAt: request.measuredAt,
                algorithmVersion:
                    profile.signalQualityConfiguration.algorithmVersion,
                sourceSampleIndex: request.windowEndSampleIndex,
                sourceTimeSeconds: request.windowEndTimeSeconds,
                isProvisional: true
            )
        )
    }

    private func auditCSV(
        url: URL,
        profile: PPGLiveMetricRuntimeProfile
    ) throws -> LongDurationCSVReport {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }

        var buffer = Data()
        var sawHeader = false
        var rowCount = 0
        var firstHeartRateRow: Int?
        var firstHeartRateTime: String?
        var lastHeartRateTime: String?
        var heartRateTransitionCount = 0
        var firstSQIRow: Int?
        var firstSQITime: String?
        var lastSQITime: String?
        var sqiTransitionCount = 0
        var lastDeviceTime: String?
        var peakReadBufferBytes = 0

        func process(_ bytes: Data.SubSequence) throws {
            let line = String(decoding: bytes, as: UTF8.self)
            if !sawHeader {
                guard line + "\n" == CaptureCSVSchema.header else {
                    throw LongDurationCSVError("CSV header 不匹配。")
                }
                sawHeader = true
                return
            }

            let fields = line.split(
                separator: ",",
                omittingEmptySubsequences: false
            )
            guard fields.count == CaptureCSVSchema.columns.count else {
                throw LongDurationCSVError(
                    "CSV 第 \(rowCount) 行字段数错误。"
                )
            }
            guard fields[2] == Substring(String(rowCount)) else {
                throw LongDurationCSVError(
                    "CSV sample_index 在 \(rowCount) 处不连续。"
                )
            }
            guard fields[12].isEmpty,
                  fields[13] == "false",
                  fields[14].isEmpty else {
                throw LongDurationCSVError(
                    "SpO₂ 在第 \(rowCount) 行泄漏数值。"
                )
            }
            guard fields[18] == "1.0+long",
                  fields[19] == Substring(profile.identifier),
                  fields[20]
                    == Substring(
                        profile.preprocessingProfile.identifier
                    ),
                  fields[21] == "cup_batch_v1_draft" else {
                throw LongDurationCSVError(
                    "版本字段在第 \(rowCount) 行发生漂移。"
                )
            }

            if fields[10] == "true" {
                guard rowCount >= 800,
                      !fields[9].isEmpty,
                      !fields[11].isEmpty else {
                    throw LongDurationCSVError(
                        "HR 在 warmup 完成前有效。"
                    )
                }
                let time = String(fields[11])
                if firstHeartRateRow == nil {
                    firstHeartRateRow = rowCount
                    firstHeartRateTime = time
                }
                if time != lastHeartRateTime {
                    heartRateTransitionCount += 1
                    lastHeartRateTime = time
                }
            } else {
                guard rowCount < 800,
                      fields[9].isEmpty,
                      fields[11].isEmpty else {
                    throw LongDurationCSVError(
                        "HR valid/time 在第 \(rowCount) 行不一致。"
                    )
                }
            }

            if fields[16] == "true" {
                guard rowCount >= 800,
                      let score = Double(String(fields[15])),
                      score >= 0,
                      score <= 1,
                      !fields[17].isEmpty else {
                    throw LongDurationCSVError(
                        "SQI 在第 \(rowCount) 行未携带有效的 0...1 分值/时间。"
                    )
                }
                let time = String(fields[17])
                if firstSQIRow == nil {
                    firstSQIRow = rowCount
                    firstSQITime = time
                }
                if time != lastSQITime {
                    sqiTransitionCount += 1
                    lastSQITime = time
                }
            } else {
                guard rowCount < 800,
                      fields[15] == "0",
                      fields[17].isEmpty else {
                    throw LongDurationCSVError(
                        "SQI warmup 行在第 \(rowCount) 行泄漏数值。"
                    )
                }
            }

            lastDeviceTime = String(fields[3])
            rowCount += 1
        }

        while let block = try handle.read(upToCount: 64 * 1024),
              !block.isEmpty {
            buffer.append(block)
            peakReadBufferBytes = max(
                peakReadBufferBytes,
                buffer.count
            )
            var lineStart = buffer.startIndex
            while let newline = buffer[lineStart...]
                .firstIndex(of: 0x0A) {
                try process(buffer[lineStart..<newline])
                lineStart = buffer.index(after: newline)
                if lineStart == buffer.endIndex {
                    break
                }
            }
            buffer = Data(buffer[lineStart...])
        }
        guard buffer.isEmpty, sawHeader else {
            throw LongDurationCSVError("CSV 尾部不完整。")
        }

        return LongDurationCSVReport(
            rowCount: rowCount,
            firstHeartRateRow: firstHeartRateRow,
            firstHeartRateTime: firstHeartRateTime,
            heartRateTransitionCount: heartRateTransitionCount,
            lastHeartRateTime: lastHeartRateTime,
            firstSQIRow: firstSQIRow,
            firstSQITime: firstSQITime,
            sqiTransitionCount: sqiTransitionCount,
            lastDeviceTime: lastDeviceTime,
            peakReadBufferBytes: peakReadBufferBytes
        )
    }

    private func formattedSeconds(sampleIndex: Int) -> String {
        String(
            format: "%.6f",
            locale: Locale(identifier: "en_US_POSIX"),
            Double(sampleIndex)
                / Double(CUPBatchProtocolV1.sampleRateHz)
        )
    }

    private func temporaryRoot() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent(
            "PPGCollectorLongDurationTests-\(UUID().uuidString)"
        )
    }

    private func makeConfiguration(
        algorithmVersion: String,
        preprocessProfile: String
    ) -> CaptureSessionConfiguration {
        CaptureSessionConfiguration(
            sessionID: UUID(
                uuidString: "12345678-1234-1234-1234-1234567890AB"
            )!,
            baseName: "long_duration",
            startedUTC: Date(timeIntervalSince1970: 1_800_000_000),
            softVersion: "1.0+long",
            algorithmVersion: algorithmVersion,
            preprocessProfile: preprocessProfile,
            protocolProfile: "cup_batch_v1_draft",
            transportProfile: "cup-nus-bringup-0.1",
            device: CaptureDeviceContext(
                name: "CUP-SIM-LONG",
                identifier: UUID(
                    uuidString:
                        "ABCDEFAB-CDEF-CDEF-CDEF-ABCDEFABCDEF"
                )!,
                serviceUUID:
                    "6E400001-B5A3-F393-E0A9-E50E24DCCA9E",
                notifyCharacteristicUUID:
                    "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
            )
        )
    }
}

private struct LongDurationCSVReport {
    let rowCount: Int
    let firstHeartRateRow: Int?
    let firstHeartRateTime: String?
    let heartRateTransitionCount: Int
    let lastHeartRateTime: String?
    let firstSQIRow: Int?
    let firstSQITime: String?
    let sqiTransitionCount: Int
    let lastDeviceTime: String?
    let peakReadBufferBytes: Int
}

private struct LongDurationCSVError: LocalizedError {
    let message: String

    init(_ message: String) {
        self.message = message
    }

    var errorDescription: String? {
        message
    }
}
