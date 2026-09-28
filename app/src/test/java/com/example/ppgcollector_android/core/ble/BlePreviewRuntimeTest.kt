package com.example.ppgcollector_android.core.ble

import com.example.ppgcollector_android.core.protocol.Ads1292rPacket
import com.example.ppgcollector_android.core.protocol.Ads1292rPacketProtocol
import com.example.ppgcollector_android.core.protocol.CupBatchFrame
import com.example.ppgcollector_android.core.protocol.CupBatchProtocolV1
import com.example.ppgcollector_android.core.protocol.CupPpgSample
import com.example.ppgcollector_android.core.protocol.CupStreamProtocolMode
import com.example.ppgcollector_android.core.protocol.encodeCupBatchFrame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlePreviewRuntimeTest {
    @Test
    fun previewQueueOverflowClearsStaleWaveformAndMetricAsLocalInputLoss() {
        val blockCallbacks = AtomicBoolean(false)
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val runtime = BlePreviewRuntime(
            queueCapacity = 2,
            onAcceptedFrame = {
                if (blockCallbacks.get()) {
                    callbackEntered.countDown()
                    releaseCallback.await(2, TimeUnit.SECONDS)
                }
            },
        )
        try {
            runtime.reset(generation = 11)
            repeat(40) { frameIndex ->
                assertTrue(
                    runtime.offer(
                        BleRawNotificationChunk(
                            connectionGeneration = 11,
                            hostMonotonicNanos = frameIndex.toLong(),
                            bytes = encodeCupBatchFrame(frame(frameIndex.toUByte())),
                        ),
                    ),
                )
                awaitTrue {
                    runtime.diagnostics().receivedFrameCount == frameIndex + 1
                }
            }
            awaitTrue { runtime.snapshot.value.lastAnalysis != null }

            blockCallbacks.set(true)
            assertTrue(
                runtime.offer(
                    BleRawNotificationChunk(11, 40L, encodeCupBatchFrame(frame(40u))),
                ),
            )
            assertTrue(callbackEntered.await(2, TimeUnit.SECONDS))
            assertTrue(
                runtime.offer(
                    BleRawNotificationChunk(11, 41L, encodeCupBatchFrame(frame(41u))),
                ),
            )
            assertTrue(
                runtime.offer(
                    BleRawNotificationChunk(11, 42L, encodeCupBatchFrame(frame(42u))),
                ),
            )
            assertFalse(
                runtime.offer(
                    BleRawNotificationChunk(11, 43L, encodeCupBatchFrame(frame(43u))),
                ),
            )

            val overflow = runtime.snapshot.value
            assertEquals(3L, overflow.droppedChunkCount)
            assertEquals(3L, overflow.streamDiagnostics.appDroppedChunkCount)
            assertTrue(overflow.waveform.red.isEmpty())
            assertEquals(null, overflow.lastAnalysis)
        } finally {
            releaseCallback.countDown()
            runtime.close()
        }
    }

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
            val diagnostics = runtime.snapshot.value.streamDiagnostics
            assertEquals(81u, diagnostics.lastSequenceNumber)
            assertEquals(2L, diagnostics.lastSequenceStep)
            assertEquals(1L, diagnostics.gapEventCount)
            assertEquals(1L, diagnostics.estimatedMissingFrameCount)
            assertEquals(0L, diagnostics.appDroppedChunkCount)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun frequentAdsSequenceGapsRemainDiagnosableButDoNotBlockMetrics() {
        val runtime = BlePreviewRuntime()
        try {
            runtime.reset(generation = 5, streamProtocolMode = CupStreamProtocolMode.ADS1292R_120)
            repeat(200) { index ->
                assertTrue(
                    runtime.offer(
                        BleRawNotificationChunk(
                            connectionGeneration = 5,
                            hostMonotonicNanos = index * 40_000_000L,
                            bytes = Ads1292rPacketProtocol.encode(adsPacket((index * 2).toUInt())),
                            streamProtocolMode = CupStreamProtocolMode.ADS1292R_120,
                        ),
                    ),
                )
            }

            awaitTrue {
                runtime.snapshot.value.waveform.red.size == 800 &&
                    runtime.snapshot.value.lastAnalysis != null
            }
            val snapshot = runtime.snapshot.value
            assertEquals(800L, snapshot.waveform.continuousSampleCount)
            assertEquals(199L, snapshot.streamDiagnostics.gapEventCount)
            assertEquals(199L, snapshot.streamDiagnostics.estimatedMissingFrameCount)
            assertEquals(2L, snapshot.streamDiagnostics.lastSequenceStep)
            assertEquals(0L, snapshot.streamDiagnostics.decoderDiscardedByteCount)
            assertEquals(0L, snapshot.streamDiagnostics.appDroppedChunkCount)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun adsFramingDamageIsVisibleInLowFrequencyDiagnostics() {
        val runtime = BlePreviewRuntime()
        try {
            runtime.reset(generation = 6, streamProtocolMode = CupStreamProtocolMode.ADS1292R_120)
            val bad = Ads1292rPacketProtocol.encode(adsPacket(4u)).also { it[it.lastIndex] = 0 }
            val valid = Ads1292rPacketProtocol.encode(adsPacket(5u))
            assertTrue(
                runtime.offer(
                    BleRawNotificationChunk(
                        connectionGeneration = 6,
                        hostMonotonicNanos = 1L,
                        bytes = byteArrayOf(1, 2, 3) + bad + valid,
                        streamProtocolMode = CupStreamProtocolMode.ADS1292R_120,
                    ),
                ),
            )

            awaitTrue { runtime.snapshot.value.streamDiagnostics.decodedFrameCount == 1L }
            val diagnostics = runtime.snapshot.value.streamDiagnostics
            assertEquals(5u, diagnostics.lastSequenceNumber)
            assertTrue(diagnostics.decoderDiscardedByteCount >= 123L)
            assertEquals(1L, diagnostics.decoderInvalidFrameCount)
            assertEquals(0L, diagnostics.appDroppedChunkCount)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun resetRejectsOldAnalysisEvenWhenLocalGenerationAndRequestSequenceCollide() {
        for (newGeneration in listOf(10L, 20L)) {
            val oldEntered = CountDownLatch(1)
            val releaseOld = CountDownLatch(1)
            val newEntered = CountDownLatch(1)
            val releaseNew = CountDownLatch(1)
            val requests = java.util.concurrent.CopyOnWriteArrayList<com.example.ppgcollector_android.core.signal.LiveMetricAnalysisRequest>()
            val runtime = BlePreviewRuntime(analyze = { request ->
                requests += request
                if (requests.size == 1) {
                    oldEntered.countDown()
                    check(releaseOld.await(5, TimeUnit.SECONDS))
                } else {
                    newEntered.countDown()
                    check(releaseNew.await(5, TimeUnit.SECONDS))
                }
                com.example.ppgcollector_android.core.signal.LiveMetricAnalysisResult(request,
                    com.example.ppgcollector_android.core.signal.LiveMetricSnapshot.unavailable(true,
                        com.example.ppgcollector_android.core.signal.StreamFreshness.FRESH), null)
            })
            fun feedWindow(generation: Long, level: UInt) {
                val bytes = (0 until 40).map { index -> encodeCupBatchFrame(CupBatchFrame(index.toUByte(),
                    List(20) { CupPpgSample(level, level + 1_000u) })) }
                    .fold(byteArrayOf()) { all, part -> all + part }
                assertTrue(runtime.offer(BleRawNotificationChunk(generation, 1, bytes)))
            }
            try {
                runtime.reset(10)
                feedWindow(10, 12_000u)
                assertTrue(oldEntered.await(3, TimeUnit.SECONDS))
                runtime.reset(newGeneration)
                feedWindow(newGeneration, 22_000u)
                awaitTrue { runtime.diagnostics().analysisQueueDepth == 1 }
                releaseOld.countDown()
                assertTrue(newEntered.await(3, TimeUnit.SECONDS))
                assertEquals(requests[0].generation, requests[1].generation)
                assertEquals(requests[0].requestSequence, requests[1].requestSequence)
                assertEquals(null, runtime.snapshot.value.lastAnalysis)
                releaseNew.countDown()
                awaitTrue { runtime.snapshot.value.lastAnalysis != null }
                assertEquals(newGeneration, runtime.snapshot.value.connectionGeneration)
                assertEquals(22_000.0, runtime.snapshot.value.lastAnalysis!!.request.rawRed.first(), 0.0)
            } finally {
                releaseOld.countDown()
                releaseNew.countDown()
                runtime.close()
            }
        }
    }

    @Test fun suspensionClearsBothProtocolDecodersHalfFrames() = halfFrameDiscontinuity(overflow = false)
    @Test fun overflowClearsBothProtocolDecodersHalfFrames() = halfFrameDiscontinuity(overflow = true)

    private fun halfFrameDiscontinuity(overflow: Boolean) {
        val wires = listOf(
            CupStreamProtocolMode.BATCH_COMPATIBLE to encodeCupBatchFrame(frame(1u)),
            CupStreamProtocolMode.BATCH_COMPATIBLE to javaClass.classLoader!!
                .getResourceAsStream("protocol/legacy_golden_seq42.hex")!!.bufferedReader().use { it.readText() }
                .filterNot(Char::isWhitespace).chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
            CupStreamProtocolMode.SENSOR_PACKET_168 to com.example.ppgcollector_android.core.protocol.encodeCupSensorPacketFrame(
                frame(1u).copy(wireProfile = com.example.ppgcollector_android.core.protocol.CupWireFrameProfile.SENSOR_PACKET_168)),
            CupStreamProtocolMode.ADS1292R_120 to Ads1292rPacketProtocol.encode(adsPacket(1u)),
        )
        for ((mode, wire) in wires) {
            val prefixConsumed = CountDownLatch(1)
            val resumeWorker = CountDownLatch(1)
            val suffixConsumed = CountDownLatch(1)
            val ticks = AtomicInteger()
            val runtime = BlePreviewRuntime(queueCapacity = 2, clockTickIntervalNanos = 1, onClockTick = {
                if (ticks.getAndIncrement() == 0) {
                    prefixConsumed.countDown()
                    check(resumeWorker.await(5, TimeUnit.SECONDS))
                } else suffixConsumed.countDown()
            })
            try {
                runtime.reset(1, mode)
                assertTrue(runtime.offer(BleRawNotificationChunk(1, 1, wire.copyOfRange(0, wire.size / 2), mode)))
                assertTrue(prefixConsumed.await(3, TimeUnit.SECONDS))
                if (overflow) {
                    assertTrue(runtime.offer(BleRawNotificationChunk(1, 2, byteArrayOf(1), mode)))
                    assertTrue(runtime.offer(BleRawNotificationChunk(1, 3, byteArrayOf(2), mode)))
                    assertFalse(runtime.offer(BleRawNotificationChunk(1, 4, byteArrayOf(3), mode)))
                } else runtime.suspend()
                assertTrue(runtime.offer(BleRawNotificationChunk(1, 5, wire.copyOfRange(wire.size / 2, wire.size), mode)))
                resumeWorker.countDown()
                assertTrue(suffixConsumed.await(3, TimeUnit.SECONDS))
                assertEquals("$mode/${wire.size}/overflow=$overflow", 0L, runtime.diagnostics().processedSampleCount)
                assertEquals(0, runtime.diagnostics().receivedFrameCount)
                assertTrue(runtime.snapshot.value.waveform.red.isEmpty())
                assertTrue(runtime.offer(BleRawNotificationChunk(1, 6, wire, mode)))
                awaitTrue { runtime.diagnostics().receivedFrameCount == 1 }
                assertEquals(when (wire.size) { 120 -> 4L; 408 -> 50L; else -> 20L }, runtime.diagnostics().processedSampleCount)
            } finally {
                resumeWorker.countDown()
                runtime.close()
            }
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
