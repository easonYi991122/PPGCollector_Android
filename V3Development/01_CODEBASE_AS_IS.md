# 当前代码事实（以 app/src 为准）

阅读日期：2026-08-19。本文件只记录当时 Kotlin 实现，不引用旧移植状态文档。

包名：`com.example.ppgcollector_android`  
主代码：`app/src/main/java/com/example/ppgcollector_android/`

---

## 1. 应用结构

| 层 | 主要类型 | 职责 |
|---|---|---|
| UI | `MainActivity`, `LiveCaptureScreen`, `SavedSessionsRoute`, `SessionsScreens`, `SubjectArchiveScreen`, `SessionSignalWorkbench` | Compose 采集页 / 已保存会话 / 档案 / 回放 |
| 录制绑定 | `CaptureServiceViewModel`, `CaptureForegroundService`, `CaptureServiceClient` | Activity 绑定；前台服务持有录制 |
| BLE | `BleCoordinator`, `CupBleGattStateMachine`, `AndroidBleTransport`, `FakeBleTransport` | 扫描、单连接状态机、notify 入队 |
| 协议 | `CupBatchProtocol`, `CupSensorPacketProtocol`, `CupBatchStreamDecoder`, `CupFrameSequenceTracker` | 168-byte 两种 PPG 帧 |
| 信号 | `LivePpgSignalRuntime`, `LiveMetricRuntime`, `TemplateMatchSqi`, `HeartRateEstimator`, `PpgPreprocessing` | 波形 5 Hz 发布；指标 1 Hz；SQI 为模板匹配数值 |
| 存储 | `CaptureRecordingController`, `CaptureSessionWriter`, `CaptureCsv`, `CaptureSessionMetadata`, `SubjectProfileStore` | 会话目录、sidecar、被试档案 |

没有独立的「设备类型」领域模型。腕部 / CUP / ECG 这些词在 `app/src/main/java` 中不存在。

---

## 2. 设备连接（实际行为）

`CupBleDeviceProfile.supportedBringUpProfiles` 只有两条：

| profile id | 广播名 | GATT | 解码模式 |
|---|---|---|---|
| `cup-nus-bringup-0.1` | 前缀 `CUP`，或精确名 `Nordic_UART_Service` | NUS `6E400001-…` notify `6E400003-…` | CUP → `BATCH_COMPATIBLE`；Nordic → `SENSOR_PACKET_168` |
| `cup-fff0-bringup-0.1` | 前缀 `CUP` | FFF0 / FFF1 | `BATCH_COMPATIBLE` |

扫描：无 UUID 过滤，10 秒超时，按广播名匹配后才进列表。UI 文案是「正在查找名称以 CUP 开头的设备…」，但 `Nordic_UART_Service` 同样会被 NUS profile 收下。

连接：`CupBleGattStateMachine` 同时只允许一个 `activeDeviceId`；另一台设备的连接按钮在 UI 上禁用。被动订阅 notify，不写控制特征。

新鲜度：`CupStreamFreshnessTracker` 超时 2 秒；只有**被解码接受的 PPG 帧**推进 `FRESH`。

结论：底层已是单连接互斥。代码没有「两类仪器」的**产品/命名**概念，也没有按仪器自动选 `PPG-` / `MB-` 前缀。线协议则已经因设备而异（见第 3 节）：广播名决定 CUP batch vs Nordic sensor，只是还分不出未来的 ECG 120。

---

## 3. 线协议（实际帧）

共用帧头 `AB BA`、帧尾 `CD DC`，采样率常量 100 Hz。

**CUP batch 168**（`CupBatchProtocolV1`）：func / dataLen / **u8 seq** / RED×20 / IR×20。另支持历史 408-byte 回放，以及若干 8-byte 辅助帧跳过。

**Nordic sensor packet 168**（`CupSensorPacketProtocolV1`）：无 func/len，**u32 LE seq** / RED×20 / IR×20。注释写明来自 `Nordic_UART_Service`。

解码：`CupBatchStreamDecoder` 按 `CupStreamProtocolMode` 切帧；重复、乱序拒绝；gap 记 missing。

**不存在**：120-byte 帧、ECG 通道、`ads1292r`、每 40 ms 一帧的 4 样本 PPG。Nordic 路径当前与 CUP 一样是 20 样本/帧的 PPG-only 168-byte。

协议选择已经是因设备而异：`CupBleDeviceProfile.streamProtocolModeForAdvertisedName` 在 `connect()` 时写入 `activeStreamProtocolMode`，本 connection generation 不再改。两种 168 帧总长相同，所以 CUP batch 与 Nordic sensor **不能**靠 payload 互认，只能靠广播名。代码与需求都没有第三种广播名或 UUID 能把即将加入的 120-byte ECG 从 `Nordic_UART_Service` 上区分出来——R4 不能沿用「Nordic → 固定一种帧」这条规则。

---

## 4. 实时波形与指标

`LivePpgSignalRuntime`：环形缓冲 800 点（8 s @ 100 Hz），约 5 Hz 发布。通道只有 RED / IR（含 causal / fixed-lag 显示派生）。无 ECG。

显示模式（`LiveWaveformDisplayMode`）：`RAW` / `CAUSAL` / `FIXED_LAG`。RAW 仅绘图取负；CAUSAL / FIXED 为 0.5–12 Hz 显示滤波。落盘仍是原始 ADC。

`LiveMetricRuntimeProfile.iosBaseline01`：窗 800 点，每 100 点（1 s）一帧。指标预处理 `PpgPreprocessingProfile.iosBaseline01`：0.6–4.0 Hz Butterworth，SQI 输入再取负并标准化。

| UI 名 | 实现 | 产品状态 |
|---|---|---|
| HR | `HeartRateEstimator` | 实时，质量门控 |
| RR | `RatioOfRatiosEstimator` | 暂定诊断比值 |
| PI | 同一次 RED AC/DC | 暂定 |
| SQI | `TemplateMatchSqi`，good=0.90 / fair=0.70 | **数值**显示；`isProvisional=true`；grade 颜色字段存在但采集页不用 |
| BP（预测） | 恒 `MODEL_UNAVAILABLE` | 显示 — |
| BP（人工） | 见第 6 节 | 仅录制中弹窗 |

没有 flat / 自相关 SQI_corr / 压力 4 闸门 / 综合 SQI 状态机 / 指标帧去抖。

CSV `sqi` 列来自上述模板匹配，schema `ppgcollector_samples_v1/v2`，列集合见 `CaptureCsvSchema.columns`。

---

## 5. 命名与唯一性

`SessionNamePolicy`：

- 语法：`[A-Za-z0-9_-]{1,64}`，排除 Windows 保留名。自由命名允许，**不强制** `PPG-`。
- Canonical 正则**只认** `PPG-{subject}-{seq}`（大小写不敏感）。`normalizeCanonical` 永远写成 `PPG-`。
- 重名：会话根目录下目录名大小写不敏感相等。没有 `(prefix, subject, seq)` 逻辑主键。
- 建议名：最近一份 `rawChunkCount > 0` 的 canonical 会话，按同一 subject 取 max(seq)+1，前缀固定 `PPG-`。空目录不占序号。

元数据：`base_name`, `canonical_subject_id`, `canonical_sequence`。没有设备类型字段，也没有 `MB-`。

---

## 6. 被试资料与血压

`CaptureParticipantDraft` / `SubjectProfile` 字段：`sex`（自由文本）、`age_years`、`height_cm`、`weight_kg`、`additional_fields`（UI 只暴露 `notes`）。

档案文件：`subjects/{subject}.profile.json`，schema `ppgcollector_subject_profile_v1`，带 revision。canonical 录制开始时 `saveRevision`。

`isComplete`：性别非空 + 年龄 0–150 + 身高/体重 > 0。**完整与否不进入开始录制门控。**

血压：

- 仅录制中 `RecordingActionBar` 的「血压记录」打开 `ManualBloodPressureDialog`。
- 写入 `{baseName}.blood-pressure.csv`（`ppgcollector_manual_bp_v1`），按弹窗打开时的 PPG sample index 打时间戳，可多条。
- 录制前表单无 sbp/dbp。
- 会话详情「参考血压」只读；被试资料文案写「录制时的不可变 snapshot」。

没有 `smoking_freq` / `drinking_freq` / `gender_code` / 会话级 `sbp`/`dbp` JSON 键。

---

## 7. 开始录制门控

`CaptureStartGate.validate` **短路返回第一条**失败：

1. 已在录制
2. 名称语法非法（不是 canonical 格式、也不查逻辑序号）
3. 数据流不是 `FRESH`
4. BLE phase 不是 `Subscribed` / `Receiving`
5. 目录名已存在
6. 可用空间 &lt; 20 MiB

ViewModel 另加通知权限、FGS 启动失败。UI：`开始录制` 的 `enabled = captureGate.canStart`，下方**一句** `开始条件：{message}`。

不检查：被试必填项、血压、`(subject,seq)` 逻辑重复。

---

## 8. 录制控制

只有手动开始 / 手动停止（按钮文案「停止并保存」）。`CaptureStopReason` 有 user / disconnect / timeout / crash 等，**没有时长到时**。

没有录制模式、没有 `planned_duration_s`、停止键上没有倒计时。

停止流程：排空队列 → 写完文件与 metadata → 完成。断连等异常走同一 finalize，`record` 时间按实际。

---

## 9. 会话文件（实际契约）

会话目录 `{sessionsRoot}/{baseName}/`：

| 文件 | 必需 | 说明 |
|---|---|---|
| `{baseName}.cupraw` | 是 | 原始 notify 块 |
| `{baseName}.csv` | 是 | 100 Hz PPG 宽表 |
| `{baseName}.session.json` | 是 | 需求文档中的 `{stem}.json` 在代码里是这个名字 |
| `{baseName}.metrics.csv` | 可选 | 1 Hz 指标 epoch |
| `{baseName}.blood-pressure.csv` | 可选 | 录中人工血压事件 |

没有 `{baseName}_ecg.csv`。`CaptureSessionFilesMetadata` 只有 raw / samples / metrics / bloodPressure。

导出 ZIP 按上述清单打包。档案分组：能 parse 成 `PPG-{subject}-{seq}` 的进 `subjects/{subject}/`，否则 `unclassified/`。没有按 `PPG`/`MB` 再分子目录。

---

## 10. 回看与档案 UI

- 已保存会话：档案视图（按 subject）与扁平文件列表。
- 详情：overview / source / participant / blood pressure / integrity / replay / analysis / export；默认折叠（`SessionDetailUiPolicy`）。
- 被试资料只读 snapshot。血压只读事件列表。
- 离线分析与波形工作台存在（`SessionSignalWorkbench`、`OfflinePpgAnalysis`），与 V3.0 增量无直接对应，R1–R5 不作为主改面，除非文件清单变化迫使导出/恢复跟着改。

---

## 11. 测试存量（与 V3.0 相关的）

已有且后续会改到的测试：

- `SessionNamePolicyTest`
- `CaptureGateUiStateTest`
- `SubjectProfileTest`
- `CaptureSessionMetadataTest`
- `CaptureRecordingControllerTest`
- `CaptureArchiveExportServiceTest` / `SubjectArchiveRepositoryTest`
- `CupSensorPacketProtocolTest` / `NordicSensorDeviceCompatibilityTest`
- `TemplateMatchSqiTest` / `LiveMetricRuntimeTest`

---

## R6 实际实现补充（2026-08-20）

上面的第 1–11 节是 R5.1 之前的基线描述；R6 已将以下事实落实到 `app/src`：

- Android `AndroidBleTransport` 的回调、扫描和 GATT 操作使用 Application 创建的 `ppg-ble-transport` `HandlerThread`；`BleCoordinator` 用 owner lock 串行化平台事件和 Activity 控制调用。`ownerDispatcher` 不再把 notify/preview 工作投到 Main。
- `BlePreviewRuntime` 不在构造时启动线程，而是在首个 chunk 到达时启动有界 preview/analysis worker；`suspend/close` 清队列并停止 clock tick。`diagnostics()` 只供 fake/JVM 断言，不进入 Compose 根状态。
- ADS 120 的录制和 preview 共用 `CupFrameSequenceTracker` 的 duplicate/out-of-order 决策；accepted packet 原子地产生 PPG×4、ECG×20，gap 只重置算法连续性 epoch，显示 ring 保留历史并以共同 segment 语义交给 UI。
- FGS 的 writer 创建与容量查询运行在 `Dispatchers.IO`；录制开始接受后才异步创建 subject profile revision。健康轮询在 generation/active phase 改变或 stale grace 超时后以明确 stop reason finalize。
- 门控/建议名/subject profile 读取与大 sidecar 检查不再由 Compose/Main 直接执行；metrics、manual BP、ECG scan 使用流式、有界读取。`CaptureSessionInspection` 对可选 ECG 的 header、session id、时间/样本单调性和 ADS 行数做一致性检查。
- `SessionNamePrefix` 已进入 canonical identity；档案 UI 在同一 subject 下分出 PPG/MB 小节，metadata identity 会保留实际 prefix。参考 BP 的 `SBP > DBP` 约束在表单、dialog、metadata editor 和 writer 入口均有校验。
- `LiveCaptureScreen` 的记录表单拆为 identity、participant、reference、start 四个稳定 key 的 LazyColumn item；录制 snapshot 进度最多约 5 Hz。历史离线 PPG/BP 回归和 Combo pressure height 恒定值问题已修复。

仍未由 JVM/fake 覆盖的是真实设备、后台 30 s 宏基准和长时间真实 BLE 流；这些保持“待测”，不视为已验收。

没有 combo SQI、定时录制、ECG CSV 的测试。

## R7 实际实现补充（2026-08-20）

- 空闲 BLE 侧仍是 0 preview worker / 0 clock tick；采集页在 RED/IR 为空时只组合轻量等待内容，不再创建波形 Canvas、滤波选择器和指标网格。记录区把设备/模式与名称拆为两个 lazy item；首次建议名、profile 和 gate disk snapshot 合并为一次 I/O bootstrap。
- Activity 空闲恢复只尝试绑定已存在的录制 service（flags=0），不再用 `BIND_AUTO_CREATE` 为 IDLE 状态创建 service；用户真正开始录制后才启动并 auto-create bind，已启动 FGS 的 Activity 重建仍可重新绑定。
- 实时 RAW 使用稳定的 `liveRawPeakUp`（仅取负），不再对增长/滑动窗口反复线性拟合；`rawPeakUpForPlot` 仅保留给离线有限视窗。
- 普通 sequence gap 会重置指标预处理、HR/SQI/Combo warmup并保留 segment marker，但不会清空 display causal/fixed 的有界显示状态。FIXED 在初始右侧上下文 warmup 时可先选择，准备完成自动切换；fixed 的断点按其延迟后的 source cursor 映射。
- ECG wire/CSV 继续保留需求定义的原始 uint32。显示新增有状态 5:1 boxcar（500 Hz 每五点均值到 100 Hz），跨 BLE chunk 保持相位；显示点不会超出对应五个 raw 输入的最小/最大值。若同一时间的 `_ecg.csv` 已含尖峰，应转查开发板/ADS1292R 前端。
- R7 完整门禁为 204 JVM tests、0 failure/skip，lint 0 error；Debug、AndroidTest、Release 与 release privacy/lifecycle/BLE contracts 均成功。真实设备上的帧时序、主观滚动流畅度和 ECG CSV 对点仍待测。
