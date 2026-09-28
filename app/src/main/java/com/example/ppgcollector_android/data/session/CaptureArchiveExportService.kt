package com.example.ppgcollector_android.data.session

import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
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
            if (error is java.util.concurrent.CancellationException || error is CaptureSessionBusyException) throw error
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
        cancellation.check()
        var selected = CaptureSessionRepository.listSessions(sessionsRoot, cancellation::check).filter { session ->
            session.directory in selection.sessionDirectories ||
                SubjectArchiveRepository.identityFor(session)?.subject in selection.subjectIds
        }
        if (selected.isEmpty()) throw CaptureSessionExportException.CannotExport("没有可导出的会话")
        val leases = ArrayList<CaptureSessionAccessRegistry.Lease>()
        try {
            selected.sortedBy { it.directory.toString() }.forEach {
                leases += requireSessionLease(it.directory, CaptureSessionAccessRegistry.Access.READ_SNAPSHOT)
            }
            // Refresh metadata only after every selected directory is held.
            val current = CaptureSessionRepository.listSessions(sessionsRoot, cancellation::check)
                .associateBy { it.directory }
            selected = selected.map { current.getValue(it.directory) }
            val subjectsByDirectory = selected.mapNotNull { session ->
                SubjectArchiveRepository.identityFor(session)?.let {
                    session.directory to (it.subject to it.prefix.wireValue)
                }
            }.toMap()
            val usedNames = HashSet<String>()
            val sources = ArrayList<CaptureExportSource>()
            val missing = ArrayList<String>()
            selected.forEach { session ->
                val subject = subjectsByDirectory[session.directory]
                val files = CaptureSessionRepository.expectedFiles(session.directory, cancellation::check)
                files.allNamedPaths.forEach { (path, fileName) ->
                    val prefix = if (subject == null) {
                        "unclassified/${safeComponent(session.baseName)}"
                    } else {
                        "subjects/${safeComponent(subject.first)}/${subject.second}/" +
                            safeComponent(session.baseName)
                    }
                    val requested = "$prefix/$fileName"
                    if (!Files.isRegularFile(path)) {
                        if (path in files.requiredPaths) missing += "$requested (required)"
                        else missing += "$requested (optional)"
                    } else {
                        sources += CaptureExportSource.freeze(path, uniqueName(requested, usedNames), cancellation::check)
                    }
                }
            }
            val selectedSubjects = selected.mapNotNull { subjectsByDirectory[it.directory]?.first }.toSet() +
                selection.subjectIds
            val profileStore = SubjectProfileStore(subjectsRoot)
            selectedSubjects.sorted().forEach { subject ->
                val profilePath = runCatching { profileStore.pathFor(subject) }.getOrNull()
                if (profilePath == null || !Files.isRegularFile(profilePath)) {
                    missing += "subject_profiles/${safeComponent(subject)}.profile.json (optional)"
                } else {
                    sources += CaptureExportSource.freeze(
                        profilePath,
                        uniqueName("subject_profiles/${safeComponent(subject)}.profile.json", usedNames),
                        cancellation::check,
                    )
                }
            }
            var copied = 0L
            val total = sources.sumOf { it.version.size }
            ZipOutputStream(output).use { zip ->
                sources.forEach { entry ->
                    entry.copyTo(zip, cancellation::check) { count ->
                        copied += count
                        onProgress(CaptureExportProgress(copied, total, entry.entryName))
                    }
                }
                sources.forEach { it.verify(cancellation::check) }
                cancellation.check()
                zip.putNextEntry(ZipEntry("export_manifest.json").apply { time = 0L })
                zip.write(manifestJson(now, selection, selected, sources, missing).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.finish()
            }
            check(copied == total) { "archive size changed" }
            return CaptureArchiveExportReport(copied, total, sources.map { it.entryName }, missing)
        } catch (error: Exception) {
            if (error is java.util.concurrent.CancellationException || error is CaptureSessionExportException ||
                error is CaptureSessionBusyException) throw error
            throw CaptureSessionExportException.CannotExport("cannot stream archive export", error)
        } finally {
            leases.asReversed().forEach { it.close() }
        }
    }

    private fun manifestJson(
        now: Instant,
        selection: CaptureArchiveSelection,
        selected: List<StoredCaptureSession>,
        sources: List<CaptureExportSource>,
        missing: List<String>,
    ): String {
        fun q(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        val entries = sources.joinToString(",", prefix = "[", postfix = "]") {
            "{\"entry\":${q(it.entryName)},\"size\":${it.version.size},\"sha256\":${q(it.sha256)}}"
        }
        val missingJson = missing.joinToString(",", prefix = "[", postfix = "]", transform = ::q)
        val sessions = selected.joinToString(",", prefix = "[", postfix = "]") { session ->
            "{\"session_id\":${q(session.metadata?.sessionId.orEmpty())}," +
                "\"base_name\":${q(session.baseName)}," +
                "\"subject\":${q(SubjectArchiveRepository.identityFor(session)?.subject.orEmpty())}}"
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

}
