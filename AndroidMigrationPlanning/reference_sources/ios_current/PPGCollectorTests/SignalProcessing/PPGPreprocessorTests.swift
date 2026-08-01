import Foundation
import Testing
@testable import PPGCollector

struct PPGPreprocessorTests {
    @Test
    func frozenProfileMatchesPythonContract() throws {
        let fixture = try loadFixture()
        let profile = PPGPreprocessingProfile.iosBaseline01

        #expect(fixture.schema == "cup.preprocessing.parity.v1")
        #expect(fixture.profile == profile.identifier)
        #expect(fixture.environment.python == "3.11.15")
        #expect(fixture.environment.numpy == "2.4.6")
        #expect(fixture.environment.scipy == "1.17.1")
        #expect(fixture.config.sampleRateHz == profile.sampleRateHz)
        #expect(
            fixture.config.dcTimeConstantSeconds
                == profile.dcTimeConstantSeconds
        )
        #expect(fixture.config.dcAlpha == profile.dcAlpha)
        #expect(fixture.config.lowCutoffHz == profile.lowCutoffHz)
        #expect(fixture.config.highCutoffHz == profile.highCutoffHz)
        #expect(fixture.config.filterOrder == profile.filterOrder)
        #expect(
            fixture.config.polarityTransform
                == profile.sqiPolarityTransform.rawValue
        )
        #expect(
            fixture.config.standardDeviationEpsilon
                == profile.standardDeviationEpsilon
        )
        #expect(fixture.config.sos.count == profile.sections.count)

        for (fixtureSection, profileSection) in zip(
            fixture.config.sos,
            profile.sections
        ) {
            expectClose(
                fixtureSection,
                [
                    profileSection.b0,
                    profileSection.b1,
                    profileSection.b2,
                    profileSection.a0,
                    profileSection.a1,
                    profileSection.a2,
                ],
                tolerance: 1e-15
            )
        }
    }

    @Test
    func allPythonIntermediateArraysMatch() throws {
        let fixture = try loadFixture()

        for testCase in fixture.cases {
            var preprocessor = PPGPreprocessor()
            let resetIndices = Set(testCase.resetIndices)
            var dc: [Double] = []
            var ac: [Double] = []
            var bandpassed: [Double] = []
            var actualResetIndices: [Int] = []

            for (index, raw) in testCase.raw.enumerated() {
                let result = preprocessor.process(
                    raw: raw,
                    boundary: resetIndices.contains(index)
                        ? .gap
                        : .continuous
                )
                #expect(result.isValid)
                let sample = try #require(result.sample)
                dc.append(sample.dc)
                ac.append(sample.ac)
                bandpassed.append(sample.bandpassed)
                if sample.didResetAtBoundary {
                    actualResetIndices.append(index)
                }
            }

            #expect(actualResetIndices == testCase.resetIndices)
            expectClose(dc, testCase.expected.dc, tolerance: 1e-8)
            expectClose(ac, testCase.expected.ac, tolerance: 1e-8)
            expectClose(
                bandpassed,
                testCase.expected.bandpassed,
                tolerance: 1e-8
            )

            let normalized = PPGWindowNormalizer.normalize(bandpassed)
            expectClose(
                normalized.polarityAdjustedValues,
                testCase.expected.peakUp,
                tolerance: 1e-8
            )
            #expect(
                normalized.isValid
                    == testCase.expected.normalization.valid
            )
            #expect(
                normalized.unavailableReason?.rawValue
                    == testCase.expected.normalization.reason
            )
            if let expected = testCase.expected.normalization.values {
                let actual = try #require(normalized.normalizedValues)
                expectClose(actual, expected, tolerance: 1e-8)
                #expect(
                    abs(
                        (normalized.mean ?? .nan)
                            - (
                                testCase.expected.normalization.mean
                                    ?? .nan
                            )
                    ) <= 1e-8
                )
                #expect(
                    abs(
                        (normalized.standardDeviation ?? .nan)
                            - (
                                testCase.expected.normalization
                                    .standardDeviation
                                    ?? .nan
                            )
                    ) <= 1e-8
                )
            } else {
                #expect(normalized.normalizedValues == nil)
            }
        }
    }

    @Test
    func chunkingDoesNotChangeCausalState() throws {
        let testCase = try #require(
            try loadFixture().cases.first {
                $0.name == "pulse_down_8s"
            }
        )
        var oneShot = PPGPreprocessor()
        let expected = oneShot.process(rawValues: testCase.raw)
            .compactMap(\.sample?.bandpassed)

        var chunked = PPGPreprocessor()
        let chunkSizes = [1, 7, 50, 3, 111, 2, 244, 17, 365]
        var offset = 0
        var actual: [Double] = []
        for requestedCount in chunkSizes where offset < testCase.raw.count {
            let stop = min(
                testCase.raw.count,
                offset + requestedCount
            )
            actual.append(
                contentsOf: chunked.process(
                    rawValues: Array(testCase.raw[offset..<stop])
                ).compactMap(\.sample?.bandpassed)
            )
            offset = stop
        }
        if offset < testCase.raw.count {
            actual.append(
                contentsOf: chunked.process(
                    rawValues: Array(testCase.raw[offset...])
                ).compactMap(\.sample?.bandpassed)
            )
        }

        #expect(actual == expected)
    }

    @Test
    func gapResetMatchesASeparatedSuffix() throws {
        let testCase = try #require(
            try loadFixture().cases.first {
                $0.name == "gap_reset_5s"
            }
        )
        let resetIndex = try #require(testCase.resetIndices.first)
        var continuous = PPGPreprocessor()
        var suffixFromGap: [Double] = []
        for (index, raw) in testCase.raw.enumerated() {
            let result = continuous.process(
                raw: raw,
                boundary: index == resetIndex ? .gap : .continuous
            )
            if index >= resetIndex {
                suffixFromGap.append(
                    try #require(result.sample).bandpassed
                )
            }
        }

        var fresh = PPGPreprocessor()
        let freshSuffix = fresh.process(
            rawValues: Array(testCase.raw[resetIndex...])
        ).compactMap(\.sample?.bandpassed)

        #expect(suffixFromGap == freshSuffix)
        #expect(suffixFromGap.first == 0)
    }

    @Test
    func nonFiniteInputIsTypedAndResetsState() throws {
        var preprocessor = PPGPreprocessor()
        _ = preprocessor.process(raw: 500_000)
        _ = preprocessor.process(raw: 501_000)

        let invalid = preprocessor.process(raw: .nan)
        #expect(!invalid.isValid)
        #expect(invalid.sample == nil)
        #expect(invalid.unavailableReason == .nonFiniteInput)

        let restarted = try #require(
            preprocessor.process(raw: 520_000).sample
        )
        #expect(restarted.dc == 520_000)
        #expect(restarted.ac == 0)
        #expect(restarted.bandpassed == 0)
    }

    @Test
    func windowNormalizationHasExplicitPolarityAndFailureReasons() {
        let preserve = PPGWindowNormalizer.normalize(
            [1, 2, 3],
            polarityTransform: .preserve
        )
        let invert = PPGWindowNormalizer.normalize(
            [1, 2, 3],
            polarityTransform: .invert
        )
        let empty = PPGWindowNormalizer.normalize([])
        let nonFinite = PPGWindowNormalizer.normalize([1, .infinity])

        #expect(preserve.polarityAdjustedValues == [1, 2, 3])
        #expect(invert.polarityAdjustedValues == [-1, -2, -3])
        #expect(
            preserve.normalizedValues
                == [-(1.5).squareRoot(), 0, (1.5).squareRoot()]
        )
        #expect(
            invert.normalizedValues
                == [(1.5).squareRoot(), 0, -(1.5).squareRoot()]
        )
        #expect(empty.unavailableReason == .emptyWindow)
        #expect(nonFinite.unavailableReason == .nonFiniteInput)
    }
}

private final class PreprocessingFixtureBundleAnchor {}

private struct PreprocessingFixture: Decodable {
    let schema: String
    let profile: String
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
        let dcTimeConstantSeconds: Double
        let dcAlpha: Double
        let lowCutoffHz: Double
        let highCutoffHz: Double
        let filterOrder: Int
        let sos: [[Double]]
        let polarityTransform: String
        let standardDeviationEpsilon: Double
    }

    struct Case: Decodable {
        let name: String
        let raw: [Double]
        let resetIndices: [Int]
        let expected: Expected
    }

    struct Expected: Decodable {
        let dc: [Double]
        let ac: [Double]
        let bandpassed: [Double]
        let peakUp: [Double]
        let normalization: Normalization
    }

    struct Normalization: Decodable {
        let valid: Bool
        let reason: String?
        let mean: Double?
        let standardDeviation: Double?
        let values: [Double]?
    }
}

private func loadFixture() throws -> PreprocessingFixture {
    let bundle = Bundle(for: PreprocessingFixtureBundleAnchor.self)
    let url = try #require(
        bundle.url(
            forResource: "preprocessing_vectors",
            withExtension: "json"
        )
    )
    let decoder = JSONDecoder()
    decoder.keyDecodingStrategy = .convertFromSnakeCase
    return try decoder.decode(
        PreprocessingFixture.self,
        from: Data(contentsOf: url)
    )
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
