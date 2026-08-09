package com.example.ppgcollector_android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.nio.file.Path

@Composable
internal fun SavedSessionsRoute(
    state: SessionsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (SessionListItemUi) -> Unit,
    onToggleSubject: (String) -> Unit,
    onToggleSession: (Path) -> Unit,
    onBeginSelection: () -> Unit,
    onSelectAllArchive: () -> Unit,
    onSelectAllSessions: () -> Unit,
    onCancelSelection: () -> Unit,
    onExportSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onCancelAnalysis: (Path) -> Unit,
    onOpenCompare: () -> Unit,
    onViewModeChange: (SavedSessionsViewMode) -> Unit,
    onToggleExpandedSubject: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }
    val viewMode = state.savedSessionsViewMode
    val expandedSubjects = state.expandedArchiveSubjects
    val selectedCount = state.selectedSessionCount

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            SavedSessionsTopBar(
                viewMode = viewMode,
                selectionMode = state.sessionSelectionMode,
                selectedCount = selectedCount,
                isLoading = state.isLoading,
                actionRunning = state.action.isRunning,
                onBack = onBack,
                onRefresh = onRefresh,
                onSwitchView = {
                    onViewModeChange(when (viewMode) {
                        SavedSessionsViewMode.ARCHIVE -> SavedSessionsViewMode.FLAT_FILES
                        SavedSessionsViewMode.FLAT_FILES -> SavedSessionsViewMode.ARCHIVE
                    })
                },
                onBeginSelection = onBeginSelection,
                onExport = onExportSelection,
                onDelete = { showDeleteConfirmation = true },
                onSelectAll = if (viewMode == SavedSessionsViewMode.ARCHIVE) {
                    onSelectAllArchive
                } else {
                    onSelectAllSessions
                },
                onCancelSelection = onCancelSelection,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            SavedSessionsActionStatus(state)
            when (viewMode) {
                SavedSessionsViewMode.ARCHIVE -> SubjectArchiveContent(
                    state = state,
                    expandedSubjects = expandedSubjects,
                    onToggleExpanded = onToggleExpandedSubject,
                    onSelect = onSelect,
                    onToggleSubject = onToggleSubject,
                    onToggleSession = onToggleSession,
                    modifier = Modifier.fillMaxSize(),
                )

                SavedSessionsViewMode.FLAT_FILES -> FlatSessionsContent(
                    state = state,
                    onSelect = onSelect,
                    onToggleSelection = onToggleSession,
                    onCancelAnalysis = onCancelAnalysis,
                    onOpenCompare = onOpenCompare,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("删除所选会话？") },
            text = {
                Text("将永久删除 $selectedCount 个本地会话。被试资料不会删除，但会话文件无法恢复。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmation = false
                        onDeleteSelected()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SavedSessionsTopBar(
    viewMode: SavedSessionsViewMode,
    selectionMode: Boolean,
    selectedCount: Int,
    isLoading: Boolean,
    actionRunning: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSwitchView: () -> Unit,
    onBeginSelection: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit,
    onCancelSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) { Text("‹ 返回") }
                Column(Modifier.weight(1f)) {
                    Text(
                        "已保存会话",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        SavedSessionsUiPolicy.subtitle(viewMode, selectionMode, selectedCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isLoading) {
                    CircularProgressIndicator(Modifier.sizeIn(minWidth = 24.dp, minHeight = 24.dp), strokeWidth = 2.dp)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectionMode) {
                    Button(
                        onClick = onExport,
                        enabled = selectedCount > 0 && !actionRunning,
                        modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
                    ) { Text("导出") }
                    Button(
                        onClick = onDelete,
                        enabled = selectedCount > 0 && !actionRunning,
                        modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) { Text("删除") }
                    FilledTonalButton(
                        onClick = onSelectAll,
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
                    ) { Text("全选") }
                    OutlinedButton(
                        onClick = onCancelSelection,
                        modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
                    ) { Text("取消") }
                } else {
                    OutlinedButton(
                        onClick = onRefresh,
                        enabled = !isLoading,
                        modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp).semantics {
                            contentDescription = "刷新已保存会话"
                        },
                    ) { Text("刷新") }
                    FilledTonalButton(
                        onClick = onSwitchView,
                        modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
                    ) { Text(SavedSessionsUiPolicy.switchLabel(viewMode)) }
                    OutlinedButton(
                        onClick = onBeginSelection,
                        modifier = Modifier.weight(1f).sizeIn(minHeight = 48.dp),
                    ) { Text("选择") }
                }
            }
        }
    }
}

@Composable
private fun SavedSessionsActionStatus(state: SessionsUiState, modifier: Modifier = Modifier) {
    val text = when {
        state.action.isRunning && state.action.kind == SessionActionKind.DELETE -> "正在删除所选会话…"
        state.action.isRunning -> "正在生成导出文件…"
        state.action.error != null -> "操作失败：${state.action.error}"
        state.action.message != null -> state.action.message
        state.error != null -> state.error
        else -> null
    } ?: return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (state.action.error != null || state.error != null) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.action.isRunning) CircularProgressIndicator(Modifier.sizeIn(minWidth = 20.dp, minHeight = 20.dp), strokeWidth = 2.dp)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
