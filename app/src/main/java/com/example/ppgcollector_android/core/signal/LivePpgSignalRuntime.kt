package com.example.ppgcollector_android.core.signal

import com.example.ppgcollector_android.core.protocol.CupDecodedFrameEvent
import com.example.ppgcollector_android.core.protocol.CupSequenceEvent
import java.time.Instant

data class LivePpgIngestResult(
    val waveform: LiveWaveformSnapshot?,
    val metricRequest: LiveMetricAnalysisRequest?,
)

/**
 * Single causal PPG state owner for one ordered live stream.
 *
 * Raw and causal waveform snapshots and 800/100 metric requests are derived
 * from the same preprocessors and bounded rings. Wire gaps remain visible and
 * diagnosable, but only loss of the local accepted-sample cursor resets the
 * processing state; otherwise frequent small wire gaps could prevent metrics
 * from ever reaching their bounded input window.
 */
class LivePpgSignalRuntime(
    val profile: LiveMetricRuntimeProfile = LiveMetricRuntimeProfile.iosBaseline01,
    refreshRateHz: Int = 5,
) {
    init {
        require(profile.sampleRateHz > 0)
        require(profile.windowSampleCount > 0)
        require(profile.cadenceSampleCount > 0)
        require(refreshRateHz in 1..60)
        require(profile.sampleRateHz % refreshRateHz == 0)
    }

    private val rawRed = DoubleArray(profile.windowSampleCount)
    private val rawIr = DoubleArray(profile.windowSampleCount)
    private val displayEcg = DoubleArray(profile.windowSampleCount)
    private val causalRed = DoubleArray(profile.windowSampleCount)
    private val causalIr = DoubleArray(profile.windowSampleCount)
    private val displayCausalRed = DoubleArray(profile.windowSampleCount)
    private val displayCausalIr = DoubleArray(profile.windowSampleCount)
    private val timeSeconds = DoubleArray(profile.windowSampleCount)
    private var redPreprocessor = PpgPreprocessor(profile.preprocessingProfile)
    private var irPreprocessor = PpgPreprocessor(profile.preprocessingProfile)
    private var displayCausalRedFilter = CausalPpgDisplayFilterRuntime()
    private var displayCausalIrFilter = CausalPpgDisplayFilterRuntime()
    private var fixedLagRuntime = FixedLagPpgFilterRuntime()
    private var ecgDisplayDownsampler = EcgDisplayDownsampler()
    private val fixedLagSamples = ArrayDeque<FixedLagPpgSample>(profile.windowSampleCount)
    private var ringStart = 0
    private var ringSize = 0
    private var ecgRingStart = 0
    private var ecgRingSize = 0
    private var nextAcceptedSampleIndex = 0L
    private var acceptedSampleCount = 0L
    private var continuousSampleCount = 0L
    private var displayContinuousSampleCount = 0L
    private var nextAnalysisContinuousSampleCount = profile.windowSampleCount.toLong()
    private var metricEpoch = 0L
    private var publicationSequence = 0L
    private var nextPublishNanos: Long? = null
    private var continuityEpoch = 0L
    private var gapCount = 0L
    private val segmentBreaks = ArrayDeque<Long>()
    private val refreshIntervalNanos = 1_000_000_000L / refreshRateHz.toLong()

    var generation: Long = 0L
        private set
    var requestSequence: Long = 0L
        private set

    val bufferedSampleCount: Int
        get() = ringSize
    val continuousSamples: Long
        get() = continuousSampleCount
    val currentContinuityEpoch: Long
        get() = continuityEpoch
    val gapCountValue: Long
        get() = gapCount

    fun ingest(
        decodedFrames: List<CupDecodedFrameEvent>,
        acceptedSampleStartIndex: Long,
        measuredAt: Instant,
        nowNanos: Long,
        acceptedEcgSamples: List<UInt> = emptyList(),
    ): LivePpgIngestResult {
        if (acceptedSampleStartIndex != nextAcceptedSampleIndex) {
            invalidateContinuity(acceptedSampleStartIndex)
        }

        var analysisIsDue = false
        for (decoded in decodedFrames) {
            if (!decoded.isAccepted) continue
            if (decoded.sequenceEvent is CupSequenceEvent.Gap) {
                gapCount++
                markSegmentBreak(nextAcceptedSampleIndex)
                // The accepted-sample stream remains ordered. Preserve its
                // bounded filters/metric window and expose the wire loss as a
                // segment marker plus diagnostics instead of permanent warmup.
            }
            for (sample in decoded.frame.samples) {
                val rawRedValue = sample.red.toDouble()
                val rawIrValue = sample.ir.toDouble()
                val displayRawRed = -rawRedValue
                val displayRawIr = -rawIrValue
                val red = redPreprocessor.process(rawRedValue).sample
                val ir = irPreprocessor.process(rawIrValue).sample
                if (red == null || ir == null) {
                    invalidateContinuity(nextAcceptedSampleIndex + 1L)
                    acceptedSampleCount++
                    continue
                }
                append(
                    red = rawRedValue,
                    ir = rawIrValue,
                    filteredRed = red.bandpassed,
                    filteredIr = ir.bandpassed,
                    displayFilteredRed = displayCausalRedFilter.process(displayRawRed),
                    displayFilteredIr = displayCausalIrFilter.process(displayRawIr),
                    time = nextAcceptedSampleIndex.toDouble() / profile.sampleRateHz.toDouble(),
                )
                fixedLagRuntime.ingest(
                    sourceSampleIndex = nextAcceptedSampleIndex,
                    red = displayRawRed,
                    ir = displayRawIr,
                ).forEach { filtered ->
                    fixedLagSamples.addLast(filtered)
                    while (fixedLagSamples.size > profile.windowSampleCount) {
                        fixedLagSamples.removeFirst()
                    }
                }
                nextAcceptedSampleIndex++
                acceptedSampleCount++
                continuousSampleCount++
                displayContinuousSampleCount++
            }
            while (continuousSampleCount >= nextAnalysisContinuousSampleCount) {
                analysisIsDue = true
                nextAnalysisContinuousSampleCount += profile.cadenceSampleCount.toLong()
            }
        }

        // ECG is appended only after the same sequence gate has accepted its
        // corresponding PPG packet. This keeps the first ECG snapshot atomic
        // with RED/IR instead of publishing a transient two-window state.
        if (acceptedEcgSamples.isNotEmpty()) ingestEcgDisplaySamples(acceptedEcgSamples)
        if (ringSize > 0 && nextPublishNanos == null) nextPublishNanos = nowNanos
        return LivePpgIngestResult(
            waveform = publishIfDue(nowNanos, measuredAt),
            metricRequest = if (analysisIsDue &&
                continuousSampleCount >= profile.windowSampleCount
            ) metricRequest(measuredAt) else null,
        )
    }

    fun poll(nowNanos: Long, measuredAt: Instant): LiveWaveformSnapshot? =
        publishIfDue(nowNanos, measuredAt)

    /** Display-only ECG path: average each five 500 Hz ADC samples into one point. */
    fun ingestEcgDisplaySamples(samples: List<UInt>) {
        ecgDisplayDownsampler.ingest(samples).forEach { sample ->
            val writeIndex = (ecgRingStart + ecgRingSize) % profile.windowSampleCount
            displayEcg[writeIndex] = sample
            if (ecgRingSize < profile.windowSampleCount) {
                ecgRingSize++
            } else {
                ecgRingStart = (ecgRingStart + 1) % profile.windowSampleCount
            }
        }
    }

    fun publishNow(nowNanos: Long, measuredAt: Instant): LiveWaveformSnapshot? {
        if (ringSize == 0) return null
        publicationSequence++
        nextPublishNanos = nowNanos + refreshIntervalNanos
        return snapshot(measuredAt)
    }

    fun isCurrent(request: LiveMetricAnalysisRequest): Boolean =
        request.generation == generation && request.requestSequence == requestSequence

    /** Hard reset for an App-side loss while preserving the absolute accepted-sample cursor. */
    fun invalidateLocalInput(nextAcceptedSampleIndex: Long) {
        require(nextAcceptedSampleIndex >= 0L)
        invalidateContinuity(nextAcceptedSampleIndex)
    }

    fun reset() {
        ringStart = 0
        ringSize = 0
        ecgRingStart = 0
        ecgRingSize = 0
        acceptedSampleCount = 0L
        publicationSequence = 0L
        requestSequence = 0L
        gapCount = 0L
        segmentBreaks.clear()
        invalidateContinuity(0L)
    }

    private fun metricRequest(measuredAt: Instant): LiveMetricAnalysisRequest? {
        if (ringSize != profile.windowSampleCount) return null
        requestSequence++
        metricEpoch++
        val windowEndSampleIndex = nextAcceptedSampleIndex - 1L
        return LiveMetricAnalysisRequest(
            generation = generation,
            requestSequence = requestSequence,
            windowEndSampleIndex = windowEndSampleIndex,
            windowEndTimeSeconds = windowEndSampleIndex.toDouble() / profile.sampleRateHz.toDouble(),
            measuredAt = measuredAt,
            rawRed = copyRing(rawRed).asList(),
            rawIr = copyRing(rawIr).asList(),
            bandpassedRed = copyRing(causalRed).asList(),
            bandpassedIr = copyRing(causalIr).asList(),
            timeSeconds = copyRing(timeSeconds).asList(),
            metricEpoch = metricEpoch,
        )
    }

    private fun publishIfDue(nowNanos: Long, measuredAt: Instant): LiveWaveformSnapshot? {
        if (ringSize == 0) return null
        val due = nextPublishNanos ?: return null
        if (nowNanos < due) return null
        val elapsedNanos = (nowNanos - due).coerceAtLeast(0L)
        val skippedIntervals = elapsedNanos / refreshIntervalNanos
        nextPublishNanos = due + (skippedIntervals + 1L) * refreshIntervalNanos
        publicationSequence++
        return snapshot(measuredAt)
    }

    private fun snapshot(measuredAt: Instant): LiveWaveformSnapshot {
        val firstContinuousOffset =
            (displayContinuousSampleCount - ringSize.toLong()).coerceAtLeast(0L)
        val settlingTarget = (profile.sampleRateHz * 2).toLong()
        val settlingSamples = (settlingTarget - firstContinuousOffset)
            .coerceIn(0L, ringSize.toLong())
            .toInt()
        return LiveWaveformSnapshot(
            generation = generation,
            publicationSequence = publicationSequence,
            acceptedSampleCount = acceptedSampleCount,
            sourceSampleStartIndex = nextAcceptedSampleIndex - ringSize,
            sourceSampleEndIndex = nextAcceptedSampleIndex - 1L,
            measuredAt = measuredAt,
            red = copyRing(rawRed),
            ir = copyRing(rawIr),
            ecg = copyEcg(),
            causalRed = copyRing(causalRed),
            causalIr = copyRing(causalIr),
            preprocessProfile = profile.preprocessingProfile.identifier,
            displayCausalRed = copyRing(displayCausalRed),
            displayCausalIr = copyRing(displayCausalIr),
            displayCausalProfile = displayCausalRedFilter.profile.identifier,
            continuousSampleCount = continuousSampleCount,
            continuityEpoch = continuityEpoch,
            gapCount = gapCount,
            segmentBreakSampleIndices = segmentBreaks
                .filter { it >= nextAcceptedSampleIndex - ringSize && it <= nextAcceptedSampleIndex }
                .map { (it - (nextAcceptedSampleIndex - ringSize)).toInt() }
                .toIntArray(),
            metricWarmupSampleCount = profile.windowSampleCount,
            settlingSampleCount = settlingSamples,
            fixedLagRed = fixedLagSamples.map { it.red }.toDoubleArray(),
            fixedLagIr = fixedLagSamples.map { it.ir }.toDoubleArray(),
            fixedLagSourceSampleStartIndex = fixedLagSamples.firstOrNull()?.sourceSampleIndex,
            fixedLagSourceSampleEndIndex = fixedLagSamples.lastOrNull()?.sourceSampleIndex,
            fixedLagLatencySamples = fixedLagRuntime.latencySamples,
            fixedLagProfile = fixedLagRuntime.profile.identifier,
        )
    }

    private fun append(
        red: Double,
        ir: Double,
        filteredRed: Double,
        filteredIr: Double,
        displayFilteredRed: Double,
        displayFilteredIr: Double,
        time: Double,
    ) {
        val index = (ringStart + ringSize) % profile.windowSampleCount
        rawRed[index] = red
        rawIr[index] = ir
        causalRed[index] = filteredRed
        causalIr[index] = filteredIr
        displayCausalRed[index] = displayFilteredRed
        displayCausalIr[index] = displayFilteredIr
        timeSeconds[index] = time
        if (ringSize < profile.windowSampleCount) {
            ringSize++
        } else {
            ringStart = (ringStart + 1) % profile.windowSampleCount
        }
    }

    private fun copyRing(source: DoubleArray): DoubleArray {
        val result = DoubleArray(ringSize)
        for (index in 0 until ringSize) {
            result[index] = source[(ringStart + index) % profile.windowSampleCount]
        }
        return result
    }

    private fun invalidateContinuity(nextIndex: Long) {
        redPreprocessor.reset()
        irPreprocessor.reset()
        ringStart = 0
        ringSize = 0
        ecgRingStart = 0
        ecgRingSize = 0
        segmentBreaks.clear()
        nextPublishNanos = null
        displayCausalRedFilter.reset()
        displayCausalIrFilter.reset()
        fixedLagRuntime.reset(nextIndex)
        fixedLagSamples.clear()
        ecgDisplayDownsampler.reset()
        displayContinuousSampleCount = 0L
        nextAcceptedSampleIndex = nextIndex
        continuousSampleCount = 0L
        nextAnalysisContinuousSampleCount = profile.windowSampleCount.toLong()
        metricEpoch = 0L
        continuityEpoch++
        generation++
    }

    private fun markSegmentBreak(sourceSampleIndex: Long) {
        if (segmentBreaks.lastOrNull() != sourceSampleIndex) segmentBreaks.addLast(sourceSampleIndex)
        val minimumVisible = sourceSampleIndex - profile.windowSampleCount - 1L
        while (segmentBreaks.firstOrNull()?.let { it < minimumVisible } == true) {
            segmentBreaks.removeFirst()
        }
    }

    private fun copyEcg(): DoubleArray {
        val result = DoubleArray(ecgRingSize)
        for (index in 0 until ecgRingSize) {
            result[index] = displayEcg[(ecgRingStart + index) % profile.windowSampleCount]
        }
        return result
    }

}
