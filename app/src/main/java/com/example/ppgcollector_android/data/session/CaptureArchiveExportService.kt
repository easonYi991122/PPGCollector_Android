package com.example.ppgcollector_android.data.session

import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class CaptureArchiveExportReport(
    val bytesCopied: Long,
    val totalBytes: Long,
    val entryNames: List<String>,
    val missingEntries: List<String>,
    val manifestEntryName: String = "export_manifest.json",
)

/** Subject-aware, read-only, streaming exporter. */
object CaptureArchiveExportService {
    private const val copyBufferBytes = 64 * 1024
    private const val schemaVersion = "ppgcollector_archive_export_v1"

    fun export(
        sessionsRoot: Path,
        subjectsRoot: Path,
        selection: CaptureArchiveSelection,
        destination: Path,
        onProgress: (CaptureExportProgress) -> Unit = {},
        cancellation: CaptureExportCancellation = CaptureExportCancellation {},
    ): CaptureArchiveExportReport {
        if (Files.exists(destination)) {
            throw CaptureSessionExportException.DestinationAlreadyExists(destination)
        }
        val parent = destination.parent
            ?: throw CaptureSessionExportException.CannotExport("destination has no parent")
        Files.createDirectories(parent)
        val temporary = destination.resolveSibling(".${destination.fileName}.tmp")
        var committed = false
        try {
            Files.deleteIfExists(temporary)
            val report = Files.newOutputStream(
                temporary,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { output -> export(sessionsRoot, subjectsRoot, selection, output, onProgress, cancellation) }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, destination)
            }
            committed = true
            return report
        } catch (error: CaptureSessionExportException) {
            throw error
        } catch (error: Exception) {
            throw CaptureSessionExportException.CannotExport("cannot create archive export", error)
        } finally {
            if (!committed) Files.deleteIfExists(temporary)
        }
    }

    fun export(
        sessionsRoot: Path,
        subjectsRoot: Path,
        selection: CaptureArchiveSelection,
        output: OutputStream,
        onProgress: (CaptureExportProgress) -> Unit = {},
        cancellation: CaptureExportCancellation = CaptureExportCancellation {},
        now: Instant = Instant.now(),
    ): CaptureArchiveExportReport {
        val archive = SubjectArchiveRepository.rebuild(sessionsRoot, subjectsRoot, now)
        val selected = SubjectArchiveRepository.selectedSessions(archive, selection)
        if (selected.isEmpty()) throw CaptureSessionExportException.CannotExport("没有可导出的会话")
        val subjectsByDirectory = archive.groups
            .flatMap { group -> group.sessions.map { it.session.directory to group.summary.subject } }
            .toMap()
        val usedNames = HashSet<String>()
        val sources = ArrayList<SourceEntry>()
        val missing = ArrayList<String>()
        selected.forEach { session ->
            val subject = subjectsByDirectory[session.directory]
            val files = CaptureSessionRepository.expectedFiles(session.directory)
            files.allNamedPaths.forEach { (path, fileName) ->
                val prefix = if (subject == null) {
                    "unclassified/${safeComponent(session.baseName)}"
                } else {
                    "subjects/${safeComponent(subject)}/${safeComponent(session.baseName)}"
                }
                val requested = "$prefix/$fileName"
                if (!Files.isRegularFile(path)) {
                    if (path in files.requiredPaths) missing += "$requested (required)"
                    else missing += "$requested (optional)"
                } else {
                    sources += source(path, uniqueName(requested, usedNames), cancellation)
                }
            }
        }
        val selectedSubjects = selected.mapNotNull { subjectsByDirectory[it.directory] }.toSet() + selection.subjectIds
        val profileStore = SubjectProfileStore(subjectsRoot)
        selectedSubjects.sorted().forEach { subject ->
            val profilePath = runCatching { profileStore.pathFor(subject) }.getOrNull()
            if (profilePath == null || !Files.isRegularFile(profilePath)) {
                missing += "subject_profiles/${safeComponent(subject)}.profile.json (optional)"
            } else {
                sources += source(
                    profilePath,
                    uniqueName("subject_profiles/${safeComponent(subject)}.profile.json", usedNames),
                    cancellation,
                )
            }
        }
        val manifest = manifestJson(now, selection, selected, sources, missing)
        var copied = 0L
        val names = ArrayList<String>(sources.size + 1)
        try {
            ZipOutputStream(output).use { zip ->
                cancellation.checkOrThrow()
                zip.putNextEntry(ZipEntry("export_manifest.json"))
                zip.write(manifest.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                sources.forEach { entry ->
                    cancellation.checkOrThrow()
                    names += entry.entryName
                    zip.putNextEntry(ZipEntry(entry.entryName))
                    Files.newInputStream(entry.path, StandardOpenOption.READ).use { input ->
                        val buffer = ByteArray(copyBufferBytes)
                        while (true) {
                            cancellation.checkOrThrow()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            zip.write(buffer, 0, read)
                            copied += read
                            onProgress(CaptureExportProgress(copied, sources.sumOf { it.size }, entry.entryName))
                        }
                    }
                    zip.closeEntry()
                }
                zip.finish()
            }
        } catch (error: CaptureSessionExportException) {
            throw error
        } catch (error: Exception) {
            throw CaptureSessionExportException.CannotExport("cannot stream archive export", error)
        }
        return CaptureArchiveExportReport(copied, sources.sumOf { it.size }, names, missing)
    }

    private data class SourceEntry(val path: Path, val entryName: String, val size: Long, val sha256: String)

    private fun source(
        path: Path,
        entryName: String,
        cancellation: CaptureExportCancellation,
    ): SourceEntry {
        cancellation.checkOrThrow()
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        Files.newInputStream(path, StandardOpenOption.READ).use { input ->
            val buffer = ByteArray(copyBufferBytes)
            while (true) {
                cancellation.checkOrThrow()
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
                size += read
            }
        }
        return SourceEntry(path, entryName, size, digest.digest().toHex())
    }

    private fun manifestJson(
        now: Instant,
        selection: CaptureArchiveSelection,
        selected: List<StoredCaptureSession>,
        sources: List<SourceEntry>,
        missing: List<String>,
    ): String {
        fun q(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        val entries = sources.joinToString(",", prefix = "[", postfix = "]") {
            "{\"entry\":${q(it.entryName)},\"size\":${it.size},\"sha256\":${q(it.sha256)}}"
        }
        val missingJson = missing.joinToString(",", prefix = "[", postfix = "]", transform = ::q)
        val sessions = selected.joinToString(",", prefix = "[", postfix = "]") { session ->
            "{\"session_id\":${q(session.metadata?.sessionId.orEmpty())}," +
                "\"base_name\":${q(session.baseName)}," +
                "\"subject\":${q(SessionNamePolicy.parseCanonical(session.baseName)?.subject.orEmpty())}}"
        }
        val subjects = selection.subjectIds.sorted().joinToString(",", prefix = "[", postfix = "]", transform = ::q)
        return "{" +
            "\"schema_version\":${q(schemaVersion)}," +
            "\"exported_utc\":${q(now.toString())}," +
            "\"selection_subjects\":$subjects," +
            "\"sessions\":$sessions," +
            "\"entries\":$entries," +
            "\"missing\":$missingJson" +
            "}"
    }

    private fun uniqueName(requested: String, used: MutableSet<String>): String {
        if (used.add(requested)) return requested
        val slash = requested.lastIndexOf('/')
        val directory = requested.substring(0, slash + 1)
        val file = requested.substring(slash + 1)
        val dot = file.lastIndexOf('.')
        val stem = if (dot > 0) file.substring(0, dot) else file
        val suffix = if (dot > 0) file.substring(dot) else ""
        var index = 2
        while (!used.add("$directory$stem-$index$suffix")) index++
        return "$directory$stem-${index - 0}$suffix".also {
            // The value was inserted by the loop; return the exact candidate.
        }
    }

    private fun safeComponent(value: String): String =
        value.replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "unknown" }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun CaptureExportCancellation.checkOrThrow() {
        try {
            check()
        } catch (_: CaptureSessionExportException.Cancelled) {
            throw CaptureSessionExportException.Cancelled
        } catch (_: java.util.concurrent.CancellationException) {
            throw CaptureSessionExportException.Cancelled
        }
    }
}
