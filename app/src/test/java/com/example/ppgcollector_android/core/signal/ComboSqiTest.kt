package com.example.ppgcollector_android.core.signal

import com.example.ppgcollector_android.core.signal.combo.ComboSqi
import com.example.ppgcollector_android.core.signal.combo.ComboSqiDebounce
import com.example.ppgcollector_android.core.signal.combo.ComboSqiInputs
import com.example.ppgcollector_android.core.signal.combo.ComboSqiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

        val good = ComboSqi.arbitrate(
            ComboSqiInputs(false, 0.7, 0.9, null, null, null, 4),
        )
        assertEquals(ComboSqiState.GOOD, good.state)
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
}
