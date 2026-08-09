package com.example.ppgcollector_android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.ppgcollector_android.ui.theme.PPGCollector_AndroidTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SavedSessionsRouteTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sharedTopBarSwitchesContentAndSelectionActionsWithoutChangingBackSemantics() {
        var state by mutableStateOf(SessionsUiState())
        var backCount = 0
        composeRule.setContent {
            PPGCollector_AndroidTheme {
                SavedSessionsRoute(
                    state = state,
                    onBack = { backCount++ },
                    onRefresh = {},
                    onSelect = {},
                    onToggleSubject = {},
                    onToggleSession = {},
                    onBeginSelection = { state = state.copy(sessionSelectionMode = true) },
                    onSelectAllArchive = {},
                    onSelectAllSessions = {},
                    onCancelSelection = { state = state.copy(sessionSelectionMode = false) },
                    onExportSelection = {},
                    onDeleteSelected = {},
                    onCancelAnalysis = {},
                    onOpenCompare = {},
                    onViewModeChange = { state = state.copy(savedSessionsViewMode = it) },
                    onToggleExpandedSubject = {},
                )
            }
        }

        composeRule.onNodeWithText("逐文件").performClick()
        composeRule.onNodeWithText("被试档案").assertIsDisplayed()
        composeRule.onNodeWithText("选择").performClick()
        composeRule.onNodeWithText("导出").assertIsDisplayed()
        composeRule.onNodeWithText("删除").assertIsDisplayed()
        composeRule.onNodeWithText("全选").assertIsDisplayed()
        composeRule.onNodeWithText("取消").assertIsDisplayed()
        composeRule.onNodeWithText("‹ 返回").performClick()
        assertEquals(1, backCount)
    }
}
