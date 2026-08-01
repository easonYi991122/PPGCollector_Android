package com.example.ppgcollector_android.core.signal

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PpgPreprocessingTest {
    @Test
    fun frozenProfileAndAllFixtureArraysMatchSwiftContract() {
        val fixture = loadFixture()
        val profile = PpgPreprocessingProfile.iosBaseline01
        val config = fixture.objectValue("config")

        assertEquals("cup.preprocessing.parity.v1", fixture.stringValue("schema"))
        assertEquals(profile.identifier, fixture.stringValue("profile"))
        assertEquals(100.0, config.numberValue("sample_rate_hz"), 0.0)
        assertEquals(profile.dcAlpha, config.numberValue("dc_alpha"), 0.0)
        assertEquals(profile.sections.size, config.arrayValue("sos").size)
        assertEquals("invert", config.stringValue("polarity_transform"))

        config.arrayValue("sos").forEachIndexed { index, sectionValue ->
            val expected = profile.sections[index]
            val actual = sectionValue.arrayValues().map { it.numberValue() }
            val coefficients = listOf(
                expected.b0, expected.b1, expected.b2,
                expected.a0, expected.a1, expected.a2,
            )
            coefficients.zip(actual).forEach { (want, got) ->
                assertClose(want, got, 1e-15)
            }
        }

        fixture.arrayValue("cases").forEach { case ->
            val name = case.stringValue("name")
            val raw = case.arrayValue("raw").map { it.numberValue() }
            val resetIndices = case.arrayValue("reset_indices")
                .map { it.numberValue().toInt() }
            val expected = case.objectValue("expected")
            val expectedDc = expected.arrayValue("dc").map { it.numberValue() }
            val expectedAc = expected.arrayValue("ac").map { it.numberValue() }
            val expectedBandpassed = expected.arrayValue("bandpassed")
                .map { it.numberValue() }
            val expectedPeakUp = expected.arrayValue("peak_up")
                .map { it.numberValue() }

            val preprocessor = PpgPreprocessor()
            val results = raw.mapIndexed { index, value ->
                preprocessor.process(
                    raw = value,
                    boundary = if (index in resetIndices) {
                        PpgStreamBoundary.GAP
                    } else {
                        PpgStreamBoundary.CONTINUOUS
                    },
                )
            }
            val samples = results.mapNotNull { it.sample }
            assertEquals("$name reset count", resetIndices.size,
                samples.count { it.didResetAtBoundary })
            assertEquals("$name valid count", raw.size, samples.size)
            assertCloseList("$name dc", samples.map { it.dc }, expectedDc, 1e-8)
            assertCloseList("$name ac", samples.map { it.ac }, expectedAc, 1e-8)
            assertCloseList(
                "$name bandpassed",
                samples.map { it.bandpassed },
                expectedBandpassed,
                1e-8,
            )

            val normalized = PpgWindowNormalizer.normalize(
                samples.map { it.bandpassed },
            )
            val expectedNormalization = expected.objectValue("normalization")
            assertEquals(
                "$name normalization validity",
                expectedNormalization.booleanValue("valid"),
                normalized.isValid,
            )
            val expectedReason = expectedNormalization.nullableStringValue("reason")
            assertEquals(expectedReason, normalized.unavailableReason?.wireValue)
            assertCloseList(
                "$name peak_up",
                normalized.polarityAdjustedValues,
                expectedPeakUp,
                1e-8,
            )
            if (expectedNormalization.has("values")) {
                val expectedValues = expectedNormalization.nullableArrayValue("values")
                if (expectedValues != null) {
                    assertCloseList(
                        "$name normalized",
                        normalized.normalizedValues!!,
                        expectedValues.map { it.numberValue() },
                        1e-8,
                    )
                } else {
                    assertNull(normalized.normalizedValues)
                }
            }
            if (expectedNormalization.has("mean")) {
                val expectedMean = expectedNormalization.nullableNumberValue("mean")
                if (expectedMean == null) assertNull(normalized.mean)
                else assertClose(expectedMean, normalized.mean!!, 1e-8)
            }
            if (expectedNormalization.has("standard_deviation")) {
                val expectedStd = expectedNormalization.nullableNumberValue("standard_deviation")
                if (expectedStd == null) assertNull(normalized.standardDeviation)
                else assertClose(expectedStd, normalized.standardDeviation!!, 1e-8)
            }
        }
    }

    @Test
    fun chunkingAndGapResetPreserveCausalState() {
        val fixture = loadFixture()
        val testCase = fixture.arrayValue("cases").first {
            it.stringValue("name") == "pulse_down_8s"
        }
        val raw = testCase.arrayValue("raw").map { it.numberValue() }
        val oneShot = PpgPreprocessor().process(raw).map { it.sample!!.bandpassed }
        val chunkedProcessor = PpgPreprocessor()
        val actual = ArrayList<Double>()
        var offset = 0
        listOf(1, 7, 50, 3, 111, 2, 244, 17, 365).forEach { requested ->
            if (offset >= raw.size) return@forEach
            val end = minOf(raw.size, offset + requested)
            actual += chunkedProcessor.process(raw.subList(offset, end))
                .map { it.sample!!.bandpassed }
            offset = end
        }
        if (offset < raw.size) {
            actual += chunkedProcessor.process(raw.subList(offset, raw.size))
                .map { it.sample!!.bandpassed }
        }
        assertCloseList("chunked", actual, oneShot, 0.0)

        val gapCase = fixture.arrayValue("cases").first {
            it.stringValue("name") == "gap_reset_5s"
        }
        val gapRaw = gapCase.arrayValue("raw").map { it.numberValue() }
        val resetIndex = gapCase.arrayValue("reset_indices").single().numberValue().toInt()
        // Verify the explicit boundary path against a fresh processor suffix.
        val withExplicitGap = PpgPreprocessor().let { processor ->
            gapRaw.mapIndexed { index, value ->
                processor.process(
                    value,
                    if (index == resetIndex) PpgStreamBoundary.GAP
                    else PpgStreamBoundary.CONTINUOUS,
                ).sample!!.bandpassed
            }
        }
        val freshSuffix = PpgPreprocessor()
            .process(gapRaw.subList(resetIndex, gapRaw.size))
            .map { it.sample!!.bandpassed }
        assertCloseList(
            "gap suffix",
            withExplicitGap.subList(resetIndex, withExplicitGap.size),
            freshSuffix,
            0.0,
        )
        assertEquals(0.0, withExplicitGap[resetIndex], 0.0)
    }

    @Test
    fun invalidAndConstantInputsHaveExplicitReasonsAndResetState() {
        var processor = PpgPreprocessor()
        processor.process(500_000.0)
        processor.process(501_000.0)
        val invalid = processor.process(Double.NaN)
        assertFalse(invalid.isValid)
        assertNull(invalid.sample)
        assertEquals(
            PpgPreprocessingUnavailableReason.NON_FINITE_INPUT,
            invalid.unavailableReason,
        )
        val restarted = processor.process(520_000.0).sample!!
        assertEquals(520_000.0, restarted.dc, 0.0)
        assertEquals(0.0, restarted.ac, 0.0)
        assertEquals(0.0, restarted.bandpassed, 0.0)

        val empty = PpgWindowNormalizer.normalize(emptyList())
        val nonFinite = PpgWindowNormalizer.normalize(listOf(1.0, Double.POSITIVE_INFINITY))
        val constant = PpgWindowNormalizer.normalize(List(10) { 5.0 })
        assertEquals(PpgPreprocessingUnavailableReason.EMPTY_WINDOW, empty.unavailableReason)
        assertEquals(PpgPreprocessingUnavailableReason.NON_FINITE_INPUT, nonFinite.unavailableReason)
        assertEquals(PpgPreprocessingUnavailableReason.CONSTANT_SIGNAL, constant.unavailableReason)
        assertNotNull(constant.mean)
    }

    private fun assertCloseList(name: String, actual: List<Double>, expected: List<Double>, tolerance: Double) {
        assertEquals("$name size", expected.size, actual.size)
        actual.zip(expected).forEachIndexed { index, (got, want) ->
            if (tolerance == 0.0) assertEquals("$name[$index]", want, got, 0.0)
            else assertClose("$name[$index]", want, got, tolerance)
        }
    }

    private fun assertClose(expected: Double, actual: Double, tolerance: Double) {
        assertTrue(
            "expected $expected, actual $actual, delta ${abs(expected - actual)} > $tolerance",
            abs(expected - actual) <= tolerance,
        )
    }

    private fun assertClose(name: String, expected: Double, actual: Double, tolerance: Double) {
        assertTrue(
            "$name: expected $expected, actual $actual, delta ${abs(expected - actual)} > $tolerance",
            abs(expected - actual) <= tolerance,
        )
    }

    private fun loadFixture(): JsonValue.ObjectValue {
        val candidates = listOf(
            Paths.get("AndroidMigrationPlanning/reference_sources/signal_fixtures/preprocessing/preprocessing_vectors.json"),
            Paths.get("../AndroidMigrationPlanning/reference_sources/signal_fixtures/preprocessing/preprocessing_vectors.json"),
        )
        val path = candidates.firstOrNull { Files.exists(it) }
            ?: error("preprocessing fixture not found from ${Paths.get("").toAbsolutePath()}")
        return JsonParser(Files.readString(path)).parse().objectValue()
    }
}

private sealed interface JsonValue {
    data class ObjectValue(val fields: Map<String, JsonValue>) : JsonValue
    data class ArrayValue(val values: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class NumberValue(val number: Double) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object NullValue : JsonValue

    fun objectValue(): ObjectValue = this as ObjectValue
    fun arrayValues(): List<JsonValue> = (this as ArrayValue).values
    fun objectValue(name: String): ObjectValue = (this as ObjectValue).fields.getValue(name).objectValue()
    fun arrayValue(name: String): List<JsonValue> = (this as ObjectValue).fields.getValue(name).arrayValues()
    fun stringValue(name: String): String = (this as ObjectValue).fields.getValue(name).stringValue()
    fun stringValue(): String = (this as StringValue).value
    fun numberValue(name: String): Double = (this as ObjectValue).fields.getValue(name).numberValue()
    fun numberValue(): Double = (this as NumberValue).number
    fun booleanValue(name: String): Boolean = (this as ObjectValue).fields.getValue(name).booleanValue()
    fun booleanValue(): Boolean = (this as BooleanValue).value
    fun nullableStringValue(name: String): String? = when (val value = (this as ObjectValue).fields.getValue(name)) {
        JsonValue.NullValue -> null
        else -> value.stringValue()
    }
    fun nullableNumberValue(name: String): Double? = when (val value = (this as ObjectValue).fields.getValue(name)) {
        JsonValue.NullValue -> null
        else -> value.numberValue()
    }
    fun nullableArrayValue(name: String): List<JsonValue>? = when (val value = (this as ObjectValue).fields.getValue(name)) {
        JsonValue.NullValue -> null
        else -> value.arrayValues()
    }
    fun has(name: String): Boolean = (this as ObjectValue).fields.containsKey(name)
}

private class JsonParser(private val input: String) {
    private var index = 0

    fun parse(): JsonValue {
        skipWhitespace()
        val value = parseValue()
        skipWhitespace()
        check(index == input.length) { "trailing JSON at $index" }
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
            else -> error("unexpected JSON at $index")
        }
    }

    private fun parseObject(): JsonValue.ObjectValue {
        expect('{')
        skipWhitespace()
        val fields = LinkedHashMap<String, JsonValue>()
        if (consume('}')) return JsonValue.ObjectValue(fields)
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace(); expect(':')
            fields[key] = parseValue()
            skipWhitespace()
            if (consume('}')) break
            expect(',')
        }
        return JsonValue.ObjectValue(fields)
    }

    private fun parseArray(): JsonValue.ArrayValue {
        expect('[')
        skipWhitespace()
        val values = ArrayList<JsonValue>()
        if (consume(']')) return JsonValue.ArrayValue(values)
        while (true) {
            values += parseValue()
            skipWhitespace()
            if (consume(']')) break
            expect(',')
        }
        return JsonValue.ArrayValue(values)
    }

    private fun parseString(): String {
        expect('"')
        val result = StringBuilder()
        while (true) {
            val character = input[index++]
            when (character) {
                '"' -> return result.toString()
                '\\' -> when (val escaped = input[index++]) {
                    '"', '\\', '/' -> result.append(escaped)
                    'b' -> result.append('\b')
                    'f' -> result.append('\u000C')
                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    'u' -> result.append(input.substring(index, index + 4).toInt(16).toChar()).also { index += 4 }
                    else -> error("bad escape at $index")
                }
                else -> result.append(character)
            }
        }
    }

    private fun parseNumber(): String {
        val start = index
        if (consume('-')) Unit
        while (index < input.length && input[index].isDigit()) index += 1
        if (consume('.')) while (index < input.length && input[index].isDigit()) index += 1
        if (index < input.length && (input[index] == 'e' || input[index] == 'E')) {
            index += 1
            if (index < input.length && (input[index] == '+' || input[index] == '-')) index += 1
            while (index < input.length && input[index].isDigit()) index += 1
        }
        return input.substring(start, index)
    }

    private fun literal(text: String, value: JsonValue): JsonValue {
        check(input.startsWith(text, index)) { "expected $text at $index" }
        index += text.length
        return value
    }

    private fun skipWhitespace() {
        while (index < input.length && input[index].isWhitespace()) index += 1
    }

    private fun consume(character: Char): Boolean {
        if (index < input.length && input[index] == character) {
            index += 1
            return true
        }
        return false
    }

    private fun expect(character: Char) {
        check(consume(character)) { "expected $character at $index" }
    }
}
