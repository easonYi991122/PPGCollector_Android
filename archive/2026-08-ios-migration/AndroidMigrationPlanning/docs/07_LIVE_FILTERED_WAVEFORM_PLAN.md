# 实时因果滤波波形分析与开发规划

> **适用性提示（2026-08-08）**：本文保留 M6/M7.2 已实现的 0.6～4 Hz causal 历史基线；M7.2 另增加 `fixed-lag-fir-0.5-12hz-0.1` display candidate、PI 与统一 source epoch。fixed-lag 尚未完成真实 CUP/zero-phase 数值准入和真机门禁；后续产品交互仍以 [`09_M7_CAPTURE_TRACEABILITY_SUBJECT_ARCHIVE_PLAN.md`](09_M7_CAPTURE_TRACEABILITY_SUBJECT_ARCHIVE_PLAN.md) 为准。

日期：2026-08-02

Migration：`M6` live UI/runtime 增量；若未来改变 live profile/version，再建立独立算法版本。

状态：A/B/C 已于 2026-08-02 的 `M6` 增量实现并有 JVM/构建证据；D 性能与真机门禁待执行。用户已明确实时页默认显示 CAUSAL，RAW 仍可随时切换。

## 1. 目标与非目标

目标是在 BLE 实时采集期间同时提供 RAW RED/IR 与平滑、可解释的因果 0.6–4 Hz RED/IR 波形，并保证屏幕上看到的 filtered samples 与 HR/SQI/R 使用的预处理样本来自同一状态机。UI 可以切换 raw/causal，继续使用 8 s bounded ring、默认 5 Hz snapshot 和保序 min/max Canvas。

非目标：

- 不把离线 `sosfiltfilt`/zero-phase 放进实时路径。zero-phase 需要未来样本，实时使用会引入等待、边缘重算或伪造低延迟。
- 不把显示滤波结果写回 raw 或覆盖 CSV 已写行；raw notification 仍是恢复真源。
- 不改变 HR/SQI/R 的有效性、版本或 cadence，不新增 SpO2/BP/医疗含义。
- 不用 UI 插值制造设备没有采集的样本，也不让 Compose animation 掩盖 gap。

## 2. 参考行为与实施前 Android 差距

### Python GUI

`online.py:OnlineProcessor._push_ppg` 对每个 PPG sample 先用 0.5 s DC tracker 去基线，再以 SciPy Butterworth 3 阶 0.6–4 Hz SOS 因果处理 RED/IR；raw 与 filtered 均保留在 bounded deque，约 1 Hz 更新 HR。`visible_y_range` 只在 Y 轴估计时排除最早 2 s settling，不删除原样本。其 snapshot 是 GUI-friendly immutable copy，packet reader 不逐样本驱动绘图。

### Swift

`PPGPreprocessor.swift` 固化 `ios_baseline_0.1`：100 Hz、DC alpha `0.019801326693244747`、三段 SOS、gap reset、RED preserve/IR polarity 仅按各算法要求处理。`PPGLiveMetricRuntime` 以 800 samples/100 samples cadence 使用因果 bandpass；`CUPDualWaveformPreview` 明确区分 raw、causallyPreprocessed 与 replay viewport，显示切换不改变 detector 输出。

### 实施前 Android（本轮已关闭）

- `LiveMetricWindowScheduler` 已有 RED/IR 各自的 `PpgPreprocessor`，逐 accepted sample 生成 raw + causal bandpassed ring；但只在 800 samples warm-up 后、每 100 samples形成 analysis request。
- `LiveWaveformSnapshotScheduler` 同时独立维护 raw RED/IR 800-sample ring，并按默认 5 Hz 发布。
- `BlePreviewRuntime` 对同一 accepted frame 先调用 raw waveform scheduler，再调用 metric scheduler。因果输出已被计算，但没有进入 5 Hz waveform snapshot；若直接再加第三套 UI filter，会形成重复状态、gap reset 漂移和额外 CPU。

## 3. 已实施架构

新增纯 Kotlin `LivePpgSignalRuntime`，成为每个有序 preview/recording owner 内唯一的逐样本 PPG 预处理状态所有者：

```text
accepted frame
    │
    ├── raw-first capture owner（保持不变）
    │
    └── LivePpgSignalRuntime
          ├── RED/IR PpgPreprocessor（唯一状态）
          ├── bounded raw + causal ring（各 800）
          ├── 5 Hz DualStageWaveformSnapshot
          └── 800/100 LiveMetricAnalysisRequest
```

规划接口如下；实现复用扩展后的 `LiveWaveformSnapshot` 保持已有调用兼容：

```kotlin
data class DualStageWaveformSnapshot(
    val generation: Long,
    val publicationSequence: Long,
    val sourceSampleStartIndex: Long?,
    val sourceSampleEndIndex: Long?,
    val measuredAt: Instant?,
    val rawRed: DoubleArray,
    val rawIr: DoubleArray,
    val causalRed: DoubleArray,
    val causalIr: DoubleArray,
    val preprocessProfile: String,
    val warmupSampleCount: Int,
)

data class LivePpgIngestResult(
    val waveform: DualStageWaveformSnapshot?,
    val metricRequest: LiveMetricAnalysisRequest?,
)
```

实现时应把 `LiveMetricWindowScheduler` 的 preprocessor/ring/cadence 逻辑迁入或委托给该 runtime，而不是复制。`BlePreviewRuntime` 仍在单一有序 worker 中调用它；BLE callback、raw writer、Compose 主线程不执行 SOS/DFT/I/O。

## 4. 显示语义

- Live 页新增 `RAW / CAUSAL 0.6–4 Hz` 切换；用户已在本轮明确要求同步显示滤波后信号，因此默认 CAUSAL，RAW 仍为随时可切换的真源视图。
- CAUSAL 模式显示 `ios_baseline_0.1 · 因果 · gap reset`，不得标为 zero-phase。离线 Sessions 工作台继续标 `scipy-sosfiltfilt-parity-0.1`。
- RED/IR 共用 X 时间范围、独立 Y。Y 范围计算可采用 Python 意图：窗口超过 2 s 时，settling 前 2 s 仍画出，但不参与自动 Y；gap 后重新进入 warm-up 并显示半透明区域/状态。
- 5 Hz snapshot 中每次携带完整 800-sample bounded ring；Canvas 继续按像素保序保留 extrema。不得把滤波结果逐 sample 发布到 StateFlow。
- “丝滑”优先来自稳定的 100 Hz 因果输出、固定 5 Hz 快照和无 GC 的绘图缓存。若真机 trace 证明 5 Hz 视觉不足，可仅将显示刷新配置提高到 10 Hz（仍在现有 1–60 Hz contract 内），不得提高指标 cadence 或伪造中间 PPG 点。
- source sample index、generation、freshness、warm-up 必须随 raw/causal 一起变化；切换显示模式只读同一 snapshot，不重跑历史窗口。

## 5. Gap、启动与错误边界

- connection generation、accepted sample index 不连续或 `CupSequenceEvent.Gap` 时，原子清空 raw/causal rings、两通道 SOS/DC state、metric warm-up 和 publish deadline；旧 generation snapshot 不可复用。
- 单个通道 non-finite/preprocess unavailable 时，两通道共同失效并重新 warm up，避免 RED/IR 时间轴错位。
- preview queue overflow 继续只影响 preview，并明确报告；不能影响 recording raw sink，也不能将缺失的 causal sample 连线补齐。
- filter/profile 异常时回退 RAW 并显示状态；不得沿用旧 causal ring 冒充当前连接。
- raw/CSV/session schema 不因显示能力改变。若未来导出 causal trace，必须作为独立 versioned analysis/display artifact，而不是改写源 CSV。

## 6. 分阶段实施状态

### A. 纯 Kotlin 合并状态机

- 提取 `LivePpgSignalRuntime` 和 `DualStageWaveformSnapshot`。
- 复用 `PpgPreprocessingProfile.iosBaseline01`，从单一逐样本处理同时产生 raw/causal ring 与 metric request。
- 维持 800/100/5 Hz、generation、sample index 和 no-burst deadline。

验收：preprocessing fixture `1e-8`；连续/随机 frame chunk 输出一致；metric request 与当前 scheduler byte/Double 字段一致；gap 后首样本 `didResetAtBoundary`；2 h 内存固定。

### B. Preview owner 接入

- `BlePreviewRuntime` 用合并 runtime 替代两个平行 scheduler 的 PPG sample 状态，DFT 仍只对 1 Hz request 执行。
- `BlePreviewSnapshot` 暴露 dual-stage waveform/profile/warm-up；StateFlow 仍最多按 snapshot cadence 更新。

验收：queue overflow、late generation、reset/close、stale clock tests；RAW snapshot 与原实现逐样本相等；causal snapshot 与 metric request 尾窗相等。

### C. Compose 显示

- Live waveform 卡增加 RAW/CAUSAL 切换、profile 与 warm-up/gap 文案。
- 对 causal Y-range 实现“画出 settling、缩放时排除起始 2 s”的纯 math helper；保留 RED/IR 独立 Y 和有序 extrema。
- 对大字体/TalkBack 提供明确模式、时间范围和不可用原因，不仅依靠颜色。

验收：Compose semantics/截图、Activity recreation 保留显示模式但不保留旧 generation 数据；API/emulator/真机检查 5/10 Hz 帧率、GC、主线程耗时。

### D. 性能与真机门禁

- 使用 30 min synthetic 与 2 h capture simulation，记录 preview worker CPU、snapshot allocation、heap high-water、Compose frame time。
- 真机 CUP 验证 raw 与 causal 同步、gap reset、锁屏/恢复、旋转、停止录制后预览连续，以及 RAW 切换不触发重算。
- 只有在 trace 证明必要时把默认显示刷新从 5 Hz 调到 10 Hz；变更写入 status/config，不影响 1 Hz 指标。

## 7. 必须保留的测试证据

- Swift/Python/Kotlin 相同 100 Hz fixture 的 causal bandpassed RED/IR 对等。
- 任意 BLE notification 分片不改变 accepted raw/causal 序列。
- waveform snapshot raw/causal/time/source index 长度严格一致且不超过 800。
- 首次 800 samples、随后每 100 samples 的 metric request 与当前算法结果不变。
- gap/duplicate/out-of-order/new generation 矩阵不跨边界连线或沿用滤波 state。
- 5 Hz scheduler 遇到延迟不 burst；StateFlow 不逐样本更新。
- release privacy/APK audit 不包含真实 raw/causal 数据。

## 8. 实施结果

- 新增纯 Kotlin `LivePpgSignalRuntime`，每个有序 preview/recording owner 仅保留一组 RED/IR `PpgPreprocessor`，由同一 bounded ring 同时生成 RAW/CAUSAL 波形和 800/100 指标请求；旧 raw/metric scheduler 不再用于 production owner。
- connection/sample discontinuity 与 sequence gap 会原子清空两通道滤波状态、RAW/CAUSAL ring、指标 warm-up 和发布 deadline；duplicate/out-of-order rejected frame 不进入状态。快照继续为 800 点上限、默认 5 Hz、延迟 tick 不 burst。
- Live Compose 默认 CAUSAL 0.6–4 Hz，保留 RAW 切换；标注 `ios_baseline_0.1`、因果/gap-reset/settling 语义。起始 2 s 仍完整绘制，仅从动态 Y 轴估计中排除，并用浅色区域提示。
- raw-first writer、CUPRAW1、CSV/session schema、1 Hz HR/SQI/R cadence 和离线 zero-phase profile 均未改变；没有插值、Compose 波形动画或实时 zero-phase。
- 本地证据覆盖 single-state 精确一致、独立 preprocessor 对等、800 点滚动、gap/index discontinuity、rejected frame、5 Hz no-burst、30 min/2 h bounded simulation、preview/recording integration 和 Compose semantics/编译。真实设备帧率、GC、主线程、锁屏/旋转观感仍属于 D 门禁。

## 9. 开放项

- 是否开放 5/10 Hz 显示刷新设置仍受 `D-011` 约束；先用性能证据决策。
- 因果波形是否作为导出字段属于 schema/version 产品变更，本规划默认不导出。
- zero-phase 只属于离线完整信号工作台；任何“实时 zero-phase”需求必须明确允许的延迟与边缘重算语义后另立 ADR。
