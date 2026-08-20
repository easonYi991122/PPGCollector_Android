package com.example.ppgcollector_android.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NordicWireProbeTest {
    @Test
    fun three120FramesLockAds1292r() {
        val probe = NordicWireProbe()
        repeat(3) { probe.feed(Ads1292rPacketProtocol.encode(packet(it.toUInt()))) }
        assertEquals(CupStreamProtocolMode.ADS1292R_120, probe.result.locked)
        assertEquals(WireFrameGeometry.BYTES_120, probe.result.lockedGeometry)
    }

    @Test
    fun bufferShorterThan168DoesNotVote120() {
        val probe = NordicWireProbe()
        probe.feed(Ads1292rPacketProtocol.encode(packet(0u)))
        assertNull(probe.result.locked)
        assertEquals(0, probe.result.votes120)
        assertTrue(probe.result.pending)
    }

    @Test
    fun dualFooterDropsOneByteWithoutCounting() {
        val probe = NordicWireProbe()
        val wire = ByteArray(168)
        wire[0] = Ads1292rPacketProtocol.header[0]
        wire[1] = Ads1292rPacketProtocol.header[1]
        wire[118] = Ads1292rPacketProtocol.footer[0]
        wire[119] = Ads1292rPacketProtocol.footer[1]
        wire[166] = Ads1292rPacketProtocol.footer[0]
        wire[167] = Ads1292rPacketProtocol.footer[1]
        probe.feed(wire)
        assertNull(probe.result.locked)
        assertEquals(0, probe.result.votes120)
        assertEquals(0, probe.result.votes168)
        assertTrue(probe.result.ambiguousFrames >= 1)
    }

    @Test
    fun three168SensorFramesLockSensorPacket() {
        val probe = NordicWireProbe()
        repeat(3) {
            probe.feed(
                encodeCupSensorPacketFrame(
                    CupBatchFrame(
                        sequence = it.toUByte(),
                        sequenceNumber = it.toUInt(),
                        wireProfile = CupWireFrameProfile.SENSOR_PACKET_168,
                        samples = List(20) { index -> CupPpgSample(100u + index.toUInt(), 200u) },
                    ),
                ),
            )
        }
        assertEquals(CupStreamProtocolMode.SENSOR_PACKET_168, probe.result.locked)
        assertEquals(WireFrameGeometry.BYTES_168, probe.result.lockedGeometry)
    }

    private fun packet(sequence: UInt) = Ads1292rPacket(
        sequenceNumber = sequence,
        ecg = List(20) { it.toUInt() },
        red = List(4) { it.toUInt() },
        ir = List(4) { it.toUInt() },
    )
}
