package com.example.ppgcollector_android.core.signal

import java.time.Instant

enum class MetricUnavailableReason(val wireValue: String, val message: String) {
    NO_DEVICE("noDevice", "尚未连接设备"),
    WAITING_FOR_DATA("waitingForData", "等待合法数据"),
    STALE_DATA("staleData", "数据流已超时"),
    INSUFFICIENT_DATA("insufficientData", "有效数据不足"),
    ALGORITHM_UNAVAILABLE("algorithmUnavailable", "算法尚未接入"),
    REFERENCE_PARITY_PENDING("referenceParityPending", "参考流程尚未对齐"),
    PROVISIONAL_PREPROCESSING("provisionalPreprocessing", "完整预处理/CPE 尚未准入"),
    CALIBRATION_UNAVAILABLE("calibrationUnavailable", "缺少正式标定"),
    MODEL_UNAVAILABLE("modelUnavailable", "未提供可用模型"),
    COMPUTATION_FAILED("computationFailed", "本次计算失败"),
}

data class MetricResult<T : Any>(
    val value: T?,
    val isValid: Boolean,
    val isProvisional: Boolean,
    val unavailableReason: MetricUnavailableReason?,
    val measuredAt: Instant?,
    val sourceSampleIndex: Long?,
    val sourceTimeSeconds: Double?,
    val algorithmVersion: String,
    val calibrationId: String?,
) {
    companion object {
        fun <T : Any> valid(
            value: T,
            measuredAt: Instant,
            algorithmVersion: String,
            sourceSampleIndex: Long? = null,
            sourceTimeSeconds: Double? = null,
            calibrationId: String? = null,
            isProvisional: Boolean = false,
        ): MetricResult<T> = MetricResult(
            value = value,
            isValid = true,
            isProvisional = isProvisional,
            unavailableReason = null,
            measuredAt = measuredAt,
            sourceSampleIndex = sourceSampleIndex,
            sourceTimeSeconds = sourceTimeSeconds,
            algorithmVersion = algorithmVersion,
            calibrationId = calibrationId,
        )

        fun <T : Any> unavailable(
            reason: MetricUnavailableReason,
            algorithmVersion: String = "unavailable",
            measuredAt: Instant? = null,
            sourceSampleIndex: Long? = null,
            sourceTimeSeconds: Double? = null,
            calibrationId: String? = null,
        ): MetricResult<T> = MetricResult(
            value = null,
            isValid = false,
            isProvisional = false,
            unavailableReason = reason,
            measuredAt = measuredAt,
            sourceSampleIndex = sourceSampleIndex,
            sourceTimeSeconds = sourceTimeSeconds,
            algorithmVersion = algorithmVersion,
            calibrationId = calibrationId,
        )
    }
}

data class BloodPressureReading(
    val systolicMmHg: Double,
    val diastolicMmHg: Double,
)

data class LiveMetricSnapshot(
    val heartRateBpm: MetricResult<Double>,
    val oxygenSaturationPercent: MetricResult<Double>,
    val ratioOfRatios: MetricResult<Double>,
    val signalQuality: MetricResult<Double>,
    val bloodPressure: MetricResult<BloodPressureReading>,
) {
    companion object {
        fun unavailable(
            hasConnectedDevice: Boolean,
            freshness: StreamFreshness,
        ): LiveMetricSnapshot {
            if (!hasConnectedDevice) return allUnavailable(MetricUnavailableReason.NO_DEVICE)
            return when (freshness) {
                StreamFreshness.UNAVAILABLE, StreamFreshness.WAITING ->
                    allUnavailable(MetricUnavailableReason.WAITING_FOR_DATA)
                StreamFreshness.STALE -> allUnavailable(MetricUnavailableReason.STALE_DATA)
                StreamFreshness.FRESH -> LiveMetricSnapshot(
                    heartRateBpm = MetricResult.unavailable(MetricUnavailableReason.ALGORITHM_UNAVAILABLE),
                    oxygenSaturationPercent = MetricResult.unavailable(MetricUnavailableReason.CALIBRATION_UNAVAILABLE),
                    ratioOfRatios = MetricResult.unavailable(MetricUnavailableReason.CALIBRATION_UNAVAILABLE),
                    signalQuality = MetricResult.unavailable(MetricUnavailableReason.REFERENCE_PARITY_PENDING),
                    bloodPressure = MetricResult.unavailable(MetricUnavailableReason.MODEL_UNAVAILABLE),
                )
            }
        }

        fun warmingUp(): LiveMetricSnapshot = LiveMetricSnapshot(
            heartRateBpm = MetricResult.unavailable(
                MetricUnavailableReason.INSUFFICIENT_DATA,
                algorithmVersion = "ppg-ios-hr-0.1",
            ),
            oxygenSaturationPercent = MetricResult.unavailable(MetricUnavailableReason.CALIBRATION_UNAVAILABLE),
            ratioOfRatios = MetricResult.unavailable(
                MetricUnavailableReason.INSUFFICIENT_DATA,
                algorithmVersion = "ppg-ios-rr-0.1",
            ),
            signalQuality = MetricResult.unavailable(
                MetricUnavailableReason.INSUFFICIENT_DATA,
                algorithmVersion = "ppg-ios-sqi-0.1",
            ),
            bloodPressure = MetricResult.unavailable(MetricUnavailableReason.MODEL_UNAVAILABLE),
        )

        fun runtime(
            heartRateBpm: MetricResult<Double>,
            signalQuality: MetricResult<Double>,
            ratioOfRatios: MetricResult<Double> = MetricResult.unavailable(
                MetricUnavailableReason.CALIBRATION_UNAVAILABLE,
                algorithmVersion = "ppg-ios-rr-0.1",
            ),
        ) = LiveMetricSnapshot(
            heartRateBpm = heartRateBpm,
            oxygenSaturationPercent = MetricResult.unavailable(MetricUnavailableReason.CALIBRATION_UNAVAILABLE),
            ratioOfRatios = ratioOfRatios,
            signalQuality = signalQuality,
            bloodPressure = MetricResult.unavailable(MetricUnavailableReason.MODEL_UNAVAILABLE),
        )

        private fun allUnavailable(reason: MetricUnavailableReason) = LiveMetricSnapshot(
            heartRateBpm = MetricResult.unavailable(reason),
            oxygenSaturationPercent = MetricResult.unavailable(reason),
            ratioOfRatios = MetricResult.unavailable(reason),
            signalQuality = MetricResult.unavailable(reason),
            bloodPressure = MetricResult.unavailable(reason),
        )
    }
}

enum class StreamFreshness { UNAVAILABLE, WAITING, FRESH, STALE }
