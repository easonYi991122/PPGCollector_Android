package com.example.ppgcollector_android

import android.app.Application
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.example.ppgcollector_android.core.ble.AndroidBleTransport
import com.example.ppgcollector_android.core.ble.BleCoordinator
import com.example.ppgcollector_android.data.session.CaptureRecordingSnapshot
import com.example.ppgcollector_android.data.session.CaptureSessionAccessRegistry
import com.example.ppgcollector_android.data.session.CaptureSessionRepository
import com.example.ppgcollector_android.data.session.StoredCaptureSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PpgCollectorApplication : Application() {
    val sessionAccessRegistry = CaptureSessionAccessRegistry.app
    /** Last terminal event survives Activity unbinding while the service finishes. */
    private val _captureTerminal = MutableStateFlow<CaptureRecordingSnapshot?>(null)
    val captureTerminal: StateFlow<CaptureRecordingSnapshot?> = _captureTerminal.asStateFlow()

    fun recordCaptureTerminal(snapshot: CaptureRecordingSnapshot) {
        _captureTerminal.value = snapshot
    }

    val sessionsRoot
        get() = filesDir.toPath().resolve("sessions")

    /** Versioned subject profiles live beside sessions but never inside a capture directory. */
    val subjectsRoot
        get() = filesDir.toPath().resolve("subjects")

    val bleCoordinator: BleCoordinator by lazy {
        val bleThread = HandlerThread("ppg-ble-transport").apply { start() }
        val bleHandler = Handler(bleThread.looper)
        BleCoordinator(
            transport = AndroidBleTransport(
                context = applicationContext,
                mainHandler = bleHandler,
                monotonicNanos = SystemClock::elapsedRealtimeNanos,
            ),
            apiLevel = android.os.Build.VERSION.SDK_INT,
            uptimeSeconds = {
                SystemClock.elapsedRealtimeNanos().toDouble() / 1_000_000_000.0
            },
            hostMonotonicNanos = SystemClock::elapsedRealtimeNanos,
            ownerDispatcher = { action -> action() },
        )
    }

    /** Filesystem-backed startup/recovery discovery; inspection remains read-only. */
    fun incompleteSessions(): List<StoredCaptureSession> =
        CaptureSessionRepository.incompleteSessions(sessionsRoot)
}
