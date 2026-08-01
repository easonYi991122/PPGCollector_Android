package com.example.ppgcollector_android.data.session

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Streams the same bounded zip exporter into a user-selected SAF document. */
class CaptureSafExportService(
    private val contentResolver: ContentResolver,
) {
    fun export(
        session: StoredCaptureSession,
        destination: Uri,
        onProgress: (CaptureExportProgress) -> Unit = {},
        cancellation: CaptureExportCancellation = CaptureExportCancellation {},
    ): CaptureExportReport {
        val output = contentResolver.openOutputStream(destination, "w")
            ?: throw CaptureSessionExportException.CannotExport("cannot open SAF destination")
        return output.use {
            CaptureSessionExportService.exportZip(session, it, onProgress, cancellation)
        }
    }
}

data class CaptureShareExport(
    val uri: Uri,
    val stagingPath: Path,
    val report: CaptureExportReport,
)

/** Creates a cache-only FileProvider share; internal session paths are never shared. */
class CaptureFileProviderExportService(
    private val context: Context,
) {
    fun createShare(
        session: StoredCaptureSession,
        onProgress: (CaptureExportProgress) -> Unit = {},
        cancellation: CaptureExportCancellation = CaptureExportCancellation {},
    ): CaptureShareExport {
        val stagingRoot = context.cacheDir.toPath().resolve("capture-export-staging")
        Files.createDirectories(stagingRoot)
        val safeBase = session.baseName.replace(Regex("[^A-Za-z0-9_-]"), "_")
            .ifEmpty { "session" }
        val path = stagingRoot.resolve("$safeBase-${UUID.randomUUID()}.zip")
        val report = CaptureSessionExportService.exportZip(session, path, onProgress, cancellation)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            path.toFile(),
        )
        return CaptureShareExport(uri, path, report)
    }

    fun cleanup(export: CaptureShareExport) {
        Files.deleteIfExists(export.stagingPath)
    }
}
