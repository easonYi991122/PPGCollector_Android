package com.example.ppgcollector_android.core.protocol

/** Incremental decoder for arbitrary BLE notification boundaries. */
class CupBatchStreamDecoder(
    private val maxPendingBytes: Int = CupBatchProtocolV1.maximumFrameLength * 2,
) {
    init {
        require(maxPendingBytes >= CupBatchProtocolV1.frameLength) {
            "maxPendingBytes must hold one complete frame"
        }
    }

    private val buffer = ArrayList<Byte>(maxPendingBytes)
    private var detectedDataLength: Int? = null
    var stats: CupDecoderStats = CupDecoderStats()
        private set

    val detectedProtocolProfile: String?
        get() = when (detectedDataLength) {
            CupBatchProtocolV1.dataLength -> CupBatchProtocolV1.profileIdentifier
            CupBatchProtocolV1.legacyDataLength -> CupBatchProtocolV1.legacyProfileIdentifier
            else -> null
        }

    val pendingByteCount: Int
        get() = buffer.size

    fun feed(data: ByteArray): List<CupBatchFrame> {
        data.forEach(buffer::add)
        val frames = ArrayList<CupBatchFrame>()

        while (true) {
            val headerIndex = firstHeaderIndex()
            if (headerIndex == -1) {
                keepPossibleHeaderPrefix()
                break
            }
            if (headerIndex > 0) discardFirst(headerIndex)
            if (buffer.size < 5) break

            if (buffer[2].toUByte() != CupBatchProtocolV1.batchFunction) {
                stats = stats.withInvalidFunction()
                discardFirst(1)
                continue
            }

            val dataLength = readUInt16Le(3)
            if (!CupBatchProtocolV1.isSupportedDataLength(dataLength) ||
                detectedDataLength?.let { it != dataLength } == true
            ) {
                stats = stats.withInvalidLength()
                discardFirst(1)
                continue
            }

            val totalLength = 2 + 1 + 2 + dataLength + 2
            if (buffer.size < totalLength) break
            if (buffer[totalLength - 2] != CupBatchProtocolV1.tail[0] ||
                buffer[totalLength - 1] != CupBatchProtocolV1.tail[1]
            ) {
                stats = stats.withInvalidTail()
                discardFirst(1)
                continue
            }

            val wire = buffer.subList(0, totalLength).toByteArray()
            frames += decodeCupBatchFrame(wire)
            detectedDataLength = dataLength
            stats = stats.withFrame()
            buffer.subList(0, totalLength).clear()
        }

        trimPendingBuffer()
        return frames
    }

    fun reset() {
        buffer.clear()
        detectedDataLength = null
        stats = CupDecoderStats()
    }

    private fun firstHeaderIndex(): Int {
        if (buffer.size < CupBatchProtocolV1.header.size) return -1
        for (index in 0 until buffer.size - 1) {
            if (buffer[index] == CupBatchProtocolV1.header[0] &&
                buffer[index + 1] == CupBatchProtocolV1.header[1]
            ) return index
        }
        return -1
    }

    private fun keepPossibleHeaderPrefix() {
        val keepCount = if (buffer.lastOrNull() == CupBatchProtocolV1.header[0]) 1 else 0
        discardFirst(buffer.size - keepCount)
    }

    private fun discardFirst(count: Int) {
        if (count <= 0) return
        buffer.subList(0, count).clear()
        stats = stats.withDiscarded(count)
    }

    private fun trimPendingBuffer() {
        val excess = buffer.size - maxPendingBytes
        if (excess > 0) discardFirst(excess)
    }

    private fun readUInt16Le(offset: Int): Int =
        (buffer[offset].toInt() and 0xFF) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 8)
}
