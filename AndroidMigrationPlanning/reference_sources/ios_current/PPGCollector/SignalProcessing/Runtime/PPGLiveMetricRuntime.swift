import Foundation

nonisolated struct PPGLiveMetricRuntimeProfile:
    Equatable,
    Sendable
{
    let identifier: String
    let sampleRateHz: Int
    let windowSampleCount: Int
    let cadenceSampleCount: Int
    let preprocessingProfile: PPGPreprocessingProfile
    let heartRateConfiguration: HeartRateConfiguration
    let signalQualityConfiguration: TemplateMatchSQIConfiguration
    let signalQualityIsApproved: Bool

    static let iosBaseline01 = PPGLiveMetricRuntimeProfile(
        identifier: "ppg-ios-live-0.1",
        sampleRateHz: CUPBatchProtocolV1.sampleRateHz,
        windowSampleCount: CUPBatchProtocolV1.sampleRateHz * 8,
        cadenceSampleCount: CUPBatchProtocolV1.sampleRateHz,
        preprocessingProfile: .iosBaseline01,
        heartRateConfiguration: .pythonBaseline01,
        signalQualityConfiguration: .iosBaseline01,
        signalQualityIsApproved: false
    )
}

nonisolated struct PPGLiveMetricAnalysisRequest:
    Equatable,
    Sendable
{
    let generation: UInt64
    let requestSequence: UInt64
    let windowEndSampleIndex: UInt64
    let windowEndTimeSeconds: Double
    let measuredAt: Date
    let rawRED: [Double]
    let rawIR: [Double]
    let bandpassedRED: [Double]
    let bandpassedIR: [Double]
    let timeSeconds: [Double]

    init(
        generation: UInt64,
        requestSequence: UInt64,
        windowEndSampleIndex: UInt64,
        windowEndTimeSeconds: Double,
        measuredAt: Date,
        rawRED: [Double] = [],
        rawIR: [Double] = [],
        bandpassedRED: [Double] = [],
        bandpassedIR: [Double],
        timeSeconds: [Double]
    ) {
        self.generation = generation
        self.requestSequence = requestSequence
        self.windowEndSampleIndex = windowEndSampleIndex
        self.windowEndTimeSeconds = windowEndTimeSeconds
        self.measuredAt = measuredAt
        self.rawRED = rawRED
        self.rawIR = rawIR
        self.bandpassedRED = bandpassedRED
        self.bandpassedIR = bandpassedIR
        self.timeSeconds = timeSeconds
    }
}

nonisolated struct PPGLiveMetricAnalysisResult:
    Equatable,
    Sendable
{
    let request: PPGLiveMetricAnalysisRequest
    let snapshot: LiveMetricSnapshot
    let provisionalSignalQuality: TemplateMatchSQIEstimate?
}

/// Async boundary for the isolated live-metric worker. Production keeps the
/// existing HR/SQI analyzer; integration tests can deliberately hold work
/// in-flight while exercising the BLE and recording paths.
nonisolated struct PPGLiveMetricAnalysisRunner: Sendable {
    let run: @Sendable (
        PPGLiveMetricAnalysisRequest,
        PPGLiveMetricRuntimeProfile
    ) async -> PPGLiveMetricAnalysisResult?

    init(
        run: @escaping @Sendable (
            PPGLiveMetricAnalysisRequest,
            PPGLiveMetricRuntimeProfile
        ) async -> PPGLiveMetricAnalysisResult?
    ) {
        self.run = run
    }

    static let production = PPGLiveMetricAnalysisRunner {
        request,
        profile in
        guard !Task.isCancelled else {
            return nil
        }
        let result = PPGLiveMetricAnalyzer.analyze(
            request,
            profile: profile
        )
        return Task.isCancelled ? nil : result
    }
}

nonisolated struct PPGLiveMetricRuntimeDiagnostics:
    Equatable,
    Sendable
{
    var continuousSampleCount = 0
    var bufferedSampleCount = 0
    var scheduledAnalysisCount = 0
    var completedAnalysisCount = 0
    var discardedAnalysisCount = 0
    var latestWindowEndSampleIndex: UInt64?
    var latestHeartRateReason: String?
    var latestProvisionalSQI: Double?
    var latestProvisionalSQIGrade: String?
    var latestProvisionalSQIReason: String?
}

/// Stateful causal preprocessing and sample-count scheduling for live metrics.
///
/// The first analysis is emitted only after eight continuous seconds. Later
/// requests are emitted every 100 accepted samples. A sequence gap, stale
/// stream, or connection reset clears filter/window state so no result spans a
/// discontinuity.
nonisolated struct PPGLiveMetricWindowScheduler: Sendable {
    let profile: PPGLiveMetricRuntimeProfile

    private var preprocessor: PPGPreprocessor
    private var redPreprocessor: PPGPreprocessor
    private var rawRED: [Double] = []
    private var rawIR: [Double] = []
    private var bandpassedRED: [Double] = []
    private var bandpassedIR: [Double] = []
    private var timeSeconds: [Double] = []
    private var nextAcceptedSampleIndex: UInt64 = 0
    private var continuousSampleCount = 0
    private var nextAnalysisContinuousSampleCount: Int
    private(set) var generation: UInt64 = 0
    private(set) var requestSequence: UInt64 = 0

    init(
        profile: PPGLiveMetricRuntimeProfile = .iosBaseline01
    ) {
        self.profile = profile
        preprocessor = PPGPreprocessor(
            profile: profile.preprocessingProfile
        )
        redPreprocessor = PPGPreprocessor(
            profile: profile.preprocessingProfile
        )
        nextAnalysisContinuousSampleCount = profile.windowSampleCount
    }

    var bufferedSampleCount: Int {
        bandpassedIR.count
    }

    var continuousSamples: Int {
        continuousSampleCount
    }

    mutating func ingest(
        decodedFrames: [CUPDecodedFrameEvent],
        acceptedSampleStartIndex: UInt64? = nil,
        measuredAt: Date
    ) -> PPGLiveMetricAnalysisRequest? {
        if let acceptedSampleStartIndex,
           acceptedSampleStartIndex != nextAcceptedSampleIndex {
            invalidateContinuity()
            nextAcceptedSampleIndex = acceptedSampleStartIndex
        }

        var analysisIsDue = false
        for decoded in decodedFrames where decoded.isAccepted {
            if case .gap = decoded.sequenceEvent {
                invalidateContinuity()
            }

            for sample in decoded.frame.samples {
                let redResult = redPreprocessor.process(raw: Double(sample.red))
                let irResult = preprocessor.process(raw: Double(sample.ir))
                guard let redProcessed = redResult.sample,
                      let irProcessed = irResult.sample else {
                    invalidateContinuity()
                    nextAcceptedSampleIndex &+= 1
                    continue
                }

                rawRED.append(Double(sample.red))
                rawIR.append(Double(sample.ir))
                bandpassedRED.append(redProcessed.bandpassed)
                bandpassedIR.append(irProcessed.bandpassed)
                timeSeconds.append(
                    Double(nextAcceptedSampleIndex)
                        / Double(profile.sampleRateHz)
                )
                nextAcceptedSampleIndex &+= 1
                continuousSampleCount += 1
            }
            trimWindowIfNeeded()

            while continuousSampleCount
                    >= nextAnalysisContinuousSampleCount {
                analysisIsDue = true
                nextAnalysisContinuousSampleCount +=
                    profile.cadenceSampleCount
            }
        }

        guard analysisIsDue,
              rawRED.count == profile.windowSampleCount,
              rawIR.count == profile.windowSampleCount,
              bandpassedRED.count == profile.windowSampleCount,
              bandpassedIR.count == profile.windowSampleCount,
              timeSeconds.count == profile.windowSampleCount,
              let windowEndSampleIndex = currentWindowEndSampleIndex else {
            return nil
        }

        requestSequence &+= 1
        return PPGLiveMetricAnalysisRequest(
            generation: generation,
            requestSequence: requestSequence,
            windowEndSampleIndex: windowEndSampleIndex,
            windowEndTimeSeconds:
                Double(windowEndSampleIndex)
                / Double(profile.sampleRateHz),
            measuredAt: measuredAt,
            rawRED: rawRED,
            rawIR: rawIR,
            bandpassedRED: bandpassedRED,
            bandpassedIR: bandpassedIR,
            timeSeconds: timeSeconds
        )
    }

    func isCurrent(
        _ request: PPGLiveMetricAnalysisRequest
    ) -> Bool {
        request.generation == generation
            && request.requestSequence == requestSequence
    }

    mutating func invalidateContinuity() {
        preprocessor.reset()
        redPreprocessor.reset()
        rawRED.removeAll(keepingCapacity: true)
        rawIR.removeAll(keepingCapacity: true)
        bandpassedRED.removeAll(keepingCapacity: true)
        bandpassedIR.removeAll(keepingCapacity: true)
        timeSeconds.removeAll(keepingCapacity: true)
        continuousSampleCount = 0
        nextAnalysisContinuousSampleCount = profile.windowSampleCount
        generation &+= 1
    }

    mutating func reset() {
        invalidateContinuity()
        nextAcceptedSampleIndex = 0
    }

    private var currentWindowEndSampleIndex: UInt64? {
        guard nextAcceptedSampleIndex > 0 else {
            return nil
        }
        return nextAcceptedSampleIndex - 1
    }

    private mutating func trimWindowIfNeeded() {
        let overflow = bandpassedIR.count - profile.windowSampleCount
        guard overflow > 0 else {
            return
        }
        rawRED.removeFirst(overflow)
        rawIR.removeFirst(overflow)
        bandpassedRED.removeFirst(overflow)
        bandpassedIR.removeFirst(overflow)
        timeSeconds.removeFirst(overflow)
    }
}

nonisolated enum PPGLiveMetricAnalyzer {
    static func analyze(
        _ request: PPGLiveMetricAnalysisRequest,
        profile: PPGLiveMetricRuntimeProfile = .iosBaseline01
    ) -> PPGLiveMetricAnalysisResult {
        let heartRate = HeartRateEstimator.estimate(
            values: request.bandpassedIR,
            timeSeconds: request.timeSeconds,
            sampleRateHz: Double(profile.sampleRateHz),
            configuration: profile.heartRateConfiguration
        )
        let heartRateMetric: MetricResult<Double>
        if let bpm = HeartRateEstimator.acceptedBPM(
            heartRate,
            configuration: profile.heartRateConfiguration
        ), bpm.isFinite {
            heartRateMetric = .valid(
                bpm,
                measuredAt: request.measuredAt,
                algorithmVersion:
                    profile.heartRateConfiguration.algorithmVersion,
                sourceSampleIndex: request.windowEndSampleIndex,
                sourceTimeSeconds: request.windowEndTimeSeconds
            )
        } else {
            heartRateMetric = .unavailable(
                metricReason(for: heartRate.unavailableReason),
                algorithmVersion:
                    profile.heartRateConfiguration.algorithmVersion,
                measuredAt: request.measuredAt,
                sourceSampleIndex: request.windowEndSampleIndex,
                sourceTimeSeconds: request.windowEndTimeSeconds
            )
        }

        let normalized = PPGWindowNormalizer.normalize(
            request.bandpassedIR,
            profile: profile.preprocessingProfile
        )
        let provisionalSignalQuality = normalized.normalizedValues.map {
            TemplateMatchSQI.compute(
                preprocessedPeakUpValues: $0,
                configuration: profile.signalQualityConfiguration
            )
        }
        let signalQualityMetric: MetricResult<Double>
        if let estimate = provisionalSignalQuality,
           let sqi = estimate.sqi,
           estimate.isValid,
           sqi.isFinite {
            signalQualityMetric = .valid(
                sqi,
                measuredAt: request.measuredAt,
                algorithmVersion:
                    profile.signalQualityConfiguration.algorithmVersion,
                sourceSampleIndex: request.windowEndSampleIndex,
                sourceTimeSeconds: request.windowEndTimeSeconds,
                isProvisional: !profile.signalQualityIsApproved
            )
        } else {
            signalQualityMetric = .unavailable(
                .computationFailed,
                algorithmVersion:
                    profile.signalQualityConfiguration.algorithmVersion,
                measuredAt: request.measuredAt,
                sourceSampleIndex: request.windowEndSampleIndex,
                sourceTimeSeconds: request.windowEndTimeSeconds
            )
        }

        let ratioEstimate = RatioOfRatiosEstimator.estimate(
            redBandpassed: request.bandpassedRED,
            irBandpassed: request.bandpassedIR,
            redRaw: request.rawRED,
            irRaw: request.rawIR
        )
        let ratioMetric: MetricResult<Double>
        if let ratio = ratioEstimate.value, ratio.isFinite {
            ratioMetric = .valid(
                ratio,
                measuredAt: request.measuredAt,
                algorithmVersion: ratioEstimate.algorithmVersion,
                sourceSampleIndex: request.windowEndSampleIndex,
                sourceTimeSeconds: request.windowEndTimeSeconds,
                isProvisional: true
            )
        } else {
            ratioMetric = .unavailable(
                .computationFailed,
                algorithmVersion: ratioEstimate.algorithmVersion,
                measuredAt: request.measuredAt,
                sourceSampleIndex: request.windowEndSampleIndex,
                sourceTimeSeconds: request.windowEndTimeSeconds
            )
        }

        return PPGLiveMetricAnalysisResult(
            request: request,
            snapshot: .runtime(
                heartRateBPM: heartRateMetric,
                signalQuality: signalQualityMetric,
                ratioOfRatios: ratioMetric
            ),
            provisionalSignalQuality: provisionalSignalQuality
        )
    }

    private static func metricReason(
        for reason: HeartRateUnavailableReason?
    ) -> MetricUnavailableReason {
        switch reason {
        case .insufficientSamples, .insufficientWorkWindow:
            .insufficientData
        case .invalidConfiguration,
             .inputLengthMismatch,
             .nonFiniteInput,
             .insufficientAmplitude,
             .noPeakCandidate,
             .lowConfidence,
             nil:
            .computationFailed
        }
    }
}
