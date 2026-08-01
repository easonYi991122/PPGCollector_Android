package com.example.ppgcollector_android

import android.app.Application
import com.example.ppgcollector_android.core.ble.AndroidBleTransport
import com.example.ppgcollector_android.core.ble.BleCoordinator

class PpgCollectorApplication : Application() {
    val bleCoordinator: BleCoordinator by lazy {
        BleCoordinator(
            transport = AndroidBleTransport(applicationContext),
            apiLevel = android.os.Build.VERSION.SDK_INT,
        )
    }
}
