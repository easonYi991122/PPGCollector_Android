# PPGCollector Android Development Status

更新时间：2026-08-02
当前迁移版本：`M5`
当前规划阶段：Phase 5（长稳、API/厂商矩阵、性能、隐私、发布硬化）
状态：M1 protocol/raw/session/signal core、M2 BLE owner/adapter/permission/UI seam、M3 raw-first writer/FGS/session recovery/export/async analysis，以及 M4 Activity/ViewModel→connectedDevice FGS lifecycle-aware binding、capture name/gate/start-stop、bounded recording waveform/metrics、app-scope non-recording preview continuity、filesystem-backed Sessions catalog/detail inspection、SAF export/safe-prefix recovery actions、bounded raw replay detail、pure viewport controls、可滚动 Compose 页面、instrumentation test seam、first-stop/FGS acceptance audit 和 waveform accessibility semantics 已实现并通过 JVM/build/androidTest 编译验证；M5 已完成 release artifact 预检、R8/resource shrinking + API-26 lint 修复、sessions backup exclusion/UI disclosure、REL-001 30 min/2 h JVM simulation、REL-007 FGS start rejection/permission manifest contract、API 33+ notification permission gate、REL-006 BLE permission API boundary matrix、CAP-007 writer failure injection、REL-005 privacy/log/APK static audit，以及 REL-004 停止后等待 writer finalization 的 FGS 生命周期契约；unsigned、API/厂商/真机、签名/正式隐私门禁仍未完成。

本轮增量：M4 `BleHome` 改为可滚动根容器，instrumentation 在 Activity recreation 前后使用 `performScrollTo()` 检查 Live/Capture/Sessions/卸载提示入口可达；仅改善小屏/动态字号下的 UI 可达性，不改变 raw、CSV、算法或服务所有权。

本轮增量：M4 SAF picker 取消现在始终产生“导出已取消” action feedback；运行中的 export/recovery 取消保留 action 类型，协程 `CancellationException` 不再被宽泛异常处理写成失败，避免旧操作污染新会话状态。

本轮增量：M2 `BlePermissionResultSeam` 现在合并分步 runtime permission 回调，保留此前已授予但本次 map 未携带的权限，同时尊重显式 `false` 撤权；补充 API 33 分步 SCAN→CONNECT 恢复测试。

本轮增量：M4 Sessions refresh/inspection 的文件读取结果现在保留 coroutine cancellation，不会把被新操作取消的旧 job 写成错误状态；新增 cancellation boundary JVM 回归测试。

本轮增量：已明确 live metrics 仅作为 raw 写入时快照，异步分析不得回填已写 CSV 行；离线分析结果应另行版本化且不修改源 CSV。

本轮增量：新增 release `REL-003/REL-004` lifecycle contract report，静态检查 `START_NOT_STICKY`、terminal finalization observation、onDestroy cleanup 顺序及 immutable stop action；该报告不替代 emulator/真机生命周期验证。

本轮增量：补齐 service 内部 `startForeground()` SecurityException 的保留式 failure StateFlow；binder→ViewModel→capture gate 可显示 `ForegroundServiceStartRejected`，并新增 JVM gate 断言与 release 静态 wiring 检查。

本轮增量：新增 REL-002 fake GATT 20 次生命周期循环门禁；验证 generation 单调、Idle/freshness reset、late callback 丢弃、CCCD enable/disable 一轮一次及 passive control 边界，真机循环仍待执行。

本轮增量：Android BLE adapter 新增统一幂等 GATT 释放路径；替换连接、断开权限异常、stale callback 和 transport close 均清理 GATT/map/connectedIds/pending CCCD，并加入 REL-002 release 静态 contract 报告。

本轮增量：补齐 BLE transport/coordinator availability activation；初始化先安装 event sink 再 activate，权限恢复后的首次扫描会重新查询 adapter，activate 的 SecurityException 映射为 `UNAUTHORIZED`，并新增 fake coordinator 测试。

本轮增量：补齐连接后撤销蓝牙权限时的 CCCD 写入失败路径；adapter 清理 pending descriptor 并发布订阅失败事件，fake GATT 与 release contract 覆盖该边界，真机权限撤销仍待执行。

## 当前一句话

Android 工程仍是 Kotlin/Compose 默认壳；已交付 M1 CUP protocol、CUPRAW1、CSV、snake_case session metadata codec、bounded raw replay、CSV audit、metadata cross-check、固定 SOS/DC/gap-reset preprocessing、SciPy-compatible peak detector、HR estimator、provisional SQI、diagnostic ratio-of-ratios、MetricResult/source-time 和 800/100 live window scheduler，M2 BLE profile/权限/freshness/deadline/fake transport/GATT event-state core、Android scanner/GATT adapter、permission result seam、app-scope coordinator、Activity Result/lifecycle Compose StateFlow seam，以及 M3 raw-first writer、incomplete metadata、no-overwrite、storage/name/start gate、幂等结束语义、有界 256 raw queue、增量 decoder/sequence gate、recording controller、connectedDevice FGS/notification/bind seam、filesystem catalog/incomplete discovery、atomic metadata/raw/CSV checkpoint、streaming safe-prefix recovery/provenance、streaming zip/SAF/FileProvider export seam 和 bounded async live-analysis StateFlow/binder seam，以及 M4 的 CaptureViewModel/service client、recording/analysis snapshot StateFlow、capture name/gate/start-stop surface、真实存储预检传递、bounded 800-sample/5 Hz waveform ring、min/max bucket Canvas、HR/SQI/R validity presentation、app-scope BLE preview decoder/sequence/waveform/metrics StateFlow、filesystem-backed Sessions catalog/detail inspection surface、SAF export/safe-prefix recovery action feedback、bounded raw replay detail/diagnostic waveform、Swift-compatible replay viewport controls、Activity/FileProvider instrumentation test seam、first-stop/FGS acceptance audit 和 waveform accessibility semantics。下一步是运行 instrumentation 后的 M4 acceptance audit；不关闭真实设备协议风险。

## 与 MigrationPlanning 对照

| 版本 | 对应阶段 | 已交付能力 | 状态 | 下一步 |
|---|---|---|---|---|
| `M0.1` | Phase 0 | Agent 入口 prompt、项目级工作约定、详细/简版状态、当前 Android 基线核对 | 已完成（文档） | `M0.2`：工程基础、ADR、测试门禁 |
| `M0.2` | Phase 0 | Android 基线 ADR、CI JVM/build 门禁 | 已实现并经 JDK 验证 | `M1`：纯 Kotlin CUP protocol golden slice |
| `M1` | Phase 1 | CUP protocol、CUPRAW1、25 列 CSV、snake_case session metadata codec、bounded replay/inspection、preprocessing parity、peak detector、HR estimator、SQI、diagnostic ratio-of-ratios、MetricResult、800/100 live scheduler、数据完整性边界 JVM tests | protocol/raw/CSV/metadata/inspection-replay/preprocessing/peak/HR/SQI/ratio/live-core slices 已实现；Android async runner/lifecycle 未接入 | M2 BLE permissions/GATT/fake transport |
| `M2` | Phase 2 | BLE 权限、扫描、GATT、订阅、freshness、诊断 | profile、权限策略、分步 permission callback merge、phase/deadline/freshness、fake transport、fake GATT state machine、Android scanner/GATT adapter、permission seam、app-scope coordinator、Activity Result/Compose StateFlow 已实现并经 JVM/build 验证；真实 UI/真机门禁未开始 | async analysis/lifecycle / M3 writer integration |
| `M3` | Phase 3 | raw-first writer、CSV/session、开始前 gate、幂等 finalizer、accepted raw stream controller、connectedDevice FGS seam、session catalog/checkpoint、safe-prefix recovery、export seam、async live-analysis seam | writer/session/controller/manifest/service/repository/recovery/export/analysis 已实现并经 JVM/build 验证；系统后台/重建行为、用户正式页面仍未验收 | formal capture/sessions UI、lifecycle binding、metrics CSV policy |
| `M4` | Phase 4 | V1 Compose 实时/录制/历史/详情/重放 | lifecycle-aware FGS binding、recording/analysis StateFlow、capture name/gate/start-stop、recording waveform/metrics、coordinator preview continuity、Sessions catalog/detail inspection、SAF export/recovery action feedback、确定性 picker cancel、可取消 refresh/inspection、bounded raw replay summary/waveform、inspection cancel、pure zoom/pan viewport、可滚动页面、waveform semantics 和 instrumentation test seam 已实现；instrumentation runtime/系统重建/动态字号/TalkBack/SAF provider 验收未完成 | run instrumentation when emulator/device is available、M4 acceptance audit |
| `M5` | Phase 5 | 长稳、API/厂商矩阵、性能、隐私、发布硬化，形成 V1.0 | 已完成 release preflight、R8/resource shrinking、release lint（0 errors）、静态 artifact scan、sessions backup exclusion、UI disclosure contract、REL-001 30 min/2 h JVM simulation、REL-007 FGS start rejection/permission manifest contract、API 33+ notification permission gate、REL-006 BLE permission API boundary matrix、CAP-007 writer failure injection、REL-005 privacy/log/APK static audit、REL-004 FGS 在 raw/CSV/session finalizer 结束后再退出，以及 REL-006/REL-007 merged-manifest/API target 静态报告；unsigned、API/厂商/真机/签名/正式隐私门禁未完成 | API emulator/厂商运行矩阵、真实生命周期/2 h、签名/隐私策略 |
| `M6` | Phase 6 | V1.1 离线稳定段、频谱、周期和对比工作台 | 未开始 | 依赖 raw replay 和独立分析版本 |
| `M7` | 后续 V2 | 专家诊断和有证据支持的扩展 | 未开始 | 另行决策 |

## 当前 Android 工程事实

- Kotlin `2.2.10`、AGP `9.3.1`、Gradle `9.5.0`、Compose BOM `2026.02.01`、Java 11；release 已启用 R8/resource shrinking，仍为 unsigned 构建。
- `minSdk=26`、`compileSdk=37`、`targetSdk=37`。
- `applicationId=com.example.ppgcollector_android`、`versionName=1.0` 是占位值。
- 已有 `MainActivity`、Material 3 theme、BLE/协议/信号/session/FGS/Compose 实现及 JVM/instrumentation 编译门禁；instrumentation runtime 和真实设备门禁仍未执行。

## 当前未完成能力

Android 真机长稳均未交付；sessions 已配置不进入 cloud/device backup，并在 Sessions 页面说明卸载与显式 ZIP 导出边界；M4 已有 recording-owned bounded 800-sample/5 Hz 双轨 Canvas、HR/SQI/R 状态卡、source/version 展示和 coordinator-owned preview runtime，停止录制后 UI 可切换到 app-scope preview；Sessions 页面已支持 filesystem catalog、complete/incomplete/recovery findings、只读详情 inspection、inspection cancel、raw replay bounded statistics/recent RED/IR waveform、用户选择目标的 SAF ZIP export、进度/取消反馈、确定性 picker cancel、新目录 safe-prefix recovery action、bounded replay zoom/pan viewport、RED/IR/replay Canvas semantics 和可滚动页面，并已加入 Activity recreation/UI、FileProvider/connectedDevice FGS contract 和 first-stop regression seam，但尚未运行 emulator/device instrumentation、实际 backup restore、验证外部 provider、TalkBack/dynamic font、系统重建/后台/锁屏；metrics snapshot CSV 边界已定义为 raw acknowledgement 时快照，异步分析不回填源 CSV，真实设备行为仍待硬件验证，SQI 与 ratio 明确为 provisional/diagnostic，SpO2/BP 明确 unavailable。

## 验证与真机策略

- 本轮 M4 使用 Android Studio JDK 25 完成 `./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --no-daemon` 和 `./gradlew test lintRelease assembleRelease assembleDebugAndroidTest --no-daemon`，均 `BUILD SUCCESSFUL`；JVM 99 tests/0 failures，release lint 0 errors、R8/resource shrinking、debug/androidTest APK 编译通过；`./gradlew :app:verifyReleasePrivacy --no-configuration-cache --no-daemon` 也通过，既有 REL-002/003/004/006/007 静态契约保持通过。本轮不进行 emulator/真机运行，Activity recreation、动态字号和 TalkBack 仅有编译/测试 wiring 证据。
- 最近 M5 使用 Android Studio JDK 25 完成 release artifact、REL-001 30 min/2 h simulation、checkpoint 预算、raw/CSV/replay 对齐断言、REL-007 FGS 启动失败映射和 target/permission/service 静态 contract、API 33+ notification permission gate、REL-006 BLE permission API boundary matrix、CAP-007 写入失败升级与 incomplete-prefix 断言、REL-004 service finalization wait、merged-manifest/API target report，以及 `:app:verifyReleasePrivacy --no-configuration-cache` 的源码日志/APK 内容审计；本轮 M4 不重复关闭这些证据。
- `git diff --check` 通过；golden wire、协议边界、CUPRAW1 round-trip、LE header、截尾 safe-prefix、超限防御、跨 raw chunk replay、bounded recent samples、25 列 header、CSV streaming tail audit、raw/CSV/metadata cross-check、固定 SOS/DC/gap reset、四个 preprocessing fixture case、plateau midpoint、distance tie、prominence/width peak semantics、6 个 HR fixture case 的 BPM/DFT/RR/confidence/reason trace、8 个 SQI fixture case 的 peak/cycle/template/Pearson/grade trace、ratio-of-ratios 的 trim/RMS/ACDC/invalid paths、MetricResult invalid/calibration separation、800/100 cadence/window bound、gap generation、rejected frame、stale request/source timestamp、M2 NUS profile/name filter、API 30/31/33 permission branches、waiting/fresh/stale freshness、stage/device stale deadline、timeout clamp、phase flags、fake transport command order、fake GATT connect/service/characteristic/CCCD/receiving path、raw byte/timestamp copy、wrong phase/generation rejection、missing service/notify failure、deadline polling、Android adapter compile/Manifest merge、permission result deny/recover、coordinator permission/availability/scan/connect gates、coordinator snapshot/raw seam、Activity Result/lifecycle-compose compile、Application manifest wiring、Locale.ROOT、RFC 4180 转义、metadata round-trip 和 JSON audit 均有 JVM 测试。
- 每轮迭代结束不立即上机；真机协议、后台/锁屏、API/厂商矩阵、长录制和功耗统一作为待执行硬件门禁。除非用户明确要求，不将真机测试作为本轮默认动作。

## 当前开放阻塞

`D-001` 真实 CUP 协议/固件抓包；`D-002` 目标设备矩阵；`D-003` 后台录制策略；`D-004` 最终包名/签名/分发；`D-005` 数据保留/导出/加密；`D-006` 跨平台文件双向兼容；`D-007` version/profile 命名；`D-008` SpO2/BP 产品文案。

详细事实记录见 [`DEVELOPMENT_STATUS_DETAILED.md`](DEVELOPMENT_STATUS_DETAILED.md)。
