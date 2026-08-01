import Testing
@testable import PPGCollector

struct CUPWaveformViewportTests {
    @Test
    func zoomKeepsTheVisibleCenterStable() {
        var viewport = CUPWaveformViewport()

        viewport.setZoom(2, totalSampleCount: 1_000)

        #expect(viewport.zoomScale == 2)
        #expect(viewport.visibleRange(totalSampleCount: 1_000) == 250..<750)
    }

    @Test
    func horizontalPanMovesAndClampsTheVisibleRange() {
        var viewport = CUPWaveformViewport()
        viewport.setZoom(5, totalSampleCount: 1_000)

        viewport.pan(sampleDelta: 100, totalSampleCount: 1_000)
        #expect(viewport.visibleRange(totalSampleCount: 1_000) == 500..<700)

        viewport.pan(sampleDelta: 10_000, totalSampleCount: 1_000)
        #expect(viewport.visibleRange(totalSampleCount: 1_000) == 800..<1_000)

        viewport.pan(sampleDelta: -10_000, totalSampleCount: 1_000)
        #expect(viewport.visibleRange(totalSampleCount: 1_000) == 0..<200)
    }

    @Test
    func sampleRefreshClampsWithoutResettingZoom() {
        var viewport = CUPWaveformViewport(
            zoomScale: 4,
            visibleStart: 700
        )
        viewport.clamp(totalSampleCount: 1_000)
        #expect(viewport.visibleRange(totalSampleCount: 1_000) == 700..<950)

        viewport.clamp(totalSampleCount: 400)

        #expect(viewport.zoomScale == 4)
        #expect(viewport.visibleRange(totalSampleCount: 400) == 300..<400)
    }

    @Test
    func zoomBoundsAndResetRemainDeterministic() {
        var viewport = CUPWaveformViewport()
        viewport.setZoom(1_000, totalSampleCount: 1_000)
        #expect(viewport.zoomScale == CUPWaveformViewport.maximumZoom)

        viewport.setZoom(0.1, totalSampleCount: 1_000)
        #expect(viewport.zoomScale == CUPWaveformViewport.minimumZoom)

        viewport.pan(sampleDelta: 100, totalSampleCount: 1_000)
        viewport.reset()
        #expect(viewport == CUPWaveformViewport())
    }
}
