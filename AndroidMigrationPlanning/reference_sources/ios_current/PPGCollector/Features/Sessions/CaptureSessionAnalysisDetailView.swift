import SwiftUI

struct CaptureSessionAnalysisDetailView: View {
    let artifact: CaptureSessionAnalysisArtifact

    var body: some View {
        List {
            Section("来源与版本") {
                LabeledContent("文件", value: artifact.url.lastPathComponent)
                LabeledContent("运行时", value: artifact.report.runtimeProfile)
                LabeledContent("预处理", value: artifact.report.preprocessProfile)
                LabeledContent("HR 算法", value: artifact.report.heartRateAlgorithmVersion)
                LabeledContent("SQI 算法", value: artifact.report.signalQualityAlgorithmVersion)
                LabeledContent("raw SHA-256", value: artifact.report.sourceRawSHA256)
            }

            Section("摘要") {
                LabeledContent("raw 记录", value: "\(artifact.report.input.rawRecordCount)")
                LabeledContent("接受帧", value: "\(artifact.report.input.acceptedFrameCount)")
                LabeledContent("接受样本", value: "\(artifact.report.input.acceptedSampleCount)")
                LabeledContent("缺失帧", value: "\(artifact.report.input.missingFrameCount)")
                LabeledContent("重复帧", value: "\(artifact.report.input.duplicateFrameCount)")
                LabeledContent("乱序帧", value: "\(artifact.report.input.outOfOrderFrameCount)")
                LabeledContent("结构异常帧", value: "\(artifact.report.input.structurallyInvalidFrameCount)")
                LabeledContent("丢弃字节", value: "\(artifact.report.input.discardedByteCount)")
                if artifact.report.input.trailingRawByteCount > 0 {
                    LabeledContent("未纳入的 raw 尾部", value: "\(artifact.report.input.trailingRawByteCount) bytes")
                }
                LabeledContent("分析窗口", value: "\(artifact.report.summary.analysisWindowCount)")
                LabeledContent("有效 HR", value: "\(artifact.report.summary.validHeartRateWindowCount)")
                LabeledContent("暂定 SQI", value: "\(artifact.report.summary.provisionalSQIWindowCount)")
                if let value = artifact.report.summary.heartRateBPMMean {
                    LabeledContent("HR 平均值", value: String(format: "%.1f bpm", value))
                }
                if let value = artifact.report.summary.heartRateBPMMinimum {
                    LabeledContent("HR 最低值", value: String(format: "%.1f bpm", value))
                }
                if let value = artifact.report.summary.heartRateBPMMaximum {
                    LabeledContent("HR 最高值", value: String(format: "%.1f bpm", value))
                }
            }

            if !artifact.report.warnings.isEmpty {
                Section("提示") {
                    ForEach(artifact.report.warnings, id: \.self) { warning in
                        Label(warning, systemImage: "exclamationmark.triangle.fill")
                            .font(.footnote)
                            .foregroundStyle(.orange)
                    }
                }
            }

            Section("逐窗结果（8 秒窗 / 1 秒步长）") {
                ForEach(artifact.report.windows, id: \.endSampleIndex) { window in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(String(format: "%.1f s · 样本 %llu", window.endTimeSeconds, window.endSampleIndex))
                            .font(.headline)
                        Text(window.heartRateBPM.map { String(format: "HR %.1f bpm", $0) } ?? "HR 不可用：\(window.heartRateReason ?? "未知")")
                        Text(window.provisionalSQI.map { String(format: "暂定 SQI %.3f · %@", $0, window.provisionalSQIGrade ?? "—") } ?? "暂定 SQI 不可用：\(window.provisionalSQIReason ?? "未知")")
                            .foregroundStyle(.secondary)
                    }
                    .font(.footnote)
                }
            }
        }
        .navigationTitle("分析详情")
        .navigationBarTitleDisplayMode(.inline)
    }
}
