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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ppgcollector_android.data.session.StoredCaptureSession
import com.example.ppgcollector_android.data.session.SubjectArchiveGroup
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val archiveDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault())

private sealed interface ArchiveListEntry {
    val stableKey: String

    data class Subject(val group: SubjectArchiveGroup) : ArchiveListEntry {
        override val stableKey: String = "subject:${group.summary.subject}"
    }

    data class Session(
        val session: StoredCaptureSession,
        val subject: String?,
        val sequenceLabel: String?,
    ) : ArchiveListEntry {
        override val stableKey: String = "session:${session.directory}"
    }

    data class UnclassifiedHeader(val count: Int) : ArchiveListEntry {
        override val stableKey: String = "unclassified-header"
    }
}

@Composable
internal fun SubjectArchiveContent(
    state: SessionsUiState,
    expandedSubjects: Set<String>,
    onToggleExpanded: (String) -> Unit,
    onSelect: (SessionListItemUi) -> Unit,
    onToggleSubject: (String) -> Unit,
    onToggleSession: (java.nio.file.Path) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = remember(state.archive, expandedSubjects) {
        buildList {
            state.archive.groups.forEach { group ->
                add(ArchiveListEntry.Subject(group))
                if (group.summary.subject in expandedSubjects) {
                    group.sessions.forEach { session ->
                        add(
                            ArchiveListEntry.Session(
                                session = session.session,
                                subject = group.summary.subject,
                                sequenceLabel = "seq ${session.identity.sequence}",
                            ),
                        )
                    }
                }
            }
            if (state.archive.unclassified.isNotEmpty()) {
                add(ArchiveListEntry.UnclassifiedHeader(state.archive.unclassified.size))
                state.archive.unclassified.forEach { session ->
                    add(ArchiveListEntry.Session(session, subject = null, sequenceLabel = null))
                }
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.isLoading && entries.isEmpty()) {
            item(key = "archive-loading") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator() }
            }
        }
        if (!state.isLoading && entries.isEmpty()) {
            item(key = "archive-empty") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("尚无本地会话", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "完成录制后，canonical 会话会按被试归档，其它文件保留在未归档区域。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        items(entries, key = ArchiveListEntry::stableKey) { entry ->
            when (entry) {
                is ArchiveListEntry.Subject -> ArchiveSubjectCard(
                    group = entry.group,
                    expanded = entry.group.summary.subject in expandedSubjects,
                    selectionMode = state.sessionSelectionMode,
                    selected = entry.group.summary.subject in state.archiveSelectedSubjects,
                    onToggleExpanded = { onToggleExpanded(entry.group.summary.subject) },
                    onToggleSelected = { onToggleSubject(entry.group.summary.subject) },
                )

                is ArchiveListEntry.Session -> ArchiveSessionRow(
                    session = entry.session,
                    sequenceLabel = entry.sequenceLabel,
                    selectionMode = state.sessionSelectionMode,
                    selected = entry.session.directory in state.archiveSelectedDirectories ||
                        entry.subject in state.archiveSelectedSubjects,
                    onToggleSelected = { onToggleSession(entry.session.directory) },
                    onSelect = { onSelect(SessionListItemMapper.map(entry.session)) },
                    modifier = if (entry.subject == null) Modifier else Modifier.padding(start = 18.dp),
                )

                is ArchiveListEntry.UnclassifiedHeader -> Text(
                    "未归档 / legacy（${entry.count}）",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        item(key = "archive-bottom-space") { Spacer(Modifier.padding(bottom = 8.dp)) }
    }
}

@Composable
private fun ArchiveSubjectCard(
    group: SubjectArchiveGroup,
    expanded: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().semantics {
            stateDescription = if (expanded) "已展开" else "已收起"
        },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelected() })
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    group.summary.subject,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${group.summary.recordingCount} seq · 最大 ${group.summary.maxSequence ?: "—"} · " +
                        "完整 ${group.summary.completeCount} / 异常 ${group.summary.anomalyCount} · " +
                        "BP ${group.summary.bloodPressureGroupCount} 组",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "HR ${group.summary.heartRateBpm?.let { "%.1f bpm".format(it) } ?: "不可用"} · " +
                        "${formatArchiveInstant(group.summary.firstStartedUtc)} — " +
                        formatArchiveInstant(group.summary.lastStartedUtc) +
                        if (group.summary.profileMissing) " · 资料缺失" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = onToggleExpanded) {
                Text(if (expanded) "收起" else "展开")
            }
        }
    }
}

@Composable
private fun ArchiveSessionRow(
    session: StoredCaptureSession,
    sequenceLabel: String?,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelected: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().semantics {
            if (selectionMode) stateDescription = if (selected) "已选择" else "未选择"
        },
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onToggleSelected() })
            }
            Column(Modifier.weight(1f)) {
                Text(
                    sequenceLabel?.let { "$it · ${session.baseName}" } ?: session.baseName,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "${session.metadata?.sampleCount ?: 0} samples · " +
                        formatArchiveInstant(session.metadata?.startedUtc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = onSelect) { Text("详情") }
        }
    }
}

private fun formatArchiveInstant(value: Instant?): String = value?.let(archiveDateFormatter::format) ?: "—"
