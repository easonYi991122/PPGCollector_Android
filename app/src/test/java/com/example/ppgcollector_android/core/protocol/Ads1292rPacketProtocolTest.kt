package com.example.ppgcollector_android.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Ads1292rPacketProtocolTest {
    private fun packet(sequence: UInt = 7u) = Ads1292rPacket(
        sequenceNumber = sequence,
        ecg = List(20) { (1_000 + it).toUInt() },
        red = List(4) { (2_000 + it).toUInt() },
        ir = List(4) { (3_000 + it).toUInt() },
    )

    @Test
    fun legalFrameRoundTripsAndDecoderAcceptsFragments() {
        val wire = Ads1292rPacketProtocol.encode(packet())
        assertEquals(120, wire.size)
        val decoder = Ads1292rStreamDecoder()
        assertTrue(decoder.feed(wire.copyOfRange(0, 17)).isEmpty())
        val decoded = decoder.feed(wire.copyOfRange(17, wire.size))
        assertEquals(listOf(packet()), decoded)
    }

    @Test
    fun badTailIsDiscardedAndSequenceGapIsCounted() {
        val decoder = Ads1292rStreamDecoder()
        val bad = Ads1292rPacketProtocol.encode(packet()).also { it[119] = 0 }
        assertTrue(decoder.feed(bad).isEmpty())
        decoder.feed(
            Ads1292rPacketProtocol.encode(packet(1u)) +
                Ads1292rPacketProtocol.encode(packet(3u)),
        )
        assertEquals(1, decoder.stats.sequenceGaps)
        assertEquals(1, decoder.stats.invalidTails)
    }
}
