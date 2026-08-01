package com.example.ppgcollector_android.core.protocol

sealed interface CupSequenceEvent {
    data object First : CupSequenceEvent
    data object Continuous : CupSequenceEvent
    data class Gap(val missingFrames: Int) : CupSequenceEvent
    data object Duplicate : CupSequenceEvent
    data object OutOfOrder : CupSequenceEvent
}

data class CupSequenceStats(
    val previous: UByte? = null,
    val receivedFrames: Int = 0,
    val missingFrames: Int = 0,
    val duplicateFrames: Int = 0,
    val outOfOrderFrames: Int = 0,
) {
    val missingSamples: Int
        get() = missingFrames * CupBatchProtocolV1.samplesPerFrame
}

class CupFrameSequenceTracker {
    var stats: CupSequenceStats = CupSequenceStats()
        private set

    fun observe(sequence: UByte): CupSequenceEvent {
        val previous = stats.previous
        stats = stats.copy(receivedFrames = stats.receivedFrames + 1)
        if (previous == null) {
            stats = stats.copy(previous = sequence)
            return CupSequenceEvent.First
        }

        val delta = (sequence.toInt() - previous.toInt()) and 0xFF
        return when {
            delta == 0 -> {
                stats = stats.copy(duplicateFrames = stats.duplicateFrames + 1)
                CupSequenceEvent.Duplicate
            }
            delta == 1 -> {
                stats = stats.copy(previous = sequence)
                CupSequenceEvent.Continuous
            }
            delta in 2 until 128 -> {
                val missing = delta - 1
                stats = stats.copy(
                    previous = sequence,
                    missingFrames = stats.missingFrames + missing,
                )
                CupSequenceEvent.Gap(missing)
            }
            else -> {
                stats = stats.copy(outOfOrderFrames = stats.outOfOrderFrames + 1)
                CupSequenceEvent.OutOfOrder
            }
        }
    }

    fun reset() {
        stats = CupSequenceStats()
    }
}
