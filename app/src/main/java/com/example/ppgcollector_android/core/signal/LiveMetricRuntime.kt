package com.example.ppgcollector_android.core.signal

import com.example.ppgcollector_android.core.protocol.CupBatchProtocolV1
import com.example.ppgcollector_android.core.protocol.CupDecodedFrameEvent
import com.example.ppgcollector_android.core.signal.combo.ComboSqi
import com.example.ppgcollector_android.core.signal.combo.ComboSqiResult
import java.time.Instant
import kotlin.math.max

data class LiveMetricRuntimeProfile(
    val identifier: String,
    val sampleRateHz: Int,
    val windowSampleCount: Int,
    val cadenceSampleCount: Int,
    val preprocessingProfile: PpgPreprocessingProfile,
    val heartRateConfiguration: HeartRateConfiguration,
    val signalQualityConfiguration: TemplateMatchSqiConfiguration,
    val signalQualityIsApproved: Boolean,
) {
    companion object {
        val iosBaseline01 = LiveMetricRuntimeProfile(
            identifier = "ppg-ios-live-0.1",
            sampleRateHz = CupBatchProtocolV1.sampleRateHz,
            windowSampleCount = CupBatchProtocolV1.sampleRateHz * 8,
            cadenceSampleCount = CupBatchProtocolV1.sampleRateHz,
            preprocessingProfile = PpgPreprocessingProfile.iosBaseline01,
            heartRateConfiguration = HeartRateConfiguration.pythonBaseline01,
            signalQualityConfiguration = TemplateMatchSqiConfiguration.iosBaseline01,
            signalQualityIsApproved = false,
        )
    }
}

data class LiveMetricAnalysisRequest(
    val generation: Long,
    val requestSequence: Long,
    val windowEndSampleIndex: Long,
    val windowEndTimeSeconds: Double,
    val measuredAt: Instant,
    val rawRed: List<Double> = emptyList(),
    val rawIr: List<Double> = emptyList(),
    val bandpassedRed: List<Double> = emptyList(),
    val bandpassedIr: List<Double>,
    val timeSeconds: List<Double>,
    /** 1 Hz epoch number within one locally continuous accepted-sample generation. */
    val metricEpoch: Long = requestSequence,
)

data class LiveMetricAnalysisResult(
    val request: LiveMetricAnalysisRequest,
    val snapshot: LiveMetricSnapshot,
    val provisionalSignalQuality: TemplateMatchSqiEstimate?,
    val comboSqiCandidate: ComboSqiResult = ComboSqi.unknown(),
)

/** Stateful causal preprocessing and bounded 800/100 sample-count scheduling. */
class LiveMetricWindowScheduler(
    val profile: LiveMetricRuntimeProfile = LiveMetricRuntimeProfile.iosBaseline01,
) {
    private val irPreprocessor = PpgPreprocessor(profile.preprocessingProfile)
    private val redPreprocessor = PpgPreprocessor(profile.preprocessingProfile)
    private val rawRed = ArrayList<Double>(profile.windowSampleCount)
    private val rawIr = ArrayList<Double>(profile.windowSampleCount)
    private val bandpassedRed = ArrayList<Double>(profile.windowSampleCount)
    private val bandpassedIr = ArrayList<Double>(profile.windowSampleCount)
    private val timeSeconds = ArrayList<Double>(profile.windowSampleCount)
    private var nextAcceptedSampleIndex = 0L
    private var continuousSampleCount = 0
    private var nextAnalysisContinuousSampleCount = profile.windowSampleCount
    private var metricEpoch = 0L
    var generation: Long = 0
        private set
    var requestSequence: Long = 0
        private set

    val bufferedSampleCount: Int
        get() = bandpassedIr.size
    val continuousSamples: Int
        get() = continuousSampleCount

    fun ingest(
        decodedFrames: List<CupDecodedFrameEvent>,
        measuredAt: Instant,
        acceptedSampleStartIndex: Long? = null,
    ): LiveMetricAnalysisRequest? {
        if (acceptedSampleStartIndex != null && acceptedSampleStartIndex != nextAcceptedSampleIndex) {
            invalidateContinuity()
            nextAcceptedSampleIndex = acceptedSampleStartIndex
        }

        var analysisIsDue = false
        decodedFrames.filter { it.isAccepted }.forEach { decoded ->
            decoded.frame.samples.forEach { sample ->
                val redResult = redPreprocessor.process(sample.red.toDouble())
                val irResult = irPreprocessor.process(sample.ir.toDouble())
                val redProcessed = redResult.sample
                val irProcessed = irResult.sample
                if (redProcessed == null || irProcessed == null) {
                    invalidateContinuity()
                    nextAcceptedSampleIndex++
                    return@forEach
                }
                rawRed += sample.red.toDouble()
                rawIr += sample.ir.toDouble()
                bandpassedRed += redProcessed.bandpassed
                bandpassedIr += irProcessed.bandpassed
                timeSeconds += nextAcceptedSampleIndex.toDouble() / profile.sampleRateHz.toDouble()
                nextAcceptedSampleIndex++
                continuousSampleCount++
            }
            trimWindowIfNeeded()
            while (continuousSampleCount >= nextAnalysisContinuousSampleCount) {
                analysisIsDue = true
                nextAnalysisContinuousSampleCount += profile.cadenceSampleCount
            }
        }

        if (!analysisIsDue ||
            rawRed.size != profile.windowSampleCount ||
            rawIr.size != profile.windowSampleCount ||
            bandpassedRed.size != profile.windowSampleCount ||
            bandpassedIr.size != profile.windowSampleCount ||
            timeSeconds.size != profile.windowSampleCount
        ) return null

        requestSequence++
        metricEpoch++
        val windowEndSampleIndex = nextAcceptedSampleIndex - 1
        return LiveMetricAnalysisRequest(
            generation = generation,
            requestSequence = requestSequence,
            windowEndSampleIndex = windowEndSampleIndex,
            windowEndTimeSeconds = windowEndSampleIndex.toDouble() / profile.sampleRateHz.toDouble(),
            measuredAt = measuredAt,
            rawRed = rawRed.toList(),
            rawIr = rawIr.toList(),
            bandpassedRed = bandpassedRed.toList(),
            bandpassedIr = bandpassedIr.toList(),
            timeSeconds = timeSeconds.toList(),
            metricEpoch = metricEpoch,
        )
    }

    fun isCurrent(request: LiveMetricAnalysisRequest): Boolean =
        request.generation == generation && request.requestSequence == requestSequence

    fun invalidateContinuity() {
        irPreprocessor.reset()
        redPreprocessor.reset()
        rawRed.clear()
        rawIr.clear()
        bandpassedRed.clear()
        bandpassedIr.clear()
        timeSeconds.clear()
        continuousSampleCount = 0
        nextAnalysisContinuousSampleCount = profile.windowSampleCount
        metricEpoch = 0L
        generation++
    }

    fun reset() {
        invalidateContinuity()
        nextAcceptedSampleIndex = 0L
    }

    private fun trimWindowIfNeeded() {
        val overflow = bandpassedIr.size - profile.windowSampleCount
        if (overflow <= 0) return
        repeat(overflow) {
            rawRed.removeAt(0)
            rawIr.removeAt(0)
            bandpassedRed.removeAt(0)
            bandpassedIr.removeAt(0)
            timeSeconds.removeAt(0)
        }
    }
}

object LiveMetricAnalyzer {
    fun analyze(
        request: LiveMetricAnalysisRequest,
        profile: LiveMetricRuntimeProfile = LiveMetricRuntimeProfile.iosBaseline01,
    ): LiveMetricAnalysisResult {
        val heartRate = HeartRateEstimator.estimate(
            values = request.bandpassedIr,
            timeSeconds = request.timeSeconds,
            sampleRateHz = profile.sampleRateHz.toDouble(),
            configuration = profile.heartRateConfiguration,
        )
        val heartRateMetric = HeartRateEstimator.acceptedBpm(heartRate, profile.heartRateConfiguration)?.let {
            MetricResult.valid(
                value = it,
                measuredAt = request.measuredAt,
                algorithmVersion = profile.heartRateConfiguration.algorithmVersion,
                sourceSampleIndex = request.windowEndSampleIndex,
                sourceTimeSeconds = request.windowEndTimeSeconds,
            )
        } ?: MetricResult.unavailable<Double>(
            reason = heartRateReason(heartRate.unavailableReason),
            algorithmVersion = profile.heartRateConfiguration.algorithmVersion,
            measuredAt = request.measuredAt,
            sourceSampleIndex = request.windowEndSampleIndex,
            sourceTimeSeconds = request.windowEndTimeSeconds,
        )

        val normalized = PpgWindowNormalizer.normalize(
            request.bandpassedIr,
            profile = profile.preprocessingProfile,
        )
        val provisionalSignalQuality = normalized.normalizedValues?.let {
            TemplateMatchSqi.compute(it, profile.signalQualityConfiguration)
        }
        val signalQualityMetric = provisionalSignalQuality?.takeIf { it.isValid && it.sqi?.isFinite() == true }?.let {
            MetricResult.valid(
                value = it.sqi!!,
                measuredAt = request.measuredAt,
                algorithmVersion = profile.signalQualityConfiguration.algorithmVersion,
                sourceSampleIndex = request.windowEndSampleIndex,
                sourceTimeSeconds = request.windowEndTimeSeconds,
                isProvisional = !profile.signalQualityIsApproved,
            )
        } ?: MetricResult.unavailable<Double>(
            reason = MetricUnavailableReason.COMPUTATION_FAILED,
            algorithmVersion = profile.signalQualityConfiguration.algorithmVersion,
            measuredAt = request.measuredAt,
            sourceSampleIndex = request.windowEndSampleIndex,
            sourceTimeSeconds = request.windowEndTimeSeconds,
        )

        val ratio = RatioOfRatiosEstimator.estimate(
            redBandpassed = request.bandpassedRed,
            irBandpassed = request.bandpassedIr,
            redRaw = request.rawRed,
            irRaw = request.rawIr,
        )
        val ratioUnavailableReason = ratioReason(ratio.unavailableReason)
        val ratioMetric = ratio.value?.takeIf { it.isFinite() }?.let {
            MetricResult.valid(
                value = it,
                measuredAt = request.measuredAt,
                algorithmVersion = ratio.algorithmVersion,
                sourceSampleIndex = request.windowEndSampleIndex,
                sourceTimeSeconds = request.windowEndTimeSeconds,
                isProvisional = true,
            )
        } ?: MetricResult.unavailable<Double>(
            reason = ratioUnavailableReason,
            algorithmVersion = ratio.algorithmVersion,
            measuredAt = request.measuredAt,
            sourceSampleIndex = request.windowEndSampleIndex,
            sourceTimeSeconds = request.windowEndTimeSeconds,
        )

        val perfusionIndexMetric = ratio.redAcDcPercent?.takeIf { it.isFinite() }?.let {
            MetricResult.valid(
                value = it,
                measuredAt = request.measuredAt,
                algorithmVersion = "ppg-pi-red-acdc-0.1",
                sourceSampleIndex = request.windowEndSampleIndex,
                sourceTimeSeconds = request.windowEndTimeSeconds,
                isProvisional = true,
            )
        } ?: MetricResult.unavailable<Double>(
            reason = ratioUnavailableReason,
            algorithmVersion = "ppg-pi-red-acdc-0.1",
            measuredAt = request.measuredAt,
            sourceSampleIndex = request.windowEndSampleIndex,
            sourceTimeSeconds = request.windowEndTimeSeconds,
        )

        return LiveMetricAnalysisResult(
            request = request,
            snapshot = LiveMetricSnapshot.runtime(
                heartRateBpm = heartRateMetric,
                signalQuality = signalQualityMetric,
                ratioOfRatios = ratioMetric,
                perfusionIndex = perfusionIndexMetric,
            ),
            provisionalSignalQuality = provisionalSignalQuality,
            comboSqiCandidate = ComboSqi.evaluate(
                request.rawIr.takeLast(ComboSqi.minimumSamples),
                profile.sampleRateHz,
            ),
        )
    }

    private fun heartRateReason(reason: HeartRateUnavailableReason?) = when (reason) {
        HeartRateUnavailableReason.INSUFFICIENT_SAMPLES,
        HeartRateUnavailableReason.INSUFFICIENT_WORK_WINDOW -> MetricUnavailableReason.INSUFFICIENT_DATA
        else -> MetricUnavailableReason.COMPUTATION_FAILED
    }

    private fun ratioReason(reason: RatioOfRatiosUnavailableReason?) = when (reason) {
        RatioOfRatiosUnavailableReason.INSUFFICIENT_SAMPLES -> MetricUnavailableReason.INSUFFICIENT_DATA
        RatioOfRatiosUnavailableReason.INPUT_LENGTH_MISMATCH,
        RatioOfRatiosUnavailableReason.NON_FINITE_INPUT,
        RatioOfRatiosUnavailableReason.INSUFFICIENT_DC,
        RatioOfRatiosUnavailableReason.INSUFFICIENT_AC,
        RatioOfRatiosUnavailableReason.NON_FINITE_RESULT,
        null -> MetricUnavailableReason.COMPUTATION_FAILED
    }
}
