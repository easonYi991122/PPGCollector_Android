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

    fun read(
        path: Path,
        acceptedSessionIds: Set<String> = emptySet(),
        cancellationCheck: () -> Unit = {},
    ): List<ManualBloodPressureEvent> {
        if (!java.nio.file.Files.isRegularFile(path)) return emptyList()
        val result = ArrayList<ManualBloodPressureEvent>()
        val report = scanSessionSidecar(path, header, acceptedSessionIds, cancellationCheck,
            onValidRow = { fields ->
                require(result.size < 10_000) { "blood pressure event count exceeds limit" }
                result += ManualBloodPressureEvent(
                    CaptureReferenceTimestamp(fields[1], 0, fields[2].toLong(), fields[3].toLong(),
                        fields[4].toDouble(), fields[5].toULong(), Instant.parse(fields[6])),
                    Instant.parse(fields[7]), fields[8].toInt(), fields[9].toInt(),
                )
            }, rowValidator = ::validateRow)
        require(report.isStructurallyValid) { report.monotonicityError ?: "unexpected blood-pressure header" }
        return result
    }

    fun scan(
        path: Path,
        acceptedSessionIds: Set<String> = emptySet(),
        cancellationCheck: () -> Unit = {},
    ): CaptureSidecarScanReport =
        scanSessionSidecar(path, header, acceptedSessionIds, cancellationCheck, rowValidator = ::validateRow)

    private fun validateRow(fields: List<String>, previous: List<String>?): String? {
        if (fields.size != columns.size) return "expected ${columns.size} fields"
        if (fields[0] != schemaVersion) return "unsupported schema_version"
        if (fields[1].isBlank()) return "session_id is blank"
        val event = fields[2].toLongOrNull() ?: return "invalid event_index"
        val cursor = fields[3].toLongOrNull() ?: return "invalid source_sample_index"
        val time = fields[4].toDoubleOrNull()?.takeIf(Double::isFinite) ?: return "invalid source_time_s"
        if (event < 0 || cursor < 0 || kotlin.math.abs(time - cursor / 100.0) > 0.000001) return "invalid BP cursor/time"
        fields[5].toULongOrNull() ?: return "invalid dialog_open_host_monotonic_ns"
        try { Instant.parse(fields[6]); Instant.parse(fields[7]) }
        catch (_: java.time.format.DateTimeParseException) { return "invalid blood-pressure UTC" }
        val systolic = fields[8].toIntOrNull() ?: return "invalid systolic"
        val diastolic = fields[9].toIntOrNull() ?: return "invalid diastolic"
        if (systolic <= 0 || diastolic <= 0) return "blood pressure must be positive"
        if (systolic <= diastolic) return "systolic must be greater than diastolic"
        if (previous != null && event <= (previous[2].toLongOrNull() ?: -1L)) {
            return "event_index is not increasing"
        }
        if (previous != null && fields[1] != previous[1]) {
            return "session_id changed within sidecar"
        }
        if (previous != null && fields[3].toLongOrNull()!! < previous[3].toLongOrNull()!!) {
            return "source_sample_index is not monotonic"
        }
        return null
    }
}
