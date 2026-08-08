package com.example.ppgcollector_android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ppgcollector_android.data.session.StoredCaptureSession
import com.example.ppgcollector_android.data.session.SubjectArchiveGroup
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val archiveDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault())

@Composable
internal fun SubjectArchiveScreen(
    state: SessionsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (SessionListItemUi) -> Unit,
    onToggleSubject: (String) -> Unit,
    onToggleSession: (java.nio.file.Path) -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onBeginSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onCancelSelection: () -> Unit,
    onOpenFlat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedCount = state.archiveSelectedDirectories.size + state.archiveSelectedSubjects.size
    val selectionMode = state.sessionSelectionMode
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("已保存会话", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            if (selectionMode) "已选择 $selectedCount 项"
                            else "被试档案视图 · canonical subject 与未归档会话",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(onClick = onBack) { Text("返回") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selectionMode) {
                        Button(onClick = onExport, enabled = selectedCount > 0 && !state.action.isRunning) {
                            Text("导出")
                        }
                        OutlinedButton(onClick = onDelete, enabled = selectedCount > 0 && !state.action.isRunning) {
                            Text("删除")
                        }
                        OutlinedButton(onClick = onSelectAll, enabled = !state.isLoading) { Text("全选") }
                        OutlinedButton(onClick = onCancelSelection) { Text("取消") }
                    } else {
                        OutlinedButton(onClick = onRefresh, enabled = !state.isLoading) { Text("刷新") }
                        OutlinedButton(onClick = onOpenFlat) { Text("逐文件视图") }
                        OutlinedButton(onClick = onBeginSelection) { Text("选择") }
                    }
                }
            }
        }
        if (state.action.isRunning) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(if (state.action.kind == SessionActionKind.DELETE) "正在删除所选会话…" else "正在流式生成 ZIP…")
                }
            }
        }
        state.action.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.tertiary) } }
        state.action.error?.let { error -> item { Text("操作失败：$error", color = MaterialTheme.colorScheme.error) } }
        state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
        if (state.isLoading && state.archive.groups.isEmpty() && state.archive.unclassified.isEmpty()) {
            item { CircularProgressIndicator() }
        }
        if (!state.isLoading && state.archive.groups.isEmpty() && state.archive.unclassified.isEmpty()) {
            item { Text("尚无本地会话", style = MaterialTheme.typography.bodyLarge) }
        }
        items(state.archive.groups, key = { it.summary.subject }) { group ->
            SubjectArchiveCard(
                group = group,
                state = state,
                selectionMode = selectionMode,
                onToggleSubject = onToggleSubject,
                onToggleSession = onToggleSession,
                onSelect = onSelect,
            )
        }
        if (state.archive.unclassified.isNotEmpty()) {
            item { Text("未归档 / legacy（${state.archive.unclassified.size}）", style = MaterialTheme.typography.titleMedium) }
            items(state.archive.unclassified, key = { it.directory.toString() }) { session ->
                ArchiveSessionRow(session, state, selectionMode, onToggleSession, onSelect)
            }
        }
        item { Spacer(Modifier.padding(bottom = 8.dp)) }
    }
}

@Composable
private fun SubjectArchiveCard(
    group: SubjectArchiveGroup,
    state: SessionsUiState,
    selectionMode: Boolean,
    onToggleSubject: (String) -> Unit,
    onToggleSession: (java.nio.file.Path) -> Unit,
    onSelect: (SessionListItemUi) -> Unit,
) {
    var expanded by rememberSaveable(group.summary.subject) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectionMode) {
                    Checkbox(
                        checked = group.summary.subject in state.archiveSelectedSubjects,
                        onCheckedChange = { onToggleSubject(group.summary.subject) },
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(group.summary.subject, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${group.summary.recordingCount} seq · 最大 ${group.summary.maxSequence ?: "—"} · " +
                            "完整 ${group.summary.completeCount} / 异常 ${group.summary.anomalyCount} · BP ${group.summary.bloodPressureGroupCount} 组",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "HR ${group.summary.heartRateBpm?.let { "%.1f bpm".format(it) } ?: "不可用"} · " +
                            "${formatArchiveInstant(group.summary.firstStartedUtc)} — ${formatArchiveInstant(group.summary.lastStartedUtc)}" +
                            if (group.summary.profileMissing) " · 资料缺失" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开") }
            }
            if (expanded) {
                group.sessions.forEach { entry ->
                    ArchiveSessionRow(entry.session, state, selectionMode, onToggleSession, onSelect, "seq ${entry.identity.sequence}")
                }
            }
        }
    }
}

@Composable
private fun ArchiveSessionRow(
    session: StoredCaptureSession,
    state: SessionsUiState,
    selectionMode: Boolean,
    onToggleSession: (java.nio.file.Path) -> Unit,
    onSelect: (SessionListItemUi) -> Unit,
    prefix: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(
                checked = session.directory in state.archiveSelectedDirectories,
                onCheckedChange = { onToggleSession(session.directory) },
            )
        }
        Column(Modifier.weight(1f)) {
            Text(prefix?.let { "$it · ${session.baseName}" } ?: session.baseName)
            Text(
                "${session.metadata?.sampleCount ?: 0} samples · ${formatArchiveInstant(session.metadata?.startedUtc)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = { onSelect(SessionListItemMapper.map(session)) }) { Text("详情") }
    }
}

private fun formatArchiveInstant(value: Instant?): String = value?.let(archiveDateFormatter::format) ?: "—"
