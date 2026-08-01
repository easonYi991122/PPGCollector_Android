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
- `git diff --check`：通过。
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

## 2026-08-02 · M2 · Permission result seam and app-scope BLE coordinator

### 本轮目标

按 BLE-001 与架构所有权规则提供可测试的 runtime permission result seam 和 app-scope coordinator：Activity 只请求/回传权限结果，coordinator 统一门控扫描/连接并暴露 immutable snapshot；不在 Activity 构造时弹权限、不让 UI 直接持有 GATT。

### 需求/参考/Android 目标

- Requirements: BLE-001、UI-001/UI-006 的 BLE gate 部分；架构 §1/§2/§8；风险 R-002/R-003。
- Primary sources: `docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md` BLE-001、`docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` ownership rules，以及当前 Swift `BLECentralService` gate/state 语义。
- Tests/golden: JVM tests cover API 33 missing/denied/recovered permissions, API 30 location branch, scan/connect permission gate, powered-on gate, snapshot publication and raw callback seam.
- Android target: `app/src/main/java/com/example/ppgcollector_android/core/ble/BleCoordinator.kt`、`app/src/test/java/com/example/ppgcollector_android/core/ble/BleCoordinatorTest.kt`。
- Non-goals: Activity Result launcher/Compose UI/FGS lifecycle、真实 permission dialog、真机 BLE 验证、raw file writer。

### 实现事实

- `BlePermissionResultSeam` 将 required/granted/missing 与 `UNKNOWN/REQUEST_REQUIRED/GRANTED/DENIED` 明确分开；`permissionsToRequest()` 只返回缺失 Manifest names，`applyResult()` 支持撤销后恢复。
- `BleCoordinator` app-scope 单一持有 `BleTransport` 与 `CupBleGattStateMachine`，对外仅暴露 permission request/result、scan/connect/disconnect、freshness/deadline actions 和 immutable `BleCoordinatorSnapshot`。
- scan/connect 在权限未授权或蓝牙未 powered-on 时不触发 transport；transport event 由 coordinator 转交 owner 并刷新 snapshot，raw chunk 通过注入 sink 继续保持 raw-first 边界。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test`：通过，`64 tests completed`，`BUILD SUCCESSFUL`。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug`：通过，`BUILD SUCCESSFUL`。
- `git diff --check`：待提交前执行。
- 真机权限弹窗/扫描/GATT/后台/厂商矩阵/长录制：pending hardware validation，按约定本轮延期。

### 风险与决策变化

- permission seam 尚未绑定 Activity Result API；当前测试证明的是结果归约和业务 gate，不是系统弹窗行为。
- Coordinator 尚未接入 ViewModel/StateFlow/FGS；真实设备 profile、GATT callback 和 Android lifecycle 仍需平台门禁。
- D-001、D-002、D-003、D-005、D-006、D-007、D-008 仍开放；`00_AGENT_MIGRATION_BRIEF.md` 用户修改和 `.idea/` 未纳入本轮提交。

### 下一轮

接入 Activity Result permission launcher 与 Compose/StateFlow 只读观察，随后把 coordinator 绑定到 app scope；不创建第二 GATT owner，不把 UI 写操作混入 callback。

## 2026-08-02 · M2 · Activity Result permission and lifecycle-aware BLE snapshot wiring

### 本轮目标

按 BLE-001、UI-001 与架构 ownership 规则将 permission seam/coordinator 接到 Android app scope：Application 持有唯一 coordinator，Activity 只注册 Activity Result launcher 并回传结果，Compose 使用 lifecycle-aware StateFlow 观察 immutable snapshot；不接录制 writer/FGS，不做真机。

### 需求/参考/Android 目标

- Requirements: BLE-001、UI-001/UI-006 gate 部分、REL-003 ownership；架构 §1/§2/§8。
- Primary sources: `docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` ownership/UI rules、`BlePermissionResultSeam`/`BleCoordinator` 与 Android Activity Result contract。
- Tests/golden: existing 64 JVM tests retain permission deny/recover and coordinator gates; Android compile/build validates `RequestMultiplePermissions`, lifecycle-aware collection, Application manifest wiring and API 26-compatible packaging。
- Android targets: `app/src/main/java/com/example/ppgcollector_android/PpgCollectorApplication.kt`、`MainActivity.kt`、`core/ble/BleCoordinator.kt`、`gradle/libs.versions.toml`、`app/build.gradle.kts`、`app/src/main/AndroidManifest.xml`。
- Non-goals: real permission dialog/GATT/device validation、recording FGS/raw writer、formal product UI/navigation、SpO2/BP。

### 实现事实

- `PpgCollectorApplication` app-scope lazy owns `BleCoordinator(AndroidBleTransport(...))`; Activity recreation does not create a second GATT owner within the process.
- `BleCoordinator` now exposes `StateFlow<BleCoordinatorSnapshot>`; `MainActivity` uses `RequestMultiplePermissions` and `collectAsStateWithLifecycle`, and scan/connect actions remain coordinator calls.
- Minimal Compose device screen shows permission/availability/phase/freshness, CUP discovery, scan controls and connect buttons; it does not write the control characteristic or perform disk/algorithm work in callbacks.

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test`：通过，`64 tests completed`，`BUILD SUCCESSFUL`。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug`：通过，`BUILD SUCCESSFUL`；包含 Activity Result/lifecycle-compose 依赖、Manifest/Application wiring。
- `git diff --check`：待提交前执行。
- 真机权限弹窗/扫描/GATT/后台/厂商矩阵/长录制：pending hardware validation，按约定本轮延期。

### 风险与决策变化

- 当前 Compose 页面是最小设备/连接状态 seam，不是 V1 正式实时/录制 UI；Activity Result 编译通过不等于用户设备权限行为已验证。
- Application scope 只覆盖当前进程；进程死亡恢复、FGS ownership、raw-first recording 尚未实现。
- D-001、D-002、D-003、D-005、D-006、D-007、D-008 仍开放；`00_AGENT_MIGRATION_BRIEF.md` 用户修改和 `.idea/` 未纳入本轮提交。

### 下一轮

进入 M3 raw-first writer/session start gate：在 accepted raw chunk 前置写入、CSV/metadata validity 追踪和 single finalizer 之前，先保持 coordinator/FGS ownership seam。

## 2026-08-02 · M3 · Raw-first session writer and start gate

### 本轮目标

按 Phase 3 录制可靠性切片，先交付不依赖 Android Service 的可验收 writer core：raw-first、session no-overwrite、incomplete metadata、容量/名称 gate，以及首个 stop reason 的幂等 finalizer。

### 需求/参考/Android 目标

- Requirements: M3 recording/raw-first/session integrity；start preflight、single finalizer、no-overwrite、20 MiB capacity gate。
- Primary source: `reference_sources/ios_current/PPGCollector/Infrastructure/Storage/CaptureSessionWriter.swift`、`Domain/Models/CaptureModels.swift`、`Features/LiveCapture/CaptureSessionController.swift`。
- Android targets: `data/session/CaptureSessionWriter.kt`、`CaptureStartGate.kt` 与 `CaptureSessionWriterTest.kt`。
- Non-goals: BLE coordinator integration、async analysis、FGS、crash recovery/export、real-device test。

### 实现事实

- `CaptureSessionWriter` 创建 `<name>/<name>.cupraw|csv|session.json`，拒绝覆盖，开始前写 `complete=false` metadata；使用现有 CUPRAW1 writer 和 25 列 CSV formatter。
- append 严格先写原始 BLE chunk，再生成 accepted frame/sample CSV；保留 gap/duplicate/out-of-order 诊断计数，空 chunk 与超 64 KiB 受控处理。
- `finish` 首次调用确定 summary/stop reason，后续调用返回同一 summary；正常停止才标记 complete，异常 reason/error 保持 incomplete 语义。`discardIfEmptyBeforeRecording` 支持 preflight 失败的空目录清理。
- `CaptureStartGate` 对 recording、合法名称、freshness、Subscribed/Receiving phase、已有目录和容量进行纯函数 gate；不把 SpO2/BP 或 provisional metrics 宣称为产品结果。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test --no-daemon`：通过，`BUILD SUCCESSFUL`；新增 writer round-trip/no-overwrite/start-gate/idempotent-finish JVM tests。
- `git diff --check`：待提交前执行。
- 真机协议/后台/FGS/锁屏/厂商矩阵/长录制：pending hardware validation，按约定本轮延期。

### 风险与决策变化

- writer 目前为同步 JVM core；BLE raw sink、accepted decoder event、async queue/backpressure 和 FGS ownership 尚未接入，因此不能宣称已交付产品录制。
- 当前 metric 默认走 unavailable/invalid CSV cells；SpO2/BP 仍不可用，ratio-of-ratios 仍是 diagnostic/provisional。
- D-001、D-002、D-003、D-005、D-006、D-007、D-008 仍开放；`00_AGENT_MIGRATION_BRIEF.md` 用户修改和 `.idea/` 未纳入本轮提交。

### 下一轮

把 writer 接到 coordinator 的 accepted stream 与 async analysis/lifecycle seam，再实现 FGS ownership；随后补 crash recovery/export，而不是先扩展正式页面。

## 2026-08-02 · M3 · Accepted stream recording controller

### 本轮目标

在已交付 writer/start gate 之上完成 accepted raw stream 的单一控制器边界：BLE callback 只复制并进入有界 256 队列，worker 负责 decoder/sequence gate 和 writer，所有停止/溢出/写入错误共享 first-reason finalizer。

### 需求/参考/Android 目标

- Requirements: CAP-001/CAP-002/CAP-007、REL-003；Phase 3 §6.1 的 pending channel 256、raw-first、统一停止。
- Primary source: `docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md`、`docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` §1/§3.3、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §6、`CaptureSessionController.swift`、`CUPStreamingPipeline.swift`。
- Android targets: `data/session/CaptureRecordingController.kt`、`CaptureSessionWriter.appendRawThenDerive` 与对应 JVM tests。
- Non-goals: connectedDevice FGS/notification、Activity binding、crash recovery/export、real-device validation、正式 metric UI。

### 实现事实

- `CaptureRecordingController` 以 connection generation 校验并复制 `BleRawNotificationChunk`，使用 256 有界 `ArrayBlockingQueue`；队列满时记录 `queueOverflowCount`，请求 `resourcePressure` stop，不静默丢 raw。
- worker 内维护每会话 `CupBatchStreamDecoder` 与 `CupFrameSequenceTracker`，将 first/continuous/gap 接受、duplicate/out-of-order 拒绝后生成 `CaptureStreamChunkEvent`；writer 是该会话唯一文件写入者。
- `CaptureSessionWriter.appendRawThenDerive` 先完成 CUPRAW1 record，再执行延迟 decoder callback 和 CSV 派生。decoder 异常测试确认已 ack raw 可读，最终会话为 incomplete。
- `stop` 采用 first stop reason wins，先 drain 已入队 chunk 再 finish；重复停止不会创建第二 summary。stale generation、队列溢出、accepted 408-byte frame 与 50 CSV rows 均有 JVM 覆盖。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test --no-daemon`：通过，`BUILD SUCCESSFUL`。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug --no-daemon`：通过，`BUILD SUCCESSFUL`。
- `git diff --check`：待提交前执行。
- 真机 GATT/后台/锁屏/FGS/厂商矩阵/长录制：pending hardware validation，按约定本轮延期。

### 风险与决策变化

- controller 是可被 FGS 持有的纯 JVM lifecycle seam，但目前尚未声明 Android Service、notification channel 或 process-recovery ownership；不能宣称已交付后台录制。
- LiveMetricWindowScheduler 尚未接入该 worker，CSV metric cells 默认按当前不可用/未校准语义写入；SpO2/BP 仍不可用，ratio-of-ratios 仍 diagnostic/provisional。
- D-001、D-002、D-003、D-005、D-006、D-007、D-008 仍开放；`00_AGENT_MIGRATION_BRIEF.md` 用户修改和 `.idea/` 未纳入本轮提交。

### 下一轮

以 controller 作为唯一 session owner 接入 `connectedDevice` FGS、notification start/stop action 和 Activity bind/rebind；随后再接 async analysis snapshot 与 incomplete discovery/recovery。

## 2026-08-02 · M3 · connectedDevice foreground-service ownership seam

### 本轮目标

依据 Phase 3 §6.2 和 REL-003/REL-007，将 recording controller 放入 Android `connectedDevice` 前台服务边界：服务单一持有 controller，通知提供停止入口，Activity 可通过 local binder 重绑观察；不创建第二个 GATT owner。

### 需求/参考/Android 目标

- Requirements: CAP-007、REL-003、REL-004、REL-007；`connectedDevice` FGS、`START_NOT_STICKY`、可见通知、stop action、bind/rebind seam。
- Primary source: `docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` §1/§9、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §6.2、`docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md` REL-003/REL-007、`CaptureSessionController.swift` ownership semantics。
- Android targets: `CaptureForegroundService.kt`、`BleCoordinatorSnapshot.connectionGeneration`、`AndroidManifest.xml`。
- Non-goals: real Activity start flow/formal capture page、API 34/36 runtime test、process-death recovery/export、real-device/background endurance。

### 实现事实

- Manifest 声明 `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CONNECTED_DEVICE`、`POST_NOTIFICATIONS`，service 使用 `android:foregroundServiceType="connectedDevice"` 且 `exported=false`。
- `CaptureForegroundService` 返回 `START_NOT_STICKY`，创建低打扰 notification channel，启动时调用 connected-device foreground type，通知 action 触发统一 `USER` stop；local binder 暴露 snapshot/stop，支持 Activity 后续 bind/rebind。
- 服务复用 `PpgCollectorApplication.bleCoordinator`，通过 snapshot 的 connection generation、phase、freshness 做 start gate，并把 raw callback sink 交给服务持有的 `CaptureRecordingController`；没有新建第二个 GATT owner。
- FGS start 失败、gate 失败、service destroy 都清理 foreground 状态；真正系统可见启动限制、通知授权、锁屏/任务移除/进程死亡仍标 pending hardware/system validation。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test --no-daemon`：通过，`BUILD SUCCESSFUL`。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug --no-daemon`：通过，`BUILD SUCCESSFUL`；Manifest merge、service type、FGS permissions 和 APK packaging 通过。
- `git diff --check`：待提交前执行。
- API 34/36 FGS launch、通知拒绝、后台/锁屏/进程重建、真机 GATT：pending hardware/system validation，按约定本轮延期。

### 风险与决策变化

- 当前服务已形成 ownership seam，但 app-scope `BleCoordinator` 仍是实际 GATT owner；完整“开始录制时原子移交 GATT 到 service”需要后续 service-aware coordinator 重构，不能把当前代码描述为最终后台架构。
- service start intent 只提供最小 session name/device 参数，正式 Capture UI、POST_NOTIFICATIONS 结果 seam、elapsed/written status notification 和 recovery discovery 尚未完成。
- D-001、D-002、D-003、D-005、D-006、D-007、D-008 仍开放；`00_AGENT_MIGRATION_BRIEF.md` 用户修改和 `.idea/` 未纳入本轮提交。

### 下一轮

补 service lifecycle fake/instrumented contract 与 Activity Result notification/start flow，再接 LiveMetricWindowScheduler 异步分析快照；随后实现 incomplete session discovery/recovery/export。

## 2026-08-02 · M3 · Filesystem session catalog and atomic checkpoints

### 本轮目标

按 CAP-005/CAP-006/CAP-008/CAP-009 与 Phase 3 recovery 入口，补足文件系统权威 catalog：启动/刷新时识别 complete、incomplete、缺文件和 metadata 损坏会话；同时让 writer 的 raw/CSV/metadata checkpoint 更接近崩溃可审计语义。

### 需求/参考/Android 目标

- Requirements: UI-009、CAP-006、CAP-008、CAP-009、REL-004；目录扫描不依赖数据库索引，inspection 不修改源，incomplete 可发现。
- Primary source: `docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md`、`docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` §7/§8、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §6.1/§6.3/§6.4、`CaptureSessionRepository.swift`、`CaptureSessionInspectionService.swift`、`CaptureSessionRecoveryService.swift`。
- Android targets: `data/session/CaptureSessionRepository.kt`、`CaptureSessionWriter.kt`、`PpgCollectorApplication.kt` 与 JVM tests。
- Non-goals: copying safe prefixes into a new recovery directory、hash/provenance export、SAF/FileProvider UI、real process-death/system test。

### 实现事实

- `CaptureSessionRepository` 直接扫描 sessions root，读取 snake_case metadata、检查三个预期文件、累计文件大小/修改时间，并按最新修改时间排序；`incompleteSessions` 将 incomplete、metadata unreadable、缺文件统一暴露为 recovery candidates。
- `PpgCollectorApplication.incompleteSessions()` 提供 app-level startup/recovery discovery seam；`CaptureSessionRepository.inspect` 复用现有只读 streaming raw replay/CSV/metadata cross-check。
- writer 每次 raw append 后调用 `CupRawWriter.flush()`；CSV append 使用 `FileChannel.force(true)`；metadata 使用同目录 temp 文件、`force(true)` 和 `ATOMIC_MOVE`（不支持时 fallback replace），并清理 temp 文件。
- JVM tests 覆盖 complete/incomplete/malformed catalog、candidate discovery、无临时文件、source hash 不变、已有 safe-prefix inspection。没有修改 reference snapshot。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test --no-daemon`：通过，`BUILD SUCCESSFUL`。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug --no-daemon`：通过，`BUILD SUCCESSFUL`。
- `git diff --check`：待提交前执行。
- 真机崩溃/满盘/锁屏/FGS/进程恢复、API/厂商矩阵：pending hardware/system validation，按约定本轮延期。

### 风险与决策变化

- atomic checkpoint 已实现，但尚未做真正 SIGKILL/文件系统故障注入；`complete=true` 仍不能替代 inspection，recovery 仍必须复制安全前缀并记录 provenance/hash。
- repository 当前是同步纯文件 catalog，尚未接正式 Compose Sessions 页面或 service 启动时的用户可见 recovery prompt。
- D-001、D-002、D-003、D-005、D-006、D-007、D-008 仍开放；`00_AGENT_MIGRATION_BRIEF.md` 用户修改和 `.idea/` 未纳入本轮提交。

### 下一轮

实现新目录 recovery service：流式复制完整 raw/CSV safe prefix、计算源 hash、写 recovery metadata provenance，并用 no-overwrite/staging/atomic move JVM tests 验收；之后接 SAF export。

## 2026-08-02 · M3 · Streaming safe-prefix recovery and provenance

### 本轮目标

按 CAP-008/CAP-009 和 Phase 3 §6.3，实现不修改源会话的 recovery copy：扫描 raw/CSV safe prefix，流式复制到 staging，记录源文件 SHA-256 与 provenance，写新 session metadata，再 no-overwrite/atomic move 到新目录。

### 需求/参考/Android 目标

- Requirements: CAP-008、CAP-009、REL-004；raw 截尾只保留最后完整 record，CSV 截尾不复制，源目录只读，恢复副本 session ID 新建且 CSV 保留源 session ID。
- Primary source: `docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` §6.5、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §6.3/§6.4、`CaptureSessionRecoveryService.swift`、`CaptureSessionInspectionService.swift`。
- Android targets: `data/session/CaptureSessionRecoveryService.kt` 与 `CaptureSessionRecoveryServiceTest.kt`。
- Non-goals: SAF/FileProvider export、用户恢复页面、SIGKILL/真实文件系统故障注入、真机验证。

### 实现事实

- `CaptureSessionRecoveryService.assess` 检查源 raw/CSV 存在性、CUPRAW safe scan、CSV header/tail、metadata count；缺文件/header 错误不会伪造可恢复副本。
- `recover` 通过合法名称和 destination no-overwrite gate，创建 `.recovery-<id>` staging；raw/CSV 使用 64 KiB bounded streaming copy，仅写 `validByteCount`，并分别 force。
- metadata 创建新 session ID、`complete=false`、`crashRecovery`、`copy_safe_prefix_v1`，保留源 CSV session ID，并记录 source directory/session、全源 raw/CSV/metadata SHA-256、总字节/复制字节和 provenance。
- staging 完成后使用 atomic move（不支持时 fallback move）；任意失败清理 staging，源 raw/CSV/metadata 内容保持不变。测试覆盖尾部、hash、source immutability、destination collision、invalid header。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test assembleDebug --no-daemon`：通过，`BUILD SUCCESSFUL`。
- `git diff --check`：待提交前执行。
- 真机/进程崩溃/满盘/SAF provider/锁屏/FGS：pending hardware/system validation，按约定本轮延期。

### 风险与决策变化

- recovery metadata 明确保持 incomplete；恢复副本需要后续 inspection/用户确认后才可考虑 verified，不能把 safe-prefix copy 宣称为数据完整修复。
- 当前仍未实现 SAF 流式导出、FileProvider 临时 staging、导出取消/进度和正式 Sessions UI；SpO2/BP 与 ratio-of-ratios 语义不变。
- D-001、D-002、D-003、D-005、D-006、D-007、D-008 仍开放；`00_AGENT_MIGRATION_BRIEF.md` 用户修改和 `.idea/` 未纳入本轮提交。

### 下一轮

实现 SAF/export boundary：流式复制会话三文件、进度/取消、临时分享 staging 与不暴露内部路径；随后接正式 Sessions/Recovery Compose 页面。

## 2026-08-02 · M3 · Streaming SAF/FileProvider export boundary

### 本轮目标

按 CAP-010 与 Phase 3 §6.3，实现内部会话到外部分享/SAF 的安全导出边界：三文件 zip 流式复制、进度/取消、目标不覆盖、相对 entry name；FileProvider 只开放 cache staging，不暴露 `filesDir`。

### 需求/参考/Android 目标

- Requirements: CAP-010、REL-005；内部存储是真源，SAF 用于用户导出，FileProvider 只分享临时 staging，不能把内部绝对路径交给外部。
- Primary source: `docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md` CAP-010、`docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` §8/§6.5、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §6.3、Swift `ShareLink`/session repository shareable file semantics。
- Android targets: `data/session/CaptureSessionExportService.kt`、`CaptureAndroidExport.kt`、`res/xml/capture_file_paths.xml`、`AndroidManifest.xml`。
- Non-goals: formal Compose export UI/Activity Result launcher, external DocumentsProvider instrumentation, encryption/retention policy, real-device validation。

### 实现事实

- core exporter 只导出 expected raw/CSV/session JSON 三文件，entry name 是受控相对 basename；使用 64 KiB buffer、`ZipOutputStream`、progress callback 和 cancellation callback。
- Path export 先写同目录隐藏 temp zip，再 no-overwrite/atomic move；取消或失败清理 temp，既有目标不覆盖。SAF adapter 把同一流导出到 caller-selected `Uri`，未让外部 provider 成为高频 raw writer。
- FileProvider adapter 将 zip 写入 `cache/capture-export-staging/`，文件名由安全 basename+随机 ID 构成；`capture_file_paths.xml` 只允许该 cache 子目录，manifest provider `exported=false`/grant URI permissions。
- JVM tests 验证三 entry、无绝对/路径穿越名称、进度终点、取消清理、destination collision；debug packaging 验证 SAF/FileProvider resources、provider manifest 和 FGS 共存。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test --no-daemon`：通过，`BUILD SUCCESSFUL`。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug --no-daemon`：通过，`BUILD SUCCESSFUL`。
- `git diff --check`：待提交前执行。
- SAF provider 实际写入/取消、FileProvider 外部 app 读取、后台/锁屏/真机：pending instrumentation/system/hardware validation，按约定本轮延期。

### 风险与决策变化

- 当前是可被 Activity Result/Compose 调用的 export seam，不是正式用户导出页面；外部 Uri provider 的错误、权限撤销、用户取消仍需 Android instrumentation 覆盖。
- zip 导出保留 incomplete/recovery metadata 原语义，不把导出视为 verified 或修复；不打印 raw、完整设备地址或内部路径。
- D-001、D-002、D-003、D-005、D-006、D-007、D-008 仍开放；`00_AGENT_MIGRATION_BRIEF.md` 用户修改和 `.idea/` 未纳入本轮提交。

### 下一轮

进入 M4 前的连接切片：将 `LiveMetricWindowScheduler` 作为 bounded worker 的异步分析 seam，输出 StateFlow 可观察 snapshot，再接正式 Capture/Sessions Compose 页面和 Activity Result export flow。

## 2026-08-02 · M3/M4 boundary · Bounded asynchronous live-analysis seam

### 本轮目标

将既有 `LiveMetricWindowScheduler`/`LiveMetricAnalyzer` 接入 recording worker 的 accepted stream：分析在独立有界队列/线程运行，raw/CSV writer 不等待 DFT/指标计算；通过 StateFlow 和 FGS binder 暴露可观察分析结果。

### 需求/参考/Android 目标

- Requirements: UI-005/UI-007、CAP-001、REL-003；800 samples/8 s、100-sample cadence、指标 source index/time、分析不阻塞 raw、生命周期可观察。
- Primary source: `docs/01_ANDROID_MIGRATION_MASTER_PLAN.md` §3/§8、`docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` §7.2/§10、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` Phase 4/long-stability guidance、`LiveMetricWindowScheduler`/`LiveMetricAnalyzer` Swift/Python parity references。
- Android targets: `data/session/CaptureRecordingController.kt`、`CaptureForegroundService.kt` 与 `CaptureRecordingControllerTest.kt`。
- Non-goals: metrics 回填既有 CSV rows、正式 Compose waveform/metrics/capture/history pages、real lifecycle/device test、SpO2/BP calibration。

### 实现事实

- controller 新增独立 bounded analysis queue（与 raw queue 同为 256 上限）和 analysis worker；raw writer worker 在 raw ack/CSV 派生后提交 accepted frame event，分析失败/分析队列溢出只更新 diagnostic snapshot，不丢 raw。
- analysis worker 每会话重置 `LiveMetricWindowScheduler`，保持 fixed 800/100 generation/cadence 语义，调用既有 `LiveMetricAnalyzer`；结果包含原 request 的 source sample/time、metric validity/reason/version/provisional 信息。
- `CaptureAnalysisSnapshot` 通过 `StateFlow` 暴露 `WARMING/ANALYZING/READY/FAILED/STOPPED`、generation、processed sample count 和 last result；FGS local binder 同时提供 snapshot 与 StateFlow，供后续 lifecycle-aware Activity/ViewModel 观察。
- 16 个 50-sample frame 的 JVM 集成测试证明首个分析 window end index 为 799、window 长度 800，且 raw writer/controller finalization 正常完成。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test --no-daemon`：通过，`BUILD SUCCESSFUL`。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug --no-daemon`：通过，`BUILD SUCCESSFUL`。
- `git diff --check`：待提交前执行。
- 真机/后台/Activity 重建/FGS bind-rebind、长稳和指标 UI：pending instrumentation/system/hardware validation，按约定本轮延期。

### 风险与决策变化

- 当前 live metrics 仍未写回已生成 CSV row；需要后续明确 snapshot timing/metric source 与 writer acknowledgement 的 policy，不能用异步结果覆盖历史行或伪装同步有效值。
- SQI 仍 provisional，ratio-of-ratios 仍 diagnostic，SpO2/BP 继续 unavailable；分析 StateFlow 不是产品临床结果。
- D-001、D-002、D-003、D-005、D-006、D-007、D-008 仍开放；`00_AGENT_MIGRATION_BRIEF.md` 用户修改和 `.idea/` 未纳入本轮提交。

### 下一轮

进入 M4 UI/lifecycle slice：建立 Activity/ViewModel 对 FGS binder StateFlow 的 bind/rebind 观察、录制 gate/停止状态和 Sessions catalog；保持 writer/analysis ownership 在 service/controller 内。

## 2026-08-02 · M4 · Lifecycle-aware capture service observation seam

### 本轮目标

建立 Activity/ViewModel 对 `connectedDevice` FGS 的可重复 bind/unbind 观察链路：Activity 生命周期只管理订阅，service/controller 继续是 recording、raw、CSV 和 analysis 的唯一所有者。

### 需求/参考/Android 目标

- Requirements: UI-005/UI-007、CAP-001、REL-003/004；旋转/Activity 重建不创建第二 writer，停止入口继续调用 service finalizer，录制/分析状态以不可变 `StateFlow` 观察。
- Primary source: `docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md` UI-005/UI-007、`docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` §1/§8/§9、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` Phase 4 §7.1/§7.2、`docs/01_ANDROID_MIGRATION_MASTER_PLAN.md` §8。
- Android targets: `CaptureServiceViewModel.kt`、`MainActivity.kt`、`CaptureForegroundService.kt`、`CaptureRecordingController.kt`、`CaptureRecordingControllerTest.kt`、lifecycle-viewmodel dependency。
- Non-goals: 完整 capture/name gate 页面、Canvas waveform/metrics、Sessions/detail/replay UI、SAF Activity Result、真实 Activity 重建/FGS system instrumentation、real-device validation、metrics CSV backfill。

### 实现事实

- `CaptureRecordingController` 新增只读 `snapshotFlow`；finalizer 发布 `FINALIZED/FAILED` 快照，和既有 analysis `StateFlow` 一起形成 service 观察契约。
- `CaptureForegroundService.LocalBinder` 暴露 recording/analysis snapshot 与 flow；没有把 writer、GATT 或文件 I/O 移到 Activity。
- `CaptureServiceClient` 使用 `ServiceConnection` 做绑定、断连/空 binder/绑定失败状态分类；`CaptureViewModel` 持有 `viewModelScope`，`MainActivity.onStart/onStop` 对应 bind/unbind，Activity 重建时可重新建立观察而不创建第二 recording controller。
- 最小 Compose surface 只展示 binding、recording、analysis 状态并提供 service stop action；不把 provisional SQI、diagnostic ratio 或 unavailable SpO2/BP 渲染成临床结果。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test assembleDebug --no-daemon`：通过，`BUILD SUCCESSFUL`。
- JVM recording integration assertion：`snapshotFlow.value.state == FINALIZED`；既有协议/raw/CSV/session/signal/recovery/export/800-sample analysis tests 继续通过。
- `git diff --check`：通过。
- 真实 Activity 重建、系统 bind/rebind、后台/锁屏、FGS stop、SAF/provider、真机和长稳：pending instrumentation/system/hardware validation，按约定本轮延期。

### 风险与决策变化

- 当前 client 在 Activity stop 时解除观察；service 若由 `startForegroundService` 启动仍独立存活，下一次 Activity start 再绑定。异常 binding 只报告可行动状态，不私自新建 writer/GATT；系统重建行为仍需 instrumentation 证据。
- 正式 capture name/gate/elapsed UI、waveform/metrics presentation、Sessions catalog/detail/export/recovery 页面尚未实现。
- live metrics 仍不回填既有 CSV row；SQI provisional、ratio diagnostic、SpO2/BP unavailable 语义保持不变。`D-001`、`D-002`、`D-003`、`D-005`、`D-006`、`D-007`、`D-008` 仍开放；用户修改 brief 和 `.idea/` 未纳入本轮提交。

### 下一轮

实现 M4 formal capture surface：复用 `CaptureStartGate` 展示名称/连接/fresh/storage 原因、显式 start/stop intent 和 elapsed/status；随后接 waveform/metric presentation，保持 service/controller ownership。

## 2026-08-02 · M4 · Formal capture gate and start/stop surface

### 本轮目标

把既有纯 Kotlin `CaptureStartGate` 接到 Compose/FGS 真实入口：录制名称、连接/fresh/重名/存储原因必须可见，开始动作只能启动 `connectedDevice` service，停止动作只调用 service finalizer。

### 需求/参考/Android 目标

- Requirements: UI-005、UI-006、UI-008、CAP-001/CAP-007、REL-003/004；合法 ASCII 名称、gate 单独失败可解释、service notification/stop 入口、raw-first ownership 不变。
- Primary source: `docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md` UI-005/UI-006/UI-008、`docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` §6/§9、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` Phase 3 §6.1/§6.2 与 Phase 4 §7.1/§7.2、`05_SOURCE_REFERENCE_INDEX.md` 的 `SessionNameValidator.swift`/`CaptureSessionController.swift`。
- Android targets: `CaptureServiceViewModel.kt`、`MainActivity.kt`、`CaptureForegroundService.kt`、`CaptureSessionWriterTest.kt` 与 `CaptureGateUiStateTest.kt`。
- Non-goals: waveform Canvas、正式 metrics cards、Sessions/detail/replay、SAF Activity Result、真实 FGS/Activity recreation instrumentation、real-device validation、metrics CSV backfill。

### 实现事实

- `CaptureViewModel` 将 session name、app-scope BLE snapshot 和 service recording snapshot 合并为 `CaptureGateUiState`；开始按钮只有 `CaptureStartGate.validate` 返回 null 时启用，并把非法名、stale、device-not-ready、重名、容量不足映射为用户可读原因。
- Compose surface 增加 name field、开始按钮和 service stop button；`startRecording()` 只调用 `ContextCompat.startForegroundService`，Activity 不创建 writer、不接收 raw callback。
- `CaptureForegroundService` 将真实 `FileStore.usableSpace` 传入 controller gate，修复此前 `availableBytes=null` 会跳过 20 MiB 预检的边界；service 仍重复执行 gate，抵御 UI/service 状态竞态。
- 增加 gate 容量不足测试、UI gate reason/canStart 测试；已有 first-stop/raw-first/finalizer 测试继续覆盖数据完整性。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test assembleDebug --no-daemon`：通过，`BUILD SUCCESSFUL`，79 个 JVM tests 全部通过。
- `git diff --check`：通过。
- 真实 FGS notification、Activity 重建、后台/锁屏、重复点击/竞态、SAF/provider、真机与长稳：pending instrumentation/system/hardware validation，按约定本轮延期。

### 风险与决策变化

- `startForegroundService` 后的 service start rejection 尚未通过专门 binder result flow 回传到 UI；正式 instrumentation 需要验证权限、可见启动、重复 start 和 gate 竞态的用户提示。
- 当前仍没有 elapsed duration、Canvas waveform、HR/SQI/ratio presentation；SpO2/BP 不可用语义不改变，live metrics 不回填 CSV。
- `D-001`、`D-002`、`D-003`、`D-005`、`D-006`、`D-007`、`D-008` 仍开放；用户修改 brief 和 `.idea/` 未纳入本轮提交。

### 下一轮

继续 M4 waveform/metrics slice：接 800-sample live analysis result 的明确 valid/provisional/reason/source presentation 与 bounded Canvas 波形快照；随后推进 Sessions catalog/detail。

## 2026-08-02 · M4 · Bounded live waveform and metrics presentation

### 本轮目标

把 recording-owned accepted stream 接到 bounded 800-sample/5 Hz waveform snapshot 与 Compose presentation，并直接展示已有 `MetricResult` 的 valid/provisional/reason/algorithm/source 字段；不修改 raw、CSV 或分析算法语义。

### 需求/参考/Android 目标

- Requirements: UI-002/UI-003/UI-004、SIG-002、REL-003；8 s 双轨 RED/IR、默认 5 Hz、动态独立 Y、min/max bucket、HR/SQI/R 状态、SpO2/BP unavailable。
- Primary source: `docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md` UI-002/UI-003/UI-004、`docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` §7.2/§8/§10、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` Phase 2 §5.1/Phase 4 §7.1/§7.2、Swift `CUPWaveformSnapshot.swift`/`CUPDualWaveformPreview.swift`/`BLECentralService.swift`。
- Android targets: `core/signal/LiveWaveformRuntime.kt`、`CaptureRecordingController.kt`、`CaptureForegroundService.kt`、`CaptureServiceViewModel.kt`、`MainActivity.kt`、waveform/controller JVM tests。
- Non-goals: coordinator-owned non-recording preview continuity, replay viewport, formal Sessions UI, Activity Result export, metrics CSV backfill, calibration/clinical SpO2/BP, real-device/system validation。

### 实现事实

- `LiveWaveformSnapshotScheduler` maintains primitive RED/IR rings capped at 800 samples, generation/source index/time and publication sequence; a 200 ms wall-clock poll publishes at most one snapshot and advances from the due tick, so delayed workers do not burst historical snapshots.
- `LiveWaveformBucketMath` reduces each channel independently to min/max buckets sized by Canvas width; constant/empty inputs have safe handling and no per-sample Composable is created.
- recording analysis worker updates waveform state independently of 1 Hz analysis requests, flushes a final snapshot before analysis worker exit, and exposes it through FGS local binder/`CaptureServiceClient` StateFlow.
- Compose renders separate RED/IR Canvas tracks with independent dynamic Y padding and shows HR/SQI/R from `MetricResult`; invalid values show reason, valid provisional values show temporary state, source index and algorithm version remain visible, and SpO2/BP are explicit unavailable text.

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test assembleDebug --no-daemon`：通过，`BUILD SUCCESSFUL`，81 个 JVM tests 全部通过。
- JVM coverage: immediate/5 Hz/delayed no-burst publication, bounded 800 ring, source indices, min/max bucket extrema, controller final waveform flush and existing 800-sample metric/raw finalization。
- `git diff --check`：通过。
- Canvas screenshot/perf、非录制 preview continuity、Activity recreation/FGS bind-rebind、后台/锁屏、真机和长稳：pending instrumentation/system/hardware validation，按约定本轮延期。

### 风险与决策变化

- 当前 waveform source 是 recording controller 的 accepted stream；停止录制后不破坏已连接预览，但尚未把 coordinator 的非录制 raw stream 接入同一 preview StateFlow，UI-007 的“停止后继续显示”仍需下一轮专门切片。
- live metrics 仍不回填 CSV；SQI provisional、ratio diagnostic、SpO2/BP unavailable 语义保持不变。`D-001`、`D-002`、`D-003`、`D-005`、`D-006`、`D-007`、`D-008` 仍开放；用户修改 brief 和 `.idea/` 未纳入本轮提交。

### 下一轮

实现 coordinator-owned non-recording preview pipeline：BLE callback 继续只复制/投递，app-scope bounded decoder/sequence/waveform StateFlow 在停止录制后仍可供 Activity 观察；随后推进 Sessions catalog/detail。

## 2026-08-02 · M4 · Coordinator-owned non-recording preview continuity

### 本轮目标

让 app-scope `BleCoordinator` 在未录制时也维护 bounded decoder/sequence/waveform/metrics preview；录制 FGS 只作为独立 recording sink，停止录制不关闭 BLE preview 数据链路。

### 需求/参考/Android 目标

- Requirements: UI-002/UI-003/UI-007、SIG-002、REL-003/004；停止 writer 不破坏仍 fresh 的实时预览，BLE callback 不做 I/O/DFT/Compose 更新，generation/gap 重新 warm up。
- Primary source: `docs/01_ANDROID_MIGRATION_MASTER_PLAN.md` §5、`docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md` UI-002/UI-003/UI-007、`docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` §1/§5/§7.2/§8/§10、`docs/05_SOURCE_REFERENCE_INDEX.md` BLECentralService/live scheduler/CUPWaveformSnapshot references。
- Android targets: `core/ble/BlePreviewRuntime.kt`、`BleCoordinator.kt`、`CaptureServiceViewModel.kt`、`MainActivity.kt` 与 preview JVM test。
- Non-goals: raw/session file writes in preview, second GATT owner, formal Sessions/detail/replay UI, CSV metric backfill, real BLE/device/system validation。

### 实现事实

- `BleCoordinator` keeps the single GATT owner callback and fans each already-copied notification into a bounded preview queue plus the optional recording sink; recording sink removal no longer removes preview processing.
- `BlePreviewRuntime` runs on a daemon worker with bounded queue 256, production decoder and sequence tracker, independent 800/5 Hz waveform scheduler and existing 800/100 live metric scheduler; it exposes generation, waveform, last analysis, processed count, drop diagnostic and error through `previewFlow`.
- Coordinator resets preview decoder/rings/results on connection generation changes and transition out of receiving; stale generation chunks cannot populate the new preview.
- Activity chooses recording-owned snapshot while recording/stopping and coordinator preview after stop, so the UI can continue showing fresh data without coupling preview to the writer finalizer.
- JVM test feeds 16 raw frames to the preview worker, verifies 800-sample waveform and first analysis window end 799, then resets generation and verifies old results are cleared.

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test assembleDebug --no-daemon`：通过，`BUILD SUCCESSFUL`，82 个 JVM tests 全部通过。
- `git diff --check`：通过。
- 真 BLE callback latency、Activity stop/rebind、后台/锁屏、preview queue pressure、Canvas screenshot/perf、真机和长稳：pending instrumentation/system/hardware validation，按约定本轮延期。

### 风险与决策变化

- Preview queue overflow is diagnosed and does not use `DROP_OLDEST`; recording raw sink remains independent, but non-recording preview may show a gap and must surface the drop diagnostic rather than fabricate continuity.
- Preview metrics remain provisional/diagnostic according to existing `MetricResult`; SpO2/BP remain unavailable and no values are written to CSV.
- `D-001`、`D-002`、`D-003`、`D-005`、`D-006`、`D-007`、`D-008` 仍开放；用户修改 brief 和 `.idea/` 未纳入本轮提交。

### 下一轮

进入 M4 Sessions slice：从 filesystem catalog 构建 lifecycle-aware Sessions list/detail 状态，展示 complete/incomplete、stop reason、版本和 integrity findings；保持 preview/recording ownership 不变。

## 2026-08-02 · M4 · Filesystem-backed Sessions catalog state

### 本轮目标

将既有 filesystem `CaptureSessionRepository` 接入 lifecycle-aware Sessions ViewModel/Compose 页面：不建立不可恢复的数据库真源，展示 complete/incomplete、stop reason、版本、文件存在性和 integrity findings，并为详情/恢复/导出保留明确入口。

### 需求/参考/Android 目标

- Requirements: UI-009、CAP-009/CAP-010、REL-003/004；目录扫描可从文件系统重建，损坏/截尾会话标记为 recovery candidate，不崩溃、不修改源。
- Primary source: `docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md` UI-009/CAP-009/CAP-010、`docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` §1/§6.5/§8、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` Phase 4 §7.1/§7.2、Swift `CaptureSessionRepository.swift`/`SavedSessionsView.swift`/`SavedSessionDetailView.swift`。
- Android targets: `data/session/CaptureSessionRepository.kt`/inspection models、Sessions ViewModel/Compose screen、repository JVM tests。
- Non-goals: database index, destructive delete, recovery mutation, raw replay/analysis implementation, SAF Activity Result, real filesystem/provider instrumentation, real-device validation。

### 实现事实

- `CaptureSessionRepository.listSessions` remains the only source of catalog truth; ViewModel loads it on `Dispatchers.IO`, exposes immutable `StateFlow`, and refreshes on lifecycle start without touching recording/preview owners.
- Presentation maps metadata `complete`, `stopReason`, version/profile fields, expected-file presence, readable metadata and `isRecoveryCandidate` into actionable Chinese status/findings; malformed entries remain listable as incomplete rather than throwing.
- Compose Sessions surface is read-only: refresh, complete/incomplete badges, stop reason/version/bytes, and detail selection; recovery/export actions are explicit pending seams and do not mutate source files.
- Detail state retains repository inspection result and expected file paths for a later recovery/export flow; no raw bytes or full files are loaded into the UI state.

### 验证

- Command/result: `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test assembleDebug --no-daemon` → `BUILD SUCCESSFUL`；debug assemble 通过。
- JVM coverage: 累计 83 tests；新增 `SessionsPresentationTest` 覆盖不可读取 metadata、缺少预期文件、recovery candidate 和 findings 映射，既有 repository/inspection tests 继续通过。
- `git diff --check`：pass。真实 filesystem 大会话扫描、Activity recreation、recovery/export provider、后台/锁屏和硬件：仍待 instrumentation/system/hardware validation，符合本项目本轮不做真机门禁的策略。
- Real filesystem large-session scan, Activity recreation, recovery/export provider, background/lock-screen and hardware: pending instrumentation/system/hardware validation, per project policy.

### 风险与决策变化

- Catalog is intentionally read-only and filesystem-backed; it must not infer verified completeness from directory names or UI state, and must not silently repair/rewrite metadata.
- Metrics CSV backfill, offline analysis history/versioning and user retention/encryption decisions remain open. `D-001`、`D-002`、`D-003`、`D-005`、`D-006`、`D-007`、`D-008` 仍开放；用户修改 brief 和 `.idea/` 未纳入本轮提交。

### 下一轮

继续接 Sessions recovery/export Activity Result seams，先做只读 inspection findings 与 SAF/FileProvider progress/cancel 状态，再考虑分析历史与重放 UI。

## 2026-08-02 · M4 · Sessions SAF export and safe-prefix recovery actions

### 本轮目标

把已有的流式 ZIP 导出和只读 safe-prefix recovery 内核接入 Sessions 详情页，提供用户选择目标、进度/取消、恢复副本和结果反馈；不修改原会话、不把 raw/CSV 全量载入 UI，也不提前实现重放工作台。

### 需求/参考/Android 目标

- Requirements: UI-009、CAP-010、Phase 4 §7.1 Detail、Phase 3 §6.3、REL-003/004；导出经 SAF，恢复只复制安全前缀到新目录并保留 provenance。
- Primary source: `docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §6.3/§7.1、`docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` §6.5、`CaptureSessionExportService.kt`、`CaptureSessionRecoveryService.kt`、`CaptureAndroidExport.kt`、Swift `SavedSessionDetailView.swift`。
- Android targets: `SessionsViewModel.kt`、`MainActivity.kt` Activity Result `CreateDocument`/Sessions detail action surface；existing export/recovery JVM tests。
- Non-goals: shared-storage writes without user choice, destructive delete, source mutation, FileProvider share action UI, replay viewport/workbench, real provider/instrumentation and real-device validation。

### 实现事实

- `SessionsViewModel` now owns lifecycle-scoped export/recovery action state; export resolves the selected directory from the filesystem catalog again before streaming, so stale UI items cannot export a different source.
- `MainActivity` launches `ActivityResultContracts.CreateDocument("application/zip")`; the SAF adapter streams the three expected session files through the bounded ZIP exporter and reports copied/total bytes. A cancelled picker or running action exposes an explicit cancellation path.
- Recovery invokes `CaptureSessionRecoveryService.suggestedBaseName` and `recover` on `Dispatchers.IO`; the source directory remains read-only, the result is a new filesystem session with recovery provenance, and the catalog re-scans/selects the new copy for inspection.
- Detail copy states show export progress, recovery/export errors and user-facing completion text; action buttons are disabled while busy. No raw bytes or stack traces are placed in Compose state.

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test assembleDebug --no-daemon` → `BUILD SUCCESSFUL`；debug compile includes `CreateDocument` wiring and Sessions action state.
- Existing `CaptureSessionExportServiceTest` and `CaptureSessionRecoveryServiceTest` continue to pass, including relative ZIP entries, cancellation/destination protection, safe-prefix copy and source preservation; full JVM suite passed (83 tests).
- `git diff --check`：pass。真实 SAF provider cancellation/partial-document behavior、FileProvider share、Activity recreation、后台/锁屏和 hardware：pending instrumentation/system/hardware validation；本轮不进行真机测试。

### 风险与决策变化

- SAF cancellation cannot universally delete a provider-owned partial document; the UI stops the stream and reports cancellation, while provider-specific cleanup remains an instrumentation decision. Export destination is always user-selected and no shared-storage path is inferred.
- Recovery remains an explicit copy operation and is disabled only while another detail action is running; replay visualization, action history and metrics CSV backfill remain open. `D-001`、`D-002`、`D-003`、`D-005`、`D-006`、`D-007`、`D-008` 仍开放；用户修改 brief 和 `.idea/` 未纳入本轮提交。

### 下一轮

继续 M4 Detail：接入 bounded raw replay 的最近样本/统计摘要与明确的 replay loading/cancel 状态，随后补 Activity recreation/system lifecycle 和 SAF/FileProvider instrumentation 门禁。

## 2026-08-02 · M4 · Bounded raw replay detail surface

### 本轮目标

将 production `CupRawReplayEngine` 的有限统计和最近样本接入 Sessions 详情：展示流式读取的记录/帧/样本/序号异常/host duration 与峰值 raw buffer，并以双轨 Canvas 显示最多 800 个最近样本；检查过程可取消，避免 UI 阻塞或无界保存 raw。

### 需求/参考/Android 目标

- Requirements: UI-009、CAP-009、Phase 4 §7.1 Detail/§7.2；raw replay 必须流式、bounded、与 inspection 共用 production pipeline。
- Primary source: `docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` §1/§10、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §6.1/§7.1、Swift `SavedSessionDetailView.swift`、`CUPRawReplayEngine.swift`、`CUPReplayWaveformValuesTests.swift`。
- Android targets: `CupRawReplay.kt` existing bounded report、`SessionsViewModel.kt` inspection cancellation、`MainActivity.kt` `ReplaySummary`/Canvas surface。
- Non-goals: editable zoom/pan viewport, offline analysis workbench/history, raw/preprocessed full-file display, provider/hardware/system instrumentation。

### 实现事实

- Detail reuses `CaptureSessionInspection.replay`; it does not reread or copy raw bytes in Compose. The report exposes raw record count, decoded/accepted frames, accepted samples, sequence anomalies, discarded/pending bytes, host duration and peak record buffer.
- `CupRawReplayEngine` already caps retained samples at 800; the UI maps only that bounded list to independent RED/IR min/max bucket Canvas traces and labels the retention bound.
- `SessionsViewModel.cancelInspection` cancels the inspection job and reports a user-visible cancelled state; selecting another session resets stale action results and starts a fresh inspection job.
- Errors remain summarized in UI state without stack traces; source files remain read-only and replay remains diagnostic/raw-derived, not a new metric or CSV source.

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test assembleDebug --no-daemon` → `BUILD SUCCESSFUL`；debug compile includes replay summary, bounded waveform and cancel callback wiring。
- Full JVM suite passed (83 tests), including existing `CaptureSessionInspectionTest`/`CupRawReplay` coverage for streaming tail classification, accepted sequence behavior and bounded recent samples。
- `git diff --check`：pass。Large-session timing/heap, screenshot/accessibility, Activity recreation, background/lock-screen, SAF/FileProvider provider and hardware validation remain pending；本轮不进行真机测试。

### 风险与决策变化

- Replay waveform is a recent-sample diagnostic view, not a claim of full-session visualization; zoom/pan and preprocessed replay require a separately versioned workbench slice.
- `D-001`、`D-002`、`D-003`、`D-005`、`D-006`、`D-007`、`D-008` 仍开放；用户修改 brief 和 `.idea/` 未纳入本轮提交。

### 下一轮

推进 M4 系统生命周期/可访问性与 SAF/FileProvider instrumentation seam，随后再评估 replay viewport 的纯数学 zoom/pan 组件；不提前进入 V1.1 分析工作台。

## 2026-08-02 · M4 · Replay viewport pure math and controls

### 本轮目标

迁移 Swift `CUPWaveformViewport` 的 zoom/pan/clamp 语义，并将其接入 bounded replay 波形：zoom 1–80、锚点保持、水平平移边界、样本数量变化保留 zoom、reset；Canvas 只绘制当前可视切片。

### 需求/参考/Android 目标

- Requirements: Phase 4 §7.1 Detail、§7.2、waveform/viewport pure math JVM gate；不改变 raw replay 的 accepted sample 或时间轴。
- Primary source: `docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §7.1/验证矩阵、`docs/05_SOURCE_REFERENCE_INDEX.md` `CUPWaveformViewport.swift`、Swift `CUPWaveformViewportTests.swift`。
- Android targets: `data/session/ReplayWaveformViewport.kt`、`ReplayWaveformViewportTest.kt`、`MainActivity.kt` replay controls。
- Non-goals: raw file mutation, full-session unbounded rendering, preprocessed replay, offline analysis/workbench, screenshot/performance and real-device/system validation。

### 实现事实

- `ReplayWaveformViewport` is Android/core-independent pure Kotlin with Swift-compatible visible-count/range, anchor-centered zoom, pan/clamp, sample-refresh clamp and reset semantics; zoom is bounded to 1–80 and non-finite input is made deterministic.
- Four JVM tests port the Swift behaviors: center-stable zoom, pan boundary clamps, sample-count shrink without zoom reset, and zoom bounds/reset.
- Replay detail now exposes zoom/缩小/重置 and half-window horizontal pan controls. The selected range is sliced before RED/IR Canvas bucketing, so rendering work remains bounded by the retained replay samples and current viewport.

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test assembleDebug --no-daemon` → `BUILD SUCCESSFUL`；debug compile includes viewport controls。
- Full JVM suite passed (87 tests), including the four new `ReplayWaveformViewportTest` cases and all existing protocol/raw/CSV/session/signal/inspection/export/recovery tests。
- `git diff --check`：pass。Compose screenshot/accessibility, large-session timing/heap, Activity recreation, background/lock-screen, SAF/FileProvider provider and hardware validation remain pending；本轮不进行真机测试。

### 风险与决策变化

- The viewport is intentionally a recent bounded replay view; it must not be described as full-session or preprocessed analysis. Gesture support and richer replay navigation can build on this pure model later without changing data contracts.
- `D-001`、`D-002`、`D-003`、`D-005`、`D-006`、`D-007`、`D-008` 仍开放；用户修改 brief 和 `.idea/` 未纳入本轮提交。

### 下一轮

推进本地 system/accessibility/provider test seam（Activity recreation、screen state、SAF/FileProvider contract），然后再评估 M4 阶段收口与 M5 硬化前置条件。

## 2026-08-02 · M4 · System/accessibility/provider test seam

### 本轮目标

为 M4 详情/实时页面建立可执行的 Android instrumentation 门禁：Activity 重建后关键 Live/Sessions surface 仍可访问，以及 FileProvider 保持 private、仅允许显式 URI grant；本轮只完成测试契约和编译，不把无 emulator 的状态伪装为运行通过。

### 需求/参考/Android 目标

- Requirements: UI-009、CAP-010、REL-003、Phase 4 §7.2；screen state、Activity recreation、TalkBack 可发现文本和 FileProvider share boundary。
- Primary source: `docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md` UI-009/CAP-010/REL-003、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §7.2、`AndroidManifest.xml` provider declaration、`capture_file_paths.xml`。
- Android targets: `MainActivitySystemTest.kt` Compose rule/recreation test、`FileProviderContractTest.kt` provider metadata contract、`app/build.gradle.kts` existing instrumentation dependencies。
- Non-goals: claiming emulator/device execution, BLE/FGS hardware lifecycle, real SAF provider cancellation, screenshot golden/performance, or release accessibility audit。

### 实现事实

- `MainActivitySystemTest` asserts the primary `CUPCollector`, `扫描 CUP` and `已保存会话` surfaces, recreates the Activity through `ActivityScenario`, waits for Compose idle, and asserts the key surfaces remain displayed.
- `FileProviderContractTest` resolves `${applicationId}.fileprovider` from the installed manifest and asserts it is not exported while explicit URI grants remain enabled; this matches cache-only staging in `capture_file_paths.xml`.
- Tests use the already-configured `AndroidJUnit4`, `createAndroidComposeRule`, Compose UI test and instrumentation dependencies; no production raw/session ownership changes were needed.

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test assembleDebug assembleDebugAndroidTest --no-daemon` → `BUILD SUCCESSFUL`；JVM tests and debug/androidTest APK compilation passed (87 JVM tests)。
- Instrumentation test execution on emulator/device、Activity recreation runtime behavior、TalkBack/dynamic font/screenshot checks、SAF/FileProvider provider behavior and hardware lifecycle remain pending by project policy;本轮不进行真机测试。
- `git diff --check`：pass。

### 风险与决策变化

- Compilation proves test/API wiring only; it does not prove provider implementation behavior on every Android API/provider implementation or runtime accessibility. Those remain explicit gates rather than being inferred from source.
- `D-001`、`D-002`、`D-003`、`D-005`、`D-006`、`D-007`、`D-008` 仍开放；用户修改 brief 和 `.idea/` 未纳入本轮提交。

### 下一轮

在有 emulator/设备条件时运行本轮 instrumentation；在本地继续做 M4 acceptance audit（生命周期状态、权限返回、重复 start/stop、SAF cancel）并整理进入 M5 前的未决门禁。

## 2026-08-02 · M4 · Capture stop and FGS acceptance audit seam

### 本轮目标

补齐本地 acceptance audit 中最小的可靠性证据：重复停止必须保持 first stop reason 并只完成一次；Manifest 中 connectedDevice 前台服务必须 private 且声明正确 service type。权限 reducer、gate 和 raw queue 故障已有 JVM 覆盖，本轮不扩展到真机故障注入。

### 需求/参考/Android 目标

- Requirements: REL-003、REL-004、Phase 3 §6.2/§6.4；单一 FGS 录制所有者、幂等 finalizer、first stop reason wins。
- Primary source: `docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` §6.4/§9、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §6.2/§6.4、`CaptureForegroundService.kt`、`CaptureRecordingController.kt`、`AndroidManifest.xml`。
- Android targets: `CaptureRecordingControllerTest.repeatedStopsKeepTheFirstReasonAndFinalizeOnce`、`FileProviderContractTest.captureServiceIsPrivateAndDeclaresConnectedDeviceType`。
- Non-goals: starting FGS in tests, BLE disconnect/timeout hardware injection, emulator/device instrumentation execution, background/lock-screen policy decision。

### 实现事实

- The new JVM regression starts one controller, submits two different stop reasons, waits for finalization, asserts the first reason is persisted, asserts `FINALIZED`, and confirms a later stop cannot transition it again.
- The instrumentation contract resolves the installed capture service and asserts `exported=false` plus `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`; together with the existing FileProvider test this covers the manifest ownership/share boundary at runtime when executed.
- No production code or data contract changed; existing permission/gate, raw queue overflow, stale generation and service binding seams remain the source of behavior.

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test assembleDebug assembleDebugAndroidTest --no-daemon` → `BUILD SUCCESSFUL`；JVM suite、debug APK 和 androidTest APK compile passed (88 JVM tests)。
- `git diff --check`：pass。Instrumentation execution on emulator/device、real FGS start/stop, BLE disconnect/2 s timeout, background/lock-screen and hardware remain pending;本轮不进行真机测试。

### 风险与决策变化

- Manifest/source assertions are contract evidence, not proof of OEM runtime policy or Android API matrix behavior; M5 must still exercise those paths on target API/device combinations.
- `D-001`、`D-002`、`D-003`、`D-005`、`D-006`、`D-007`、`D-008` 仍开放；用户修改 brief 和 `.idea/` 未纳入本轮提交。

### 下一轮

运行 instrumentation 后完成 M4 acceptance audit；若仍无 emulator，则继续补本地 SAF cancel/permission-return state tests，并保持 M5 长稳/厂商门禁未开始。

## 2026-08-02 · M4 · Waveform accessibility semantics

### 本轮目标

补齐 Phase 4 §7.2 的 Canvas 可访问性语义：RED、IR 和 replay 波形不能只依赖视觉绘制，TalkBack/Compose semantics 应能读出通道与当前有界样本数量；不改变绘图数据、采样窗口或指标来源。

### 需求/参考/Android 目标

- Requirements: Phase 4 §7.2 screen/accessibility gate、UI-003/UI-007；动态波形仍使用 bounded Canvas/min-max bucket。
- Primary source: `docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §7.2、`docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` §6.4/§8、`CUPDualWaveformPreview.swift` information architecture。
- Android targets: `MainActivity.kt` `WaveformPanel` semantics、`waveformContentDescription`、`WaveformAccessibilityTest.kt`。
- Non-goals: claiming TalkBack runtime pass, screenshot/performance audit, full-session accessibility, raw/CSV/metric changes or device testing。

### 实现事实

- Every RED/IR/replay Canvas now exposes a semantics content description containing its channel label and the exact bounded sample count, e.g. `RED 波形，800 个样本`.
- The label formatter is pure and covered by JVM tests for live and replay labels; Canvas still receives the same min/max-bucketed values and does not allocate per-sample Composables.
- Existing text-labeled buttons/cards and instrumentation surface tests remain unchanged; runtime TalkBack/dynamic-font behavior is still an explicit device gate.

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test assembleDebug assembleDebugAndroidTest --no-daemon` → `BUILD SUCCESSFUL`；JVM suite、debug APK 和 androidTest APK 编译通过（89 JVM tests）。
- `git diff --check`：pass。Instrumentation execution, TalkBack, dynamic font, screenshot/performance, Activity recreation runtime and hardware remain pending;本轮不进行真机测试。

### 风险与决策变化

- Semantics describe retained samples, not a clinical result or full-session completeness; screen readers must not infer missing samples are absent from the source without inspection findings.
- `D-001`、`D-002`、`D-003`、`D-005`、`D-006`、`D-007`、`D-008` 仍开放；用户修改 brief 和 `.idea/` 未纳入本轮提交。

### 下一轮

运行 instrumentation 后完成 M4 acceptance audit；本地可继续补 SAF cancel/permission-return state coverage，但不提前宣称 M4 runtime 门禁通过。

## 2026-08-02 · M5 preflight · Release artifact smoke and privacy scan

### 本轮目标

完成 Phase 5 §8.3 的最小 release artifact 预检：确认 release 构建可产出，并检查测试/fixture/原始 PPG 资源是否被打入 APK；本轮不把预检结果表述为 M5 发布硬化完成。

### 需求/参考/Android 目标

- Requirement: `REL-005`；Phase 5 §8.3 release hardening/privacy preflight。
- Primary source: `AndroidMigrationPlanning/docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §8.3、`docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md` 的 REL-005，以及 `app/build.gradle.kts` release 配置。
- Tests/golden: release APK ZIP/resource scan；既有 JVM、debug 和 androidTest compile gate。
- Android target: `app/build/outputs/apk/release/app-release-unsigned.apk`。
- Non-goals: 签名/最终包名与分发、R8/resource shrink 开启与 keep 规则、API/厂商矩阵、隐私保留/加密决策、emulator/真机运行。

### 实现事实

- `assembleRelease` 成功，产出 unsigned APK，大小 `8,124,516 bytes`。
- APK ZIP 未发现 `androidTest`、fixture、`heart_rate`、`sqi_vectors`、`golden_seq` 或 raw/CSV/session JSON 测试资源条目；DEX 中仅见通用依赖/debug 符号字符串，未发现应用测试 fixture 数据。
- 当前 release 配置仍为 `optimization.enable = false`，因此本轮是 artifact smoke/privacy scan，不是完整的 R8、资源压缩、签名或发布硬化验收。
- 构建仍报告 `libandroidx.graphics.path.so` 无法 strip（按原样打包）及 Android BLE deprecated API 警告；均记录为后续发布门禁事项。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleRelease --no-daemon` → `BUILD SUCCESSFUL`。
- release APK ZIP/DEX 静态扫描通过；此前 `test assembleDebug assembleDebugAndroidTest` → `BUILD SUCCESSFUL`，89 JVM tests，androidTest 仅完成编译。
- Hardware validation: pending；本轮不进行 emulator/真机测试。

### 风险与决策变化

- `D-004` 签名/包名/分发、`D-005` 数据保留/导出/加密、`D-002` API/厂商矩阵仍开放；还需 privacy log audit、release lint/permissions 和长稳/功耗证据。
- 下一步应在决策明确后启用并验证 R8/resource shrink、签名 artifact 和 keep 规则，再执行 emulator/目标设备矩阵；不因 unsigned APK 构建成功而关闭 M5。

### 下一轮

继续 M5 release hardening 前置：明确签名/包名/隐私策略，评估 R8/resource shrink 与 release lint/权限门禁；有 emulator/设备后运行 instrumentation 和长稳门禁。

## 2026-08-02 · M5 · Release shrinking and API-26 lint gate

### 本轮目标

把 Phase 5 §8.3 的 release code/resource shrinking 从预检状态推进到可重复构建，并修复压缩门禁暴露的 minSdk 兼容性问题；不把 unsigned artifact、静态 lint 或模拟构建当作签名、真机或厂商发布验收。

### 需求/参考/Android 目标

- Requirement: `REL-005`（release 不含 debug/export 测试数据）并为 `REL-006/REL-007` 的后续 API/FGS 验收建立 release 静态门禁；Phase 5 §8.3。
- Primary source: `AndroidMigrationPlanning/docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §8.1–§8.3、`docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md` REL-005/REL-006/REL-007、`docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` §12。
- Android target: `app/build.gradle.kts` release optimization、session metadata/raw/CSV Android data path、`AndroidBleTransport` descriptor subscription path。
- Non-goals: signing/package/distribution, privacy policy and retention decision, API/vendor matrix, real BLE, 2 h endurance and emulator/device instrumentation execution.

### 实现事实

- `app/build.gradle.kts` 将 release `optimization.enable` 从 `false` 改为 `true`；AGP 实际执行 `minifyReleaseWithR8`、`convertShrunkResourcesToBinaryRelease` 和 `optimizeReleaseResources`。
- release lint 首次发现 4 个阻断项：`Files.readString`/`Files.writeString` 在 minSdk 26 不可用，以及 API 33 `writeDescriptor` 返回值误与 `BluetoothGatt.GATT_SUCCESS` 比较。
- session metadata 改为 API 26 可用的 `Files.newInputStream` bounded UTF-8 reader，增加 1 MiB metadata 上限；CSV header 改为 `newOutputStream`；API 33 descriptor 分支改用 `BluetoothStatusCodes.SUCCESS`。
- 无需添加反射 keep 规则：metadata JSON 是手写 codec，manifest 中的 Activity、connectedDevice service、Application 和 FileProvider 均在压缩后 dex/merged manifest 中保留。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew lintRelease test --no-daemon` → `BUILD SUCCESSFUL`；release lint 0 errors、21 warnings，JVM suite 通过（当前 89 tests）。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleRelease assembleDebugAndroidTest --no-daemon` → `BUILD SUCCESSFUL`；release R8/resource shrink、debug androidTest APK 编译通过。
- 压缩 APK：`app/build/outputs/apk/release/app-release-unsigned.apk`，`1,328,529 bytes`；ZIP 未发现 `androidTest`、fixture、golden、raw/CSV/session JSON 测试资源条目；保留 `MainActivity`、`CaptureForegroundService`、`PpgCollectorApplication` 和 `FileProvider` 相关符号。
- Hardware validation: pending；本轮不进行 emulator/真机测试。

### 风险与决策变化

- release lint 仍有 21 个非阻断 warning（BLE deprecated API、manifest API 属性、旧依赖版本及默认样式资源）；本轮不借升级依赖或修改产品身份来隐藏它们。
- `D-002` API/厂商矩阵、`D-003` 后台策略、`D-004` 签名/包名/分发、`D-005` 数据保留/导出/加密、`D-001` 真实协议仍开放；压缩构建不关闭这些风险。

### 下一轮

继续 M5 本地可执行门禁：补 release privacy/log/backup policy audit 与 API/FGS 静态 contract 检查；有 emulator/目标设备后执行 instrumentation、生命周期和长稳矩阵。

## 2026-08-02 · M5 · Session backup boundary and lifecycle disclosure

### 本轮目标

落实 `REL-005`/`D-005` 的当前安全默认：内部 `files/sessions` 会话不进入 Android cloud backup 或 device transfer，用户在 Sessions 页面明确知道未导出数据的卸载后果和 ZIP 导出路径；不替代最终保留期限、加密和签名决策。

### 需求/参考/Android 目标

- Requirement: `REL-005`、Phase 5 §8.3 数据安全/隐私检查；`D-005` 当前建议“不默认云备份”。
- Primary source: `AndroidMigrationPlanning/docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md` REL-005、`docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` §12、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §8.3、`docs/06_OPEN_DECISIONS_AND_RISK_REGISTER.md` D-005/R-007/R-015。
- Android target: `app/src/main/res/xml/backup_rules.xml`、`data_extraction_rules.xml`、`MainActivity` Sessions surface 和 Activity recreation instrumentation contract。
- Non-goals: at-rest encryption、最终 retention/privacy policy、signed distribution、emulator/真机 backup restore、API/厂商矩阵。

### 实现事实

- `files/sessions` 在 legacy `full-backup-content` 和 Android 12+ `cloud-backup`、`device-transfer` 规则中均显式 exclude；内部采集仍只写 app-specific storage，显式 SAF ZIP export 仍是用户归档路径。
- Sessions 页面新增用户可见说明：“会话仅保存在本应用内部；卸载应用会删除未导出的会话……导出 ZIP”；Activity recreation instrumentation contract 同时断言该说明可见。
- 未修改 raw、CSV、metadata schema 或 export contents；隐私边界只限制系统备份复制，避免改变跨平台数据契约。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew lintRelease test assembleRelease assembleDebugAndroidTest --no-daemon` → `BUILD SUCCESSFUL`；release lint 0 errors、JVM suite 通过（89 tests），release R8/resource shrinking、debug androidTest APK 编译通过。
- release APK `app/build/outputs/apk/release/app-release-unsigned.apk` 为 `1,328,729 bytes`；静态 ZIP scan 未发现 `androidTest`、fixture、golden、raw/CSV/session JSON 测试资源条目；manifest 仍引用两套 backup rules。
- Instrumentation runtime、实际 backup/restore、emulator/真机验证: pending；本轮按约定不进行真机测试。

### 风险与决策变化

- 本轮落实的是技术默认，不关闭 `D-005`：加密、保留期限、用户删除入口和正式隐私政策仍需产品/安全决策；`D-004` 签名、`D-002` API/厂商和 `D-003` 后台策略仍开放。
- UI disclosure 不是 runtime backup proof；需要 API 级 instrumentation/backup restore 或发布 QA 证据确认系统行为。

### 下一轮

继续 M5 本地 privacy/log/FGS 静态审计和模拟长稳门禁；在 emulator/目标设备可用时运行 backup/lifecycle/instrumentation，再处理签名与正式隐私决策。

## 2026-08-02 · M5 · REL-001 long-duration raw/CSV/replay simulation

### 本轮目标

移植 Swift `LongDurationDataPathTests` 的 30 min/2 h JVM 门禁，直接驱动 Android production decoder、sequence gate、raw-first writer、CSV、metadata、repository inspection、raw replay、metric scheduler 和 waveform scheduler，验证长流数据完整性与执行预算。

### 需求/参考/Android 目标

- Requirement: `REL-001`、`PROTO-004`、`UI-002`；Phase 5 §8.1–§8.2 长稳精确断言。
- Primary source: `AndroidMigrationPlanning/reference_sources/ios_current/PPGCollectorTests/Integration/LongDurationDataPathTests.swift`、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §6.1、§8.2、`docs/05_SOURCE_REFERENCE_INDEX.md` 长稳测试索引。
- Android target: `LongDurationDataPathTest.kt` 与 `CaptureSessionWriter.kt` checkpoint/flush policy。
- Non-goals: real BLE/firmware, emulator/device 2 h endurance, OEM battery/thermal/power metrics, API matrix and clinical validity.

### 实现事实

- 新增 30 min（3,600 frames/7,200 raw chunks/180,000 samples）和 2 h（14,400 frames/28,800 raw chunks/720,000 samples）测试；每帧按 244+164 bytes notification fragmentation 进入同一 decoder/writer path，并覆盖 UInt8 sequence wrap。
- 测试精确断言 408-byte input、decoded/accepted/frame/raw/CSV/metadata 数量、invalid/discard/missing/duplicate/out-of-order 为 0、decoder pending=0、recent/replay samples=800、raw record peak=256、metric window 从 799 起每 100、waveform 5 Hz publication、warm-up 后 HR/SQI source time、SpO₂ 空值、版本字段和 CSV sample index 连续性。
- 发现原 writer 每个 chunk 都 force raw/CSV 并 atomic checkpoint metadata，30 min 首次耗时 `71.78s`，超过 `60s` budget；按 Phase 3 §6.1/Phase 5 checkpoint 约 1s 契约改为约 1s force+metadata checkpoint，finish 仍强制 flush CSV/raw，保留 raw append 先于派生顺序。

### 验证

- targeted 30 min：`./gradlew :app:testDebugUnitTest --tests ...thirtyMinuteStreamingKeepsCadenceAndFilesExactlyAligned --no-daemon` → pass，test case `1.525s`。
- targeted 2 h：`./gradlew :app:testDebugUnitTest --tests ...twoHourRecordingStreamsWriterReplayAndCsvWithinBudget --no-daemon` → pass，test case `6.918s`。
- full local gate：`env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test lintRelease assembleRelease assembleDebugAndroidTest --no-daemon` → `BUILD SUCCESSFUL`；91 JVM tests、0 failures，release lint 0 errors，R8/resource shrinking、release APK 和 androidTest APK 编译通过。
- Hardware validation: pending；本轮不进行 emulator/真机测试，真实 2 h 的 missing/queue/flush latency/heap/CPU/温升/电量/OEM service survival 仍未验证。

### 风险与决策变化

- checkpoint 由每 chunk 改为约 1s：checkpoint 之间发生 SIGKILL 时，文件可能有超出最近 metadata 的 safe prefix，但 metadata 保持 incomplete，inspection/recovery 仍是恢复路径；finish 会同步最终文件。
- `D-001` 真实协议、`D-002` API/厂商、`D-003` 后台策略、`D-005` 隐私/保留/加密仍开放；模拟数据不能证明真实无线无丢包或厂商后台存活。

### 下一轮

继续 M5 本地 FGS/API 静态 contract 与 queue/stop/failure endurance 注入；设备可用后运行 instrumentation 和真实 2 h 门禁。

## 2026-08-02 · M5 · REL-007 FGS start rejection and manifest contract

### 本轮目标

补齐前台服务启动失败的可解释状态，并把 target SDK、FGS 基础权限、`connectedDevice` 类型权限和私有 service 声明纳入静态 instrumentation contract；不在本轮启动真实服务或宣称系统生命周期已验收。

### 需求/参考/Android 目标

- Requirement: `REL-007`、`REL-003`、`REL-004`；Phase 5 §8.3；风险 `R-003`/`R-015`，决策 `D-002`/`D-003`。
- Primary source: `docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md`、`docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` §12、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §8.3、`docs/06_OPEN_DECISIONS_AND_RISK_REGISTER.md`；Android 官方 [FGS 启动规则](https://developer.android.com/develop/background-work/services/fgs/launch)、[后台启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) 和 [Android 14 FGS 类型要求](https://developer.android.com/about/versions/14/changes/fgs-types-required?hl=en)。
- Android target: `CaptureStartFailure`/`CaptureServiceViewModel`、`FileProviderContractTest`、`CaptureGateUiStateTest`。
- Non-goals: emulator/真机运行、API/厂商矩阵、通知运行时授权流程、锁屏/后台存活、正式签名和产品隐私策略。

### 实现事实

- 新增 `ForegroundServiceStartRejected`，将 `SecurityException`、API 31+ `ForegroundServiceStartNotAllowedException` 及 FGS type exception 映射为可操作的“从前台页面重试并检查服务权限”状态；普通启动错误仍显示 `DeviceNotReady`，避免把系统策略拒绝误报为设备断连。
- instrumentation contract 断言 target SDK 37、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CONNECTED_DEVICE`、`POST_NOTIFICATIONS` 请求权限，以及已有私有 `connectedDevice` capture service 声明。

### 验证

- targeted: `./gradlew :app:testDebugUnitTest --tests com.example.ppgcollector_android.CaptureGateUiStateTest assembleDebugAndroidTest --no-daemon` → `BUILD SUCCESSFUL`。
- full local gate: `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test lintRelease assembleRelease assembleDebugAndroidTest --no-daemon` → `BUILD SUCCESSFUL`；92 JVM tests、0 failures，release lint 0 errors，R8/resource shrinking、release APK 和 androidTest APK 编译通过；merged manifest 含 FGS 权限、`connectedDevice` service 和 target SDK 37。
- Hardware validation: pending；本轮只编译 instrumentation contract，不运行 emulator/真机。

### 风险与决策变化

- Android 官方规则要求 API 31+ 关注后台启动限制，API 34+ 关注 FGS 类型权限；本轮只完成异常映射和声明静态检查，不能证明 Activity 可见性、通知权限、系统重建、锁屏或 OEM 后台策略下的 runtime 行为。
- `D-002` API/厂商矩阵、`D-003` 后台策略、`D-004` 签名/分发和 `D-005` 隐私/保留/加密仍开放。

### 下一轮

继续 M5 failure endurance：注入 startForeground/stop/queue/断连失败，核对服务停止与 safe-prefix recovery；设备可用后运行 API/厂商 instrumentation 和真实生命周期门禁。

## 2026-08-02 · M5 · CAP-007 writer failure and incomplete-prefix injection

### 本轮目标

补齐写入/派生线程异常与用户 stop 竞争时的故障语义：已确认 raw/CSV 前缀仍可读、会话不会伪装为 complete、writer failure 会覆盖尚未完成的普通 stop；不模拟真实磁盘故障或真机服务崩溃。

### 需求/参考/Android 目标

- Requirement: `CAP-002`、`CAP-007`、`REL-004`；Phase 3 §6.1/§6.4、Phase 5 §8.2。
- Primary source: `docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md`、`docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` §6.2、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` 故障注入表，以及 Swift `PPGCollectorTests/Integration/CaptureSessionControllerIntegrationTests.swift` 的 `delayedAppendFailureUpgradesUserStopToIncompleteWriteError`。
- Android target: `CaptureRecordingController.kt`、`CaptureRecordingControllerTest.kt`。
- Non-goals: physical disk-full/raw I/O injection, process SIGKILL, Android service runtime, emulator/真机/API/厂商矩阵。

### 实现事实

- worker 捕获 writer/decoder 异常时，若原 stop reason 是 USER、VIEW_EXIT、SCENE_BACKGROUND、DEVICE_DISCONNECT、DATA_TIMEOUT 或 UNKNOWN，则升级为 `WRITE_ERROR`；已发生的 `RESOURCE_PRESSURE`/其他 fatal reason 保留首因，错误文本保留在 metadata writer error。
- 新 JVM 场景先排入一个有效 408-byte frame，再排入超过 64 KiB raw 防御上限的 chunk，同时请求 USER stop；验证有效 chunk 已写入 raw/CSV 后，异常导致 summary/metadata 为 `WRITE_ERROR`、`complete=false`，且 CSV 仍有 50 行可读前缀。

### 验证

- targeted: `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests com.example.ppgcollector_android.data.session.CaptureRecordingControllerTest --no-daemon` → `BUILD SUCCESSFUL`。
- full local gate: `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test lintRelease assembleRelease assembleDebugAndroidTest --no-daemon` → `BUILD SUCCESSFUL`；93 JVM tests、0 failures，release lint 0 errors，R8/resource shrinking、release APK 和 androidTest APK 编译通过。
- Hardware validation: pending；本轮没有运行 emulator/真机，也没有把超限输入当作真实磁盘错误的替代证明。

### 风险与决策变化

- 该切片证明的是 controller 的确定性异常语义和已确认前缀，不证明 `FileChannel`/文件系统耗尽时每个 OS 错误都能保留完整 prefix；真实 raw write error、SIGKILL 最近 checkpoint、服务被系统终止仍需独立门禁。
- `D-002` API/厂商矩阵、`D-003` 后台策略、`D-004` 签名/分发、`D-005` 隐私/保留/加密和真实 CUP 证据仍开放。

### 下一轮

继续 M5 的 release privacy/log 静态审计与 API/厂商矩阵准备；本地优先补充真实文件 I/O failure seam，再在设备可用时执行生命周期与 2 h 门禁。

## 2026-08-02 · M5 · REL-005 release privacy/log artifact audit

### 本轮目标

把 REL-005 的本地发布检查变成可重复的 Gradle 门禁：生产源码不得通过 ad-hoc logging 输出原始 PPG、设备身份、路径或堆栈；release APK 不得携带测试/fixture/session 文件。该门禁不替代最终隐私政策、系统日志抓取或真实分发 QA。

### 需求/参考/Android 目标

- Requirement: `REL-005`；Phase 5 §8.3；风险 `R-015`，决策 `D-005`。
- Primary source: `docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md` REL-005、`docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` §12、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §8.3，以及现有 release preflight/artifact scan 记录。
- Android target: `app/build.gradle.kts` 的 `verifyReleasePrivacy` task、release APK。
- Non-goals: at-rest encryption、retention/privacy policy、完整系统 logcat/Crash report 审计、签名发布、emulator/真机测试。

### 实现事实

- `verifyReleasePrivacy` 依赖 `assembleRelease`，扫描 `src/main` 的 Kotlin/Java，拒绝 `android.util.Log`、Timber、`println`、`System.out/err` 和 `printStackTrace` 等 ad-hoc 输出入口。
- 任务流式扫描 unsigned release APK ZIP，拒绝 `androidTest`/`test`、fixture/golden、`.cupraw` 和 `.session.json` 条目；只报告文件/entry 名，不打印采集数据。
- 任务声明 source/APK inputs，并明确使用 `--no-configuration-cache` 的专用审计模式；普通 JVM/lint/release/androidTest 构建继续使用项目 configuration cache。

### 验证

- privacy audit: `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:verifyReleasePrivacy --no-daemon --no-configuration-cache` → `BUILD SUCCESSFUL`，输出 `REL-005 privacy audit passed`。
- full local gate: `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test lintRelease assembleRelease assembleDebugAndroidTest --no-daemon` → `BUILD SUCCESSFUL`；93 JVM tests、0 failures，release lint 0 errors，R8/resource shrinking、release APK 和 androidTest APK 编译通过。
- Hardware validation: pending；本轮不运行 emulator/真机，不把静态 audit 当作系统 logcat、备份恢复或签名渠道证明。

### 风险与决策变化

- 当前生产源码无 ad-hoc logging API，因此静态门禁通过；如果后续引入统一诊断 logger，必须先增加字段级脱敏测试，禁止把本任务规则简单放宽。
- `D-005` 的加密、保留期限、用户删除入口和正式隐私政策仍开放；`D-002`/`D-003` API/厂商后台与生命周期门禁仍未执行。

### 下一轮

继续 M5 API/厂商矩阵与真实生命周期门禁准备；本地优先完善 notification/FGS/permission contract 和可注入的系统 stop/断连报告，再等待设备执行 instrumentation 与真实 2 h 记录。

## 2026-08-02 · M5 · REL-007 API 33 notification permission gate

### 本轮目标

补齐 API 33+ `POST_NOTIFICATIONS` 被拒绝时的可解释录制 gate：开始录制前由可见 Activity 请求权限，拒绝后不启动 FGS，并在 UI 说明通知是持续采集状态的必要可见性；API 32 及以下不引入该 runtime gate。

### 需求/参考/Android 目标

- Requirement: `REL-007`、`REL-003`、`REL-004`；Phase 3 §6.2、Phase 5 §8.3；风险 `R-003`，决策 `D-003`。
- Primary source: `docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md` REL-007、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §6.2/§8.3；Android 官方 [notification runtime permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission)（API 33+，FGS notification 也受可见性影响）。
- Android target: `CaptureStartGate.kt`/`CaptureServiceViewModel.kt` notification policy and gate、`MainActivity.kt` `RequestPermission` launcher、`CaptureGateUiStateTest.kt`。
- Non-goals: actual permission dialog/device notification drawer, API 26/30/31/34/36 runtime matrix, OEM behavior, FGS process survival and real BLE.

### 实现事实

- 新增 `NotificationPermissionDenied` 与纯 Kotlin `CaptureNotificationPermissionPolicy`；仅 API 33+ 且未授权时返回该 failure，API 32 及以下返回 no gate。
- `MainActivity` 在开始录制前请求 `POST_NOTIFICATIONS`；授权后重试 start，拒绝后不调用 `startForegroundService`，gate 展示“允许通知后再开始录制”的行动建议。
- `CaptureViewModel` 将通知权限结果纳入 gate StateFlow，同时保留 FGS `SecurityException`/background-start rejection 的独立映射；未修改 raw、CSV、session schema 或 FGS ownership。

### 验证

- targeted: `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests com.example.ppgcollector_android.CaptureGateUiStateTest --no-daemon` → `BUILD SUCCESSFUL`。
- full local gate: `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test lintRelease assembleRelease assembleDebugAndroidTest --no-daemon` → `BUILD SUCCESSFUL`；94 JVM tests、0 failures，release lint 0 errors，R8/resource shrinking、release APK 和 androidTest APK 编译通过。
- Hardware validation: pending；本轮不执行权限对话框、通知抽屉、FGS runtime、emulator/真机或 OEM 测试。

### 风险与决策变化

- 静态/JVM 证据证明 API 分支、gate reducer 和请求回调 wiring，但不能证明用户在系统设置撤销权限、通知渠道状态、Task Manager 或厂商后台策略下的实际表现；这些仍是 REL-006/REL-007 硬件门禁。
- `D-002` API/厂商矩阵、`D-003` 后台策略、`D-004` 签名/分发、`D-005` 隐私/保留/加密和真实 CUP 证据仍开放。

### 下一轮

继续 M5 API/厂商矩阵准备与 notification/FGS lifecycle static contract；设备可用后运行 API 33/34/36 权限、通知拒绝、FGS start/stop、后台/锁屏和真实 2 h 门禁。

## 2026-08-02 · M5 · REL-006 BLE permission API boundary matrix

### 本轮目标

补齐 BLE runtime permission policy 的发布 API 边界证据：覆盖规划要求的 API 26/30/31/33/34/35/36/37，并验证 API 33+ `neverForLocation` 分支与未来更高 API 不回退到 legacy location-only 逻辑；不把 JVM policy test 误称为 emulator/厂商运行矩阵。

### 需求/参考/Android 目标

- Requirement: `REL-006`、`BLE-001`；Phase 2 §5.1、Phase 5 §8.1；风险 `R-003`。
- Primary source: `docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md` REL-006/BLE-001、`docs/01_ANDROID_MIGRATION_MASTER_PLAN.md` API boundary list、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §5.1/§8.1、`BlePermissionPolicy`。
- Android target: `app/src/test/java/com/example/ppgcollector_android/core/ble/BleCoreTest.kt`。
- Non-goals: emulator boot, runtime permission dialogs, real Bluetooth stack, OEM battery/background behavior, FGS/notification runtime and target-device reports.

### 实现事实

- 新增 matrix test：API 26/30 只需 legacy `ACCESS_FINE_LOCATION`；API 31/33/34/35/36/37 需要 `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`；`neverForLocation=false` 时 API 31+ 额外需要 location；API 38 future branch 保持 nearby-device policy。
- 该测试与既有 Manifest `ACCESS_FINE_LOCATION maxSdk=30`、`BLUETOOTH_SCAN neverForLocation` 和 API 30/31/33 permission-result seam 共同形成静态/JVM boundary evidence，不改变权限或数据契约。

### 验证

- targeted: `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests com.example.ppgcollector_android.core.ble.BleCoreTest --no-daemon` → `BUILD SUCCESSFUL`。
- full local gate: `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test lintRelease assembleRelease assembleDebugAndroidTest --no-daemon` → `BUILD SUCCESSFUL`；95 JVM tests、0 failures，release lint 0 errors，R8/resource shrinking、release APK 和 androidTest APK 编译通过。
- Hardware validation: pending；本轮不运行 API emulator、权限对话框、真实 BLE 或厂商设备。

### 风险与决策变化

- JVM policy coverage 不能证明 API 34/36 的系统权限行为、扫描限制、FGS policy、蓝牙关闭/撤权或厂商后台存活；REL-006 仍未完成。
- `D-002` 目标设备矩阵、`D-003` 后台策略、`D-004` 签名/分发、`D-005` 隐私/保留/加密和真实 CUP 证据仍开放。

### 下一轮

继续 API/厂商矩阵的本地准备：补静态 merged-manifest/API target report 和服务生命周期 failure contract；设备可用后运行 API 26/30/31/33/34/35/36/37 emulator 与至少两类厂商门禁。

## 2026-08-02 · M5 · REL-004 service finalization lifecycle contract

### 本轮目标

补齐停止录制时的 connectedDevice FGS 生命周期契约：服务必须保持前台状态，直到 raw-first writer 完成最终 flush/metadata 状态发布；只在 `FINALIZED` 或 `FAILED` 后移除通知并停止服务。

### 需求/参考/Android 目标

- Requirement: `REL-004`、`CAP-002`、`CAP-007`；Phase 3 §6.2/§6.4、Phase 5 §8.1；风险 `R-003`、`R-015`，决策 `D-003`。
- Primary source: `docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` §6.4/§8、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §6.4/§8.1；`CaptureRecordingController` finalizer and `CaptureForegroundService` lifecycle.
- Android target: `app/src/main/java/com/example/ppgcollector_android/CaptureForegroundService.kt`。
- Non-goals: changing raw/CUPRAW1/CSV/session schema, stop-reason precedence, BLE ownership, emulator/OEM/real-device runtime validation.

### 实现事实

- `CaptureForegroundService.stopRecording` now observes the controller `StateFlow` after requesting stop and keeps the FGS notification until `FINALIZED` or `FAILED`; the existing immediate-stop path remains for idle/already-terminal states.
- The service owns a supervisor coroutine scope and cancels pending finalization observation before controller cleanup in `onDestroy`, preventing a late callback from calling `stopSelf` after service teardown.
- The controller remains the single finalizer and still drains accepted raw chunks before publishing the terminal state; no data contract or first-stop-reason behavior changed.

### 验证

- Targeted: `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest :app:compileDebugKotlin --no-daemon` → `BUILD SUCCESSFUL`。
- `git diff --check` → passed。
- Hardware validation: pending; this round does not run emulator/device, notification drawer, lock-screen, OEM, or real 2 h capture tests.

### 风险与决策变化

- JVM/build evidence verifies compilation and existing controller finalization tests, but not Android process death, notification timing, or vendor-specific FGS behavior; those remain release/device gates.
- `D-002`、`D-003`、`D-004`、`D-005` and real CUP evidence remain open.

### 下一轮

Add the static merged-manifest/API target report requested by the M5 release checklist, then run available instrumentation and API/vendor lifecycle gates when an emulator/device is available.

## 2026-08-02 · M5 · REL-006/REL-007 merged manifest API contract report

### 本轮目标

把发布构建的 API/权限/前台服务契约从源码和设备运行时检查扩展为 merged-manifest 静态门禁，生成可审计报告，防止依赖 manifest merge 后静默改变 target/min API、BLE 权限或 connectedDevice FGS 配置。

### 需求/参考/Android 目标

- Requirement: `REL-006`、`REL-007`；Phase 5 §8.1/§8.3；风险 `R-003`，决策 `D-002`/`D-003`。
- Primary source: `docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md` REL-006/REL-007、`docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` §9、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §8.1/§8.3、`docs/05_SOURCE_REFERENCE_INDEX.md` Android manifest/service seam。
- Android target: `app/build.gradle.kts` `verifyReleaseApiContract` and `release-api-contract.txt` report.
- Non-goals: emulator/device permission dialogs, OEM behavior, real BLE, signing, package identity, and changing raw/CSV/session/algorithm contracts.

### 实现事实

- Centralized release API constants (`minSdk=26`, `compileSdk=37`, `targetSdk=37`) and report both declared values and the parsed release merged manifest values.
- Added `verifyReleaseApiContract`, which validates merged `uses-sdk`, legacy location `maxSdkVersion=30`, `BLUETOOTH_SCAN` `neverForLocation`, required BLE/FGS/notification permissions, and private `connectedDevice` `CaptureForegroundService`.
- The task writes `app/build/reports/release-api-contract.txt` and is now a dependency of `verifyReleasePrivacy`, making the existing release privacy audit also enforce the API/manifest contract.
- The report is build output only; no generated artifact or user-owned `.idea/` content is committed.

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:verifyReleaseApiContract --no-configuration-cache --no-daemon` → `BUILD SUCCESSFUL`; report status passed.
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test lintRelease assembleRelease assembleDebugAndroidTest --no-daemon` → `BUILD SUCCESSFUL`; 95 JVM tests, release lint 0 errors, release R8/resource shrinking and androidTest APK compilation passed.
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:verifyReleasePrivacy --no-configuration-cache --no-daemon` → `BUILD SUCCESSFUL`; REL-005 and merged API contract both passed.
- `git diff --check` → passed。
- Hardware validation: pending; this round does not run API emulator, runtime permission, OEM, instrumentation, or real-device tests.

### 风险与决策变化

- Static merged-manifest evidence now covers the release configuration boundary, but cannot prove API 34/36 system enforcement, background-start behavior, notification drawer visibility, or vendor lifecycle survival; `REL-006`/`REL-007` runtime gates remain open.
- `D-001`、`D-002`、`D-003`、`D-004`、`D-005` and real CUP evidence remain open.

### 下一轮

When an emulator/device is available, run the API 26/30/31/33/34/35/36/37 permission and FGS matrix; locally continue the remaining M5 release checks without claiming runtime coverage from this static report.

## 2026-08-02 · M5 · live metrics CSV snapshot policy

### 本轮目标

明确并验证 live metrics 与 raw-first CSV 的边界：metrics 只能作为 raw notification 写入时的 point-in-time snapshot，异步 HR/SQI/R 结果不得回填已经写出的 CSV 行；离线分析结果另行版本化且不修改源 CSV。

### 需求/参考/Android 目标

- Requirement: `CAP-002`、`SIG-002`、`REL-001`；架构 §6.3/§7.2/§7.3/§10；风险 `R-015`。
- Primary source: `docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` §6.3/§7.3；Swift `CaptureSessionWriter.swift` uses the chunk's `metrics` snapshot and does not perform asynchronous CSV backfill.
- Android targets: `CaptureSessionWriter.kt` and `CaptureRecordingControllerTest.kt`。
- Non-goals: changing the 25-column schema, adding an offline analysis sidecar, calibrating SpO2/BP, or claiming runtime/device evidence.

### 实现事实

- Architecture contract now states that emitted CSV rows are immutable with respect to later analysis; metric `*_time_s` remains derived from the real `sourceSampleIndex`.
- Writer documentation labels the `metrics` parameter as the snapshot captured at raw acknowledgement and explicitly prohibits async mutation of emitted rows.
- The 8-second controller integration test waits for a real analysis result (`windowEndSampleIndex=799`) and then parses all 800 CSV rows, asserting HR/SQI/R remain invalid rather than being backfilled by the later analysis worker.
- Existing valid metric formatter behavior is unchanged: callers that provide a valid snapshot at write time still preserve value, validity, and source time.

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests com.example.ppgcollector_android.data.session.CaptureRecordingControllerTest --no-daemon` → `BUILD SUCCESSFUL`；24 JVM tests completed。
- `git diff --check` → passed。
- Hardware validation: pending; this round does not run emulator/device, background, or real BLE tests.

### 风险与决策变化

- CSV now has an explicit no-backfill policy, but a separately versioned offline analysis result artifact remains a future M6 scope; live SQI remains provisional, ratio remains diagnostic, and SpO2/BP remain unavailable.
- `D-001`、`D-002`、`D-003`、`D-004`、`D-005`、`D-006`、`D-007`、`D-008` remain open。

### 下一轮

Continue M5 local hardening and, when an emulator/device is available, run the pending lifecycle/API/vendor matrix; do not reinterpret this JVM assertion as runtime coverage.

## 2026-08-02 · M5 · REL-003/REL-004 service lifecycle contract report

### 本轮目标

为 connectedDevice 前台服务补充发布构建级 lifecycle/failure 静态报告，确保系统重建策略、终止观察和通知停止入口在没有 emulator 时也有可审计的源码契约；不把静态检查冒充运行时证明。

### 需求/参考/Android 目标

- Requirement: `REL-003`、`REL-004`、`UI-008`；Phase 3 §6.2、Phase 5 §8.1；架构 §9；风险 `R-003`/`R-015`。
- Primary source: `docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md` REL-003/REL-004、`docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` §9、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §8.1；`CaptureForegroundService.kt`。
- Android target: `app/build.gradle.kts` `verifyReleaseLifecycleContract` and `release-lifecycle-contract.txt` report.
- Non-goals: process death, background/lock-screen survival, notification drawer behavior, API/OEM runtime behavior, real BLE, and signing.

### 实现事实

- Added a release task that compiles the release source and checks the service lifecycle fragments: `START_NOT_STICKY`, terminal `FINALIZED/FAILED` observation, observer cancellation, controller close, foreground removal, and immutable stop action.
- The task emits `app/build/reports/release-lifecycle-contract.txt` and is included by `verifyReleasePrivacy` beside the merged-manifest/API and privacy checks.
- The contract also checks the relative `onDestroy()` order: cancel the pending observer, cancel the service scope, close the controller, then remove the foreground notification.
- No recording ownership, raw/CSV/session schema, stop-reason precedence, or service implementation behavior changed in this slice.

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:verifyReleaseLifecycleContract --no-configuration-cache --no-daemon` → `BUILD SUCCESSFUL`；report status passed。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test lintRelease assembleRelease assembleDebugAndroidTest --no-daemon` → `BUILD SUCCESSFUL`；95 JVM tests、release lint 0 errors、R8/resource shrinking and androidTest APK compilation passed。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:verifyReleasePrivacy --no-configuration-cache --no-daemon` → `BUILD SUCCESSFUL`；REL-005、REL-006/007 API and REL-003/004 lifecycle reports passed。
- `git diff --check` → passed。
- Hardware validation: pending; this round does not run emulator/device, process recreation, background/lock-screen, or vendor tests.

### 风险与决策变化

- Static source evidence reduces release regression risk but does not prove Android callback ordering after process kill or OEM service survival; REL-003/REL-004 runtime gates remain open.
- `D-001`、`D-002`、`D-003`、`D-004`、`D-005`、`D-006`、`D-007`、`D-008` remain open。

### 下一轮

Continue local release hardening; when an emulator/device is available, execute process recreation, FGS stop, API matrix, and vendor lifecycle tests without closing the runtime gate from static evidence alone.

## 2026-08-02 · M5 · REL-007 foreground service runtime failure propagation

### 本轮目标

补齐服务内部 `startForeground()` 权限/类型拒绝的用户可见失败路径：即使异常发生在 `startForegroundService()` 返回之后，Activity 仍能通过 binder/StateFlow 收到 `ForegroundServiceStartRejected`，不把失败伪装成设备断连或静默停止。

### 需求/参考/Android 目标

- Requirement: `REL-007`、`REL-003`；Phase 3 §6.2、Phase 5 §8.1/§8.3；架构 §9；风险 `R-003`。
- Primary source: `docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md` REL-007、`docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` §9、Android FGS start/type requirements；`CaptureForegroundService.kt`/`CaptureServiceViewModel.kt`。
- Android targets: service runtime failure StateFlow, local binder, `CaptureServiceClient`, `CaptureGateUiState`。
- Non-goals: changing raw/CSV/session ownership, retrying a rejected FGS automatically, runtime permission dialogs, process/OEM behavior, or real-device validation.

### 实现事实

- `CaptureForegroundService` retains `ForegroundServiceStartRejected` in a `MutableStateFlow`; the failure is cleared only when a new start action begins, so a late Activity bind still observes it.
- `LocalBinder.runtimeFailureFlow()` exposes the state; `CaptureServiceClient` observes it and includes the value in `CaptureServiceObservation`; `CaptureViewModel` folds it into the existing actionable capture gate.
- Added JVM coverage proving the propagated failure remains non-startable and displays the existing permission/front-page retry guidance. Raw/session/CSV behavior is unchanged.
- Release lifecycle static contract now also checks the service/ViewModel failure-flow fragments and reports `runtime_failure=service_stateflow_to_capture_gate`.

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests com.example.ppgcollector_android.CaptureGateUiStateTest :app:verifyReleaseLifecycleContract --no-configuration-cache --no-daemon` → `BUILD SUCCESSFUL`。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test lintRelease assembleRelease assembleDebugAndroidTest --no-daemon` → `BUILD SUCCESSFUL`；96 JVM tests、0 failures，release lint 0 errors，R8/resource shrinking and androidTest APK compilation passed。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:verifyReleasePrivacy --no-configuration-cache --no-daemon` → `BUILD SUCCESSFUL`；REL-005、REL-006/007 API、REL-003/004 lifecycle and runtime failure-flow reports passed。
- `git diff --check` → passed。
- Hardware validation: pending; this round does not run FGS runtime, permission dialog, process, emulator, OEM, or real-device tests.

### 风险与决策变化

- The retained flow closes the Activity-observation race but cannot prove system callback timing or vendor behavior; REL-007 runtime matrix remains open.
- `D-001`、`D-002`、`D-003`、`D-004`、`D-005`、`D-006`、`D-007`、`D-008` remain open。

### 下一轮

Run the integrated release gate, then use an emulator/device when available to verify API 34/36 FGS rejection, permission revocation, notification visibility, process recreation, and vendor lifecycle behavior.

## 2026-08-02 · M5 · REL-002 fake GATT 20-cycle lifecycle gate

### 本轮目标

补齐 REL-002 的可重复 fake GATT 验收切片：20 次 scan/connect/subscribe/receiving/disconnect 循环中，每轮 generation 单调、receiver 阶段和 freshness 归零、晚到 callback 被丢弃、CCCD 命令不重复且保持 passive control 边界。

### 需求/参考/Android 目标

- Requirement: `REL-002`；Phase 2 BLE owner/fake GATT 方案、Phase 5 §8.2 reliability gate；风险 `R-002`/`R-003`。
- Primary source: `docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md` REL-002、`docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` BLE ownership/data boundary、Swift BLE integration lifecycle semantics。
- Tests/golden: fake transport event path plus existing GATT state-machine happy/error/generation tests；本轮新增 20-cycle loop assertions。
- Android target: `app/src/main/java/com/example/ppgcollector_android/core/ble/BleGattStateMachine.kt` 的现有 owner seam、`FakeBleTransport`、`CupBleGattStateMachineTest.kt`。
- Non-goals: concrete device script、BluetoothGatt/OEM behavior、emulator or real-device execution、raw/session/CSV changes。

### 实现事实

- 新增 `twentyFakeGattLifecycleLoopsResetReceiverAndRejectLateCallbacks`：每轮完成 discovery/notify/receiving 后显式 disconnect，验证 `Idle` 与 `UNAVAILABLE` reset，并以旧 generation 注入 late value callback，确认 diagnostics 增量且状态不被污染。
- 20 轮累计断言 connect/disconnect、CCCD enable/disable 各恰好一次；control characteristic 没有 notification write，保留 passive stream 语义；generation 从 1 到 20 单调递增。
- 测试只依赖既有 fake event seam，不改变生产 raw、CSV、协议或 session 数据路径。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests com.example.ppgcollector_android.core.ble.CupBleGattStateMachineTest --no-daemon` → `BUILD SUCCESSFUL`。
- Hardware validation: pending；本轮不运行真机/模拟器、BluetoothGatt/OEM、后台或服务重建测试。

### 风险与决策变化

- fake loop 证明 owner 的 generation/phase/freshness/reset 语义和命令边界，不证明真实 BluetoothGatt callback 排序、系统资源回收或厂商行为；REL-002 真机脚本门禁仍开放。
- `D-001`、`D-002`、`D-003`、`D-004`、`D-005`、`D-006`、`D-007`、`D-008` remain open；用户修改的 `00_AGENT_MIGRATION_BRIEF.md` 与 `.idea/` 未纳入提交。

### 下一轮

继续 M5 release/API/厂商矩阵硬化；有 emulator/device 后执行 REL-002 真机循环及 REL-003/004 生命周期门禁，不以 fake 结果关闭真实设备风险。

## 2026-08-02 · M5 · REL-002 Android GATT resource release contract

### 本轮目标

把 REL-002 从 fake owner 循环进一步落实到 Android `BluetoothGatt` adapter：每次连接替换、正常断开、断开权限异常、stale callback 和 transport close 都必须释放旧平台对象及其关联 bookkeeping，不让旧 GATT 或 pending CCCD 状态污染新连接。

### 需求/参考/Android 目标

- Requirement: `REL-002`；Phase 2 §5.2、Phase 5 §8.1/§8.2；风险 `R-002`/`R-003`。
- Primary source: `docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md` REL-002、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §5.2/§8.1、`docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` BLE single-owner/generation boundary。
- Tests/golden: existing fake GATT 20-cycle JVM gate plus release source contract; real BluetoothGatt callback/resource behavior remains hardware evidence.
- Android target: `app/src/main/java/com/example/ppgcollector_android/core/ble/AndroidBleTransport.kt`、`app/build.gradle.kts`。
- Non-goals: changing CUP UUIDs, passive control behavior, raw notification bytes/timestamps, CSV/session schema, or running emulator/device tests.

### 实现事实

- 新增 `releaseGatt(deviceId, gatt, disconnect)` 统一释放函数：按对象身份移除 current map slot，清理 `connectedIds`（仅 current owner）、pending descriptor，并在需要时 disconnect 后 `BluetoothGatt.close()`。
- 新连接替换其他设备时先释放旧 GATT；`disconnect()` 的 `SecurityException` 路径也释放对象；`close()` 遍历 snapshot 释放全部连接。
- stale `onConnectionStateChange` callback 不再静默 return，而是关闭迟到的旧 GATT；身份判断避免旧 callback 清除新连接的 `connectedIds`。
- 新增 `verifyReleaseBleTransportContract`，生成 `app/build/reports/release-ble-transport-contract.txt`，并纳入 `verifyReleasePrivacy`；未改变协议、raw、CSV、算法或 session 数据路径。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin :app:verifyReleaseBleTransportContract --no-configuration-cache --no-daemon` → `BUILD SUCCESSFUL`；adapter 编译通过，保留既有 Android API deprecation warnings。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test lintRelease assembleRelease assembleDebugAndroidTest --no-daemon` → `BUILD SUCCESSFUL`；97 JVM tests、0 failures，release lint 0 errors，R8/resource shrinking、release APK 和 androidTest APK 编译通过。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:verifyReleasePrivacy --no-configuration-cache --no-daemon` → `BUILD SUCCESSFUL`；REL-005 privacy、REL-006/007 API、REL-003/004 lifecycle 和 REL-002 BLE transport contract 均通过。
- `git diff --check` → 待提交前执行。
- Hardware validation: pending；本轮不执行真实 BluetoothGatt、API/厂商、后台/锁屏或真机循环。

### 风险与决策变化

- 统一释放和静态契约降低 stale GATT/pending descriptor 泄漏风险，但不能证明 Android framework/OEM 在真实 callback 排序、蓝牙关闭或进程终止时的行为；REL-002 真机脚本及 REL-006 API/厂商运行矩阵仍开放。
- `D-001`、`D-002`、`D-003`、`D-004`、`D-005`、`D-006`、`D-007`、`D-008` remain open；用户修改的 `00_AGENT_MIGRATION_BRIEF.md` 与 `.idea/` 未纳入提交。

### 下一轮

继续 M5 本地发布硬化或准备可执行的 instrumentation lifecycle report；有 emulator/device 后执行 REL-002 真机循环、API/厂商和 FGS/进程重建门禁，不以静态 contract 替代运行时证据。

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
