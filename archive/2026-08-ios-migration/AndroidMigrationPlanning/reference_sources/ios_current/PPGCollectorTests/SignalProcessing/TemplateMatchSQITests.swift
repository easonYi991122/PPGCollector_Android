import Foundation
import Testing
@testable import PPGCollector

struct TemplateMatchSQITests {
    @Test
    func frozenConfigurationMatchesSuppliedPythonContract() throws {
        let fixture = try loadSQIFixture()
        let configuration = TemplateMatchSQIConfiguration.iosBaseline01

        #expect(fixture.schema == "cup.sqi.parity.v2")
        #expect(
            fixture.algorithmVersion
                == configuration.algorithmVersion
        )
        #expect(
            fixture.preprocessProfile
                == configuration.preprocessProfile
        )
        #expect(fixture.environment.python == "3.11.15")
        #expect(fixture.environment.numpy == "2.4.6")
        #expect(fixture.environment.scipy == "1.17.1")
        #expect(
            fixture.config.sampleRateHz
                == configuration.sampleRateHz
        )
        #expect(fixture.config.ratioPre == configuration.ratioPre)
        #expect(
            fixture.config.hrMaxBpm
                == configuration.maximumHeartRateBPM
        )
        #expect(
            fixture.config.minimumWindowSeconds
                == configuration.minimumWindowSeconds
        )
        #expect(
            fixture.config.standardDeviationEpsilon
                == configuration.standardDeviationEpsilon
        )
        #expect(
            fixture.config.primaryMinimumWidthSamples
                == configuration.primaryMinimumWidthSamples
        )
        #expect(
            fixture.config.goodThreshold
                == configuration.goodThreshold
        )
        #expect(
            fixture.config.fairThreshold
                == configuration.fairThreshold
        )
    }

    @Test
    func finalScoresGradesAndReasonsMatchPython() throws {
        for testCase in try loadSQIFixture().cases {
            let actual = TemplateMatchSQI.compute(
                preprocessedPeakUpValues: testCase.signal
            )
            let expected = testCase.expected

            #expect(actual.isValid == expected.valid)
            expectOptionalClose(
                actual.rawMeanQuality,
                expected.meanQuality,
                tolerance: 1e-10
            )
            expectOptionalClose(
                actual.sqi,
                expected.displaySqi,
                tolerance: 1e-10
            )
            #expect(actual.cycleCount == expected.nCycles)
            #expect(actual.peakCount == expected.nPeaks)
            expectOptionalClose(
                actual.estimatedHeartRateBPM,
                expected.hrEst,
                tolerance: 1e-10
            )
            #expect(actual.reasonCode == expected.reason)
            #expect(actual.grade.rawValue == expected.grade)
            #expect(actual.grade.colorHex == expected.gradeColorHex)
        }
    }

    @Test
    func primaryAndFallbackPeakPropertiesMatchSciPy() throws {
        for testCase in try loadSQIFixture().cases {
            let actual = TemplateMatchSQI.compute(
                preprocessedPeakUpValues: testCase.signal
            )
            guard let expected = testCase.trace.peakDetection else {
                #expect(actual.trace.peakDetection == nil)
                continue
            }
            let peakTrace = try #require(actual.trace.peakDetection)
            #expect(abs(peakTrace.mean - expected.mean) <= 1e-12)
            #expect(
                abs(
                    peakTrace.standardDeviation
                        - expected.standardDeviation
                ) <= 1e-12
            )
            #expect(
                peakTrace.minimumDistanceSamples
                    == expected.minimumDistanceSamples
            )
            expectPeakPass(
                peakTrace.primary,
                expected.primary
            )
            #expect(
                peakTrace.fallbackWasEvaluated
                    == expected.fallback.wasEvaluated
            )
            expectPeakPass(
                peakTrace.fallback,
                expected.fallback
            )
            #expect(
                peakTrace.selectedMode.rawValue
                    == expected.selectedMode
            )
            #expect(
                peakTrace.selectedPeakIndices
                    == expected.selectedPeakIndices
            )
        }
    }

    @Test
    func cyclesTemplatePearsonAndQualityTraceMatchPython() throws {
        for testCase in try loadSQIFixture().cases {
            let actual = TemplateMatchSQI.compute(
                preprocessedPeakUpValues: testCase.signal
            ).trace.cycles
            let expected = testCase.trace.cycles

            #expect(actual.error == expected.error)
            expectOptionalClose(
                actual.rateBPM,
                expected.rateBpm,
                tolerance: 1e-10
            )
            #expect(actual.preSamples == expected.preSamples)
            #expect(actual.postSamples == expected.postSamples)
            #expect(actual.windowLength == expected.windowLength)
            expectClose(
                actual.timeAxisSeconds,
                expected.timeAxisS,
                tolerance: 1e-12
            )
            #expect(actual.cycleValidMask == expected.cycleValidMask)
            #expect(
                actual.droppedPeakIndices
                    == expected.droppedPeakIndices
            )
            #expect(
                actual.validPeakIndices
                    == expected.validPeakIndices
            )
            #expect(actual.cycles.count == expected.cycles.count)
            for (actualCycle, expectedCycle) in zip(
                actual.cycles,
                expected.cycles
            ) {
                expectClose(
                    actualCycle,
                    expectedCycle,
                    tolerance: 1e-10
                )
            }
            expectClose(
                actual.template,
                expected.template,
                tolerance: 1e-10
            )
            #expect(
                actual.qualityAnchorPeakIndices
                    == expected.qualityAnchorPeakIndices
            )
            expectClose(
                actual.cycleQuality,
                expected.cycleQuality,
                tolerance: 1e-10
            )
            expectClose(
                actual.qualityTrace,
                expected.qualityTrace,
                tolerance: 1e-10
            )
        }
    }

    @Test
    func nonFiniteAndConfigurationFailuresAreTyped() {
        let nonFinite = TemplateMatchSQI.compute(
            preprocessedPeakUpValues:
                Array(repeating: 0, count: 799) + [.infinity]
        )
        var invalidConfiguration =
            TemplateMatchSQIConfiguration.iosBaseline01
        invalidConfiguration = TemplateMatchSQIConfiguration(
            algorithmVersion: invalidConfiguration.algorithmVersion,
            preprocessProfile: invalidConfiguration.preprocessProfile,
            sampleRateHz: 0,
            ratioPre: invalidConfiguration.ratioPre,
            maximumHeartRateBPM:
                invalidConfiguration.maximumHeartRateBPM,
            minimumWindowSeconds:
                invalidConfiguration.minimumWindowSeconds,
            standardDeviationEpsilon:
                invalidConfiguration.standardDeviationEpsilon,
            primaryHeightStandardDeviationFactor:
                invalidConfiguration
                    .primaryHeightStandardDeviationFactor,
            primaryProminenceStandardDeviationFactor:
                invalidConfiguration
                    .primaryProminenceStandardDeviationFactor,
            primaryMinimumWidthSamples:
                invalidConfiguration.primaryMinimumWidthSamples,
            fallbackHeightStandardDeviationFactor:
                invalidConfiguration
                    .fallbackHeightStandardDeviationFactor,
            fallbackProminenceStandardDeviationFactor:
                invalidConfiguration
                    .fallbackProminenceStandardDeviationFactor,
            goodThreshold: invalidConfiguration.goodThreshold,
            fairThreshold: invalidConfiguration.fairThreshold
        )
        let invalid = TemplateMatchSQI.compute(
            preprocessedPeakUpValues: Array(repeating: 0, count: 800),
            configuration: invalidConfiguration
        )

        #expect(
            nonFinite.unavailableReason == .nonFiniteInput
        )
        #expect(nonFinite.sqi == nil)
        #expect(
            invalid.unavailableReason == .invalidConfiguration
        )
        #expect(invalid.sqi == nil)
    }
}

private final class SQIFixtureBundleAnchor {}

private struct SQIFixture: Decodable {
    let schema: String
    let algorithmVersion: String
    let preprocessProfile: String
    let environment: Environment
    let config: Config
    let cases: [Case]

    struct Environment: Decodable {
        let python: String
        let numpy: String
        let scipy: String
    }

    struct Config: Decodable {
        let sampleRateHz: Double
        let ratioPre: Double
        let hrMaxBpm: Double
        let minimumWindowSeconds: Double
        let standardDeviationEpsilon: Double
        let primaryMinimumWidthSamples: Double
        let goodThreshold: Double
        let fairThreshold: Double
    }

    struct Case: Decodable {
        let name: String
        let fsHz: Double
        let signal: [Double]
        let expected: Expected
        let trace: Trace
    }

    struct Expected: Decodable {
        let valid: Bool
        let meanQuality: Double?
        let nCycles: Int
        let nPeaks: Int
        let hrEst: Double?
        let reason: String
        let displaySqi: Double?
        let grade: String
        let gradeColorHex: String
    }

    struct Trace: Decodable {
        let unavailableReason: String?
        let peakDetection: PeakDetection?
        let cycles: Cycles
    }

    struct PeakDetection: Decodable {
        let mean: Double
        let standardDeviation: Double
        let minimumDistanceSamples: Int
        let primary: PeakPass
        let fallback: PeakPass
        let selectedMode: String
        let selectedPeakIndices: [Int]
    }

    struct PeakPass: Decodable {
        let wasEvaluated: Bool?
        let minimumHeight: Double
        let minimumProminence: Double
        let minimumWidthSamples: Double?
        let peakIndices: [Int]
        let peakHeights: [Double]
        let prominences: [Double]
        let leftBases: [Int]
        let rightBases: [Int]
        let widths: [Double]
        let widthHeights: [Double]
        let leftIps: [Double]
        let rightIps: [Double]
    }

    struct Cycles: Decodable {
        let error: String?
        let rateBpm: Double?
        let preSamples: Int?
        let postSamples: Int?
        let windowLength: Int?
        let timeAxisS: [Double]
        let cycleValidMask: [Bool]
        let droppedPeakIndices: [Int]
        let validPeakIndices: [Int]
        let cycles: [[Double]]
        let template: [Double]
        let qualityAnchorPeakIndices: [Int]
        let cycleQuality: [Double]
        let qualityTrace: [Double]
    }
}

private func loadSQIFixture() throws -> SQIFixture {
    let bundle = Bundle(for: SQIFixtureBundleAnchor.self)
    let url = try #require(
        bundle.url(
            forResource: "sqi_vectors",
            withExtension: "json"
        )
    )
    let decoder = JSONDecoder()
    decoder.keyDecodingStrategy = .convertFromSnakeCase
    return try decoder.decode(
        SQIFixture.self,
        from: Data(contentsOf: url)
    )
}

private func expectPeakPass(
    _ actual: SQIPeakPassTrace,
    _ expected: SQIFixture.PeakPass
) {
    #expect(
        abs(actual.minimumHeight - expected.minimumHeight) <= 1e-12
    )
    #expect(
        abs(
            actual.minimumProminence
                - expected.minimumProminence
        ) <= 1e-12
    )
    expectOptionalClose(
        actual.minimumWidthSamples,
        expected.minimumWidthSamples,
        tolerance: 0
    )
    #expect(actual.peaks.map(\.index) == expected.peakIndices)
    expectClose(
        actual.peaks.map(\.height),
        expected.peakHeights,
        tolerance: 1e-12
    )
    expectClose(
        actual.peaks.map(\.prominence),
        expected.prominences,
        tolerance: 1e-10
    )
    #expect(
        actual.peaks.map(\.leftBaseIndex)
            == expected.leftBases
    )
    #expect(
        actual.peaks.map(\.rightBaseIndex)
            == expected.rightBases
    )
    expectClose(
        actual.peaks.compactMap(\.width),
        expected.widths,
        tolerance: 1e-9
    )
    expectClose(
        actual.peaks.compactMap(\.widthHeight),
        expected.widthHeights,
        tolerance: 1e-10
    )
    expectClose(
        actual.peaks.compactMap(\.leftIntersection),
        expected.leftIps,
        tolerance: 1e-9
    )
    expectClose(
        actual.peaks.compactMap(\.rightIntersection),
        expected.rightIps,
        tolerance: 1e-9
    )
}

private func expectOptionalClose(
    _ actual: Double?,
    _ expected: Double?,
    tolerance: Double
) {
    switch (actual, expected) {
    case let (.some(actual), .some(expected)):
        #expect(abs(actual - expected) <= tolerance)
    case (.none, .none):
        break
    default:
        Issue.record("Optional numeric result differs")
    }
}

private func expectClose(
    _ actual: [Double],
    _ expected: [Double],
    tolerance: Double
) {
    #expect(actual.count == expected.count)
    guard actual.count == expected.count else {
        return
    }
    let maximumError = zip(actual, expected)
        .map { abs($0 - $1) }
        .max() ?? 0
    #expect(maximumError <= tolerance)
}
