package com.example.ppgcollector_android.data.session

import com.example.ppgcollector_android.core.ble.BleConnectionPhase
import com.example.ppgcollector_android.core.signal.StreamFreshness
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStartGateTest {
    @Test
    fun collectsOperationalAndParticipantReasonsWithoutBlockingOnOptionalBloodPressure() {
        val root = Files.createTempDirectory("capture-gate")
        try {
            val failures = CaptureStartGate.validateAll(
                CaptureStartContext(
                    isRecording = false,
                    phase = BleConnectionPhase.Idle,
                    freshness = StreamFreshness.WAITING,
                    sessionsRoot = root,
                    sessionName = "PPG-S001-1",
                    availableBytes = null,
                    participant = CaptureParticipantDraft(
                        systolicBp = "120",
                        diastolicBp = "80",
                    ),
                ),
            )
            assertTrue(CaptureStartFailure.StreamNotFresh in failures)
            assertTrue(CaptureStartFailure.DeviceNotReady in failures)
            assertTrue(failures.any { it is CaptureStartFailure.ParticipantIncomplete })
            assertTrue(failures.none { it is CaptureStartFailure.InvalidSessionName })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun optionalBloodPressureCanBeEmptyOrValid() {
        val draft = CaptureParticipantDraft(
            sex = "男",
            ageYears = "30",
            heightCm = "175",
            weightKg = "70",
        )
        assertEquals(null, bloodPressureValidationError("", ""))
        assertEquals(null, bloodPressureValidationError("120", "80"))
        assertTrue(draft.validationErrors().none { it.contains("血压") })
    }
}
