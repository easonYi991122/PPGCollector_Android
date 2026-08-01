package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.ble.BleConnectionPhase
import com.example.ppgcollector_android.core.ble.BleRawNotificationChunk
import com.example.ppgcollector_android.core.protocol.CupBatchStreamDecoder
import com.example.ppgcollector_android.core.protocol.CupDecodedFrameEvent
import com.example.ppgcollector_android.core.protocol.CupFrameSequenceTracker
import com.example.ppgcollector_android.core.protocol.CupSequenceEvent
import com.example.ppgcollector_android.core.signal.StreamFreshness
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

enum class CaptureRecordingState {
    IDLE,
    RECORDING,
    STOPPING,
    FINALIZED,
    FAILED,
}

data class CaptureRecordingSnapshot(
    val state: CaptureRecordingState = CaptureRecordingState.IDLE,
    val connectionGeneration: Long? = null,
    val pendingWriteCount: Int = 0,
    val queueOverflowCount: Long = 0,
    val lastError: String? = null,
    val summary: CaptureSessionSummary? = null,
)

sealed interface CaptureRecordingStartResult {
    data object Started : CaptureRecordingStartResult
    data class Rejected(val failure: CaptureStartFailure) : CaptureRecordingStartResult
    data class Failed(val message: String) : CaptureRecordingStartResult
}

/**
 * Serialized recording seam for the future connectedDevice foreground service.
 * BLE callbacks only copy/enqueue bytes; decoding and file I/O run on [worker].
 */
class CaptureRecordingController(
    private val sessionsRoot: Path,
    private val capacityProvider: CaptureStorageCapacityProvider,
    private val writerFactory: (
        CaptureSessionConfiguration,
        Path,
        CaptureStorageCapacityProvider,
    ) -> CaptureSessionWriter = ::CaptureSessionWriter,
    private val queueCapacity: Int = 256,
    private val workerStartGate: CountDownLatch? = null,
) : AutoCloseable {
    init {
        require(queueCapacity > 0)
    }

    private data class QueuedChunk(
        val generation: Long,
        val hostMonotonicNanoseconds: ULong,
        val bytes: ByteArray,
    )

    private val lock = Any()
    private val queue = ArrayBlockingQueue<QueuedChunk>(queueCapacity)
    private var writer: CaptureSessionWriter? = null
    private var activeGeneration: Long? = null
    private var stopReason: CaptureStopReason? = null
    private var stopRequested = false
    private var queueOverflowCount = 0L
    private var lastError: String? = null
    private var finalSummary: CaptureSessionSummary? = null
    private var worker: Thread? = null

    @Volatile
    private var snapshotValue = CaptureRecordingSnapshot()

    val snapshot: CaptureRecordingSnapshot
        get() = snapshotValue

    fun start(
        configuration: CaptureSessionConfiguration,
        phase: BleConnectionPhase,
        freshness: StreamFreshness,
        connectionGeneration: Long,
        availableBytes: Long? = null,
    ): CaptureRecordingStartResult {
        synchronized(lock) {
            val gateFailure = CaptureStartGate.validate(
                CaptureStartContext(
                    isRecording = writer != null,
                    phase = phase,
                    freshness = freshness,
                    sessionsRoot = sessionsRoot,
                    sessionName = configuration.baseName,
                    availableBytes = availableBytes,
                ),
            )
            if (gateFailure != null) return CaptureRecordingStartResult.Rejected(gateFailure)
            if (snapshotValue.state == CaptureRecordingState.STOPPING) {
                return CaptureRecordingStartResult.Failed("recording is stopping")
            }
            return try {
                writer = writerFactory(configuration, sessionsRoot, capacityProvider)
                activeGeneration = connectionGeneration
                stopReason = null
                stopRequested = false
                queue.clear()
                finalSummary = null
                lastError = null
                worker = thread(start = true, name = "ppg-capture-writer") { workerLoop() }
                publish(CaptureRecordingState.RECORDING)
                CaptureRecordingStartResult.Started
            } catch (error: CaptureSessionWriterException) {
                lastError = error.message
                publish(CaptureRecordingState.FAILED)
                CaptureRecordingStartResult.Failed(error.message ?: "cannot create session")
            }
        }
    }

    /** Returns false only when the chunk is stale, no recording is active, or the queue is full. */
    fun onRawChunk(chunk: BleRawNotificationChunk): Boolean {
        val copied = chunk.copyOfBytes()
        synchronized(lock) {
            if (writer == null || snapshotValue.state != CaptureRecordingState.RECORDING) return false
            if (chunk.connectionGeneration != activeGeneration) return false
            if (!queue.offer(
                    QueuedChunk(
                        chunk.connectionGeneration,
                        chunk.hostMonotonicNanos.toULong(),
                        copied.bytes,
                    ),
                )
            ) {
                queueOverflowCount += 1
                requestStopLocked(CaptureStopReason.RESOURCE_PRESSURE, "raw queue overflow")
                publish(CaptureRecordingState.STOPPING)
                return false
            }
            publish(CaptureRecordingState.RECORDING)
            return true
        }
    }

    /** First stop reason wins; worker drains already accepted raw chunks before finalizing. */
    fun stop(reason: CaptureStopReason, error: String? = null) {
        synchronized(lock) {
            if (writer == null) return
            requestStopLocked(reason, error)
            publish(CaptureRecordingState.STOPPING)
        }
    }

    fun awaitFinalized(timeout: Long, unit: TimeUnit): CaptureSessionSummary? {
        val target = unit.toNanos(timeout)
        val start = System.nanoTime()
        while (System.nanoTime() - start < target) {
            if (snapshotValue.state == CaptureRecordingState.FINALIZED ||
                snapshotValue.state == CaptureRecordingState.FAILED
            ) return finalSummary
            Thread.sleep(1)
        }
        return finalSummary
    }

    override fun close() {
        stop(CaptureStopReason.UNKNOWN, "controller closed")
        awaitFinalized(5, TimeUnit.SECONDS)
        worker?.join(100)
    }

    private fun requestStopLocked(reason: CaptureStopReason, error: String?) {
        if (stopReason == null) stopReason = reason
        if (error != null && lastError == null) lastError = error
        stopRequested = true
    }

    private fun workerLoop() {
        workerStartGate?.await()
        val decoder = CupBatchStreamDecoder()
        val sequenceTracker = CupFrameSequenceTracker()
        var acceptedSampleIndex = 0L
        try {
            while (true) {
                val item = queue.poll(100, TimeUnit.MILLISECONDS)
                if (item != null) {
                    val acceptedBefore = acceptedSampleIndex
                    var events: List<CupDecodedFrameEvent> = emptyList()
                    writer?.appendRawThenDerive(
                        hostMonotonicNanoseconds = item.hostMonotonicNanoseconds,
                        data = item.bytes,
                        acceptedSampleStartIndex = acceptedBefore,
                    ) {
                        // The writer has already acknowledged raw before this lambda runs.
                        events = decoder.feed(item.bytes).map { frame ->
                            val sequence = sequenceTracker.observe(frame.sequence)
                            CupDecodedFrameEvent(
                                frame = frame,
                                sequenceEvent = sequence,
                                isAccepted = sequence !is CupSequenceEvent.Duplicate &&
                                    sequence !is CupSequenceEvent.OutOfOrder,
                            )
                        }
                        events
                    }
                    events.filter { it.isAccepted }.forEach {
                        acceptedSampleIndex += it.frame.samples.size
                    }
                    synchronized(lock) { publish(snapshotValue.state) }
                }
                synchronized(lock) {
                    if (stopRequested && queue.isEmpty()) break
                }
            }
            finalizeWriter()
        } catch (error: Throwable) {
            synchronized(lock) {
                if (stopReason == null) stopReason = CaptureStopReason.WRITE_ERROR
                if (lastError == null) lastError = error.message ?: error::class.simpleName
                stopRequested = true
            }
            finalizeWriter()
        }
    }

    private fun finalizeWriter() {
        synchronized(lock) {
            if (finalSummary != null) return
            val currentWriter = writer ?: return
            val reason = stopReason ?: CaptureStopReason.UNKNOWN
            finalSummary = runCatching { currentWriter.finish(reason, lastError) }
                .onFailure { lastError = it.message ?: it::class.simpleName }
                .getOrNull()
            writer = null
            activeGeneration = null
            publish(if (finalSummary != null) CaptureRecordingState.FINALIZED else CaptureRecordingState.FAILED)
        }
    }

    private fun publish(state: CaptureRecordingState) {
        snapshotValue = CaptureRecordingSnapshot(
            state = state,
            connectionGeneration = activeGeneration,
            pendingWriteCount = queue.size,
            queueOverflowCount = queueOverflowCount,
            lastError = lastError,
            summary = finalSummary,
        )
    }
}
