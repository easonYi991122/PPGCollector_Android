# ADR-0001：Android 基础工程与测试门禁

- 状态：Accepted for M0.2 foundation；产品/设备决策仍开放
- 日期：2026-08-01
- 影响版本：M0.2

## 背景

Android 工程目前是单模块 Kotlin/Compose 默认壳。迁移必须先建立可在没有真机的环境中验证的 JVM/build 门禁，同时不能把尚未由真实 CUP 固件确认的协议草案、算法结果或平台生命周期行为伪装成已冻结能力。

## 决策

1. 当前基础工程继续使用现有事实基线：Kotlin `2.2.10`、AGP `9.3.1`、Gradle `9.5.0`、Java 11、`minSdk=26`、`compileSdk/targetSdk=37`。这些值是当前工程基线，不代表最终发布目标；发布前仍需完成 D-002 审查。
2. 在进入 M1 前，CI 的快速门禁执行 `./gradlew test` 与 `./gradlew assembleDebug`。JVM 单测是纯 Kotlin core 的首选验收；instrumented test 和真机 BLE/FGS 门禁分开执行，不在普通 CI 中假定有物理设备。
3. 业务代码按 `core`（纯 Kotlin 协议/信号）与 Android 平台层（BLE、服务、Compose、文件 API）分界。核心构造函数不得依赖 `Context`、`BluetoothGatt` 或 Compose 类型；后续拆模块时保持依赖方向 `feature/data → core`。
4. 录制相关所有权在架构层预先冻结为单一 owner：录制期间由 connected-device foreground service 持有 GATT/session writer，Activity 只观察和绑定。实现和 API 34+ 验收延期到后续切片，直到 D-003 明确。
5. 协议 profile 在 M1 仍标记为 draft/bring-up；当前 408-byte、50-pair、100 Hz 仅作为 golden fixture 基线。D-001 未关闭前，不修改 reference snapshot、不称为 production protocol。

## 不选择的方案

- 不在 M0.2 提前引入 Hilt、Room 或第三方 DSP；它们会扩大基础切片，且 core 的普通 Kotlin 注入已经满足 JVM 测试 seam。
- 不用 fake BLE 或模拟数据替代真实 GATT 证据；fake 只用于后续行为测试，不能关闭硬件门禁。
- 不把 `versionName=1.0` 当作迁移版本；迁移版本以 `M*` 和状态/提交记录为准。

## 后果与待办

- 当前单模块仍可构建，未来可按 `:core:protocol`、`:core:signal`、`:data:session`、`:data:ble` 拆分而不改变契约。
- CI 配置提供命令门禁，但本地当前没有可用 Java Runtime，因此本轮只能完成静态配置核对；JDK 配置后必须重跑。
- D-001、D-002、D-003、D-004、D-005、D-006、D-007、D-008 仍是开放项；本 ADR 不关闭它们。

## 验收

- `./gradlew test`
- `./gradlew assembleDebug`
- `git diff --check`
- 真机 GATT、后台/锁屏、API/厂商矩阵和长录制：pending hardware validation
