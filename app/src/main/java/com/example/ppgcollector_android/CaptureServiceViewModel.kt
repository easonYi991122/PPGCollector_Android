package com.example.ppgcollector_android

import android.os.Build
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ppgcollector_android.core.ble.BleCoordinatorSnapshot
import com.example.ppgcollector_android.core.ble.BlePreviewSnapshot
import com.example.ppgcollector_android.data.session.CaptureAnalysisSnapshot
import com.example.ppgcollector_android.data.session.CaptureNotificationPermissionPolicy
import com.example.ppgcollector_android.data.session.CaptureStartContext
import com.example.ppgcollector_android.data.session.CaptureStartFailure
import com.example.ppgcollector_android.data.session.CaptureStartGate
import com.example.ppgcollector_android.data.session.CaptureRecordingSnapshot
import com.example.ppgcollector_android.data.session.CaptureRecordMode
import com.example.ppgcollector_android.data.session.CaptureRecordModePolicy
import com.example.ppgcollector_android.data.session.CaptureParticipantSnapshot
import com.example.ppgcollector_android.data.session.CaptureParticipantDraft
import com.example.ppgcollector_android.data.session.CaptureSessionWriterPolicy
import com.example.ppgcollector_android.data.session.CaptureReferenceTimestamp
import com.example.ppgcollector_android.data.session.ManualBloodPressureEvent
import com.example.ppgcollector_android.data.session.SessionNamePolicy
import com.example.ppgcollector_android.data.session.CanonicalSessionIdentity
import com.example.ppgcollector_android.data.session.SubjectProfileStore
import com.example.ppgcollector_android.data.session.SubjectProfileRevision
import com.example.ppgcollector_android.data.session.referenceBloodPressure
import com.example.ppgcollector_android.core.signal.LiveWaveformSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
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
import java.nio.file.Files

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
    is CaptureStartFailure.ParticipantIncomplete ->
        "请补齐被试信息必填项：${fields.joinToString("、")}"
    CaptureStartFailure.InsufficientStorage -> "可用存储不足"
}

/**
 * Owns only the Activity-side binding. The foreground service remains the
 * recording owner; this object is safe to bind again after Activity recreation.
 */
class CaptureServiceClient(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(CaptureServiceObservation())
    private var requested = false
    private var bound = false
    private var binder: CaptureForegroundService.LocalBinder? = null
    private var recordingJob: Job? = null
    private var analysisJob: Job? = null
    private var waveformJob: Job? = null
    private var runtimeFailureJob: Job? = null

    val state: StateFlow<CaptureServiceObservation> = _state.asStateFlow()

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

    fun bind() {
        if (requested) return
        requested = true
        _state.update { it.copy(binding = CaptureServiceBindingState.BINDING, error = null) }
        val accepted = runCatching {
            appContext.bindService(
                Intent(appContext, CaptureForegroundService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }.getOrElse {
            fail(it.message ?: it::class.simpleName ?: "bind failed")
            false
        }
        if (!accepted) fail("capture service bind rejected")
    }

    fun unbind() {
        requested = false
        clearObservers()
        if (bound) {
            runCatching { appContext.unbindService(connection) }
        }
        bound = false
        binder = null
        publishUnbound()
    }

    fun stopRecording() {
        binder?.stop()
    }

    fun captureReferenceTimestamp(): CaptureReferenceTimestamp? = binder?.captureReferenceTimestamp()

    fun commitManualBloodPressure(event: ManualBloodPressureEvent): Boolean =
        binder?.commitManualBloodPressure(event) == true

    fun updateParticipantProfile(participant: CaptureParticipantSnapshot?): Boolean =
        binder?.updateParticipantProfile(participant) == true

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

class CaptureViewModel(application: android.app.Application) : AndroidViewModel(application) {
    private val collectorApplication = application as PpgCollectorApplication
    private val serviceClient = CaptureServiceClient(application, viewModelScope)
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
    private var participantDraftDirty = false
    private val _captureGate = MutableStateFlow(CaptureGateUiState())
    private val _notificationPermissionFailure = MutableStateFlow<CaptureStartFailure?>(null)
    private val _bloodPressureReference = MutableStateFlow<CaptureReferenceTimestamp?>(null)

    val serviceState: StateFlow<CaptureServiceObservation> = serviceClient.state
    val serviceStatus: StateFlow<CaptureServiceStatusObservation> = serviceClient.state
        .map { state ->
            CaptureServiceStatusObservation(
                binding = state.binding,
                recording = state.recording,
                runtimeFailure = state.runtimeFailure,
                error = state.error,
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
    val sessionName: StateFlow<String> = _sessionName.asStateFlow()
    val sessionPrefix: StateFlow<com.example.ppgcollector_android.data.session.SessionNamePrefix> =
        _sessionPrefix.asStateFlow()
    val participantDraft: StateFlow<CaptureParticipantDraft> = _participantDraft.asStateFlow()
    val recordMode: StateFlow<CaptureRecordMode> = _recordMode.asStateFlow()
    val plannedDurationText: StateFlow<String> = _plannedDurationText.asStateFlow()
    val captureGate: StateFlow<CaptureGateUiState> = _captureGate.asStateFlow()
    val previewState: StateFlow<BlePreviewSnapshot> = collectorApplication.bleCoordinator.previewFlow
    val bloodPressureReference: StateFlow<CaptureReferenceTimestamp?> = _bloodPressureReference.asStateFlow()

    init {
        _sessionName.value = SessionNamePolicy
            .suggestedBaseNameOrExample(collectorApplication.sessionsRoot)
        viewModelScope.launch {
            combine(
                _sessionName,
                collectorApplication.bleCoordinator.snapshotFlow
                    .map { ble ->
                        ble.copy(
                            diagnostics = com.example.ppgcollector_android.core.ble.BleGattDiagnostics(),
                            attemptDiagnostics =
                                com.example.ppgcollector_android.core.ble.BleConnectionAttemptDiagnostics(),
                        )
                    }
                    .distinctUntilChanged(),
                serviceClient.state,
                _notificationPermissionFailure,
                _participantDraft,
            ) { name, ble, service, notificationFailure, participant ->
                val failures = evaluateGate(name, ble, service.recording) +
                    listOfNotNull(notificationFailure, service.runtimeFailure)
                CaptureGateUiState(
                    sessionName = name,
                    failures = failures,
                    failure = failures.firstOrNull(),
                )
            }.collect { _captureGate.value = it }
        }
    }

    fun setSessionName(value: String) {
        _sessionName.value = SessionNamePolicy.normalizeCanonical(value) ?: value
        SessionNamePolicy.parseCanonical(value)?.let { _sessionPrefix.value = it.prefix }
        if (!participantDraftDirty) prefillParticipantFor(value)
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
            setSessionName(
                "${prefix.wireValue}-${identity.subject}-${SessionNamePolicy.nextSequenceForSubject(
                    collectorApplication.sessionsRoot, prefix, identity.subject,
                )}",
            )
        }
    }

    fun useSuggestedSessionName() {
        setSessionName(
            SessionNamePolicy.suggestedBaseNameOrExample(
                collectorApplication.sessionsRoot,
                _sessionPrefix.value,
            ),
        )
    }

    fun setParticipantDraft(value: CaptureParticipantDraft) {
        participantDraftDirty = true
        _participantDraft.value = value
    }

    fun setRecordMode(value: CaptureRecordMode) {
        _recordMode.value = value
    }

    fun setPlannedDurationText(value: String) {
        _plannedDurationText.value = value.filter(Char::isDigit).take(4)
    }

    fun resetParticipantDraftFromSubject() {
        participantDraftDirty = false
        prefillParticipantFor(_sessionName.value)
    }

    fun onStart() = serviceClient.bind()

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
        val participant = participantForSession(_sessionName.value, persistCanonical = true)
        return serviceClient.updateParticipantProfile(participant)
    }

    fun setNotificationPermissionResult(granted: Boolean) {
        _notificationPermissionFailure.value = CaptureNotificationPermissionPolicy.failureFor(
            Build.VERSION.SDK_INT,
            granted,
        )
    }

    fun startRecording(notificationPermissionGranted: Boolean = false) {
        val gate = _captureGate.value
        if (!gate.canStart &&
            !(notificationPermissionGranted &&
                gate.failure == CaptureStartFailure.NotificationPermissionDenied)
        ) return
        val ble = collectorApplication.bleCoordinator.snapshot
        val deviceName = ble.phase.deviceId?.let { id ->
            ble.discoveredDevices.firstOrNull { it.id == id }?.name
        }
        val participant = participantForSession(gate.sessionName, persistCanonical = true)
        val bp = _participantDraft.value.referenceBloodPressure()
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
        }.onFailure { error ->
            _captureGate.value = gate.copy(failure = mapCaptureServiceStartFailure(error))
        }
    }

    fun validateSessionName(value: String = _sessionName.value) =
        SessionNamePolicy.validate(value, collectorApplication.sessionsRoot)

    private fun prefillParticipantFor(name: String) {
        val identity = SessionNamePolicy.parseCanonical(name)
        val latest = identity?.let { subjectProfileStore().read(it.subject)?.latest }
        _participantDraft.value = CaptureParticipantDraft.fromSnapshot(
            latest?.asParticipantSnapshot(identity!!.subject),
        )
    }

    private fun participantForSession(
        name: String,
        persistCanonical: Boolean,
    ): CaptureParticipantSnapshot? {
        val identity = SessionNamePolicy.parseCanonical(name)
        if (identity == null && _participantDraft.value == CaptureParticipantDraft()) return null
        val store = subjectProfileStore()
        if (identity != null && persistCanonical) {
            val existing = store.read(identity.subject)?.latest
            val draftSnapshot = _participantDraft.value.toSnapshot(identity, existing?.revisionId)
            val saved = store.saveRevision(
                subject = identity.subject,
                sex = draftSnapshot.sex,
                ageYears = draftSnapshot.ageYears,
                heightCm = draftSnapshot.heightCm,
                weightKg = draftSnapshot.weightKg,
                smokingFreq = draftSnapshot.smokingFreq,
                drinkingFreq = draftSnapshot.drinkingFreq,
                additionalFields = draftSnapshot.additionalFields,
            )
            return saved.latest?.asParticipantSnapshot(identity.subject)?.copy(sequence = identity.sequence)
        }
        return _participantDraft.value.toSnapshot(identity)
    }

    private fun subjectProfileStore() = SubjectProfileStore(collectorApplication.subjectsRoot)

    private fun evaluateGate(
        name: String,
        ble: BleCoordinatorSnapshot,
        recording: CaptureRecordingSnapshot,
    ): List<CaptureStartFailure> {
        val root = collectorApplication.sessionsRoot
        val capacityRoot = root.parent ?: root
        return CaptureStartGate.validateAll(
            CaptureStartContext(
                isRecording = recording.state != com.example.ppgcollector_android.data.session.CaptureRecordingState.IDLE &&
                    recording.state != com.example.ppgcollector_android.data.session.CaptureRecordingState.FINALIZED &&
                    recording.state != com.example.ppgcollector_android.data.session.CaptureRecordingState.FAILED,
                phase = ble.phase,
                freshness = ble.freshness,
                sessionsRoot = root,
                sessionName = name,
                availableBytes = runCatching { Files.getFileStore(capacityRoot).usableSpace }.getOrNull(),
                participant = _participantDraft.value,
            ),
        )
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
