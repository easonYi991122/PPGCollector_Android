package com.example.ppgcollector_android.core.ble

import com.example.ppgcollector_android.core.protocol.CupBatchStreamDecoder
import com.example.ppgcollector_android.core.protocol.Ads1292rStreamDecoder
import com.example.ppgcollector_android.core.protocol.CupBatchFrame
import com.example.ppgcollector_android.core.protocol.CupPpgSample
import com.example.ppgcollector_android.core.protocol.CupWireFrameProfile
import com.example.ppgcollector_android.core.protocol.CupDecodedFrameEvent
import com.example.ppgcollector_android.core.protocol.CupFrameSequenceTracker
import com.example.ppgcollector_android.core.protocol.CupSequenceEvent
import com.example.ppgcollector_android.core.protocol.CupStreamProtocolMode
import com.example.ppgcollector_android.core.signal.LiveMetricAnalysisRequest
import com.example.ppgcollector_android.core.signal.LiveMetricAnalysisResult
import com.example.ppgcollector_android.core.signal.LiveMetricAnalyzer
import com.example.ppgcollector_android.core.signal.LivePpgSignalRuntime
import com.example.ppgcollector_android.core.signal.LiveWaveformSnapshot
import com.example.ppgcollector_android.core.signal.combo.ComboSqiDebounce
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class BlePreviewSnapshot(
    val connectionGeneration: Long = 0,
    val waveform: LiveWaveformSnapshot = LiveWaveformSnapshot(),
    val lastAnalysis: LiveMetricAnalysisResult? = null,
    val processedSampleCount: Long = 0,
    val droppedChunkCount: Long = 0,
    val streamDiagnostics: LiveStreamDiagnostics = LiveStreamDiagnostics(),
    val lastError: String? = null,
)

data class BlePreviewRuntimeDiagnostics(
    val connectionGeneration: Long,
    val queueDepth: Int,
    val analysisQueueDepth: Int,
    val droppedChunkCount: Long,
    val processedSampleCount: Long,
    val continuityEpoch: Long,
    val gapCount: Long,
    val receivedFrameCount: Int,
    val missingFrameCount: Int,
    val duplicateFrameCount: Int,
    val outOfOrderFrameCount: Int,
    val previewWorkerActive: Boolean,
    val analysisWorkerActive: Boolean,
    val clockTickCount: Long,
    val waveformEmissionCount: Long,
    val metricEmissionCount: Long,
)

/** App-scope, bounded preview pipeline. It never writes session files. */
class BlePreviewRuntime(
    queueCapacity: Int = 256,
    private val onAcceptedFrame: (Long) -> Unit = {},
    private val onClockTick: (Long) -> Unit = {},
    private val clockTickIntervalNanos: Long = 250_000_000L,
    private val analyze: (LiveMetricAnalysisRequest) -> LiveMetricAnalysisResult = { LiveMetricAnalyzer.analyze(it) },
) : AutoCloseable {
    init {
        require(queueCapacity > 0)
        require(clockTickIntervalNanos > 0)
    }

    private data class Input(
        val epoch: Any,
        val generation: Long,
        val hostMonotonicNanos: Long,
        val bytes: ByteArray,
        val streamProtocolMode: CupStreamProtocolMode,
    )

    private data class PendingAnalysis(val epoch: Any, val generation: Long, val request: LiveMetricAnalysisRequest)

    private var resetEpoch = Any()
    private val queue = ArrayBlockingQueue<Input>(queueCapacity)
    private val lock = Any()
    private val _snapshot = MutableStateFlow(BlePreviewSnapshot())
    private var activeGeneration = 0L
    private var activeStreamProtocolMode = CupStreamProtocolMode.BATCH_COMPATIBLE
    private var droppedChunkCount = 0L
    private var lastSequenceNumber: UInt? = null
    private var lastSequenceStep: Long? = null
    private var acceptedSampleIndex = 0L
    private var stopRequested = false
    private var lastClockTickNanos = System.nanoTime()
    private var clockTickCount = 0L
    private var waveformEmissionCount = 0L
    private var metricEmissionCount = 0L
    private var decoder = CupBatchStreamDecoder()
    private var adsDecoder = Ads1292rStreamDecoder()
    private var sequenceTracker = CupFrameSequenceTracker()
    private var signalRuntime = LivePpgSignalRuntime()
    private var comboSqiDebounce = ComboSqiDebounce()
    private val analysisQueue = ArrayBlockingQueue<PendingAnalysis>(1)
    private var worker: Thread? = null
    private var analysisWorker: Thread? = null
    private var closed = false

    val snapshot: StateFlow<BlePreviewSnapshot> = _snapshot.asStateFlow()

    fun diagnostics(): BlePreviewRuntimeDiagnostics = synchronized(lock) {
        BlePreviewRuntimeDiagnostics(
            connectionGeneration = activeGeneration,
            queueDepth = queue.size,
            analysisQueueDepth = analysisQueue.size,
            droppedChunkCount = droppedChunkCount,
            processedSampleCount = acceptedSampleIndex,
            continuityEpoch = signalRuntime.currentContinuityEpoch,
            gapCount = signalRuntime.gapCountValue,
            receivedFrameCount = sequenceTracker.stats.receivedFrames,
            missingFrameCount = sequenceTracker.stats.missingFrames,
            duplicateFrameCount = sequenceTracker.stats.duplicateFrames,
            outOfOrderFrameCount = sequenceTracker.stats.outOfOrderFrames,
            previewWorkerActive = worker?.isAlive == true,
            analysisWorkerActive = analysisWorker?.isAlive == true,
            clockTickCount = clockTickCount,
            waveformEmissionCount = waveformEmissionCount,
            metricEmissionCount = metricEmissionCount,
        )
    }

    fun offer(chunk: BleRawNotificationChunk): Boolean {
        val copied = chunk.copyOfBytes()
        val accepted = synchronized(lock) {
            if (closed) return@synchronized false
            ensureWorkersLocked()
            queue.offer(
                Input(
                    resetEpoch,
                    copied.connectionGeneration,
                    copied.hostMonotonicNanos,
                    copied.bytes,
                    copied.streamProtocolMode,
                ),
            )
        }
        if (!accepted) {
            synchronized(lock) {
                if (closed) return false
                // Preview is disposable and independent from the recording raw
                // sink. Drop its stale backlog as one local discontinuity so a
                // later metric can never span bytes the App failed to consume.
                val staleQueuedChunkCount = queue.size
                queue.clear()
                invalidateInputLocked()
                droppedChunkCount += staleQueuedChunkCount + 1L
                _snapshot.value = _snapshot.value.copy(
                    waveform = LiveWaveformSnapshot(generation = signalRuntime.generation),
                    lastAnalysis = null,
                    droppedChunkCount = droppedChunkCount,
                    streamDiagnostics = currentStreamDiagnostics(),
                    lastError = "preview queue overflow; recording raw sink remains independent",
                )
            }
        }
        return accepted
    }

    fun reset(
        generation: Long,
        streamProtocolMode: CupStreamProtocolMode = CupStreamProtocolMode.BATCH_COMPATIBLE,
        clearQueuedChunks: Boolean = true,
    ) {
        synchronized(lock) {
            if (closed) return
            activeGeneration = generation
            activeStreamProtocolMode = streamProtocolMode
            // Every reset invalidates even inputs already polled by a worker.
            resetEpoch = Any()
            queue.clear()
            analysisQueue.clear()
            decoder = CupBatchStreamDecoder(protocolMode = streamProtocolMode)
            adsDecoder = Ads1292rStreamDecoder()
            sequenceTracker = CupFrameSequenceTracker()
            signalRuntime = LivePpgSignalRuntime()
            comboSqiDebounce = ComboSqiDebounce()
            acceptedSampleIndex = 0L
            droppedChunkCount = 0L
            lastSequenceNumber = null
            lastSequenceStep = null
            lastClockTickNanos = System.nanoTime()
            _snapshot.value = BlePreviewSnapshot(connectionGeneration = generation)
        }
    }

    /** Stop idle preview work without blocking the BLE owner or UI thread. */
    fun suspend() {
        synchronized(lock) {
            if (stopRequested || closed) return
            stopRequested = true
            queue.clear()
            invalidateInputLocked()
            _snapshot.value = _snapshot.value.copy(
                waveform = LiveWaveformSnapshot(generation = signalRuntime.generation),
                lastAnalysis = null,
            )
        }
    }

    private fun invalidateInputLocked() {
        resetEpoch = Any()
        analysisQueue.clear()
        decoder = CupBatchStreamDecoder(protocolMode = activeStreamProtocolMode)
        adsDecoder = Ads1292rStreamDecoder()
        sequenceTracker = CupFrameSequenceTracker()
        lastSequenceNumber = null
        lastSequenceStep = null
        signalRuntime.invalidateLocalInput(acceptedSampleIndex)
        comboSqiDebounce.reset()
    }

    override fun close() {
        val workers = synchronized(lock) {
            closed = true
            resetEpoch = Any()
            stopRequested = true
            queue.clear()
            analysisQueue.clear()
            listOfNotNull(worker, analysisWorker)
        }
        workers.forEach { it.join(2_000) }
        synchronized(lock) {
            worker = null
            analysisWorker = null
        }
    }

    private fun loop() {
        try {
            while (true) {
                val input = queue.poll(100, TimeUnit.MILLISECONDS)
                if (input != null) process(input)
                val now = System.nanoTime()
                var tickGeneration: Long? = null
                var shouldStop = false
                synchronized(lock) {
                    signalRuntime.poll(now, Instant.now())?.let { publishWaveform(it) }
                    if (now - lastClockTickNanos >= clockTickIntervalNanos) {
                        lastClockTickNanos = now
                        clockTickCount++
                        tickGeneration = activeGeneration
                    }
                    shouldStop = stopRequested && queue.isEmpty()
                }
                tickGeneration?.let { generation -> runCatching { onClockTick(generation) } }
                if (shouldStop) return
            }
        } finally {
            synchronized(lock) {
                if (worker === Thread.currentThread()) worker = null
                if (!closed && !stopRequested && queue.isNotEmpty()) ensureWorkersLocked()
            }
        }
    }

    private fun process(input: Input) {
        var acceptedFrame = false
        var metricRequest: PendingAnalysis? = null
        synchronized(lock) {
            if (closed || input.epoch !== resetEpoch || input.generation != activeGeneration) return
            if (input.streamProtocolMode != activeStreamProtocolMode) {
                _snapshot.value = _snapshot.value.copy(
                    lastError = "preview protocol mode does not match the active connection",
                )
                return
            }
            try {
                val acceptedEcgSamples = ArrayList<UInt>()
                val events = if (activeStreamProtocolMode == CupStreamProtocolMode.ADS1292R_120) {
                    adsDecoder.feed(input.bytes).map { packet ->
                        val frame = CupBatchFrame(
                            sequence = packet.sequenceNumber.toUByte(),
                            sequenceNumber = packet.sequenceNumber,
                            wireProfile = CupWireFrameProfile.SENSOR_PACKET_168,
                            samples = packet.red.indices.map { index ->
                                CupPpgSample(packet.red[index], packet.ir[index])
                            },
                        )
                        val sequence = sequenceTracker.observe(frame)
                        noteSequence(frame.sequenceNumber, sequence)
                        val accepted = sequence !is CupSequenceEvent.Duplicate &&
                            sequence !is CupSequenceEvent.OutOfOrder
                        if (accepted) acceptedEcgSamples += packet.ecg
                        CupDecodedFrameEvent(
                            frame = frame,
                            sequenceEvent = sequence,
                            isAccepted = accepted,
                        )
                    }
                } else decoder.feed(input.bytes).map { frame ->
                    val sequence = sequenceTracker.observe(frame)
                    noteSequence(frame.sequenceNumber, sequence)
                    CupDecodedFrameEvent(
                        frame = frame,
                        sequenceEvent = sequence,
                        isAccepted = sequence !is CupSequenceEvent.Duplicate &&
                            sequence !is CupSequenceEvent.OutOfOrder,
                    )
                }
                acceptedFrame = events.any { it.isAccepted }
                val acceptedBefore = acceptedSampleIndex
                val signalGenerationBefore = signalRuntime.generation
                val signal = signalRuntime.ingest(
                    decodedFrames = events,
                    acceptedSampleStartIndex = acceptedBefore,
                    measuredAt = Instant.now(),
                    nowNanos = System.nanoTime(),
                    acceptedEcgSamples = acceptedEcgSamples,
                )
                if (signalRuntime.generation != signalGenerationBefore) {
                    comboSqiDebounce.reset()
                    _snapshot.value = _snapshot.value.copy(lastAnalysis = null)
                }
                acceptedSampleIndex += events.filter { it.isAccepted }
                    .sumOf { it.frame.samples.size.toLong() }
                signal.waveform?.let { publishWaveform(it) }
                metricRequest = signal.metricRequest?.let { PendingAnalysis(resetEpoch, activeGeneration, it) }
            } catch (error: Throwable) {
                _snapshot.value = _snapshot.value.copy(
                    lastError = error.message ?: error::class.simpleName,
                )
            }
        }
        metricRequest?.let(::enqueueAnalysis)
        if (acceptedFrame) runCatching { onAcceptedFrame(input.generation) }
    }

    private fun analysisLoop() {
        try {
            while (true) {
                val request = analysisQueue.poll(100, TimeUnit.MILLISECONDS)
                val shouldStop = synchronized(lock) { stopRequested && analysisQueue.isEmpty() }
                if (request != null) {
                    val result = runCatching { analyze(request.request) }.getOrNull() ?: continue
                    synchronized(lock) {
                        if (!closed && !stopRequested && request.epoch === resetEpoch &&
                            request.generation == activeGeneration && signalRuntime.isCurrent(request.request)) {
                            val combo = comboSqiDebounce.update(result.comboSqiCandidate)
                            _snapshot.value = _snapshot.value.copy(
                                lastAnalysis = result.copy(
                                    snapshot = result.snapshot.copy(comboSqi = combo),
                                ),
                            )
                        }
                    }
                } else if (shouldStop) {
                    return
                }
            }
        } finally {
            synchronized(lock) {
                if (analysisWorker === Thread.currentThread()) analysisWorker = null
                if (!closed && !stopRequested && analysisQueue.isNotEmpty()) ensureWorkersLocked()
            }
        }
    }

    private fun ensureWorkersLocked() {
        check(!closed) { "preview runtime is closed" }
        stopRequested = false
        if (worker?.isAlive != true) {
            worker = thread(start = true, isDaemon = true, name = "ppg-ble-preview") { loop() }
        }
        if (analysisWorker?.isAlive != true) {
            analysisWorker = thread(start = true, isDaemon = true, name = "ppg-ble-preview-metrics") {
                analysisLoop()
            }
        }
    }

    private fun enqueueAnalysis(request: PendingAnalysis) {
        synchronized(lock) {
            if (closed || stopRequested || request.epoch !== resetEpoch || request.generation != activeGeneration) return
            metricEmissionCount++
            if (!analysisQueue.offer(request)) {
                analysisQueue.poll()
                analysisQueue.offer(request)
            }
        }
    }

    private fun publishWaveform(snapshot: LiveWaveformSnapshot) {
        waveformEmissionCount++
        _snapshot.value = _snapshot.value.copy(
            waveform = snapshot,
            processedSampleCount = acceptedSampleIndex,
            streamDiagnostics = currentStreamDiagnostics(),
            lastError = null,
        )
    }

    private fun noteSequence(sequenceNumber: UInt, event: CupSequenceEvent) {
        lastSequenceNumber = sequenceNumber
        lastSequenceStep = when (event) {
            CupSequenceEvent.First -> null
            CupSequenceEvent.Continuous -> 1L
            is CupSequenceEvent.Gap -> event.missingFrames.toLong() + 1L
            CupSequenceEvent.Duplicate -> 0L
            CupSequenceEvent.OutOfOrder -> null
        }
    }

    private fun currentStreamDiagnostics(): LiveStreamDiagnostics {
        val sequence = sequenceTracker.stats
        val adsStats = adsDecoder.stats
        val batchStats = decoder.stats
        val isAds = activeStreamProtocolMode == CupStreamProtocolMode.ADS1292R_120
        return LiveStreamDiagnostics(
            decodedFrameCount = sequence.receivedFrames.toLong(),
            lastSequenceNumber = lastSequenceNumber,
            lastSequenceStep = lastSequenceStep,
            gapEventCount = signalRuntime.gapCountValue,
            estimatedMissingFrameCount = sequence.missingFrames.toLong(),
            duplicateFrameCount = sequence.duplicateFrames.toLong(),
            outOfOrderFrameCount = sequence.outOfOrderFrames.toLong(),
            decoderDiscardedByteCount = if (isAds) adsStats.discardedBytes else batchStats.bytesDiscarded.toLong(),
            decoderInvalidFrameCount = if (isAds) {
                adsStats.invalidHeaders + adsStats.invalidTails
            } else {
                (batchStats.invalidFunction + batchStats.invalidLength + batchStats.invalidTail).toLong()
            },
            appDroppedChunkCount = droppedChunkCount,
        )
    }
}
