package com.example.ppgcollector_android.data.session

import java.util.Locale
import kotlin.math.abs

object CaptureCsvSchema {
    val columns: List<String> = listOf(
        "schema_version",
        "session_id",
        "sample_index",
        "device_time_s",
        "host_frame_time_ns",
        "frame_sequence",
        "sample_in_frame",
        "red",
        "ir",
        "heart_rate_bpm",
        "heart_rate_valid",
        "heart_rate_time_s",
        "spo2_percent",
        "spo2_valid",
        "spo2_time_s",
        "sqi",
        "sqi_valid",
        "sqi_time_s",
        "soft_version",
        "alg_version",
        "preprocess_profile",
        "protocol_profile",
        "ratio_of_ratios",
        "ratio_of_ratios_valid",
        "ratio_of_ratios_time_s",
    )

    val header: String
        get() = columns.joinToString(",") + "\n"

    const val sampleRateHz = 100
}

data class CsvMetricCell(
    val value: Double?,
    val isValid: Boolean,
    val sourceSampleIndex: Long?,
)

data class CaptureCsvRow(
    val schemaVersion: String,
    val sessionId: String,
    val sampleIndex: Long,
    val hostFrameTimeNanoseconds: ULong,
    val frameSequence: UByte,
    val sampleInFrame: Int,
    val red: UInt,
    val ir: UInt,
    val heartRateBpm: CsvMetricCell,
    val oxygenSaturationPercent: CsvMetricCell,
    val signalQuality: CsvMetricCell,
    val softVersion: String,
    val algorithmVersion: String,
    val preprocessProfile: String,
    val protocolProfile: String,
    val ratioOfRatios: CsvMetricCell,
) {
    val deviceTimeSeconds: Double
        get() = sampleIndex.toDouble() / CaptureCsvSchema.sampleRateHz
}

enum class CsvInvalidValue {
    EMPTY,
    ZERO,
}

object CaptureCsvFormatter {
    fun format(row: CaptureCsvRow, firstStreamSampleIndex: Long?): String {
        val heartRate = metricFields(
            row.heartRateBpm,
            firstStreamSampleIndex,
            CsvInvalidValue.EMPTY,
        )
        val oxygenSaturation = metricFields(
            row.oxygenSaturationPercent,
            firstStreamSampleIndex,
            CsvInvalidValue.EMPTY,
        )
        val signalQuality = metricFields(
            row.signalQuality,
            firstStreamSampleIndex,
            CsvInvalidValue.ZERO,
        )
        val ratioOfRatios = metricFields(
            row.ratioOfRatios,
            firstStreamSampleIndex,
            CsvInvalidValue.EMPTY,
        )

        val fields = listOf(
            row.schemaVersion,
            row.sessionId,
            row.sampleIndex.toString(),
            formatDouble(row.deviceTimeSeconds),
            row.hostFrameTimeNanoseconds.toString(),
            row.frameSequence.toString(),
            row.sampleInFrame.toString(),
            row.red.toString(),
            row.ir.toString(),
            heartRate.value,
            heartRate.valid,
            heartRate.time,
            oxygenSaturation.value,
            oxygenSaturation.valid,
            oxygenSaturation.time,
            signalQuality.value,
            signalQuality.valid,
            signalQuality.time,
            row.softVersion,
            row.algorithmVersion,
            row.preprocessProfile,
            row.protocolProfile,
            ratioOfRatios.value,
            ratioOfRatios.valid,
            ratioOfRatios.time,
        )
        return fields.joinToString(",", transform = ::escapeField) + "\n"
    }

    private fun metricFields(
        metric: CsvMetricCell,
        firstStreamSampleIndex: Long?,
        invalidValue: CsvInvalidValue,
    ): MetricFields {
        val sourceIndex = metric.sourceSampleIndex
        val value = metric.value
        if (!metric.isValid || value == null || !value.isFinite() ||
            sourceIndex == null || firstStreamSampleIndex == null ||
            sourceIndex < firstStreamSampleIndex
        ) {
            return MetricFields(
                value = if (invalidValue == CsvInvalidValue.ZERO) "0" else "",
                valid = "false",
                time = "",
            )
        }

        val sourceTime =
            (sourceIndex - firstStreamSampleIndex).toDouble() /
                CaptureCsvSchema.sampleRateHz
        return MetricFields(
            value = formatDouble(value),
            valid = "true",
            time = formatDouble(sourceTime),
        )
    }

    private fun escapeField(value: String): String {
        if (!value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            return value
        }
        return "\"${value.replace("\"", "\"\"")}\""
    }

    private fun formatDouble(value: Double): String =
        String.format(Locale.ROOT, "%.6f", value)

    private data class MetricFields(
        val value: String,
        val valid: String,
        val time: String,
    )
}

class CaptureCsvParseException(message: String) : IllegalArgumentException(message)

object CaptureCsvParser {
    fun parseRow(line: String): CaptureCsvRow {
        val fields = parseFields(line.removeSuffix("\n").removeSuffix("\r"))
        if (fields.size != CaptureCsvSchema.columns.size) {
            throw CaptureCsvParseException(
                "expected ${CaptureCsvSchema.columns.size} fields, got ${fields.size}",
            )
        }
        return try {
            val sampleIndex = fields[2].toLongStrict("sample_index")
            val deviceTimeSeconds = fields[3].toDoubleStrict("device_time_s")
            val expectedDeviceTime =
                sampleIndex.toDouble() / CaptureCsvSchema.sampleRateHz
            if (abs(deviceTimeSeconds - expectedDeviceTime) > 0.0000005) {
                throw CaptureCsvParseException("device_time_s does not match sample_index")
            }
            CaptureCsvRow(
                schemaVersion = fields[0],
                sessionId = fields[1],
                sampleIndex = sampleIndex,
                hostFrameTimeNanoseconds = fields[4].toULongStrict("host_frame_time_ns"),
                frameSequence = fields[5].toUIntInRange("frame_sequence", 0u, 255u).toUByte(),
                sampleInFrame = fields[6].toIntStrict("sample_in_frame"),
                red = fields[7].toUIntStrict("red"),
                ir = fields[8].toUIntStrict("ir"),
                heartRateBpm = parseMetric(fields[9], fields[10], fields[11], "heart_rate"),
                oxygenSaturationPercent = parseMetric(
                    fields[12], fields[13], fields[14], "spo2",
                ),
                signalQuality = parseMetric(fields[15], fields[16], fields[17], "sqi"),
                softVersion = fields[18],
                algorithmVersion = fields[19],
                preprocessProfile = fields[20],
                protocolProfile = fields[21],
                ratioOfRatios = parseMetric(
                    fields[22], fields[23], fields[24], "ratio_of_ratios",
                ),
            )
        } catch (error: NumberFormatException) {
            throw CaptureCsvParseException(error.message ?: "invalid numeric field")
        }
    }

    fun requireHeader(headerLine: String) {
        val normalized = headerLine.removeSuffix("\n").removeSuffix("\r")
        if (normalized != CaptureCsvSchema.header.trimEnd('\n')) {
            throw CaptureCsvParseException("unexpected CSV header")
        }
    }

    private fun parseMetric(
        valueField: String,
        validField: String,
        timeField: String,
        name: String,
    ): CsvMetricCell {
        val valid = when (validField) {
            "true" -> true
            "false" -> false
            else -> throw CaptureCsvParseException("$name valid must be true or false")
        }
        val value = if (valueField.isEmpty()) null else valueField.toDoubleStrict(name)
        val time = if (timeField.isEmpty()) null else timeField.toDoubleStrict("${name}_time_s")
        if (valid && (value == null || time == null)) {
            throw CaptureCsvParseException("valid $name metric requires value and time")
        }
        if (!valid && time != null) {
            throw CaptureCsvParseException("invalid $name metric cannot have time")
        }
        return CsvMetricCell(value, valid, null)
    }

    private fun parseFields(line: String): List<String> {
        val fields = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        var justClosedQuote = false
        var index = 0

        while (index < line.length) {
            val character = line[index]
            if (inQuotes) {
                if (character == '"') {
                    if (index + 1 < line.length && line[index + 1] == '"') {
                        current.append('"')
                        index += 2
                        continue
                    }
                    inQuotes = false
                    justClosedQuote = true
                } else {
                    current.append(character)
                }
                index += 1
                continue
            }

            if (justClosedQuote) {
                when (character) {
                    ',' -> {
                        fields += current.toString()
                        current.clear()
                        justClosedQuote = false
                    }
                    else -> throw CaptureCsvParseException(
                        "unexpected character after closing quote",
                    )
                }
                index += 1
                continue
            }

            when (character) {
                ',' -> {
                    fields += current.toString()
                    current.clear()
                }
                '"' -> {
                    if (current.isNotEmpty()) {
                        throw CaptureCsvParseException("quote inside unquoted field")
                    }
                    inQuotes = true
                }
                else -> current.append(character)
            }
            index += 1
        }

        if (inQuotes) throw CaptureCsvParseException("unterminated quoted field")
        fields += current.toString()
        return fields
    }

    private fun String.toLongStrict(name: String): Long =
        toLongOrNull() ?: throw NumberFormatException("invalid $name: $this")

    private fun String.toULongStrict(name: String): ULong =
        toULongOrNull() ?: throw NumberFormatException("invalid $name: $this")

    private fun String.toUIntStrict(name: String): UInt =
        toUIntOrNull() ?: throw NumberFormatException("invalid $name: $this")

    private fun String.toUIntInRange(name: String, minimum: UInt, maximum: UInt): UInt {
        val value = toUIntStrict(name)
        if (value !in minimum..maximum) throw NumberFormatException("invalid $name: $this")
        return value
    }

    private fun String.toIntStrict(name: String): Int =
        toIntOrNull() ?: throw NumberFormatException("invalid $name: $this")

    private fun String.toDoubleStrict(name: String): Double {
        val value = toDoubleOrNull()
            ?: throw NumberFormatException("invalid $name: $this")
        if (!value.isFinite()) throw NumberFormatException("non-finite $name")
        return value
    }
}
