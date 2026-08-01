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

## 2026-08-01 · M1 · Bounded raw replay and session inspection JVM slice

### 本轮目标

按 Phase 1 `:data:session` 的 inspection/replay 路线，把 CUPRAW1 safe-prefix scan 接到同一 decoder/sequence pipeline，流式审计 CSV，并只读交叉核对 raw、CSV、metadata；不实现恢复复制、session writer、repository 或 UI。

### 需求/参考/Android 目标

- Requirement: CAP-009；路线 §4.2、§6.5；架构文档 §6.5、§11；风险 R-008/R-013。
- Primary source: `reference_sources/ios_current/PPGCollector/Infrastructure/Storage/CUPRawFileReader.swift`、`CUPRawReplayEngine.swift`、`CaptureSessionInspectionService.swift`、`CaptureSessionRepository.swift`。
- Tests/golden: Swift raw streaming report、safe-prefix tail classification、CSV 64 KiB scan、raw/CSV/metadata count cross-check；Android JVM temp-directory tests使用现有 draft golden frame encoder。
- Android target: `app/src/main/java/com/example/ppgcollector_android/data/session/CupRawReplay.kt`、`CaptureSessionInspection.kt` 及 `CaptureSessionInspectionTest.kt`。
- Non-goals: recovery safe-prefix copy/staging/atomic move、writer checkpoint/fsync actor、repository list/export、signal algorithms、BLE/FGS/UI、真机测试。

### 实现事实

- `CupRawReplayEngine` 通过 `CupRawReader.scan` 流式消费完整 raw records，跨 notification chunk 保持 decoder 状态；sequence first/continuous/gap 进入 accepted stream，duplicate/out-of-order 只计诊断。
- replay report 保留 raw record/payload/valid-byte/tail、decoder/sequence 诊断、host 时间范围和 accepted sample count；recent replay samples 有界为 800，避免按会话时长线性保留内存。
- `CaptureSessionInspectionService` 只读约定目录文件，流式扫描 CSV（64 KiB buffer），分类 missing/unreadable/header/tail/structure/sequence findings，并交叉核对 metadata 的 raw chunk/sample count 与 CSV 完整行数。
- `complete=true` 仍不自动等同 verified；只有无 findings、raw 结构干净、CSV header 正确且无截尾时才报告 `isVerifiedConsistent`。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test`：通过，`31 tests completed`，`BUILD SUCCESSFUL`。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug`：通过，`BUILD SUCCESSFUL`。
- `git diff --check`：待提交前执行。
- 真机 GATT/后台/厂商矩阵/长录制：pending hardware validation，按约定本轮延期。

### 风险与决策变化

- 当前 replay 的 accepted sample recent buffer 已有界，但完整的 signal pipeline、CSV 行语义校验和 recovery copy 仍未实现；不得把 inspection slice 宣称为完整录制能力。
- raw host timestamp 按“完成一帧的 notification chunk”记录，保持 Swift replay 语义；跨真实设备 chunk 边界仍需抓包验证。
- D-001、D-005、D-006、D-007 仍开放；本轮未改变协议/schema/profile 命名。
- `.idea/` 为既有用户未跟踪内容，未纳入本轮提交。

### 下一轮

进入 M1 signal fixtures：先移植 preprocessing/gap reset 的纯 Kotlin 最小切片，再接 HR/SQI；继续保持 draft protocol 和未校准 ratio 语义。

## 2026-08-01 · M1 · Preprocessing parity JVM fixture slice

### 本轮目标

按 SIG-001 和 Phase 1 `:core:signal` 路线移植固定 `ios_baseline_0.1` 的因果预处理最小切片：DC、三段 SOS、IR polarity、窗口 z-score、non-finite/constant reason 与 gap reset；不进入 HR、peak、SQI 或 ratio。

### 需求/参考/Android 目标

- Requirement: SIG-001；路线 §4.3、§4.4；架构文档 §7.1；风险 R-004/R-012。
- Primary source: `reference_sources/ios_current/PPGCollector/SignalProcessing/Preprocessing/PPGPreprocessor.swift` 与 `PPGPreprocessorTests.swift`。
- Tests/golden: `reference_sources/signal_fixtures/preprocessing/preprocessing_vectors.json`；校验 4 个 synthetic cases、SOS 系数 `1e-15`、中间/输出数组 `1e-8`、chunking、gap suffix、invalid/constant reason。
- Android target: `app/src/main/java/com/example/ppgcollector_android/core/signal/PpgPreprocessing.kt`、`app/src/test/java/com/example/ppgcollector_android/core/signal/PpgPreprocessingTest.kt`。
- Non-goals: HR/peak/DFT、SQI/template match、ratio-of-ratios、live scheduler、BLE/FGS/UI、真机测试。

### 实现事实

- 固化 `ios_baseline_0.1` profile 与三段 SciPy SOS，`a0` 归一化并使用与 Swift 相同的 transposed direct-form II delay recurrence。
- `PpgPreprocessor` 保持 DC 与 section delay 的 causal state；gap 在当前样本前 reset，non-finite 输入返回 typed reason 并 reset 后续状态。
- `PpgWindowNormalizer` 明确 polarity transform、population standard deviation、epsilon constant-signal gate 和 empty/non-finite failure reasons；不把 normalized failure 当作有效指标。
- JVM 测试直接读取只读 reference fixture，覆盖 `pulse_down_8s`、`mixed_frequency_6s`、`gap_reset_5s`、`constant_4s`，并验证 chunking 不改变 causal state。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test`：通过，`34 tests completed`，`BUILD SUCCESSFUL`。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug`：通过，`BUILD SUCCESSFUL`。
- `git diff --check`：待提交前执行。
- 真机 GATT/后台/厂商矩阵/长录制：pending hardware validation，按约定本轮延期。

### 风险与决策变化

- fixture 是 synthetic numerical parity，不是生理有效性或真实设备验证；profile 仍为 iOS baseline draft，未改 `preprocess_profile` 命名。
- 当前 fixture 测试从仓库 reference path 读取，若未来构建环境改变目录布局，应迁移为受控 test resource，同时保持 fixture hash/内容审计。
- D-001、D-005、D-006、D-007 仍开放；HR/SQI/R 仍未实现，不能把 preprocessing 输出宣称为 HR/SpO2/BP。
- `.idea/` 为既有用户未跟踪内容，未纳入本轮提交。

### 下一轮

进入 M1 HR/peak 最小 fixture slice：先移植 peak detector/HR 输入输出契约，保持双极性、频带和 confidence/reason 语义；SQI 与 ratio 另行切片。

## 2026-08-02 · M1 · SciPy-compatible peak detector slice

### 本轮目标

按 SIG-004 和 Phase 1 `:core:signal` 路线独立移植 HR/SQI 共用的 peak primitive；保持 SciPy 1.17.1 的 plateau midpoint、distance pruning、prominence base 和 half-prominence width 语义，不进入 HR estimator、DFT 或 SQI。

### 需求/参考/Android 目标

- Requirement: SIG-004；路线 §4.3、§4.4；架构文档 §7.1；风险 R-004。
- Primary source: `reference_sources/ios_current/PPGCollector/SignalProcessing/Shared/SciPyPeakDetector.swift`；HR 使用契约来自 `HeartRateEstimator.swift`。
- Tests/golden: Swift detector implementation and HR/SQI peak trace semantics；JVM synthetic vectors cover plateau, higher/tie distance pruning, prominence bases, half-prominence linear width, height/prominence/width filters and short input.
- Android target: `app/src/main/java/com/example/ppgcollector_android/core/signal/SciPyPeakDetector.kt`、`app/src/test/java/com/example/ppgcollector_android/core/signal/SciPyPeakDetectorTest.kt`。
- Non-goals: HR spectral estimator, DFT/Hann, RR/confidence, SQI/template match, ratio-of-ratios, live scheduler, BLE/FGS/UI、真机测试。

### 实现事实

- 新增 `SciPyPeak`/`SciPyPeakDetectionResult`，保留 index、height、prominence、base indices 和可选 width trace 字段。
- 候选峰严格按局部极大值与 plateau midpoint 生成；distance priority 按高度排序、等高按候选顺序处理，保留输入索引顺序输出。
- prominence 从峰向两侧扫描至更高值/边界；width 使用 half-prominence height 的线性交点，并在 width filter 未启用时保持 Swift 的 null trace 字段。
- 该 primitive 不做非协议性平滑、插值或“补峰”；后续 HR 只应复用此实现，不能另写近似 detector。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test`：通过，`38 tests completed`，`BUILD SUCCESSFUL`。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug`：通过，`BUILD SUCCESSFUL`。
- `git diff --check`：待提交前执行。
- 真机 GATT/后台/厂商矩阵/长录制：pending hardware validation，按约定本轮延期。

### 风险与决策变化

- 本轮仅证明 peak primitive 的合成边界语义；未证明 HR fixture 最终 BPM、spectral trace、RR 或 confidence parity。
- SIG-001 profile、协议 profile 和 version 命名未改变；D-001、D-005、D-006、D-007 仍开放。
- `00_AGENT_MIGRATION_BRIEF.md` 在恢复时已有用户修改，本轮未纳入提交；`.idea/` 也未跟踪。

### 下一轮

进入 M1 HR estimator fixture slice：先复用 preprocessing/peak primitive 实现 robust scale、linear detrend、Hann/DFT spectral trace、双极性 RR 和 confidence/reason，再用 `heart_rate_vectors.json` 验证。

## 2026-08-02 · M1 · Heart-rate estimator parity JVM fixture slice

### 本轮目标

按 SIG-003 和 Phase 1 `:core:signal` 路线移植 HR estimator：latest 8 s window、edge trim、robust scale、linear detrend/Hann exact DFT、35–200 BPM cardiac band、双极性 peak candidates、spectral interval filtering、RR regularity 和 confidence gate；复用 SIG-004 peak primitive，不进入 SQI/ratio/UI。

### 需求/参考/Android 目标

- Requirement: SIG-003；路线 §4.3、§4.4；架构文档 §7.1；风险 R-004/R-012。
- Primary source: `reference_sources/ios_current/PPGCollector/SignalProcessing/HeartRate/HeartRateEstimator.swift`、`SciPyPeakDetector.swift` 与对应 Swift tests。
- Tests/golden: `reference_sources/signal_fixtures/heart_rate/heart_rate_vectors.json`；校验 6 cases 的 final BPM/peak BPM/spectral BPM/SNR/polarity/indices/RR/reason，以及 work/spectral/candidate trace。
- Android target: `app/src/main/java/com/example/ppgcollector_android/core/signal/HeartRateEstimator.kt`、`app/src/test/java/com/example/ppgcollector_android/core/signal/HeartRateEstimatorTest.kt`。
- Non-goals: SQI/template match、ratio-of-ratios、live scheduler、MetricResult/source time integration、BLE/FGS/UI、真机测试。

### 实现事实

- 固化 `ppg-ios-hr-0.1` configuration 与 4–8 s window semantics；长输入只取最新 800 samples，并保留 global window offset/peak indices。
- 实现与 Swift 一致的 population robust scale、linear detrend、periodic Hann、exact one-sided DFT density scaling、cardiac bin/tie selection、near-peak SNR/concentration。
- 双极性候选均复用 `SciPyPeakDetector`；保留 in-range/spectral masks、MAD 清理、longest valid RR run、peak BPM、regularity/coverage/agreement score 和 low-confidence reason。
- `acceptedBpm` 仅复现 live display 的 peak/spectral 一致性辅助 gate；未把 HR 结果扩展为 SpO2、BP 或临床结论。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test`：通过，`42 tests completed`，`BUILD SUCCESSFUL`；HR fixture test 4 tests 覆盖 6 cases。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug`：通过，`BUILD SUCCESSFUL`。
- `git diff --check`：待提交前执行。
- 真机 GATT/后台/厂商矩阵/长录制：pending hardware validation，按约定本轮延期。

### 风险与决策变化

- fixture 是 synthetic numerical parity；HR 仍是算法 parity/provisional core，不是生理有效性或真实设备验证。
- 当前 test fixture 从仓库 reference path 读取；未来 CI 若改变目录布局，应迁移到受控 test resource 并保持内容/hash 审计。
- D-001、D-005、D-006、D-007 仍开放；SQI、ratio、MetricResult validity/source time 仍未实现。
- `00_AGENT_MIGRATION_BRIEF.md` 的既有用户修改和 `.idea/` 均未纳入本轮提交。

### 下一轮

进入 M1 SQI fixture slice：移植周期 peak/template/Pearson/grade 与 provisional validity，复用同一 peak detector；ratio-of-ratios 另行切片。

## 2026-08-02 · M1 · SQI template matching parity JVM fixture slice

### 本轮目标

按 SIG-005 移植周期对齐、mean template、Pearson SQI、0–1 display clamp、Good/Fair/Poor grade 与 provisional validity；复用 SIG-004 peak primitive，不进入 ratio-of-ratios、live scheduler 或 UI。

### 需求/参考/Android 目标

- Requirement: SIG-005；路线 §4.3、§4.4；架构文档 §7.1；风险 R-004/R-012。
- Primary source: `reference_sources/ios_current/PPGCollector/SignalProcessing/SQI/TemplateMatchSQI.swift`、对应 Swift tests。
- Tests/golden: `reference_sources/signal_fixtures/sqi/sqi_vectors.json`；覆盖 8 cases 的 final SQI/reason/grade、primary/fallback peak trace、cycle/template/Pearson/quality trace。
- Android target: `app/src/main/java/com/example/ppgcollector_android/core/signal/TemplateMatchSqi.kt`、`app/src/test/java/com/example/ppgcollector_android/core/signal/Sqi/TemplateMatchSqiTest.kt`。
- Non-goals: ratio-of-ratios、MetricResult/source time、live scheduler、BLE/FGS/UI、真机测试；SQI 不代表临床有效性。

### 实现事实

- 固化 `ppg-ios-sqi-0.1` baseline 配置与 100 Hz 周期窗口；primary/fallback peak pass、最小两峰约束、bankers rounding 的 pre/post 对齐和完整周期过滤与 Swift 对齐。
- 实现 mean template、逐周期 Pearson、quality trace、raw/display clamp、阈值 grade 及 invalid reason；所有可用 SQI 标记为 provisional，不能外推 SpO2/BP。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test`：通过，`45 tests completed`，`BUILD SUCCESSFUL`。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug`：通过，`BUILD SUCCESSFUL`。
- `git diff --check`：待提交前执行。
- 真机 GATT/后台/厂商矩阵/长录制：pending hardware validation，按约定本轮延期。

### 风险与决策变化

- fixture 是 synthetic numerical parity；SQI 仍是 provisional algorithm core，不是生理有效性或真实设备验证。
- D-001、D-005、D-006、D-007 仍开放；ratio-of-ratios、MetricResult validity/source time、BLE/FGS/UI 仍未实现。
- `00_AGENT_MIGRATION_BRIEF.md` 的既有用户修改和 `.idea/` 均未纳入本轮提交。

### 下一轮

进入 M1 ratio-of-ratios / MetricResult validity slice，继续保持 provisional 与 invalid 语义；live scheduler 另行切片。

## 2026-08-02 · M1 · Ratio-of-ratios diagnostic parity JVM slice

### 本轮目标

按 SIG-006 移植 RED/IR ratio-of-ratios 诊断计算：长度与最小样本门槛、边缘 trim、RMS AC/mean DC 与明确 unavailable reason；不把 R 转换为 SpO2/BP，不接 UI/CSV/live scheduler。

### 需求/参考/Android 目标

- Requirement: SIG-006；路线 §4.3、§4.4；架构文档 §7.1；风险 R-004/R-008。
- Primary source: `reference_sources/ios_current/PPGCollector/SignalProcessing/RatioOfRatiosEstimator.swift`、`reference_sources/ios_current/PPGCollectorTests/SignalProcessing/RatioOfRatiosEstimatorTests.swift`。
- Tests/golden: Swift synthetic cases；JVM tests cover valid sinusoidal ratio, 10% trim, input mismatch, short input, non-finite input, insufficient DC/AC and non-finite result.
- Android target: `app/src/main/java/com/example/ppgcollector_android/core/signal/RatioOfRatiosEstimator.kt`、`app/src/test/java/com/example/ppgcollector_android/core/signal/ratio/RatioOfRatiosEstimatorTest.kt`。
- Non-goals: SpO2/BP calibration/model, MetricResult/source time, live scheduler、BLE/FGS/UI、真机测试。

### 实现事实

- 固化 `ppg-ios-rr-0.1`；要求四路输入等长且至少 400 samples，按 `min(count / 10, count / 2 - 1)` 去除两端，再对 bandpassed RMS 与 raw mean DC 计算百分比和 R。
- 保留 `inputLengthMismatch`、`insufficientSamples`、`nonFiniteInput`、`insufficientDC`、`insufficientAC`、`nonFiniteResult`；结果显式 `isProvisional=true`，仅为 diagnostic ratio，禁止冒充 SpO2/BP。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test`：通过，`48 tests completed`，`BUILD SUCCESSFUL`。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug`：通过，`BUILD SUCCESSFUL`。
- `git diff --check`：待提交前执行。
- 真机 GATT/后台/厂商矩阵/长录制：pending hardware validation，按约定本轮延期。

### 风险与决策变化

- 该 estimator 只证明 Swift synthetic semantics；没有校准曲线、参考血氧或真实设备证据，不能生成 SpO2/BP。
- D-001、D-005、D-006、D-007、D-008 仍开放；MetricResult validity/source time、live scheduler、BLE/FGS/UI 仍未实现。
- `00_AGENT_MIGRATION_BRIEF.md` 的既有用户修改和 `.idea/` 均未纳入本轮提交。

### 下一轮

进入 M1 `MetricResult` validity/source-time contract 与 800/100 live window scheduler slice；保持 generation/gap/旧结果丢弃语义。

## 2026-08-02 · M1 · MetricResult and live window scheduler parity JVM slice

### 本轮目标

按 SIG-002 与 live pipeline 契约移植 `MetricResult` 的 value/valid/provisional/reason/version/source time 字段，以及 100 Hz accepted sample 的 800 sample rolling window、首个 end=799、每 +100 cadence、gap generation 和旧 request 丢弃语义。

### 需求/参考/Android 目标

- Requirement: SIG-002；路线 §4.3、§4.4；架构文档 §3.1、§7.2、§10；风险 R-004/R-012。
- Primary source: `reference_sources/ios_current/PPGCollector/Domain/Models/LiveMetricModels.swift`、`reference_sources/ios_current/PPGCollector/SignalProcessing/Runtime/PPGLiveMetricRuntime.swift`、对应 runtime tests。
- Tests/golden: Swift runtime synthetic stream semantics；JVM tests cover 800/100 cadence, 8 s time axis, bounded ring, gap reset/re-warmup, rejected frame, stale generation/request, MetricResult invalid/calibration separation and analyzer source metadata.
- Android target: `app/src/main/java/com/example/ppgcollector_android/core/signal/LiveMetricModels.kt`、`LiveMetricRuntime.kt`、`app/src/main/java/com/example/ppgcollector_android/core/protocol/CupDecodedFrameEvent.kt` and live tests.
- Non-goals: Android coroutine/FGS/BLE ownership, async cancellation runner, Compose/UI, raw-first writer、真机测试；SpO2/BP remains unavailable.

### 实现事实

- 新增 typed `MetricResult<T>`、`LiveMetricSnapshot` 与 unavailable/warming-up/runtime constructors；source sample/time、measuredAt、algorithm version 和 calibration separation 被保留，ratio/SQI 只能 provisional/diagnostic。
- 新增 `CupDecodedFrameEvent` 作为 sequence gate 到 live scheduler 的明确边界；scheduler 只接收 accepted frames，gap 清空两个 causal preprocessor 与 bounded arrays、generation++，首次 800 后每 100 样本发出不可变 request。
- `LiveMetricAnalyzer` 复用既有 HR/SQI/ratio core，将 request window end/source metadata 写入结果；氧饱和度和血压明确 unavailable，不生成伪造数值。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test`：通过，`49 tests completed`，`BUILD SUCCESSFUL`。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug`：通过，`BUILD SUCCESSFUL`。
- `git diff --check`：待提交前执行。
- 真机 GATT/后台/厂商矩阵/长录制：pending hardware validation，按约定本轮延期。

### 风险与决策变化

- 当前证明的是纯 Kotlin scheduler/analyzer 和 stale-request guard；Android coroutine cancellation、FGS ownership、BLE callback/backpressure 尚未接入，不能宣称 live 产品链路完成。
- D-001、D-002、D-003、D-005、D-006、D-007、D-008 仍开放；SpO2/BP 无校准/模型仍 unavailable。
- `00_AGENT_MIGRATION_BRIEF.md` 的既有用户修改和 `.idea/` 均未纳入本轮提交。

### 下一轮

进入 M2 BLE permissions/GATT/fake transport 纯平台切片，或在平台依赖准备后补 async analysis runner；保持 scheduler 的单实例、generation 和 raw-first 边界。

## 2026-08-02 · M2 · BLE profile, freshness, deadline and fake transport core slice

### 本轮目标

按 BLE-001/002/004/006 移植不依赖真机的 BLE 契约：API 31+/≤30 权限分支、CUP-NUS bring-up profile、CUP 名称过滤、连接阶段与 per-stage deadline、旧 generation 防护、2 s stream freshness，以及可注入的 fake transport seam。

### 需求/参考/Android 目标

- Requirements: BLE-001、BLE-002、BLE-004、BLE-006；Phase 2 路线与架构 §2/§3；风险 D-001/R-002/R-003。
- Primary sources: `reference_sources/ios_current/PPGCollector/Domain/Configuration/CUPDeviceProfile.swift`、`BluetoothModels.swift`、`CUPStreamFreshnessTracker.swift`、`BLEConnectionDeadlineTracker.swift`、`BLETransport.swift` 及对应 Bluetooth tests。
- Tests/golden: Swift profile/freshness/deadline semantics；JVM tests cover NUS UUID/prefix/passive flag, API permission branches, inclusive timeout, stale stage/device deadlines, cancellation, connection phase flags and fake command ordering.
- Android targets: `app/src/main/java/com/example/ppgcollector_android/core/ble/BleModels.kt`、`BleFreshness.kt`、`BleConnectionDeadline.kt`、`FakeBleTransport.kt`、`app/src/main/AndroidManifest.xml` and `BleCoreTest.kt`。
- Non-goals: `BluetoothLeScanner`/`BluetoothGatt` callbacks, runtime permission UI, CCCD implementation, FGS/lifecycle, real device validation and raw writer。

### 实现事实

- `CupBleDeviceProfile.cupNusBringUp` 固化当前 NUS service/notify/control UUID、`CUP` 前缀和 passive stream；没有引入 START/STOP control write。
- `BlePermissionPolicy` 与 Manifest 声明对应 API 31+ `BLUETOOTH_SCAN/CONNECT`、API ≤30 `ACCESS_FINE_LOCATION`，并支持是否声明 `neverForLocation` 的策略测试。
- `CupStreamFreshnessTracker` 区分 unavailable/waiting/fresh/stale，最后合法帧在 timeout 边界内保持 fresh；`BleConnectionDeadlineTracker` 以 operation/device/generation 键控，阶段切换、取消和新设备尝试会使旧 timeout 失效。
- `BleTransport`/`FakeBleTransport` 保留 ordered event sink 与 scan/connect/discovery/notification 命令边界，供后续 Android `BluetoothGatt` owner 和 fake GATT state machine 注入；当前 fake 不模拟真实系统 callback。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test`：通过，`57 tests completed`，`BUILD SUCCESSFUL`。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug`：通过，`BUILD SUCCESSFUL`。
- `git diff --check`：通过。
- 真机 GATT/扫描/权限弹窗/后台/厂商矩阵/长录制：pending hardware validation，按约定本轮延期。

### 风险与决策变化

- NUS UUID、采样协议和 passive 行为仍是 bring-up draft；没有真实固件抓包，不能宣称生产协议或真实 receiving 成功。
- 当前只完成 pure core/fake seam；Android callback 线程复制、monotonic raw chunk、CCCD 成功回调、旧 callback 丢弃和 backpressure 仍待 GATT owner 切片。
- D-001、D-002、D-003、D-005、D-006、D-007、D-008 仍开放；`00_AGENT_MIGRATION_BRIEF.md` 用户修改和 `.idea/` 未纳入本轮提交。

### 下一轮

继续 M2 Android BLE owner/fake GATT event state machine：扫描 CUP 过滤、connect→service→characteristic→CCCD→receiving、deadline callback 与 generation guard；保持 control passive 和 raw-first 边界。

## 2026-08-02 · M2 · Fake GATT event/state owner parity slice

### 本轮目标

按 BLE-002/003/004/005/006 将 transport event seam 组织成单一有序 GATT owner：扫描 CUP 过滤、connect→service→characteristic→CCCD→subscribed/receiving 阶段、目标 notify 能力检查、deadline polling、旧 generation/错误阶段 callback 丢弃与原始 notification copy；保持 control characteristic passive。

### 需求/参考/Android 目标

- Requirements: BLE-002、BLE-003、BLE-004、BLE-005、BLE-006；Phase 2 路线与架构 §1/§2/§3；风险 R-002/R-003/R-004。
- Primary sources: `reference_sources/ios_current/PPGCollector/Infrastructure/Bluetooth/BLECentralService.swift`、`BLETransport.swift`、对应 `BLECentralServiceIntegrationTests.swift`。
- Tests/golden: JVM fake GATT tests cover happy path, non-CUP filtering, target service/notify/CCCD gates, failure disconnect, copied raw bytes, old callback generation, wrong-phase callback, timeout poll and no control write.
- Android target: `app/src/main/java/com/example/ppgcollector_android/core/ble/BleGattStateMachine.kt`、`app/src/test/java/com/example/ppgcollector_android/core/ble/CupBleGattStateMachineTest.kt`。
- Non-goals: concrete `BluetoothLeScanner`/`BluetoothGatt`, runtime permission UI, coroutine/FGS lifecycle, real GATT/MTU behavior, raw file writer、真机测试。

### 实现事实

- `CupBleGattStateMachine` 在 transport seam 上安装单一 ordered event handler，维护 availability、discovered CUP devices、connection phase、active generation、deadline/freshness 与 diagnostics。
- 只有目标 service、notify/indicate characteristic 和成功 notification callback 才能进入 `Subscribed`；value callback 只接受 notify UUID 且复制 `ByteArray` 后交给 `BleRawNotificationChunk` sink；没有任何 START/STOP/control write。
- callback generation 与 expected phase/device 双重校验；旧 callback 只计入 diagnostics，错误服务/特征/CCCD 会进入 failed 并 disconnect；`markValidFrame` 将 transport notification 与 decoder 合法帧 freshness 分开。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test`：通过，`61 tests completed`，`BUILD SUCCESSFUL`。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug`：通过，`BUILD SUCCESSFUL`。
- `git diff --check`：待提交前执行。
- 真机 GATT/扫描/CCCD/MTU/权限弹窗/后台/厂商矩阵/长录制：pending hardware validation，按约定本轮延期。

### 风险与决策变化

- `BleRawNotificationChunk` 已定义 generation/host monotonic/bytes 边界，但具体 Android callback 仍未接入 `elapsedRealtimeNanos`；不得把 fake event 视为真实接收证据。
- NUS UUID、passive stream 与 408-byte protocol 仍为 bring-up draft；D-001、D-002、D-003、D-005、D-006、D-007、D-008 仍开放。
- `00_AGENT_MIGRATION_BRIEF.md` 的既有用户修改和 `.idea/` 均未纳入本轮提交。

### 下一轮

继续 M2 Android `BluetoothLeScanner`/`BluetoothGatt` adapter 与 runtime permission result seam；将系统 callback 转为上述 event、使用 `elapsedRealtimeNanos`、保持单一 owner、CCCD success gate 和 raw-first 投递。

## 2026-08-02 · M2 · Android scanner/GATT adapter and raw timestamp seam

### 本轮目标

按 BLE-003/005 将纯 Kotlin GATT owner 接到 Android platform adapter：扫描结果进入统一 event、GATT callback 串行化、notification bytes 在 callback 内复制并附带 `elapsedRealtime` 等价 monotonic nanos、service/characteristic discovery、CCCD 写入成功回调和单一 active GATT identity；不在本轮接 Compose/permission UI 或真机。

### 需求/参考/Android 目标

- Requirements: BLE-003、BLE-005；Phase 2 路线与架构 §1/§2；风险 R-002/R-003/R-004。
- Primary sources: `reference_sources/ios_current/PPGCollector/Infrastructure/Bluetooth/BLETransport.swift`、`BLECentralService.swift`；Android platform contract uses `BluetoothLeScanner`/`BluetoothGatt` callback APIs.
- Tests/golden: existing fake GATT path plus event contract; JVM test asserts callback timestamp survives into `BleRawNotificationChunk`; build verifies API 26-compatible adapter and Manifest merge.
- Android target: `app/src/main/java/com/example/ppgcollector_android/core/ble/AndroidBleTransport.kt` and `FakeBleTransport.kt` event timestamp extension.
- Non-goals: runtime permission request UI, Activity/ViewModel/FGS wiring, MTU optimization, real scanner/GATT/CCCD/device validation, raw file persistence。

### 实现事实

- `AndroidBleTransport` uses one main-handler event queue, keeps one active GATT per device generation, filters only at the owner layer while scanner itself remains unfiltered, and converts availability/scan/connect/discovery/CCCD/value callbacks to the pure event contract.
- API 33 descriptor write overload and API <33 compatibility path are both represented; CCCD completion emits `NotificationStateChanged` only after the descriptor callback, and no CUP control characteristic write is issued.
- Notification bytes are copied before posting; `ValueReceived.hostMonotonicNanos` preserves callback-time monotonic timestamp for raw-first consumers. Security/permission failures are surfaced as typed failure/availability events.

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test`：通过，`61 tests completed`，`BUILD SUCCESSFUL`。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug`：通过，`BUILD SUCCESSFUL`；包含 Android adapter 编译和 Manifest merge。
- `git diff --check`：待提交前执行。
- 真机扫描/GATT/CCCD/MTU/权限弹窗/后台/厂商矩阵/长录制：pending hardware validation，按约定本轮延期。

### 风险与决策变化

- 当前 adapter 尚未由 Activity/ViewModel 注入，也未实际调用系统权限请求；编译通过不等于真实 BluetoothGatt receiving 成功。
- `connectGatt`、CCCD 行为、设备 address/name、API 厂商差异仍需硬件矩阵；NUS/passive/408-byte profile 仍是 bring-up draft。
- D-001、D-002、D-003、D-005、D-006、D-007、D-008 仍开放；`00_AGENT_MIGRATION_BRIEF.md` 用户修改和 `.idea/` 未纳入本轮提交。

### 下一轮

接入 runtime permission result seam 与 app-scope BLE coordinator；保持 Activity 不直接持有 GATT、单一 owner、freshness/generation 和 raw-first 事件边界。

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
