# PPGCollector Android Development Status

更新时间：2026-08-02
当前迁移版本：`M6`
当前规划阶段：Phase 6（V1.1 离线分析与工作台）
状态：M1–M5 既有实现与本地证据均保留；本轮完成 raw replay 驱动、不可覆盖且可追溯的 M6 离线分析，移植 Python segmented-pulse 稳定段/窗口/通道/极性/BPM 聚类和 SciPy zero-phase SOS 语义，并提供平均周期/CI、频谱、峰、分析历史/进度/取消、独立 Sessions/detail/replay/compare Compose 页面。真实 `CollectedData` 的录制边界被改判为 warning 而非结构错误；122 个 JVM tests、debug lint/build/androidTest 编译及 release privacy 门禁通过，真机 UI/runtime 与 `D-014` 跨进程后台策略仍待执行。

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

Android 工程已形成可运行的 Compose 采集与独立会话工作台：M1–M5 的 protocol/raw/CSV/signal/BLE/FGS/live/session/release 本地切片均保留，M6 已增加 raw replay 驱动的版本化 segmented-pulse 离线分析、平均周期/CI/频谱/峰、可取消历史、触控 replay 和双会话对比。本地算法/数据/构建/隐私门禁通过；下一步是 emulator/真机运行 Sessions/replay/analysis/compare 交互并继续 M5 API/厂商/后台/长稳矩阵，不关闭真实设备协议与 `D-014` 后台执行策略。

## 与 MigrationPlanning 对照

| 版本 | 对应阶段 | 已交付能力 | 状态 | 下一步 |
|---|---|---|---|---|
| `M0.1` | Phase 0 | Agent 入口 prompt、项目级工作约定、详细/简版状态、当前 Android 基线核对 | 已完成（文档） | `M0.2`：工程基础、ADR、测试门禁 |
| `M0.2` | Phase 0 | Android 基线 ADR、CI JVM/build 门禁 | 已实现并经 JDK 验证 | `M1`：纯 Kotlin CUP protocol golden slice |
| `M1` | Phase 1 | CUP protocol、CUPRAW1、25 列 CSV、snake_case session metadata codec、bounded replay/inspection、preprocessing parity、peak detector、HR estimator、SQI、diagnostic ratio-of-ratios、MetricResult、800/100 live scheduler、数据完整性边界 JVM tests | protocol/raw/CSV/metadata/inspection-replay/preprocessing/peak/HR/SQI/ratio/live-core slices、固定种子随机分片/中间噪声 resync 已实现；Android async runner/lifecycle 未接入 | M2 BLE permissions/GATT/fake transport |
| `M2` | Phase 2 | BLE 权限、扫描、GATT、订阅、freshness、诊断 | profile、权限策略、分步 permission callback merge、phase/deadline/freshness、fake transport、fake GATT state machine、Android scanner/GATT adapter、10 s scan timeout/typed failure/late-result gate、permission seam、app-scope coordinator、Activity Result/Compose StateFlow 已实现并经 JVM/build 验证；首设备列表崩溃已修复，修复后真机门禁待执行 | 真机复验无设备超时、CUP 发现/渲染/连接；随后继续 M5 runtime matrix |
| `M3` | Phase 3 | raw-first writer、CSV/session、开始前 gate、幂等 finalizer、accepted raw stream controller、connectedDevice FGS seam、session catalog/checkpoint、safe-prefix recovery、export seam、async live-analysis seam | writer/session/controller/manifest/service/repository/recovery/export/analysis 已实现并经 JVM/build 验证；系统后台/重建行为、用户正式页面仍未验收 | formal capture/sessions UI、lifecycle binding、metrics CSV policy |
| `M4` | Phase 4 | V1 Compose 实时/录制/历史/详情/重放 | lifecycle-aware FGS binding、合法帧→freshness→capture gate、Swift 对等保序极值双轨 Path、连接/断开状态按钮、分组卡片/状态/指标 UI、Sessions/detail/SAF/replay、可滚动页面、waveform semantics 和 instrumentation seam 已实现；真机波形/录制、instrumentation runtime/系统重建/动态字号/TalkBack/SAF provider 验收未完成 | 真机复验本轮交互后继续 M5 runtime matrix |
| `M5` | Phase 5 | 长稳、API/厂商矩阵、性能、隐私、发布硬化，形成 V1.0 | 已完成 release preflight、R8/resource shrinking、release lint（0 errors）、静态 artifact scan、sessions backup exclusion、UI disclosure contract、REL-001 30 min/2 h JVM simulation、REL-007 FGS start rejection/permission manifest contract、API 33+ notification permission gate、REL-006 BLE permission API boundary matrix、CAP-007 writer failure injection、REL-005 privacy/log/APK static audit、REL-004 FGS 在 raw/CSV/session finalizer 结束后再退出，以及 REL-006/REL-007 merged-manifest/API target 静态报告；unsigned、API/厂商/真机/签名/正式隐私门禁未完成 | API emulator/厂商运行矩阵、真实生命周期/2 h、签名/隐私策略 |
| `M6` | Phase 6 | V1.1 离线稳定段、频谱、周期和对比工作台 | raw replay、独立版本 JSON、Python segmented-pulse/SciPy SOS 对等、平均周期/CI/频谱/峰、任务历史/取消、独立 Sessions/detail/replay/compare Compose 页面已实现并经 JVM/build/privacy 与两份实际会话只读验证；runtime UI、长任务跨进程策略待验收 | emulator/真机运行工作台交互；按 `D-014` 决定是否升级 WorkManager/用户可见 FGS |
| `M7` | 后续 V2 | 专家诊断和有证据支持的扩展 | 未开始 | 另行决策 |

## 当前 Android 工程事实

- Kotlin `2.2.10`、AGP `9.3.1`、Gradle `9.6.1`、Compose BOM `2026.02.01`、Java 11；release 已启用 R8/resource shrinking，仍为 unsigned 构建。
- `minSdk=26`、`compileSdk=37`、`targetSdk=37`。
- `applicationId=com.example.ppgcollector_android`、`versionName=1.0` 是占位值。
- 已有 `MainActivity`、Material 3 theme、BLE/协议/信号/session/FGS/Compose 实现及 JVM/instrumentation 编译门禁；instrumentation runtime 和真实设备门禁仍未执行。

## 当前未完成能力

Android 真机长稳均未交付；sessions 不进入 cloud/device backup，并说明卸载与显式 ZIP 导出边界。M4 live capture 与 M6 独立 Saved Sessions/detail/replay/analysis/compare 页面均已有本地实现；分析是 memory-bounded 短任务 app-scope coroutine，普通进程被系统终止后不会自动续跑，`D-014` 的 WorkManager/用户可见 FGS 决策仍开放。尚未运行 emulator/device instrumentation、真实波形/录制/断开、replay 指尖缩放、分析 cancel/history/compare、实际 backup restore、外部 provider、TalkBack/dynamic font、系统重建/后台/锁屏；metrics snapshot CSV 边界不变，SQI 与 ratio 明确为 provisional/diagnostic，SpO2/BP 明确 unavailable，IMU 不显示。

## 验证与真机策略

- 本轮 M6 使用 Android Studio JDK/Gradle wrapper 9.6.1 完成 `./gradlew test lintDebug assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy --no-configuration-cache --no-daemon`，`BUILD SUCCESSFUL`；JVM 122 tests/0 failures/0 errors、debug lint 0 errors/9 个依赖版本提示、debug/release/androidTest APK 及 REL-002/003/004/005/006/007 静态契约通过。固定 Python/Kotlin 算法字段对等和两份解压实际会话只读分析通过；用户采集数据未写回、未打包、未提交。
- 最近 M4 使用 Android Studio JDK 25/Gradle wrapper 9.6.1 完成 `./gradlew test lintDebug assembleDebug assembleDebugAndroidTest --no-configuration-cache --no-daemon`，`BUILD SUCCESSFUL`；JVM 109 tests/0 failures、debug lint 0 errors/9 个依赖版本提示、debug/androidTest APK 通过。可处理的 API/manifest/resource warning 已清理，BLE Kotlin deprecated warning 未再出现；生成的 Windows wrapper 保留 CRLF，除 `gradlew.bat` 行尾格式外 `git diff --check` 通过。
- 本轮 M2 使用 Android Studio JDK 25/用户当前 Gradle wrapper 9.6.1 完成 `./gradlew test lintRelease assembleRelease assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy --no-configuration-cache --no-daemon`，`BUILD SUCCESSFUL`；JVM 106 tests/0 failures、release lint 0 errors、R8/resource shrinking、debug/release/androidTest APK 和 REL-002/003/004/005/006/007 静态契约通过。新增 scan timeout/平台失败/coordinator retry JVM tests 和设备列表嵌套滚动 instrumentation seam；本轮不运行 emulator/真机，修复后的 CUP 扫描/连接与厂商行为待复验。
- 最近 M5 使用 Android Studio JDK 25 完成 release artifact、REL-001 30 min/2 h simulation、checkpoint 预算、raw/CSV/replay 对齐断言、REL-007 FGS 启动失败映射和 target/permission/service 静态 contract、API 33+ notification permission gate、REL-006 BLE permission API boundary matrix、CAP-007 写入失败升级与 incomplete-prefix 断言、REL-004 service finalization wait、merged-manifest/API target report，以及 `:app:verifyReleasePrivacy --no-configuration-cache` 的源码日志/APK 内容审计；本轮 M4 不重复关闭这些证据。
- `git diff --check` 通过；golden wire、协议边界、CUPRAW1 round-trip、LE header、截尾 safe-prefix、超限防御、跨 raw chunk replay、bounded recent samples、25 列 header、CSV streaming tail audit、raw/CSV/metadata cross-check、固定 SOS/DC/gap reset、四个 preprocessing fixture case、plateau midpoint、distance tie、prominence/width peak semantics、6 个 HR fixture case 的 BPM/DFT/RR/confidence/reason trace、8 个 SQI fixture case 的 peak/cycle/template/Pearson/grade trace、ratio-of-ratios 的 trim/RMS/ACDC/invalid paths、MetricResult invalid/calibration separation、800/100 cadence/window bound、gap generation、rejected frame、stale request/source timestamp、M2 NUS profile/name filter、API 30/31/33 permission branches、waiting/fresh/stale freshness、stage/device stale deadline、timeout clamp、phase flags、fake transport command order、fake GATT connect/service/characteristic/CCCD/receiving path、raw byte/timestamp copy、wrong phase/generation rejection、missing service/notify failure、deadline polling、Android adapter compile/Manifest merge、permission result deny/recover、coordinator permission/availability/scan/connect gates、coordinator snapshot/raw seam、Activity Result/lifecycle-compose compile、Application manifest wiring、Locale.ROOT、RFC 4180 转义、metadata round-trip 和 JSON audit 均有 JVM 测试。
- 每轮迭代结束不立即上机；真机协议、后台/锁屏、API/厂商矩阵、长录制和功耗统一作为待执行硬件门禁。除非用户明确要求，不将真机测试作为本轮默认动作。

## 当前开放阻塞

`D-001` 真实 CUP 协议/固件抓包；`D-002` 目标设备矩阵；`D-003` 后台录制策略；`D-004` 最终包名/签名/分发；`D-005` 数据保留/导出/加密；`D-006` 跨平台文件双向兼容；`D-007` version/profile 命名；`D-008` SpO2/BP 产品文案；`D-014` 离线分析目前按短任务 app scope 执行，跨进程/长任务需 WorkManager 或用户可见 FGS。

详细事实记录见 [`DEVELOPMENT_STATUS_DETAILED.md`](DEVELOPMENT_STATUS_DETAILED.md)。
