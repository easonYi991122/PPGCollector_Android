import SwiftUI

struct PPGExpertDiagnosticsView: View {
    let session: StoredCaptureSession

    @State private var report: PPGExpertDiagnosticsReport?
    @State private var errorMessage: String?
    @State private var selectedSection: PPGExpertSection = .workbench
    @State private var selectedStage: PPGExpertSignalStage = .causallyPreprocessed
    @State private var selectedChannel: PPGExpertChannel = .ir
    @State private var selectedWindowID = 0
    @State private var localWindowOnly = true
    @State private var showPeaks = true
    @State private var invertRED = false
    @State private var invertIR = false

    var body: some View {
        Group {
            if let report {
                List {
                    Picker("诊断模块", selection: $selectedSection) {
                        ForEach(PPGExpertSection.allCases) { section in
                            Text(section.title).tag(section)
                        }
                    }
                    .pickerStyle(.segmented)

                    switch selectedSection {
                    case .workbench:
                        workbench(report)
                    case .compare:
                        compare(report)
                    case .diagnostics:
                        diagnostics(report)
                    case .ppg:
                        ppg(report)
                    case .spectrum:
                        spectrum(report)
                    }
                }
                .listStyle(.insetGrouped)
            } else if let errorMessage {
                ContentUnavailableView(
                    "诊断失败",
                    systemImage: "exclamationmark.triangle",
                    description: Text(errorMessage)
                )
            } else {
                ProgressView("正在读取 raw 并生成诊断…")
            }
        }
        .navigationTitle("高级诊断")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: session.id) {
            do {
                let value = try await Task.detached(priority: .userInitiated) {
                    try PPGExpertDiagnosticsService.analyze(session: session)
                }.value
                guard !Task.isCancelled else { return }
                report = value
                if let first = value.windows.first {
                    selectedWindowID = first.id
                }
            } catch is CancellationError {
                return
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    @ViewBuilder
    private func workbench(_ report: PPGExpertDiagnosticsReport) -> some View {
        Section {
            Picker("源信号", selection: $selectedStage) {
                ForEach(PPGExpertSignalStage.allCases) { stage in
                    Text(stage.title).tag(stage)
                }
            }
            Picker("通道", selection: $selectedChannel) {
                ForEach(PPGExpertChannel.allCases) { channel in
                    Text(channel.rawValue).tag(channel)
                }
            }
            Toggle("只看当前 8 秒窗口", isOn: $localWindowOnly)
            Toggle("显示峰值 overlay", isOn: $showPeaks)

            if !report.windows.isEmpty {
                Picker("当前窗口", selection: $selectedWindowID) {
                    ForEach(report.windows) { window in
                        Text(String(format: "%.1f s", window.endTimeSeconds))
                            .tag(window.id)
                    }
                }
            }

            signalChart(
                report: report,
                stage: selectedStage,
                channel: selectedChannel,
                window: selectedWindow(report),
                showPeaks: showPeaks
            )
            .frame(height: 230)

            if let window = selectedWindow(report) {
                windowSummary(window)
            }
        } header: {
            Text("Workbench")
        } footer: {
            Text("原始与因果预处理使用同一时间轴；峰值来自当前窗口的 HR trace，暂定 SQI 来自 Python 模板匹配移植。")
        }

        Section {
            if report.stableSegments.isEmpty {
                Text("尚未形成满足 HR + SQI 条件的稳定段。")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(report.stableSegments) { segment in
                    Button {
                        selectWindowNear(segment.startSampleIndex, report: report)
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(String(format: "%.2f–%.2f s", Double(segment.startSampleIndex) / report.sampleRateHz, Double(segment.endSampleIndex) / report.sampleRateHz))
                                .font(.headline)
                            Text("窗口 \(segment.acceptedWindowCount) · HR \(bpm(segment.meanHeartRateBPM)) · SQI \(decimal(segment.meanSQI))")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
        } header: {
            Text("稳定段")
        }
    }

    @ViewBuilder
    private func compare(_ report: PPGExpertDiagnosticsReport) -> some View {
        Section {
            Toggle("反相 RED", isOn: $invertRED)
            Toggle("反相 IR", isOn: $invertIR)
            PPGExpertSignalChart(
                values: transformed(report.rawRED, inverted: invertRED),
                secondaryValues: nil,
                markers: [],
                tint: .red
            )
            .frame(height: 130)
            Text("RED")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.red)
            PPGExpertSignalChart(
                values: transformed(report.rawIR, inverted: invertIR),
                secondaryValues: nil,
                markers: [],
                tint: .blue
            )
            .frame(height: 130)
            Text("IR")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.blue)
        } header: {
            Text("RED / IR 全信号")
        } footer: {
            Text("两条信号保留统一样本轴；反相只影响显示，不修改 raw。")
        }

        Section {
            if let window = selectedWindow(report),
               let cycle = window.signalQuality.trace.cycles.cycles.first,
               let template = nonEmpty(window.signalQuality.trace.cycles.template) {
                PPGExpertSignalChart(
                    values: cycle,
                    secondaryValues: template,
                    markers: [],
                    tint: .blue,
                    secondaryTint: .orange
                )
                .frame(height: 190)
                Text("当前窗口第一拍与模板叠加；模板由全部有效周期逐点均值生成。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                Text("当前窗口没有足够的有效周期。")
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text("统一心拍比较")
        }
    }

    @ViewBuilder
    private func diagnostics(_ report: PPGExpertDiagnosticsReport) -> some View {
        Section {
            diagnosticRow("raw records", report.input.rawRecordCount)
            diagnosticRow("decoded frames", report.input.decodedFrameCount)
            diagnosticRow("accepted frames", report.input.acceptedFrameCount)
            diagnosticRow("accepted samples", report.input.acceptedSampleCount)
            diagnosticRow("missing frames", report.input.missingFrameCount)
            diagnosticRow("duplicate frames", report.input.duplicateFrameCount)
            diagnosticRow("out-of-order frames", report.input.outOfOrderFrameCount)
            diagnosticRow("invalid frames", report.input.structurallyInvalidFrameCount)
            diagnosticRow("discarded bytes", report.input.discardedByteCount)
            diagnosticRow("trailing bytes", report.input.trailingByteCount)
        } header: {
            Text("协议与完整性")
        }

        Section {
            let accepted = report.windows.filter(\.accepted).count
            LabeledContent("总窗口", value: "\(report.windows.count)")
            LabeledContent("接受窗口", value: "\(accepted)")
            LabeledContent("拒绝窗口", value: "\(report.windows.count - accepted)")
            ForEach(report.windows.filter { !$0.accepted }) { window in
                Button {
                    selectedWindowID = window.id
                    selectedSection = .ppg
                } label: {
                    HStack {
                        Text(String(format: "%.1f s", window.endTimeSeconds))
                        Spacer()
                        Text(window.rejectionReason ?? "未知")
                            .foregroundStyle(.orange)
                    }
                    .font(.caption)
                }
            }
        } header: {
            Text("窗口接受/拒绝")
        }
    }

    @ViewBuilder
    private func ppg(_ report: PPGExpertDiagnosticsReport) -> some View {
        Section {
            ForEach(report.windows) { window in
                Button {
                    selectedWindowID = window.id
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(String(format: "%.1f–%.1f s", window.startTimeSeconds, window.endTimeSeconds))
                            Text("\(window.selectedChannel.rawValue) · HR \(bpm(window.selectedBPM)) · SQI \(decimal(window.signalQuality.sqi))")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Image(systemName: window.stable ? "checkmark.circle.fill" : "xmark.circle")
                            .foregroundStyle(window.stable ? .green : .orange)
                    }
                }
                .buttonStyle(.plain)
            }
        } header: {
            Text("稳定段与局部窗口")
        }

        if let window = selectedWindow(report) {
            Section {
                windowSummary(window)
                LabeledContent("选择通道", value: window.selectedChannel.rawValue)
                LabeledContent("HR confidence", value: String(format: "%.3f", window.selectedHeartRate.confidence))
                LabeledContent("HR spectral", value: bpm(window.selectedHeartRate.spectralBPM))
                LabeledContent("RR", value: decimal(window.ratioOfRatios.value))
                LabeledContent("SQI grade", value: window.signalQuality.grade.rawValue)
            } header: {
                Text("当前窗口详情")
            }
        }
    }

    @ViewBuilder
    private func spectrum(_ report: PPGExpertDiagnosticsReport) -> some View {
        if let window = selectedWindow(report),
           let spectral = window.selectedHeartRate.trace.spectral {
            Section {
                PPGExpertSpectrumChart(
                    frequencies: spectral.frequenciesHz,
                    power: spectral.power,
                    cardiacIndices: spectral.cardiacIndices,
                    peakIndex: spectral.peakIndex
                )
                .frame(height: 220)
                LabeledContent("频谱 HR", value: bpm(spectral.spectralBPM))
                LabeledContent("SNR", value: String(format: "%.2f dB", spectral.snrDB))
                LabeledContent("concentration", value: String(format: "%.3f", spectral.concentration))
            } header: {
                Text("Welch PSD / 频谱候选")
            }

            Section {
                let trace = window.signalQuality.trace.cycles
                PPGExpertSignalChart(
                    values: trace.template,
                    secondaryValues: nil,
                    markers: [],
                    tint: .orange
                )
                .frame(height: 170)
                ForEach(Array(trace.cycleQuality.enumerated()), id: \.offset) { index, value in
                    LabeledContent("周期 \(index + 1)", value: String(format: "%.4f", value))
                }
            } header: {
                Text("模板 / 周期相关")
            }
        } else {
            Section {
                Text("当前窗口没有可用频谱或模板 trace。")
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func signalChart(
        report: PPGExpertDiagnosticsReport,
        stage: PPGExpertSignalStage,
        channel: PPGExpertChannel,
        window: PPGExpertWindow?,
        showPeaks: Bool
    ) -> some View {
        let values = sourceValues(report, stage: stage, channel: channel)
        let range: Range<Int>
        if localWindowOnly, let window {
            range = window.startSampleIndex..<min(values.count, window.endSampleIndex + 1)
        } else {
            range = 0..<values.count
        }
        let markers: [Int]
        if showPeaks, let window {
            markers = window.selectedHeartRate.peakIndices
                .map { $0 + window.startSampleIndex }
                .filter { range.contains($0) }
        } else {
            markers = []
        }
        return PPGExpertSignalChart(
            values: Array(values[range]),
            secondaryValues: nil,
            markers: markers.map { $0 - range.lowerBound },
            tint: channel == .red ? .red : .blue
        )
    }

    private func sourceValues(
        _ report: PPGExpertDiagnosticsReport,
        stage: PPGExpertSignalStage,
        channel: PPGExpertChannel
    ) -> [Double] {
        switch (stage, channel) {
        case (.raw, .red):
            report.rawRED
        case (.raw, .ir):
            report.rawIR
        case (.causallyPreprocessed, .red):
            report.preprocessedRED
        case (.causallyPreprocessed, .ir):
            report.preprocessedIR
        }
    }

    private func selectedWindow(
        _ report: PPGExpertDiagnosticsReport
    ) -> PPGExpertWindow? {
        report.windows.first { $0.id == selectedWindowID } ?? report.windows.first
    }

    private func selectWindowNear(
        _ sampleIndex: Int,
        report: PPGExpertDiagnosticsReport
    ) {
        selectedWindowID = report.windows.min {
            abs($0.startSampleIndex - sampleIndex)
                < abs($1.startSampleIndex - sampleIndex)
        }?.id ?? selectedWindowID
    }

    private func windowSummary(_ window: PPGExpertWindow) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(window.accepted ? "稳定窗口" : "待检查窗口")
                .font(.headline)
                .foregroundStyle(window.accepted ? .green : .orange)
            Text("HR \(bpm(window.selectedBPM)) · SQI \(decimal(window.signalQuality.sqi)) · RR \(decimal(window.ratioOfRatios.value))")
                .font(.caption)
                .foregroundStyle(.secondary)
            if let reason = window.rejectionReason {
                Text(reason)
                    .font(.caption)
                    .foregroundStyle(.orange)
            }
        }
    }

    private func diagnosticRow(_ title: String, _ value: Int) -> some View {
        LabeledContent(title, value: "\(value)")
    }

    private func transformed(_ values: [Double], inverted: Bool) -> [Double] {
        inverted ? values.map { -$0 } : values
    }

    private func nonEmpty(_ values: [Double]) -> [Double]? {
        values.isEmpty ? nil : values
    }

    private func bpm(_ value: Double?) -> String {
        value.map { String(format: "%.1f bpm", $0) } ?? "—"
    }

    private func decimal(_ value: Double?) -> String {
        value.map { String(format: "%.3f", $0) } ?? "—"
    }
}

private enum PPGExpertSection: String, CaseIterable, Identifiable {
    case workbench
    case compare
    case diagnostics
    case ppg
    case spectrum

    var id: Self { self }

    var title: String {
        switch self {
        case .workbench: "Workbench"
        case .compare: "Compare"
        case .diagnostics: "Diagnostics"
        case .ppg: "PPG"
        case .spectrum: "Spectrum/Cycle"
        }
    }
}

private struct PPGExpertSignalChart: View {
    let values: [Double]
    let secondaryValues: [Double]?
    let markers: [Int]
    let tint: Color
    var secondaryTint: Color = .orange

    var body: some View {
        Canvas { context, size in
            let allValues = values + (secondaryValues ?? [])
            guard values.count > 1,
                  let minimum = allValues.min(),
                  let maximum = allValues.max() else {
                return
            }
            let span = max(1e-12, maximum - minimum)
            draw(values, color: tint, context: &context, size: size, minimum: minimum, span: span)
            if let secondaryValues, secondaryValues.count > 1 {
                draw(secondaryValues, color: secondaryTint, context: &context, size: size, minimum: minimum, span: span)
            }
            for marker in markers where marker >= 0 && marker < values.count {
                let x = CGFloat(marker) / CGFloat(max(1, values.count - 1)) * size.width
                var path = Path()
                path.move(to: CGPoint(x: x, y: 0))
                path.addLine(to: CGPoint(x: x, y: size.height))
                context.stroke(path, with: .color(.orange.opacity(0.65)), style: StrokeStyle(lineWidth: 0.8, dash: [3, 3]))
            }
        }
        .padding(8)
        .background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 10))
    }

    private func draw(
        _ values: [Double],
        color: Color,
        context: inout GraphicsContext,
        size: CGSize,
        minimum: Double,
        span: Double
    ) {
        var path = Path()
        let count = values.count
        let step = max(1, count / max(2, Int(size.width * 2)))
        for index in Swift.stride(from: 0, to: count, by: step) {
            let x = CGFloat(index) / CGFloat(max(1, count - 1)) * size.width
            let normalized = (values[index] - minimum) / span
            let point = CGPoint(x: x, y: size.height - CGFloat(normalized) * size.height)
            if index == 0 {
                path.move(to: point)
            } else {
                path.addLine(to: point)
            }
        }
        context.stroke(path, with: .color(color), style: StrokeStyle(lineWidth: 1.2, lineJoin: .round))
    }
}

private struct PPGExpertSpectrumChart: View {
    let frequencies: [Double]
    let power: [Double]
    let cardiacIndices: [Int]
    let peakIndex: Int?

    var body: some View {
        Canvas { context, size in
            guard let minPower = power.min(),
                  let maxPower = power.max(),
                  frequencies.count == power.count,
                  power.count > 1 else {
                return
            }
            let cardiac = cardiacIndices.compactMap { power.indices.contains($0) ? power[$0] : nil }
            let lower = cardiac.min() ?? minPower
            let upper = cardiac.max() ?? maxPower
            let span = max(1e-12, upper - lower)
            var path = Path()
            for index in power.indices {
                let x = CGFloat(index) / CGFloat(max(1, power.count - 1)) * size.width
                let y = size.height - CGFloat((power[index] - lower) / span) * size.height
                let point = CGPoint(x: x, y: y.isFinite ? y : size.height)
                if index == 0 { path.move(to: point) } else { path.addLine(to: point) }
            }
            context.stroke(path, with: .color(.purple), style: StrokeStyle(lineWidth: 1.2))
            if let peakIndex, power.indices.contains(peakIndex) {
                let x = CGFloat(peakIndex) / CGFloat(max(1, power.count - 1)) * size.width
                var marker = Path()
                marker.move(to: CGPoint(x: x, y: 0))
                marker.addLine(to: CGPoint(x: x, y: size.height))
                context.stroke(marker, with: .color(.orange), style: StrokeStyle(lineWidth: 1, dash: [4, 3]))
            }
            _ = maxPower
        }
        .padding(8)
        .background(.quaternary.opacity(0.4), in: RoundedRectangle(cornerRadius: 10))
    }
}
