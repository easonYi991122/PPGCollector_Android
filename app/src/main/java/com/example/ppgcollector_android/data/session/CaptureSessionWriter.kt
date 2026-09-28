package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.protocol.CupBatchProtocolV1
import com.example.ppgcollector_android.core.protocol.Ads1292rPacket
import com.example.ppgcollector_android.core.protocol.Ads1292rPacketProtocol
import com.example.ppgcollector_android.core.protocol.CupDecodedFrameEvent
import com.example.ppgcollector_android.core.protocol.CupSequenceEvent
import com.example.ppgcollector_android.core.protocol.CupWireFrameProfile
import com.example.ppgcollector_android.core.signal.LiveMetricSnapshot
import com.example.ppgcollector_android.core.signal.MetricResult
import java.nio.file.FileStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
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
    val canonicalSubjectId: String? = null,
    val canonicalSequence: Long? = null,
    val participant: CaptureParticipantSnapshot? = null,
    val systolicBp: Int? = null,
    val diastolicBp: Int? = null,
    val recordMode: CaptureRecordMode = CaptureRecordMode.MANUAL,
    val plannedDurationSeconds: Int? = null,
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
    val metricsRows: Long = 0,
    val bloodPressureRows: Long = 0,
    val ecgRows: Long = 0,
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
    const val sessionSchemaVersion = "ppgcollector_session_v2"
    const val sampleSchemaVersion = CaptureCsvSchema.version1
    const val sensorPacketSampleSchemaVersion = CaptureCsvSchema.version2
    const val minimumAvailableCapacityBytes = 20L * 1024L * 1024L
    fun isValidBaseName(name: String): Boolean = SessionNamePolicy.isValid(name)
}

/** Synchronous raw-first writer; lifecycle/service code can safely wrap it later. */
class CaptureSessionWriter(
    configuration: CaptureSessionConfiguration,
    sessionsRoot: Path,
    capacityProvider: CaptureStorageCapacityProvider,
    accessRegistry: CaptureSessionAccessRegistry,
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val beforeForce: (Path) -> Unit,
) : AutoCloseable {
    constructor(
        configuration: CaptureSessionConfiguration,
        sessionsRoot: Path,
        capacityProvider: CaptureStorageCapacityProvider = CaptureStorageCapacityProvider {
            kotlin.runCatching { Files.getFileStore(it).usableSpace }.getOrNull()
        },
    ) : this(configuration, sessionsRoot, capacityProvider, CaptureSessionAccessRegistry.app, beforeForce = {})

    /** Persist canonical sessions with one stable prefix, even for legacy callers. */
    val configuration: CaptureSessionConfiguration = configuration.copy(
        baseName = SessionNamePolicy.normalizeCanonical(configuration.baseName)
            ?: configuration.baseName,
    )
    val directory: Path = sessionsRoot.resolve(this.configuration.baseName)
    val rawPath: Path = directory.resolve("${this.configuration.baseName}.cupraw")
    val csvPath: Path = directory.resolve("${this.configuration.baseName}.csv")
    val metadataPath: Path = directory.resolve("${this.configuration.baseName}.session.json")
    val metricsPath: Path = directory.resolve("${this.configuration.baseName}.metrics.csv")
    val bloodPressurePath: Path = directory.resolve("${this.configuration.baseName}.blood-pressure.csv")
    val ecgPath: Path = directory.resolve("${this.configuration.baseName}_ecg.csv")

    private lateinit var rawWriter: CupRawWriter
    private var writeLease: CaptureSessionAccessRegistry.Lease? = null
    private var invalidFrames = 0L
    private var discardedBytes = 0L
    private var closed = false
    private var finalSummary: CaptureSessionSummary? = null
    private var terminalFailure: Throwable? = null
    private var snapshot = CaptureWriterSnapshot()
    private var nextSampleIndex = 0L
    private var firstStreamSampleIndex: Long? = null
    private var observedSamplesPerFrame: Int? = null
    private var observedProtocolProfile: String? = null
    private var lastCheckpointNanos = monotonicNanos()
    private var metricsInitialized = false
    private var bloodPressureInitialized = false
    private var ecgInitialized = false
    private var participantSnapshot: CaptureParticipantSnapshot? = this.configuration.participant

    private companion object {
        const val checkpointIntervalNanos = 1_000_000_000L
    }

    init {
        if (!CaptureSessionWriterPolicy.isValidBaseName(this.configuration.baseName)) {
            throw CaptureSessionWriterException.CannotCreate("invalid session name")
        }
        writeLease = accessRegistry.tryAcquire(directory, CaptureSessionAccessRegistry.Access.WRITE)
            ?: throw CaptureSessionWriterException.CannotCreate("session directory is in use")
        var createdDirectory = false
        try {
            Files.createDirectories(sessionsRoot)
            capacityProvider.usableBytes(sessionsRoot)?.let {
                if (it < CaptureSessionWriterPolicy.minimumAvailableCapacityBytes) {
                    throw CaptureSessionWriterException.InsufficientStorage
                }
            }
            try {
                Files.createDirectory(directory)
                createdDirectory = true
            } catch (error: java.nio.file.FileAlreadyExistsException) {
                throw CaptureSessionWriterException.SessionAlreadyExists(this.configuration.baseName)
            }
            rawWriter = CupRawWriter(rawPath)
            Files.newOutputStream(
                csvPath,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { output ->
                output.write(CaptureCsvSchema.header.toByteArray(Charsets.UTF_8))
            }
            writeMetadata(endedUtc = null, reason = null, complete = false, error = null)
        } catch (error: Throwable) {
            if (::rawWriter.isInitialized) runCatching { rawWriter.close() }
            if (createdDirectory) runCatching { directory.toFile().deleteRecursively() }
            releaseLease()
            if (error is CaptureSessionWriterException) throw error
            throw CaptureSessionWriterException.CannotCreate("cannot create session files", error)
        }
    }

    /** The recording decoder supplies only this session's cumulative evidence. */
    fun updateDecoderDiagnostics(invalidFrames: Long, discardedBytes: Long) {
        checkOpen()
        this.invalidFrames = invalidFrames
        this.discardedBytes = discardedBytes
    }

    fun append(event: CaptureStreamChunkEvent): CaptureWriterSnapshot {
        return appendRawThenDerive(
            hostMonotonicNanoseconds = event.hostMonotonicNanoseconds,
            data = event.data,
            acceptedSampleStartIndex = event.acceptedSampleStartIndex,
            metrics = event.metrics,
        ) { event.decodedFrames }
    }

    /** Appends one complete 1 Hz epoch. This method is called by the writer owner. */
    fun appendMetricEpoch(epoch: CaptureMetricEpoch): CaptureWriterSnapshot {
        checkOpen()
        require(epoch.sessionId == this.configuration.sessionId) { "metric session id mismatch" }
        ensureMetricsFile()
        appendBytes(metricsPath, CaptureMetricSeries.format(epoch).toByteArray(Charsets.UTF_8))
        snapshot = snapshot.copy(metricsRows = snapshot.metricsRows + 1)
        checkpointIfDue()
        return snapshot
    }

    /** Appends one manually entered reference BP event without touching raw/CSV. */
    fun appendBloodPressure(event: ManualBloodPressureEvent): CaptureWriterSnapshot {
        checkOpen()
        require(event.reference.sessionId == this.configuration.sessionId) {
            "blood pressure session id mismatch"
        }
        ensureBloodPressureFile()
        appendBytes(
            bloodPressurePath,
            CaptureBloodPressureSeries.format(event).toByteArray(Charsets.UTF_8),
        )
        snapshot = snapshot.copy(bloodPressureRows = snapshot.bloodPressureRows + 1)
        checkpointIfDue()
        return snapshot
    }

    fun appendAds1292rPacket(
        hostMonotonicNanoseconds: ULong,
        packet: Ads1292rPacket,
        sequenceEvent: CupSequenceEvent = CupSequenceEvent.Continuous,
        metrics: LiveMetricSnapshot = LiveMetricSnapshot.unavailable(
            hasConnectedDevice = true,
            freshness = com.example.ppgcollector_android.core.signal.StreamFreshness.FRESH,
        ),
    ): CaptureWriterSnapshot {
        checkOpen()
        require(configuration.protocolProfile == Ads1292rPacketProtocol.profileIdentifier) {
            "ECG packet requires ads1292r protocol configuration"
        }
        snapshot = snapshot.copy(
            missingFrames = snapshot.missingFrames +
                (sequenceEvent as? CupSequenceEvent.Gap)?.missingFrames.orZero(),
            duplicateFrames = snapshot.duplicateFrames +
                if (sequenceEvent is CupSequenceEvent.Duplicate) 1 else 0,
            outOfOrderFrames = snapshot.outOfOrderFrames +
                if (sequenceEvent is CupSequenceEvent.OutOfOrder) 1 else 0,
        )
        if (sequenceEvent is CupSequenceEvent.Duplicate ||
            sequenceEvent is CupSequenceEvent.OutOfOrder
        ) return snapshot
        observedSamplesPerFrame = packet.red.size
        observedProtocolProfile = configuration.protocolProfile
        if (firstStreamSampleIndex == null) firstStreamSampleIndex = nextSampleIndex
        ensureEcgFile()
        val ppg = StringBuilder()
        packet.red.indices.forEach { sampleInFrame ->
            ppg.append(
                CaptureCsvFormatter.format(
                    CaptureCsvRow(
                        CaptureSessionWriterPolicy.sensorPacketSampleSchemaVersion,
                        configuration.sessionId,
                        nextSampleIndex,
                        hostMonotonicNanoseconds,
                        packet.sequenceNumber,
                        sampleInFrame,
                        packet.red[sampleInFrame],
                        packet.ir[sampleInFrame],
                        metrics.heartRateBpm.toCsvCell(),
                        metrics.oxygenSaturationPercent.toCsvCell(),
                        metrics.signalQuality.toCsvCell(),
                        configuration.softVersion,
                        configuration.algorithmVersion,
                        configuration.preprocessProfile,
                        configuration.protocolProfile,
                        metrics.ratioOfRatios.toCsvCell(),
                    ),
                    firstStreamSampleIndex ?: nextSampleIndex,
                ),
            )
            nextSampleIndex++
            snapshot = snapshot.copy(
                csvRows = snapshot.csvRows + 1,
                acceptedSamples = snapshot.acceptedSamples + 1,
            )
        }
        appendBytes(csvPath, ppg.toString().toByteArray(Charsets.UTF_8))
        appendBytes(
            ecgPath,
            packet.ecg.mapIndexed { index, value ->
                CaptureEcgCsv.format(
                    configuration.sessionId,
                    snapshot.ecgRows + index,
                    packet.sequenceNumber,
                    value,
                )
            }.joinToString("").toByteArray(Charsets.UTF_8),
        )
        snapshot = snapshot.copy(
            acceptedFrames = snapshot.acceptedFrames + 1,
            ecgRows = snapshot.ecgRows + packet.ecg.size,
        )
        checkpointIfDue()
        return snapshot
    }

    private fun Int?.orZero(): Int = this ?: 0

    /** Called only by the serialized writer owner; publishes a new metadata snapshot. */
    fun updateParticipant(participant: CaptureParticipantSnapshot?) {
        checkOpen()
        participantSnapshot = participant
        writeMetadata(null, null, false, null)
    }

    /**
     * A raw acknowledgement is completed before [decode] is evaluated. This is
     * the seam used by the recording worker so decoder failures cannot erase
     * an already acknowledged notification from the raw source of truth.
     */
    fun appendRawThenDerive(
        hostMonotonicNanoseconds: ULong,
        data: ByteArray,
        acceptedSampleStartIndex: Long? = null,
        // This is a point-in-time snapshot at raw acknowledgement. Async
        // analysis results must never mutate rows already emitted to CSV.
        metrics: LiveMetricSnapshot = LiveMetricSnapshot.unavailable(
            hasConnectedDevice = true,
            freshness = com.example.ppgcollector_android.core.signal.StreamFreshness.FRESH,
        ),
        decode: () -> List<CupDecodedFrameEvent>,
    ): CaptureWriterSnapshot {
        checkOpen()
        if (data.isEmpty()) return snapshot
        if (data.size > CupRawFormat.maximumChunkLength) {
            throw CaptureSessionWriterException.ChunkTooLarge(data.size)
        }

        // This call intentionally precedes decoder/CSV work.
        rawWriter.append(hostMonotonicNanoseconds, data)
        snapshot = snapshot.copy(
            rawChunkCount = snapshot.rawChunkCount + 1,
            rawPayloadBytes = snapshot.rawPayloadBytes + data.size,
            rawFileBytes = rawWriter.bytesWritten,
        )

        val csv = StringBuilder()
        var streamIndex = acceptedSampleStartIndex
        for (decoded in decode()) {
            snapshot = snapshot.copy(
                missingFrames = snapshot.missingFrames +
                    ((decoded.sequenceEvent as? CupSequenceEvent.Gap)?.missingFrames ?: 0),
                duplicateFrames = snapshot.duplicateFrames +
                    if (decoded.sequenceEvent is CupSequenceEvent.Duplicate) 1 else 0,
                outOfOrderFrames = snapshot.outOfOrderFrames +
                    if (decoded.sequenceEvent is CupSequenceEvent.OutOfOrder) 1 else 0,
            )
            if (!decoded.isAccepted) continue
            val previousSamplesPerFrame = observedSamplesPerFrame
            if (previousSamplesPerFrame != null && previousSamplesPerFrame != decoded.frame.samples.size) {
                throw IllegalStateException("wire protocol changed within one capture session")
            }
            observedSamplesPerFrame = decoded.frame.samples.size
            observedProtocolProfile = decoded.frame.protocolProfileIdentifier
            snapshot = snapshot.copy(acceptedFrames = snapshot.acceptedFrames + 1)
            if (firstStreamSampleIndex == null) firstStreamSampleIndex = streamIndex ?: nextSampleIndex
            decoded.frame.samples.forEachIndexed { sampleInFrame, sample ->
                csv.append(
                    CaptureCsvFormatter.format(
                        CaptureCsvRow(
                            if (decoded.frame.wireProfile == CupWireFrameProfile.SENSOR_PACKET_168) {
                                CaptureSessionWriterPolicy.sensorPacketSampleSchemaVersion
                            } else {
                                CaptureSessionWriterPolicy.sampleSchemaVersion
                            },
                            this.configuration.sessionId,
                            nextSampleIndex,
                            hostMonotonicNanoseconds,
                            decoded.frame.sequenceNumber,
                            sampleInFrame,
                            sample.red,
                            sample.ir,
                            metrics.heartRateBpm.toCsvCell(),
                            metrics.oxygenSaturationPercent.toCsvCell(),
                            metrics.signalQuality.toCsvCell(),
                            this.configuration.softVersion,
                            this.configuration.algorithmVersion,
                            this.configuration.preprocessProfile,
                            observedProtocolProfile ?: this.configuration.protocolProfile,
                            metrics.ratioOfRatios.toCsvCell(),
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
            FileChannel.open(csvPath, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
                val bytes = csv.toString().toByteArray(Charsets.UTF_8)
                var offset = 0
                while (offset < bytes.size) {
                    offset += channel.write(java.nio.ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                }
            }
        }
        checkpointIfDue()
        return snapshot
    }

    fun finish(reason: CaptureStopReason, error: String? = null): CaptureSessionSummary {
        finalSummary?.let { return it }
        terminalFailure?.let { throw it }
        val ended = Instant.now()
        try {
            try {
                closeFiles()
            } catch (failure: Throwable) {
                // Never publish complete after even one failed force. Preserve
                // the original error if writing the incomplete marker also fails.
                runCatching {
                    writeMetadata(ended, CaptureStopReason.WRITE_ERROR, false,
                        failure.message ?: failure::class.simpleName)
                }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
            snapshot = snapshot.copy(lastFlushUtc = ended)
            val complete = error == null && reason in setOf(
                CaptureStopReason.USER, CaptureStopReason.VIEW_EXIT,
                CaptureStopReason.SCENE_BACKGROUND, CaptureStopReason.DEVICE_DISCONNECT,
                CaptureStopReason.DATA_TIMEOUT, CaptureStopReason.DURATION_ELAPSED,
            )
            writeMetadata(ended, reason, complete, error)
            return CaptureSessionSummary(
                configuration.sessionId, configuration.baseName, directory,
                configuration.startedUtc, ended, reason, complete, snapshot,
            ).also { finalSummary = it }
        } catch (failure: Throwable) {
            terminalFailure = failure
            throw failure
        } finally {
            releaseLease()
        }
    }

    fun discardIfEmptyBeforeRecording(): Boolean {
        if (finalSummary != null || snapshot.rawChunkCount != 0L || closed) return false
        try {
            closeFiles()
            directory.toFile().deleteRecursively()
            return true
        } finally {
            releaseLease()
        }
    }

    override fun close() {
        if (!closed) finish(CaptureStopReason.UNKNOWN, "writer closed without finalization")
    }

    private fun releaseLease() {
        writeLease?.close()
        writeLease = null
    }

    private fun checkOpen() {
        if (closed) throw CaptureSessionWriterException.WriterClosed
    }

    private fun closeFiles() {
        if (closed) return
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        // CupRawWriter closes its channel even when its force fails. The
        // injected hook must likewise never skip the actual close.
        attempt { beforeForce(rawPath) }
        attempt { rawWriter.close() }
        attempt { forceFile(csvPath) }
        if (metricsInitialized) attempt { forceFile(metricsPath) }
        if (bloodPressureInitialized) attempt { forceFile(bloodPressurePath) }
        if (ecgInitialized) attempt { forceFile(ecgPath) }
        closed = true
        failure?.let { throw it }
    }

    private fun checkpointIfDue() {
        val now = monotonicNanos()
        if (now - lastCheckpointNanos < checkpointIntervalNanos) return
        beforeForce(rawPath)
        rawWriter.flush()
        forceFile(csvPath)
        if (metricsInitialized) forceFile(metricsPath)
        if (bloodPressureInitialized) forceFile(bloodPressurePath)
        if (ecgInitialized) forceFile(ecgPath)
        snapshot = snapshot.copy(lastFlushUtc = Instant.now())
        writeMetadata(null, null, false, null)
        lastCheckpointNanos = now
    }

    private fun forceFile(path: Path) {
        beforeForce(path)
        FileChannel.open(path, StandardOpenOption.WRITE).use { it.force(true) }
    }

    private fun ensureMetricsFile() {
        if (metricsInitialized) return
        Files.newOutputStream(
            metricsPath,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { it.write(CaptureMetricSeries.header.toByteArray(Charsets.UTF_8)) }
        metricsInitialized = true
    }

    private fun ensureBloodPressureFile() {
        if (bloodPressureInitialized) return
        Files.newOutputStream(
            bloodPressurePath,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { it.write(CaptureBloodPressureSeries.header.toByteArray(Charsets.UTF_8)) }
        bloodPressureInitialized = true
    }

    private fun ensureEcgFile() {
        if (ecgInitialized) return
        Files.newOutputStream(
            ecgPath,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
        ).use { it.write(CaptureEcgCsv.header.toByteArray(Charsets.UTF_8)) }
        ecgInitialized = true
    }

    private fun appendBytes(path: Path, bytes: ByteArray) {
        FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
            var offset = 0
            while (offset < bytes.size) {
                offset += channel.write(java.nio.ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            }
        }
    }

    private fun writeMetadata(
        endedUtc: Instant?, reason: CaptureStopReason?, complete: Boolean, error: String?,
    ) {
        val metadata = CaptureSessionMetadata(
            schemaVersion = CaptureSessionWriterPolicy.sessionSchemaVersion,
            sessionId = this.configuration.sessionId,
            baseName = this.configuration.baseName,
            startedUtc = this.configuration.startedUtc,
            endedUtc = endedUtc,
            softVersion = this.configuration.softVersion,
            algVersion = this.configuration.algorithmVersion,
            preprocessProfile = this.configuration.preprocessProfile,
            protocolProfile = observedProtocolProfile ?: this.configuration.protocolProfile,
            transportProfile = this.configuration.transportProfile,
            sampleRateHz = CupBatchProtocolV1.sampleRateHz,
            samplesPerFrame = observedSamplesPerFrame ?: CupBatchProtocolV1.samplesPerFrame,
            device = CaptureSessionDeviceMetadata(this.configuration.device.name, this.configuration.device.identifier,
                this.configuration.device.serviceUuid, this.configuration.device.notifyCharacteristicUuid, null, null),
            complete = complete,
            stopReason = reason,
            frameCount = snapshot.acceptedFrames,
            sampleCount = snapshot.acceptedSamples,
            rawChunkCount = snapshot.rawChunkCount,
            missingFrames = snapshot.missingFrames,
            duplicateFrames = snapshot.duplicateFrames,
            outOfOrderFrames = snapshot.outOfOrderFrames,
            invalidFrames = invalidFrames,
            discardedBytes = discardedBytes,
            writer = CaptureSessionWriterMetadata(
                lastFlushUtc = snapshot.lastFlushUtc,
                rawBytes = snapshot.rawFileBytes,
                csvRows = snapshot.csvRows,
                error = error,
                metricsRows = snapshot.metricsRows,
                bloodPressureRows = snapshot.bloodPressureRows,
            ),
            files = CaptureSessionFilesMetadata(
                raw = rawPath.fileName.toString(),
                samples = csvPath.fileName.toString(),
                metrics = metricsPath.fileName.toString().takeIf { metricsInitialized },
                bloodPressure = bloodPressurePath.fileName.toString().takeIf { bloodPressureInitialized },
                ecg = ecgPath.fileName.toString().takeIf { ecgInitialized },
            ),
            recovery = null,
            canonicalSubjectId = this.configuration.canonicalSubjectId
                ?: SessionNamePolicy.parseCanonical(this.configuration.baseName)?.subject,
            canonicalSequence = this.configuration.canonicalSequence
                ?: SessionNamePolicy.parseCanonical(this.configuration.baseName)?.sequence,
            participant = participantSnapshot,
            systolicBp = this.configuration.systolicBp,
            diastolicBp = this.configuration.diastolicBp,
            recordMode = this.configuration.recordMode,
            plannedDurationSeconds = this.configuration.plannedDurationSeconds,
            ecgSampleRateHz = ecgInitialized.takeIf { it }?.let { CaptureEcgCsv.sampleRateHz },
        )
        val tempPath = metadataPath.resolveSibling(".${metadataPath.fileName}.tmp")
        val bytes = CaptureSessionMetadataCodec.encode(metadata).toByteArray(Charsets.UTF_8)
        try {
            FileChannel.open(
                tempPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            ).use { channel ->
                var offset = 0
                while (offset < bytes.size) {
                    offset += channel.write(java.nio.ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                }
                beforeForce(metadataPath)
                channel.force(true)
            }
            try {
                Files.move(
                    tempPath,
                    metadataPath,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tempPath,
                    metadataPath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            Files.deleteIfExists(tempPath)
        }
    }
}

private fun MetricResult<Double>.toCsvCell(): CsvMetricCell =
    CsvMetricCell(value, isValid, sourceSampleIndex)
