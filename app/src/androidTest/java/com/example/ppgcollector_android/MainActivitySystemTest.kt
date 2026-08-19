package com.example.ppgcollector_android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
        assertLiveSurfacesVisible()
        composeRule.onNodeWithText("已保存会话").performClick()
        composeRule.waitForIdle()
        assertSessionsSurfaceVisible()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        assertSessionsSurfaceVisible()
        composeRule.onNodeWithText("‹ 返回").performClick()
        composeRule.waitForIdle()
        assertLiveSurfacesVisible()
    }

    private fun assertLiveSurfacesVisible() {
        composeRule.onNodeWithText("CUPCollector").assertIsDisplayed()
        composeRule.onNodeWithText("扫描 CUP").assertIsDisplayed()
        composeRule.onNodeWithText("CAUSAL").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "RED 波形",
            substring = true,
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "IR 波形",
            substring = true,
        ).performScrollTo().assertIsDisplayed()
        listOf("心率", "RR（Red/IR）", "PI（RED AC/DC）", "信号质量 SQI", "计算血压").forEach { label ->
            composeRule.onNodeWithContentDescription(label, substring = true)
                .performScrollTo()
                .assertIsDisplayed()
        }
        composeRule.onNodeWithText("开始录制").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("已保存会话").performScrollTo().assertIsDisplayed()
    }

    private fun assertSessionsSurfaceVisible() {
        composeRule.onNodeWithText("已保存会话").assertIsDisplayed()
        composeRule.onNodeWithText(
            "文件系统记录、完整性复核与版本化离线分析",
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            "卸载应用会删除未导出",
            substring = true,
        ).assertIsDisplayed()
    }
}
