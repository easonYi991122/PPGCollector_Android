package com.example.ppgcollector_android.core.signal.sqi

import com.example.ppgcollector_android.core.signal.*
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateMatchSqiTest {
    @Test
    fun frozenConfigurationAndAllFinalScoresMatchFixture() {
        val root = loadFixture()
        val config = root.obj("config")
        val expectedConfig = TemplateMatchSqiConfiguration.iosBaseline01
        assertEquals("cup.sqi.parity.v2", root.string("schema"))
        assertEquals(expectedConfig.algorithmVersion, root.string("algorithm_version"))
        assertEquals(expectedConfig.preprocessProfile, root.string("preprocess_profile"))
        assertEquals(expectedConfig.sampleRateHz, config.number("sample_rate_hz"), 0.0)
        assertEquals(expectedConfig.ratioPre, config.number("ratio_pre"), 0.0)
        assertEquals(expectedConfig.maximumHeartRateBpm, config.number("hr_max_bpm"), 0.0)
        assertEquals(expectedConfig.goodThreshold, config.number("good_threshold"), 0.0)
        assertEquals(expectedConfig.fairThreshold, config.number("fair_threshold"), 0.0)

        root.array("cases").forEach { testCase ->
            val actual = TemplateMatchSqi.compute(
                testCase.array("signal").map { it.number() },
            )
            val expected = testCase.obj("expected")
            assertEquals(testCase.string("name") + " valid", expected.boolean("valid"), actual.isValid)
            assertOptionalClose(testCase.string("name") + " quality", actual.rawMeanQuality, expected.numberOrNull("mean_quality"), 1e-10)
            assertOptionalClose(testCase.string("name") + " display", actual.sqi, expected.numberOrNull("display_sqi"), 1e-10)
            assertEquals(expected.number("n_cycles").toInt(), actual.cycleCount)
            assertEquals(expected.number("n_peaks").toInt(), actual.peakCount)
            assertOptionalClose(testCase.string("name") + " hr", actual.estimatedHeartRateBpm, expected.numberOrNull("hr_est"), 1e-10)
            assertEquals(expected.string("reason"), actual.reasonCode)
            assertEquals(expected.string("grade"), actual.grade.wireValue)
            assertEquals(expected.string("grade_color_hex"), actual.grade.colorHex)
        }
    }

    @Test
    fun peakAndCycleTracesMatchFixtureForEveryCase() {
        loadFixture().array("cases").forEach { testCase ->
            val actual = TemplateMatchSqi.compute(testCase.array("signal").map { it.number() })
            val trace = testCase.obj("trace")
            val expectedPeak = trace.objOrNull("peak_detection")
            if (expectedPeak == null) {
                assertNull(actual.trace.peakDetection)
            } else {
                val peak = actual.trace.peakDetection!!
                assertClose("mean", peak.mean, expectedPeak.number("mean"), 1e-12)
                assertClose("std", peak.standardDeviation, expectedPeak.number("standard_deviation"), 1e-12)
                assertEquals(expectedPeak.number("minimum_distance_samples").toInt(), peak.minimumDistanceSamples)
                assertEquals(expectedPeak.array("selected_peak_indices").map { it.number().toInt() }, peak.selectedPeakIndices)
                assertEquals(expectedPeak.string("selected_mode"), peak.selectedMode.wireValue)
                assertPeakPass(peak.primary, expectedPeak.obj("primary"))
                assertPeakPass(peak.fallback, expectedPeak.obj("fallback"))
            }
            val expectedCycles = trace.obj("cycles")
            val cycles = actual.trace.cycles
            assertEquals(expectedCycles.stringOrNull("error"), cycles.error)
            assertOptionalClose("cycle rate", cycles.rateBpm, expectedCycles.numberOrNull("rate_bpm"), 1e-10)
            assertEquals(expectedCycles.intOrNull("pre_samples"), cycles.preSamples)
            assertEquals(expectedCycles.intOrNull("post_samples"), cycles.postSamples)
            assertEquals(expectedCycles.intOrNull("window_length"), cycles.windowLength)
            assertCloseList("time axis", cycles.timeAxisSeconds, expectedCycles.array("time_axis_s").map { it.number() }, 1e-12)
            assertEquals(expectedCycles.array("cycle_valid_mask").map { it.boolean() }, cycles.cycleValidMask)
            assertEquals(expectedCycles.array("dropped_peak_indices").map { it.number().toInt() }, cycles.droppedPeakIndices)
            assertEquals(expectedCycles.array("valid_peak_indices").map { it.number().toInt() }, cycles.validPeakIndices)
            val expectedCycleArrays = expectedCycles.array("cycles")
            assertEquals(expectedCycleArrays.size, cycles.cycles.size)
            expectedCycleArrays.zip(cycles.cycles).forEach { (expectedCycle, actualCycle) ->
                assertCloseList("cycle", actualCycle, expectedCycle.arrayValues().map { it.number() }, 1e-10)
            }
            assertCloseList("template", cycles.template, expectedCycles.array("template").map { it.number() }, 1e-10)
            assertEquals(expectedCycles.array("quality_anchor_peak_indices").map { it.number().toInt() }, cycles.qualityAnchorPeakIndices)
            assertCloseList("cycle quality", cycles.cycleQuality, expectedCycles.array("cycle_quality").map { it.number() }, 1e-10)
            assertCloseList("quality trace", cycles.qualityTrace, expectedCycles.array("quality_trace").map { it.number() }, 1e-10)
        }
    }

    @Test
    fun nonFiniteAndInvalidConfigurationHaveTypedReasons() {
        val nonFinite = TemplateMatchSqi.compute(List(799) { 0.0 } + Double.POSITIVE_INFINITY)
        val invalid = TemplateMatchSqi.compute(
            List(800) { 0.0 },
            TemplateMatchSqiConfiguration.iosBaseline01.copy(sampleRateHz = 0.0),
        )
        assertEquals(TemplateMatchSqiUnavailableReason.NonFiniteInput, nonFinite.unavailableReason)
        assertEquals(TemplateMatchSqiUnavailableReason.InvalidConfiguration, invalid.unavailableReason)
        assertTrue(!nonFinite.isValid && !invalid.isValid)
    }

    private fun assertPeakPass(actual: SqiPeakPassTrace, expected: JsonValue) {
        assertClose("minimum height", actual.minimumHeight, expected.number("minimum_height"), 1e-12)
        assertClose("minimum prominence", actual.minimumProminence, expected.number("minimum_prominence"), 1e-12)
        assertEquals(expected.numberOrNull("minimum_width_samples"), actual.minimumWidthSamples)
        assertEquals(expected.array("peak_indices").map { it.number().toInt() }, actual.peaks.map { it.index })
        assertCloseList("peak heights", actual.peaks.map { it.height }, expected.array("peak_heights").map { it.number() }, 1e-10)
        assertCloseList("prominences", actual.peaks.map { it.prominence }, expected.array("prominences").map { it.number() }, 1e-10)
        assertEquals(expected.array("left_bases").map { it.number().toInt() }, actual.peaks.map { it.leftBaseIndex })
        assertEquals(expected.array("right_bases").map { it.number().toInt() }, actual.peaks.map { it.rightBaseIndex })
        assertCloseList("widths", actual.peaks.mapNotNull { it.width }, expected.array("widths").map { it.number() }, 1e-10)
        assertCloseList("width heights", actual.peaks.mapNotNull { it.widthHeight }, expected.array("width_heights").map { it.number() }, 1e-10)
        assertCloseList("left ips", actual.peaks.mapNotNull { it.leftIntersection }, expected.array("left_ips").map { it.number() }, 1e-10)
        assertCloseList("right ips", actual.peaks.mapNotNull { it.rightIntersection }, expected.array("right_ips").map { it.number() }, 1e-10)
    }

    private fun assertOptionalClose(name: String, actual: Double?, expected: Double?, tolerance: Double) {
        if (expected == null) assertNull("$name expected null", actual)
        else {
            assertTrue("$name actual null", actual != null)
            assertClose(name, actual!!, expected, tolerance)
        }
    }
    private fun assertClose(name: String, actual: Double, expected: Double, tolerance: Double) {
        assertTrue("$name expected $expected actual $actual", abs(actual - expected) <= tolerance)
    }
    private fun assertCloseList(name: String, actual: List<Double>, expected: List<Double>, tolerance: Double) {
        assertEquals("$name size", expected.size, actual.size)
        actual.zip(expected).forEachIndexed { index, (got, want) -> assertClose("$name[$index]", got, want, tolerance) }
    }
    private fun loadFixture(): JsonValue {
        val json = javaClass.classLoader!!
            .getResourceAsStream("signal_fixtures/sqi/sqi_vectors.json")
            ?.bufferedReader()
            ?.readText()
            ?: error("SQI fixture not found on test classpath")
        return SqiJsonParser(json).parse()
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
    fun objOrNull(name: String): JsonValue? = (this as ObjectValue).fields[name].let { if (it is NullValue) null else it }
    fun array(name: String): List<JsonValue> = (obj(name) as ArrayValue).values
    fun arrayValues(): List<JsonValue> = (this as ArrayValue).values
    fun string(name: String): String = (obj(name) as StringValue).value
    fun stringOrNull(name: String): String? = when (val value = obj(name)) { NullValue -> null; else -> (value as StringValue).value }
    fun number(name: String): Double = (obj(name) as NumberValue).number
    fun number(): Double = (this as NumberValue).number
    fun numberOrNull(name: String): Double? = when (val value = obj(name)) { NullValue -> null; else -> (value as NumberValue).number }
    fun intOrNull(name: String): Int? = numberOrNull(name)?.toInt()
    fun boolean(name: String): Boolean = (obj(name) as BooleanValue).boolean
    fun boolean(): Boolean = (this as BooleanValue).boolean
}

private class SqiJsonParser(private val input: String) {
    private var index = 0
    fun parse(): JsonValue { skip(); val value = value(); skip(); check(index == input.length); return value }
    private fun value(): JsonValue {
        skip()
        return when (input[index]) {
            '{' -> objectValue()
            '[' -> arrayValue()
            '"' -> JsonValue.StringValue(stringValue())
            't' -> literal("true", JsonValue.BooleanValue(true))
            'f' -> literal("false", JsonValue.BooleanValue(false))
            'n' -> literal("null", JsonValue.NullValue)
            '-', in '0'..'9' -> JsonValue.NumberValue(numberValue().toDouble())
            else -> error("invalid JSON at $index")
        }
    }
    private fun objectValue(): JsonValue.ObjectValue {
        expect('{'); skip(); val fields = LinkedHashMap<String, JsonValue>()
        if (consume('}')) return JsonValue.ObjectValue(fields)
        while (true) { skip(); val key = stringValue(); skip(); expect(':'); fields[key] = value(); skip(); if (consume('}')) break; expect(',') }
        return JsonValue.ObjectValue(fields)
    }
    private fun arrayValue(): JsonValue.ArrayValue {
        expect('['); skip(); val values = ArrayList<JsonValue>()
        if (consume(']')) return JsonValue.ArrayValue(values)
        while (true) { values += value(); skip(); if (consume(']')) break; expect(',') }
        return JsonValue.ArrayValue(values)
    }
    private fun stringValue(): String {
        expect('"'); val result = StringBuilder()
        while (true) { when (val char = input[index++]) {
            '"' -> return result.toString()
            '\\' -> when (val escaped = input[index++]) {
                '"', '\\', '/' -> result.append(escaped)
                'b' -> result.append('\b'); 'f' -> result.append('\u000C'); 'n' -> result.append('\n')
                'r' -> result.append('\r'); 't' -> result.append('\t')
                'u' -> result.append(input.substring(index, index + 4).toInt(16).toChar()).also { index += 4 }
                else -> error("invalid escape")
            }
            else -> result.append(char)
        } }
    }
    private fun numberValue(): String {
        val start = index; consume('-'); while (index < input.length && input[index].isDigit()) index++
        if (consume('.')) while (index < input.length && input[index].isDigit()) index++
        if (index < input.length && (input[index] == 'e' || input[index] == 'E')) { index++; if (index < input.length && (input[index] == '+' || input[index] == '-')) index++; while (index < input.length && input[index].isDigit()) index++ }
        return input.substring(start, index)
    }
    private fun literal(text: String, result: JsonValue): JsonValue { check(input.startsWith(text, index)); index += text.length; return result }
    private fun skip() { while (index < input.length && input[index].isWhitespace()) index++ }
    private fun consume(char: Char): Boolean = if (index < input.length && input[index] == char) { index++; true } else false
    private fun expect(char: Char) { check(consume(char)) { "expected $char at $index" } }
}
