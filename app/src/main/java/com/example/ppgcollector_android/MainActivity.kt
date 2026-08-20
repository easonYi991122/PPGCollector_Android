package com.example.ppgcollector_android

import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ppgcollector_android.data.session.CaptureNotificationPermissionPolicy
import com.example.ppgcollector_android.ui.theme.PPGCollector_AndroidTheme

private const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"

private enum class AppPage { LIVE, SESSIONS, SESSION_DETAIL, WORKBENCH, COMPARE }

class MainActivity : ComponentActivity() {
    private val captureViewModel: CaptureViewModel by viewModels()
    private val sessionsViewModel: SessionsViewModel by viewModels()
    private var archiveExportMode = false

    private val bleCoordinator
        get() = (application as PpgCollectorApplication).bleCoordinator

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        bleCoordinator.applyPermissionResult(grants)
        if (bleCoordinator.snapshot.permission.canUseBle) {
            bleCoordinator.startScanning(clearPreviousResults = true)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        captureViewModel.setNotificationPermissionResult(granted)
        if (granted) captureViewModel.startRecording(notificationPermissionGranted = true)
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { destination ->
        if (destination == null) {
            sessionsViewModel.cancelExportPicker()
            archiveExportMode = false
        } else {
            if (archiveExportMode) sessionsViewModel.exportArchiveTo(destination)
            else sessionsViewModel.exportSelectedTo(destination)
            archiveExportMode = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PPGCollector_AndroidTheme {
                val snapshot by bleCoordinator.uiSnapshotFlow.collectAsStateWithLifecycle(
                    initialValue = bleCoordinator.snapshot.copy(
                        diagnostics = com.example.ppgcollector_android.core.ble.BleGattDiagnostics(),
                        attemptDiagnostics =
                            com.example.ppgcollector_android.core.ble.BleConnectionAttemptDiagnostics(),
                    ),
                )
                val captureStatus by captureViewModel.serviceStatus.collectAsStateWithLifecycle()
                val sessionName by captureViewModel.sessionName.collectAsStateWithLifecycle()
                val participantDraft by captureViewModel.participantDraft.collectAsStateWithLifecycle()
                val sessionPrefix by captureViewModel.sessionPrefix.collectAsStateWithLifecycle()
                val recordMode by captureViewModel.recordMode.collectAsStateWithLifecycle()
                val plannedDurationText by captureViewModel.plannedDurationText.collectAsStateWithLifecycle()
                val captureGate by captureViewModel.captureGate.collectAsStateWithLifecycle()
                val bloodPressureReference by captureViewModel.bloodPressureReference.collectAsStateWithLifecycle()
                val sessionNameIsValid = remember(sessionName) {
                    captureViewModel.validateSessionName().isValid
                }
                var page by rememberSaveable { mutableStateOf(AppPage.LIVE) }

                LaunchedEffect(page) {
                    if (page == AppPage.SESSIONS) sessionsViewModel.refresh()
                    val insets = WindowCompat.getInsetsController(window, window.decorView)
                    if (page == AppPage.WORKBENCH) {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        insets.systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        insets.hide(WindowInsetsCompat.Type.systemBars())
                    } else {
                        if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) {
                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                        insets.show(WindowInsetsCompat.Type.systemBars())
                    }
                }

                BackHandler(enabled = page != AppPage.LIVE) {
                    page = when (page) {
                        AppPage.WORKBENCH -> AppPage.SESSION_DETAIL
                        AppPage.SESSION_DETAIL, AppPage.COMPARE -> AppPage.SESSIONS
                        AppPage.SESSIONS -> {
                            sessionsViewModel.cancelSessionSelection()
                            AppPage.LIVE
                        }
                        AppPage.LIVE -> AppPage.LIVE
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background,
                ) { innerPadding ->
                    when (page) {
                        AppPage.LIVE -> LiveCaptureScreen(
                            snapshot = snapshot,
                            captureStatus = captureStatus,
                            captureWaveformState = captureViewModel.waveformState,
                            captureAnalysisState = captureViewModel.analysisState,
                            previewState = captureViewModel.previewState,
                            sessionName = sessionName,
                            participantDraft = participantDraft,
                            sessionPrefix = sessionPrefix,
                            recordMode = recordMode,
                            plannedDurationText = plannedDurationText,
                            sessionNameIsValid = sessionNameIsValid,
                            captureGate = captureGate,
                            onSessionNameChange = captureViewModel::setSessionName,
                            onSessionPrefixChange = captureViewModel::setSessionPrefix,
                            onRecordModeChange = captureViewModel::setRecordMode,
                            onPlannedDurationChange = captureViewModel::setPlannedDurationText,
                            onParticipantDraftChange = captureViewModel::setParticipantDraft,
                            onUseSuggestedName = captureViewModel::useSuggestedSessionName,
                            onStartCapture = ::requestCaptureStart,
                            onStopCapture = captureViewModel::stopRecording,
                            onOpenBloodPressure = captureViewModel::openManualBloodPressure,
                            onScan = ::requestScan,
                            onStopScan = bleCoordinator::stopScanning,
                            onConnect = bleCoordinator::connect,
                            onDisconnect = bleCoordinator::disconnect,
                            onSelectStreamProtocol = { mode -> bleCoordinator.selectStreamProtocol(mode) },
                            onOpenSessions = {
                                page = AppPage.SESSIONS
                            },
                            modifier = Modifier.padding(innerPadding),
                        )

                        AppPage.SESSIONS -> {
                            val sessions by sessionsViewModel.state.collectAsStateWithLifecycle()
                            SavedSessionsRoute(
                                state = sessions,
                                onBack = {
                                    sessionsViewModel.cancelSessionSelection()
                                    page = AppPage.LIVE
                                },
                                onRefresh = sessionsViewModel::refresh,
                                onSelect = { item ->
                                    sessionsViewModel.select(item)
                                    page = AppPage.SESSION_DETAIL
                                },
                                onToggleSubject = sessionsViewModel::toggleArchiveSubject,
                                onToggleSession = sessionsViewModel::toggleArchiveSession,
                                onBeginSelection = sessionsViewModel::beginSessionSelection,
                                onSelectAllArchive = sessionsViewModel::selectAllArchive,
                                onSelectAllSessions = sessionsViewModel::selectAllSessions,
                                onCancelSelection = sessionsViewModel::cancelSessionSelection,
                                onExportSelection = ::requestArchiveExport,
                                onDeleteSelected = sessionsViewModel::deleteSelectedSessions,
                                onCancelAnalysis = sessionsViewModel::cancelAnalysis,
                                onOpenCompare = { page = AppPage.COMPARE },
                                onViewModeChange = sessionsViewModel::setSavedSessionsViewMode,
                                onToggleExpandedSubject =
                                    sessionsViewModel::toggleArchiveExpandedSubject,
                                modifier = Modifier.padding(innerPadding),
                            )
                        }

                        AppPage.SESSION_DETAIL -> {
                            val sessions by sessionsViewModel.state.collectAsStateWithLifecycle()
                            SavedSessionDetailScreen(
                                state = sessions,
                                onBack = {
                                    sessionsViewModel.clearSelection()
                                    page = AppPage.SESSIONS
                                },
                                onCancelInspection = sessionsViewModel::cancelInspection,
                                onRequestExport = ::requestSessionExport,
                                onRecover = sessionsViewModel::recoverSelected,
                                onCancelAction = sessionsViewModel::cancelAction,
                                onClearAction = sessionsViewModel::clearAction,
                                onStartAnalysis = sessionsViewModel::startAnalysis,
                                onCancelAnalysis = sessionsViewModel::cancelAnalysis,
                                onOpenFullscreenWorkbench = { page = AppPage.WORKBENCH },
                                onUpdateBloodPressure = sessionsViewModel::updateSelectedBloodPressure,
                                modifier = Modifier.padding(innerPadding),
                            )
                        }

                        AppPage.WORKBENCH -> {
                            val sessions by sessionsViewModel.state.collectAsStateWithLifecycle()
                            FullscreenSessionWorkbenchScreen(
                                state = sessions,
                                onBack = { page = AppPage.SESSION_DETAIL },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }

                        AppPage.COMPARE -> {
                            val sessions by sessionsViewModel.state.collectAsStateWithLifecycle()
                            SessionComparisonScreen(
                                state = sessions,
                                onBack = { page = AppPage.SESSIONS },
                                modifier = Modifier.padding(innerPadding),
                            )
                        }
                    }

                    bloodPressureReference?.let { reference ->
                        ManualBloodPressureDialog(
                            reference = reference,
                            onDismiss = captureViewModel::dismissManualBloodPressure,
                            onSave = captureViewModel::commitManualBloodPressure,
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bleCoordinator.setPreviewUiActive(true)
        captureViewModel.onStart()
    }

    override fun onStop() {
        bleCoordinator.setPreviewUiActive(false)
        captureViewModel.onStop()
        super.onStop()
    }

    private fun requestScan() {
        val missing = bleCoordinator.permissionRequest()
        if (missing.isEmpty()) {
            bleCoordinator.startScanning(clearPreviousResults = true)
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requestCaptureStart() {
        val requiresNotificationPermission =
            CaptureNotificationPermissionPolicy.isRuntimePermissionRequired(Build.VERSION.SDK_INT)
        if (!requiresNotificationPermission ||
            checkSelfPermission(POST_NOTIFICATIONS_PERMISSION) == PackageManager.PERMISSION_GRANTED
        ) {
            captureViewModel.setNotificationPermissionResult(granted = true)
            captureViewModel.startRecording()
        } else {
            notificationPermissionLauncher.launch(POST_NOTIFICATIONS_PERMISSION)
        }
    }

    private fun requestSessionExport(item: SessionListItemUi) {
        archiveExportMode = false
        exportLauncher.launch("${item.baseName}.zip")
    }

    private fun requestArchiveExport() {
        archiveExportMode = true
        exportLauncher.launch("ppgcollector-archive.zip")
    }
}
