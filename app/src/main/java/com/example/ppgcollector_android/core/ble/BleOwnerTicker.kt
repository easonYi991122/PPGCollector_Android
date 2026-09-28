package com.example.ppgcollector_android.core.ble

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Starts a cancellable clock independent of notification and preview workers. */
fun interface BleOwnerTicker {
    fun start(tick: () -> Unit): AutoCloseable

    companion object {
        val system = BleOwnerTicker { tick ->
            val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "ppg-ble-owner-clock").apply { isDaemon = true }
            }
            executor.scheduleAtFixedRate(tick, 250, 250, TimeUnit.MILLISECONDS)
            AutoCloseable { executor.shutdownNow() }
        }
    }
}
