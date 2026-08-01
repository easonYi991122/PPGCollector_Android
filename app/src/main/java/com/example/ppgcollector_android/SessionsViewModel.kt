package com.example.ppgcollector_android

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.example.ppgcollector_android.data.session.CaptureExportCancellation
import com.example.ppgcollector_android.data.session.CaptureExportProgress
import com.example.ppgcollector_android.data.session.CaptureSafExportService
import com.example.ppgcollector_android.data.session.CaptureSessionExportException
import com.example.ppgcollector_android.data.session.CaptureSessionInspection
import com.example.ppgcollector_android.data.session.CaptureSessionRecoveryService
import com.example.ppgcollector_android.data.session.CaptureSessionRepository
import com.example.ppgcollector_android.data.session.StoredCaptureSession
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

enum class SessionActionKind { EXPORT, RECOVER }

data class SessionActionUi(
    val kind: SessionActionKind? = null,
    val isRunning: Boolean = false,
    val bytesCopied: Long = 0L,
    val totalBytes: Long = 0L,
    val message: String? = null,
    val error: String? = null,
)

data class SessionsUiState(
    val isLoading: Boolean = false,
    val sessions: List<SessionListItemUi> = emptyList(),
    val selected: SessionDetailUi? = null,
    val error: String? = null,
    val action: SessionActionUi = SessionActionUi(),
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
    private var actionJob: Job? = null

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
        actionJob?.cancel()
        _state.value = _state.value.copy(selected = null)
    }

    fun exportSelectedTo(destination: Uri) {
        val item = _state.value.selected?.item ?: return
        actionJob?.cancel()
        actionJob = viewModelScope.launch {
            setAction(SessionActionUi(kind = SessionActionKind.EXPORT, isRunning = true))
            try {
                val report = withContext(Dispatchers.IO) {
                    val session = findSession(item.directory)
                        ?: error("session no longer exists")
                    val job = kotlinx.coroutines.currentCoroutineContext()[Job]
                    CaptureSafExportService(app.contentResolver).export(
                        session = session,
                        destination = destination,
                        onProgress = ::publishExportProgress,
                        cancellation = CaptureExportCancellation {
                            if (job?.isActive == false) {
                                throw CaptureSessionExportException.Cancelled
                            }
                        },
                    )
                }
                setAction(
                    SessionActionUi(
                        kind = SessionActionKind.EXPORT,
                        message = "已导出 ${report.entryNames.size} 个文件",
                    ),
                )
            } catch (_: CaptureSessionExportException.Cancelled) {
                setAction(SessionActionUi(message = "导出已取消"))
            } catch (error: Exception) {
                setAction(
                    SessionActionUi(
                        kind = SessionActionKind.EXPORT,
                        error = error.message ?: error::class.simpleName,
                    ),
                )
            }
        }
    }

    fun recoverSelected() {
        val item = _state.value.selected?.item ?: return
        actionJob?.cancel()
        actionJob = viewModelScope.launch {
            setAction(SessionActionUi(kind = SessionActionKind.RECOVER, isRunning = true))
            try {
                val recovered = withContext(Dispatchers.IO) {
                    val session = findSession(item.directory)
                        ?: error("session no longer exists")
                    CaptureSessionRecoveryService.recover(
                        session = session,
                        requestedBaseName = CaptureSessionRecoveryService.suggestedBaseName(session),
                    )
                }
                setAction(
                    SessionActionUi(
                        kind = SessionActionKind.RECOVER,
                        message = "已创建安全恢复副本：${recovered.baseName}",
                    ),
                )
                refreshAndSelect(recovered.directory)
            } catch (error: Exception) {
                setAction(
                    SessionActionUi(
                        kind = SessionActionKind.RECOVER,
                        error = error.message ?: error::class.simpleName,
                    ),
                )
            }
        }
    }

    fun cancelAction() {
        if (_state.value.action.isRunning) {
            actionJob?.cancel()
            setAction(SessionActionUi(message = "操作已取消"))
        }
    }

    fun clearAction() {
        setAction(SessionActionUi())
    }

    private suspend fun findSession(directory: Path): StoredCaptureSession? =
        CaptureSessionRepository.listSessions(app.sessionsRoot)
            .firstOrNull { it.directory == directory }

    private suspend fun refreshAndSelect(directory: Path) {
        val items = withContext(Dispatchers.IO) {
            CaptureSessionRepository.listSessions(app.sessionsRoot).map(SessionListItemMapper::map)
        }
        _state.update { it.copy(sessions = items) }
        items.firstOrNull { it.directory == directory }?.let(::select)
    }

    private fun publishExportProgress(progress: CaptureExportProgress) {
        _state.update {
            it.copy(
                action = it.action.copy(
                    bytesCopied = progress.bytesCopied,
                    totalBytes = progress.totalBytes,
                ),
            )
        }
    }

    private fun setAction(action: SessionActionUi) {
        _state.update { it.copy(action = action) }
    }

    private fun expectedFileNames(directory: Path): List<String> {
        val files = CaptureSessionRepository.expectedFiles(directory)
        return listOf(files.raw, files.csv, files.metadata).map { path ->
            val marker = if (Files.isRegularFile(path)) "✓" else "✗"
            "$marker ${path.fileName}"
        }
    }
}
