package com.example.ppgcollector_android.core.signal

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

data class OfflinePpgInput(
    val timeSeconds: DoubleArray,
    val red: DoubleArray,
    val ir: DoubleArray,
    val breakIndices: IntArray = intArrayOf(),
) {
    init {
        require(timeSeconds.size == red.size && red.size == ir.size) {
            "offline PPG arrays must have equal lengths"
        }
    }
}

data class OfflineFilteredSignal(
    val red: DoubleArray,
    val ir: DoubleArray,
)

data class OfflineSignalSegment(
    val index: Int,
    val startIndex: Int,
    val stopIndex: Int,
    val startSeconds: Double,
    val stopSeconds: Double,
) {
    val durationSeconds: Double get() = stopSeconds - startSeconds
}

data class OfflinePulseWindow(
    val segmentIndex: Int,
    val startIndex: Int,
    val stopIndex: Int,
    val startSeconds: Double,
    val stopSeconds: Double,
    val bestChannel: String,
    val usedChannel: String?,
    val redPeakBpm: Double?,
    val irPeakBpm: Double?,
    val peakBpm: Double?,
    val spectralBpm: Double?,
    val confidence: Double,
    val snrDb: Double?,
    val polarity: String?,
    val redAcDcPercent: Double,
    val irAcDcPercent: Double,
    val accepted: Boolean,
    val rejectionReason: String?,
)

data class OfflineSpectrum(
    val frequenciesHz: DoubleArray = doubleArrayOf(),
    val power: DoubleArray = doubleArrayOf(),
)

/** Bounded Welch-style spectrum used only by the interactive display range. */
object OfflineDisplaySpectrum {
    private const val maximumSegments = 32

    fun estimate(
        values: DoubleArray,
        visibleRange: IntRange,
        sampleRateHz: Double = OfflinePpgAnalyzer.sampleRateHz,
    ): OfflineSpectrum {
        if (visibleRange.isEmpty() || values.isEmpty() || sampleRateHz <= 0.0) {
            return OfflineSpectrum()
        }
        val start = visibleRange.first.coerceIn(0, values.lastIndex)
        val stop = (visibleRange.last + 1).coerceIn(start + 1, values.size)
        val count = stop - start
        val segmentLength = min(800, count)
        if (segmentLength < 32) return OfflineSpectrum()
        val hop = max(1, segmentLength / 2)
        val candidates = buildList {
            var offset = start
            while (offset + segmentLength <= stop) {
                add(offset)
                offset += hop
            }
            val finalStart = stop - segmentLength
            if (isEmpty() || last() != finalStart) add(finalStart)
        }
        val selectedStarts = if (candidates.size <= maximumSegments) candidates else {
            List(maximumSegments) { index ->
                candidates[(index * (candidates.lastIndex).toDouble() /
                    (maximumSegments - 1).toDouble()).toInt()]
            }.distinct()
        }
        val firstBin = max(1, kotlin.math.ceil(0.3 * segmentLength / sampleRateHz).toInt())
        val lastBin = min(segmentLength / 2, kotlin.math.floor(8.0 * segmentLength / sampleRateHz).toInt())
        if (lastBin < firstBin) return OfflineSpectrum()
        val power = DoubleArray(lastBin - firstBin + 1)
        var acceptedSegments = 0
        selectedStarts.forEach { segmentStart ->
            val segment = values.copyOfRange(segmentStart, segmentStart + segmentLength)
            if (segment.any { !it.isFinite() }) return@forEach
            val xMean = (segmentLength - 1) / 2.0
            val yMean = segment.average()
            var numerator = 0.0
            var denominator = 0.0
            segment.indices.forEach { index ->
                val centeredX = index - xMean
                numerator += centeredX * (segment[index] - yMean)
                denominator += centeredX * centeredX
            }
            val slope = if (denominator > 0.0) numerator / denominator else 0.0
            val detrended = DoubleArray(segmentLength) { index ->
                val trend = yMean + slope * (index - xMean)
                (segment[index] - trend) * (0.5 - 0.5 * cos(2.0 * PI * index / (segmentLength - 1)))
            }
            for (bin in firstBin..lastBin) {
                var real = 0.0
                var imaginary = 0.0
                detrended.indices.forEach { index ->
                    val angle = 2.0 * PI * bin * index / segmentLength
                    real += detrended[index] * cos(angle)
                    imaginary -= detrended[index] * sin(angle)
                }
                power[bin - firstBin] += (real * real + imaginary * imaginary) / segmentLength
            }
            acceptedSegments += 1
        }
        if (acceptedSegments == 0) return OfflineSpectrum()
        for (index in power.indices) power[index] /= acceptedSegments.toDouble()
        val frequencies = DoubleArray(power.size) { index ->
            (firstBin + index) * sampleRateHz / segmentLength
        }
        return OfflineSpectrum(frequencies, power)
    }
}

data class OfflineAverageCycle(
    val phase: DoubleArray = doubleArrayOf(),
    val mean: DoubleArray = doubleArrayOf(),
    val standardDeviation: DoubleArray = doubleArrayOf(),
    val ci95: DoubleArray = doubleArrayOf(),
    val cycleCount: Int = 0,
)

data class OfflinePpgPreview(
    val startSeconds: Double = 0.0,
    val timeSeconds: DoubleArray = doubleArrayOf(),
    val rawRed: DoubleArray = doubleArrayOf(),
    val rawIr: DoubleArray = doubleArrayOf(),
    val filteredRed: DoubleArray = doubleArrayOf(),
    val filteredIr: DoubleArray = doubleArrayOf(),
    val peakIndices: IntArray = intArrayOf(),
)

data class OfflinePpgAnalysis(
    val segments: List<OfflineSignalSegment>,
    val windows: List<OfflinePulseWindow>,
    val selectedChannel: String?,
    val selectedPolarity: String?,
    val peakIndices: IntArray,
    val bpm: Double?,
    val spectralBpm: Double?,
    val confidence: Double,
    val snrDb: Double?,
    val rrMadSeconds: Double?,
    val stableSampleRatio: Double,
    val rejectionCounts: Map<String, Int>,
    val spectrum: OfflineSpectrum,
    val averageCycle: OfflineAverageCycle,
    val preview: OfflinePpgPreview,
    val redBandpass: DoubleArray,
    val irBandpass: DoubleArray,
    val sampleSegmentIndex: IntArray,
)

/**
 * V1.1 offline profile derived from Python segmented-pulse-0.3.
 *
 * This is deliberately separate from the live causal runtime: each stable
 * segment uses a forward/backward fixed-SOS filter, then 8 s windows with a
 * 2 s hop are channel-scored and clustered before a session result is formed.
 */
object OfflinePpgAnalyzer {
    const val analysisProfile = "ppg-offline-segmented-0.1"
    const val algorithmVersion = "segmented-pulse-parity-0.3"
    const val preprocessProfile = "scipy-sosfiltfilt-parity-0.1"
    const val sampleRateHz = 100.0
    const val windowSeconds = 8.0
    const val hopSeconds = 2.0

    /**
     * Produces a display-only zero-phase trace for every complete continuity run.
     * Stable-segment guards still control analysis acceptance; they do not hide
     * the rest of the recorded signal from the workbench.
     */
    fun filterFullSignal(
        input: OfflinePpgInput,
        cancellationCheck: () -> Unit = {},
        profile: PpgPreprocessingProfile = PpgPreprocessingProfile.iosBaseline01,
    ): OfflineFilteredSignal {
        val count = input.timeSeconds.size
        val red = DoubleArray(count) { Double.NaN }
        val ir = DoubleArray(count) { Double.NaN }
        if (count == 0) return OfflineFilteredSignal(red, ir)
        val breaks = BooleanArray(count)
        input.breakIndices.filter { it in 1 until count }.forEach { breaks[it] = true }
        for (index in 1 until count) {
            val delta = input.timeSeconds[index] - input.timeSeconds[index - 1]
            if (!delta.isFinite() || delta <= 0.0 || delta > 0.015) breaks[index] = true
        }

        fun filterRun(start: Int, stop: Int) {
            if (stop - start < 32) return
            cancellationCheck()
            ZeroPhasePpgFilter.filter(
                PpgDisplayTransform.rawPeakUp(input.red.copyOfRange(start, stop)),
                profile,
            ).copyInto(red, start)
            cancellationCheck()
            ZeroPhasePpgFilter.filter(
                PpgDisplayTransform.rawPeakUp(input.ir.copyOfRange(start, stop)),
                profile,
            ).copyInto(ir, start)
        }

        var runStart = -1
        for (index in 0..count) {
            val valid = index < count &&
                input.timeSeconds[index].isFinite() &&
                input.red[index].isFinite() &&
                input.ir[index].isFinite()
            val boundary = index == count || !valid || (index < count && breaks[index])
            if (boundary && runStart >= 0) {
                filterRun(runStart, index)
                runStart = -1
            }
            if (valid && runStart < 0) runStart = index
        }
        return OfflineFilteredSignal(red, ir)
    }

    fun analyze(
        input: OfflinePpgInput,
        progress: (completedWindows: Int, totalWindows: Int) -> Unit = { _, _ -> },
        cancellationCheck: () -> Unit = {},
    ): OfflinePpgAnalysis {
        val count = input.timeSeconds.size
        if (count == 0) return emptyAnalysis()
        require(input.timeSeconds.all(Double::isFinite)) { "offline time axis must be finite" }

        val segments = stableSegments(input)
        val redBandpass = DoubleArray(count) { Double.NaN }
        val irBandpass = DoubleArray(count) { Double.NaN }
        val segmentIds = IntArray(count) { -1 }
        val windowSamples = (windowSeconds * sampleRateHz).toInt()
        val hopSamples = (hopSeconds * sampleRateHz).toInt()
        val startsBySegment = segments.associateWith { segment ->
            windowStarts(segment.startIndex, segment.stopIndex, windowSamples, hopSamples)
        }
        val totalWindows = startsBySegment.values.sumOf(List<Int>::size)
        val rawWindows = ArrayList<WindowWork>(totalWindows)

        segments.forEach { segment ->
            cancellationCheck()
            val redSlice = input.red.copyOfRange(segment.startIndex, segment.stopIndex)
            val irSlice = input.ir.copyOfRange(segment.startIndex, segment.stopIndex)
            val filteredRed = ZeroPhasePpgFilter.filter(redSlice)
            val filteredIr = ZeroPhasePpgFilter.filter(irSlice)
            filteredRed.copyInto(redBandpass, segment.startIndex)
            filteredIr.copyInto(irBandpass, segment.startIndex)
            for (index in segment.startIndex until segment.stopIndex) segmentIds[index] = segment.index

            startsBySegment.getValue(segment).forEach { start ->
                cancellationCheck()
                val stop = start + windowSamples
                val times = input.timeSeconds.copyOfRange(start, stop).asList()
                val localRed = redBandpass.copyOfRange(start, stop).asList()
                val localIr = irBandpass.copyOfRange(start, stop).asList()
                val redEstimate = HeartRateEstimator.estimate(localRed, times, sampleRateHz)
                val irEstimate = HeartRateEstimator.estimate(localIr, times, sampleRateHz)
                val bestChannel = if (redEstimate.confidence >= irEstimate.confidence) "RED" else "IR"
                rawWindows += WindowWork(
                    segmentIndex = segment.index,
                    startIndex = start,
                    stopIndex = stop,
                    redEstimate = redEstimate,
                    irEstimate = irEstimate,
                    bestChannel = bestChannel,
                    redAcDcPercent = acDcPercent(redBandpass, input.red, start, stop),
                    irAcDcPercent = acDcPercent(irBandpass, input.ir, start, stop),
                )
                progress(rawWindows.size, totalWindows)
            }
        }

        val channel = selectChannel(rawWindows)
        if (channel == null) {
            return assemble(
                input, segments, rawWindows, null, null, intArrayOf(), null, null,
                0.0, null, null, redBandpass, irBandpass, segmentIds,
            )
        }

        rawWindows.forEach { window ->
            window.usedChannel = channel
            window.rejectionReason = basicRejection(window, channel)
        }
        val candidates = rawWindows.indices.filter { rawWindows[it].rejectionReason == null }
        val cluster = dominantBpmCluster(rawWindows, candidates, channel)
        val clusterSet = cluster.indices.toSet()
        candidates.filterNot(clusterSet::contains).forEach {
            rawWindows[it].rejectionReason = "bpm_outlier"
        }
        cluster.indices.forEach { rawWindows[it].accepted = true }

        val accepted = cluster.indices.map(rawWindows::get)
        val centerBpm = cluster.centerBpm
        if (accepted.isEmpty() || centerBpm == null) {
            return assemble(
                input, segments, rawWindows, channel, null, intArrayOf(), null, null,
                0.0, null, null, redBandpass, irBandpass, segmentIds,
            )
        }

        val weights = accepted.map { max(it.estimate(channel).confidence, 1e-6) }
        val spectralPairs = accepted.mapNotNull { window ->
            window.estimate(channel).spectralBpm?.let { it to max(window.estimate(channel).confidence, 1e-6) }
        }
        val spectralBpm = weightedMedianOrNull(spectralPairs)
        val snrDb = weightedMedianOrNull(
            accepted.mapNotNull { window ->
                window.estimate(channel).snrDb.takeIf(Double::isFinite)?.let {
                    it to max(window.estimate(channel).confidence, 1e-6)
                }
            },
        )
        val rrMad = weightedMedianOrNull(
            accepted.mapNotNull { window ->
                window.estimate(channel).rrMadSeconds?.let {
                    it to max(window.estimate(channel).confidence, 1e-6)
                }
            },
        )
        val polarity = selectPolarity(accepted, channel)
        val selectedValues = if (channel == "RED") redBandpass else irBandpass
        val peaks = collectPeaks(
            accepted, polarity, selectedValues, segmentIds, centerBpm,
        )
        val bpmValues = accepted.mapNotNull { it.estimate(channel).peakBpm }
        val bpmMad = weightedMedian(
            bpmValues.mapIndexed { index, value -> abs(value - centerBpm) to weights[index] },
        )
        val weightedConfidence = accepted.indices.sumOf { index ->
            accepted[index].estimate(channel).confidence * weights[index]
        } / weights.sum()
        val evidence = min(1.0, accepted.size / 6.0)
        val stability = 1.0 / (1.0 + (bpmMad / 6.0) * (bpmMad / 6.0))
        val confidence = min(1.0, weightedConfidence * (0.75 + 0.25 * evidence) * stability)
        val bpm = centerBpm.takeIf { accepted.size >= 2 && confidence >= 0.25 }

        return assemble(
            input, segments, rawWindows, channel, polarity, peaks, bpm, spectralBpm,
            confidence, snrDb, rrMad, redBandpass, irBandpass, segmentIds,
        )
    }

    private fun assemble(
        input: OfflinePpgInput,
        segments: List<OfflineSignalSegment>,
        work: List<WindowWork>,
        channel: String?,
        polarity: HeartRatePolarity?,
        peaks: IntArray,
        bpm: Double?,
        spectralBpm: Double?,
        confidence: Double,
        snrDb: Double?,
        rrMad: Double?,
        redBandpass: DoubleArray,
        irBandpass: DoubleArray,
        segmentIds: IntArray,
    ): OfflinePpgAnalysis {
        val windows = work.map { window ->
            val estimate = window.usedChannel?.let(window::estimate)
            OfflinePulseWindow(
                segmentIndex = window.segmentIndex,
                startIndex = window.startIndex,
                stopIndex = window.stopIndex,
                startSeconds = input.timeSeconds[window.startIndex],
                stopSeconds = input.timeSeconds[window.stopIndex - 1],
                bestChannel = window.bestChannel,
                usedChannel = window.usedChannel,
                redPeakBpm = window.redEstimate.peakBpm,
                irPeakBpm = window.irEstimate.peakBpm,
                peakBpm = estimate?.peakBpm,
                spectralBpm = estimate?.spectralBpm,
                confidence = estimate?.confidence ?: 0.0,
                snrDb = estimate?.snrDb?.takeIf(Double::isFinite),
                polarity = estimate?.polarity?.wireValue,
                redAcDcPercent = window.redAcDcPercent,
                irAcDcPercent = window.irAcDcPercent,
                accepted = window.accepted,
                rejectionReason = window.rejectionReason,
            )
        }
        val selectedValues = when (channel) {
            "RED" -> redBandpass
            "IR" -> irBandpass
            else -> doubleArrayOf()
        }
        val bestWindow = work.filter(WindowWork::accepted).maxByOrNull {
            if (channel == null) 0.0 else it.estimate(channel).confidence
        }
        val spectrum = bestWindow?.let { window ->
            val trace = channel?.let(window::estimate)?.trace?.spectral
            OfflineSpectrum(
                frequenciesHz = trace?.frequenciesHz?.toDoubleArray() ?: doubleArrayOf(),
                power = trace?.power?.toDoubleArray() ?: doubleArrayOf(),
            )
        } ?: OfflineSpectrum()
        val cycle = if (selectedValues.isNotEmpty()) {
            averageCycle(selectedValues, peaks, segmentIds)
        } else {
            OfflineAverageCycle()
        }
        val preview = bestWindow?.let { window ->
            val start = window.startIndex
            val stop = window.stopIndex
            OfflinePpgPreview(
                startSeconds = input.timeSeconds[start],
                timeSeconds = input.timeSeconds.copyOfRange(start, stop),
                rawRed = input.red.copyOfRange(start, stop),
                rawIr = input.ir.copyOfRange(start, stop),
                filteredRed = redBandpass.copyOfRange(start, stop),
                filteredIr = irBandpass.copyOfRange(start, stop),
                peakIndices = peaks.filter { it in start until stop }.map { it - start }.toIntArray(),
            )
        } ?: OfflinePpgPreview()
        val stableCount = segmentIds.count { it >= 0 }
        val rejectionCounts = windows.filterNot(OfflinePulseWindow::accepted)
            .mapNotNull(OfflinePulseWindow::rejectionReason)
            .groupingBy(String::toString)
            .eachCount()
            .toSortedMap()
        return OfflinePpgAnalysis(
            segments = segments,
            windows = windows,
            selectedChannel = channel,
            selectedPolarity = polarity?.wireValue,
            peakIndices = peaks,
            bpm = bpm,
            spectralBpm = spectralBpm,
            confidence = confidence,
            snrDb = snrDb,
            rrMadSeconds = rrMad,
            stableSampleRatio = stableCount.toDouble() / max(1, input.timeSeconds.size),
            rejectionCounts = rejectionCounts,
            spectrum = spectrum,
            averageCycle = cycle,
            preview = preview,
            redBandpass = redBandpass,
            irBandpass = irBandpass,
            sampleSegmentIndex = segmentIds,
        )
    }

    private fun stableSegments(input: OfflinePpgInput): List<OfflineSignalSegment> {
        val count = input.timeSeconds.size
        val invalid = BooleanArray(count) { index ->
            !input.red[index].isFinite() || !input.ir[index].isFinite()
        }
        initialStableStart(input)?.let { stableStart ->
            input.timeSeconds.indices.filter { input.timeSeconds[it] < stableStart }
                .forEach { invalid[it] = true }
        }
        val transitions = linkedSetOf<Int>()
        input.breakIndices.filter { it in 1 until count }.forEach(transitions::add)
        for (index in 1 until count) {
            val redLevel = max(0.5 * (abs(input.red[index - 1]) + abs(input.red[index])), 1_000.0)
            val irLevel = max(0.5 * (abs(input.ir[index - 1]) + abs(input.ir[index])), 1_000.0)
            if (abs(input.red[index] - input.red[index - 1]) / redLevel > 0.02 ||
                abs(input.ir[index] - input.ir[index - 1]) / irLevel > 0.02
            ) transitions += index
            val delta = input.timeSeconds[index] - input.timeSeconds[index - 1]
            if (delta <= 0.0 || delta > 0.015) transitions += index
        }
        val smoothSamples = 25
        val smoothRed = movingAverage(input.red, smoothSamples)
        val smoothIr = movingAverage(input.ir, smoothSamples)
        for (index in smoothSamples until count) {
            val left = index - smoothSamples
            val redLevel = max(0.5 * (abs(smoothRed[left]) + abs(smoothRed[index])), 1_000.0)
            val irLevel = max(0.5 * (abs(smoothIr[left]) + abs(smoothIr[index])), 1_000.0)
            if (abs(smoothRed[index] - smoothRed[left]) / redLevel > 0.05 ||
                abs(smoothIr[index] - smoothIr[left]) / irLevel > 0.05
            ) transitions += left + smoothSamples / 2
        }
        transitions.forEach { transition ->
            val center = input.timeSeconds[transition]
            var left = transition
            while (left > 0 && input.timeSeconds[left - 1] >= center - 1.5) left--
            var right = transition
            while (right < count && input.timeSeconds[right] <= center + 1.5) right++
            for (index in left until right) invalid[index] = true
        }

        val redReference = max(percentile(input.red, 75.0), 1.0)
        val irReference = max(percentile(input.ir, 75.0), 1.0)
        val segments = ArrayList<OfflineSignalSegment>()
        var index = 0
        while (index < count) {
            while (index < count && invalid[index]) index++
            val start = index
            while (index < count && !invalid[index]) index++
            val stop = index
            if (stop - start < 2) continue
            val duration = input.timeSeconds[stop - 1] - input.timeSeconds[start]
            if (duration < windowSeconds) continue
            val contact = max(
                median(input.red.copyOfRange(start, stop).asList()) / redReference,
                median(input.ir.copyOfRange(start, stop).asList()) / irReference,
            )
            if (contact < 0.15) continue
            segments += OfflineSignalSegment(
                index = segments.size,
                startIndex = start,
                stopIndex = stop,
                startSeconds = input.timeSeconds[start],
                stopSeconds = input.timeSeconds[stop - 1],
            )
        }
        return segments
    }

    private fun initialStableStart(input: OfflinePpgInput): Double? {
        val count = input.timeSeconds.size
        if (count < 2) return null
        val startSeconds = input.timeSeconds.first()
        val stopSeconds = input.timeSeconds.last()
        if (stopSeconds <= startSeconds) return null
        val guardStart = min(stopSeconds, startSeconds + 2.0)
        if (stopSeconds - guardStart < windowSeconds) return guardStart
        val binSeconds = 0.5
        val binCount = ((stopSeconds - startSeconds) / binSeconds).toInt()
        if (binCount <= 0) return guardStart
        val binTimes = DoubleArray(binCount) { startSeconds + (it + 0.5) * binSeconds }
        val redMedians = DoubleArray(binCount) { Double.NaN }
        val irMedians = DoubleArray(binCount) { Double.NaN }
        var sampleStart = 0
        repeat(binCount) { bin ->
            val binStart = startSeconds + bin * binSeconds
            val binStop = binStart + binSeconds
            while (sampleStart < count && input.timeSeconds[sampleStart] < binStart) sampleStart++
            var sampleStop = sampleStart
            while (sampleStop < count && input.timeSeconds[sampleStop] < binStop) sampleStop++
            if (sampleStop - sampleStart >= 13) {
                redMedians[bin] = median(input.red.copyOfRange(sampleStart, sampleStop).asList())
                irMedians[bin] = median(input.ir.copyOfRange(sampleStart, sampleStop).asList())
            }
            sampleStart = sampleStop
        }
        val horizonBins = max(4, ceil(windowSeconds / binSeconds).toInt())
        val firstBin = binTimes.indexOfFirst { it >= guardStart }.let { if (it < 0) binCount else it }
        for (bin in firstBin..(binCount - horizonBins)) {
            val redLocal = redMedians.copyOfRange(bin, bin + horizonBins)
            val irLocal = irMedians.copyOfRange(bin, bin + horizonBins)
            if (redLocal.any { !it.isFinite() } || irLocal.any { !it.isFinite() }) continue
            val localTimes = binTimes.copyOfRange(bin, bin + horizonBins)
            val redLevel = max(median(redLocal.map(::abs)), 1_000.0)
            val irLevel = max(median(irLocal.map(::abs)), 1_000.0)
            val redSpread = (percentile(redLocal, 90.0) - percentile(redLocal, 10.0)) / redLevel
            val irSpread = (percentile(irLocal, 90.0) - percentile(irLocal, 10.0)) / irLevel
            val redTrend = abs(linearSlope(localTimes, redLocal)) * windowSeconds / redLevel
            val irTrend = abs(linearSlope(localTimes, irLocal)) * windowSeconds / irLevel
            if (redSpread <= 0.025 && irSpread <= 0.025 &&
                redTrend <= 0.015 && irTrend <= 0.015
            ) return binTimes[bin] - 0.25
        }
        return null
    }

    private fun selectChannel(windows: List<WindowWork>): String? = listOf("RED", "IR")
        .map { channel ->
            channel to windows.sumOf { window ->
                val estimate = window.estimate(channel)
                if (estimate.peakBpm != null && estimate.spectralBpm != null &&
                    abs(estimate.peakBpm - estimate.spectralBpm) <= 15.0
                ) estimate.confidence else 0.0
            }
        }
        .maxByOrNull(Pair<String, Double>::second)
        ?.takeIf { it.second > 0.0 }
        ?.first

    private fun basicRejection(window: WindowWork, channel: String): String? {
        val estimate = window.estimate(channel)
        if (estimate.peakBpm == null || estimate.polarity == null) return "no_regular_peaks"
        val spectral = estimate.spectralBpm ?: return "no_spectral_peak"
        if (abs(estimate.peakBpm - spectral) > 15.0) return "peak_spectral_disagreement"
        if (estimate.confidence < 0.20) return "low_confidence"
        if (window.acDc(channel) < 0.01) return "low_perfusion"
        return null
    }

    private fun dominantBpmCluster(
        windows: List<WindowWork>,
        candidates: List<Int>,
        channel: String,
    ): Cluster {
        if (candidates.isEmpty()) return Cluster(emptyList(), null)
        val values = candidates.map { windows[it].estimate(channel).peakBpm!! }
        val weights = candidates.map { max(windows[it].estimate(channel).confidence, 1e-6) }
        val seedIndex = values.indices.maxByOrNull { center ->
            values.indices.filter { abs(values[it] - values[center]) <= 10.0 }.sumOf { weights[it] }
        } ?: return Cluster(emptyList(), null)
        val initial = values.indices.filter { abs(values[it] - values[seedIndex]) <= 10.0 }
        val center = weightedMedian(initial.map { values[it] to weights[it] })
        val mad = weightedMedian(initial.map { abs(values[it] - center) to weights[it] })
        val tolerance = min(12.0, max(8.0, 3.0 * mad))
        val selectedPositions = values.indices.filter { abs(values[it] - center) <= tolerance }
        if (selectedPositions.isEmpty()) return Cluster(emptyList(), null)
        return Cluster(
            indices = selectedPositions.map(candidates::get),
            centerBpm = weightedMedian(selectedPositions.map { values[it] to weights[it] }),
        )
    }

    private fun selectPolarity(
        windows: List<WindowWork>,
        channel: String,
    ): HeartRatePolarity? = HeartRatePolarity.entries.maxByOrNull { polarity ->
        windows.filter { it.estimate(channel).polarity == polarity }
            .sumOf { it.estimate(channel).confidence }
    }?.takeIf { polarity -> windows.any { it.estimate(channel).polarity == polarity } }

    private fun collectPeaks(
        accepted: List<WindowWork>,
        polarity: HeartRatePolarity?,
        filtered: DoubleArray,
        segmentIds: IntArray,
        bpm: Double,
    ): IntArray {
        if (polarity == null) return intArrayOf()
        val acceptedMask = BooleanArray(filtered.size)
        accepted.forEach { window ->
            for (index in window.startIndex until window.stopIndex) acceptedMask[index] = true
        }
        val distance = max(1, (sampleRateHz * 0.65 * 60.0 / bpm).toInt())
        val sign = if (polarity == HeartRatePolarity.POSITIVE) 1.0 else -1.0
        val peaks = ArrayList<Int>()
        segmentIds.filter { it >= 0 }.distinct().forEach { segmentId ->
            val start = segmentIds.indexOfFirst { it == segmentId }
            val stop = segmentIds.indexOfLast { it == segmentId } + 1
            val values = filtered.copyOfRange(start, stop)
            val center = median(values.asList())
            val robustScale = median(values.map { abs(it - center) }) * 1.4826
            if (!robustScale.isFinite() || robustScale <= 0.0) return@forEach
            SciPyPeakDetector.findPeaks(
                values = values.map { sign * it },
                distance = distance,
                minimumProminence = max(1e-9, 0.5 * robustScale),
            ).indices.map { it + start }.filter(acceptedMask::get).forEach(peaks::add)
        }
        return peaks.distinct().sorted().toIntArray()
    }

    internal fun averageCycle(
        values: DoubleArray,
        peaks: IntArray,
        segmentIds: IntArray? = null,
        points: Int = 200,
    ): OfflineAverageCycle {
        if (peaks.size < 3) return OfflineAverageCycle()
        val intervals = (0 until peaks.lastIndex).map { index ->
            (peaks[index + 1] - peaks[index]).toDouble()
        }
        val medianInterval = median(intervals)
        if (medianInterval <= 2.0) return OfflineAverageCycle()
        val phase = DoubleArray(points) { it.toDouble() / (points - 1).toDouble() }
        val cycles = ArrayList<DoubleArray>()
        for (peakIndex in 0 until peaks.lastIndex) {
            val start = peaks[peakIndex]
            val stop = peaks[peakIndex + 1]
            if (start !in values.indices || stop !in values.indices || stop <= start) continue
            if (segmentIds != null && (segmentIds[start] < 0 || segmentIds[start] != segmentIds[stop])) {
                continue
            }
            val length = stop - start + 1
            if (length < 0.5 * medianInterval || length > 1.8 * medianInterval) continue
            val cycle = values.copyOfRange(start, stop + 1)
            if (cycle.any { !it.isFinite() }) continue
            cycles += interpolateCycle(cycle, points)
        }
        if (cycles.size < 2) return OfflineAverageCycle()
        val template = columnMean(cycles, points)
        if (populationStandardDeviation(template) < 1e-12) return OfflineAverageCycle()
        val correlations = cycles.map { pearson(it, template) }
        var keep = correlations.map { it >= 0.45 }
        if (keep.none { it } && cycles.size < 3) return OfflineAverageCycle()
        if (keep.count { it } < 2 && cycles.size >= 3) {
            val keepCount = max(2, kotlin.math.round(cycles.size / 2.0).toInt())
            val selected = correlations.indices.sortedBy { correlations[it] }.takeLast(keepCount).toSet()
            keep = correlations.indices.map(selected::contains)
        }
        val accepted = cycles.indices.filter { keep[it] }.map(cycles::get)
        if (accepted.isEmpty()) return OfflineAverageCycle()
        val mean = columnMean(accepted, points)
        val standardDeviation = DoubleArray(points)
        val ci95 = DoubleArray(points)
        if (accepted.size > 1) {
            for (point in 0 until points) {
                val variance = accepted.sumOf { cycle ->
                    val difference = cycle[point] - mean[point]
                    difference * difference
                } / (accepted.size - 1).toDouble()
                standardDeviation[point] = sqrt(variance)
                ci95[point] = 1.96 * standardDeviation[point] / sqrt(accepted.size.toDouble())
            }
        }
        return OfflineAverageCycle(phase, mean, standardDeviation, ci95, accepted.size)
    }

    private fun acDcPercent(filtered: DoubleArray, raw: DoubleArray, start: Int, stop: Int): Double {
        val length = stop - start
        if (length <= 0) return 0.0
        val edge = min(length / 10, max(0, length / 2 - 1))
        val selectedStart = start + edge
        val selectedStop = stop - edge
        val count = selectedStop - selectedStart
        if (count <= 0) return 0.0
        val rms = sqrt((selectedStart until selectedStop).sumOf { filtered[it] * filtered[it] } / count)
        val dc = abs((selectedStart until selectedStop).sumOf { raw[it] } / count)
        return 100.0 * rms / max(dc, 1.0)
    }

    private fun windowStarts(start: Int, stop: Int, window: Int, hop: Int): List<Int> {
        if (stop - start < window) return emptyList()
        val starts = ArrayList<Int>()
        var current = start
        while (current + window <= stop) {
            starts += current
            current += hop
        }
        val finalStart = stop - window
        if (starts.isEmpty() || finalStart - starts.last() >= hop / 2) starts += finalStart
        return starts
    }

    private fun movingAverage(values: DoubleArray, window: Int): DoubleArray {
        val result = DoubleArray(values.size)
        val radius = window / 2
        var sum = 0.0
        var left = 0
        var right = 0
        for (index in values.indices) {
            val requestedLeft = max(0, index - radius)
            val requestedRight = min(values.size, index + radius + 1)
            while (right < requestedRight) sum += values[right++]
            while (left < requestedLeft) sum -= values[left++]
            result[index] = sum / (right - left).toDouble()
        }
        return result
    }

    private fun linearSlope(x: DoubleArray, y: DoubleArray): Double {
        val meanX = x.average()
        val meanY = y.average()
        var numerator = 0.0
        var denominator = 0.0
        x.indices.forEach { index ->
            numerator += (x[index] - meanX) * (y[index] - meanY)
            denominator += (x[index] - meanX) * (x[index] - meanX)
        }
        return if (denominator > 0.0) numerator / denominator else 0.0
    }

    private fun percentile(values: DoubleArray, percentile: Double): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sortedArray()
        val position = percentile.coerceIn(0.0, 100.0) / 100.0 * (sorted.size - 1)
        val lower = position.toInt()
        val upper = ceil(position).toInt()
        if (lower == upper) return sorted[lower]
        val fraction = position - lower
        return sorted[lower] * (1.0 - fraction) + sorted[upper] * fraction
    }

    private fun weightedMedianOrNull(values: List<Pair<Double, Double>>): Double? =
        values.takeIf(List<Pair<Double, Double>>::isNotEmpty)?.let(::weightedMedian)

    private fun weightedMedian(values: List<Pair<Double, Double>>): Double {
        val sorted = values.sortedBy(Pair<Double, Double>::first)
        val threshold = sorted.sumOf(Pair<Double, Double>::second) * 0.5
        var cumulative = 0.0
        sorted.forEach { (value, weight) ->
            cumulative += weight
            if (cumulative >= threshold) return value
        }
        return sorted.last().first
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private fun interpolateCycle(values: DoubleArray, points: Int): DoubleArray =
        DoubleArray(points) { point ->
            val position = point.toDouble() / (points - 1).toDouble() * (values.size - 1)
            val left = position.toInt()
            val right = min(values.lastIndex, ceil(position).toInt())
            val fraction = position - left
            values[left] * (1.0 - fraction) + values[right] * fraction
        }

    private fun columnMean(values: List<DoubleArray>, points: Int): DoubleArray =
        DoubleArray(points) { point -> values.sumOf { it[point] } / values.size.toDouble() }

    private fun populationStandardDeviation(values: DoubleArray): Double {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size.toDouble())
    }

    private fun pearson(left: DoubleArray, right: DoubleArray): Double {
        if (populationStandardDeviation(left) < 1e-12) return -1.0
        val leftMean = left.average()
        val rightMean = right.average()
        var numerator = 0.0
        var leftSquares = 0.0
        var rightSquares = 0.0
        left.indices.forEach { index ->
            val leftValue = left[index] - leftMean
            val rightValue = right[index] - rightMean
            numerator += leftValue * rightValue
            leftSquares += leftValue * leftValue
            rightSquares += rightValue * rightValue
        }
        val denominator = sqrt(leftSquares * rightSquares)
        return if (denominator > 0.0) numerator / denominator else -1.0
    }

    private fun emptyAnalysis() = OfflinePpgAnalysis(
        emptyList(), emptyList(), null, null, intArrayOf(), null, null, 0.0,
        null, null, 0.0, emptyMap(), OfflineSpectrum(), OfflineAverageCycle(),
        OfflinePpgPreview(), doubleArrayOf(), doubleArrayOf(), intArrayOf(),
    )

    private data class WindowWork(
        val segmentIndex: Int,
        val startIndex: Int,
        val stopIndex: Int,
        val redEstimate: HeartRateEstimate,
        val irEstimate: HeartRateEstimate,
        val bestChannel: String,
        val redAcDcPercent: Double,
        val irAcDcPercent: Double,
        var usedChannel: String? = null,
        var accepted: Boolean = false,
        var rejectionReason: String? = null,
    ) {
        fun estimate(channel: String) = if (channel == "RED") redEstimate else irEstimate
        fun acDc(channel: String) = if (channel == "RED") redAcDcPercent else irAcDcPercent
    }

    private data class Cluster(val indices: List<Int>, val centerBpm: Double?)
}

/** Fixed SOS forward/backward filtering with SciPy-compatible odd padding. */
internal object ZeroPhasePpgFilter {
    fun filter(
        values: DoubleArray,
        profile: PpgPreprocessingProfile = PpgPreprocessingProfile.iosBaseline01,
    ): DoubleArray {
        if (values.size < 32) return DoubleArray(values.size)
        val sections = profile.sections
        val edge = min(values.size - 1, max(12, 3 * sections.size))
        val extended = oddExtension(values, edge)
        val forward = filterOneDirection(extended, sections)
        val backward = filterOneDirection(forward.reversedArray(), sections).reversedArray()
        return backward.copyOfRange(edge, edge + values.size)
    }

    private fun oddExtension(values: DoubleArray, edge: Int): DoubleArray {
        val result = DoubleArray(values.size + edge * 2)
        for (index in 0 until edge) {
            result[index] = 2.0 * values.first() - values[edge - index]
        }
        values.copyInto(result, edge)
        for (index in 0 until edge) {
            result[edge + values.size + index] =
                2.0 * values.last() - values[values.lastIndex - 1 - index]
        }
        return result
    }

    private fun filterOneDirection(
        values: DoubleArray,
        sections: List<PpgSecondOrderSection>,
    ): DoubleArray {
        if (values.isEmpty()) return values
        var scale = 1.0
        val states = sections.map { section ->
            val normalized = normalized(section)
            val denominator = 1.0 + normalized.a1 + normalized.a2
            val zi0 = (
                normalized.b1 + normalized.b2 -
                    (normalized.a1 + normalized.a2) * normalized.b0
                ) / denominator
            val zi1 = normalized.b2 - normalized.a2 * normalized.b0 - normalized.a2 * zi0
            SosState(
                normalized,
                delay1 = zi0 * scale * values.first(),
                delay2 = zi1 * scale * values.first(),
            ).also {
                scale *= (normalized.b0 + normalized.b1 + normalized.b2) / denominator
            }
        }
        return DoubleArray(values.size) { index ->
            var current = values[index]
            states.forEach { state -> current = state.process(current) }
            current
        }
    }

    private fun normalized(section: PpgSecondOrderSection) = PpgSecondOrderSection(
        section.b0 / section.a0,
        section.b1 / section.a0,
        section.b2 / section.a0,
        1.0,
        section.a1 / section.a0,
        section.a2 / section.a0,
    )

    private class SosState(
        private val section: PpgSecondOrderSection,
        private var delay1: Double,
        private var delay2: Double,
    ) {
        fun process(input: Double): Double {
            val output = section.b0 * input + delay1
            delay1 = section.b1 * input - section.a1 * output + delay2
            delay2 = section.b2 * input - section.a2 * output
            return output
        }
    }
}
