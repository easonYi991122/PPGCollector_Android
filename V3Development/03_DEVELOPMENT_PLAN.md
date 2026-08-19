# V3.0 开发方案

原约束：编码工作不超过五轮（`V3.R1` … `V3.R5`）。  
**V3.R4.1** 是在 R4 验收缺口、ECG 实时显示、采集页卡顿、以及 R1–R3 口径偏差确认之后，额外插入的一轮（仍在 R5 之前）。每轮交付可 JVM 验收的增量。需求依据 [`02_REQUIREMENTS_REBASED.md`](02_REQUIREMENTS_REBASED.md)，代码基线见 [`01_CODEBASE_AS_IS.md`](01_CODEBASE_AS_IS.md)。

轮次 ID：`V3.R1` … `V3.R4`、`V3.R4.1`、`V3.R5`。

---

## 总览

```text
R1 表单 / 命名 / 门控 / 录前血压字段
R2 定时录制与停止键倒计时
R3 综合 SQI 显示状态机（对齐 combo_sqi.py）
R4 腕部 ads1292r + 保留既有两条 168 直播协议
R4.1 收口 R4 直播/文件缺口 + ECG 实时可视化 + 采集页卡顿 + R1–R3 口径修正
R5 回看改血压、档案/导出、兼容收口
```

| 轮 | 主题 | 主风险 | 可独立演示 |
|---|---|---|---|
| R1 | 采集前信息架构 | 命名唯一性与旧 PPG-only 会话并存 | 选 MB 前缀可与 PPG 同 subject/seq 共存；门控逐项列出；录前填血压、录中无弹窗 |
| R2 | 录制时长 | 倒计时必须跟样本时钟 | 定时 60 s 按接受样本自动停；断流时倒计时停 |
| R3 | 综合 SQI | 口径必须跟 Python，且不改 CSV `sqi` | 标签出现绿/橙/红/灰文案；去抖在 1 s 帧上 |
| R4 | ECG 协议 | 三条直播协议隔离；Nordic 120/168 无类型码 | fake 120 显示 ECG 并落 `_ecg.csv`；同身份 fake 168 仍按 sensor packet 采、无 ECG 文件；CUP 回归仍绿 |
| R4.1 | 直播收口 / ECG 显示 / 卡顿 | 120 预览未切解码器则永远非 FRESH；显示缓冲不得改落盘 | Nordic 120 可开始录制；采集页 RED/IR/ECG 并列；空闲连接不再整页 25 Hz 重组 |
| R5 | 回看与导出 | 旧会话缺新键 | 详情改 sbp/dbp；ZIP 含 ECG；档案分 PPG/MB |

依赖：R2 依赖 R1 的 metadata 扩展习惯（同一 codec）。R3 不依赖 R2。R4 不依赖 R3。**R4.1 依赖 R4 已落地的协议/writer 骨架，必须在 R5 之前完成**（R5 导出/恢复要用完整的 `SessionFileSet.ecg`）。R5 依赖 R1 字段 + R4/R4.1 文件清单。

**当前待执行轮次：`V3.R4.1`。** 不要从 R5 开工；R4 本章验收项未闭环。

---

## V3.R1 采集前信息：命名空间、被试字段、门控、录前血压

### 目标

把「叫什么、谁、能不能按开始」改到 V3.0 口径。不改 BLE 协议，不加定时器，不上综合 SQI。

### 子任务

1. **命名策略**  
   - `CanonicalSessionIdentity` 增加 `prefix: PPG | MB`。  
   - `parseCanonical` / `normalizeCanonical` / `suggestedBaseNameForSubject` 按前缀分命名空间。  
   - 新增 `isLogicalDuplicate(prefix, subject, seq)`。  
   - 上次前缀写入简单 store（例如 DataStore/SharedPreferences）。

2. **表单 UI**（`LiveCaptureScreen.CaptureSetupCard`）  
   - 设备类型二选一（指尖 PPG / 腕部脉搏）→ 改建议名前缀。  
   - 性别 Radio：男 / 女。  
   - 吸烟频率、饮酒频率：可空下拉/单选。  
   - sbp / dbp 选填，校验失败红字，不进门控。  
   - 名称失焦：文件名重复 / 次数重复分开展示。

3. **门控**  
   - `CaptureStartGate.validate` 改为返回 `List<CaptureStartFailure>`（或新 `CaptureStartReport`）。  
   - 增加 `ParticipantIncomplete`（列出缺哪几项）。  
   - `CaptureGateUiState`：`canStart`、`blockingReasons: List<String>`；UI 逐项列出，全过则「✓ 可以开始录制」。  
   - 明确文案：无数据时必须出现「有数据读入后方可开始录制」语义。

4. **落盘**  
   - `CaptureParticipantSnapshot`：`smokingFreq`, `drinkingFreq`, `genderCode`。  
   - `CaptureSessionMetadata`：`sbp`, `dbp`（Int?）。录制开始时写入，录中不再提供血压入口。  
   - `SubjectProfileRevision` 同步吸烟/饮酒/性别约束。  
   - 去掉 `RecordingActionBar` 的「血压记录」；若已填血压则只读展示。  
   - `ManualBloodPressureDialog` 本轮可留文件但采集页不再调用（R5 回看可复用校验逻辑，抽成共享 validator）。

5. **测试**  
   - 扩展 `SessionNamePolicyTest`：MB 建议序号、PPG/MB 逻辑共存、文件名仍全局唯一。  
   - 扩展 `CaptureGateUiStateTest` / 新 `CaptureStartGateTest`：多原因并列、血压缺失不拦、性别空拦截。  
   - `SubjectProfileTest` / `CaptureSessionMetadataTest`：新键编解码、缺键旧 JSON 仍能 decode。

### 主改文件

- `data/session/SessionNamePolicy.kt`
- `data/session/CaptureSetupModels.kt`
- `data/session/SubjectProfile.kt`
- `data/session/CaptureSessionMetadata.kt`
- `data/session/CaptureStartGate.kt`
- `CaptureServiceViewModel.kt`
- `LiveCaptureScreen.kt`
- `CaptureUiPolicy.kt` 或新建 `CaptureGateUiPolicy.kt`（纯函数生成提示列表，便于测）
- 对应 test

### 验收

- 已有 `PPG-S001-1` 时选 MB 建议 `MB-S001-1` 且可开始（其它条件满足）。  
- 再录一个 `MB-S001-1` 被次数/文件名拦住。  
- 未连或未出波形时按钮禁用且列表含数据读入原因。  
- 开始后无血压弹窗；session.json 有 sbp/dbp 或 null。

### 本轮不做

定时录制、SQI 颜色、ECG、会话详情写回、导出目录结构。

---

## V3.R2 录制模式：定时录制 / 手动录制

### 目标

录前选模式；定时到样本时长自动停止；停止键显示倒计时或「手动停止」。

### 子任务

1. **领域模型**  
   - `enum class CaptureRecordMode { TIMED, MANUAL }`  
   - `plannedDurationSeconds: Int?`（TIMED: 10–3600，默认 60；MANUAL: null）  
   - 非法输入即时红字并回退默认 60。  
   - 开始时写入 configuration，录中不可改。

2. **控制器**  
   - `CaptureRecordingController` 在每次接受 PPG 样本后计算 `acceptedDurationS = acceptedSampleCount / sampleRateHz`。  
   - TIMED 且 `acceptedDurationS >= planned` → `stop(CaptureStopReason.DURATION_ELAPSED)`。  
   - 新鲜度为非 FRESH 时不累加（样本本就不会 accepted）；倒计时 UI 用已接受时长，断流自然暂停。  
   - `STOPPING` / finalize 期间 `CaptureStartGate` 仍视为不可开始。

3. **UI**  
   - 录前：模式选择 + 定时时长输入 + 开始。允许两个按钮「定时录制」「录制」，或开关 + 一个开始键，选一种并在测试里固定。推荐：**模式开关 + 现有开始键**，避免两套门控。  
   - 录中停止键：TIMED `停止 (剩 m:ss)`；MANUAL `手动停止`。  
   - 通知栏停止文案可同步，非必须。

4. **落盘**  
   - `planned_duration_s`：number 或 JSON null。  
   - `stop_reason` 增加 `durationElapsed`（wire value 自定并写进 codec 测试）。

5. **测试**  
   - `CaptureRecordingControllerTest`：用 fake 帧喂精确样本数，100 Hz × 10 s → 自动停；中途停喂则不提前停。  
   - 时长边界 10 / 3600。  
   - metadata round-trip。

### 主改文件

- `CaptureRecordingController.kt`
- `CaptureSessionMetadata.kt`（stop reason + planned duration）
- `CaptureForegroundService.kt` / `CaptureServiceViewModel.kt`（把 mode 传入 configuration）
- `LiveCaptureScreen.kt`
- 新 `CaptureRecordModePolicy.kt`（校验时长）
- 对应 test

### 验收

- 定时 10 s，100 Hz 接受满 1000 点后进入 STOPPING/FINALIZED。  
- 墙钟等待 10 s 但只接受 200 点时不停止。  
- 手动模式 `planned_duration_s` 为 null，停止键为「手动停止」。

### 本轮不做

ECG、SQI、导出结构。

---

## V3.R3 综合 SQI（显示层，对齐 combo_sqi.py）

### 目标

采集页用单一「综合 SQI」标签替代当前纯数值 SQI 磁贴的**展示**。判定在 1 s 指标帧上跑，去抖不在 Compose。CSV `sqi` 仍走 `TemplateMatchSqi`。

### 子任务

1. **移植算法（只读 Python）**  
   参考 `references/需求V3.0/code/`：  
   - `complete_preprocessing_pipeline.py` → 滑动平均 2/2/10 + 0.5–12 Hz order=2  
   - `sqi_calculator.py` / `ppg_metrics.py` → SQI_tm、SQI_corr、flat  
   - `overpressure_detect.py` → p2_height / init_steep / sev / n_beats  
   - `combo_sqi.py` → `arbitrate_combo`、`_pressure_severity`、常量表、去抖器  

   Kotlin 建议包：`core/signal/combo/`，入口 `ComboSqi.evaluate(rawIr, fs)` 与 `ComboSqiDebounce`。常量与 Python 同名同值。

2. **运行时挂接**  
   - 使用 **原始 IR** 最近 5 s（500 点），每 1 s 一次。可从 `LiveMetricWindowScheduler` 已有 `rawIr` 切片，或独立 500 点缓冲。不要把综合 SQI 喂进现有 0.6–4 Hz 标准化信号。  
   - 断连 / generation 递增 / gap 重置 debounce → `unknown`。  
   - 结果：`state, text, colorHex, score` 发布到 UI；**不要**写进 `CaptureCsvRow.signalQuality`。

3. **UI**  
   - 采集页 SQI 磁贴改为综合标签（颜色背景或色条 + 提示文本 + 分数）。  
   - 热身：灰 `综合SQI: --`。  
   - `liveMetricCompactOrder` 可仍含 SQI，语义变为综合 SQI。

4. **测试**  
   - 用 Python `combo_sqi.py` 自测三类合成波做跨语言对照（干净 → good/unstable；平直 → flat）。  
   - 阈值表：p2h 闸门、flat 豁免（n_beats + corr）、debounce 2 帧。  
   - 回归：`TemplateMatchSqiTest`、`CaptureCsvTest` 的 sqi 列不变。

### 主改文件

- 新建 `core/signal/combo/*`
- `LiveMetricRuntime.kt` / `LivePpgSignalRuntime.kt`（挂 5 s 原始 IR）
- `LiveMetricModels.kt`（增加 combo 结果，保留旧 sqi 数值给 CSV）
- `LiveWaveformComponents.kt` / `LiveCaptureScreen.kt`
- 新 unit tests

### 验收

- 合成平直 IR → 红「信号平直」。  
- 连续两帧才切换状态。  
- 同一段录制 CSV 的 `sqi` 数值仍由旧算法产生。  
- 无 ECG 依赖。

### 本轮不做

`sqi_state` CSV 列、腕部关闭压力分支的产品开关 UI（代码里可留 `enablePressureBranch=true` 常量）。

---

## V3.R4 腕部 ECG：ads1292r 协议、波形、落盘

### 目标

在**不丢掉现有两条直播协议**的前提下，为腕部 Nordic 身份增加 120-byte ECG 解析、波形与 `{stem}_ecg.csv`。可录制仍只取决于 PPG 新鲜度。

新增 ECG 之后，直播协议必须仍是因设备而异的三条，而不是「全部 Nordic 改走 ECG」：

| 设备身份 | 直播协议 | 如何选定 |
|---|---|---|
| 广播名前缀 `CUP` | `BATCH_COMPATIBLE`（168-byte batch） | 连接开始即按广播名锁定（现有，禁止探测、禁止改成 ECG） |
| 精确名 `Nordic_UART_Service` 且帧为 168 | `SENSOR_PACKET_168` | 订阅后帧几何探测锁定；超时可手动覆盖 |
| 精确名 `Nordic_UART_Service` 且帧为 120 | `ADS1292R_120` | 同上 |

旧 cupraw 回放仍按会话里已写入的 `protocol_profile` 选解码器，与直播探测无关。

### 为何不能只靠设备身份选 ECG

- **代码已经能按设备区分现有两种协议：** `CupBleDeviceProfile.streamProtocolModeForAdvertisedName` → `connect()` 固化 `activeStreamProtocolMode`。`CUP*` → batch；精确 `Nordic_UART_Service` → sensor packet 168。两种 168 总长相同，现有架构禁止按 payload 互猜。
- **需求没有给出 ECG 的第三种身份：** 第 1.1 / 1.3 节「腕部暂无类型码」、连接方案保持 NUS + `Nordic_UART_Service`。命名表单的指尖/腕部只改 `PPG-`/`MB-` 前缀，**不**绑定线协议（rebase 2.1 / 2.8）。
- 因此 120-byte ECG 与既有 Nordic 168 会撞在同一广播名、同一 NUS UUID 上。R4 **不得**把 `Nordic_UART_Service` 默认切到 `ADS1292R_120`。

### 子任务

1. **协议分发（身份优先 + Nordic 帧几何探测）**  
   - `Ads1292rPacketProtocol`：帧长 120，字段与需求附录 A.4 一致。  
   - `CupStreamProtocolMode` 增加 `ADS1292R_120`。CUP 继续走现有 `CupBatchStreamDecoder`；腕部 120 用独立 decoder（或平行 Wrist 模式），由 preview/recording 按**已锁定** mode 分发。禁止用 120 解析器去啃 CUP 168。  
   - **CUP：** 行为与今日完全一致。`connect()` 仍立即锁定 `BATCH_COMPATIBLE`。此身份**不跑**探测。  
   - **Nordic：** `connect()` 不再把 `SENSOR_PACKET_168` 当成已提交的解码器。订阅后进入 pending：原始 notify 仍按块上送（`.cupraw` 真源不变），但先进入有界探测缓冲。拼分片方式与现有 decoder 相同，**不以单次 notify 长度为帧长**（MTU 会切包）。  
   - **探测规则（只比较 120 vs 168 sensor，绝不拿 CUP batch 来猜）：** 搜 `AB BA`；若 `header+118..119 == CD DC` 且该 120 窗口独占成立 → 记一票 120；若 `header+166..167 == CD DC` 且该 168 窗口独占成立 → 记一票 168。两票同时成立则丢 1 字节继续搜，不算命中。连续 **3** 帧同一候选 → 锁定对应 mode，把缓冲回放进该 decoder；本 connection generation 不再改 mode。重连才重新探测。  
   - 锁定前新鲜度保持非 FRESH；UI「正在识别数据协议…」。开始录制继续被 `StreamNotFresh` 拦住。  
   - **超时（2 s，与现有新鲜度窗口一致）**仍无法独占锁定：保持非 FRESH，提示「无法识别数据协议」，并只对 Nordic 连接给出手动覆盖：「腕部 ECG (120)」/「Nordic PPG (168)」。CUP 连接不出现此控件。上次成功的 Nordic 锁定结果只可作探测先验（先核对该长度），**必须**再通过几何匹配才锁定，不可跳过探测。  
   - 不要把 R1 命名表单的设备类型二选一接到解码器上。  
   - seq 连续性、uint32 回绕、头尾校验失败丢帧并计数。ECG 丢帧不阻断录制。

2. **样本展开**（仅 `ADS1292R_120`）  
   - 每帧：4 个 PPG 样本写入现有 `{stem}.csv` 路径（100 Hz）；20 个 ECG 样本写入 ECG writer（500 Hz）。时间轴 `device_time_s = sample_index / 500`，`frame_seq_no` 带上。  
   - PPG 与 ECG 用同一 `session_id`。  
   - Nordic 168 与 CUP 168：仍每帧 20 个 PPG 样本，不写 ECG。

3. **波形**  
   - `LiveWaveformSnapshot` 增加 `ecg`（显示缓冲按视窗抽点）。锁定为 120 之前或非 ECG 协议不画第三条。  
   - 通道切换或 RED/IR/ECG 并列，选实现成本低的一种（推荐：有 ECG 时第三面板，无则保持现状）。

4. **文件**  
   - `CaptureEcgCsv`：6 列，schema `ppgcollector_ecg_v1`。  
   - `SessionFileSet` / `expectedFiles` / writer / recovery / inspection / 单会话导出 增加可选 `ecg`。  
   - metadata：`files.ecg`、`ecg_sample_rate=500` 或 null。CUP / Nordic 168 必须是 null，且磁盘上无 `{stem}_ecg.csv`。  
   - 会话 `protocol_profile` 写入**锁定后**的 identifier（batch / sensor-168 / ads1292r-120），供回放直接选用，回放不做探测。

5. **Fake BLE**  
   - 合成 120-byte 帧发生器（25 Hz 帧、PPG 100、ECG 500）。  
   - 保留既有 168-byte sensor packet 发生器，证明同一 `Nordic_UART_Service` 身份仍能锁定 168 并完成录制。  
   - CUP fake 流不得进入 Nordic 探测。

6. **测试**  
   - 120 合法帧 round-trip；坏头尾丢弃；seq gap 计数。  
   - **探测：** 连续 3 个 120 → 锁 `ADS1292R_120`；连续 3 个 168 sensor → 锁 `SENSOR_PACKET_168`；分片 notify 仍能锁；两种候选同时成立不算命中；超时保持 pending。  
   - **身份隔离：** `CUP*` 连接立即 batch，探测不运行；CUP 168 喂给 120 解码器不得接受。  
   - **手动覆盖：** 超时后选 120 / 168 分别锁定；CUP 路径无此入口。  
   - CUP fixture 回归 `CupBatchProtocolTest`。  
   - `NordicSensorDeviceCompatibilityTest` / `NordicSensorCaptureCompatibilityTest`：改为「订阅后 pending → 168 帧锁定 → 录制无 ECG」；旧 168 cupraw 回放仍过。  
   - 录制：120 流有 `_ecg.csv` 且行数约为 PPG 行数 × 5；CUP 与 Nordic 168 录制 `ecg_file=null` 且无该文件。

### 主改文件

- 新建 `core/protocol/Ads1292rPacketProtocol.kt`
- 新建 `core/protocol/NordicWireProbe.kt`（纯函数：喂字节 → 候选票 / 锁定结果；JVM 可单测）
- `CupStreamProtocolMode` / `CupBatchStreamDecoder.kt` 或新 120 decoder
- `BleModels.kt` / `BleGattStateMachine.kt` / `BlePreviewRuntime.kt` / `CaptureRecordingController.kt`（Nordic pending → 锁定；CUP 仍立即锁定）
- `LiveWaveformRuntime.kt` / `LiveWaveformComponents.kt` / `LiveCaptureScreen.kt`（识别中文案 + Nordic 超时手动覆盖）
- `CaptureSessionWriter.kt` / `CaptureSessionRepository.kt` / export/recovery/inspection
- `FakeBleTransport` 或测试夹具
- 对应 test（含更新后的 `NordicSensor*CompatibilityTest`）

### 验收

- fake 腕部 120 流：RED/IR + ECG 可见；停止后存在 `_ecg.csv` 且行数约为 PPG 行数 × 5。  
- 同一 Nordic 身份改吐 fake 168 sensor：锁定 168，无 ECG 波形、无 `_ecg.csv`，可按 PPG 新鲜度开始录制。  
- CUP 扫描连接录制：无探测 UI、无 ECG 文件，既有 batch 协议测试全绿。  
- 故意丢 ECG 样本不阻止 start/stop。  
- 探测超时未手动选择时不能开始录制。

以上验收在 R4 编码后**未闭环**（预览未切 120 解码器、无探测超时 UI、无 fake 120 流、无 `SessionFileSet.ecg`、录制路径未喂 PPG runtime）。执行与复验见 **V3.R4.1**。

### 本轮不做

档案分小节、回看改血压、ECG-SQI、按命名前缀选协议、为腕部发明类型码/新广播名。

### R4 实际落地与缺口（2026-08-19 对照源码）

R4 编码已交付协议骨架，但**不能按本章验收视为闭环**。状态文档曾写「R4 已完成」，R4.1 以此清单为准，不以该结论为准。

| 已落地 | 未闭环 / 偏差 |
|---|---|
| `Ads1292rPacketProtocol` 120 B 小端布局；`Ads1292rStreamDecoder` 可拼分片 | `BlePreviewRuntime` / `CupRawReplayEngine` 仍只用 `CupBatchStreamDecoder`，无 120 分支 |
| `CupStreamProtocolMode.ADS1292R_120`；CUP 连接不跑探测 | Nordic `connect()` 仍立刻把 `activeStreamProtocolMode` 写成 `SENSOR_PACKET_168`；锁定 120 后 `BleCoordinator.publish()` **不按 mode 重建 preview**，预览报 mode mismatch，新鲜度无法变 FRESH → **120 流不能开始录制** |
| `NordicWireProbe` 连续 3 票锁定；pending 期间 `WAITING`、不 `onRawChunk` | 无 2 s 超时、无「正在识别 / 无法识别」、无 Nordic 手动覆盖 120/168；双候选同时成立时丢字节口径与规划不完全一致 |
| Writer 可写 `{stem}_ecg.csv`（6 列）与 metadata `files.ecg` / `ecg_sample_rate` | `SessionFileSet` / `expectedFiles` / 导出 / 恢复 / inspection **没有 optional `ecg`** |
| 录制 worker 的 120 分支写 PPG 4 点 + ECG 20 点 | 该分支 **不** 把 PPG 喂进 `LivePpgSignalRuntime` / analysis 队列；ECG 环按包 `copy` 进 snapshot（约 25 Hz），且 `LiveSignalCard` 用 `captureWaveform.red.isNotEmpty()` 才切录制波形 → 直播第三条经常看不到 |
| `NordicWireProbeTest` / `Ads1292rPacketProtocolTest` 快乐路径 | 无双候选、超时 pending、CUP 隔离、手动覆盖、120 录制行数×5、168 `ecg=null` 的完整验收 |
| `FakeBleTransport` 可注入 notify | **没有** 25 Hz 合成 120 发生器 |

波形、探测 UI、文件清单、fake 120 流与预览切解码器全部挪到 **V3.R4.1**。

---

## V3.R4.1 收口：Nordic 120 直播、ECG 实时可视化、采集页卡顿、R1–R3 修正

### 目标

在**不改 R5 产品闭环范围**的前提下，把 R4 未验收项做完，并完成三件新增工作：

1. 锁定 `ADS1292R_120` 后，采集页「实时信号波形」与 RED/IR **并列**画出 ECG；显示降采样与落盘完全独立。  
2. 治理采集页在**未录制**时也卡顿的主因（BLE snapshot / 预览波形 / 门控扫盘）。  
3. 修正 R1–R4 已合入代码里偏离规划或会阻断后续轮次的问题。

本轮仍禁止：按 `PPG-`/`MB-` 选解码器、把全部 Nordic 默认切到 120、改 CSV `sqi` 列、把显示用 ECG 抽点写进 `{stem}_ecg.csv`。

### 建议执行顺序

协议直播能 FRESH → 降采集页重组频率 → 再加 ECG 第三面板（避免 25 Hz 画布叠在未治理的整页重组上）→ 文件清单 / Combo SQI 口径 → 小修正与测试。

---

### A. R4 直播协议收口（阻塞项）

1. **锁定后切换解码器**  
   - Nordic 探测锁定后：`activeStreamProtocolMode` 改为锁定值；`BleCoordinator` 在 **mode 变化**（不只 `connectionGeneration`）时 `previewRuntime.reset(generation, lockedMode)`。  
   - 把 pending 缓冲按锁定 mode 回放进 preview **和**（若已在录）recording sink。  
   - `BlePreviewRuntime`：`ADS1292R_120` 走 `Ads1292rStreamDecoder`；`SENSOR_PACKET_168` / CUP batch 保持现有 decoder。禁止 120 解析器处理 CUP 168。  
   - 120 预览：每包 4 个 PPG 样本喂 `LivePpgSignalRuntime`（与 100 Hz 口径一致），ECG 样本只进显示环（见 B），**不要**把 20 个 ECG 点当 PPG。  
   - 锁定后对已接受 PPG 帧 `markValidFrame`，新鲜度可到 `FRESH`，开始录制不再被 `StreamNotFresh` 永久挡住。

2. **探测超时与手动覆盖**  
   - 订阅后 2 s（与现有新鲜度窗一致）仍无独占锁定：保持非 FRESH；文案「无法识别数据协议」。  
   - 仅 `Nordic_UART_Service` 连接显示手动覆盖：「腕部 ECG (120)」/「Nordic PPG (168)」。选中后仍须用该长度做几何校验再锁定；失败保持 pending。CUP 永不出现此控件、永不跑 probe。  
   - 锁定前 UI：「正在识别数据协议…」（可挂在连接摘要 / 新鲜度旁，不要只改扫描列表）。  
   - 上次成功 Nordic 锁定只作先验（先核对该帧长），**必须**再过几何匹配。

3. **`NordicWireProbe` 几何口径**  
   - 两票同时成立：丢 **1** 字节再搜，**不计**命中。  
   - 缓冲区不足以否定 168 时，不要只凭 120 尾就投票。  
   - 有界缓冲（与 decoder `maxPendingBytes` 同量级）；分片 notify 仍能锁定。

4. **录制 120 路径与 PPG 运行时对齐**  
   - `CaptureRecordingController` 的 ADS 分支在写盘之后，把 4 点 PPG 编成与现有 `CupDecodedFrameEvent` 兼容的输入（或平行 ingest），喂 `LivePpgSignalRuntime` + analysis 队列。  
   - 定时停止仍按 **PPG 接受点数 / 100 Hz**，不要用 ECG 500 Hz。  
   - ECG 丢帧只计数，不 `stop`。  
   - `CupRawReplayEngine`：`protocol_profile == ads1292r-120-…` 时用 120 decoder；回放不做探测。

5. **Fake BLE 120 流**  
   - 测试夹具：25 Hz 帧、PPG 100、ECG 500；可按 MTU 切包。  
   - 保留现有 168 sensor 发生器。CUP fake 不得进入 Nordic probe。

---

### B. ECG 实时可视化（与存储解耦）

仿照现有 PPG 方案，只在**已锁定** `ADS1292R_120` 时出现第三条。非 120 或探测中不画 ECG，以免空窗。

1. **模块更名与布局**  
   - `LiveSignalCard` 标题：「实时 PPG 波形」→「实时信号波形」。  
   - 有 ECG 时 RED / IR / ECG 三个 `WaveformPanel` **并列**（纵向堆叠，与现 RED/IR 一致）。无 ECG 时保持两窗，不留空白第三槽。  
   - 不要用通道切换替代并列（规划已选并列，降低交互状态）。

2. **显示管线（独立于 writer）**  
   - 新增显示专用缓冲（建议挂在 `LivePpgSignalRuntime` 或平行的 `LiveEcgDisplayRuntime`），**不要**再从 `CaptureRecordingController.ecgDisplaySamples` 按包 `StateFlow.copy`。  
   - 输入：ADS 帧的 20 个 ECG uint32，**不取负、不定标**（与落盘一致）；显示层可去基线，但默认先与 RAW PPG「仅可视化变换」同策略。  
   - **降采样**：500 Hz → 与 RED/IR 显示密度同级。推荐固定 **5:1 抽点**（每 5 个 ECG 点取 1 个，对齐 100 Hz 视窗），再交给现有 `LiveWaveformPlotMath.plot`；禁止把 500 Hz 全点送 Canvas。  
   - 发布节拍与 PPG 相同（现网 `refreshRateHz = 5`），**禁止**按 25 Hz 帧各发一次 Compose snapshot。  
   - 预览与录制共用同一显示 runtime；`LiveSignalCard` 选择波形时以 `recordingActive && acceptedSampleCount>0`（或 generation 匹配）为准，**不要**再用 `captureWaveform.red.isNotEmpty()`（120 录制若 PPG 环未填会永远停在 preview）。

3. **与存储 / 回看隔离（硬约束）**  
   - `{stem}_ecg.csv` 仍 500 Hz 原始 ADC，6 列，`device_time_s = sample_index/500`。显示抽点不得写盘、不得改 `CaptureEcgCsv`。  
   - 不把 ECG 显示缓冲塞进 `SessionSignalWorkbench` / `CupRawReplayEngine` 的回看路径。回看 ECG 若本轮要能打开文件，只读 CSV/raw **另开**离线加载；默认 **R4.1 不改回看工作台**，避免和直播抽点缠在一起。  
   - `LiveWaveformSnapshot.ecg` 语义改为「**已降采样的显示序列**」。若测试需要原始 500 Hz，用 runtime 内部环，不要把全速率数组发到 Compose。

4. **测试**  
   - 100 个 ECG 点 → 显示长度 20（5:1）。  
   - 非 120 会话 snapshot.ecg 为空。  
   - writer 行数仍为 PPG×5，与显示点数无关。

---

### C. 采集页卡顿（未录制也会发生）

源码结论：卡顿主因在 **preview + coordinator**，不是录制 CSV。未连接扫描时较轻；**已连接出流**时 GATT notify 会驱动整页重组。

| 优先级 | 机制 | 为何空闲也卡 |
|---|---|---|
| P0 | `BleCoordinator.snapshotFlow` 在 `MainActivity.setContent` 根部 `collectAsState`；`handleValue` 每包改 diagnostics 计数 → data class 永不相等；`markValidFrame` 再 `publish` 一次 | CUP ~5 Hz、ADS ~25 Hz 整页（表单+设备列表+波形）重组 |
| P0 | `CaptureViewModel.combine(snapshotFlow)` 每次 `Files.list` / `getFileStore` / `listSessions` | 主线程扫会话目录，跟 notify 同频 |
| P1 | `LivePpgSignalRuntime` 5 Hz 复制 6～8 条 800 点数组；`plot(max=1600)` 不抽点，每通道 800 个 `WaveformPlotPoint` + `Path` | 波形卡本身重，再被父级重组放大 |
| P1 | `BlePreviewRuntime.process` 在同一把锁里跑 `LiveMetricAnalyzer`（HR DFT + Template SQI + `ComboSqi.evaluate`） | 1 Hz 重计算堵住 5 Hz 波形发布 |
| P2 | `LiveSignalCard` 与表单同 `LazyColumn`；不稳定 lambda；`LiveWaveformSnapshot` 含 `DoubleArray` 被当成不稳定 | 父级一动，子级无法 skip |
| P2 | 录制 ADS 路径已按包 25 Hz `ecg.toDoubleArray()`（B 必须改掉，否则第三窗会明显恶化） | 仅录制；B 一并修 |

**方案（本轮要做）：**

1. **拆 BLE UI 快照**  
   - 根组合只收集低频字段：`phase`、`freshness`、`activeStreamProtocolMode`、设备列表、扫描开关、`lastError`。  
   - diagnostics 计数不要驱动采集页。`markValidFrame` 只在 freshness **跃迁**时 publish。  
   - `LiveSignalCard` 自己 collect `previewState` / 波形（已部分如此），**不要**再因 coordinator 计数重组。

2. **门控降频**  
   - `combine` 用 `distinctUntilChanged` 后的 `phase+freshness+sessionName+recordingState+participantDraft`。  
   - 重名 / 磁盘空间：名称变更、resume、或最多 1 Hz，禁止每包 `Files.list`。  
   - 把 `_participantDraft` 纳入 combine（顺带修 R1 表单填完不刷新门控）。

3. **波形绘制减负**  
   - `plot` 的 `maximumPointCount` 跟面板像素宽度走（约 200–400），不要 1600。  
   - 生产者侧可预抽点；Compose 不要每帧 `ArrayList<WaveformPlotPoint>`。  
   - ECG 只走 5 Hz + 5:1，见 B。

4. **预览分析离锁**  
   - 对齐录制：`LiveMetricAnalyzer.analyze` 放到 preview 的 analysis 队列/线程，失败不影响波形 poll。  
   - Combo SQI 去抖也在该 1 s 帧上做（顺带修预览磁贴永远 `--`）。

5. **验收（JVM 可测部分 + 人工）**  
   - 单测：coordinator UI snapshot 在仅计数 +1 时 **equals 不变**；gate 在连续 notify 下 `listSessions` 调用次数有上限。  
   - 人工（不阻塞轮次）：连接 CUP 预览 30 s，滑动表单/输入名称无明显掉帧；再连 Nordic 120 预览同样标准。

---

### D. 文件契约：optional ECG（R4 规划第 4 点，R5 前置）

- `SessionFileSet` 增加 `ecg: Path?`。`expectedFiles` 读 metadata `files.ecg`，否则 `{stem}_ecg.csv` 若存在则采纳。  
- **缺 ECG 合法**（CUP / Nordic 168）；inspection 不得当错误。  
- `CaptureSessionExportService` / 单会话 ZIP、`CaptureSessionRecoveryService` 复制 ECG sidecar（若有）。批量档案目录结构仍归 **R5**。  
- 120 录制测试：`_ecg.csv` 行数约为 PPG 行 × 5；168/CUP：`files.ecg=null` 且磁盘无该文件。

---

### E. R3 综合 SQI 口径补齐（偏离规划）

现状：`ComboSqi.arbitrate` 常量与 Python 一致，但 `evaluate()` 是简化近似——无取负 + MA 2/2/10 + 0.5–12 Hz；压力 `p2_height` 恒为 0.5，**evaluate 几乎永不 PRESSURE**；预览未去抖、未写入 `LiveMetricSnapshot.comboSqi`。

1. 按只读 `references/需求V3.0/code/` 移植输入：`moving_average` 2/2/10、`bandpass` 0.5–12 order=2、`compute_sqi` / `compute_sqi1` / `compute_flat` / `detect_overpressure` 的 **SQI 所需子集**（可留 `enablePressureBranch=true` 常量）。不要改 Python。  
2. 1 s 帧 + `ComboSqiDebounce`：预览与录制都发布到 UI；**仍不写** `CaptureCsvRow.signalQuality`。  
3. 测试：用 `combo_sqi.py` 自测三类合成波做跨语言对照；debounce 2 帧；`TemplateMatchSqiTest` 回归。

若压力四闸门完整移植明显超出本轮工时，最低交付：**flat / good / unstable 与 Python 同类合成波一致**，压力闸门有单测（注入 `ComboSqiInputs`）且 `evaluate()` 不再使用恒 0.5 的假 p2h。完整 `overpressure_detect` 可标「R4.1 未完则记入状态文档，R5 不做算法」。

---

### F. R1 / R2 修正（小、但会误导操作者）

| 项 | 现状 | R4.1 做法 |
|---|---|---|
| 门控不订阅表单 | `combine` 无 `_participantDraft`，填完性别/年龄可能不刷新 `canStart` | 纳入 combine（与 C.2 同一改动） |
| 服务侧 start 仍 `validate` 单失败且无 participant | UI 用 `validateAll`；`CaptureRecordingController.start` 可绕过被试完整性 | start 改 `validateAll` 或显式传入 participant；保持 `validate` 给旧单测 |
| `StreamNotFresh` 文案 | 「等待新鲜数据流」 | 增加规划语义：「有数据读入后方可开始录制」；探测中改用 A.2 文案 |
| 非法时长未回写 60 | 红字有，开始时 fallback 60，输入框可能仍是非法值 | 失焦/开始失败时把文本设回默认 60 |
| 采集页血压死参数 | `onOpenBloodPressure` / `ManualBloodPressureDialog` 仍挂在 `MainActivity` | 采集页彻底断开；dialog 文件留给 R5 回看，不要再从录制页打开 |
| 扫描文案仍写 CUP | 「扫描 CUP」「暂无 CUP 设备」 | 改为同时覆盖 Nordic（R5 也写了文案；本轮采集页先改，避免 R4.1 测 120 时找不到设备的错觉） |

R1 的「失焦才报重名」维持现状即可：门控列表已分文件名重复 / 逻辑序号重复。

---

### 主改文件

- `BleGattStateMachine.kt` / `BleCoordinator.kt` / `BlePreviewRuntime.kt` / `BleModels.kt`  
- `NordicWireProbe.kt` / `Ads1292rPacketProtocol.kt` / `CupRawReplay.kt`  
- `LivePpgSignalRuntime.kt`（或新 `LiveEcgDisplayRuntime.kt`）/ `LiveWaveformRuntime.kt` / `LiveWaveformComponents.kt` / `LiveCaptureScreen.kt` / `MainActivity.kt`  
- `CaptureRecordingController.kt` / `CaptureSessionWriter.kt`  
- `CaptureSessionRepository.kt`（`SessionFileSet`）/ `CaptureSessionExportService.kt` / `CaptureSessionRecoveryService.kt` / `CaptureSessionInspection.kt`  
- `core/signal/combo/*` / `LiveMetricRuntime.kt`  
- `CaptureServiceViewModel.kt` / `CaptureStartGate.kt` / `CaptureRecordModePolicy` 的 UI 回写  
- `FakeBleTransport` 或测试夹具  
- 测试：探测超时/覆盖、preview mode 切换、ECG 抽点、120 文件清单、Combo 合成波、gate combine、coordinator snapshot equals

### 验收

- fake Nordic **120**：探测锁定 → 预览 FRESH → 可开始录制；RED/IR/ECG 三窗可见；ECG 窗点数约为同窗 PPG 的同等时间尺度（5:1）；停止后 `_ecg.csv` 行数约为 PPG 行 × 5。  
- 同一身份 fake **168**：锁 sensor packet，无第三窗、无 `_ecg.csv`。  
- CUP：无探测 UI、无 ECG 文件；`CupBatchProtocolTest` 仍绿。  
- 探测 2 s 未锁定且未手动选：不能开始。手动选 120/168 可分别锁定。  
- 显示抽点变化不改变 CSV/ECG 文件内容。  
- 未录制连接预览：coordinator UI 快照不因 notify 计数每包变化；门控不每包 `listSessions`。  
- 综合 SQI：平直合成 IR → 红「信号平直」；CSV `sqi` 仍为模板匹配。

### 本轮不做

R5 的档案 PPG/MB 分节、回看改 sbp/dbp、批量 ZIP 目录树、ECG-SQI、回看工作台 ECG 重放 UI（只保证文件契约，不改 `SessionSignalWorkbench` 交互）。

---

## V3.R5 回看、档案、导出、兼容收口

### 目标

把 R1–R4.1 的数据在已保存会话里用完，并处理旧文件。本轮是产品闭环，不再引入新算法。单会话 optional ECG 的 `SessionFileSet` / 导出 / 恢复已在 R4.1；本轮做档案分组、回看改血压、批量 ZIP 目录树。采集页扫描文案若 R4.1 已改，此处只扫残留。

### 子任务

1. **回看改血压**  
   - 会话详情「参考血压」：可编辑 sbp/dbp（校验同录前）；保存写回该 `{stem}.session.json`，设 `bp_updated_at`。  
   - 不改 subject profile，不改其它会话。  
   - 若用户同时改 subject/seq（若本轮仍不允许改身份，则只开放血压；身份修改不在 V3.0 五轮必做——需求写「若同时修改 subject/seq 须重跑唯一性」。**默认只开放血压编辑**，降低风险。若详情已有改名入口再接唯一性，否则不做改名）。

2. **档案 UI**  
   - 同一 subject 下按 `PPG` / `MB` 分小节；非 canonical 仍进未分类。

3. **导出**  
   - 批量 ZIP：`subjects/{subject}/PPG|MB/{stem}/…`（无前缀的 unclassified 保持）。  
   - 每个会话带上 `_ecg.csv`（若有）及既有 cupraw/csv/json/metrics/旧血压 CSV。  
   - `export_manifest` 列出新文件。

4. **删除 / 恢复 / 检查**  
   - 删除目录即删除 ECG。  
   - recovery 复制 ECG sidecar（若有）。  
   - inspection 不因缺 ECG 报错（CUP 与 Nordic 168 均合法）。

5. **兼容**  
   - 旧 session.json 无新键：吸烟饮酒空、血压空、planned_duration null、ecg null。  
   - 旧 `sex` 非 男/女：回填清空。  
   - 旧 blood-pressure.csv：详情只读展示历史事件，同时允许填写新的会话级 sbp/dbp。

6. **文案**  
   - 连接列表/提示：不能同时测；换机先断开。  
   - 扫描文案不要暗示只能 CUP（Nordic 也要找得到）。

7. **测试**  
   - metadata 缺键 decode。  
   - 档案分组。  
   - archive export 路径与 ECG optional。  
   - 血压写回只影响一个会话文件。

8. **状态文档**  
   - 更新 `V3Development/status/DEVELOPMENT_STATUS.md`：五轮完成项、真机待测清单（腕部 ads1292r 真机、CUP 回归、定时录制断流）。

### 主改文件

- `SessionsScreens.kt` / `SessionsViewModel.kt` / `SavedSessionsRoute.kt`
- `SubjectArchiveModels.kt` / `SubjectArchiveScreen.kt`
- `CaptureArchiveExportService.kt` / `CaptureSessionExportService.kt`
- `CaptureSessionRecoveryService.kt` / `CaptureSessionInspection.kt`
- `LiveCaptureScreen.kt`（扫描文案）
- 对应 test
- `V3Development/status/DEVELOPMENT_STATUS.md`

### 验收

- 打开旧会话不崩溃。  
- 改血压后重进详情为新值。  
- 含 MB+ECG 的导出自检文件齐全；CUP 与 Nordic 168 导出无 ECG。  
- 档案里同一人 PPG 与 MB 分段可见。

---

## 跨轮工程约定

- 每轮先补/改 **JVM 测试再改生产代码**（算法轮尤其如此）。  
- schema 新键一律 optional decode。  
- 不改 `references/需求V3.0/` 里的 Python。  
- 不把 SpO2、预测 BP 做成「看起来可用」。参考血压 UI 明确是人工填写。  
- 综合 SQI 颜色是显示逻辑；录制 CSV `sqi` 口径锁定，直到有单独需求再改。  
- CUP 与腕部协议分模式，禁止用 120-byte 解析器去啃 CUP 168 帧。
- 直播协议因设备而异：CUP 靠广播名锁定 batch；Nordic 的 120/168 靠帧几何探测锁定，禁止把全部 Nordic 默认切到 120。两种 168 不得靠 payload 互猜。

## 建议的真机清单（全部延期，不阻塞轮次完成）

1. CUP 指尖：R1 命名/门控、R2 定时、R3 综合 SQI 颜色、R5 导出。  
2. 腕部仪器：R4 120-byte 实流、ECG 波形；若仍有 `Nordic_UART_Service` 168 固件，确认探测锁定 sensor packet 而非 ECG；与 CUP 互斥换机。  
3. 长录制与断流：R2 倒计时暂停/恢复。
