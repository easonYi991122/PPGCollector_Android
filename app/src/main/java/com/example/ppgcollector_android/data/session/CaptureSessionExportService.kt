package com.example.ppgcollector_android.data.session

import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class CaptureExportProgress(
    val bytesCopied: Long,
    val totalBytes: Long,
    val entryName: String,
)

data class CaptureExportReport(
    val bytesCopied: Long,
    val totalBytes: Long,
    val entryNames: List<String>,
)

fun interface CaptureExportCancellation {
    fun check()
}

sealed class CaptureSessionExportException(message: String) : Exception(message) {
    data object Cancelled : CaptureSessionExportException("export cancelled")
    class DestinationAlreadyExists(path: Path) :
        CaptureSessionExportException("export destination already exists: $path")
    class SourceFileMissing(name: String) :
        CaptureSessionExportException("source file is missing: $name")
    class CannotExport(message: String, cause: Throwable? = null) :
        CaptureSessionExportException(message) {
        init {
            if (cause != null) initCause(cause)
        }
    }
}

/** Streams the manifest-declared session files to one zip without exposing internal paths. */
object CaptureSessionExportService {
    private const val copyBufferBytes = 64 * 1024

    fun exportZip(
        session: StoredCaptureSession,
        destination: Path,
        onProgress: (CaptureExportProgress) -> Unit = {},
        cancellation: CaptureExportCancellation = CaptureExportCancellation {},
    ): CaptureExportReport {
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
            ).use { output ->
                exportZip(session, output, onProgress, cancellation)
            }
            moveNoOverwrite(temporary, destination)
            committed = true
            return report
        } catch (error: CaptureSessionExportException) {
            throw error
        } catch (error: Exception) {
            if (error is java.util.concurrent.CancellationException || error is CaptureSessionBusyException) throw error
            throw CaptureSessionExportException.CannotExport("cannot create export zip", error)
        } finally {
            if (!committed) Files.deleteIfExists(temporary)
        }
    }

    /** Adapter seam for SAF: caller owns the Uri OutputStream and its lifecycle. */
    fun exportZip(
        session: StoredCaptureSession,
        output: OutputStream,
        onProgress: (CaptureExportProgress) -> Unit = {},
        cancellation: CaptureExportCancellation = CaptureExportCancellation {},
    ): CaptureExportReport {
        return requireSessionLease(session.directory, CaptureSessionAccessRegistry.Access.READ_SNAPSHOT).use {
            cancellation.check()
            val files = CaptureSessionRepository.expectedFiles(session.directory, cancellation::check)
            val sources = files.allNamedPaths.map { (path, name) ->
                if (!Files.isRegularFile(path)) throw CaptureSessionExportException.SourceFileMissing(name)
                CaptureExportSource.freeze(path, name, cancellation::check)
            }
            val total = sources.sumOf { it.version.size }
            var copied = 0L
            try {
                ZipOutputStream(output).use { zip ->
                    sources.forEach { source ->
                        source.copyTo(zip, cancellation::check) { count ->
                            copied += count
                            onProgress(CaptureExportProgress(copied, total, source.entryName))
                        }
                    }
                    sources.forEach { it.verify(cancellation::check) }
                    cancellation.check()
                    zip.putNextEntry(ZipEntry("export_manifest.json"))
                    zip.write(CaptureExportSource.manifest(sources).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    zip.finish()
                }
                check(copied == total) { "export size changed" }
                CaptureExportReport(copied, total, sources.map { it.entryName })
            } catch (error: Exception) {
                if (error is java.util.concurrent.CancellationException || error is CaptureSessionExportException) throw error
                throw CaptureSessionExportException.CannotExport("cannot stream export zip", error)
            }
        }
    }

    private fun moveNoOverwrite(source: Path, destination: Path) {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source, destination)
        } catch (error: java.nio.file.FileAlreadyExistsException) {
            throw CaptureSessionExportException.DestinationAlreadyExists(destination)
        }
    }
}

/** File identity and EOF fixed before copy; hashes cover the exact bytes written to ZIP. */
internal data class CaptureFileVersion(
    val size: Long,
    val key: String?,
    val modified: String,
    val created: String,
) {
    companion object {
        fun read(path: Path): CaptureFileVersion {
            val attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java,
                java.nio.file.LinkOption.NOFOLLOW_LINKS)
            require(attributes.isRegularFile) { "source is not a regular file" }
            return CaptureFileVersion(attributes.size(), attributes.fileKey()?.toString(),
                attributes.lastModifiedTime().toString(), attributes.creationTime().toString())
        }
    }
}

internal data class CaptureExportSource(
    val path: Path,
    val entryName: String,
    val version: CaptureFileVersion,
    val sha256: String,
) {
    fun verify(cancellationCheck: () -> Unit) {
        check(CaptureFileVersion.read(path) == version && hash(path, cancellationCheck, version.size) == sha256 &&
            CaptureFileVersion.read(path) == version) { "export source changed: $entryName" }
    }

    fun copyTo(zip: ZipOutputStream, cancellationCheck: () -> Unit, copied: (Long) -> Unit) {
        check(CaptureFileVersion.read(path) == version) { "export source changed: $entryName" }
        zip.putNextEntry(ZipEntry(entryName).apply { time = 0L })
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            var remaining = version.size
            while (remaining > 0) {
                cancellationCheck()
                val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                check(count > 0) { "export source shortened: $entryName" }
                zip.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                remaining -= count
                copied(count.toLong())
            }
            cancellationCheck()
            check(input.read() == -1) { "export source appended: $entryName" }
        }
        zip.closeEntry()
        check(digest.digest().toHex() == sha256 && CaptureFileVersion.read(path) == version) {
            "export source changed during copy: $entryName"
        }
    }

    companion object {
        fun freeze(path: Path, name: String, cancellationCheck: () -> Unit): CaptureExportSource {
            cancellationCheck()
            val version = CaptureFileVersion.read(path)
            val digest = hash(path, cancellationCheck, version.size)
            check(CaptureFileVersion.read(path) == version) { "export source changed during snapshot: $name" }
            return CaptureExportSource(path, name, version, digest)
        }

        fun hash(
            path: Path,
            cancellationCheck: () -> Unit = {},
            expectedBytes: Long = Files.size(path),
        ): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(64 * 1024)
                var remaining = expectedBytes
                while (remaining > 0) {
                    cancellationCheck()
                    val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                    check(count > 0) { "source shortened while hashing" }
                    digest.update(buffer, 0, count)
                    remaining -= count
                }
                cancellationCheck()
                check(input.read() == -1) { "source appended while hashing" }
            }
            return digest.digest().toHex()
        }

        fun manifest(sources: List<CaptureExportSource>): String = JsonWriter.write(JsonValue.ObjectValue(mapOf(
            "schema_version" to JsonValue.StringValue("ppgcollector_session_export_v1"),
            "entries" to JsonValue.ArrayValue(sources.map { source -> JsonValue.ObjectValue(mapOf(
                "entry" to JsonValue.StringValue(source.entryName),
                "size" to JsonValue.NumberValue(source.version.size.toString()),
                "sha256" to JsonValue.StringValue(source.sha256),
            )) }),
        )))

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
