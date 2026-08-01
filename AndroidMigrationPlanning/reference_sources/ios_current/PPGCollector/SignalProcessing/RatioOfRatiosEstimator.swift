import Foundation

nonisolated enum RatioOfRatiosUnavailableReason:
    String,
    Equatable,
    Sendable
{
    case inputLengthMismatch
    case insufficientSamples
    case nonFiniteInput
    case insufficientDC
    case insufficientAC
    case nonFiniteResult
}

nonisolated struct RatioOfRatiosEstimate: Equatable, Sendable {
    let value: Double?
    let redACDCPercent: Double?
    let irACDCPercent: Double?
    let unavailableReason: RatioOfRatiosUnavailableReason?
    let algorithmVersion: String

    var isValid: Bool {
        value != nil && unavailableReason == nil
    }
}

/// Diagnostic-only red/IR ratio-of-ratios calculation.
///
/// This intentionally does not convert R into SpO2. That conversion requires
/// device-specific optical calibration and reference measurements.
nonisolated enum RatioOfRatiosEstimator {
    static let algorithmVersion = "ppg-ios-rr-0.1"

    static func estimate(
        redBandpassed: [Double],
        irBandpassed: [Double],
        redRaw: [Double],
        irRaw: [Double]
    ) -> RatioOfRatiosEstimate {
        let count = min(
            redBandpassed.count,
            min(irBandpassed.count, min(redRaw.count, irRaw.count))
        )
        guard redBandpassed.count == irBandpassed.count,
              redBandpassed.count == redRaw.count,
              redBandpassed.count == irRaw.count else {
            return unavailable(.inputLengthMismatch)
        }
        guard count >= 400 else {
            return unavailable(.insufficientSamples)
        }

        let selected = trimmedRange(count: count)
        let redBand = Array(redBandpassed[selected])
        let irBand = Array(irBandpassed[selected])
        let redBase = Array(redRaw[selected])
        let irBase = Array(irRaw[selected])
        guard redBand.allSatisfy(\.isFinite),
              irBand.allSatisfy(\.isFinite),
              redBase.allSatisfy(\.isFinite),
              irBase.allSatisfy(\.isFinite) else {
            return unavailable(.nonFiniteInput)
        }

        let redDC = abs(mean(redBase))
        let irDC = abs(mean(irBase))
        guard redDC > 1, irDC > 1 else {
            return unavailable(.insufficientDC)
        }
        let redAC = rootMeanSquare(redBand)
        let irAC = rootMeanSquare(irBand)
        guard redAC > 0, irAC > 0 else {
            return unavailable(.insufficientAC)
        }
        let redACDC = 100 * redAC / max(redDC, 1)
        let irACDC = 100 * irAC / max(irDC, 1)
        let ratio = redACDC / irACDC
        guard ratio.isFinite, ratio >= 0 else {
            return unavailable(.nonFiniteResult)
        }
        return RatioOfRatiosEstimate(
            value: ratio,
            redACDCPercent: redACDC,
            irACDCPercent: irACDC,
            unavailableReason: nil,
            algorithmVersion: algorithmVersion
        )
    }

    private static func trimmedRange(count: Int) -> Range<Int> {
        let edge = min(count / 10, max(0, count / 2 - 1))
        return edge..<(count - edge)
    }

    private static func mean(_ values: [Double]) -> Double {
        values.reduce(0, +) / Double(values.count)
    }

    private static func rootMeanSquare(_ values: [Double]) -> Double {
        sqrt(values.reduce(0) { $0 + $1 * $1 } / Double(values.count))
    }

    private static func unavailable(
        _ reason: RatioOfRatiosUnavailableReason
    ) -> RatioOfRatiosEstimate {
        RatioOfRatiosEstimate(
            value: nil,
            redACDCPercent: nil,
            irACDCPercent: nil,
            unavailableReason: reason,
            algorithmVersion: algorithmVersion
        )
    }
}
