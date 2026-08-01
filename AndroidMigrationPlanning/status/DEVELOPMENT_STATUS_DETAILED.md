# PPGCollector Android 详细开发状态

本文件只记录实际发生的项目事实、变更、验证、风险和决策，不作为新的规划替代物。每轮迭代完成后在文件末尾追加一条记录；不要删除或改写历史记录来美化进度。规划和下一步以 [`../00_AGENT_MIGRATION_BRIEF.md`](../00_AGENT_MIGRATION_BRIEF.md) 与 [`DEVELOPMENT_STATUS.md`](DEVELOPMENT_STATUS.md) 为入口。

## 记录规则

- 每条记录使用一个 Migration 版本（`M0`、`M1`……）；它与 Android `versionName` 分开。
- 记录“实现/验证/待真机验证”三种状态，不把代码存在等同于功能验收通过。
- 测试命令、环境错误、未运行的真机门禁都必须如实记录。
- `reference_sources/` 为只读快照；发现问题时记录证据和处理方式，不直接改快照。

## 2026-08-01 · M0.1 · 规划与 agent 工作流基线

### 本轮目标

建立一个在每轮迭代和上下文压缩后都能恢复的 Codex 迁移入口，整理 AndroidMigrationPlanning 与当前 Android 工程的关系，记录双层 development status，并明确版本/测试/提交纪律。

### 已阅读和核对的材料

- `AndroidMigrationPlanning/README.md`、`docs/01` 至 `docs/06`、`manifests/SOURCE_CATALOG.md`、`manifests/EXCLUSIONS.md`。
- iOS 当前实现的 Domain、BLE、CUP protocol、storage、signal processing、live capture、session UI 及对应 tests/resources。
- Python/C++ CUP protocol、golden frame、Python GUI 的 online/offline/session/integrity/timing/analysis 代码和测试。
- 产品需求 DOCX 的正文、需求 mockup、protocol frame 图和 SQI 参考脚本。
- 当前 Android 工程的 Gradle/Kotlin/Compose 配置、manifest、MainActivity、theme、示例 unit/instrumented tests。

### 形成的关键事实

1. Android 工程当前是基础 Compose 壳：`MainActivity` 只显示默认 Hello 文案；没有 BLE 权限/扫描/GATT、CUP decoder、signal、raw/CSV/session、foreground service 或迁移页面。
2. 工程实际基线为 Kotlin `2.2.10`、AGP `9.3.1`、Gradle wrapper `9.5.0`、Compose BOM `2026.02.01`、Java source/target `11`、`minSdk=26`、`compileSdk=37`、`targetSdk=37`。
3. 当前 Android `applicationId=com.example.ppgcollector_android`、显示名 `PPGCollector_Android`、`versionName=1.0` 均是基础工程占位事实，不能作为最终产品/迁移完成版本。
4. 跨语言协议草案一致使用 408 bytes、100 Hz、50 组 UInt32 LE RED/IR、sequence 和 `AB BA`/`CD DC`；产品协议图同时出现 32 与 50 的表述，真实设备确认仍是 Phase 0 阻断项。
5. iOS 当前可执行行为和测试提供了 raw-first、CUPRAW1、25 列 CSV、snake_case metadata、恢复/inspection、800 sample live window、100 sample step、5 Hz waveform、1 Hz metrics 和 provisional SQI/R 语义。
6. Python 资料适合作为算法/离线工作台交叉参考；旧 Python BLE/NUS、旧协议、旧 CSV、IMU 和隔壁工程不作为 Android V1 依据。

### 本轮修改

- 新增项目级 [`AGENTS.md`](../../AGENTS.md)，将入口文档、状态阅读、真机延后、状态更新和 commit 规则固化为项目工作约定。
- 新增 [`00_AGENT_MIGRATION_BRIEF.md`](../00_AGENT_MIGRATION_BRIEF.md)，形成可直接复制的 Codex 目标模式 prompt，并记录当前工程事实、证据优先级、M0–M7 映射、不可破坏契约和迭代流程。
- 新增本详细状态文件和 [`DEVELOPMENT_STATUS.md`](DEVELOPMENT_STATUS.md) 简版状态文件。
- 将现有规划文档中的 SDK 基线与当前工程的 `compileSdk/targetSdk=37` 对齐，并保留发布前重新审查平台政策的要求。
- 在 `README.md` 增加 agent 入口、状态入口和当前 Android 工程快照说明。

### 验证

- 已完成规划、源代码索引、参考源分类、DOCX 正文、两张需求图片和当前 Android 工程配置的静态核对。
- 已尝试：`./gradlew test assembleDebug`。
- 结果：未执行，当前环境报告 `Unable to locate a Java Runtime`；不是测试通过。安装/配置 JDK 后需重跑。
- 按项目约定，本轮未进行真机测试；真实 GATT、API/厂商矩阵和长录制仍为 pending hardware validation。

### 当前开放项/风险

- `D-001`：真实 CUP 固件/GATT/通知抓包未确认 408-byte/50-pair draft、length 语义、端序、checksum、采样率和控制流程。
- `D-002`：目标设备/OS/厂商矩阵未确认；工程当前用 `minSdk=26`、compile/target 37。
- `D-003`：录制后台/锁屏/任务划除策略未取得产品确认；规划默认 connectedDevice FGS + 明确通知停止。
- `D-004`：最终 applicationId、显示名、签名和分发渠道未确认。
- `D-005` 至 `D-008`：数据保留/导出/加密、跨平台兼容范围、版本命名、SpO2/BP 文案仍需决策。

### 下一轮建议

以 `M0.2` 继续工程基础和 Phase 0 ADR/测试门禁，或在不冻结生产协议的前提下进入 `M1` 的纯 Kotlin CUP protocol golden slice。不得把真实设备未确认的 draft profile 改名为 production。

## 后续记录模板（复制后追加到文件末尾）

```text
## YYYY-MM-DD · Mx[.y] · <iteration title>

### 本轮目标
-

### 需求/参考/Android 目标
- Requirement:
- Primary source:
- Tests/golden:
- Android target:
- Non-goals:

### 实现事实
-

### 验证
- Command/result:
- Hardware validation: pending / passed / blocked (<reason>)

### 风险与决策变化
-

### 下一轮
-
```
