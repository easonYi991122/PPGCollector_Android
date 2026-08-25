package com.example.ppgcollector_android.core.signal

import com.example.ppgcollector_android.core.signal.combo.ComboSqi
import com.example.ppgcollector_android.core.signal.combo.ComboSqiDebounce
import com.example.ppgcollector_android.core.signal.combo.ComboSqiInputs
import com.example.ppgcollector_android.core.signal.combo.ComboSqiState
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComboSqiTest {
    @Test
    fun arbitrationPrioritizesFlatThenPressureThenTemplateScore() {
        val flat = ComboSqi.arbitrate(
            ComboSqiInputs(
                flat = true,
                sqiCorrelation = 0.1,
                templateMatch = 0.99,
                pressureSeverity = 1.0,
                pressureHeight = 0.1,
                initialSteepness = 8.0,
                beatCount = 4,
            ),
        )
        assertEquals(ComboSqiState.FLAT, flat.state)
        assertEquals(0.0, flat.score)

        val pressure = ComboSqi.arbitrate(
            ComboSqiInputs(
                flat = false,
                sqiCorrelation = 0.7,
                templateMatch = 0.99,
                pressureSeverity = 0.8,
                pressureHeight = 0.2,
                initialSteepness = 5.0,
                beatCount = 4,
            ),
        )
        assertEquals(ComboSqiState.PRESSURE, pressure.state)
        assertTrue(pressure.text.contains("严重"))

        val good = ComboSqi.arbitrate(
            ComboSqiInputs(false, 0.7, 0.9, null, null, null, 4),
        )
        assertEquals(ComboSqiState.GOOD, good.state)
    }

    @Test
    fun pressureGatesRequireHeightSteepnessSeverityAndBeats() {
        val missingHeight = ComboSqi.arbitrate(
            ComboSqiInputs(false, 0.7, 0.99, 0.8, 0.9, 5.0, 4),
        )
        assertEquals(ComboSqiState.GOOD, missingHeight.state)

        val missingSteep = ComboSqi.arbitrate(
            ComboSqiInputs(false, 0.7, 0.99, 0.8, 0.2, 2.0, 4),
        )
        assertEquals(ComboSqiState.GOOD, missingSteep.state)
    }

    @Test
    fun stateTransitionsRequireTwoMetricFramesAndResetToUnknown() {
        val debounce = ComboSqiDebounce()
        val good = ComboSqi.arbitrate(ComboSqiInputs(false, 0.7, 0.9, null, null, null, 4))
        assertEquals(ComboSqiState.UNKNOWN, debounce.update(good).state)
        assertEquals(ComboSqiState.GOOD, debounce.update(good).state)
        debounce.reset()
        assertEquals(ComboSqiState.UNKNOWN, debounce.current.state)
        assertNull(ComboSqi.unknown().score)
    }

    @Test
    fun constantIrEvaluatesToFlat() {
        val result = ComboSqi.evaluate(List(500) { 50_000.0 })
        assertEquals(ComboSqiState.FLAT, result.state)
        assertEquals("⚠ 信号平直 (0.00)", result.text)
        assertEquals(ComboSqi.poorColor, result.colorHex)
        assertEquals(0.0, result.score)
    }

    @Test
    fun pythonSelftestWavesMatchComboSqiStates() {
        val goldens = loadPythonGoldens()
        assertTrue(goldens.size >= 3)
        goldens.forEach { golden ->
            val result = ComboSqi.evaluate(golden.signal)
            val inputs = ComboSqi.computeInputs(golden.signal)!!
            assertEquals("${golden.name} state", golden.state, result.state.name.lowercase())
            assertEquals("${golden.name} text", golden.text, result.text)
            assertEquals("${golden.name} color", golden.color, result.colorHex)
            if (golden.score == null) {
                assertNull(golden.name, result.score)
            } else {
                assertTrue(
                    "${golden.name} score expected ${golden.score} actual ${result.score}",
                    abs((result.score ?: Double.NaN) - golden.score) <= 0.02,
                )
            }
            assertEquals("${golden.name} flat", golden.flat, inputs.flat)
            if (golden.sqiTm != null && inputs.templateMatch != null) {
                assertTrue(
                    "${golden.name} sqi_tm expected ${golden.sqiTm} actual ${inputs.templateMatch}",
                    abs(inputs.templateMatch - golden.sqiTm) <= 0.05,
                )
            }
            if (golden.sqiCorr != null && inputs.sqiCorrelation != null) {
                assertTrue(
                    "${golden.name} sqi_corr expected ${golden.sqiCorr} actual ${inputs.sqiCorrelation}",
                    abs(inputs.sqiCorrelation - golden.sqiCorr) <= 0.05,
                )
            }
            assertNotEquals(
                "${golden.name} must not use the old constant p2_height=0.5 placeholder",
                0.5,
                inputs.pressureHeight,
            )
        }
    }

    private data class PythonGolden(
        val name: String,
        val state: String,
        val text: String,
        val color: String,
        val score: Double?,
        val flat: Boolean?,
        val sqiTm: Double?,
        val sqiCorr: Double?,
        val signal: List<Double>,
    )

    private fun loadPythonGoldens(): List<PythonGolden> {
        val json = javaClass.classLoader!!
            .getResourceAsStream("combo_sqi_python_goldens.json")
            ?.bufferedReader()
            ?.readText()
            ?: error("combo SQI Python goldens missing from test classpath")
        val root = ComboJsonParser(json).parse() as ComboJson.ArrayValue
        return root.values.map { item ->
            val obj = item as ComboJson.ObjectValue
            PythonGolden(
                name = obj.string("name"),
                state = obj.string("state"),
                text = obj.string("text"),
                color = obj.string("color"),
                score = obj.numberOrNull("score"),
                flat = obj.booleanOrNull("flat"),
                sqiTm = obj.numberOrNull("sqi_tm"),
                sqiCorr = obj.numberOrNull("sqi_corr"),
                signal = obj.array("signal").map { (it as ComboJson.NumberValue).number },
            )
        }
    }
}

private sealed interface ComboJson {
    data class ObjectValue(val fields: Map<String, ComboJson>) : ComboJson {
        fun obj(name: String) = fields.getValue(name)
        fun string(name: String) = (obj(name) as StringValue).text
        fun array(name: String) = (obj(name) as ArrayValue).values
        fun numberOrNull(name: String): Double? = when (val value = fields[name]) {
            null, NullValue -> null
            is NumberValue -> value.number
            else -> error(name)
        }
        fun booleanOrNull(name: String): Boolean? = when (val value = fields[name]) {
            null, NullValue -> null
            is BooleanValue -> value.boolean
            else -> error(name)
        }
    }
    data class ArrayValue(val values: List<ComboJson>) : ComboJson
    data class StringValue(val text: String) : ComboJson
    data class NumberValue(val number: Double) : ComboJson
    data class BooleanValue(val boolean: Boolean) : ComboJson
    data object NullValue : ComboJson
}

private class ComboJsonParser(private val input: String) {
    private var index = 0
    fun parse(): ComboJson {
        skip()
        val value = value()
        skip()
        check(index == input.length)
        return value
    }

    private fun value(): ComboJson {
        skip()
        return when (input[index]) {
            '{' -> objectValue()
            '[' -> arrayValue()
            '"' -> ComboJson.StringValue(stringValue())
            't' -> literal("true", ComboJson.BooleanValue(true))
            'f' -> literal("false", ComboJson.BooleanValue(false))
            'n' -> literal("null", ComboJson.NullValue)
            '-', in '0'..'9' -> ComboJson.NumberValue(numberValue().toDouble())
            else -> error("invalid JSON at $index")
        }
    }

    private fun objectValue(): ComboJson.ObjectValue {
        expect('{')
        skip()
        val fields = LinkedHashMap<String, ComboJson>()
        if (consume('}')) return ComboJson.ObjectValue(fields)
        while (true) {
            skip()
            val key = stringValue()
            skip()
            expect(':')
            fields[key] = value()
            skip()
            if (consume('}')) break
            expect(',')
        }
        return ComboJson.ObjectValue(fields)
    }

    private fun arrayValue(): ComboJson.ArrayValue {
        expect('[')
        skip()
        val values = ArrayList<ComboJson>()
        if (consume(']')) return ComboJson.ArrayValue(values)
        while (true) {
            values += value()
            skip()
            if (consume(']')) break
            expect(',')
        }
        return ComboJson.ArrayValue(values)
    }

    private fun stringValue(): String {
        expect('"')
        val result = StringBuilder()
        while (true) {
            when (val char = input[index++]) {
                '"' -> return result.toString()
                '\\' -> when (val escaped = input[index++]) {
                    '"', '\\', '/' -> result.append(escaped)
                    'b' -> result.append('\b')
                    'f' -> result.append('\u000C')
                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    'u' -> {
                        result.append(input.substring(index, index + 4).toInt(16).toChar())
                        index += 4
                    }
                    else -> error("invalid escape")
                }
                else -> result.append(char)
            }
        }
    }

    private fun numberValue(): String {
        val start = index
        consume('-')
        while (index < input.length && input[index].isDigit()) index++
        if (consume('.')) while (index < input.length && input[index].isDigit()) index++
        if (index < input.length && (input[index] == 'e' || input[index] == 'E')) {
            index++
            if (index < input.length && (input[index] == '+' || input[index] == '-')) index++
            while (index < input.length && input[index].isDigit()) index++
        }
        return input.substring(start, index)
    }

    private fun literal(text: String, result: ComboJson): ComboJson {
        check(input.startsWith(text, index))
        index += text.length
        return result
    }

    private fun skip() {
        while (index < input.length && input[index].isWhitespace()) index++
    }

    private fun consume(char: Char): Boolean =
        if (index < input.length && input[index] == char) {
            index++
            true
        } else {
            false
        }

    private fun expect(char: Char) {
        check(consume(char)) { "expected $char at $index" }
    }
}
