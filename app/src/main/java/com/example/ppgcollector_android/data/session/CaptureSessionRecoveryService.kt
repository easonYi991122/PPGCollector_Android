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

    fun assess(session: StoredCaptureSession): CaptureSessionRecoveryAssessment {
        if (!session.isRecoveryCandidate) {
            return CaptureSessionRecoveryAssessment(false, false, 0, 0, null)
        }
        val files = CaptureSessionRepository.expectedFiles(session.directory)
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
            val raw = CupRawReader.scan(files.raw)
            val csv = CaptureSessionInspectionService.scanCsv(files.csv)
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
    ): CaptureSessionRecoveryResult {
        if (!CaptureSessionWriterPolicy.isValidBaseName(requestedBaseName)) {
            throw CaptureSessionRecoveryException.CannotCreate("invalid recovery session name")
        }
        val sourceFiles = CaptureSessionRepository.expectedFiles(session.directory)
        if (!Files.isRegularFile(sourceFiles.raw)) {
            throw CaptureSessionRecoveryException.SourceRawMissing
        }
        if (!Files.isRegularFile(sourceFiles.csv)) {
            throw CaptureSessionRecoveryException.SourceCsvMissing
        }

        val rawScan = try {
            CupRawReader.scan(sourceFiles.raw)
        } catch (error: Exception) {
            throw CaptureSessionRecoveryException.CannotCreate("cannot scan source raw", error)
        }
        val csvScan = try {
            CaptureSessionInspectionService.scanCsv(sourceFiles.csv)
        } catch (error: Exception) {
            throw CaptureSessionRecoveryException.CannotCreate("cannot scan source CSV", error)
        }
        if (!csvScan.hasExpectedHeader) {
            throw CaptureSessionRecoveryException.SourceCsvHeaderInvalid
        }

        val destination = session.directory.resolveSibling(requestedBaseName)
        if (Files.exists(destination)) {
            throw CaptureSessionRecoveryException.DestinationAlreadyExists(requestedBaseName)
        }
        val staging = session.directory.resolveSibling(".recovery-$recoverySessionId")
        var committed = false
        try {
            Files.createDirectory(staging)
            val metricsScan = sourceFiles.metrics?.let { path ->
                if (!Files.isRegularFile(path)) null else CaptureMetricSeries.scan(path)
            }
            val bloodPressureScan = sourceFiles.bloodPressure?.let { path ->
                if (!Files.isRegularFile(path)) null else CaptureBloodPressureSeries.scan(path)
            }
            val ecgScan = sourceFiles.ecg?.let { path ->
                if (!Files.isRegularFile(path)) null else CaptureEcgCsv.scan(path)
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
            copyPrefix(sourceFiles.raw, rawScan.validByteCount, destinationFiles.raw)
            copyPrefix(sourceFiles.csv, csvScan.validByteCount, destinationFiles.csv)
            if (destinationFiles.metrics != null && metricsScan != null) {
                copyPrefix(sourceFiles.metrics!!, metricsScan.validByteCount, destinationFiles.metrics)
            }
            if (destinationFiles.bloodPressure != null && bloodPressureScan != null) {
                copyPrefix(
                    sourceFiles.bloodPressure!!,
                    bloodPressureScan.validByteCount,
                    destinationFiles.bloodPressure,
                )
            }
            if (destinationFiles.ecg != null && ecgScan != null) {
                copyPrefix(sourceFiles.ecg!!, ecgScan.validByteCount, destinationFiles.ecg)
            }

            val sourceMetadataBytes = if (Files.isRegularFile(sourceFiles.metadata)) {
                Files.readAllBytes(sourceFiles.metadata)
            } else {
                null
            }
            val sourceMetadata = sourceMetadataBytes?.let {
                runCatching { CaptureSessionMetadataCodec.decode(it) }.getOrNull()
            }
            val replay = CupRawReplayEngine.replay(
                sourceFiles.raw,
                CupStreamProtocolMode.fromProtocolProfileIdentifier(
                    sourceMetadata?.protocolProfile ?: session.metadata?.protocolProfile,
                ),
            )
            val recoveryMetadata = CaptureSessionRecoveryMetadata(
                strategy = strategy,
                recoveredUtc = recoveredAt,
                recoverySoftVersion = recoverySoftVersion,
                sourceDirectoryName = session.baseName,
                sourceSessionId = sourceMetadata?.sessionId ?: session.metadata?.sessionId,
                sourceRawSha256 = sha256File(sourceFiles.raw),
                sourceCsvSha256 = sha256File(sourceFiles.csv),
                sourceMetadataSha256 = sourceMetadataBytes?.let(::sha256Bytes),
                sourceRawTotalBytes = Files.size(sourceFiles.raw),
                sourceRawCopiedBytes = rawScan.validByteCount,
                sourceCsvTotalBytes = Files.size(sourceFiles.csv),
                sourceCsvCopiedBytes = csvScan.validByteCount,
                csvPreservesSourceSessionId = true,
                sourceMetricsSha256 = sourceFiles.metrics?.takeIf(Files::isRegularFile)?.let(::sha256File),
                sourceMetricsTotalBytes = sourceFiles.metrics?.takeIf(Files::isRegularFile)
                    ?.let(Files::size) ?: 0L,
                sourceMetricsCopiedBytes = metricsScan?.validByteCount ?: 0L,
                sourceBloodPressureSha256 = sourceFiles.bloodPressure
                    ?.takeIf(Files::isRegularFile)?.let(::sha256File),
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
            )
            writeMetadataAtomically(destinationFiles.metadata, recoveredMetadata)
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
                ecgCopiedBytes = destinationFiles.ecg?.let { Files.size(it) } ?: 0L,
            )
        } catch (error: CaptureSessionRecoveryException) {
            throw error
        } catch (error: Exception) {
            throw CaptureSessionRecoveryException.CannotCreate("cannot create recovery copy", error)
        } finally {
            if (!committed) staging.toFile().deleteRecursively()
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
                    ecg = source?.files?.ecg?.let { "$baseName" + "_ecg.csv" },
                ),
        recovery = recovery,
        canonicalSubjectId = source?.canonicalSubjectId,
        canonicalSequence = source?.canonicalSequence,
        participant = source?.participant,
    )

    private fun copyPrefix(source: Path, count: Long, destination: Path) {
        Files.newInputStream(source, StandardOpenOption.READ).use { input ->
            FileChannel.open(
                destination,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { output ->
                copyExactly(input, output, count)
                output.force(true)
            }
        }
    }

    private fun copyExactly(input: InputStream, output: FileChannel, count: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = count
        while (remaining > 0) {
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

    private fun sha256File(path: Path): String =
        Files.newInputStream(path, StandardOpenOption.READ).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

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
