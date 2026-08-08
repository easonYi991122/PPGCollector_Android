package com.example.ppgcollector_android.data.session

import java.nio.file.Files
import java.nio.file.Path

/** Version-aware file set; v1 has only raw/samples/metadata. */
data class CaptureSessionFileManifest(
    val raw: Path,
    val samples: Path,
    val metadata: Path,
    val metrics: Path? = null,
    val bloodPressure: Path? = null,
) {
    val required: List<Pair<Path, String>>
        get() = listOf(
            raw to raw.fileName.toString(),
            samples to samples.fileName.toString(),
            metadata to metadata.fileName.toString(),
        )

    val optional: List<Pair<Path, String>>
        get() = listOfNotNull(
            metrics?.let { it to it.fileName.toString() },
            bloodPressure?.let { it to it.fileName.toString() },
        )

    val all: List<Pair<Path, String>> get() = required + optional

    fun hasAllFiles(): Boolean = required.all { Files.isRegularFile(it.first) } &&
        optional.all { Files.isRegularFile(it.first) }
}

