package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.signal.LiveMetricAnalysisResult
import com.example.ppgcollector_android.core.signal.LiveMetricSnapshot
import com.example.ppgcollector_android.core.signal.MetricResult
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Locale
import kotlin.math.abs

/** One atomically published 1 Hz metric epoch. */
data class CaptureMetricEpoch(
    val sessionId: String,
    val connectionGeneration: Long,
    val metricEpoch: Long,
    val sourceSampleIndex: Long,
    val sourceTimeSeconds: Double,
    val measuredUtc: Instant,
    val snapshot: LiveMetricSnapshot,
)

/** Read-only, bounded subset used to align persisted 1 Hz metrics with replay. */
data class CaptureMetricTimelinePoint(
    val metricEpoch: Long,
    val sourceSampleIndex: Long,
    val sourceTimeSeconds: Double,
    val heartRateBpm: Double?,
    val signalQuality: Double?,
    val ratioOfRatios: Double?,
    val perfusionIndexPercent: Double?,
    val connectionGeneration: Long = 0,
)

object CaptureMetricEpochFactory {
    fun fromAnalysis(sessionId: String, result: LiveMetricAnalysisResult): CaptureMetricEpoch =
        CaptureMetricEpoch(
            sessionId = sessionId,
            connectionGeneration = result.request.generation,
            metricEpoch = result.request.metricEpoch,
            sourceSampleIndex = result.request.windowEndSampleIndex,
            sourceTimeSeconds = result.request.windowEndTimeSeconds,
            measuredUtc = result.request.measuredAt,
            snapshot = result.snapshot,
        )
}

data class CaptureSidecarScanReport(
    val totalBytes: Long,
    val validByteCount: Long,
    val completeDataRowCount: Long,
    val hasExpectedHeader: Boolean,
    val hasTruncatedFinalLine: Boolean,
    val monotonicityError: String? = null,
    val sessionId: String? = null,
) {
    val trailingByteCount: Long get() = maxOf(0L, totalBytes - validByteCount)
    val isStructurallyValid: Boolean get() = hasExpectedHeader && monotonicityError == null
}

object CaptureMetricSeries {
    const val schemaVersion = "ppgcollector_metrics_v1"
    private const val maximumTimelineRows = 100_000
    val columns = listOf(
        "schema_version", "session_id", "connection_generation", "metric_epoch",
        "source_sample_index", "source_time_s", "measured_utc",
        "heart_rate_bpm", "heart_rate_valid", "heart_rate_provisional", "heart_rate_reason",
        "heart_rate_alg_version", "sqi", "sqi_valid", "sqi_provisional", "sqi_reason",
        "sqi_alg_version", "ratio_of_ratios", "ratio_valid", "ratio_provisional", "ratio_reason",
        "ratio_alg_version", "perfusion_index_percent", "pi_valid", "pi_provisional", "pi_reason",
        "pi_alg_version",
    )
    val header: String get() = columns.joinToString(",") + "\n"

    fun format(epoch: CaptureMetricEpoch): String {
        val fields = ArrayList<String>(columns.size)
        fields += schemaVersion
        fields += epoch.sessionId
        fields += epoch.connectionGeneration.toString()
        fields += epoch.metricEpoch.toString()
        fields += epoch.sourceSampleIndex.toString()
        fields += formatDouble(epoch.sourceTimeSeconds)
        fields += epoch.measuredUtc.toString()
        appendMetric(fields, epoch.snapshot.heartRateBpm)
        appendMetric(fields, epoch.snapshot.signalQuality)
        appendMetric(fields, epoch.snapshot.ratioOfRatios)
        appendMetric(fields, epoch.snapshot.perfusionIndex)
        return fields.joinToString(",", transform = ::escape) + "\n"
    }

    fun scan(
        path: Path,
        acceptedSessionIds: Set<String> = emptySet(),
        cancellationCheck: () -> Unit = {},
    ): CaptureSidecarScanReport = scanSessionSidecar(
        path, header, acceptedSessionIds, cancellationCheck, rowValidator = ::validateRow,
    )

    fun readTimeline(
        path: Path,
        expectedSessionId: String? = null,
        acceptedSessionIds: Set<String> = emptySet(),
        cancellationCheck: () -> Unit = {},
    ): List<CaptureMetricTimelinePoint> {
        if (!Files.isRegularFile(path)) return emptyList()
        val result = ArrayList<CaptureMetricTimelinePoint>()
        val report = scanSessionSidecar(
            path, header, acceptedSessionIds + setOfNotNull(expectedSessionId), cancellationCheck,
            onValidRow = { fields ->
                require(result.size < maximumTimelineRows) {
                    "metrics timeline exceeds $maximumTimelineRows rows"
                }
                result += CaptureMetricTimelinePoint(
                    metricEpoch = fields[3].toLong(),
                    sourceSampleIndex = fields[4].toLong(),
                    sourceTimeSeconds = fields[5].toDouble(),
                    heartRateBpm = validMetric(fields, 7, 8),
                    signalQuality = validMetric(fields, 12, 13),
                    ratioOfRatios = validMetric(fields, 17, 18),
                    perfusionIndexPercent = validMetric(fields, 22, 23),
                    connectionGeneration = fields[2].toLong(),
                )
            },
            rowValidator = ::validateRow,
        )
        require(report.isStructurallyValid) { report.monotonicityError ?: "unexpected metrics header" }
        return result
    }

    private fun appendMetric(fields: MutableList<String>, metric: MetricResult<Double>) {
        fields += metric.value?.takeIf(Double::isFinite)?.let(::formatDouble) ?: ""
        fields += metric.isValid.toString()
        fields += metric.isProvisional.toString()
        fields += metric.unavailableReason?.wireValue ?: ""
        fields += metric.algorithmVersion
    }

    private fun validMetric(fields: List<String>, valueIndex: Int, validIndex: Int): Double? {
        if (!fields[validIndex].toBooleanStrict()) return null
        return fields[valueIndex].toDoubleOrNull()?.takeIf(Double::isFinite)
            ?: error("valid metric value is not finite")
    }

    private fun validateRow(fields: List<String>, previous: List<String>?): String? {
        if (fields.size != columns.size) return "expected ${columns.size} fields, got ${fields.size}"
        if (fields[0] != schemaVersion) return "unsupported schema_version: ${fields[0]}"
        if (fields[1].isBlank()) return "session_id is blank"
        val generation = fields[2].toLongOrNull() ?: return "invalid connection_generation"
        if (generation < 0L) return "negative connection_generation"
        val epoch = fields[3].toLongOrNull() ?: return "invalid metric_epoch"
        val source = fields[4].toLongOrNull() ?: return "invalid source_sample_index"
        val time = fields[5].toDoubleOrNull() ?: return "invalid source_time_s"
        if (epoch < 0L || source < 0L) return "metric cursor is negative"
        if (!time.isFinite()) return "non-finite source_time_s"
        if (time < 0.0 || abs(time - source.toDouble() / 100.0) > 0.000001) {
            return "source_time_s does not match source_sample_index"
        }
        try {
            Instant.parse(fields[6])
            for (offset in listOf(7, 12, 17, 22)) {
                val valid = fields[offset + 1].toBooleanStrict()
                fields[offset + 2].toBooleanStrict()
                if (fields[offset].isNotEmpty() && fields[offset].toDoubleOrNull()?.isFinite() != true) {
                    return "non-finite metric value"
                }
                if (valid && fields[offset].isEmpty()) return "valid metric has no value"
            }
        } catch (_: IllegalArgumentException) {
            return "invalid metric value or flag"
        } catch (_: java.time.format.DateTimeParseException) {
            return "invalid measured_utc"
        }
        if (previous != null) {
            if (fields[1] != previous[1]) return "session_id changed within sidecar"
            val previousEpoch = previous[3].toLongOrNull()
            val previousSource = previous[4].toLongOrNull()
            val previousGeneration = previous[2].toLong()
            if (generation < previousGeneration) return "connection_generation decreased"
            if (generation == previousGeneration && previousEpoch != null && epoch <= previousEpoch) {
                return "metric_epoch is not increasing within generation"
            }
            if (previousSource != null && source <= previousSource) return "source_sample_index is not increasing"
        }
        return null
    }
}

/** Bounded physical lines. Only newline-terminated rows enter a recoverable prefix. */
internal class SessionCsvLineReader(
    path: Path,
    private val cancellationCheck: () -> Unit = {},
) : java.io.Closeable {
    private val input = Files.newInputStream(path)
    private val buffer = ByteArray(64 * 1024)
    private var position = 0
    private var limit = 0
    private var eof = false
    var consumedBytes = 0L
        private set
    var truncated = false
        private set

    fun next(): String? {
        cancellationCheck()
        if (eof) return null
        val line = ByteArrayOutputStream()
        while (true) {
            if (position == limit) {
                cancellationCheck()
                limit = input.read(buffer)
                position = 0
                if (limit < 0) {
                    eof = true
                    truncated = line.size() > 0
                    return null
                }
            }
            val value = buffer[position++].toInt() and 0xff
            consumedBytes++
            if (value == 10) return line.toString(Charsets.UTF_8.name()).removeSuffix("\r")
            require(line.size() < 64 * 1024) { "CSV line exceeds 64 KiB" }
            line.write(value)
        }
    }

    override fun close() = input.close()
}

internal fun scanSessionSidecar(
    path: Path,
    expectedHeader: String,
    acceptedSessionIds: Set<String> = emptySet(),
    cancellationCheck: () -> Unit = {},
    onValidRow: (List<String>) -> Unit = {},
    rowValidator: (fields: List<String>, previous: List<String>?) -> String?,
): CaptureSidecarScanReport {
    val totalBytes = Files.size(path)
    var error: String? = null
    var rows = 0L
    var previous: List<String>? = null
    var sessionId: String? = null
    var hasExpectedHeader = false
    var validByteCount = 0L
    var truncated = false
    SessionCsvLineReader(path, cancellationCheck).use { reader ->
        try {
            hasExpectedHeader = reader.next() == expectedHeader.trimEnd('\n')
            if (!hasExpectedHeader) error = "unexpected sidecar header"
            else validByteCount = reader.consumedBytes
            while (error == null) {
                val line = reader.next() ?: break
                val fields = parseSessionCsvFields(line)
                error = rowValidator(fields, previous)
                if (error == null && acceptedSessionIds.isNotEmpty() && fields.getOrNull(1) !in acceptedSessionIds) {
                    error = "session_id does not match metadata"
                }
                if (error != null) break
                onValidRow(fields)
                // Commit count, identity, cursor and byte prefix together.
                rows++
                sessionId = fields.getOrNull(1)
                previous = fields
                validByteCount = reader.consumedBytes
            }
        } catch (invalid: IllegalArgumentException) {
            error = invalid.message ?: "invalid CSV row"
        }
        truncated = reader.truncated
    }
    return CaptureSidecarScanReport(totalBytes, validByteCount, rows, hasExpectedHeader, truncated, error, sessionId)
}

internal fun parseSessionCsvFields(line: String): List<String> {
    val fields = ArrayList<String>()
    val current = StringBuilder()
    var quoted = false
    var closedQuote = false
    var index = 0
    while (index < line.length) {
        val character = line[index]
        if (quoted) {
            if (character == '"') {
                if (index + 1 < line.length && line[index + 1] == '"') {
                    current.append('"')
                    index += 2
                    continue
                }
                quoted = false
                closedQuote = true
            } else {
                current.append(character)
            }
            index++
            continue
        }
        if (closedQuote) {
            if (character != ',') throw IllegalArgumentException("unexpected character after quote")
            fields += current.toString()
            current.clear()
            closedQuote = false
            index++
            continue
        }
        when (character) {
            ',' -> {
                fields += current.toString()
                current.clear()
            }
            '"' -> {
                if (current.isNotEmpty()) throw IllegalArgumentException("quote in unquoted field")
                quoted = true
            }
            else -> current.append(character)
        }
        index++
    }
    if (quoted) throw IllegalArgumentException("unterminated quote")
    fields += current.toString()
    return fields
}

internal fun escapeSessionCsvField(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"${value.replace("\"", "\"\"")}\""
    } else {
        value
    }

private fun escape(value: String): String = escapeSessionCsvField(value)

private fun formatDouble(value: Double): String = String.format(Locale.ROOT, "%.9f", value)
