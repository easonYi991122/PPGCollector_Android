package com.example.ppgcollector_android

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ppgcollector_android.data.session.CaptureSessionInspection
import com.example.ppgcollector_android.data.session.CaptureSessionRepository
import com.example.ppgcollector_android.data.session.StoredCaptureSession
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SessionListItemUi(
    val directory: Path,
    val baseName: String,
    val complete: Boolean?,
    val verifiedComplete: Boolean,
    val recoveryCandidate: Boolean,
    val stopReason: String?,
    val softVersion: String?,
    val algorithmVersion: String?,
    val totalBytes: Long,
    val findings: List<String>,
)

data class SessionDetailUi(
    val item: SessionListItemUi,
    val isInspecting: Boolean = true,
    val inspection: CaptureSessionInspection? = null,
    val expectedFiles: List<String> = emptyList(),
    val error: String? = null,
)

data class SessionsUiState(
    val isLoading: Boolean = false,
    val sessions: List<SessionListItemUi> = emptyList(),
    val selected: SessionDetailUi? = null,
    val error: String? = null,
)

object SessionListItemMapper {
    fun map(session: StoredCaptureSession): SessionListItemUi {
        val metadata = session.metadata
        val findings = buildList {
            if (!session.metadataIsReadable) add("session metadata 不可读取")
            if (!session.hasAllExpectedFiles) add("缺少一个或多个预期文件")
            if (metadata?.complete == false) add("会话标记为 incomplete")
            if (metadata?.missingFrames?.let { it > 0 } == true) {
                add("存在 ${metadata.missingFrames} 个缺失帧")
            }
            if (metadata?.invalidFrames?.let { it > 0 } == true) {
                add("存在 ${metadata.invalidFrames} 个无效帧")
            }
        }
        return SessionListItemUi(
            directory = session.directory,
            baseName = session.baseName,
            complete = session.isComplete,
            verifiedComplete = session.isVerifiedComplete,
            recoveryCandidate = session.isRecoveryCandidate,
            stopReason = metadata?.stopReason?.wireValue,
            softVersion = metadata?.softVersion,
            algorithmVersion = metadata?.algVersion,
            totalBytes = session.totalBytes,
            findings = findings,
        )
    }
}

class SessionsViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val app = application as PpgCollectorApplication
    private val _state = MutableStateFlow(SessionsUiState())
    private var refreshJob: Job? = null
    private var inspectionJob: Job? = null

    val state: StateFlow<SessionsUiState> = _state.asStateFlow()

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    CaptureSessionRepository.listSessions(app.sessionsRoot)
                        .map(SessionListItemMapper::map)
                }
            }
            result.onSuccess { items ->
                _state.value = _state.value.copy(isLoading = false, sessions = items)
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = error.message ?: error::class.simpleName,
                )
            }
        }
    }

    fun select(item: SessionListItemUi) {
        inspectionJob?.cancel()
        _state.value = _state.value.copy(
            selected = SessionDetailUi(
                item = item,
                expectedFiles = expectedFileNames(item.directory),
            ),
        )
        inspectionJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { CaptureSessionRepository.inspect(item.directory) }
            }
            val selected = _state.value.selected
            if (selected?.item?.directory != item.directory) return@launch
            result.onSuccess { inspection ->
                _state.value = _state.value.copy(
                    selected = selected.copy(isInspecting = false, inspection = inspection),
                )
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    selected = selected.copy(
                        isInspecting = false,
                        error = error.message ?: error::class.simpleName,
                    ),
                )
            }
        }
    }

    fun clearSelection() {
        inspectionJob?.cancel()
        _state.value = _state.value.copy(selected = null)
    }

    private fun expectedFileNames(directory: Path): List<String> {
        val files = CaptureSessionRepository.expectedFiles(directory)
        return listOf(files.raw, files.csv, files.metadata).map { path ->
            val marker = if (Files.isRegularFile(path)) "✓" else "✗"
            "$marker ${path.fileName}"
        }
    }
}
