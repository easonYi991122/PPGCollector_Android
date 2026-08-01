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
        return SessionFileSet(
            raw = directory.resolve("$baseName.cupraw"),
            csv = directory.resolve("$baseName.csv"),
            metadata = directory.resolve("$baseName.session.json"),
        )
    }

    fun inspect(directory: Path): CaptureSessionInspection =
        CaptureSessionInspectionService.inspect(directory)

    private fun readEntry(directory: Path): StoredCaptureSession {
        val files = expectedFiles(directory)
        val metadata = runCatching {
            if (Files.isRegularFile(files.metadata)) {
                CaptureSessionMetadataCodec.decode(Files.readString(files.metadata))
            } else {
                null
            }
        }.getOrNull()
        val metadataIsReadable = Files.isRegularFile(files.metadata) && metadata != null
        val expected = listOf(files.raw, files.csv, files.metadata)
        val totalBytes = expected.sumOf { path ->
            runCatching { if (Files.isRegularFile(path)) Files.size(path) else 0L }.getOrDefault(0L)
        }
        val modifiedAt = expected.asSequence()
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
            hasAllExpectedFiles = expected.all(Files::isRegularFile),
            totalBytes = totalBytes,
        )
    }
}

data class SessionFileSet(
    val raw: Path,
    val csv: Path,
    val metadata: Path,
)
