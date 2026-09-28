package com.example.ppgcollector_android

import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.ppgcollector_android.data.session.CaptureArchiveExportService
import com.example.ppgcollector_android.data.session.CaptureArchiveSelection
import com.example.ppgcollector_android.data.session.CaptureArtifactSummary
import com.example.ppgcollector_android.data.session.CaptureBloodPressureSeries
import com.example.ppgcollector_android.data.session.CaptureExportCancellation
import com.example.ppgcollector_android.data.session.CaptureExportProgress
import com.example.ppgcollector_android.data.session.CaptureParticipantSnapshot
import com.example.ppgcollector_android.data.session.CaptureRecordMode
import com.example.ppgcollector_android.data.session.CaptureRecordingSnapshot
import com.example.ppgcollector_android.data.session.CaptureRecordingState
import com.example.ppgcollector_android.data.session.CaptureSessionAccessRegistry
import com.example.ppgcollector_android.data.session.CaptureSessionAnalysisArtifact
import com.example.ppgcollector_android.data.session.CaptureSessionAnalysisProgress
import com.example.ppgcollector_android.data.session.CaptureSessionDeleteResult
import com.example.ppgcollector_android.data.session.CaptureSessionExportException
import com.example.ppgcollector_android.data.session.CaptureSessionExportService
import com.example.ppgcollector_android.data.session.CaptureSessionInspection
import com.example.ppgcollector_android.data.session.CaptureSessionMetadataEditor
import com.example.ppgcollector_android.data.session.CaptureSessionOfflineAnalysisService
import com.example.ppgcollector_android.data.session.CaptureSessionRecoveryResult
import com.example.ppgcollector_android.data.session.CaptureSessionRecoveryService
import com.example.ppgcollector_android.data.session.CaptureSessionRepository
import com.example.ppgcollector_android.data.session.CaptureSessionSignalTrace
import com.example.ppgcollector_android.data.session.CaptureSignalLoadBudget
import com.example.ppgcollector_android.data.session.SessionNamePolicy
import com.example.ppgcollector_android.data.session.SessionNamePrefix
import com.example.ppgcollector_android.data.session.StoredCaptureSession
import com.example.ppgcollector_android.data.session.SubjectArchiveRepository
import com.example.ppgcollector_android.data.session.SubjectArchiveSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
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

private fun SessionsUiState.recountSelection(): SessionsUiState {
    val directories = SavedSessionsUiPolicy.selectedSessionDirectories(archive, archiveSelectedDirectories, busyDirectories)
    val subjects = archive.groups.filter { group ->
        val eligible = group.sessions.map { it.session.directory }.filterNot { it in busyDirectories }
        eligible.isNotEmpty() && eligible.all { it in directories }
    }.mapTo(linkedSetOf()) { it.summary.subject }
    return copy(archiveSelectedDirectories = directories, archiveSelectedSubjects = subjects,
        selectedSessionCount = directories.size)
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
    val participant: CaptureParticipantSnapshot? = null,
    val bloodPressureCount: Int = 0,
    val systolicBp: Int? = null,
    val diastolicBp: Int? = null,
    val sessionPrefix: SessionNamePrefix? = null,
    val recordMode: CaptureRecordMode? = null,
    val plannedDurationSeconds: Int? = null,
    val actualDurationSeconds: Long? = null,
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

enum class SessionActionKind { EXPORT, ARCHIVE_EXPORT, DELETE, RECOVER, UPDATE_BP }

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
        message = if (kind == SessionActionKind.EXPORT || kind == SessionActionKind.ARCHIVE_EXPORT) "导出已取消" else "操作已取消",
    )

data class SessionsUiState(
    val isLoading: Boolean = false,
    val sessions: List<SessionListItemUi> = emptyList(),
    val selected: SessionDetailUi? = null,
    val artifactsBySession: Map<Path, List<CaptureArtifactSummary>> = emptyMap(),
    val loadedArtifacts: Map<Path, CaptureSessionAnalysisArtifact> = emptyMap(),
    val artifactErrors: Map<Path, String> = emptyMap(),
    val loadingArtifacts: Set<Path> = emptySet(),
    val artifactIndexErrors: Map<Path, String> = emptyMap(),
    val busyDirectories: Set<Path> = emptySet(),
    val analysisTasks: Map<Path, SessionAnalysisTaskUi> = emptyMap(),
    val error: String? = null,
    val action: SessionActionUi = SessionActionUi(),
    val archive: SubjectArchiveSnapshot = SubjectArchiveSnapshot(),
    val archiveSelectedDirectories: Set<Path> = emptySet(),
    val archiveSelectedSubjects: Set<String> = emptySet(),
    val selectedSessionCount: Int = 0,
    val sessionSelectionMode: Boolean = false,
    val savedSessionsViewMode: SavedSessionsViewMode = SavedSessionsViewMode.ARCHIVE,
    val expandedArchiveSubjects: Set<String> = emptySet(),
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
    fun map(session: StoredCaptureSession, cancellationCheck: () -> Unit = {}): SessionListItemUi {
        cancellationCheck()
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
            participant = metadata?.participant,
            bloodPressureCount = runCatchingCancellable {
                CaptureSessionRepository.expectedFiles(session.directory, cancellationCheck).bloodPressure
                    ?.let { CaptureBloodPressureSeries.scan(it, cancellationCheck = cancellationCheck) }
                    ?.completeDataRowCount?.toInt() ?: 0
            }.getOrDefault(0),
            systolicBp = metadata?.systolicBp,
            diastolicBp = metadata?.diastolicBp,
            sessionPrefix = SessionNamePolicy.parseCanonical(session.baseName)?.prefix,
            recordMode = metadata?.recordMode,
            plannedDurationSeconds = metadata?.plannedDurationSeconds,
            actualDurationSeconds = metadata?.endedUtc?.let { ended ->
                java.time.Duration.between(metadata.startedUtc, ended).seconds.coerceAtLeast(0L)
            },
        )
    }
}

class SessionsViewModel @JvmOverloads constructor(
    application: android.app.Application,
    savedStateHandle: SavedStateHandle = SavedStateHandle(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val inspectSession: (Path, () -> Unit) -> CaptureSessionInspection = { path, check ->
        CaptureSessionRepository.inspect(path, check)
    },
    private val recoverSession: (StoredCaptureSession, () -> Unit) -> CaptureSessionRecoveryResult = { session, check ->
        CaptureSessionRecoveryService.recover(session, CaptureSessionRecoveryService.suggestedBaseName(session),
            cancellationCheck = check)
    },
    private val saveBloodPressure: (Path, Int?, Int?) -> Unit = { directory, systolic, diastolic ->
        CaptureSessionMetadataEditor.updateReferenceBloodPressure(directory, systolic, diastolic)
    },
    private val signalBudget: () -> CaptureSignalLoadBudget = CaptureSignalLoadBudget::runtime,
) : AndroidViewModel(application) {
    private val app = application as PpgCollectorApplication
    private val _state = MutableStateFlow(SessionsUiState())
    private var refreshJob: Job? = null
    private var inspectionJob: Job? = null
    private var signalJob: Job? = null
    private var actionJob: Job? = null
    private var artifactJob: Job? = null
    private val analysisJobs = ConcurrentHashMap<Path, Job>()
    private val selectionGeneration = AtomicLong()
    private val signalGeneration = AtomicLong()
    private val refreshGeneration = AtomicLong()
    private val operationId = AtomicLong()
    private val artifactGeneration = AtomicLong()
    private var requestedArtifacts: List<CaptureArtifactSummary> = emptyList()
    private var recordingDirectory: Path? = null
    private val exports = PendingSessionExports(savedStateHandle)

    val state: StateFlow<SessionsUiState> = _state.asStateFlow()
    val pendingExport: PendingSessionExport? get() = exports.pending

    fun observeRecording(snapshot: CaptureRecordingSnapshot, pendingBaseName: String? = null) {
        val previous = recordingDirectory
        recordingDirectory = if (snapshot.state in setOf(CaptureRecordingState.STARTING,
                CaptureRecordingState.RECORDING, CaptureRecordingState.STOPPING)) {
            (if (snapshot.state == CaptureRecordingState.STARTING) pendingBaseName ?: snapshot.baseName else snapshot.baseName)
                ?.let(app.sessionsRoot::resolve)
        } else null
        _state.update { state -> state.copy(busyDirectories =
            (state.busyDirectories - listOfNotNull(previous).toSet()) + listOfNotNull(recordingDirectory)).recountSelection() }
    }

    private fun isBusy(directory: Path): Boolean {
        if (directory == recordingDirectory) return true
        val lease = app.sessionAccessRegistry.tryAcquire(directory, CaptureSessionAccessRegistry.Access.READ_SNAPSHOT)
            ?: return true
        lease.close()
        return false
    }

    fun refresh() {
        val previous = refreshJob
        previous?.cancel()
        val generation = refreshGeneration.incrementAndGet()
        refreshJob = viewModelScope.launch {
            previous?.join()
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val context = currentCoroutineContext()
                val result = withContext(ioDispatcher) {
                    val sessions = CaptureSessionRepository.listSessions(app.sessionsRoot) { context.ensureActive() }
                    val busy = sessions.filter { isBusy(it.directory) }.mapTo(linkedSetOf()) { it.directory }
                    val items = sessions.map { SessionListItemMapper.map(it) { context.ensureActive() } }
                    val errors = linkedMapOf<Path, String>()
                    val summaries = sessions.associate { session ->
                        context.ensureActive()
                        session.directory to if (session.directory in busy) {
                            errors[session.directory] = "会话占用中（准备、录制、收尾或文件操作）"
                            _state.value.artifactsBySession[session.directory].orEmpty()
                        } else try {
                            CaptureSessionOfflineAnalysisService.listArtifactSummaries(session) { context.ensureActive() }
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            errors[session.directory] = error.message ?: "分析索引读取失败"
                            _state.value.artifactsBySession[session.directory].orEmpty()
                        }
                    }
                    context.ensureActive()
                    val archive = SubjectArchiveRepository.rebuild(app.sessionsRoot, app.subjectsRoot)
                    context.ensureActive()
                    SessionsUiState(sessions = items, artifactsBySession = summaries, archive = archive,
                        busyDirectories = busy, artifactIndexErrors = errors)
                }
                if (refreshGeneration.get() == generation) _state.update { current ->
                    current.copy(sessions = result.sessions, artifactsBySession = result.artifactsBySession,
                        archive = result.archive, busyDirectories = result.busyDirectories,
                        artifactIndexErrors = result.artifactIndexErrors).recountSelection()
                }
            } catch (_: CancellationException) {
                // A successor owns the loading flag; this worker cannot publish into it.
            } catch (error: Exception) {
                if (refreshGeneration.get() == generation) _state.update { it.copy(error = error.message) }
            } finally {
                if (refreshGeneration.get() == generation) _state.update { it.copy(isLoading = false) }
            }
        }
    }

    fun toggleArchiveSession(directory: Path) {
        val busy = isBusy(directory)
        _state.update { state ->
            val next = state.archiveSelectedDirectories.toMutableSet()
            if (busy) next.remove(directory) else if (!next.add(directory)) next.remove(directory)
            state.copy(archiveSelectedDirectories = next,
                busyDirectories = if (busy) state.busyDirectories + directory else state.busyDirectories - directory)
                .recountSelection()
        }
    }

    fun toggleArchiveSubject(subject: String) {
        val directories = _state.value.archive.groups.firstOrNull { it.summary.subject == subject }
            ?.sessions?.map { it.session.directory }.orEmpty()
        val busy = directories.filter(::isBusy).toSet()
        val eligible = directories.toSet() - busy
        _state.update { state ->
            val selected = state.archiveSelectedDirectories
            state.copy(archiveSelectedDirectories = if (eligible.all { it in selected }) selected - eligible else selected + eligible,
                busyDirectories = (state.busyDirectories - directories.toSet()) + busy).recountSelection()
        }
    }

    fun clearArchiveSelection() {
        _state.update { it.copy(archiveSelectedDirectories = emptySet(), archiveSelectedSubjects = emptySet(), selectedSessionCount = 0) }
    }
    fun beginSessionSelection() { _state.update { it.copy(sessionSelectionMode = true) } }
    fun setSavedSessionsViewMode(mode: SavedSessionsViewMode) { _state.update { it.copy(savedSessionsViewMode = mode) } }
    fun toggleArchiveExpandedSubject(subject: String) {
        _state.update { state ->
            val next = state.expandedArchiveSubjects.toMutableSet()
            if (!next.add(subject)) next.remove(subject)
            state.copy(expandedArchiveSubjects = next)
        }
    }
    fun cancelSessionSelection() {
        clearArchiveSelection()
        _state.update { it.copy(sessionSelectionMode = false) }
    }
    fun selectAllArchive() = selectAllSessions()
    fun selectAllSessions() {
        val directories = _state.value.sessions.map { it.directory }.toSet()
        val busy = directories.filter(::isBusy).toSet()
        _state.update { it.copy(sessionSelectionMode = true, archiveSelectedDirectories = directories - busy,
            busyDirectories = busy).recountSelection() }
    }

    fun deleteSelectedSessions() {
        val directories = _state.value.archiveSelectedDirectories.toList()
        if (directories.isEmpty()) return
        startAction(SessionActionKind.DELETE) { _ ->
            val context = currentCoroutineContext()
            val results = withContext(ioDispatcher) {
                directories.map { directory ->
                    context.ensureActive()
                    CaptureSessionRepository.deleteSession(app.sessionsRoot, directory, app.sessionAccessRegistry)
                }
            }
            cancelSessionSelection()
            refresh()
            val deleted = results.count { it == CaptureSessionDeleteResult.DELETED }
            val busy = results.count { it == CaptureSessionDeleteResult.BUSY }
            SessionActionUi(kind = SessionActionKind.DELETE, message = "已删除 $deleted 个会话" +
                if (busy > 0) "；$busy 个会话占用中，未删除" else "")
        }
    }

    fun prepareSingleExport(item: SessionListItemUi): PendingSessionExport? {
        if (isBusy(item.directory)) {
            _state.update { it.copy(action = SessionActionUi(kind = SessionActionKind.EXPORT, error = "会话占用中，请稍后导出")) }
            return null
        }
        return exports.begin(PendingSessionExport.Kind.SINGLE, setOf(item.directory))
    }

    fun prepareArchiveExport(): PendingSessionExport? {
        val directories = _state.value.archiveSelectedDirectories
        val busy = directories.filter(::isBusy).toSet()
        _state.update { it.copy(busyDirectories = it.busyDirectories + busy,
            archiveSelectedDirectories = directories - busy).recountSelection() }
        return exports.begin(PendingSessionExport.Kind.ARCHIVE, directories - busy)
    }

    fun completeExportPicker(token: String, destination: Uri?) {
        val request = exports.consume(token) ?: return
        if (destination == null) {
            _state.update { if (it.action.isRunning) it else it.copy(action = cancelledSessionAction(request.actionKind())) }
            return
        }
        exportRequest(request) { staging, check ->
            try {
                app.contentResolver.openOutputStream(destination, "w")?.use { output ->
                    Files.newInputStream(staging).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            check()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        check()
                    }
                } ?: error("cannot open SAF destination")
            } catch (error: Exception) {
                // A cancelled/failed SAF copy is never reported as a successful empty ZIP.
                runCatching { android.provider.DocumentsContract.deleteDocument(app.contentResolver, destination) }
                throw error
            }
        }
    }

    internal fun completeExportPickerToFile(token: String, destination: Path) {
        val request = exports.consume(token) ?: return
        exportRequest(request) { staging, check -> check(); Files.copy(staging, destination); check() }
    }

    private fun PendingSessionExport.actionKind() = if (kind == PendingSessionExport.Kind.SINGLE)
        SessionActionKind.EXPORT else SessionActionKind.ARCHIVE_EXPORT

    private fun exportRequest(request: PendingSessionExport, copy: (Path, () -> Unit) -> Unit) {
        startAction(request.actionKind()) { id ->
            val context = currentCoroutineContext()
            withContext(ioDispatcher) {
                val staging = app.cacheDir.toPath().resolve("session-export-${request.token}.zip")
                val cancellation = CaptureExportCancellation { context.ensureActive() }
                try {
                    val directories = request.directories.map { Paths.get(it) }.toSet()
                    if (request.kind == PendingSessionExport.Kind.SINGLE) {
                        val session = findSession(directories.single()) { context.ensureActive() } ?: error("session no longer exists")
                        CaptureSessionExportService.exportZip(session, staging,
                            onProgress = { publishExportProgress(id, it) }, cancellation = cancellation)
                    } else {
                        CaptureArchiveExportService.export(app.sessionsRoot, app.subjectsRoot,
                            CaptureArchiveSelection(sessionDirectories = directories), staging,
                            onProgress = { publishExportProgress(id, it) }, cancellation = cancellation)
                    }
                    context.ensureActive()
                    copy(staging) { context.ensureActive() }
                } finally { Files.deleteIfExists(staging) }
            }
            SessionActionUi(kind = request.actionKind(), message = "导出完成（${request.directories.size} 个会话）")
        }
    }

    fun select(item: SessionListItemUi) = openSession(item, preserveAction = false)

    private fun openSession(item: SessionListItemUi, preserveAction: Boolean) {
        val previous = listOfNotNull(inspectionJob, signalJob, artifactJob, refreshJob)
        previous.forEach(Job::cancel)
        refreshGeneration.incrementAndGet()
        if (!preserveAction) {
            cancelAction()
            operationId.incrementAndGet()
        }
        releaseArtifacts()
        val generation = selectionGeneration.incrementAndGet()
        _state.update { it.copy(isLoading = false,
            action = if (preserveAction) it.action else SessionActionUi(), selected = SessionDetailUi(item)) }
        inspectionJob = viewModelScope.launch {
            previous.joinAll()
            val context = currentCoroutineContext()
            try {
                val (files, inspection) = withContext(ioDispatcher) {
                    val files = expectedFileNames(item.directory) { context.ensureActive() }
                    files to inspectSession(item.directory) { context.ensureActive() }
                }
                updateSelected(item.directory, generation) { it.copy(isInspecting = false, expectedFiles = files, inspection = inspection) }
            } catch (_: CancellationException) {
                updateSelected(item.directory, generation) { it.copy(isInspecting = false, error = "检查已取消") }
            } catch (error: Exception) {
                updateSelected(item.directory, generation) { it.copy(isInspecting = false, error = error.message) }
            }
        }
        val inspection = inspectionJob
        val signalToken = signalGeneration.incrementAndGet()
        signalJob = viewModelScope.launch {
            inspection?.join()
            loadSignal(item, generation, signalToken)
        }
    }

    fun retrySignal() {
        val item = _state.value.selected?.item ?: return
        val previous = signalJob
        previous?.cancel()
        val generation = selectionGeneration.get()
        val signalToken = signalGeneration.incrementAndGet()
        updateSelected(item.directory, generation) { it.copy(isLoadingSignal = true, signalError = null) }
        signalJob = viewModelScope.launch {
            previous?.join()
            inspectionJob?.join()
            loadSignal(item, generation, signalToken)
        }
    }

    private suspend fun loadSignal(item: SessionListItemUi, generation: Long, signalToken: Long) {
        fun publish(update: (SessionDetailUi) -> SessionDetailUi) {
            updateSelected(item.directory, generation) { if (signalGeneration.get() == signalToken) update(it) else it }
        }
        val context = currentCoroutineContext()
        try {
            val signal = withContext(computeDispatcher) {
                val session = withContext(ioDispatcher) { findSession(item.directory) { context.ensureActive() } }
                    ?: error("session no longer exists")
                CaptureSessionOfflineAnalysisService.loadSignalTrace(session,
                    cancellationCheck = { context.ensureActive() }, onPartial = { partial ->
                        context.ensureActive()
                        publish { it.copy(isLoadingSignal = false, signal = partial, signalError = null) }
                    }, budget = signalBudget())
            }
            publish { it.copy(isLoadingSignal = false, signal = signal, signalError = null) }
        } catch (_: CancellationException) {
            publish { it.copy(isLoadingSignal = false, signalError = "信号加载已取消") }
        } catch (error: Exception) {
            publish { it.copy(isLoadingSignal = false, signalError = error.message ?: "信号加载失败") }
        }
    }

    fun cancelInspection() {
        inspectionJob?.cancel()
        val selected = _state.value.selected ?: return
        updateSelected(selected.item.directory, selectionGeneration.get()) { it.copy(isInspecting = false, error = "检查已取消") }
    }
    fun clearSelection() {
        selectionGeneration.incrementAndGet()
        inspectionJob?.cancel()
        signalJob?.cancel()
        cancelAction()
        releaseArtifacts()
        _state.update { it.copy(selected = null) }
    }

    fun recoverSelected() {
        val item = _state.value.selected?.item ?: return
        startAction(SessionActionKind.RECOVER) { id ->
            // Finish/cancel our own readers before requesting the recovery snapshot lease.
            stopDetailReaders()
            val context = currentCoroutineContext()
            val (recovered, recoveredItem) = withContext(ioDispatcher) {
                val session = findSession(item.directory) { context.ensureActive() } ?: error("session no longer exists")
                val recovered = recoverSession(session) { context.ensureActive() }
                val stored = findSession(recovered.directory) { context.ensureActive() }
                    ?: error("recovery copy no longer exists")
                recovered to SessionListItemMapper.map(stored) { context.ensureActive() }
            }
            if (operationId.get() == id) {
                _state.update { it.copy(sessions = (it.sessions + recoveredItem).distinctBy { entry -> entry.directory }) }
                openSession(recoveredItem, preserveAction = true)
            }
            SessionActionUi(kind = SessionActionKind.RECOVER, message = "已创建安全恢复副本：${recovered.baseName}")
        }
    }

    fun updateSelectedBloodPressure(systolic: Int?, diastolic: Int?) {
        val item = _state.value.selected?.item ?: return
        if (_state.value.action.isRunning) return
        val validation = SessionDetailUiPolicy.bloodPressureError(systolic, diastolic)
        if (validation != null) {
            _state.update { it.copy(action = SessionActionUi(kind = SessionActionKind.UPDATE_BP, error = validation)) }
            return
        }
        startAction(SessionActionKind.UPDATE_BP) { _ ->
            stopDetailReaders()
            val context = currentCoroutineContext()
            val updated = withContext(ioDispatcher) {
                context.ensureActive()
                saveBloodPressure(item.directory, systolic, diastolic)
                findSession(item.directory) { context.ensureActive() }?.let { session ->
                    SessionListItemMapper.map(session) { context.ensureActive() }
                }
            }
            if (updated != null) updateSelected(item.directory, selectionGeneration.get()) { it.copy(item = updated) }
            refresh()
            SessionActionUi(kind = SessionActionKind.UPDATE_BP, message = "参考血压已保存")
        }
    }

    private suspend fun stopDetailReaders() {
        listOfNotNull(inspectionJob, signalJob, artifactJob).also { jobs ->
            jobs.forEach(Job::cancel)
            jobs.joinAll()
        }
    }

    private fun startAction(kind: SessionActionKind, block: suspend (Long) -> SessionActionUi) {
        actionJob?.cancel()
        val id = operationId.incrementAndGet()
        _state.update { it.copy(action = SessionActionUi(kind = kind, isRunning = true)) }
        actionJob = viewModelScope.launch {
            var terminal = cancelledSessionAction(kind)
            try {
                terminal = block(id)
                currentCoroutineContext().ensureActive()
            } catch (_: CancellationException) {
                terminal = cancelledSessionAction(kind)
            } catch (_: CaptureSessionExportException.Cancelled) {
                terminal = cancelledSessionAction(kind)
            } catch (error: Exception) {
                terminal = SessionActionUi(kind = kind, error = error.message ?: error.javaClass.simpleName)
            } finally {
                _state.update { if (operationId.get() == id) it.copy(action = terminal.copy(isRunning = false)) else it }
            }
        }
    }

    fun cancelExportPicker() { pendingExport?.let { completeExportPicker(it.token, null) } }
    fun cancelAction() {
        if (!_state.value.action.isRunning) return
        actionJob?.cancel()
        _state.update { it.copy(action = cancelledSessionAction(it.action.kind)) }
    }
    fun clearAction() {
        if (_state.value.action.isRunning) return
        operationId.incrementAndGet()
        _state.update { it.copy(action = SessionActionUi()) }
    }

    /** Retains at most the currently displayed detail report or comparison pair. */
    fun loadArtifacts(summaries: List<CaptureArtifactSummary>) {
        val requested = summaries.distinctBy { it.path }.take(2)
        if (requested == requestedArtifacts && _state.value.artifactErrors.isEmpty()) return
        val previous = artifactJob
        previous?.cancel()
        requestedArtifacts = requested
        val generation = artifactGeneration.incrementAndGet()
        val paths = requested.mapTo(linkedSetOf()) { it.path }
        _state.update { it.copy(loadedArtifacts = emptyMap(), artifactErrors = emptyMap(), loadingArtifacts = paths) }
        artifactJob = viewModelScope.launch {
            try {
                previous?.join()
                // Trace and report reads use exclusive snapshot leases.
                signalJob?.join()
                inspectionJob?.join()
                val context = currentCoroutineContext()
                for (summary in requested) {
                    try {
                        val artifact = withContext(ioDispatcher) {
                            CaptureSessionOfflineAnalysisService.readArtifact(summary.path,
                                cancellationCheck = { context.ensureActive() }, expectedSourceVersion = summary.sourceVersion)
                        }
                        _state.update { if (artifactGeneration.get() == generation) it.copy(
                            loadedArtifacts = it.loadedArtifacts + (summary.path to artifact),
                            loadingArtifacts = it.loadingArtifacts - summary.path) else it }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        _state.update { if (artifactGeneration.get() == generation) it.copy(
                            artifactErrors = it.artifactErrors + (summary.path to (error.message ?: "报告读取失败")),
                            loadingArtifacts = it.loadingArtifacts - summary.path) else it }
                    }
                }
            } catch (_: CancellationException) {
                _state.update { if (artifactGeneration.get() == generation) it.copy(
                    artifactErrors = it.artifactErrors + it.loadingArtifacts.associateWith { "报告读取已取消，可重试" }) else it }
            } finally {
                _state.update { if (artifactGeneration.get() == generation) it.copy(loadingArtifacts = emptySet()) else it }
            }
        }
    }

    fun releaseArtifacts() {
        artifactGeneration.incrementAndGet()
        artifactJob?.cancel()
        requestedArtifacts = emptyList()
        _state.update { it.copy(loadedArtifacts = emptyMap(), artifactErrors = emptyMap(), loadingArtifacts = emptySet()) }
    }

    fun startAnalysis(item: SessionListItemUi) {
        val previous = analysisJobs[item.directory]
        if (previous?.isActive == true) return
        _state.update { it.copy(analysisTasks = it.analysisTasks + (item.directory to
            SessionAnalysisTaskUi(item.baseName, SessionAnalysisTaskStatus.RUNNING))) }
        val job = viewModelScope.launch(computeDispatcher, start = CoroutineStart.LAZY) {
            val context = currentCoroutineContext()
            try {
                previous?.join()
                if (_state.value.selected?.item?.directory == item.directory) stopDetailReaders()
                val session = withContext(ioDispatcher) { findSession(item.directory) { context.ensureActive() } }
                    ?: error("session no longer exists")
                val artifact = CaptureSessionOfflineAnalysisService.analyzeAndSave(session,
                    progress = { progress ->
                        context.ensureActive()
                        _state.update { state -> if (analysisJobs[item.directory] !== context[Job]) state else state.copy(analysisTasks = state.analysisTasks + (item.directory to
                            SessionAnalysisTaskUi(item.baseName, SessionAnalysisTaskStatus.RUNNING, progress))) }
                    }, cancellationCheck = { context.ensureActive() })
                val summaries = withContext(ioDispatcher) {
                    CaptureSessionOfflineAnalysisService.listArtifactSummaries(session) { context.ensureActive() }
                }
                _state.update { if (analysisJobs[item.directory] !== context[Job]) it else it.copy(artifactsBySession = it.artifactsBySession + (item.directory to summaries),
                    analysisTasks = it.analysisTasks + (item.directory to SessionAnalysisTaskUi(
                        item.baseName, SessionAnalysisTaskStatus.COMPLETED, artifactName = artifact.path.fileName.toString()))) }
            } catch (_: CancellationException) {
                _state.update { if (analysisJobs[item.directory] !== context[Job]) it else it.copy(analysisTasks = it.analysisTasks + (item.directory to
                    SessionAnalysisTaskUi(item.baseName, SessionAnalysisTaskStatus.CANCELLED))) }
            } catch (error: Exception) {
                _state.update { if (analysisJobs[item.directory] !== context[Job]) it else it.copy(analysisTasks = it.analysisTasks + (item.directory to
                    SessionAnalysisTaskUi(item.baseName, SessionAnalysisTaskStatus.FAILED, error = error.message))) }
            } finally { analysisJobs.remove(item.directory, context[Job]) }
        }
        analysisJobs[item.directory] = job
        job.start()
    }
    fun cancelAnalysis(directory: Path) { analysisJobs[directory]?.cancel() }
    fun clearFinishedAnalysis(directory: Path) {
        if (_state.value.analysisTasks[directory]?.status != SessionAnalysisTaskStatus.RUNNING)
            _state.update { it.copy(analysisTasks = it.analysisTasks - directory) }
    }

    private fun findSession(directory: Path, check: () -> Unit = {}): StoredCaptureSession? =
        CaptureSessionRepository.listSessions(app.sessionsRoot, check).firstOrNull { it.directory == directory }

    private fun publishExportProgress(id: Long, progress: CaptureExportProgress) {
        _state.update { if (operationId.get() == id) it.copy(action = it.action.copy(
            bytesCopied = progress.bytesCopied, totalBytes = progress.totalBytes)) else it }
    }
    private fun updateSelected(directory: Path, generation: Long, update: (SessionDetailUi) -> SessionDetailUi) {
        _state.update { state ->
            val selected = state.selected
            if (selectionGeneration.get() != generation || selected?.item?.directory != directory) state
            else state.copy(selected = update(selected))
        }
    }
    private fun expectedFileNames(directory: Path, check: () -> Unit): List<String> {
        val files = CaptureSessionRepository.expectedFiles(directory, check)
        return listOf(files.raw, files.csv, files.metadata).map { path ->
            "${if (Files.isRegularFile(path)) "✓" else "✗"} ${path.fileName}"
        }
    }
}
