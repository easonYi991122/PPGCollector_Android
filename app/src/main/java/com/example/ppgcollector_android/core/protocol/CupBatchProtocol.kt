package com.example.ppgcollector_android.core.protocol

/**
 * Current CUP batch profile supplied for the FFF0 hardware bring-up.
 *
 * One 168-byte frame contains 20 little-endian RED words followed by 20
 * little-endian IR words. Sampling remains 100 Hz outside the wire layout.
 */
object CupBatchProtocolV1 {
    const val profileIdentifier = "cup-batch-168-planar-0.1"
    const val legacyProfileIdentifier = "cup-batch-408-interleaved-legacy-0.1"
    val header = byteArrayOf(0xAB.toByte(), 0xBA.toByte())
    val tail = byteArrayOf(0xCD.toByte(), 0xDC.toByte())
    const val batchFunction: UByte = 0x15u
    const val sampleRateHz = 100
    const val samplesPerFrame = 20
    const val sampleWordLength = 4
    const val channelDataLength = samplesPerFrame * sampleWordLength
    const val redDataOffset = 6
    const val irDataOffset = redDataOffset + channelDataLength
    const val dataLength = 1 + channelDataLength * 2
    const val frameLength = 2 + 1 + 2 + dataLength + 2
    const val legacySamplesPerFrame = 50
    const val legacySampleWireLength = 8
    const val legacyDataLength = 1 + legacySamplesPerFrame * legacySampleWireLength
    const val legacyFrameLength = 2 + 1 + 2 + legacyDataLength + 2
    const val maximumFrameLength = legacyFrameLength
    const val auxiliaryFrameLength = 8

    fun isSupportedDataLength(length: Int): Boolean =
        length == dataLength || length == legacyDataLength

    /** Function codes observed as complete 8-byte FFF1 auxiliary notifications. */
    fun isObservedAuxiliaryFunction(function: UByte): Boolean = when (function) {
        0x02u.toUByte(),
        0x06u.toUByte(),
        0x0Cu.toUByte(),
        0x0Fu.toUByte(),
        -> true
        else -> false
    }
}

data class CupPpgSample(val red: UInt, val ir: UInt)

data class CupBatchFrame(
    /** Compatibility view for the existing UInt8 batch profile. */
    val sequence: UByte,
    val samples: List<CupPpgSample>,
    /** Full wire value; sensor-packet devices use all 32 bits. */
    val sequenceNumber: UInt = sequence.toUInt(),
    val wireProfile: CupWireFrameProfile =
        CupWireFrameProfile.batchProfileForSampleCount(samples.size),
) {
    init {
        require(
            samples.size == CupBatchProtocolV1.samplesPerFrame ||
                samples.size == CupBatchProtocolV1.legacySamplesPerFrame ||
                samples.size == Ads1292rPacketProtocol.ppgSamplesPerFrame,
        ) {
            "expected ${CupBatchProtocolV1.samplesPerFrame} current, " +
                "${CupBatchProtocolV1.legacySamplesPerFrame} legacy, or " +
                "${Ads1292rPacketProtocol.ppgSamplesPerFrame} ads1292r PPG samples, got ${samples.size}"
        }
    }

    val protocolProfileIdentifier: String
        get() = wireProfile.identifier
}

data class CupDecoderStats(
    val frames: Int = 0,
    val auxiliaryFrames: Int = 0,
    val bytesDiscarded: Int = 0,
    val invalidFunction: Int = 0,
    val invalidLength: Int = 0,
    val invalidTail: Int = 0,
) {
    fun withFrame() = copy(frames = frames + 1)
    fun withAuxiliaryFrame() = copy(auxiliaryFrames = auxiliaryFrames + 1)
    fun withDiscarded(count: Int) = copy(bytesDiscarded = bytesDiscarded + count)
    fun withInvalidFunction() = copy(invalidFunction = invalidFunction + 1)
    fun withInvalidLength() = copy(invalidLength = invalidLength + 1)
    fun withInvalidTail() = copy(invalidTail = invalidTail + 1)
}

class CupProtocolException(message: String) : IllegalArgumentException(message)

fun encodeCupBatchFrame(frame: CupBatchFrame): ByteArray {
    require(frame.wireProfile == CupWireFrameProfile.BATCH_168) {
        "current batch encoder requires the 168-byte batch wire profile"
    }
    require(frame.samples.size == CupBatchProtocolV1.samplesPerFrame) {
        "current encoder requires ${CupBatchProtocolV1.samplesPerFrame} samples"
    }
    val wire = ByteArray(CupBatchProtocolV1.frameLength)
    CupBatchProtocolV1.header.copyInto(wire, 0)
    wire[2] = CupBatchProtocolV1.batchFunction.toByte()
    writeUInt16Le(wire, 3, CupBatchProtocolV1.dataLength)
    wire[5] = frame.sequence.toByte()

    frame.samples.forEachIndexed { index, sample ->
        writeUInt32Le(
            wire,
            CupBatchProtocolV1.redDataOffset + index * CupBatchProtocolV1.sampleWordLength,
            sample.red,
        )
        writeUInt32Le(
            wire,
            CupBatchProtocolV1.irDataOffset + index * CupBatchProtocolV1.sampleWordLength,
            sample.ir,
        )
    }
    CupBatchProtocolV1.tail.copyInto(wire, wire.size - CupBatchProtocolV1.tail.size)
    return wire
}

fun decodeCupBatchFrame(wire: ByteArray): CupBatchFrame {
    if (wire.size < 5) throw CupProtocolException("frame too short")
    if (!wire.copyOfRange(0, 2).contentEquals(CupBatchProtocolV1.header)) {
        throw CupProtocolException("invalid header")
    }
    if (wire[2].toUByte() != CupBatchProtocolV1.batchFunction) {
        throw CupProtocolException("invalid function")
    }
    val dataLength = readUInt16Le(wire, 3)
    val expectedFrameLength = 2 + 1 + 2 + dataLength + 2
    if (!CupBatchProtocolV1.isSupportedDataLength(dataLength) || wire.size != expectedFrameLength) {
        throw CupProtocolException("invalid data length")
    }
    if (!wire.copyOfRange(wire.size - 2, wire.size).contentEquals(CupBatchProtocolV1.tail)) {
        throw CupProtocolException("invalid tail")
    }

    val samples = if (dataLength == CupBatchProtocolV1.dataLength) {
        List(CupBatchProtocolV1.samplesPerFrame) { index ->
            CupPpgSample(
                red = readUInt32Le(
                    wire,
                    CupBatchProtocolV1.redDataOffset + index * CupBatchProtocolV1.sampleWordLength,
                ),
                ir = readUInt32Le(
                    wire,
                    CupBatchProtocolV1.irDataOffset + index * CupBatchProtocolV1.sampleWordLength,
                ),
            )
        }
    } else {
        List(CupBatchProtocolV1.legacySamplesPerFrame) { index ->
            val offset = 6 + index * CupBatchProtocolV1.legacySampleWireLength
            CupPpgSample(
                red = readUInt32Le(wire, offset),
                ir = readUInt32Le(wire, offset + CupBatchProtocolV1.sampleWordLength),
            )
        }
    }
    val sequence = wire[5].toUByte()
    val wireProfile = if (dataLength == CupBatchProtocolV1.dataLength) {
        CupWireFrameProfile.BATCH_168
    } else {
        CupWireFrameProfile.BATCH_408_LEGACY
    }
    return CupBatchFrame(sequence, samples, sequence.toUInt(), wireProfile)
}

private fun writeUInt16Le(target: ByteArray, offset: Int, value: Int) {
    target[offset] = value.toByte()
    target[offset + 1] = (value ushr 8).toByte()
}

private fun writeUInt32Le(target: ByteArray, offset: Int, value: UInt) {
    target[offset] = value.toByte()
    target[offset + 1] = (value shr 8).toByte()
    target[offset + 2] = (value shr 16).toByte()
    target[offset + 3] = (value shr 24).toByte()
}

private fun readUInt16Le(source: ByteArray, offset: Int): Int =
    (source[offset].toInt() and 0xFF) or
        ((source[offset + 1].toInt() and 0xFF) shl 8)

private fun readUInt32Le(source: ByteArray, offset: Int): UInt =
    ((source[offset].toUInt() and 0xFFu)) or
        ((source[offset + 1].toUInt() and 0xFFu) shl 8) or
        ((source[offset + 2].toUInt() and 0xFFu) shl 16) or
        ((source[offset + 3].toUInt() and 0xFFu) shl 24)
