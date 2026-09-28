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
class CaptureSessionBusyException(val directory: Path) : IllegalStateException("session directory is in use")

enum class CaptureSessionDeleteResult { DELETED, BUSY, NOT_FOUND }

internal fun requireSessionLease(
    directory: Path,
    access: CaptureSessionAccessRegistry.Access,
    registry: CaptureSessionAccessRegistry = CaptureSessionAccessRegistry.app,
): CaptureSessionAccessRegistry.Lease = registry.tryAcquire(directory, access)
    ?: throw CaptureSessionBusyException(directory)

object CaptureSessionRepository {
    /** Lease acquisition and containment are checked again at the destructive boundary. */
    fun deleteSession(
        sessionsRoot: Path,
        directory: Path,
        accessRegistry: CaptureSessionAccessRegistry = CaptureSessionAccessRegistry.app,
    ): CaptureSessionDeleteResult {
        val root = sessionsRoot.toAbsolutePath().normalize()
        val target = directory.toAbsolutePath().normalize()
        require(target.parent == root && !target.fileName.toString().startsWith(".")) { "not a session directory" }
        val lease = accessRegistry.tryAcquire(target, CaptureSessionAccessRegistry.Access.DESTRUCTIVE)
            ?: return CaptureSessionDeleteResult.BUSY
        lease.use {
            if (!Files.exists(target)) return CaptureSessionDeleteResult.NOT_FOUND
            require(!Files.isSymbolicLink(target) && Files.isDirectory(target)) { "not a physical session directory" }
            require(target.toRealPath().parent == root.toRealPath()) { "session escapes root" }
            Files.walk(target).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            }
            return CaptureSessionDeleteResult.DELETED
        }
    }

    fun listSessions(root: Path, cancellationCheck: () -> Unit = {}): List<StoredCaptureSession> {
        if (!Files.isDirectory(root)) return emptyList()
        Files.list(root).use { entries ->
            return entries
                .filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }
                .map { cancellationCheck(); readEntry(it, cancellationCheck) }
                .collect(Collectors.toList())
                .sortedWith(
                    compareByDescending<StoredCaptureSession> { it.modifiedAt }
                        .thenByDescending { it.baseName },
                )
        }
    }

    fun incompleteSessions(root: Path): List<StoredCaptureSession> =
        listSessions(root).filter(StoredCaptureSession::isRecoveryCandidate)

    fun expectedFiles(directory: Path, cancellationCheck: () -> Unit = {}): SessionFileSet {
        val baseName = directory.fileName.toString()
        val conventionalMetrics = directory.resolve("$baseName.metrics.csv")
        val conventionalBloodPressure = directory.resolve("$baseName.blood-pressure.csv")
        val conventionalEcg = directory.resolve("${baseName}_ecg.csv")
        val metadata = directory.resolve("$baseName.session.json")
            .takeIf(Files::isRegularFile)
            ?.let { readMetadataOrNull(it, cancellationCheck) }
        fun sidecar(name: String): Path {
            require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name) {
                "sidecar filename escapes session directory"
            }
            return directory.resolve(name)
        }
        return SessionFileSet(
            raw = directory.resolve("$baseName.cupraw"),
            csv = directory.resolve("$baseName.csv"),
            metadata = directory.resolve("$baseName.session.json"),
            metrics = metadata?.files?.metrics?.let(::sidecar)
                ?: conventionalMetrics.takeIf(Files::exists),
            bloodPressure = metadata?.files?.bloodPressure?.let(::sidecar)
                ?: conventionalBloodPressure.takeIf(Files::exists),
            ecg = metadata?.files?.ecg?.let(::sidecar)
                ?: conventionalEcg.takeIf(Files::exists),
        )
    }

    /** Report-based compatibility facade; an occupied directory is never scanned. */
    fun inspect(directory: Path, cancellationCheck: () -> Unit = {}): CaptureSessionInspection {
        cancellationCheck()
        return try {
            CaptureSessionInspectionService.inspect(directory, cancellationCheck)
        } catch (_: CaptureSessionBusyException) {
            CaptureSessionInspection(null, null, null, listOf(CaptureInspectionFinding(
                "session-in-use", CaptureInspectionSeverity.ERROR, "会话正在使用，未执行检查",
            )))
        }
    }

    private fun readMetadataOrNull(path: Path, cancellationCheck: () -> Unit): CaptureSessionMetadata? =
        try {
            if (Files.isRegularFile(path)) CaptureSessionMetadataCodec.decode(path, cancellationCheck) else null
        } catch (error: Exception) {
            if (error is java.util.concurrent.CancellationException) throw error
            null
        }

    private fun readEntry(directory: Path, cancellationCheck: () -> Unit): StoredCaptureSession {
        val files = expectedFiles(directory, cancellationCheck)
        val metadata = readMetadataOrNull(files.metadata, cancellationCheck)
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
