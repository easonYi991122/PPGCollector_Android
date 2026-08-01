package com.example.ppgcollector_android.core.signal.hr

import com.example.ppgcollector_android.core.signal.*
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateEstimatorTest {
    @Test
    fun frozenConfigurationMatchesFixtureContract() {
        val root = loadFixture()
        val config = root.obj("config")
        val expected = HeartRateConfiguration.pythonBaseline01
        assertEquals("cup.heart-rate.parity.v1", root.string("schema"))
        assertEquals(expected.algorithmVersion, root.string("algorithm_version"))
        assertEquals(expected.minBpm, config.number("min_bpm"), 0.0)
        assertEquals(expected.maxBpm, config.number("max_bpm"), 0.0)
        assertEquals(expected.minimumWindowSeconds, config.number("minimum_window_seconds"), 0.0)
        assertEquals(expected.maximumWindowSeconds, config.number("maximum_window_seconds"), 0.0)
        assertEquals(expected.minimumRobustScale, config.number("minimum_robust_scale"), 0.0)
        assertEquals(expected.confidenceThreshold, config.number("confidence_threshold"), 0.0)
        assertEquals(expected.welchNearPeakHz, config.number("welch_near_peak_hz"), 0.0)
        assertEquals("hann_periodic", config.string("welch_window"))
        assertEquals("linear", config.string("welch_detrend"))
        assertEquals("density", config.string("welch_scaling"))
    }

    @Test
    fun finalEstimatesAndReasonsMatchAllFixtureCases() {
        loadFixture().array("cases").forEach { testCase ->
            val actual = estimate(testCase)
            val expected = testCase.obj("expected")
            assertOptionalClose(testCase.string("name"), actual.bpm, expected.numberOrNull("bpm"), 1e-10)
            assertOptionalClose(testCase.string("name"), actual.peakBpm, expected.numberOrNull("peak_bpm"), 1e-10)
            assertOptionalClose(testCase.string("name"), actual.spectralBpm, expected.numberOrNull("spectral_bpm"), 1e-10)
            assertClose(testCase.string("name") + " confidence", actual.confidence, expected.number("confidence"), 1e-10)
            val expectedSnr = expected.numberOrNull("snr_db")
            if (expectedSnr == null) assertEquals(Double.NEGATIVE_INFINITY, actual.snrDb, 0.0)
            else assertClose(testCase.string("name") + " snr", actual.snrDb, expectedSnr, 1e-9)
            assertEquals(expected.stringOrNull("polarity"), actual.polarity?.wireValue)
            assertEquals(expected.array("peak_indices").map { it.number().toInt() }, actual.peakIndices)
            assertOptionalClose(testCase.string("name"), actual.rrMadSeconds, expected.numberOrNull("rr_mad_s"), 1e-10)
            assertEquals(expected.stringOrNull("unavailable_reason"), actual.unavailableReason?.wireValue)
            assertEquals(expected.numberOrNull("bpm") != null && expected.stringOrNull("unavailable_reason") == null, actual.isValid)
        }
    }

    @Test
    fun workSpectralAndCandidateTraceMatchFixture() {
        loadFixture().array("cases").filter {
            it.obj("trace").array("candidates").isNotEmpty()
        }.forEach { testCase ->
            val actual = estimate(testCase)
            val trace = testCase.obj("trace")
            assertEquals(trace.number("edge_trim_count").toInt(), actual.trace.edgeTrimCount)
            assertOptionalClose(testCase.string("name"), actual.trace.workMedian, trace.numberOrNull("work_median"), 1e-10)
            assertOptionalClose(testCase.string("name"), actual.trace.robustScale, trace.numberOrNull("robust_scale"), 1e-10)
            assertCloseList(testCase.string("name") + " centered", actual.trace.workCentered, trace.array("work_centered").map { it.number() }, 1e-10)
            val expectedSpectral = trace.obj("spectral")
            val spectral = actual.trace.spectral!!
            assertEquals(expectedSpectral.number("nperseg").toInt(), spectral.segmentLength)
            assertCloseList(testCase.string("name") + " frequencies", spectral.frequenciesHz, expectedSpectral.array("frequencies_hz").map { it.number() }, 1e-12)
            assertCloseList(testCase.string("name") + " power", spectral.power, expectedSpectral.array("power").map { it.number() }, 1e-8)
            assertEquals(expectedSpectral.array("cardiac_indices").map { it.number().toInt() }, spectral.cardiacIndices)
            assertEquals(expectedSpectral.numberOrNull("peak_index")?.toInt(), spectral.peakIndex)
            assertEquals(expectedSpectral.array("near_peak_indices").map { it.number().toInt() }, spectral.nearPeakIndices)
            assertOptionalClose(testCase.string("name"), spectral.spectralBpm, expectedSpectral.numberOrNull("spectral_bpm"), 1e-10)
            assertClose(testCase.string("name") + " concentration", spectral.concentration, expectedSpectral.number("concentration"), 1e-10)

            val expectedCandidates = trace.array("candidates")
            assertEquals(expectedCandidates.size, actual.trace.candidates.size)
            expectedCandidates.zip(actual.trace.candidates).forEach { (expectedCandidate, candidate) ->
                assertEquals(expectedCandidate.string("polarity"), candidate.polarity.wireValue)
                assertEquals(expectedCandidate.number("distance_samples").toInt(), candidate.distanceSamples)
                assertEquals(expectedCandidate.array("detected_local_peak_indices").map { it.number().toInt() }, candidate.detectedLocalPeakIndices)
                assertEquals(expectedCandidate.array("detected_global_peak_indices").map { it.number().toInt() }, candidate.detectedGlobalPeakIndices)
                assertCloseList("prominences", candidate.prominences, expectedCandidate.array("prominences").map { it.number() }, 1e-8)
                assertEquals(expectedCandidate.array("in_range_mask").map { it.boolean() }, candidate.inRangeMask)
                assertEquals(expectedCandidate.array("spectral_match_mask").map { it.boolean() }, candidate.spectralMatchMask)
                assertEquals(expectedCandidate.boolean("spectral_filter_applied"), candidate.spectralFilterApplied)
                assertEquals(expectedCandidate.array("valid_mask").map { it.boolean() }, candidate.validMask)
                assertEquals(expectedCandidate.array("longest_run_global_peak_indices").map { it.number().toInt() }, candidate.longestRunGlobalPeakIndices)
                assertOptionalClose("candidate peak", candidate.peakBpm, expectedCandidate.numberOrNull("peak_bpm"), 1e-10)
                assertOptionalClose("candidate score", candidate.score, expectedCandidate.numberOrNull("score"), 1e-10)
            }
        }
    }

    @Test
    fun inputFailuresAndLatestEightSecondWindowAreExplicit() {
        val mismatch = HeartRateEstimator.estimate(
            values = List(400) { 2.0 }, timeSeconds = List(399) { 0.0 }, sampleRateHz = 100.0,
        )
        val nonFinite = HeartRateEstimator.estimate(
            values = List(399) { 2.0 } + Double.NaN,
            timeSeconds = List(400) { it / 100.0 }, sampleRateHz = 100.0,
        )
        val invalidRate = HeartRateEstimator.estimate(
            values = List(400) { 2.0 }, timeSeconds = List(400) { it / 100.0 }, sampleRateHz = 0.0,
        )
        assertEquals(HeartRateUnavailableReason.INPUT_LENGTH_MISMATCH, mismatch.unavailableReason)
        assertEquals(HeartRateUnavailableReason.NON_FINITE_INPUT, nonFinite.unavailableReason)
        assertEquals(HeartRateUnavailableReason.INVALID_CONFIGURATION, invalidRate.unavailableReason)

        val time = List(1_000) { it / 100.0 }
        val values = time.map { 2_000.0 * kotlin.math.sin(2.0 * Math.PI * 1.5 * it) }
        val long = HeartRateEstimator.estimate(values, time, 100.0)
        val suffix = HeartRateEstimator.estimate(values.takeLast(800), time.takeLast(800), 100.0)
        assertEquals(200, long.trace.windowOffset)
        assertEquals(long.bpm, suffix.bpm)
        assertEquals(suffix.peakIndices.map { it + 200 }, long.peakIndices)
    }

    private fun estimate(testCase: JsonValue): HeartRateEstimate = HeartRateEstimator.estimate(
        values = testCase.array("values").map { it.number() },
        timeSeconds = testCase.array("time_s").map { it.number() },
        sampleRateHz = testCase.number("sample_rate_hz"),
    )

    private fun assertOptionalClose(name: String, actual: Double?, expected: Double?, tolerance: Double) {
        if (expected == null) assertNull("$name expected null", actual)
        else {
            assertTrue("$name actual null", actual != null)
            assertClose(name, actual!!, expected, tolerance)
        }
    }

    private fun assertClose(name: String, actual: Double, expected: Double, tolerance: Double) {
        assertTrue("$name: expected $expected actual $actual", abs(actual - expected) <= tolerance)
    }

    private fun assertCloseList(name: String, actual: List<Double>, expected: List<Double>, tolerance: Double) {
        assertEquals("$name size", expected.size, actual.size)
        actual.zip(expected).forEachIndexed { index, (got, want) ->
            assertClose("$name[$index]", got, want, tolerance)
        }
    }

    private fun loadFixture(): JsonValue {
        val candidates = listOf(
            Paths.get("AndroidMigrationPlanning/reference_sources/signal_fixtures/heart_rate/heart_rate_vectors.json"),
            Paths.get("../AndroidMigrationPlanning/reference_sources/signal_fixtures/heart_rate/heart_rate_vectors.json"),
        )
        val path = candidates.firstOrNull { Files.exists(it) }
            ?: error("heart-rate fixture not found from ${Paths.get("").toAbsolutePath()}")
        return JsonParser(Files.readString(path)).parse()
    }
}

private sealed interface JsonValue {
    data class ObjectValue(val fields: Map<String, JsonValue>) : JsonValue
    data class ArrayValue(val values: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val number: Double) : JsonValue
    data class BooleanValue(val boolean: Boolean) : JsonValue
    data object NullValue : JsonValue

    fun obj(name: String): JsonValue = (this as ObjectValue).fields.getValue(name)
    fun array(name: String): List<JsonValue> = (obj(name) as ArrayValue).values
    fun string(name: String): String = (obj(name) as StringValue).value
    fun stringOrNull(name: String): String? = when (val value = obj(name)) {
        NullValue -> null
        else -> (value as StringValue).value
    }
    fun number(name: String): Double = (obj(name) as NumberValue).number
    fun number(): Double = (this as NumberValue).number
    fun numberOrNull(name: String): Double? = when (val value = obj(name)) {
        NullValue -> null
        else -> (value as NumberValue).number
    }
    fun boolean(name: String): Boolean = (obj(name) as BooleanValue).boolean
    fun boolean(): Boolean = (this as BooleanValue).boolean
}

private class JsonParser(private val input: String) {
    private var index = 0

    fun parse(): JsonValue {
        skipWhitespace()
        val value = parseValue()
        skipWhitespace()
        check(index == input.length)
        return value
    }

    private fun parseValue(): JsonValue {
        skipWhitespace()
        return when (input[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonValue.StringValue(parseString())
            't' -> literal("true", JsonValue.BooleanValue(true))
            'f' -> literal("false", JsonValue.BooleanValue(false))
            'n' -> literal("null", JsonValue.NullValue)
            '-', in '0'..'9' -> JsonValue.NumberValue(parseNumber().toDouble())
            else -> error("invalid JSON at $index")
        }
    }

    private fun parseObject(): JsonValue.ObjectValue {
        expect('{'); skipWhitespace()
        val fields = LinkedHashMap<String, JsonValue>()
        if (consume('}')) return JsonValue.ObjectValue(fields)
        while (true) {
            skipWhitespace(); val key = parseString(); skipWhitespace(); expect(':')
            fields[key] = parseValue(); skipWhitespace()
            if (consume('}')) break
            expect(',')
        }
        return JsonValue.ObjectValue(fields)
    }

    private fun parseArray(): JsonValue.ArrayValue {
        expect('['); skipWhitespace()
        val values = ArrayList<JsonValue>()
        if (consume(']')) return JsonValue.ArrayValue(values)
        while (true) {
            values += parseValue(); skipWhitespace()
            if (consume(']')) break
            expect(',')
        }
        return JsonValue.ArrayValue(values)
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (true) {
            when (val character = input[index++]) {
                '"' -> return result.toString()
                '\\' -> when (val escaped = input[index++]) {
                    '"', '\\', '/' -> result.append(escaped)
                    'b' -> result.append('\b')
                    'f' -> result.append('\u000C')
                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    'u' -> result.append(input.substring(index, index + 4).toInt(16).toChar()).also { index += 4 }
                    else -> error("invalid escape")
                }
                else -> result.append(character)
            }
        }
    }

    private fun parseNumber(): String {
        val start = index
        consume('-')
        while (index < input.length && input[index].isDigit()) index++
        if (consume('.')) while (index < input.length && input[index].isDigit()) index++
        if (index < input.length && (input[index] == 'e' || input[index] == 'E')) {
            index++; if (index < input.length && (input[index] == '+' || input[index] == '-')) index++
            while (index < input.length && input[index].isDigit()) index++
        }
        return input.substring(start, index)
    }

    private fun literal(text: String, value: JsonValue): JsonValue {
        check(input.startsWith(text, index)); index += text.length; return value
    }
    private fun skipWhitespace() { while (index < input.length && input[index].isWhitespace()) index++ }
    private fun consume(character: Char): Boolean = if (index < input.length && input[index] == character) { index++; true } else false
    private fun expect(character: Char) { check(consume(character)) { "expected $character at $index" } }
}
