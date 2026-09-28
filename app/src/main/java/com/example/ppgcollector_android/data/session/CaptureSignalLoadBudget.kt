package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.protocol.CupStreamProtocolMode
import java.nio.file.Files
import java.nio.file.Path

/** Conservative peak estimate includes input builders, filters and automatic analysis together. */
data class CaptureSignalLoadBudget(
    val availableHeapBytes: Long,
    val workingBytes: Long,
    val estimatedBytesPerSample: Long = 1024,
    val maximumPreviewBuckets: Int = 1024,
) {
    val maximumFullSamples: Long get() = (workingBytes / estimatedBytesPerSample).coerceAtMost(1_500_000)
    fun permits(samples: Long): Boolean = samples >= 0 && samples <= maximumFullSamples

    companion object {
        fun runtime(): CaptureSignalLoadBudget {
            val runtime = Runtime.getRuntime()
            val available = (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()).coerceAtLeast(0)
            val reserve = maxOf(16L * 1024 * 1024, runtime.maxMemory() / 4)
            return CaptureSignalLoadBudget(available, minOf(128L * 1024 * 1024,
                ((available - reserve).coerceAtLeast(0)) / 2))
        }
    }
}

class CaptureSignalBudgetException(message: String) : IllegalStateException(message)

data class CaptureSignalSourceIdentity(val sessionId: String?, val rawSha256: String, val rawBytes: Long)
enum class CaptureSignalStage { RAW, ZERO, FIXED, METRICS }
enum class CaptureSignalStageState { LOADING, READY, UNAVAILABLE_BUDGET, FAILED }
data class CaptureSignalStageStatus(val state: CaptureSignalStageState, val detail: String? = null)
enum class CaptureTimelineMetric { HEART_RATE, SQI, RATIO, PERFUSION_INDEX }
data class CaptureMetricAvailability(
    val source: CaptureMetricTimelineSource,
    val cadenceSamples: Long?,
    val unavailableSourceIndices: LongArray,
    val unavailableReason: String? = null,
)

internal data class CaptureRawPreview(
    val points: List<CupReplaySample>,
    val gapSourceIndices: LongArray,
    val gapMarkersTruncated: Boolean,
    val replay: CupRawReplayReport,
)

/** First/last and both channels' extrema per bucket, with absolute accepted-source cursors. */
internal fun loadBoundedRawPreview(
    path: Path,
    protocolMode: CupStreamProtocolMode,
    budget: CaptureSignalLoadBudget,
    cancellationCheck: () -> Unit,
): CaptureRawPreview {
    require(budget.maximumPreviewBuckets in 1..4096)
    // Every supported frame uses at least 8 bytes per accepted PPG sample.
    val upperSampleBound = Files.size(path) / 8 + 1
    val width = maxOf(1L, (upperSampleBound + budget.maximumPreviewBuckets - 1) / budget.maximumPreviewBuckets)
    val points = ArrayList<CupReplaySample>()
    val gaps = ArrayList<Long>()
    var truncated = false
    var first: CupReplaySample? = null
    var last: CupReplaySample? = null
    var minRed: CupReplaySample? = null
    var maxRed: CupReplaySample? = null
    var minIr: CupReplaySample? = null
    var maxIr: CupReplaySample? = null
    fun flush() {
        points += listOfNotNull(first, last, minRed, maxRed, minIr, maxIr)
            .distinctBy { it.sampleIndex }.sortedBy { it.sampleIndex }
        first = null; last = null; minRed = null; maxRed = null; minIr = null; maxIr = null
    }
    val replay = CupRawReplayEngine.replay(path, protocolMode, cancellationCheck) { sample ->
        if (sample.sampleInFrame == 0) cancellationCheck()
        if (first != null && sample.sampleIndex / width != first!!.sampleIndex / width) flush()
        if (first == null) first = sample
        last = sample
        if (minRed == null || sample.sample.red < minRed!!.sample.red) minRed = sample
        if (maxRed == null || sample.sample.red > maxRed!!.sample.red) maxRed = sample
        if (minIr == null || sample.sample.ir < minIr!!.sample.ir) minIr = sample
        if (maxIr == null || sample.sample.ir > maxIr!!.sample.ir) maxIr = sample
        if (sample.missingFramesBefore > 0) {
            if (gaps.size < 4096) gaps += sample.sampleIndex else truncated = true
        }
    }
    flush()
    check(points.size <= budget.maximumPreviewBuckets * 6 + 6) { "preview budget exceeded" }
    return CaptureRawPreview(points, gaps.toLongArray(), truncated, replay)
}
