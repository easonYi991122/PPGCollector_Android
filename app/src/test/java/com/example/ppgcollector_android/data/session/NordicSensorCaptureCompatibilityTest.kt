package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.ble.BleConnectionPhase
import com.example.ppgcollector_android.core.ble.BleRawNotificationChunk
import com.example.ppgcollector_android.core.protocol.CupBatchFrame
import com.example.ppgcollector_android.core.protocol.CupPpgSample
import com.example.ppgcollector_android.core.protocol.CupSensorPacketProtocolV1
import com.example.ppgcollector_android.core.protocol.CupStreamProtocolMode
import com.example.ppgcollector_android.core.protocol.CupWireFrameProfile
import com.example.ppgcollector_android.core.protocol.encodeCupSensorPacketFrame
import com.example.ppgcollector_android.core.signal.StreamFreshness
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NordicSensorCaptureCompatibilityTest {
    @Test
    fun recordingPreservesUInt32SequenceAndMetadataSelectsSensorReplay() {
        val root = Files.createTempDirectory("nordic-sensor-capture")
        try {
            val controller = CaptureRecordingController(
                sessionsRoot = root,
                capacityProvider = CaptureStorageCapacityProvider { Long.MAX_VALUE },
            )
            assertEquals(
                CaptureRecordingStartResult.Started,
                controller.start(
                    configuration(),
                    BleConnectionPhase.Receiving("device"),
                    StreamFreshness.FRESH,
                    connectionGeneration = 41,
                    availableBytes = Long.MAX_VALUE,
                ),
            )
            listOf(0x1234_5678u, 0x1234_5679u).forEachIndexed { index, sequence ->
                assertTrue(
                    controller.onRawChunk(
                        BleRawNotificationChunk(
                            connectionGeneration = 41,
                            hostMonotonicNanos = 1_000L + index,
                            bytes = encodeCupSensorPacketFrame(sensorFrame(sequence)),
                            streamProtocolMode = CupStreamProtocolMode.SENSOR_PACKET_168,
                        ),
                    ),
                )
            }
            controller.stop(CaptureStopReason.USER)
            val summary = controller.awaitFinalized(5, TimeUnit.SECONDS)
            assertNotNull(summary)
            val directory = summary!!.directory
            val metadata = CaptureSessionMetadataCodec.decode(
                directory.resolve("sensor_capture.session.json"),
            )
            assertEquals(CupSensorPacketProtocolV1.profileIdentifier, metadata.protocolProfile)
            assertEquals(40L, metadata.sampleCount)

            val rows = Files.readAllLines(directory.resolve("sensor_capture.csv"))
                .drop(1)
                .map(CaptureCsvParser::parseRow)
            assertEquals(40, rows.size)
            assertTrue(rows.all { it.schemaVersion == CaptureCsvSchema.version2 })
            assertEquals(0x1234_5678u, rows.first().frameSequence)
            assertEquals(0x1234_5679u, rows.last().frameSequence)

            val replay = CupRawReplayEngine.replay(
                directory.resolve("sensor_capture.cupraw"),
                CupStreamProtocolMode.SENSOR_PACKET_168,
            )
            assertEquals(2, replay.acceptedFrames)
            assertEquals(40L, replay.acceptedSamples)
            assertTrue(replay.isStructurallyClean)
            assertEquals(0x1234_5679u, replay.recentSamples.last().frameSequence)

            val inspection = CaptureSessionInspectionService.inspect(directory)
            assertTrue(inspection.findings.joinToString { it.message }, inspection.isVerifiedConsistent)

            val stored = CaptureSessionRepository.listSessions(root).single()
            val trace = CaptureSessionOfflineAnalysisService.loadSignalTrace(stored)
            assertEquals(40, trace.rawRed.size)
            assertEquals(45_000.0, trace.rawRed.first(), 0.0)
            assertEquals(52_019.0, trace.rawIr.last(), 0.0)
            assertEquals(40L, trace.replay.acceptedSamples)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun configuration() = CaptureSessionConfiguration(
        sessionId = "sensor-session-id",
        baseName = "sensor_capture",
        startedUtc = Instant.parse("2026-08-05T09:04:12Z"),
        softVersion = "android-test",
        algorithmVersion = "unavailable",
        preprocessProfile = "ios-baseline-0.1",
        protocolProfile = CupSensorPacketProtocolV1.profileIdentifier,
        transportProfile = "cup-nus-bringup-0.1",
        device = CaptureDeviceContext(
            "Nordic_UART_Service",
            "device",
            "6E400001-B5A3-F393-E0A9-E50E24DCCA9E",
            "6E400003-B5A3-F393-E0A9-E50E24DCCA9E",
        ),
    )

    private fun sensorFrame(sequence: UInt) = CupBatchFrame(
        sequence = sequence.toUByte(),
        sequenceNumber = sequence,
        wireProfile = CupWireFrameProfile.SENSOR_PACKET_168,
        samples = List(CupSensorPacketProtocolV1.samplesPerFrame) { index ->
            CupPpgSample(45_000u + index.toUInt(), 52_000u + index.toUInt())
        },
    )
}
