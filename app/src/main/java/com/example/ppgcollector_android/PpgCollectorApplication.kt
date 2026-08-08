package com.example.ppgcollector_android

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.example.ppgcollector_android.core.ble.AndroidBleTransport
import com.example.ppgcollector_android.core.ble.BleCoordinator
import com.example.ppgcollector_android.data.session.CaptureSessionRepository
import com.example.ppgcollector_android.data.session.StoredCaptureSession

class PpgCollectorApplication : Application() {
    val sessionsRoot
        get() = filesDir.toPath().resolve("sessions")

    /** Versioned subject profiles live beside sessions but never inside a capture directory. */
    val subjectsRoot
        get() = filesDir.toPath().resolve("subjects")

    val bleCoordinator: BleCoordinator by lazy {
        val mainHandler = Handler(Looper.getMainLooper())
        BleCoordinator(
            transport = AndroidBleTransport(
                context = applicationContext,
                monotonicNanos = SystemClock::elapsedRealtimeNanos,
            ),
            apiLevel = android.os.Build.VERSION.SDK_INT,
            uptimeSeconds = {
                SystemClock.elapsedRealtimeNanos().toDouble() / 1_000_000_000.0
            },
            hostMonotonicNanos = SystemClock::elapsedRealtimeNanos,
            ownerDispatcher = { action ->
                if (Looper.myLooper() == mainHandler.looper) {
                    action()
                } else {
                    mainHandler.post(action)
                }
            },
        )
    }

    /** Filesystem-backed startup/recovery discovery; inspection remains read-only. */
    fun incompleteSessions(): List<StoredCaptureSession> =
        CaptureSessionRepository.incompleteSessions(sessionsRoot)
}
