package com.example.ppgcollector_android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Requires an emulator/device; compilation is part of the local acceptance gate. */
@RunWith(AndroidJUnit4::class)
class MainActivitySystemTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun keyLiveAndSessionsSurfacesSurviveActivityRecreation() {
        assertLandingSurfacesVisible()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        assertLandingSurfacesVisible()
    }

    private fun assertLandingSurfacesVisible() {
        composeRule.onNodeWithText("CUPCollector").assertIsDisplayed()
        composeRule.onNodeWithText("扫描 CUP").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "RED 波形",
            substring = true,
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "IR 波形",
            substring = true,
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("开始录制").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("已保存会话").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "卸载应用会删除未导出的会话",
            substring = true,
        ).performScrollTo().assertIsDisplayed()
    }
}
