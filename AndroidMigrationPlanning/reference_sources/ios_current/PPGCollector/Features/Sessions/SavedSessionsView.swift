import SwiftUI

struct SavedSessionsView: View {
    @ObservedObject var store: CaptureSessionStore
    @EnvironmentObject private var analysisTaskStore: CaptureSessionAnalysisTaskStore

    var body: some View {
        Group {
            if store.sessions.isEmpty, store.lastError == nil {
                ContentUnavailableView {
                    Label("尚无本地记录", systemImage: "externaldrive")
                } description: {
                    Text("完成一次记录后，可在这里核验并导出 raw、CSV 和 metadata。")
                }
            } else {
                sessionList
            }
        }
        .navigationTitle("已保存记录")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    store.refresh()
                } label: {
                    Label("刷新", systemImage: "arrow.clockwise")
                }
            }
        }
        .task {
            store.refresh()
        }
    }

    private var sessionList: some View {
        List {
            if !analysisTaskStore.runningSnapshots.isEmpty {
                Section("离线分析任务") {
                    ForEach(analysisTaskStore.runningSnapshots, id: \.sessionID) { task in
                        VStack(alignment: .leading, spacing: 5) {
                            Text(task.sessionBaseName)
                                .font(.headline)
                            ProgressView(value: task.progress?.fractionCompleted)
                            Text(taskProgressText(task))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Button("取消分析", role: .cancel) {
                                analysisTaskStore.cancel(
                                    sessionID: task.sessionID
                                )
                            }
                            .font(.caption)
                        }
                    }
                }
            }

            if store.isAuditingRecovery {
                Section {
                    HStack {
                        ProgressView()
                        Text("正在检查可恢复会话…")
                    }
                }
            } else if !store.recoveryCandidates.isEmpty {
                Section {
                    Label(
                        "发现 \(store.recoveryCandidates.count) 个可另存恢复副本的会话。",
                        systemImage: "lifepreserver.fill"
                    )
                    .foregroundStyle(.orange)
                }
            }

            Section {
                Label(
                    "文件 App：浏览 → 我的 iPhone → PPGCollector → PPGCollector",
                    systemImage: "folder"
                )
                .font(.footnote)
                .foregroundStyle(.secondary)
            } header: {
                Text("存储位置")
            } footer: {
                Text("若“文件”App 尚未刷新，可直接使用每条记录下方的“导出文件”。")
            }

            if let error = store.lastError {
                Section {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.red)
                }
            }

            Section("本机记录（\(store.sessions.count)）") {
                ForEach(store.sessions) { session in
                    VStack(alignment: .leading, spacing: 9) {
                        NavigationLink {
                            SavedSessionDetailView(
                                session: session,
                                onRecoveryCreated: store.refresh
                            )
                        } label: {
                            SavedSessionSummary(session: session)
                        }

                        ShareLink(items: session.shareableFileURLs) {
                            Label(
                                "导出 \(session.shareableFileURLs.count) 个文件",
                                systemImage: "square.and.arrow.up"
                            )
                        }
                        .buttonStyle(.bordered)
                        .disabled(session.shareableFileURLs.isEmpty)
                    }
                    .padding(.vertical, 5)
                }
            }
        }
        .listStyle(.insetGrouped)
        .refreshable {
            store.refresh()
        }
    }

    private func taskProgressText(
        _ task: CaptureSessionAnalysisTaskStore.Snapshot
    ) -> String {
        guard let progress = task.progress else {
            return "正在准备离线分析…"
        }
        return "\(Int(progress.fractionCompleted * 100))% · \(progress.processedRecordCount) 条记录 · \(progress.completedWindowCount) 窗口"
    }
}

private struct SavedSessionSummary: View {
    let session: StoredCaptureSession

    var body: some View {
        VStack(alignment: .leading, spacing: 7) {
            HStack(alignment: .firstTextBaseline) {
                Text(session.baseName)
                    .font(.headline)
                Spacer()
                statusLabel
            }

            HStack(spacing: 12) {
                Label(
                    "\(session.sampleCount ?? 0) 样本",
                    systemImage: "waveform.path.ecg"
                )
                Label(
                    ByteCountFormatter.string(
                        fromByteCount: session.totalBytes,
                        countStyle: .file
                    ),
                    systemImage: "externaldrive"
                )
            }
            .font(.caption)
            .foregroundStyle(.secondary)

            Text(session.modifiedAt, format: .dateTime
                .year()
                .month()
                .day()
                .hour()
                .minute())
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private var statusLabel: some View {
        if session.isRecoveryCopy {
            Label("恢复副本", systemImage: "lifepreserver.fill")
                .foregroundStyle(.orange)
        } else if session.isVerifiedComplete {
            Label("完整", systemImage: "checkmark.circle.fill")
                .foregroundStyle(.green)
        } else {
            Label("需检查", systemImage: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
        }
    }
}
