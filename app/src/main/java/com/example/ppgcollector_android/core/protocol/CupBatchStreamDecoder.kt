package com.example.ppgcollector_android.core.protocol

/** Incremental decoder for arbitrary BLE notification boundaries. */
class CupBatchStreamDecoder(
    private val maxPendingBytes: Int = CupBatchProtocolV1.maximumFrameLength * 2,
    private val protocolMode: CupStreamProtocolMode = CupStreamProtocolMode.BATCH_COMPATIBLE,
) {
    init {
        require(maxPendingBytes >= CupBatchProtocolV1.frameLength) {
            "maxPendingBytes must hold one complete frame"
        }
    }

    private val buffer = ByteRingBuffer(maxPendingBytes)
    private var detectedDataLength: Int? = null
    var stats: CupDecoderStats = CupDecoderStats()
        private set

    val detectedProtocolProfile: String?
        get() = if (protocolMode == CupStreamProtocolMode.SENSOR_PACKET_168) {
            if (stats.frames > 0) CupSensorPacketProtocolV1.profileIdentifier else null
        } else {
            when (detectedDataLength) {
                CupBatchProtocolV1.dataLength -> CupBatchProtocolV1.profileIdentifier
                CupBatchProtocolV1.legacyDataLength -> CupBatchProtocolV1.legacyProfileIdentifier
                else -> null
            }
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
            if (protocolMode == CupStreamProtocolMode.SENSOR_PACKET_168) {
                if (buffer.size < CupSensorPacketProtocolV1.frameLength) break
                if (buffer[CupSensorPacketProtocolV1.tailOffset] != CupBatchProtocolV1.tail[0] ||
                    buffer[CupSensorPacketProtocolV1.tailOffset + 1] != CupBatchProtocolV1.tail[1]
                ) {
                    stats = stats.withInvalidTail()
                    discardFirst(1)
                    continue
                }
                val wire = buffer.toByteArray(CupSensorPacketProtocolV1.frameLength)
                frames += decodeCupSensorPacketFrame(wire)
                stats = stats.withFrame()
                buffer.removeFirst(CupSensorPacketProtocolV1.frameLength)
                continue
            }
            if (buffer.size < 5) break

            val function = buffer[2].toUByte()
            if (function != CupBatchProtocolV1.batchFunction &&
                CupBatchProtocolV1.isObservedAuxiliaryFunction(function)
            ) {
                if (buffer.size < CupBatchProtocolV1.auxiliaryFrameLength) break
                if (buffer[CupBatchProtocolV1.auxiliaryFrameLength - 2] != CupBatchProtocolV1.tail[0] ||
                    buffer[CupBatchProtocolV1.auxiliaryFrameLength - 1] != CupBatchProtocolV1.tail[1]
                ) {
                    stats = stats.withInvalidTail()
                    discardFirst(1)
                    continue
                }
                buffer.removeFirst(CupBatchProtocolV1.auxiliaryFrameLength)
                stats = stats.withAuxiliaryFrame()
                continue
            }

            if (function != CupBatchProtocolV1.batchFunction) {
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

            val wire = buffer.toByteArray(totalLength)
            frames += decodeCupBatchFrame(wire)
            detectedDataLength = dataLength
            stats = stats.withFrame()
            buffer.removeFirst(totalLength)
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
        buffer.removeFirst(count)
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
