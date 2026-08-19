package com.example.ppgcollector_android.data.session

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant

object CaptureSessionMetadataEditor {
    fun updateReferenceBloodPressure(
        directory: Path,
        systolic: Int?,
        diastolic: Int?,
        updatedAt: Instant = Instant.now(),
    ): CaptureSessionMetadata {
        require((systolic == null) == (diastolic == null)) {
            "systolic and diastolic must be provided together"
        }
        require(systolic == null || systolic in 20..300) { "systolic must be 20..300" }
        require(diastolic == null || diastolic in 10..250) { "diastolic must be 10..250" }
        val files = CaptureSessionRepository.expectedFiles(directory)
        require(Files.isRegularFile(files.metadata)) { "session metadata is missing" }
        val current = CaptureSessionMetadataCodec.decode(files.metadata)
        val next = current.copy(
            systolicBp = systolic,
            diastolicBp = diastolic,
            bloodPressureUpdatedUtc = updatedAt,
        )
        val temporary = files.metadata.resolveSibling(".${files.metadata.fileName}.tmp")
        Files.write(
            temporary,
            CaptureSessionMetadataCodec.encodeBytes(next),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        try {
            try {
                Files.move(
                    temporary,
                    files.metadata,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, files.metadata, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        return next
    }
}
