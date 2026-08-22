package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.ble.BleConnectionPhase
import com.example.ppgcollector_android.core.ble.BleRawNotificationChunk
import com.example.ppgcollector_android.core.ble.LiveStreamDiagnostics
import com.example.ppgcollector_android.core.protocol.CupBatchStreamDecoder
import com.example.ppgcollector_android.core.protocol.Ads1292rStreamDecoder
import com.example.ppgcollector_android.core.protocol.CupDecodedFrameEvent
import com.example.ppgcollector_android.core.protocol.CupBatchFrame
import com.example.ppgcollector_android.core.protocol.CupPpgSample
import com.example.ppgcollector_android.core.protocol.CupFrameSequenceTracker
import com.example.ppgcollector_android.core.protocol.CupSequenceEvent
import com.example.ppgcollector_android.core.protocol.CupStreamProtocolMode
import com.example.ppgcollector_android.core.protocol.CupWireFrameProfile
import com.example.ppgcollector_android.core.signal.StreamFreshness
import com.example.ppgcollector_android.core.signal.LiveMetricAnalysisResult
import com.example.ppgcollector_android.core.signal.LiveMetricAnalyzer
import com.example.ppgcollector_android.core.signal.LivePpgSignalRuntime
import com.example.ppgcollector_android.core.signal.LiveWaveformSnapshot
import com.example.ppgcollector_android.core.signal.combo.ComboSqiDebounce
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
    val recordMode: CaptureRecordMode = CaptureRecordMode.MANUAL,
    val plannedDurationSeconds: Int? = null,
    val acceptedSampleCount: Long = 0L,
    val acceptedDurationSeconds: Double = 0.0,
    val remainingDurationSeconds: Int? = null,
    val connectionGeneration: Long? = null,
    val pendingWriteCount: Int = 0,
    val queueOverflowCount: Long = 0,
    val streamDiagnostics: LiveStreamDiagnostics = LiveStreamDiagnostics(),
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
    private var analysisInputDropCount = 0L
    private var streamDiagnosticsValue = LiveStreamDiagnostics()
    private var lastError: String? = null
    private var finalSummary: CaptureSessionSummary? = null
    private var acceptedSampleCount = 0L
    private var worker: Thread? = null
    private var analysisWorker: Thread? = null
    private var analysisStopRequested = false
    private var signalRuntime = LivePpgSignalRuntime()
    private var comboSqiDebounce = ComboSqiDebounce()
    private var comboGeneration = -1L
    private var latestAcceptedSourceSampleIndex: Long? = null
    private var nextReferenceEventIndex = 0L
    private var finalizedLatch = CountDownLatch(0)
    private var lastProgressPublishNanos = Long.MIN_VALUE
    private val committedReferenceTokens = ConcurrentHashMap.newKeySet<String>()
    private val _analysisSnapshot = MutableStateFlow(CaptureAnalysisSnapshot())
    private val _waveformSnapshot = MutableStateFlow(LiveWaveformSnapshot())

    private data class AnalysisInput(
        val frames: List<CupDecodedFrameEvent>,
        val acceptedSampleStartIndex: Long,
        val measuredAt: Instant,
        val ecgSamples: List<UInt> = emptyList(),
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
        participant: CaptureParticipantSnapshot? = null,
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
                    participant = participant?.let(CaptureParticipantDraft::fromSnapshot),
                    recordMode = configuration.recordMode,
                    plannedDurationSeconds = configuration.plannedDurationSeconds,
                ),
            )
            if (gateFailure != null) return CaptureRecordingStartResult.Rejected(gateFailure)
            if (snapshotValue.state == CaptureRecordingState.STOPPING) {
                return CaptureRecordingStartResult.Failed("recording is stopping")
            }
            return try {
                val acceptedWriter = writerFactory(configuration, sessionsRoot, capacityProvider)
                writer = acceptedWriter
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
                acceptedSampleCount = 0L
                queueOverflowCount = 0L
                analysisInputDropCount = 0L
                streamDiagnosticsValue = LiveStreamDiagnostics()
                val initialBloodPressure = initialBloodPressureEvent(
                    configuration = acceptedWriter.configuration,
                    connectionGeneration = connectionGeneration,
                )
                if (initialBloodPressure != null) {
                    acceptedWriter.appendBloodPressure(initialBloodPressure)
                    committedReferenceTokens +=
                        "${initialBloodPressure.reference.sessionId}:${initialBloodPressure.reference.eventIndex}"
                }
                nextReferenceEventIndex = if (initialBloodPressure == null) 0L else 1L
                analysisStopRequested = false
                finalizedLatch = CountDownLatch(1)
                lastProgressPublishNanos = Long.MIN_VALUE
                signalRuntime = LivePpgSignalRuntime()
                comboSqiDebounce = ComboSqiDebounce()
                comboGeneration = -1L
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
                finalizedLatch.countDown()
                CaptureRecordingStartResult.Failed(error.message ?: "cannot create session")
            }
        }
    }

    private fun initialBloodPressureEvent(
        configuration: CaptureSessionConfiguration,
        connectionGeneration: Long,
    ): ManualBloodPressureEvent? {
        val systolic = configuration.systolicBp ?: return null
        val diastolic = configuration.diastolicBp ?: return null
        if (systolic <= diastolic || diastolic <= 0) return null
        return ManualBloodPressureEvent(
            reference = CaptureReferenceTimestamp(
                sessionId = configuration.sessionId,
                connectionGeneration = connectionGeneration,
                eventIndex = 0L,
                sourceSampleIndex = 0L,
                sourceTimeSeconds = 0.0,
                dialogOpenHostMonotonicNanoseconds = System.nanoTime().toULong(),
                dialogOpenUtc = configuration.startedUtc,
            ),
            savedUtc = configuration.startedUtc,
            systolicMmHg = systolic,
            diastolicMmHg = diastolic,
        )
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
                streamDiagnosticsValue = streamDiagnosticsValue.copy(
                    appDroppedChunkCount = queueOverflowCount,
                )
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
        require(event.systolicMmHg > event.diastolicMmHg) {
            "systolic must be greater than diastolic"
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
        if (snapshotValue.state != CaptureRecordingState.FINALIZED &&
            snapshotValue.state != CaptureRecordingState.FAILED
        ) finalizedLatch.await(timeout, unit)
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
        const val PROGRESS_PUBLISH_INTERVAL_NANOS = 200_000_000L
        val nonFatalStopReasons = setOf(
            CaptureStopReason.USER,
            CaptureStopReason.VIEW_EXIT,
            CaptureStopReason.SCENE_BACKGROUND,
            CaptureStopReason.DEVICE_DISCONNECT,
            CaptureStopReason.DATA_TIMEOUT,
            CaptureStopReason.DURATION_ELAPSED,
            CaptureStopReason.UNKNOWN,
        )
    }

    private fun workerLoop() {
        workerStartGate?.await()
        val protocolMode = synchronized(lock) {
            activeStreamProtocolMode ?: CupStreamProtocolMode.BATCH_COMPATIBLE
        }
        val decoder = CupBatchStreamDecoder(protocolMode = protocolMode)
        val adsDecoder = Ads1292rStreamDecoder()
        val sequenceTracker = CupFrameSequenceTracker()
        var acceptedSampleIndex = 0L
        var lastSequenceNumber: UInt? = null
        var lastSequenceStep: Long? = null
        var gapEventCount = 0L
        try {
            while (true) {
                val item = queue.poll(100, TimeUnit.MILLISECONDS)
                if (item != null) {
                    check(item.streamProtocolMode == protocolMode) {
                        "queued raw chunk protocol mode changed within one recording"
                    }
                    val acceptedBefore = acceptedSampleIndex
                    if (protocolMode == CupStreamProtocolMode.ADS1292R_120) {
                        writer?.appendRawThenDerive(
                            hostMonotonicNanoseconds = item.hostMonotonicNanoseconds,
                            data = item.bytes,
                            acceptedSampleStartIndex = acceptedBefore,
                        ) { emptyList() }
                        val acceptedEcgSamples = ArrayList<UInt>()
                        val adsEvents = adsDecoder.feed(item.bytes).map { packet ->
                            val frame = CupBatchFrame(
                                sequence = packet.sequenceNumber.toUByte(),
                                sequenceNumber = packet.sequenceNumber,
                                wireProfile = CupWireFrameProfile.SENSOR_PACKET_168,
                                samples = packet.red.indices.map { index ->
                                    CupPpgSample(packet.red[index], packet.ir[index])
                                },
                            )
                            val sequence = sequenceTracker.observe(frame)
                            lastSequenceNumber = frame.sequenceNumber
                            lastSequenceStep = sequenceStep(sequence)
                            if (sequence is CupSequenceEvent.Gap) gapEventCount++
                            val accepted = sequence !is CupSequenceEvent.Duplicate &&
                                sequence !is CupSequenceEvent.OutOfOrder
                            writer?.appendAds1292rPacket(
                                hostMonotonicNanoseconds = item.hostMonotonicNanoseconds,
                                packet = packet,
                                sequenceEvent = sequence,
                            )
                            if (accepted) {
                                acceptedSampleIndex += packet.red.size
                                acceptedEcgSamples += packet.ecg
                            }
                            CupDecodedFrameEvent(
                                frame = frame,
                                sequenceEvent = sequence,
                                isAccepted = accepted,
                            )
                        }
                        if (adsEvents.isNotEmpty() &&
                            !analysisQueue.offer(
                                AnalysisInput(
                                    frames = adsEvents,
                                    acceptedSampleStartIndex = acceptedBefore,
                                    measuredAt = Instant.now(),
                                    ecgSamples = acceptedEcgSamples,
                                ),
                            )
                        ) {
                            synchronized(lock) {
                                analysisInputDropCount++
                                streamDiagnosticsValue = streamDiagnosticsValue.copy(
                                    appDroppedChunkCount = queueOverflowCount + analysisInputDropCount,
                                )
                            }
                            _analysisSnapshot.value = _analysisSnapshot.value.copy(
                                state = CaptureAnalysisState.FAILED,
                                lastResult = null,
                                error = "analysis queue overflow; raw recording preserved",
                            )
                        }
                        synchronized(lock) {
                            streamDiagnosticsValue = streamDiagnostics(
                                protocolMode = protocolMode,
                                decoder = decoder,
                                adsDecoder = adsDecoder,
                                sequenceTracker = sequenceTracker,
                                lastSequenceNumber = lastSequenceNumber,
                                lastSequenceStep = lastSequenceStep,
                                gapEventCount = gapEventCount,
                                appDroppedChunkCount = queueOverflowCount + analysisInputDropCount,
                            )
                            acceptedSampleCount = acceptedSampleIndex
                            val planned = writer?.configuration?.plannedDurationSeconds
                            if (writer?.configuration?.recordMode == CaptureRecordMode.TIMED &&
                                planned != null && acceptedSampleIndex >= planned * 100L
                            ) {
                                requestStopLocked(CaptureStopReason.DURATION_ELAPSED, null)
                                publish(CaptureRecordingState.STOPPING)
                            }
                            publish(snapshotValue.state)
                        }
                        drainMetricQueue()
                        drainBloodPressureQueue()
                        drainParticipantQueue()
                        synchronized(lock) {
                            if (stopRequested && queue.isEmpty() && bloodPressureQueue.isEmpty() &&
                                participantQueue.isEmpty() && metricQueue.isEmpty()
                            ) break
                        }
                        continue
                    }
                    var events: List<CupDecodedFrameEvent> = emptyList()
                    writer?.appendRawThenDerive(
                        hostMonotonicNanoseconds = item.hostMonotonicNanoseconds,
                        data = item.bytes,
                        acceptedSampleStartIndex = acceptedBefore,
                    ) {
                        // The writer has already acknowledged raw before this lambda runs.
                        events = decoder.feed(item.bytes).map { frame ->
                            val sequence = sequenceTracker.observe(frame)
                            lastSequenceNumber = frame.sequenceNumber
                            lastSequenceStep = sequenceStep(sequence)
                            if (sequence is CupSequenceEvent.Gap) gapEventCount++
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
                    synchronized(lock) {
                        streamDiagnosticsValue = streamDiagnostics(
                            protocolMode = protocolMode,
                            decoder = decoder,
                            adsDecoder = adsDecoder,
                            sequenceTracker = sequenceTracker,
                            lastSequenceNumber = lastSequenceNumber,
                            lastSequenceStep = lastSequenceStep,
                            gapEventCount = gapEventCount,
                            appDroppedChunkCount = queueOverflowCount + analysisInputDropCount,
                        )
                        acceptedSampleCount = acceptedSampleIndex
                        publish(snapshotValue.state)
                    }
                    if (events.any { it.isAccepted }) {
                        val mode = synchronized(lock) { writer?.configuration?.recordMode }
                        val planned = synchronized(lock) {
                            writer?.configuration?.plannedDurationSeconds
                        }
                        if (mode == CaptureRecordMode.TIMED &&
                            planned != null &&
                            acceptedSampleIndex >= planned * 100L
                        ) {
                            synchronized(lock) {
                                requestStopLocked(CaptureStopReason.DURATION_ELAPSED, null)
                                publish(CaptureRecordingState.STOPPING)
                            }
                        }
                    }
                    if (events.any { it.isAccepted } &&
                        !analysisQueue.offer(
                            AnalysisInput(events, acceptedBefore, Instant.now()),
                        )
                    ) {
                        synchronized(lock) {
                            analysisInputDropCount++
                            streamDiagnosticsValue = streamDiagnosticsValue.copy(
                                appDroppedChunkCount = queueOverflowCount + analysisInputDropCount,
                            )
                        }
                        _analysisSnapshot.value = _analysisSnapshot.value.copy(
                            state = CaptureAnalysisState.FAILED,
                            lastResult = null,
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
                        val signalGenerationBefore = signalRuntime.generation
                        val signal = signalRuntime.ingest(
                            decodedFrames = input.frames,
                            acceptedSampleStartIndex = input.acceptedSampleStartIndex,
                            measuredAt = input.measuredAt,
                            nowNanos = nowNanos,
                            acceptedEcgSamples = input.ecgSamples,
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
                        val hardReset = signalRuntime.generation != signalGenerationBefore
                        _analysisSnapshot.value = if (hardReset) {
                            CaptureAnalysisSnapshot(
                                state = CaptureAnalysisState.WARMING,
                                generation = signalRuntime.generation,
                                processedSampleCount = signalRuntime.continuousSamples,
                            )
                        } else {
                            _analysisSnapshot.value.copy(
                                state = if (request == null) CaptureAnalysisState.WARMING
                                else CaptureAnalysisState.ANALYZING,
                                generation = signalRuntime.generation,
                                processedSampleCount = signalRuntime.continuousSamples,
                                error = null,
                            )
                        }
                        if (request != null) {
                            val result = LiveMetricAnalyzer.analyze(request)
                            val comboResult = synchronized(lock) {
                                if (comboGeneration != signalRuntime.generation) {
                                    comboSqiDebounce.reset()
                                    comboGeneration = signalRuntime.generation
                                }
                                comboSqiDebounce.update(result.comboSqiCandidate)
                            }
                            val publishedResult = result.copy(
                                snapshot = result.snapshot.copy(comboSqi = comboResult),
                            )
                            _analysisSnapshot.value = CaptureAnalysisSnapshot(
                                state = CaptureAnalysisState.READY,
                                generation = request.generation,
                                processedSampleCount = signalRuntime.continuousSamples,
                                lastResult = publishedResult,
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
            // Signal only after the terminal snapshot is visible to callers
            // waiting for finalization; otherwise awaitFinalized can return
            // between writer.close() and the StateFlow publication.
            finalizedLatch.countDown()
        }
    }

    private fun publish(state: CaptureRecordingState) {
        val previous = snapshotValue
        val next = CaptureRecordingSnapshot(
            state = state,
            recordMode = writer?.configuration?.recordMode ?: snapshotValue.recordMode,
            plannedDurationSeconds =
                writer?.configuration?.plannedDurationSeconds ?: snapshotValue.plannedDurationSeconds,
            acceptedSampleCount = acceptedSampleCount,
            acceptedDurationSeconds = acceptedSampleCount / 100.0,
            remainingDurationSeconds = CaptureRecordModePolicy.remainingSeconds(
                writer?.configuration?.recordMode ?: snapshotValue.recordMode,
                writer?.configuration?.plannedDurationSeconds ?: snapshotValue.plannedDurationSeconds,
                acceptedSampleCount,
                100,
            ),
            connectionGeneration = activeGeneration,
            pendingWriteCount = queue.size,
            queueOverflowCount = queueOverflowCount,
            streamDiagnostics = streamDiagnosticsValue.copy(
                appDroppedChunkCount = queueOverflowCount + analysisInputDropCount,
            ),
            lastError = lastError,
            summary = finalSummary,
        )
        snapshotValue = next
        val now = System.nanoTime()
        val shouldEmit = state != CaptureRecordingState.RECORDING ||
            previous.state != CaptureRecordingState.RECORDING ||
            now - lastProgressPublishNanos >= PROGRESS_PUBLISH_INTERVAL_NANOS
        if (shouldEmit) {
            lastProgressPublishNanos = now
            _snapshotFlow.value = next
        }
    }

    private fun sequenceStep(event: CupSequenceEvent): Long? = when (event) {
        CupSequenceEvent.First -> null
        CupSequenceEvent.Continuous -> 1L
        is CupSequenceEvent.Gap -> event.missingFrames.toLong() + 1L
        CupSequenceEvent.Duplicate -> 0L
        CupSequenceEvent.OutOfOrder -> null
    }

    private fun streamDiagnostics(
        protocolMode: CupStreamProtocolMode,
        decoder: CupBatchStreamDecoder,
        adsDecoder: Ads1292rStreamDecoder,
        sequenceTracker: CupFrameSequenceTracker,
        lastSequenceNumber: UInt?,
        lastSequenceStep: Long?,
        gapEventCount: Long,
        appDroppedChunkCount: Long,
    ): LiveStreamDiagnostics {
        val sequence = sequenceTracker.stats
        val isAds = protocolMode == CupStreamProtocolMode.ADS1292R_120
        val adsStats = adsDecoder.stats
        val batchStats = decoder.stats
        return LiveStreamDiagnostics(
            decodedFrameCount = sequence.receivedFrames.toLong(),
            lastSequenceNumber = lastSequenceNumber,
            lastSequenceStep = lastSequenceStep,
            gapEventCount = gapEventCount,
            estimatedMissingFrameCount = sequence.missingFrames.toLong(),
            duplicateFrameCount = sequence.duplicateFrames.toLong(),
            outOfOrderFrameCount = sequence.outOfOrderFrames.toLong(),
            decoderDiscardedByteCount = if (isAds) adsStats.discardedBytes else batchStats.bytesDiscarded.toLong(),
            decoderInvalidFrameCount = if (isAds) {
                adsStats.invalidHeaders + adsStats.invalidTails
            } else {
                (batchStats.invalidFunction + batchStats.invalidLength + batchStats.invalidTail).toLong()
            },
            appDroppedChunkCount = appDroppedChunkCount,
        )
    }

}
