package com.example.ppgcollector_android.core.ble

import com.example.ppgcollector_android.core.protocol.Ads1292rPacket
import com.example.ppgcollector_android.core.protocol.Ads1292rPacketProtocol
import com.example.ppgcollector_android.core.protocol.CupBatchFrame
import com.example.ppgcollector_android.core.protocol.CupBatchProtocolV1
import com.example.ppgcollector_android.core.protocol.CupPpgSample
import com.example.ppgcollector_android.core.protocol.CupStreamProtocolMode
import com.example.ppgcollector_android.core.protocol.encodeCupBatchFrame
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlePreviewRuntimeTest {
    @Test
    fun idleRuntimeDoesNotStartWorkersOrClockTicksUntilDataArrives() {
        val runtime = BlePreviewRuntime(clockTickIntervalNanos = 10_000_000L)
        try {
            Thread.sleep(80)
            val idle = runtime.diagnostics()
            assertTrue(!idle.previewWorkerActive)
            assertTrue(!idle.analysisWorkerActive)
            assertEquals(0L, idle.clockTickCount)

            runtime.reset(generation = 1)
            runtime.offer(
                BleRawNotificationChunk(
                    connectionGeneration = 1,
                    hostMonotonicNanos = 1L,
                    bytes = encodeCupBatchFrame(frame(1u)),
                ),
            )
            awaitTrue { runtime.diagnostics().previewWorkerActive }
            runtime.suspend()
            awaitTrue { !runtime.diagnostics().previewWorkerActive }
        } finally {
            runtime.close()
        }
    }

    @Test
    fun acceptedFrameCallbackOnlyFiresAfterAValidDecodedFrame() {
        val acceptedCallbacks = AtomicInteger()
        val acceptedGeneration = AtomicLong(-1L)
        val runtime = BlePreviewRuntime(
            onAcceptedFrame = { generation ->
                acceptedGeneration.set(generation)
                acceptedCallbacks.incrementAndGet()
            },
        )
        try {
            runtime.reset(generation = 1)
            runtime.offer(
                BleRawNotificationChunk(
                    connectionGeneration = 1,
                    hostMonotonicNanos = 1L,
                    bytes = byteArrayOf(0x01, 0x02, 0x03),
                ),
            )
            Thread.sleep(20)
            assertEquals(0, acceptedCallbacks.get())

            runtime.offer(
                BleRawNotificationChunk(
                    connectionGeneration = 1,
                    hostMonotonicNanos = 2L,
                    bytes = encodeCupBatchFrame(frame(1u)),
                ),
            )
            awaitTrue { acceptedCallbacks.get() == 1 }
            assertEquals(1L, acceptedGeneration.get())
        } finally {
            runtime.close()
        }
    }

    @Test
    fun previewWorkerDecodesBoundedStreamAndSurvivesRecordingSinkChanges() {
        val runtime = BlePreviewRuntime()
        try {
            runtime.reset(generation = 7)
            repeat(800 / CupBatchProtocolV1.samplesPerFrame) { frameIndex ->
                val wire = encodeCupBatchFrame(frame(frameIndex.toUByte()))
                val splitOffset = CupBatchProtocolV1.frameLength * 3 / 5
                val frameNanos = frameIndex.toLong() * CupBatchProtocolV1.samplesPerFrame *
                    1_000_000_000L / CupBatchProtocolV1.sampleRateHz
                assertTrue(
                    runtime.offer(
                        BleRawNotificationChunk(
                            connectionGeneration = 7,
                            hostMonotonicNanos = frameNanos,
                            bytes = wire.copyOfRange(0, splitOffset),
                        ),
                    ),
                )
                assertTrue(
                    runtime.offer(
                        BleRawNotificationChunk(
                            connectionGeneration = 7,
                            hostMonotonicNanos = frameNanos + 1_000_000L,
                            bytes = wire.copyOfRange(splitOffset, wire.size),
                        ),
                    ),
                )
            }
            awaitTrue {
                runtime.snapshot.value.waveform.red.size == 800 &&
                    runtime.snapshot.value.lastAnalysis != null
            }
            val snapshot = runtime.snapshot.value
            assertEquals(7L, snapshot.connectionGeneration)
            assertEquals(800, snapshot.waveform.ir.size)
            assertEquals(800, snapshot.waveform.causalRed.size)
            assertEquals(800, snapshot.waveform.causalIr.size)
            assertEquals("ios_baseline_0.1", snapshot.waveform.preprocessProfile)
            assertEquals(
                snapshot.lastAnalysis!!.request.bandpassedIr,
                snapshot.waveform.causalIr.asList(),
            )
            assertEquals(799L, snapshot.lastAnalysis!!.request.windowEndSampleIndex)
            assertEquals(0L, snapshot.droppedChunkCount)

            runtime.reset(generation = 8)
            assertEquals(8L, runtime.snapshot.value.connectionGeneration)
            assertEquals(0, runtime.snapshot.value.waveform.red.size)
            assertEquals(null, runtime.snapshot.value.lastAnalysis)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun twentyFiveAdsFramesInOneSecondPublishAboutFiveWaveforms() {
        val runtime = BlePreviewRuntime()
        try {
            runtime.reset(generation = 3, streamProtocolMode = CupStreamProtocolMode.ADS1292R_120)
            repeat(25) { index ->
                assertTrue(
                    runtime.offer(
                        BleRawNotificationChunk(
                            connectionGeneration = 3,
                            hostMonotonicNanos = index * 40_000_000L,
                            bytes = Ads1292rPacketProtocol.encode(adsPacket(index.toUInt())),
                            streamProtocolMode = CupStreamProtocolMode.ADS1292R_120,
                        ),
                    ),
                )
            }
            Thread.sleep(1_000)
            val sequence = runtime.snapshot.value.waveform.publicationSequence
            assertTrue("publicationSequence=$sequence", sequence in 4L..8L)
            assertTrue(runtime.snapshot.value.waveform.ecg.isNotEmpty())
        } finally {
            runtime.close()
        }
    }

    @Test
    fun ordinaryAdsSequenceGapDoesNotDiscardReadyFixedLagDisplay() {
        val runtime = BlePreviewRuntime()
        try {
            runtime.reset(generation = 4, streamProtocolMode = CupStreamProtocolMode.ADS1292R_120)
            repeat(80) { index ->
                assertTrue(
                    runtime.offer(
                        BleRawNotificationChunk(
                            connectionGeneration = 4,
                            hostMonotonicNanos = index * 40_000_000L,
                            bytes = Ads1292rPacketProtocol.encode(adsPacket(index.toUInt())),
                            streamProtocolMode = CupStreamProtocolMode.ADS1292R_120,
                        ),
                    ),
                )
            }
            awaitTrue { runtime.snapshot.value.waveform.fixedLagRed.isNotEmpty() }
            val fixedBeforeGap = runtime.snapshot.value.waveform.fixedLagRed.size

            runtime.offer(
                BleRawNotificationChunk(
                    connectionGeneration = 4,
                    hostMonotonicNanos = 81L * 40_000_000L,
                    bytes = Ads1292rPacketProtocol.encode(adsPacket(81u)),
                    streamProtocolMode = CupStreamProtocolMode.ADS1292R_120,
                ),
            )

            awaitTrue { runtime.snapshot.value.waveform.gapCount == 1L }
            val afterGap = runtime.snapshot.value.waveform
            assertTrue(afterGap.fixedLagRed.size >= fixedBeforeGap)
            assertTrue(afterGap.red.isNotEmpty())
            assertTrue(afterGap.ir.isNotEmpty())
            assertTrue(afterGap.ecg.isNotEmpty())
        } finally {
            runtime.close()
        }
    }

    private fun adsPacket(sequence: UInt) = Ads1292rPacket(
        sequenceNumber = sequence,
        ecg = List(20) { it.toUInt() },
        red = List(4) { 100u + it.toUInt() },
        ir = List(4) { 200u + it.toUInt() },
    )

    private fun awaitTrue(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline && !predicate()) {
            Thread.sleep(1)
        }
        assertTrue(predicate())
    }

    private fun frame(sequence: UByte) = CupBatchFrame(
        sequence = sequence,
        samples = List(CupBatchProtocolV1.samplesPerFrame) { index ->
            CupPpgSample(
                red = (10_000 + sequence.toInt() * CupBatchProtocolV1.samplesPerFrame + index).toUInt(),
                ir = (20_000 + sequence.toInt() * CupBatchProtocolV1.samplesPerFrame + index).toUInt(),
            )
        },
    )
}
