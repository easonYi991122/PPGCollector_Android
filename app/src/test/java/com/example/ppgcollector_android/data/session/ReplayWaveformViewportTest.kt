package com.example.ppgcollector_android.data.session

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplayWaveformViewportTest {
    @Test
    fun zoomKeepsVisibleCenterStable() {
        val viewport = ReplayWaveformViewport()

        viewport.setZoom(2.0, totalSampleCount = 1_000)

        assertEquals(2.0, viewport.zoomScale, 0.0)
        assertEquals(250 until 750, viewport.visibleRange(1_000))
    }

    @Test
    fun horizontalPanMovesAndClampsVisibleRange() {
        val viewport = ReplayWaveformViewport()
        viewport.setZoom(5.0, totalSampleCount = 1_000)

        viewport.pan(100.0, totalSampleCount = 1_000)
        assertEquals(500 until 700, viewport.visibleRange(1_000))

        viewport.pan(10_000.0, totalSampleCount = 1_000)
        assertEquals(800 until 1_000, viewport.visibleRange(1_000))

        viewport.pan(-10_000.0, totalSampleCount = 1_000)
        assertEquals(0 until 200, viewport.visibleRange(1_000))
    }

    @Test
    fun sampleRefreshClampsWithoutResettingZoom() {
        val viewport = ReplayWaveformViewport(zoomScale = 4.0, visibleStart = 700.0)
        viewport.clamp(1_000)
        assertEquals(700 until 950, viewport.visibleRange(1_000))

        viewport.clamp(400)

        assertEquals(4.0, viewport.zoomScale, 0.0)
        assertEquals(300 until 400, viewport.visibleRange(400))
    }

    @Test
    fun zoomBoundsAndResetRemainDeterministic() {
        val viewport = ReplayWaveformViewport()
        viewport.setZoom(1_000.0, totalSampleCount = 1_000)
        assertEquals(ReplayWaveformViewport.maximumZoom, viewport.zoomScale, 0.0)

        viewport.setZoom(0.1, totalSampleCount = 1_000)
        assertEquals(ReplayWaveformViewport.minimumZoom, viewport.zoomScale, 0.0)

        viewport.pan(100.0, 1_000)
        viewport.reset()
        assertEquals(ReplayWaveformViewport(), viewport)
    }
}
