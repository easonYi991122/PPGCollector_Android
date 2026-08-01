# PPGCollector Android Development Status

更新时间：2026-08-02
当前迁移版本：`M5`
当前规划阶段：Phase 5（长稳、API/厂商矩阵、性能、隐私、发布硬化）
状态：M1 protocol/raw/session/signal core、M2 BLE owner/adapter/permission/UI seam、M3 raw-first writer/FGS/session recovery/export/async analysis，以及 M4 Activity/ViewModel→connectedDevice FGS lifecycle-aware binding、capture name/gate/start-stop、bounded recording waveform/metrics、app-scope non-recording preview continuity、filesystem-backed Sessions catalog/detail inspection、SAF export/safe-prefix recovery actions、bounded raw replay detail、pure viewport controls、instrumentation test seam、first-stop/FGS acceptance audit 和 waveform accessibility semantics 已实现并通过 JVM/build/androidTest 编译验证；M5 已完成 release artifact 预检、R8/resource shrinking + API-26 lint 修复、sessions backup exclusion/UI disclosure、REL-001 30 min/2 h JVM simulation、REL-007 FGS start rejection/permission manifest contract、API 33+ notification permission gate、REL-006 BLE permission API boundary matrix、CAP-007 writer failure injection、REL-005 privacy/log/APK static audit，以及 REL-004 停止后等待 writer finalization 的 FGS 生命周期契约；unsigned、API/厂商/真机、签名/正式隐私门禁仍未完成。

## 当前一句话

Android 工程仍是 Kotlin/Compose 默认壳；已交付 M1 CUP protocol、CUPRAW1、CSV、snake_case session metadata codec、bounded raw replay、CSV audit、metadata cross-check、固定 SOS/DC/gap-reset preprocessing、SciPy-compatible peak detector、HR estimator、provisional SQI、diagnostic ratio-of-ratios、MetricResult/source-time 和 800/100 live window scheduler，M2 BLE profile/权限/freshness/deadline/fake transport/GATT event-state core、Android scanner/GATT adapter、permission result seam、app-scope coordinator、Activity Result/lifecycle Compose StateFlow seam，以及 M3 raw-first writer、incomplete metadata、no-overwrite、storage/name/start gate、幂等结束语义、有界 256 raw queue、增量 decoder/sequence gate、recording controller、connectedDevice FGS/notification/bind seam、filesystem catalog/incomplete discovery、atomic metadata/raw/CSV checkpoint、streaming safe-prefix recovery/provenance、streaming zip/SAF/FileProvider export seam 和 bounded async live-analysis StateFlow/binder seam，以及 M4 的 CaptureViewModel/service client、recording/analysis snapshot StateFlow、capture name/gate/start-stop surface、真实存储预检传递、bounded 800-sample/5 Hz waveform ring、min/max bucket Canvas、HR/SQI/R validity presentation、app-scope BLE preview decoder/sequence/waveform/metrics StateFlow、filesystem-backed Sessions catalog/detail inspection surface、SAF export/safe-prefix recovery action feedback、bounded raw replay detail/diagnostic waveform、Swift-compatible replay viewport controls、Activity/FileProvider instrumentation test seam、first-stop/FGS acceptance audit 和 waveform accessibility semantics。下一步是运行 instrumentation 后的 M4 acceptance audit；不关闭真实设备协议风险。

## 与 MigrationPlanning 对照

| 版本 | 对应阶段 | 已交付能力 | 状态 | 下一步 |
|---|---|---|---|---|
| `M0.1` | Phase 0 | Agent 入口 prompt、项目级工作约定、详细/简版状态、当前 Android 基线核对 | 已完成（文档） | `M0.2`：工程基础、ADR、测试门禁 |
| `M0.2` | Phase 0 | Android 基线 ADR、CI JVM/build 门禁 | 已实现并经 JDK 验证 | `M1`：纯 Kotlin CUP protocol golden slice |
| `M1` | Phase 1 | CUP protocol、CUPRAW1、25 列 CSV、snake_case session metadata codec、bounded replay/inspection、preprocessing parity、peak detector、HR estimator、SQI、diagnostic ratio-of-ratios、MetricResult、800/100 live scheduler、数据完整性边界 JVM tests | protocol/raw/CSV/metadata/inspection-replay/preprocessing/peak/HR/SQI/ratio/live-core slices 已实现；Android async runner/lifecycle 未接入 | M2 BLE permissions/GATT/fake transport |
| `M2` | Phase 2 | BLE 权限、扫描、GATT、订阅、freshness、诊断 | profile、权限策略、phase/deadline/freshness、fake transport、fake GATT state machine、Android scanner/GATT adapter、permission seam、app-scope coordinator、Activity Result/Compose StateFlow 已实现并经 JVM/build 验证；真实 UI/真机门禁未开始 | async analysis/lifecycle / M3 writer integration |
| `M3` | Phase 3 | raw-first writer、CSV/session、开始前 gate、幂等 finalizer、accepted raw stream controller、connectedDevice FGS seam、session catalog/checkpoint、safe-prefix recovery、export seam、async live-analysis seam | writer/session/controller/manifest/service/repository/recovery/export/analysis 已实现并经 JVM/build 验证；系统后台/重建行为、用户正式页面仍未验收 | formal capture/sessions UI、lifecycle binding、metrics CSV policy |
| `M4` | Phase 4 | V1 Compose 实时/录制/历史/详情/重放 | lifecycle-aware FGS binding、recording/analysis StateFlow、capture name/gate/start-stop、recording waveform/metrics、coordinator preview continuity、Sessions catalog/detail inspection、SAF export/recovery action feedback、bounded raw replay summary/waveform、inspection cancel、pure zoom/pan viewport 和 instrumentation test seam 已实现；instrumentation runtime/系统重建/可访问性/SAF provider 验收未完成 | run instrumentation when emulator/device is available、M4 acceptance audit |
| `M5` | Phase 5 | 长稳、API/厂商矩阵、性能、隐私、发布硬化，形成 V1.0 | 已完成 release preflight、R8/resource shrinking、release lint（0 errors）、静态 artifact scan、sessions backup exclusion、UI disclosure contract、REL-001 30 min/2 h JVM simulation、REL-007 FGS start rejection/permission manifest contract、API 33+ notification permission gate、REL-006 BLE permission API boundary matrix、CAP-007 写入失败 incomplete-prefix 故障注入、REL-005 privacy/log/APK static audit，以及 REL-004 FGS 在 raw/CSV/session finalizer 结束后再退出；unsigned、API/厂商/真机/签名/正式隐私门禁未完成 | merged-manifest/API target report、API emulator/厂商运行矩阵、真实生命周期/2 h、签名/隐私策略 |
| `M6` | Phase 6 | V1.1 离线稳定段、频谱、周期和对比工作台 | 未开始 | 依赖 raw replay 和独立分析版本 |
| `M7` | 后续 V2 | 专家诊断和有证据支持的扩展 | 未开始 | 另行决策 |

## 当前 Android 工程事实

- Kotlin `2.2.10`、AGP `9.3.1`、Gradle `9.5.0`、Compose BOM `2026.02.01`、Java 11；release 已启用 R8/resource shrinking，仍为 unsigned 构建。
- `minSdk=26`、`compileSdk=37`、`targetSdk=37`。
- `applicationId=com.example.ppgcollector_android`、`versionName=1.0` 是占位值。
- 已有 `MainActivity`、Material 3 theme、BLE/协议/信号/session/FGS/Compose 实现及 JVM/instrumentation 编译门禁；instrumentation runtime 和真实设备门禁仍未执行。

## 当前未完成能力

Android 真机长稳均未交付；sessions 已配置不进入 cloud/device backup，并在 Sessions 页面说明卸载与显式 ZIP 导出边界；M4 已有 recording-owned bounded 800-sample/5 Hz 双轨 Canvas、HR/SQI/R 状态卡、source/version 展示和 coordinator-owned preview runtime，停止录制后 UI 可切换到 app-scope preview；Sessions 页面已支持 filesystem catalog、complete/incomplete/recovery findings、只读详情 inspection、inspection cancel、raw replay bounded statistics/recent RED/IR waveform、用户选择目标的 SAF ZIP export、进度/取消反馈、新目录 safe-prefix recovery action、bounded replay zoom/pan viewport 和 RED/IR/replay Canvas semantics，并已加入 Activity recreation/UI、FileProvider/connectedDevice FGS contract 和 first-stop regression seam，但尚未运行 emulator/device instrumentation、实际 backup restore、验证外部 provider、TalkBack/dynamic font、系统重建/后台/锁屏，也尚未定义 metrics snapshot 回填 CSV 的正式策略，真实设备行为仍待硬件验证，SQI 与 ratio 明确为 provisional/diagnostic，SpO2/BP 明确 unavailable。

## 验证与真机策略

- 本轮 M5 使用 Android Studio JDK 25 完成 `./gradlew test lintRelease assembleRelease assembleDebugAndroidTest --no-daemon`，`BUILD SUCCESSFUL`；release lint 0 errors、21 warnings，95 JVM tests/0 failures，debug/androidTest APK 编译通过，release 实际执行 R8/resource shrinking；包含 REL-001 30 min/2 h simulation、checkpoint 预算、raw/CSV/replay 对齐断言、REL-007 FGS 启动失败映射和 target/permission/service 静态 contract、API 33+ notification permission gate、REL-006 BLE permission API boundary matrix、CAP-007 写入失败升级与 incomplete-prefix 断言、REL-004 service finalization wait，以及 `:app:verifyReleasePrivacy --no-configuration-cache` 的源码日志/APK 内容审计。本轮不进行 emulator/真机运行。
- `git diff --check` 通过；golden wire、协议边界、CUPRAW1 round-trip、LE header、截尾 safe-prefix、超限防御、跨 raw chunk replay、bounded recent samples、25 列 header、CSV streaming tail audit、raw/CSV/metadata cross-check、固定 SOS/DC/gap reset、四个 preprocessing fixture case、plateau midpoint、distance tie、prominence/width peak semantics、6 个 HR fixture case 的 BPM/DFT/RR/confidence/reason trace、8 个 SQI fixture case 的 peak/cycle/template/Pearson/grade trace、ratio-of-ratios 的 trim/RMS/ACDC/invalid paths、MetricResult invalid/calibration separation、800/100 cadence/window bound、gap generation、rejected frame、stale request/source timestamp、M2 NUS profile/name filter、API 30/31/33 permission branches、waiting/fresh/stale freshness、stage/device stale deadline、timeout clamp、phase flags、fake transport command order、fake GATT connect/service/characteristic/CCCD/receiving path、raw byte/timestamp copy、wrong phase/generation rejection、missing service/notify failure、deadline polling、Android adapter compile/Manifest merge、permission result deny/recover、coordinator permission/availability/scan/connect gates、coordinator snapshot/raw seam、Activity Result/lifecycle-compose compile、Application manifest wiring、Locale.ROOT、RFC 4180 转义、metadata round-trip 和 JSON audit 均有 JVM 测试。
- 每轮迭代结束不立即上机；真机协议、后台/锁屏、API/厂商矩阵、长录制和功耗统一作为待执行硬件门禁。除非用户明确要求，不将真机测试作为本轮默认动作。

## 当前开放阻塞

`D-001` 真实 CUP 协议/固件抓包；`D-002` 目标设备矩阵；`D-003` 后台录制策略；`D-004` 最终包名/签名/分发；`D-005` 数据保留/导出/加密；`D-006` 跨平台文件双向兼容；`D-007` version/profile 命名；`D-008` SpO2/BP 产品文案。

详细事实记录见 [`DEVELOPMENT_STATUS_DETAILED.md`](DEVELOPMENT_STATUS_DETAILED.md)。
