package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.protocol.CupStreamProtocolMode
import com.example.ppgcollector_android.core.protocol.CupBatchProtocolV1
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

data class CaptureSessionRecoveryAssessment(
    val canCreateRecoveryCopy: Boolean,
    val requiresRecovery: Boolean,
    val rawTrailingBytes: Long,
    val csvTrailingBytes: Long,
    val reason: String?,
)

data class CaptureSessionRecoveryResult(
    val sessionId: String,
    val baseName: String,
    val directory: Path,
    val rawPath: Path,
    val csvPath: Path,
    val metadataPath: Path,
    val rawCopiedBytes: Long,
    val csvCopiedBytes: Long,
    val rawDiscardedTailBytes: Long,
    val csvDiscardedTailBytes: Long,
    val metricsPath: Path? = null,
    val bloodPressurePath: Path? = null,
    val ecgPath: Path? = null,
    val metricsCopiedBytes: Long = 0,
    val bloodPressureCopiedBytes: Long = 0,
    val ecgCopiedBytes: Long = 0,
)

sealed class CaptureSessionRecoveryException(message: String) : Exception(message) {
    data object SourceRawMissing : CaptureSessionRecoveryException("source raw file is missing")
    data object SourceCsvMissing : CaptureSessionRecoveryException("source CSV file is missing")
    data object SourceCsvHeaderInvalid :
        CaptureSessionRecoveryException("source CSV header is not the v1 schema")
    class DestinationAlreadyExists(name: String) :
        CaptureSessionRecoveryException("recovery destination already exists: $name")
    class CannotCreate(message: String, cause: Throwable? = null) :
        CaptureSessionRecoveryException(message) {
        init {
            if (cause != null) initCause(cause)
        }
    }
}

/** Safe-prefix recovery; the source directory is never opened for writing. */
object CaptureSessionRecoveryService {
    const val strategy = "copy_safe_prefix_v1"

    fun assess(
        session: StoredCaptureSession,
        cancellationCheck: () -> Unit = {},
    ): CaptureSessionRecoveryAssessment = requireSessionLease(
        session.directory, CaptureSessionAccessRegistry.Access.READ_SNAPSHOT,
    ).use {
        if (!session.isRecoveryCandidate) {
            return CaptureSessionRecoveryAssessment(false, false, 0, 0, null)
        }
        val files = CaptureSessionRepository.expectedFiles(session.directory, cancellationCheck)
        if (!Files.isRegularFile(files.raw) || !Files.isRegularFile(files.csv)) {
            return CaptureSessionRecoveryAssessment(
                canCreateRecoveryCopy = false,
                requiresRecovery = true,
                rawTrailingBytes = 0,
                csvTrailingBytes = 0,
                reason = "raw or CSV is missing; no complete recovery copy can be created",
            )
        }
        return try {
            val raw = CupRawReader.scan(files.raw, cancellationCheck)
            val csv = CaptureSessionInspectionService.scanCsv(
                files.csv, session.metadata.allowedRowSessionIds(),
                files.raw, CupStreamProtocolMode.fromProtocolProfileIdentifier(session.metadata?.protocolProfile),
                cancellationCheck,
            )
            if (!csv.hasExpectedHeader) {
                CaptureSessionRecoveryAssessment(
                    canCreateRecoveryCopy = false,
                    requiresRecovery = true,
                    rawTrailingBytes = raw.trailingByteCount,
                    csvTrailingBytes = csv.trailingByteCount,
                    reason = "CSV header is not the v1 schema",
                )
            } else {
                val countMismatch = session.metadata?.let {
                    it.rawChunkCount != raw.recordCount ||
                        it.sampleCount != csv.completeDataRowCount
                } == true
                val requires = raw.tailIssue != null ||
                    csv.hasTruncatedFinalLine || countMismatch || session.isRecoveryCandidate
                CaptureSessionRecoveryAssessment(
                    canCreateRecoveryCopy = true,
                    requiresRecovery = requires,
                    rawTrailingBytes = raw.trailingByteCount,
                    csvTrailingBytes = csv.trailingByteCount,
                    reason = if (requires) "source has incomplete files or metadata" else null,
                )
            }
        } catch (error: Exception) {
            if (error is java.util.concurrent.CancellationException) throw error
            CaptureSessionRecoveryAssessment(
                canCreateRecoveryCopy = false,
                requiresRecovery = true,
                rawTrailingBytes = 0,
                csvTrailingBytes = 0,
                reason = error.message ?: error::class.simpleName,
            )
        }
    }

    fun suggestedBaseName(session: StoredCaptureSession): String {
        val sanitized = sanitizeBaseName(session.baseName)
        var attempt = 1
        while (attempt < 10_000) {
            val suffix = if (attempt == 1) "_recovered" else "_recovered_$attempt"
            val prefix = sanitized.take(maxOf(1, 64 - suffix.length))
            val candidate = prefix + suffix
            if (!Files.exists(session.directory.resolveSibling(candidate))) return candidate
            attempt += 1
        }
        return "recovered_${UUID.randomUUID().toString().take(8)}"
    }

    fun recover(
        session: StoredCaptureSession,
        requestedBaseName: String,
        recoveredAt: Instant = Instant.now(),
        recoverySessionId: String = UUID.randomUUID().toString(),
        recoverySoftVersion: String = "android-unknown",
        cancellationCheck: () -> Unit = {},
        accessRegistry: CaptureSessionAccessRegistry = CaptureSessionAccessRegistry.app,
    ): CaptureSessionRecoveryResult = requireSessionLease(
        session.directory, CaptureSessionAccessRegistry.Access.READ_SNAPSHOT, accessRegistry,
    ).use {
        cancellationCheck()
        if (!CaptureSessionWriterPolicy.isValidBaseName(requestedBaseName)) {
            throw CaptureSessionRecoveryException.CannotCreate("invalid recovery session name")
        }
        val sourceFiles = CaptureSessionRepository.expectedFiles(session.directory, cancellationCheck)
        if (!Files.isRegularFile(sourceFiles.raw)) {
            throw CaptureSessionRecoveryException.SourceRawMissing
        }
        if (!Files.isRegularFile(sourceFiles.csv)) {
            throw CaptureSessionRecoveryException.SourceCsvMissing
        }

        val sourceVersions = sourceFiles.allPaths.filter(Files::isRegularFile).map {
            CaptureExportSource.freeze(it, it.fileName.toString(), cancellationCheck)
        }
        val sourceMetadata = if (Files.isRegularFile(sourceFiles.metadata)) {
            try { CaptureSessionMetadataCodec.decode(sourceFiles.metadata, cancellationCheck) }
            catch (_: IllegalArgumentException) { null }
        } else null
        val allowedIds = sourceMetadata.allowedRowSessionIds()
        val rawScan = try {
            CupRawReader.scan(sourceFiles.raw, cancellationCheck)
        } catch (error: Exception) {
            if (error is java.util.concurrent.CancellationException) throw error
            throw CaptureSessionRecoveryException.CannotCreate("cannot scan source raw", error)
        }
        val csvScan = try {
            CaptureSessionInspectionService.scanCsv(
                sourceFiles.csv, allowedIds, sourceFiles.raw,
                CupStreamProtocolMode.fromProtocolProfileIdentifier(sourceMetadata?.protocolProfile),
                cancellationCheck,
            )
        } catch (error: Exception) {
            if (error is java.util.concurrent.CancellationException) throw error
            throw CaptureSessionRecoveryException.CannotCreate("cannot scan source CSV", error)
        }
        if (!csvScan.hasExpectedHeader) {
            throw CaptureSessionRecoveryException.SourceCsvHeaderInvalid
        }

        val destination = session.directory.resolveSibling(requestedBaseName)
        if (Files.exists(destination)) {
            throw CaptureSessionRecoveryException.DestinationAlreadyExists(requestedBaseName)
        }
        val destinationLease = requireSessionLease(destination, CaptureSessionAccessRegistry.Access.WRITE, accessRegistry)
        val staging = session.directory.resolveSibling(".recovery-$recoverySessionId")
        var committed = false
        try {
            Files.createDirectory(staging)
            val metricsScan = sourceFiles.metrics?.let { path ->
                if (!Files.isRegularFile(path)) null else CaptureMetricSeries.scan(path, allowedIds, cancellationCheck)
            }
            val bloodPressureScan = sourceFiles.bloodPressure?.let { path ->
                if (!Files.isRegularFile(path)) null else CaptureBloodPressureSeries.scan(path, allowedIds, cancellationCheck)
            }
            val ecgScan = sourceFiles.ecg?.let { path ->
                if (!Files.isRegularFile(path)) null else CaptureEcgCsv.scan(path, allowedIds, cancellationCheck)
            }
            val destinationFiles = SessionFileSet(
                raw = staging.resolve("$requestedBaseName.cupraw"),
                csv = staging.resolve("$requestedBaseName.csv"),
                metadata = staging.resolve("$requestedBaseName.session.json"),
                metrics = sourceFiles.metrics?.takeIf { metricsScan != null }?.let {
                    staging.resolve("$requestedBaseName.metrics.csv")
                },
                bloodPressure = sourceFiles.bloodPressure?.takeIf { bloodPressureScan != null }?.let {
                    staging.resolve("$requestedBaseName.blood-pressure.csv")
                },
                ecg = sourceFiles.ecg?.takeIf { ecgScan != null }?.let {
                    staging.resolve("${requestedBaseName}_ecg.csv")
                },
            )
            copyPrefix(sourceFiles.raw, rawScan.validByteCount, destinationFiles.raw, cancellationCheck)
            copyPrefix(sourceFiles.csv, csvScan.validByteCount, destinationFiles.csv, cancellationCheck)
            if (destinationFiles.metrics != null && metricsScan != null) {
                copyPrefix(sourceFiles.metrics!!, metricsScan.validByteCount, destinationFiles.metrics, cancellationCheck)
            }
            if (destinationFiles.bloodPressure != null && bloodPressureScan != null) {
                copyPrefix(
                    sourceFiles.bloodPressure!!,
                    bloodPressureScan.validByteCount,
                    destinationFiles.bloodPressure, cancellationCheck,
                )
            }
            if (destinationFiles.ecg != null && ecgScan != null) {
                copyPrefix(sourceFiles.ecg!!, ecgScan.validByteCount, destinationFiles.ecg, cancellationCheck)
            }

            val sourceMetadataBytes = if (Files.isRegularFile(sourceFiles.metadata)) {
                CaptureSessionMetadataCodec.readBytes(sourceFiles.metadata, cancellationCheck)
            } else {
                null
            }
            val replay = CupRawReplayEngine.replay(
                sourceFiles.raw,
                CupStreamProtocolMode.fromProtocolProfileIdentifier(
                    sourceMetadata?.protocolProfile ?: session.metadata?.protocolProfile,
                ),
                cancellationCheck,
            ) {}
            val recoveryMetadata = CaptureSessionRecoveryMetadata(
                strategy = strategy,
                recoveredUtc = recoveredAt,
                recoverySoftVersion = recoverySoftVersion,
                sourceDirectoryName = session.baseName,
                sourceSessionId = if (sourceMetadata?.recovery != null) {
                    sourceMetadata.recovery.sourceSessionId
                } else sourceMetadata?.sessionId,
                parentSessionId = sourceMetadata?.sessionId,
                originalCanonicalPrefix = sourceMetadata?.recovery?.originalCanonicalPrefix
                    ?: SessionNamePolicy.parseCanonical(session.baseName)?.prefix?.wireValue,
                sourceRawSha256 = sha256File(sourceFiles.raw, cancellationCheck),
                sourceCsvSha256 = sha256File(sourceFiles.csv, cancellationCheck),
                sourceMetadataSha256 = sourceMetadataBytes?.let(::sha256Bytes),
                sourceRawTotalBytes = Files.size(sourceFiles.raw),
                sourceRawCopiedBytes = rawScan.validByteCount,
                sourceCsvTotalBytes = Files.size(sourceFiles.csv),
                sourceCsvCopiedBytes = csvScan.validByteCount,
                csvPreservesSourceSessionId = true,
                sourceMetricsSha256 = sourceFiles.metrics?.takeIf(Files::isRegularFile)?.let { sha256File(it, cancellationCheck) },
                sourceMetricsTotalBytes = sourceFiles.metrics?.takeIf(Files::isRegularFile)
                    ?.let(Files::size) ?: 0L,
                sourceMetricsCopiedBytes = metricsScan?.validByteCount ?: 0L,
                sourceBloodPressureSha256 = sourceFiles.bloodPressure
                    ?.takeIf(Files::isRegularFile)?.let { sha256File(it, cancellationCheck) },
                sourceBloodPressureTotalBytes = sourceFiles.bloodPressure
                    ?.takeIf(Files::isRegularFile)?.let(Files::size) ?: 0L,
                sourceBloodPressureCopiedBytes = bloodPressureScan?.validByteCount ?: 0L,
            )
            val recoveredMetadata = buildRecoveredMetadata(
                source = sourceMetadata ?: session.metadata,
                session = session,
                recoverySessionId = recoverySessionId,
                baseName = requestedBaseName,
                recoveredAt = recoveredAt,
                recovery = recoveryMetadata,
                replay = replay,
                csv = csvScan,
                metrics = metricsScan,
                bloodPressure = bloodPressureScan,
                ecg = ecgScan,
            )
            val copiedEcgBytes = destinationFiles.ecg?.let { Files.size(it) } ?: 0L
            writeMetadataAtomically(destinationFiles.metadata, recoveredMetadata)
            sourceVersions.forEach { it.verify(cancellationCheck) }
            cancellationCheck()
            moveStaging(staging, destination)
            committed = true
            return CaptureSessionRecoveryResult(
                sessionId = recoverySessionId,
                baseName = requestedBaseName,
                directory = destination,
                rawPath = destination.resolve(destinationFiles.raw.fileName.toString()),
                csvPath = destination.resolve(destinationFiles.csv.fileName.toString()),
                metadataPath = destination.resolve(destinationFiles.metadata.fileName.toString()),
                rawCopiedBytes = rawScan.validByteCount,
                csvCopiedBytes = csvScan.validByteCount,
                rawDiscardedTailBytes = rawScan.trailingByteCount,
                csvDiscardedTailBytes = csvScan.trailingByteCount,
                metricsPath = destinationFiles.metrics?.let { destination.resolve(it.fileName.toString()) },
                bloodPressurePath = destinationFiles.bloodPressure
                    ?.let { destination.resolve(it.fileName.toString()) },
                ecgPath = destinationFiles.ecg
                    ?.let { destination.resolve(it.fileName.toString()) },
                metricsCopiedBytes = metricsScan?.validByteCount ?: 0L,
                bloodPressureCopiedBytes = bloodPressureScan?.validByteCount ?: 0L,
                ecgCopiedBytes = copiedEcgBytes,
            )
        } catch (error: CaptureSessionRecoveryException) {
            throw error
        } catch (error: Exception) {
            if (error is java.util.concurrent.CancellationException) throw error
            throw CaptureSessionRecoveryException.CannotCreate("cannot create recovery copy", error)
        } finally {
            if (!committed) staging.toFile().deleteRecursively()
            destinationLease.close()
        }
    }

    private fun buildRecoveredMetadata(
        source: CaptureSessionMetadata?,
        session: StoredCaptureSession,
        recoverySessionId: String,
        baseName: String,
        recoveredAt: Instant,
        recovery: CaptureSessionRecoveryMetadata,
        replay: CupRawReplayReport,
        csv: CaptureCsvScanReport,
        metrics: CaptureSidecarScanReport?,
        bloodPressure: CaptureSidecarScanReport?,
        ecg: CaptureSidecarScanReport?,
    ) = CaptureSessionMetadata(
        schemaVersion = CaptureSessionWriterPolicy.sessionSchemaVersion,
        sessionId = recoverySessionId,
        baseName = baseName,
        startedUtc = source?.startedUtc ?: session.modifiedAt,
        endedUtc = recoveredAt,
        softVersion = source?.softVersion ?: "unknown",
        algVersion = source?.algVersion ?: "unknown",
        preprocessProfile = source?.preprocessProfile ?: "unknown",
        protocolProfile = source?.protocolProfile ?: "unknown",
        transportProfile = source?.transportProfile ?: "unknown",
        sampleRateHz = source?.sampleRateHz ?: CupBatchProtocolV1.sampleRateHz,
        samplesPerFrame = source?.samplesPerFrame ?: CupBatchProtocolV1.samplesPerFrame,
        device = source?.device ?: CaptureSessionDeviceMetadata(
            name = "unknown",
            identifier = "unknown",
            serviceUuid = "",
            notifyCharacteristicUuid = "",
            firmwareVersion = null,
            calibrationId = null,
        ),
        complete = false,
        stopReason = CaptureStopReason.CRASH_RECOVERY,
        frameCount = replay.acceptedFrames.toLong(),
        sampleCount = replay.acceptedSamples,
        rawChunkCount = replay.rawRecordCount,
        missingFrames = replay.missingFrames.toLong(),
        duplicateFrames = replay.duplicateFrames.toLong(),
        outOfOrderFrames = replay.outOfOrderFrames.toLong(),
        invalidFrames = replay.structurallyInvalidFrames.toLong(),
        discardedBytes = replay.discardedBytes.toLong(),
        writer = CaptureSessionWriterMetadata(
            lastFlushUtc = source?.writer?.lastFlushUtc,
            rawBytes = replay.validRawBytes,
            csvRows = csv.completeDataRowCount,
            error = "Recovered copy; source session was preserved unchanged.",
            metricsRows = metrics?.completeDataRowCount ?: 0L,
            bloodPressureRows = bloodPressure?.completeDataRowCount ?: 0L,
        ),
        files = CaptureSessionFilesMetadata(
            raw = "$baseName.cupraw",
            samples = "$baseName.csv",
            metrics = metrics?.let { "$baseName.metrics.csv" },
            bloodPressure = bloodPressure?.let { "$baseName.blood-pressure.csv" },
            ecg = ecg?.let { "${baseName}_ecg.csv" },
        ),
        recovery = recovery,
        canonicalSubjectId = source?.canonicalSubjectId,
        canonicalSequence = source?.canonicalSequence,
        participant = source?.participant,
        systolicBp = source?.systolicBp,
        diastolicBp = source?.diastolicBp,
        recordMode = source?.recordMode,
        plannedDurationSeconds = source?.plannedDurationSeconds,
        ecgSampleRateHz = source?.ecgSampleRateHz,
        bloodPressureUpdatedUtc = source?.bloodPressureUpdatedUtc,
    )

    private fun copyPrefix(source: Path, count: Long, destination: Path, cancellationCheck: () -> Unit) {
        Files.newInputStream(source, StandardOpenOption.READ).use { input ->
            FileChannel.open(
                destination,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { output ->
                copyExactly(input, output, count, cancellationCheck)
                output.force(true)
            }
        }
    }

    private fun copyExactly(input: InputStream, output: FileChannel, count: Long, cancellationCheck: () -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var remaining = count
        while (remaining > 0) {
            cancellationCheck()
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw IllegalStateException("source ended before safe prefix")
            if (read == 0) continue
            var offset = 0
            while (offset < read) offset += output.write(ByteBuffer.wrap(buffer, offset, read - offset))
            remaining -= read
        }
    }

    private fun writeMetadataAtomically(path: Path, metadata: CaptureSessionMetadata) {
        val temp = path.resolveSibling(".${path.fileName}.tmp")
        val bytes = CaptureSessionMetadataCodec.encodeBytes(metadata)
        try {
            FileChannel.open(
                temp,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            ).use { channel ->
                var offset = 0
                while (offset < bytes.size) offset += channel.write(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
                channel.force(true)
            }
            moveStaging(temp, path)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun moveStaging(source: Path, destination: Path) {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, destination)
        }
    }

    private fun sha256File(path: Path, cancellationCheck: () -> Unit): String =
        CaptureExportSource.hash(path, cancellationCheck)

    private fun sha256Bytes(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun sanitizeBaseName(value: String): String {
        val result = value.map { char ->
            if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '_' || char == '-') {
                char
            } else {
                '_'
            }
        }.joinToString("")
        return result.ifEmpty { "session" }
    }
}
