package com.example.ppgcollector_android.core.ble

import kotlin.math.max

enum class BleConnectionOperation(val title: String) {
    CONNECT("连接设备"),
    SERVICE_DISCOVERY("发现服务"),
    CHARACTERISTIC_DISCOVERY("发现特征"),
    NOTIFICATION_SUBSCRIPTION("订阅通知"),
}

data class BleConnectionTimeoutPolicy(
    val connectSeconds: Double,
    val serviceDiscoverySeconds: Double,
    val characteristicDiscoverySeconds: Double,
    val notificationSubscriptionSeconds: Double,
) {
    companion object {
        val iosDefault = BleConnectionTimeoutPolicy(12.0, 8.0, 8.0, 8.0)
    }

    fun timeout(operation: BleConnectionOperation): Double = max(
        0.1,
        when (operation) {
            BleConnectionOperation.CONNECT -> connectSeconds
            BleConnectionOperation.SERVICE_DISCOVERY -> serviceDiscoverySeconds
            BleConnectionOperation.CHARACTERISTIC_DISCOVERY -> characteristicDiscoverySeconds
            BleConnectionOperation.NOTIFICATION_SUBSCRIPTION -> notificationSubscriptionSeconds
        },
    )
}

data class BleConnectionDeadline(
    val operation: BleConnectionOperation,
    val deviceId: String,
    val generation: Long,
    val deadlineUptimeSeconds: Double,
)

class BleConnectionDeadlineTracker {
    var generation: Long = 0
        private set
    var activeDeadline: BleConnectionDeadline? = null
        private set

    fun arm(
        operation: BleConnectionOperation,
        deviceId: String,
        nowUptimeSeconds: Double,
        timeoutSeconds: Double,
    ): BleConnectionDeadline {
        generation++
        val deadline = BleConnectionDeadline(
            operation = operation,
            deviceId = deviceId,
            generation = generation,
            deadlineUptimeSeconds = nowUptimeSeconds + max(0.1, timeoutSeconds),
        )
        activeDeadline = deadline
        return deadline
    }

    fun isCurrent(deadline: BleConnectionDeadline): Boolean =
        activeDeadline == deadline && deadline.generation == generation

    fun consumeExpiration(deadline: BleConnectionDeadline, nowUptimeSeconds: Double): Boolean {
        if (!isCurrent(deadline) || nowUptimeSeconds < deadline.deadlineUptimeSeconds) return false
        activeDeadline = null
        generation++
        return true
    }

    fun cancel() {
        activeDeadline = null
        generation++
    }
}

data class BleConnectionAttemptDiagnostics(
    val attemptCount: Int = 0,
    val timeoutCount: Int = 0,
    val ignoredStaleCallbackCount: Int = 0,
    val activeOperation: BleConnectionOperation? = null,
    val lastTimedOutOperation: BleConnectionOperation? = null,
)
