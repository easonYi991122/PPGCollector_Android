package com.example.ppgcollector_android

import com.example.ppgcollector_android.core.ble.BleConnectionPhase
import com.example.ppgcollector_android.core.ble.BleRawNotificationChunk
import com.example.ppgcollector_android.core.ble.BleCoordinator
import com.example.ppgcollector_android.core.ble.FakeBleTransport
import com.example.ppgcollector_android.core.ble.FakeBleOwnerTicker
import com.example.ppgcollector_android.core.protocol.CupBatchFrame
import com.example.ppgcollector_android.core.protocol.CupPpgSample
import com.example.ppgcollector_android.core.protocol.encodeCupBatchFrame
import com.example.ppgcollector_android.core.signal.StreamFreshness
import com.example.ppgcollector_android.data.session.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CaptureServiceLifecycleTest {
    @Test fun doubleStartDuringInitializationInstallsExactlyOneSink() = startupRace("double")
    @Test fun stopDuringInitializationRetainsHandoffAndFinalizesOnce() = startupRace("stop")
    @Test fun destroyDuringInitializationRetainsOwnerUntilTerminal() = startupRace("destroy")

    @Test fun recreatedServiceCannotClaimSinkWhileDestroyedOwnerIsStillInitializing() {
        val coordinator = BleCoordinator(FakeBleTransport(), 33, ticker = FakeBleOwnerTicker())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rejected = AtomicInteger()
        try {
            Fixture(sharedCoordinator = coordinator, capacity = CaptureStorageCapacityProvider {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                Long.MAX_VALUE
            }).use { old ->
                Fixture(sharedCoordinator = coordinator, onFailure = {
                    assertEquals(CaptureStartFailure.AlreadyRecording, it)
                    rejected.incrementAndGet()
                }).use { next ->
                    try {
                        old.start()
                        assertTrue(entered.await(3, TimeUnit.SECONDS))
                        old.onOwner { old.lifecycle.destroy() }
                        next.onOwner { assertFalse(next.startOnOwner(21, "second", "B")) }
                        assertEquals(1, rejected.get())
                        assertEquals(0, next.prepared.get())
                        assertEquals(0, next.removed.get())
                        assertEquals(0, next.attached.get())
                        release.countDown()
                        old.await { old.stopped.get() == 1 }
                        next.onOwner { assertTrue(next.startOnOwner(22, "second", "B")) }
                        next.await { next.sinkAttached }
                        old.onOwner { old.lifecycle.destroy() }
                        assertNotNull(coordinator.onRawChunk)
                        assertEquals(1, next.stopped.get()) // Only the rejected startId 21.
                        assertEquals(0, next.removed.get())
                        next.onOwner { next.lifecycle.stop(CaptureStopReason.USER) }
                        next.await { next.removed.get() == 1 }
                        assertNull(coordinator.onRawChunk)
                        assertEquals(22, next.stoppedStartId)
                    } finally { release.countDown() }
                }
            }
        } finally { coordinator.close() }
    }

    private fun startupRace(action: String) {
        val initializing = CountDownLatch(1)
        val release = CountDownLatch(1)
        Fixture(capacity = CaptureStorageCapacityProvider {
            initializing.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            Long.MAX_VALUE
        }).use { fixture ->
            try {
                fixture.start()
                assertTrue(initializing.await(3, TimeUnit.SECONDS))
                fixture.onOwner {
                    assertEquals(CaptureRecordingState.STARTING, fixture.lifecycle.state.value)
                    when (action) {
                        "double" -> assertFalse(fixture.startOnOwner(startId = 2))
                        "stop" -> fixture.lifecycle.stop(CaptureStopReason.USER)
                        "destroy" -> fixture.lifecycle.destroy()
                    }
                }
                assertTrue(fixture.scope.isActive)
                assertEquals(1, fixture.prepared.get())
                release.countDown()
                fixture.await { fixture.attached.get() == 1 }
                if (action == "double") fixture.onOwner { fixture.lifecycle.stop(CaptureStopReason.USER) }
                fixture.await { fixture.stopped.get() == 1 }
                assertEquals(1, fixture.attached.get())
                assertEquals(1, fixture.detached.get())
                assertEquals(1, fixture.removed.get())
                assertEquals(1, fixture.terminalCalls.get())
                assertFalse(fixture.sinkAttached)
                assertEquals(if (action == "double") 2 else 1, fixture.stoppedStartId)
                fixture.onOwner { fixture.lifecycle.stop(CaptureStopReason.USER) }
                assertEquals(1, fixture.stopped.get())
                if (action == "destroy") assertFalse(fixture.scope.isActive)
            } finally {
                release.countDown()
            }
        }
    }

    @Test fun timedCompletionCleansServiceWithoutExplicitServiceStop() = internalCompletion("timed")
    @Test fun writeErrorCleansServiceWithoutExplicitServiceStop() = internalCompletion("write-error")
    @Test fun rawOverflowCleansServiceWithoutExplicitServiceStop() = internalCompletion("overflow")

    private fun internalCompletion(trigger: String) {
        val workerGate = CountDownLatch(if (trigger == "overflow") 1 else 0)
        Fixture(workerGate = workerGate, queueCapacity = 2).use { fixture ->
            fixture.start(if (trigger == "timed") CaptureRecordMode.TIMED else CaptureRecordMode.MANUAL)
            fixture.await { fixture.sinkAttached }
            try {
                val goodFrames = (0 until 50).map { index -> encodeCupBatchFrame(CupBatchFrame(
                    sequence = index.toUByte(), samples = List(20) { CupPpgSample(10_000u, 20_000u) })) }
                    .fold(byteArrayOf()) { bytes, frame -> bytes + frame }
                val frames = goodFrames.copyOfRange(0, 168).also { it[it.lastIndex] = 0 } + goodFrames
                if (trigger == "overflow") {
                    assertTrue(fixture.deliver(frames))
                    assertTrue(fixture.deliver(frames))
                    assertFalse(fixture.deliver(frames))
                } else {
                    assertTrue(fixture.deliver(frames))
                    if (trigger == "write-error") {
                        fixture.await { fixture.controller.snapshot.acceptedSampleCount == 1_000L }
                        assertTrue(fixture.deliver(ByteArray(CupRawFormat.maximumChunkLength + 1)))
                    }
                }
                workerGate.countDown()
                fixture.await { fixture.stopped.get() == 1 }
                assertEquals(1, fixture.detached.get())
                assertEquals(1, fixture.removed.get())
                assertEquals(1, fixture.terminalCalls.get())
                assertFalse(fixture.sinkAttached)
                val summary = fixture.controller.snapshot.summary!!
                assertEquals(when (trigger) {
                    "timed" -> CaptureStopReason.DURATION_ELAPSED
                    "overflow" -> CaptureStopReason.RESOURCE_PRESSURE
                    else -> CaptureStopReason.WRITE_ERROR
                }, summary.stopReason)
                assertEquals(trigger == "timed", summary.complete)
                val metadata = CaptureSessionMetadataCodec.decode(Files.readString(summary.directory.resolve("service_test.session.json")))
                assertTrue(metadata.invalidFrames > 0)
                assertEquals(fixture.controller.snapshot.streamDiagnostics.decoderInvalidFrameCount, metadata.invalidFrames)
                assertEquals(fixture.controller.snapshot.streamDiagnostics.decoderDiscardedByteCount, metadata.discardedBytes)
            } finally { workerGate.countDown() }
        }
    }

    @Test fun oldTerminalCannotRemoveSuccessorSinkOrStopItsStartId() {
        Fixture().use { fixture ->
            fixture.start()
            fixture.await { fixture.sinkAttached }
            fixture.onOwner { fixture.lifecycle.stop(CaptureStopReason.USER) }
            fixture.await { fixture.stopped.get() == 1 }
            fixture.onOwner { fixture.startOnOwner(startId = 22, name = "second", sessionId = "B") }
            fixture.await { fixture.attached.get() == 2 }
            assertTrue(fixture.sinkAttached)
            assertEquals(1, fixture.removed.get())
            fixture.onOwner { fixture.lifecycle.stop(CaptureStopReason.USER) }
            fixture.await { fixture.stopped.get() == 2 }
            assertEquals(22, fixture.stoppedStartId)
        }
    }

    @Test fun finalForceFailureStillDetachesAndPublishesVisibleTerminalError() {
        Fixture(forceFailure = true).use { fixture ->
            fixture.start()
            fixture.await { fixture.sinkAttached }
            fixture.onOwner { fixture.lifecycle.stop(CaptureStopReason.USER) }
            fixture.await { fixture.stopped.get() == 1 }
            assertEquals(1, fixture.detached.get())
            assertEquals(1, fixture.terminalCalls.get())
            assertEquals(CaptureRecordingState.FAILED, fixture.lifecycle.recording.value.state)
            assertTrue(fixture.lifecycle.recording.value.lastError!!.contains("final force failed"))
            val metadata = CaptureSessionMetadataCodec.decode(Files.readString(fixture.root.resolve("service_test/service_test.session.json")))
            assertFalse(metadata.complete)
            assertTrue(metadata.writer.error!!.contains("final force failed"))
        }
    }

    @Test fun delayedProfileSuccessAndFailureCannotMutateOrStopSuccessor() {
        for (failSave in listOf(false, true)) {
            Fixture().use { fixture ->
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                var saveJob: Job? = null
                try {
                    fixture.start()
                    fixture.await { fixture.sinkAttached }
                    fixture.onOwner {
                        saveJob = fixture.lifecycle.persistParticipant("A") {
                            entered.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                            if (failSave) throw java.io.IOException("late profile A failure")
                            CaptureParticipantSnapshot(subjectId = "S001", profileRevisionId = "late-A", profileComplete = false)
                        }
                    }
                    assertTrue(entered.await(3, TimeUnit.SECONDS))
                    fixture.onOwner { fixture.lifecycle.stop(CaptureStopReason.USER) }
                    fixture.await { fixture.stopped.get() == 1 }
                    fixture.onOwner { fixture.startOnOwner(2, "second", "B") }
                    fixture.await { fixture.attached.get() == 2 }
                    release.countDown()
                    runBlocking { saveJob!!.join() }
                    assertEquals(CaptureRecordingState.RECORDING, fixture.controller.snapshot.state)
                    assertNull(fixture.controller.snapshot.lastError)
                    fixture.onOwner { fixture.lifecycle.stop(CaptureStopReason.USER) }
                    fixture.await { fixture.stopped.get() == 2 }
                    val metadata = CaptureSessionMetadataCodec.decode(Files.readString(fixture.root.resolve("second/second.session.json")))
                    assertNull(metadata.participant)
                } finally { release.countDown() }
            }
        }
    }

    @Test fun healthGraceUsesMonotonicClockAndResetsForEachSession() {
        val transport = com.example.ppgcollector_android.core.ble.FakeBleTransport()
        val coordinator = com.example.ppgcollector_android.core.ble.BleCoordinator(transport, 33,
            ticker = com.example.ppgcollector_android.core.ble.FakeBleOwnerTicker())
        try {
            var millis = 1_000L
            val health = CaptureStreamHealthMonitor({ millis })
            val recording = CaptureRecordingSnapshot(state = CaptureRecordingState.RECORDING,
                sessionId = "A", sessionToken = 1, connectionGeneration = 9)
            val ble = coordinator.snapshot.copy(connectionGeneration = 9,
                phase = BleConnectionPhase.Receiving("device"), freshness = StreamFreshness.FRESH)
            repeat(15) {
                millis += 1_000
                assertNull(health.stopReason(recording, ble))
            }
            val stale = ble.copy(freshness = StreamFreshness.STALE)
            assertNull(health.stopReason(recording, stale))
            millis += 4_999
            assertNull(health.stopReason(recording, stale))
            millis++
            assertEquals(CaptureStopReason.DATA_TIMEOUT, health.stopReason(recording, stale))
            assertNull(health.stopReason(recording.copy(sessionId = "B", sessionToken = 2), stale))
            assertEquals(CaptureStopReason.DEVICE_DISCONNECT, health.stopReason(recording, stale.copy(connectionGeneration = 10)))
        } finally { coordinator.close() }
    }

    private class Fixture(
        capacity: CaptureStorageCapacityProvider = CaptureStorageCapacityProvider { Long.MAX_VALUE },
        workerGate: CountDownLatch? = null,
        queueCapacity: Int = 256,
        forceFailure: Boolean = false,
        private val sharedCoordinator: BleCoordinator? = null,
        onFailure: (CaptureStartFailure) -> Unit = { fail("unexpected start failure: $it") },
    ) : AutoCloseable {
        val root = Files.createTempDirectory("capture-service-lifecycle")
        private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val controller = CaptureRecordingController(root, capacity, queueCapacity = queueCapacity, workerStartGate = workerGate,
            writerFactory = { config, dir, storage ->
                CaptureSessionWriter(config, dir, storage, CaptureSessionAccessRegistry()) { path ->
                    if (forceFailure && path.fileName.toString().endsWith(".csv")) throw java.io.IOException("final force failed")
                }
            })
        val prepared = AtomicInteger()
        val attached = AtomicInteger()
        val detached = AtomicInteger()
        val removed = AtomicInteger()
        val stopped = AtomicInteger()
        val terminalCalls = AtomicInteger()
        @Volatile var sinkAttached = false
        @Volatile var stoppedStartId = 0
        private var sinkToken: Any? = null
        private val coordinator = sharedCoordinator ?: BleCoordinator(FakeBleTransport(), 33,
            ticker = FakeBleOwnerTicker())
        val lifecycle = runBlocking(dispatcher) {
            CaptureServiceLifecycle(controller, scope,
                claimRecordingOwner = coordinator::tryAcquireRecordingOwner,
                releaseRecordingOwner = coordinator::releaseRecordingOwner,
                attachSink = { token ->
                    coordinator.attachRecordingSink(token) { controller.onRawChunk(it) }
                    sinkToken = token; sinkAttached = true; attached.incrementAndGet()
                },
                detachSink = { token ->
                    assertSame(sinkToken, token)
                    coordinator.detachRecordingSink(token)
                    sinkAttached = false
                    detached.incrementAndGet()
                },
                removeForeground = { removed.incrementAndGet() },
                stopSelf = { id -> stoppedStartId = id; stopped.incrementAndGet() },
                onFailure = onFailure,
                onTerminal = { terminalCalls.incrementAndGet() })
        }

        fun onOwner(action: () -> Unit) = runBlocking(dispatcher) { action() }
        fun start(mode: CaptureRecordMode = CaptureRecordMode.MANUAL) = onOwner { startOnOwner(mode = mode) }
        fun startOnOwner(startId: Int = 1, name: String = "service_test", sessionId: String = "A",
            mode: CaptureRecordMode = CaptureRecordMode.MANUAL): Boolean = lifecycle.start(
            startId, sessionId,
            prepareForeground = { prepared.incrementAndGet() },
            initialize = {
                controller.start(CaptureSessionConfiguration(sessionId, name, Instant.EPOCH, "test", "unavailable",
                    "ios_v1", "cup_v1", "ble_gatt_v1", CaptureDeviceContext("CUP", "device", "service", "notify"),
                    recordMode = mode, plannedDurationSeconds = if (mode == CaptureRecordMode.TIMED) 10 else null),
                    BleConnectionPhase.Receiving("device"), StreamFreshness.FRESH, 1, availableBytes = Long.MAX_VALUE)
            }, onAccepted = {})

        fun deliver(bytes: ByteArray): Boolean {
            check(sinkAttached)
            return controller.onRawChunk(BleRawNotificationChunk(1, 1, bytes))
        }

        fun await(predicate: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!predicate() && System.nanoTime() < deadline) Thread.yield()
            assertTrue(predicate())
            onOwner {} // Also drain effects following the observed counter.
        }

        override fun close() {
            onOwner { lifecycle.destroy() }
            controller.close()
            onOwner {}
            scope.cancel()
            dispatcher.close()
            if (sharedCoordinator == null) coordinator.close()
            root.toFile().deleteRecursively()
        }
    }
}
