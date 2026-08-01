package com.example.ppgcollector_android.core.ble

import com.example.ppgcollector_android.core.signal.StreamFreshness

class CupStreamFreshnessTracker(
    val timeoutSeconds: Double,
) {
    init {
        require(timeoutSeconds.isFinite() && timeoutSeconds > 0.0)
    }

    private var subscriptionStartedUptime: Double? = null
    private var lastValidFrameUptime: Double? = null

    fun start(atUptimeSeconds: Double) {
        subscriptionStartedUptime = atUptimeSeconds
        lastValidFrameUptime = null
    }

    fun observeValidFrame(atUptimeSeconds: Double) {
        if (subscriptionStartedUptime != null) lastValidFrameUptime = atUptimeSeconds
    }

    fun reset() {
        subscriptionStartedUptime = null
        lastValidFrameUptime = null
    }

    fun freshness(atUptimeSeconds: Double): StreamFreshness {
        val started = subscriptionStartedUptime ?: return StreamFreshness.UNAVAILABLE
        val lastFrame = lastValidFrameUptime
        return if (lastFrame != null) {
            if (atUptimeSeconds - lastFrame <= timeoutSeconds) StreamFreshness.FRESH
            else StreamFreshness.STALE
        } else if (atUptimeSeconds - started <= timeoutSeconds) {
            StreamFreshness.WAITING
        } else {
            StreamFreshness.STALE
        }
    }
}
