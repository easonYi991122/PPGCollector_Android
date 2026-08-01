package com.example.ppgcollector_android

import android.app.Application
import com.example.ppgcollector_android.core.ble.AndroidBleTransport
import com.example.ppgcollector_android.core.ble.BleCoordinator
import com.example.ppgcollector_android.data.session.CaptureSessionRepository
import com.example.ppgcollector_android.data.session.StoredCaptureSession

class PpgCollectorApplication : Application() {
    val sessionsRoot
        get() = filesDir.toPath().resolve("sessions")

    val bleCoordinator: BleCoordinator by lazy {
        BleCoordinator(
            transport = AndroidBleTransport(applicationContext),
            apiLevel = android.os.Build.VERSION.SDK_INT,
        )
    }

    /** Filesystem-backed startup/recovery discovery; inspection remains read-only. */
    fun incompleteSessions(): List<StoredCaptureSession> =
        CaptureSessionRepository.incompleteSessions(sessionsRoot)
}
