package com.example.ppgcollector_android.core.protocol

/**
 * Draft CUP batch profile derived from the current Swift/Python references.
 *
 * The 408-byte layout is a bring-up baseline until a real CUP capture confirms
 * length semantics, sample ordering, sampling rate, and checksum behavior.
 */
object CupBatchProtocolV1 {
    val header = byteArrayOf(0xAB.toByte(), 0xBA.toByte())
    val tail = byteArrayOf(0xCD.toByte(), 0xDC.toByte())
    const val batchFunction: UByte = 0x15u
    const val sampleRateHz = 100
    const val samplesPerFrame = 50
    const val sampleWireLength = 8
    const val dataLength = 1 + samplesPerFrame * sampleWireLength
    const val frameLength = 2 + 1 + 2 + dataLength + 2
}

data class CupPpgSample(val red: UInt, val ir: UInt)

data class CupBatchFrame(val sequence: UByte, val samples: List<CupPpgSample>) {
    init {
        require(samples.size == CupBatchProtocolV1.samplesPerFrame) {
            "expected ${CupBatchProtocolV1.samplesPerFrame} samples, got ${samples.size}"
        }
    }
}

data class CupDecoderStats(
    val frames: Int = 0,
    val bytesDiscarded: Int = 0,
    val invalidFunction: Int = 0,
    val invalidLength: Int = 0,
    val invalidTail: Int = 0,
) {
    fun withFrame() = copy(frames = frames + 1)
    fun withDiscarded(count: Int) = copy(bytesDiscarded = bytesDiscarded + count)
    fun withInvalidFunction() = copy(invalidFunction = invalidFunction + 1)
    fun withInvalidLength() = copy(invalidLength = invalidLength + 1)
    fun withInvalidTail() = copy(invalidTail = invalidTail + 1)
}

class CupProtocolException(message: String) : IllegalArgumentException(message)

fun encodeCupBatchFrame(frame: CupBatchFrame): ByteArray {
    val wire = ByteArray(CupBatchProtocolV1.frameLength)
    CupBatchProtocolV1.header.copyInto(wire, 0)
    wire[2] = CupBatchProtocolV1.batchFunction.toByte()
    writeUInt16Le(wire, 3, CupBatchProtocolV1.dataLength)
    wire[5] = frame.sequence.toByte()

    frame.samples.forEachIndexed { index, sample ->
        val offset = 6 + index * CupBatchProtocolV1.sampleWireLength
        writeUInt32Le(wire, offset, sample.red)
        writeUInt32Le(wire, offset + 4, sample.ir)
    }
    CupBatchProtocolV1.tail.copyInto(wire, wire.size - CupBatchProtocolV1.tail.size)
    return wire
}

fun decodeCupBatchFrame(wire: ByteArray): CupBatchFrame {
    if (wire.size != CupBatchProtocolV1.frameLength) {
        throw CupProtocolException("expected ${CupBatchProtocolV1.frameLength} bytes, got ${wire.size}")
    }
    if (!wire.copyOfRange(0, 2).contentEquals(CupBatchProtocolV1.header)) {
        throw CupProtocolException("invalid header")
    }
    if (wire[2].toUByte() != CupBatchProtocolV1.batchFunction) {
        throw CupProtocolException("invalid function")
    }
    if (readUInt16Le(wire, 3) != CupBatchProtocolV1.dataLength) {
        throw CupProtocolException("invalid data length")
    }
    if (!wire.copyOfRange(wire.size - 2, wire.size).contentEquals(CupBatchProtocolV1.tail)) {
        throw CupProtocolException("invalid tail")
    }

    val samples = List(CupBatchProtocolV1.samplesPerFrame) { index ->
        val offset = 6 + index * CupBatchProtocolV1.sampleWireLength
        CupPpgSample(readUInt32Le(wire, offset), readUInt32Le(wire, offset + 4))
    }
    return CupBatchFrame(wire[5].toUByte(), samples)
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
