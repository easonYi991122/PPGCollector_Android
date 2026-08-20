import Foundation

nonisolated enum PPGExpertChannel: String, CaseIterable, Identifiable, Sendable {
    case red = "RED"
    case ir = "IR"

    var id: Self { self }
}

nonisolated enum PPGExpertSignalStage: String, CaseIterable, Identifiable, Sendable {
    case raw
    case causallyPreprocessed

    var id: Self { self }

    var title: String {
        switch self {
        case .raw:
            "原始"
        case .causallyPreprocessed:
            "因果预处理"
        }
    }
}

nonisolated struct PPGExpertInputDiagnostics: Equatable, Sendable {
    let rawRecordCount: Int
    let decodedFrameCount: Int
    let acceptedFrameCount: Int
    let acceptedSampleCount: Int
    let missingFrameCount: Int
    let duplicateFrameCount: Int
    let outOfOrderFrameCount: Int
    let structurallyInvalidFrameCount: Int
    let discardedByteCount: Int
    let trailingByteCount: Int
}

nonisolated struct PPGExpertStableSegment: Equatable, Sendable, Identifiable {
    let id: Int
    let startSampleIndex: Int
    let endSampleIndex: Int
    let acceptedWindowCount: Int
    let meanHeartRateBPM: Double?
    let meanSQI: Double?

    var durationSeconds: Double {
        Double(max(0, endSampleIndex - startSampleIndex + 1))
            / Double(CUPBatchProtocolV1.sampleRateHz)
    }
}

nonisolated struct PPGExpertWindow: Equatable, Sendable, Identifiable {
    let id: Int
    let startSampleIndex: Int
    let endSampleIndex: Int
    let startTimeSeconds: Double
    let endTimeSeconds: Double
    let selectedChannel: PPGExpertChannel
    let redHeartRate: HeartRateEstimate
    let irHeartRate: HeartRateEstimate
    let selectedBPM: Double?
    let signalQuality: TemplateMatchSQIEstimate
    let ratioOfRatios: RatioOfRatiosEstimate
    let accepted: Bool
    let rejectionReason: String?

    var selectedHeartRate: HeartRateEstimate {
        selectedChannel == .red ? redHeartRate : irHeartRate
    }

    var stable: Bool {
        accepted && signalQuality.isValid && (signalQuality.sqi ?? 0) >= 0.70
    }
}

nonisolated struct PPGExpertDiagnosticsReport: Equatable, Sendable {
    let sampleRateHz: Double
    let input: PPGExpertInputDiagnostics
    let timeSeconds: [Double]
    let rawRED: [Double]
    let rawIR: [Double]
    let preprocessedRED: [Double]
    let preprocessedIR: [Double]
    let windows: [PPGExpertWindow]
    let stableSegments: [PPGExpertStableSegment]

    var durationSeconds: Double {
        timeSeconds.last ?? 0
    }
}

nonisolated enum PPGExpertDiagnosticsService {
    static let windowSampleCount = CUPBatchProtocolV1.sampleRateHz * 8
    static let hopSampleCount = CUPBatchProtocolV1.sampleRateHz

    static func analyze(
        session: StoredCaptureSession,
        profile: PPGLiveMetricRuntimeProfile = .iosBaseline01,
        cancellationCheck: () throws -> Void = {
            try Task.checkCancellation()
        }
    ) throws -> PPGExpertDiagnosticsReport {
        let rawURL = CaptureSessionAnalysisService.rawFileURL(for: session)
        var pipeline = CUPStreamingPipeline(recentSampleCapacity: 0)
        var samples: [CUPPPGSample] = []
        var continuityBreaks: Set<Int> = []
        var decodedFrameCount = 0
        var acceptedFrameCount = 0

        let scan = try CUPRawFileReader.scanRecords(
            url: rawURL,
            cancellationCheck: cancellationCheck
        ) { record in
            try cancellationCheck()
            for event in pipeline.receive(record.chunk) {
                decodedFrameCount += 1
                guard event.isAccepted else {
                    continue
                }
                acceptedFrameCount += 1
                if case .gap = event.sequenceEvent {
                    continuityBreaks.insert(samples.count)
                }
                samples.append(contentsOf: event.frame.samples)
            }
        }
        try cancellationCheck()

        let rawRED = samples.map { Double($0.red) }
        let rawIR = samples.map { Double($0.ir) }
        var redPreprocessor = PPGPreprocessor(
            profile: profile.preprocessingProfile
        )
        var irPreprocessor = PPGPreprocessor(
            profile: profile.preprocessingProfile
        )
        var preprocessedRED: [Double] = []
        var preprocessedIR: [Double] = []
        preprocessedRED.reserveCapacity(samples.count)
        preprocessedIR.reserveCapacity(samples.count)
        for index in samples.indices {
            try cancellationCheck()
            if continuityBreaks.contains(index) {
                redPreprocessor.reset()
                irPreprocessor.reset()
            }
            let red = redPreprocessor.process(raw: rawRED[index]).sample?.bandpassed
            let ir = irPreprocessor.process(raw: rawIR[index]).sample?.bandpassed
            preprocessedRED.append(red ?? 0)
            preprocessedIR.append(ir ?? 0)
        }

        let timeSeconds = samples.indices.map {
            Double($0) / Double(profile.sampleRateHz)
        }
        let windows = try makeWindows(
            rawRED: rawRED,
            rawIR: rawIR,
            preprocessedRED: preprocessedRED,
            preprocessedIR: preprocessedIR,
            timeSeconds: timeSeconds,
            continuityBreaks: continuityBreaks,
            profile: profile,
            cancellationCheck: cancellationCheck
        )
        let stableSegments = makeStableSegments(windows)
        let diagnostics = pipeline.diagnostics
        return PPGExpertDiagnosticsReport(
            sampleRateHz: Double(profile.sampleRateHz),
            input: PPGExpertInputDiagnostics(
                rawRecordCount: scan.recordCount,
                decodedFrameCount: decodedFrameCount,
                acceptedFrameCount: acceptedFrameCount,
                acceptedSampleCount: diagnostics.acceptedSamples,
                missingFrameCount: diagnostics.sequenceStats.missingFrames,
                duplicateFrameCount: diagnostics.sequenceStats.duplicateFrames,
                outOfOrderFrameCount: diagnostics.sequenceStats.outOfOrderFrames,
                structurallyInvalidFrameCount: diagnostics.structurallyInvalidFrames,
                discardedByteCount: diagnostics.decoderStats.bytesDiscarded,
                trailingByteCount: scan.trailingByteCount
            ),
            timeSeconds: timeSeconds,
            rawRED: rawRED,
            rawIR: rawIR,
            preprocessedRED: preprocessedRED,
            preprocessedIR: preprocessedIR,
            windows: windows,
            stableSegments: stableSegments
        )
    }

    private static func makeWindows(
        rawRED: [Double],
        rawIR: [Double],
        preprocessedRED: [Double],
        preprocessedIR: [Double],
        timeSeconds: [Double],
        continuityBreaks: Set<Int>,
        profile: PPGLiveMetricRuntimeProfile,
        cancellationCheck: () throws -> Void
    ) rethrows -> [PPGExpertWindow] {
        guard rawIR.count >= windowSampleCount else {
            return []
        }
        var windows: [PPGExpertWindow] = []
        var start = 0
        var windowID = 0
        while start + windowSampleCount <= rawIR.count {
            try cancellationCheck()
            let end = start + windowSampleCount
            let hasBreak = continuityBreaks.contains { $0 > start && $0 < end }
            if !hasBreak {
                let rawRedWindow = Array(rawRED[start..<end])
                let rawIRWindow = Array(rawIR[start..<end])
                let redWindow = Array(preprocessedRED[start..<end])
                let irWindow = Array(preprocessedIR[start..<end])
                let times = Array(timeSeconds[start..<end])
                let redHeartRate = HeartRateEstimator.estimate(
                    values: redWindow,
                    timeSeconds: times,
                    sampleRateHz: Double(profile.sampleRateHz),
                    configuration: profile.heartRateConfiguration
                )
                let irHeartRate = HeartRateEstimator.estimate(
                    values: irWindow,
                    timeSeconds: times,
                    sampleRateHz: Double(profile.sampleRateHz),
                    configuration: profile.heartRateConfiguration
                )
                let selectedChannel: PPGExpertChannel =
                    redHeartRate.confidence > irHeartRate.confidence
                        ? .red
                        : .ir
                let normalized = PPGWindowNormalizer.normalize(
                    irWindow,
                    profile: profile.preprocessingProfile
                )
                let sqi = normalized.normalizedValues.map {
                    TemplateMatchSQI.compute(
                        preprocessedPeakUpValues: $0,
                        configuration: profile.signalQualityConfiguration
                    )
                } ?? TemplateMatchSQI.compute(
                    preprocessedPeakUpValues: [],
                    configuration: profile.signalQualityConfiguration
                )
                let ratio = RatioOfRatiosEstimator.estimate(
                    redBandpassed: redWindow,
                    irBandpassed: irWindow,
                    redRaw: rawRedWindow,
                    irRaw: rawIRWindow
                )
                let selected = selectedChannel == .red
                    ? redHeartRate
                    : irHeartRate
                let acceptedBPM = HeartRateEstimator.acceptedBPM(
                    selected,
                    configuration: profile.heartRateConfiguration
                )
                let accepted = acceptedBPM != nil && sqi.isValid
                let reason = accepted
                    ? nil
                    : rejectionReason(
                        heartRate: selected,
                        sqi: sqi
                    )
                windows.append(
                    PPGExpertWindow(
                        id: windowID,
                        startSampleIndex: start,
                        endSampleIndex: end - 1,
                        startTimeSeconds: times.first ?? 0,
                        endTimeSeconds: times.last ?? 0,
                        selectedChannel: selectedChannel,
                        redHeartRate: redHeartRate,
                        irHeartRate: irHeartRate,
                        selectedBPM: acceptedBPM,
                        signalQuality: sqi,
                        ratioOfRatios: ratio,
                        accepted: accepted,
                        rejectionReason: reason
                    )
                )
                windowID += 1
            }
            start += hopSampleCount
        }
        return windows
    }

    private static func rejectionReason(
        heartRate: HeartRateEstimate,
        sqi: TemplateMatchSQIEstimate
    ) -> String {
        if let reason = heartRate.unavailableReason?.rawValue {
            return "HR: \(reason)"
        }
        if let reason = sqi.unavailableReason?.referenceCode {
            return "SQI: \(reason)"
        }
        return "窗口未达到稳定准入条件"
    }

    private static func makeStableSegments(
        _ windows: [PPGExpertWindow]
    ) -> [PPGExpertStableSegment] {
        var result: [PPGExpertStableSegment] = []
        var current: [PPGExpertWindow] = []
        func appendCurrent() {
            guard !current.isEmpty else { return }
            let hr = current.compactMap(\.selectedBPM)
            let sqi = current.compactMap(\.signalQuality.sqi)
            result.append(
                PPGExpertStableSegment(
                    id: result.count,
                    startSampleIndex: current.first!.startSampleIndex,
                    endSampleIndex: current.last!.endSampleIndex,
                    acceptedWindowCount: current.count,
                    meanHeartRateBPM: hr.isEmpty ? nil : hr.reduce(0, +) / Double(hr.count),
                    meanSQI: sqi.isEmpty ? nil : sqi.reduce(0, +) / Double(sqi.count)
                )
            )
            current.removeAll(keepingCapacity: true)
        }
        for window in windows {
            if window.stable {
                current.append(window)
            } else {
                appendCurrent()
            }
        }
        appendCurrent()
        return result
    }
}
