package com.example.ppgcollector_android.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureRecordModePolicyTest {
    @Test
    fun timedDurationUsesDefaultForInvalidInputAndAcceptsBounds() {
        assertEquals(
            CaptureRecordModePolicy.defaultDurationSeconds,
            CaptureRecordModePolicy.effectiveDurationSeconds(CaptureRecordMode.TIMED, "9"),
        )
        assertEquals(
            CaptureRecordModePolicy.minimumDurationSeconds,
            CaptureRecordModePolicy.effectiveDurationSeconds(
                CaptureRecordMode.TIMED,
                CaptureRecordModePolicy.minimumDurationSeconds.toString(),
            ),
        )
        assertEquals(
            CaptureRecordModePolicy.maximumDurationSeconds,
            CaptureRecordModePolicy.effectiveDurationSeconds(
                CaptureRecordMode.TIMED,
                CaptureRecordModePolicy.maximumDurationSeconds.toString(),
            ),
        )
    }

    @Test
    fun manualModeHasNoPlanAndCountdownPausesWithAcceptedSamples() {
        assertNull(CaptureRecordModePolicy.effectiveDurationSeconds(CaptureRecordMode.MANUAL, "60"))
        assertEquals(
            8,
            CaptureRecordModePolicy.remainingSeconds(
                CaptureRecordMode.TIMED,
                plannedDurationSeconds = 10,
                acceptedSampleCount = 200,
                sampleRateHz = 100,
            ),
        )
    }
}
