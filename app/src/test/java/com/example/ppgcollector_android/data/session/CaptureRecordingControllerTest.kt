package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.ble.BleConnectionPhase
import com.example.ppgcollector_android.core.ble.BleRawNotificationChunk
import com.example.ppgcollector_android.core.protocol.CupBatchFrame
import com.example.ppgcollector_android.core.protocol.CupPpgSample
import com.example.ppgcollector_android.core.protocol.encodeCupBatchFrame
import com.example.ppgcollector_android.core.signal.StreamFreshness
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureRecordingControllerTest {
    @Test
    fun acceptedRawChunkIsDecodedAndWrittenThroughSingleController() {
        val root = Files.createTempDirectory("capture-controller")
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
                    connectionGeneration = 7,
                    availableBytes = Long.MAX_VALUE,
                ),
            )
            assertTrue(
                controller.onRawChunk(
                    BleRawNotificationChunk(7, 123L, encodeCupBatchFrame(frame())),
                ),
            )
            controller.stop(CaptureStopReason.USER)
            val summary = controller.awaitFinalized(5, TimeUnit.SECONDS)
            assertNotNull(summary)
            assertEquals(CaptureStopReason.USER, summary!!.stopReason)
            assertEquals(1, summary.writer.rawChunkCount)
            assertEquals(50, summary.writer.csvRows)
            assertEquals(50L, CaptureRawSessionReaders.sampleRows(summary.directory))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun staleGenerationIsRejectedAndQueueOverflowWinsAsResourceStop() {
        val root = Files.createTempDirectory("capture-overflow")
        val gate = CountDownLatch(1)
        try {
            val controller = CaptureRecordingController(
                sessionsRoot = root,
                capacityProvider = CaptureStorageCapacityProvider { Long.MAX_VALUE },
                queueCapacity = 1,
                workerStartGate = gate,
            )
            assertEquals(
                CaptureRecordingStartResult.Started,
                controller.start(
                    configuration(),
                    BleConnectionPhase.Subscribed("device"),
                    StreamFreshness.FRESH,
                    connectionGeneration = 9,
                    availableBytes = Long.MAX_VALUE,
                ),
            )
            val chunk = BleRawNotificationChunk(9, 1L, byteArrayOf(1))
            assertFalse(controller.onRawChunk(chunk.copy(connectionGeneration = 8)))
            assertTrue(controller.onRawChunk(chunk))
            assertFalse(controller.onRawChunk(chunk.copy(hostMonotonicNanos = 2L)))
            gate.countDown()
            val summary = controller.awaitFinalized(5, TimeUnit.SECONDS)
            assertNotNull(summary)
            assertEquals(CaptureStopReason.RESOURCE_PRESSURE, summary!!.stopReason)
            assertTrue(controller.snapshot.queueOverflowCount > 0)
        } finally {
            gate.countDown()
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun acceptedEightSecondWindowProducesObservableAnalysisResultWithoutBlockingRawFinalize() {
        val root = Files.createTempDirectory("capture-analysis")
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
                    connectionGeneration = 11,
                    availableBytes = Long.MAX_VALUE,
                ),
            )
            repeat(16) { frameIndex ->
                assertTrue(
                    controller.onRawChunk(
                        BleRawNotificationChunk(
                            11,
                            frameIndex.toLong() * 500_000_000L,
                            encodeCupBatchFrame(analysisFrame(frameIndex)),
                        ),
                    ),
                )
            }
            controller.stop(CaptureStopReason.USER)
            assertNotNull(controller.awaitFinalized(10, TimeUnit.SECONDS))
            assertEquals(CaptureRecordingState.FINALIZED, controller.snapshotFlow.value.state)
            val analysis = controller.analysisSnapshot.value
            assertNotNull(analysis.lastResult)
            assertEquals(799L, analysis.lastResult!!.request.windowEndSampleIndex)
            assertEquals(800, analysis.lastResult!!.request.rawIr.size)
            assertTrue(
                analysis.lastResult!!.snapshot.heartRateBpm.algorithmVersion.isNotEmpty(),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun configuration() = CaptureSessionConfiguration(
        sessionId = "controller-session-id",
        baseName = "controller_001",
        startedUtc = Instant.parse("2026-08-02T00:00:00Z"),
        softVersion = "android-test",
        algorithmVersion = "unavailable",
        preprocessProfile = "ios_v1",
        protocolProfile = "cup_v1",
        transportProfile = "ble_gatt_v1",
        device = CaptureDeviceContext("CUP", "device-id", "service", "notify"),
    )

    private fun frame() = CupBatchFrame(
        sequence = 1u,
        samples = List(50) { CupPpgSample(100u + it.toUInt(), 200u + it.toUInt()) },
    )

    private fun analysisFrame(frameIndex: Int) = CupBatchFrame(
        sequence = frameIndex.toUByte(),
        samples = List(50) { sampleInFrame ->
            val index = frameIndex * 50 + sampleInFrame
            val pulse = (kotlin.math.sin(index * 2.0 * Math.PI / 25.0) * 120.0).toUInt()
            CupPpgSample(10_000u + pulse, 20_000u + pulse)
        },
    )
}

private object CaptureRawSessionReaders {
    fun sampleRows(directory: java.nio.file.Path): Long =
        Files.readAllLines(directory.resolve("controller_001.csv")).size.toLong() - 1
}
