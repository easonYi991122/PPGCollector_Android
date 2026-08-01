# PPGCollector Android Development Status

更新时间：2026-08-01
当前迁移版本：`M1`
当前规划阶段：Phase 1（纯 Kotlin 对等内核）
状态：M1 protocol、raw file 与 CSV slices 已实现并通过 JVM/build 验证；profile 仍为 draft。

## 当前一句话

Android 工程仍是 Kotlin/Compose 默认壳；已交付 M1 CUP protocol、CUPRAW1、CSV schema/formatter/parser 和 JVM tests。下一步继续 session metadata/inspection；不关闭真实设备协议风险。

## 与 MigrationPlanning 对照

| 版本 | 对应阶段 | 已交付能力 | 状态 | 下一步 |
|---|---|---|---|---|
| `M0.1` | Phase 0 | Agent 入口 prompt、项目级工作约定、详细/简版状态、当前 Android 基线核对 | 已完成（文档） | `M0.2`：工程基础、ADR、测试门禁 |
| `M0.2` | Phase 0 | Android 基线 ADR、CI JVM/build 门禁 | 已实现并经 JDK 验证 | `M1`：纯 Kotlin CUP protocol golden slice |
| `M1` | Phase 1 | CUP protocol、CUPRAW1、25 列 CSV schema/formatter/parser、数据完整性边界 JVM tests | protocol/raw/CSV slices 已实现；session/signal 未开始 | 继续 session metadata，再做 signal |
| `M2` | Phase 2 | BLE 权限、扫描、GATT、订阅、freshness、诊断 | 未开始 | 依赖 profile/设备证据和 fake GATT |
| `M3` | Phase 3 | raw-first writer、CSV/session、FGS、停止/恢复/导出 | 未开始 | 依赖 M1/M2 的 accepted stream |
| `M4` | Phase 4 | V1 Compose 实时/录制/历史/详情/重放 | 未开始 | 依赖可观察的数据链路 |
| `M5` | Phase 5 | 长稳、API/厂商矩阵、性能、隐私、发布硬化，形成 V1.0 | 未开始 | 需要真机与发布证据 |
| `M6` | Phase 6 | V1.1 离线稳定段、频谱、周期和对比工作台 | 未开始 | 依赖 raw replay 和独立分析版本 |
| `M7` | 后续 V2 | 专家诊断和有证据支持的扩展 | 未开始 | 另行决策 |

## 当前 Android 工程事实

- Kotlin `2.2.10`、AGP `9.3.1`、Gradle `9.5.0`、Compose BOM `2026.02.01`、Java 11。
- `minSdk=26`、`compileSdk=37`、`targetSdk=37`。
- `applicationId=com.example.ppgcollector_android`、`versionName=1.0` 是占位值。
- 已有默认 `MainActivity`、Material 3 theme、示例 unit/instrumented test；尚无迁移业务能力。

## 当前未完成能力

BLE/权限/GATT、CUP decoder/sequence gate、raw/CSV/session、预处理/HR/SQI/R、前台服务、恢复/导出、正式 Compose 页面、Android 生命周期与真机长稳均未交付。

## 验证与真机策略

- 本轮 M1 使用 Android Studio JDK 25 完成 `./gradlew test` 和 `./gradlew assembleDebug`。
- `git diff --check` 通过；golden wire、协议边界、CUPRAW1 round-trip、LE header、截尾 safe-prefix、超限防御、25 列 header、Locale.ROOT、RFC 4180 转义、metric invalid 语义和 parser audit 均有 JVM 测试。
- 每轮迭代结束不立即上机；真机协议、后台/锁屏、API/厂商矩阵、长录制和功耗统一作为待执行硬件门禁。除非用户明确要求，不将真机测试作为本轮默认动作。

## 当前开放阻塞

`D-001` 真实 CUP 协议/固件抓包；`D-002` 目标设备矩阵；`D-003` 后台录制策略；`D-004` 最终包名/签名/分发；`D-005` 数据保留/导出/加密；`D-006` 跨平台文件双向兼容；`D-007` version/profile 命名；`D-008` SpO2/BP 产品文案。

详细事实记录见 [`DEVELOPMENT_STATUS_DETAILED.md`](DEVELOPMENT_STATUS_DETAILED.md)。
