package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.ble.BleConnectionPhase
import com.example.ppgcollector_android.core.ble.BleRawNotificationChunk
import com.example.ppgcollector_android.core.protocol.CupBatchStreamDecoder
import com.example.ppgcollector_android.core.protocol.CupDecodedFrameEvent
import com.example.ppgcollector_android.core.protocol.CupFrameSequenceTracker
import com.example.ppgcollector_android.core.protocol.CupSequenceEvent
import com.example.ppgcollector_android.core.protocol.CupStreamProtocolMode
import com.example.ppgcollector_android.core.signal.StreamFreshness
import com.example.ppgcollector_android.core.signal.LiveMetricAnalysisResult
import com.example.ppgcollector_android.core.signal.LiveMetricAnalyzer
import com.example.ppgcollector_android.core.signal.LivePpgSignalRuntime
import com.example.ppgcollector_android.core.signal.LiveWaveformSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

enum class CaptureRecordingState {
    IDLE,
    RECORDING,
    STOPPING,
    FINALIZED,
    FAILED,
}

enum class CaptureAnalysisState { IDLE, WARMING, ANALYZING, READY, FAILED, STOPPED }

data class CaptureAnalysisSnapshot(
    val state: CaptureAnalysisState = CaptureAnalysisState.IDLE,
    val generation: Long = 0,
    val processedSampleCount: Long = 0,
    val lastResult: LiveMetricAnalysisResult? = null,
    val error: String? = null,
)

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
        val streamProtocolMode: CupStreamProtocolMode,
    )

    private val lock = Any()
    private val queue = ArrayBlockingQueue<QueuedChunk>(queueCapacity)
    private val analysisQueue = ArrayBlockingQueue<AnalysisInput>(queueCapacity)
    private val metricQueue = ArrayBlockingQueue<CaptureMetricEpoch>(queueCapacity)
    private val bloodPressureQueue = ArrayBlockingQueue<ManualBloodPressureEvent>(queueCapacity)
    private val participantQueue = ArrayBlockingQueue<ParticipantUpdate>(4)
    private var writer: CaptureSessionWriter? = null
    private var activeGeneration: Long? = null
    private var activeStreamProtocolMode: CupStreamProtocolMode? = null
    private var stopReason: CaptureStopReason? = null
    private var stopRequested = false
    private var queueOverflowCount = 0L
    private var lastError: String? = null
    private var finalSummary: CaptureSessionSummary? = null
    private var worker: Thread? = null
    private var analysisWorker: Thread? = null
    private var analysisStopRequested = false
    private var signalRuntime = LivePpgSignalRuntime()
    private var latestAcceptedSourceSampleIndex: Long? = null
    private var nextReferenceEventIndex = 0L
    private val committedReferenceTokens = ConcurrentHashMap.newKeySet<String>()
    private val _analysisSnapshot = MutableStateFlow(CaptureAnalysisSnapshot())
    private val _waveformSnapshot = MutableStateFlow(LiveWaveformSnapshot())

    private data class AnalysisInput(
        val frames: List<CupDecodedFrameEvent>,
        val acceptedSampleStartIndex: Long,
        val measuredAt: Instant,
    )

    private data class ParticipantUpdate(val participant: CaptureParticipantSnapshot?)

    @Volatile
    private var snapshotValue = CaptureRecordingSnapshot()
    private val _snapshotFlow = MutableStateFlow(snapshotValue)

    val snapshot: CaptureRecordingSnapshot
        get() = snapshotValue

    val snapshotFlow: StateFlow<CaptureRecordingSnapshot> = _snapshotFlow.asStateFlow()

    val analysisSnapshot: StateFlow<CaptureAnalysisSnapshot> = _analysisSnapshot.asStateFlow()

    val waveformSnapshot: StateFlow<LiveWaveformSnapshot> = _waveformSnapshot.asStateFlow()

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
                activeStreamProtocolMode =
                    CupStreamProtocolMode.fromProtocolProfileIdentifier(configuration.protocolProfile)
                stopReason = null
                stopRequested = false
                queue.clear()
                analysisQueue.clear()
                metricQueue.clear()
                bloodPressureQueue.clear()
                participantQueue.clear()
                committedReferenceTokens.clear()
                latestAcceptedSourceSampleIndex = null
                nextReferenceEventIndex = 0L
                analysisStopRequested = false
                signalRuntime = LivePpgSignalRuntime()
                _analysisSnapshot.value = CaptureAnalysisSnapshot(
                    state = CaptureAnalysisState.WARMING,
                )
                _waveformSnapshot.value = LiveWaveformSnapshot()
                finalSummary = null
                lastError = null
                analysisWorker = thread(start = true, name = "ppg-capture-analysis") {
                    analysisLoop()
                }
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
            if (chunk.streamProtocolMode != activeStreamProtocolMode) {
                requestStopLocked(
                    CaptureStopReason.PROTOCOL_ERROR,
                    "raw chunk protocol mode changed within one recording",
                )
                publish(CaptureRecordingState.STOPPING)
                return false
            }
            if (!queue.offer(
                    QueuedChunk(
                        chunk.connectionGeneration,
                        chunk.hostMonotonicNanos.toULong(),
                        copied.bytes,
                        copied.streamProtocolMode,
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

    /** Freezes the latest accepted PPG cursor without touching the writer. */
    fun captureReferenceTimestamp(
        dialogOpenUtc: Instant = Instant.now(),
        dialogOpenHostMonotonicNanoseconds: ULong = System.nanoTime().toULong(),
    ): CaptureReferenceTimestamp? = synchronized(lock) {
        val currentWriter = writer ?: return@synchronized null
        if (snapshotValue.state != CaptureRecordingState.RECORDING) return@synchronized null
        val sampleIndex = latestAcceptedSourceSampleIndex ?: return@synchronized null
        val generation = activeGeneration ?: return@synchronized null
        CaptureReferenceTimestamp(
            sessionId = currentWriter.configuration.sessionId,
            connectionGeneration = generation,
            eventIndex = nextReferenceEventIndex++,
            sourceSampleIndex = sampleIndex,
            sourceTimeSeconds = sampleIndex.toDouble() / 100.0,
            dialogOpenHostMonotonicNanoseconds = dialogOpenHostMonotonicNanoseconds,
            dialogOpenUtc = dialogOpenUtc,
        )
    }

    /** Enqueues a BP event; duplicate event tokens are idempotent. */
    fun commitManualBloodPressure(event: ManualBloodPressureEvent): Boolean = synchronized(lock) {
        val currentWriter = writer ?: return@synchronized false
        if (snapshotValue.state != CaptureRecordingState.RECORDING) return@synchronized false
        if (event.reference.sessionId != currentWriter.configuration.sessionId ||
            event.reference.connectionGeneration != activeGeneration
        ) return@synchronized false
        require(event.systolicMmHg > 0 && event.diastolicMmHg > 0) {
            "blood pressure must be positive"
        }
        val token = "${event.reference.sessionId}:${event.reference.eventIndex}"
        if (!committedReferenceTokens.add(token)) return@synchronized true
        if (!bloodPressureQueue.offer(event)) {
            committedReferenceTokens.remove(token)
            return@synchronized false
        }
        true
    }

    /** Coalesces profile edits so the writer owns all metadata mutations. */
    fun updateParticipantProfile(participant: CaptureParticipantSnapshot?): Boolean = synchronized(lock) {
        if (writer == null || snapshotValue.state != CaptureRecordingState.RECORDING) {
            return@synchronized false
        }
        participantQueue.clear()
        participantQueue.offer(ParticipantUpdate(participant))
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

    /** A writer/decoder failure makes an otherwise benign stop incomplete. */
    private fun requestWriteFailureLocked(error: Throwable) {
        if (stopReason == null || stopReason in nonFatalStopReasons) {
            stopReason = CaptureStopReason.WRITE_ERROR
        }
        if (lastError == null) lastError = error.message ?: error::class.simpleName
        stopRequested = true
    }

    private companion object {
        val nonFatalStopReasons = setOf(
            CaptureStopReason.USER,
            CaptureStopReason.VIEW_EXIT,
            CaptureStopReason.SCENE_BACKGROUND,
            CaptureStopReason.DEVICE_DISCONNECT,
            CaptureStopReason.DATA_TIMEOUT,
            CaptureStopReason.UNKNOWN,
        )
    }

    private fun workerLoop() {
        workerStartGate?.await()
        val protocolMode = synchronized(lock) {
            activeStreamProtocolMode ?: CupStreamProtocolMode.BATCH_COMPATIBLE
        }
        val decoder = CupBatchStreamDecoder(protocolMode = protocolMode)
        val sequenceTracker = CupFrameSequenceTracker()
        var acceptedSampleIndex = 0L
        try {
            while (true) {
                val item = queue.poll(100, TimeUnit.MILLISECONDS)
                if (item != null) {
                    check(item.streamProtocolMode == protocolMode) {
                        "queued raw chunk protocol mode changed within one recording"
                    }
                    val acceptedBefore = acceptedSampleIndex
                    var events: List<CupDecodedFrameEvent> = emptyList()
                    writer?.appendRawThenDerive(
                        hostMonotonicNanoseconds = item.hostMonotonicNanoseconds,
                        data = item.bytes,
                        acceptedSampleStartIndex = acceptedBefore,
                    ) {
                        // The writer has already acknowledged raw before this lambda runs.
                        events = decoder.feed(item.bytes).map { frame ->
                            val sequence = sequenceTracker.observe(frame)
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
                    if (events.any { it.isAccepted } &&
                        !analysisQueue.offer(
                            AnalysisInput(events, acceptedBefore, Instant.now()),
                        )
                    ) {
                        _analysisSnapshot.value = _analysisSnapshot.value.copy(
                            state = CaptureAnalysisState.FAILED,
                            error = "analysis queue overflow; raw recording preserved",
                        )
                    }
                    synchronized(lock) { publish(snapshotValue.state) }
                }
                drainMetricQueue()
                drainBloodPressureQueue()
                drainParticipantQueue()
                synchronized(lock) {
                    if (stopRequested && queue.isEmpty() && bloodPressureQueue.isEmpty() &&
                        participantQueue.isEmpty() && metricQueue.isEmpty()
                    ) break
                }
            }
            finishAnalysis()
            drainMetricQueue()
            drainBloodPressureQueue()
            drainParticipantQueue()
            finalizeWriter()
        } catch (error: Throwable) {
            synchronized(lock) {
                requestWriteFailureLocked(error)
            }
            finishAnalysis()
            finalizeWriter()
        }
    }

    private fun analysisLoop() {
        try {
            while (true) {
                val input = analysisQueue.poll(100, TimeUnit.MILLISECONDS)
                if (input != null) {
                    try {
                        val nowNanos = System.nanoTime()
                        val signal = signalRuntime.ingest(
                            decodedFrames = input.frames,
                            acceptedSampleStartIndex = input.acceptedSampleStartIndex,
                            measuredAt = input.measuredAt,
                            nowNanos = nowNanos,
                        )
                        val acceptedSamples = input.frames.filter { it.isAccepted }
                            .sumOf { it.frame.samples.size }
                        if (acceptedSamples > 0) {
                            synchronized(lock) {
                                latestAcceptedSourceSampleIndex =
                                    input.acceptedSampleStartIndex + acceptedSamples - 1L
                            }
                        }
                        signal.waveform?.let { _waveformSnapshot.value = it }
                        val request = signal.metricRequest
                        _analysisSnapshot.value = _analysisSnapshot.value.copy(
                            state = if (request == null) CaptureAnalysisState.WARMING
                            else CaptureAnalysisState.ANALYZING,
                            generation = signalRuntime.generation,
                            processedSampleCount = signalRuntime.continuousSamples,
                            error = null,
                        )
                        if (request != null) {
                            val result = LiveMetricAnalyzer.analyze(request)
                            _analysisSnapshot.value = CaptureAnalysisSnapshot(
                                state = CaptureAnalysisState.READY,
                                generation = request.generation,
                                processedSampleCount = signalRuntime.continuousSamples,
                                lastResult = result,
                                error = null,
                            )
                            val sessionId = synchronized(lock) { writer?.configuration?.sessionId }
                            if (sessionId != null &&
                                !metricQueue.offer(CaptureMetricEpochFactory.fromAnalysis(sessionId, result))
                            ) {
                                _analysisSnapshot.value = _analysisSnapshot.value.copy(
                                    error = "metrics queue overflow; raw recording preserved",
                                )
                            }
                        }
                    } catch (error: Throwable) {
                        _analysisSnapshot.value = _analysisSnapshot.value.copy(
                            state = CaptureAnalysisState.FAILED,
                            error = error.message ?: error::class.simpleName,
                        )
                    }
                }
                signalRuntime.poll(System.nanoTime(), Instant.now())?.let {
                    _waveformSnapshot.value = it
                }
                synchronized(lock) {
                    if (analysisStopRequested && analysisQueue.isEmpty()) return
                }
            }
        } finally {
            signalRuntime.publishNow(System.nanoTime(), Instant.now())?.let {
                _waveformSnapshot.value = it
            }
            if (_analysisSnapshot.value.state != CaptureAnalysisState.FAILED) {
                _analysisSnapshot.value = _analysisSnapshot.value.copy(
                    state = CaptureAnalysisState.STOPPED,
                )
            }
        }
    }

    private fun finishAnalysis() {
        synchronized(lock) {
            analysisStopRequested = true
        }
        analysisWorker?.join(5_000)
        analysisWorker = null
    }

    private fun drainMetricQueue() {
        while (true) {
            val epoch = metricQueue.poll() ?: return
            try {
                writer?.appendMetricEpoch(epoch)
            } catch (error: Throwable) {
                synchronized(lock) { requestWriteFailureLocked(error) }
                return
            }
        }
    }

    private fun drainBloodPressureQueue() {
        while (true) {
            val event = bloodPressureQueue.poll() ?: return
            try {
                writer?.appendBloodPressure(event)
            } catch (error: Throwable) {
                synchronized(lock) { requestWriteFailureLocked(error) }
                return
            }
        }
    }

    private fun drainParticipantQueue() {
        while (true) {
            val participant = participantQueue.poll() ?: return
            try {
                writer?.updateParticipant(participant.participant)
            } catch (error: Throwable) {
                synchronized(lock) { requestWriteFailureLocked(error) }
                return
            }
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
            activeStreamProtocolMode = null
            publish(if (finalSummary != null) CaptureRecordingState.FINALIZED else CaptureRecordingState.FAILED)
        }
    }

    private fun publish(state: CaptureRecordingState) {
        val next = CaptureRecordingSnapshot(
            state = state,
            connectionGeneration = activeGeneration,
            pendingWriteCount = queue.size,
            queueOverflowCount = queueOverflowCount,
            lastError = lastError,
            summary = finalSummary,
        )
        snapshotValue = next
        _snapshotFlow.value = next
    }
}
