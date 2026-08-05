package com.example.ppgcollector_android.core.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CupSensorPacketProtocolTest {
    @Test
    fun sensorPacketUsesFrozenOffsetsAndPreservesUInt32Sequence() {
        val frame = sensorFrame(0x7856_3412u)

        val wire = encodeCupSensorPacketFrame(frame)

        assertEquals(168, wire.size)
        assertArrayEquals(byteArrayOf(0xAB.toByte(), 0xBA.toByte()), wire.copyOfRange(0, 2))
        assertArrayEquals(
            byteArrayOf(0x12, 0x34, 0x56, 0x78),
            wire.copyOfRange(2, 6),
        )
        assertArrayEquals(byteArrayOf(0xCD.toByte(), 0xDC.toByte()), wire.copyOfRange(166, 168))

        val decoded = decodeCupSensorPacketFrame(wire)
        assertEquals(0x7856_3412u, decoded.sequenceNumber)
        assertEquals(0x12u.toUByte(), decoded.sequence)
        assertEquals(CupWireFrameProfile.SENSOR_PACKET_168, decoded.wireProfile)
        assertEquals(CupSensorPacketProtocolV1.profileIdentifier, decoded.protocolProfileIdentifier)
        assertEquals(frame.samples, decoded.samples)
        assertEquals(10_000u, decoded.samples.first().red)
        assertEquals(20_000u, decoded.samples.first().ir)
        assertEquals(10_019u, decoded.samples.last().red)
        assertEquals(20_019u, decoded.samples.last().ir)
    }

    @Test
    fun streamDecoderHandlesNoiseFragmentationConcatenationAndBadTail() {
        val first = encodeCupSensorPacketFrame(sensorFrame(0x1234_5678u))
        val second = encodeCupSensorPacketFrame(sensorFrame(0x1234_5679u))
        val damaged = first.copyOf().also { it[166] = 0 }
        val decoder = CupBatchStreamDecoder(
            protocolMode = CupStreamProtocolMode.SENSOR_PACKET_168,
        )

        assertTrue(decoder.feed(byteArrayOf(9, 8, 7) + first.copyOfRange(0, 83)).isEmpty())
        val decoded = decoder.feed(
            first.copyOfRange(83, first.size) + damaged + second,
        )

        assertEquals(listOf(0x1234_5678u, 0x1234_5679u), decoded.map { it.sequenceNumber })
        assertEquals(2, decoder.stats.frames)
        assertEquals(1, decoder.stats.invalidTail)
        assertTrue(decoder.stats.bytesDiscarded >= 3)
        assertEquals(CupSensorPacketProtocolV1.profileIdentifier, decoder.detectedProtocolProfile)
        assertEquals(0, decoder.pendingByteCount)
    }

    @Test
    fun sequenceTrackerUsesUInt32ModuloWithoutTruncatingToLowByte() {
        val tracker = CupFrameSequenceTracker()

        assertEquals(CupSequenceEvent.First, tracker.observe(sensorFrame(UInt.MAX_VALUE - 1u)))
        assertEquals(CupSequenceEvent.Continuous, tracker.observe(sensorFrame(UInt.MAX_VALUE)))
        assertEquals(CupSequenceEvent.Continuous, tracker.observe(sensorFrame(0u)))
        assertEquals(CupSequenceEvent.Gap(1), tracker.observe(sensorFrame(2u)))
        assertEquals(CupSequenceEvent.Duplicate, tracker.observe(sensorFrame(2u)))
        assertEquals(CupSequenceEvent.OutOfOrder, tracker.observe(sensorFrame(1u)))

        assertEquals(2u, tracker.stats.previousSequenceNumber)
        assertEquals(32, tracker.stats.sequenceBitWidth)
        assertEquals(1, tracker.stats.missingFrames)
        assertEquals(CupSensorPacketProtocolV1.samplesPerFrame, tracker.stats.missingSamples)
    }

    private fun sensorFrame(sequence: UInt) = CupBatchFrame(
        sequence = sequence.toUByte(),
        sequenceNumber = sequence,
        wireProfile = CupWireFrameProfile.SENSOR_PACKET_168,
        samples = List(CupSensorPacketProtocolV1.samplesPerFrame) { index ->
            CupPpgSample(10_000u + index.toUInt(), 20_000u + index.toUInt())
        },
    )
}
