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

没有 combo SQI、定时录制、ECG CSV 的测试。
