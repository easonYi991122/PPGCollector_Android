package com.example.ppgcollector_android.core.protocol

enum class NordicWireCandidate { ADS1292R_120, SENSOR_PACKET_168 }

data class NordicWireProbeResult(
    val locked: CupStreamProtocolMode? = null,
    val pending: Boolean = true,
    val votes120: Int = 0,
    val votes168: Int = 0,
    val ambiguousFrames: Int = 0,
)

/** Geometry-only probe used only for the exact Nordic UART identity. */
class NordicWireProbe(private val framesToLock: Int = 3) {
    private val buffer = ArrayList<Byte>(Ads1292rPacketProtocol.frameLength * 4)
    private var candidate: NordicWireCandidate? = null
    private var consecutiveVotes = 0
    private var votes120 = 0
    private var votes168 = 0
    private var ambiguous = 0
    var result: NordicWireProbeResult = NordicWireProbeResult()
        private set

    fun feed(data: ByteArray): NordicWireProbeResult {
        if (result.locked != null) return result
        data.forEach(buffer::add)
        while (true) {
            val header = findHeader()
            if (header < 0) {
                trimToHeaderPrefix()
                break
            }
            if (header > 0) repeat(header) { buffer.removeAt(0) }
            if (buffer.size < 120) break
            val is120 = hasFooterAt(118)
            val is168 = buffer.size >= 168 && hasFooterAt(166)
            if (is120 == is168) {
                if (is120) ambiguous++
                buffer.removeAt(0)
                continue
            }
            val hit = if (is120) NordicWireCandidate.ADS1292R_120 else NordicWireCandidate.SENSOR_PACKET_168
            if (candidate == hit) consecutiveVotes++ else {
                candidate = hit
                consecutiveVotes = 1
            }
            if (hit == NordicWireCandidate.ADS1292R_120) votes120++ else votes168++
            val mode = if (consecutiveVotes >= framesToLock) {
                if (hit == NordicWireCandidate.ADS1292R_120) CupStreamProtocolMode.ADS1292R_120
                else CupStreamProtocolMode.SENSOR_PACKET_168
            } else null
            result = NordicWireProbeResult(
                locked = mode,
                pending = mode == null,
                votes120 = votes120,
                votes168 = votes168,
                ambiguousFrames = ambiguous,
            )
            if (mode != null) return result
            repeat(if (is120) 120 else 168) { buffer.removeAt(0) }
        }
        result = result.copy(
            pending = result.locked == null,
            votes120 = votes120,
            votes168 = votes168,
            ambiguousFrames = ambiguous,
        )
        return result
    }

    fun reset() {
        buffer.clear()
        candidate = null
        consecutiveVotes = 0
        votes120 = 0
        votes168 = 0
        ambiguous = 0
        result = NordicWireProbeResult()
    }

    private fun findHeader(): Int =
        (0 until buffer.size - 1).firstOrNull {
            buffer[it] == Ads1292rPacketProtocol.header[0] &&
                buffer[it + 1] == Ads1292rPacketProtocol.header[1]
        } ?: -1

    private fun hasFooterAt(offset: Int): Boolean =
        buffer[offset] == Ads1292rPacketProtocol.footer[0] &&
            buffer[offset + 1] == Ads1292rPacketProtocol.footer[1]

    private fun trimToHeaderPrefix() {
        val keep = if (buffer.lastOrNull() == Ads1292rPacketProtocol.header[0]) 1 else 0
        repeat((buffer.size - keep).coerceAtLeast(0)) { buffer.removeAt(0) }
    }
}
