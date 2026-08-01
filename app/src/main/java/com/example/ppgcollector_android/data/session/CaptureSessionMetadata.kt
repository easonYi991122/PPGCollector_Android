package com.example.ppgcollector_android.data.session

import java.time.Instant
import java.util.LinkedHashMap

enum class CaptureStopReason(val wireValue: String) {
    USER("user"),
    VIEW_EXIT("viewExit"),
    SCENE_BACKGROUND("sceneBackground"),
    DEVICE_DISCONNECT("deviceDisconnect"),
    DATA_TIMEOUT("dataTimeout"),
    CRASH_RECOVERY("crashRecovery"),
    WRITE_ERROR("writeError"),
    PROTOCOL_ERROR("protocolError"),
    RESOURCE_PRESSURE("resourcePressure"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWireValue(value: String): CaptureStopReason =
            entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

data class CaptureSessionDeviceMetadata(
    val name: String,
    val identifier: String,
    val serviceUuid: String,
    val notifyCharacteristicUuid: String,
    val firmwareVersion: String?,
    val calibrationId: String?,
)

data class CaptureSessionWriterMetadata(
    val lastFlushUtc: Instant?,
    val rawBytes: Long,
    val csvRows: Long,
    val error: String?,
)

data class CaptureSessionFilesMetadata(
    val raw: String,
    val samples: String,
)

data class CaptureSessionRecoveryMetadata(
    val strategy: String,
    val recoveredUtc: Instant,
    val recoverySoftVersion: String,
    val sourceDirectoryName: String,
    val sourceSessionId: String?,
    val sourceRawSha256: String,
    val sourceCsvSha256: String,
    val sourceMetadataSha256: String?,
    val sourceRawTotalBytes: Long,
    val sourceRawCopiedBytes: Long,
    val sourceCsvTotalBytes: Long,
    val sourceCsvCopiedBytes: Long,
    val csvPreservesSourceSessionId: Boolean,
)

data class CaptureSessionMetadata(
    val schemaVersion: String,
    val sessionId: String,
    val baseName: String,
    val startedUtc: Instant,
    val endedUtc: Instant?,
    val softVersion: String,
    val algVersion: String,
    val preprocessProfile: String,
    val protocolProfile: String,
    val transportProfile: String,
    val sampleRateHz: Int,
    val samplesPerFrame: Int,
    val device: CaptureSessionDeviceMetadata,
    val complete: Boolean,
    val stopReason: CaptureStopReason?,
    val frameCount: Long,
    val sampleCount: Long,
    val rawChunkCount: Long,
    val missingFrames: Long,
    val duplicateFrames: Long,
    val outOfOrderFrames: Long,
    val invalidFrames: Long,
    val discardedBytes: Long,
    val writer: CaptureSessionWriterMetadata,
    val files: CaptureSessionFilesMetadata,
    val recovery: CaptureSessionRecoveryMetadata?,
)

class CaptureSessionMetadataJsonException(message: String) :
    IllegalArgumentException(message)

/** JSON codec for the versioned session metadata contract. */
object CaptureSessionMetadataCodec {
    fun encode(metadata: CaptureSessionMetadata): String =
        JsonWriter.write(toJson(metadata))

    fun encodeBytes(metadata: CaptureSessionMetadata): ByteArray =
        encode(metadata).toByteArray(Charsets.UTF_8)

    fun decode(json: String): CaptureSessionMetadata =
        fromJson(JsonParser(json).parse())

    fun decode(bytes: ByteArray): CaptureSessionMetadata =
        decode(bytes.toString(Charsets.UTF_8))

    private fun toJson(metadata: CaptureSessionMetadata): JsonValue.ObjectValue =
        obj(
            "schema_version" to string(metadata.schemaVersion),
            "session_id" to string(metadata.sessionId),
            "base_name" to string(metadata.baseName),
            "started_utc" to string(metadata.startedUtc.toString()),
            "ended_utc" to (metadata.endedUtc?.let { string(it.toString()) } ?: JsonValue.NullValue),
            "soft_version" to string(metadata.softVersion),
            "alg_version" to string(metadata.algVersion),
            "preprocess_profile" to string(metadata.preprocessProfile),
            "protocol_profile" to string(metadata.protocolProfile),
            "transport_profile" to string(metadata.transportProfile),
            "sample_rate_hz" to number(metadata.sampleRateHz.toLong()),
            "samples_per_frame" to number(metadata.samplesPerFrame.toLong()),
            "device" to obj(
                "name" to string(metadata.device.name),
                "identifier" to string(metadata.device.identifier),
                "service_uuid" to string(metadata.device.serviceUuid),
                "notify_characteristic_uuid" to string(metadata.device.notifyCharacteristicUuid),
                "firmware_version" to (metadata.device.firmwareVersion?.let(::string)
                    ?: JsonValue.NullValue),
                "calibration_id" to (metadata.device.calibrationId?.let(::string)
                    ?: JsonValue.NullValue),
            ),
            "complete" to JsonValue.BooleanValue(metadata.complete),
            "stop_reason" to (metadata.stopReason?.let { string(it.wireValue) }
                ?: JsonValue.NullValue),
            "frame_count" to number(metadata.frameCount),
            "sample_count" to number(metadata.sampleCount),
            "raw_chunk_count" to number(metadata.rawChunkCount),
            "missing_frames" to number(metadata.missingFrames),
            "duplicate_frames" to number(metadata.duplicateFrames),
            "out_of_order_frames" to number(metadata.outOfOrderFrames),
            "invalid_frames" to number(metadata.invalidFrames),
            "discarded_bytes" to number(metadata.discardedBytes),
            "writer" to obj(
                "last_flush_utc" to (metadata.writer.lastFlushUtc?.let { string(it.toString()) }
                    ?: JsonValue.NullValue),
                "raw_bytes" to number(metadata.writer.rawBytes),
                "csv_rows" to number(metadata.writer.csvRows),
                "error" to (metadata.writer.error?.let(::string) ?: JsonValue.NullValue),
            ),
            "files" to obj(
                "raw" to string(metadata.files.raw),
                "samples" to string(metadata.files.samples),
            ),
            "recovery" to (metadata.recovery?.let(::toJson) ?: JsonValue.NullValue),
        )

    private fun toJson(recovery: CaptureSessionRecoveryMetadata): JsonValue.ObjectValue =
        obj(
            "strategy" to string(recovery.strategy),
            "recovered_utc" to string(recovery.recoveredUtc.toString()),
            "recovery_soft_version" to string(recovery.recoverySoftVersion),
            "source_directory_name" to string(recovery.sourceDirectoryName),
            "source_session_id" to (recovery.sourceSessionId?.let(::string)
                ?: JsonValue.NullValue),
            "source_raw_sha256" to string(recovery.sourceRawSha256),
            "source_csv_sha256" to string(recovery.sourceCsvSha256),
            "source_metadata_sha256" to (recovery.sourceMetadataSha256?.let(::string)
                ?: JsonValue.NullValue),
            "source_raw_total_bytes" to number(recovery.sourceRawTotalBytes),
            "source_raw_copied_bytes" to number(recovery.sourceRawCopiedBytes),
            "source_csv_total_bytes" to number(recovery.sourceCsvTotalBytes),
            "source_csv_copied_bytes" to number(recovery.sourceCsvCopiedBytes),
            "csv_preserves_source_session_id" to
                JsonValue.BooleanValue(recovery.csvPreservesSourceSessionId),
        )

    private fun fromJson(value: JsonValue): CaptureSessionMetadata {
        val root = value.asObject("metadata")
        val device = root.requiredObject("device")
        val writer = root.requiredObject("writer")
        val files = root.requiredObject("files")
        return CaptureSessionMetadata(
            schemaVersion = root.requiredString("schema_version"),
            sessionId = root.requiredString("session_id"),
            baseName = root.requiredString("base_name"),
            startedUtc = root.requiredInstant("started_utc"),
            endedUtc = root.optionalInstant("ended_utc"),
            softVersion = root.requiredString("soft_version"),
            algVersion = root.requiredString("alg_version"),
            preprocessProfile = root.requiredString("preprocess_profile"),
            protocolProfile = root.requiredString("protocol_profile"),
            transportProfile = root.requiredString("transport_profile"),
            sampleRateHz = root.requiredInt("sample_rate_hz"),
            samplesPerFrame = root.requiredInt("samples_per_frame"),
            device = CaptureSessionDeviceMetadata(
                name = device.requiredString("name"),
                identifier = device.requiredString("identifier"),
                serviceUuid = device.requiredString("service_uuid"),
                notifyCharacteristicUuid = device.requiredString("notify_characteristic_uuid"),
                firmwareVersion = device.optionalString("firmware_version"),
                calibrationId = device.optionalString("calibration_id"),
            ),
            complete = root.requiredBoolean("complete"),
            stopReason = root.optionalString("stop_reason")?.let(CaptureStopReason::fromWireValue),
            frameCount = root.requiredLong("frame_count"),
            sampleCount = root.requiredLong("sample_count"),
            rawChunkCount = root.requiredLong("raw_chunk_count"),
            missingFrames = root.requiredLong("missing_frames"),
            duplicateFrames = root.requiredLong("duplicate_frames"),
            outOfOrderFrames = root.requiredLong("out_of_order_frames"),
            invalidFrames = root.requiredLong("invalid_frames"),
            discardedBytes = root.requiredLong("discarded_bytes"),
            writer = CaptureSessionWriterMetadata(
                lastFlushUtc = writer.optionalInstant("last_flush_utc"),
                rawBytes = writer.requiredLong("raw_bytes"),
                csvRows = writer.requiredLong("csv_rows"),
                error = writer.optionalString("error"),
            ),
            files = CaptureSessionFilesMetadata(
                raw = files.requiredString("raw"),
                samples = files.requiredString("samples"),
            ),
            recovery = root.optionalObject("recovery")?.let(::fromRecoveryJson),
        )
    }

    private fun fromRecoveryJson(value: JsonValue): CaptureSessionRecoveryMetadata {
        val recovery = value.asObject("recovery")
        return CaptureSessionRecoveryMetadata(
            strategy = recovery.requiredString("strategy"),
            recoveredUtc = recovery.requiredInstant("recovered_utc"),
            recoverySoftVersion = recovery.requiredString("recovery_soft_version"),
            sourceDirectoryName = recovery.requiredString("source_directory_name"),
            sourceSessionId = recovery.optionalString("source_session_id"),
            sourceRawSha256 = recovery.requiredString("source_raw_sha256"),
            sourceCsvSha256 = recovery.requiredString("source_csv_sha256"),
            sourceMetadataSha256 = recovery.optionalString("source_metadata_sha256"),
            sourceRawTotalBytes = recovery.requiredLong("source_raw_total_bytes"),
            sourceRawCopiedBytes = recovery.requiredLong("source_raw_copied_bytes"),
            sourceCsvTotalBytes = recovery.requiredLong("source_csv_total_bytes"),
            sourceCsvCopiedBytes = recovery.requiredLong("source_csv_copied_bytes"),
            csvPreservesSourceSessionId = recovery.requiredBoolean(
                "csv_preserves_source_session_id",
            ),
        )
    }

    private fun obj(vararg fields: Pair<String, JsonValue>) =
        JsonValue.ObjectValue(linkedMapOf(*fields))

    private fun string(value: String) = JsonValue.StringValue(value)
    private fun number(value: Long) = JsonValue.NumberValue(value.toString())

    private fun JsonValue.ObjectValue.requiredString(name: String): String =
        field(name).asString(name)

    private fun JsonValue.ObjectValue.optionalString(name: String): String? =
        fields[name]?.let { if (it is JsonValue.NullValue) null else it.asString(name) }

    private fun JsonValue.ObjectValue.requiredLong(name: String): Long =
        field(name).asLong(name)

    private fun JsonValue.ObjectValue.requiredInt(name: String): Int =
        field(name).asLong(name).let {
            if (it !in Int.MIN_VALUE..Int.MAX_VALUE) {
                throw CaptureSessionMetadataJsonException("$name is out of Int range")
            }
            it.toInt()
        }

    private fun JsonValue.ObjectValue.requiredBoolean(name: String): Boolean =
        field(name).asBoolean(name)

    private fun JsonValue.ObjectValue.requiredInstant(name: String): Instant =
        field(name).asString(name).parseInstant(name)

    private fun JsonValue.ObjectValue.optionalInstant(name: String): Instant? =
        optionalString(name)?.parseInstant(name)

    private fun JsonValue.ObjectValue.requiredObject(name: String): JsonValue.ObjectValue =
        field(name).asObject(name)

    private fun JsonValue.ObjectValue.optionalObject(name: String): JsonValue.ObjectValue? =
        fields[name]?.let { if (it is JsonValue.NullValue) null else it.asObject(name) }

    private fun JsonValue.ObjectValue.field(name: String): JsonValue =
        fields[name] ?: throw CaptureSessionMetadataJsonException("missing field: $name")

    private fun String.parseInstant(name: String): Instant =
        try {
            Instant.parse(this)
        } catch (error: Exception) {
            throw CaptureSessionMetadataJsonException("invalid $name: ${error.message}")
        }

    private fun JsonValue.asObject(name: String): JsonValue.ObjectValue = this as? JsonValue.ObjectValue
        ?: throw CaptureSessionMetadataJsonException("$name must be an object")

    private fun JsonValue.asString(name: String): String =
        ((this as? JsonValue.StringValue)?.value
            ?: throw CaptureSessionMetadataJsonException("$name must be a string"))

    private fun JsonValue.asLong(name: String): Long =
        ((this as? JsonValue.NumberValue)?.raw?.toLongOrNull()
            ?: throw CaptureSessionMetadataJsonException("$name must be an integer"))

    private fun JsonValue.asBoolean(name: String): Boolean =
        ((this as? JsonValue.BooleanValue)?.value
            ?: throw CaptureSessionMetadataJsonException("$name must be boolean"))
}

private sealed interface JsonValue {
    data class ObjectValue(val fields: Map<String, JsonValue>) : JsonValue
    data class ArrayValue(val values: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val raw: String) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object NullValue : JsonValue
}

private object JsonWriter {
    fun write(value: JsonValue): String = buildString { appendValue(value, 0) }

    private fun StringBuilder.appendValue(value: JsonValue, indent: Int) {
        when (value) {
            is JsonValue.ObjectValue -> appendObject(value, indent)
            is JsonValue.ArrayValue -> appendArray(value, indent)
            is JsonValue.StringValue -> appendQuoted(value.value)
            is JsonValue.NumberValue -> append(value.raw)
            is JsonValue.BooleanValue -> append(value.value)
            JsonValue.NullValue -> append("null")
        }
    }

    private fun StringBuilder.appendObject(value: JsonValue.ObjectValue, indent: Int) {
        append('{')
        val fields = value.fields.toSortedMap()
        if (fields.isNotEmpty()) {
            fields.entries.forEachIndexed { index, (key, child) ->
                append("\n").append("  ".repeat(indent + 1))
                appendQuoted(key)
                append(": ")
                appendValue(child, indent + 1)
                if (index != fields.size - 1) append(',')
            }
            append("\n").append("  ".repeat(indent))
        }
        append('}')
    }

    private fun StringBuilder.appendArray(value: JsonValue.ArrayValue, indent: Int) {
        append('[')
        value.values.forEachIndexed { index, child ->
            if (index > 0) append(',')
            appendValue(child, indent)
        }
        append(']')
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}

private class JsonParser(private val input: String) {
    private var index = 0

    fun parse(): JsonValue {
        skipWhitespace()
        val value = parseValue()
        skipWhitespace()
        if (index != input.length) fail("trailing JSON content")
        return value
    }

    private fun parseValue(): JsonValue {
        skipWhitespace()
        if (index >= input.length) fail("unexpected end of JSON")
        return when (input[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue.StringValue(parseString())
            't' -> parseLiteral("true", JsonValue.BooleanValue(true))
            'f' -> parseLiteral("false", JsonValue.BooleanValue(false))
            'n' -> parseLiteral("null", JsonValue.NullValue)
            '-', in '0'..'9' -> JsonValue.NumberValue(parseNumber())
            else -> fail("unexpected JSON token at $index")
        }
    }

    private fun parseObject(): JsonValue.ObjectValue {
        expect('{')
        val values = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (consumeIf('}')) return JsonValue.ObjectValue(values)
        while (true) {
            skipWhitespace()
            if (index >= input.length || input[index] != '"') fail("object key must be a string")
            val key = parseString()
            skipWhitespace()
            expect(':')
            values[key] = parseValue()
            skipWhitespace()
            if (consumeIf('}')) return JsonValue.ObjectValue(values)
            expect(',')
        }
    }

    private fun parseArray(): JsonValue.ArrayValue {
        expect('[')
        val values = ArrayList<JsonValue>()
        skipWhitespace()
        if (consumeIf(']')) return JsonValue.ArrayValue(values)
        while (true) {
            values += parseValue()
            skipWhitespace()
            if (consumeIf(']')) return JsonValue.ArrayValue(values)
            expect(',')
        }
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (index < input.length) {
            when (val character = input[index++]) {
                '"' -> return result.toString()
                '\\' -> {
                    if (index >= input.length) fail("unfinished string escape")
                    when (val escaped = input[index++]) {
                        '"', '\\', '/' -> result.append(escaped)
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000C')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            if (index + 4 > input.length) fail("short unicode escape")
                            val hex = input.substring(index, index + 4)
                            result.append(hex.toIntOrNull(16)?.toChar()
                                ?: fail("invalid unicode escape"))
                            index += 4
                        }
                        else -> fail("invalid string escape: $escaped")
                    }
                }
                else -> {
                    if (character.code < 0x20) fail("unescaped control character")
                    result.append(character)
                }
            }
        }
        fail("unterminated string")
    }

    private fun parseNumber(): String {
        val start = index
        if (consumeIf('-')) Unit else Unit
        if (consumeIf('0')) Unit else {
            if (index >= input.length || input[index] !in '1'..'9') fail("invalid number")
            while (index < input.length && input[index].isDigit()) index += 1
        }
        if (consumeIf('.')) {
            if (index >= input.length || !input[index].isDigit()) fail("invalid number fraction")
            while (index < input.length && input[index].isDigit()) index += 1
        }
        if (index < input.length && input[index] in "eE") {
            index += 1
            if (index < input.length && input[index] in "+-") index += 1
            if (index >= input.length || !input[index].isDigit()) fail("invalid number exponent")
            while (index < input.length && input[index].isDigit()) index += 1
        }
        return input.substring(start, index)
    }

    private fun parseLiteral(literal: String, value: JsonValue): JsonValue {
        if (!input.startsWith(literal, index)) fail("expected $literal")
        index += literal.length
        return value
    }

    private fun expect(character: Char) {
        if (index >= input.length || input[index] != character) {
            fail("expected '$character' at $index")
        }
        index += 1
    }

    private fun consumeIf(character: Char): Boolean {
        if (index < input.length && input[index] == character) {
            index += 1
            return true
        }
        return false
    }

    private fun skipWhitespace() {
        while (index < input.length && input[index].isWhitespace()) index += 1
    }

    private fun fail(message: String): Nothing =
        throw CaptureSessionMetadataJsonException("$message (offset $index)")
}
