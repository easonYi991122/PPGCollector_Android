package com.example.ppgcollector_android.core.ble

import com.example.ppgcollector_android.core.protocol.CupBatchStreamDecoder
import com.example.ppgcollector_android.core.protocol.CupDecodedFrameEvent
import com.example.ppgcollector_android.core.protocol.CupFrameSequenceTracker
import com.example.ppgcollector_android.core.protocol.CupSequenceEvent
import com.example.ppgcollector_android.core.protocol.CupStreamProtocolMode
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
    private var decoder = CupBatchStreamDecoder()
    private var sequenceTracker = CupFrameSequenceTracker()
    private var signalRuntime = LivePpgSignalRuntime()
    private val worker = thread(start = true, isDaemon = true, name = "ppg-ble-preview") { loop() }

    val snapshot: StateFlow<BlePreviewSnapshot> = _snapshot.asStateFlow()

    fun offer(chunk: BleRawNotificationChunk): Boolean {
        val copied = chunk.copyOfBytes()
        val accepted = queue.offer(
            Input(
                copied.connectionGeneration,
                copied.hostMonotonicNanos,
                copied.bytes,
                copied.streamProtocolMode,
            ),
        )
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
    ) {
        synchronized(lock) {
            activeGeneration = generation
            activeStreamProtocolMode = streamProtocolMode
            queue.clear()
            decoder = CupBatchStreamDecoder(protocolMode = streamProtocolMode)
            sequenceTracker = CupFrameSequenceTracker()
            signalRuntime = LivePpgSignalRuntime()
            acceptedSampleIndex = 0L
            lastClockTickNanos = System.nanoTime()
            _snapshot.value = BlePreviewSnapshot(connectionGeneration = generation)
        }
    }

    override fun close() {
        synchronized(lock) { stopRequested = true }
        worker.join(2_000)
    }

    private fun loop() {
        while (true) {
            val input = queue.poll(100, TimeUnit.MILLISECONDS)
            if (input != null) process(input)
            val now = System.nanoTime()
            var tickGeneration: Long? = null
            var shouldStop = false
            synchronized(lock) {
                signalRuntime.poll(now, Instant.now())?.let { publishWaveform(it) }
                if (_snapshot.value.processedSampleCount > 0 &&
                    now - lastClockTickNanos >= clockTickIntervalNanos
                ) {
                    lastClockTickNanos = now
                    tickGeneration = activeGeneration
                }
                shouldStop = stopRequested && queue.isEmpty()
            }
            tickGeneration?.let { generation -> runCatching { onClockTick(generation) } }
            if (shouldStop) return
        }
    }

    private fun process(input: Input) {
        var acceptedFrame = false
        synchronized(lock) {
            if (input.generation != activeGeneration) return
            if (input.streamProtocolMode != activeStreamProtocolMode) {
                _snapshot.value = _snapshot.value.copy(
                    lastError = "preview protocol mode does not match the active connection",
                )
                return
            }
            try {
                val events = decoder.feed(input.bytes).map { frame ->
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
                )
                signal.waveform?.let { publishWaveform(it) }
                val request = signal.metricRequest
                acceptedSampleIndex += events.filter { it.isAccepted }
                    .sumOf { it.frame.samples.size.toLong() }
                val next = _snapshot.value.copy(
                    processedSampleCount = acceptedSampleIndex,
                    lastError = null,
                )
                _snapshot.value = if (request == null) next
                else next.copy(lastAnalysis = LiveMetricAnalyzer.analyze(request))
            } catch (error: Throwable) {
                _snapshot.value = _snapshot.value.copy(
                    lastError = error.message ?: error::class.simpleName,
                )
            }
        }
        if (acceptedFrame) runCatching { onAcceptedFrame(input.generation) }
    }

    private fun publishWaveform(snapshot: LiveWaveformSnapshot) {
        _snapshot.value = _snapshot.value.copy(waveform = snapshot)
    }
}
