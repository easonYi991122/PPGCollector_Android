package com.example.ppgcollector_android.core.ble

import com.example.ppgcollector_android.core.protocol.CupBatchFrame
import com.example.ppgcollector_android.core.protocol.CupPpgSample
import com.example.ppgcollector_android.core.protocol.encodeCupBatchFrame
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlePreviewRuntimeTest {
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
            repeat(16) { frameIndex ->
                assertTrue(
                    runtime.offer(
                        BleRawNotificationChunk(
                            connectionGeneration = 7,
                            hostMonotonicNanos = frameIndex * 500_000_000L,
                            bytes = encodeCupBatchFrame(frame(frameIndex.toUByte())),
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

    private fun awaitTrue(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline && !predicate()) {
            Thread.sleep(1)
        }
        assertTrue(predicate())
    }

    private fun frame(sequence: UByte) = CupBatchFrame(
        sequence = sequence,
        samples = List(50) { index ->
            CupPpgSample(
                red = (10_000 + sequence.toInt() * 50 + index).toUInt(),
                ir = (20_000 + sequence.toInt() * 50 + index).toUInt(),
            )
        },
    )
}
