import Foundation
import Testing
@testable import PPGCollector

struct RatioOfRatiosEstimatorTests {
    @Test
    func computesDiagnosticRatioFromRedAndIRACDC() {
        let sampleRate = 100.0
        let redRaw = (0..<800).map { index in
            1_000 + 50 * sin(2 * .pi * 1.2 * Double(index) / sampleRate)
        }
        let irRaw = (0..<800).map { index in
            2_000 + 100 * sin(2 * .pi * 1.2 * Double(index) / sampleRate)
        }
        let redBandpassed = (0..<800).map { index in
            20 * sin(2 * .pi * 1.2 * Double(index) / sampleRate)
        }
        let irBandpassed = (0..<800).map { index in
            40 * sin(2 * .pi * 1.2 * Double(index) / sampleRate)
        }

        let result = RatioOfRatiosEstimator.estimate(
            redBandpassed: redBandpassed,
            irBandpassed: irBandpassed,
            redRaw: redRaw,
            irRaw: irRaw
        )

        #expect(result.isValid)
        #expect(abs((result.value ?? 0) - 1) < 0.03)
        let expectedACDC = 100 * (20 / sqrt(2.0)) / 1_000
        #expect(abs((result.redACDCPercent ?? 0) - expectedACDC) < 0.1)
        #expect(abs((result.irACDCPercent ?? 0) - expectedACDC) < 0.1)
    }

    @Test
    func rejectsMismatchedAndShortInputs() {
        let short = RatioOfRatiosEstimator.estimate(
            redBandpassed: Array(repeating: 1.0, count: 399),
            irBandpassed: Array(repeating: 1.0, count: 399),
            redRaw: Array(repeating: 1_000.0, count: 399),
            irRaw: Array(repeating: 1_000.0, count: 399)
        )
        #expect(short.unavailableReason == .insufficientSamples)

        let mismatched = RatioOfRatiosEstimator.estimate(
            redBandpassed: Array(repeating: 1.0, count: 800),
            irBandpassed: Array(repeating: 1.0, count: 799),
            redRaw: Array(repeating: 1_000.0, count: 800),
            irRaw: Array(repeating: 1_000.0, count: 800)
        )
        #expect(mismatched.unavailableReason == .inputLengthMismatch)
    }
}
