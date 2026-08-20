import Foundation

nonisolated enum PPGPolarityTransform:
    String,
    Codable,
    Equatable,
    Sendable
{
    case preserve
    case invert

    func apply(_ value: Double) -> Double {
        switch self {
        case .preserve:
            value
        case .invert:
            -value
        }
    }
}

nonisolated enum PPGStreamBoundary: Equatable, Sendable {
    case continuous
    case gap
}

nonisolated enum PPGPreprocessingUnavailableReason:
    String,
    Codable,
    Equatable,
    Sendable
{
    case emptyWindow
    case nonFiniteInput
    case constantSignal
}

nonisolated struct PPGSecondOrderSection:
    Codable,
    Equatable,
    Sendable
{
    let b0: Double
    let b1: Double
    let b2: Double
    let a0: Double
    let a1: Double
    let a2: Double
}

nonisolated struct PPGPreprocessingProfile: Equatable, Sendable {
    let identifier: String
    let sampleRateHz: Double
    let dcTimeConstantSeconds: Double
    let dcAlpha: Double
    let lowCutoffHz: Double
    let highCutoffHz: Double
    let filterOrder: Int
    let sections: [PPGSecondOrderSection]
    let sqiPolarityTransform: PPGPolarityTransform
    let standardDeviationEpsilon: Double

    /// Fixed SciPy 1.17.1 coefficients for
    /// `butter(3, [0.6, 4], btype="bandpass", fs=100, output="sos")`.
    static let iosBaseline01 = PPGPreprocessingProfile(
        identifier: "ios_baseline_0.1",
        sampleRateHz: 100,
        dcTimeConstantSeconds: 0.5,
        dcAlpha: 0.019801326693244747,
        lowCutoffHz: 0.6,
        highCutoffHz: 4,
        filterOrder: 3,
        sections: [
            PPGSecondOrderSection(
                b0: 0.0009951735615644872,
                b1: 0.0019903471231289744,
                b2: 0.0009951735615644872,
                a0: 1,
                a1: -1.7806577292895467,
                a2: 0.8331525078192623
            ),
            PPGSecondOrderSection(
                b0: 1,
                b1: 0,
                b2: -1,
                a0: 1,
                a1: -1.7977389079873187,
                a2: 0.8063221045221778
            ),
            PPGSecondOrderSection(
                b0: 1,
                b1: -2,
                b2: 1,
                a0: 1,
                a1: -1.968647554073201,
                a2: 0.9701854631165024
            ),
        ],
        sqiPolarityTransform: .invert,
        standardDeviationEpsilon: 1e-8
    )
}

nonisolated struct PPGPreprocessedSample: Equatable, Sendable {
    let raw: Double
    let dc: Double
    let ac: Double
    let bandpassed: Double
    let didResetAtBoundary: Bool
    let profileIdentifier: String
}

nonisolated struct PPGSamplePreprocessingResult:
    Equatable,
    Sendable
{
    let sample: PPGPreprocessedSample?
    let unavailableReason: PPGPreprocessingUnavailableReason?

    var isValid: Bool {
        sample != nil && unavailableReason == nil
    }

    static func valid(_ sample: PPGPreprocessedSample) -> Self {
        Self(sample: sample, unavailableReason: nil)
    }

    static func unavailable(
        _ reason: PPGPreprocessingUnavailableReason
    ) -> Self {
        Self(sample: nil, unavailableReason: reason)
    }
}

nonisolated struct PPGNormalizedWindow: Equatable, Sendable {
    let polarityAdjustedValues: [Double]
    let normalizedValues: [Double]?
    let mean: Double?
    let standardDeviation: Double?
    let polarityTransform: PPGPolarityTransform
    let profileIdentifier: String
    let unavailableReason: PPGPreprocessingUnavailableReason?

    var isValid: Bool {
        normalizedValues != nil && unavailableReason == nil
    }
}

nonisolated enum PPGWindowNormalizer {
    static func normalize(
        _ values: [Double],
        profile: PPGPreprocessingProfile = .iosBaseline01,
        polarityTransform: PPGPolarityTransform? = nil
    ) -> PPGNormalizedWindow {
        let transform =
            polarityTransform ?? profile.sqiPolarityTransform
        guard !values.isEmpty else {
            return unavailable(
                .emptyWindow,
                transform: transform,
                profile: profile
            )
        }
        guard values.allSatisfy(\.isFinite) else {
            return unavailable(
                .nonFiniteInput,
                transform: transform,
                profile: profile
            )
        }

        let adjusted = values.map(transform.apply)
        let mean = adjusted.reduce(0, +) / Double(adjusted.count)
        let squaredError = adjusted.reduce(into: 0.0) { total, value in
            let centered = value - mean
            total += centered * centered
        }
        let standardDeviation = sqrt(
            squaredError / Double(adjusted.count)
        )
        guard standardDeviation.isFinite,
              standardDeviation
                >= profile.standardDeviationEpsilon else {
            return PPGNormalizedWindow(
                polarityAdjustedValues: adjusted,
                normalizedValues: nil,
                mean: mean,
                standardDeviation: standardDeviation,
                polarityTransform: transform,
                profileIdentifier: profile.identifier,
                unavailableReason: .constantSignal
            )
        }

        return PPGNormalizedWindow(
            polarityAdjustedValues: adjusted,
            normalizedValues: adjusted.map {
                ($0 - mean) / standardDeviation
            },
            mean: mean,
            standardDeviation: standardDeviation,
            polarityTransform: transform,
            profileIdentifier: profile.identifier,
            unavailableReason: nil
        )
    }

    private static func unavailable(
        _ reason: PPGPreprocessingUnavailableReason,
        transform: PPGPolarityTransform,
        profile: PPGPreprocessingProfile
    ) -> PPGNormalizedWindow {
        PPGNormalizedWindow(
            polarityAdjustedValues: [],
            normalizedValues: nil,
            mean: nil,
            standardDeviation: nil,
            polarityTransform: transform,
            profileIdentifier: profile.identifier,
            unavailableReason: reason
        )
    }
}

nonisolated struct PPGPreprocessor: Sendable {
    let profile: PPGPreprocessingProfile

    private var dc: Double?
    private var sectionStates: [PPGSecondOrderSectionState]

    init(profile: PPGPreprocessingProfile = .iosBaseline01) {
        self.profile = profile
        dc = nil
        sectionStates = profile.sections.map {
            PPGSecondOrderSectionState(coefficients: $0)
        }
    }

    mutating func process(
        raw: Double,
        boundary: PPGStreamBoundary = .continuous
    ) -> PPGSamplePreprocessingResult {
        let resetsAtBoundary = boundary == .gap
        if resetsAtBoundary {
            reset()
        }
        guard raw.isFinite else {
            reset()
            return .unavailable(.nonFiniteInput)
        }

        if let previousDC = dc {
            dc = previousDC + profile.dcAlpha * (raw - previousDC)
        } else {
            dc = raw
        }
        let dcValue = dc ?? raw
        let ac = raw - dcValue
        var filtered = ac
        for index in sectionStates.indices {
            filtered = sectionStates[index].process(filtered)
        }

        return .valid(
            PPGPreprocessedSample(
                raw: raw,
                dc: dcValue,
                ac: ac,
                bandpassed: filtered,
                didResetAtBoundary: resetsAtBoundary,
                profileIdentifier: profile.identifier
            )
        )
    }

    mutating func process(
        rawValues: [Double],
        boundaryBeforeFirst: PPGStreamBoundary = .continuous
    ) -> [PPGSamplePreprocessingResult] {
        rawValues.enumerated().map { index, raw in
            process(
                raw: raw,
                boundary: index == 0
                    ? boundaryBeforeFirst
                    : .continuous
            )
        }
    }

    mutating func reset() {
        dc = nil
        sectionStates = profile.sections.map {
            PPGSecondOrderSectionState(coefficients: $0)
        }
    }
}

private nonisolated struct PPGSecondOrderSectionState: Sendable {
    private let b0: Double
    private let b1: Double
    private let b2: Double
    private let a1: Double
    private let a2: Double
    private var delay1 = 0.0
    private var delay2 = 0.0

    init(coefficients: PPGSecondOrderSection) {
        let denominator = coefficients.a0
        precondition(
            denominator.isFinite && denominator != 0,
            "SOS a0 must be finite and non-zero"
        )
        b0 = coefficients.b0 / denominator
        b1 = coefficients.b1 / denominator
        b2 = coefficients.b2 / denominator
        a1 = coefficients.a1 / denominator
        a2 = coefficients.a2 / denominator
    }

    mutating func process(_ input: Double) -> Double {
        let output = b0 * input + delay1
        delay1 = b1 * input - a1 * output + delay2
        delay2 = b2 * input - a2 * output
        return output
    }
}
