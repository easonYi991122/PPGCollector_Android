# 当前代码事实（以 app/src 为准）

阅读日期：2026-08-22。本文件以 Kotlin 实现和文末逐轮补充为准，不引用旧移植状态文档。

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

## R8 实际实现补充（2026-08-22）

- 实时绘图仍保留最近 800 点，但纵轴不再由整个显示窗口控制：未满 800 点时参考最近最多 200 点，满 800 点后参考最近 600 点；RAW 数值仍仅取负，历史点本身不做视窗相关变换。
- R7 所述“普通 sequence gap 重置指标 warmup”已由本轮替代。`LivePpgSignalRuntime` 现在把 wire gap 作为数据完整性事件：累计 gap/missing、切断绘图 path，但保留同一有序 accepted-sample ring、预处理状态和 800/100 指标调度。只有本地 accepted cursor 丢失、App preview/analysis 队列丢输入、连接/协议代际切换或处理失败才执行硬重置并清除旧指标；preview 溢出会丢弃其过期 backlog，但不影响独立的 recording raw sink。
- sequence-gap 标记仍保留用于真机诊断，但已从贯穿波形的竖线移到每个波形面板下方的独立短刻度带，不参与纵轴，也不连接断点两侧 path。
- Preview 与 recording 的低频快照新增链路诊断：解码帧数、最近 UInt32 `seq/Δ`、gap/估算缺帧、duplicate/out-of-order、decoder 丢弃字节/坏帧和 App 队列丢块。诊断跟随既有约 5 Hz 发布节流，不保留逐帧日志。
- 诊断口径：`App 丢块 > 0` 优先排查 App 消费压力；decoder 丢弃/坏帧大于 0 排查通知字节流或帧边界；二者为 0 而 `Δ != 1` 表示 App 收到的完整帧序号已不连续，仍需用板端日志区分开发板发送和 BLE 链路丢通知。
- 未扫描时的残留卡顿按用户决定暂缓，R8 未继续修改该路径。真实设备的序号来源和 ECG CSV 对点仍待测。

## R8.1 实际实现补充（2026-08-22）

- R8 的 200/600 规则继续生成每帧候选范围，但 RED/IR RAW 不再让约 5 Hz 更新的候选 min/max 直接替换当前坐标轴。
- 每个 preview/recording 来源、signal generation、显示模式和通道独立持有 `LiveRawWaveformAxisRuntime`。首次 RAW 候选建立固定中心和 15% 安全边距；候选位于当前轴内时上下界完全不变，越界时保持中心并至少按 25% 阶梯对称扩展，本来源内不自动收缩。
- 因此真实 ADC 基线变化会表现为波形相对固定轴移动，而滚动极值的进入/退出不会再让纵轴追随并制造缓慢上移/回跳。状态机只返回绘图范围，不写 waveform array、指标、BLE 或文件。
- CAUSAL/FIXED/ECG 继续使用 R8 的即时候选范围；未扫描卡顿和信号算法不在 R8.1 范围。

## R8.2 实际实现补充（2026-08-22）

- M7.6 已有的录制中手工 BP 时间冻结、dialog、controller 幂等提交和 `blood-pressure.csv` 仍然完整；R8.2 将「血压记录」按钮重新接回录制底栏。录制前 BP 已在 R9 转为开始时第 0 条手工事件。
- 实时 gap marker 仍在波形面板下方的 7 dp 独立 strip；生理曲线不再在每个 marker 处切成大量短 path，避免高 gap 滚动时的闪烁/碎片感。gap/missing 数字证据不变。
- 低频诊断行新增两个估算比例：缺帧率使用 `missing/(decoded+missing)`，异常帧率使用 `(missing+duplicate+out-of-order+invalid)/(decoded+missing+invalid)`。decoder 弃字节和 App 丢块无法准确换算帧数，仍保留独立计数。
- 离线 raw replay 在 gap 时使用了与实时 metric/BP 不同的时钟，并将密集 gap 全部作为硬分段；该剩余缺口属于 R10。

## R9/R9.1 实际实现补充（2026-08-22）

- GATT 连接后先请求 ATT `MTU=247`，收到实际 MTU 或失败/5 s 超时后再发现服务；拒绝和超时只进入 FALLBACK，不单独断开。Android 14+ 可能返回 517，诊断显示请求值与实际值。该能力不能证明板端 sequence gap 已修复。
- 完整录前 BP 在 writer 接受会话时同时保留 metadata，并写入 `blood-pressure.csv` 的第 0 条、source time/index 0；录中 dialog 从第 1 条继续追加。两路可以在同一会话共存。
- `notes` 与录前 BP 是会话字段：notes 不再写入/回填 subject profile；实际录制进入终态后清空 BP、notes 和未提交 dialog。重名 runtime failure 只在当前名称与磁盘事实匹配时进入 gate，完成终态立即使缓存失效。
- 定时录制无效时长现在是 typed gate failure，不再静默回落到 60 s。participant dirty 状态按 canonical subject 作用，同一 subject 换序号可保留编辑，切换 subject 必须重新加载对应 profile。
- metadata 新增 optional `record_mode`；详情页从 stem 显示 PPG/MB，补齐模式、计划/实际时长、生活方式、notes、录前会话级 BP 和时间轴 BP 事件数。旧 metadata 不猜测模式。

## R10 实际实现补充（2026-08-22）

- `CaptureSessionOfflineAnalysisService` 现在同时构造 raw evidence input 和 repaired analysis input。二者共享原始 accepted ADC 数组及顺序；时间为零基 accepted cursor `index / 100 Hz`。gap 不再向分析时间轴补 missing sample，不插值或写回源文件，只在 raw input 中保留 break index 与 replay 统计。
- `OfflinePpgAnalyzer.analyze`、全程 ZERO、FIXED 和离线指标后备统一消费无 break 的 repaired input。因此高密度 sequence gap 不会再把滤波切成不足 32/201 点的碎段，也不会仅因 gap guard 使 8 秒窗口消失；RAW stage 仍显示原 accepted ADC 并可选择 gap evidence。
- analysis JSON 继续保留来源 raw SHA-256，并在 input 下 optional 记录 `analysis_signal_profile=accepted-order-gap-compression-v1`、repair gap 数和 repaired 点数。旧 JSON 不含这些键时返回 `null`，不猜测旧分析口径。
- `CaptureSessionSignalTrace` 的指标证据区分 persisted valid / persisted no-valid-values / sidecar missing / sidecar invalid；后三态基于当前 repaired input 重算接受窗口，界面明确显示「录制期 1 Hz」「离线重算」或不可用原因。录制期 metrics 和人工 BP 均用 accepted source sample index/time 对齐。
- 详情波形区即使没有 analysis artifact 也会显示指标时间轴。详情和横屏工作台的 gap marker 可开关；低密度默认开、高密度默认关。marker 在波形下层低透明绘制并按像素去重，RAW path 可按 break 断开，ZERO/FIXED 保持连续；missing/break/source 诊断数字始终保留。
- R10 完整自动门禁为 231 JVM tests、0 failure/error/skip；lint、Debug、AndroidTest、Release 与 API/lifecycle/BLE/privacy contracts 全部通过。开发板修复前/后的真机会话对照仍待执行。

## R11 实际实现补充（2026-09-04）

- 录制启动现在把录前 BP sidecar 写入视为启动事务的一部分；writer 只有在该写入和状态初始化成功后才发布为 active。初始化异常会清空队列、关闭/删除无 raw 的临时会话并发布 typed `RecordingStartFailed`，service 不再把文件错误伪装成设备未就绪；同一 controller 可在失败后重试。
- `Ads1292rStreamDecoder` 暴露 bounded pending byte count；`CupRawReplayEngine` 对 ADS 120-byte 使用 ADS decoder 的 frames/invalid/discarded/pending 统计，CUP/Nordic 168 仍使用原 decoder。序号异常仍来自共同 tracker；leading alignment 只从当前活动 decoder 统计。
- 恢复副本保留 session BP、BP 更新时间、record mode/planned duration、ECG sample rate 与 participant；ECG 文件指针来自实际复制的 sidecar。详情 inspection 对恢复副本允许 metadata session id 或 recovery source session id 的 sidecar 身份，源 CSV/ECG 内容不被改写。
- R11 新增启动故障、ADS replay、ADS+ECG/BP recovery JVM/file-contract 回归。完整 R11 门禁：235 tests（当前工作区含用户新增 UI 测试），0 failure/error/skip；lint、Debug/AndroidTest、Release、API/lifecycle/BLE/privacy 均通过。真机仍待测。

## R12 实际实现补充（2026-09-04）

- 详情和全屏工作台 RAW 使用与实时一致的逐点取负；兼容名称 `rawPeakUpForPlot` 不再移除 DC/线性趋势，源数组与 gap evidence 不变。
- metrics sidecar 读取校验允许的 session/provenance、非负且单调的 epoch/cursor、`source_time_s == source_sample_index / 100`、有效值有限性及 raw cursor 范围；恢复副本可使用 recovery source session id。无效或缺失 sidecar 的离线重算失败会保留 raw/滤波结果并显示有界不可用原因。
- 指标时间轴保留无值 epoch/离线 rejected window 的 source cursor；UI 按无效 epoch/大 cursor 跳变断线，单个有效点绘制圆点，并将录制期模板 SQI 标为「录制 SQI」。
- `loadSignalTrace` 先通过 `onPartial` 发布 raw/滤波 signal，再加载 sidecar/后备指标；SessionsViewModel 可先展示波形，旧任务切换仍按目录校验。R12 未设置未经测量的性能目标或引入无界缓存。
- R12 新增 RAW 变换、metrics 校验、partial signal 和指标断线策略回归。完整门禁：242 JVM tests，0 failure/error/skip；lint、Debug/AndroidTest、Release、API/lifecycle/BLE/privacy 均通过。真机仍待测。

## 跨端审阅现状（2026-09-27）

- 录制 service 接受启动时进入 `STARTING` 并先建立前台通知；初始化在 I/O dispatcher 执行。service 生命周期事务保留到 writer 真正进入 `FINALIZED` 或 `FAILED` 后才拆 raw sink、移除通知、停止 service、释放录制 owner。启动初始化和异步终结不会因 Activity 销毁而取消。
- 录制期后台采集走独立 raw sink。健康监视每秒检查连接 generation、Subscribed/Receiving phase 与 freshness；代际/连接失效立即以 `DEVICE_DISCONNECT` 结束，freshness 连续 stale 超过 5 秒以 `DATA_TIMEOUT` 结束。它证明的是应用观察到合法新帧的健康状态，不证明设备或 BLE 在所有真机后台场景下持续可靠。
- `CaptureRecordingController` 为每次会话递增 `sessionToken`；指标、BP 与 participant 队列项按 session/token 校验。应用级 recording-owner 防重复录制；文件访问 registry 对写入、导出快照、删除和恢复持有互斥 lease。停止时先阻止新写入、排空 worker/sidecar 队列，再完成 writer force/finalize；这些 JVM 结果不等同于断电耐久性实测。
- BP 手工事件冻结当时 accepted PPG cursor，时间以 100 Hz cursor 推导并由 writer 单调追加；CSV/metrics/BP/ECG 检查器验证 header、身份、行数、时间/cursor 与值域。CSV `sqi` 仍由模板匹配写入；Combo SQI 是暂定实时反馈，不是已校准医学结果。
- 新 recovery metadata 键保持可选解码。`source_session_id` 指向 recovery 链的原始 session 身份，`parent_session_id` 指向直接父 recovery/session；source hash 将来源内容与本次安全前缀副本关联。恢复仅读源并复制 `.cupraw`/CSV/sidecar 安全前缀，不改源 raw。缺少新增字段的旧 session、旧 artifact 继续按旧路径读取。
- 会话列表摘要与 artifact 摘要采用有界读取；离线信号按内存预算和分桶摘要控制绘图量，raw/filter partial trace 可先展示，后续 stage 或指标可单独显示预算不足/失败。pending export 以 SavedStateHandle 中的 token、类型和目录保存，Activity result 仅消费匹配 token 的请求。
- 通知权限未授予时采集 gate 给出明确阻止原因及设置入口。紧凑实时指标 caption 去掉已由 tile 数值承载的末尾括号分数，并使用较小字体/行高；此为显示压缩，不改 metric provenance 或值。
- 本轮实际 Swift writer 导出由 `ReviewCrossPlatformContractTest` 的 `-PppgReviewSwiftFixtures=<zip>` 参数交给 Kotlin reader；当前 zip 有 6 sessions / 34 entries，SHA-256 为 `008ab9f12adb4c09d54903ec8ed106e23413e01c6569639751b8748eedd08d5e`。2026-09-27 JVM 336/336 通过；真机后台长流、通知权限、布局/字体与真实 session 回看仍未实测。

## R7 后代码结构审计（2026-08-20）

- `app/src/main/java` 共 76 个 Kotlin 文件、约 2.14 万行；`core/ble`、`core/protocol`、`core/signal`、`data/session` 的分包边界与当前项目规模相称。短文件主要承载单一协议、策略或文件契约，不适合为了减少文件数量而合并。
- 明确的维护问题是采集页单文件过大而不是文件过多：`LiveCaptureScreen.kt` 原有 1287 行，同时承担页面编排、连接/波形区和完整录制表单，并保留一套无调用的旧表单实现。
- 录制表单及输入辅助组件已移到 `CaptureSetupComponents.kt`，旧表单副本已删除；`LiveCaptureScreen.kt` 收敛到约 638 行，只保留页面编排、连接/实时波形和录制操作栏。调用参数、Compose key、状态所有权及 UI 文案均未改变。
- 其余超千行文件集中在离线工作台、保存会话界面和离线分析器，各自仍是单一功能域；本次不做跨包搬迁或为了行数继续拆分，避免制造只转发一层的碎片文件。后续只有在这些功能发生实质开发时再按屏幕/算法阶段拆分。
