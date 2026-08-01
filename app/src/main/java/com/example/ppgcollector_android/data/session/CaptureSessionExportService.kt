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

/** Streams a session's three files to one zip without exposing internal paths. */
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
        val files = CaptureSessionRepository.expectedFiles(session.directory)
        val sources = listOf(
            files.raw to "${session.baseName}.cupraw",
            files.csv to "${session.baseName}.csv",
            files.metadata to "${session.baseName}.session.json",
        )
        val sizes = sources.map { (path, name) ->
            if (!Files.isRegularFile(path)) throw CaptureSessionExportException.SourceFileMissing(name)
            path to Files.size(path)
        }
        val total = sizes.sumOf { it.second }
        var copied = 0L
        val entryNames = ArrayList<String>(sources.size)
        try {
            ZipOutputStream(output).use { zip ->
                sizes.forEach { (path, size) ->
                    cancellation.checkOrThrow()
                    val name = sources.first { it.first == path }.second
                    entryNames += name
                    zip.putNextEntry(ZipEntry(name))
                    Files.newInputStream(path, StandardOpenOption.READ).use { input ->
                        val buffer = ByteArray(copyBufferBytes)
                        while (true) {
                            cancellation.checkOrThrow()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            zip.write(buffer, 0, read)
                            copied += read
                            onProgress(CaptureExportProgress(copied, total, name))
                        }
                    }
                    zip.closeEntry()
                    check(copied >= 0 && size >= 0) { "invalid source size" }
                }
                zip.finish()
            }
            return CaptureExportReport(copied, total, entryNames.toList())
        } catch (error: CaptureSessionExportException) {
            throw error
        } catch (error: Exception) {
            throw CaptureSessionExportException.CannotExport("cannot stream export zip", error)
        }
    }

    private fun CaptureExportCancellation.checkOrThrow() {
        try {
            check()
        } catch (_: CaptureSessionExportException.Cancelled) {
            throw CaptureSessionExportException.Cancelled
        } catch (error: CancellationException) {
            throw CaptureSessionExportException.Cancelled
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

private typealias CancellationException = java.util.concurrent.CancellationException
