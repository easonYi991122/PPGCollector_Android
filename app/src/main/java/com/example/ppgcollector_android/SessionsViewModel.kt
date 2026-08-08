package com.example.ppgcollector_android

import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.example.ppgcollector_android.data.session.CaptureExportCancellation
import com.example.ppgcollector_android.data.session.CaptureExportProgress
import com.example.ppgcollector_android.data.session.CaptureSafExportService
import com.example.ppgcollector_android.data.session.CaptureSessionExportException
import com.example.ppgcollector_android.data.session.CaptureSessionInspection
import com.example.ppgcollector_android.data.session.CaptureSessionAnalysisArtifact
import com.example.ppgcollector_android.data.session.CaptureSessionAnalysisProgress
import com.example.ppgcollector_android.data.session.CaptureSessionOfflineAnalysisService
import com.example.ppgcollector_android.data.session.CaptureSessionSignalTrace
import com.example.ppgcollector_android.data.session.CaptureSessionRecoveryService
import com.example.ppgcollector_android.data.session.CaptureSessionRepository
import com.example.ppgcollector_android.data.session.StoredCaptureSession
import com.example.ppgcollector_android.data.session.SubjectArchiveRepository
import com.example.ppgcollector_android.data.session.SubjectArchiveSnapshot
import com.example.ppgcollector_android.data.session.CaptureArchiveSelection
import com.example.ppgcollector_android.data.session.CaptureArchiveExportService
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }

data class SessionListItemUi(
    val directory: Path,
    val baseName: String,
    val complete: Boolean?,
    val verifiedComplete: Boolean,
    val recoveryCandidate: Boolean,
    val stopReason: String?,
    val softVersion: String?,
    val algorithmVersion: String?,
    val preprocessProfile: String?,
    val protocolProfile: String?,
    val transportProfile: String?,
    val deviceName: String?,
    val sessionId: String?,
    val startedUtc: java.time.Instant?,
    val endedUtc: java.time.Instant?,
    val modifiedAt: java.time.Instant,
    val frameCount: Long?,
    val sampleCount: Long?,
    val rawChunkCount: Long?,
    val totalBytes: Long,
    val findings: List<String>,
)

data class SessionDetailUi(
    val item: SessionListItemUi,
    val isInspecting: Boolean = true,
    val inspection: CaptureSessionInspection? = null,
    val isLoadingSignal: Boolean = true,
    val signal: CaptureSessionSignalTrace? = null,
    val signalError: String? = null,
    val expectedFiles: List<String> = emptyList(),
    val error: String? = null,
)

enum class SessionActionKind { EXPORT, ARCHIVE_EXPORT, RECOVER }

data class SessionActionUi(
    val kind: SessionActionKind? = null,
    val isRunning: Boolean = false,
    val bytesCopied: Long = 0L,
    val totalBytes: Long = 0L,
    val message: String? = null,
    val error: String? = null,
)

internal fun cancelledSessionAction(kind: SessionActionKind?): SessionActionUi =
    SessionActionUi(
        kind = kind,
        message = if (kind == SessionActionKind.EXPORT) "导出已取消" else "操作已取消",
    )

data class SessionsUiState(
    val isLoading: Boolean = false,
    val sessions: List<SessionListItemUi> = emptyList(),
    val selected: SessionDetailUi? = null,
    val artifactsBySession: Map<Path, List<CaptureSessionAnalysisArtifact>> = emptyMap(),
    val analysisTasks: Map<Path, SessionAnalysisTaskUi> = emptyMap(),
    val error: String? = null,
    val action: SessionActionUi = SessionActionUi(),
    val archive: SubjectArchiveSnapshot = SubjectArchiveSnapshot(),
    val archiveSelectedDirectories: Set<Path> = emptySet(),
    val archiveSelectedSubjects: Set<String> = emptySet(),
)

enum class SessionAnalysisTaskStatus { RUNNING, COMPLETED, CANCELLED, FAILED }

data class SessionAnalysisTaskUi(
    val sessionBaseName: String,
    val status: SessionAnalysisTaskStatus,
    val progress: CaptureSessionAnalysisProgress? = null,
    val artifactName: String? = null,
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
            preprocessProfile = metadata?.preprocessProfile,
            protocolProfile = metadata?.protocolProfile,
            transportProfile = metadata?.transportProfile,
            deviceName = metadata?.device?.name,
            sessionId = metadata?.sessionId,
            startedUtc = metadata?.startedUtc,
            endedUtc = metadata?.endedUtc,
            modifiedAt = session.modifiedAt,
            frameCount = metadata?.frameCount,
            sampleCount = metadata?.sampleCount,
            rawChunkCount = metadata?.rawChunkCount,
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
    private var signalJob: Job? = null
    private var actionJob: Job? = null
    private val analysisJobs = ConcurrentHashMap<Path, Job>()

    val state: StateFlow<SessionsUiState> = _state.asStateFlow()

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
                val result = withContext(Dispatchers.IO) {
                    runCatchingCancellable {
                        val sessions = CaptureSessionRepository.listSessions(app.sessionsRoot)
                        val items = sessions.map(SessionListItemMapper::map)
                        val artifacts = sessions.associate { session ->
                            session.directory to CaptureSessionOfflineAnalysisService.listArtifacts(session)
                        }
                        val archive = SubjectArchiveRepository.rebuild(app.sessionsRoot, app.subjectsRoot)
                        Triple(items, artifacts, archive)
                    }
                }
            result.onSuccess { (items, artifacts, archive) ->
                _state.update { current ->
                    val mergedArtifacts = artifacts.mapValues { (directory, diskArtifacts) ->
                        (diskArtifacts + current.artifactsBySession[directory].orEmpty())
                            .distinctBy { it.path }
                            .sortedByDescending { it.report.endedUtc }
                    }
                    current.copy(
                        isLoading = false,
                        sessions = items,
                        artifactsBySession = mergedArtifacts,
                        archive = archive,
                    )
                }
            }.onFailure { error ->
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = error.message ?: error::class.simpleName,
                )
            }
        }
    }

    fun toggleArchiveSession(directory: Path) {
        _state.update { state ->
            val next = state.archiveSelectedDirectories.toMutableSet()
            if (!next.add(directory)) next.remove(directory)
            state.copy(archiveSelectedDirectories = next)
        }
    }

    fun toggleArchiveSubject(subject: String) {
        _state.update { state ->
            val next = state.archiveSelectedSubjects.toMutableSet()
            if (!next.add(subject)) next.remove(subject)
            state.copy(archiveSelectedSubjects = next)
        }
    }

    fun clearArchiveSelection() {
        _state.update { it.copy(archiveSelectedDirectories = emptySet(), archiveSelectedSubjects = emptySet()) }
    }

    fun exportArchiveTo(destination: Uri) {
        val snapshot = _state.value
        val selection = CaptureArchiveSelection(
            sessionDirectories = snapshot.archiveSelectedDirectories,
            subjectIds = snapshot.archiveSelectedSubjects,
        )
        if (selection.isEmpty) return
        actionJob?.cancel()
        actionJob = viewModelScope.launch {
            setAction(SessionActionUi(kind = SessionActionKind.ARCHIVE_EXPORT, isRunning = true))
            try {
                withContext(Dispatchers.IO) {
                    val job = kotlinx.coroutines.currentCoroutineContext()[Job]
                    val staging = app.cacheDir.toPath().resolve("archive-export-${System.nanoTime()}.zip")
                    try {
                        CaptureArchiveExportService.export(
                            sessionsRoot = app.sessionsRoot,
                            subjectsRoot = app.subjectsRoot,
                            selection = selection,
                            destination = staging,
                            onProgress = { progress -> publishExportProgress(progress) },
                            cancellation = CaptureExportCancellation {
                                if (job?.isActive == false) throw CaptureSessionExportException.Cancelled
                            },
                        )
                        app.contentResolver.openOutputStream(destination, "w")?.use { output ->
                            Files.newInputStream(staging).use { input -> input.copyTo(output, 64 * 1024) }
                        } ?: throw CaptureSessionExportException.CannotExport("cannot open SAF destination")
                    } finally {
                        Files.deleteIfExists(staging)
                    }
                }
                setAction(SessionActionUi(kind = SessionActionKind.ARCHIVE_EXPORT, message = "批量导出完成"))
            } catch (_: CancellationException) {
                return@launch
            } catch (_: CaptureSessionExportException.Cancelled) {
                setAction(SessionActionUi(kind = SessionActionKind.ARCHIVE_EXPORT, message = "导出已取消"))
            } catch (error: Exception) {
                setAction(SessionActionUi(kind = SessionActionKind.ARCHIVE_EXPORT, error = error.message))
            }
        }
    }

    fun select(item: SessionListItemUi) {
        inspectionJob?.cancel()
        signalJob?.cancel()
        _state.value = _state.value.copy(
            action = SessionActionUi(),
            selected = SessionDetailUi(
                item = item,
                expectedFiles = expectedFileNames(item.directory),
            ),
        )
        inspectionJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatchingCancellable { CaptureSessionRepository.inspect(item.directory) }
            }
            result.onSuccess { inspection ->
                updateSelected(item.directory) {
                    it.copy(isInspecting = false, inspection = inspection)
                }
            }.onFailure { error ->
                updateSelected(item.directory) {
                    it.copy(
                        isInspecting = false,
                        error = error.message ?: error::class.simpleName,
                    )
                }
            }
        }
        signalJob = viewModelScope.launch(Dispatchers.Default) {
            val workerJob = kotlinx.coroutines.currentCoroutineContext()[Job]
            val result = runCatchingCancellable {
                val session = withContext(Dispatchers.IO) { findSession(item.directory) }
                    ?: error("session no longer exists")
                CaptureSessionOfflineAnalysisService.loadSignalTrace(
                    session = session,
                    cancellationCheck = {
                        if (workerJob?.isActive != true) throw CancellationException()
                    },
                )
            }
            result.onSuccess { signal ->
                updateSelected(item.directory) {
                    it.copy(isLoadingSignal = false, signal = signal, signalError = null)
                }
            }.onFailure { error ->
                updateSelected(item.directory) {
                    it.copy(
                        isLoadingSignal = false,
                        signalError = error.message ?: error::class.simpleName,
                    )
                }
            }
        }
    }

    fun cancelInspection() {
        val selected = _state.value.selected ?: return
        if (!selected.isInspecting) return
        inspectionJob?.cancel()
        _state.value = _state.value.copy(
            selected = selected.copy(
                isInspecting = false,
                error = "检查已取消",
            ),
        )
    }

    fun clearSelection() {
        inspectionJob?.cancel()
        signalJob?.cancel()
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
            } catch (_: CancellationException) {
                return@launch
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
            } catch (_: CancellationException) {
                return@launch
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

    fun cancelExportPicker() {
        if (_state.value.action.isRunning) {
            actionJob?.cancel()
        }
        setAction(cancelledSessionAction(SessionActionKind.EXPORT))
    }

    fun cancelAction() {
        val action = _state.value.action
        if (!action.isRunning) return
        actionJob?.cancel()
        setAction(cancelledSessionAction(action.kind))
    }

    fun clearAction() {
        setAction(SessionActionUi())
    }

    fun startAnalysis(item: SessionListItemUi) {
        if (analysisJobs[item.directory]?.isActive == true) return
        _state.update { state ->
            state.copy(
                analysisTasks = state.analysisTasks + (
                    item.directory to SessionAnalysisTaskUi(
                        sessionBaseName = item.baseName,
                        status = SessionAnalysisTaskStatus.RUNNING,
                    )
                    ),
            )
        }
        val job = viewModelScope.launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            val workerJob = kotlinx.coroutines.currentCoroutineContext()[Job]
            try {
                val session = withContext(Dispatchers.IO) { findSession(item.directory) }
                    ?: error("session no longer exists")
                val artifact = CaptureSessionOfflineAnalysisService.analyzeAndSave(
                    session = session,
                    progress = { update ->
                        _state.update { state ->
                            val current = state.analysisTasks[item.directory]
                            if (current?.status != SessionAnalysisTaskStatus.RUNNING) state
                            else state.copy(
                                analysisTasks = state.analysisTasks + (
                                    item.directory to current.copy(progress = update)
                                    ),
                            )
                        }
                    },
                    cancellationCheck = {
                        if (workerJob?.isActive != true) throw CancellationException()
                    },
                )
                val artifacts = withContext(Dispatchers.IO) {
                    CaptureSessionOfflineAnalysisService.listArtifacts(session)
                }
                _state.update { state ->
                    state.copy(
                        artifactsBySession = state.artifactsBySession + (item.directory to artifacts),
                        analysisTasks = state.analysisTasks + (
                            item.directory to SessionAnalysisTaskUi(
                                sessionBaseName = item.baseName,
                                status = SessionAnalysisTaskStatus.COMPLETED,
                                artifactName = artifact.path.fileName.toString(),
                            )
                            ),
                    )
                }
            } catch (_: CancellationException) {
                _state.update { state ->
                    state.copy(
                        analysisTasks = state.analysisTasks + (
                            item.directory to SessionAnalysisTaskUi(
                                sessionBaseName = item.baseName,
                                status = SessionAnalysisTaskStatus.CANCELLED,
                            )
                            ),
                    )
                }
            } catch (error: Exception) {
                _state.update { state ->
                    state.copy(
                        analysisTasks = state.analysisTasks + (
                            item.directory to SessionAnalysisTaskUi(
                                sessionBaseName = item.baseName,
                                status = SessionAnalysisTaskStatus.FAILED,
                                error = error.message ?: error::class.simpleName,
                            )
                            ),
                    )
                }
            } finally {
                analysisJobs.remove(item.directory)
            }
        }
        analysisJobs[item.directory] = job
        job.start()
    }

    fun cancelAnalysis(directory: Path) {
        analysisJobs[directory]?.cancel()
    }

    fun clearFinishedAnalysis(directory: Path) {
        if (_state.value.analysisTasks[directory]?.status == SessionAnalysisTaskStatus.RUNNING) return
        _state.update { state ->
            state.copy(analysisTasks = state.analysisTasks - directory)
        }
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

    private fun updateSelected(
        directory: Path,
        update: (SessionDetailUi) -> SessionDetailUi,
    ) {
        _state.update { state ->
            val selected = state.selected
            if (selected?.item?.directory != directory) state
            else state.copy(selected = update(selected))
        }
    }

    private fun expectedFileNames(directory: Path): List<String> {
        val files = CaptureSessionRepository.expectedFiles(directory)
        return listOf(files.raw, files.csv, files.metadata).map { path ->
            val marker = if (Files.isRegularFile(path)) "✓" else "✗"
            "$marker ${path.fileName}"
        }
    }
}
