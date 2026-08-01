import Foundation

nonisolated enum TemplateMatchSQIGrade:
    String,
    Codable,
    Equatable,
    Sendable
{
    case good = "Good"
    case fair = "Fair"
    case poor = "Poor"
    case unavailable = "—"

    var colorHex: String {
        switch self {
        case .good:
            "#2E7D32"
        case .fair:
            "#EF6C00"
        case .poor:
            "#C62828"
        case .unavailable:
            "#888888"
        }
    }
}

nonisolated enum TemplateMatchSQIUnavailableReason:
    Equatable,
    Sendable
{
    case invalidConfiguration
    case nonFiniteInput
    case signalTooShort
    case constantSignal
    case templateFailed(String)
    case noCycleQuality

    var referenceCode: String {
        switch self {
        case .invalidConfiguration:
            "invalid_configuration"
        case .nonFiniteInput:
            "non_finite_input"
        case .signalTooShort:
            "signal_too_short"
        case .constantSignal:
            "constant_signal"
        case let .templateFailed(detail):
            "template_failed:\(detail)"
        case .noCycleQuality:
            "no_cycle_quality"
        }
    }
}

nonisolated enum SQIPeakDetectionMode:
    String,
    Codable,
    Equatable,
    Sendable
{
    case primary
    case fallback
}

nonisolated struct TemplateMatchSQIConfiguration:
    Equatable,
    Sendable
{
    let algorithmVersion: String
    let preprocessProfile: String
    let sampleRateHz: Double
    let ratioPre: Double
    let maximumHeartRateBPM: Double
    let minimumWindowSeconds: Double
    let standardDeviationEpsilon: Double
    let primaryHeightStandardDeviationFactor: Double
    let primaryProminenceStandardDeviationFactor: Double
    let primaryMinimumWidthSamples: Double
    let fallbackHeightStandardDeviationFactor: Double
    let fallbackProminenceStandardDeviationFactor: Double
    let goodThreshold: Double
    let fairThreshold: Double

    static let iosBaseline01 = TemplateMatchSQIConfiguration(
        algorithmVersion: "ppg-ios-sqi-0.1",
        preprocessProfile: "ios_baseline_0.1",
        sampleRateHz: 100,
        ratioPre: 0.5,
        maximumHeartRateBPM: 180,
        minimumWindowSeconds: 4,
        standardDeviationEpsilon: 1e-8,
        primaryHeightStandardDeviationFactor: 0.5,
        primaryProminenceStandardDeviationFactor: 0.3,
        primaryMinimumWidthSamples: 5,
        fallbackHeightStandardDeviationFactor: 0.2,
        fallbackProminenceStandardDeviationFactor: 0.2,
        goodThreshold: 0.90,
        fairThreshold: 0.70
    )
}

nonisolated struct SQIPeakPassTrace: Equatable, Sendable {
    let minimumHeight: Double
    let minimumProminence: Double
    let minimumWidthSamples: Double?
    let peaks: [SciPyPeak]
}

nonisolated struct SQIPeakDetectionTrace: Equatable, Sendable {
    let mean: Double
    let standardDeviation: Double
    let minimumDistanceSamples: Int
    let primary: SQIPeakPassTrace
    let fallbackWasEvaluated: Bool
    let fallback: SQIPeakPassTrace
    let selectedMode: SQIPeakDetectionMode
    let selectedPeakIndices: [Int]
}

nonisolated struct SQICycleTrace: Equatable, Sendable {
    let error: String?
    let rateBPM: Double?
    let preSamples: Int?
    let postSamples: Int?
    let windowLength: Int?
    let timeAxisSeconds: [Double]
    let cycleValidMask: [Bool]
    let droppedPeakIndices: [Int]
    let validPeakIndices: [Int]
    let cycles: [[Double]]
    let template: [Double]
    let qualityAnchorPeakIndices: [Int]
    let cycleQuality: [Double]
    let qualityTrace: [Double]
}

nonisolated struct TemplateMatchSQIDebugTrace:
    Equatable,
    Sendable
{
    let peakDetection: SQIPeakDetectionTrace?
    let cycles: SQICycleTrace
}

nonisolated struct TemplateMatchSQIEstimate: Equatable, Sendable {
    let sqi: Double?
    let rawMeanQuality: Double?
    let isValid: Bool
    let unavailableReason: TemplateMatchSQIUnavailableReason?
    let grade: TemplateMatchSQIGrade
    let cycleCount: Int
    let peakCount: Int
    let estimatedHeartRateBPM: Double?
    let algorithmVersion: String
    let preprocessProfile: String
    let trace: TemplateMatchSQIDebugTrace

    var reasonCode: String {
        isValid ? "ok" : (
            unavailableReason?.referenceCode ?? "no_cycle_quality"
        )
    }
}

nonisolated enum TemplateMatchSQI {
    static func compute(
        preprocessedPeakUpValues values: [Double],
        configuration: TemplateMatchSQIConfiguration = .iosBaseline01
    ) -> TemplateMatchSQIEstimate {
        guard configurationIsValid(configuration) else {
            return unavailable(
                .invalidConfiguration,
                configuration: configuration
            )
        }
        guard values.allSatisfy(\.isFinite) else {
            return unavailable(
                .nonFiniteInput,
                configuration: configuration
            )
        }
        guard values.count >= Int(
            configuration.sampleRateHz
                * configuration.minimumWindowSeconds
        ) else {
            return unavailable(
                .signalTooShort,
                configuration: configuration
            )
        }

        let signalMean = mean(values)
        let standardDeviation = populationStandardDeviation(
            values,
            mean: signalMean
        )
        guard standardDeviation
            >= configuration.standardDeviationEpsilon else {
            return unavailable(
                .constantSignal,
                configuration: configuration
            )
        }

        let peakTrace = detectPeaks(
            values: values,
            mean: signalMean,
            standardDeviation: standardDeviation,
            configuration: configuration
        )
        let selectedPeaks = peakTrace.selectedPeakIndices
        let heartRate = estimatedHeartRate(
            peaks: selectedPeaks,
            sampleRateHz: configuration.sampleRateHz
        )
        let cycleResult = buildCycleTrace(
            values: values,
            peaks: selectedPeaks,
            configuration: configuration
        )
        let debugTrace = TemplateMatchSQIDebugTrace(
            peakDetection: peakTrace,
            cycles: cycleResult.trace
        )
        if let error = cycleResult.trace.error {
            return unavailable(
                .templateFailed(error),
                configuration: configuration,
                peakCount: selectedPeaks.count,
                estimatedHeartRateBPM: heartRate,
                trace: debugTrace
            )
        }
        guard !cycleResult.trace.cycleQuality.isEmpty else {
            return unavailable(
                .noCycleQuality,
                configuration: configuration,
                peakCount: selectedPeaks.count,
                estimatedHeartRateBPM: heartRate,
                trace: debugTrace
            )
        }

        let rawQuality = mean(cycleResult.trace.cycleQuality)
        let displaySQI = min(1, max(0, rawQuality))
        return TemplateMatchSQIEstimate(
            sqi: displaySQI,
            rawMeanQuality: rawQuality,
            isValid: true,
            unavailableReason: nil,
            grade: grade(
                rawQuality,
                configuration: configuration
            ),
            cycleCount: cycleResult.trace.cycleQuality.count,
            peakCount: selectedPeaks.count,
            estimatedHeartRateBPM: heartRate,
            algorithmVersion: configuration.algorithmVersion,
            preprocessProfile: configuration.preprocessProfile,
            trace: debugTrace
        )
    }

    private static func configurationIsValid(
        _ configuration: TemplateMatchSQIConfiguration
    ) -> Bool {
        configuration.sampleRateHz.isFinite
            && configuration.sampleRateHz > 0
            && configuration.ratioPre.isFinite
            && (0...1).contains(configuration.ratioPre)
            && configuration.maximumHeartRateBPM.isFinite
            && configuration.maximumHeartRateBPM > 0
            && configuration.minimumWindowSeconds.isFinite
            && configuration.minimumWindowSeconds > 0
            && configuration.standardDeviationEpsilon.isFinite
            && configuration.standardDeviationEpsilon >= 0
            && configuration.primaryHeightStandardDeviationFactor
                .isFinite
            && configuration.primaryProminenceStandardDeviationFactor
                .isFinite
            && configuration.primaryMinimumWidthSamples.isFinite
            && configuration.primaryMinimumWidthSamples >= 0
            && configuration.fallbackHeightStandardDeviationFactor
                .isFinite
            && configuration.fallbackProminenceStandardDeviationFactor
                .isFinite
            && configuration.goodThreshold.isFinite
            && configuration.fairThreshold.isFinite
            && configuration.goodThreshold
                >= configuration.fairThreshold
    }

    private static func detectPeaks(
        values: [Double],
        mean: Double,
        standardDeviation: Double,
        configuration: TemplateMatchSQIConfiguration
    ) -> SQIPeakDetectionTrace {
        let minimumDistance = max(
            1,
            Int(
                configuration.sampleRateHz * 60
                    / configuration.maximumHeartRateBPM
            )
        )
        let primaryHeight =
            mean
            + configuration.primaryHeightStandardDeviationFactor
                * standardDeviation
        let primaryProminence =
            configuration.primaryProminenceStandardDeviationFactor
            * standardDeviation
        let primary = SciPyPeakDetector.findPeaks(
            values: values,
            distance: minimumDistance,
            minimumHeight: primaryHeight,
            minimumProminence: primaryProminence,
            minimumWidth: configuration.primaryMinimumWidthSamples
        )
        let primaryTrace = SQIPeakPassTrace(
            minimumHeight: primaryHeight,
            minimumProminence: primaryProminence,
            minimumWidthSamples:
                configuration.primaryMinimumWidthSamples,
            peaks: primary.peaks
        )

        let fallbackHeight =
            mean
            + configuration.fallbackHeightStandardDeviationFactor
                * standardDeviation
        let fallbackProminence =
            configuration.fallbackProminenceStandardDeviationFactor
            * standardDeviation
        if primary.peaks.count >= 2 {
            return SQIPeakDetectionTrace(
                mean: mean,
                standardDeviation: standardDeviation,
                minimumDistanceSamples: minimumDistance,
                primary: primaryTrace,
                fallbackWasEvaluated: false,
                fallback: SQIPeakPassTrace(
                    minimumHeight: fallbackHeight,
                    minimumProminence: fallbackProminence,
                    minimumWidthSamples: nil,
                    peaks: []
                ),
                selectedMode: .primary,
                selectedPeakIndices: primary.indices
            )
        }

        let fallback = SciPyPeakDetector.findPeaks(
            values: values,
            distance: minimumDistance,
            minimumHeight: fallbackHeight,
            minimumProminence: fallbackProminence
        )
        return SQIPeakDetectionTrace(
            mean: mean,
            standardDeviation: standardDeviation,
            minimumDistanceSamples: minimumDistance,
            primary: primaryTrace,
            fallbackWasEvaluated: true,
            fallback: SQIPeakPassTrace(
                minimumHeight: fallbackHeight,
                minimumProminence: fallbackProminence,
                minimumWidthSamples: nil,
                peaks: fallback.peaks
            ),
            selectedMode: .fallback,
            selectedPeakIndices: fallback.indices
        )
    }

    private static func estimatedHeartRate(
        peaks: [Int],
        sampleRateHz: Double
    ) -> Double? {
        let positiveDifferences = zip(peaks, peaks.dropFirst())
            .map { $1 - $0 }
            .filter { $0 > 0 }
        guard !positiveDifferences.isEmpty else {
            return nil
        }
        return 60 * sampleRateHz / median(
            positiveDifferences.map(Double.init)
        )
    }

    private static func buildCycleTrace(
        values: [Double],
        peaks: [Int],
        configuration: TemplateMatchSQIConfiguration
    ) -> (trace: SQICycleTrace, valid: Bool) {
        let sortedPeaks = Array(Set(peaks)).sorted()
        guard sortedPeaks.count >= 2 else {
            return (
                emptyCycleTrace(
                    error: "at least two cycle indices required"
                ),
                false
            )
        }
        let differences = zip(sortedPeaks, sortedPeaks.dropFirst())
            .map { $1 - $0 }
            .filter { $0 > 0 }
        guard !differences.isEmpty else {
            return (
                emptyCycleTrace(
                    error: "cycle indices must be increasing"
                ),
                false
            )
        }

        let meanDifference =
            differences.map(Double.init).reduce(0, +)
            / Double(differences.count)
        let rateBPM =
            60 * configuration.sampleRateHz / meanDifference
        let preSamples = Int(
            (
                configuration.ratioPre * meanDifference
            ).rounded(.toNearestOrEven)
        )
        let postSamples = Int(
            (
                (1 - configuration.ratioPre) * meanDifference
            ).rounded(.toNearestOrEven)
        )
        let windowLength = preSamples + postSamples + 1
        let timeAxis = (0..<windowLength).map {
            Double($0 - preSamples) / configuration.sampleRateHz
        }

        var validMask: [Bool] = []
        var droppedPeaks: [Int] = []
        var validPeaks: [Int] = []
        var cycles: [[Double]] = []
        for peak in sortedPeaks {
            let start = peak - preSamples
            let end = peak + postSamples + 1
            let isComplete = start >= 0 && end <= values.count
            validMask.append(isComplete)
            if isComplete {
                validPeaks.append(peak)
                cycles.append(Array(values[start..<end]))
            } else {
                droppedPeaks.append(peak)
            }
        }
        guard !cycles.isEmpty else {
            return (
                emptyCycleTrace(
                    error: "no complete cycle after NaN filtering"
                ),
                false
            )
        }

        let template = (0..<windowLength).map { index in
            cycles.reduce(into: 0.0) {
                $0 += $1[index]
            } / Double(cycles.count)
        }
        guard validPeaks.count >= 2 else {
            return (
                SQICycleTrace(
                    error:
                        "need >= 2 valid cycles to build quality trace",
                    rateBPM: rateBPM,
                    preSamples: preSamples,
                    postSamples: postSamples,
                    windowLength: windowLength,
                    timeAxisSeconds: timeAxis,
                    cycleValidMask: validMask,
                    droppedPeakIndices: droppedPeaks,
                    validPeakIndices: validPeaks,
                    cycles: cycles,
                    template: template,
                    qualityAnchorPeakIndices: [],
                    cycleQuality: [],
                    qualityTrace: []
                ),
                false
            )
        }

        let cycleQuality = cycles.dropLast().map {
            pearson($0, template)
        }
        guard !cycleQuality.isEmpty else {
            return (
                SQICycleTrace(
                    error: nil,
                    rateBPM: rateBPM,
                    preSamples: preSamples,
                    postSamples: postSamples,
                    windowLength: windowLength,
                    timeAxisSeconds: timeAxis,
                    cycleValidMask: validMask,
                    droppedPeakIndices: droppedPeaks,
                    validPeakIndices: validPeaks,
                    cycles: cycles,
                    template: template,
                    qualityAnchorPeakIndices: [],
                    cycleQuality: [],
                    qualityTrace: []
                ),
                false
            )
        }

        let anchors = Array(validPeaks.dropLast())
        var qualityTrace = Array(
            repeating: cycleQuality[0],
            count: values.count
        )
        if anchors.count > 1 {
            for index in 0..<(anchors.count - 1) {
                for sampleIndex in anchors[index]..<anchors[index + 1] {
                    qualityTrace[sampleIndex] = cycleQuality[index]
                }
            }
        }
        for sampleIndex in anchors[anchors.count - 1]..<values.count {
            qualityTrace[sampleIndex] = cycleQuality[cycleQuality.count - 1]
        }
        return (
            SQICycleTrace(
                error: nil,
                rateBPM: rateBPM,
                preSamples: preSamples,
                postSamples: postSamples,
                windowLength: windowLength,
                timeAxisSeconds: timeAxis,
                cycleValidMask: validMask,
                droppedPeakIndices: droppedPeaks,
                validPeakIndices: validPeaks,
                cycles: cycles,
                template: template,
                qualityAnchorPeakIndices: anchors,
                cycleQuality: cycleQuality,
                qualityTrace: qualityTrace
            ),
            true
        )
    }

    private static func pearson(
        _ left: [Double],
        _ right: [Double]
    ) -> Double {
        precondition(left.count == right.count)
        let leftMean = mean(left)
        let rightMean = mean(right)
        var numerator = 0.0
        var leftSquares = 0.0
        var rightSquares = 0.0
        for index in left.indices {
            let centeredLeft = left[index] - leftMean
            let centeredRight = right[index] - rightMean
            numerator += centeredLeft * centeredRight
            leftSquares += centeredLeft * centeredLeft
            rightSquares += centeredRight * centeredRight
        }
        let denominator = sqrt(leftSquares * rightSquares)
        return denominator > 0 ? numerator / denominator : .nan
    }

    private static func grade(
        _ rawQuality: Double,
        configuration: TemplateMatchSQIConfiguration
    ) -> TemplateMatchSQIGrade {
        if rawQuality >= configuration.goodThreshold {
            return .good
        }
        if rawQuality >= configuration.fairThreshold {
            return .fair
        }
        return .poor
    }

    private static func mean(_ values: [Double]) -> Double {
        values.reduce(0, +) / Double(values.count)
    }

    private static func populationStandardDeviation(
        _ values: [Double],
        mean: Double
    ) -> Double {
        sqrt(
            values.reduce(into: 0.0) {
                let centered = $1 - mean
                $0 += centered * centered
            } / Double(values.count)
        )
    }

    private static func median(_ values: [Double]) -> Double {
        let sorted = values.sorted()
        let middle = sorted.count / 2
        if sorted.count.isMultiple(of: 2) {
            return (sorted[middle - 1] + sorted[middle]) / 2
        }
        return sorted[middle]
    }

    private static func emptyCycleTrace(
        error: String? = nil
    ) -> SQICycleTrace {
        SQICycleTrace(
            error: error,
            rateBPM: nil,
            preSamples: nil,
            postSamples: nil,
            windowLength: nil,
            timeAxisSeconds: [],
            cycleValidMask: [],
            droppedPeakIndices: [],
            validPeakIndices: [],
            cycles: [],
            template: [],
            qualityAnchorPeakIndices: [],
            cycleQuality: [],
            qualityTrace: []
        )
    }

    private static func unavailable(
        _ reason: TemplateMatchSQIUnavailableReason,
        configuration: TemplateMatchSQIConfiguration,
        peakCount: Int = 0,
        estimatedHeartRateBPM: Double? = nil,
        trace: TemplateMatchSQIDebugTrace? = nil
    ) -> TemplateMatchSQIEstimate {
        TemplateMatchSQIEstimate(
            sqi: nil,
            rawMeanQuality: nil,
            isValid: false,
            unavailableReason: reason,
            grade: .unavailable,
            cycleCount: 0,
            peakCount: peakCount,
            estimatedHeartRateBPM: estimatedHeartRateBPM,
            algorithmVersion: configuration.algorithmVersion,
            preprocessProfile: configuration.preprocessProfile,
            trace: trace ?? TemplateMatchSQIDebugTrace(
                peakDetection: nil,
                cycles: emptyCycleTrace()
            )
        )
    }
}
