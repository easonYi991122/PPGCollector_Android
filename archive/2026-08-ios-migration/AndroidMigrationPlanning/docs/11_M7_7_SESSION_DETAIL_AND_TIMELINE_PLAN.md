# M7.7 单轮实时指标、会话详情与对齐时间轴规划

版本：1.0
日期：2026-08-09
执行轮次：`M7.7`（单轮完成）

## 1. 文档地位与治理边界

本文是用户 2026-08-09 提出的实时页小修、Saved Sessions 操作区、会话详情折叠、0.5～12 Hz 离线滤波、fixed replay、指标/血压对齐可视化和离线分析 UI 收敛的本轮权威执行文档。开发必须先按本文冻结行为，再修改业务源码。

- [`09_M7_CAPTURE_TRACEABILITY_SUBJECT_ARCHIVE_PLAN.md`](09_M7_CAPTURE_TRACEABILITY_SUBJECT_ARCHIVE_PLAN.md) 继续作为 raw/CSV、metrics/BP sidecar、subject 与 source timestamp 数据合同，不被本文替代。
- [`10_M7_6_COMPOSE_UI_PERFORMANCE_PLAN.md`](10_M7_6_COMPOSE_UI_PERFORMANCE_PLAN.md) 继续作为 Compose 状态/重组、Saved Sessions 单 route、compact capture 和 Baseline Profile 基线；本轮具体布局与详情页行为冲突时以本文为准。
- [`07_LIVE_FILTERED_WAVEFORM_PLAN.md`](07_LIVE_FILTERED_WAVEFORM_PLAN.md) 只保留 M6 的 0.6～4 Hz 历史证据；不得用于回退本轮离线 0.5～12 Hz 目标。
- [`08_REMAINING_MIGRATION_AUDIT.md`](08_REMAINING_MIGRATION_AUDIT.md) 仍是 2026-08-02 历史审计，计算 BP、设备门禁和发布阻塞继续有效。
- `reference_sources/` 保持只读；不改变 `CUPRAW1`、25 列 sample CSV、metrics/BP sidecar schema、raw-first writer 或 1 Hz source cursor。

## 2. 审计结论

1. `CAUSAL` label 在代码中拼写正确；窄按钮的 Material 默认水平 content padding 把末尾 `L` 裁掉，属于布局缺陷而非算法名称错误。
2. `metricTiles()` 已生成 HR、RR、PI、SQI、BP 五项；但内容宽度低于 340 dp 时退化为每项 96/152 dp 的横向滚动行，正常手机首屏只能看见前两到三项。
3. Saved Sessions 顶部操作区使用 12 dp 外边距，archive subject 面板使用 16 dp content padding，形成轻微宽度错位。
4. `OfflinePpgAnalyzer`、完整 signal replay 和分析 artifact 仍默认 `iosBaseline01` / `scipy-sosfiltfilt-parity-0.1`，实际频带为 0.6～4 Hz；必须升级为新的 0.5～12 Hz profile 并提升 analysis/profile version，旧 artifact 继续只读兼容。
5. realtime fixed-lag 是 201-tap 对称 0.5～12 Hz FIR；当前 offline zero-phase 是 SOS forward/backward，二者不是同一种滤波，因此 replay 与 offline PPG stage 必须同时提供 ZERO 与 FIXED。
6. metrics sidecar 已有 1 Hz HR/SQI/R/PI source sample/time，但 signal trace 没有加载它；当前详情页无法把指标和 PPG 放在共享时间窗中。
7. 手工参考 BP 已按 dialog-open source cursor 保存；计算 BP 始终 `MODEL_UNAVAILABLE`。项目没有训练数据、subject calibration、模型权重或临床验证，不能部署或宣称真实 BP 预测算法。
8. 离线分析的“历史 1”在只有一个 artifact 时没有信息价值；嵌套“概览/工作台 → 诊断/PPG/频谱/周期”也增加了导航层级。

## 3. 需求 ID 与完成定义

| ID | 目标 | 本轮可验收结果 |
|---|---|---|
| `M7-UI-006` | 实时滤波与五指标完整呈现 | `CAUSAL` 不裁切；未录制与 compact 录制均同时可见 HR/RR/PI/SQI/BP；常规宽度为一行五列，窄屏/大字体为 3+2 紧凑网格而非隐藏在横向滚动后 |
| `M7-UI-007` | Saved Sessions 操作区视觉对齐 | 非选择态三个按钮的可见宽度与 16 dp archive 内容边界一致；选择态保留四按钮与危险色语义 |
| `M7-DETAIL-001` | 会话详情渐进披露 | 概览、版本、被试、参考 BP、完整性、重放、离线分析、导出/恢复均可折叠；进入新会话默认全部收起，标题行保留关键摘要和明确展开状态 |
| `M7-DSP-003` | 离线 0.5～12 Hz ZERO/FIXED | 新生成的 replay/analysis 使用 versioned 0.5～12 Hz biquad filtfilt；另加载并展示同 realtime profile 的 201-tap fixed-lag FIR，gap 两侧不共享状态；旧 0.6～4 artifact 仍可读取 |
| `M7-MET-003` | 指标与 PPG 同窗对齐 | signal trace 读取 metrics sidecar；离线 PPG 面板用共享 viewport 的上下小图呈现 PPG 与 HR/PI/SQI/R，缩放/平移共同作用于 source sample/time |
| `M7-BP-002` | 参考/占位预测 BP 对比 | 参考 BP 以 dialog-open cursor 绘点；离线占位序列按真实 1 Hz metric epoch（无 sidecar 时按 800 warm-up/100 cadence）绘制固定 120/80 mmHg，并与 PPG 共用时间窗；实时 BP 继续显示横杠 |
| `M7-ANL-001` | 离线分析信息架构 | 移除单 artifact 的“历史 1”；多个 artifact 才显示结果选择器；把摘要、信号与指标、频谱、周期、诊断扁平为同级 section，减少嵌套层级 |

## 4. 关键设计决策

### 4.1 五指标布局

所有模式先呈现相同五项数据，不通过 horizontal scroll 隐藏后两项：

- 常规手机宽度且 fontScale 不超过 1.3：五个 64～68 dp 紧凑 tile 一行；
- 更窄或更大字体：第一行 HR/RR/PI，第二行 SQI/BP，每行使用相同 tile 样式；
- detailed 模式在网格下追加状态/source 摘要，不把每张卡放大到 152 dp；
- BP 永远显示 `—` 和“模型未接入/未标定”，不显示占位数值。

滤波按钮继续一行三列，但显式收紧 content padding、设置最小 48 dp 高度并保留完整 `RAW / CAUSAL / FIXED` 文字与 radio semantics。

### 4.2 详情页折叠

详情 screen 持有当前 session 的 `expandedSectionIds`，默认空集合；数据向下、toggle event 向上。折叠卡标题行包含：

- 标题；
- 一行关键摘要（如样本数/被试、profile、BP 组数、检查状态、signal 点数、artifact 数）；
- “展开/收起”按钮与 state description。

只有展开时才组合大网格、Canvas、artifact 列表和操作区，减少长详情页初始测量与重组成本。切换到另一个 session 时重新回到默认收起。

### 4.3 ZERO 与 FIXED 滤波

新增 `offline-biquad-filtfilt-0.5-12hz-0.1`：100 Hz 下级联二阶 Butterworth high-pass 0.5 Hz 与 low-pass 12 Hz，然后按连续段 forward/backward，实现零相位显示/分析。旧 `ios_baseline_0.1` 保留给既有 live metrics 和旧 artifact regression。

FIXED 使用现有 `fixed-lag-fir-0.5-12hz-0.1` 201-tap 对称 FIR；离线 trace 将输出按 source index 对齐，连续段两端没有足够上下文的位置保持不可画，不跨 gap 填线。ZERO 和 FIXED 是不同 profile，UI 不合并或混称。

新分析版本升级为：

```text
analysis_profile = ppg-offline-segmented-0.2
algorithm_version = segmented-pulse-0.5-12hz-0.4
preprocess_profile = offline-biquad-filtfilt-0.5-12hz-0.1
```

analysis JSON schema 不变；reader 继续读取旧版本。显示变换和离线滤波均不回写 raw/sample CSV。

### 4.4 共享时间轴可视化

采用成熟的 shared-x small multiples，而不是把 mmHg、ADC、bpm 和百分比强塞到同一 Y 轴：上方 PPG，下方指标或 BP，各 lane 独立 Y scale，但共享 `ReplayWaveformViewport`、source sample index、gap 和时间标签。该方式参考 Matplotlib 官方 shared-axis 示例的同步缩放语义，并为 Compose Canvas 补充整体 content description：

- <https://matplotlib.org/stable/gallery/subplots_axes_and_figures/shared_axis_demo.html>
- <https://developer.android.com/develop/ui/compose/accessibility/semantics>

指标优先使用录制期 metrics sidecar 的 HR/PI/SQI/R；legacy 会话没有 sidecar 时，离线窗口仅回退显示 HR/PI/confidence，并明确来源。BP lane 同时显示：

- 手工参考 SBP/DBP 点；
- 1 Hz 占位 SBP/DBP 线；
- 同一 viewport 的 PPG RED 位置参考。

### 4.5 血压算法边界

本轮不部署所谓“极轻量 BP 模型”。PPG→BP 需要同步标注数据、subject/calibration 策略、权重与外部验证；当前仓库不具备这些输入。离线占位序列固定 120/80 mmHg，只验证 UI、cadence 与 source-time join：

- `modelAvailable=false`、`isPlaceholder=true`；
- UI 必须直接写明“未接入血压预测算法，不是模型输出”；
- 占位值不写 metrics/BP sidecar、不写 analysis JSON、不参与 summary/误差评估；
- 实时 `LiveMetricSnapshot.bloodPressure` 继续 unavailable 和 `—`。

## 5. 文件级操作

### 新增

- `data/session/OfflineBloodPressurePreview.kt`：1 Hz placeholder cadence、固定值、真实 metric epoch 优先及 gap warm-up fallback。
- `OfflineBloodPressurePreviewTest.kt`：1 Hz cadence、800 sample warm-up、gap reset、metric epoch 复用和 placeholder flags。
- `SessionDetailUiPolicy.kt` / test：详情 section 默认折叠、摘要与 artifact history 可见策略。

### 修改

- `LiveWaveformComponents.kt`：按钮 padding；五项始终可见的 5 列/3+2 布局；detailed source/status 摘要。
- `SavedSessionsRoute.kt`：非选择态 action row 向内 4 dp，与 archive 16 dp 内容边界一致。
- `PpgPreprocessing.kt`：新增 0.5～12 Hz offline biquad profile，保留旧 profile。
- `OfflinePpgAnalysis.kt`：新 profile/version；ZERO 使用新 profile；新增连续段 fixed FIR trace helper，避免跨 gap。
- `CaptureMetricSeries.kt`：增加有界 timeline reader，只返回 source cursor 与可视化所需的 valid HR/SQI/R/PI。
- `CaptureSessionOfflineAnalysis.kt`：signal trace 加载 ZERO、FIXED、metrics、BP；不改 artifact/source 文件。
- `SessionSignalWorkbench.kt`：replay/analysis 增加 ZERO/FIXED stage；PPG+metrics shared viewport；参考/占位 BP+PPG shared viewport；Canvas semantics。
- `SessionsScreens.kt`：所有详情 section 默认折叠；离线分析扁平 section；仅多个 artifact 显示结果选择，移除“历史 1”。
- `CaptureUiPolicyTest.kt`、`OfflinePpgAnalysisTest.kt`、`CaptureSessionOfflineAnalysisTest.kt`、Compose semantics tests：覆盖本轮行为和旧 artifact/profile 兼容。
- `00_AGENT_MIGRATION_BRIEF.md`、`README.md`、`docs/05_SOURCE_REFERENCE_INDEX.md`、两份 status：登记 M7.7、验证证据和延期门禁；详细 status 只追加事实。

## 6. 单轮实施顺序

1. 实时与 Saved Sessions 小修：完整 label、五指标布局、操作区边界。
2. 详情页折叠 policy 和全部 panel 渐进披露。
3. 0.5～12 Hz offline profile、ZERO/FIXED trace、版本升级与兼容测试。
4. metrics sidecar reader、shared-x PPG/metric 图和 BP/PPG 对比图。
5. 扁平离线分析导航、移除单项历史入口、无障碍语义与测试。
6. 更新 brief/index/双层 status，最后运行一次综合门禁并提交 M7.7。

## 7. 唯一综合校验门禁

全部源码、测试和状态文档完成后统一执行：

```text
env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test lintDebug assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy :app:assembleRelease :baselineprofile:assembleNonMinifiedRelease --no-configuration-cache --no-daemon
```

门禁必须证明 JVM/filter/session/UI policy regression、Compose AndroidTest 编译、lint、debug/release/androidTest、R8、REL contracts、Baseline Profile producer 和 compiled profile 打包均未回退。若失败，修复后用完全相同命令重试并如实记录。

真实设备的滤波观感、五列/3+2 动态字号、TalkBack Canvas 描述、shared viewport 手势、SAF、Macrobenchmark、真实 BP 数据和 2 h capture 仍是硬件/runtime 门禁，不得用本地构建替代。

## 8. 完成定义

- 用户列出的四个实时/Saved Sessions 小问题均由布局或状态测试覆盖；
- 详情所有大 panel 默认折叠且可独立展开，折叠态仍能判断内容和异常；
- 新生成 replay/analysis 的 ZERO 与 FIXED 都明确为 0.5～12 Hz，旧 0.6～4 artifact 可读；
- 至少离线 PPG 模块具有 shared-x PPG+生理指标图；参考 BP panel 具有 reference/placeholder BP+PPG 同轴定位图；
- “历史 1”消失，多 artifact 选择能力保留；
- 没有虚构实时/离线 BP 算法、临床精度或真机通过；
- 双层状态、diff、测试和独立 M7.7 commit 完整，用户 `.idea/*` 与 `app/release/` 不暂存、不修改。
