package com.example.ppgcollector_android

import com.example.ppgcollector_android.data.session.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.time.Instant

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = PpgCollectorApplication::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CaptureFormLifecycleTest {
    @Test fun viewModelBootstrapAndPrefillExposeReadErrorsAndPreserveManualDraft() {
        withViewModel(CaptureFormLifecycle { throw IOException("profile unavailable") }, prepare = { app ->
            val previous = CaptureSessionWriter(CaptureSessionConfiguration("previous", "PPG-S001-1", Instant.EPOCH,
                "test", "unavailable", "ios_v1", "cup_v1", "ble_gatt_v1",
                CaptureDeviceContext("CUP", "device", "service", "notify")), app.sessionsRoot) { Long.MAX_VALUE }
            previous.appendRawThenDerive(1u, byteArrayOf(1, 2, 3)) { emptyList() }
            previous.finish(CaptureStopReason.USER)
        }) { vm, _, scheduler, _ ->
            scheduler.runCurrent()
            assertNotNull(vm.profileReadError.value)
            assertTrue(vm.serviceStatus.value.error!!.contains("profile unavailable"))
            vm.setSessionName("PPG-S009-1")
            scheduler.runCurrent()
            assertEquals("S009", vm.profileReadError.value!!.subjectId)
            val manual = CaptureParticipantDraft(sex = "女", ageYears = "30", heightCm = "165", weightKg = "55",
                smokingFreq = "不吸烟", drinkingFreq = "不饮酒", systolicBp = "120", diastolicBp = "80")
            vm.setParticipantDraft(manual)
            scheduler.runCurrent()
            assertEquals(manual, vm.participantDraft.value)
            assertTrue(vm.captureGate.value.failures.none { it is CaptureStartFailure.ParticipantIncomplete })
            vm.resetParticipantDraftFromSubject()
            scheduler.runCurrent()
            assertNotNull(vm.profileReadError.value)
        }
    }

    @Test fun viewModelOnStopOnStartPreservesBpAndMatchingBackgroundTerminalClearsSessionFields() {
        withViewModel(CaptureFormLifecycle { null }) { vm, binding, scheduler, app ->
            scheduler.runCurrent()
            vm.setSessionName("PPG-S001-1")
            scheduler.runCurrent()
            val draft = CaptureParticipantDraft(systolicBp = "120", diastolicBp = "80", additionalFields = mapOf("notes" to "session note"))
            vm.setParticipantDraft(draft)
            binding.recording = CaptureRecordingSnapshot(state = CaptureRecordingState.RECORDING,
                sessionId = "A", baseName = "PPG-S001-1", sessionToken = 1)
            binding.reference = CaptureReferenceTimestamp("A", 1, 1, 999, 9.99, 1u, Instant.EPOCH)
            vm.onStart()
            scheduler.runCurrent()
            vm.openManualBloodPressure()
            val reference = vm.bloodPressureReference.value
            assertNotNull(reference)
            // The real directory now exists, just as it does during recording.
            Files.createDirectories(app.sessionsRoot.resolve("PPG-S001-1"))
            vm.onStop()
            scheduler.runCurrent()
            vm.onStart()
            scheduler.advanceTimeBy(200)
            scheduler.runCurrent()
            assertSame(reference, vm.bloodPressureReference.value)
            assertEquals(draft, vm.participantDraft.value)
            vm.onStop()
            scheduler.runCurrent()
            app.recordCaptureTerminal(binding.recording.copy(state = CaptureRecordingState.FINALIZED))
            scheduler.runCurrent()
            assertNull(vm.bloodPressureReference.value)
            assertEquals("", vm.participantDraft.value.systolicBp)
            assertEquals("", vm.participantDraft.value.diastolicBp)
            assertFalse(vm.participantDraft.value.additionalFields.containsKey("notes"))
        }
    }

    private fun withViewModel(form: CaptureFormLifecycle,
        prepare: (PpgCollectorApplication) -> Unit = {},
        test: (CaptureViewModel, TestBinding, kotlinx.coroutines.test.TestCoroutineScheduler, PpgCollectorApplication) -> Unit) {
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        val app = org.robolectric.RuntimeEnvironment.getApplication() as PpgCollectorApplication
        prepare(app)
        val binding = TestBinding()
        val ble = com.example.ppgcollector_android.core.ble.BleCoordinator(
            com.example.ppgcollector_android.core.ble.FakeBleTransport(), 28,
            ticker = com.example.ppgcollector_android.core.ble.FakeBleOwnerTicker())
        val vm = CaptureViewModel(app, form, { _, _ -> binding }, dispatcher, ble)
        val store = androidx.lifecycle.ViewModelStore().apply { put("capture", vm) }
        try {
            test(vm, binding, dispatcher.scheduler, app)
        } finally {
            store.clear()
            dispatcher.scheduler.runCurrent()
            ble.close()
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    private class TestBinding : CaptureServiceBinding {
        override val state = kotlinx.coroutines.flow.MutableStateFlow(CaptureServiceObservation())
        var recording = CaptureRecordingSnapshot()
        var reference: CaptureReferenceTimestamp? = null
        override fun bind(createIfNeeded: Boolean): Boolean {
            state.value = CaptureServiceObservation(binding = CaptureServiceBindingState.BOUND, recording = recording)
            return true
        }
        override fun unbind() { state.value = CaptureServiceObservation() }
        override fun stopRecording() = Unit
        override fun captureReferenceTimestamp() = reference
        override fun commitManualBloodPressure(event: ManualBloodPressureEvent) = true
        override fun updateParticipantProfile(participant: CaptureParticipantSnapshot?) = true
        override fun clearRuntimeFailure() = Unit
    }

    @Test fun corruptProfileReturnsRetryableErrorWithoutChangingSourceAndStillAllowsManualEntry() {
        val root = Files.createTempDirectory("capture-form-corrupt-profile")
        try {
            val store = SubjectProfileStore(root)
            val path = store.pathFor("S001")
            val evidence = "{broken profile".toByteArray()
            Files.write(path, evidence)
            val lifecycle = CaptureFormLifecycle(store::read)
            val result = lifecycle.readParticipant(SessionNamePolicy.parseCanonical("PPG-S001-1"))
            assertNotNull(result.error)
            assertEquals("S001", result.error!!.subjectId)
            assertTrue(result.error!!.message.contains("手动填写"))
            assertEquals(CaptureParticipantDraft(), result.participant)
            assertArrayEquals(evidence, Files.readAllBytes(path))
            // Retry follows the same production reader and preserves evidence.
            assertNotNull(lifecycle.readParticipant(SessionNamePolicy.parseCanonical("PPG-S001-1")).error)
            assertArrayEquals(evidence, Files.readAllBytes(path))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun profileIoFailureIsRetryableButCancellationPropagates() {
        var failRead = true
        val lifecycle = CaptureFormLifecycle {
            if (failRead) throw IOException("storage unavailable")
            null
        }
        val identity = SessionNamePolicy.parseCanonical("PPG-S001-1")
        assertEquals("storage unavailable", lifecycle.readParticipant(identity).error!!.detail)
        failRead = false
        assertNull(lifecycle.readParticipant(identity).error)
        val cancellation = CancellationException("cancelled prefill")
        val cancelled = CaptureFormLifecycle { throw cancellation }
        assertSame(cancellation, runCatching { cancelled.readParticipant(identity) }.exceptionOrNull())
    }

    @Test fun recordingBpAnchorSurvivesUnbindAndRebindAndOnlyMatchingTerminalClearsIt() {
        val lifecycle = CaptureFormLifecycle { null }
        lifecycle.startRequested("PPG-S001-1")
        val recording = CaptureRecordingSnapshot(state = CaptureRecordingState.RECORDING,
            sessionId = "A", baseName = "PPG-S001-1", sessionToken = 1)
        assertFalse(lifecycle.observe(recording))
        val reference = CaptureReferenceTimestamp("A", 1, 1, 999, 9.99, 1u, Instant.EPOCH)
        lifecycle.bloodPressureReference.value = reference
        // CaptureServiceClient publishes IDLE on onStop/unbind, and the real
        // recording snapshot after onStart/rebind. Neither is a terminal event.
        assertFalse(lifecycle.observe(CaptureRecordingSnapshot()))
        assertSame(reference, lifecycle.bloodPressureReference.value)
        assertFalse(lifecycle.observe(recording))
        assertSame(reference, lifecycle.bloodPressureReference.value)
        assertFalse(lifecycle.observe(recording.copy(state = CaptureRecordingState.FINALIZED, sessionId = "old")))
        assertSame(reference, lifecycle.bloodPressureReference.value)
        assertTrue(lifecycle.observe(recording.copy(state = CaptureRecordingState.FINALIZED)))
        assertNull(lifecycle.bloodPressureReference.value)
        assertFalse(lifecycle.observe(recording.copy(state = CaptureRecordingState.FINALIZED)))
    }

    @Test fun oldTerminalCannotClearNewFormEvenIfDirectoryNameMatches() {
        val lifecycle = CaptureFormLifecycle { null }
        lifecycle.startRequested("PPG-S001-1")
        val current = CaptureRecordingSnapshot(state = CaptureRecordingState.RECORDING,
            sessionId = "B", sessionToken = 2, baseName = "PPG-S001-1")
        lifecycle.observe(current)
        val reference = CaptureReferenceTimestamp("B", 1, 1, 19, 0.19, 1u, Instant.EPOCH)
        lifecycle.bloodPressureReference.value = reference
        assertFalse(lifecycle.observe(current.copy(state = CaptureRecordingState.FAILED, sessionId = "A", sessionToken = 1)))
        assertSame(reference, lifecycle.bloodPressureReference.value)
    }
}
