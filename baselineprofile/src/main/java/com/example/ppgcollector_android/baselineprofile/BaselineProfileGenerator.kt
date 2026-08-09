package com.example.ppgcollector_android.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun criticalUserJourneys() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()

        device.wait(Until.hasObject(By.text("已保存会话")), 5_000)
        device.findObject(By.text("已保存会话"))?.click()
        device.wait(Until.hasObject(By.text("逐文件")), 5_000)
        device.findObject(By.text("逐文件"))?.click()
        device.waitForIdle()
        device.swipe(
            device.displayWidth / 2,
            device.displayHeight * 3 / 4,
            device.displayWidth / 2,
            device.displayHeight / 4,
            12,
        )
    }

    private companion object {
        const val TARGET_PACKAGE = "com.example.ppgcollector_android"
    }
}
