import Foundation
import Testing
@testable import PPGCollector

struct HeartRateEstimatorTests {
    @Test
    func frozenConfigurationMatchesPythonContract() throws {
        let fixture = try loadHeartRateFixture()
        let configuration = HeartRateConfiguration.pythonBaseline01

        #expect(fixture.schema == "cup.heart-rate.parity.v1")
        #expect(
            fixture.algorithmVersion
                == configuration.algorithmVersion
        )
        #expect(fixture.environment.python == "3.11.15")
        #expect(fixture.environment.numpy == "2.4.6")
        #expect(fixture.environment.scipy == "1.17.1")
        #expect(fixture.config.minBpm == configuration.minBPM)
        #expect(fixture.config.maxBpm == configuration.maxBPM)
        #expect(
            fixture.config.minimumWindowSeconds
                == configuration.minimumWindowSeconds
        )
        #expect(
            fixture.config.maximumWindowSeconds
                == configuration.maximumWindowSeconds
        )
        #expect(
            fixture.config.minimumRobustScale
                == configuration.minimumRobustScale
        )
        #expect(
            fixture.config.confidenceThreshold
                == configuration.confidenceThreshold
        )
        #expect(
            fixture.config.welchNearPeakHz
                == configuration.welchNearPeakHz
        )
        #expect(fixture.config.welchWindow == "hann_periodic")
        #expect(fixture.config.welchDetrend == "linear")
        #expect(fixture.config.welchScaling == "density")
    }

    @Test
    func finalEstimatesMatchPython() throws {
        for testCase in try loadHeartRateFixture().cases {
            let actual = HeartRateEstimator.estimate(
                values: testCase.values,
                timeSeconds: testCase.timeS,
                sampleRateHz: testCase.sampleRateHz
            )
            let expected = testCase.expected

            expectOptionalClose(
                actual.bpm,
                expected.bpm,
                tolerance: 1e-10
            )
            expectOptionalClose(
                actual.peakBPM,
                expected.peakBpm,
                tolerance: 1e-10
            )
            expectOptionalClose(
                actual.spectralBPM,
                expected.spectralBpm,
                tolerance: 1e-10
            )
            #expect(abs(actual.confidence - expected.confidence) <= 1e-10)
            if let expectedSNR = expected.snrDb {
                #expect(abs(actual.snrDB - expectedSNR) <= 1e-9)
            } else {
                #expect(actual.snrDB == -.infinity)
            }
            #expect(actual.polarity?.rawValue == expected.polarity)
            #expect(actual.peakIndices == expected.peakIndices)
            expectOptionalClose(
                actual.rrMADSeconds,
                expected.rrMadS,
                tolerance: 1e-10
            )
            #expect(
                actual.unavailableReason?.rawValue
                    == expected.unavailableReason
            )
            #expect(
                actual.isValid
                    == (
                        expected.bpm != nil
                            && expected.unavailableReason == nil
                    )
            )
        }
    }

    @Test
    func workAndWelchArraysMatchPython() throws {
        for testCase in try loadHeartRateFixture().cases {
            let actual = HeartRateEstimator.estimate(
                values: testCase.values,
                timeSeconds: testCase.timeS,
                sampleRateHz: testCase.sampleRateHz
            )
            #expect(actual.trace.windowOffset == 0)
            #expect(
                actual.trace.edgeTrimCount
                    == testCase.trace.edgeTrimCount
            )
            expectOptionalClose(
                actual.trace.workMedian,
                testCase.trace.workMedian,
                tolerance: 1e-10
            )
            expectOptionalClose(
                actual.trace.robustScale,
                testCase.trace.robustScale,
                tolerance: 1e-10
            )
            expectClose(
                actual.trace.workCentered,
                testCase.trace.workCentered,
                absoluteTolerance: 1e-10,
                relativeTolerance: 1e-12
            )

            guard let expected = testCase.trace.spectral else {
                #expect(actual.trace.spectral == nil)
                continue
            }
            let spectral = try #require(actual.trace.spectral)
            #expect(spectral.segmentLength == expected.nperseg)
            expectClose(
                spectral.frequenciesHz,
                expected.frequenciesHz,
                absoluteTolerance: 1e-12,
                relativeTolerance: 1e-12
            )
            expectClose(
                spectral.power,
                expected.power,
                absoluteTolerance: 1e-8,
                relativeTolerance: 2e-8
            )
            #expect(spectral.cardiacIndices == expected.cardiacIndices)
            #expect(spectral.peakIndex == expected.peakIndex)
            #expect(spectral.nearPeakIndices == expected.nearPeakIndices)
            expectOptionalClose(
                spectral.spectralBPM,
                expected.spectralBpm,
                tolerance: 1e-10
            )
            if let expectedSNR = expected.snrDb {
                #expect(abs(spectral.snrDB - expectedSNR) <= 1e-9)
            } else {
                #expect(spectral.snrDB == -.infinity)
            }
            #expect(
                abs(spectral.concentration - expected.concentration)
                    <= 1e-10
            )
        }
    }

    @Test
    func bothPolarityPeakAndRRTracesMatchPython() throws {
        for testCase in try loadHeartRateFixture().cases {
            let actual = HeartRateEstimator.estimate(
                values: testCase.values,
                timeSeconds: testCase.timeS,
                sampleRateHz: testCase.sampleRateHz
            )
            #expect(
                actual.trace.candidates.count
                    == testCase.trace.candidates.count
            )
            for (candidate, expected) in zip(
                actual.trace.candidates,
                testCase.trace.candidates
            ) {
                #expect(candidate.polarity.rawValue == expected.polarity)
                #expect(
                    candidate.distanceSamples
                        == expected.distanceSamples
                )
                #expect(
                    candidate.detectedLocalPeakIndices
                        == expected.detectedLocalPeakIndices
                )
                #expect(
                    candidate.detectedGlobalPeakIndices
                        == expected.detectedGlobalPeakIndices
                )
                expectClose(
                    candidate.prominences,
                    expected.prominences,
                    absoluteTolerance: 1e-8,
                    relativeTolerance: 1e-10
                )
                expectClose(
                    candidate.intervalsSeconds,
                    expected.intervalsS,
                    absoluteTolerance: 1e-12,
                    relativeTolerance: 1e-12
                )
                #expect(candidate.inRangeMask == expected.inRangeMask)
                #expect(
                    candidate.spectralMatchMask
                        == expected.spectralMatchMask
                )
                #expect(
                    candidate.spectralFilterApplied
                        == expected.spectralFilterApplied
                )
                #expect(candidate.validMask == expected.validMask)
                #expect(
                    candidate.longestRunLocalPeakIndices
                        == expected.longestRunLocalPeakIndices
                )
                #expect(
                    candidate.longestRunGlobalPeakIndices
                        == expected.longestRunGlobalPeakIndices
                )
                expectClose(
                    candidate.cleanedIntervalsSeconds,
                    expected.cleanedIntervalsS,
                    absoluteTolerance: 1e-12,
                    relativeTolerance: 1e-12
                )
                expectOptionalClose(
                    candidate.medianRRSeconds,
                    expected.medianRrS,
                    tolerance: 1e-12
                )
                expectOptionalClose(
                    candidate.rrMADSeconds,
                    expected.rrMadS,
                    tolerance: 1e-12
                )
                expectOptionalClose(
                    candidate.peakBPM,
                    expected.peakBpm,
                    tolerance: 1e-10
                )
                expectOptionalClose(
                    candidate.score,
                    expected.score,
                    tolerance: 1e-10
                )
                #expect(
                    candidate.rejectionReason?.rawValue
                        == expected.rejectionReason
                )
            }
        }
    }

    @Test
    func inputFailuresAreTypedAndLongWindowsUseLatestEightSeconds() {
        let mismatch = HeartRateEstimator.estimate(
            values: Array(repeating: 2, count: 400),
            timeSeconds: Array(repeating: 0, count: 399),
            sampleRateHz: 100
        )
        let nonFinite = HeartRateEstimator.estimate(
            values: Array(repeating: 2, count: 399) + [.nan],
            timeSeconds: (0..<400).map { Double($0) / 100 },
            sampleRateHz: 100
        )
        let invalidRate = HeartRateEstimator.estimate(
            values: Array(repeating: 2, count: 400),
            timeSeconds: (0..<400).map { Double($0) / 100 },
            sampleRateHz: 0
        )
        #expect(mismatch.unavailableReason == .inputLengthMismatch)
        #expect(nonFinite.unavailableReason == .nonFiniteInput)
        #expect(invalidRate.unavailableReason == .invalidConfiguration)

        let time = (0..<1_000).map { Double($0) / 100 }
        let values = time.map {
            2_000 * sin(2 * Double.pi * 1.5 * $0)
        }
        let long = HeartRateEstimator.estimate(
            values: values,
            timeSeconds: time,
            sampleRateHz: 100
        )
        let suffix = HeartRateEstimator.estimate(
            values: Array(values.suffix(800)),
            timeSeconds: Array(time.suffix(800)),
            sampleRateHz: 100
        )
        #expect(long.trace.windowOffset == 200)
        #expect(long.bpm == suffix.bpm)
        #expect(
            long.peakIndices
                == suffix.peakIndices.map { $0 + 200 }
        )
    }
}

private final class HeartRateFixtureBundleAnchor {}

private struct HeartRateFixture: Decodable {
    let schema: String
    let algorithmVersion: String
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
        let minBpm: Double
        let maxBpm: Double
        let minimumWindowSeconds: Double
        let maximumWindowSeconds: Double
        let minimumRobustScale: Double
        let confidenceThreshold: Double
        let welchWindow: String
        let welchDetrend: String
        let welchScaling: String
        let welchNearPeakHz: Double
    }

    struct Case: Decodable {
        let name: String
        let sampleRateHz: Double
        let timeS: [Double]
        let values: [Double]
        let expected: Expected
        let trace: Trace
    }

    struct Expected: Decodable {
        let bpm: Double?
        let peakBpm: Double?
        let spectralBpm: Double?
        let confidence: Double
        let snrDb: Double?
        let polarity: String?
        let peakIndices: [Int]
        let rrMadS: Double?
        let unavailableReason: String?
    }

    struct Trace: Decodable {
        let unavailableReason: String?
        let edgeTrimCount: Int
        let workMedian: Double?
        let robustScale: Double?
        let workCentered: [Double]
        let spectral: Spectral?
        let candidates: [Candidate]
    }

    struct Spectral: Decodable {
        let nperseg: Int
        let frequenciesHz: [Double]
        let power: [Double]
        let cardiacIndices: [Int]
        let peakIndex: Int?
        let nearPeakIndices: [Int]
        let spectralBpm: Double?
        let snrDb: Double?
        let concentration: Double
    }

    struct Candidate: Decodable {
        let polarity: String
        let distanceSamples: Int
        let detectedLocalPeakIndices: [Int]
        let detectedGlobalPeakIndices: [Int]
        let prominences: [Double]
        let intervalsS: [Double]
        let inRangeMask: [Bool]
        let spectralMatchMask: [Bool]
        let spectralFilterApplied: Bool
        let validMask: [Bool]
        let longestRunLocalPeakIndices: [Int]
        let longestRunGlobalPeakIndices: [Int]
        let cleanedIntervalsS: [Double]
        let medianRrS: Double?
        let rrMadS: Double?
        let peakBpm: Double?
        let score: Double?
        let rejectionReason: String?
    }
}

private func loadHeartRateFixture() throws -> HeartRateFixture {
    let bundle = Bundle(for: HeartRateFixtureBundleAnchor.self)
    let url = try #require(
        bundle.url(
            forResource: "heart_rate_vectors",
            withExtension: "json"
        )
    )
    let decoder = JSONDecoder()
    decoder.keyDecodingStrategy = .convertFromSnakeCase
    return try decoder.decode(
        HeartRateFixture.self,
        from: Data(contentsOf: url)
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
    absoluteTolerance: Double,
    relativeTolerance: Double
) {
    #expect(actual.count == expected.count)
    guard actual.count == expected.count else {
        return
    }
    let maximumNormalizedError = zip(actual, expected).map {
        abs($0 - $1)
            / max(absoluteTolerance, abs($1) * relativeTolerance)
    }.max() ?? 0
    #expect(maximumNormalizedError <= 1)
}
