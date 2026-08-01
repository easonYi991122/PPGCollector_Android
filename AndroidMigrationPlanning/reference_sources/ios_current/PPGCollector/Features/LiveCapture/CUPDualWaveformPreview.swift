import SwiftUI

enum CUPWaveformInteractionMode {
    case live
    case interactiveReplay
}

enum CUPReplayWaveformDisplayMode: String, CaseIterable, Identifiable {
    case raw
    case causallyPreprocessed

    var id: Self { self }

    var title: String {
        switch self {
        case .raw:
            "原始"
        case .causallyPreprocessed:
            "因果预处理"
        }
    }
}

struct CUPDualWaveformPreview: View {
    let samples: [CUPPPGSample]
    var compact = false
    var interactionMode: CUPWaveformInteractionMode = .live

    @State private var viewport = CUPWaveformViewport()
    @State private var dragBaseViewport: CUPWaveformViewport?
    @State private var zoomBaseViewport: CUPWaveformViewport?
    @State private var replayDisplayMode: CUPReplayWaveformDisplayMode = .raw
    @State private var replayRawChannels: CUPWaveformChannelValues
    @State private var replayPreprocessedChannels: CUPWaveformChannelValues?
    @State private var isPreparingReplayWaveform = false
    @State private var replayWaveformPreparationTask: Task<Void, Never>?

    init(
        samples: [CUPPPGSample],
        compact: Bool = false,
        interactionMode: CUPWaveformInteractionMode = .live
    ) {
        self.samples = samples
        self.compact = compact
        self.interactionMode = interactionMode
        _replayRawChannels = State(
            initialValue: CUPReplayWaveformValues.raw(samples: samples)
        )
        _replayPreprocessedChannels = State(initialValue: nil)
    }

    var body: some View {
        GeometryReader { geometry in
            interactionGestures(
                VStack(alignment: .leading, spacing: compact ? 9 : 14) {
                    WaveformPanel(
                        title: "RED",
                        color: .red,
                        values: visibleRedValues,
                        height: compact ? 62 : 92
                    )
                    WaveformPanel(
                        title: "IR",
                        color: .blue,
                        values: visibleIRValues,
                        height: compact ? 62 : 92
                    )

                    if interactionMode == .interactiveReplay {
                        replayControls
                    } else {
                        Text("最近 \(samples.count) 个样本 · 通道独立纵向缩放")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .contentShape(Rectangle()),
                width: geometry.size.width
            )
        }
        .frame(height: previewHeight)
        .padding(.vertical, compact ? 0 : 4)
        .onChange(of: samples.count) { _, count in
            viewport.clamp(totalSampleCount: count)
        }
        .onDisappear {
            replayWaveformPreparationTask?.cancel()
        }
    }

    private var previewHeight: CGFloat {
        let panelHeight: CGFloat = compact ? 62 : 92
        let panels = panelHeight * 2
        let panelHeaders: CGFloat = 58
        let spacing: CGFloat = (compact ? 9 : 14) * 2
        let footer: CGFloat
        if interactionMode == .interactiveReplay {
            footer = isPreparingReplayWaveform ? 116 : 94
        } else {
            footer = 18
        }
        return panels + panelHeaders + spacing + footer
    }

    private var visibleRange: Range<Int> {
        switch interactionMode {
        case .live:
            samples.indices
        case .interactiveReplay:
            viewport.visibleRange(totalSampleCount: samples.count)
        }
    }

    private var displayedChannels: CUPWaveformChannelValues {
        guard interactionMode == .interactiveReplay else {
            return CUPReplayWaveformValues.raw(samples: samples)
        }
        guard replayDisplayMode == .causallyPreprocessed,
              let replayPreprocessedChannels else {
            return replayRawChannels
        }
        return replayPreprocessedChannels
    }

    private var visibleRedValues: ArraySlice<Double> {
        displayedChannels.red[visibleRange]
    }

    private var visibleIRValues: ArraySlice<Double> {
        displayedChannels.ir[visibleRange]
    }

    private var replayControls: some View {
        VStack(alignment: .leading, spacing: 7) {
            Picker("重放波形数据", selection: replayDisplayModeSelection) {
                ForEach(CUPReplayWaveformDisplayMode.allCases) { mode in
                    Text(mode.title).tag(mode)
                }
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("replay-waveform-display-mode")

            if isPreparingReplayWaveform {
                ProgressView("正在准备完整预处理波形…")
                    .font(.caption2)
            }

            HStack(spacing: 10) {
                Text(visibleRangeText)
                    .font(.caption2.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .accessibilityIdentifier(
                        "replay-waveform-visible-range"
                    )

                Spacer(minLength: 8)

                Button {
                    setZoom(viewport.zoomScale / 2)
                } label: {
                    Image(systemName: "minus.magnifyingglass")
                }
                .disabled(viewport.zoomScale <= CUPWaveformViewport.minimumZoom)
                .accessibilityLabel("缩小重放波形")

                Text(String(format: "%.1fx", viewport.zoomScale))
                    .font(.caption.monospacedDigit())
                    .frame(minWidth: 38)
                    .accessibilityIdentifier("replay-waveform-zoom")

                Button {
                    setZoom(viewport.zoomScale * 2)
                } label: {
                    Image(systemName: "plus.magnifyingglass")
                }
                .disabled(viewport.zoomScale >= CUPWaveformViewport.maximumZoom)
                .accessibilityLabel("放大重放波形")

                Button("适应") {
                    viewport.reset()
                }
                .font(.caption)
                .accessibilityLabel("显示完整重放波形")
            }
            .buttonStyle(.borderless)

            Text(replayDisplayMode == .raw
                ? "双指缩放 · 放大后横向拖动 · RED/IR 共用时间范围"
                : "ios_baseline_0.1 因果预处理 · 仅供可视化，不代表临床结论")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }

    private var visibleRangeText: String {
        guard !visibleRange.isEmpty else {
            return "无样本"
        }
        let first = visibleRange.lowerBound
        let last = visibleRange.upperBound - 1
        let firstTime = Double(first) / Double(CUPBatchProtocolV1.sampleRateHz)
        let lastTime = Double(last) / Double(CUPBatchProtocolV1.sampleRateHz)
        return "\(first)–\(last) · \(String(format: "%.2f", firstTime))–\(String(format: "%.2f", lastTime)) s"
    }

    private func horizontalDragGesture(width: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 8)
            .onChanged { value in
                guard abs(value.translation.width)
                        > abs(value.translation.height),
                      width > 0 else {
                    return
                }
                if dragBaseViewport == nil {
                    dragBaseViewport = viewport
                }
                var candidate = dragBaseViewport ?? viewport
                let visibleCount = candidate.visibleSampleCount(
                    totalSampleCount: samples.count
                )
                candidate.pan(
                    sampleDelta:
                        -Double(value.translation.width / width)
                        * Double(visibleCount),
                    totalSampleCount: samples.count
                )
                viewport = candidate
            }
            .onEnded { _ in
                dragBaseViewport = nil
            }
    }

    private var magnificationGesture: some Gesture {
        MagnificationGesture()
            .onChanged { value in
                if zoomBaseViewport == nil {
                    zoomBaseViewport = viewport
                }
                let base = zoomBaseViewport ?? viewport
                var candidate = base
                candidate.setZoom(
                    base.zoomScale * value,
                    totalSampleCount: samples.count
                )
                viewport = candidate
            }
            .onEnded { _ in
                zoomBaseViewport = nil
            }
    }

    private func setZoom(_ scale: Double) {
        viewport.setZoom(
            scale,
            totalSampleCount: samples.count
        )
    }

    private var replayDisplayModeSelection: Binding<CUPReplayWaveformDisplayMode> {
        Binding(
            get: { replayDisplayMode },
            set: { selectReplayDisplayMode($0) }
        )
    }

    private func selectReplayDisplayMode(_ mode: CUPReplayWaveformDisplayMode) {
        guard mode != replayDisplayMode else {
            return
        }
        guard mode == .causallyPreprocessed else {
            replayWaveformPreparationTask?.cancel()
            isPreparingReplayWaveform = false
            replayDisplayMode = .raw
            return
        }
        if replayPreprocessedChannels != nil {
            replayDisplayMode = mode
            return
        }

        isPreparingReplayWaveform = true
        let sourceSamples = samples
        replayWaveformPreparationTask?.cancel()
        replayWaveformPreparationTask = Task {
            let channels = await Task.detached(priority: .userInitiated) {
                CUPReplayWaveformValues.causallyPreprocessed(
                    samples: sourceSamples
                )
            }.value
            guard !Task.isCancelled else {
                return
            }
            replayPreprocessedChannels = channels
            isPreparingReplayWaveform = false
            replayDisplayMode = .causallyPreprocessed
        }
    }

    @ViewBuilder
    private func interactionGestures<Content: View>(
        _ content: Content,
        width: CGFloat
    ) -> some View {
        if interactionMode == .interactiveReplay {
            content
                .simultaneousGesture(horizontalDragGesture(width: width))
                .simultaneousGesture(magnificationGesture)
        } else {
            content
        }
    }
}

private struct WaveformPanel: View {
    let title: String
    let color: Color
    let values: ArraySlice<Double>
    let height: CGFloat

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(title)
                    .font(.headline)
                    .foregroundStyle(color)
                Spacer()
                if let latest = values.last {
                    Text(latest.formatted(.number.precision(.fractionLength(0))))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }

            Canvas { context, size in
                let plot = makePlot(size: size)
                guard plot.points.count > 1 else {
                    return
                }

                let span = max(1, plot.maximum - plot.minimum)
                var path = Path()

                for (index, point) in plot.points.enumerated() {
                    let x = CGFloat(point.offset)
                        * size.width
                        / CGFloat(max(1, values.count - 1))
                    let normalized = (point.value - plot.minimum) / span
                    let y = size.height - CGFloat(normalized) * size.height
                    let renderedPoint = CGPoint(x: x, y: y)
                    if index == 0 {
                        path.move(to: renderedPoint)
                    } else {
                        path.addLine(to: renderedPoint)
                    }
                }

                context.stroke(
                    path,
                    with: .color(color),
                    style: StrokeStyle(lineWidth: 1.25, lineJoin: .round)
                )
            }
            .frame(height: height)
            .padding(compactPadding)
            .background(.quaternary.opacity(0.45), in: RoundedRectangle(cornerRadius: 10))
        }
    }

    private var compactPadding: CGFloat {
        height < 80 ? 6 : 8
    }

    private func makePlot(size: CGSize) -> WaveformPlot {
        guard !values.isEmpty else {
            return WaveformPlot(points: [], minimum: 0, maximum: 1)
        }

        let maximumPointCount = max(2, Int(size.width * 2))
        let binCount = max(1, maximumPointCount / 2)
        let sampleCount = values.count
        var points: [WaveformPlotPoint] = []
        points.reserveCapacity(min(sampleCount, maximumPointCount))
        var overallMinimum = Double.greatestFiniteMagnitude
        var overallMaximum = -Double.greatestFiniteMagnitude

        if sampleCount <= maximumPointCount {
            for (offset, value) in values.enumerated() {
                overallMinimum = min(overallMinimum, value)
                overallMaximum = max(overallMaximum, value)
                points.append(
                    WaveformPlotPoint(offset: offset, value: value)
                )
            }
        } else {
            for bin in 0..<binCount {
                let lowerOffset = bin * sampleCount / binCount
                let upperOffset = min(
                    sampleCount,
                    (bin + 1) * sampleCount / binCount
                )
                guard lowerOffset < upperOffset else {
                    continue
                }

                var minimumPoint: WaveformPlotPoint?
                var maximumPoint: WaveformPlotPoint?
                for offset in lowerOffset..<upperOffset {
                    let value = values[values.index(
                        values.startIndex,
                        offsetBy: offset
                    )]
                    let point = WaveformPlotPoint(
                        offset: offset,
                        value: value
                    )
                    if let currentMinimum = minimumPoint {
                        if value < currentMinimum.value {
                            minimumPoint = point
                        }
                    } else {
                        minimumPoint = point
                    }
                    if let currentMaximum = maximumPoint {
                        if value > currentMaximum.value {
                            maximumPoint = point
                        }
                    } else {
                        maximumPoint = point
                    }
                    overallMinimum = min(overallMinimum, value)
                    overallMaximum = max(overallMaximum, value)
                }

                if let minimumPoint, let maximumPoint {
                    if minimumPoint.offset <= maximumPoint.offset {
                        points.append(minimumPoint)
                        if maximumPoint.offset != minimumPoint.offset {
                            points.append(maximumPoint)
                        }
                    } else {
                        points.append(maximumPoint)
                        points.append(minimumPoint)
                    }
                }
            }
        }

        return WaveformPlot(
            points: points,
            minimum: overallMinimum,
            maximum: overallMaximum
        )
    }
}

nonisolated struct CUPWaveformChannelValues: Equatable, Sendable {
    let red: [Double]
    let ir: [Double]
}

nonisolated enum CUPReplayWaveformValues {
    static func raw(samples: [CUPPPGSample]) -> CUPWaveformChannelValues {
        CUPWaveformChannelValues(
            red: samples.map { Double($0.red) },
            ir: samples.map { Double($0.ir) }
        )
    }

    /// Processes the complete recorded signal once per channel, before the
    /// selected viewport is sliced. This preserves causal filter state while a
    /// user pans or zooms through the replay.
    static func causallyPreprocessed(
        samples: [CUPPPGSample],
        profile: PPGPreprocessingProfile = .iosBaseline01
    ) -> CUPWaveformChannelValues {
        var redPreprocessor = PPGPreprocessor(profile: profile)
        var irPreprocessor = PPGPreprocessor(profile: profile)
        var red: [Double] = []
        var ir: [Double] = []
        red.reserveCapacity(samples.count)
        ir.reserveCapacity(samples.count)

        for sample in samples {
            red.append(
                redPreprocessor.process(raw: Double(sample.red)).sample?
                    .bandpassed ?? 0
            )
            ir.append(
                irPreprocessor.process(raw: Double(sample.ir)).sample?
                    .bandpassed ?? 0
            )
        }
        return CUPWaveformChannelValues(red: red, ir: ir)
    }
}

private struct WaveformPlotPoint {
    let offset: Int
    let value: Double
}

private struct WaveformPlot {
    let points: [WaveformPlotPoint]
    let minimum: Double
    let maximum: Double
}
