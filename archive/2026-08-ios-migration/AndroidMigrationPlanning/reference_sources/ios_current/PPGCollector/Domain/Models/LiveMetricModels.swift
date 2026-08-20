import Foundation

nonisolated enum MetricUnavailableReason:
    String,
    Codable,
    Equatable,
    Sendable
{
    case noDevice
    case waitingForData
    case staleData
    case insufficientData
    case algorithmUnavailable
    case referenceParityPending
    case provisionalPreprocessing
    case calibrationUnavailable
    case modelUnavailable
    case computationFailed

    var message: String {
        switch self {
        case .noDevice:
            "尚未连接设备"
        case .waitingForData:
            "等待合法数据"
        case .staleData:
            "数据流已超时"
        case .insufficientData:
            "有效数据不足"
        case .algorithmUnavailable:
            "算法尚未接入"
        case .referenceParityPending:
            "参考流程尚未对齐"
        case .provisionalPreprocessing:
            "完整预处理/CPE 尚未准入"
        case .calibrationUnavailable:
            "缺少正式标定"
        case .modelUnavailable:
            "未提供可用模型"
        case .computationFailed:
            "本次计算失败"
        }
    }
}

nonisolated struct MetricResult<Value>:
    Equatable,
    Sendable
where Value: Equatable & Sendable {
    let value: Value?
    let isValid: Bool
    /// A finite score may be usable for acquisition diagnostics while the
    /// reference preprocessing/CPE or product threshold is still pending.
    let isProvisional: Bool
    let unavailableReason: MetricUnavailableReason?
    let measuredAt: Date?
    let sourceSampleIndex: UInt64?
    let sourceTimeSeconds: Double?
    let algorithmVersion: String
    let calibrationID: String?

    private init(
        value: Value?,
        isValid: Bool,
        isProvisional: Bool,
        unavailableReason: MetricUnavailableReason?,
        measuredAt: Date?,
        sourceSampleIndex: UInt64?,
        sourceTimeSeconds: Double?,
        algorithmVersion: String,
        calibrationID: String?
    ) {
        self.value = value
        self.isValid = isValid
        self.isProvisional = isProvisional
        self.unavailableReason = unavailableReason
        self.measuredAt = measuredAt
        self.sourceSampleIndex = sourceSampleIndex
        self.sourceTimeSeconds = sourceTimeSeconds
        self.algorithmVersion = algorithmVersion
        self.calibrationID = calibrationID
    }

    static func valid(
        _ value: Value,
        measuredAt: Date,
        algorithmVersion: String,
        sourceSampleIndex: UInt64? = nil,
        sourceTimeSeconds: Double? = nil,
        calibrationID: String? = nil,
        isProvisional: Bool = false
    ) -> Self {
        Self(
            value: value,
            isValid: true,
            isProvisional: isProvisional,
            unavailableReason: nil,
            measuredAt: measuredAt,
            sourceSampleIndex: sourceSampleIndex,
            sourceTimeSeconds: sourceTimeSeconds,
            algorithmVersion: algorithmVersion,
            calibrationID: calibrationID
        )
    }

    static func unavailable(
        _ reason: MetricUnavailableReason,
        algorithmVersion: String = "unavailable",
        measuredAt: Date? = nil,
        sourceSampleIndex: UInt64? = nil,
        sourceTimeSeconds: Double? = nil,
        calibrationID: String? = nil
    ) -> Self {
        Self(
            value: nil,
            isValid: false,
            isProvisional: false,
            unavailableReason: reason,
            measuredAt: measuredAt,
            sourceSampleIndex: sourceSampleIndex,
            sourceTimeSeconds: sourceTimeSeconds,
            algorithmVersion: algorithmVersion,
            calibrationID: calibrationID
        )
    }
}

nonisolated struct BloodPressureReading: Equatable, Sendable {
    let systolicMMHg: Double
    let diastolicMMHg: Double
}

nonisolated struct LiveMetricSnapshot: Equatable, Sendable {
    let heartRateBPM: MetricResult<Double>
    let oxygenSaturationPercent: MetricResult<Double>
    /// Ratio-of-ratios diagnostic. This is intentionally separate from
    /// oxygen saturation because no SpO2 calibration is admitted yet.
    let ratioOfRatios: MetricResult<Double>
    let signalQuality: MetricResult<Double>
    let bloodPressure: MetricResult<BloodPressureReading>

    static func unavailable(
        hasConnectedDevice: Bool,
        freshness: StreamFreshness
    ) -> Self {
        if !hasConnectedDevice {
            return allUnavailable(.noDevice)
        }

        switch freshness {
        case .unavailable, .waiting:
            return allUnavailable(.waitingForData)
        case .stale:
            return allUnavailable(.staleData)
        case .fresh:
            return LiveMetricSnapshot(
                heartRateBPM: .unavailable(.algorithmUnavailable),
                oxygenSaturationPercent:
                    .unavailable(.calibrationUnavailable),
                ratioOfRatios: .unavailable(.calibrationUnavailable),
                signalQuality:
                    .unavailable(.referenceParityPending),
                bloodPressure: .unavailable(.modelUnavailable)
            )
        }
    }

    static func warmingUp() -> Self {
        LiveMetricSnapshot(
            heartRateBPM: .unavailable(
                .insufficientData,
                algorithmVersion: "ppg-ios-hr-0.1"
            ),
            oxygenSaturationPercent:
                .unavailable(.calibrationUnavailable),
            ratioOfRatios: .unavailable(
                .insufficientData,
                algorithmVersion: "ppg-ios-rr-0.1"
            ),
            signalQuality: .unavailable(
                .insufficientData,
                algorithmVersion: "ppg-ios-sqi-0.1"
            ),
            bloodPressure: .unavailable(.modelUnavailable)
        )
    }

    static func runtime(
        heartRateBPM: MetricResult<Double>,
        signalQuality: MetricResult<Double>,
        ratioOfRatios: MetricResult<Double> = .unavailable(
            .calibrationUnavailable,
            algorithmVersion: "ppg-ios-rr-0.1"
        )
    ) -> Self {
        LiveMetricSnapshot(
            heartRateBPM: heartRateBPM,
            oxygenSaturationPercent:
                .unavailable(.calibrationUnavailable),
            ratioOfRatios: ratioOfRatios,
            signalQuality: signalQuality,
            bloodPressure: .unavailable(.modelUnavailable)
        )
    }

    private static func allUnavailable(
        _ reason: MetricUnavailableReason
    ) -> Self {
        LiveMetricSnapshot(
            heartRateBPM: .unavailable(reason),
            oxygenSaturationPercent: .unavailable(reason),
            ratioOfRatios: .unavailable(reason),
            signalQuality: .unavailable(reason),
            bloodPressure: .unavailable(reason)
        )
    }
}
