package com.example.ppgcollector_android

import com.example.ppgcollector_android.data.session.CaptureArchiveSelection
import com.example.ppgcollector_android.data.session.SubjectArchiveRepository
import com.example.ppgcollector_android.data.session.SubjectArchiveSnapshot
import java.nio.file.Path

enum class SavedSessionsViewMode {
    ARCHIVE,
    FLAT_FILES,
}

/** Pure selection policy shared by Compose and JVM tests. */
internal object SavedSessionsUiPolicy {
    fun selectedSessionDirectories(
        archive: SubjectArchiveSnapshot,
        selectedDirectories: Set<Path>,
        busyDirectories: Set<Path> = emptySet(),
    ): Set<Path> = SubjectArchiveRepository.selectedSessions(
        archive,
        CaptureArchiveSelection(
            sessionDirectories = selectedDirectories,
            subjectIds = emptySet(),
        ),
    ).mapTo(linkedSetOf()) { it.directory } - busyDirectories

    fun switchLabel(mode: SavedSessionsViewMode): String = when (mode) {
        SavedSessionsViewMode.ARCHIVE -> "逐文件"
        SavedSessionsViewMode.FLAT_FILES -> "被试档案"
    }

    fun subtitle(mode: SavedSessionsViewMode, selectionMode: Boolean, selectedCount: Int): String =
        if (selectionMode) {
            "已选择 $selectedCount 个会话"
        } else {
            when (mode) {
                SavedSessionsViewMode.ARCHIVE -> "被试档案视图"
                SavedSessionsViewMode.FLAT_FILES -> "逐文件视图"
            }
        }
}
