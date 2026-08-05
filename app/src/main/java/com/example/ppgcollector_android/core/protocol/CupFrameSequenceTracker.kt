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
    val previousSequenceNumber: UInt? = null,
    val sequenceBitWidth: Int? = null,
    val receivedFrames: Int = 0,
    val missingFrames: Int = 0,
    val missingSamples: Int = 0,
    val duplicateFrames: Int = 0,
    val outOfOrderFrames: Int = 0,
)

class CupFrameSequenceTracker {
    var stats: CupSequenceStats = CupSequenceStats()
        private set

    fun observe(
        sequence: UByte,
        samplesPerFrame: Int = CupBatchProtocolV1.samplesPerFrame,
    ): CupSequenceEvent = observe(sequence.toUInt(), 8, samplesPerFrame)

    fun observe(frame: CupBatchFrame): CupSequenceEvent = observe(
        sequenceNumber = frame.sequenceNumber,
        sequenceBitWidth = frame.wireProfile.sequenceBitWidth,
        samplesPerFrame = frame.samples.size,
    )

    private fun observe(
        sequenceNumber: UInt,
        sequenceBitWidth: Int,
        samplesPerFrame: Int,
    ): CupSequenceEvent {
        require(samplesPerFrame > 0) { "samplesPerFrame must be positive" }
        require(sequenceBitWidth == 8 || sequenceBitWidth == 32) {
            "sequenceBitWidth must be 8 or 32"
        }
        val previous = stats.previousSequenceNumber
        require(stats.sequenceBitWidth == null || stats.sequenceBitWidth == sequenceBitWidth) {
            "wire sequence width changed within one stream"
        }
        stats = stats.copy(receivedFrames = stats.receivedFrames + 1)
        if (previous == null) {
            stats = stats.copy(
                previous = sequenceNumber.toUByte(),
                previousSequenceNumber = sequenceNumber,
                sequenceBitWidth = sequenceBitWidth,
            )
            return CupSequenceEvent.First
        }

        val modulus = 1uL shl sequenceBitWidth
        val delta = (sequenceNumber.toULong() + modulus - previous.toULong()) % modulus
        return when {
            delta == 0uL -> {
                stats = stats.copy(duplicateFrames = stats.duplicateFrames + 1)
                CupSequenceEvent.Duplicate
            }
            delta == 1uL -> {
                stats = stats.copy(
                    previous = sequenceNumber.toUByte(),
                    previousSequenceNumber = sequenceNumber,
                    sequenceBitWidth = sequenceBitWidth,
                )
                CupSequenceEvent.Continuous
            }
            delta >= 2uL && delta < modulus / 2u -> {
                val missing = (delta - 1uL).toInt()
                stats = stats.copy(
                    previous = sequenceNumber.toUByte(),
                    previousSequenceNumber = sequenceNumber,
                    sequenceBitWidth = sequenceBitWidth,
                    missingFrames = saturatedAdd(stats.missingFrames, missing),
                    missingSamples = saturatedAdd(
                        stats.missingSamples,
                        missing.toLong() * samplesPerFrame,
                    ),
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

    private fun saturatedAdd(current: Int, increment: Int): Int =
        saturatedAdd(current, increment.toLong())

    private fun saturatedAdd(current: Int, increment: Long): Int =
        (current.toLong() + increment).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
