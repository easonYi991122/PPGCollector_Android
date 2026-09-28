package com.example.ppgcollector_android

import com.example.ppgcollector_android.core.signal.OfflineDisplaySpectrum
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import com.example.ppgcollector_android.data.session.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sin

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = PpgCollectorApplication::class)
class SessionRenderModelTest {
    @Test fun plotAndSpectrumRunOffCallerAndCacheTheLatestKey() = runBlocking {
        val caller = Thread.currentThread()
        val computations = mutableListOf<String>()
        val model = SessionRenderModel(onCompute = { kind ->
            assertNotSame(caller, Thread.currentThread())
            computations += kind
        })
        val values = DoubleArray(1_500_000) { it.toDouble() }
        val key = SessionRenderKey("raw-sha", listOf(SessionRenderSeries("RED/RAW", values, true)),
            null, 111_111..1_499_999, 2048)
        val first = model.render(key)!!
        assertTrue(first.plots.single().points.size <= 4096)
        assertEquals(-1_499_999.0, first.plots.single().minimum, 0.0)
        assertSame(first, model.render(key.copy()))
        assertEquals(listOf("plot"), computations)
        val signal = DoubleArray(10_000) { sin(it * 0.09) }
        val spectrumKey = SessionSpectrumKey("raw-sha", "IR/ZERO", signal, signal.indices)
        val spectrum = model.spectrum(spectrumKey)!!
        assertSame(spectrum, model.spectrum(spectrumKey.copy()))
        assertEquals(listOf("plot", "spectrum"), computations)
        val expected = OfflineDisplaySpectrum.estimate(signal, signal.indices)
        assertArrayEquals(expected.frequenciesHz, spectrum.frequenciesHz, 0.0)
        assertArrayEquals(expected.power, spectrum.power, 1e-12)
    }

    @Test fun cancelledOrSupersededRangeCannotReplaceLatestSnapshot() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val model = SessionRenderModel(onCompute = {
            if (calls.incrementAndGet() == 1) { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
        })
        val values = DoubleArray(1_000_000) { it.toDouble() }
        val old = SessionRenderKey("A", listOf(SessionRenderSeries("IR", values)), null, values.indices, 200)
        val first = async { model.render(old) }
        withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
        val current = old.copy(range = 100..500)
        val latest = model.render(current)
        release.countDown()
        assertNull(first.await())
        assertSame(latest, model.render(current))
        val cancellationChecks = AtomicInteger()
        assertThrows(CancellationException::class.java) {
            cancellableDisplaySpectrum(DoubleArray(50_000) { sin(it * 0.1) }, 0..49_999) {
                if (cancellationChecks.incrementAndGet() == 3) throw CancellationException()
            }
        }
        assertEquals(3, cancellationChecks.get())
    }

    @Test fun budgetPreviewPlotsOriginalSourceCursorsAndRetainsTheSourceClock() = runBlocking {
        val sourceIndices = longArrayOf(0, 17, 100, 900, 1_000, 5_000)
        val key = SessionRenderKey("preview", listOf(SessionRenderSeries("RED/RAW",
            doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0), true)), sourceIndices,
            90..1_010, 300, breaks = intArrayOf(3), gaps = intArrayOf(3), gapSources = longArrayOf(850))
        val snapshot = SessionRenderModel().render(key)!!
        assertEquals(listOf(100L, 900L, 1_000L), snapshot.plots.single().points.map { it.sourceIndex })
        assertEquals(listOf(-3.0, -4.0, -5.0), snapshot.plots.single().points.map { it.value })
        assertTrue(snapshot.plots.single().points[1].startsSegment)
        assertEquals(listOf(850L), snapshot.gapSources)
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun productionRememberedControlsSurviveRawPartialToMetricsAndFilters() = runTest {
        val root = java.nio.file.Files.createTempDirectory("review-partial-controls")
        try {
            val session = ReviewSessionFixtures.writeProtocols(root).first()
            var partial: CaptureSessionSignalTrace? = null
            val final = CaptureSessionOfflineAnalysisService.loadSignalTrace(session, onPartial = { if (partial == null) partial = it })
            val trace = mutableStateOf(partial!!)
            val frameClock = BroadcastFrameClock()
            val recomposer = Recomposer(coroutineContext + frameClock)
            val runner = launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
            val composition = Composition(object : AbstractApplier<Unit>(Unit) {
                override fun insertBottomUp(index: Int, instance: Unit) = Unit
                override fun insertTopDown(index: Int, instance: Unit) = Unit
                override fun move(from: Int, to: Int, count: Int) = Unit
                override fun remove(index: Int, count: Int) = Unit
                override fun onClear() = Unit
            }, recomposer)
            var controls: SessionReviewControls<String>? = null
            composition.setContent { controls = rememberSessionReviewControls(trace.value, "RAW") }
            runCurrent()
            val original = controls!!
            original.stage.value = "ZERO"
            original.showGapMarkers.value = !original.showGapMarkers.value
            val marker = original.showGapMarkers.value
            val viewport = ReplayWaveformViewport().apply { showWindow(2, 4, final.reviewSampleCount()) }
            original.viewport.value = viewport
            trace.value = final
            Snapshot.sendApplyNotifications()
            runCurrent(); frameClock.sendFrame(1); runCurrent()
            assertSame(original, controls)
            assertEquals("ZERO", controls!!.stage.value)
            assertEquals(marker, controls!!.showGapMarkers.value)
            assertSame(viewport, controls!!.viewport.value)
            composition.dispose(); recomposer.close(); runner.cancelAndJoin()
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun metricSnapshotIsBoundedByPixelsWithoutBridgingRejectedIslands() {
        val points = (0 until 100_000 step 200).map { it to sin(it.toDouble()) }
        val boundaries = (100 until 100_000 step 200).toList().toIntArray()
        val segments = boundedMetricSegments(points, boundaries, 200, 0..99_999, 100)
        assertTrue(segments.sumOf { it.size } <= 200)
        assertTrue(segments.all { it.size == 1 })
    }

    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Test fun productionCanvasDrawsTheWorkerSnapshotAndRedrawDoesNotRescan() {
        val activity = org.robolectric.Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup()
        val computes = AtomicInteger()
        val worker = SessionRenderModel(onCompute = { computes.incrementAndGet() })
        val values = DoubleArray(1_000_000) { sin(it * 0.03) }
        val view = androidx.compose.ui.platform.ComposeView(activity.get())
        try {
            view.setContent {
                androidx.compose.material3.MaterialTheme {
                    CompleteSignalChart(listOf(CompleteSignalSeries("fixture", androidx.compose.ui.graphics.Color.Red, values)),
                        sourceIdentity = values, visibleRange = values.indices, renderModel = worker,
                        modifier = androidx.compose.ui.Modifier)
                }
            }
            val width = 600
            val height = 200
            activity.get().setContentView(view, android.view.ViewGroup.LayoutParams(width, height))
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            fun layoutAndDraw() {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))
                view.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY))
                view.layout(0, 0, width, height)
                view.draw(canvas)
            }
            var waveformPixels = 0
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (waveformPixels == 0 && System.nanoTime() < deadline) {
                layoutAndDraw()
                // Below the title label, red pixels can only come from the Canvas waveform.
                for (y in 60 until height - 20) for (x in 20 until width - 20) {
                    val pixel = bitmap.getPixel(x, y)
                    if (android.graphics.Color.red(pixel) > 180 && android.graphics.Color.green(pixel) < 100) waveformPixels++
                }
                if (waveformPixels == 0) Thread.sleep(5)
            }
            assertTrue("Canvas did not consume the computed waveform", waveformPixels > 0)
            assertEquals(1, computes.get())
            repeat(3) { view.invalidate(); layoutAndDraw() }
            assertEquals(1, computes.get())
        } finally { view.disposeComposition(); activity.pause().stop().destroy() }
    }

}
