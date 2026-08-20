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
) : AutoCloseable {
    init {
        require(queueCapacity > 0)
        require(clockTickIntervalNanos > 0)
    }

    private data class Input(
        val generation: Long,
        val hostMonotonicNanos: Long,
        val bytes: ByteArray,
        val streamProtocolMode: CupStreamProtocolMode,
    )

    private val queue = ArrayBlockingQueue<Input>(queueCapacity)
    private val lock = Any()
    private val _snapshot = MutableStateFlow(BlePreviewSnapshot())
    private var activeGeneration = 0L
    private var activeStreamProtocolMode = CupStreamProtocolMode.BATCH_COMPATIBLE
    private var droppedChunkCount = 0L
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
    private val analysisQueue = ArrayBlockingQueue<LiveMetricAnalysisRequest>(1)
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
                    copied.connectionGeneration,
                    copied.hostMonotonicNanos,
                    copied.bytes,
                    copied.streamProtocolMode,
                ),
            )
        }
        if (!accepted) {
            synchronized(lock) {
                droppedChunkCount++
                _snapshot.value = _snapshot.value.copy(
                    droppedChunkCount = droppedChunkCount,
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
            if (clearQueuedChunks) queue.clear()
            decoder = CupBatchStreamDecoder(protocolMode = streamProtocolMode)
            adsDecoder = Ads1292rStreamDecoder()
            sequenceTracker = CupFrameSequenceTracker()
            signalRuntime = LivePpgSignalRuntime()
            acceptedSampleIndex = 0L
            lastClockTickNanos = System.nanoTime()
            _snapshot.value = BlePreviewSnapshot(connectionGeneration = generation)
        }
    }

    /** Stop idle preview work without blocking the BLE owner or UI thread. */
    fun suspend() {
        synchronized(lock) {
            stopRequested = true
            queue.clear()
            analysisQueue.clear()
        }
    }

    override fun close() {
        val workers = synchronized(lock) {
            closed = true
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
            }
        }
    }

    private fun process(input: Input) {
        var acceptedFrame = false
        var metricRequest: LiveMetricAnalysisRequest? = null
        synchronized(lock) {
            if (input.generation != activeGeneration) return
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
                    CupDecodedFrameEvent(
                        frame = frame,
                        sequenceEvent = sequence,
                        isAccepted = sequence !is CupSequenceEvent.Duplicate &&
                            sequence !is CupSequenceEvent.OutOfOrder,
                    )
                }
                acceptedFrame = events.any { it.isAccepted }
                val acceptedBefore = acceptedSampleIndex
                val signal = signalRuntime.ingest(
                    decodedFrames = events,
                    acceptedSampleStartIndex = acceptedBefore,
                    measuredAt = Instant.now(),
                    nowNanos = System.nanoTime(),
                    acceptedEcgSamples = acceptedEcgSamples,
                )
                acceptedSampleIndex += events.filter { it.isAccepted }
                    .sumOf { it.frame.samples.size.toLong() }
                signal.waveform?.let { publishWaveform(it) }
                metricRequest = signal.metricRequest
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
                    val result = runCatching { LiveMetricAnalyzer.analyze(request) }.getOrNull() ?: continue
                    synchronized(lock) {
                        if (!stopRequested && signalRuntime.isCurrent(request)) {
                            _snapshot.value = _snapshot.value.copy(lastAnalysis = result)
                        }
                    }
                } else if (shouldStop) {
                    return
                }
            }
        } finally {
            synchronized(lock) {
                if (analysisWorker === Thread.currentThread()) analysisWorker = null
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

    private fun enqueueAnalysis(request: LiveMetricAnalysisRequest) {
        synchronized(lock) { metricEmissionCount++ }
        if (!analysisQueue.offer(request)) {
            analysisQueue.poll()
            analysisQueue.offer(request)
        }
    }

    private fun publishWaveform(snapshot: LiveWaveformSnapshot) {
        waveformEmissionCount++
        _snapshot.value = _snapshot.value.copy(
            waveform = snapshot,
            processedSampleCount = acceptedSampleIndex,
            lastError = null,
        )
    }
}
