import SwiftUI

struct CaptureSessionAnalysisComparisonView: View {
    let artifacts: [CaptureSessionAnalysisArtifact]
    @State private var baselineURL: URL
    @State private var candidateURL: URL

    init(artifacts: [CaptureSessionAnalysisArtifact]) {
        precondition(artifacts.count >= 2)
        self.artifacts = artifacts
        _baselineURL = State(initialValue: artifacts[0].url)
        _candidateURL = State(initialValue: artifacts[1].url)
    }

    var body: some View {
        List {
            Section("比较对象") {
                Picker("基准结果", selection: $baselineURL) {
                    ForEach(artifacts, id: \.url) { artifact in
                        Text(artifactLabel(artifact)).tag(artifact.url)
                    }
                }
                Picker("对比结果", selection: $candidateURL) {
                    ForEach(artifacts, id: \.url) { artifact in
                        Text(artifactLabel(artifact)).tag(artifact.url)
                    }
                }
            }

            if let baseline, let candidate {
                sourceSection(baseline: baseline, candidate: candidate)
                versionSection(baseline: baseline, candidate: candidate)
                summarySection(baseline: baseline, candidate: candidate)
            }
        }
        .navigationTitle("分析结果对比")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var baseline: CaptureSessionAnalysisArtifact? {
        artifacts.first { $0.url == baselineURL }
    }

    private var candidate: CaptureSessionAnalysisArtifact? {
        artifacts.first { $0.url == candidateURL }
    }

    private func sourceSection(
        baseline: CaptureSessionAnalysisArtifact,
        candidate: CaptureSessionAnalysisArtifact
    ) -> some View {
        Section("来源一致性") {
            let sameRaw = baseline.report.sourceRawSHA256
                == candidate.report.sourceRawSHA256
            Label(
                sameRaw
                    ? "两个结果来自相同 raw"
                    : "两个结果的 raw SHA-256 不同，不能作为同源对比",
                systemImage: sameRaw
                    ? "checkmark.seal.fill"
                    : "exclamationmark.triangle.fill"
            )
            .foregroundStyle(sameRaw ? .green : .orange)
            LabeledContent("基准 raw", value: baseline.report.sourceRawSHA256)
            LabeledContent("对比 raw", value: candidate.report.sourceRawSHA256)
        }
    }

    private func versionSection(
        baseline: CaptureSessionAnalysisArtifact,
        candidate: CaptureSessionAnalysisArtifact
    ) -> some View {
        Section("版本") {
            comparisonRow(
                "运行时 profile",
                baseline.report.runtimeProfile,
                candidate.report.runtimeProfile
            )
            comparisonRow(
                "预处理 profile",
                baseline.report.preprocessProfile,
                candidate.report.preprocessProfile
            )
            comparisonRow(
                "HR 算法",
                baseline.report.heartRateAlgorithmVersion,
                candidate.report.heartRateAlgorithmVersion
            )
            comparisonRow(
                "SQI 算法",
                baseline.report.signalQualityAlgorithmVersion,
                candidate.report.signalQualityAlgorithmVersion
            )
        }
    }

    private func summarySection(
        baseline: CaptureSessionAnalysisArtifact,
        candidate: CaptureSessionAnalysisArtifact
    ) -> some View {
        Section("摘要差异") {
            comparisonRow(
                "分析窗口",
                "\(baseline.report.summary.analysisWindowCount)",
                "\(candidate.report.summary.analysisWindowCount)"
            )
            comparisonRow(
                "有效 HR 窗口",
                "\(baseline.report.summary.validHeartRateWindowCount)",
                "\(candidate.report.summary.validHeartRateWindowCount)"
            )
            comparisonRow(
                "暂定 SQI 窗口",
                "\(baseline.report.summary.provisionalSQIWindowCount)",
                "\(candidate.report.summary.provisionalSQIWindowCount)"
            )
            comparisonRow(
                "HR 平均值",
                bpm(baseline.report.summary.heartRateBPMMean),
                bpm(candidate.report.summary.heartRateBPMMean)
            )
            comparisonRow(
                "HR 最低值",
                bpm(baseline.report.summary.heartRateBPMMinimum),
                bpm(candidate.report.summary.heartRateBPMMinimum)
            )
            comparisonRow(
                "HR 最高值",
                bpm(baseline.report.summary.heartRateBPMMaximum),
                bpm(candidate.report.summary.heartRateBPMMaximum)
            )
        }
    }

    private func comparisonRow(
        _ title: String,
        _ baseline: String,
        _ candidate: String
    ) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Text("基准：\(baseline)")
            Text("对比：\(candidate)")
        }
        .font(.footnote)
    }

    private func artifactLabel(_ artifact: CaptureSessionAnalysisArtifact) -> String {
        "\(artifact.report.runtimeProfile) · \(artifact.url.lastPathComponent)"
    }

    private func bpm(_ value: Double?) -> String {
        value.map { String(format: "%.1f bpm", $0) } ?? "—"
    }
}
