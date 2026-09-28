package com.example.ppgcollector_android

import android.os.Build
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ppgcollector_android.core.ble.BleCoordinator
import com.example.ppgcollector_android.core.ble.BleConnectionPhase
import com.example.ppgcollector_android.core.ble.BlePreviewSnapshot
import com.example.ppgcollector_android.data.session.CaptureAnalysisSnapshot
import com.example.ppgcollector_android.data.session.CaptureNotificationPermissionPolicy
import com.example.ppgcollector_android.data.session.CaptureStartContext
import com.example.ppgcollector_android.data.session.CaptureStartFailure
import com.example.ppgcollector_android.data.session.CaptureStartGate
import com.example.ppgcollector_android.data.session.CaptureGateDiskCache
import com.example.ppgcollector_android.data.session.CaptureGateDiskSnapshot
import com.example.ppgcollector_android.data.session.CaptureRecordingSnapshot
import com.example.ppgcollector_android.data.session.CaptureRecordingState
import com.example.ppgcollector_android.data.session.CaptureRecordMode
import com.example.ppgcollector_android.data.session.CaptureRecordModePolicy
import com.example.ppgcollector_android.data.session.CaptureParticipantSnapshot
import com.example.ppgcollector_android.data.session.CaptureParticipantDraft
import com.example.ppgcollector_android.data.session.CaptureSessionWriterPolicy
import com.example.ppgcollector_android.data.session.CaptureReferenceTimestamp
import com.example.ppgcollector_android.data.session.ManualBloodPressureEvent
import com.example.ppgcollector_android.data.session.SessionNamePolicy
import com.example.ppgcollector_android.data.session.CanonicalSessionIdentity
import com.example.ppgcollector_android.data.session.SubjectProfile
import com.example.ppgcollector_android.data.session.SubjectProfileStore
import com.example.ppgcollector_android.data.session.referenceBloodPressure
import com.example.ppgcollector_android.core.signal.LiveWaveformSnapshot
import com.example.ppgcollector_android.core.signal.StreamFreshness
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CaptureServiceBindingState {
    UNBOUND,
    BINDING,
    BOUND,
    DISCONNECTED,
    FAILED,
}

data class CaptureServiceObservation(
    val binding: CaptureServiceBindingState = CaptureServiceBindingState.UNBOUND,
    val recording: CaptureRecordingSnapshot = CaptureRecordingSnapshot(),
    val analysis: CaptureAnalysisSnapshot = CaptureAnalysisSnapshot(),
    val waveform: LiveWaveformSnapshot = LiveWaveformSnapshot(),
    val runtimeFailure: CaptureStartFailure? = null,
    val error: String? = null,
)

/** Low-frequency service slice; live waveform publications do not invalidate the capture form tree. */
data class CaptureServiceStatusObservation(
    val binding: CaptureServiceBindingState = CaptureServiceBindingState.UNBOUND,
    val recording: CaptureRecordingSnapshot = CaptureRecordingSnapshot(),
    val runtimeFailure: CaptureStartFailure? = null,
    val error: String? = null,
)

data class CaptureGateUiState(
    val sessionName: String = "",
    val failure: CaptureStartFailure? = CaptureStartFailure.InvalidSessionName,
    val failures: List<CaptureStartFailure> = emptyList(),
) {
    val canStart: Boolean
        get() = failures.isEmpty() && failure == null

    val messages: List<String>
        get() = failures.map(CaptureStartFailure::message)

    val message: String?
        get() = messages.firstOrNull() ?: failure?.message()
}

private fun CaptureStartFailure.message(): String = when (this) {
    CaptureStartFailure.AlreadyRecording -> "已有录制进行中"
    CaptureStartFailure.InvalidSessionName -> "录制名只能包含字母、数字、下划线和短横线"
    CaptureStartFailure.StreamNotFresh -> "等待新鲜数据流"
    CaptureStartFailure.DeviceNotReady -> "设备尚未进入接收状态"
    CaptureStartFailure.ForegroundServiceStartRejected ->
        "系统拒绝启动录制服务，请从前台页面重试并检查服务权限"
    CaptureStartFailure.NotificationPermissionDenied ->
        "通知权限未授予，请允许通知后再开始录制，否则持续采集状态可能无法显示"
    CaptureStartFailure.SessionAlreadyExists -> "会话名已存在"
    CaptureStartFailure.LogicalSessionAlreadyExists -> "次数重复：当前前缀下该被试的采集序号已存在"
    is CaptureStartFailure.InvalidPlannedDuration -> detail
    is CaptureStartFailure.ParticipantIncomplete ->
        "请补齐被试信息必填项：${fields.joinToString("、")}"
    CaptureStartFailure.InsufficientStorage -> "可用存储不足"
    is CaptureStartFailure.RecordingStartFailed -> "录制初始化失败：$detail"
}

/** Injectable Activity binding used by the real client and ViewModel JVM tests. */
interface CaptureServiceBinding {
    val state: StateFlow<CaptureServiceObservation>
    fun bind(createIfNeeded: Boolean = false): Boolean
    fun unbind()
    fun stopRecording()
    fun captureReferenceTimestamp(): CaptureReferenceTimestamp?
    fun commitManualBloodPressure(event: ManualBloodPressureEvent): Boolean
    fun updateParticipantProfile(participant: CaptureParticipantSnapshot?): Boolean
    fun clearRuntimeFailure()
}

/**
 * Owns only the Activity-side binding. The foreground service remains the
 * recording owner; this object is safe to bind again after Activity recreation.
 */
class CaptureServiceClient(
    context: Context,
    private val scope: CoroutineScope,
) : CaptureServiceBinding {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(CaptureServiceObservation())
    private var requested = false
    private var bound = false
    private var binder: CaptureForegroundService.LocalBinder? = null
    private var recordingJob: Job? = null
    private var analysisJob: Job? = null
    private var waveformJob: Job? = null
    private var runtimeFailureJob: Job? = null

    override val state: StateFlow<CaptureServiceObservation> = _state.asStateFlow()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (!requested) {
                runCatching { appContext.unbindService(this) }
                return
            }
            val localBinder = service as? CaptureForegroundService.LocalBinder
            if (localBinder == null) {
                fail("unexpected capture service binder")
                return
            }
            binder = localBinder
            bound = true
            _state.update {
                it.copy(
                    binding = CaptureServiceBindingState.BOUND,
                    recording = localBinder.snapshot(),
                    analysis = localBinder.analysisSnapshot(),
                    waveform = localBinder.waveformSnapshot(),
                    runtimeFailure = localBinder.runtimeFailureFlow().value,
                    error = null,
                )
            }
            recordingJob?.cancel()
            analysisJob?.cancel()
            waveformJob?.cancel()
            runtimeFailureJob?.cancel()
            recordingJob = observeRecording(localBinder)
            analysisJob = observeAnalysis(localBinder)
            waveformJob = observeWaveform(localBinder)
            runtimeFailureJob = observeRuntimeFailure(localBinder)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            clearObservers()
            requested = false
            bound = false
            binder = null
            _state.update {
                it.copy(
                    binding = CaptureServiceBindingState.DISCONNECTED,
                    error = "capture service disconnected; rebind on next Activity start",
                )
            }
        }

        override fun onBindingDied(name: ComponentName) {
            clearObservers()
            requested = false
            bound = false
            binder = null
            _state.update {
                it.copy(
                    binding = CaptureServiceBindingState.DISCONNECTED,
                    error = "capture service binding died; rebind required",
                )
            }
        }

        override fun onNullBinding(name: ComponentName) {
            fail("capture service returned no binder")
        }
    }

    override fun bind(createIfNeeded: Boolean): Boolean {
        if (requested) return true
        requested = true
        val accepted = runCatching {
            appContext.bindService(
                Intent(appContext, CaptureForegroundService::class.java),
                connection,
                if (createIfNeeded) Context.BIND_AUTO_CREATE else 0,
            )
        }.getOrElse {
            fail(it.message ?: it::class.simpleName ?: "bind failed")
            false
        }
        if (accepted) {
            if (!bound) {
                _state.update { it.copy(binding = CaptureServiceBindingState.BINDING, error = null) }
            }
        } else if (createIfNeeded) {
            fail("capture service bind rejected")
        } else {
            // No running recording service is the normal idle state. Do not
            // create one only to observe an IDLE controller and a Main timer.
            requested = false
            publishUnbound()
        }
        return accepted
    }

    override fun unbind() {
        requested = false
        clearObservers()
        if (bound) {
            runCatching { appContext.unbindService(connection) }
        }
        bound = false
        binder = null
        publishUnbound()
    }

    override fun stopRecording() {
        binder?.stop()
    }

    override fun captureReferenceTimestamp(): CaptureReferenceTimestamp? = binder?.captureReferenceTimestamp()

    override fun commitManualBloodPressure(event: ManualBloodPressureEvent): Boolean =
        binder?.commitManualBloodPressure(event) == true

    override fun updateParticipantProfile(participant: CaptureParticipantSnapshot?): Boolean =
        state.value.recording.sessionId?.let { sessionId ->
            binder?.updateParticipantProfile(sessionId, participant) == true
        } == true

    override fun clearRuntimeFailure() {
        binder?.clearRuntimeFailure()
        _state.update { it.copy(runtimeFailure = null) }
    }

    private fun observeRecording(localBinder: CaptureForegroundService.LocalBinder): Job =
        scope.launch {
            localBinder.recordingFlow().collect { recording ->
                _state.update { it.copy(recording = recording) }
            }
        }

    private fun observeAnalysis(localBinder: CaptureForegroundService.LocalBinder): Job =
        scope.launch {
            localBinder.analysisFlow().collect { analysis ->
                _state.update { it.copy(analysis = analysis) }
            }
        }

    private fun observeWaveform(localBinder: CaptureForegroundService.LocalBinder): Job =
        scope.launch {
            localBinder.waveformFlow().collect { waveform ->
                _state.update { it.copy(waveform = waveform) }
            }
        }

    private fun observeRuntimeFailure(localBinder: CaptureForegroundService.LocalBinder): Job =
        scope.launch {
            localBinder.runtimeFailureFlow().collect { failure ->
                _state.update { it.copy(runtimeFailure = failure) }
            }
        }

    private fun clearObservers() {
        recordingJob?.cancel()
        analysisJob?.cancel()
        recordingJob = null
        analysisJob = null
        waveformJob?.cancel()
        waveformJob = null
        runtimeFailureJob?.cancel()
        runtimeFailureJob = null
    }

    private fun fail(message: String) {
        clearObservers()
        requested = false
        bound = false
        binder = null
        _state.update {
            it.copy(binding = CaptureServiceBindingState.FAILED, error = message)
        }
    }

    private fun publishUnbound() {
        _state.value = CaptureServiceObservation()
    }
}

class CaptureViewModel @JvmOverloads constructor(
    application: android.app.Application,
    private val formLifecycle: CaptureFormLifecycle = CaptureFormLifecycle { subject ->
        SubjectProfileStore((application as PpgCollectorApplication).subjectsRoot).read(subject)
    },
    serviceClientFactory: (Context, CoroutineScope) -> CaptureServiceBinding = ::CaptureServiceClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val bleCoordinator: BleCoordinator =
        (application as PpgCollectorApplication).bleCoordinator,
) : AndroidViewModel(application) {
    private data class CaptureFormBootstrap(
        val initialName: String,
        val suggestedName: String,
        val disk: CaptureGateDiskSnapshot,
        val participant: CaptureParticipantDraft,
        val profileError: CaptureProfileReadError? = null,
    )

    private data class CaptureGateForm(
        val participant: CaptureParticipantDraft,
        val recordMode: CaptureRecordMode,
        val plannedDurationText: String,
    )

    private val collectorApplication = application as PpgCollectorApplication
    private val serviceClient = serviceClientFactory(application, viewModelScope)
    private val _sessionName = MutableStateFlow("")
    private val _sessionPrefix = MutableStateFlow(
        com.example.ppgcollector_android.data.session.SessionNamePrefix.fromWireValue(
            application.getSharedPreferences("capture_preferences", android.content.Context.MODE_PRIVATE)
                .getString("session_prefix", "PPG").orEmpty(),
        ) ?: com.example.ppgcollector_android.data.session.SessionNamePrefix.PPG,
    )
    private val _participantDraft = MutableStateFlow(CaptureParticipantDraft())
    private val _recordMode = MutableStateFlow(CaptureRecordMode.TIMED)
    private val _plannedDurationText = MutableStateFlow(
        CaptureRecordModePolicy.defaultDurationSeconds.toString(),
    )
    private var participantDraftSubject: String? = null
    private var participantDraftDirtySubject: String? = null
    private val _captureGate = MutableStateFlow(CaptureGateUiState())
    private val _gateDiskSnapshot = MutableStateFlow(CaptureGateDiskSnapshot(false, false, null))
    private val _notificationPermissionFailure = MutableStateFlow<CaptureStartFailure?>(null)
    private val _bloodPressureReference = formLifecycle.bloodPressureReference
    private val _profileReadError = MutableStateFlow<CaptureProfileReadError?>(null)
    private val _startPending = MutableStateFlow(false)
    private val diskCache = CaptureGateDiskCache(
        sessionsRoot = { collectorApplication.sessionsRoot },
    )
    private var gateDiskJob: Job? = null
    private var participantPrefillJob: Job? = null
    private var firstActivityStart = true

    val serviceState: StateFlow<CaptureServiceObservation> = serviceClient.state
    val serviceStatus: StateFlow<CaptureServiceStatusObservation> = combine(
        serviceClient.state, _profileReadError, _startPending,
    ) { state, profileError, startPending ->
            CaptureServiceStatusObservation(
                binding = state.binding,
                recording = if (startPending && state.recording.state in setOf(
                        CaptureRecordingState.IDLE, CaptureRecordingState.FINALIZED, CaptureRecordingState.FAILED)) {
                    state.recording.copy(state = CaptureRecordingState.STARTING)
                } else state.recording,
                runtimeFailure = state.runtimeFailure,
                error = state.error ?: state.recording.lastError ?: profileError?.message,
            )
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, CaptureServiceStatusObservation())
    val waveformState: StateFlow<LiveWaveformSnapshot> = serviceClient.state
        .map { state -> state.waveform }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, LiveWaveformSnapshot())
    val analysisState: StateFlow<CaptureAnalysisSnapshot> = serviceClient.state
        .map { state -> state.analysis }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, CaptureAnalysisSnapshot())
    val profileReadError: StateFlow<CaptureProfileReadError?> = _profileReadError.asStateFlow()
    val startPending: StateFlow<Boolean> = _startPending.asStateFlow()
    val sessionName: StateFlow<String> = _sessionName.asStateFlow()
    val sessionPrefix: StateFlow<com.example.ppgcollector_android.data.session.SessionNamePrefix> =
        _sessionPrefix.asStateFlow()
    val participantDraft: StateFlow<CaptureParticipantDraft> = _participantDraft.asStateFlow()
    val recordMode: StateFlow<CaptureRecordMode> = _recordMode.asStateFlow()
    val plannedDurationText: StateFlow<String> = _plannedDurationText.asStateFlow()
    val captureGate: StateFlow<CaptureGateUiState> = _captureGate.asStateFlow()
    val previewState: StateFlow<BlePreviewSnapshot> = bleCoordinator.previewFlow
    val bloodPressureReference: StateFlow<CaptureReferenceTimestamp?> = _bloodPressureReference.asStateFlow()

    init {
        _sessionName.value = "${_sessionPrefix.value.wireValue}-${SessionNamePolicy.exampleSuggestedName.substringAfter('-')}"
        initializeCaptureForm()
        viewModelScope.launch {
            val gateForm = combine(
                _participantDraft,
                _recordMode,
                _plannedDurationText,
            ) { participant, recordMode, duration ->
                CaptureGateForm(participant, recordMode, duration)
            }
            combine(
                combine(_sessionName, _gateDiskSnapshot) { name, disk -> name to disk },
                bleCoordinator.snapshotFlow
                    .map { ble -> ble.phase to ble.freshness }
                    .distinctUntilChanged(),
                serviceStatus,
                _notificationPermissionFailure,
                gateForm,
            ) { nameAndDisk, bleSlice, service, notificationFailure, form ->
                val name = nameAndDisk.first
                val disk = nameAndDisk.second
                val runtimeFailure = scopedCaptureRuntimeFailure(
                    service.runtimeFailure,
                    name,
                    disk,
                )
                val failures = evaluateGate(
                    name,
                    disk,
                    bleSlice.first,
                    bleSlice.second,
                    service.recording,
                    form.participant,
                    form.recordMode,
                    form.plannedDurationText,
                ) + listOfNotNull(notificationFailure, runtimeFailure)
                CaptureGateUiState(
                    sessionName = name,
                    failures = failures,
                    failure = failures.firstOrNull(),
                )
            }.collect { _captureGate.value = it }
        }
        viewModelScope.launch {
            collectorApplication.captureTerminal.collect { terminal ->
                if (terminal != null && formLifecycle.observe(terminal)) clearSessionScopedFormAfterRecording()
            }
        }
        viewModelScope.launch {
            serviceClient.state
                .map { Triple(it.recording, it.runtimeFailure, it.binding) }
                .distinctUntilChanged()
                .collect { (recording, runtimeFailure, binding) ->
                    if (recording.state in setOf(CaptureRecordingState.STARTING, CaptureRecordingState.RECORDING,
                            CaptureRecordingState.STOPPING) || runtimeFailure != null || binding == CaptureServiceBindingState.FAILED) {
                        _startPending.value = false
                    }
                    if (formLifecycle.observe(recording)) {
                        _startPending.value = false
                        clearSessionScopedFormAfterRecording()
                    }
                    if (runtimeFailure == CaptureStartFailure.SessionAlreadyExists ||
                        runtimeFailure == CaptureStartFailure.LogicalSessionAlreadyExists
                    ) {
                        diskCache.invalidate()
                        refreshGateDiskSnapshot(immediate = true)
                    }
                }
        }
    }

    fun setSessionName(value: String) {
        val normalized = SessionNamePolicy.normalizeCanonical(value) ?: value
        val identity = SessionNamePolicy.parseCanonical(normalized)
        _sessionName.value = normalized
        identity?.let { _sessionPrefix.value = it.prefix }
        if (shouldPrefillParticipantDraft(
                participantDraftSubject,
                participantDraftDirtySubject,
                identity?.subject,
            )
        ) {
            if (identity?.subject != participantDraftSubject) {
                participantDraftDirtySubject = null
                participantDraftSubject = identity?.subject
                // Do not leave the previous subject's values startable while
                // the new profile is being read on the I/O dispatcher.
                _participantDraft.value = CaptureParticipantDraft()
            }
            prefillParticipantFor(normalized)
        }
        serviceClient.clearRuntimeFailure()
        refreshGateDiskSnapshot()
    }

    fun setSessionPrefix(prefix: com.example.ppgcollector_android.data.session.SessionNamePrefix) {
        _sessionPrefix.value = prefix
        getApplication<android.app.Application>()
            .getSharedPreferences("capture_preferences", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("session_prefix", prefix.wireValue)
            .apply()
        val identity = SessionNamePolicy.parseCanonical(_sessionName.value)
        if (identity != null) {
            viewModelScope.launch {
                val nextName = withContext(ioDispatcher) {
                    "${prefix.wireValue}-${identity.subject}-${SessionNamePolicy.nextSequenceForSubject(
                        collectorApplication.sessionsRoot, prefix, identity.subject,
                    )}"
                }
                if (_sessionPrefix.value == prefix) setSessionName(nextName)
            }
        }
    }

    fun useSuggestedSessionName() {
        viewModelScope.launch {
            val suggested = withContext(ioDispatcher) {
                SessionNamePolicy.suggestedBaseNameOrExample(
                    collectorApplication.sessionsRoot,
                    _sessionPrefix.value,
                )
            }
            setSessionName(suggested)
        }
    }

    fun setParticipantDraft(value: CaptureParticipantDraft) {
        participantDraftSubject = SessionNamePolicy.parseCanonical(_sessionName.value)?.subject
        participantDraftDirtySubject = participantDraftSubject
        _participantDraft.value = value
        serviceClient.clearRuntimeFailure()
    }

    fun setRecordMode(value: CaptureRecordMode) {
        _recordMode.value = value
        serviceClient.clearRuntimeFailure()
    }

    fun setPlannedDurationText(value: String) {
        _plannedDurationText.value = value.filter(Char::isDigit).take(4)
        serviceClient.clearRuntimeFailure()
    }

    fun resetParticipantDraftFromSubject() {
        participantDraftDirtySubject = null
        prefillParticipantFor(_sessionName.value)
    }

    fun onStart() {
        if (firstActivityStart) {
            firstActivityStart = false
        } else {
            diskCache.invalidate()
            refreshGateDiskSnapshot()
        }
        serviceClient.bind(createIfNeeded = false)
    }

    fun onStop() = serviceClient.unbind()

    fun stopRecording() = serviceClient.stopRecording()

    fun openManualBloodPressure() {
        _bloodPressureReference.value = serviceClient.captureReferenceTimestamp()
    }

    fun dismissManualBloodPressure() {
        _bloodPressureReference.value = null
    }

    fun commitManualBloodPressure(event: ManualBloodPressureEvent): Boolean {
        val accepted = serviceClient.commitManualBloodPressure(event)
        if (accepted) _bloodPressureReference.value = null
        return accepted
    }

    fun updateParticipantProfile(): Boolean {
        val participant = participantForSession(_sessionName.value)
        return serviceClient.updateParticipantProfile(participant)
    }

    fun setNotificationPermissionResult(granted: Boolean) {
        _notificationPermissionFailure.value = CaptureNotificationPermissionPolicy.failureFor(
            Build.VERSION.SDK_INT,
            granted,
        )
    }

    fun startRecording(notificationPermissionGranted: Boolean = false) {
        if (_startPending.value) return
        val gate = _captureGate.value
        if (!gate.canStart &&
            !(notificationPermissionGranted &&
                gate.failure == CaptureStartFailure.NotificationPermissionDenied)
        ) return
        val ble = bleCoordinator.snapshot
        val deviceName = ble.phase.deviceId?.let { id ->
            ble.discoveredDevices.firstOrNull { it.id == id }?.name
        }
        // The foreground service is the transaction owner.  Do not create a
        // profile revision before it has accepted the recording start.
        val participant = participantForSession(gate.sessionName)
        val bp = _participantDraft.value.referenceBloodPressure()
        _startPending.value = true
        formLifecycle.startRequested(gate.sessionName)
        runCatching {
            androidx.core.content.ContextCompat.startForegroundService(
                getApplication(),
                CaptureForegroundService.startIntent(
                    getApplication(),
                    sessionName = gate.sessionName,
                    deviceName = deviceName,
                    participant = participant,
                    systolicBp = bp?.first,
                    diastolicBp = bp?.second,
                    recordMode = _recordMode.value,
                    plannedDurationSeconds = CaptureRecordModePolicy.effectiveDurationSeconds(
                        _recordMode.value,
                        _plannedDurationText.value,
                    ),
                ),
            )
        }.onSuccess {
            serviceClient.bind(createIfNeeded = true)
        }.onFailure { error ->
            _startPending.value = false
            _captureGate.value = gate.copy(failure = mapCaptureServiceStartFailure(error))
        }
    }

    fun validateSessionName(value: String = _sessionName.value) =
        SessionNamePolicy.validateSyntax(value)

    private fun prefillParticipantFor(name: String) {
        val identity = SessionNamePolicy.parseCanonical(name)
        participantPrefillJob?.cancel()
        participantPrefillJob = viewModelScope.launch {
            val result = withContext(ioDispatcher) { formLifecycle.readParticipant(identity) }
            if (_sessionName.value == name) _profileReadError.value = result.error
            if (participantDraftDirtySubject != identity?.subject && _sessionName.value == name) {
                _participantDraft.value = result.participant
                participantDraftSubject = identity?.subject
            }
        }
    }

    private fun participantForSession(name: String): CaptureParticipantSnapshot? {
        val identity = SessionNamePolicy.parseCanonical(name)
        if (identity == null && _participantDraft.value == CaptureParticipantDraft()) return null
        return _participantDraft.value.toSnapshot(identity)
    }

    private fun evaluateGate(
        name: String,
        disk: CaptureGateDiskSnapshot,
        phase: BleConnectionPhase,
        freshness: StreamFreshness,
        recording: CaptureRecordingSnapshot,
        participant: CaptureParticipantDraft,
        recordMode: CaptureRecordMode,
        plannedDurationText: String,
    ): List<CaptureStartFailure> {
        val root = collectorApplication.sessionsRoot
        val diskMatchesName = disk.sessionName == name
        return CaptureStartGate.validateAll(
            CaptureStartContext(
                isRecording = recording.state != com.example.ppgcollector_android.data.session.CaptureRecordingState.IDLE &&
                    recording.state != com.example.ppgcollector_android.data.session.CaptureRecordingState.FINALIZED &&
                    recording.state != com.example.ppgcollector_android.data.session.CaptureRecordingState.FAILED,
                phase = phase,
                freshness = freshness,
                sessionsRoot = root,
                sessionName = name,
                availableBytes = disk.availableBytes.takeIf { diskMatchesName },
                participant = participant,
                sessionNameIsDuplicate = disk.duplicate && diskMatchesName,
                sessionNameIsLogicalDuplicate = disk.logicalDuplicate && diskMatchesName,
                recordMode = recordMode,
                plannedDurationSeconds = CaptureRecordModePolicy.effectiveDurationSeconds(
                    recordMode,
                    plannedDurationText,
                ),
            ),
        )
    }

    private fun initializeCaptureForm() {
        val initialName = _sessionName.value
        val prefix = _sessionPrefix.value
        viewModelScope.launch {
            val bootstrap = withContext(ioDispatcher) {
                val suggested = SessionNamePolicy.suggestedBaseNameOrExample(
                    collectorApplication.sessionsRoot,
                    prefix,
                )
                diskCache.invalidate()
                val disk = diskCache.snapshot(suggested)
                val identity = SessionNamePolicy.parseCanonical(suggested)
                val profile = formLifecycle.readParticipant(identity)
                CaptureFormBootstrap(
                    initialName = initialName,
                    suggestedName = suggested,
                    disk = disk,
                    participant = profile.participant,
                    profileError = profile.error,
                )
            }
            if (_sessionName.value == bootstrap.initialName) {
                _sessionName.value = bootstrap.suggestedName
                _gateDiskSnapshot.value = bootstrap.disk
                _profileReadError.value = bootstrap.profileError
                val subject = SessionNamePolicy.parseCanonical(bootstrap.suggestedName)?.subject
                if (participantDraftDirtySubject != subject) {
                    _participantDraft.value = bootstrap.participant
                    participantDraftSubject = subject
                }
            }
        }
    }

    private fun refreshGateDiskSnapshot(immediate: Boolean = false) {
        gateDiskJob?.cancel()
        val name = _sessionName.value
        gateDiskJob = viewModelScope.launch {
            if (!immediate) delay(120L)
            val next = withContext(ioDispatcher) {
                diskCache.snapshot(name)
            }
            _gateDiskSnapshot.value = next

        }
    }

    private fun clearSessionScopedFormAfterRecording() {
        _participantDraft.value = _participantDraft.value.clearSessionScopedFields()
        _bloodPressureReference.value = null
        serviceClient.clearRuntimeFailure()
        diskCache.invalidate()
        refreshGateDiskSnapshot(immediate = true)
    }

    override fun onCleared() {
        serviceClient.unbind()
        super.onCleared()
    }
}

/** Maps API 31+ background-start and API 34+ FGS permission failures to an actionable gate. */
internal fun mapCaptureServiceStartFailure(error: Throwable): CaptureStartFailure =
    if (error is SecurityException ||
        error::class.java.name == "android.app.ForegroundServiceStartNotAllowedException" ||
        error::class.java.name.endsWith("ForegroundServiceTypeException")
    ) {
        CaptureStartFailure.ForegroundServiceStartRejected
    } else {
        CaptureStartFailure.DeviceNotReady
    }

internal fun scopedCaptureRuntimeFailure(
    failure: CaptureStartFailure?,
    sessionName: String,
    disk: CaptureGateDiskSnapshot,
): CaptureStartFailure? = when (failure) {
    CaptureStartFailure.SessionAlreadyExists -> failure.takeIf {
        disk.sessionName == sessionName && disk.duplicate
    }
    CaptureStartFailure.LogicalSessionAlreadyExists -> failure.takeIf {
        disk.sessionName == sessionName && disk.logicalDuplicate
    }
    else -> failure
}

internal fun shouldPrefillParticipantDraft(
    currentDraftSubject: String?,
    dirtySubject: String?,
    nextSubject: String?,
): Boolean = nextSubject != currentDraftSubject || dirtySubject != nextSubject


/** Typed, retryable profile error; it does not prevent manual participant entry. */
data class CaptureProfileReadError(val subjectId: String, val detail: String) {
    val message: String get() = "被试 $subjectId 档案读取失败：$detail。可手动填写，或重新载入档案重试。"
}

data class CaptureProfileReadResult(
    val participant: CaptureParticipantDraft,
    val error: CaptureProfileReadError? = null,
)

/** Production form ownership used by bootstrap, prefill, and service observations. */
class CaptureFormLifecycle(
    private val readProfile: (String) -> SubjectProfile?,
) {
    val bloodPressureReference = MutableStateFlow<CaptureReferenceTimestamp?>(null)
    private var pendingName: String? = null
    private var acceptedSessionId: String? = null
    private var acceptedToken: Long? = null

    fun startRequested(baseName: String) {
        pendingName = baseName
        acceptedSessionId = null
        acceptedToken = null
    }

    /** Unbind/IDLE and disk duplicate facts never clear a recording's form. */
    fun observe(recording: CaptureRecordingSnapshot): Boolean {
        if (recording.state in setOf(CaptureRecordingState.RECORDING, CaptureRecordingState.STOPPING) &&
            (pendingName == null || pendingName == recording.baseName)) {
            acceptedSessionId = recording.sessionId
            acceptedToken = recording.sessionToken
        }
        val terminal = recording.state == CaptureRecordingState.FINALIZED || recording.state == CaptureRecordingState.FAILED
        if (!terminal || acceptedSessionId == null || recording.sessionId != acceptedSessionId ||
            recording.sessionToken != acceptedToken) return false
        acceptedSessionId = null
        acceptedToken = null
        pendingName = null
        bloodPressureReference.value = null
        return true
    }

    fun readParticipant(identity: CanonicalSessionIdentity?): CaptureProfileReadResult {
        if (identity == null) return CaptureProfileReadResult(CaptureParticipantDraft())
        return try {
            val latest = readProfile(identity.subject)?.latest
            CaptureProfileReadResult(CaptureParticipantDraft.fromSnapshot(latest?.asParticipantSnapshot(identity.subject)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            CaptureProfileReadResult(CaptureParticipantDraft(),
                CaptureProfileReadError(identity.subject, error.message ?: error::class.java.simpleName))
        }
    }
}
