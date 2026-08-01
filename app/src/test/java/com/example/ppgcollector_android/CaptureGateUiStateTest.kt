package com.example.ppgcollector_android

import com.example.ppgcollector_android.data.session.CaptureStartFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureGateUiStateTest {
    @Test
    fun invalidNameIsDisabledAndExplainsTheGate() {
        val state = CaptureGateUiState()

        assertFalse(state.canStart)
        assertEquals("录制名只能包含字母、数字、下划线和短横线", state.message)
    }

    @Test
    fun validGateEnablesStartWithoutInventingAStatusMessage() {
        val state = CaptureGateUiState("session_001", failure = null)

        assertTrue(state.canStart)
        assertEquals(null, state.message)
    }

    @Test
    fun eachOperationalFailureRemainsVisibleToTheUi() {
        assertEquals(
            "等待新鲜数据流",
            CaptureGateUiState("session_001", CaptureStartFailure.StreamNotFresh).message,
        )
        assertEquals(
            "可用存储不足",
            CaptureGateUiState("session_001", CaptureStartFailure.InsufficientStorage).message,
        )
    }
}
