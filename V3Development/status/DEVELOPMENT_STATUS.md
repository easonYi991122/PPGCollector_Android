# V3.0 开发状态（简版）

更新：2026-08-19  
当前轮次：**V3.R5 已完成代码收口，待后续真机/产品复验**

## 当前事实

- Android 采集 App 已能扫描/连接 CUP（名称前缀 `CUP`）和 `Nordic_UART_Service`（NUS），解析两种 168-byte PPG 帧，前台服务录制，会话目录落盘。
- R1–R3 产品面已合入；R4.1 已补齐 Nordic 120 预览解码、锁定后切换、录制 PPG/ECG 显示管线、optional ECG 文件契约及协议探测交互。
- R5 已补齐会话级参考血压写回、PPG/MB 导出目录、ECG sidecar 导出/恢复与旧 metadata 兼容。
- 规划与参考已收口到 `V3Development/`。旧 iOS 移植文档在 `archive/2026-08-ios-migration/`。

## R1 证据与残留

- 生产代码：`SessionNamePolicy`、`CaptureSetupModels`、`CaptureStartGate`、`CaptureServiceViewModel`、`CaptureSessionMetadata`、`SubjectProfile`、`LiveCaptureScreen`。
- 测试：`SessionNamePolicyTest`、`CaptureStartGateTest`、`CaptureGateUiStateTest`、`SubjectProfileTest`、`CaptureSessionMetadataTest`。
- **R4.1 待修**：门控 `combine` 未订阅 `_participantDraft`；服务侧 `start` 仍用无 participant 的 `validate`；采集页血压 dialog 死参数仍挂在 `MainActivity`。

## R2 证据与残留

- 生产代码：`CaptureRecordModePolicy`、`CaptureRecordingController`、`CaptureSessionConfiguration`、`CaptureSessionMetadata`、`CaptureSessionWriter`、`CaptureForegroundService`、`CaptureServiceViewModel`、`LiveCaptureScreen`。
- 测试：`CaptureRecordModePolicyTest`、`CaptureRecordingControllerTest`、`CaptureSessionMetadataTest`。
- **R4.1 待修**：非法时长开始时 fallback 60，输入框不回写默认值。

## R3 证据与残留

- 生产代码：`core/signal/combo/ComboSqi`、`ComboSqiDebounce`、`LiveMetricRuntime`、`LiveMetricModels`、`CaptureRecordingController`、`LiveWaveformComponents`。
- 综合 SQI 仅用于显示层；CSV `sqi` 仍为 TemplateMatchSqi。
- **R4.1 待修**：`ComboSqi.evaluate()` 是简化近似（无 Python 输入链，压力高度恒 0.5）；预览路径未把 combo 写入磁贴。

## R4 证据与缺口

- 已落地：`Ads1292rPacketProtocol`、`Ads1292rStreamDecoder`、`NordicWireProbe`、writer `{stem}_ecg.csv`、metadata `files.ecg`。
- **未闭环（阻塞下一产品轮）**：`BlePreviewRuntime` 无 120 分支；锁定 120 后不重建 preview → 新鲜度无法 FRESH；无 2 s 超时/手动覆盖 UI；录制 120 不喂 `LivePpgSignalRuntime`；`SessionFileSet` 无 optional `ecg`；无 fake 25 Hz 120 发生器。
- 定向协议测试通过 ≠ R4 验收通过。

## R4.1 / R5 事实

- `LiveWaveformSnapshot.ecg` 为 500 Hz ECG 的 5:1 显示抽点，writer 仍写原始 500 Hz `_ecg.csv`。
- `BleCoordinator.uiSnapshotFlow` 去除高频 diagnostics 驱动，门控订阅 participant 并过滤计数变更；波形绘图上限降至 320 点。
- `CaptureSessionMetadataEditor` 以原子替换写回 session-level `sbp/dbp` 与 `bp_updated_at`。
- `SessionFileSet.ecg` 为 optional；CUP/Nordic 168 缺少 ECG 合法，120 会话可导出和安全恢复。
- 归档 ZIP 路径为 `subjects/{subject}/PPG|MB/{stem}/…`。

## 验收记录

- `:app:compileDebugKotlin` 通过。
- JVM 测试共 183 个，当前 3 个失败：2 个为工作区既有离线分析回归（`OfflinePpgAnalysisTest`、`OfflineBloodPressurePreviewTest`），1 个归档路径断言已按 R5 新目录修正，需再次运行确认。
- 真机仍不作为默认门禁；需后续用 fake 120/168、CUP 和真实腕部设备复验。

## 真机门禁

默认不把真机操作算作完成条件。R4.1 用 fake BLE 分别验收 120-byte ECG 与同一 Nordic 身份上的 168-byte sensor packet；无腕部固件不阻塞轮次完成。
