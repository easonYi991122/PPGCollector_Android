package com.example.ppgcollector_android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.ppgcollector_android.core.ble.BleCoordinatorAction
import com.example.ppgcollector_android.core.ble.DiscoveredBleDevice
import java.time.Instant
import org.junit.Rule
import org.junit.Test

class CupDeviceListTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun discoveredDeviceRendersInsideScrollableScreenWithoutNestedScrollCrash() {
        val device = DiscoveredBleDevice(
            id = "00:11:22:33:44:55",
            name = "CUP-SIM",
            rssi = -42,
            isConnectable = true,
            lastSeen = Instant.EPOCH,
        )

        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    CupDeviceList(
                        devices = listOf(device),
                        connectionBusy = false,
                        onConnect = { BleCoordinatorAction.STARTED },
                    )
                }
            }
        }

        composeRule.onNodeWithText("CUP-SIM").assertIsDisplayed()
        composeRule.onNodeWithText("RSSI -42").assertIsDisplayed()
        composeRule.onNodeWithText("连接").assertIsDisplayed()
    }
}
