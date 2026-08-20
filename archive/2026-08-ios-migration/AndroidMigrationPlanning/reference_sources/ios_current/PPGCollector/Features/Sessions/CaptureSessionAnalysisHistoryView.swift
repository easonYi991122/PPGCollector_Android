import SwiftUI

nonisolated enum CaptureSessionAnalysisHistoryFilter {
    static func runtimeProfiles(
        in artifacts: [CaptureSessionAnalysisArtifact]
    ) -> [String] {
        Array(Set(artifacts.map(\.report.runtimeProfile))).sorted()
    }

    static func filter(
        _ artifacts: [CaptureSessionAnalysisArtifact],
        runtimeProfile: String?
    ) -> [CaptureSessionAnalysisArtifact] {
        guard let runtimeProfile else {
            return artifacts
        }
        return artifacts.filter {
            $0.report.runtimeProfile == runtimeProfile
        }
    }
}

struct CaptureSessionAnalysisHistoryView: View {
    let artifacts: [CaptureSessionAnalysisArtifact]
    @State private var selectedRuntimeProfile: String?

    private var runtimeProfiles: [String] {
        CaptureSessionAnalysisHistoryFilter.runtimeProfiles(in: artifacts)
    }

    private var filteredArtifacts: [CaptureSessionAnalysisArtifact] {
        CaptureSessionAnalysisHistoryFilter.filter(
            artifacts,
            runtimeProfile: selectedRuntimeProfile
        )
    }

    var body: some View {
        List {
            if runtimeProfiles.count > 1 {
                Section("运行时 profile") {
                    Picker("运行时 profile", selection: $selectedRuntimeProfile) {
                        Text("全部（\(artifacts.count)）")
                            .tag(String?.none)
                        ForEach(runtimeProfiles, id: \.self) { profile in
                            Text(profile).tag(Optional(profile))
                        }
                    }
                    .pickerStyle(.menu)
                }
            }

            if artifacts.count > 1 {
                Section {
                    NavigationLink {
                        CaptureSessionAnalysisComparisonView(
                            artifacts: artifacts
                        )
                    } label: {
                        Label(
                            "比较两个版本化结果",
                            systemImage: "arrow.left.arrow.right"
                        )
                    }
                } footer: {
                    Text("仅比较已保存的版本和汇总；来源 raw 不同时会明确提示。")
                }
            }

            Section("结果（\(filteredArtifacts.count)）") {
                ForEach(filteredArtifacts, id: \.url) { artifact in
                    NavigationLink {
                        CaptureSessionAnalysisDetailView(artifact: artifact)
                    } label: {
                        VStack(alignment: .leading, spacing: 5) {
                            Text(artifact.url.lastPathComponent)
                                .font(.headline)
                            Text(artifact.report.createdUTC, format: .dateTime
                                .year()
                                .month()
                                .day()
                                .hour()
                                .minute()
                                .second())
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Text("\(artifact.report.runtimeProfile) · \(artifact.report.summary.analysisWindowCount) 窗口 · \(artifact.report.summary.validHeartRateWindowCount) 个有效 HR")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .navigationTitle("分析历史（\(artifacts.count)）")
        .navigationBarTitleDisplayMode(.inline)
    }
}
