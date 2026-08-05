package com.example.ppgcollector_android.core.protocol

/**
 * Wire profile observed from the `Nordic_UART_Service` NUS device.
 *
 * The packet is also 168 bytes, but unlike [CupBatchProtocolV1] it has no
 * function/length fields and carries a 32-bit little-endian sequence number.
 */
object CupSensorPacketProtocolV1 {
    const val profileIdentifier = "cup-sensor-168-planar-u32seq-0.1"
    const val frameLength = 168
    const val sequenceOffset = 2
    const val sequenceLength = 4
    const val samplesPerFrame = 20
    const val sampleWordLength = 4
    const val channelDataLength = samplesPerFrame * sampleWordLength
    const val redDataOffset = sequenceOffset + sequenceLength
    const val irDataOffset = redDataOffset + channelDataLength
    const val tailOffset = irDataOffset + channelDataLength
}

enum class CupWireFrameProfile(
    val identifier: String,
    val sequenceBitWidth: Int,
) {
    BATCH_168(CupBatchProtocolV1.profileIdentifier, 8),
    BATCH_408_LEGACY(CupBatchProtocolV1.legacyProfileIdentifier, 8),
    SENSOR_PACKET_168(CupSensorPacketProtocolV1.profileIdentifier, 32),
    ;

    companion object {
        fun batchProfileForSampleCount(sampleCount: Int): CupWireFrameProfile =
            if (sampleCount == CupBatchProtocolV1.samplesPerFrame) BATCH_168
            else BATCH_408_LEGACY
    }
}

/** Explicit decoder selection made from the connected device identity. */
enum class CupStreamProtocolMode(val configuredProfileIdentifier: String) {
    BATCH_COMPATIBLE(CupBatchProtocolV1.profileIdentifier),
    SENSOR_PACKET_168(CupSensorPacketProtocolV1.profileIdentifier),
    ;

    companion object {
        fun fromProtocolProfileIdentifier(identifier: String?): CupStreamProtocolMode =
            if (identifier == CupSensorPacketProtocolV1.profileIdentifier) SENSOR_PACKET_168
            else BATCH_COMPATIBLE
    }
}

fun encodeCupSensorPacketFrame(frame: CupBatchFrame): ByteArray {
    require(frame.wireProfile == CupWireFrameProfile.SENSOR_PACKET_168) {
        "sensor packet encoder requires the sensor packet wire profile"
    }
    require(frame.samples.size == CupSensorPacketProtocolV1.samplesPerFrame) {
        "sensor packet encoder requires ${CupSensorPacketProtocolV1.samplesPerFrame} samples"
    }
    val wire = ByteArray(CupSensorPacketProtocolV1.frameLength)
    CupBatchProtocolV1.header.copyInto(wire, 0)
    writeSensorUInt32Le(wire, CupSensorPacketProtocolV1.sequenceOffset, frame.sequenceNumber)
    frame.samples.forEachIndexed { index, sample ->
        writeSensorUInt32Le(
            wire,
            CupSensorPacketProtocolV1.redDataOffset + index * CupSensorPacketProtocolV1.sampleWordLength,
            sample.red,
        )
        writeSensorUInt32Le(
            wire,
            CupSensorPacketProtocolV1.irDataOffset + index * CupSensorPacketProtocolV1.sampleWordLength,
            sample.ir,
        )
    }
    CupBatchProtocolV1.tail.copyInto(wire, CupSensorPacketProtocolV1.tailOffset)
    return wire
}

fun decodeCupSensorPacketFrame(wire: ByteArray): CupBatchFrame {
    if (wire.size != CupSensorPacketProtocolV1.frameLength) {
        throw CupProtocolException("invalid sensor packet length")
    }
    if (!wire.copyOfRange(0, 2).contentEquals(CupBatchProtocolV1.header)) {
        throw CupProtocolException("invalid header")
    }
    if (!wire.copyOfRange(CupSensorPacketProtocolV1.tailOffset, wire.size)
            .contentEquals(CupBatchProtocolV1.tail)
    ) {
        throw CupProtocolException("invalid tail")
    }
    val sequenceNumber = readSensorUInt32Le(wire, CupSensorPacketProtocolV1.sequenceOffset)
    val samples = List(CupSensorPacketProtocolV1.samplesPerFrame) { index ->
        CupPpgSample(
            red = readSensorUInt32Le(
                wire,
                CupSensorPacketProtocolV1.redDataOffset + index * CupSensorPacketProtocolV1.sampleWordLength,
            ),
            ir = readSensorUInt32Le(
                wire,
                CupSensorPacketProtocolV1.irDataOffset + index * CupSensorPacketProtocolV1.sampleWordLength,
            ),
        )
    }
    return CupBatchFrame(
        sequence = sequenceNumber.toUByte(),
        samples = samples,
        sequenceNumber = sequenceNumber,
        wireProfile = CupWireFrameProfile.SENSOR_PACKET_168,
    )
}

private fun writeSensorUInt32Le(target: ByteArray, offset: Int, value: UInt) {
    target[offset] = value.toByte()
    target[offset + 1] = (value shr 8).toByte()
    target[offset + 2] = (value shr 16).toByte()
    target[offset + 3] = (value shr 24).toByte()
}

private fun readSensorUInt32Le(source: ByteArray, offset: Int): UInt =
    (source[offset].toUInt() and 0xFFu) or
        ((source[offset + 1].toUInt() and 0xFFu) shl 8) or
        ((source[offset + 2].toUInt() and 0xFFu) shl 16) or
        ((source[offset + 3].toUInt() and 0xFFu) shl 24)
