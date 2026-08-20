package com.example.ppgcollector_android.core.protocol

enum class NordicWireCandidate { ADS1292R_120, SENSOR_PACKET_168 }

enum class WireFrameGeometry { BYTES_120, BYTES_168 }

data class NordicWireProbeResult(
    val locked: CupStreamProtocolMode? = null,
    val lockedGeometry: WireFrameGeometry? = null,
    val pending: Boolean = true,
    val votes120: Int = 0,
    val votes168: Int = 0,
    val ambiguousFrames: Int = 0,
)

/**
 * Geometry-only probe: 120 vs 168 by footer offset. Identity mapping to a
 * decoder mode happens in the GATT owner.
 */
class NordicWireProbe(private val framesToLock: Int = 3) {
    private val buffer = ByteRingBuffer(Ads1292rPacketProtocol.frameLength * 4)
    private var candidate: NordicWireCandidate? = null
    private var consecutiveVotes = 0
    private var votes120 = 0
    private var votes168 = 0
    private var ambiguous = 0
    private var confirmedGeometry: WireFrameGeometry? = null
    var result: NordicWireProbeResult = NordicWireProbeResult()
        private set

    fun feed(data: ByteArray): NordicWireProbeResult {
        if (result.locked != null) return result
        data.forEach(buffer::add)
        trimIfUnbounded()
        while (true) {
            val header = findHeader()
            if (header < 0) {
                trimToHeaderPrefix()
                break
            }
            if (header > 0) buffer.removeFirst(header)
            if (buffer.size < 120) break
            val is120 = hasFooterAt(118)
            val is168 = buffer.size >= 168 && hasFooterAt(166)
            if (is120 && is168) {
                ambiguous++
                confirmedGeometry = null
                buffer.removeFirst(1)
                continue
            }
            val canDeny168 = buffer.size >= 168 || hasHeaderAt(120)
            when {
                is120 && !is168 && (canDeny168 || confirmedGeometry == WireFrameGeometry.BYTES_120) -> {
                    vote(NordicWireCandidate.ADS1292R_120)
                    confirmedGeometry = WireFrameGeometry.BYTES_120
                    if (result.locked != null) return result
                    buffer.removeFirst(120)
                }
                is168 && !is120 -> {
                    vote(NordicWireCandidate.SENSOR_PACKET_168)
                    confirmedGeometry = WireFrameGeometry.BYTES_168
                    if (result.locked != null) return result
                    buffer.removeFirst(168)
                }
                buffer.size < 168 -> break
                else -> buffer.removeFirst(1)
            }
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
        confirmedGeometry = null
        result = NordicWireProbeResult()
    }

    private fun vote(hit: NordicWireCandidate) {
        if (candidate == hit) consecutiveVotes++ else {
            candidate = hit
            consecutiveVotes = 1
        }
        if (hit == NordicWireCandidate.ADS1292R_120) votes120++ else votes168++
        val mode = if (consecutiveVotes >= framesToLock) {
            if (hit == NordicWireCandidate.ADS1292R_120) CupStreamProtocolMode.ADS1292R_120
            else CupStreamProtocolMode.SENSOR_PACKET_168
        } else null
        val geometry = when (mode) {
            CupStreamProtocolMode.ADS1292R_120 -> WireFrameGeometry.BYTES_120
            CupStreamProtocolMode.SENSOR_PACKET_168 -> WireFrameGeometry.BYTES_168
            else -> null
        }
        result = NordicWireProbeResult(
            locked = mode,
            lockedGeometry = geometry,
            pending = mode == null,
            votes120 = votes120,
            votes168 = votes168,
            ambiguousFrames = ambiguous,
        )
    }

    private fun findHeader(): Int {
        val last = buffer.size - 1
        for (index in 0 until last) {
            if (hasHeaderAt(index)) return index
        }
        return -1
    }

    private fun hasHeaderAt(offset: Int): Boolean =
        offset + 1 < buffer.size &&
            buffer[offset] == Ads1292rPacketProtocol.header[0] &&
            buffer[offset + 1] == Ads1292rPacketProtocol.header[1]

    private fun hasFooterAt(offset: Int): Boolean =
        offset + 1 < buffer.size &&
            buffer[offset] == Ads1292rPacketProtocol.footer[0] &&
            buffer[offset + 1] == Ads1292rPacketProtocol.footer[1]

    private fun trimToHeaderPrefix() {
        val keep = if (buffer.lastOrNull() == Ads1292rPacketProtocol.header[0]) 1 else 0
        buffer.removeFirst((buffer.size - keep).coerceAtLeast(0))
    }

    private fun trimIfUnbounded() {
        val maxBytes = 168 * 6
        if (buffer.size <= maxBytes) return
        val header = findHeader()
        val drop = if (header > 0) header else buffer.size - maxBytes
        buffer.removeFirst(drop.coerceAtLeast(1).coerceAtMost(buffer.size))
    }
}
