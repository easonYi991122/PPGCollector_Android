package com.example.ppgcollector_android.data.session

import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Instant

/** No windows, peaks, waveform arrays or complete report are retained by the index. */
data class CaptureArtifactSourceVersion(
    val sizeBytes: Long,
    val modified: String,
    val fileKey: String?,
    val sha256: String,
)

enum class CaptureArtifactReadState { READY, INVALID, UNREADABLE }

data class CaptureArtifactSummary(
    val path: Path,
    val sourceVersion: CaptureArtifactSourceVersion?,
    val state: CaptureArtifactReadState,
    val detail: String? = null,
    val analysisId: String? = null,
    val sourceSessionId: String? = null,
    val sourceRawSha256: String? = null,
    val algorithmVersion: String? = null,
    val analysisProfile: String? = null,
    val endedUtc: Instant? = null,
    val heartRateBpm: Double? = null,
    val windowCount: Long? = null,
    val acceptedWindowCount: Long? = null,
    val peakCount: Long? = null,
)

internal object CaptureArtifactSummaryReader {
    private val retainedPaths = setOf(
        "schema_version", "analysis_id", "source_session_id", "source_raw_sha256",
        "algorithm_version", "analysis_profile", "ended_utc", "metrics.heart_rate_bpm",
        "metrics.window_count", "metrics.accepted_window_count", "metrics.peak_count",
    )

    fun read(path: Path, cancellationCheck: () -> Unit): CaptureArtifactSummary {
        try {
            cancellationCheck()
            val version = CaptureFileVersion.read(path)
            val digest = MessageDigest.getInstance("SHA-256")
            val fields = Files.newInputStream(path).use { input ->
                DigestInputStream(input, digest).reader(Charsets.UTF_8).buffered(16 * 1024).use { reader ->
                    Projection(reader, cancellationCheck, retainedPaths).read()
                }
            }
            require(CaptureFileVersion.read(path) == version) { "artifact changed while indexing" }
            require(fields["schema_version"] == CaptureSessionOfflineAnalysisService.schemaVersion) { "unsupported analysis schema" }
            fun required(name: String) = requireNotNull(fields[name]) { "missing $name" }
            fun count(name: String) = required("metrics.$name").toLong().also { require(it >= 0) }
            val heartRate = fields["metrics.heart_rate_bpm"]?.toDouble()?.also { require(it.isFinite()) }
            return CaptureArtifactSummary(
                path, CaptureArtifactSourceVersion(version.size, version.modified, version.key,
                    digest.digest().joinToString("") { "%02x".format(it) }),
                CaptureArtifactReadState.READY,
                analysisId = required("analysis_id"), sourceSessionId = required("source_session_id"),
                sourceRawSha256 = required("source_raw_sha256"), algorithmVersion = required("algorithm_version"),
                analysisProfile = required("analysis_profile"), endedUtc = Instant.parse(required("ended_utc")),
                heartRateBpm = heartRate, windowCount = count("window_count"),
                acceptedWindowCount = count("accepted_window_count"), peakCount = count("peak_count"),
            )
        } catch (error: Exception) {
            if (error is java.util.concurrent.CancellationException || error is CaptureSessionBusyException) throw error
            return CaptureArtifactSummary(path, null,
                if (error is java.io.IOException) CaptureArtifactReadState.UNREADABLE else CaptureArtifactReadState.INVALID,
                detail = (error.message ?: error.javaClass.simpleName).take(160))
        }
    }

    /** Validate JSON while projecting a fixed set of scalar paths; arrays are streamed away. */
    private class Projection(
        private val reader: Reader,
        private val cancellationCheck: () -> Unit,
        private val retained: Set<String>,
    ) {
        private var next = -2
        private var chars = 0L
        private val result = HashMap<String, String?>()

        private fun peek(): Int {
            if (next == -2) {
                if (chars % 4096L == 0L) cancellationCheck()
                next = reader.read()
                chars++
                require(chars <= 64L * 1024 * 1024) { "analysis JSON exceeds index read limit" }
            }
            return next
        }
        private fun take(): Int = peek().also { next = -2 }
        private fun space() { while (peek() == 9 || peek() == 10 || peek() == 13 || peek() == 32) take() }
        private fun expect(value: Char) { space(); require(take() == value.code) { "invalid JSON" } }

        fun read(): Map<String, String?> {
            value("", 0)
            space()
            require(peek() == -1) { "trailing JSON content" }
            return result
        }

        private fun value(path: String?, depth: Int) {
            require(depth <= 32) { "JSON nesting exceeds limit" }
            space()
            when (peek()) {
                '{'.code -> {
                    take(); space()
                    if (peek() != '}'.code) while (true) {
                        space(); val key = string()
                        expect(':')
                        val child = if (path == null) null else if (path.isEmpty()) key else "$path.$key"
                        val wanted = child?.takeIf { candidate -> retained.any { it == candidate || it.startsWith("$candidate.") } }
                        value(wanted, depth + 1)
                        space()
                        if (peek() != ','.code) break
                        take()
                    }
                    expect('}')
                }
                '['.code -> {
                    take(); space()
                    if (peek() != ']'.code) while (true) {
                        value(null, depth + 1); space()
                        if (peek() != ','.code) break
                        take()
                    }
                    expect(']')
                }
                '"'.code -> scalar(path, string())
                else -> {
                    val token = StringBuilder()
                    while (peek() != -1 && peek().toChar() !in ",]} \t\r\n") {
                        require(token.length < 128) { "JSON scalar exceeds limit" }
                        token.append(take().toChar())
                    }
                    val text = token.toString()
                    require(text in setOf("null", "true", "false") || NUMBER.matches(text)) { "invalid JSON scalar" }
                    scalar(path, text.takeUnless { it == "null" })
                }
            }
        }

        private fun scalar(path: String?, value: String?) {
            if (path in retained) {
                require(!result.containsKey(path)) { "duplicate summary key" }
                result[path!!] = value
            }
        }

        private fun string(): String {
            require(take() == '"'.code) { "expected JSON string" }
            val encoded = StringBuilder("\"")
            var escaped = false
            while (true) {
                val char = take()
                require(char >= 32) { "unterminated JSON string" }
                require(encoded.length < 16 * 1024) { "JSON string exceeds limit" }
                encoded.append(char.toChar())
                if (char == '"'.code && !escaped) break
                escaped = char == '\\'.code && !escaped
            }
            return (JsonParser(encoded.toString()).parse() as JsonValue.StringValue).value
        }

        companion object { val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?") }
    }
}
