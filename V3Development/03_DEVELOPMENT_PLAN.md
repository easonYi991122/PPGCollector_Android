# V3.0 开发方案

原计划约束是编码工作不超过五轮（`V3.R1` … `V3.R5`）；后续已因复验插入 R4.1/R5.1，并按稳定性审计追加 R6。R6 真机复验暴露实时显示回归与残留空闲卡顿，因此继续追加 R7，不把自动化门禁通过等同于真机体验完成。
**V3.R4.1** 插在 R4 与 R5 之间。**V3.R5.1** 是 R4.1/R5 代码提交后，针对真机/CUP 名模拟腕带复验发现的直播缺口（采集页仍卡顿、ECG 在 CUP 身份上不可用）追加的修正轮。**V3.R6** 是对 M7.6 之后增量的稳定性审计轮，收口生命周期卡死、录制/波形连续性、主线程 I/O 与 R1–R5 文件/业务契约缺口。每轮交付可 JVM 验收的增量。需求依据 [`02_REQUIREMENTS_REBASED.md`](02_REQUIREMENTS_REBASED.md)，代码基线见 [`01_CODEBASE_AS_IS.md`](01_CODEBASE_AS_IS.md)。

轮次 ID：`V3.R1` … `V3.R4`、`V3.R4.1`、`V3.R5`、`V3.R5.1`、`V3.R6`、`V3.R7`、`V3.R8`、`V3.R8.1`、`V3.R8.2`、`V3.R9`、`V3.R10`。

---

## 总览

```text
R1 表单 / 命名 / 门控 / 录前血压字段
R2 定时录制与停止键倒计时
R3 综合 SQI 显示状态机（对齐 combo_sqi.py）
R4 腕部 ads1292r + 保留既有两条 168 直播协议
R4.1 收口 R4 直播/文件缺口 + ECG 实时可视化 + 采集页卡顿 + R1–R3 口径修正
R5 回看改血压、档案/导出、兼容收口
R5.1 CUP/ECG 协议探测修正 + 采集页卡顿收口
R6 生命周期/录制稳定性 + 主线程与连续性收口 + M7.6 后逻辑缺陷修正
R7 恢复 M7.6 实时显示语义 + 空闲采集页轻量化 + ECG 显示抗混叠
R8 实时纵轴稳定 + 指标恢复 + wire gap 诊断
R8.1 RAW 纵轴中心锁定 + 分级对称扩展
R8.2 恢复录中 BP 入口 + 实时 gap 渲染/诊断比例
R9/R9.1 双路 BP + 表单/gate + 详情元数据 + ATT MTU 协商
R10 gap-aware repaired signal + 指标来源 + 详情重放
```

| 轮 | 主题 | 主风险 | 可独立演示 |
|---|---|---|---|
| R1 | 采集前信息架构 | 命名唯一性与旧 PPG-only 会话并存 | 选 MB 前缀可与 PPG 同 subject/seq 共存；门控逐项列出；录前填血压、录中无弹窗 |
| R2 | 录制时长 | 倒计时必须跟样本时钟 | 定时 60 s 按接受样本自动停；断流时倒计时停 |
| R3 | 综合 SQI | 口径必须跟 Python，且不改 CSV `sqi` | 标签出现绿/橙/红/灰文案；去抖在 1 s 帧上 |
| R4 | ECG 协议 | 三条直播协议隔离；Nordic 120/168 无类型码 | fake 120 显示 ECG 并落 `_ecg.csv`；同身份 fake 168 仍按 sensor packet 采、无 ECG 文件；CUP 回归仍绿 |
| R4.1 | 直播收口 / ECG 显示 / 卡顿 | 120 预览未切解码器则永远非 FRESH；显示缓冲不得改落盘 | Nordic 120 可开始录制；采集页 RED/IR/ECG 并列；空闲连接不再整页 25 Hz 重组 |
| R5 | 回看与导出 | 旧会话缺新键 | 详情改 sbp/dbp；ZIP 含 ECG；档案分 PPG/MB |
| R5.1 | CUP 上 ECG + 卡顿收口 | CUP 名模拟腕带走 batch 会把 120 帧当废帧；R4.1 卡顿治理未真正切断主线程 publish | CUP 名前缀设备可锁定 ads1292r 120 并显示 ECG；手动覆盖可见；空闲预览不再按 notify 重组整页 |
| R6 | 生命周期、录制与 M7.6 后稳定性 | BLE/协议工作仍占主线程；缺帧会清空三窗；恢复前台叠加全量文件扫描；多项 R5 契约未闭环 | fake BLE 连续/缺帧/后台恢复均不重置或卡死；录制可正确结束；全量 JVM 0 失败；MB/BP/ECG 文件契约回归通过 |
| R7 | 实时显示回归与空闲 UI 卡顿 | R6 把显示历史与滤波连续性拆成不一致状态；空闲页仍首组装完整图表/表单；ECG 硬抽点会混叠 | RAW 不再随视窗重拟合漂移；CAUSAL/FIXED 在普通序号 gap 后保持可用；空闲页无图表重绘负担；ECG 显示不生成抽点伪峰 |
| R8 | 实时纵轴、指标和链路诊断 | 整窗自动缩放造成视觉漂移；wire gap 同时阻断指标并覆盖波形；现有 UI 无法区分板端序号异常、解码异常和 App 队列丢块 | 纵轴按最近 200/600 点；普通 wire gap 不再使指标永久停在 warmup；中断标记移到图下；预览/录制显示一行有界诊断 |
| R8.1 | RAW 纵轴视觉稳定 | R8 虽限制为最近 200/600 点，但每个 5 Hz 快照仍直接替换上下界，同一历史样本会因极值滑动而缓慢上下移动 | RAW 每个来源/通道锁定纵轴中心；正常候选波动不改轴，越界时仅分级、对称扩展；数据值和 200/600 候选规则不变 |
| R8.2 | 即时 UI/诊断收口 | 录中 BP 底层存在但入口丢失；密集 gap 把实时 path 切成大量闪烁短段；诊断只有计数 | 恢复录中 BP 按钮；marker 留在图下而 path 连续；显示缺帧率/异常帧率 |
| R9 | 采集表单和参考 BP 闭环 | 录前 BP 不在事件时间轴；notes/BP 继承；重名错误粘住；详情 metadata 不全 | 一次会话可同时含录前/录中 BP；录后表单清理；gate 实时恢复；详情字段完整 |
| R10 | 详情修复信号与指标重放 | 离线时钟与 accepted cursor 不一致；密集 gap 使滤波/分析为空；marker 遮盖波形；metrics 失败静默 | raw 证据不变，repaired input 对齐实时口径；指标来源/降级可见；marker 可关且在波形下层 |

依赖：R2 依赖 R1 的 metadata 扩展习惯（同一 codec）。R3 不依赖 R2。R4 不依赖 R3。**R4.1 依赖 R4 已落地的协议/writer 骨架，必须在 R5 之前完成**（R5 导出/恢复要用完整的 `SessionFileSet.ecg`）。R5 依赖 R1 字段 + R4/R4.1 文件清单。**R5.1 依赖 R4.1 的 120 decoder / ECG 显示环 / uiSnapshotFlow 骨架。R6 依赖先保全 R5.1 当前工作区并形成可回退基线；不得在未区分用户已有修改时覆盖或回滚。**

**当前轮次：R3 综合 SQI 口径收口已完成编码；R9/R10 真机节点均待测。未扫描时残留卡顿仍暂不处理。**

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

### R3 实际收口（2026-08-25）

R3 编码后 `arbitrate` 常量已对齐，但 `evaluate()` 长期是简化近似（R4.1 E / R6 F.5 记下未完）。2026-08-25 已按只读 Python 补齐预处理、SQI_tm/SQI_corr/flat/overpressure、预览去抖与跨语言合成波金标；CSV `sqi` 未改。详见 `status/DEVELOPMENT_STATUS.md`。

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
   - 仅 `Nordic_UART_Service` 连接显示手动覆盖：「腕部 ECG (120)」/「Nordic PPG (168)」。选中后仍须用该长度做几何校验再锁定；失败保持 pending。  
   - **R5.1 已修正：** 上条「CUP 永不出现此控件、永不跑 probe」作废。CUP 默认仍立刻按 batch 预览；batch 无接受帧时用同一套帧几何探测 120，超时出示「腕部 ECG (120) / CUP PPG (168)」。详见 **V3.R5.1**。  
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

## V3.R5.1 修正：CUP 身份上的 ads1292r 探测、手动覆盖、采集页卡顿收口

### 2026-08-20 复验结论（以源码为准）

R4.1 / R5 已提交骨架，但**采集页仍卡、ECG 在 CUP 名设备上不可用**。状态文档不得再把 R4.1 卡顿项与「仅 Nordic 才探测」写成已验收。

#### ECG 读不到 / 显示不出来 / 没有手动选择 — 根因

用户用 **CUP 广播名的模拟腕带开发板** 发 ads1292r 120 字节帧（每 40 ms 一帧：ECG×20 + RED×4 + IR×4，头 `AB BA`、尾 `CD DC`）。这与 `Ads1292rPacketProtocol` 布局一致，**不是协议实现错了**。

现行锁定把探测绑死在精确名：

```text
connect(): nordicWireProbe = (name == "Nordic_UART_Service") ? NordicWireProbe() : null
handleValue(): 仅当 probe != null && mode == SENSOR_PACKET_168 才探测
selectNordicProtocol(): 若 name != "Nordic_UART_Service" 直接 false
```

CUP 前缀设备 `streamProtocolMode = BATCH_COMPATIBLE`，120 帧被 `CupBatchStreamDecoder` 当成非法 function（byte2 是 seq 低字节，不是 `0x15`），逐字节丢弃，**永远没有接受帧 → 不 FRESH → 不显示 ECG**。`protocolProbePending` 对 CUP 恒 false，连接摘要上的「腕部 ECG (120)」按钮不会出现。

R4.1 原文「CUP 永不 probe」在正式 CUP 指尖 168 上仍正确，但**不能**覆盖「CUP 名 + ads1292r 120 固件」的开发板。本轮改掉这条禁令，改为：**CUP 默认 batch；batch 无接受帧时用同一套帧几何探测 120，并给出手动覆盖。**

Nordic 路径保持：120 vs **sensor packet 168**（不是 CUP batch）。CUP 锁定 168 时必须是 `BATCH_COMPATIBLE`，禁止把 CUP 名设备切到 `SENSOR_PACKET_168`。

#### 采集页仍卡顿 — 根因（R4.1 C 未收口）

| 项 | 代码现状 | 为何仍卡 |
|---|---|---|
| 主线程每包 `publish()` | `BleCoordinator` 的 `transport.eventHandler` 在每次 `ValueReceived` 后无条件 `publish()`；`snapshotNow()` 每次 `discoveredDevices.toList()` | GATT notify 在主线程；25 Hz 120 流或失败 batch 丢字节时同样每包跑一遍 |
| `markValidFrame` 每帧都 publish | `CupBleGattStateMachine.markValidFrame` **恒 `return true`**；`handlePreviewAcceptedFrame` 因此每次跳回主线程 | `uiSnapshotFlow` 即使 `distinctUntilChanged` 也会在主线程做 snapshot 拷贝 |
| 预览 StateFlow 每包变 | `BlePreviewRuntime.process` 每次接受都写 `processedSampleCount`；`LiveSignalCard` collect 整个 `previewState` | 波形 5 Hz，卡片仍按帧率重组 |
| 分析仍在 preview 锁内 | `LiveMetricAnalyzer.analyze` 与 `poll` 同一 `synchronized(lock)` | 1 Hz DFT/SQI 堵住 5 Hz 发布 |
| 门控仍跟全量 service state | `combine(..., serviceClient.state, ...)` 含 waveform；`evaluateGate` 仍每发射 `Files.list` | 预览/录制波形变化会扫盘 |
| 探测 UI 第二钮写死 Nordic 168 | CUP 即使 pending 也会把覆盖目标设成 sensor packet | CUP 开发板手动选 168 会解错 |

`uiSnapshotFlow` 把 diagnostics 置空是必要的，**不够**。

---

### 目标

1. **CUP 前缀与 Nordic 精确名都能锁定 ads1292r 120**，RED/IR/ECG 三窗可见，落盘仍是 500 Hz `{stem}_ecg.csv`。  
2. **CUP 默认仍是 batch 168**；仅当 PPG-only 解码没有接受帧时才切 120 或出示手动覆盖。正式 CUP 指尖回归不得误锁 ECG。  
3. **真正切断采集页与 notify 同频的主线程重组与扫盘。**

本轮不做：回看工作台 ECG、改 CSV `sqi`、按 `PPG-`/`MB-` 选协议、把全部 CUP 默认切到 120。

---

### A. 协议探测：CUP 默认 batch，失效则测 120

帧几何（与固件一致，不要改 `Ads1292rPacketProtocol` 布局）：

```text
120 B packed: AB BA | u32le seq | u32le ecg[20] | u32le red[4] | u32le ir[4] | CD DC
发送：40 ms / 帧 → PPG 100 Hz，ECG 500 Hz
```

168 B 只看尾偏移区分几何（118 vs 166），**解码器按广播身份映射**：

| 广播身份 | 120 几何锁定 | 168 几何锁定 / 默认 |
|---|---|---|
| 精确名 `Nordic_UART_Service` | `ADS1292R_120` | `SENSOR_PACKET_168`（现网） |
| 名称前缀 `CUP`（含 NUS 与 FFF0） | `ADS1292R_120` | `BATCH_COMPATIBLE` |

**CUP 连接时序（必须按此实现）：**

1. `connect()`：`activeStreamProtocolMode = BATCH_COMPATIBLE`；**立刻**按 batch 预览（正式 CUP 不能先卡 2 s）。同时创建几何 probe，`protocolProbePending = false`。  
2. 每个 notify：原始字节 **拷贝两路**——一路 `CupBatchStreamDecoder`，一路 `NordicWireProbe`（或改名为 `WireGeometryProbe`，逻辑仍是 120 vs 168 尾）。  
3. **先有接受的 batch 帧**：取消 probe，保持 batch，不出现手动覆盖。  
4. **连续 3 个独占 120 票且尚无接受 batch 帧**：锁定 `ADS1292R_120`，`previewRuntime.reset(generation, ADS1292R_120)`，按 120 **回放 pending 原始块**，之后 `markValidFrame` 可 FRESH。  
5. **订阅后 2 s 仍无接受 batch 帧且未锁 120**：`protocolProbeTimedOut = true`，`protocolProbePending = true`，新鲜度保持非 FRESH，文案「无法识别数据协议」，出示手动覆盖。  
6. 超时后继续缓冲；用户选 120 须再过 120 几何校验；选 CUP 168 强制 `BATCH_COMPATIBLE` 并回放。失败保持 pending。

**Nordic 连接时序：** 保持 R4.1：连接即 pending，不先当 CUP batch 解；3 票锁 120 或 sensor 168；2 s 超时 + 手动「腕部 ECG (120) / Nordic PPG (168)」。

**手动覆盖 UI**（`ConnectionSummaryCard`）：

- 在 `protocolProbePending || protocolProbeTimedOut` 时显示（CUP 超时后也会进这里）。  
- 第一钮永远「腕部 ECG (120)」→ `ADS1292R_120`。  
- 第二钮：CUP 身份「CUP PPG (168)」→ `BATCH_COMPATIBLE`；Nordic「Nordic PPG (168)」→ `SENSOR_PACKET_168`。  
- 把 `selectNordicProtocol` 改名为 `selectStreamProtocol`（或保留旧名但去掉 `name == Nordic_UART_Service` 限制，按上表映射）。

**禁止：**

- 用 120 解析器啃已锁定的 CUP 168。  
- 把 CUP 名设备锁成 `SENSOR_PACKET_168`。  
- 按会话名前缀 `PPG-`/`MB-` 选解码器。

**探测几何口径**（补 R4.1 未完）：两票同时成立丢 1 字节不计命中；缓冲区不足以否定 168 时不投 120；有界缓冲。

---

### B. 采集页卡顿收口（在 A 之后立刻做，避免 25 Hz 120 让卡顿更明显）

1. **主线程 publish 门闩**  
   - `eventHandler` 在 `ValueReceived` 上 **不要**无条件 `publish()`。只在 UI 切片变化时发布：`phase`、`freshness`、`activeStreamProtocolMode`、`protocolProbePending/TimedOut`、`isScanning`、设备列表内容、`lastError`。  
   - `snapshotNow()` 不要每包 `toList()`；设备列表仅在发现/连接变化时拷贝。  
   - `markValidFrame` 仅当 freshness **值改变**时返回 true（对齐 `handlePreviewClockTick`）。  
   - 根组合继续只 collect `uiSnapshotFlow`。diagnostics / characteristics 永不进入该切片。

2. **预览 StateFlow 降频**  
   - `BlePreviewRuntime` 不得因 `processedSampleCount++` 发布。波形只在既有 5 Hz `poll`/`publishIfDue` 时发布；指标 1 Hz。  
   - `LiveSignalCard` collect 波形用 `waveform.publicationSequence`，指标用 analysis epoch，不要整份 `BlePreviewSnapshot`。  
   - `LiveMetricAnalyzer.analyze` 移出 preview 锁（独立队列/线程），失败不影响 `poll`。

3. **门控**  
   - `combine` 用 `serviceStatus`（或 recording state 枚举），**禁止** `serviceClient.state`（内含 waveform）。  
   - BLE 侧只订阅 `phase+freshness`（已 `distinctUntilChanged`）。  
   - `Files.list` / `getFileStore`：名称变更、resume、或 ≤1 Hz。`_participantDraft` 保留在 combine。

4. **绘制**  
   - 保持 `plot` 点预算 ≤400；ECG 仍 5:1 + 5 Hz。锁定 120 后第三窗标签改为「ECG」，不要写「500 Hz」以免暗示全速率上屏。

5. **验收（JVM）**  
   - 仅 diagnostics/rawChunkCount +1 → UI snapshot equals 不变，且 `publish`/gate 的 `listSessions` 不增加。  
   - 预览 25 个 120 帧 / 1 s → 波形 `publicationSequence` 约 5，不是 25。  
   - CUP 名 + fake 120：3 帧内锁 120，ECG 显示长度约为 PPG 的同时长（5:1），可 FRESH。  
   - CUP 名 + fake batch 168：无第三窗、无 `_ecg.csv`，不出现「无法识别」。  
   - Nordic 168/120 回归仍绿。

---

### 主改文件

- `BleGattStateMachine.kt` / `BleCoordinator.kt` / `BlePreviewRuntime.kt` / `BleModels.kt`  
- `NordicWireProbe.kt`（可改名 `WireGeometryProbe`，保留旧测试别名）  
- `LiveCaptureScreen.kt` / `MainActivity.kt` / `CaptureServiceViewModel.kt`  
- `LivePpgSignalRuntime.kt` / `LiveWaveformComponents.kt`  
- 测试：`NordicWireProbeTest`、CUP 名前缀 + 120 锁定、CUP batch 不误锁、coordinator UI equals、preview 发布节拍

### 本轮不做

R5 已交付的血压写回 / ZIP 树 / `SessionFileSet.ecg` 不要回滚。档案 UI 若仍缺 PPG/MB 小节，单列状态，不在本轮顺手大改。Combo SQI Python 全量对齐仍不在本轮。

---

## V3.R6 稳定性收口：生命周期、录制连续性、主线程与 M7.6 后逻辑缺陷

### 审计基线与结论范围（2026-08-20）

- 审计范围：`2997db7 feat(M7.6)` 之后的已提交增量，直到 `2fc100d`，再加当前未提交的 R5.1 工作区。开始编码前必须先保全当前工作区，记录基线 commit/patch；不得用 reset/checkout 覆盖用户已有修改。
- 已阅读 V3 brief、as-is、rebased requirements、状态文档与 R1–R5.1 计划，并沿 `MainActivity → ViewModel → BleCoordinator/Transport → preview/recording runtime → writer/repository/archive` 检查实际源码。
- 当前 `:app:testDebugUnitTest` 实跑为 **194 tests / 2 failed**：`OfflinePpgAnalysisTest.stableSegmentsExcludeContactChangeAndRecoverDominantRate` 与 `OfflineBloodPressurePreviewTest.fallbackUsesEightSecondWarmupOneHertzCadenceAndResetsAtGap`。R6 不允许继续把它们作为已知例外。
- 静态代码可以直接证明“哪些路径会阻塞/重置”，但无法仅凭源码证明真机当时究竟是序号缺口、队列拥塞还是连接 generation 改变。因此 R6 必须先补有界轨迹与 fake BLE 复现，再按轨迹落修复；禁止只凭 UI 观感改延时或扩大缓冲区。

### 已知现象的根因分层

| 现象 | 代码直接证据 | 结论 / 仍需验证 |
|---|---|---|
| 未扫描、未显示波形也卡 | `CaptureViewModel` 初始化建议名称、名称校验、subject profile 回填、`CaptureGateDiskCache.snapshot()` 的 `Files.list/getFileStore` 都可能在 Main；`CaptureSetupCard` 是一个进入可见区时一次性首组装的大型 Lazy item；应用级 preview 两个线程空闲轮询并周期把 clock tick 投回 Main | 主线程文件 I/O + 巨型首组装是直接缺陷；“连接再断开后消失”与文件缓存、首次 Compose/JIT/资源预热完成相符，但须用 frame trace/StrictMode 确认各自占比 |
| 滑到“数据记录”窗口弹出前卡 | `CaptureSetupCard` 把命名、被试、生活方式、血压、时长、门控和多组 Material 输入控件放在同一 `LazyColumn.item` | 进入预取/视口时整棵子树同时测量、组装；应拆为有稳定 key 的多个 lazy item，并延迟非首屏区块 |
| 后台再唤出卡死 | `AndroidBleTransport` 把扫描/GATT/notify 事件投到主 `Handler`，状态机、probe 和字节解码随后也在 Main；非录制时应用级 BLE/preview 仍可运行。与此同时 `MainActivity.onStart()` 无条件 `sessionsViewModel.refresh()`，它会重复列会话、扫描旧 BP、列 artifact、再次 rebuild archive，并对 metrics 使用 `readAllLines` | 高概率是恢复时 Main 的 BLE backlog 与大规模 I/O/分配竞争；须用后台前后队列深度、Main dispatch latency、refresh pass 计数确认，不能只在 UI 层加 debounce |
| PPG 约 1 s 后出现 ECG，随后三窗清空并循环 | preview 中 ADS 包先写 ECG，再做序号事件；`LivePpgSignalRuntime` 对任意 `Gap` 调 `invalidateContinuity()`，会清空 RED/IR/ECG 环、滤波器、指标 warmup 和发布时间；录制 analysis 又在生成 PPG snapshot **之后**才写 ECG | “一个缺帧导致三窗回到刚收到数据状态”是直接原因；当前代码在同一 generation 锁定协议后不会自行来回切换，反复重置不能先归因于协议模式抖动。缺帧由真丢包、主线程积压还是 probe/replay 边界触发，先靠轨迹确认 |
| 录制进度/界面与 notify 同频 | `CaptureRecordingController.onRawChunk()` 每个原始块都 `publish(CaptureRecordingSnapshot)`，worker 同一块还会多次 publish；`CaptureServiceStatusObservation` 又包含持续变化的完整 recording snapshot | R5.1 只降了 preview 发布频率，录制路径仍会把 service/root UI 拉回包频率；必须拆 status 与 progress 并限频 |
| ADS 录制连续性不可信 | ADS recording 分支创建了 `CupFrameSequenceTracker` 却不使用，每个包强制 `Continuous/isAccepted=true`；preview 则拒绝 duplicate/out-of-order，二者口径不一致 | 重复/乱序 ADS 会进入派生 PPG/ECG CSV，缺帧统计也不正确；raw 仍应原样保留，但派生文件必须使用同一序号策略 |
| 断流后录制可能永不结束 | service/controller 没有对连接断开、generation 改变或长期 stale 做录制终止；定时录制只按已接受样本计时，断流后计时会一直暂停 | “样本时钟暂停”本身正确，但必须有明确的断开/长期断流 finalization 策略，不能无限处于 RECORDING |

### M7.6 之后额外发现的逻辑/契约缺陷

| 优先级 | 缺陷 | 修正方向 |
|---|---|---|
| P0 | `CaptureStartGate` 用错误文案是否以“血压”开头来排除 BP；“收缩压须高于舒张压”不以该词开头，会错误阻塞开始 | 使用结构化字段/错误类型，不得用中文文案判断业务类别；参考 BP 仍不是开始门控条件 |
| P0 | ADS writer 未把 `observedSamplesPerFrame` 设为 4，metadata 会回落成 batch 的 20 | 按锁定协议写准确的 PPG samples/frame、ECG samples/frame/row count，并补文件契约测试 |
| P0 | service/controller 二次 start gate 没带 participant；ViewModel 又会在 FGS 真正 start 成功前先保存 profile revision | service 端重验同一不可绕过字段；把 profile 持久化放进“录制已接受”后的事务，失败开始不得制造 revision |
| P1 | `SubjectArchiveRepository` 从 metadata 重建 `CanonicalSessionIdentity(subject, seq)` 时使用默认 PPG，导致 `MB-*` 被归到 PPG，ZIP 路径也随之错 | 身份优先从 baseName 解析完整 prefix，并与 metadata subject/seq 交叉校验；必要的新 metadata 键只能 optional decode |
| P1 | 档案界面仍未真正显示 PPG/MB 小节；会话详情的 BP 摘要仍偏向旧 blood-pressure event count，新的 session-level sbp/dbp 展示/校验未闭环 | 完成 R5 原验收：按 prefix 分节；优先展示 session-level BP，旧 event 仅作为历史；编辑器复用范围与 `SBP > DBP` 校验 |
| P1 | optional ECG 只进入文件清单/复制，inspection 尚未校验 ECG CSV header、session_id、时间单调性和行数；损坏 ECG 可能被当作完整会话 | 增加 optional sidecar scanner；缺 ECG 对 PPG-only 合法，有 ECG 时必须过契约；recovery/export 共用 inspection 结果 |
| P1 | Combo SQI 的压力高度 `amplitude * 0.5 / amplitude` 恒等于 0.5，而阈值为 0.32，压力分支不会按参考实现触发；整体仍未完成 Python 跨语言金标 | 以只读参考 Python 生成 fixture，逐字段对齐预处理、压力/运动/异常决策和 debounce；禁止改 Python 迎合 Kotlin |
| P1 | 当前两项离线 JVM 金标失败 | 先判定是生产回归还是 fixture/测试契约过期；只能以需求与参考实现为准，修完后全量测试不得排除 |
| P2 | `SessionsViewModel.refresh()` 同一轮多次扫描 sessions/profile/BP/artifact，archive HR 对大 metrics 用 `readAllLines`；导出还会预 hash 后再复制 | 建单次不可变 catalog snapshot，流式聚合大 CSV，复用已扫描结果；导出总大小预计算一次并在复制时增量 hash |
| P2 | 录中手动血压旧 dialog/callback 仍残留；扫描/详情部分文案仍带旧 CUP-only 或旧 BP 语义 | 删除不可达入口与状态，统一 V3 文案和无障碍描述，不改变 CSV `sqi` 口径 |

### 目标

1. 空闲采集页、滚动到记录表单、后台/前台切换不再发生主线程文件 I/O、notify 同频重组或无界消息积压。
2. CUP batch 168、Nordic sensor 168、ads1292r 120 三条协议都遵循“一个连接 generation 内只锁一次；一个接受决定同时作用于 RED/IR/ECG”；缺帧不再把可视波形全部清空。
3. 录制使用与 preview 相同的序号/协议口径，持续状态低频发布，断开/长期断流可确定性结束并留下可恢复、可检查的文件。
4. 关闭上述 M7.6 后业务/文件缺陷，恢复全量 JVM 0 failure；不把未校准 SpO2/预测血压包装成有效结果。

### 建议执行顺序（同一 R6 内按门闩推进）

`A 复现与轨迹 → B BLE 线程/生命周期 → C 连续性与协议原子化 → D UI/磁盘 → E 录制事务/结束 → F 业务和文件契约 → G 全量验收`

前一步的 fake/JVM 契约未绿，不进入下一步。R6 可以拆成多个小提交，但对外仍是一个轮次；不要把性能重构、算法修正和档案修正压成一个不可审查提交。

### A. 先补可复现轨迹与回归夹具

1. 增加仅用于诊断/测试的**有界环形轨迹**，记录 `connectionGeneration`、协议锁定/手动覆盖、notify 单调时间、完整 32-bit sequence、sequence decision、queue depth/drop、continuity reset reason、录制 state/stop reason。不得把逐包轨迹塞进 Compose 收集的 StateFlow，也不得无界写日志/文件。
2. 为 `AndroidBleTransport/BleCoordinator` 增加可注入 serial dispatcher、虚拟时钟与 fake lifecycle；测试能够模拟碎片化 notify、合包、重复、乱序、单帧/多帧 gap、disconnect/reconnect、后台/前台。
3. 先写一个复现现象的 120 流：启动为两窗，锁 ADS 后三窗；注入一个 gap，当前基线测试应证明三窗被清空。修复后的契约改为“显示历史保留且分段，指标 warmup 重置”。
4. 增加发布/磁盘计数器：transport 事件数、state-machine work 数、UI slice emission、waveform emission、metric emission、recording status/progress emission、session catalog scan pass。只在 debug/test 暴露。
5. Debug 构建启用可控 StrictMode/主线程慢 dispatch 标记，覆盖 `Files.list/readAllLines/getFileStore/profile read`；正式构建不弹开发诊断。

### B. BLE 串行线程与生命周期策略

1. **移出 Main：**平台回调只立即复制必要字段/bytes，然后进入一个专用 `HandlerThread` 或 single-thread coroutine dispatcher；扫描/GATT 状态机、wire probe、stream decoder、sequence tracker 都在该串行域执行。Main 只处理权限、导航和低频不可变 UI slice。
2. **队列分级且有界：**连接/断开/协议选择等控制事件不得被 preview 数据淹没；录制 raw sink 保持 loss-intolerant（满则明确 `RESOURCE_PRESSURE` 停录），preview 可丢旧保新并统计 drop。禁止靠扩大无界队列掩盖消费不足。
3. **消除字节级二次复杂度：**`NordicWireProbe` / `Ads1292rStreamDecoder` 不再用 `ArrayList<Byte>` + 头部 `removeAt(0)`；改为有界 ByteArray ring/读写 offset，限制 pending replay 字节数并只回放一次。
4. **明确生命周期：**
   - 未扫描、未连接、未录制：不启动 preview/analysis worker，不投 clock tick 到 Main。
   - 已连接但 Activity 不可见且未录制：保留最小连接状态或按既定产品策略暂停 preview，停止 UI 波形/指标 publication；恢复时只交付最新 bounded snapshot，绝不回放 UI backlog。
   - 正在录制：FGS 与 BLE 数据串行域在后台继续，UI collector 可解绑；回前台只 rebind 当前状态，不重建 decoder/sequence tracker。
   - disconnect/close：取消 timeout/callback，清空对应 generation 队列并退出 worker；旧 generation 事件不得污染新连接。
5. 一个 generation 内协议从 pending/default 到 locked 最多一次；只有 disconnect/new generation 或显式且校验通过的手动覆盖可以 reset/replay。把 mode lock、continuity epoch、UI publication epoch 分开，不再用一个 generation 表达三种语义。

### C. RED/IR/ECG 原子连续性与显示语义

1. 把 ADS packet 处理改成单事务：**先**完成 sequence decision；duplicate/out-of-order 三通道都不进入派生显示/CSV；accepted packet 再一次性写入 RED×4、IR×4、ECG×20，并在三通道写完后生成同一 waveform snapshot。
2. preview 与 recording 共用序号决策 helper，按完整 32-bit sequence 处理 wrap/gap/duplicate/out-of-order。raw sidecar 仍保留收到的原始字节；派生 PPG/ECG、accepted sample clock、missing/invalid metadata 只按接受事件推进。
3. `Gap` 分开处理两种连续性：
   - **显示连续性：**保留已有 ring，并插入 segment/gap marker，使绘图断线而不是回到 4 个点；RED/IR/ECG 使用同一 marker。
   - **算法连续性：**重置预处理滤波状态、fixed-lag、HR/SQI/Combo warmup 与 timed-analysis epoch；下一段达到连续窗口前显示 warming/unknown。
   - 单纯 gap 不增加 connection generation，也不清空三个显示环；真正连接/协议 generation 改变才清空会话级显示。
4. 修正 recording analysis 当前“先发布 PPG、后写 ECG”的顺序；首个含 ECG 的 snapshot 必须同时包含三通道，不能产生人为的两窗→三窗闪烁。
5. 增加至少 10,000 帧 fake 持续流，以及碎片/合包/gap/duplicate/out-of-order 组合；断言协议不振荡、队列有界、accepted/missing 数、三个通道时长比例和 continuity epoch 一致。

### D. Compose 状态切片与磁盘访问收口

1. 将 `CaptureSetupCard` 拆为多个有稳定 key 的 top-level lazy item（命名、基本资料、生活方式/参考 BP、录制模式、门控/开始），默认只组装即将可见的区块；不嵌套同方向滚动容器。各输入组件只收集自己的最小 StateFlow，回调用稳定引用。
2. root composable 只收集页面选择、连接 UI slice 与录制 state 枚举；波形 5 Hz、指标 1 Hz、录制 progress（≤5 Hz）留在对应卡片。diagnostics、raw count、queue depth 和 `acceptedSampleCount` 不得让整个 `MainActivity` 重组。
3. 所有会话名建议/唯一性、profile 读取、可用空间与 session catalog 构建移到 `Dispatchers.IO`；输入时以内存索引即时校验，磁盘查询 debounce 且 latest-wins。Composable/`remember`/Main 上禁止直接 `Files.*`。
4. 引入单次 `SessionCatalogSnapshot`：一轮 refresh 只 `listSessions` 一次，并复用给列表、artifact、archive、BP/HR summary；录制完成、删除、编辑、恢复后以 dirty/version 触发增量或一次重建。
5. `MainActivity.onStart()` 不再无条件刷新 sessions/archive。只有该页面首次可见、catalog dirty 或用户主动刷新时执行；快速 stop/start 不得留下多个不可取消的阻塞扫描。
6. metrics/BP/ECG 大文件一律 streaming 聚合，禁止 `readAllLines`；设置解析上限/坏行计数并保持内存有界。批量导出总大小只计算一次，hash 与 copy 同趟完成（若 manifest 格式需要，先写临时 manifest entry/末尾 manifest，不重复读大文件）。

### E. 录制 service、进度与确定性结束

1. 把 `CaptureRecordingSnapshot` 拆成：低频/离散 `RecordingStatus`（IDLE/RECORDING/STOPPING/FINALIZED/FAILED、generation、mode、reason/summary）与限频 `RecordingProgress`（accepted duration、remaining、pending）。raw enqueue 不直接发布 UI 状态；progress 最多 5 Hz，倒计时文字最多 1 Hz。
2. FGS 监听连接 generation、locked protocol 与 freshness：
   - disconnect 或 generation/protocol 改变：停止接收新块，排空已确认块并以明确 reason finalize；
   - 短暂 stale：accepted sample clock/倒计时暂停；
   - 仍 subscribed 但超过明确 grace（常量、可测试）无接受样本：以 `STREAM_STALE`/等价 stop reason finalize，禁止永久 RECORDING。
3. 开始录制改成 service 内的单一事务：重新验证 name 唯一性、BLE generation/mode/freshness、participant 必填字段与容量；writer/FGS 接受成功后才保存 subject profile revision。失败/竞态开始不创建会话目录或 profile revision。
4. ADS metadata 明确写 PPG 4 samples/frame、PPG 100 Hz、ECG 20 samples/frame、ECG 500 Hz；PPG/ECG derived row count、时间戳、session_id、accepted/missing/duplicate 统计彼此可推导。CUP/Nordic 168 的既有 metadata 不回归。
5. `awaitFinalized`/close 使用 condition/latch 或 coroutine completion，不做 1 ms busy polling；停止过程中保持 first stop reason wins，错误也要关闭 writer/worker 并可由 recovery 检查。

### F. M7.6 后业务与文件契约收口

1. 建立 typed participant validation result；CaptureStartGate 只选择明确的 blocking 字段，参考 BP 无论为空或填写错误都不以字符串前缀混入 start gate。BP 编辑 UI 自身显示范围/成对/`SBP > DBP` 错误。
2. Canonical identity 优先解析 baseName 的 `PPG|MB` prefix，再用 metadata subject/sequence 校验；档案同 subject 下按 PPG/MB 分节，ZIP 路径断言 `subjects/{subject}/MB/...` 不会落到 PPG。
3. 会话详情优先显示/编辑 session metadata 的 sbp/dbp 与 `bp_updated_at`，旧 blood-pressure.csv 只作为历史事件摘要；一次编辑只修改该会话 JSON，不改 subject profile/其它会话。
4. 为 optional ECG 实现 streaming inspection：header、session_id、row count、device/sample time 单调性、数值字段；PPG-only 缺 ECG 仍合法，存在但损坏的 ECG 必须产生 finding 并影响 verified-complete/export manifest 状态。
5. Combo SQI 以 `references/需求V3.0/code/combo_sqi.py` 和关联预处理代码生成跨语言 fixture；修复恒定 pressure height 等偏差，并验证 4 种显示状态与 debounce。参考 Python 只读，不改录制 CSV `sqi`。
6. 修复两项现有离线回归并补原因说明；删除录中手动 BP 的残留 dialog/state/callback，统一 CUP/Nordic 扫描和 session-level BP 文案。

### 主改文件

- BLE/生命周期：`AndroidBleTransport.kt`、`BleCoordinator.kt`、`BleGattStateMachine.kt`、`BlePreviewRuntime.kt`、`NordicWireProbe.kt`、`PpgCollectorApplication.kt`
- 连续性/录制：`LivePpgSignalRuntime.kt`、`CaptureRecordingController.kt`、`CaptureForegroundService.kt`、`CaptureSessionWriter.kt`、`Ads1292rPacketProtocol.kt`
- UI/状态：`MainActivity.kt`、`CaptureServiceViewModel.kt`、`LiveCaptureScreen.kt`、`LiveWaveformComponents.kt`、`SessionsViewModel.kt`
- 文件/业务：`CaptureStartGate.kt`、`SessionNamePolicy.kt`、`SubjectArchiveModels.kt`、`SubjectArchiveScreen.kt`、`SessionsScreens.kt`、`CaptureSessionMetadataEditor.kt`、`CaptureSessionInspection.kt`、`CaptureArchiveExportService.kt`、`ComboSqi.kt`
- 对应 JVM/fake BLE/file-contract tests；必要时增加不依赖真机的 Compose/Macrobenchmark fixture

### G. 验收（全部为退出门闩）

1. `:app:testDebugUnitTest` **0 failure、0 ignore 新增**；现有两项失败必须关闭。再跑 `test lintDebug assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy`，不得只跑焦点测试。
2. fake ADS 120 连续 10,000 帧：只锁一次协议，RED/IR 40,000 accepted samples、ECG 200,000 accepted samples；波形约 5 Hz、指标 1 Hz、recording progress ≤5 Hz，UI connection/status emission 不与 25 Hz notify 同频。
3. fake gap/duplicate/out-of-order：raw 可回放全部输入；派生 CSV 不重复接受；missing/duplicate/out-of-order 统计准确；gap 后三窗保留旧历史并出现共同断点，指标回到 warming 后可恢复。
4. fake lifecycle：空闲 10 s 无 preview worker/clock tick 投 Main；连接但未录制后台 30 s 后队列不增长；录制中后台继续写入，回前台不 reset decoder/sequence；disconnect/长期 stale 能在 grace 后 finalize。
5. Debug StrictMode/计数测试：Main 上没有 session/profile/metrics `Files.*`；滚动到记录表单不会一次组装全表单；Activity 重复 stop/start 不重复全量 catalog pass。固定 emulator 的 Macrobenchmark 记录 startup、滚动与 background-resume frame timing，R6 相对修复前基线不得退化，并把阈值/设备配置写入测试说明。
6. 文件夹 fixture 同时含 `PPG-A-1`、`MB-A-1`、PPG-only、合法/损坏 ECG、旧 metadata：档案分节和 ZIP 路径正确；旧文件兼容；ADS metadata/ECG 行数正确；BP 修改只影响目标 JSON。
7. 更新 `01_CODEBASE_AS_IS.md` 与 `status/DEVELOPMENT_STATUS.md`：记录实际线程/生命周期策略、测试命令与结果、仍需真机验证项。未跑的真机项必须写“待测”，不得写已验收。

### 本轮不做

- 不做真机操作，除非用户另行明确要求；fake BLE/JVM/file contract 是本轮停止条件。
- 不修改 `references/需求V3.0/` 的 Python，不改变录制 CSV `sqi` 既有口径。
- 不新增或宣称未校准的 SpO2/预测血压，不把人工参考 BP 变成测量结果。
- 不借性能重构重做整个导航/视觉设计，不回滚 R5.1 已完成的 CUP/ADS 探测与 optional ECG 文件清单。

---

## V3.R7 实时显示回归与空闲采集页卡顿修正

### 问题结论（2026-08-20 真机复验后）

1. **未扫描时的卡顿不是 BLE 解码或信号处理在空转。** `BlePreviewRuntime` 空闲时没有 worker/clock；主要负担来自 UI/初始化：空波形仍首组装模式按钮、两张 Canvas、五项指标及详细文案；滚动到记录区时首次组装多组 Material 输入控件；`CaptureViewModel` 启动又重复发起建议名、profile、gate 扫盘，并用 `BIND_AUTO_CREATE` 创建空闲录制 service及 Main 上的健康轮询。首次连接/断开后代码、字体与绘制缓存已预热，所以观感会暂时改善。
2. **RAW 漂移来自显示层的非时不变变换。** `rawPeakUpForPlot()` 每次 5 Hz 发布都对不断增长/滑动的整个视窗重新拟合直线；同一个稳定样本会随窗口端点和 segment 组成被反复改值。R6 又保留 gap 前历史，使跨 segment 拟合进一步放大该现象。落盘 raw 没有被改。
3. **CAUSAL/FIXED 回归来自 R6 的连续性拆分不完整。** gap 后 RAW 历史保留，但 display causal 与 fixed-lag 被清空；CAUSAL 因反复从大 DC 初值启动产生明显瞬态，FIXED 每次都重新等待 201 点，频繁 gap 下长期不可选。M7.6 已验证的显示滤波参数和输入极性本身不改，算法指标的连续窗口仍必须在 gap 后重置。
4. **ECG 接收布局与落盘口径未发现错位：** 120-byte 帧仍按 `uint32 ECG×20 + RED×4 + IR×4` 小端解析，CSV 原样保存。显示层当前直接每 5 点取 1 点且随后再做 min/max 降采样，没有 500→100 Hz 抗混叠；它不会生成超出输入范围的数值，但会把高频噪声/孤立 ADC 异常突出为伪峰。若修正显示降采样后 CSV 同一时刻仍有尖峰，来源应判为开发板/模拟前端，而不是 App 解码。

后台恢复卡死已由 R6 修复，本轮不重复改生命周期主线。

### 开发任务

1. **恢复稳定的 PPG 显示契约**
   - 实时 RAW 保留 M7.6 的 raw 输入与取负极性，但针对本次真机复验去掉其随 publication 重算的整窗线性拟合，收敛为确定性的“原始 ADC 仅取负”；离线工作台是否去趋势保持原状。
   - 把“指标/算法连续性 reset”与“显示滤波连续性”分开：普通 sequence gap 只重置预处理、HR/SQI/Combo warmup并画 segment marker，display causal/fixed 保持有界状态；真正连接 generation、协议切换或实际丢失 analysis 输入才整体重置。
   - FIXED 按钮始终允许选择；未积满右侧上下文时明确显示 warming，积满后自动进入 fixed，不再被 UI fallback 改写用户选择。

2. **修正 ECG 显示，不改变 raw/CSV**
   - 新增有状态的 5:1 boxcar 降采样器，每 5 个原始 `uint32` 只发布一个均值点；跨 notify 保持相位，连接/协议 reset 时清空。
   - 保持 parser、`{stem}_ecg.csv` 和 uint32 原始口径不变；测试断言显示值位于对应五点范围内，App 不产生输入中不存在的越界峰值。

3. **削减未连接页面的首次工作**
   - 无波形时只显示轻量等待卡，不组装 Canvas、滤波选择器和指标网格；收到首批样本后再进入完整实时面板。
   - Activity 空闲绑定 service 时不使用 `BIND_AUTO_CREATE`；真正开始录制时再创建并绑定，已有 FGS 的 Activity 重建仍可重新绑定。
   - 合并首次建议名/profile/gate 磁盘初始化，避免 init、首个 `onStart` 和建议名回填连续触发同一轮扫盘；把最重的“设备/模式 + 名称”首项拆为两个有界 lazy item，其余记录表单结构不再扩大重构。

### 必要验收

- JVM：稳定 raw 显示只做取负；单个/多个 gap 后 CAUSAL 不出现重新启动的大 DC 瞬态、FIXED 保持/恢复可选且算法 metric 仍重新 warmup；ECG 5:1 跨 chunk 均值与范围契约。
- fake BLE：连续 ADS 与带 gap ADS 都能保持 RED/IR/ECG 三窗，FIXED 在达到初始延迟后不因普通 gap 消失；preview 空闲仍为 0 worker/0 tick。
- 编译与现有全量 JVM/lint/release contract 通过。真机仅复验帧时序和 CSV 对点；若 CSV 自身含异常 ECG 峰，单列为板端待查。

### 本轮不做

- 不改 ECG raw parser/CSV 的 uint32 口径，不用裁剪或符号转换掩盖板端异常。
- 不改 M7.6 的 0.5–12 Hz CAUSAL/FIXED 参数，不改 valley/peak 算法；只恢复其正确输入与连续性。
- 不再重做导航、档案、录制文件契约或后台生命周期。

---

## V3.R8 实时纵轴稳定、指标恢复与 wire gap 诊断

### 已确认现象与代码结论（2026-08-21）

1. RAW 数值本身没有漂移，移动来自可视化纵轴。实时 RAW 已经只做 `UInt32 → Double → 取负`，不得恢复 M7.6 的逐窗口线性去趋势。
2. 当前 RAW/CAUSAL/FIXED/ECG 的纵轴仍对整个可见数组取极值；代码中不存在“未满 800 参考最近 200、满 800 后参考最近 600”的规则。
3. 波形和指标使用同一个 `LivePpgSignalRuntime` 与同一组 800 点 ring。`指标 n/800` 长期小于 80，是因为每个 `CupSequenceEvent.Gap` 都把算法连续计数清零，而不是指标另走了一条短窗口路径。
4. 波形上的高频竖线不是峰谷，而是 R7 增加的 sequence-gap segment marker；它们可暂留作真机诊断，但不得覆盖波形主体。
5. BLE 回调已在专用串行线程，preview/recording 各有 256 块有界队列，stream decoder 支持拆包/粘包且字节 ring 可扩容。源码不能证明频繁 gap 究竟来自开发板 `seq_no`、链路丢通知还是 App 队列压力；现有诊断只在测试接口，且 preview overflow 错误会被下一次波形发布清掉。

### 目标与实施

1. **稳定纵轴参考窗**
   - 三个通道和三种 PPG 显示模式仍绘制最多最近 800 点。
   - 样本未满 800 时，纵轴只用最近最多 200 点求上下界；达到 800 后只用最近 600 点；padding 保持 8%。CAUSAL 的 settling 排除与该尾窗取交集，但不得得到空范围。
   - RAW 继续只取负，不修改 source、CSV 或历史点；增加纯函数边界测试，覆盖 1/199/200/799/800 点以及旧异常值退出参考窗。

2. **让指标不再被普通 wire gap 永久阻断**
   - 分离“wire 完整性事件”和“本地算法输入丢失”。普通 sequence gap 继续累计 missing/gap 并切断显示 path，但不清空 800 点 ring、不重置预处理、fixed-lag 或指标 warmup；指标按同一 accepted-sample ring 首次 800 点、之后每 100 点计算。
   - generation/协议切换、analysis queue 丢输入、非有限样本等真正破坏本地处理状态的事件仍做硬重置，并立即清掉已发布的旧指标，禁止重置后继续显示陈旧结果。
   - 指标只是可用性恢复；gap/missing 仍在诊断行明确展示，SQI 仍可将受损波形判为低质量。不得把缺帧隐藏成“链路正常”。

3. **中断标记移出波形主体**
   - 不再画贯穿 Canvas 的整高竖线；在每个 RED/IR/ECG 面板波形下方增加独立、低高度的 marker strip，以相同横坐标画短刻度。
   - marker 不参与纵轴、不连接两个 segment，详细模式注明其语义为“序号中断”，后续定位完成可整体移除。

4. **增加一行有界链路诊断**
   - preview 与 recording 都发布低频快照：已解码帧数、最近完整 UInt32 序号及最近步进、gap 事件/估算缺帧、duplicate/out-of-order、decoder 丢弃字节/坏帧、App 输入队列丢块。
   - 诊断只随既有 5 Hz 波形或录制 progress 发布，不保存逐包列表、不驱动根 Compose、不新增无界日志。
   - 判读口径：`App丢块 > 0` 指向软件消费压力；`解码弃字节/坏帧 > 0` 指向字节流/帧边界异常；二者为 0 但 `seq Δ != 1` 指向 App 收到的板端/链路序号本身不连续。后两者仍需录制导出或板端日志最终区分。

### 必要测试与验收

- JVM：纵轴 200/600 尾窗；RAW 历史值时间不变；普通 gap 后 `n/800` 继续增长并在 800 点产生 metric request；真正输入 cursor 跳变仍硬重置并清除旧指标。
- fake BLE：长 ADS 连续流与高频小 gap 流都能填满 RED/IR/ECG 和生成指标；诊断中的最近 `seq/Δ`、gap/missing、decoder discard 与 queue drop 可分别构造验证。
- UI 纯函数/语义：中断标记只位于独立下方 strip，波形 path 仍在 break 处断开；无数据时不增加诊断重组负担。
- 完整执行 `test lintDebug assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy`。本轮不以真机完成为门禁，但下一轮真机必须记录诊断行并导出一份问题会话。

### 本轮不做

- 暂不继续处理未扫描时残留卡顿。
- 不修改 ADS1292R raw/CSV 的 uint32 口径，不改变 0.5–12 Hz CAUSAL/FIXED 参数、峰谷算法或综合 SQI 公式。
- 不用无界队列、无限日志或简单隐藏 gap 来掩盖数据完整性问题。

---

## V3.R8.1 RAW 纵轴中心锁定与分级扩展

### 复验结论（2026-08-22）

1. RAW 数组和右上角最新 ADC 数值没有随窗口增长被修改；`liveRawPeakUp` 仍是逐点取负，确认不是接收数据或 raw 变换漂移。
2. R8 的 200/600 规则只限制了“用哪些点计算候选上下界”，`WaveformPanel` 仍在每次约 5 Hz publication 时直接把新候选 min/max 作为当前坐标轴。滚动窗口中的极值进入、退出或轻微变化时，纵轴中心随之移动，因此屏幕上的既有历史点仍会出现缓慢上移和偶发回跳。
3. 该问题只属于绘图坐标系；不得通过去趋势、减均值、高通或修改 raw ring/CSV 来掩盖。

### 实施

1. **保留 R8 候选窗**
   - RAW 仍绘制最新最多 800 点；未满 800 点时从最近最多 200 点、满 800 后从最近 600 点计算带 8% padding 的候选范围。
   - CAUSAL/FIXED 的滤波、settling 与现有自动缩放不在本小轮改动；ECG raw/CSV 也不改。

2. **为 RED/IR RAW 增加有状态纵轴**
   - 每个数据来源、显示 generation、显示模式和通道各自持有一个有界纵轴状态；连接/协议重置、preview/recording 来源切换或离开再进入 RAW 时重新初始化。
   - 首个候选范围建立固定中心并预留安全边距。后续候选完全落在当前范围内时，纵轴上下界保持逐位相同，禁止因滚动极值收缩或中心抖动而改动既有点的屏幕坐标。
   - 候选越界时保持中心不变，仅对上下界做对称扩展；每次至少扩展一个固定比例并追加安全边距，避免连续多个 5 Hz 快照小步缩放。纵轴在本来源生命周期内不自动收缩；真实基线变化仍表现为波形相对固定坐标轴移动，而不是坐标轴追随数据。

3. **严格隔离数据与显示**
   - 状态机只消费 `WaveformVerticalRange` 并返回绘图范围，不写 `values`，不进入 `LivePpgSignalRuntime`、指标、文件或 BLE 路径。
   - 详细诊断行、图下 gap marker 和 R8 指标连续性规则保持不变。

### 必要验收

- JVM 纯函数：候选范围在轴内轻微上移/下移时输出上下界完全不变；单边越界时中心不变且范围按固定阶梯对称扩展；后续候选收窄不收缩；显式新状态可重新定标。
- UI 接线：只有 RED/IR RAW 使用保持轴，CAUSAL/FIXED/ECG 继续使用即时候选范围；preview 与 recording 切换会使用不同 reset token。
- 完整执行 `test lintDebug assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy`；真机重点观察右上角 ADC 数值稳定时，屏幕历史点是否还整体缓慢移动。

### 本轮不做

- 不处理未扫描时残留卡顿，不更改 BLE/decoder/录制/指标算法。
- 不加入逐点去趋势、滑动减均值或对 raw 数据本身的任何修正。
- 不为处理一次异常而加入无界历史、定时缩轴或持续动画；若真机仍移动，下一轮以 ADC 数值与轴范围调试值对点，不猜测数据漂移。

---

## V3.R8.2 即时低风险修正（本次完成，不计入 R9–R10 两轮）

### 定位结论

1. M7.6 的录制中手工血压能力没有被删除：`CaptureReferenceTimestamp`、`ManualBloodPressureDialog`、controller 幂等提交和 `blood-pressure.csv` 写入仍然存在。回归仅是 `LiveCaptureScreen.RecordingActionBar` 不再把 `onOpenBloodPressure` 接到按钮。
2. 实时闪烁/断裂不是短 marker 覆盖了波形；marker 已在单独的图下 strip。可见断裂来自 `WaveformPanel` 在每个 sequence gap 主动 `moveTo` 切断 path，gap 密集且滚动时会使大量短线段持续进出视窗。
3. 当前诊断只有累计数。可从现有有界计数安全派生两个估算值：`缺帧率 = missing / (decoded + missing)`，`异常帧率 = (missing + duplicate + out-of-order + invalid) / (decoded + missing + invalid)`。丢弃字节和 App 丢块不能精确换算成帧，仍只显示计数。

### 实施与验收

- 恢复录制底栏的「血压记录」按钮，不改现有弹窗和 sidecar 契约。
- 实时波形 path 按 accepted sample 连续绘制，sequence gap 仍在波形下方短刻度带告警；marker 不进入生理信号 Canvas 的前景层。
- 诊断行增加缺帧率/异常帧率，无分母时显示「—」，并用 JVM 覆盖 0 帧、普通 gap 和 duplicate/invalid 组合。
- 本小轮不改 raw/CSV、序号决策、滤波和指标输入。

---

## V3.R9 双路参考血压、录制表单与详情元数据闭环

### V3.R9.1 ATT MTU 协商前置（与 R9 同轮完成）

#### 背景与边界

- 当前 Android GATT 在连接回调后直接进入服务发现，没有显式请求 ATT MTU；168-byte 通知是否能作为单个 ATT payload 到达完全依赖系统/对端默认协商结果。
- App 在连接成功后、服务发现前请求 `MTU=247`（可用 ATT payload 244 bytes，超过 123 且可容纳当前 168-byte 帧）。Android 14 及以后系统可能把首个请求提升为 517，UI 和诊断必须记录回调给出的**实际值**，不能把 247 当成协商结果。
- MTU 请求是兼容性增强而不是链路修复证明：请求被拒、回调失败或超时均降级继续服务发现；开发板 sequence gap、BLE 通知丢失和板端缓存问题仍须依靠现有诊断与板端检修判断。

#### 开发任务

1. BLE owner 增加 `NegotiatingMtu` 阶段、MTU deadline、transport `requestMtu` 与 `onMtuChanged` 事件；严格保持 `connect -> request MTU -> callback/timeout fallback -> discover services` 的有序调用。
2. 目标 MTU 固定为 247；记录 `requested/actual/status/message`。成功回调和失败回调都只消费当前 connection generation/device/phase，迟到回调计入 stale，不得干扰已开始的服务发现。
3. Android `requestMtu()` 同步返回 false、权限/平台异常、回调失败和 5 s 超时均进入有界 FALLBACK 状态并继续连接，不因 MTU 协商单独断开设备。
4. 连接详情显示 MTU 协商状态及实际值；fake BLE 覆盖成功顺序、立即拒绝、超时降级和迟到回调。

#### 验收

- fake BLE 命令顺序中 `RequestMtu(247)` 必须早于 `DiscoverServices`；成功回调记录实际 MTU（允许 247/517 等大于等于 23 的值）。
- 请求失败与 deadline 超时后仍进入服务发现；迟到 MTU 回调不改变阶段或已记录状态。
- 完整 BLE contract、JVM、lint、构建和 privacy 门禁通过；真机 R9 节点额外记录请求值、实际值和原有丢帧诊断，二者分开判读。

### 问题溯源

| 用户可见问题 | 代码根因 |
|---|---|
| 录制前血压不是详情中的一次手工参考事件 | `CaptureForegroundService` 只把 SBP/DBP 写到 session metadata，`CaptureSessionWriter` 不会为它建立 `blood-pressure.csv` 事件，因此详情计数和时间轴都看不到 |
| 备注被下次录制继承 | `CaptureParticipantDraft.fromSnapshot` 无区分地回填 `additionalFields`，service 又把 `notes` 作为 subject profile 字段持久化 |
| 录制结束后血压/备注仍在表单 | `CaptureViewModel` 没有观察 RECORDING 到 FINALIZED/FAILED 的转换，也没有 session-scoped draft clear |
| 不改名时不立即报重复，点击后错误又粘住 | `CaptureGateDiskCache` 对同名有 1 s 缓存且录制完成时不 invalidate；service 二次 gate 返回的 `SessionAlreadyExists` 作为无名称作用域的 `runtimeFailure` 持续混入 UI gate |
| 其它同类表单状态漏洞 | 定时时长只在输入框显示错误，没有进入 `captureGate`，无效值会在开始时静默回落成 60 s；`participantDraftDirty` 又是 ViewModel 全局布尔值，从被试 A 改名到被试 B 后仍可阻止 B 的 profile 加载并留住 A 的资料 |
| 详情页少录制模式、完整生活方式和备注 | configuration 有 `recordMode`，但 `CaptureSessionMetadata` 没有 `record_mode`；详情只显示基本 participant 字段，没有 smoking/drinking/additional fields 和 planned duration |

### 开发任务

1. **双路参考血压共存**
   - 保留 R8.2 恢复的录制中手工登记，一次录制可追加多条。
   - 录制前血压同时保留在 session metadata 中，并在 writer 真正接受录制时写成 `event_index=0`、`source_sample_index=0`、`source_time_s=0`的手工 BP 事件；`dialog_open_utc` 和 `saved_utc` 都使用 `startedUtc`。录制中事件从下一个 index 继续，两种入口可在同一 sidecar 中共存。
   - 无完整 SBP/DBP 时不生成事件；不改手工参考的产品语义，不将其当成预测结果。

2. **会话级表单清理与 gate 失效**
   - 将 SBP、DBP、`notes` 定义为 session-scoped；subject profile 回填/持久化时过滤这些字段，老 profile 中已存的 notes 也不再回填。
   - 仅在实际经历过录制后的终态转换清除 session-scoped draft 和未提交 dialog，人口学/生活方式 profile 仍可为同一被试回填。
   - 终态一到达立即 invalidate 完成会话名的磁盘快照并刷新 gate；service 的重复名/表单类事务失败只在当前名称与当前磁盘事实仍匹配时参与 gate。改名、使用建议名或磁盘复核通过后必须解除旧失败。
   - 定时时长加入 typed gate；有错误时禁止开始，不再静默改为 60 s。`participantDraftDirty` 改为按 canonical subject 作用：同一被试只改 sequence 可保留当前编辑，改到另一被试必须加载其 profile 或空表单，不带入前一被试资料。
   - 为「同名录制刚完成」、「事务竞态拒绝后改名」、「录制失败但未真正开始」增加 StateFlow/gate JVM 回归。

3. **元数据和详情补齐**
   - metadata 增加 optional-decode 的 `record_mode`，继续保留 `planned_duration_s`；旧会话显示「未记录」而不猜测模式。
   - 详情页补齐 PPG/MB 录制类型、手动/定时模式、计划/实际时长、性别/年龄/身高/体重、吸烟/饮酒、会话备注、录制前参考 BP 和手工 BP 事件数。
   - 会话级 metadata BP 编辑仍是对该会话的后期更正，不伪造录制时手工事件，详情页分开标识两者。

### R9 验收与真机门闩

- JVM/file contract：无 BP、仅录制前 BP、仅录制中 BP、两者共存；sidecar event/time/index 单调；旧 metadata 解码；notes 不进 profile 回填；gate 不粘住；无效定时时长不能开始；跨 subject 不泄漏 draft。
- 完整 JVM/lint/build/privacy/lifecycle/BLE contract 门禁通过。
- **真机节点 1：**一次录制同时登记录制前 BP 和两条录制中 BP，停止后不改名即时显示重复，改名后可开始，BP/备注已清空，详情页的三个 BP 时点和录制资料一致。通过此节点后进入 R10。

### R9/R9.1 实施状态（2026-08-22）

- 已实现 MTU 247 请求、实际值回调、失败/5 s 超时降级、UI 请求/实际值和 fake BLE 顺序/迟到回调覆盖；降级不阻塞服务发现。
- 已实现录前 BP 第 0 条 sidecar 事件与录中事件共存、notes/profile 隔离、录后 session 字段清理、按名称作用域化 runtime duplicate、终态 gate cache 失效、typed duration gate 和跨 subject draft 策略。
- 已实现 optional `record_mode` 编解码/恢复保留，以及详情页 PPG/MB、模式、计划/实际时长、生活方式、notes、会话级 BP 与时间轴事件数的分开展示。
- `test lintDebug assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy` 已通过：223 JVM tests，0 failure/error/skip；lint、Debug/AndroidTest/Release、API/lifecycle/BLE/privacy contracts 均通过。
- 未执行真机；R9 真机节点增加记录 ATT MTU 请求/实际/FALLBACK 状态，并与 gap/missing/App 丢块诊断分开判读。用户随后明确要求在开发板检修期间先执行 R10，R9 真机结论仍保持待测。

---

## V3.R10 缺帧修复信号、指标来源与详情重放对齐

### 问题溯源

1. `CaptureSessionOfflineAnalysisService.loadRawInput` 在 gap 前把 missing samples 加入 `logicalSampleIndex`，但实时 `CaptureMetricEpoch` / `CaptureReferenceTimestamp` 和录制 CSV 使用连续 accepted-sample cursor。这使详情 PPG、录制期指标和手工 BP 不在同一时钟上，gap 越多错位越大。
2. `OfflinePpgAnalyzer.continuityRuns/stableSegments` 把每个 `breakIndex` 和 >15 ms 时间跳变当成硬断点，stable analysis 还在每个转换两侧约 1.5 s 标无效。密集 gap 会让 ZERO/FIXED 各段短于 32/201 点，也会让离线 8 s 窗口为空；这与 R8 实时端「保留有序 accepted stream」的口径不一致。
3. 详情已会读取 `metrics.csv`，并在无 sidecar 时用离线 artifact windows 后备；但 sidecar 缺失、损坏或「有行但所有指标无效」都被压成空列表，页面不显示来源/失败原因，且没有 artifact 时必然为空。
4. `CompleteSignalChart` 在波形 path 之后绘制全高 gap 线，并无开关；高 gap 密度时会遮盖信号。

### 开发任务

1. **显式区分 raw 与修复/分析信号**
   - raw 文件、raw ADC 值、accepted 顺序和 gap 证据保持不变；不回写源会话，不伪造板端样本。
   - 建立内存中的 repaired/analysis input：以 accepted sample 连续时钟 `index / 100 Hz` 对齐实时指标和 BP，值仍是原顺序的 accepted ADC；正常 raw 时两者逐点一致。gap 处不做虚构生理插值，只压缩已缺失的时间并保留 break marker/统计。
   - ZERO/FIXED、离线窗口和详情指标后备统一消费 repaired input；RAW stage 仍显示原 accepted ADC 与可选 gap 证据。修复口径、gap 数和来源 raw hash 写入新 analysis artifact，旧 artifact optional decode。

2. **指标来源和失败降级可见**
   - 加载结果区分 `PERSISTED_VALID`、`PERSISTED_NO_VALID_VALUES`、`SIDECAR_MISSING`、`SIDECAR_INVALID`，保留有界错误摘要，不再 `runCatching(...).getOrDefault(emptyList())` 静默丢原因。
   - 录制期指标有有效值时优先重放，并显示「录制期 1 Hz」；否则用当前 repaired input 重新计算的离线窗口指标后备，标明「离线重算」及降级原因。若两者都无证据，显示具体原因而非空白。
   - 指标和 BP 都以 accepted source cursor 对齐，测试覆盖大量 missing 后的首尾时点。

3. **详情 gap 呈现不再干扰波形**
   - 详情/工作台提供「显示缺帧标记」开关；高密度会话默认关闭，低密度可默认开启，用户选择只影响显示。
   - 开启时先画低 alpha 标记层，再画生理波形；marker 不再覆盖 path。RAW 可保留断点语义，repaired ZERO/FIXED 按修复输入连续绘制。
   - 诊断页始终保留 gap/missing/异常比例的数字证据，关闭 marker 不得隐藏数据质量问题。

### R10 验收与真机门闩

- JVM/file fixture：无 gap、单 gap、高密度 gap、duplicate/out-of-order/invalid 混合；raw 值和 hash 不变，repaired 点数与 accepted 一致，ZERO/FIXED 有界可用，8 s 窗口可恢复，metrics/BP 时间对齐。
- 指标来源四态、旧 artifact/metadata optional decode、marker 默认/开关/层级的纯 JVM/UI policy 测试；完整门禁通过。
- **真机节点 2：**用开发板修复前的高 gap 会话和修复后的会话各一份，对照 raw 诊断、repaired ZERO/FIXED、录制期/离线指标来源、BP marker 和 gap 开关。此节点通过即完成本次不超过两轮的开发。

### R10 实施状态（2026-08-22）

- 已将 raw evidence 与 repaired analysis input 分离：accepted ADC、顺序、raw hash 和 gap 证据不变；repaired input 只使用零基 accepted cursor 压缩缺失时间，不插值、不补点。ZERO/FIXED、离线分析和指标后备均使用 repaired input，RAW 仍可保留断点。
- analysis artifact 新增 optional `analysis_signal_profile`、`repair_gap_count`、`repair_input_sample_count`，并继续记录来源 raw SHA-256；旧 artifact 缺键可解码。
- metrics sidecar 四态和有界错误已显式建模；有效 sidecar 标记为「录制期 1 Hz」，其余状态使用当前 repaired input 离线重算。指标时间轴在无 artifact 时也随完整波形加载；无重算证据时显示原因。
- 详情/分析/横屏工作台提供 gap marker 开关；低密度默认开、高密度默认关。marker 在 waveform 前以低 alpha、像素去重绘制，RAW path 可断，ZERO/FIXED 连续；诊断页始终保留 missing/break/source 数字。
- 定向 fixture 覆盖无 gap、单 gap、高密度 gap、duplicate/out-of-order/坏帧混合、metrics 四态、BP accepted cursor、旧 artifact 和 marker policy。完整 `test lintDebug assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy` 门禁通过：231 JVM tests，0 failure/error/skip；lint、Debug/AndroidTest/Release、API/lifecycle/BLE/privacy contracts 均通过。
- 未执行真机；按用户指示先完成编码。R9 真机节点与本节真机节点 2 均保留待测，不能由上述自动门禁替代。

### R9–R10 不做

- 不修改板端 sequence 生成，不用 App 修复来宣称链路已无丢帧。
- 不将缺失样本插值成新 raw，不覆盖原会话或原 analysis artifact。
- 不引入未校准预测 BP/SpO2，不在这两轮重做 BLE transport 或暂缓的空闲页卡顿。

---

## 跨轮工程约定

- 每轮先补/改 **JVM 测试再改生产代码**（算法轮尤其如此）。  
- schema 新键一律 optional decode。  
- 不改 `references/需求V3.0/` 里的 Python。  
- 不把 SpO2、预测 BP 做成「看起来可用」。参考血压 UI 明确是人工填写。  
- 综合 SQI 颜色是显示逻辑；录制 CSV `sqi` 口径锁定，直到有单独需求再改。  
- CUP 与腕部协议分模式，禁止用 120-byte 解析器去啃 CUP 168 帧。
- 直播协议因设备而异：CUP **默认** batch 168；若 batch 无接受帧，则用与 Nordic 相同的帧几何探测 ads1292r 120，并允许手动覆盖。锁定 168 时 CUP 必须保持 `BATCH_COMPATIBLE`，禁止把 CUP 名切到 Nordic sensor packet。Nordic 的 120/168 仍靠几何探测，禁止把全部 Nordic 默认切到 120。两种 168 不得靠 payload 互猜。禁止用 120 解析器去啃已锁定的 CUP 168 帧。

## 建议的真机清单（全部延期，不阻塞轮次完成）

1. CUP 指尖：R1 命名/门控、R2 定时、R3 综合 SQI 颜色、R5 导出。  
2. 腕部仪器：R4 120-byte 实流、ECG 波形；若仍有 `Nordic_UART_Service` 168 固件，确认探测锁定 sensor packet 而非 ECG；与 CUP 互斥换机。  
3. 长录制与断流：R2 倒计时暂停/恢复。
