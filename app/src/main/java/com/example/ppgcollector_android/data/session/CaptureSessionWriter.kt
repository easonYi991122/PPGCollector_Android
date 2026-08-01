package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.protocol.CupBatchProtocolV1
import com.example.ppgcollector_android.core.protocol.CupDecodedFrameEvent
import com.example.ppgcollector_android.core.protocol.CupSequenceEvent
import com.example.ppgcollector_android.core.signal.LiveMetricSnapshot
import com.example.ppgcollector_android.core.signal.MetricResult
import java.nio.file.FileStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant

data class CaptureDeviceContext(
    val name: String,
    val identifier: String,
    val serviceUuid: String,
    val notifyCharacteristicUuid: String,
)

data class CaptureSessionConfiguration(
    val sessionId: String,
    val baseName: String,
    val startedUtc: Instant,
    val softVersion: String,
    val algorithmVersion: String,
    val preprocessProfile: String,
    val protocolProfile: String,
    val transportProfile: String,
    val device: CaptureDeviceContext,
)

data class CaptureStreamChunkEvent(
    val hostMonotonicNanoseconds: ULong,
    val data: ByteArray,
    val decodedFrames: List<CupDecodedFrameEvent>,
    val acceptedSampleStartIndex: Long? = null,
    val metrics: LiveMetricSnapshot = LiveMetricSnapshot.unavailable(
        hasConnectedDevice = true,
        freshness = com.example.ppgcollector_android.core.signal.StreamFreshness.FRESH,
    ),
)

data class CaptureWriterSnapshot(
    val rawChunkCount: Long = 0,
    val rawPayloadBytes: Long = 0,
    val rawFileBytes: Long = CupRawFormat.magic.size.toLong(),
    val csvRows: Long = 0,
    val acceptedFrames: Long = 0,
    val acceptedSamples: Long = 0,
    val missingFrames: Long = 0,
    val duplicateFrames: Long = 0,
    val outOfOrderFrames: Long = 0,
    val lastFlushUtc: Instant? = null,
)

data class CaptureSessionSummary(
    val sessionId: String,
    val baseName: String,
    val directory: Path,
    val startedUtc: Instant,
    val endedUtc: Instant,
    val stopReason: CaptureStopReason,
    val complete: Boolean,
    val writer: CaptureWriterSnapshot,
)

sealed class CaptureSessionWriterException(message: String) : Exception(message) {
    class SessionAlreadyExists(name: String) :
        CaptureSessionWriterException("session already exists: $name")
    data object InsufficientStorage : CaptureSessionWriterException("insufficient storage")
    class ChunkTooLarge(length: Int) :
        CaptureSessionWriterException("raw payload exceeds 64 KiB: $length")
    data object WriterClosed : CaptureSessionWriterException("writer is closed")
    class CannotCreate(message: String, cause: Throwable? = null) :
        CaptureSessionWriterException(message) {
        init {
            if (cause != null) initCause(cause)
        }
    }
}

fun interface CaptureStorageCapacityProvider {
    fun usableBytes(root: Path): Long?
}

object CaptureSessionWriterPolicy {
    const val sessionSchemaVersion = "ppgcollector_session_v1"
    const val sampleSchemaVersion = "ppgcollector_samples_v1"
    const val minimumAvailableCapacityBytes = 20L * 1024L * 1024L
    private val namePattern = Regex("[A-Za-z0-9_-]+")

    fun isValidBaseName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 80 && namePattern.matches(name)
}

/** Synchronous raw-first writer; lifecycle/service code can safely wrap it later. */
class CaptureSessionWriter(
    val configuration: CaptureSessionConfiguration,
    sessionsRoot: Path,
    capacityProvider: CaptureStorageCapacityProvider = CaptureStorageCapacityProvider {
        runCatching { Files.getFileStore(it).usableSpace }.getOrNull()
    },
) : AutoCloseable {
    val directory: Path = sessionsRoot.resolve(configuration.baseName)
    val rawPath: Path = directory.resolve("${configuration.baseName}.cupraw")
    val csvPath: Path = directory.resolve("${configuration.baseName}.csv")
    val metadataPath: Path = directory.resolve("${configuration.baseName}.session.json")

    private val rawWriter: CupRawWriter
    private var csvOpen = true
    private var closed = false
    private var finalSummary: CaptureSessionSummary? = null
    private var snapshot = CaptureWriterSnapshot()
    private var nextSampleIndex = 0L
    private var firstStreamSampleIndex: Long? = null

    init {
        if (!CaptureSessionWriterPolicy.isValidBaseName(configuration.baseName)) {
            throw CaptureSessionWriterException.CannotCreate("invalid session name")
        }
        Files.createDirectories(sessionsRoot)
        capacityProvider.usableBytes(sessionsRoot)?.let {
            if (it < CaptureSessionWriterPolicy.minimumAvailableCapacityBytes) {
                throw CaptureSessionWriterException.InsufficientStorage
            }
        }
        try {
            Files.createDirectory(directory)
        } catch (error: java.nio.file.FileAlreadyExistsException) {
            throw CaptureSessionWriterException.SessionAlreadyExists(configuration.baseName)
        } catch (error: Exception) {
            throw CaptureSessionWriterException.CannotCreate("cannot create session directory", error)
        }
        try {
            rawWriter = CupRawWriter(rawPath)
            Files.writeString(
                csvPath,
                CaptureCsvSchema.header,
                Charsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            writeMetadata(endedUtc = null, reason = null, complete = false, error = null)
        } catch (error: Throwable) {
            runCatching { if (Files.exists(directory)) directory.toFile().deleteRecursively() }
            if (error is CaptureSessionWriterException) throw error
            throw CaptureSessionWriterException.CannotCreate("cannot create session files", error)
        }
    }

    fun append(event: CaptureStreamChunkEvent): CaptureWriterSnapshot {
        checkOpen()
        if (event.data.isEmpty()) return snapshot
        if (event.data.size > CupRawFormat.maximumChunkLength) {
            throw CaptureSessionWriterException.ChunkTooLarge(event.data.size)
        }

        // This call is intentionally before any derived CSV mutation.
        rawWriter.append(event.hostMonotonicNanoseconds, event.data)
        snapshot = snapshot.copy(
            rawChunkCount = snapshot.rawChunkCount + 1,
            rawPayloadBytes = snapshot.rawPayloadBytes + event.data.size,
            rawFileBytes = rawWriter.bytesWritten,
        )

        val csv = StringBuilder()
        var streamIndex = event.acceptedSampleStartIndex
        for (decoded in event.decodedFrames) {
            snapshot = snapshot.copy(
                missingFrames = snapshot.missingFrames +
                    ((decoded.sequenceEvent as? CupSequenceEvent.Gap)?.missingFrames ?: 0),
                duplicateFrames = snapshot.duplicateFrames +
                    if (decoded.sequenceEvent is CupSequenceEvent.Duplicate) 1 else 0,
                outOfOrderFrames = snapshot.outOfOrderFrames +
                    if (decoded.sequenceEvent is CupSequenceEvent.OutOfOrder) 1 else 0,
            )
            if (!decoded.isAccepted) continue
            snapshot = snapshot.copy(acceptedFrames = snapshot.acceptedFrames + 1)
            if (firstStreamSampleIndex == null) firstStreamSampleIndex = streamIndex ?: nextSampleIndex
            decoded.frame.samples.forEachIndexed { sampleInFrame, sample ->
                csv.append(
                    CaptureCsvFormatter.format(
                        CaptureCsvRow(
                            CaptureSessionWriterPolicy.sampleSchemaVersion,
                            configuration.sessionId,
                            nextSampleIndex,
                            event.hostMonotonicNanoseconds,
                            decoded.frame.sequence,
                            sampleInFrame,
                            sample.red,
                            sample.ir,
                            event.metrics.heartRateBpm.toCsvCell(),
                            event.metrics.oxygenSaturationPercent.toCsvCell(),
                            event.metrics.signalQuality.toCsvCell(),
                            configuration.softVersion,
                            configuration.algorithmVersion,
                            configuration.preprocessProfile,
                            configuration.protocolProfile,
                            event.metrics.ratioOfRatios.toCsvCell(),
                        ),
                        firstStreamSampleIndex,
                    ),
                )
                nextSampleIndex++
                streamIndex = streamIndex?.plus(1)
                snapshot = snapshot.copy(
                    csvRows = snapshot.csvRows + 1,
                    acceptedSamples = snapshot.acceptedSamples + 1,
                )
            }
        }
        if (csv.isNotEmpty()) {
            Files.writeString(csvPath, csv.toString(), Charsets.UTF_8, StandardOpenOption.APPEND)
        }
        writeMetadata(null, null, false, null)
        return snapshot
    }

    fun finish(reason: CaptureStopReason, error: String? = null): CaptureSessionSummary {
        finalSummary?.let { return it }
        val ended = Instant.now()
        closeFiles()
        val complete = error == null && reason in setOf(
            CaptureStopReason.USER, CaptureStopReason.VIEW_EXIT,
            CaptureStopReason.SCENE_BACKGROUND, CaptureStopReason.DEVICE_DISCONNECT,
            CaptureStopReason.DATA_TIMEOUT,
        )
        writeMetadata(ended, reason, complete, error)
        return CaptureSessionSummary(
            configuration.sessionId, configuration.baseName, directory,
            configuration.startedUtc, ended, reason, complete, snapshot,
        ).also { finalSummary = it }
    }

    fun discardIfEmptyBeforeRecording(): Boolean {
        if (finalSummary != null || snapshot.rawChunkCount != 0L || closed) return false
        closeFiles()
        directory.toFile().deleteRecursively()
        return true
    }

    override fun close() {
        if (!closed) finish(CaptureStopReason.UNKNOWN, "writer closed without finalization")
    }

    private fun checkOpen() {
        if (closed) throw CaptureSessionWriterException.WriterClosed
    }

    private fun closeFiles() {
        if (closed) return
        rawWriter.close()
        csvOpen = false
        closed = true
    }

    private fun writeMetadata(
        endedUtc: Instant?, reason: CaptureStopReason?, complete: Boolean, error: String?,
    ) {
        val metadata = CaptureSessionMetadata(
            schemaVersion = CaptureSessionWriterPolicy.sessionSchemaVersion,
            sessionId = configuration.sessionId,
            baseName = configuration.baseName,
            startedUtc = configuration.startedUtc,
            endedUtc = endedUtc,
            softVersion = configuration.softVersion,
            algVersion = configuration.algorithmVersion,
            preprocessProfile = configuration.preprocessProfile,
            protocolProfile = configuration.protocolProfile,
            transportProfile = configuration.transportProfile,
            sampleRateHz = CupBatchProtocolV1.sampleRateHz,
            samplesPerFrame = CupBatchProtocolV1.samplesPerFrame,
            device = CaptureSessionDeviceMetadata(configuration.device.name, configuration.device.identifier,
                configuration.device.serviceUuid, configuration.device.notifyCharacteristicUuid, null, null),
            complete = complete,
            stopReason = reason,
            frameCount = snapshot.acceptedFrames,
            sampleCount = snapshot.acceptedSamples,
            rawChunkCount = snapshot.rawChunkCount,
            missingFrames = snapshot.missingFrames,
            duplicateFrames = snapshot.duplicateFrames,
            outOfOrderFrames = snapshot.outOfOrderFrames,
            invalidFrames = 0,
            discardedBytes = 0,
            writer = CaptureSessionWriterMetadata(snapshot.lastFlushUtc, snapshot.rawFileBytes, snapshot.csvRows, error),
            files = CaptureSessionFilesMetadata(rawPath.fileName.toString(), csvPath.fileName.toString()),
            recovery = null,
        )
        Files.writeString(metadataPath, CaptureSessionMetadataCodec.encode(metadata), Charsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
    }
}

private fun MetricResult<Double>.toCsvCell(): CsvMetricCell =
    CsvMetricCell(value, isValid, sourceSampleIndex)
