package com.example.ppgcollector_android.data.session

import java.nio.file.Path
import java.time.Instant
import java.util.Locale

/** Frozen at dialog open; the saved time is audit-only and never replaces it. */
data class CaptureReferenceTimestamp(
    val sessionId: String,
    val connectionGeneration: Long,
    val eventIndex: Long,
    val sourceSampleIndex: Long,
    val sourceTimeSeconds: Double,
    val dialogOpenHostMonotonicNanoseconds: ULong,
    val dialogOpenUtc: Instant,
)

data class ManualBloodPressureEvent(
    val reference: CaptureReferenceTimestamp,
    val savedUtc: Instant,
    val systolicMmHg: Int,
    val diastolicMmHg: Int,
)

object CaptureBloodPressureSeries {
    const val schemaVersion = "ppgcollector_manual_bp_v1"
    val columns = listOf(
        "schema_version", "session_id", "event_index",
        "dialog_open_source_sample_index", "dialog_open_source_time_s",
        "dialog_open_host_monotonic_ns", "dialog_open_utc", "saved_utc",
        "systolic_mm_hg", "diastolic_mm_hg",
    )
    val header: String get() = columns.joinToString(",") + "\n"

    fun format(event: ManualBloodPressureEvent): String = listOf(
        schemaVersion,
        event.reference.sessionId,
        event.reference.eventIndex.toString(),
        event.reference.sourceSampleIndex.toString(),
        String.format(Locale.ROOT, "%.9f", event.reference.sourceTimeSeconds),
        event.reference.dialogOpenHostMonotonicNanoseconds.toString(),
        event.reference.dialogOpenUtc.toString(),
        event.savedUtc.toString(),
        event.systolicMmHg.toString(),
        event.diastolicMmHg.toString(),
    ).joinToString(",", transform = ::escapeSessionCsvField) + "\n"

    fun scan(path: Path): CaptureSidecarScanReport =
        scanSessionSidecar(path, header) { fields, previous ->
            if (fields.size != columns.size) return@scanSessionSidecar "expected ${columns.size} fields"
            if (fields[0] != schemaVersion) return@scanSessionSidecar "unsupported schema_version"
            if (fields[1].isBlank()) return@scanSessionSidecar "session_id is blank"
            val event = fields[2].toLongOrNull() ?: return@scanSessionSidecar "invalid event_index"
            fields[3].toLongOrNull() ?: return@scanSessionSidecar "invalid source_sample_index"
            fields[4].toDoubleOrNull()?.takeIf(Double::isFinite)
                ?: return@scanSessionSidecar "invalid source_time_s"
            fields[5].toULongOrNull() ?: return@scanSessionSidecar "invalid dialog_open_host_monotonic_ns"
            runCatching { Instant.parse(fields[6]) }.getOrElse {
                return@scanSessionSidecar "invalid dialog_open_utc"
            }
            runCatching { Instant.parse(fields[7]) }.getOrElse {
                return@scanSessionSidecar "invalid saved_utc"
            }
            val systolic = fields[8].toIntOrNull() ?: return@scanSessionSidecar "invalid systolic"
            val diastolic = fields[9].toIntOrNull() ?: return@scanSessionSidecar "invalid diastolic"
            if (systolic <= 0 || diastolic <= 0) return@scanSessionSidecar "blood pressure must be positive"
            if (previous != null && event <= (previous[2].toLongOrNull() ?: -1L)) {
                return@scanSessionSidecar "event_index is not increasing"
            }
            if (previous != null && fields[1] != previous[1]) {
                return@scanSessionSidecar "session_id changed within sidecar"
            }
            if (previous != null && fields[3].toLongOrNull()!! < previous[3].toLongOrNull()!!) {
                return@scanSessionSidecar "source_sample_index is not monotonic"
            }
            null
        }
}
