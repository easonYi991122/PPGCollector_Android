import SwiftUI

struct DeviceListView: View {
    @ObservedObject var bluetoothService: BLECentralService
    @ObservedObject var captureController: CaptureSessionController
    @StateObject private var sessionStore = CaptureSessionStore()
    @State private var showSavedSessions = false
    @State private var showRecoveryNotice = false
    @State private var hasPresentedRecoveryNotice = false

    var body: some View {
        VStack(spacing: 0) {
            statusHeader

            if bluetoothService.discoveredDevices.isEmpty {
                ContentUnavailableView {
                    Label("未发现 CUP 设备", systemImage: "wave.3.right")
                } description: {
                    Text(emptyStateDescription)
                } actions: {
                    Button("重新扫描") {
                        bluetoothService.startScanning(clearPreviousResults: true)
                    }
                    .disabled(bluetoothService.availability != .poweredOn)
                }
            } else {
                deviceList
            }
        }
        .navigationTitle(
            bluetoothService.connectionPhase.deviceID == nil
                ? "连接设备"
                : "PPG 采集"
        )
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button {
                    showSavedSessions = true
                } label: {
                    Label(
                        "已保存记录",
                        systemImage:
                            sessionStore.recoveryCandidates.isEmpty
                            ? "folder"
                            : "folder.badge.questionmark"
                    )
                }

                Button {
                    if bluetoothService.isScanning {
                        bluetoothService.stopScanning()
                    } else {
                        bluetoothService.startScanning()
                    }
                } label: {
                    Label(
                        bluetoothService.isScanning ? "停止扫描" : "扫描",
                        systemImage: bluetoothService.isScanning
                            ? "stop.circle"
                            : "arrow.clockwise"
                    )
                }
                .disabled(bluetoothService.availability != .poweredOn)
            }
        }
        .task {
            bluetoothService.startScanning()
            sessionStore.refresh()
        }
        .navigationDestination(isPresented: $showSavedSessions) {
            SavedSessionsView(store: sessionStore)
        }
        .onDisappear {
            captureController.handleViewExit()
        }
        .onChange(of: captureController.state) { _, state in
            switch state {
            case .finalized, .failed:
                sessionStore.refresh()
            case .idle, .preflighting, .recording, .stopping:
                break
            }
        }
        .onChange(
            of: sessionStore.recoveryCandidates.map(\.id)
        ) { _, candidateIDs in
            guard !candidateIDs.isEmpty,
                  !hasPresentedRecoveryNotice else {
                return
            }
            hasPresentedRecoveryNotice = true
            showRecoveryNotice = true
        }
        .alert(
            "发现可恢复记录",
            isPresented: $showRecoveryNotice
        ) {
            Button("稍后", role: .cancel) {}
            Button("查看记录") {
                showSavedSessions = true
            }
        } message: {
            Text(
                "检测到 \(sessionStore.recoveryCandidates.count) 个未完整会话，其原文件不会被自动修改。可进入详情检查并另存恢复副本。"
            )
        }
    }

    private var statusHeader: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                Image(systemName: bluetoothService.availability.systemImage)
                    .foregroundStyle(statusColor)
                    .frame(width: 24)

                VStack(alignment: .leading, spacing: 2) {
                    Text(bluetoothService.availability.title)
                        .font(.headline)
                    Text(scanStatusText)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                Spacer()

                if bluetoothService.isScanning {
                    ProgressView()
                }
            }

            if let error = bluetoothService.lastError {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "exclamationmark.circle.fill")
                    Text(error)
                        .font(.footnote)
                    Spacer()
                    if bluetoothService.canRetryLastConnection {
                        Button("重试") {
                            bluetoothService.retryLastConnection()
                        }
                        .font(.footnote)
                    }
                    Button("关闭") {
                        bluetoothService.clearError()
                    }
                    .font(.footnote)
                }
                .foregroundStyle(.red)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(.thinMaterial)
    }

    private var deviceList: some View {
        List {
            if shouldShowLiveWorkspace {
                liveWaveformSection
            }
            if shouldShowCaptureSection {
                captureSection
                liveMetricsSection
            }

            Section("附近设备") {
                ForEach(bluetoothService.discoveredDevices) { device in
                    DeviceRow(
                        device: device,
                        connectionPhase: bluetoothService.connectionPhase,
                        connect: {
                            bluetoothService.connect(to: device.id)
                        },
                        disconnect: {
                            bluetoothService.disconnect()
                        }
                    )
                }
            }

            if let deviceID = bluetoothService.connectionPhase.deviceID {
                connectionDiagnostics(deviceID: deviceID)
            }
        }
        .listStyle(.insetGrouped)
    }

    private var liveWaveformSection: some View {
        Section {
            CUPDualWaveformPreview(
                samples: bluetoothService.liveWaveformSnapshot.samples,
                compact: true
            )
        } header: {
            HStack {
                Text("实时原始波形")
                Spacer()
                Label(
                    bluetoothService.streamFreshness.title,
                    systemImage: bluetoothService.streamFreshness == .fresh
                        ? "checkmark.circle.fill"
                        : "clock"
                )
                .foregroundStyle(
                    bluetoothService.streamFreshness == .fresh
                        ? .green
                        : .orange
                )
            }
        }
    }

    private var liveMetricsSection: some View {
        let snapshot = bluetoothService.liveMetricSnapshot

        return Section("实时指标") {
            LazyVGrid(
                columns: [
                    GridItem(.flexible(), spacing: 10),
                    GridItem(.flexible(), spacing: 10)
                ],
                spacing: 10
            ) {
                scalarMetricCard(
                    title: "心率",
                    unit: "bpm",
                    systemImage: "heart.fill",
                    tint: .red,
                    identifier: "live-metric-heart-rate",
                    result: snapshot.heartRateBPM,
                    formatter: { String(format: "%.0f", $0) }
                )
                scalarMetricCard(
                    title: "RR（Red/IR）",
                    unit: "",
                    systemImage: "lungs.fill",
                    tint: .blue,
                    identifier: "live-metric-ratio-of-ratios",
                    result: snapshot.ratioOfRatios,
                    formatter: { String(format: "%.3f", $0) }
                )
                scalarMetricCard(
                    title: "信号质量",
                    unit: "",
                    systemImage: "waveform.path.ecg",
                    tint: .green,
                    identifier: "live-metric-sqi",
                    result: snapshot.signalQuality,
                    formatter: { String(format: "%.2f", $0) }
                )
                LiveMetricCard(
                    title: "血压",
                    value: bloodPressureText(snapshot.bloodPressure),
                    unit: "mmHg",
                    systemImage: "gauge.medium",
                    tint: .purple,
                    reason: snapshot.bloodPressure
                        .unavailableReason?.message,
                    provisional: false,
                    identifier: "live-metric-blood-pressure"
                )
            }

            Text(
                "心率、RR 和 SQI 使用连续 8 秒窗口、约 1 Hz 更新；RR 只是 Red/IR 诊断比值，不能当作 SpO₂ 百分比。SQI 当前为暂定评分。"
            )
            .font(.caption)
            .foregroundStyle(.secondary)
        }
    }

    private func scalarMetricCard(
        title: String,
        unit: String,
        systemImage: String,
        tint: Color,
        identifier: String,
        result: MetricResult<Double>,
        formatter: (Double) -> String
    ) -> some View {
        LiveMetricCard(
            title: title,
            value: result.value.map(formatter) ?? "—",
            unit: unit,
            systemImage: systemImage,
            tint: tint,
            reason: result.unavailableReason?.message,
            provisional: result.isProvisional,
            identifier: identifier
        )
    }

    private func bloodPressureText(
        _ result: MetricResult<BloodPressureReading>
    ) -> String {
        guard let reading = result.value else {
            return "—"
        }
        return String(
            format: "%.0f/%.0f",
            reading.systolicMMHg,
            reading.diastolicMMHg
        )
    }

    @ViewBuilder
    private func connectionDiagnostics(deviceID: UUID) -> some View {
        Section("链路诊断") {
            LabeledContent("Peripheral", value: deviceID.uuidString)
            LabeledContent("Profile", value: bluetoothService.profile.identifier)
            let connectionDiagnostics =
                bluetoothService.connectionAttemptDiagnostics
            LabeledContent(
                "建链尝试",
                value: "\(connectionDiagnostics.attemptCount)"
            )
            if let operation = connectionDiagnostics.activeOperation {
                LabeledContent(
                    "当前阶段",
                    value: "\(operation.title)（有超时保护）"
                )
            }
            if connectionDiagnostics.timeoutCount > 0 {
                LabeledContent(
                    "建链超时",
                    value: "\(connectionDiagnostics.timeoutCount)"
                )
            }
            if connectionDiagnostics.ignoredStaleCallbackCount > 0 {
                LabeledContent(
                    "已忽略旧回调",
                    value:
                        "\(connectionDiagnostics.ignoredStaleCallbackCount)"
                )
            }
            LabeledContent(
                "已发现服务",
                value: "\(bluetoothService.discoveredServiceUUIDs.count)"
            )

            ForEach(bluetoothService.discoveredServiceUUIDs, id: \.self) { uuid in
                LabeledContent("Service UUID") {
                    Text(uuid)
                        .font(.caption.monospaced())
                        .textSelection(.enabled)
                }
            }

            ForEach(bluetoothService.discoveredCharacteristics) { characteristic in
                VStack(alignment: .leading, spacing: 5) {
                    HStack {
                        Text(characteristic.role ?? "Characteristic")
                            .font(.subheadline.weight(.medium))
                        Spacer()
                        if characteristic.isNotifying {
                            Label("已订阅", systemImage: "checkmark.circle.fill")
                                .font(.caption)
                                .foregroundStyle(.green)
                        }
                    }
                    Text(characteristic.uuid)
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                        .textSelection(.enabled)
                    Text(characteristic.propertiesText)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Text(gattStatusText)
                .font(.footnote)
                .foregroundStyle(.secondary)
        }

        if shouldShowStreamingDiagnostics {
            Section("数据流诊断") {
                let diagnostics = bluetoothService.streamingDiagnostics

                LabeledContent("通知状态", value: notificationStatusText)
                LabeledContent(
                    "合法数据新鲜度",
                    value: bluetoothService.streamFreshness.title
                )
                Picker(
                    "波形刷新",
                    selection: Binding(
                        get: {
                            bluetoothService.waveformRefreshRateHz
                        },
                        set: {
                            bluetoothService
                                .setWaveformRefreshRateHz($0)
                        }
                    )
                ) {
                    Text("2 Hz").tag(2.0)
                    Text("5 Hz").tag(5.0)
                    Text("10 Hz").tag(10.0)
                }
                LabeledContent(
                    "波形快照",
                    value:
                        "\(bluetoothService.liveWaveformSnapshot.publicationSequence)"
                )
                let metricDiagnostics =
                    bluetoothService.liveMetricRuntimeDiagnostics
                LabeledContent(
                    "指标窗口",
                    value:
                        "\(metricDiagnostics.bufferedSampleCount)/\(PPGLiveMetricRuntimeProfile.iosBaseline01.windowSampleCount)"
                )
                LabeledContent(
                    "指标计算",
                    value:
                        "\(metricDiagnostics.completedAnalysisCount)/\(metricDiagnostics.scheduledAnalysisCount)"
                )
                if metricDiagnostics.discardedAnalysisCount > 0 {
                    LabeledContent(
                        "过期计算丢弃",
                        value:
                            "\(metricDiagnostics.discardedAnalysisCount)"
                    )
                }
                LabeledContent("通知 chunks", value: "\(diagnostics.notificationChunks)")
                LabeledContent("接收字节", value: "\(diagnostics.receivedBytes)")
                LabeledContent("合法帧", value: "\(diagnostics.decodedFrames)")
                LabeledContent("接受样本", value: "\(diagnostics.acceptedSamples)")
                LabeledContent("缺失帧", value: "\(diagnostics.sequenceStats.missingFrames)")
                LabeledContent("重复帧", value: "\(diagnostics.sequenceStats.duplicateFrames)")
                LabeledContent("乱序帧", value: "\(diagnostics.sequenceStats.outOfOrderFrames)")
                LabeledContent("缺失样本", value: "\(diagnostics.sequenceStats.missingSamples)")
                LabeledContent("待组帧字节", value: "\(diagnostics.pendingDecoderBytes)")
                LabeledContent("丢弃字节", value: "\(diagnostics.decoderStats.bytesDiscarded)")
                LabeledContent("结构错误", value: "\(diagnostics.structurallyInvalidFrames)")

                if diagnostics.notificationErrors > 0 {
                    LabeledContent("通知错误", value: "\(diagnostics.notificationErrors)")
                        .foregroundStyle(.red)
                }
                if let date = bluetoothService.lastNotificationAt {
                    LabeledContent("最近通知") {
                        Text(date, style: .time)
                    }
                }
                if let event = diagnostics.lastSequenceEvent {
                    LabeledContent("最近序号", value: sequenceEventText(event))
                }
                if diagnostics.notificationChunks > 0, diagnostics.decodedFrames == 0 {
                    Label(
                        "已经收到通知，但尚未识别出 CUP 408-byte 合法帧；请保存原始 hex 以核对协议。",
                        systemImage: "exclamationmark.triangle.fill"
                    )
                    .font(.footnote)
                    .foregroundStyle(.orange)
                }
                if !diagnostics.lastChunk.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("最近 chunk hex")
                            .font(.subheadline.weight(.medium))
                        Text(diagnostics.lastChunkHex)
                            .font(.caption2.monospaced())
                            .foregroundStyle(.secondary)
                            .textSelection(.enabled)
                    }
                }
                if !diagnostics.capturedWirePrefix.isEmpty {
                    DisclosureGroup("连接后前 512 bytes") {
                        Text(diagnostics.capturedWirePrefixHex)
                            .font(.caption2.monospaced())
                            .foregroundStyle(.secondary)
                            .textSelection(.enabled)
                            .padding(.top, 6)
                    }
                }

                let recordingDiagnostics = captureController.recordingDiagnostics
                if captureController.state.isRecording
                    || captureController.state.isBusy
                    || captureController.lastSummary != nil {
                    GroupBox("写盘状态") {
                        VStack(alignment: .leading, spacing: 5) {
                            LabeledContent(
                                "写入队列",
                                value: "\(recordingDiagnostics.pendingWriteCount)/256"
                            )
                            LabeledContent(
                                "已写 raw chunks",
                                value: "\(recordingDiagnostics.writer.rawChunkCount)"
                            )
                            LabeledContent(
                                "已写 CSV 样本",
                                value: "\(recordingDiagnostics.writer.csvRows)"
                            )
                            if let lastFlush = recordingDiagnostics.writer.lastFlushUTC {
                                LabeledContent("最近落盘") {
                                    Text(lastFlush, style: .time)
                                }
                            }
                            if let writeError = recordingDiagnostics.writeError {
                                Label(
                                    writeError,
                                    systemImage: "exclamationmark.triangle.fill"
                                )
                                .font(.footnote)
                                .foregroundStyle(.red)
                            } else {
                                LabeledContent("写盘错误", value: "无")
                            }
                        }
                    }
                }
            }
        }

    }

    private var captureSection: some View {
        Section("数据记录") {
            TextField("名称（字母、数字、_、-）", text: $captureController.sessionName)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .disabled(captureController.state.isRecording
                    || captureController.state.isBusy)

            if let message = captureController.nameValidationMessage {
                Label(message, systemImage: "exclamationmark.circle")
                    .font(.footnote)
                    .foregroundStyle(.orange)
            }

            if captureController.state.isRecording {
                Button("停止并保存", role: .destructive) {
                    captureController.stopRecording()
                }
                .frame(maxWidth: .infinity)
            } else {
                Button("开始记录") {
                    captureController.startRecording()
                }
                .disabled(!captureController.canStart)
                .frame(maxWidth: .infinity)
            }

            captureStateContent
        }
    }

    @ViewBuilder
    private var captureStateContent: some View {
        switch captureController.state {
        case .idle:
            Text("收到持续合法帧后可开始；同名记录不会被覆盖。")
                .font(.footnote)
                .foregroundStyle(.secondary)
        case .preflighting:
            Label("正在创建 raw、CSV 与 metadata", systemImage: "hourglass")
                .font(.footnote)
        case .recording:
            let snapshot = captureController.writerSnapshot
            LabeledContent("状态") {
                Label("记录中", systemImage: "record.circle.fill")
                    .foregroundStyle(.red)
            }
            if let startedAt = captureController.recordingStartedAt {
                TimelineView(.periodic(from: .now, by: 1)) { context in
                    LabeledContent(
                        "时长",
                        value: durationText(
                            from: startedAt,
                            to: context.date
                        )
                    )
                }
            }
            LabeledContent("raw chunks", value: "\(snapshot.rawChunkCount)")
            LabeledContent("CSV 样本", value: "\(snapshot.csvRows)")
            LabeledContent("raw bytes", value: "\(snapshot.rawFileBytes)")
            LabeledContent("缺失帧", value: "\(snapshot.missingFrames)")
            if let lastFlush = snapshot.lastFlushUTC {
                LabeledContent("最近落盘") {
                    Text(lastFlush, style: .time)
                }
            }
        case let .stopping(reason):
            Label(
                "正在收尾（\(stopReasonText(reason))）",
                systemImage: "hourglass"
            )
            .font(.footnote)
        case let .finalized(summary):
            let shareableURLs = existingSessionFiles(for: summary)
            Label(
                summary.complete
                    ? "已保存（\(shareableURLs.count) 个文件）"
                    : "已保存（未完整结束）",
                systemImage: summary.complete
                    ? "checkmark.circle.fill"
                    : "exclamationmark.triangle.fill"
            )
            .foregroundStyle(summary.complete ? .green : .orange)
            LabeledContent("样本", value: "\(summary.writer.acceptedSamples)")
            LabeledContent(
                "时长",
                value: durationText(
                    from: summary.startedUTC,
                    to: summary.endedUTC
                )
            )
            LabeledContent("停止原因", value: stopReasonText(summary.stopReason))
            Text(summary.directoryURL.lastPathComponent)
                .font(.caption.monospaced())
                .foregroundStyle(.secondary)
                .textSelection(.enabled)
            ShareLink(items: shareableURLs) {
                Label("导出本次记录", systemImage: "square.and.arrow.up")
            }
            .disabled(shareableURLs.isEmpty)
            Text("文件 App：我的 iPhone → PPGCollector → PPGCollector")
                .font(.caption)
                .foregroundStyle(.secondary)
        case let .failed(message):
            Label(message, systemImage: "xmark.circle.fill")
                .font(.footnote)
                .foregroundStyle(.red)
            Button("清除错误") {
                captureController.clearError()
            }
            .font(.footnote)
            if let summary = captureController.lastSummary {
                Text("可恢复目录：\(summary.directoryURL.lastPathComponent)")
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
            }
        }
    }

    private var emptyStateDescription: String {
        switch bluetoothService.availability {
        case .poweredOn:
            bluetoothService.isScanning
                ? "正在扫描名称以 CUP 开头的蓝牙设备。"
                : "点击“重新扫描”查找名称以 CUP 开头的设备。"
        case .unauthorized:
            "请前往系统设置允许 PPGCollector 使用蓝牙。"
        case .poweredOff:
            "请先打开 iPhone 蓝牙。"
        case .unsupported:
            "当前设备无法使用蓝牙低功耗功能。"
        case .unknown, .resetting:
            "正在等待系统蓝牙状态。"
        }
    }

    private var scanStatusText: String {
        if bluetoothService.isScanning {
            return "正在扫描 CUP 设备"
        }
        switch bluetoothService.connectionPhase {
        case .idle:
            return "等待扫描或连接"
        case .connecting:
            return "正在连接"
        case .discoveringServices:
            return "正在发现服务"
        case .discoveringCharacteristics:
            return "正在发现特征"
        case .subscribing:
            return "正在订阅通知"
        case .subscribed:
            return "通知已订阅，等待 CUP 数据"
        case .receiving:
            return "正在接收 CUP 通知"
        case .disconnecting:
            return "正在断开"
        case .failed:
            return "连接失败"
        }
    }

    private var statusColor: Color {
        bluetoothService.availability == .poweredOn ? .green : .orange
    }

    private var shouldShowStreamingDiagnostics: Bool {
        switch bluetoothService.connectionPhase {
        case .subscribed, .receiving:
            true
        case .idle,
             .connecting,
             .discoveringServices,
             .discoveringCharacteristics,
             .subscribing,
             .disconnecting,
             .failed:
            false
        }
    }

    private var shouldShowCaptureSection: Bool {
        if shouldShowStreamingDiagnostics {
            return true
        }
        switch captureController.state {
        case .idle:
            return false
        case .preflighting,
             .recording,
             .stopping,
             .finalized,
             .failed:
            return true
        }
    }

    private var shouldShowLiveWorkspace: Bool {
        shouldShowStreamingDiagnostics
    }

    private var notificationStatusText: String {
        switch bluetoothService.connectionPhase {
        case .subscribed:
            "已订阅，等待数据"
        case .receiving:
            "正在接收"
        default:
            "未就绪"
        }
    }

    private var gattStatusText: String {
        switch bluetoothService.connectionPhase {
        case .connecting:
            "正在建立 BLE 连接。"
        case .discoveringServices:
            "正在查找 CUP NUS service。"
        case .discoveringCharacteristics:
            "正在验证 NUS RX/TX characteristics 与 properties。"
        case .subscribing:
            "已找到 TX notify，正在启用通知。"
        case .subscribed:
            "TX 通知已启用。当前 passive profile 不发送 HELLO/START/STOP。"
        case .receiving:
            "BLE 通知正在进入 CUP stream decoder。"
        case .disconnecting:
            "正在安全断开连接。"
        case .idle:
            "尚未连接。"
        case .failed:
            "GATT 建链失败，请查看顶部错误。"
        }
    }

    private func sequenceEventText(_ event: CUPSequenceEvent) -> String {
        switch event {
        case .first:
            "首帧"
        case .continuous:
            "连续"
        case let .gap(missingFrames):
            "缺失 \(missingFrames) 帧"
        case .duplicate:
            "重复"
        case .outOfOrder:
            "乱序/旧帧"
        }
    }

    private func stopReasonText(_ reason: CaptureStopReason) -> String {
        switch reason {
        case .user:
            "用户停止"
        case .viewExit:
            "离开采集页"
        case .sceneBackground:
            "进入后台"
        case .deviceDisconnect:
            "设备断开"
        case .dataTimeout:
            "数据超时"
        case .crashRecovery:
            "崩溃恢复副本"
        case .writeError:
            "写盘错误"
        case .protocolError:
            "协议错误"
        case .resourcePressure:
            "资源不足"
        case .unknown:
            "未知"
        }
    }

    private func durationText(from start: Date, to end: Date) -> String {
        let seconds = max(0, Int(end.timeIntervalSince(start)))
        return String(format: "%02d:%02d", seconds / 60, seconds % 60)
    }

    private func existingSessionFiles(
        for summary: CaptureSessionSummary
    ) -> [URL] {
        CaptureSessionRepository.expectedFileURLs(in: summary.directoryURL)
            .filter { FileManager.default.fileExists(atPath: $0.path) }
    }
}

private struct LiveMetricCard: View {
    let title: String
    let value: String
    let unit: String
    let systemImage: String
    let tint: Color
    let reason: String?
    let provisional: Bool
    let identifier: String

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            Label(title, systemImage: systemImage)
                .font(.caption.weight(.semibold))
                .foregroundStyle(tint)

            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(value)
                    .font(.title3.monospacedDigit().weight(.semibold))
                if !unit.isEmpty {
                    Text(unit)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }

            Text(reason ?? (provisional ? "暂定评分，待 CPE/阈值准入" : "有效"))
                .font(.caption2)
                .foregroundStyle(
                    reason == nil
                        ? (provisional ? .orange : .green)
                        : .secondary
                )
                .lineLimit(2)
        }
        .frame(maxWidth: .infinity, minHeight: 78, alignment: .topLeading)
        .padding(10)
        .background(
            .quaternary.opacity(0.45),
            in: RoundedRectangle(cornerRadius: 10)
        )
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier(identifier)
    }
}

private struct DeviceRow: View {
    let device: DiscoveredBLEDevice
    let connectionPhase: BLEConnectionPhase
    let connect: () -> Void
    let disconnect: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: signalImage)
                .foregroundStyle(signalColor)
                .frame(width: 28)

            VStack(alignment: .leading, spacing: 4) {
                Text(device.name)
                    .font(.headline)
                HStack(spacing: 8) {
                    Text(device.rssi.map { "\($0) dBm" } ?? "RSSI —")
                    Text(device.id.uuidString.prefix(8))
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }

            Spacer()

            if isBusy {
                ProgressView()
                    .frame(width: 64)
            } else if isConnected {
                Button("断开", role: .destructive, action: disconnect)
                    .buttonStyle(.bordered)
            } else {
                Button("连接", action: connect)
                    .buttonStyle(.borderedProminent)
                    .disabled(!device.isConnectable || anotherDeviceIsActive)
            }
        }
        .padding(.vertical, 4)
    }

    private var isConnected: Bool {
        connectionPhase.isReadyToDisconnect
            && connectionPhase.deviceID == device.id
    }

    private var isBusy: Bool {
        connectionPhase.isBusy && connectionPhase.deviceID == device.id
    }

    private var anotherDeviceIsActive: Bool {
        guard let activeID = connectionPhase.deviceID else {
            return false
        }
        return activeID != device.id
    }

    private var signalImage: String {
        guard let rssi = device.rssi else {
            return "antenna.radiowaves.left.and.right.slash"
        }
        if rssi >= -60 {
            return "wifi"
        }
        if rssi >= -75 {
            return "wifi.exclamationmark"
        }
        return "antenna.radiowaves.left.and.right"
    }

    private var signalColor: Color {
        guard let rssi = device.rssi else {
            return .secondary
        }
        if rssi >= -60 {
            return .green
        }
        if rssi >= -75 {
            return .orange
        }
        return .red
    }
}
