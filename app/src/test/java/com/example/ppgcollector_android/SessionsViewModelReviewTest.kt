package com.example.ppgcollector_android

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.example.ppgcollector_android.core.protocol.*
import com.example.ppgcollector_android.data.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlin.math.PI
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PpgCollectorApplication::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelReviewTest {
    private lateinit var app: PpgCollectorApplication
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private var vmIndex = 0

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        app = RuntimeEnvironment.getApplication() as PpgCollectorApplication
    }
    @After fun cleanup() {
        store.clear()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }
    private fun keep(vm: SessionsViewModel): SessionsViewModel = vm.also { store.put("sessions-${vmIndex++}", it) }
    private fun vm(savedState: SavedStateHandle = SavedStateHandle()) = keep(SessionsViewModel(app, savedState, dispatcher, dispatcher))
    private fun drain() = dispatcher.scheduler.advanceUntilIdle()
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) {
            dispatcher.scheduler.runCurrent()
            Thread.sleep(5)
        }
        dispatcher.scheduler.runCurrent()
        assertTrue("worker did not settle", condition())
    }

    @Test fun activeWriterIsExcludedByAllSelectionModesAndStaleDeleteIsRefusedAtomically() {
        val history = write("PPG-A-1")
        val active = writer("PPG-A-2")
        try {
            append(active, 0)
            val vm = vm()
            vm.refresh(); drain()
            vm.selectAllArchive()
            assertEquals(setOf(history.directory), vm.state.value.archiveSelectedDirectories)
            assertTrue(active.directory in vm.state.value.busyDirectories)
            vm.toggleArchiveSession(active.directory)
            assertEquals(1, vm.state.value.selectedSessionCount)
            vm.toggleArchiveSubject("A")
            assertEquals(0, vm.state.value.selectedSessionCount)
            vm.toggleArchiveSubject("A")
            assertEquals(setOf(history.directory), vm.state.value.archiveSelectedDirectories)
            vm.deleteSelectedSessions(); drain()
            assertFalse(Files.exists(history.directory))
            assertTrue(Files.exists(active.rawPath))
            append(active, 1)
            active.finish(CaptureStopReason.USER)
            val saved = CaptureSessionRepository.listSessions(app.sessionsRoot).single()
            assertEquals(40L, saved.metadata!!.sampleCount)
            assertEquals(2L, CupRawReader.scan(active.rawPath).recordCount)

            vm.refresh(); drain(); vm.selectAllSessions()
            app.sessionAccessRegistry.tryAcquire(saved.directory, CaptureSessionAccessRegistry.Access.WRITE)!!.use {
                vm.deleteSelectedSessions(); drain()
                assertTrue(Files.exists(saved.directory))
                assertTrue(vm.state.value.action.message!!.contains("占用中"))
            }
        } finally { active.close() }
    }

    @Test fun allThreeMinusOneChangesCheckboxCountFrozenExportAndActualDeleteToTwo() {
        val sessions = (1..3).map { write("PPG-B-$it") }
        val vm = vm()
        vm.refresh(); drain(); vm.selectAllArchive()
        vm.toggleArchiveSession(sessions[1].directory)
        val expected = setOf(sessions[0].directory, sessions[2].directory)
        assertEquals(expected, vm.state.value.archiveSelectedDirectories)
        assertEquals(2, vm.state.value.selectedSessionCount)
        assertFalse("B" in vm.state.value.archiveSelectedSubjects)
        val request = vm.prepareArchiveExport()!!
        vm.clearArchiveSelection() // Picker is open; the request is already frozen.
        val destination = app.cacheDir.toPath().resolve("two-sessions.zip")
        vm.completeExportPickerToFile(request.token, destination); drain()
        assertNull(vm.state.value.action.error)
        ZipFile(destination.toFile()).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertTrue(names.any { it.endsWith("PPG-B-1.cupraw") })
            assertTrue(names.any { it.endsWith("PPG-B-3.cupraw") })
            assertFalse(names.any { it.contains("PPG-B-2") })
        }
        vm.selectAllArchive(); vm.toggleArchiveSession(sessions[1].directory)
        vm.deleteSelectedSessions(); drain()
        assertEquals(listOf("PPG-B-2"), CaptureSessionRepository.listSessions(app.sessionsRoot).map { it.baseName })
        assertFalse(vm.state.value.action.isRunning)
    }

    @Test fun restoredSingleExportDoesNotUseTheNewSelectedSessionAndCancelCreatesNoZip() {
        val a = write("PPG-C-1")
        val b = write("PPG-C-2")
        val saved = SavedStateHandle()
        val first = vm(saved)
        val request = first.prepareSingleExport(SessionListItemMapper.map(a))!!
        val restored = vm(SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) }))
        restored.select(SessionListItemMapper.map(b)); drain()
        val destination = app.cacheDir.toPath().resolve("single.zip")
        restored.completeExportPickerToFile(request.token, destination); drain()
        assertNull(restored.state.value.action.error)
        ZipFile(destination.toFile()).use { zip ->
            assertNotNull(zip.getEntry("PPG-C-1.cupraw"))
            assertNull(zip.getEntry("PPG-C-2.cupraw"))
        }
        val cancelled = restored.prepareSingleExport(SessionListItemMapper.map(a))!!
        restored.completeExportPicker(cancelled.token, null)
        restored.completeExportPickerToFile(cancelled.token, app.cacheDir.toPath().resolve("cancelled.zip")); drain()
        assertFalse(Files.exists(app.cacheDir.toPath().resolve("cancelled.zip")))
        assertEquals("导出已取消", restored.state.value.action.message)
    }

    @Test fun realInspectionStopsOnCancellationAndReselectionOnlyPublishesCurrentWorker() {
        val a = write("PPG-D-1", 150)
        val b = write("PPG-D-2")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val checks = AtomicInteger()
        val vm = keep(SessionsViewModel(app, ioDispatcher = Dispatchers.IO, computeDispatcher = dispatcher,
            inspectSession = { path, check ->
                try {
                    CaptureSessionInspectionService.inspect(path, cancellationCheck = {
                        if (path == a.directory && checks.incrementAndGet() == 20) {
                            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS))
                        }
                        check()
                    })
                } finally { if (path == a.directory) stopped.countDown() }
            }))
        try {
            vm.select(SessionListItemMapper.map(a)); dispatcher.scheduler.runCurrent()
            await { entered.count == 0L }
            vm.cancelInspection()
            vm.select(SessionListItemMapper.map(b)); dispatcher.scheduler.runCurrent()
            release.countDown()
            await { stopped.count == 0L && vm.state.value.selected?.isInspecting == false }
            assertEquals(20, checks.get())
            assertEquals(b.directory, vm.state.value.selected!!.item.directory)
            assertNull(vm.state.value.selected!!.error)
        } finally { release.countDown(); store.clear(); await { stopped.count == 0L } }
    }

    @Test fun leavingDuringRealRecoveryClosesActionAndOldFinallyCannotOverwriteNewDelete() {
        val source = write("PPG-E-1", 150)
        val history = write("PPG-E-2")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val sourceFiles = CaptureSessionRepository.expectedFiles(source.directory)
        val rawBefore = Files.readAllBytes(sourceFiles.raw)
        val checks = AtomicInteger()
        val vm = keep(SessionsViewModel(app, ioDispatcher = Dispatchers.IO, computeDispatcher = dispatcher,
            recoverSession = { session, check ->
                try {
                    CaptureSessionRecoveryService.recover(session, "PPG-E-recovered", cancellationCheck = {
                        if (checks.incrementAndGet() == 20) {
                            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS))
                        }
                        check()
                    })
                } finally { stopped.countDown() }
            }))
        try {
            vm.refresh(); await { vm.state.value.sessions.size == 2 }
            vm.select(SessionListItemMapper.map(source))
            vm.recoverSelected(); dispatcher.scheduler.runCurrent()
            await { entered.count == 0L }
            vm.clearSelection() // Shared by toolbar and system back.
            assertFalse(vm.state.value.action.isRunning)
            assertNull(vm.state.value.selected)
            vm.toggleArchiveSession(history.directory)
            vm.deleteSelectedSessions(); dispatcher.scheduler.runCurrent()
            await { !vm.state.value.action.isRunning }
            assertEquals(SessionActionKind.DELETE, vm.state.value.action.kind)
            release.countDown()
            await { stopped.count == 0L }
            assertEquals(SessionActionKind.DELETE, vm.state.value.action.kind)
            assertTrue(vm.state.value.action.message!!.contains("已删除 1"))
            assertEquals(20, checks.get())
            assertArrayEquals(rawBefore, Files.readAllBytes(sourceFiles.raw))
            assertFalse(Files.exists(app.sessionsRoot.resolve("PPG-E-recovered")))
        } finally { release.countDown(); store.clear(); await { stopped.count == 0L } }
    }

    @Test fun invalidAndFailedBpSaveAreVisibleInBpStateAndLeaveMetadataUntouched() {
        val session = write("PPG-F-1")
        val metadata = CaptureSessionRepository.expectedFiles(session.directory).metadata
        val before = Files.readAllBytes(metadata)
        var saves = 0
        val vm = keep(SessionsViewModel(app, ioDispatcher = dispatcher, computeDispatcher = dispatcher,
            saveBloodPressure = { _, _, _ -> saves++; throw IOException("injected metadata failure") }))
        vm.select(SessionListItemMapper.map(session)); drain()
        vm.updateSelectedBloodPressure(80, 120); drain()
        assertEquals(SessionActionKind.UPDATE_BP, vm.state.value.action.kind)
        assertTrue(vm.state.value.action.error!!.contains("SBP"))
        assertEquals(0, saves)
        vm.updateSelectedBloodPressure(120, 80)
        vm.updateSelectedBloodPressure(130, 90) // First save is still pending.
        assertTrue(vm.state.value.action.isRunning)
        drain()
        assertEquals(1, saves)
        assertEquals("injected metadata failure", vm.state.value.action.error)
        assertFalse(vm.state.value.action.isRunning)
        assertArrayEquals(before, Files.readAllBytes(metadata))
    }

    @Test fun missingRawWithAnExistingArtifactEndsSpinnerAndCanRetry() {
        val session = write("PPG-G-1")
        CaptureSessionOfflineAnalysisService.analyzeAndSave(session)
        val raw = CaptureSessionRepository.expectedFiles(session.directory).raw
        val original = Files.readAllBytes(raw)
        Files.delete(raw)
        val vm = vm()
        vm.refresh(); drain()
        assertEquals(1, vm.state.value.artifactsBySession[session.directory]!!.size)
        vm.select(vm.state.value.sessions.single()); drain()
        val failed = vm.state.value.selected!!
        assertFalse(failed.isLoadingSignal)
        assertNotNull(failed.signalError)
        assertEquals(SessionSignalUiState.FAILED,
            SessionDetailUiPolicy.signalState(failed.isLoadingSignal, failed.signalError, failed.signal != null))
        Files.write(raw, original)
        vm.retrySignal(); drain()
        assertNotNull(vm.state.value.selected!!.signal)
        assertNull(vm.state.value.selected!!.signalError)
    }

    @Test fun budgetDegradationFlowsThroughRealViewModelWithSourceIndicesAndStageReasons() {
        val session = write("PPG-H-1", 150)
        val vm = keep(SessionsViewModel(app, ioDispatcher = dispatcher, computeDispatcher = dispatcher,
            signalBudget = { CaptureSignalLoadBudget(32 * 1024 * 1024, 0, maximumPreviewBuckets = 8) }))
        vm.select(SessionListItemMapper.map(session)); drain()
        val trace = vm.state.value.selected!!.signal!!
        assertTrue(trace.budgetDegraded)
        assertEquals(3000, trace.reviewSampleCount())
        assertEquals(2999L, trace.sourceSampleIndices.last())
        assertTrue(trace.rawRed.size <= 54)
        assertTrue(trace.stages.filterKeys { it != CaptureSignalStage.RAW }.values.all { it.detail != null })
        assertFalse(vm.state.value.selected!!.isLoadingSignal)
    }

    @Test fun indexKeepsAll160ReportsAndOnDemandCacheNeverExceedsComparisonPair() {
        val session = write("PPG-I-1")
        val artifact = CaptureSessionOfflineAnalysisService.analyzeAndSave(session)
        val bytes = CaptureSessionAnalysisCodec.encode(artifact.report).toByteArray()
        Files.delete(artifact.path)
        repeat(160) { Files.write(artifact.path.parent.resolve("history-$it.json"), bytes) }
        Files.writeString(artifact.path.parent.resolve("broken.json"), "{broken}")
        val vm = vm()
        vm.refresh(); drain()
        val summaries = vm.state.value.artifactsBySession[session.directory]!!
        assertEquals(161, summaries.size)
        assertEquals(160, summaries.count { it.state == CaptureArtifactReadState.READY })
        assertTrue(summaries.single { it.state == CaptureArtifactReadState.INVALID }.detail != null)
        assertTrue(vm.state.value.loadedArtifacts.isEmpty())
        val ready = summaries.filter { it.state == CaptureArtifactReadState.READY }
        vm.loadArtifacts(ready.take(3)); drain()
        assertEquals(2, vm.state.value.loadedArtifacts.size)
        vm.loadArtifacts(ready.drop(10).take(1)); drain()
        assertEquals(setOf(ready[10].path), vm.state.value.loadedArtifacts.keys)
        vm.releaseArtifacts()
        assertTrue(vm.state.value.loadedArtifacts.isEmpty())
    }


    @Test fun realOfflineFallbackCadenceAndMetricSpecificNullsReachTheProductionSegmentation() {
        val session = write("PPG-J-1", 150)
        val trace = CaptureSessionOfflineAnalysisService.loadSignalTrace(session)
        assertEquals(CaptureMetricTimelineSource.OFFLINE_RECOMPUTED, trace.metricTimelineEvidence.source)
        val hr = buildPersistedMetricSeries(trace).first { it.label == "HR bpm" }
        assertEquals(200, hr.cadenceSamples)
        assertEquals(listOf(600, 800, 1000), hr.points.take(3).map { it.first })
        assertEquals(1, boundedMetricSegments(hr.points.take(3), hr.unavailableSourceIndices,
            hr.cadenceSamples, 0..1200, 400).size)
        val sparse = trace.copy(metricTimeline = trace.metricTimeline.take(3).mapIndexed { index, point ->
            if (index == 1) point.copy(heartRateBpm = null) else point
        }, metricAvailability = emptyMap(), breakIndices = intArrayOf(700))
        val series = buildPersistedMetricSeries(sparse)
        val sparseHr = series.first { it.label == "HR bpm" }
        val pi = series.first { it.label == "PI %" }
        assertEquals(listOf(1, 1), boundedMetricSegments(sparseHr.points, sparseHr.unavailableSourceIndices,
            sparseHr.cadenceSamples, 0..1200, 400).map { it.size })
        assertEquals(1, boundedMetricSegments(pi.points, pi.unavailableSourceIndices, pi.cadenceSamples, 0..1200, 400).size)
    }

    @Test fun pendingIdentityIsExcludedBeforeWriterLeaseAndArchiveClickUsesCachedItem() {
        write("PPG-K-1")
        val vm = vm()
        vm.refresh(); drain()
        val item = vm.state.value.sessions.single()
        vm.observeRecording(CaptureRecordingSnapshot(state = CaptureRecordingState.STARTING), item.baseName)
        vm.selectAllArchive()
        assertEquals(0, vm.state.value.selectedSessionCount)
        assertTrue(item.directory in vm.state.value.busyDirectories)
        vm.observeRecording(CaptureRecordingSnapshot())
        vm.selectAllArchive()
        assertEquals(1, vm.state.value.selectedSessionCount)
        val metadata = CaptureSessionRepository.expectedFiles(item.directory).metadata
        Files.writeString(metadata, "{broken after list load}")
        vm.select(item)
        // Navigation is immediate and uses the existing presentation object; disk work is deferred.
        assertSame(item, vm.state.value.selected!!.item)
        assertTrue(vm.state.value.selected!!.isInspecting)
        assertTrue(vm.state.value.selected!!.expectedFiles.isEmpty())
        vm.clearSelection(); drain()
        assertNull(vm.state.value.selected)
    }

    @Test fun completedRecoverySelectsItsCopyAndPublishesACompletedAction() {
        val source = write("PPG-L-1")
        val raw = CaptureSessionRepository.expectedFiles(source.directory).raw
        val before = Files.readAllBytes(raw)
        val vm = vm()
        vm.select(SessionListItemMapper.map(source)); drain()
        vm.recoverSelected(); drain()
        assertFalse(vm.state.value.action.isRunning)
        assertNull(vm.state.value.action.error)
        assertEquals(SessionActionKind.RECOVER, vm.state.value.action.kind)
        assertNotEquals(source.directory, vm.state.value.selected!!.item.directory)
        assertTrue(vm.state.value.action.message!!.contains(vm.state.value.selected!!.item.baseName))
        assertArrayEquals(before, Files.readAllBytes(raw))
    }

    private fun writer(name: String) = CaptureSessionWriter(CaptureSessionConfiguration(
        "ui-$name", name, Instant.EPOCH, "review", "review", "ios_v1", CupBatchProtocolV1.profileIdentifier,
        "ble_gatt_v1", CaptureDeviceContext("CUP", "fake", "service", "notify")), app.sessionsRoot) { Long.MAX_VALUE }

    private fun write(name: String, frames: Int = 60): StoredCaptureSession {
        writer(name).use { writer -> repeat(frames) { append(writer, it) }; writer.finish(CaptureStopReason.USER) }
        return CaptureSessionRepository.listSessions(app.sessionsRoot).single { it.baseName == name }
    }
    private fun append(writer: CaptureSessionWriter, frameIndex: Int) {
        val frame = CupBatchFrame(frameIndex.toUByte(), List(20) { offset ->
            val phase = 2 * PI * 1.2 * (frameIndex * 20 + offset) / 100
            CupPpgSample((100_000 + 850 * sin(phase)).toUInt(), (120_000 + 1_050 * sin(phase)).toUInt())
        })
        writer.appendRawThenDerive(frameIndex.toULong(), encodeCupBatchFrame(frame)) {
            listOf(CupDecodedFrameEvent(frame, CupSequenceEvent.Continuous, true))
        }
    }
}
