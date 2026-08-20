import SwiftUI

struct SavedSessionDetailView: View {
    let session: StoredCaptureSession
    var onRecoveryCreated: () -> Void = {}
    @EnvironmentObject private var analysisTaskStore: CaptureSessionAnalysisTaskStore

    @State private var inspection: CaptureSessionInspection?
    @State private var isInspecting = true
    @State private var isShowingRecoveryPrompt = false
    @State private var recoveryName = ""
    @State private var isRecovering = false
    @State private var recoveryNotice: String?
    @State private var recoveryError: String?
    @State private var recoveredFileURLs: [URL] = []
    @State private var analysisArtifacts: [CaptureSessionAnalysisArtifact] = []

    var body: some View {
        List {
            overviewSection
            versionAndDeviceSection
            analysisSection

            if isInspecting {
                Section {
                    HStack {
                        ProgressView()
                        Text("正在从 raw 重新解码并核对 CSV…")
                    }
                }
            } else if let inspection {
                integritySection(inspection)
                replaySection(inspection)

            if let samples = inspection.replay?.samples,
                   !samples.isEmpty {
                Section("raw 重放波形") {
                        CUPDualWaveformPreview(
                            samples: samples,
                            interactionMode: .interactiveReplay
                        )
                    }
                }

                Section("高级诊断") {
                    NavigationLink {
                        PPGExpertDiagnosticsView(session: session)
                    } label: {
                        Label(
                            "打开 V2 诊断工作台",
                            systemImage: "waveform.badge.magnifyingglass"
                        )
                    }
                }

                if shouldOfferRecovery(for: inspection) {
                    recoverySection(inspection)
                }
            }

            exportSection
        }
        .listStyle(.insetGrouped)
        .navigationTitle(session.baseName)
        .navigationBarTitleDisplayMode(.inline)
        .task(id: session.id) {
            isInspecting = true
            do {
                let result =
                    try await CaptureSessionInspectionService
                        .inspectCancellable(session)
                try Task.checkCancellation()
                inspection = result
                isInspecting = false
            } catch is CancellationError {
                return
            } catch {
                inspection = CaptureSessionInspection(
                    replay: nil,
                    csv: nil,
                    findings: [
                        CaptureInspectionFinding(
                            id: "inspection-failed",
                            severity: .error,
                            message:
                                "检查未完成：\(error.localizedDescription)"
                        )
                    ]
                )
                isInspecting = false
            }
        }
        .task(id: session.id) {
            analysisArtifacts = await Task.detached {
                CaptureSessionAnalysisService.listArtifacts(for: session)
            }.value
        }
        .onChange(of: analysisSnapshot?.status) { _, status in
            guard case .completed = status else {
                return
            }
            reloadAnalysisArtifacts()
        }
        .alert("另存恢复副本", isPresented: $isShowingRecoveryPrompt) {
            TextField("恢复副本名称", text: $recoveryName)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button("取消", role: .cancel) {}
            Button("创建副本") {
                createRecoveryCopy()
            }
        } message: {
            Text(
                "只复制 raw/CSV 的最后完整边界并生成新的 incomplete metadata；原会话不会被修改。"
            )
        }
    }

    private var overviewSection: some View {
        Section("会话") {
            LabeledContent("目录", value: session.baseName)
            LabeledContent("metadata 样本", value: optionalCount(session.sampleCount))
            LabeledContent("metadata chunks", value: optionalCount(session.rawChunkCount))
            LabeledContent(
                "文件大小",
                value: ByteCountFormatter.string(
                    fromByteCount: session.totalBytes,
                    countStyle: .file
                )
            )
            LabeledContent("停止原因", value: session.stopReason ?? "—")
            if let sessionID = session.sessionID {
                LabeledContent("Session ID", value: sessionID)
            }
            if let source = session.recoverySourceBaseName {
                LabeledContent("恢复来源", value: source)
            }
            LabeledContent("更新时间") {
                Text(session.modifiedAt, format: .dateTime
                    .year()
                    .month()
                    .day()
                    .hour()
                    .minute()
                    .second())
            }
        }
    }

    private var versionAndDeviceSection: some View {
        Section {
            LabeledContent("App", value: metadataValue(session.softVersion))
            LabeledContent(
                "算法",
                value: metadataValue(session.algorithmVersion)
            )
            LabeledContent(
                "预处理",
                value: metadataValue(session.preprocessProfile)
            )
            LabeledContent(
                "协议",
                value: metadataValue(session.protocolProfile)
            )
            LabeledContent(
                "传输 profile",
                value: metadataValue(session.transportProfile)
            )
            LabeledContent("设备", value: metadataValue(session.deviceName))
            LabeledContent("开始时间") {
                metadataDate(session.startedUTC)
            }
            LabeledContent("结束时间") {
                metadataDate(session.endedUTC)
            }
        } header: {
            Text("版本与设备")
        } footer: {
            Text("这些字段来自会话创建时固化的 metadata；重放和恢复不会改写原始版本信息。")
        }
    }

    private func integritySection(
        _ inspection: CaptureSessionInspection
    ) -> some View {
        Section {
            Label(
                inspection.isVerifiedConsistent
                    ? "raw、CSV 与 metadata 计数一致"
                    : "发现需要检查的项目",
                systemImage: inspection.isVerifiedConsistent
                    ? "checkmark.seal.fill"
                    : "exclamationmark.triangle.fill"
            )
            .foregroundStyle(
                inspection.isVerifiedConsistent ? .green : .orange
            )

            ForEach(inspection.findings) { finding in
                Label(
                    finding.message,
                    systemImage: finding.severity == .error
                        ? "xmark.circle.fill"
                        : "exclamationmark.triangle.fill"
                )
                .font(.footnote)
                .foregroundStyle(
                    finding.severity == .error ? .red : .orange
                )
            }

            if inspection.hasRecoverableTail {
                Text("当前只分析可恢复边界，不会改写原始文件。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text("完整性复核")
        }
    }

    @ViewBuilder
    private func replaySection(
        _ inspection: CaptureSessionInspection
    ) -> some View {
        if let replay = inspection.replay {
            Section("raw 重放") {
                LabeledContent(
                    "读取方式",
                    value: replay.readMode == .streaming
                        ? "逐记录流式"
                        : "内存数据"
                )
                LabeledContent("完整记录", value: "\(replay.rawRecordCount)")
                LabeledContent("解码帧", value: "\(replay.decodedFrames)")
                LabeledContent("接受样本", value: "\(replay.acceptedSamples)")
                LabeledContent(
                    "波形样本缓存",
                    value: ByteCountFormatter.string(
                        fromByteCount: Int64(replay.retainedSampleBytes),
                        countStyle: .memory
                    )
                )
                LabeledContent(
                    "raw 峰值记录缓冲",
                    value: ByteCountFormatter.string(
                        fromByteCount:
                            Int64(replay.peakRawRecordBufferBytes),
                        countStyle: .memory
                    )
                )
                LabeledContent("缺失帧", value: "\(replay.missingFrames)")
                LabeledContent("重复帧", value: "\(replay.duplicateFrames)")
                LabeledContent("乱序帧", value: "\(replay.outOfOrderFrames)")
                if let duration = replay.hostDurationSeconds {
                    LabeledContent(
                        "host 帧跨度",
                        value: String(format: "%.3f s", duration)
                    )
                }
                LabeledContent(
                    "notification 长度",
                    value: chunkLengthSummary(replay.chunkLengthCounts)
                )
                if replay.trailingRawBytes > 0 {
                    LabeledContent(
                        "尾部未确认字节",
                        value: "\(replay.trailingRawBytes)"
                    )
                }
            }
        }

        if let csv = inspection.csv {
            Section("CSV 扫描") {
                LabeledContent(
                    "完整数据行",
                    value: "\(csv.completeDataRowCount)"
                )
                LabeledContent(
                    "v1 header",
                    value: csv.hasExpectedHeader ? "匹配" : "不匹配"
                )
                LabeledContent(
                    "完整尾行",
                    value: csv.hasTruncatedFinalLine ? "否" : "是"
                )
            }
        }
    }

    private var exportSection: some View {
        Section {
            ShareLink(items: session.shareableFileURLs) {
                Label(
                    "导出 \(session.shareableFileURLs.count) 个原始文件",
                    systemImage: "square.and.arrow.up"
                )
            }
            .disabled(session.shareableFileURLs.isEmpty)
        } footer: {
            Text("复核和重放均为只读操作，不会改变采集文件。")
        }
    }

    @ViewBuilder
    private var analysisSection: some View {
        let snapshot = analysisSnapshot
        let isAnalyzing = snapshot?.isRunning == true
        Section {
            Button {
                startAnalysis()
            } label: {
                if isAnalyzing {
                    HStack {
                        ProgressView(value: snapshot?.progress?.fractionCompleted)
                        Text(analysisProgressText)
                    }
                } else {
                    Label(
                        "生成离线分析",
                        systemImage: "waveform.badge.magnifyingglass"
                    )
                }
            }
            .disabled(isAnalyzing)

            if isAnalyzing {
                Button("取消分析", role: .cancel) {
                    analysisTaskStore.cancel(session)
                }
            }

            if isAnalyzing, let analysisProgress = snapshot?.progress {
                LabeledContent(
                    "已扫描 raw",
                    value: ByteCountFormatter.string(
                        fromByteCount: Int64(
                            analysisProgress.processedRawByteCount
                        ),
                        countStyle: .file
                    ) + " / " + ByteCountFormatter.string(
                        fromByteCount: Int64(
                            analysisProgress.totalRawByteCount
                        ),
                        countStyle: .file
                    )
                )
                LabeledContent(
                    "已处理",
                    value: "(analysisProgress.processedRecordCount) 条记录 · (analysisProgress.acceptedSampleCount) 样本 · (analysisProgress.completedWindowCount) 窗口"
                )
            }

            if let latest = analysisArtifacts.first {
                LabeledContent("最近结果") {
                    Text(latest.report.createdUTC, format: .dateTime
                        .year()
                        .month()
                        .day()
                        .hour()
                        .minute()
                        .second())
                }
                LabeledContent("运行时 profile", value: latest.report.runtimeProfile)
                LabeledContent(
                    "分析窗口",
                    value: "\(latest.report.summary.analysisWindowCount)"
                )
                LabeledContent(
                    "有效 HR 窗口",
                    value: "\(latest.report.summary.validHeartRateWindowCount)"
                )
                if let bpm = latest.report.summary.heartRateBPMMean {
                    LabeledContent(
                        "HR 平均值",
                        value: String(format: "%.1f bpm", bpm)
                    )
                }
                LabeledContent(
                    "SQI",
                    value: latest.report.signalQualityIsApproved
                        ? "已准入"
                        : "暂定，未准入"
                )

                NavigationLink {
                    CaptureSessionAnalysisDetailView(artifact: latest)
                } label: {
                    Label("查看逐窗结果", systemImage: "list.bullet.rectangle")
                }

                if analysisArtifacts.count > 1 {
                    NavigationLink {
                        CaptureSessionAnalysisHistoryView(
                            artifacts: analysisArtifacts
                        )
                    } label: {
                        Label(
                            "查看 \(analysisArtifacts.count) 个版本化分析结果",
                            systemImage: "clock.arrow.circlepath"
                        )
                    }
                }
                ShareLink(items: analysisArtifacts.map(\.url)) {
                    Label(
                        "导出 \(analysisArtifacts.count) 个分析文件",
                        systemImage: "square.and.arrow.up"
                    )
                }
            } else if !isAnalyzing {
                Text("尚未生成离线分析结果。")
                    .foregroundStyle(.secondary)
            }

            if case let .completed(url) = snapshot?.status {
                Label("已生成 \(url.lastPathComponent)。", systemImage: "checkmark.circle.fill")
                    .font(.footnote)
                    .foregroundStyle(.green)
            }
            if case let .failed(message) = snapshot?.status {
                Label("分析失败：\(message)", systemImage: "xmark.circle.fill")
                .font(.footnote)
                .foregroundStyle(.red)
            }
            if case .cancelled = snapshot?.status {
                Label("已取消，未写入未完成的分析结果。", systemImage: "xmark.circle.fill")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text("离线分析")
        } footer: {
            Text("从原始 raw 流式重放，生成带 SHA-256 与算法版本的独立 JSON；不会改写 raw、CSV 或 metadata。SQI 仍为暂定估计，不能用于临床判断。")
        }
    }

    private func recoverySection(
        _ inspection: CaptureSessionInspection
    ) -> some View {
        Section {
            Text(
                recoveryDescription(inspection)
            )
            .font(.footnote)
            .foregroundStyle(.secondary)

            Button {
                recoveryName =
                    CaptureSessionRecoveryService.suggestedBaseName(
                        for: session
                    )
                recoveryError = nil
                isShowingRecoveryPrompt = true
            } label: {
                if isRecovering {
                    HStack {
                        ProgressView()
                        Text("正在创建恢复副本…")
                    }
                } else {
                    Label(
                        "另存恢复副本",
                        systemImage: "lifepreserver"
                    )
                }
            }
            .disabled(isRecovering)

            if let recoveryNotice {
                Label(
                    recoveryNotice,
                    systemImage: "checkmark.circle.fill"
                )
                .font(.footnote)
                .foregroundStyle(.green)
            }

            if let recoveryError {
                Label(
                    recoveryError,
                    systemImage: "xmark.circle.fill"
                )
                .font(.footnote)
                .foregroundStyle(.red)
            }

            if !recoveredFileURLs.isEmpty {
                ShareLink(items: recoveredFileURLs) {
                    Label(
                        "导出新恢复副本",
                        systemImage: "square.and.arrow.up"
                    )
                }
            }
        } header: {
            Text("非破坏性恢复")
        } footer: {
            Text(
                "恢复副本始终标记为 incomplete / crashRecovery，并在 metadata 中保存源文件 SHA-256。"
            )
        }
    }

    private func shouldOfferRecovery(
        for inspection: CaptureSessionInspection
    ) -> Bool {
        let requiresAttention = !session.isVerifiedComplete
            || !inspection.isVerifiedConsistent
        return requiresAttention
            && inspection.replay != nil
            && inspection.csv?.hasExpectedHeader == true
    }

    private func recoveryDescription(
        _ inspection: CaptureSessionInspection
    ) -> String {
        let rawTail = inspection.replay?.trailingRawBytes ?? 0
        let csvTail = inspection.csv?.trailingByteCount ?? 0
        if rawTail > 0 || csvTail > 0 {
            return "可安全忽略 raw 尾部 \(rawTail) bytes、CSV 尾部 \(csvTail) bytes，并将完整前缀另存为新会话。"
        }
        return "文件边界可读取，但 metadata 或计数仍需检查；可另存一份带恢复来源的新会话。"
    }

    private func createRecoveryCopy() {
        let source = session
        let requestedName = recoveryName
        isRecovering = true
        recoveryNotice = nil
        recoveryError = nil
        recoveredFileURLs = []

        Task {
            do {
                let result = try await Task.detached {
                    try CaptureSessionRecoveryService.recover(
                        source,
                        as: requestedName
                    )
                }.value
                recoveredFileURLs = result.shareableFileURLs
                recoveryNotice =
                    "已创建“\(result.baseName)”；舍弃 raw 尾部 \(result.rawDiscardedTailBytes) bytes、CSV 尾部 \(result.csvDiscardedTailBytes) bytes。"
                onRecoveryCreated()
            } catch {
                recoveryError = error.localizedDescription
            }
            isRecovering = false
        }
    }

    private func startAnalysis() {
        analysisTaskStore.start(session)
    }

    private var analysisProgressText: String {
        guard let progress = analysisSnapshot?.progress else {
            return "正在从 raw 生成离线分析…"
        }
        return "正在分析 \(Int(progress.fractionCompleted * 100))%…"
    }

    private var analysisSnapshot: CaptureSessionAnalysisTaskStore.Snapshot? {
        analysisTaskStore.snapshot(for: session)
    }

    private func reloadAnalysisArtifacts() {
        let source = session
        Task {
            analysisArtifacts = await Task.detached {
                CaptureSessionAnalysisService.listArtifacts(for: source)
            }.value
        }
    }

    private func optionalCount(_ value: Int?) -> String {
        value.map(String.init) ?? "—"
    }

    private func metadataValue(_ value: String?) -> String {
        guard let value, !value.isEmpty else {
            return "—"
        }
        return value
    }

    @ViewBuilder
    private func metadataDate(_ value: Date?) -> some View {
        if let value {
            Text(value, format: .dateTime
                .year()
                .month()
                .day()
                .hour()
                .minute()
                .second())
        } else {
            Text("—")
        }
    }

    private func chunkLengthSummary(_ counts: [Int: Int]) -> String {
        counts.keys.sorted().map { length in
            "\(length)B×\(counts[length, default: 0])"
        }
        .joined(separator: "，")
    }
}
