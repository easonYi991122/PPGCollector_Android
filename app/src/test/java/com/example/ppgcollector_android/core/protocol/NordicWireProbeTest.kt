package com.example.ppgcollector_android.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class NordicWireProbeTest {
    @Test
    fun three120FramesLockAds1292r() {
        val probe = NordicWireProbe()
        repeat(3) { probe.feed(Ads1292rPacketProtocol.encode(packet(it.toUInt()))) }
        assertEquals(CupStreamProtocolMode.ADS1292R_120, probe.result.locked)
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
    }

    private fun packet(sequence: UInt) = Ads1292rPacket(
        sequenceNumber = sequence,
        ecg = List(20) { it.toUInt() },
        red = List(4) { it.toUInt() },
        ir = List(4) { it.toUInt() },
    )
}
