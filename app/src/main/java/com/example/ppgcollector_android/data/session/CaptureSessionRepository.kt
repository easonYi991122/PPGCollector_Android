package com.example.ppgcollector_android.data.session

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.stream.Collectors

data class StoredCaptureSession(
    val directory: Path,
    val baseName: String,
    val modifiedAt: Instant,
    val metadata: CaptureSessionMetadata?,
    val metadataIsReadable: Boolean,
    val hasAllExpectedFiles: Boolean,
    val totalBytes: Long,
) {
    val isComplete: Boolean?
        get() = metadata?.complete

    val isVerifiedComplete: Boolean
        get() = metadata?.complete == true &&
            metadataIsReadable && hasAllExpectedFiles

    val isRecoveryCandidate: Boolean
        get() = !metadataIsReadable || metadata?.complete != true || !hasAllExpectedFiles
}

/** The session directory is authoritative; no database index is required to list captures. */
object CaptureSessionRepository {
    fun listSessions(root: Path): List<StoredCaptureSession> {
        if (!Files.isDirectory(root)) return emptyList()
        Files.list(root).use { entries ->
            return entries
                .filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }
                .map(::readEntry)
                .collect(Collectors.toList())
                .sortedWith(
                    compareByDescending<StoredCaptureSession> { it.modifiedAt }
                        .thenByDescending { it.baseName },
                )
        }
    }

    fun incompleteSessions(root: Path): List<StoredCaptureSession> =
        listSessions(root).filter(StoredCaptureSession::isRecoveryCandidate)

    fun expectedFiles(directory: Path): SessionFileSet {
        val baseName = directory.fileName.toString()
        val conventionalMetrics = directory.resolve("$baseName.metrics.csv")
        val conventionalBloodPressure = directory.resolve("$baseName.blood-pressure.csv")
        val conventionalEcg = directory.resolve("${baseName}_ecg.csv")
        val metadata = directory.resolve("$baseName.session.json")
            .takeIf(Files::isRegularFile)
            ?.let { runCatching { CaptureSessionMetadataCodec.decode(it) }.getOrNull() }
        return SessionFileSet(
            raw = directory.resolve("$baseName.cupraw"),
            csv = directory.resolve("$baseName.csv"),
            metadata = directory.resolve("$baseName.session.json"),
            metrics = metadata?.files?.metrics?.let(directory::resolve)
                ?: conventionalMetrics.takeIf(Files::exists),
            bloodPressure = metadata?.files?.bloodPressure?.let(directory::resolve)
                ?: conventionalBloodPressure.takeIf(Files::exists),
            ecg = metadata?.files?.ecg?.let(directory::resolve)
                ?: conventionalEcg.takeIf(Files::exists),
        )
    }

    fun inspect(directory: Path): CaptureSessionInspection =
        CaptureSessionInspectionService.inspect(directory)

    private fun readEntry(directory: Path): StoredCaptureSession {
        val files = expectedFiles(directory)
        val metadata = runCatching {
            if (Files.isRegularFile(files.metadata)) {
                CaptureSessionMetadataCodec.decode(files.metadata)
            } else {
                null
            }
        }.getOrNull()
        val metadataIsReadable = Files.isRegularFile(files.metadata) && metadata != null
        val totalBytes = files.allPaths.sumOf { path ->
            runCatching { if (Files.isRegularFile(path)) Files.size(path) else 0L }.getOrDefault(0L)
        }
        val modifiedAt = files.allPaths.asSequence()
            .filter(Files::exists)
            .map { path -> runCatching { Files.getLastModifiedTime(path) }.getOrNull() }
            .filterNotNull()
            .maxOrNull()
            ?.toInstant()
            ?: runCatching { Files.getLastModifiedTime(directory).toInstant() }
                .getOrDefault(Instant.EPOCH)
        return StoredCaptureSession(
            directory = directory,
            baseName = directory.fileName.toString(),
            modifiedAt = modifiedAt,
            metadata = metadata,
            metadataIsReadable = metadataIsReadable,
            hasAllExpectedFiles = files.requiredPaths.all(Files::isRegularFile) &&
                files.optionalPaths.all(Files::isRegularFile),
            totalBytes = totalBytes,
        )
    }
}

data class SessionFileSet(
    val raw: Path,
    val csv: Path,
    val metadata: Path,
    val metrics: Path? = null,
    val bloodPressure: Path? = null,
    val ecg: Path? = null,
) {
    val requiredPaths: List<Path> get() = listOf(raw, csv, metadata)
    val optionalPaths: List<Path> get() = listOfNotNull(metrics, bloodPressure, ecg)
    val allPaths: List<Path> get() = requiredPaths + optionalPaths
    val allNamedPaths: List<Pair<Path, String>> get() = allPaths.map { it to it.fileName.toString() }
}
