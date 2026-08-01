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
            discardedBytes == 0 &&
            pendingDecoderBytes == 0
}

object CupRawReplayEngine {
    const val recentSampleCapacity = 800

    fun replay(path: Path): CupRawReplayReport {
        val accumulator = ReplayAccumulator()
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

    private class ReplayAccumulator {
        private val decoder = CupBatchStreamDecoder()
        private val sequenceTracker = CupFrameSequenceTracker()
        private val recentSamples = ArrayDeque<CupReplaySample>(recentSampleCapacity)
        private var rawPayloadBytes = 0L
        private var acceptedFrames = 0
        private var acceptedSamples = 0L
        private var firstFrameHostNanoseconds: ULong? = null
        private var lastFrameHostNanoseconds: ULong? = null

        fun receive(record: CupRawRecord) {
            rawPayloadBytes += record.chunk.size.toLong()
            decoder.feed(record.chunk).forEach { frame ->
                when (sequenceTracker.observe(frame.sequence)) {
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
                    sample = sample,
                )
                if (recentSamples.size == recentSampleCapacity) {
                    recentSamples.removeFirst()
                }
                recentSamples.addLast(replaySample)
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
            )
        }
    }
}
