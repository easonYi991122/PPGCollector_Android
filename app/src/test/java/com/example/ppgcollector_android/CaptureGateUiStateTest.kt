package com.example.ppgcollector_android

import com.example.ppgcollector_android.data.session.CaptureStartFailure
import com.example.ppgcollector_android.data.session.CaptureNotificationPermissionPolicy
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

    @Test
    fun serviceStartPolicyFailuresAreActionableAndDoNotLookLikeDeviceLoss() {
        assertEquals(
            CaptureStartFailure.ForegroundServiceStartRejected,
            mapCaptureServiceStartFailure(SecurityException()),
        )
        assertEquals(
            "系统拒绝启动录制服务，请从前台页面重试并检查服务权限",
            CaptureGateUiState(
                "session_001",
                CaptureStartFailure.ForegroundServiceStartRejected,
            ).message,
        )
        assertEquals(
            CaptureStartFailure.DeviceNotReady,
            mapCaptureServiceStartFailure(IllegalStateException("not connected")),
        )
    }

    @Test
    fun serviceRuntimeStartFailureRemainsAnActionableCaptureGateFailure() {
        val observation = CaptureServiceObservation(
            runtimeFailure = CaptureStartFailure.ForegroundServiceStartRejected,
        )

        assertEquals(
            "系统拒绝启动录制服务，请从前台页面重试并检查服务权限",
            CaptureGateUiState("session_001", observation.runtimeFailure).message,
        )
        assertFalse(CaptureGateUiState("session_001", observation.runtimeFailure).canStart)
    }

    @Test
    fun notificationPermissionIsOnlyAStartGateFromApi33() {
        assertFalse(CaptureNotificationPermissionPolicy.isRuntimePermissionRequired(32))
        assertTrue(CaptureNotificationPermissionPolicy.isRuntimePermissionRequired(33))
        assertEquals(
            null,
            CaptureNotificationPermissionPolicy.failureFor(32, granted = false),
        )
        assertEquals(
            CaptureStartFailure.NotificationPermissionDenied,
            CaptureNotificationPermissionPolicy.failureFor(33, granted = false),
        )
        assertEquals(
            "通知权限未授予，请允许通知后再开始录制，否则持续采集状态可能无法显示",
            CaptureGateUiState(
                "session_001",
                CaptureStartFailure.NotificationPermissionDenied,
            ).message,
        )
    }
}
