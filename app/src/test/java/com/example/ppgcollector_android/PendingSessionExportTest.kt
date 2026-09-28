package com.example.ppgcollector_android

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Paths

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = PpgCollectorApplication::class)
class PendingSessionExportTest {
    @org.junit.Before fun noForegroundServiceIsRunningInThisActivityFixture() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(app).declareComponentUnbindable(
            android.content.ComponentName(app, CaptureForegroundService::class.java))
    }

    @Test fun restoredSingleAndArchiveKeepTheirFrozenIdentityAndConsumeOnce() {
        PendingSessionExport.Kind.entries.forEach { kind ->
            val handle = SavedStateHandle()
            val requests = PendingSessionExports(handle)
            val directories = linkedSetOf(Paths.get("/sessions/A"))
            if (kind == PendingSessionExport.Kind.ARCHIVE) directories.add(Paths.get("/sessions/B"))
            val request = requests.begin(kind, directories)!!
            directories.clear()
            val restored = PendingSessionExports(SavedStateHandle(handle.keys().associateWith { handle.get<Any>(it) }))
            assertEquals(request, restored.pending)
            assertNull(restored.consume("unrelated callback"))
            assertEquals(request, restored.consume(request.token))
            assertNull(restored.consume(request.token))
            assertEquals(if (kind == PendingSessionExport.Kind.SINGLE) 1 else 2, request.directories.size)
        }
    }

    @Test fun pickerCannotBeReplacedAndCancelCannotBecomeAnExport() {
        val requests = PendingSessionExports(SavedStateHandle())
        assertNull(requests.begin(PendingSessionExport.Kind.ARCHIVE, emptySet()))
        val first = requests.begin(PendingSessionExport.Kind.SINGLE, setOf(Paths.get("/sessions/A")))!!
        assertNull(requests.begin(PendingSessionExport.Kind.SINGLE, setOf(Paths.get("/sessions/B"))))
        assertEquals(first, requests.consume(first.token))
        assertNull(requests.pending)
        assertNull(requests.consume(first.token))
    }
    @Test fun singlePickerSurvivesActivityAndSavedStateRecreation() = activityRoundTrip(false)
    @Test fun archivePickerSurvivesActivityAndSavedStateRecreation() = activityRoundTrip(true)

    private fun activityRoundTrip(archive: Boolean) {
        val app = org.robolectric.RuntimeEnvironment.getApplication() as PpgCollectorApplication
        val sessions = com.example.ppgcollector_android.data.session.ReviewSessionFixtures.writeProtocols(app.sessionsRoot)
        var controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            var vm = androidx.lifecycle.ViewModelProvider(controller.get())[SessionsViewModel::class.java]
            if (archive) {
                vm.refresh()
                await { vm.state.value.sessions.size == 4 }
                vm.selectAllArchive()
                vm.toggleArchiveSession(sessions.last().directory)
                MainActivity::class.java.getDeclaredMethod("requestArchiveExport").apply { isAccessible = true }.invoke(controller.get())
            } else {
                MainActivity::class.java.getDeclaredMethod("requestSessionExport", SessionListItemUi::class.java)
                    .apply { isAccessible = true }.invoke(controller.get(), SessionListItemMapper.map(sessions.first()))
            }
            val frozen = vm.pendingExport!!
            assertEquals(if (archive) 3 else 1, frozen.directories.size)
            controller.recreate()
            vm = androidx.lifecycle.ViewModelProvider(controller.get())[SessionsViewModel::class.java]
            assertEquals(frozen, vm.pendingExport)
            val saved = android.os.Bundle()
            controller.saveInstanceState(saved).pause().stop().destroy()
            // Simulate process saved-state restoration as well as retained-VM rotation.
            val parcel = android.os.Parcel.obtain()
            val restoredState = try {
                parcel.writeBundle(saved)
                parcel.setDataPosition(0)
                parcel.readBundle(PendingSessionExport::class.java.classLoader)!!
            } finally { parcel.recycle() }
            controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).create(restoredState).start().resume()
            vm = androidx.lifecycle.ViewModelProvider(controller.get())[SessionsViewModel::class.java]
            assertEquals(frozen, vm.pendingExport)
            assertNull(vm.state.value.selected)
            val destination = app.cacheDir.toPath().resolve(if (archive) "restored-archive.zip" else "restored-single.zip")
            vm.completeExportPickerToFile(frozen.token, destination)
            await { !vm.state.value.action.isRunning }
            assertNull(vm.state.value.action.error)
            java.util.zip.ZipFile(destination.toFile()).use { zip ->
                val rawEntries = zip.entries().asSequence().filter { it.name.endsWith(".cupraw") }.map { it.name }.toList()
                assertEquals(frozen.directories.size, rawEntries.size)
                frozen.directories.forEach { path ->
                    val basename = Paths.get(path).fileName.toString()
                    assertTrue(rawEntries.any { it.endsWith("$basename.cupraw") })
                }
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun returningFromNotificationSettingsRefreshesOnlyPermissionFailureOnSameViewModel() {
        val app = org.robolectric.RuntimeEnvironment.getApplication() as PpgCollectorApplication
        val permission = "android.permission.POST_NOTIFICATIONS"
        org.robolectric.Shadows.shadowOf(app).denyPermissions(permission)
        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val vm = androidx.lifecycle.ViewModelProvider(controller.get())[CaptureViewModel::class.java]
            await { CaptureUiPolicy.needsNotificationPermission(vm.captureGate.value) }
            val otherFailures = vm.captureGate.value.failures.filterNot {
                it == com.example.ppgcollector_android.data.session.CaptureStartFailure.NotificationPermissionDenied
            }
            vm.setNotificationPermissionResult(true) // A second authorization callback on the same VM.
            await { !CaptureUiPolicy.needsNotificationPermission(vm.captureGate.value) }
            assertTrue(vm.captureGate.value.failures.containsAll(otherFailures))
            assertFalse(vm.captureGate.value.canStart)
            vm.setNotificationPermissionResult(false)
            await { CaptureUiPolicy.needsNotificationPermission(vm.captureGate.value) }
            controller.pause().stop()
            org.robolectric.Shadows.shadowOf(app).grantPermissions(permission)
            controller.start().resume()
            await { !CaptureUiPolicy.needsNotificationPermission(vm.captureGate.value) }
            assertSame(vm, androidx.lifecycle.ViewModelProvider(controller.get())[CaptureViewModel::class.java])
            assertFalse(vm.captureGate.value.canStart) // BLE/form gate failures still apply.
            assertTrue(vm.captureGate.value.failures.isNotEmpty())
        } finally { controller.pause().stop().destroy() }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("Activity worker did not settle", condition())
    }

}
