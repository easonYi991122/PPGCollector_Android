# PPGCollector Android Development Status

更新时间：2026-08-02
当前迁移版本：`M4`
当前规划阶段：Phase 4（V1 Compose 产品 UI）
状态：M1 protocol/raw/session/signal core、M2 BLE owner/adapter/permission/UI seam、M3 raw-first writer/FGS/session recovery/export/async analysis，以及 M4 Activity/ViewModel→connectedDevice FGS lifecycle-aware binding、capture name/gate/start-stop、bounded 5 Hz waveform/metrics surface 已实现并通过 JVM/build 验证；完整非录制预览、历史/详情/重放 UI、真实设备 profile 和系统生命周期门禁仍未完成。

## 当前一句话

Android 工程仍是 Kotlin/Compose 默认壳；已交付 M1 CUP protocol、CUPRAW1、CSV、snake_case session metadata codec、bounded raw replay、CSV audit、metadata cross-check、固定 SOS/DC/gap-reset preprocessing、SciPy-compatible peak detector、HR estimator、provisional SQI、diagnostic ratio-of-ratios、MetricResult/source-time 和 800/100 live window scheduler，M2 BLE profile/权限/freshness/deadline/fake transport/GATT event-state core、Android scanner/GATT adapter、permission result seam、app-scope coordinator、Activity Result/lifecycle Compose StateFlow seam，以及 M3 raw-first writer、incomplete metadata、no-overwrite、storage/name/start gate、幂等结束语义、有界 256 raw queue、增量 decoder/sequence gate、recording controller、connectedDevice FGS/notification/bind seam、filesystem catalog/incomplete discovery、atomic metadata/raw/CSV checkpoint、streaming safe-prefix recovery/provenance、streaming zip/SAF/FileProvider export seam 和 bounded async live-analysis StateFlow/binder seam，以及 M4 的 CaptureViewModel/service client、recording/analysis snapshot StateFlow、capture name/gate/start-stop surface、真实存储预检传递、bounded 800-sample/5 Hz waveform ring、min/max bucket Canvas 和 HR/SQI/R validity presentation。下一步是非录制实时预览连续性、Sessions catalog/详情/重放 UI 与 Activity Result export；不关闭真实设备协议风险。

## 与 MigrationPlanning 对照

| 版本 | 对应阶段 | 已交付能力 | 状态 | 下一步 |
|---|---|---|---|---|
| `M0.1` | Phase 0 | Agent 入口 prompt、项目级工作约定、详细/简版状态、当前 Android 基线核对 | 已完成（文档） | `M0.2`：工程基础、ADR、测试门禁 |
| `M0.2` | Phase 0 | Android 基线 ADR、CI JVM/build 门禁 | 已实现并经 JDK 验证 | `M1`：纯 Kotlin CUP protocol golden slice |
| `M1` | Phase 1 | CUP protocol、CUPRAW1、25 列 CSV、snake_case session metadata codec、bounded replay/inspection、preprocessing parity、peak detector、HR estimator、SQI、diagnostic ratio-of-ratios、MetricResult、800/100 live scheduler、数据完整性边界 JVM tests | protocol/raw/CSV/metadata/inspection-replay/preprocessing/peak/HR/SQI/ratio/live-core slices 已实现；Android async runner/lifecycle 未接入 | M2 BLE permissions/GATT/fake transport |
| `M2` | Phase 2 | BLE 权限、扫描、GATT、订阅、freshness、诊断 | profile、权限策略、phase/deadline/freshness、fake transport、fake GATT state machine、Android scanner/GATT adapter、permission seam、app-scope coordinator、Activity Result/Compose StateFlow 已实现并经 JVM/build 验证；真实 UI/真机门禁未开始 | async analysis/lifecycle / M3 writer integration |
| `M3` | Phase 3 | raw-first writer、CSV/session、开始前 gate、幂等 finalizer、accepted raw stream controller、connectedDevice FGS seam、session catalog/checkpoint、safe-prefix recovery、export seam、async live-analysis seam | writer/session/controller/manifest/service/repository/recovery/export/analysis 已实现并经 JVM/build 验证；系统后台/重建行为、用户正式页面仍未验收 | formal capture/sessions UI、lifecycle binding、metrics CSV policy |
| `M4` | Phase 4 | V1 Compose 实时/录制/历史/详情/重放 | lifecycle-aware FGS binding、recording/analysis StateFlow、capture name/gate/start-stop、recording-owned waveform/metrics surface 已实现；非录制预览、sessions 页面与重建/可访问性验收未完成 | preview continuity、Sessions/detail/replay UI、Activity Result export |
| `M5` | Phase 5 | 长稳、API/厂商矩阵、性能、隐私、发布硬化，形成 V1.0 | 未开始 | 需要真机与发布证据 |
| `M6` | Phase 6 | V1.1 离线稳定段、频谱、周期和对比工作台 | 未开始 | 依赖 raw replay 和独立分析版本 |
| `M7` | 后续 V2 | 专家诊断和有证据支持的扩展 | 未开始 | 另行决策 |

## 当前 Android 工程事实

- Kotlin `2.2.10`、AGP `9.3.1`、Gradle `9.5.0`、Compose BOM `2026.02.01`、Java 11。
- `minSdk=26`、`compileSdk=37`、`targetSdk=37`。
- `applicationId=com.example.ppgcollector_android`、`versionName=1.0` 是占位值。
- 已有默认 `MainActivity`、Material 3 theme、示例 unit/instrumented test；尚无迁移业务能力。

## 当前未完成能力

Android 非录制实时预览、历史/详情/重放 Compose 页面与真机长稳均未交付；M4 已有 recording-owned bounded 800-sample/5 Hz 双轨 Canvas、HR/SQI/R 状态卡和 source/version 展示，以及 service 侧 20 MiB 存储预检，但尚未完成 coordinator-owned preview continuity、Activity Result/用户取消与外部 provider instrumentation、Sessions catalog/detail/recovery/export UI，也尚未定义 metrics snapshot 回填 CSV 的正式策略，真实设备行为仍待硬件验证，SQI 与 ratio 明确为 provisional/diagnostic，SpO2/BP 明确 unavailable，inspection/replay 当前只提供 JVM 纯文件内核。

## 验证与真机策略

- 本轮 M4 使用 Android Studio JDK 25 完成 `./gradlew test assembleDebug --no-daemon`，`BUILD SUCCESSFUL`；debug 编译包含 CaptureViewModel/service binding、recording/analysis/waveform StateFlow、capture name/gate/start-stop 与双轨 Canvas/metrics surface；5 Hz no-burst tick、800 ring、final flush、min/max bucket、valid/provisional/reason/source presentation、gate 和既有 raw/session/signal/export JVM tests 通过（81 tests）。
- `git diff --check` 通过；golden wire、协议边界、CUPRAW1 round-trip、LE header、截尾 safe-prefix、超限防御、跨 raw chunk replay、bounded recent samples、25 列 header、CSV streaming tail audit、raw/CSV/metadata cross-check、固定 SOS/DC/gap reset、四个 preprocessing fixture case、plateau midpoint、distance tie、prominence/width peak semantics、6 个 HR fixture case 的 BPM/DFT/RR/confidence/reason trace、8 个 SQI fixture case 的 peak/cycle/template/Pearson/grade trace、ratio-of-ratios 的 trim/RMS/ACDC/invalid paths、MetricResult invalid/calibration separation、800/100 cadence/window bound、gap generation、rejected frame、stale request/source timestamp、M2 NUS profile/name filter、API 30/31/33 permission branches、waiting/fresh/stale freshness、stage/device stale deadline、timeout clamp、phase flags、fake transport command order、fake GATT connect/service/characteristic/CCCD/receiving path、raw byte/timestamp copy、wrong phase/generation rejection、missing service/notify failure、deadline polling、Android adapter compile/Manifest merge、permission result deny/recover、coordinator permission/availability/scan/connect gates、coordinator snapshot/raw seam、Activity Result/lifecycle-compose compile、Application manifest wiring、Locale.ROOT、RFC 4180 转义、metadata round-trip 和 JSON audit 均有 JVM 测试。
- 每轮迭代结束不立即上机；真机协议、后台/锁屏、API/厂商矩阵、长录制和功耗统一作为待执行硬件门禁。除非用户明确要求，不将真机测试作为本轮默认动作。

## 当前开放阻塞

`D-001` 真实 CUP 协议/固件抓包；`D-002` 目标设备矩阵；`D-003` 后台录制策略；`D-004` 最终包名/签名/分发；`D-005` 数据保留/导出/加密；`D-006` 跨平台文件双向兼容；`D-007` version/profile 命名；`D-008` SpO2/BP 产品文案。

详细事实记录见 [`DEVELOPMENT_STATUS_DETAILED.md`](DEVELOPMENT_STATUS_DETAILED.md)。
