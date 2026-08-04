# 需求与功能对等矩阵

本文件把产品需求、当前 Swift 行为和 Python 参考转成可测试的 Android 条目。优先级：P0 阻断 V1，P1 阻断 V1.1，P2 后续。来源中的路径均指向本资料包快照；关键符号进一步见[源代码参考索引](05_SOURCE_REFERENCE_INDEX.md)。

## 1. 产品与交互

| ID | P | Android 要求 | 依据 | 验收 |
|---|---:|---|---|---|
| UI-001 | P0 | 首页能扫描名称以 `CUP` 开头的 BLE 设备，展示名称/RSSI/连接阶段，能停止扫描、连接、断开、重试 | [原始需求](../reference_sources/product_requirements/测试软件需求V1.0.docx)、[DeviceListView.swift](../reference_sources/ios_current/PPGCollector/Features/Devices/DeviceListView.swift) | 真机出现非 CUP 广播时不进入列表；CUP 可完整走到 receiving |
| UI-002 | P0 | RED 与 IR 原始波形分轨显示，窗口 8 s，分别动态 Y 轴；默认 5 Hz 快照，调度器允许 1–60 Hz | [需求](../reference_sources/product_requirements/测试软件需求V1.0.docx)、[CUPDualWaveformPreview.swift](../reference_sources/ios_current/PPGCollector/Features/LiveCapture/CUPDualWaveformPreview.swift)、[CUPWaveformSnapshot.swift](../reference_sources/ios_current/PPGCollector/Domain/Models/CUPWaveformSnapshot.swift) | 输入 100 Hz/2 h 模拟流，发布节奏正确；UI ring 仍为 800 样本，跳帧不补发 burst |
| UI-003 | P0 | HR、SQI 和 R（ratio-of-ratios）诊断每秒更新；R 不是呼吸率；状态包括 warming-up/valid/provisional/stale/unavailable，不能只给默认数字 | [LiveMetricModels.swift](../reference_sources/ios_current/PPGCollector/Domain/Models/LiveMetricModels.swift)、[online.py](../reference_sources/python_gui/ppg_monitor/online.py) | 前 8 s、gap、无峰、断流分别显示正确状态；无效 SQI 不显示为有效 0% |
| UI-004 | P0 | SpO2、BP 在缺少校准/模型时明确“不可用”；ratio 仅放诊断页并标注未校准 | [RatioOfRatiosEstimator.swift](../reference_sources/ios_current/PPGCollector/SignalProcessing/RatioOfRatiosEstimator.swift)、[LiveMetricModels.swift](../reference_sources/ios_current/PPGCollector/Domain/Models/LiveMetricModels.swift) | V1 无任何伪造 SpO2/BP 数字；导出元数据能说明不可用原因 |
| UI-005 | P0 | 录制名仅 `[A-Za-z0-9_-]+`；空白无默认名、不可重名、录制期间锁定 | [原始需求](../reference_sources/product_requirements/测试软件需求V1.0.docx)、[SessionNameValidator.swift](../reference_sources/ios_current/PPGCollector/Domain/Validation/SessionNameValidator.swift) | 参数化单测覆盖中文、空格、路径字符、空串、已有目录和合法边界 |
| UI-006 | P0 | 开始按钮只有在已连接、流 fresh、名称合法、未录制、存储预检通过时可用 | [CaptureSessionController.swift](../reference_sources/ios_current/PPGCollector/Features/LiveCapture/CaptureSessionController.swift) | 每个 gate 单独失败时禁用并给可理解原因；状态竞态测试通过 |
| UI-007 | P0 | 停止录制后如 BLE 数据仍新鲜，实时波形继续；断连/无数据则显示对应状态 | [原始需求](../reference_sources/product_requirements/测试软件需求V1.0.docx) | 用户停止只关闭 writer，不破坏预览连接；断连走统一停止 |
| UI-008 | P0 | 录制前台服务通知显示设备/时长/健康状态，并提供“停止并保存” | Android 平台要求与产品长录制目标 | 退到后台/锁屏后继续写入；通知动作只 finalise 一次；Activity 重建不建第二会话 |
| UI-009 | P0 | 会话列表和详情来自文件系统扫描；展示 complete/incomplete、停止原因、版本和完整性 | [CaptureSessionRepository.swift](../reference_sources/ios_current/PPGCollector/Infrastructure/Storage/CaptureSessionRepository.swift)、[SavedSessionsView.swift](../reference_sources/ios_current/PPGCollector/Features/Sessions/SavedSessionsView.swift) | 删除可重建数据库索引后仍可列出会话；损坏会话被标记而非崩溃 |
| UI-010 | P1 | 工作台含 Overview、Workbench、Compare、Diagnostics、PPG、Spectrum、Cycle；分析任务可取消，有历史 | [analysis_gui.py](../reference_sources/python_gui/ppg_monitor/analysis_gui.py)、[PPGExpertDiagnosticsView.swift](../reference_sources/ios_current/PPGCollector/Features/Sessions/PPGExpertDiagnosticsView.swift) | 统一使用 raw replay 结果；长分析不阻塞主线程；结果版本化且不可覆盖 |

## 2. BLE、协议与时序

| ID | P | Android 要求 | 依据 | 验收 |
|---|---:|---|---|---|
| BLE-001 | P0 | API 31+ 请求 `BLUETOOTH_SCAN`/`CONNECT`；API ≤30 使用正确 legacy/位置分支；拒绝或撤销权限可恢复 | [Android BLE 权限](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions) | API 30/31/33/36 permission matrix instrumented test + 手测 |
| BLE-002 | P0 | 保留 iOS NUS service/notify/control `6E400001/3/2-...`，并支持新硬件 `0000FFF0/1/2-0000-1000-8000-00805F9B34FB`；服务发现后精确选择整组 profile。FFF2 命令契约未知，bring-up 不写控制数据 | [CUPDeviceProfile.swift](../reference_sources/ios_current/PPGCollector/Domain/Configuration/CUPDeviceProfile.swift)、[ADR-0002](adr/ADR-0002-cup-ble-profile-registry.md) | 两组 profile/fake GATT 单测；实际 profile 写入 session；新旧真机发现/订阅成功；无推测性 control write |
| BLE-003 | P0 | 回调复制通知 bytes 并记录 `elapsedRealtimeNanos`；不得阻塞回调线程 | [BLECentralService.swift](../reference_sources/ios_current/PPGCollector/Infrastructure/Bluetooth/BLECentralService.swift)、[live_gui.py](../reference_sources/python_gui/entry_points/live_gui.py) | 回调压力测试无磁盘/算法调用；StrictMode/trace 无主线程 I/O |
| BLE-004 | P0 | connect/service/characteristic/subscribe 每阶段独立超时，旧 generation callback 丢弃 | [BLEConnectionDeadlineTracker.swift](../reference_sources/ios_current/PPGCollector/Infrastructure/Bluetooth/BLEConnectionDeadlineTracker.swift) | fake GATT 对每阶段超时、晚到回调、快速重连做测试 |
| BLE-005 | P0 | 订阅必须写 CCCD 并等待成功回调；请求 MTU 只可优化，不能改变 decoder 契约 | [BLETransport.swift](../reference_sources/ios_current/PPGCollector/Infrastructure/Bluetooth/BLETransport.swift) | 默认/不同 MTU 与任意分片都产出相同 frame |
| BLE-006 | P0 | 最后接受样本超过 2 s 为 stale；流新鲜度与 GATT connected 分开 | [CUPStreamFreshnessTracker.swift](../reference_sources/ios_current/PPGCollector/Infrastructure/Bluetooth/CUPStreamFreshnessTracker.swift) | 虚拟时钟测试 waiting/fresh/stale；stale 录制安全停止 |
| PROTO-001 | P0 | 当前接收 168-byte CUP：header、function、LE length=161、seq、20×RED LE UInt32 后接 20×IR LE UInt32、tail；历史 408-byte interleaved raw 可回放 | 用户提供的新硬件协议、[ADR-0003](adr/ADR-0003-cup-168-byte-planar-wire-protocol.md)、历史 [CUPBatchProtocol.swift](../reference_sources/ios_current/PPGCollector/Infrastructure/Protocol/CUPBatchProtocol.swift) | 当前 168-byte golden byte-for-byte/number-for-number；历史 408-byte fixture 仍可解码/回放且 profile 可追溯 |
| PROTO-002 | P0 | stream decoder 处理任意拆包、粘包、前导噪声、坏 length/tail 并 resync | [CUPBatchStreamDecoder.swift](../reference_sources/ios_current/PPGCollector/Infrastructure/Protocol/CUPBatchStreamDecoder.swift) | 逐字节、随机 chunk、多帧、噪声、截尾 property tests |
| PROTO-003 | P0 | 首帧/连续接受；gap 后帧接受并计 missing；duplicate/out-of-order 拒绝进入 accepted sample | [CUPFrameSequenceTracker.swift](../reference_sources/ios_current/PPGCollector/Infrastructure/Protocol/CUPFrameSequenceTracker.swift) | 0/255 wrap、gap、duplicate、out-of-order 矩阵全绿 |
| PROTO-004 | P0 | 最近样本最多 800；诊断包含 chunks/bytes/decoded/accepted/invalid/discarded/sequence/pending | [CUPStreamingPipeline.swift](../reference_sources/ios_current/PPGCollector/Infrastructure/Protocol/CUPStreamingPipeline.swift) | 2 h 模拟流内存不随时长增长；计数与输入严格一致 |

## 3. 录制、文件与恢复

| ID | P | Android 要求 | 依据 | 验收 |
|---|---:|---|---|---|
| CAP-001 | P0 | raw-first：每个原始 BLE notification record 成功追加后才解码/写 CSV | [CaptureSessionWriter.swift](../reference_sources/ios_current/PPGCollector/Infrastructure/Storage/CaptureSessionWriter.swift)、[live_gui.py](../reference_sources/python_gui/entry_points/live_gui.py) | 注入 CSV/decoder 故障仍保留所有已 ack raw；顺序测试 |
| CAP-002 | P0 | 单一有界队列建议 256；溢出不能静默丢数据，必须安全停止为 incomplete | [CaptureSessionController.swift](../reference_sources/ios_current/PPGCollector/Features/LiveCapture/CaptureSessionController.swift) | 慢盘/突发注入触发确定 stop reason；没有无提示丢 chunk |
| CAP-003 | P0 | raw 文件为 `CUPRAW1\0` + 多条 `<u64 hostNs><u32 length><payload>` little-endian | [CUPRawFileReader.swift](../reference_sources/ios_current/PPGCollector/Infrastructure/Storage/CUPRawFileReader.swift)、[raw_format.py](../reference_sources/python_gui/ppg_monitor/raw_format.py) | Kotlin/iOS reader 交叉读取；截尾停在最后完整 record |
| CAP-004 | P0 | CSV 固定列顺序、相对 device time、指标源时间/有效性/版本；invalid SQI 写 `0,false`，其他无效指标空值/false | [CaptureCSVSchema.swift](../reference_sources/ios_current/PPGCollector/Domain/Models/CaptureCSVSchema.swift)、[CaptureSessionWriter.swift](../reference_sources/ios_current/PPGCollector/Infrastructure/Storage/CaptureSessionWriter.swift) | header golden；第 800 行首次指标时间 `7.990000`；行数=accepted samples |
| CAP-005 | P0 | session JSON snake_case，录制开始固化 soft/alg/preprocess/protocol；进行中 `complete=false`，结束后同步更新 | [CaptureSessionMetadata.swift](../reference_sources/ios_current/PPGCollector/Domain/Models/CaptureSessionMetadata.swift) | schema round-trip；版本在 app 更新模拟中不漂移 |
| CAP-006 | P0 | 每秒 flush+fsync raw/CSV/metadata；低空间预检建议至少 20 MiB，并在写失败时停止 | [CaptureSessionWriter.swift](../reference_sources/ios_current/PPGCollector/Infrastructure/Storage/CaptureSessionWriter.swift) | fake filesystem/满盘/权限错误测试；已确认写入前缀可读 |
| CAP-007 | P0 | 第一个停止原因获胜；user、navigation policy、disconnect、timeout、service stop、write/protocol/resource、crashRecovery 共用幂等 finalizer | [CaptureModels.swift](../reference_sources/ios_current/PPGCollector/Domain/Models/CaptureModels.swift)、[CaptureSessionController.swift](../reference_sources/ios_current/PPGCollector/Features/LiveCapture/CaptureSessionController.swift) | 并发发送多 stop reason 只 close 一次且 reason 确定 |
| CAP-008 | P0 | 会话目录不可覆盖；写入 staging 或 incomplete 元数据，完成/恢复使用原子 rename；恢复不改源文件 | [CaptureSessionRecoveryService.swift](../reference_sources/ios_current/PPGCollector/Infrastructure/Storage/CaptureSessionRecoveryService.swift) | 重名拒绝；损坏/截尾恢复只复制完整 raw/CSV 前缀并留 provenance |
| CAP-009 | P0 | 检查器流式核对 raw/CSV/metadata 的帧、样本、行数、hash、结构和峰值 buffer | [CaptureSessionInspectionService.swift](../reference_sources/ios_current/PPGCollector/Infrastructure/Storage/CaptureSessionInspectionService.swift) | 2 h 会话检查不整文件载入；坏 magic/length/CSV 尾/metadata 被分类 |
| CAP-010 | P0 | 内部存储为真源；Room 如使用只能做可重建索引；导出经 SAF，分享经 FileProvider | [Android app-specific storage](https://developer.android.com/training/data-storage/app-specific) | 清空索引仍恢复列表；无 storage runtime permission；导出后外部文件可读 |
| CAP-011 | P1 | 分析输出在 `analysis/` 下按算法/profile/version/时间生成，不覆盖旧结果，可取消 | [CaptureSessionAnalysisService.swift](../reference_sources/ios_current/PPGCollector/Infrastructure/Storage/CaptureSessionAnalysisService.swift) | 同会话多版本并存；取消不留下冒充 complete 的结果 |

## 4. 信号算法

| ID | P | Android 要求 | 依据 | 验收 |
|---|---:|---|---|---|
| SIG-001 | P0 | 100 Hz 因果预处理，固定 SciPy 1.17.1 Butterworth 3 阶 0.6–4 Hz SOS、DC、IR invert、z-score；gap reset | [PPGPreprocessor.swift](../reference_sources/ios_current/PPGCollector/SignalProcessing/Preprocessing/PPGPreprocessor.swift) | [preprocessing_vectors.json](../reference_sources/signal_fixtures/preprocessing/preprocessing_vectors.json)；系数 `1e-15`、主要输出 `1e-8` 容差 |
| SIG-002 | P0 | live window=800、cadence=100；gap/new generation 后重新预热，旧计算结果丢弃 | [PPGLiveMetricRuntime.swift](../reference_sources/ios_current/PPGCollector/SignalProcessing/Runtime/PPGLiveMetricRuntime.swift) | 连续/分块/gap/取消/慢分析测试；首次窗口 end=799 |
| SIG-003 | P0 | HR 保持 robust scale、Hann/DFT、35–200 bpm、双极性、peak 语义、RR regularity、confidence | [HeartRateEstimator.swift](../reference_sources/ios_current/PPGCollector/SignalProcessing/HeartRate/HeartRateEstimator.swift)、[pulse.py](../reference_sources/python_gui/ppg_monitor/pulse.py) | [heart_rate_vectors.json](../reference_sources/signal_fixtures/heart_rate/heart_rate_vectors.json)；既有字段按 `1e-10` 级容差 |
| SIG-004 | P0 | peak detector 复刻 SciPy plateau midpoint、distance pruning、prominence、half-prominence width | [SciPyPeakDetector.swift](../reference_sources/ios_current/PPGCollector/SignalProcessing/Shared/SciPyPeakDetector.swift) | plateau、等高峰、边界、distance 冲突、prominence/width 向量 |
| SIG-005 | P0 | SQI 按周期对齐+均值模板+Pearson；分数 clamp 0–1、阈值 0.9/0.7、provisional=true | [TemplateMatchSQI.swift](../reference_sources/ios_current/PPGCollector/SignalProcessing/SQI/TemplateMatchSQI.swift)、[sqi_template_match.py](../reference_sources/product_requirements/sqi_template_match.py) | [sqi_vectors.json](../reference_sources/signal_fixtures/sqi/sqi_vectors.json)，主要输出 `1e-10` 级容差；invalid=0,false |
| SIG-006 | P0 | RED/IR ratio-of-ratios 仅诊断；需要 ≥400 样本、边缘 trim、RMS AC/mean DC | [RatioOfRatiosEstimator.swift](../reference_sources/ios_current/PPGCollector/SignalProcessing/RatioOfRatiosEstimator.swift) | 单测通过；任何 UI/CSV 不把 ratio 标成 SpO2 |
| SIG-007 | P1 | 离线稳定段：settling/transition guard、8 s window/2 s hop、通道评分、拒绝原因、dominant BPM cluster | [segmented_pulse.py](../reference_sources/python_gui/ppg_monitor/segmented_pulse.py) | 对 Python tests/固定会话输出对等；算法 version 与 live 分开 |
| SIG-008 | P1 | 离线平均周期/CI、频谱、峰标记、窗口审计、两个会话对比 | [offline.py](../reference_sources/python_gui/ppg_monitor/offline.py)、[analysis_gui.py](../reference_sources/python_gui/ppg_monitor/analysis_gui.py) | 固定输入的峰数、选段、均值周期、时间轴和导出字段可复现 |

## 5. 可靠性、安全与发布

| ID | P | Android 要求 | 验收 |
|---|---:|---|---|
| REL-001 | P0 | 30 min 流、2 h 录制/写入/重放/CSV 对齐且内存有界 | [LongDurationDataPathTests.swift](../reference_sources/ios_current/PPGCollectorTests/Integration/LongDurationDataPathTests.swift) | 模拟门禁分别 <60 s/<240 s（CI 能力不足可标 slow nightly）；真实 2 h 另测 |
| REL-002 | P0 | 20 次 scan/connect/subscribe/disconnect 循环无资源泄漏、旧回调、重复收集者 | 当前 Swift BLE integration tests | 真机脚本 + fake GATT；每轮 generation、receiver、service 状态归零 |
| REL-003 | P0 | 旋转、进后台、进程/Activity 重建不创建第二 writer；前台服务为录制单一所有者 | Android 生命周期差异 | instrumentation + `adb` 生命周期/进程测试；会话目录唯一 |
| REL-004 | P0 | 蓝牙关闭、权限撤销、设备断电、2 s 无数据、满盘、服务停止均安全完成/标 incomplete | 产品停止条件与 Swift lifecycle tests | 故障注入矩阵每项验证 reason、文件可读性、恢复结果 |
| REL-005 | P0 | 日志不打印原始 PPG、完整设备地址或路径；release 不含 debug/export 测试数据 | 隐私与发布要求 | 静态检查、release 包检查、日志审计 |
| REL-006 | P0 | target API 37，API 26/30/31/33/34/35/36/37 测试，目标厂商至少两类；有更高 API 时做前向测试 | [Play target API](https://developer.android.com/google/play/requirements/target-sdk) | CI emulator matrix + 目标真机报告；已知电池优化行为记录 |
| REL-007 | P0 | 前台服务类型、权限与可见启动满足 API 34+；通知拒绝也有可解释状态 | [FGS 类型](https://developer.android.com/develop/background-work/services/fgs/service-types)、[FGS 启动](https://developer.android.com/develop/background-work/services/fgs/launch) | API 34/36 测试无 `ForegroundServiceStartNotAllowedException`/SecurityException |

## 6. 对等裁决方法

每个差异在 issue/ADR 中归入一种：

1. **缺陷**：Android 输出违反上述 P0 契约，必须修复。
2. **浮点差异**：在 fixture 规定容差内接受；不得通过大幅放宽容差掩盖算法变化。
3. **平台差异**：例如 Android 权限/前台服务。记录原因，并用平台特定验收替换 iOS 生命周期断言。
4. **有意产品变更**：必须有产品确认、schema/algorithm version 评估和对两个平台兼容性的说明。
5. **来源歧义**：用真实设备/固件抓取或新的 golden fixture 解决，不能靠猜测。
