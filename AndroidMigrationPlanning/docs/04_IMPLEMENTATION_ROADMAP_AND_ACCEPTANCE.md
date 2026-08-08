# 实施路线、工作分解与验收

## 1. 计划假设

估算按“1 名资深 Android + 1 名可覆盖 DSP/测试的工程师，真机和固件可用，产品决策响应及时”计算。V1 建议 10–14 个日历周、约 100–145 工程人日；V1.1 另需约 35–55 人日；V2 专家工作台另估。若只有 1 人、真机协议未确认或需同时建立发布/隐私体系，应按依赖重新排期，不能简单把工期相乘。

所有 phase 都以门禁结束；门禁未过不能靠 UI 演示宣布完成。可并行项仅在契约冻结后并行。

## 2. 里程碑总览

| Phase | 目标 | 主要产物 | 估算 | 退出门禁 |
|---|---|---|---:|---|
| 0 | 契约与工程基线 | 设备抓包、ADR、Android repo/CI、版本表 | 8–12 人日 | 协议/设备/后台/SDK 决策有证据 |
| 1 | 纯 Kotlin 对等内核 | protocol、raw、signal、golden JVM tests | 22–30 人日 | 所有协议/算法金标通过 |
| 2 | BLE 真机垂直切片 | 权限、扫描、GATT、订阅、freshness、诊断 | 15–22 人日 | 真机稳定 receiving，故障状态确定 |
| 3 | 可靠录制与服务 | FGS、raw-first、CSV/JSON、stop/recovery/export | 22–30 人日 | 断连/满盘/重建/崩溃路径可审计 |
| 4 | V1 产品 UI | 实时页、波形、录制、历史、详情、重放 | 18–25 人日 | P0 UX + accessibility + 生命周期通过 |
| 5 | 系统硬化/发布 | 长稳、设备/API 矩阵、性能、隐私、release | 15–26 人日 | V1 Definition of Done 全部通过 |
| 6 | V1.1 离线工作台 | versioned analysis、stable segments、对比/导出 | 35–55 人日 | 固定会话与 Python/Swift 基线对等 |

## 3. Phase 0：契约冻结与工程启动

### 3.1 工作项

- 用至少 3 段真实 CUP 通知抓取确认：UUID、是否需 control write、header/tail、length 端序/含义、sequence wrap、RED/IR 顺序、通知分片、实际采样率、是否有 checksum/固件变体。
- 记录固件版本、设备型号和 calibration ID 的读取途径；若读不到，元数据写 `null`，不猜。
- 确认 Android `applicationId`、显示名、签名/分发渠道、`minSdk`、目标手机/平板、横竖屏、语言、隐私/保留/导出政策。
- 产品确认后台策略：推荐“开始后进入 FGS，后台/锁屏继续，通知停止”；是否允许划掉任务继续；是否需要页面退出确认。
- 冻结 V1 文件兼容目标：Android 输出是否必须由当前 iOS/Python 工具直接读取；确定 schema 只 additive 还是允许新版本。
- 新建 Android Studio 工程：Kotlin DSL、version catalog、Compose、静态检查、unit/instrumented tests、CI、slow/nightly 标签、dependency verification。
- 写首批 ADR：SDK/minSdk、模块、FGS ownership、raw-first、文件系统真源、算法版本策略。

### 3.2 退出门禁

- `golden_seq42.bin` 与真实抓取至少一帧由三方审阅；任何协议推测都有 issue/owner/date。
- API 37 构建和最小 API emulator 启动；CI 可运行 JVM tests、lint 和 debug assemble。
- [决策登记](06_OPEN_DECISIONS_AND_RISK_REGISTER.md) 中 D-001～D-008 已关闭或有明确不阻塞理由。

## 4. Phase 1：纯 Kotlin 内核

### 4.1 `:core:protocol`

- `CupBatchProtocolV1` 常量、unsigned LE reader、frame/sample 模型。
- `CupBatchStreamDecoder`：任意 chunk、resync、分类 diagnostics、最大缓冲保护。
- `CupFrameSequenceTracker`：first/continuous/gap/duplicate/out-of-order/wrap。
- `CupStreamingPipeline`：accepted samples、recent ring=800、完整 diagnostics。
- 把 [CUPBatchProtocolTests](../reference_sources/ios_current/PPGCollectorTests/Protocol/) 转为 JUnit/kotlin.test；加载 [golden fixture](../reference_sources/protocol/fixtures/)。
- 增加 property tests：随机拆分同一字节流输出不变；前导/中间噪声最终 resync；decoder pending 有界。

### 4.2 `:data:session` 的纯文件部分

- `CupRawWriter/Reader`、magic、LE record header、64 KiB 防御上限、safe prefix report。
- CSV schema/formatter/parser audit；Locale.ROOT、转义、行尾和空值规则。
- session metadata serializers；Swift/Kotlin JSON cross-read fixture。
- inspection/replay 先用 JVM temp directory 实现，同一 production pipeline。

### 4.3 `:core:signal`

- 移植 fixed SOS/DC/zscore、gap reset；不要先引第三方 DSP 抽象。
- 移植 SciPy peak detector，再移植 HR；保持 tie-break、双极性、DFT 和 confidence。
- 移植 SQI、ratio 和 `MetricResult`；区分 value/valid/provisional/reason/version/source time。
- 移植 live window scheduler：800/100、generation、bounded arrays。
- 导入 [preprocessing](../reference_sources/signal_fixtures/preprocessing/)、[heart-rate](../reference_sources/signal_fixtures/heart_rate/)、[SQI](../reference_sources/signal_fixtures/sqi/) fixtures。

### 4.4 数值门禁

| 模块 | 主要门槛 |
|---|---|
| SOS coefficient | 绝对误差 ≤ `1e-15` |
| preprocess 中间/输出 | 既有向量绝对误差 ≤ `1e-8`；gap/state 精确 |
| HR/peak | 既有 scalar/debug 字段通常 ≤ `1e-10`，index/grade/reason 完全相同 |
| SQI | 主要 scalar ≤ `1e-10`，部分严字段 `1e-12`；cycle/index/grade 完全相同 |
| protocol/raw/schema | 字节、计数、列顺序完全相同，无浮点容差 |

若 JVM `sin/cos` 或优化导致极小差异，先定位字段和原因，提交 parity report；只能针对已解释字段设局部容差，禁止全局改成 `1e-5`。

## 5. Phase 2：BLE 垂直切片

### 5.1 工作项

- Permission coordinator：API 26–30、31+、33+ 通知；永久拒绝/系统设置返回。
- Scanner：超时、停止、去重、名称前缀、RSSI 更新、蓝牙 adapter 状态。
- GATT adapter：generation、per-stage deadline、NUS/FFF0 profile registry 与按 service 自动选择、characteristics、CCCD、disconnect/close。
- `RawNotificationChunk`：复制 bytes、monotonic timestamp、bounded channel。
- freshness tracker、pipeline diagnostics、5 Hz waveform snapshot；先做最小 debug Compose 屏。
- fake GATT/clock 测试；真机抓取与 golden 逐样本比较。

### 5.2 故障测试

- 用户拒绝/撤销权限、蓝牙关闭、设备消失、connect timeout、未知/缺 service、两组 profile 各自缺 notify、CCCD 失败。
- 晚到 callback、快速 connect A → disconnect → connect B、重复 subscribe callback、onServiceChanged。
- 通知逐字节/随机分片/多帧粘包、sequence wrap/gap/duplicate/out-of-order、2 s 无 accepted sample。
- 20 次 connect/subscribe/disconnect 循环；每轮 GATT close 一次、任务/receiver 无增长。

### 5.3 退出门禁

- 目标 CUP 真机 30 分钟 receiving，无 app 主动造成的缺帧/丢 raw；诊断与抓取计数一致。
- API 30、31、34、36 至少覆盖权限边界；无权限时 app 不 crash/死循环。

## 6. Phase 3：可靠录制、前台服务与恢复

### 6.1 会话与写入

- 实现 name gate、20 MiB 基线预检、唯一目录、initial incomplete metadata。
- `SessionWriterActor` 按 raw append+sync policy → decode → CSV；pending channel 256。
- 1 s flush/sync/metadata checkpoint；指标取当前已完成 snapshot，记录 source index/time。
- `CaptureLifecycleGate` 与所有 stop reason；并发 finalizer/延迟写错误测试。
- repository/list/inspection/replay/hash；2 h 文件流式扫描不整载入。

### 6.2 前台服务

- Manifest service type/permissions、notification channel、Start/Stop action、绑定/重绑。
- 可见 Activity 发起 `startForegroundService`；先有 session reservation，再将 connection/writer ownership 交给 service，失败回滚。
- 旋转、进后台、锁屏、process/activity recreation、task removed 和 Active apps stop 的行为测试。
- `START_NOT_STICKY`；app 下次启动扫描 incomplete metadata 并提示 inspection/recovery。

### 6.3 恢复与导出

- 原目录只读 inspection；恢复到新目录：hash、safe raw/CSV prefix、staging、provenance、atomic move。
- SAF export 以 copy 流式输出并显示 progress/cancel；FileProvider 分享只暴露临时 export staging。
- 明示内部数据随卸载删除；没有用户动作不写共享存储。

### 6.4 故障注入门禁

| 场景 | 期望 |
|---|---|
| 用户/通知同时停止 | 第一个 reason 获胜，close 一次，complete/inspection 一致 |
| 设备断电/2 s stale | 停止、保留 raw、metadata 记录 disconnect/timeout |
| queue overflow | 不静默丢 raw；resource stop，incomplete 可恢复 |
| 磁盘耗尽/raw write error | 不派生未落 raw 的 CSV；safe prefix 可读 |
| CSV write error | raw 已保留；session 不冒充 verified complete |
| 进程 SIGKILL | 最近 checkpoint 以前可读；下次启动识别 incomplete |
| 截尾 raw/CSV/JSON | inspection 分类；recovery 不修改源且只复制完整前缀 |
| 重名/路径穿越 | 开始前拒绝，任何旧目录不变 |

## 7. Phase 4：V1 Compose 产品 UI

### 7.1 页面任务

- **Devices/Live**：permission/adapter/scan/connection phase、CUP 列表、错误/重试。
- **Waveform**：Canvas 双轨、8 s、5 Hz、独立 Y、min/max bucket、暂停诊断可选。
- **Metrics**：HR、SQI、R 诊断、SpO2/BP unavailable；valid/provisional/source time。
- **Capture**：name field、gate explanation、开始/停止、elapsed、raw queue/write/flush 状态。
- **Sessions**：文件系统列表、complete/verified/finding、版本/stop reason。
- **Detail**：inspection、raw/preprocessed replay、zoom/pan、导出、恢复入口。

### 7.2 UI 验收

- 关键状态 screen test/screenshot test；动态字号、TalkBack label、最小点击区域、深浅主题。
- Canvas 不为每个样本创建 Composable；连续 30 min 帧时间/heap 平稳。
- Activity 重建、导航返回、重复点击 start/stop、权限弹窗返回都不重复副作用。
- 错误信息给行动建议（授权、开蓝牙、重连、释放空间、恢复），内部异常放 diagnostics 而非直接给用户堆栈。

## 8. Phase 5：硬化与发布

### 8.1 自动门禁

CI 分层：

- 每 PR：format/static analysis、JVM golden/unit、module boundary、debug assemble、核心 instrumented smoke。
- nightly：随机分片/property、30 min/2 h 模拟、API emulator matrix、macrobenchmark、leak checks。
- release candidate：真机 BLE/锁屏/后台/厂商矩阵、真实 2 h、导出/恢复、签名 release、依赖/许可证/隐私检查。

把当前 Swift 测试套件按如下方式迁移：

| Swift 套件 | Android 目标 |
|---|---|
| `Protocol/*` | `:core:protocol` JVM |
| `SignalProcessing/*` | `:core:signal` JVM golden |
| `Bluetooth/*` | fake clock/GATT JVM/Robolectric + instrumented |
| `Storage/*` | JVM temp FS + Android internal/SAF instrumented |
| `CaptureSessionControllerIntegrationTests` | service/use-case coroutine integration |
| `BLECentralServiceIntegrationTests` | fake adapter + CUP 真机 acceptance |
| `LongDurationDataPathTests` | nightly JVM + real device endurance |
| waveform/viewport | pure math JVM + Compose Canvas screenshot/perf |

### 8.2 长稳门槛

30 min 和 2 h 模拟应保持当前精确断言：

- input frames、按所选 wire profile 计算的 received bytes、decoded/accepted samples、raw chunk count 完全相等；invalid/discard/missing/duplicate/out-of-order 为 0（无故障输入）。
- `recentSamples=800`、metric buffer=800、decoder pending=0；metric window ends 从 799 开始每 100 样本一次。
- waveform publications=duration×5；raw/CSV/metadata/replay 数量一致。
- raw replay record peak buffer 不超过 record 防御上限；CSV read buffer <66 KiB；heap/RSS 无持续随时间增长趋势。
- 模拟执行预算参考 iOS：30 min <60 s、2 h <240 s；如 CI 硬件差异，用相同 runner 的基线回归阈值替代，但正确性断言不变。

真实设备 2 h 另记录：missing/invalid、queue high-water、flush latency p50/p95/max、metric latency、UI jank、heap/native heap、CPU、温升、电量、厂商电池策略、服务是否存活。真实无线环境允许设备端缺帧，但必须精确记录且不能由 app 静默造成。

### 8.3 发布检查

- `targetSdk=37`，release minify/resources shrink 后 BLE/serialization/FileProvider 正常。
- 数据安全表、隐私政策、权限说明与实际一致；不声明 location/health/notification 超出需要。
- 前台服务通知文案说明持续采集；app 内说明卸载删除内部会话和如何导出。
- release 无 Python runtime、fixture、生理测试数据、调试菜单（除明确受控 diagnostics）。
- 签名、versionCode、回滚、崩溃符号、内部测试渠道和固件兼容表就绪。

## 9. Phase 6：V1.1 离线分析与工作台

### 9.1 算法/数据

- analysis service 从 raw file 重放，不从 CSV 反推原始波形。
- 移植 `segmented_pulse.py`：settling、稳定/过渡段、8 s/2 s window、通道/极性、拒绝、BPM cluster、weighted median。
- 移植 offline average cycle/CI、spectrum、peak list；明确 live causal 和 offline zero-phase profiles 不同。
- 结果 JSON 包含 input raw SHA-256、source session ID、analysis/profile/version、started/ended、warnings、metrics、segments/windows/peaks；不可覆盖。

### 9.2 UI

- Analysis history/status/cancel；Overview 和 Workbench stage 导航。
- Compare：两个会话 stacked/full/unified cycles，时间轴/归一化语义清晰。
- Diagnostics/PPG/Spectrum/Cycle 按 Python GUI 意图实现，但采用 Compose/Canvas，避免照搬 Qt 组件层级。
- IMU tab 不显示或明确 unsupported，直到有 Android/固件数据契约。

### 9.3 门禁

- 对固定 raw 会话，Python 与 Kotlin 的稳定段边界、接受窗口、通道、BPM、峰和平均周期在字段级报告中对等。
- cancel/restart/version change 不覆盖旧结果；分析过程中进后台由 WorkManager 或前台策略另行决策，不能假设普通 coroutine 永久存活。

## 10. 缺陷分级与发布阻断

- **Blocker**：raw 丢失/错序、覆盖会话、格式不可恢复、GATT/writer 双实例、权限/FGS crash、算法错误却标 valid。
- **P1**：可重复的断连恢复失败、指标/CSV 时间错位、长稳内存增长、inspection 漏报、导出损坏。
- **P2**：诊断计数/错误提示/波形交互问题，不影响原始数据与有效性。
- **P3**：视觉细节和非核心工作台增强。

Blocker/P1 为零才可进入 release candidate；不能用“用户可重试”豁免数据完整性问题。

## 11. 建议 issue/epic 划分

```text
EPIC-A Contract & Android foundation
EPIC-B Protocol/raw parity
EPIC-C Signal parity
EPIC-D BLE & permissions
EPIC-E Capture FGS & session writer
EPIC-F Inspection/recovery/export
EPIC-G Compose live/capture UI
EPIC-H Sessions/replay UI
EPIC-I Reliability/performance/release
EPIC-J Offline analysis/workbench (V1.1)
```

每个 issue 至少写：需求 ID、源索引链接、输入/输出契约、错误路径、测试类型、完成证据、是否影响 schema/algorithm/profile version。

## M7.1–M7.2 进度注记（2026-08-08）

M7.1 数据合同/命名/profile 内核与 M7.2 RAW display、PI、epoch/source sidecar wiring、fixed-lag candidate 已实现；两轮均执行同一综合 Gradle gate 并通过。M7.3 录制身份/参考血压、M7.4 archive/batch export、M7.5 UI/IME 收尾仍为下一阶段，不把本地 build 通过当作真机或数值准入。
