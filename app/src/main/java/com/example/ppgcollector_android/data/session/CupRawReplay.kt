package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.protocol.CupBatchFrame
import com.example.ppgcollector_android.core.protocol.CupBatchStreamDecoder
import com.example.ppgcollector_android.core.protocol.CupFrameSequenceTracker
import com.example.ppgcollector_android.core.protocol.CupPpgSample
import com.example.ppgcollector_android.core.protocol.CupSequenceEvent
import java.nio.file.Path

data class CupReplaySample(
    val sampleIndex: Long,
    val hostMonotonicNanoseconds: ULong,
    val frameSequence: UByte,
    val sampleInFrame: Int,
    val samplesPerFrame: Int,
    val sample: CupPpgSample,
)

data class CupRawReplayReport(
    val peakRawRecordBufferBytes: Int,
    val rawRecordCount: Long,
    val rawPayloadBytes: Long,
    val validRawBytes: Long,
    val totalRawBytes: Long,
    val tailIssue: CupRawTailIssue?,
    val decodedFrames: Int,
    val acceptedFrames: Int,
    val acceptedSamples: Long,
    val missingFrames: Int,
    val duplicateFrames: Int,
    val outOfOrderFrames: Int,
    val structurallyInvalidFrames: Int,
    val discardedBytes: Int,
    val pendingDecoderBytes: Int,
    val firstFrameHostNanoseconds: ULong?,
    val lastFrameHostNanoseconds: ULong?,
    val recentSamples: List<CupReplaySample>,
    /** Bytes before the first decoded frame when recording began mid-frame. */
    val leadingAlignmentBytes: Int = 0,
) {
    val trailingRawBytes: Long
        get() = maxOf(0L, totalRawBytes - validRawBytes)

    val hostDurationSeconds: Double?
        get() {
            val first = firstFrameHostNanoseconds ?: return null
            val last = lastFrameHostNanoseconds ?: return null
            if (last < first) return null
            return (last - first).toDouble() / 1_000_000_000.0
        }

    val isStructurallyClean: Boolean
        get() = tailIssue == null &&
            structurallyInvalidFrames == 0 &&
            structuralDiscardedBytes == 0

    val structuralDiscardedBytes: Int
        get() = maxOf(0, discardedBytes - leadingAlignmentBytes)
}

object CupRawReplayEngine {
    const val recentSampleCapacity = 800

    fun replay(path: Path): CupRawReplayReport = replay(path) {}

    /** Streams every accepted sample while retaining the same bounded replay report. */
    fun replay(
        path: Path,
        onAcceptedSample: (CupReplaySample) -> Unit,
    ): CupRawReplayReport {
        val accumulator = ReplayAccumulator(onAcceptedSample)
        val summary = CupRawReader.scan(path) { record ->
            accumulator.receive(record)
        }
        return accumulator.report(summary)
    }

    fun replay(bytes: ByteArray): CupRawReplayReport {
        val accumulator = ReplayAccumulator()
        val summary = CupRawReader.scan(bytes) { record ->
            accumulator.receive(record)
        }
        return accumulator.report(summary)
    }

    private class ReplayAccumulator(
        private val onAcceptedSample: (CupReplaySample) -> Unit = {},
    ) {
        private val decoder = CupBatchStreamDecoder()
        private val sequenceTracker = CupFrameSequenceTracker()
        private val recentSamples = ArrayDeque<CupReplaySample>(recentSampleCapacity)
        private var rawPayloadBytes = 0L
        private var acceptedFrames = 0
        private var acceptedSamples = 0L
        private var firstFrameHostNanoseconds: ULong? = null
        private var lastFrameHostNanoseconds: ULong? = null
        private var hasDecodedFrame = false
        private var leadingAlignmentBytes = 0

        fun receive(record: CupRawRecord) {
            rawPayloadBytes += record.chunk.size.toLong()
            val frames = decoder.feed(record.chunk)
            if (!hasDecodedFrame && frames.isNotEmpty()) {
                // Raw-first capture can attach while the shared live decoder is already
                // inside a frame. Replay has no earlier context, so this prefix is an
                // auditable alignment condition rather than post-alignment corruption.
                leadingAlignmentBytes = decoder.stats.bytesDiscarded
                hasDecodedFrame = true
            }
            frames.forEach { frame ->
                when (sequenceTracker.observe(frame.sequence, frame.samples.size)) {
                    CupSequenceEvent.Duplicate,
                    CupSequenceEvent.OutOfOrder -> Unit
                    CupSequenceEvent.First,
                    CupSequenceEvent.Continuous,
                    is CupSequenceEvent.Gap -> acceptFrame(frame, record)
                }
            }
        }

        private fun acceptFrame(frame: CupBatchFrame, record: CupRawRecord) {
            acceptedFrames += 1
            if (firstFrameHostNanoseconds == null) {
                firstFrameHostNanoseconds = record.hostMonotonicNanoseconds
            }
            lastFrameHostNanoseconds = record.hostMonotonicNanoseconds
            frame.samples.forEachIndexed { sampleInFrame, sample ->
                val replaySample = CupReplaySample(
                    sampleIndex = acceptedSamples,
                    hostMonotonicNanoseconds = record.hostMonotonicNanoseconds,
                    frameSequence = frame.sequence,
                    sampleInFrame = sampleInFrame,
                    samplesPerFrame = frame.samples.size,
                    sample = sample,
                )
                if (recentSamples.size == recentSampleCapacity) {
                    recentSamples.removeFirst()
                }
                recentSamples.addLast(replaySample)
                onAcceptedSample(replaySample)
                acceptedSamples += 1
            }
        }

        fun report(summary: CupRawScanSummary): CupRawReplayReport {
            val decoderStats = decoder.stats
            val sequenceStats = sequenceTracker.stats
            return CupRawReplayReport(
                peakRawRecordBufferBytes = summary.peakRecordBufferBytes,
                rawRecordCount = summary.recordCount,
                rawPayloadBytes = rawPayloadBytes,
                validRawBytes = summary.validByteCount,
                totalRawBytes = summary.totalByteCount,
                tailIssue = summary.tailIssue,
                decodedFrames = decoderStats.frames,
                acceptedFrames = acceptedFrames,
                acceptedSamples = acceptedSamples,
                missingFrames = sequenceStats.missingFrames,
                duplicateFrames = sequenceStats.duplicateFrames,
                outOfOrderFrames = sequenceStats.outOfOrderFrames,
                structurallyInvalidFrames = decoderStats.invalidFunction +
                    decoderStats.invalidLength + decoderStats.invalidTail,
                discardedBytes = decoderStats.bytesDiscarded,
                pendingDecoderBytes = decoder.pendingByteCount,
                firstFrameHostNanoseconds = firstFrameHostNanoseconds,
                lastFrameHostNanoseconds = lastFrameHostNanoseconds,
                recentSamples = recentSamples.toList(),
                leadingAlignmentBytes = leadingAlignmentBytes,
            )
        }
    }
}
