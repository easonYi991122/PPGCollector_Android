package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.signal.LiveMetricAnalysisResult
import com.example.ppgcollector_android.core.signal.LiveMetricSnapshot
import com.example.ppgcollector_android.core.signal.MetricResult
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.Locale

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

    fun scan(path: Path): CaptureSidecarScanReport =
        scanSessionSidecar(path, header, ::validateRow)

    fun readTimeline(path: Path): List<CaptureMetricTimelinePoint> {
        if (!Files.isRegularFile(path)) return emptyList()
        Files.newBufferedReader(path).use { reader ->
            require(reader.readLine()?.removeSuffix("\r") == header.trimEnd('\n')) {
                "unexpected metrics header"
            }
            val result = ArrayList<CaptureMetricTimelinePoint>()
            var previousEpoch = -1L
            var previousSource = -1L
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                require(result.size < maximumTimelineRows) {
                    "metrics timeline exceeds $maximumTimelineRows rows"
                }
                val fields = parseSessionCsvFields(line.removeSuffix("\r"))
                require(fields.size == columns.size) { "expected ${columns.size} metrics fields" }
                require(fields[0] == schemaVersion) { "unsupported metrics schema" }
                val epoch = fields[3].toLong()
                val source = fields[4].toLong()
                val sourceTime = fields[5].toDouble()
                require(epoch > previousEpoch && source > previousSource && sourceTime.isFinite()) {
                    "metrics timeline is not monotonic"
                }
                result += CaptureMetricTimelinePoint(
                    metricEpoch = epoch,
                    sourceSampleIndex = source,
                    sourceTimeSeconds = sourceTime,
                    heartRateBpm = validMetric(fields, 7, 8),
                    signalQuality = validMetric(fields, 12, 13),
                    ratioOfRatios = validMetric(fields, 17, 18),
                    perfusionIndexPercent = validMetric(fields, 22, 23),
                )
                previousEpoch = epoch
                previousSource = source
            }
            return result
        }
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
    }

    private fun validateRow(fields: List<String>, previous: List<String>?): String? {
        if (fields.size != columns.size) return "expected ${columns.size} fields, got ${fields.size}"
        if (fields[0] != schemaVersion) return "unsupported schema_version: ${fields[0]}"
        if (fields[1].isBlank()) return "session_id is blank"
        val epoch = fields[3].toLongOrNull() ?: return "invalid metric_epoch"
        val source = fields[4].toLongOrNull() ?: return "invalid source_sample_index"
        val time = fields[5].toDoubleOrNull() ?: return "invalid source_time_s"
        if (!time.isFinite()) return "non-finite source_time_s"
        if (previous != null) {
            if (fields[1] != previous[1]) return "session_id changed within sidecar"
            val previousEpoch = previous[3].toLongOrNull()
            val previousSource = previous[4].toLongOrNull()
            if (previousEpoch != null && epoch <= previousEpoch) return "metric_epoch is not increasing"
            if (previousSource != null && source <= previousSource) return "source_sample_index is not increasing"
        }
        return null
    }
}

internal fun scanSessionSidecar(
    path: Path,
    expectedHeader: String,
    rowValidator: (fields: List<String>, previous: List<String>?) -> String?,
): CaptureSidecarScanReport {
    val totalBytes = Files.size(path)
    val expectedHeaderLine = expectedHeader.trimEnd('\n')
    var error: String? = null
    var rows = 0L
    var previous: List<String>? = null
    var sessionId: String? = null
    var headerLine = ""
    var hasExpectedHeader = false
    var validByteCount = 0L
    var consumedBytes = 0L
    var lineIndex = 0
    var hasTrailingNewline = false
    val lineBuffer = ByteArrayOutputStream()

    fun consumeLine(terminated: Boolean) {
        val line = lineBuffer.toByteArray().toString(Charsets.UTF_8).removeSuffix("\r")
        val lineEnd = consumedBytes
        if (lineIndex == 0) {
            headerLine = line
            hasExpectedHeader = line == expectedHeaderLine
            if (!hasExpectedHeader || !terminated) error = "unexpected sidecar header"
            if (error == null && terminated) validByteCount = lineEnd
        } else if (line.isNotEmpty() && error == null) {
            val fields = runCatching { parseSessionCsvFields(line) }.getOrElse {
                error = it.message ?: "invalid CSV row"
                emptyList()
            }
            if (error == null) error = rowValidator(fields, previous)
            if (error == null) {
                rows++
                if (sessionId == null) sessionId = fields.getOrNull(1)
                previous = fields
                if (terminated) validByteCount = lineEnd
            }
        } else if (error == null && terminated) {
            validByteCount = lineEnd
        }
        lineIndex++
        lineBuffer.reset()
    }

    BufferedInputStream(Files.newInputStream(path, StandardOpenOption.READ)).use { input ->
        while (true) {
            val value = input.read()
            if (value < 0) break
            consumedBytes++
            if (value == '\n'.code) {
                hasTrailingNewline = true
                consumeLine(terminated = true)
            } else {
                if (lineBuffer.size() >= MAX_SIDECAR_LINE_BYTES) {
                    if (error == null) error = "sidecar line exceeds limit"
                } else {
                    lineBuffer.write(value)
                }
                hasTrailingNewline = false
            }
        }
        if (lineBuffer.size() > 0) consumeLine(terminated = false)
    }
    return CaptureSidecarScanReport(
        totalBytes = totalBytes,
        validByteCount = validByteCount,
        completeDataRowCount = rows,
        hasExpectedHeader = hasExpectedHeader,
        hasTruncatedFinalLine = totalBytes > 0L && !hasTrailingNewline,
        monotonicityError = error,
        sessionId = sessionId,
    )
}

private const val MAX_SIDECAR_LINE_BYTES = 1_048_576

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
