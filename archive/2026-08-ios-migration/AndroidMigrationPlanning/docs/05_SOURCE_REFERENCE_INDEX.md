# 源代码参考索引

本索引把 Android 工作项指向资料包内的只读快照。链接后的行号以 2026-08-01 快照为准；快照更新后应重跑校验并更新本表。`P0 移植`表示逐语义移植；`平台重写`表示保留契约但用 Android API 重写；`算法/UX 参考`不表示可以直接嵌入 Python。

## 1. 快速入口

| 主题 | 首读文件 | Android 去向 |
|---|---|---|
| CUP profile | [CUPDeviceProfile.swift:9](../reference_sources/ios_current/PPGCollector/Domain/Configuration/CUPDeviceProfile.swift#L9)、[ADR-0002](adr/ADR-0002-cup-ble-profile-registry.md)、[ADR-0005](adr/ADR-0005-nordic-nus-sensor-packet-profile.md) | `core/ble` profile registry；NUS/FFF0 transport + exact-name wire selection |
| 帧布局 | [ADR-0003](adr/ADR-0003-cup-168-byte-planar-wire-protocol.md)、[ADR-0004](adr/ADR-0004-cup-eight-byte-auxiliary-frames.md)、[ADR-0005](adr/ADR-0005-nordic-nus-sensor-packet-profile.md)、历史 [CUPBatchProtocol.swift:7](../reference_sources/ios_current/PPGCollector/Infrastructure/Protocol/CUPBatchProtocol.swift#L7) | `core/protocol` 两个 168-byte profile + auxiliary + legacy replay |
| 任意碎片解码 | [CUPBatchStreamDecoder.swift:3](../reference_sources/ios_current/PPGCollector/Infrastructure/Protocol/CUPBatchStreamDecoder.swift#L3) | `:core:protocol` |
| 序号/gap | [CUPFrameSequenceTracker.swift:21](../reference_sources/ios_current/PPGCollector/Infrastructure/Protocol/CUPFrameSequenceTracker.swift#L21) | `:core:protocol` |
| BLE 状态链 | [BLECentralService.swift:5](../reference_sources/ios_current/PPGCollector/Infrastructure/Bluetooth/BLECentralService.swift#L5) | `:data:ble` 平台重写 |
| 录制 gate/finalizer | [CaptureSessionController.swift:5](../reference_sources/ios_current/PPGCollector/Features/LiveCapture/CaptureSessionController.swift#L5) | service/use case |
| raw-first writer | [CaptureSessionWriter.swift:50](../reference_sources/ios_current/PPGCollector/Infrastructure/Storage/CaptureSessionWriter.swift#L50) | `:data:session` |
| raw reader/replay | [CUPRawFileReader.swift:68](../reference_sources/ios_current/PPGCollector/Infrastructure/Storage/CUPRawFileReader.swift#L68)、[CUPRawReplayEngine.swift:63](../reference_sources/ios_current/PPGCollector/Infrastructure/Storage/CUPRawReplayEngine.swift#L63) | `:data:session` |
| 预处理 | [PPGPreprocessor.swift:225](../reference_sources/ios_current/PPGCollector/SignalProcessing/Preprocessing/PPGPreprocessor.swift#L225) | `:core:signal` |
| HR | [HeartRateEstimator.swift:123](../reference_sources/ios_current/PPGCollector/SignalProcessing/HeartRate/HeartRateEstimator.swift#L123) | `:core:signal` |
| SQI | [TemplateMatchSQI.swift:167](../reference_sources/ios_current/PPGCollector/SignalProcessing/SQI/TemplateMatchSQI.swift#L167) | `:core:signal` |
| live scheduler | [PPGLiveMetricRuntime.swift:3](../reference_sources/ios_current/PPGCollector/SignalProcessing/Runtime/PPGLiveMetricRuntime.swift#L3) | `:core:signal` + live coordinator |
| Python live 参考 | [online.py:109](../reference_sources/python_gui/ppg_monitor/online.py#L109)、[live_gui.py:42](../reference_sources/python_gui/entry_points/live_gui.py#L42) | 算法/并发意图参考 |
| Python 离线稳定段 | [segmented_pulse.py:142](../reference_sources/python_gui/ppg_monitor/segmented_pulse.py#L142) | V1.1 `:feature:diagnostics` |
| Python 工作台 | [analysis_gui.py:205](../reference_sources/python_gui/ppg_monitor/analysis_gui.py#L205) | V1.1 UX 参考 |

## 2. iOS 当前实现：Domain 与配置

| 快照文件/符号 | 要提取的契约 | 等级 |
|---|---|---|
| [CUPDeviceProfile.swift](../reference_sources/ios_current/PPGCollector/Domain/Configuration/CUPDeviceProfile.swift) | CUP 名称前缀、既有 NUS service/notify/control UUID、passive stream | P0 保留；FFF0 来自 ADR-0002，`Nordic_UART_Service` 的 NUS + sensor wire 来自 ADR-0005；BLE API 平台重写 |
| [BluetoothModels.swift](../reference_sources/ios_current/PPGCollector/Domain/Models/BluetoothModels.swift) | availability、连接阶段、发现设备、诊断、资源快照、freshness | P0 移植 typed state |
| [CaptureModels.swift](../reference_sources/ios_current/PPGCollector/Domain/Models/CaptureModels.swift) | raw chunk event、stop reason、first-reason lifecycle gate、capture state | P0 移植 |
| [CaptureSessionMetadata.swift:3](../reference_sources/ios_current/PPGCollector/Domain/Models/CaptureSessionMetadata.swift#L3) | session JSON 字段、snake_case、ISO-8601、recovery provenance | P0 格式兼容 |
| [CaptureCSVSchema.swift:3](../reference_sources/ios_current/PPGCollector/Domain/Models/CaptureCSVSchema.swift#L3) | 25 列固定顺序 | P0 格式兼容 |
| [LiveMetricModels.swift](../reference_sources/ios_current/PPGCollector/Domain/Models/LiveMetricModels.swift) | valid/provisional/reason/version/source index/time/calibration；SpO2/BP unavailable | P0 移植 |
| [CUPWaveformSnapshot.swift](../reference_sources/ios_current/PPGCollector/Domain/Models/CUPWaveformSnapshot.swift) | immutable snapshot、默认 5 Hz、1–60 Hz、missed tick 不 burst | P0 行为 |
| [SessionNameValidator.swift](../reference_sources/ios_current/PPGCollector/Domain/Validation/SessionNameValidator.swift) | ASCII 名称、空白/重名规则 | P0 行为 |

对应测试：

- [Domain tests](../reference_sources/ios_current/PPGCollectorTests/Domain/)：lifecycle gate、metric model、name、wave snapshot、viewport 和 replay raw value。
- [Bluetooth profile tests](../reference_sources/ios_current/PPGCollectorTests/Bluetooth/CUPDeviceProfileTests.swift)。

## 3. iOS 当前实现：BLE

| 快照文件 | 关键内容 | Android 处理 |
|---|---|---|
| [BLETransport.swift](../reference_sources/ios_current/PPGCollector/Infrastructure/Bluetooth/BLETransport.swift) | transport abstraction、CoreBluetooth callback 事件化 | 用 `BluetoothLeScanner/BluetoothGatt` 重写，保留可 fake 接口 |
| [BLECentralService.swift](../reference_sources/ios_current/PPGCollector/Infrastructure/Bluetooth/BLECentralService.swift) | scan→connect→discover→subscribe→receive、active ID/phase gate、monotonic timestamp、pipeline、metrics、diagnostic throttle | 平台重写；保持状态/生成代/时序 |
| [BLEConnectionDeadlineTracker.swift](../reference_sources/ios_current/PPGCollector/Infrastructure/Bluetooth/BLEConnectionDeadlineTracker.swift) | per-stage deadline、取消和陈旧 deadline 拒绝 | 纯 Kotlin + coroutine clock |
| [CUPStreamFreshnessTracker.swift](../reference_sources/ios_current/PPGCollector/Infrastructure/Bluetooth/CUPStreamFreshnessTracker.swift) | waiting/fresh/stale、2 s threshold | 纯 Kotlin |

对应测试：

- [BLE deadline tests](../reference_sources/ios_current/PPGCollectorTests/Bluetooth/BLEConnectionDeadlineTrackerTests.swift)
- [Freshness tests](../reference_sources/ios_current/PPGCollectorTests/Bluetooth/CUPStreamFreshnessTrackerTests.swift)
- [BLE integration tests](../reference_sources/ios_current/PPGCollectorTests/Integration/BLECentralServiceIntegrationTests.swift)

注意：Python 快照的旧 BLE/NUS/transport 没有归档，Android 不应以它们替代上述当前 profile。FFF0 来自 2026-08-03 硬件证据；`Nordic_UART_Service` 的 NUS GATT 与 sensor packet 来自 2026-08-05 nRF 日志。两者不回写只读 iOS reference；固件身份、实际采样率与控制命令仍需真机证据。

## 4. iOS 当前实现：协议

| 快照文件/符号 | 关键内容 | Android 去向 |
|---|---|---|
| [ADR-0003](adr/ADR-0003-cup-168-byte-planar-wire-protocol.md) | 当前 168-byte layout、20+20 planar、函数/头尾与兼容策略 | `:core:protocol` 当前常量、model 与 golden |
| [ADR-0004](adr/ADR-0004-cup-eight-byte-auxiliary-frames.md) | `testdevice1` 中 8-byte FFF1 辅助帧、功能码白名单与完整性分类 | stream decoder auxiliary diagnostics、raw replay/inspection |
| [ADR-0005](adr/ADR-0005-nordic-nus-sensor-packet-profile.md) | exact-name NUS 设备、UInt32 sequence sensor packet、CSV v2 与 decoder 路由 | `CupSensorPacketProtocolV1`、BLE mode、preview/recording/replay/inspection |
| [CUPBatchProtocol.swift:7](../reference_sources/ios_current/PPGCollector/Infrastructure/Protocol/CUPBatchProtocol.swift#L7) | 历史 408-byte interleaved、100 Hz、50 samples | legacy raw/session 读取与回放兼容，不作为当前 encoder |
| [CUPBatchStreamDecoder.swift:3](../reference_sources/ios_current/PPGCollector/Infrastructure/Protocol/CUPBatchStreamDecoder.swift#L3) | 任意 byte chunk、resync、LE decode、invalid stats | `CupBatchStreamDecoder` |
| [CUPFrameSequenceTracker.swift:21](../reference_sources/ios_current/PPGCollector/Infrastructure/Protocol/CUPFrameSequenceTracker.swift#L21) | continuity/gap/duplicate/out-of-order/wrap | `CupFrameSequenceTracker` |
| [CUPStreamingPipeline.swift:36](../reference_sources/ios_current/PPGCollector/Infrastructure/Protocol/CUPStreamingPipeline.swift#L36) | decoder+sequence、accepted stream、recent 800、diagnostics | `CupStreamingPipeline` |

跨语言核验：

- [cup_batch_protocol.py](../reference_sources/protocol/cup_batch_protocol.py)
- [CUPBatchProtocol.h](../reference_sources/protocol/CUPBatchProtocol.h) / [CUPBatchProtocol.cpp](../reference_sources/protocol/CUPBatchProtocol.cpp)
- [golden_seq42.bin](../reference_sources/protocol/fixtures/golden_seq42.bin) / [golden_seq42.json](../reference_sources/protocol/fixtures/golden_seq42.json)
- [golden 生成器](../reference_sources/protocol/fixtures/generate_golden_vectors.py) / [Python 自测](../reference_sources/protocol/fixtures/test_cup_batch_protocol.py)
- [Swift protocol tests](../reference_sources/ios_current/PPGCollectorTests/Protocol/)

## 5. iOS 当前实现：录制与存储

| 快照文件/符号 | 关键内容 | 等级 |
|---|---|---|
| [CaptureSessionController.swift:5](../reference_sources/ios_current/PPGCollector/Features/LiveCapture/CaptureSessionController.swift#L5) | start gates、版本固化、pending=256、串行 write tail、统一/idempotent stop、生命周期错误 | P0；拆为 use case + FGS owner |
| [CaptureSessionDependencies.swift](../reference_sources/ios_current/PPGCollector/Features/LiveCapture/CaptureSessionDependencies.swift) | stream/writer 注入 seam | P0 可测试性参考 |
| [CaptureSessionWriter.swift:50](../reference_sources/ios_current/PPGCollector/Infrastructure/Storage/CaptureSessionWriter.swift#L50) | 20 MiB preflight、no overwrite、raw-first、CSV、1 s sync、metadata/finalise | P0 逐语义移植 |
| [CUPRawFileReader.swift:68](../reference_sources/ios_current/PPGCollector/Infrastructure/Storage/CUPRawFileReader.swift#L68) | `CUPRAW1`、record、安全尾部、64 KiB | P0 格式/防御 |
| [CUPRawReplayEngine.swift:63](../reference_sources/ios_current/PPGCollector/Infrastructure/Storage/CUPRawReplayEngine.swift#L63) | 流式 scan、同 production pipeline、计数/内存报告 | P0 |
| [CaptureSessionRepository.swift](../reference_sources/ios_current/PPGCollector/Infrastructure/Storage/CaptureSessionRepository.swift) | 文件系统权威、list、metadata snapshot/recovery audit | P0 |
| [CaptureSessionInspectionService.swift](../reference_sources/ios_current/PPGCollector/Infrastructure/Storage/CaptureSessionInspectionService.swift) | raw/CSV/metadata 一致性、stream scan、findings | P0 |
| [CaptureSessionRecoveryService.swift:62](../reference_sources/ios_current/PPGCollector/Infrastructure/Storage/CaptureSessionRecoveryService.swift#L62) | 源只读、safe prefix、staging、新 metadata/provenance、atomic move | P0 |
| [CaptureSessionAnalysisService.swift:94](../reference_sources/ios_current/PPGCollector/Infrastructure/Storage/CaptureSessionAnalysisService.swift#L94) | raw SHA、版本化非覆盖分析、进度/取消 | P1/V1.1 |
| [PPGExpertDiagnosticsService.swift:93](../reference_sources/ios_current/PPGCollector/Infrastructure/Storage/PPGExpertDiagnosticsService.swift#L93) | production raw pipeline、8 s/1 s、HR/SQI/ratio、稳定窗口 | P1/V1.1 基线 |

对应测试全部位于 [PPGCollectorTests/Storage](../reference_sources/ios_current/PPGCollectorTests/Storage/)，控制器集成见 [CaptureSessionControllerIntegrationTests.swift](../reference_sources/ios_current/PPGCollectorTests/Integration/CaptureSessionControllerIntegrationTests.swift)，长稳见 [LongDurationDataPathTests.swift:6](../reference_sources/ios_current/PPGCollectorTests/Integration/LongDurationDataPathTests.swift#L6)。

## 6. iOS 当前实现：信号处理

| 快照文件/符号 | 关键内容 | fixture/test |
|---|---|---|
| [PPGPreprocessor.swift:225](../reference_sources/ios_current/PPGCollector/SignalProcessing/Preprocessing/PPGPreprocessor.swift#L225) | 固定 SOS、DC、causal state、IR polarity、zscore、gap reset | [fixture](../reference_sources/signal_fixtures/preprocessing/preprocessing_vectors.json)、[Swift tests](../reference_sources/ios_current/PPGCollectorTests/SignalProcessing/PPGPreprocessorTests.swift) |
| [SciPyPeakDetector.swift](../reference_sources/ios_current/PPGCollector/SignalProcessing/Shared/SciPyPeakDetector.swift) | plateau midpoint、distance、prominence、width | HR/SQI tests 中的 peak debug 字段 |
| [HeartRateEstimator.swift:123](../reference_sources/ios_current/PPGCollector/SignalProcessing/HeartRate/HeartRateEstimator.swift#L123) | robust scale、DFT/Hann、双极性、RR、confidence | [fixture](../reference_sources/signal_fixtures/heart_rate/heart_rate_vectors.json)、[Swift tests](../reference_sources/ios_current/PPGCollectorTests/SignalProcessing/HeartRateEstimatorTests.swift) |
| [TemplateMatchSQI.swift:167](../reference_sources/ios_current/PPGCollector/SignalProcessing/SQI/TemplateMatchSQI.swift#L167) | peak cycle、mean template、Pearson、grade、trace | [fixture](../reference_sources/signal_fixtures/sqi/sqi_vectors.json)、[Swift tests](../reference_sources/ios_current/PPGCollectorTests/SignalProcessing/TemplateMatchSQITests.swift) |
| [RatioOfRatiosEstimator.swift](../reference_sources/ios_current/PPGCollector/SignalProcessing/RatioOfRatiosEstimator.swift) | trim/RMS AC/DC ratio，仅诊断 | [Swift tests](../reference_sources/ios_current/PPGCollectorTests/SignalProcessing/RatioOfRatiosEstimatorTests.swift) |
| [PPGLiveMetricRuntime.swift](../reference_sources/ios_current/PPGCollector/SignalProcessing/Runtime/PPGLiveMetricRuntime.swift) | profile、800/100、双预处理器、generation、request/result | [runtime tests](../reference_sources/ios_current/PPGCollectorTests/SignalProcessing/PPGLiveMetricRuntimeTests.swift) |

Fixture 生成与独立核验脚本：

- [preprocessing generator](../reference_sources/signal_fixtures/preprocessing/generate_preprocessing_vectors.py) / [test](../reference_sources/signal_fixtures/preprocessing/test_preprocessing_vectors.py)
- [heart-rate generator](../reference_sources/signal_fixtures/heart_rate/generate_heart_rate_vectors.py) / [test](../reference_sources/signal_fixtures/heart_rate/test_heart_rate_vectors.py)
- [SQI generator](../reference_sources/signal_fixtures/sqi/generate_sqi_vectors.py) / [test](../reference_sources/signal_fixtures/sqi/test_sqi_vectors.py)

## 7. iOS 当前实现：UI/功能页面

这些文件主要用于信息架构和交互语义；Android 使用 Compose 重写，不追求 SwiftUI view 层级一一对应。

| 快照文件 | 参考内容 | Android feature |
|---|---|---|
| [PPGCollectorApp.swift](../reference_sources/ios_current/PPGCollector/PPGCollectorApp.swift)、[ContentView.swift](../reference_sources/ios_current/PPGCollector/ContentView.swift) | app scope dependency、导航、background stop 现状 | `:app`；生命周期策略按 Android ADR |
| [DeviceListView.swift](../reference_sources/ios_current/PPGCollector/Features/Devices/DeviceListView.swift) | 设备/实时/指标/诊断/录制的组合与 gate 文案 | `:feature:devices`、`:feature:capture` |
| [CUPDualWaveformPreview.swift:24](../reference_sources/ios_current/PPGCollector/Features/LiveCapture/CUPDualWaveformPreview.swift#L24) | RED/IR Canvas、独立 Y、min/max bucket、raw/processed | Compose Canvas |
| [CUPWaveformViewport.swift](../reference_sources/ios_current/PPGCollector/Features/LiveCapture/CUPWaveformViewport.swift) | zoom 1–80、pan/clamp | replay viewport pure math |
| [SavedSessionsView.swift:3](../reference_sources/ios_current/PPGCollector/Features/Sessions/SavedSessionsView.swift#L3)、[SavedSessionDetailView.swift](../reference_sources/ios_current/PPGCollector/Features/Sessions/SavedSessionDetailView.swift) | 会话列表/详情、检查、重放、导出、恢复、分析 | `:feature:sessions` |
| [CaptureSessionAnalysisTaskStore.swift](../reference_sources/ios_current/PPGCollector/Features/Sessions/CaptureSessionAnalysisTaskStore.swift) | progress/cancel/task state | analysis ViewModel/use case |
| [Analysis history](../reference_sources/ios_current/PPGCollector/Features/Sessions/CaptureSessionAnalysisHistoryView.swift)、[detail](../reference_sources/ios_current/PPGCollector/Features/Sessions/CaptureSessionAnalysisDetailView.swift)、[comparison](../reference_sources/ios_current/PPGCollector/Features/Sessions/CaptureSessionAnalysisComparisonView.swift) | 版本化分析和对比 UI | V1.1 |
| [PPGExpertDiagnosticsView.swift:3](../reference_sources/ios_current/PPGCollector/Features/Sessions/PPGExpertDiagnosticsView.swift#L3) | Workbench/Compare/Diagnostics/PPG/Spectrum/Cycle | V1.1/V2 |

## 8. Python GUI：应参考的代码

### 8.1 在线采集/处理

| 文件/符号 | 参考点 | 处置 |
|---|---|---|
| [live_gui.py:42](../reference_sources/python_gui/entry_points/live_gui.py#L42) | reader thread、raw-first、UI snapshot 解耦、每秒 flush、安全 stop/drain、session JSON、offline analyze 入口 | 并发/产品意图参考；用 Kotlin/FGS 重写 |
| [online.py:36](../reference_sources/python_gui/ppg_monitor/online.py#L36) | dynamic Y；起始过渡排除和 padding | Compose 波形参考 |
| [online.py:109](../reference_sources/python_gui/ppg_monitor/online.py#L109) | bounded deque、causal filter、1 Hz HR、RED/IR selection、quality state | 算法交叉参考；Swift parity 优先 |
| [pulse.py:23](../reference_sources/python_gui/ppg_monitor/pulse.py#L23) | pulse estimate、spectral candidate、RR run | HR 语义参考 |
| [raw_format.py:16](../reference_sources/python_gui/ppg_monitor/raw_format.py#L16) | RawWriter/iter_chunks | raw cross-check；当前 Swift `CUPRAW1` 优先 |
| [capture_source.py](../reference_sources/python_gui/ppg_monitor/capture_source.py)、[timing.py](../reference_sources/python_gui/ppg_monitor/timing.py)、[integrity.py](../reference_sources/python_gui/ppg_monitor/integrity.py) | source abstraction、时序/完整性统计 | Android diagnostic/test 参考 |

### 8.2 离线/工作台

| 文件/符号 | 参考点 | 处置 |
|---|---|---|
| [segmented_pulse.py:142](../reference_sources/python_gui/ppg_monitor/segmented_pulse.py#L142) | stable segments、settling、8 s/2 s、通道/极性、rejection、BPM cluster | V1.1 算法主参考；独立 version |
| [offline.py:146](../reference_sources/python_gui/ppg_monitor/offline.py#L146) | analyze frames、average waveform、报告 | V1.1 分析/导出参考；IMU 分支忽略 |
| [analysis_gui.py:205](../reference_sources/python_gui/ppg_monitor/analysis_gui.py#L205) | workspace、stage、compare、diagnostics、PPG/spectrum/cycle | Compose 信息架构参考 |
| [session.py:57](../reference_sources/python_gui/ppg_monitor/session.py#L57) | session loading/timing summary | 交叉检查；旧协议字段不移植 |
| [binary_analysis.py](../reference_sources/python_gui/ppg_monitor/binary_analysis.py) | binary analysis orchestration | 离线流程参考 |

对应测试集中在 [python_gui/tests](../reference_sources/python_gui/tests/)；优先阅读 `test_online.py`、`test_pulse.py`、`test_segmented_pulse.py`、`test_offline.py`、`test_raw_session.py` 和 `test_analysis_gui.py`。这些测试转写时要剥离 PyQt/旧 transport 依赖，保留输入输出断言。

## 9. 产品原始材料

| 文件 | 用途 |
|---|---|
| [测试软件需求V1.0.docx](../reference_sources/product_requirements/测试软件需求V1.0.docx) | CUP 扫描、UI、RED/IR、8 s、0.2 s 刷新、SQI、录制 gate/stop/name/version 的最高产品依据 |
| [requirement_mockup.png](../reference_sources/product_requirements/requirement_mockup.png) | 页面布局意图，非像素级 Android 规范 |
| [protocol_frame_requirement.jpg](../reference_sources/product_requirements/protocol_frame_requirement.jpg) | 帧示意；必须由真实抓包和当前 decoder 复核 |
| [sqi_template_match.py:200](../reference_sources/product_requirements/sqi_template_match.py#L200) | SQI 公式/分级参考；其完整 preprocessing/CPE 缺失，因此当前 SQI 保持 provisional |
| [requirements README](../reference_sources/product_requirements/README.md) | 材料来源、已知歧义和限制 |

## 10. 全量测试与资源入口

- 当前 Swift 测试根：[PPGCollectorTests](../reference_sources/ios_current/PPGCollectorTests/)
- 协议测试资源：[Resources/Protocol](../reference_sources/ios_current/PPGCollectorTests/Resources/Protocol/)
- 信号测试资源：[Resources/SignalProcessing](../reference_sources/ios_current/PPGCollectorTests/Resources/SignalProcessing/)
- Python 参考测试：[python_gui/tests](../reference_sources/python_gui/tests/)
- 快照来源/排除边界：[SOURCE_CATALOG.md](../manifests/SOURCE_CATALOG.md)、[EXCLUSIONS.md](../manifests/EXCLUSIONS.md)
- 文件级 SHA-256：[SHA256SUMS.txt](../manifests/SHA256SUMS.txt)

## 11. Android issue 引用格式

建议每个实现 issue 写明：

```text
Requirement: PROTO-002
Primary source: reference_sources/ios_current/.../CUPBatchStreamDecoder.swift:3
Tests: reference_sources/ios_current/PPGCollectorTests/Protocol/...
Golden: reference_sources/protocol/fixtures/golden_seq42.bin
Android target: :core:protocol/CupBatchStreamDecoder.kt
Parity rule: exact frame/sample/stats; random chunking invariant
```

这样源快照、需求 ID、Android 目标和验收始终可追溯。

## M7.1–M7.5 Android 增量索引（2026-08-08）

| 需求 | Android target | 关键合同/证据 |
|---|---|---|
| `M7-NAME-001` / `M7-SUB-001` | `data/session/SessionNamePolicy.kt`, `SubjectProfile.kt`, `CaptureSessionMetadata.kt` | `SessionNamePolicyTest`, `SubjectProfileTest`, metadata v1/v2 compatibility |
| `M7-MET-001/002` / `M7-BP-001` | `CaptureMetricSeries.kt`, `CaptureBloodPressureSeries.kt`, `CaptureRecordingController.kt`, `CaptureSessionWriter.kt` | sidecar schema/scan tests; one epoch row per analysis result; BP token contract |
| `M7-DSP-001` | `core/signal/PpgDisplayTransform.kt`, `MainActivity.kt`, `SessionSignalWorkbench.kt` | `PpgDisplayTransformTest`; raw storage path not transformed |
| `M7-DSP-002` | `core/signal/FixedLagPpgFilterRuntime.kt`, `LivePpgSignalRuntime.kt` | `FixedLagPpgFilterRuntimeTest`; 100 sample explicit latency; numerical/device admission pending |
| `M7.3` `M7-NAME-001` / `M7-SUB-001` / `M7-BP-001` | `CaptureSetupModels.kt`, `CaptureServiceViewModel.kt`, `CaptureForegroundService.kt`, `CaptureRecordingController.kt`, `CaptureSessionWriter.kt`, `ManualBloodPressureDialog.kt` | service/controller BP token seam; participant snapshot/profile revision; full Gradle gate; real BP/IME/rotation pending |
| `M7.4` `M7-ARC-001` / `M7-EXP-001` | `SubjectArchiveModels.kt`, `CaptureArchiveExportService.kt`, `SubjectArchiveScreen.kt`, `SessionsViewModel.kt`, `MainActivity.kt` | subject-first numeric seq grouping, unclassified retention, manifest/hash/streaming ZIP; archive selection/SAF runtime pending |
| `M7.5` `M7-UI-001` plus naming/archive integration | `SessionNamePolicy.kt`, `CaptureSessionWriter.kt`, `SubjectArchiveScreen.kt`, `SessionsScreens.kt`, `SessionsViewModel.kt`, `MainActivity.kt` | example suggestion fallback, case-insensitive canonical prefix normalization, Saved Sessions archive/file sibling views, selection toolbar/delete/export, compact recording device block and `imePadding`; emulator/real-device accessibility and SAF runtime pending |

M7.5 code is implemented and indexed above; real-device IME/rotation, TalkBack/dynamic font, SAF batch progress/cancellation and long-capture gates remain pending.

## M7.6 Compose UI、状态与发布性能索引（2026-08-09）

| 需求 | Android target | 关键合同/证据 |
|---|---|---|
| `M7-UI-002/003` | `SavedSessionsRoute.kt`, `SavedSessionsUiPolicy.kt`, `SubjectArchiveScreen.kt`, `SessionsScreens.kt`, `SessionsViewModel.kt` | 单一 Saved Sessions route、固定双层 top bar、稳定返回/视图切换语义、contextual selection bar、去重会话计数、删除确认、扁平 archive LazyList keys；`SavedSessionsUiPolicyTest` / `SavedSessionsRouteTest` |
| `M7-UI-004/005` | `LiveCaptureScreen.kt`, `LiveWaveformComponents.kt`, `CaptureUiPolicy.kt`, `ManualBloodPressureDialog.kt`, `CaptureServiceViewModel.kt` | event-driven compact/detailed、固定录制操作栏、RAW/CAUSAL/FIXED、五指标自适应一行、IME/focus/数字键盘、BP dialog ViewModel state；`CaptureUiPolicyTest` / existing Compose seams |
| `M7-PERF-001/002` | `MainActivity.kt`, `CaptureServiceViewModel.kt`, `LiveWaveformComponents.kt`, `PpgDisplayTransform.kt`, `LiveWaveformRuntime.kt` | waveform/analysis/preview leaf collection、low/high-frequency state slice、只读 array ownership、无装箱 detrend、publication-keyed transform/plot 与 `drawWithCache`、删除旧 duplicate `SessionsPanel` |
| `M7-PERF-003` | `baselineprofile/`, `app/src/main/baseline-prof.txt`, Gradle/settings/version catalog, `verifyReleasePrivacy` | Baseline Profile generator + startup/sessions CUJ、ProfileInstaller、release `assets/dexopt/baseline.prof` 打包断言；真实设备 profile 生成与 Macrobenchmark 数值仍是 runtime 门禁 |

本节与 [`10_M7_6_COMPOSE_UI_PERFORMANCE_PLAN.md`](10_M7_6_COMPOSE_UI_PERFORMANCE_PLAN.md) 是 M7.6 UI/性能的当前依据；`docs/09` 继续作为 M7.0～M7.5 历史和数据合同依据，不得用于回退本节页面层级。

## M7.7 会话详情、离线滤波与对齐可视化索引（2026-08-09）

| 需求 | Android target | 关键合同/证据 |
|---|---|---|
| `M7-UI-006/007` | `LiveWaveformComponents.kt`, `CaptureUiPolicy.kt`, `SavedSessionsRoute.kt` | `RAW/CAUSAL/FIXED` 完整标签；HR/RR/PI/SQI/BP 五项无横向隐藏，常规 5 列、窄屏/大字体 3+2；Saved Sessions 操作区与 16 dp 内容边界一致；`CaptureUiPolicyTest` / Compose system-test seam |
| `M7-DETAIL-001` / `M7-ANL-001` | `SessionDetailUiPolicy.kt`, `SessionsScreens.kt` | 概览、来源、被试、BP、完整性、重放、分析、导出默认全部收起且独立 toggle；单 artifact 不显示无意义“历史 1”，多个结果才显示选择器；`SessionDetailUiPolicyTest` |
| `M7-DSP-003` | `PpgPreprocessing.kt`, `OfflinePpgAnalysis.kt`, `CaptureSessionOfflineAnalysis.kt`, `SessionSignalWorkbench.kt` | `offline-biquad-filtfilt-0.5-12hz-0.1` 分段 ZERO 与 source-aligned `fixed-lag-fir-0.5-12hz-0.1` 同时可选；gap 不共享状态，raw/CSV 不回写；频带响应/FIR 对齐/session trace tests |
| `M7-MET-003` | `CaptureMetricSeries.kt`, `CaptureSessionOfflineAnalysis.kt`, `SessionSignalWorkbench.kt` | 有界读取 1 Hz valid HR/SQI/R/PI source cursor；PPG 与指标采用 shared-x、独立 Y lane，viewport/gap 同步；sidecar round-trip 与 UI semantics seam |
| `M7-BP-002` | `OfflineBloodPressurePreview.kt`, `SessionSignalWorkbench.kt` | 手工 SBP/DBP 使用 dialog-open source time；无模型时仅离线生成不落盘 120/80、1 Hz 占位序列并显式标注，live 继续 `—`；warm-up/cadence/gap/metric-epoch tests |

本节与 [`11_M7_7_SESSION_DETAIL_AND_TIMELINE_PLAN.md`](11_M7_7_SESSION_DETAIL_AND_TIMELINE_PLAN.md) 是 M7.7 的当前依据。`docs/09` 继续管理 session/sidecar/subject 数据合同；`docs/10` 保留 Compose/性能基线，但其“大字体改为横向可达”和旧详情页行为被 M7.7 的 3+2 网格、默认折叠与 shared-x 设计覆盖。
