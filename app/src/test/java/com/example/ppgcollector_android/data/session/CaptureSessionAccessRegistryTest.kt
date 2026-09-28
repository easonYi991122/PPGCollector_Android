package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.ble.BleConnectionPhase
import com.example.ppgcollector_android.core.signal.StreamFreshness
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class CaptureSessionAccessRegistryTest {
    @Test fun normalizedDirectoryAcquisitionIsAtomicAndExclusiveForEveryOperation() {
        val root = Files.createTempDirectory("session-access")
        val registry = CaptureSessionAccessRegistry()
        try {
            for (access in CaptureSessionAccessRegistry.Access.entries) {
                registry.tryAcquire(root.resolve("session"), access)!!.use {
                    for (other in CaptureSessionAccessRegistry.Access.entries) {
                        assertNull(registry.tryAcquire(root.resolve("unused/../session"), other))
                    }
                    registry.tryAcquire(root.resolve("other"), access)!!.close()
                }
            }
            val go = CountDownLatch(1)
            val acquired = CountDownLatch(8)
            val release = CountDownLatch(1)
            val winners = java.util.concurrent.atomic.AtomicInteger()
            val contenders = List(8) {
                thread {
                    go.await()
                    val lease = registry.tryAcquire(root, CaptureSessionAccessRegistry.Access.DESTRUCTIVE)
                    if (lease != null) winners.incrementAndGet()
                    acquired.countDown()
                    release.await()
                    lease?.close()
                }
            }
            go.countDown()
            assertTrue(acquired.await(3, TimeUnit.SECONDS))
            assertEquals(1, winners.get())
            release.countDown()
            contenders.forEach { it.join() }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun preparingRecordingAndStoppingHoldLeaseUntilActualFinalClose() {
        val root = Files.createTempDirectory("session-writer-lease")
        val registry = CaptureSessionAccessRegistry()
        val preparing = CountDownLatch(1)
        val releasePrepare = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val controller = CaptureRecordingController(root, CaptureStorageCapacityProvider {
            preparing.countDown()
            check(releasePrepare.await(5, TimeUnit.SECONDS))
            Long.MAX_VALUE
        }, writerFactory = { config, dir, capacity ->
            CaptureSessionWriter(config, dir, capacity, registry) { path ->
                if (path.fileName.toString().endsWith(".cupraw")) {
                    closing.countDown()
                    check(releaseClose.await(5, TimeUnit.SECONDS))
                }
            }
        })
        val starter = thread {
            assertEquals(CaptureRecordingStartResult.Started, controller.start(configuration(),
                BleConnectionPhase.Receiving("device"), StreamFreshness.FRESH, 1, Long.MAX_VALUE))
        }
        fun assertBusy() {
            assertNull(registry.tryAcquire(root.resolve("session"), CaptureSessionAccessRegistry.Access.DESTRUCTIVE))
            assertNull(registry.tryAcquire(root.resolve("session"), CaptureSessionAccessRegistry.Access.READ_SNAPSHOT))
        }
        try {
            assertTrue(preparing.await(3, TimeUnit.SECONDS))
            assertEquals(CaptureRecordingState.STARTING, controller.snapshot.state)
            assertBusy()
            releasePrepare.countDown()
            starter.join(3_000)
            assertFalse(starter.isAlive)
            assertEquals(CaptureRecordingState.RECORDING, controller.snapshot.state)
            assertBusy()
            controller.stop(CaptureStopReason.USER)
            assertTrue(closing.await(3, TimeUnit.SECONDS))
            assertEquals(CaptureRecordingState.STOPPING, controller.snapshot.state)
            assertBusy()
            releaseClose.countDown()
            assertNotNull(controller.awaitFinalized(3, TimeUnit.SECONDS))
            registry.tryAcquire(root.resolve("session"), CaptureSessionAccessRegistry.Access.DESTRUCTIVE)!!.use {
                assertTrue(root.resolve("session").toFile().deleteRecursively())
            }
        } finally {
            releasePrepare.countDown()
            releaseClose.countDown()
            starter.join()
            controller.close()
            root.toFile().deleteRecursively()
        }
    }

    @Test fun failedInitializationReleasesLeaseAndDoesNotRemoveExistingEvidence() {
        val root = Files.createTempDirectory("session-failed-lease")
        val registry = CaptureSessionAccessRegistry()
        try {
            assertNotNull(runCatching {
                CaptureSessionWriter(configuration(), root, CaptureStorageCapacityProvider { Long.MAX_VALUE }, registry) {
                    throw java.io.IOException("initial metadata force failed")
                }
            }.exceptionOrNull())
            assertFalse(Files.exists(root.resolve("session")))
            registry.tryAcquire(root.resolve("session"), CaptureSessionAccessRegistry.Access.WRITE)!!.close()
            Files.createDirectory(root.resolve("session"))
            Files.writeString(root.resolve("session/evidence"), "keep")
            assertNotNull(runCatching {
                CaptureSessionWriter(configuration(), root, CaptureStorageCapacityProvider { Long.MAX_VALUE }, registry) {}
            }.exceptionOrNull())
            assertEquals("keep", Files.readString(root.resolve("session/evidence")))
            registry.tryAcquire(root.resolve("session"), CaptureSessionAccessRegistry.Access.READ_SNAPSHOT)!!.close()
        } finally { root.toFile().deleteRecursively() }
    }

    private fun configuration() = CaptureSessionConfiguration("id", "session", Instant.EPOCH, "test", "unavailable",
        "ios_v1", "cup_v1", "ble_gatt_v1", CaptureDeviceContext("CUP", "device", "service", "notify"))
}
