# PPGCollector Android Development Status

更新时间：2026-08-08
当前迁移版本：`M7.0`（规划）
当前规划阶段：M7 五轮增量规划；M7.1～M7.5 尚未实现
状态：M1–M6 既有实现与本地证据均保留；新增 [`docs/09_M7_CAPTURE_TRACEABILITY_SUBJECT_ARCHIVE_PLAN.md`](../docs/09_M7_CAPTURE_TRACEABILITY_SUBJECT_ARCHIVE_PLAN.md)，把 RAW 仅显示取负、PI/统一 1 Hz PPG 时间轴、手工参考血压、自由命名/被试资料、subject 档案/批量导出、0.5～12 Hz fixed-lag 和最终 UI 收敛安排为不超过五轮的 M7.1～M7.5。当前 app 尚未具有这些 M7 能力，现有 25 列 CSV、三文件会话、0.6～4 Hz runtime 和逐会话 Sessions 行为仍是代码事实。

本轮增量：完成当前代码/测试/迁移文档的只读审计，形成 M7.0 文件级开发计划与旧文档治理表。计划冻结 `{stem}.csv` 的 raw ADC/25 列兼容边界，建议新增 1 Hz metrics 与手工 BP sidecar、session v2 participant snapshot、全局 subject revision、严格 canonical parser 和多会话 export manifest；推荐用明确约 1 秒延迟的 0.5～12 Hz fixed-lag 方案接近离线 zero-phase，并保留旧 profile 回退。该内容均为规划，不宣称 runtime/schema/UI 已实现。

本轮增量：只读审计 `CollectedData/Log 2026-08-05 17_04_47.txt` 确认 97/97 条 NUS TX 通知均为 168 bytes，头尾有效且 UInt32 LE sequence 从 0 连续到 96。扫描现只额外接受精确名 `Nordic_UART_Service`，复用 `cup-nus-bringup-0.1` transport，同时将 `cup-sensor-168-planar-u32seq-0.1` wire mode 贯穿 GATT chunk、preview、raw-first recording、CSV/session、replay、inspection/recovery/offline；`CUP*` 设备路径不变。sensor CSV 使用相同 25 列/header 的 `ppgcollector_samples_v2` 保存完整 UInt32 `frame_sequence`，旧 v1 仍限制 0…255。新增 ADR-0005 和 7 个 JVM tests；完整 130-task 门禁成功，148 tests/0 failures/errors/skips，lint 仅 9 个依赖版本 warning，用户 `.idea` 与 `app/release/` 保持未提交且 release 目录经备份 diff 不变。真机未执行，100 Hz 暂沿用既有合同而非由 nRF 通知间隔推断。

本轮增量：新增根目录 [`REALTIME_AND_STORAGE.md`](../../REALTIME_AND_STORAGE.md)，按当前 production 代码串联 BLE notification、stream decoder、sequence gate、统一 causal/raw runtime、800/100 指标、FGS raw-first 录制、`CUPRAW1`、25 列 CSV、session metadata、inspection/recovery/export 和不可变 analysis JSON，并为每一步列出准确 Kotlin 文件/函数。根据 Codex 文件浏览无法打开原深层路径的反馈，正文已迁移到短 ASCII 根路径并重写全部源码相对链接；文档同时明确当前 CSV 不接收异步 analysis 回填、`complete` 不等于 verified、20 MiB 仅为静态低空间门槛，以及 capture version/profile 命名未冻结。本轮不修改 runtime/schema/算法。

本轮增量：`testdevice1` 的 272 条完整 raw records 中有 212 条 168-byte PPG 和 60 条头尾完整的 8-byte auxiliary（functions `02/06/0C/0F`）。旧 decoder 将后者误计为 60 个无效帧并丢弃 480 B，导致完整性复核假阳性；实际 212 个 sequence 连续、4240 replay samples/CSV rows/metadata 全部一致。decoder 现仅对白名单 function + 精确 8-byte + 正确头尾单独计数，不产样本/freshness；未知 function 与坏 tail 仍报错。production inspection 对导出副本返回 60 auxiliary、0 invalid、0 discard、0 findings，源 ZIP/raw/CSV/session 哈希未变；见 ADR-0004。

本轮增量：M1 按用户提供的新硬件协议将当前 wire profile 改为 `cup-batch-168-planar-0.1`：`AB BA`、`0x15`、LE length `161`、sequence、20×UInt32 LE RED、20×UInt32 LE IR、`CD DC`。采样率 100 Hz、800/100 live cadence、CUPRAW1、25 列 CSV、session schema、算法和 UI 均不变；旧 408-byte/50-pair interleaved 仅作为 `legacy` 读取/回放 profile，单流首帧后锁定布局。新增 ADR-0003、新旧 golden/direct/fragment/replay/metadata 测试，并把 gap、离线时间轴、metadata 的 samples-per-frame 改为按实际布局计算。

本轮增量：M2 根据新设备 `CUP_FEAE89AB24A9` 的真实 UUID 证据新增 `cup-fff0-bringup-0.1`，保留旧 NUS 并按 discovered service 精确自动选择。状态快照暴露实际 profile 与发现的 service/characteristic，录制 metadata 固化实际 service/notify/profile；FFF2 命令未知时不做推测性写入。新增 ADR-0002 和 FFF0 fake GATT 测试；仅成功接受现有 CUP frame 才会 fresh，订阅成功本身不会开放录制。

本轮增量：2026-08-03 通过 Computer Use 直接操作 Android Studio，先完成 Gradle Sync，再在 IDE Terminal 使用 Android Studio JBR 强制重跑 `:app:assembleRelease :app:verifyReleasePrivacy --rerun-tasks`。50 个 task 全部执行，`BUILD SUCCESSFUL in 3m 51s`；REL-002/003/004/005/006/007 均通过。fresh artifact 为 `app/build/outputs/apk/release/app-release-unsigned.apk`，1,443,417 bytes，SHA-256 `0a88646270fd1230f1c26f3e19cd6a7feb23195323eb19bdd856442d3cd0d9a7`，ZIP 完整性通过。

本轮增量：APK manifest 为 `com.example.ppgcollector_android`、`versionCode=1`、`versionName=1.0`、minSdk 26、target/compileSdk 37；`apksigner` 明确返回 `DOES NOT VERIFY`，因为项目没有 signing config/keystore。该文件是 R8/resource-shrunk release variant，但不能作为正式安装/上架签名包；`D-004` 未关闭。`libandroidx.graphics.path.so` 的无法重复 strip 提示保持为 AndroidX 预编译库原样打包 warning，构建未失败。

本轮增量：`BlePreviewRuntime` 与录制 `CaptureRecordingController` 各自使用统一有序 `LivePpgSignalRuntime`，一次 ingest 同步生成 RAW/CAUSAL 800 点波形和既有指标请求；不再由 production owner 分别维护 raw waveform scheduler 与 metric preprocessor。sample-index discontinuity/sequence gap 原子清空两通道 DC/SOS、rings、warm-up 与 deadline，rejected frame 不进入状态；发布保持默认 5 Hz、延迟不 burst，指标保持 800/100。

本轮增量：实时页默认显示实际 100 Hz accepted samples 的 CAUSAL 0.6–4 Hz RED/IR，可切换 RAW；明确标注 `ios_baseline_0.1`、因果、gap reset 和 settling。起始 2 s 仍完整绘制，仅从自动 Y 轴估计中排除并以浅色区域提示；不使用插值、Compose animation 或实时 zero-phase，不改 CUPRAW1、CSV/session、HR/SQI/R cadence/version。

本轮增量：新增 [`docs/08_REMAINING_MIGRATION_AUDIT.md`](../docs/08_REMAINING_MIGRATION_AUDIT.md)，把未完成工作分为代码缺口、emulator/真机验收和外部产品/发布决策。最高优先级代码缺口是 production capture 的正式 algorithm/preprocess version 追踪、service-owned elapsed/write-health 通知与录制页、FileProvider 分享入口、动态空间预算及 CI/benchmark；M7 仍需新证据后再定可验收范围。

本轮增量：会话详情在选择后通过 production `CUPRAW1` replay 加载最多 1,500,000 个完整 accepted samples，按 sequence/time continuity 对每个连续段执行 `scipy-sosfiltfilt-parity-0.1`，保留 RAW RED/IR、全程 zero-phase RED/IR、时间轴和 gap。大数组仅存在于当前选中会话内存，不写入 analysis JSON、不改 raw/CSV/session；取消选择会取消加载并释放引用。

本轮增量：Replay 和紧凑 PPG 工作台默认显示最佳/起始 8 s，但真实视窗覆盖完整记录，支持单指拖动、双指缩放、按钮缩放与全幅。长记录绘制直接对可见源范围做保序 extrema 降采样，不复制整段数组；RAW/ZERO-PHASE、RED/IR、稳定段、接受峰和 gap 共用同一时间视窗。

本轮增量：新增全屏横屏工作台，按 Python GUI 思路提供 selected/RED/IR、RAW/ZERO-PHASE/PEAKS、反相、稳定段聚焦、完整窗口审计、当前可见范围 bounded Welch-style spectrum、平均周期/95% CI、分析历史与不可变来源诊断；未移植无数据契约的 IMU。实时采集平滑波形只形成 `docs/07_LIVE_FILTERED_WAVEFORM_PLAN.md`：明确 zero-phase 不可实时，后续应合并现有因果 preprocessor/ring，避免第三套滤波状态；本轮未修改 live runtime、raw、CSV、指标或 FGS。

本轮增量：M6 分析服务只从 `CUPRAW1` 重放 accepted RED/IR，按 sequence gap 建立中断边界，并把 raw SHA-256、session ID、schema/profile/algorithm/preprocess version、时间、warnings、metrics、segments/windows/peaks/spectrum/cycle/preview 写入 `analysis/` 下不可覆盖的 JSON；任务可进度观察、取消、重启并保留历史，取消不留下伪完整产物。

本轮增量：Saved Sessions 从实时采集页拆成独立列表、详情与双会话对比页面；replay 使用美化的分组控件和共享 viewport，支持单指拖动、双指缩放、缩放按钮和 fit。详情按 Overview/Workbench/History 组织 Diagnostics/PPG/Spectrum/Cycle，Compare 提供 stacked/full/unified 周期视图，并明确不提供无数据契约的 IMU。

本轮增量：解压分析 `CollectedData/test1.zip` 与 `test2.zip` 后确认每条 CUPRAW1 notification record 完整；test1 仅有首个完整 frame 前 28 个跨录制边界字节，两份会话分别有停止时尚未凑满下一 408-byte frame 的 220/60 个 pending bytes。它们现作为 `raw-alignment-prefix`/`raw-frame-suffix` warning 呈现；只有首帧后的丢弃、非法帧或 raw record 截尾仍是 structural ERROR，inspection 保持只读。

本轮增量：M6 固定 40 s synthetic 输入与 Python `segmented_pulse.py` 在稳定段边界、稳定样本比、8 s/2 s 窗口、通道/极性、峰数、HR、频谱、confidence、SNR、RR MAD 字段一致；Kotlin zero-phase SOS 对 SciPy 固定字段误差门限为 `1e-8`。实际 test1/test2 的 Kotlin/Python summary 也一致，用户 ZIP/raw 未修改且不纳入仓库。

本轮增量：M4 BLE preview 每个接受帧按连接 generation 回写 owner freshness，并使用 Android monotonic clock 与主线程 dispatcher；录制名合法且连接/数据流 fresh 时 gate 现可解除，2 s 后的 stale 刷新仍保持录制保护。波形由单样本零长度 min/max 竖线改为 Swift 对等的保序极值连续 Path，RED/IR 面板在空窗口和早期窗口也始终可见；设备按钮在订阅/接收后切换为红色“断开”。首页改为稳定品牌色、分组卡片、freshness 胶囊和 2×2 指标卡，同时继续明确 RR/SQI 的诊断/暂定语义以及 SpO2/BP unavailable。

本轮增量：M2 Android scanner 在 10 s 后确定性停止，owner/coordinator 发布可重试状态；platform scan failure 不再把 adapter 误报为 `UNKNOWN`，停止后排队到达的 scan result 不再进入列表。设备列表改为外层页面统一滚动，避免首个 CUP 设备出现时创建无界嵌套 `LazyColumn` 而闪退；旧/新 BLE API 兼容分支的 Kotlin deprecated warning 已消除。

本轮增量：M4 `BleHome` 改为可滚动根容器，instrumentation 在 Activity recreation 前后使用 `performScrollTo()` 检查 Live/Capture/Sessions/卸载提示入口可达；仅改善小屏/动态字号下的 UI 可达性，不改变 raw、CSV、算法或服务所有权。

本轮增量：M4 SAF picker 取消现在始终产生“导出已取消” action feedback；运行中的 export/recovery 取消保留 action 类型，协程 `CancellationException` 不再被宽泛异常处理写成失败，避免旧操作污染新会话状态。

本轮增量：M2 `BlePermissionResultSeam` 现在合并分步 runtime permission 回调，保留此前已授予但本次 map 未携带的权限，同时尊重显式 `false` 撤权；补充 API 33 分步 SCAN→CONNECT 恢复测试。

本轮增量：M4 Sessions refresh/inspection 的文件读取结果现在保留 coroutine cancellation，不会把被新操作取消的旧 job 写成错误状态；新增 cancellation boundary JVM 回归测试。

本轮增量：M1 CUP stream decoder 新增固定种子随机 notification 分片、24 帧粘连和帧间噪声 resync 测试，验证输出序列、frame count、discard 计数和 pending=0。

本轮增量：已明确 live metrics 仅作为 raw 写入时快照，异步分析不得回填已写 CSV 行；离线分析结果应另行版本化且不修改源 CSV。

本轮增量：新增 release `REL-003/REL-004` lifecycle contract report，静态检查 `START_NOT_STICKY`、terminal finalization observation、onDestroy cleanup 顺序及 immutable stop action；该报告不替代 emulator/真机生命周期验证。

本轮增量：补齐 service 内部 `startForeground()` SecurityException 的保留式 failure StateFlow；binder→ViewModel→capture gate 可显示 `ForegroundServiceStartRejected`，并新增 JVM gate 断言与 release 静态 wiring 检查。

本轮增量：新增 REL-002 fake GATT 20 次生命周期循环门禁；验证 generation 单调、Idle/freshness reset、late callback 丢弃、CCCD enable/disable 一轮一次及 passive control 边界，真机循环仍待执行。

本轮增量：Android BLE adapter 新增统一幂等 GATT 释放路径；替换连接、断开权限异常、stale callback 和 transport close 均清理 GATT/map/connectedIds/pending CCCD，并加入 REL-002 release 静态 contract 报告。

本轮增量：补齐 BLE transport/coordinator availability activation；初始化先安装 event sink 再 activate，权限恢复后的首次扫描会重新查询 adapter，activate 的 SecurityException 映射为 `UNAUTHORIZED`，并新增 fake coordinator 测试。

本轮增量：补齐连接后撤销蓝牙权限时的 CCCD 写入失败路径；adapter 清理 pending descriptor 并发布订阅失败事件，fake GATT 与 release contract 覆盖该边界，真机权限撤销仍待执行。

## 当前一句话

Android 工程已形成可运行的 M1～M6 Compose 采集与独立会话工作台；M7.0 已把十项新增需求收敛为五轮计划但尚无产品代码。下一步是 M7.1 的 session v2/sidecar、命名和 subject profile 纯 Kotlin 合同，同时保留 `Nordic_UART_Service`、FFF1/FFF2 与真实 runtime 矩阵门禁。

## 与 MigrationPlanning 对照

| 版本 | 对应阶段 | 已交付能力 | 状态 | 下一步 |
|---|---|---|---|---|
| `M0.1` | Phase 0 | Agent 入口 prompt、项目级工作约定、详细/简版状态、当前 Android 基线核对 | 已完成（文档） | `M0.2`：工程基础、ADR、测试门禁 |
| `M0.2` | Phase 0 | Android 基线 ADR、CI JVM/build 门禁 | 已实现并经 JDK 验证 | `M1`：纯 Kotlin CUP protocol golden slice |
| `M1` | Phase 1 | batch 168-byte UInt8、sensor 168-byte UInt32、真实 8-byte auxiliary、历史 408-byte replay、CUPRAW1、CSV v1/v2、session/replay/inspection、信号算法与 live scheduler | `testdevice1` 与新 nRF 日志结构证据；两个 168-byte codec/fragment/sequence/storage/replay 测试通过；sensor 实际采样率与长稳待真机 | M2 sensor/NUS 与 FFF1/FFF2 的 30 min receiving 门禁 |
| `M2` | Phase 2 | BLE 权限、扫描、GATT、订阅、freshness、诊断与 device→wire mode | NUS/FFF0 registry；`CUP*` 按 service 选 transport，精确 `Nordic_UART_Service` 选 NUS + sensor mode；mode 贯穿 preview/recording，fake GATT/JVM/build 已验证 | 真机确认广播身份、采样率、RX/FFF2 控制、reconnect 与 runtime matrix |
| `M3` | Phase 3 | raw-first writer、CSV/session、开始前 gate、幂等 finalizer、accepted raw stream controller、connectedDevice FGS seam、session catalog/checkpoint、safe-prefix recovery、export seam、async live-analysis seam | writer/session/controller/manifest/service/repository/recovery/export/analysis 已实现并经 JVM/build 验证；系统后台/重建行为、用户正式页面仍未验收 | formal capture/sessions UI、lifecycle binding、metrics CSV policy |
| `M4` | Phase 4 | V1 Compose 实时/录制/历史/详情/重放 | lifecycle-aware FGS binding、合法帧→freshness→capture gate、Swift 对等保序极值双轨 Path、连接/断开状态按钮、分组卡片/状态/指标 UI、Sessions/detail/SAF/replay、可滚动页面、waveform semantics 和 instrumentation seam 已实现；真机波形/录制、instrumentation runtime/系统重建/动态字号/TalkBack/SAF provider 验收未完成 | 真机复验本轮交互后继续 M5 runtime matrix |
| `M5` | Phase 5 | 长稳、API/厂商矩阵、性能、隐私、发布硬化，形成 V1.0 | 已完成 release preflight、R8/resource shrinking、release lint（历史 0 errors）、静态 artifact scan、sessions backup exclusion、REL-001 模拟、REL-002/003/004/005/006/007 静态门禁；2026-08-03 Android Studio fresh unsigned APK/manifest/SHA/ZIP/privacy 校验通过。正式 identity/signing、API/厂商/真机/隐私门禁未完成 | API emulator/厂商运行矩阵、真实 lifecycle/2 h；取得 D-004 输入后生成正式签名包 |
| `M6` | Phase 6 | V1.1 离线稳定段、频谱、周期和对比工作台 | raw replay/独立版本 JSON/Python 与 SciPy 对等、完整 RAW/全程 zero-phase 触控视窗、窗口审计/频谱/周期/诊断、横屏工作台、历史/取消/Sessions/compare，以及统一实时 RAW/CAUSAL 0.6–4 Hz runtime 均已实现并经 JVM/build/privacy 证据；runtime UI/性能和长任务跨进程策略待验收 | emulator/真机运行实时 causal、完整信号触控、横屏与性能；按 `D-014` 决定 WorkManager/用户可见 FGS |
| `M7.0` | 五轮增量规划 | 采集追溯、手工参考血压、被试档案/批量导出、滤波与 UI 收敛 | 规划文档已形成；全部产品代码仍待 M7.1～M7.5 实现 | M7.1：数据合同、sidecar、命名与 subject profile 内核 |

## 当前 Android 工程事实

- Kotlin `2.2.10`、AGP `9.3.1`、Gradle `9.6.1`、Compose BOM `2026.02.01`、Java 11；release 已启用 R8/resource shrinking，仍为 unsigned 构建。
- `minSdk=26`、`compileSdk=37`、`targetSdk=37`。
- `applicationId=com.example.ppgcollector_android`、`versionName=1.0` 是占位值。
- 已有 `MainActivity`、Material 3 theme、BLE/协议/信号/session/FGS/Compose 实现及 JVM/instrumentation 编译门禁；instrumentation runtime 和真实设备门禁仍未执行。

## 当前未完成能力

Android 真机长稳均未交付；sessions 不进入 cloud/device backup，并说明卸载与显式 ZIP 导出边界。M4 live capture 与 M6 实时 RAW/CAUSAL、独立 Saved Sessions/detail/replay/analysis/compare/横屏工作台均已有本地实现；完整信号最多持有 1,500,000 点的 raw/filter 数组，离开详情即释放，分析仍是短任务 app-scope coroutine，普通进程被系统终止后不会自动续跑。尚未运行 emulator/device instrumentation、真实 causal 波形/gap/录制/断开、完整 replay 指尖缩放/全幅性能、横屏沉浸与旋转恢复、分析 cancel/history/compare、backup/SAF/TalkBack/dynamic font/系统后台。capture metadata/CSV 仍需关闭正式 `alg_version`/preprocess profile 命名，FGS 通知仍缺 elapsed/write health，FileProvider 分享未接 UI。M7 的 RAW 显示反相、PI、1 Hz metrics sidecar、手工参考 BP、自由命名建议、被试资料/档案、批量导出、0.5～12 Hz fixed-lag 和录制态紧凑 UI 均尚未实现；当前 SQI/ratio 仍 provisional/diagnostic，计算 SpO2/BP unavailable，IMU 不显示。

## 验证与真机策略

- 2026-08-05 M1/M2 sensor 兼容轮只读审计新 nRF 日志：97 notifications、长度全部 168、header/tail 97/97、UInt32 sequence 0…96 连续；文件 SHA-256 `771ce1a7f39062ad4b0db450a14768afaeb584236215097be8b1b368cdefd28f`，未修改/未提交。定向 sensor protocol/BLE/capture/CSV tests 通过；完整 `./gradlew test lintDebug assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy --no-configuration-cache --no-daemon` → `BUILD SUCCESSFUL in 1m 12s`、130 tasks、148 JVM tests/0 failures/errors/skips，lint 0 errors/9 dependency warnings，debug/release/androidTest、R8 与 REL contracts 通过。真机未执行；用户 `app/release/` 测试前备份、测试后 `diff -qr` 无差异。
- 2026-08-04 M1 auxiliary 修复轮对 `testdevice1.zip` 做只读二进制审计与 production inspection，确认 212 data + 60 auxiliary、4240 samples、0 sequence anomaly/invalid/discard/finding；随后完整运行 `./gradlew test lintDebug assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy --no-configuration-cache --no-daemon`，`BUILD SUCCESSFUL in 1m 4s`、130 tasks、141 JVM tests/0 failures/errors/skips，debug lint、debug/release/androidTest APK、R8/lifecycle/BLE/privacy contracts 通过。源 ZIP SHA-256 保持 `1b9ba2bfee58191abcadfe03056a80b53b36bc9a00cb6558dbe833dc0700f1bf`。
- 2026-08-04 M1 协议轮使用 Android Studio JBR 完成定向 protocol/replay/session/offline 回归及 `./gradlew test lintDebug assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy --no-configuration-cache --no-daemon`，`BUILD SUCCESSFUL in 1m 20s`、130 tasks；138 JVM tests/0 failures/0 errors/skips，debug lint、debug/release/androidTest APK、R8、BLE/lifecycle/privacy contracts 通过。真机未执行，FFF1 原始通知、FFF2 命令和实际 100 Hz 仍是 D-001 门禁。
- 2026-08-03 M2 FFF0 兼容轮使用 Android Studio JBR 完成 BLE 定向测试与 `./gradlew test lintDebug assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy --no-configuration-cache --no-daemon`，`BUILD SUCCESSFUL in 1m 14s`；133 JVM tests/0 failures/0 errors/skips、debug lint 0 errors/9 个依赖版本 warning、debug/release/androidTest APK 和 REL-002/003/004/005/006/007 静态契约通过。真机未执行，FFF1 数据与 FFF2 命令仍是 D-001 门禁。
- 2026-08-03 M5 使用 Android Studio Gradle Sync 与 IDE Terminal 完成 `:app:assembleRelease :app:verifyReleasePrivacy --rerun-tasks --no-configuration-cache --no-daemon`，`BUILD SUCCESSFUL in 3m 51s`、50 tasks executed；fresh unsigned APK 的 package/version/SDK、ZIP integrity、1,443,417-byte size 和 SHA-256 已独立复核。Mac 随后锁屏，Computer Use 无法继续点击 IDE；额外 fresh `lintRelease` 的 Gradle cache 提权因审批通道断开未执行，本轮不把历史 lint 结果伪装成 fresh lint。
- 本轮 M6 使用 Android Studio JDK/Gradle wrapper 9.6.1 完成 `./gradlew test lintDebug assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy --no-configuration-cache --no-daemon`，`BUILD SUCCESSFUL`；JVM 132 tests/0 failures/0 errors、debug lint 0 issues、debug/release/androidTest APK 及 REL-002/003/004/005/006/007 静态契约通过。新增统一 causal/raw state、独立 preprocessor 精确对等、gap/index discontinuity/rejected frame、5 Hz no-burst、preview/recording 同窗、Python-style settling Y-scale 与 2 h bounded ring 证据；用户采集数据未写回、未打包、未提交。
- 最近 M4 使用 Android Studio JDK 25/Gradle wrapper 9.6.1 完成 `./gradlew test lintDebug assembleDebug assembleDebugAndroidTest --no-configuration-cache --no-daemon`，`BUILD SUCCESSFUL`；JVM 109 tests/0 failures、debug lint 0 errors/9 个依赖版本提示、debug/androidTest APK 通过。可处理的 API/manifest/resource warning 已清理，BLE Kotlin deprecated warning 未再出现；生成的 Windows wrapper 保留 CRLF，除 `gradlew.bat` 行尾格式外 `git diff --check` 通过。
- 本轮 M2 使用 Android Studio JDK 25/用户当前 Gradle wrapper 9.6.1 完成 `./gradlew test lintRelease assembleRelease assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy --no-configuration-cache --no-daemon`，`BUILD SUCCESSFUL`；JVM 106 tests/0 failures、release lint 0 errors、R8/resource shrinking、debug/release/androidTest APK 和 REL-002/003/004/005/006/007 静态契约通过。新增 scan timeout/平台失败/coordinator retry JVM tests 和设备列表嵌套滚动 instrumentation seam；本轮不运行 emulator/真机，修复后的 CUP 扫描/连接与厂商行为待复验。
- 最近 M5 使用 Android Studio JDK 25 完成 release artifact、REL-001 30 min/2 h simulation、checkpoint 预算、raw/CSV/replay 对齐断言、REL-007 FGS 启动失败映射和 target/permission/service 静态 contract、API 33+ notification permission gate、REL-006 BLE permission API boundary matrix、CAP-007 写入失败升级与 incomplete-prefix 断言、REL-004 service finalization wait、merged-manifest/API target report，以及 `:app:verifyReleasePrivacy --no-configuration-cache` 的源码日志/APK 内容审计；本轮 M4 不重复关闭这些证据。
- `git diff --check` 通过；golden wire、协议边界、CUPRAW1 round-trip、LE header、截尾 safe-prefix、超限防御、跨 raw chunk replay、bounded recent samples、25 列 header、CSV streaming tail audit、raw/CSV/metadata cross-check、固定 SOS/DC/gap reset、四个 preprocessing fixture case、plateau midpoint、distance tie、prominence/width peak semantics、6 个 HR fixture case 的 BPM/DFT/RR/confidence/reason trace、8 个 SQI fixture case 的 peak/cycle/template/Pearson/grade trace、ratio-of-ratios 的 trim/RMS/ACDC/invalid paths、MetricResult invalid/calibration separation、800/100 cadence/window bound、gap generation、rejected frame、stale request/source timestamp、M2 NUS/FFF0 profile/name filter 与 service 自动选择、API 30/31/33 permission branches、waiting/fresh/stale freshness、stage/device stale deadline、timeout clamp、phase flags、fake transport command order、fake GATT connect/service/characteristic/CCCD/receiving path、raw byte/timestamp copy、wrong phase/generation rejection、missing service/notify failure、deadline polling、Android adapter compile/Manifest merge、permission result deny/recover、coordinator permission/availability/scan/connect gates、coordinator snapshot/raw seam、Activity Result/lifecycle-compose compile、Application manifest wiring、Locale.ROOT、RFC 4180 转义、metadata round-trip 和 JSON audit 均有 JVM 测试。
- 每轮迭代结束不立即上机；真机协议、后台/锁屏、API/厂商矩阵、长录制和功耗统一作为待执行硬件门禁。除非用户明确要求，不将真机测试作为本轮默认动作。

## 当前开放阻塞

`D-001` 各设备固件、sensor 稳定身份/真实采样率、控制与长稳；`D-002` 目标设备矩阵；`D-003` 后台录制策略；`D-004` 最终包名/签名/分发；`D-005` 数据保留/导出/加密；`D-006` 跨平台文件双向兼容；`D-007` version/profile 命名；`D-008` 计算 SpO2/BP 产品文案；`D-009`–`D-013` 的 location/页面退出/刷新/空间/device ID；`D-014` 离线分析跨进程策略；`D-016` SQI provisional 提升证据。M1～M6 的代码/运行时/发布余项详见带历史提示的 [`docs/08_REMAINING_MIGRATION_AUDIT.md`](../docs/08_REMAINING_MIGRATION_AUDIT.md)；M7 新范围以 [`docs/09_M7_CAPTURE_TRACEABILITY_SUBJECT_ARCHIVE_PLAN.md`](../docs/09_M7_CAPTURE_TRACEABILITY_SUBJECT_ARCHIVE_PLAN.md) 为准。

详细事实记录见 [`DEVELOPMENT_STATUS_DETAILED.md`](DEVELOPMENT_STATUS_DETAILED.md)。
