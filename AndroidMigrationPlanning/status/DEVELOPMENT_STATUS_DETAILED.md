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

## 2026-08-01 · M0.2 · Android 基础工程与测试门禁

### 本轮目标

在进入 M1 纯 Kotlin 协议实现前，交付 Phase 0 的最小工程基础切片：记录架构/版本/所有权决策，并建立可重复的 JVM 与 debug assemble 门禁。

### 需求/参考/Android 目标

- Requirement: Phase 0 §3.1/§3.2；REL-006、REL-007；架构文档 §1–§3。
- Primary source: `AndroidMigrationPlanning/docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md`、`docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md`、`docs/06_OPEN_DECISIONS_AND_RISK_REGISTER.md`。
- Tests/golden: 当前默认 JUnit/Compose test 基线；M1 再接入 `golden_seq42.bin`。
- Android target: `.github/workflows/android.yml`、`AndroidMigrationPlanning/docs/adr/ADR-0001-android-foundation-and-test-gates.md`。
- Non-goals: BLE/GATT、CUP decoder、raw/CSV/session、信号算法、FGS 实现、Compose 产品页面和真机测试。

### 实现事实

- 新增 ADR-0001，明确 Java/SDK 当前基线、core 与平台层边界、录制单一 owner、draft protocol 约束以及 M0.2 的不选择项。
- 新增 GitHub Actions workflow，在 Ubuntu/Temurin 11 上执行 `./gradlew test` 和 `./gradlew assembleDebug`；instrumented/真机门禁保持独立。
- 更新简版状态至 M0.2；未关闭 D-001～D-008。

### 验证

- `git diff --check`：通过（无空白错误）。
- `./gradlew test`：未执行，环境报告 `Unable to locate a Java Runtime`。
- `./gradlew assembleDebug`：未执行，同一 Java Runtime 环境阻塞。
- 真机 GATT、后台/锁屏、API/厂商矩阵、长录制：pending hardware validation，按约定本轮延期。

### 风险与决策变化

- ADR-0001 只冻结当前工程实施边界，不关闭 D-001（真实协议）或 D-002～D-008（产品/平台/兼容性决策）。
- `.idea/` 为本轮开始前已存在的用户未跟踪内容，未纳入修改或提交。

### 下一轮

进入 M1 `:core:protocol` 最小 golden slice，优先实现 draft CUP frame model、流式 decoder、sequence tracker 及碎片/粘包/噪声/序号边界测试。

## 2026-08-01 · M1 · CUP protocol golden JVM slice

### 本轮目标

按 Phase 1 `:core:protocol` 路线移植当前 CUP draft 的 frame model、LE 编解码、任意通知边界 stream decoder 和 sequence gate；保持真实设备未确认前的 draft/bring-up 表述。

### 需求/参考/Android 目标

- Requirement: PROTO-001、PROTO-002、PROTO-003；路线 §4.1；REL-006 的 JVM 门禁部分。
- Primary source: `reference_sources/ios_current/PPGCollector/Infrastructure/Protocol/CUPBatchProtocol.swift`、`CUPBatchStreamDecoder.swift`、`CUPFrameSequenceTracker.swift`；交叉核对 `reference_sources/protocol/cup_batch_protocol.py`。
- Tests/golden: `reference_sources/protocol/fixtures/golden_seq42.json` 的 `frame_hex`；Swift `CUPBatchProtocolTests`、`CUPFrameSequenceTrackerTests` 的等价边界断言。
- Android target: `app/src/main/java/com/example/ppgcollector_android/core/protocol/` 与 `app/src/test/java/com/example/ppgcollector_android/core/protocol/`。
- Non-goals: BLE/GATT、raw writer/reader、CSV/session、signal processing、pipeline/recent sample ring、真机协议冻结。

### 实现事实

- 新增 `CupBatchProtocolV1`、`CupPpgSample`、`CupBatchFrame` 和 `CupProtocolException`；保持 408-byte、`data_length=401`、50×LE `UInt32` RED/IR、sequence `UInt8`、`AB BA`/`CD DC` draft layout。
- 新增 `CupBatchStreamDecoder`：任意 byte chunk、粘包、前导噪声、错误 function/length/tail 重同步、部分 header 保留、有限 pending buffer 和分类统计。
- 新增 `CupFrameSequenceTracker`：first/continuous/gap、UInt8 wrap、duplicate/out-of-order 拒绝并保留统计；gap 不推进旧序号以外的状态。
- 新增 `golden_seq42.hex` 测试资源，测试 encode wire 与参考 frame hex byte-for-byte 相等；未修改 `reference_sources/`。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test`：通过，`BUILD SUCCESSFUL`。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug`：通过，`BUILD SUCCESSFUL`。
- `git diff --check`：通过。
- 真机 CUP GATT/MTU/通知抓取、API/厂商矩阵、后台和长录制：pending hardware validation，按约定本轮延期。

### 风险与决策变化

- D-001 仍开放：golden 只证明与当前 Swift/Python draft 对等，不证明真实固件协议或“50 对”描述已冻结。
- pending buffer 的 M1 默认上限为两个完整 frame；后续 pipeline/transport 仍需定义 raw 队列和诊断所有权，不能把 decoder 统计当作 raw 完整性证明。
- `.idea/` 为既有用户未跟踪内容，未纳入本轮提交。

### 下一轮

继续 M1 `:data:session` 纯文件 slice：先实现 `CUPRAW1\0` raw writer/reader、安全 64 KiB record 上限、截尾 safe-prefix 报告和 JVM 临时目录测试，再进入 signal parity。

## 2026-08-01 · M1 · CUPRAW1 raw file JVM slice

### 本轮目标

按 Phase 1 `:data:session` 纯文件路线实现 raw 真源的最小可验收切片：`CUPRAW1\0` writer/reader、LE record header、64 KiB 防御、流式扫描和截尾 safe-prefix 报告。

### 需求/参考/Android 目标

- Requirement: CAP-003、CAP-006、CAP-009；路线 §4.2；架构文档 §6.2、§10。
- Primary source: `reference_sources/ios_current/PPGCollector/Infrastructure/Storage/CUPRawFileReader.swift`；交叉核对 `docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md`。Python `PPGBIN1\0` 格式未采用。
- Tests/golden: Swift `CUPRawFileReader` scan/read/safe-tail 契约；本轮用 JVM temporary directory/byte arrays 对等验证。
- Android target: `app/src/main/java/com/example/ppgcollector_android/data/session/CupRawFile.kt` 与 `app/src/test/java/com/example/ppgcollector_android/data/session/CupRawFileTest.kt`。
- Non-goals: CSV schema/formatter、session JSON、raw-first decoder pipeline、recovery/export、BLE/FGS、真机测试。

### 实现事实

- `CupRawWriter` 使用 `CREATE_NEW` 防止覆盖，写入 magic 和 `<u64_le host_monotonic_ns><u32_le payload_length><payload>`；空 chunk 跳过，超出 64 KiB 在写入前拒绝，支持 `force` flush 和幂等 close。
- `CupRawReader.scan` 使用流式 `InputStream`，不按文件大小分配，先校验 magic；完整记录交给 visitor，返回 record count、valid byte count、total byte count、tail issue 和 peak record buffer。
- 对不完整 record header、声明超限 payload、截断 payload 分别返回分类 safe-prefix issue；`read` 在存在 tail issue 时抛出，`scan` 保留完整前缀信息。
- 测试覆盖 round-trip、LE 字节、空 chunk、no-overwrite 间接约束、截断 header/payload、超限未分配、invalid magic、streaming peak buffer 和 throwing read。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test`：通过，`BUILD SUCCESSFUL`。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug`：通过，`BUILD SUCCESSFUL`。
- `git diff --check`：通过（无空白错误）。
- 真机 GATT/后台/厂商矩阵/长录制：pending hardware validation，按约定本轮延期。

### 风险与决策变化

- raw writer 已验证文件格式和边界，但尚未与真实设备 notification 采集链路连接；raw append 成功后再解码/派生 CSV 的 owner 将在后续 session writer slice 冻结。
- `host_monotonic_nanoseconds` 以 Kotlin `ULong` 保持 wire UInt64 语义；Android `elapsedRealtimeNanos()` 的平台适配留给 BLE 层。
- D-005/D-006 仍开放；本轮没有改变 schema、session JSON 或跨平台兼容范围。
- `.idea/` 为既有用户未跟踪内容，未纳入本轮提交。

### 下一轮

继续 M1 `:data:session` 的 CSV schema/formatter audit，保持 25 列顺序、Locale.ROOT、RFC 4180 转义和 invalid metric 空值语义；随后再做 session metadata 或 signal parity。

## 2026-08-01 · M1 · CSV schema and formatter JVM slice

### 本轮目标

按 Phase 1 `:data:session` 文件路线实现固定 CSV schema、formatter 和 parser/audit，保持 Swift 当前 25 列顺序、数字/时间格式、RFC 4180 字段转义以及 invalid metric 语义。

### 需求/参考/Android 目标

- Requirement: CAP-004；路线 §4.2；架构文档 §6.3。
- Primary source: `reference_sources/ios_current/PPGCollector/Domain/Models/CaptureCSVSchema.swift` 与 `Infrastructure/Storage/CaptureSessionWriter.swift:333-435`；交叉核对 `CaptureSessionWriterTests.swift`。
- Tests/golden: Swift writer 的首行 `0.000000`、第 800 个样本 `7.990000`、25 列 header 和 metric invalid 规则；本轮使用 JVM JUnit parity assertions。
- Android target: `app/src/main/java/com/example/ppgcollector_android/data/session/CaptureCsv.kt` 与 `app/src/test/java/com/example/ppgcollector_android/data/session/CaptureCsvTest.kt`。
- Non-goals: session JSON、raw-first actor、CSV 文件 checkpoint/atomic rename、signal metrics、BLE/FGS、真机测试。

### 实现事实

- `CaptureCsvSchema` 固化 25 列顺序和 `ppgcollector_samples_v1` 所需的 100 Hz device-time 规则。
- `CaptureCsvFormatter` 用 `Locale.ROOT` 固定 6 位小数；metric source time 按 `sourceSampleIndex - firstStreamSampleIndex` 计算；HR/SpO2/R 无效值为空，SQI 无效值为 `0`，所有无效值 valid=false/time empty。
- formatter 实现 RFC 4180 风格逗号、双引号、CR/LF 转义；parser 支持 quoted field、CRLF、严格列数/布尔/数值检查，并审计 device time 与 metric validity/time 的一致性。
- parser 保持 schema/version/profile 字段为字符串，未把 CSV 中缺失的 source sample index 伪造回填；后续 session writer 负责传入真实来源索引。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test`：通过，`24 tests completed`，`BUILD SUCCESSFUL`。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug`：通过，`BUILD SUCCESSFUL`。
- `git diff --check`：通过（无空白错误）。
- 真机 GATT/后台/厂商矩阵/长录制：pending hardware validation，按约定本轮延期。

### 风险与决策变化

- CSV formatter 已独立于尚未移植的 live metric model；这避免用占位 metric 类型宣称 HR/SQI 算法已完成。
- D-006/D-007 仍开放；本轮未改变 schema version 命名或跨平台兼容决策，只复现当前 Swift schema。
- `.idea/` 为既有用户未跟踪内容，未纳入本轮提交。

### 下一轮

继续 M1 session metadata/inspection slice：先定义 snake_case metadata typed model 与版本/profile 固化，再将 raw/CSV 计数和 safe-prefix findings 接入可审计 session 状态。

## 2026-08-01 · M1 · Session metadata JSON JVM slice

### 本轮目标

按 Phase 1 `:data:session` metadata 路线移植 snake_case session metadata、ISO-8601 日期、版本/profile 固化、complete/stop/writer/files/recovery 字段和兼容 codec；保持未完成能力不被伪装为 inspection/recovery。

### 需求/参考/Android 目标

- Requirement: CAP-005；路线 §4.2、§6.1；架构文档 §6.4、§11。
- Primary source: `reference_sources/ios_current/PPGCollector/Domain/Models/CaptureSessionMetadata.swift`、`CaptureModels.swift` 和 `CaptureSessionWriter.swift` metadata construction。
- Tests/golden: Swift `JSONEncoder`/`JSONDecoder` ISO-8601 + sorted-key contract、writer metadata assertions、recovery metadata field assertions；本轮用 JVM JSON fixtures/assertions。
- Android target: `app/src/main/java/com/example/ppgcollector_android/data/session/CaptureSessionMetadata.kt` 与 `app/src/test/java/com/example/ppgcollector_android/data/session/CaptureSessionMetadataTest.kt`。
- Non-goals: session writer actor/checkpoint/atomic file write、repository/inspection/recovery/export、BLE/FGS、真机测试。

### 实现事实

- 新增 typed `CaptureSessionMetadata`、device/writer/files/recovery nested models 和 `CaptureStopReason` wire mapping。
- 新增无第三方依赖 JSON AST/parser/writer：UTF-8、ISO-8601 `Instant`、snake_case keys、sorted deterministic output、字符串转义、null、unknown additive fields 和可选旧字段。
- 未知 stop reason 映射为显式 `UNKNOWN`，不静默改成正常停止；缺少 required fields、错误类型、错误日期和 malformed JSON 均抛出 typed exception。
- metadata 仍只表达 schema/data contract；`complete` 不被当作 verified complete，raw/CSV 一致性检查留给后续 inspection。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test`：通过，`24 tests completed`，`BUILD SUCCESSFUL`。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug`：通过，`BUILD SUCCESSFUL`。
- `git diff --check`：通过（无空白错误）。
- 真机 GATT/后台/厂商矩阵/长录制：pending hardware validation，按约定本轮延期。

### 风险与决策变化

- 本轮 codec 采用项目内纯 Kotlin JSON 实现，后续若切换 kotlinx.serialization 必须保留相同 snake_case/ISO-8601/null/未知字段测试；没有改变 schema version。
- D-005/D-006/D-007 仍开放；recovery provenance 类型已建立但 recovery service 尚未实现。
- `.idea/` 为既有用户未跟踪内容，未纳入本轮提交。

### 下一轮

进入 M1 inspection/replay glue：组合 raw scan、CSV streaming audit、metadata cross-check 和“不一致则不 verified complete”报告；随后再决定是否进入 signal fixtures。

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
