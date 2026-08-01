package com.example.ppgcollector_android.core.signal

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

enum class HeartRatePolarity(val wireValue: String) {
    POSITIVE("positive"),
    NEGATIVE("negative"),
}

enum class HeartRateUnavailableReason(val wireValue: String) {
    INVALID_CONFIGURATION("invalidConfiguration"),
    INPUT_LENGTH_MISMATCH("inputLengthMismatch"),
    INSUFFICIENT_SAMPLES("insufficientSamples"),
    INSUFFICIENT_WORK_WINDOW("insufficientWorkWindow"),
    NON_FINITE_INPUT("nonFiniteInput"),
    INSUFFICIENT_AMPLITUDE("insufficientAmplitude"),
    NO_PEAK_CANDIDATE("noPeakCandidate"),
    LOW_CONFIDENCE("lowConfidence"),
}

enum class HeartRateCandidateRejectionReason(val wireValue: String) {
    INSUFFICIENT_PEAKS("insufficientPeaks"),
    INSUFFICIENT_INTERVALS("insufficientIntervals"),
    INSUFFICIENT_LONGEST_RUN("insufficientLongestRun"),
}

data class HeartRateConfiguration(
    val algorithmVersion: String,
    val minBpm: Double,
    val maxBpm: Double,
    val minimumWindowSeconds: Double,
    val maximumWindowSeconds: Double,
    val minimumRobustScale: Double,
    val confidenceThreshold: Double,
    val welchNearPeakHz: Double,
) {
    companion object {
        val pythonBaseline01 = HeartRateConfiguration(
            algorithmVersion = "ppg-ios-hr-0.1",
            minBpm = 35.0,
            maxBpm = 200.0,
            minimumWindowSeconds = 4.0,
            maximumWindowSeconds = 8.0,
            minimumRobustScale = 1.0,
            confidenceThreshold = 0.35,
            welchNearPeakHz = 0.15,
        )
    }
}

data class HeartRateSpectralTrace(
    val segmentLength: Int,
    val frequenciesHz: List<Double>,
    val power: List<Double>,
    val cardiacIndices: List<Int>,
    val peakIndex: Int?,
    val nearPeakIndices: List<Int>,
    val spectralBpm: Double?,
    val snrDb: Double,
    val concentration: Double,
)

data class HeartRateCandidateTrace(
    val polarity: HeartRatePolarity,
    val distanceSamples: Int,
    val detectedLocalPeakIndices: List<Int>,
    val detectedGlobalPeakIndices: List<Int>,
    val prominences: List<Double>,
    val intervalsSeconds: List<Double>,
    val inRangeMask: List<Boolean>,
    val spectralMatchMask: List<Boolean>,
    val spectralFilterApplied: Boolean,
    val validMask: List<Boolean>,
    val longestRunLocalPeakIndices: List<Int>,
    val longestRunGlobalPeakIndices: List<Int>,
    val cleanedIntervalsSeconds: List<Double>,
    val medianRrSeconds: Double?,
    val rrMadSeconds: Double?,
    val peakBpm: Double?,
    val score: Double?,
    val rejectionReason: HeartRateCandidateRejectionReason?,
)

data class HeartRateDebugTrace(
    val windowOffset: Int,
    val edgeTrimCount: Int,
    val workMedian: Double?,
    val robustScale: Double?,
    val workCentered: List<Double>,
    val spectral: HeartRateSpectralTrace?,
    val candidates: List<HeartRateCandidateTrace>,
)

data class HeartRateEstimate(
    val bpm: Double?,
    val peakBpm: Double?,
    val spectralBpm: Double?,
    val confidence: Double,
    val snrDb: Double,
    val polarity: HeartRatePolarity?,
    val peakIndices: List<Int>,
    val rrMadSeconds: Double?,
    val unavailableReason: HeartRateUnavailableReason?,
    val algorithmVersion: String,
    val trace: HeartRateDebugTrace,
) {
    val isValid: Boolean
        get() = bpm != null && unavailableReason == null
}

object HeartRateEstimator {
    fun estimate(
        values: List<Double>,
        timeSeconds: List<Double>,
        sampleRateHz: Double,
        configuration: HeartRateConfiguration = HeartRateConfiguration.pythonBaseline01,
    ): HeartRateEstimate {
        if (!configurationIsValid(configuration, sampleRateHz)) {
            return empty(HeartRateUnavailableReason.INVALID_CONFIGURATION, configuration)
        }
        if (values.size != timeSeconds.size) {
            return empty(HeartRateUnavailableReason.INPUT_LENGTH_MISMATCH, configuration)
        }
        if (values.any { !it.isFinite() } || timeSeconds.any { !it.isFinite() }) {
            return empty(HeartRateUnavailableReason.NON_FINITE_INPUT, configuration)
        }

        val minimumCount = maxOf(32, (sampleRateHz * configuration.minimumWindowSeconds).toInt())
        if (values.size < minimumCount) {
            return empty(HeartRateUnavailableReason.INSUFFICIENT_SAMPLES, configuration)
        }
        val maximumCount = maxOf(minimumCount, (sampleRateHz * configuration.maximumWindowSeconds).toInt())
        val windowOffset = maxOf(0, values.size - maximumCount)
        val windowValues = values.takeLast(maximumCount)
        val windowTimes = timeSeconds.takeLast(maximumCount)
        val edge = minOf(sampleRateHz.toInt(), maxOf(0, windowValues.size / 10))
        val stop = windowValues.size - edge
        if (stop - edge < 16) {
            return empty(
                HeartRateUnavailableReason.INSUFFICIENT_WORK_WINDOW,
                configuration,
                windowOffset,
                edge,
            )
        }

        val workInput = windowValues.subList(edge, stop)
        val workTime = windowTimes.subList(edge, stop)
        val workMedian = median(workInput)
        val work = workInput.map { it - workMedian }
        val robustScale = median(work.map(::abs)) * 1.4826
        val baseTrace = HeartRateDebugTrace(
            windowOffset = windowOffset,
            edgeTrimCount = edge,
            workMedian = workMedian,
            robustScale = robustScale,
            workCentered = work,
            spectral = null,
            candidates = emptyList(),
        )
        if (!robustScale.isFinite() || robustScale < configuration.minimumRobustScale) {
            return empty(
                HeartRateUnavailableReason.INSUFFICIENT_AMPLITUDE,
                configuration,
                trace = baseTrace,
            )
        }

        val spectral = spectralTrace(work, sampleRateHz, configuration)
        val positive = candidateTrace(
            values = work,
            timeSeconds = workTime,
            polarity = HeartRatePolarity.POSITIVE,
            sign = 1.0,
            edge = edge,
            windowOffset = windowOffset,
            robustScale = robustScale,
            sampleRateHz = sampleRateHz,
            spectral = spectral,
            configuration = configuration,
        )
        val negative = candidateTrace(
            values = work,
            timeSeconds = workTime,
            polarity = HeartRatePolarity.NEGATIVE,
            sign = -1.0,
            edge = edge,
            windowOffset = windowOffset,
            robustScale = robustScale,
            sampleRateHz = sampleRateHz,
            spectral = spectral,
            configuration = configuration,
        )
        val trace = HeartRateDebugTrace(
            windowOffset = windowOffset,
            edgeTrimCount = edge,
            workMedian = workMedian,
            robustScale = robustScale,
            workCentered = work,
            spectral = spectral,
            candidates = listOf(positive, negative),
        )

        var selected: HeartRateCandidateTrace? = null
        listOf(positive, negative).filter { it.score != null }.forEach { candidate ->
            val current = selected
            if (current == null || (candidate.score ?: Double.NEGATIVE_INFINITY) >
                (current.score ?: Double.NEGATIVE_INFINITY)
            ) {
                selected = candidate
            }
        }
        val chosen = selected ?: return HeartRateEstimate(
            bpm = null,
            peakBpm = null,
            spectralBpm = spectral.spectralBpm,
            confidence = 0.0,
            snrDb = spectral.snrDb,
            polarity = null,
            peakIndices = emptyList(),
            rrMadSeconds = null,
            unavailableReason = HeartRateUnavailableReason.NO_PEAK_CANDIDATE,
            algorithmVersion = configuration.algorithmVersion,
            trace = trace,
        )
        val confidence = min(1.0, chosen.score ?: 0.0)
        val isConfident = confidence >= configuration.confidenceThreshold
        return HeartRateEstimate(
            bpm = if (isConfident) chosen.peakBpm else null,
            peakBpm = chosen.peakBpm,
            spectralBpm = spectral.spectralBpm,
            confidence = confidence,
            snrDb = spectral.snrDb,
            polarity = chosen.polarity,
            peakIndices = chosen.longestRunGlobalPeakIndices,
            rrMadSeconds = chosen.rrMadSeconds,
            unavailableReason = if (isConfident) null else HeartRateUnavailableReason.LOW_CONFIDENCE,
            algorithmVersion = configuration.algorithmVersion,
            trace = trace,
        )
    }

    fun acceptedBpm(
        estimate: HeartRateEstimate,
        configuration: HeartRateConfiguration = HeartRateConfiguration.pythonBaseline01,
    ): Double? {
        if (estimate.bpm != null && estimate.isValid) return estimate.bpm
        val peak = estimate.peakBpm ?: return null
        val spectral = estimate.spectralBpm ?: return null
        if (!peak.isFinite() || !spectral.isFinite() ||
            peak < configuration.minBpm || peak > configuration.maxBpm ||
            abs(peak - spectral) > 8.0 || estimate.confidence < 0.25
        ) return null
        return peak
    }

    private fun configurationIsValid(
        configuration: HeartRateConfiguration,
        sampleRateHz: Double,
    ): Boolean = sampleRateHz.isFinite() && sampleRateHz > 0.0 &&
        configuration.minBpm.isFinite() && configuration.maxBpm.isFinite() &&
        configuration.minBpm > 0.0 && configuration.maxBpm > configuration.minBpm &&
        configuration.minimumWindowSeconds.isFinite() &&
        configuration.maximumWindowSeconds.isFinite() &&
        configuration.minimumWindowSeconds > 0.0 &&
        configuration.maximumWindowSeconds >= configuration.minimumWindowSeconds &&
        configuration.minimumRobustScale.isFinite() && configuration.minimumRobustScale >= 0.0 &&
        configuration.confidenceThreshold.isFinite() &&
        configuration.confidenceThreshold in 0.0..1.0 &&
        configuration.welchNearPeakHz.isFinite() && configuration.welchNearPeakHz >= 0.0

    private fun spectralTrace(
        values: List<Double>,
        sampleRateHz: Double,
        configuration: HeartRateConfiguration,
    ): HeartRateSpectralTrace {
        val segmentLength = minOf(
            values.size,
            maxOf(64, (sampleRateHz * configuration.maximumWindowSeconds).toInt()),
        )
        val segment = values.take(segmentLength)
        val detrended = linearDetrend(segment)
        val window = (0 until segmentLength).map { index ->
            0.5 - 0.5 * cos(2.0 * Math.PI * index.toDouble() / segmentLength.toDouble())
        }
        val scaleDenominator = sampleRateHz * window.sumOf { it * it }
        val lastBin = segmentLength / 2
        val frequencies = ArrayList<Double>(lastBin + 1)
        val power = ArrayList<Double>(lastBin + 1)
        for (bin in 0..lastBin) {
            var real = 0.0
            var imaginary = 0.0
            for (index in 0 until segmentLength) {
                val angle = 2.0 * Math.PI * (bin * index).toDouble() / segmentLength.toDouble()
                val windowed = detrended[index] * window[index]
                real += windowed * cos(angle)
                imaginary -= windowed * kotlin.math.sin(angle)
            }
            var density = (real * real + imaginary * imaginary) / scaleDenominator
            val isNyquist = segmentLength % 2 == 0 && bin == lastBin
            if (bin != 0 && !isNyquist) density *= 2.0
            frequencies += bin.toDouble() * sampleRateHz / segmentLength.toDouble()
            power += density
        }
        val cardiacIndices = frequencies.indices.filter {
            frequencies[it] >= configuration.minBpm / 60.0 &&
                frequencies[it] <= configuration.maxBpm / 60.0
        }
        val totalPower = cardiacIndices.sumOf { power[it] }
        if (cardiacIndices.isEmpty() || totalPower <= 0.0) {
            return HeartRateSpectralTrace(
                segmentLength, frequencies, power, cardiacIndices, null, emptyList(),
                null, Double.NEGATIVE_INFINITY, 0.0,
            )
        }
        var peakIndex = cardiacIndices.first()
        cardiacIndices.drop(1).forEach { index ->
            if (power[index] > power[peakIndex]) peakIndex = index
        }
        val peakFrequency = frequencies[peakIndex]
        val nearPeakIndices = cardiacIndices.filter {
            abs(frequencies[it] - peakFrequency) <= configuration.welchNearPeakHz
        }
        val signalPower = nearPeakIndices.sumOf { power[it] }
        val noisePower = max(totalPower - signalPower, 1e-12)
        val concentration = signalPower / max(signalPower + noisePower, 1e-12)
        val snrDb = 10.0 * log10(max(signalPower, 1e-12) / noisePower)
        return HeartRateSpectralTrace(
            segmentLength, frequencies, power, cardiacIndices, peakIndex,
            nearPeakIndices, peakFrequency * 60.0, snrDb, concentration,
        )
    }

    private fun candidateTrace(
        values: List<Double>,
        timeSeconds: List<Double>,
        polarity: HeartRatePolarity,
        sign: Double,
        edge: Int,
        windowOffset: Int,
        robustScale: Double,
        sampleRateHz: Double,
        spectral: HeartRateSpectralTrace,
        configuration: HeartRateConfiguration,
    ): HeartRateCandidateTrace {
        val minIntervalSeconds = 60.0 / configuration.maxBpm
        val maxIntervalSeconds = 60.0 / configuration.minBpm
        var guidedIntervalSeconds = minIntervalSeconds
        spectral.spectralBpm?.let { spectralBpm ->
            guidedIntervalSeconds = max(minIntervalSeconds, min(0.5, 0.55 * 60.0 / spectralBpm))
        }
        val distanceSamples = maxOf(1, (sampleRateHz * guidedIntervalSeconds).toInt())
        val transformed = values.map { sign * it }
        val detected = SciPyPeakDetector.findPeaks(
            values = transformed,
            distance = distanceSamples,
            minimumProminence = robustScale,
        )
        val peaks = detected.indices
        val intervals = peaks.zipWithNext().map { (left, right) ->
            timeSeconds[right] - timeSeconds[left]
        }
        var inRangeMask = intervals.map {
            it >= minIntervalSeconds && it <= maxIntervalSeconds
        }
        var spectralMatchMask = List(intervals.size) { true }
        var spectralFilterApplied = false
        spectral.spectralBpm?.let { spectralBpm ->
            val spectralRr = 60.0 / spectralBpm
            val tolerance = max(0.12, 0.22 * spectralRr)
            spectralMatchMask = intervals.map { abs(it - spectralRr) <= tolerance }
            val matches = inRangeMask.indices.count { inRangeMask[it] && spectralMatchMask[it] }
            if (matches >= 2) {
                inRangeMask = inRangeMask.indices.map { inRangeMask[it] && spectralMatchMask[it] }
                spectralFilterApplied = true
            }
        }
        val detectedGlobal = peaks.map { it + edge + windowOffset }
        val emptyValidMask = List(intervals.size) { false }
        if (peaks.size < 3) {
            return rejectedCandidate(
                polarity, distanceSamples, peaks, detectedGlobal, detected.peaks.map { it.prominence },
                intervals, inRangeMask, spectralMatchMask, spectralFilterApplied, emptyValidMask,
                HeartRateCandidateRejectionReason.INSUFFICIENT_PEAKS,
            )
        }
        val validIntervals = intervals.indices.filter { inRangeMask[it] }.map { intervals[it] }
        if (validIntervals.size < 2) {
            return rejectedCandidate(
                polarity, distanceSamples, peaks, detectedGlobal, detected.peaks.map { it.prominence },
                intervals, inRangeMask, spectralMatchMask, spectralFilterApplied, emptyValidMask,
                HeartRateCandidateRejectionReason.INSUFFICIENT_INTERVALS,
            )
        }
        val initialMedian = median(validIntervals)
        val initialMad = median(validIntervals.map { abs(it - initialMedian) })
        val madFloor = max(1.0 / sampleRateHz, initialMad)
        val validMask = intervals.indices.map {
            inRangeMask[it] && abs(intervals[it] - initialMedian) <= 3.0 * madFloor
        }
        val run = longestValidRun(peaks, intervals, validMask)
        val runGlobal = run.peaks.map { it + edge + windowOffset }
        if (run.intervals.size < 2) {
            return HeartRateCandidateTrace(
                polarity, distanceSamples, peaks, detectedGlobal, detected.peaks.map { it.prominence },
                intervals, inRangeMask, spectralMatchMask, spectralFilterApplied, validMask,
                run.peaks, runGlobal, run.intervals, null, null, null, null,
                HeartRateCandidateRejectionReason.INSUFFICIENT_LONGEST_RUN,
            )
        }
        val medianRr = median(run.intervals)
        val rrMad = median(run.intervals.map { abs(it - medianRr) })
        val peakBpm = 60.0 / medianRr
        val regularity = 1.0 / (1.0 + 8.0 * rrMad / max(medianRr, 1e-12))
        val coverage = min(1.0, run.intervals.size.toDouble() / 6.0)
        val agreement = spectral.spectralBpm?.let { max(0.0, 1.0 - abs(peakBpm - it) / 15.0) } ?: 0.0
        val score = coverage * regularity * agreement * (0.5 + 0.5 * spectral.concentration)
        return HeartRateCandidateTrace(
            polarity, distanceSamples, peaks, detectedGlobal, detected.peaks.map { it.prominence },
            intervals, inRangeMask, spectralMatchMask, spectralFilterApplied, validMask,
            run.peaks, runGlobal, run.intervals, medianRr, rrMad, peakBpm, score, null,
        )
    }

    private fun rejectedCandidate(
        polarity: HeartRatePolarity,
        distanceSamples: Int,
        peaks: List<Int>,
        detectedGlobal: List<Int>,
        prominences: List<Double>,
        intervals: List<Double>,
        inRangeMask: List<Boolean>,
        spectralMatchMask: List<Boolean>,
        spectralFilterApplied: Boolean,
        validMask: List<Boolean>,
        reason: HeartRateCandidateRejectionReason,
    ) = HeartRateCandidateTrace(
        polarity, distanceSamples, peaks, detectedGlobal, prominences, intervals,
        inRangeMask, spectralMatchMask, spectralFilterApplied, validMask,
        emptyList(), emptyList(), emptyList(), null, null, null, null, reason,
    )

    private fun linearDetrend(values: List<Double>): List<Double> {
        if (values.size <= 1) return values.map { 0.0 }
        val count = values.size.toDouble()
        val meanX = (count + 1.0) / (2.0 * count)
        val meanY = values.sum() / count
        var covariance = 0.0
        var varianceX = 0.0
        values.forEachIndexed { index, value ->
            val x = (index + 1).toDouble() / count
            covariance += (x - meanX) * (value - meanY)
            varianceX += (x - meanX) * (x - meanX)
        }
        val slope = covariance / varianceX
        val intercept = meanY - slope * meanX
        return values.mapIndexed { index, value ->
            val x = (index + 1).toDouble() / count
            value - (slope * x + intercept)
        }
    }

    private fun longestValidRun(
        peaks: List<Int>,
        intervals: List<Double>,
        validMask: List<Boolean>,
    ): Run {
        var bestStart = 0
        var bestLength = 0
        var currentStart = 0
        var currentLength = 0
        validMask.forEachIndexed { index, valid ->
            if (valid) {
                if (currentLength == 0) currentStart = index
                currentLength += 1
                if (currentLength > bestLength) {
                    bestStart = currentStart
                    bestLength = currentLength
                }
            } else {
                currentLength = 0
            }
        }
        if (bestLength == 0) return Run(emptyList(), emptyList())
        return Run(
            peaks.subList(bestStart, bestStart + bestLength + 1),
            intervals.subList(bestStart, bestStart + bestLength),
        )
    }

    private fun median(values: List<Double>): Double {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else sorted[middle]
    }

    private fun empty(
        reason: HeartRateUnavailableReason,
        configuration: HeartRateConfiguration,
        windowOffset: Int = 0,
        edgeTrimCount: Int = 0,
        trace: HeartRateDebugTrace? = null,
    ) = HeartRateEstimate(
        bpm = null,
        peakBpm = null,
        spectralBpm = null,
        confidence = 0.0,
        snrDb = Double.NEGATIVE_INFINITY,
        polarity = null,
        peakIndices = emptyList(),
        rrMadSeconds = null,
        unavailableReason = reason,
        algorithmVersion = configuration.algorithmVersion,
        trace = trace ?: HeartRateDebugTrace(
            windowOffset, edgeTrimCount, null, null, emptyList(), null, emptyList(),
        ),
    )

    private data class Run(val peaks: List<Int>, val intervals: List<Double>)
}
