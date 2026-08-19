package com.example.ppgcollector_android.core.protocol

data class Ads1292rPacket(
    val sequenceNumber: UInt,
    val ecg: List<UInt>,
    val red: List<UInt>,
    val ir: List<UInt>,
)

data class Ads1292rDecoderStats(
    val frames: Long = 0,
    val invalidHeaders: Long = 0,
    val invalidTails: Long = 0,
    val sequenceGaps: Long = 0,
    val discardedBytes: Long = 0,
    val lastSequence: UInt? = null,
)

object Ads1292rPacketProtocol {
    const val profileIdentifier = "ads1292r-120-ecg-ppg-0.1"
    const val frameLength = 120
    const val samplesPerFrame = 20
    const val ppgSamplesPerFrame = 4
    const val ecgSampleRateHz = 500
    const val ppgSampleRateHz = 100
    private const val sequenceOffset = 2
    private const val ecgOffset = 6
    private const val redOffset = ecgOffset + samplesPerFrame * 4
    private const val irOffset = redOffset + ppgSamplesPerFrame * 4

    val header = byteArrayOf(0xAB.toByte(), 0xBA.toByte())
    val footer = byteArrayOf(0xCD.toByte(), 0xDC.toByte())

    fun encode(packet: Ads1292rPacket): ByteArray {
        require(packet.ecg.size == samplesPerFrame)
        require(packet.red.size == ppgSamplesPerFrame)
        require(packet.ir.size == ppgSamplesPerFrame)
        return ByteArray(frameLength).also { wire ->
            header.copyInto(wire)
            writeUInt32Le(wire, sequenceOffset, packet.sequenceNumber)
            packet.ecg.forEachIndexed { index, value -> writeUInt32Le(wire, ecgOffset + index * 4, value) }
            packet.red.forEachIndexed { index, value -> writeUInt32Le(wire, redOffset + index * 4, value) }
            packet.ir.forEachIndexed { index, value -> writeUInt32Le(wire, irOffset + index * 4, value) }
            footer.copyInto(wire, irOffset + ppgSamplesPerFrame * 4)
        }
    }

    fun decode(wire: ByteArray): Ads1292rPacket {
        require(wire.size == frameLength) { "ads1292r frame must be $frameLength bytes" }
        require(wire.copyOfRange(0, 2).contentEquals(header)) { "invalid ads1292r header" }
        require(wire.copyOfRange(frameLength - 2, frameLength).contentEquals(footer)) {
            "invalid ads1292r footer"
        }
        return Ads1292rPacket(
            sequenceNumber = readUInt32Le(wire, sequenceOffset),
            ecg = List(samplesPerFrame) { readUInt32Le(wire, ecgOffset + it * 4) },
            red = List(ppgSamplesPerFrame) { readUInt32Le(wire, redOffset + it * 4) },
            ir = List(ppgSamplesPerFrame) { readUInt32Le(wire, irOffset + it * 4) },
        )
    }

    internal fun readUInt32Le(bytes: ByteArray, offset: Int): UInt =
        (bytes[offset].toUInt() and 0xFFu) or
            ((bytes[offset + 1].toUInt() and 0xFFu) shl 8) or
            ((bytes[offset + 2].toUInt() and 0xFFu) shl 16) or
            ((bytes[offset + 3].toUInt() and 0xFFu) shl 24)

    private fun writeUInt32Le(bytes: ByteArray, offset: Int, value: UInt) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value shr 8).toByte()
        bytes[offset + 2] = (value shr 16).toByte()
        bytes[offset + 3] = (value shr 24).toByte()
    }
}

class Ads1292rStreamDecoder(
    private val maxPendingBytes: Int = Ads1292rPacketProtocol.frameLength * 3,
) {
    private val buffer = ArrayList<Byte>(maxPendingBytes)
    var stats: Ads1292rDecoderStats = Ads1292rDecoderStats()
        private set

    fun feed(data: ByteArray): List<Ads1292rPacket> {
        data.forEach(buffer::add)
        val decoded = ArrayList<Ads1292rPacket>()
        while (true) {
            val header = findHeader()
            if (header < 0) {
                val keep = if (buffer.lastOrNull() == Ads1292rPacketProtocol.header[0]) 1 else 0
                discard(buffer.size - keep)
                break
            }
            if (header > 0) discard(header)
            if (buffer.size < Ads1292rPacketProtocol.frameLength) break
            val wire = buffer.take(Ads1292rPacketProtocol.frameLength).toByteArray()
            if (!wire.copyOfRange(118, 120).contentEquals(Ads1292rPacketProtocol.footer)) {
                stats = stats.copy(invalidTails = stats.invalidTails + 1)
                discard(1)
                continue
            }
            val packet = Ads1292rPacketProtocol.decode(wire)
            val previous = stats.lastSequence
            val gap = if (previous == null) 0 else {
                val expected = previous + 1u
                if (packet.sequenceNumber == expected) 0 else (packet.sequenceNumber - expected).toLong()
            }
            stats = stats.copy(
                frames = stats.frames + 1,
                sequenceGaps = stats.sequenceGaps + gap.coerceAtLeast(0),
                lastSequence = packet.sequenceNumber,
            )
            decoded += packet
            repeat(Ads1292rPacketProtocol.frameLength) { buffer.removeAt(0) }
        }
        if (buffer.size > maxPendingBytes) discard(buffer.size - maxPendingBytes)
        return decoded
    }

    fun reset() {
        buffer.clear()
        stats = Ads1292rDecoderStats()
    }

    private fun findHeader(): Int =
        (0 until buffer.size - 1).firstOrNull {
            buffer[it] == Ads1292rPacketProtocol.header[0] &&
                buffer[it + 1] == Ads1292rPacketProtocol.header[1]
        } ?: -1

    private fun discard(count: Int) {
        if (count <= 0) return
        repeat(count.coerceAtMost(buffer.size)) { buffer.removeAt(0) }
        stats = stats.copy(discardedBytes = stats.discardedBytes + count)
    }
}
