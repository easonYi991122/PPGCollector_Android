import Foundation

nonisolated enum HeartRatePolarity:
    String,
    Codable,
    Equatable,
    Sendable
{
    case positive
    case negative
}

nonisolated enum HeartRateUnavailableReason:
    String,
    Codable,
    Equatable,
    Sendable
{
    case invalidConfiguration
    case inputLengthMismatch
    case insufficientSamples
    case insufficientWorkWindow
    case nonFiniteInput
    case insufficientAmplitude
    case noPeakCandidate
    case lowConfidence
}

nonisolated enum HeartRateCandidateRejectionReason:
    String,
    Codable,
    Equatable,
    Sendable
{
    case insufficientPeaks
    case insufficientIntervals
    case insufficientLongestRun
}

nonisolated struct HeartRateConfiguration: Equatable, Sendable {
    let algorithmVersion: String
    let minBPM: Double
    let maxBPM: Double
    let minimumWindowSeconds: Double
    let maximumWindowSeconds: Double
    let minimumRobustScale: Double
    let confidenceThreshold: Double
    let welchNearPeakHz: Double

    static let pythonBaseline01 = HeartRateConfiguration(
        algorithmVersion: "ppg-ios-hr-0.1",
        minBPM: 35,
        maxBPM: 200,
        minimumWindowSeconds: 4,
        maximumWindowSeconds: 8,
        minimumRobustScale: 1,
        confidenceThreshold: 0.35,
        welchNearPeakHz: 0.15
    )
}

nonisolated struct HeartRateSpectralTrace: Equatable, Sendable {
    let segmentLength: Int
    let frequenciesHz: [Double]
    let power: [Double]
    let cardiacIndices: [Int]
    let peakIndex: Int?
    let nearPeakIndices: [Int]
    let spectralBPM: Double?
    let snrDB: Double
    let concentration: Double
}

nonisolated struct HeartRateCandidateTrace: Equatable, Sendable {
    let polarity: HeartRatePolarity
    let distanceSamples: Int
    let detectedLocalPeakIndices: [Int]
    let detectedGlobalPeakIndices: [Int]
    let prominences: [Double]
    let intervalsSeconds: [Double]
    let inRangeMask: [Bool]
    let spectralMatchMask: [Bool]
    let spectralFilterApplied: Bool
    let validMask: [Bool]
    let longestRunLocalPeakIndices: [Int]
    let longestRunGlobalPeakIndices: [Int]
    let cleanedIntervalsSeconds: [Double]
    let medianRRSeconds: Double?
    let rrMADSeconds: Double?
    let peakBPM: Double?
    let score: Double?
    let rejectionReason: HeartRateCandidateRejectionReason?
}

nonisolated struct HeartRateDebugTrace: Equatable, Sendable {
    let windowOffset: Int
    let edgeTrimCount: Int
    let workMedian: Double?
    let robustScale: Double?
    let workCentered: [Double]
    let spectral: HeartRateSpectralTrace?
    let candidates: [HeartRateCandidateTrace]
}

nonisolated struct HeartRateEstimate: Equatable, Sendable {
    let bpm: Double?
    let peakBPM: Double?
    let spectralBPM: Double?
    let confidence: Double
    let snrDB: Double
    let polarity: HeartRatePolarity?
    let peakIndices: [Int]
    let rrMADSeconds: Double?
    let unavailableReason: HeartRateUnavailableReason?
    let algorithmVersion: String
    let trace: HeartRateDebugTrace

    var isValid: Bool {
        bpm != nil && unavailableReason == nil
    }
}

nonisolated enum HeartRateEstimator {
    static func estimate(
        values inputValues: [Double],
        timeSeconds inputTimeSeconds: [Double],
        sampleRateHz: Double,
        configuration: HeartRateConfiguration = .pythonBaseline01
    ) -> HeartRateEstimate {
        guard configurationIsValid(
            configuration,
            sampleRateHz: sampleRateHz
        ) else {
            return empty(
                reason: .invalidConfiguration,
                configuration: configuration
            )
        }
        guard inputValues.count == inputTimeSeconds.count else {
            return empty(
                reason: .inputLengthMismatch,
                configuration: configuration
            )
        }
        guard inputValues.allSatisfy(\.isFinite),
              inputTimeSeconds.allSatisfy(\.isFinite) else {
            return empty(
                reason: .nonFiniteInput,
                configuration: configuration
            )
        }

        let minimumCount = max(
            32,
            Int(sampleRateHz * configuration.minimumWindowSeconds)
        )
        guard inputValues.count >= minimumCount else {
            return empty(
                reason: .insufficientSamples,
                configuration: configuration
            )
        }

        let maximumCount = max(
            minimumCount,
            Int(sampleRateHz * configuration.maximumWindowSeconds)
        )
        let windowOffset = max(0, inputValues.count - maximumCount)
        let values = Array(inputValues.suffix(maximumCount))
        let timeSeconds = Array(inputTimeSeconds.suffix(maximumCount))
        let edge = min(
            Int(sampleRateHz),
            max(0, values.count / 10)
        )
        let stop = values.count - edge
        guard stop - edge >= 16 else {
            return empty(
                reason: .insufficientWorkWindow,
                configuration: configuration,
                windowOffset: windowOffset,
                edgeTrimCount: edge
            )
        }

        let uncenteredWork = Array(values[edge..<stop])
        let workTime = Array(timeSeconds[edge..<stop])
        let workMedian = median(uncenteredWork)
        let work = uncenteredWork.map { $0 - workMedian }
        let robustScale = median(work.map { abs($0) }) * 1.4826
        let baseTrace = HeartRateDebugTrace(
            windowOffset: windowOffset,
            edgeTrimCount: edge,
            workMedian: workMedian,
            robustScale: robustScale,
            workCentered: work,
            spectral: nil,
            candidates: []
        )
        guard robustScale.isFinite,
              robustScale >= configuration.minimumRobustScale else {
            return empty(
                reason: .insufficientAmplitude,
                configuration: configuration,
                trace: baseTrace
            )
        }

        let spectral = spectralTrace(
            values: work,
            sampleRateHz: sampleRateHz,
            configuration: configuration
        )
        let positive = candidateTrace(
            values: work,
            timeSeconds: workTime,
            polarity: .positive,
            sign: 1,
            edge: edge,
            windowOffset: windowOffset,
            robustScale: robustScale,
            sampleRateHz: sampleRateHz,
            spectral: spectral,
            configuration: configuration
        )
        let negative = candidateTrace(
            values: work,
            timeSeconds: workTime,
            polarity: .negative,
            sign: -1,
            edge: edge,
            windowOffset: windowOffset,
            robustScale: robustScale,
            sampleRateHz: sampleRateHz,
            spectral: spectral,
            configuration: configuration
        )
        let candidates = [positive, negative]
        let trace = HeartRateDebugTrace(
            windowOffset: windowOffset,
            edgeTrimCount: edge,
            workMedian: workMedian,
            robustScale: robustScale,
            workCentered: work,
            spectral: spectral,
            candidates: candidates
        )

        var selected: HeartRateCandidateTrace?
        for candidate in candidates where candidate.score != nil {
            guard let current = selected else {
                selected = candidate
                continue
            }
            if (candidate.score ?? -.infinity)
                > (current.score ?? -.infinity) {
                selected = candidate
            }
        }
        guard let selected else {
            return HeartRateEstimate(
                bpm: nil,
                peakBPM: nil,
                spectralBPM: spectral.spectralBPM,
                confidence: 0,
                snrDB: spectral.snrDB,
                polarity: nil,
                peakIndices: [],
                rrMADSeconds: nil,
                unavailableReason: .noPeakCandidate,
                algorithmVersion: configuration.algorithmVersion,
                trace: trace
            )
        }

        let confidence = min(1, selected.score ?? 0)
        let isConfident =
            confidence >= configuration.confidenceThreshold
        return HeartRateEstimate(
            bpm: isConfident ? selected.peakBPM : nil,
            peakBPM: selected.peakBPM,
            spectralBPM: spectral.spectralBPM,
            confidence: confidence,
            snrDB: spectral.snrDB,
            polarity: selected.polarity,
            peakIndices: selected.longestRunGlobalPeakIndices,
            rrMADSeconds: selected.rrMADSeconds,
            unavailableReason: isConfident ? nil : .lowConfidence,
            algorithmVersion: configuration.algorithmVersion,
            trace: trace
        )
    }

    /// Returns the live-display value. The Python GUI uses the peak estimate
    /// only after its confidence gate; the live path additionally accepts a
    /// low-confidence candidate when the peak and Welch candidates agree.
    static func acceptedBPM(
        _ estimate: HeartRateEstimate,
        configuration: HeartRateConfiguration = .pythonBaseline01
    ) -> Double? {
        if let bpm = estimate.bpm, estimate.isValid {
            return bpm
        }

        guard let peak = estimate.peakBPM,
              let spectral = estimate.spectralBPM,
              peak.isFinite,
              spectral.isFinite,
              peak >= configuration.minBPM,
              peak <= configuration.maxBPM,
              abs(peak - spectral) <= 8,
              estimate.confidence >= 0.25 else {
            return nil
        }
        return peak
    }

    private static func configurationIsValid(
        _ configuration: HeartRateConfiguration,
        sampleRateHz: Double
    ) -> Bool {
        sampleRateHz.isFinite
            && sampleRateHz > 0
            && configuration.minBPM.isFinite
            && configuration.maxBPM.isFinite
            && configuration.minBPM > 0
            && configuration.maxBPM > configuration.minBPM
            && configuration.minimumWindowSeconds.isFinite
            && configuration.maximumWindowSeconds.isFinite
            && configuration.minimumWindowSeconds > 0
            && configuration.maximumWindowSeconds
                >= configuration.minimumWindowSeconds
            && configuration.minimumRobustScale.isFinite
            && configuration.minimumRobustScale >= 0
            && configuration.confidenceThreshold.isFinite
            && (0...1).contains(configuration.confidenceThreshold)
            && configuration.welchNearPeakHz.isFinite
            && configuration.welchNearPeakHz >= 0
    }

    private static func spectralTrace(
        values: [Double],
        sampleRateHz: Double,
        configuration: HeartRateConfiguration
    ) -> HeartRateSpectralTrace {
        let segmentLength = min(
            values.count,
            max(
                64,
                Int(
                    sampleRateHz
                        * configuration.maximumWindowSeconds
                )
            )
        )
        let segment = Array(values.prefix(segmentLength))
        let detrended = linearDetrend(segment)
        let window = (0..<segmentLength).map { index in
            0.5 - 0.5 * cos(
                2 * Double.pi * Double(index)
                    / Double(segmentLength)
            )
        }
        let scaleDenominator =
            sampleRateHz * window.reduce(into: 0.0) { sum, value in
                sum += value * value
            }
        let lastBin = segmentLength / 2
        var frequencies: [Double] = []
        var power: [Double] = []
        frequencies.reserveCapacity(lastBin + 1)
        power.reserveCapacity(lastBin + 1)

        for bin in 0...lastBin {
            var real = 0.0
            var imaginary = 0.0
            for index in 0..<segmentLength {
                let angle =
                    2 * Double.pi * Double(bin * index)
                    / Double(segmentLength)
                let windowed = detrended[index] * window[index]
                real += windowed * cos(angle)
                imaginary -= windowed * sin(angle)
            }
            var density =
                (real * real + imaginary * imaginary)
                / scaleDenominator
            let isNyquist =
                segmentLength.isMultiple(of: 2)
                && bin == lastBin
            if bin != 0 && !isNyquist {
                density *= 2
            }
            frequencies.append(
                Double(bin) * sampleRateHz / Double(segmentLength)
            )
            power.append(density)
        }

        let cardiacIndices = frequencies.indices.filter { index in
            frequencies[index] >= configuration.minBPM / 60
                && frequencies[index] <= configuration.maxBPM / 60
        }
        let totalPower = cardiacIndices.reduce(into: 0.0) {
            $0 += power[$1]
        }
        guard !cardiacIndices.isEmpty, totalPower > 0 else {
            return HeartRateSpectralTrace(
                segmentLength: segmentLength,
                frequenciesHz: frequencies,
                power: power,
                cardiacIndices: cardiacIndices,
                peakIndex: nil,
                nearPeakIndices: [],
                spectralBPM: nil,
                snrDB: -.infinity,
                concentration: 0
            )
        }

        var peakIndex = cardiacIndices[0]
        for index in cardiacIndices.dropFirst()
        where power[index] > power[peakIndex] {
            peakIndex = index
        }
        let peakFrequency = frequencies[peakIndex]
        let nearPeakIndices = cardiacIndices.filter {
            abs(frequencies[$0] - peakFrequency)
                <= configuration.welchNearPeakHz
        }
        let signalPower = nearPeakIndices.reduce(into: 0.0) {
            $0 += power[$1]
        }
        let noisePower = max(totalPower - signalPower, 1e-12)
        let concentration =
            signalPower / max(signalPower + noisePower, 1e-12)
        let snrDB =
            10 * log10(max(signalPower, 1e-12) / noisePower)
        return HeartRateSpectralTrace(
            segmentLength: segmentLength,
            frequenciesHz: frequencies,
            power: power,
            cardiacIndices: cardiacIndices,
            peakIndex: peakIndex,
            nearPeakIndices: nearPeakIndices,
            spectralBPM: peakFrequency * 60,
            snrDB: snrDB,
            concentration: concentration
        )
    }

    private static func candidateTrace(
        values: [Double],
        timeSeconds: [Double],
        polarity: HeartRatePolarity,
        sign: Double,
        edge: Int,
        windowOffset: Int,
        robustScale: Double,
        sampleRateHz: Double,
        spectral: HeartRateSpectralTrace,
        configuration: HeartRateConfiguration
    ) -> HeartRateCandidateTrace {
        let minIntervalSeconds = 60 / configuration.maxBPM
        let maxIntervalSeconds = 60 / configuration.minBPM
        var guidedIntervalSeconds = minIntervalSeconds
        if let spectralBPM = spectral.spectralBPM {
            guidedIntervalSeconds = max(
                minIntervalSeconds,
                min(0.5, 0.55 * 60 / spectralBPM)
            )
        }
        let distanceSamples = max(
            1,
            Int(sampleRateHz * guidedIntervalSeconds)
        )
        let transformed = values.map { sign * $0 }
        let detected = SciPyPeakDetector.findPeaks(
            values: transformed,
            distance: distanceSamples,
            minimumProminence: robustScale
        )
        let peaks = detected.indices
        let intervals = zip(peaks, peaks.dropFirst()).map {
            timeSeconds[$1] - timeSeconds[$0]
        }
        var inRangeMask = intervals.map {
            $0 >= minIntervalSeconds && $0 <= maxIntervalSeconds
        }
        var spectralMatchMask = Array(
            repeating: true,
            count: intervals.count
        )
        var spectralFilterApplied = false
        if let spectralBPM = spectral.spectralBPM {
            let spectralRR = 60 / spectralBPM
            let tolerance = max(0.12, 0.22 * spectralRR)
            spectralMatchMask = intervals.map {
                abs($0 - spectralRR) <= tolerance
            }
            let matches = inRangeMask.indices.reduce(into: 0) {
                if inRangeMask[$1] && spectralMatchMask[$1] {
                    $0 += 1
                }
            }
            if matches >= 2 {
                for index in inRangeMask.indices {
                    inRangeMask[index] =
                        inRangeMask[index]
                        && spectralMatchMask[index]
                }
                spectralFilterApplied = true
            }
        }

        let globalOffset = edge + windowOffset
        let detectedGlobal = peaks.map { $0 + globalOffset }
        let emptyValidMask = Array(
            repeating: false,
            count: intervals.count
        )
        guard peaks.count >= 3 else {
            return HeartRateCandidateTrace(
                polarity: polarity,
                distanceSamples: distanceSamples,
                detectedLocalPeakIndices: peaks,
                detectedGlobalPeakIndices: detectedGlobal,
                prominences: detected.peaks.map(\.prominence),
                intervalsSeconds: intervals,
                inRangeMask: inRangeMask,
                spectralMatchMask: spectralMatchMask,
                spectralFilterApplied: spectralFilterApplied,
                validMask: emptyValidMask,
                longestRunLocalPeakIndices: [],
                longestRunGlobalPeakIndices: [],
                cleanedIntervalsSeconds: [],
                medianRRSeconds: nil,
                rrMADSeconds: nil,
                peakBPM: nil,
                score: nil,
                rejectionReason: .insufficientPeaks
            )
        }

        let validIntervals = intervals.indices.compactMap {
            inRangeMask[$0] ? intervals[$0] : nil
        }
        guard validIntervals.count >= 2 else {
            return HeartRateCandidateTrace(
                polarity: polarity,
                distanceSamples: distanceSamples,
                detectedLocalPeakIndices: peaks,
                detectedGlobalPeakIndices: detectedGlobal,
                prominences: detected.peaks.map(\.prominence),
                intervalsSeconds: intervals,
                inRangeMask: inRangeMask,
                spectralMatchMask: spectralMatchMask,
                spectralFilterApplied: spectralFilterApplied,
                validMask: emptyValidMask,
                longestRunLocalPeakIndices: [],
                longestRunGlobalPeakIndices: [],
                cleanedIntervalsSeconds: [],
                medianRRSeconds: nil,
                rrMADSeconds: nil,
                peakBPM: nil,
                score: nil,
                rejectionReason: .insufficientIntervals
            )
        }

        let initialMedian = median(validIntervals)
        let initialMAD = median(
            validIntervals.map { abs($0 - initialMedian) }
        )
        let madFloor = max(1 / sampleRateHz, initialMAD)
        let validMask = intervals.indices.map {
            inRangeMask[$0]
                && abs(intervals[$0] - initialMedian)
                    <= 3 * madFloor
        }
        let run = longestValidRun(
            peaks: peaks,
            intervals: intervals,
            validMask: validMask
        )
        let runGlobal = run.peaks.map { $0 + globalOffset }
        guard run.intervals.count >= 2 else {
            return HeartRateCandidateTrace(
                polarity: polarity,
                distanceSamples: distanceSamples,
                detectedLocalPeakIndices: peaks,
                detectedGlobalPeakIndices: detectedGlobal,
                prominences: detected.peaks.map(\.prominence),
                intervalsSeconds: intervals,
                inRangeMask: inRangeMask,
                spectralMatchMask: spectralMatchMask,
                spectralFilterApplied: spectralFilterApplied,
                validMask: validMask,
                longestRunLocalPeakIndices: run.peaks,
                longestRunGlobalPeakIndices: runGlobal,
                cleanedIntervalsSeconds: run.intervals,
                medianRRSeconds: nil,
                rrMADSeconds: nil,
                peakBPM: nil,
                score: nil,
                rejectionReason: .insufficientLongestRun
            )
        }

        let medianRR = median(run.intervals)
        let rrMAD = median(run.intervals.map { abs($0 - medianRR) })
        let peakBPM = 60 / medianRR
        let regularity =
            1 / (1 + 8 * rrMAD / max(medianRR, 1e-12))
        let coverage = min(1, Double(run.intervals.count) / 6)
        let agreement: Double
        if let spectralBPM = spectral.spectralBPM {
            agreement = max(
                0,
                1 - abs(peakBPM - spectralBPM) / 15
            )
        } else {
            agreement = 0
        }
        let score =
            coverage
            * regularity
            * agreement
            * (0.5 + 0.5 * spectral.concentration)
        return HeartRateCandidateTrace(
            polarity: polarity,
            distanceSamples: distanceSamples,
            detectedLocalPeakIndices: peaks,
            detectedGlobalPeakIndices: detectedGlobal,
            prominences: detected.peaks.map(\.prominence),
            intervalsSeconds: intervals,
            inRangeMask: inRangeMask,
            spectralMatchMask: spectralMatchMask,
            spectralFilterApplied: spectralFilterApplied,
            validMask: validMask,
            longestRunLocalPeakIndices: run.peaks,
            longestRunGlobalPeakIndices: runGlobal,
            cleanedIntervalsSeconds: run.intervals,
            medianRRSeconds: medianRR,
            rrMADSeconds: rrMAD,
            peakBPM: peakBPM,
            score: score,
            rejectionReason: nil
        )
    }

    private static func linearDetrend(_ values: [Double]) -> [Double] {
        guard values.count > 1 else {
            return values.map { _ in 0 }
        }
        let count = Double(values.count)
        let meanX = (count + 1) / (2 * count)
        let meanY = values.reduce(0, +) / count
        var covariance = 0.0
        var varianceX = 0.0
        for (index, value) in values.enumerated() {
            let x = Double(index + 1) / count
            covariance += (x - meanX) * (value - meanY)
            varianceX += (x - meanX) * (x - meanX)
        }
        let slope = covariance / varianceX
        let intercept = meanY - slope * meanX
        return values.enumerated().map { index, value in
            let x = Double(index + 1) / count
            return value - (slope * x + intercept)
        }
    }

    private static func longestValidRun(
        peaks: [Int],
        intervals: [Double],
        validMask: [Bool]
    ) -> (peaks: [Int], intervals: [Double]) {
        var bestStart = 0
        var bestLength = 0
        var currentStart = 0
        var currentLength = 0
        for (index, isValid) in validMask.enumerated() {
            if isValid {
                if currentLength == 0 {
                    currentStart = index
                }
                currentLength += 1
                if currentLength > bestLength {
                    bestStart = currentStart
                    bestLength = currentLength
                }
            } else {
                currentLength = 0
            }
        }
        guard bestLength > 0 else {
            return ([], [])
        }
        return (
            Array(peaks[bestStart...(bestStart + bestLength)]),
            Array(intervals[bestStart..<(bestStart + bestLength)])
        )
    }

    private static func median(_ values: [Double]) -> Double {
        precondition(!values.isEmpty)
        let sorted = values.sorted()
        let middle = sorted.count / 2
        if sorted.count.isMultiple(of: 2) {
            return (sorted[middle - 1] + sorted[middle]) / 2
        }
        return sorted[middle]
    }

    private static func empty(
        reason: HeartRateUnavailableReason,
        configuration: HeartRateConfiguration,
        windowOffset: Int = 0,
        edgeTrimCount: Int = 0,
        trace: HeartRateDebugTrace? = nil
    ) -> HeartRateEstimate {
        HeartRateEstimate(
            bpm: nil,
            peakBPM: nil,
            spectralBPM: nil,
            confidence: 0,
            snrDB: -.infinity,
            polarity: nil,
            peakIndices: [],
            rrMADSeconds: nil,
            unavailableReason: reason,
            algorithmVersion: configuration.algorithmVersion,
            trace: trace ?? HeartRateDebugTrace(
                windowOffset: windowOffset,
                edgeTrimCount: edgeTrimCount,
                workMedian: nil,
                robustScale: nil,
                workCentered: [],
                spectral: nil,
                candidates: []
            )
        )
    }
}
