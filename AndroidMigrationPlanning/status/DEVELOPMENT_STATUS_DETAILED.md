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

## 2026-08-02 · M5 · BLE availability activation and permission recovery contract

### 本轮目标

修复 Android BLE app-scope owner 未触发 adapter activation 的运行缺口，并保持 BLE-001/003 的权限恢复语义：事件 sink 必须先安装再激活；初始权限拒绝后，权限恢复时扫描操作必须重新查询 adapter；系统权限异常必须转为可观察 availability，而不是抛出 callback 崩溃。

### 需求/参考/Android 目标

- Requirement: `BLE-001`、`BLE-003`、`UI-001`；Phase 2 §5.1/§5.2、Phase 5 §8.1；风险 `R-003`。
- Primary source: `docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md` BLE-001/BLE-003、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §5.1/§5.2、`docs/05_SOURCE_REFERENCE_INDEX.md` `BLECentralService`/`BLETransport` seam；Swift `BLECentralService.init` installs the event handler before `transport.activate()`.
- Tests/golden: `BleCoordinatorTest` fake transport activation/permission recovery; release BLE transport contract; real permission dialog and API/vendor behavior remain hardware evidence.
- Android target: `BleCoordinator.kt`、`AndroidBleTransport.kt`、`BleCoordinatorTest.kt`、`app/build.gradle.kts`。
- Non-goals: changing CUP UUID/profile, scan filtering, raw notification bytes/timestamps, GATT stage state machine, CSV/session data, or running emulator/device tests.

### 实现事实

- `BleCoordinator` now installs its ordered event handler and calls `transport.activate()` during initialization; `startScanning()` retries activation when permissions have just recovered or availability is not yet powered on.
- `AndroidBleTransport.activate()` catches platform `SecurityException` (including API 31+ adapter access without `BLUETOOTH_CONNECT`) and emits `BluetoothAvailability.UNAUTHORIZED`; the callback remains event-only and does no disk/algorithm work.
- Added fake coverage for initial activation, permission-gated scan, reactivation after grant, and deferred scan start after `POWERED_ON`; existing coordinator command-order expectations were updated accordingly.
- The existing REL-002 release BLE transport contract also checks the unauthorized activation branch; raw/protocol/CSV/session contracts remain unchanged.

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests com.example.ppgcollector_android.core.ble.BleCoordinatorTest :app:verifyReleaseBleTransportContract --no-configuration-cache --no-daemon` → `BUILD SUCCESSFUL`。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test lintRelease assembleRelease assembleDebugAndroidTest --no-daemon` → `BUILD SUCCESSFUL`；98 JVM tests、0 failures，release lint 0 errors，R8/resource shrinking、release APK 和 androidTest APK 编译通过。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:verifyReleasePrivacy --no-configuration-cache --no-daemon` → `BUILD SUCCESSFUL`；REL-005 privacy、REL-006/007 API、REL-003/004 lifecycle 和 REL-002 BLE transport contract 均通过。
- `git diff --check` → passed。
- Hardware validation: pending；本轮不执行真实权限对话框、Bluetooth adapter/GATT、API/厂商或真机扫描测试。

### 风险与决策变化

- JVM fake evidence now covers activation ordering and permission-recovery intent, but cannot prove Android framework permission timing, adapter callbacks, scan throttling, or OEM behavior; BLE-001/003 runtime matrix remains open.
- `D-001`、`D-002`、`D-003`、`D-004`、`D-005`、`D-006`、`D-007`、`D-008` remain open；用户修改的 `AGENTS.md`、`00_AGENT_MIGRATION_BRIEF.md` 与 `.idea/` 未纳入提交。

### 下一轮

继续 M5 本地发布/生命周期硬化；有 emulator/device 后运行 API 30/31/33/36 permission、BLE scan/GATT、后台/锁屏和厂商矩阵门禁，不以 fake activation 结果替代真机证据。

## 2026-08-02 · M5 · BLE CCCD permission-revocation failure contract

### 本轮目标

补齐 BLE-001/003 在连接后撤销 `BLUETOOTH_CONNECT` 时的 CCCD 写入失败路径：平台 adapter 不应让 `SecurityException` 穿透主线程 callback；必须清理 pending descriptor、发布可审计的订阅失败事件，并让 GATT owner 安全进入失败/断开状态，不生成 raw 数据。

### 需求/参考/Android 目标

- Requirement: `BLE-001`、`BLE-003`、`BLE-005`、`REL-004`；Phase 2 §5.2、Phase 5 §8.1；风险 `R-003`。
- Primary source: `docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md` BLE-001/003/005、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §5.2、`docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` single-owner/raw-first failure boundary；Android `BluetoothGatt.writeDescriptor` API seam。
- Tests/golden: fake GATT state-machine CCCD failure path plus release source contract; Android permission revocation, framework callback ordering and OEM behavior remain hardware evidence.
- Android target: `app/src/main/java/com/example/ppgcollector_android/core/ble/AndroidBleTransport.kt`、`CupBleGattStateMachineTest.kt`、`app/build.gradle.kts`。
- Non-goals: changing CCCD UUID/value policy, CUP control writes, raw/CSV/session schema, or running emulator/device tests.

### 实现事实

- Wrapped the Android `setNotificationsEnabled` descriptor path in a `SecurityException` boundary; it removes the pending descriptor and emits `NotificationStateChanged(..., false, "未获得蓝牙连接权限")`.
- Existing state-machine failure handling then records the subscription error, disconnects the device and does not deliver notification bytes to the raw sink.
- Added fake GATT coverage for CCCD failure and extended `verifyReleaseBleTransportContract` to inspect the notification function body, including permission catch, pending cleanup and failure event.
- No protocol, raw, CSV, algorithm or session data contract changed.

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests com.example.ppgcollector_android.core.ble.CupBleGattStateMachineTest :app:verifyReleaseBleTransportContract --no-configuration-cache --no-daemon` → `BUILD SUCCESSFUL`。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test lintRelease assembleRelease assembleDebugAndroidTest --no-daemon` → `BUILD SUCCESSFUL`；99 JVM tests、0 failures，release lint 0 errors，R8/resource shrinking、release APK 和 androidTest APK 编译通过。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:verifyReleasePrivacy --no-configuration-cache --no-daemon` → `BUILD SUCCESSFUL`；REL-005 privacy、REL-006/007 API、REL-003/004 lifecycle 和 REL-002 BLE transport contract 均通过。
- `git diff --check` → passed。
- Hardware validation: pending；本轮不执行真实权限撤销、BluetoothGatt、API/厂商、后台/锁屏或真机循环测试。

### 风险与决策变化

- Local evidence proves the adapter-to-owner failure boundary, but not system permission revocation timing, descriptor callback races, or OEM behavior; BLE-001/003/005 runtime gates remain open.
- `D-001`、`D-002`、`D-003`、`D-004`、`D-005`、`D-006`、`D-007`、`D-008` remain open；用户修改的 `AGENTS.md`、`00_AGENT_MIGRATION_BRIEF.md` 与 `.idea/` 未纳入提交。

### 下一轮

继续 M5 本地发布/生命周期硬化；有 emulator/device 后运行权限撤销、CCCD/断连、API/厂商和 FGS 生命周期门禁，不以 fake failure path 替代真实设备证据。

## 2026-08-02 · M4 · Scrollable Compose acceptance surface

### 本轮目标

补齐 Phase 4 §7.2 的页面可达性切片：Live/Capture/Sessions/详情共用的主页在小屏和较大字号下必须可滚动，Activity recreation instrumentation 不能只断言节点存在，还要把屏外的 Sessions 与卸载提示滚入视口后再断言。保持本轮不运行 emulator/真机 runtime 门禁。

### 需求/参考/Android 目标

- Requirement: `UI-002`、`UI-003`、`UI-007`、`UI-009`、`REL-003`；Phase 4 §7.1/§7.2 的动态字号、关键状态可达和 Activity recreation 门禁。
- Primary source: `docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md` UI-002/UI-003/UI-007/UI-009、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §7.1/§7.2、`docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` Compose/lifecycle boundary、`MainActivity.kt`/`MainActivitySystemTest.kt`。
- Tests/golden: existing Compose instrumentation recreation seam；JVM protocol/raw/CSV/signal golden suites remain unchanged。
- Android target: `MainActivity.kt` `BleHome` root scroll container；`MainActivitySystemTest.kt` `performScrollTo` landing-surface assertions。
- Non-goals: changing CUP protocol, raw/CSV/session schema, analysis algorithm, FGS/GATT ownership, TalkBack runtime claims, screenshot/performance evidence, SAF provider execution or real-device validation。

### 实现事实

- `BleHome` now uses a remembered `verticalScroll` state on the root content column. The existing fixed-height session list remains bounded, while the complete page can reach capture, Sessions and detail content under small-screen or larger-font layout pressure.
- `MainActivitySystemTest` now asserts `CUPCollector`, scan and start-capture entry points, then calls `performScrollTo()` before checking the Sessions heading and uninstall/ZIP disclosure both before and after `ActivityScenario.recreate()`.
- No raw, CSV, metadata, algorithm, BLE, FGS or session ownership behavior changed. User-owned `AGENTS.md`, the pre-existing brief edit and untracked `.idea/` remain outside this round’s commit.

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin --no-daemon` → `BUILD SUCCESSFUL`。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test lintRelease assembleRelease assembleDebugAndroidTest --no-daemon --console=plain` → `BUILD SUCCESSFUL`；99 JVM tests、0 failures，release lint 0 errors，R8/resource shrinking、release APK 和 instrumentation APK 编译通过。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:verifyReleasePrivacy --no-configuration-cache --no-daemon --console=plain` → `BUILD SUCCESSFUL`；REL-005 privacy、REL-002 transport、REL-003/004 lifecycle 和 REL-006/007 merged-manifest/API contracts 均保持通过。
- `git diff --check` → passed。
- Hardware validation: pending；本轮不运行 emulator/device instrumentation、动态字号/TalkBack、系统重建、后台/锁屏、SAF provider、BLE 或真机测试。

### 风险与决策变化

- The scroll container and test scroll-to prove source/test wiring for content reachability, not runtime font-scale layout, screen-reader announcements, OEM window behavior or actual Activity recreation. Those Phase 4/5 runtime gates remain open.
- `D-001`、`D-002`、`D-003`、`D-004`、`D-005`、`D-006`、`D-007`、`D-008` remain open；本轮没有改变任何 schema/profile/algorithm version。

### 下一轮

在 emulator/device 可用时执行本轮 instrumentation，重点覆盖 Activity recreation、动态字号/TalkBack、SAF/FileProvider provider 和 FGS bind/rebind；本地继续 M5 API/厂商/发布门禁，但不把编译结果替代 runtime/hardware evidence。

## 2026-08-02 · M4 · Deterministic SAF picker cancellation

### 本轮目标

补齐 Phase 4 Detail/SAF acceptance seam 的用户取消边界：用户取消 `ACTION_CREATE_DOCUMENT` 时始终得到明确反馈；正在进行的导出/恢复取消必须停止工作而不把取消异常伪装成失败，也不能让已取消操作覆盖新会话/新操作状态。

### 需求/参考/Android 目标

- Requirement: `UI-009`、`CAP-010`、`REL-003`/`REL-004`；Phase 3 §6.3、Phase 4 §7.1/§7.2 的 SAF progress/cancel 和生命周期状态边界。
- Primary source: `docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md` UI-009/CAP-010、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §6.3/§7.2、`docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` export/cancellation boundary、`MainActivity.kt`/`SessionsViewModel.kt`。
- Tests/golden: `CaptureSessionExportServiceTest` cancellation/destination protection；新增 `SessionsPresentationTest.exportPickerCancellationKeepsActionSpecificFeedback`。
- Android target: `MainActivity` `CreateDocument` result callback、`SessionsViewModel.cancelExportPicker/cancelAction`、export/recovery coroutine cancellation handling。
- Non-goals: changing ZIP/raw/CSV/session formats, shared-storage policy, provider-specific partial-document deletion, real SAF provider execution, FGS/BLE ownership or emulator/device validation。

### 实现事实

- `CreateDocument` returning `null` now calls `cancelExportPicker()`, which publishes a stable `EXPORT` action with `导出已取消` even when the picker was cancelled before an export coroutine started.
- Running export/recovery cancellation preserves the action kind and does not let a cancelled coroutine’s broad `Exception` handler write a stale error. `CancellationException` exits the coroutine without a state write; explicit user cancellation owns the visible state.
- Added a pure JVM assertion for export/recovery cancellation feedback. Raw, CSV, metadata, ZIP entry names, recovery source immutability and service/GATT ownership remain unchanged.

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests com.example.ppgcollector_android.SessionsPresentationTest :app:compileDebugKotlin --no-daemon --console=plain` → `BUILD SUCCESSFUL`。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test lintRelease assembleRelease assembleDebugAndroidTest --no-daemon --console=plain` → `BUILD SUCCESSFUL`；JVM suite 通过，release lint 0 errors，R8/resource shrinking、release APK 和 instrumentation APK 编译通过。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:verifyReleasePrivacy --no-configuration-cache --no-daemon --console=plain` → `BUILD SUCCESSFUL`；REL-005 privacy、REL-002 transport、REL-003/004 lifecycle 和 REL-006/007 merged-manifest/API contracts 均保持通过。
- `git diff --check` → passed。
- Hardware validation: pending；本轮不运行真实 DocumentsProvider、SAF partial-document cleanup、Activity recreation、FGS/BLE、emulator 或真机测试。

### 风险与决策变化

- Local state evidence now distinguishes picker cancellation from provider/stream failure, but only a real DocumentsProvider can prove URI permission revocation, partial-document behavior and provider cleanup. Those gates remain open.
- `D-001`、`D-002`、`D-003`、`D-004`、`D-005`、`D-006`、`D-007`、`D-008` remain open；本轮没有改变 schema/profile/algorithm version。

### 下一轮

继续 M4 本地 acceptance audit 的 permission-return/重复 start-stop 状态覆盖，或在 emulator/device 可用时运行 SAF/FileProvider/Activity recreation 门禁；不把 JVM cancellation 结果替代真实 provider 证据。

## 2026-08-02 · M2 · Merge incremental BLE permission callbacks

### 本轮目标

修复 `BLE-001` 权限恢复边界：Android `RequestMultiplePermissions` 回调只包含本次请求项时，不能清除此前已授予的 nearby-device/location 权限；显式 `false` 必须仍能撤销并保持可恢复的 `DENIED` 状态。

### 需求/参考/Android 目标

- Requirement: `BLE-001`、`BLE-003`、`REL-006`；Phase 2 §5.1/§5.2、Phase 5 §8.1 的 permission denial/revocation/recovery matrix。
- Primary source: `docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md` BLE-001/REL-006、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §5.1/§5.2、`docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` §3/§9、`BlePermissionResultSeam`/`MainActivity` Activity Result callback。
- Tests/golden: `BleCoordinatorTest.permissionResultSeamPreservesEarlierGrantWhenCallbackOnlyContainsMissingPermission` plus existing API 30/31/33 permission and revocation tests。
- Android target: `app/src/main/java/com/example/ppgcollector_android/core/ble/BleCoordinator.kt` `BlePermissionResultSeam.applyResult`。
- Non-goals: changing manifest permissions, `neverForLocation`, scan/GATT/raw/CSV/session behavior, system permission dialogs, API/OEM runtime or real-device validation。

### 实现事实

- `applyResult` snapshots `previouslyGranted`; a missing map entry preserves that permission, while an explicit `false` removes it. The gate therefore supports a partial SCAN grant followed by a CONNECT-only callback and still honors revocation.
- Added JVM coverage for API 33 partial SCAN→CONNECT recovery; existing full-map grants and explicit API 30 revocation remain covered.
- No protocol, raw, CSV, algorithm, session or FGS contract changed. This is a reducer correctness fix, not evidence that Android system permission dialogs or vendor behavior are validated.

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests com.example.ppgcollector_android.core.ble.BleCoordinatorTest :app:compileDebugKotlin --no-daemon --console=plain` → `BUILD SUCCESSFUL`。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test lintRelease assembleRelease assembleDebugAndroidTest --no-daemon --console=plain` → `BUILD SUCCESSFUL`；JVM suite 通过，release lint 0 errors，R8/resource shrinking、release APK 和 instrumentation APK 编译通过。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:verifyReleasePrivacy --no-configuration-cache --no-daemon --console=plain` → `BUILD SUCCESSFUL`；REL-005 privacy、REL-002 transport、REL-003/004 lifecycle 和 REL-006/007 merged-manifest/API contracts 均保持通过。
- `git diff --check` → passed。
- Hardware validation: pending；本轮不运行 runtime permission dialog、API/OEM matrix、BLE stack、emulator 或真机测试。

### 风险与决策变化

- The reducer now matches partial callback semantics, but Android framework grant maps, “don’t ask again” settings return, adapter state timing and OEM permission behavior remain runtime evidence requirements.
- `D-001`、`D-002`、`D-003`、`D-004`、`D-005`、`D-006`、`D-007`、`D-008` remain open；本轮没有改变 schema/profile/algorithm version。

### 下一轮

继续本地 M5 release/API contract 与 M4 acceptance seam，或在 emulator/device 可用时运行 BLE permission/API/FGS/Activity recreation 矩阵；不以 JVM reducer 结果关闭真实设备门禁。

## 2026-08-02 · M4 · Preserve Sessions coroutine cancellation

### 本轮目标

补齐 Sessions 页的生命周期取消边界：refresh 或只读 inspection 被新请求、选择变化或 Activity/ViewModel teardown 取消时，旧协程不能把 `CancellationException` 作为普通 I/O 错误写回 StateFlow，也不能污染新会话的反馈。

### 需求/参考/Android 目标

- Requirement: `UI-009`、`CAP-009`、`REL-003`；Phase 4 §7.1/§7.2 的 filesystem-backed catalog/detail、inspection cancel 和 lifecycle/recreation 行为。
- Primary source: `docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md` UI-009/CAP-009、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §7.1/§7.2、`docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` filesystem/lifecycle boundary、`SessionsViewModel.kt`。
- Tests/golden: existing Sessions repository/inspection/replay tests；新增 `SessionsPresentationTest.sessionWorkCancellationIsNotConvertedToPresentationError`。
- Android target: `SessionsViewModel.kt` `runCatchingCancellable`、refresh/inspection jobs and existing export/recovery cancellation handlers。
- Non-goals: changing session files, inspection findings, raw replay, SAF/ZIP format, BLE/FGS ownership, actual Activity recreation or emulator/device execution。

### 实现事实

- Added a cancellable result wrapper that rethrows `CancellationException` to the coroutine boundary and only converts ordinary exceptions to `Result.failure`.
- Sessions refresh and inspection now use the wrapper; export/recovery already use explicit cancellation handling, so cancelled work exits without publishing stale errors while user-driven cancellation publishes its own action feedback.
- Added a pure JVM regression proving cancellation is not converted into a presentation result. Filesystem, raw, CSV, metadata and session ownership contracts are unchanged.

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests com.example.ppgcollector_android.SessionsPresentationTest :app:compileDebugKotlin --no-daemon --console=plain` → `BUILD SUCCESSFUL`。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test lintRelease assembleRelease assembleDebugAndroidTest --no-daemon --console=plain` → `BUILD SUCCESSFUL`；JVM suite 通过，release lint 0 errors，R8/resource shrinking、release APK 和 instrumentation APK 编译通过。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:verifyReleasePrivacy --no-configuration-cache --no-daemon --console=plain` → `BUILD SUCCESSFUL`；REL-005 privacy、REL-002 transport、REL-003/004 lifecycle 和 REL-006/007 merged-manifest/API contracts 均保持通过。
- `git diff --check` → passed。
- Hardware validation: pending；本轮不运行 Activity recreation、后台/锁屏、SAF provider、FGS/BLE、emulator 或真机测试。

### 风险与决策变化

- Local cancellation semantics prevent stale state writes, but blocking filesystem behavior under process death, Android lifecycle dispatch and provider/runtime behavior still require instrumentation/system evidence.
- `D-001`、`D-002`、`D-003`、`D-004`、`D-005`、`D-006`、`D-007`、`D-008` remain open；本轮没有改变 schema/profile/algorithm version。

### 下一轮

继续本地 M5 release/API contract 与 M4 acceptance seam；在 emulator/device 可用时运行 Activity recreation、TalkBack/dynamic font、SAF/FileProvider 和 FGS bind/rebind 门禁。

## 2026-08-02 · M1 · Randomized CUP stream fragmentation evidence

### 本轮目标

完成 Phase 1 protocol 方案中明确的 property-style 验收补口：同一多帧字节流在固定种子随机 notification 分片、24 帧粘连和每帧之间噪声下，必须产生相同 frame sequence，且 discarded/pending 诊断有界且可审计。

### 需求/参考/Android 目标

- Requirement: `PROTO-002`、`PROTO-004`；Phase 1 §4.1/§4.4 的随机拆包、噪声 resync 和 pending buffer 门禁。
- Primary source: `docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md` PROTO-002/PROTO-004、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §4.1、`docs/05_SOURCE_REFERENCE_INDEX.md` `CUPBatchStreamDecoder.swift`/protocol tests。
- Tests/golden: `golden_seq42.hex`、existing fixed fragment/noise/bounded-pending tests；新增 `CupBatchProtocolTest.seededRandomFragmentsAndInterFrameNoisePreserveTheWholeStream`。
- Android target: `app/src/test/java/com/example/ppgcollector_android/core/protocol/CupBatchProtocolTest.kt` against production `CupBatchStreamDecoder`。
- Non-goals: changing the draft 408-byte profile, real firmware capture, raw/CSV/session files, sequence semantics, Android BLE transport or emulator/device validation。

### 实现事实

- The test builds 24 encoded frames with deterministic inter-frame noise, feeds the complete stream through a seeded variable chunk-size generator, and asserts exact sequence order, 24 decoded frames, discarded-noise accounting and zero pending bytes.
- Existing all-fixed-fragment coverage remains, so the new case exercises boundaries not enumerated by the fixed-size loop without changing the decoder or golden wire format.
- This strengthens draft protocol evidence only; real CUP frame shape and sample-count ambiguity remain `draft` under `D-001`.

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests com.example.ppgcollector_android.core.protocol.CupBatchProtocolTest :app:compileDebugKotlin --no-daemon --console=plain` → `BUILD SUCCESSFUL`。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test lintRelease assembleRelease assembleDebugAndroidTest --no-daemon --console=plain` → `BUILD SUCCESSFUL`；JVM suite 通过，release lint 0 errors，R8/resource shrinking、release APK 和 instrumentation APK 编译通过。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:verifyReleasePrivacy --no-configuration-cache --no-daemon --console=plain` → `BUILD SUCCESSFUL`；REL-005 privacy、REL-002 transport、REL-003/004 lifecycle 和 REL-006/007 merged-manifest/API contracts 均保持通过。
- `git diff --check` → passed。
- Hardware validation: pending；本轮不运行真实 CUP、BLE notification、emulator 或真机测试。

### 风险与决策变化

- Randomized JVM chunking reduces decoder regression risk but cannot authenticate the draft wire profile, actual MTU/notification behavior or firmware sample ordering; `D-001` remains open.
- `D-001`、`D-002`、`D-003`、`D-004`、`D-005`、`D-006`、`D-007`、`D-008` remain open；本轮没有改变 schema/profile/algorithm version。

### 下一轮

继续本地 M5 release/API contract 与 M4 acceptance seam；在真实 CUP 抓包可用时对同一随机分片 harness 进行逐样本 wire/sequence 对照，不把 synthetic stream 结果当作生产协议认证。

## 2026-08-02 · M2 · Bound BLE scanning and fix first-device Compose crash

### 本轮目标

修复用户在 Android Studio/真机联调中发现的两条 UI-001 扫描路径：无 CUP 设备时扫描不得无限运行；首个 CUP 广播进入列表时 app 不得因 Compose 布局异常闪退。同时处理本轮 `compileDebugKotlin` 暴露的 Android BLE deprecated API warning，不改变 CUP 协议、raw、CSV、算法或录制所有权。

### 需求/参考/Android 目标

- Requirement: `UI-001`、`BLE-001`、`BLE-003`、`REL-002`；Phase 2 §5.1 scanner 的超时/停止/去重/名称前缀/RSSI/adapter 状态及 §5.2 late callback 故障边界。
- Primary source: `docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md` UI-001/BLE-001/BLE-003、`docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md` §3 callback/ordered owner 边界、`docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md` §5.1/§5.2、`docs/05_SOURCE_REFERENCE_INDEX.md` `BLECentralService.swift`/`BLETransport.swift`，以及当前 `BleHome`/`AndroidBleTransport`。
- Tests/golden: pure owner/coordinator scan timeout/failure/retry tests；Compose instrumentation 将真实 `CupDeviceList` 放入可滚动父页面并渲染首个设备；release transport source contract检查 10 s timeout 与 late-result gate。
- Android target: `AndroidBleTransport.kt`、`FakeBleTransport.kt`、`BleGattStateMachine.kt`、`MainActivity.kt`、相关 JVM/instrumentation tests 和 `verifyReleaseBleTransportContract`。
- Non-goals: 更改 draft CUP NUS UUID/profile、主动 control write、raw/CSV/session/算法、自动连接、后台常驻扫描、真实设备协议认证或本轮执行真机测试。

### 实现事实

- 崩溃根因是 M4 为 `BleHome` 增加根 `verticalScroll` 后，设备分支仍条件性创建无高度约束的纵向 `LazyColumn`；无设备时该分支不存在，首个 CUP 设备出现时才触发 Compose 的无限高度测量异常。设备列表现改为非滚动 `Column`，由页面根容器统一滚动，并保留稳定 device key/连接 gate。
- Android scanner 默认在 10 s 后调用同一幂等停止路径并发布 typed `ScanStopped(TIMEOUT)`；owner/coordinator 把 `isScanning` 置为 false、重新开放扫描按钮，无设备时显示明确超时反馈，显式重试会清除旧错误。
- `onScanFailed` 现发布 platform failure，而不是错误地把仍可用的 adapter 改成 `UNKNOWN`；停止/超时后已排队的 scan result 会被忽略。扫描回调先在权限异常边界内复制 device ID/name/RSSI/connectable 字段，避免 `SecurityException` 穿透系统 callback。
- API <33 characteristic value 和 API 26-compatible `connectGatt` 进入明确 compatibility wrapper/suppression；`compileDebugKotlin` 不再报告用户日志中的两条 deprecated warning。`libandroidx.graphics.path.so` 已确认是 AndroidX 提供且本身已 stripped 的多 ABI ELF，Gradle 原样打包提示不是 app BLE 崩溃或构建失败。
- 未改变 protocol/raw/CSV/session/metric/FGS 数据契约。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --no-daemon` → `BUILD SUCCESSFUL`，无 Kotlin deprecated warning。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test lintRelease assembleRelease assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy --no-configuration-cache --no-daemon` → `BUILD SUCCESSFUL`；106 JVM tests/0 failures、release lint 0 errors、R8/resource shrinking、debug/release/androidTest APK 和 REL-002/003/004/005/006/007 静态契约通过。
- `verifyReleaseBleTransportContract` 报告 `scan_timeout_ms=10000`、`late_scan_result=ignored_after_stop`、`status=passed`。
- Hardware validation: pending；按项目约定本轮不运行修复后的无设备 10 s 超时、首个 CUP 广播渲染、连接/订阅、API/厂商或真机复验。

### 风险与决策变化

- 用户报告与条件分支结构可确定解释原闪退路径，但修复效果仍需在原设备/字体/窗口环境复验；instrumentation 目前只有编译证据，不能替代 runtime。
- 10 s 是当前 Android scanner bring-up policy，不改变固件/profile；如产品后续要求其他扫描时长，应配置化并重新做功耗/厂商矩阵。
- `D-001`、`D-002`、`D-003`、`D-004`、`D-005`、`D-006`、`D-007`、`D-008` remain open；用户修改的 `AGENTS.md`、Gradle wrapper 和 `.idea/` 不纳入本轮提交。

### 下一轮

在后续明确的真机验收轮复验：无设备 10 s 停止并可重试、首个 CUP 广播稳定渲染、连接/CCCD/receiving；随后继续 M5 API/厂商/runtime matrix，不把本轮 fake/compile 证据表述为硬件通过。

## 2026-08-02 · M4 · Restore live waveform, freshness gate and connected UI

### 本轮目标

修复用户真机联调暴露的 Phase 4 实时页问题：指标正常但 RED/IR 波形不可见；输入合法录制名后开始按钮仍被错误 freshness 状态永久禁用；连接完成后设备行必须改为明确的断开操作；同时按当前 iOS Swift 页面提升主要信息层级和视觉一致性，不改变协议、raw、CSV 或算法语义。

### 需求/参考/Android 目标

- Requirement: `UI-001`、`UI-002`、`UI-003`、`UI-005`、`UI-006`、`UI-007`、`BLE-006`；Phase 4 §7.1/§7.2 的 realtime/capture/accessibility gate。
- Primary source: `DeviceListView.swift` 的 `statusHeader`/`DeviceRow`/capture/metrics sections，`CUPDualWaveformPreview.swift` 的 `WaveformPanel.makePlot`，`CaptureSessionController.swift` 的 `canStart`，以及 docs/02/03/04/05。
- Tests/golden: `BleCoordinatorTest` valid frame/fresh/stale/capture gate integration、`BlePreviewRuntimeTest` accepted-frame generation callback、`LiveWaveformRuntimeTest` ordered extrema path、`CupDeviceListTest` first-device/disconnect state、`MainActivitySystemTest` persistent RED/IR surfaces。
- Android target: `BlePreviewRuntime`/`BleCoordinator`/`CupBleGattStateMachine` freshness feedback，`PpgCollectorApplication` monotonic clock/main dispatcher，`LiveWaveformPlotMath`，`MainActivity`/theme/manifest/resources 和 Compose instrumentation seams。
- Non-goals: changing CUP draft frame/UUID/sequence semantics, raw/CSV/session formats, HR/RR/SQI algorithms, adding SpO2/BP claims, real-device execution, dependency major-version upgrades or FGS ownership changes。

### 实现事实

- 波形不可见根因是 Canvas 将每个 min/max bucket 画成竖线；早期/常见的一样本 bucket 满足 `minimum == maximum`，因此全部是零长度线。现移植 Swift `makePlot`：窗口较小时按时间顺序连点，较大时每 bin 保留最小/最大值并按其原始 offset 顺序组成连续 `Path`；RED/IR 独立动态 Y、圆角线段、网格和空窗口占位保持可见，ring/5 Hz/800 样本契约不变。
- 录制 gate 根因是 app-scope preview 解码了合法帧并计算指标，却从未调用 BLE owner 的 `markValidFrame`；因此 UI 可看到 HR/RR/SQI，freshness 仍为 `WAITING/STALE`。preview 现仅在 accepted frame 后携带 connection generation 回调 coordinator；coordinator 在 owner dispatcher 上拒绝旧 generation、推进 `FRESH` 并定时刷新为 `STALE`。Application 改用 `SystemClock.elapsedRealtimeNanos`，避免 production clock 默认为 0。
- 设备列表按 Swift `DeviceRow` 区分当前设备：建链时显示进度，`Subscribed/Receiving` 时使用红色“断开”，其他设备在活动连接期间禁用；仍保持非滚动子列表，避免恢复首设备嵌套滚动崩溃。
- 首页改为稳定蓝/绿/红品牌色、grouped background、白色分组卡片、freshness pill、空态双轨波形、2×2 metrics 和全宽 capture action；录制区明确展示唯一 gate 原因。RR 继续标为 Red/IR 诊断比值、SQI 为暂定评分，SpO2/BP 仍 unavailable。
- 清理了 lint 可处理项：受 API policy 保护的通知权限使用稳定 wire string、冗余 Activity label、minSdk 26 下多余 O 判断和未使用模板颜色。剩余 9 条 lint warning 全是已有依赖更新提示，未在 UI 修复轮冒险升级。
- 按用户明确要求，本次提交同时纳入用户已有的 `AGENTS.md`、Gradle wrapper 9.6.1 和非易失 `.idea` project settings；`.idea/workspace.xml`/`caches` 仍按 ignore 保持本地。协议/raw/CSV/算法文件未改变。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests 'com.example.ppgcollector_android.core.ble.BleCoordinatorTest' --tests 'com.example.ppgcollector_android.core.ble.BlePreviewRuntimeTest' --tests 'com.example.ppgcollector_android.core.signal.LiveWaveformRuntimeTest' --no-configuration-cache --no-daemon` → `BUILD SUCCESSFUL`。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test lintDebug assembleDebug assembleDebugAndroidTest --no-configuration-cache --no-daemon` → `BUILD SUCCESSFUL`；JVM 109 tests/0 failures，debug/androidTest APK 编译通过。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew lintDebug assembleDebug --no-configuration-cache --no-daemon` → `BUILD SUCCESSFUL`；lint 从 20 warnings 降为 0 errors/9 dependency update notices，BLE Kotlin deprecated warnings 未再出现。
- `git diff --check -- . ':(exclude)gradlew.bat'` → passed；完整检查仅报告用户生成的 Windows wrapper CRLF 行尾，不改写该跨平台脚本。
- Hardware validation: pending；按约定本轮不运行真实 CUP 波形、录制开始/停止、连接/断开、动态字号/TalkBack、后台/锁屏、emulator 或真机测试。

### 风险与决策变化

- JVM 与 Compose 编译证据证明合法帧→freshness→gate 和折线采样数学，但真实设备振幅、刷新观感、厂商 Canvas/GATT 时序及按钮交互仍需原设备复验；不能以指标正常替代 raw/protocol wire 认证。
- Gradle/IDE 配置随用户要求纳入版本控制；依赖版本提示暂不升级，避免把工具链迁移混入 M4 UI correctness fix。
- `D-001`、`D-002`、`D-003`、`D-004`、`D-005`、`D-006`、`D-007`、`D-008` remain open；本轮没有改变 schema/profile/algorithm version。

### 下一轮

在后续明确的真机验收轮复验 RED/IR 连续波形、连接后红色断开、合法命名 + fresh stream 启用录制、停止后预览连续和 10 s 无设备扫描；随后继续 M5 API/厂商/runtime matrix，不把本轮本地证据表述为硬件通过。

## 2026-08-02 · M6 · Versioned offline analysis and independent Sessions workbench

### 本轮目标

把已保存会话从实时录制页面拆成独立的 Swift 风格信息架构，修复真实采集文件被误报为 raw structural error 的问题，提升 replay 控件并支持指尖平移/缩放；在此基础上完成 Phase 6 的 raw replay、版本化离线 segmented-pulse、平均周期/CI、频谱、历史/取消和双会话对比本地切片。

### 需求/参考/Android 目标

- Requirement: `UI-009`、`UI-010`、`CAP-009`、`CAP-011`、`SIG-007`、`SIG-008`；Phase 4 Sessions/detail/replay 与 Phase 6 §9.1–§9.3。
- Primary source: Swift `SessionsListView.swift`、`SessionDetailView.swift`、`SessionReplayView.swift`、`CaptureSessionInspectionService.swift`、`CUPRawReplayEngine.swift`、`CaptureSessionAnalysisService.swift`；Python `segmented_pulse.py`、`offline.py:_average_waveform`、`analysis_gui.py`；docs/02/03/04/05/06。
- Tests/golden: Python `segmented_pulse` 固定 synthetic 字段、SciPy `sosfiltfilt` 固定输出、Swift raw replay/inspection 不修改源文件契约，以及 JVM temp-directory immutable/cancel/history/gesture tests。
- Android target: `SessionsScreens.kt`、`SessionsViewModel.kt`、`MainActivity.kt`、`OfflinePpgAnalysis.kt`、`CaptureSessionOfflineAnalysis.kt`、`CupRawReplay.kt`、`CaptureSessionInspection.kt`、`ReplayWaveformViewport.kt` 和对应 JVM/instrumentation tests。
- Non-goals: 不改变 CUP draft wire、CUPRAW1/CSV/session schema 或 live causal 算法；不从 CSV 反推 raw；不增加无数据契约的 IMU；不宣称 SpO2/BP；不在本轮运行 emulator/真机或把短 app-scope coroutine 宣称为跨进程后台任务。

### 实现事实

- 解压并只读分析用户提供的 `CollectedData/test1.zip`、`test2.zip`。两份 CUPRAW1 的 notification record 都完整：test1 在首个可解 frame 前有 28 个前导协议字节，test1/test2 在录制停止时分别保留 220/60 个尚未凑满下一 408-byte frame 的 pending bytes。旧逻辑把 decoder 的全部 discarded/pending 一律作为 structural ERROR，因而误报；现将首帧前对齐和停止边界 suffix 分别记录为 `raw-alignment-prefix`/`raw-frame-suffix` warning，只有首个 frame 之后的丢弃、invalid frame 或 raw record 截尾仍为 structural ERROR。inspection/replay 始终只读，不修改源 raw/CSV/session。
- `MainActivity` 增加 Live、Saved Sessions、Session Detail、Compare 独立页面路由和 back navigation。会话列表不再嵌在实时录制处理页；详情按 iOS inset-grouped 意图整理 summary、version/source、integrity、replay、analysis、export/recovery 信息，warning/error 使用不同视觉层级。
- Replay 的 RED/IR 使用同一 immutable viewport；`detectTransformGestures` 将单指拖动、双指缩放和焦点位移归约到纯 Kotlin reducer，并提供缩小/放大/适配按钮。Canvas 继续使用 bounded replay samples，不复制或改写原始文件。
- 新增 `OfflinePpgAnalyzer`：SciPy-compatible odd padding/SOS initial condition 的 zero-phase bandpass；settling/transition/break guards；8 s window/2 s hop；RED/IR 两通道与正负极性评估；窗口拒绝原因；dominant BPM cluster/weighted median；全局 accepted peaks、频谱、200 点平均周期/样本标准差/95% CI 和 8 s preview。gap 不跨段生成 peak/cycle，分析 profile 与 live causal profile 分离。
- 新增 `CaptureSessionOfflineAnalysisService`，只从 production `CupRawReplayEngine` 流式接收 accepted samples，使用 sequence gap 形成 break indices，计算 raw SHA-256，并写入 `analysis/<timestamp>_<profile>_<uuid>.json`。`ppgcollector_analysis_v1` 产物包含 source session/raw hash、analysis/algorithm/preprocess/profile version、started/ended、input、warnings、metrics、segments/windows/peaks/spectrum/cycle/preview；先写隐藏临时文件再原子移动且不覆盖，取消不会留下冒充 complete 的 JSON。accepted samples 上限 1,500,000，analysis JSON 读取上限 8 MiB。
- `SessionsViewModel` 增加 analysis task/progress/cancel/restart/history state，磁盘刷新会合并当前 task/artifact，避免并发 refresh 丢失刚完成结果。详情提供 Overview/Workbench/History，Workbench 呈现 Diagnostics/PPG/Spectrum/Cycle；Compare 可选两个会话，显示数值差异以及 stacked/full/unified normalized cycles，并明确 IMU unsupported。
- 分析版本固定为 `analysis_profile=ppg-offline-segmented-0.1`、`algorithm_version=segmented-pulse-parity-0.3`、`preprocess_profile=scipy-sosfiltfilt-parity-0.1`；每次运行生成新文件。`CaptureSessionMetadata` 的内部 JSON AST/parser/writer 仅放宽模块可复用性，没有改变 session wire schema。
- 用户采集 ZIP/raw 保持未跟踪且不纳入 APK/commit；运行 Python 参考时产生的 `reference_sources/.../__pycache__` 临时目录已精确删除，参考快照源文件没有修改。

### 验证

- Python `segmented_pulse.py` 与 Kotlin 对同一 40 s fixed synthetic 输入的字段级结果一致：stable segments `2.00–13.25 s`、`31.52–39.99 s`，stable ratio `0.4935`，4/4 windows accepted，RED/negative，22 peaks，HR `72.28915662650601 bpm`，spectral `75 bpm`，confidence `0.5945874257168716`，SNR `1.463671953699202 dB`，RR MAD `4.44e-16 s`。
- Kotlin zero-phase SOS 对 SciPy fixed reference 的开头/中段输出在 `1e-8` 内一致；平均周期测试覆盖异常反相周期剔除、200 点 mean/std/CI 和 segment/gap 边界。Python reference 的低相关 fallback/constant-cycle 语义也已对齐。
- production replay + Kotlin analyzer 对解压实际会话只读运行：test1 为 5,500 accepted samples、24 windows/23 accepted、57 peaks、HR `65.2174 bpm`；test2 为 3,350 samples、13/13 windows、36 peaks、HR `68.1818 bpm`。同一 raw 经 Python reference segmented-pulse 的 summary 字段一致；原 ZIP/raw SHA 和内容未变化。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test lintDebug assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy --no-configuration-cache --no-daemon` → `BUILD SUCCESSFUL`；122 JVM tests、0 failures/0 errors，debug lint 0 errors/9 dependency update warnings，debug/release/androidTest APK 编译通过，REL-005 确认无 production logging API 且 APK 无测试/session fixture entries，既有 REL-002/003/004/006/007 contract 继续通过。
- `git diff --check` → passed；`CollectedData/` 已由根 `.gitignore` 明确排除，是仅保留在本机的用户采集数据，不进入 staged diff。
- Hardware validation: pending；按项目约定本轮不运行 Saved Sessions 页面、replay 指尖缩放、分析进度/取消/历史/compare、Activity recreation、后台/锁屏、SAF、BLE 或真机 runtime 门禁。

### 风险与决策变化

- `D-014` 保持 Open/Assumption：当前分析按 memory-bounded 短任务在 ViewModel app scope 执行；Activity recreation 可观察同一 ViewModel task，但进程终止不会自动续跑。若目标会话/设备要求跨进程或长时分析，必须另建 ADR 并迁移到 WorkManager 或用户可见 FGS。
- 本地 Python/Kotlin 对等证明的是当前参考算法与两份实际 draft raw 的可复现性，不认证真实 CUP production wire；`D-001` 继续开放。两份会话停止边界 warning 不代表忽略真实结构损坏，首帧后 discard/invalid/raw truncation 仍阻断 structural clean。
- Compose 和 instrumentation 只有编译/静态语义证据；多点触控、较大字体、TalkBack、GPU 性能和进程/后台行为仍需 emulator/device。M5 的 API/厂商/2 h/签名/正式隐私策略也未因此关闭。

### 下一轮

优先执行后续明确安排的 emulator/真机 M4/M6 UI runtime 验收与 M5 API/厂商/后台/长稳矩阵；若 `D-014` 要求跨进程续跑，先形成 execution-policy ADR 再实现 WorkManager/用户可见 FGS。完成这些门禁或有新证据后再进入 M7，不把专家/临床扩展提前混入 M6。

## 2026-08-02 · M6 · Complete-signal landscape analysis workbench

### 本轮目标

把 Replay 和工作台的 PPG 从序列化的代表窗口/最近 800 点扩展为完整 accepted signal：默认仍可看 8 s，但必须能在整条记录上拖动、缩放、全幅查看 RAW 与 zero-phase RED/IR；进一步移植 Python GUI 的窗口审计和多视图工作台，并提供可全屏横屏使用的自适应布局。实时采集时的平滑因果波形仅做分析规划，不在本轮实现。

### 需求/参考/Android 目标

- Requirement: `UI-009`、`UI-010`、`CAP-009`、`CAP-011`、`SIG-007`、`SIG-008`；Phase 4 replay 与 Phase 6 §9.1–§9.3。
- Primary source: Swift `SessionReplayView.swift`、`SessionDetailView.swift`、`CUPDualWaveformPreview.swift`、`PPGPreprocessor.swift`、`CaptureSessionAnalysisService.swift`；Python `analysis_gui.py`、`offline.py`、`segmented_pulse.py`、`online.py`；docs/02/03/04/05/06。
- Tests/golden: production CUPRAW1 replay/immutable SHA seam、SciPy-compatible zero-phase continuity runs、2 h viewport、bounded visible-range spectrum、ordered extrema plot math。
- Android target: `OfflinePpgAnalysis.kt`、`CaptureSessionOfflineAnalysis.kt`、`ReplayWaveformViewport.kt`、`LiveWaveformRuntime.kt`、`SessionsViewModel.kt`、`SessionsScreens.kt`、`SessionSignalWorkbench.kt`、`MainActivity.kt` 和对应 JVM tests。
- Non-goals: 不改变 CUPRAW1/CSV/session/analysis JSON schema、accepted sample/算法接受语义或 live metrics cadence；不把 zero-phase 用作实时滤波；不移植无数据契约的 IMU；不运行 emulator/真机。

### 实现事实

- `CaptureSessionOfflineAnalysisService.loadSignalTrace` 复用 production raw replay，最多加载 1,500,000 个 accepted samples，并返回完整时间轴、RAW RED/IR、gap 和 replay report。`OfflinePpgAnalyzer.filterFullSignal` 按显式 break、非单调/大于 15 ms 时间间隔和 non-finite 边界切分连续 run，对每个不少于 32 点的 run 分别执行现有 SciPy-compatible forward/backward SOS；不跨 gap，短 run 保持 NaN。该结果只用于显示，不改变稳定段、窗口接受、峰和 metrics，也不序列化进 analysis JSON。
- `SessionsViewModel` 为当前选中会话在 Default dispatcher 上可取消地加载/滤波完整 trace，并用原子 StateFlow merge 与并行 inspection 协作，避免任一结果覆盖另一结果；切换/离开会话会取消旧 job，完整数组只由当前 detail state 持有。
- 会话 Replay 和紧凑 PPG 工作台不再使用 recent/preview 截断数组。默认视窗是起始或最佳接受窗口的 800 点，RAW/ZERO-PHASE 与 RED/IR 共用完整时间轴，支持单指拖动、双指缩放、按钮缩放、8 s 复位和全幅；stable segment、accepted peak 和 continuity break 保持绝对 sample index。`LiveWaveformPlotMath.plotRange` 直接扫描可见源范围并返回绝对 offset，长记录全幅绘制不再分配完整范围副本，同时保持原有保序 extrema 语义。
- 新增全屏横屏工作台：左侧可滚动控制/稳定段/历史，右侧提供 Workbench、PPG windows、Spectrum、Cycle、Diagnostics；支持 selected/RED/IR、RAW/ZERO-PHASE/PEAKS、峰/稳定段可见性、反相、精确窗口聚焦、当前可见范围最多抽取 32 段且每段至多 8 s 的 bounded Welch-style PSD、平均周期/95% CI 和不可变 raw/version/input findings。进入页面请求 sensor-landscape 并隐藏 system bars，退出恢复 unspecified orientation 与 bars；未通过 manifest 固定整个 app 方向。
- 新增 `docs/07_LIVE_FILTERED_WAVEFORM_PLAN.md`，只分析未来 live causal 显示：zero-phase 因依赖未来样本不能实时使用；建议提取 `LivePpgSignalRuntime` 统一当前 metric preprocessor 与 waveform raw ring，同时发布 bounded RAW/causal snapshot，避免第三套滤波状态。规划保留 800/100/5 Hz、gap reset、warm-up、source index/generation 和 raw-first 边界；本轮未修改 live runtime、raw、CSV、指标或 FGS。
- 完整信号显示不改变 `ppgcollector_analysis_v1`、`segmented-pulse-parity-0.3` 或 `scipy-sosfiltfilt-parity-0.1`：算法产物语义未改变，新增的是可取消的内存 display trace 和 bounded visible-range 派生视图。

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test lintDebug assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy --no-configuration-cache --no-daemon` → `BUILD SUCCESSFUL`；127 JVM tests/0 failures/0 errors，debug lint 0 errors/9 dependency update warnings，debug/release/androidTest APK、R8/resource shrink、REL-005 privacy 与既有 REL-002/003/004/006/007 静态契约通过。
- 新增/扩展 JVM 证据：两个 continuity run 的全程滤波分别与独立 zero-phase 结果逐点一致；3,000 点 production raw trace 的原始 SHA 不变；当前范围 1.2 Hz 波形的 PSD 主峰落在 1.25 Hz bin；720,000 点/2 h 记录可精确显示 800 点、平移并复位全幅；单样本视窗安全；range plot 只取指定绝对范围并排除范围外 extrema。
- `git diff --check` 和 production logging/TODO scan 通过；`CollectedData/` 未写回、未打包、未提交。
- Hardware validation: pending；按项目约定本轮不执行 replay 多点触控、横屏沉浸/旋转恢复、长记录 GPU/heap/frame-time、动态字号/TalkBack、Activity recreation、emulator 或真机测试。

### 风险与决策变化

- 完整 trace 受 1,500,000 点上限保护，但当前选中会话同时持有 time、两条 raw 和两条 filtered `DoubleArray`，接近上限时约为 60 MiB 量级；range plot 避免全幅副本，但真实低内存设备仍需测 heap high-water、GC 和横屏帧时间。离开详情会释放引用，分析 JSON 不膨胀。
- Android 强制横屏/沉浸行为在大屏、多窗口和 OEM 上可能被系统忽略或调整；本地编译/lint 不能替代 runtime orientation/insets 验收。
- 实时平滑方案没有落地；当前 Live 页仍显示原有 RAW waveform，离线 zero-phase 不得被描述成实时能力。默认 RAW/CAUSAL、5/10 Hz 显示刷新和因果 trace 是否导出仍是后续产品/性能决策。
- `D-001` 真实 CUP profile、`D-002` API/厂商矩阵和 `D-014` 跨进程分析继续开放；本轮不改变 schema/profile/algorithm version。

### 下一轮

优先在 emulator/真机运行完整 Replay/工作台的拖动缩放、8 s/全幅切换、全屏横屏/insets/旋转恢复和长会话性能；另轮按 `07_LIVE_FILTERED_WAVEFORM_PLAN.md` 先合并纯 Kotlin 因果状态机并做 fixture/gap/2 h 证据，再接 Compose，不能直接把 offline zero-phase 放进实时路径。

## 2026-08-02 · M6 · Unified live causal waveform runtime

### 本轮目标

把采集期间已经为 HR/SQI/R 计算的 causal 0.6–4 Hz RED/IR 同步用于实时可视化，避免另建第三套滤波状态；保持 raw-first、100 Hz accepted samples、800/100 指标 cadence、默认 5 Hz snapshot、gap reset 和数据完整性边界。完成后审计 M0–M7 仍未闭环的代码、运行时证据和外部决策。

### 需求/参考/Android 目标

- Requirement: `UI-002`、`UI-003`、`PROTO-004`、`SIG-001`、`SIG-002`、`SIG-004`；Phase 4 live waveform 与 Phase 5 bounded/long-run gate。
- Primary source: Swift `PPGPreprocessor.swift`、`PPGLiveMetricRuntime.swift`、`CUPDualWaveformPreview.swift`；Python `online.py:OnlineProcessor._push_ppg`/`visible_y_range`；`docs/02/03/04/05/07`。
- Tests/golden: production `PpgPreprocessor`/existing fixtures、single-state exact arrays、gap/index discontinuity/rejected frame、5 Hz no-burst、preview/recording integration、30 min/2 h bounded simulation、waveform scale math/semantics。
- Android target: `LivePpgSignalRuntime.kt`、`LiveWaveformRuntime.kt`、`BlePreviewRuntime.kt`、`CaptureRecordingController.kt`、`MainActivity.kt` 及 JVM/instrumentation seams。
- Non-goals: 不改变 CUP draft wire、CUPRAW1、25 列 CSV、session/analysis schema、HR/SQI/R 算法与 1 Hz cadence；不导出 causal trace；不使用实时 zero-phase、插值或 UI animation；不在本轮运行 emulator/真机。

### 实现事实

- Added `LivePpgSignalRuntime` as the single per-owner ordered PPG state. One RED/IR preprocessor pair and five fixed 800-sample circular arrays now produce raw/causal waveform snapshots and metric requests together. Preview and recording owners no longer run production `LiveWaveformSnapshotScheduler` and `LiveMetricWindowScheduler` in parallel; the legacy classes remain only for focused compatibility tests.
- The runtime preserves 800-sample/100-sample/5 Hz behavior. A delayed poll publishes at most one current snapshot, accepted sample index mismatch or sequence gap atomically resets both preprocessors/rings/warm-up/deadline and advances generation, and rejected duplicate/out-of-order events do not enter state. Waveform/request raw and causal arrays are exact copies of the same ring; 2 h simulation remains bounded.
- `LiveWaveformSnapshot` now carries causal RED/IR, preprocess profile, continuous/warm-up and visible settling counts. Python-style Y scaling draws the complete settling prefix but excludes only that prefix from autoscale once enough stable values exist.
- Live Compose defaults to user-requested `CAUSAL 0.6–4 Hz` with RAW available. It labels `ios_baseline_0.1`, causal/gap-reset/settling semantics, shades settling without removing samples, retains ordered extrema Canvas paths and adds TalkBack detail. Display switching only selects arrays already in the immutable snapshot and never recomputes history.
- raw-first writer ordering, CSV/session values, capture/preview queue ownership, metric cadence/results and offline `scipy-sosfiltfilt-parity-0.1` are unchanged. Recording intentionally starts a clean per-session causal state while app-scope preview retains its own connection state.
- Updated `docs/07_LIVE_FILTERED_WAVEFORM_PLAN.md` from planning to A/B/C implemented and recorded the user's CAUSAL default decision. Added `docs/08_REMAINING_MIGRATION_AUDIT.md`, separating concrete code gaps from hardware/runtime gates and product/release inputs.
- Audit found these highest-priority code gaps: capture service still records `algorithmVersion="unavailable"` and a preprocess profile spelling inconsistent with the runtime; FGS notification/capture UI do not yet expose elapsed/write health; FileProvider staging is not connected to a share chooser; dynamic duration storage budget and CI/benchmark remain missing. `D-014` remains conditional on the chosen cross-process analysis policy.

### 验证

- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests com.example.ppgcollector_android.core.signal.LivePpgSignalRuntimeTest --no-configuration-cache --no-daemon` → `BUILD SUCCESSFUL`；new runtime 4 tests passed。
- `env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test lintDebug assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy --no-configuration-cache --no-daemon` → `BUILD SUCCESSFUL in 2m 9s`；132 JVM tests/0 failures/0 errors/skips，debug lint XML 0 issues，debug/release/androidTest APK、R8/resource shrink、REL-002/003/004/005/006/007 contracts passed。
- Full suite includes 30 min/2 h simulated data path with 800 raw/causal rings, exact source counts, no-burst waveform cadence and existing raw/CSV/metadata/replay alignment/privacy assertions.
- `git diff --check` and production TODO/log scan → passed before commit；`CollectedData/` remains ignored and untouched。
- Hardware validation: pending；按项目约定未运行真实 CUP causal 波形、gap、RAW 切换、录制切换、5 Hz frame/GC、动态字号/TalkBack、后台/锁屏、emulator 或真机测试。

### 风险与决策变化

- 用户本轮明确 Live 默认 CAUSAL，关闭 `docs/07` 的 RAW/CAUSAL default open item；RAW 始终可切回。`D-011` 的 5/10 Hz 用户配置仍开放，默认继续 5 Hz，不能在无 trace 证据时提高。
- Causal display 复用已验证 preprocessor，但真实视觉平滑度、filter settling、Canvas frame time 和 OEM GC 仍需真机；本地 2 h 数据模拟不等于 2 h UI/GATT/power evidence。
- 本轮没有改变 schema/algorithm/profile wire version。`D-001`–`D-014`/`D-016` 的剩余事实与顺序见 `docs/08_REMAINING_MIGRATION_AUDIT.md`。

### 下一轮

优先实施 capture 正式 algorithm/preprocess version 追溯和 service-owned elapsed/write-health notification/UI，随后接通 FileProvider share 与动态空间预算；再进入 emulator/真实 CUP/API/OEM/lifecycle/SAF/accessibility/2 h 验收矩阵。没有新固件/校准/产品证据前不扩张 M7 的 IMU、SpO2/BP 或医疗语义。

## 2026-08-03 · M5 · Produce a fresh Android Studio release artifact

### 本轮目标

按用户要求直接操作 Android Studio，基于当前 `main` 形成 fresh release variant app，并校验 artifact identity、完整性、privacy/API/FGS/BLE contract 与签名状态；不猜测正式包名或生成未经授权的 keystore。

### 需求/参考/Android 目标

- Requirement: `REL-005`、`REL-006`、`REL-007`，Phase 5 §8.3 release package；开放决策 `D-004`。
- Primary source: `app/build.gradle.kts` release optimization/privacy tasks、merged manifest、`docs/04` release checklist、`docs/08` remaining audit。
- Android target: Android Studio Gradle Sync/IDE Terminal、`:app:assembleRelease`、`:app:verifyReleasePrivacy`、release APK/mapping outputs。
- Non-goals: 不修改 applicationId/versionCode/versionName、协议/raw/CSV/session/算法/UI；不创建或导入 signing credential；不安装到设备、不上传、不发布商店。

### 实现事实

- Computer Use opened the existing Android Studio project, accepted the IDE's requested Gradle Sync, and ran the release commands from Android Studio's own Terminal. Because that terminal had no Java runtime on `PATH`, a temporary `/private/tmp/ppgcollector-android-jbr` symlink pointed only to Android Studio's bundled JBR; it is outside the repo and not packaged.
- Ran `:app:assembleRelease :app:verifyReleasePrivacy --rerun-tasks --no-configuration-cache --no-daemon`; all 50 tasks executed rather than reusing the previous artifact. R8/resource shrinking, merged manifest/API, BLE lifecycle, FGS lifecycle and privacy/APK scans passed.
- Fresh artifact: `app/build/outputs/apk/release/app-release-unsigned.apk`; modified `2026-08-03 15:37:21 +0800`; size 1,443,417 bytes; SHA-256 `0a88646270fd1230f1c26f3e19cd6a7feb23195323eb19bdd856442d3cd0d9a7`; ZIP test passed with no compressed-data errors. R8 mapping outputs remain under `app/build/outputs/mapping/release/`.
- `aapt dump badging` reports package `com.example.ppgcollector_android`, `versionCode=1`, `versionName=1.0`, minSdk 26, target/compileSdk 37 and label `PPGCollector_Android`. These remain explicit placeholder/release-decision inputs.
- `apksigner verify --verbose` returns `DOES NOT VERIFY`/missing manifest signature because no signing config or keystore exists. The artifact is an optimized unsigned release variant, not a formally signed install/store artifact. Generating a keystore would create a security credential and also needs D-004 owner/package/distribution decisions, so it was not inferred from the request.
- The existing AndroidX `libandroidx.graphics.path.so` strip warning reappeared; Gradle packages the prebuilt library as-is and the build succeeds. No project source or user data changed.

### 验证

- Android Studio IDE Terminal: `env JAVA_HOME=/private/tmp/ppgcollector-android-jbr ./gradlew :app:assembleRelease :app:verifyReleasePrivacy --rerun-tasks --no-configuration-cache --no-daemon` → `BUILD SUCCESSFUL in 3m 51s`、50 actionable tasks executed。
- REL-002 BLE、REL-003/004 lifecycle、REL-005 privacy/APK、REL-006/007 merged manifest/API contracts → passed。
- `aapt dump badging`、`stat`、`shasum -a 256`、`unzip -t` → package/version/SDK、size/time/hash and ZIP integrity confirmed。
- `apksigner verify --verbose` → expected unsigned failure (`DOES NOT VERIFY`)；recorded as D-004 blocker, not a test pass。
- The Mac locked after the artifact build, so Computer Use could not continue in Android Studio. A separate fresh `lintRelease` attempt was not run because the required Gradle-cache escalation channel disconnected; the previously recorded release lint evidence remains historical, not fresh evidence for this artifact.
- Hardware validation: pending；本轮未安装 APK、运行 emulator/真机、执行 BLE/FGS/SAF 或 2 h runtime。

### 风险与决策变化

- D-004 remains Open/Block: final package/app name, signing owner, keystore custody and Play/enterprise distribution are unknown. The unsigned APK must not be represented as ready for external distribution.
- The build output is ignored and not committed; its checksum in status provides local traceability, while reproducible source remains Git truth. A clean/build can replace the local artifact.
- No schema/profile/algorithm/product behavior changed.

### 下一轮

产品/发布 owner 提供 final applicationId/app name/versionCode/versionName、distribution target and approved keystore custody后，配置不入库的 signing inputs，通过 Android Studio Generate Signed App Bundle/APK 生成正式签名 artifact，并执行 `apksigner verify`、fresh release lint、安装/升级/回滚及 API/设备矩阵。

## 2026-08-03 · M2 · Support NUS and FFF0 CUP BLE profiles

### 本轮目标

定位新设备 `CUP_FEAE89AB24A9` 可扫描但连接报“未提供 CUP NUS 服务”的原因，并在不破坏既有硬件、不猜测未知控制命令和 wire protocol 的前提下接入其 FFF0/FFF1/FFF2 GATT profile。

### 需求/参考/Android 目标

- Requirement: `BLE-002`、`BLE-003`、`BLE-005`、`CAP-005`；开放决策 `D-001`，风险 `R-001/R-002`。
- Primary source: 既有只读 [CUPDeviceProfile.swift](../reference_sources/ios_current/PPGCollector/Domain/Configuration/CUPDeviceProfile.swift)、用户提供的新硬件 UUID、[ADR-0002](../docs/adr/ADR-0002-cup-ble-profile-registry.md)。
- Tests/golden: `BleCoreTest`、`CupBleGattStateMachineTest`、`BleCoordinatorTest` 及既有完整 JVM/release contracts。
- Android target: `CupBleDeviceProfile` registry、`CupBleGattStateMachine` service selection、`BleCoordinatorSnapshot` 诊断、`CaptureForegroundService` session profile 固化。
- Non-goals: 不修改只读 reference；不假设 FFF1 使用现有 408-byte payload；不向 FFF2 发送未经证实的 START/STOP；不把 fake/JVM 结果称为新硬件真机通过。

### 实现事实

- 根因确认：扫描只按 `CUP` 名称前缀收集候选，而旧 GATT owner 在 service discovery 后只接受 NUS `6E400001-...`，所以新设备能出现在列表但在发现 FFF0 后确定失败；这不是 Android 扫描或配对故障。
- 新增 `cup-fff0-bringup-0.1`（service/notify/write `FFF0/FFF1/FFF2`）并保留 `cup-nus-bringup-0.1`。状态机在发现全部 services 后忽略大小写精确选择一组 profile，只发现/订阅该组特征；未知 service 的错误同时列出期望与实际 UUID。
- 状态快照新增实际 profile、发现 service 和 characteristic diagnostics；连接/断连重置这些 generation-scoped 状态。录制开始不再硬编码 NUS，而是把当前连接实际 profile identifier/service/notify 固化到 session metadata；没有 active profile 时按 protocol error 拒绝开始。
- FFF0 bring-up 只向 FFF1 写 CCCD 以订阅通知，不向 FFF2 写业务命令。只有现有 decoder 接受合法 CUP frame 后 freshness 才变为 fresh；若硬件需要 FFF2 启动或 payload 不同，页面会保持 waiting/stale 且录制 gate 不开放。
- 新增 ADR-0002，并同步 brief、master plan、requirements、architecture、roadmap、source index、risk register 与 remaining audit。旧 NUS 路径与越序/旧 generation 诊断语义保留。

### 验证

- BLE 定向命令：`env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:testDebugUnitTest --tests com.example.ppgcollector_android.core.ble.BleCoreTest --tests com.example.ppgcollector_android.core.ble.CupBleGattStateMachineTest --tests com.example.ppgcollector_android.core.ble.BleCoordinatorTest --no-configuration-cache --no-daemon` → `BUILD SUCCESSFUL`。首次运行捕获 active profile 未选时越序通知计数回归，修复后重跑通过。
- 完整命令：`env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test lintDebug assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy --no-configuration-cache --no-daemon` → `BUILD SUCCESSFUL in 1m 14s`；130 tasks，133 JVM tests/0 failures/0 errors/skips，debug lint 0 errors/9 个依赖版本 warning，debug/release/androidTest APK、R8/resource shrink 与 REL-002/003/004/005/006/007 contracts passed。
- `git diff --check` → passed；用户既有 `.idea/deploymentTargetSelector.xml`、`.idea/misc.xml` 不在本轮修改/提交范围。开始时存在的未跟踪 `app/release/` 在完整 Gradle release 门禁后不再存在；全盘工作区/Trash/temp 搜索未找到同名副本，只确认新的 unsigned APK 位于标准 `app/build/outputs/apk/release/`。该异常必须在交付中披露，不能宣称旧 artifact 已保留。
- Hardware validation: pending；未运行新旧 CUP 真机。新硬件还需记录 FFF1 properties/通知 hex、FFF2 是否需命令及 payload/时序、固件/型号，并确认现有 408-byte wire 与 30 分钟 receiving。

### 风险与决策变化

- D-001 从“完全未知 GATT”缩小为“已知至少存在 NUS 与 FFF0 两类 transport，但通知/control/wire 未冻结”，仍为 Open/Block。
- `isPassiveStream=true` 只表达 app 当前不做猜测性控制写的安全策略，不是新硬件会自动推流的证据。若 CCCD 成功但无数据，下一步应抓包/取得固件协议，而不是尝试任意 FFF2 payload。
- service UUID 可以安全选择 transport profile，但不能单独证明 payload schema；协议 decoder/profile version 仍独立受 golden 与真机抓包约束。

### 下一轮

在新硬件上确认连接错误已消失；导出完整 GATT service/characteristic properties，并在 FFF1 记录至少一段原始通知 hex。如果订阅后无通知，向硬件方取得 FFF2 的准确 START/STOP 字节和时序，再以 fixture/fake transport 先补测试后实现控制写；随后执行新旧设备 30 分钟 receiving/重连门禁。

## 2026-08-04 · M1 · Adopt 168-byte planar CUP receive protocol

### 本轮目标

按用户提供的新硬件帧说明更改接收协议，其余 BLE transport、100 Hz 时间轴、raw/CSV/session、实时窗口、算法和 UI 行为保持不变；同时避免破坏已有 408-byte 录制会话的只读分析能力。

### 需求/参考/Android 目标

- Requirement: `PROTO-001`～`PROTO-004`、`CAP-003`～`CAP-005`、`REL-001`；开放决策 `D-001`，风险 `R-001`。
- Primary source: 用户提供的 168-byte 帧结构和 offset 表；历史只读 Swift/Python/C++ protocol 与旧 golden 仅作 compatibility reference；[ADR-0003](../docs/adr/ADR-0003-cup-168-byte-planar-wire-protocol.md)。
- Tests/golden: 新 `golden_seq42.hex`、历史 `legacy_golden_seq42.hex`、`CupBatchProtocolTest`、sequence/preview/live/session/replay/offline/long-duration JVM suites。
- Android target: `CupBatchProtocolV1`、`CupBatchStreamDecoder`、sequence tracker、preview/recording/replay/offline analysis、writer metadata、capture profile 固化及相关 JVM tests。
- Non-goals: 不修改只读 `reference_sources/`；不改变 NUS/FFF0 UUID/CCCD 流程；不猜测 FFF2 START/STOP；不改变 CUPRAW1、25 列 CSV、session schema、100 Hz、800/100、HR/SQI/R/SpO2/BP/UI；不把 JVM 结果称为新硬件真机通过。

### 实现事实

- 当前 profile 为 `cup-batch-168-planar-0.1`：168 bytes，`AB BA`、function `0x15`、LE length `161`、sequence at offset 5、RED[0..19] at 6～85、IR[0..19] at 86～165、`CD DC` at 166～167；encoder 只生成该布局。
- production stream decoder 仍支持任意 BLE notification 分片/粘包/噪声 resync，并可读取 `cup-batch-408-interleaved-legacy-0.1`。首个合法帧后锁定 data length，reset/reconnect 前拒绝静默混用布局。
- sequence gap 的 missing samples、preview/recording accepted index、离线 replay gap 时间轴和 progress frame boundary 均按实际 `frame.samples.size` 计算；当前帧为 20，legacy 为 50。
- CUPRAW1 容器和 notification boundary 不变；CSV/session schema 不升级。writer 根据实际观察帧写 `protocol_profile` 与 `samples_per_frame`；`transport_profile` 独立记录 NUS/FFF0，foreground service 初始 protocol profile 使用当前 168-byte identifier。
- 30 min/2 h 模拟按 20 samples/frame 调整 frame count 与 host time，保持总 accepted samples、100 Hz、800/100 cadence、5 Hz waveform publication 和 bounded-memory 断言不变。
- 新 ADR-0003 和规划文档记录证据优先级、当前/legacy wire 边界和仍待真机确认的 D-001；历史详细状态不重写。

### 验证

- 定向 protocol/BLE/live/session/replay/offline tests → `BUILD SUCCESSFUL`；新增 current golden、legacy direct/fragment/raw replay/profile metadata、single-stream layout lock 和 dynamic missing-sample tests 均通过。
- 完整命令：`env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test lintDebug assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy --no-configuration-cache --no-daemon` → `BUILD SUCCESSFUL in 1m 20s`、130 actionable tasks；138 JVM tests/0 failures/0 errors/skips，debug lint、debug/release/androidTest APK、R8、REL-002/003/004/005/006/007 contracts passed。
- `git diff --check` → passed；用户既有 `.idea/deploymentTargetSelector.xml`、`.idea/misc.xml` 和未跟踪 `app/release/` 不属于本轮变更。release 门禁会清理该目录中的 APK/metadata/baseline profile，因此测试前已备份，测试后恢复并用 `diff -qr` 确认与备份完全一致；APK SHA-256 仍为 `bfa28d2f9b89246d795536778a367875ac8968f93d3c0574c908ae9f67659305`。
- Hardware validation: pending；未运行 emulator/新旧 CUP 真机。仍需采集 FFF1 原始 notification hex 和 characteristic properties，确认 FFF2 是否需控制命令、实际采样率/分片/MTU及 30 分钟 receiving。

### 风险与决策变化

- D-001 已取得明确的 168-byte layout 输入，但仍是 Open/Block：说明文档不能替代真实固件通知和控制流程证据；当前 profile 保持 `0.1` bring-up，不称为 production。
- `protocol_profile` 从旧草案切换为 `cup-batch-168-planar-0.1`；schema、algorithm、preprocess 和 transport profile 未改变。旧 408-byte 会话被显式标为 legacy，不会被当前 encoder 继续产生。
- 单流 layout lock 防止噪声或设备异常导致 168/408 模式中途切换；连接 reset 后可重新识别，便于旧设备/旧 raw 兼容。

### 下一轮

在新硬件连接后记录 FFF1 properties 与至少一段原始通知 hex，验证 `A1 00`、20+20 planar、sequence 和实际 100 Hz；若订阅后没有通知，向硬件方取得 FFF2 的准确 START/STOP payload/时序，再先补 fake fixture 后实现控制写。随后执行新旧设备 30 分钟 receiving/断连重连门禁。

## 2026-08-04 · M1 · Classify real FFF1 auxiliary frames

### 本轮目标

分析用户截图中 `testdevice1` 完整性复核的 `invalid=60/discarded=480`，使用导出 raw 找到证据根因并修正分类，同时保持源文件、PPG 样本、sequence、100 Hz、CSV/session schema、算法与 freshness 语义不变。

### 需求/参考/Android 目标

- Requirement: `PROTO-001/002/004`、`CAP-003`、inspection/replay；开放决策 `D-001`，风险 `R-001`。
- Primary source: `CollectedData/testdevice1.zip` 真实 FFF1 导出（只读）、截图计数、当前 168-byte protocol；[ADR-0004](../docs/adr/ADR-0004-cup-eight-byte-auxiliary-frames.md)。
- Tests: `CupBatchProtocolTest` 的 arbitrary fragmentation/unknown/bad-tail cases，`CaptureSessionInspectionTest` 的 raw/CSV/metadata consistency，以及 production inspection 对真实导出副本的精确断言。
- Android target: `CupBatchProtocolV1` auxiliary whitelist、`CupBatchStreamDecoder` 分类/计数、`CupRawReplayReport`、Sessions replay summary 与完整性测试。
- Non-goals: 不解释 auxiliary payload；不把辅助帧作为 PPG/sequence/freshness；不忽略任意短帧或未知 function；不修改用户 ZIP/raw/CSV/session；不修改 FFF2 control、文件 schema、算法/profile 或历史 408-byte compatibility。

### 实现事实

- CUPRAW1 长度与记录边界完整：39368 bytes = 8-byte magic + 272×12-byte record header + 212×168-byte PPG + 60×8-byte auxiliary。212 个 PPG sequence 从 154 连续回绕到 109，0 missing/duplicate/out-of-order；212×20=4240，与 CSV 4240 data rows、metadata frame/sample/raw counts 完全一致。
- 60 个短帧全部为 `AB BA` + function + 3-byte payload + `CD DC`；functions 分布为 `0x02=43`、`0x06=4`、`0x0C=4`、`0x0F=9`。数据帧平均间隔约 199.902 ms（约 100.049 Hz）。旧 decoder 在 function 非 `0x15` 时丢 1 byte，再 resync 丢余下 7 bytes，故精确产生 60 invalid function 和 480 discarded bytes；截图红字是结构分类假阳性，不是 PPG 损坏。
- decoder 现只对白名单 function、精确 8-byte、正确头尾的辅助帧执行无 discard 消费并累计 `auxiliaryFrames`。它们不输出 `CupBatchFrame`，因此不进入 sequence/sample/live runtime/freshness；未知 function 和 malformed tail 的合成回归仍分别计 invalidFunction/invalidTail 与 structural discard。
- replay report 和两个会话摘要 UI 暴露辅助帧计数。inspection 的结构 clean 条件保持严格：auxiliary 不算错误，unknown/malformed/noise 仍算；CUPRAW1 原始 notification 全部保留。
- 168-byte PPG、历史 408-byte replay、`cup-batch-168-planar-0.1`、CUPRAW1/CSV/session schema 和算法版本未改变；新增 ADR-0004 记录真实证据与不解释 payload 的边界。

### 验证

- 只读二进制审计：ZIP SHA-256 `1b9ba2bfee58191abcadfe03056a80b53b36bc9a00cb6558dbe833dc0700f1bf`；raw/csv/session SHA-256 分别为 `a4fc28a680ce264780d37762be5c10e2977679d90174cd59c9d3b7b239cb9846`、`e54777a167f0537883f7a9639ff6709e156210ff039be11593d95695e3eb405d`、`7b6b92da4b9aed9a9787bf0df0556dc225f7c8990dc6ce4d92b05ccd86f744ce`，复核后不变。
- production `CaptureSessionInspectionService` 直接检查导出副本 → 272 raw records、212 decoded/accepted data frames、60 auxiliary frames、4240 samples、0 invalid、0 structural discard、0 findings、`isVerifiedConsistent=true`。
- 定向命令：protocol + inspection tests → `BUILD SUCCESSFUL in 18s`；真实导出临时只读 verification test → `BUILD SUCCESSFUL in 8s`，临时 test 文件随后删除且不进入最终 diff。
- 完整命令：`env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test lintDebug assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy --no-configuration-cache --no-daemon` → `BUILD SUCCESSFUL in 1m 4s`、130 actionable tasks；141 JVM tests/0 failures/0 errors/skips，debug lint、debug/release/androidTest APK、R8、REL-002/003/004/005/006/007 contracts passed。
- `git diff --check` → passed；用户 `.idea/` 修改和未跟踪 `app/release/` 不纳入本轮。完整门禁前已备份 `app/release/`，门禁后 `diff -qr` 确认与备份一致。
- Hardware-derived evidence: `testdevice1` export passed；direct 30 min device-operated receiving/reconnect still pending。

### 风险与决策变化

- D-001 已从“缺 FFF1 payload”缩小为“短时 168-byte/100 Hz/auxiliary functions 已有真实证据，但 firmware/properties/auxiliary semantics/FFF2/30 min 未关闭”。
- 白名单只包含本次真实出现的 `02/06/0C/0F`。新 function 不能自动忽略，必须先取得真实帧与语义证据；这保留了完整性复核发现新固件或损坏的能力。
- 本轮修复重新解释 replay diagnostics，不修改源数据，也不代表辅助 payload 已被理解。

### 下一轮

向硬件方取得 functions `02/06/0C/0F` 的 3-byte payload 定义、FFF1 characteristic properties 和 FFF2 START/STOP 契约；再执行至少 30 分钟 receiving/重连，确认 data/auxiliary 分布、sequence、实际采样率和 queue/write counters。

## 2026-08-05 · M6 docs · Document realtime processing and storage implementation

### 本轮目标

为当前 Android 工程提供一份可长期维护的中文实现指南，准确回答实时数据如何从 BLE notification 到达波形/指标，以及录制数据如何 raw-first 落盘、派生、复核、恢复和导出，并给出对应 Kotlin 文件、类和函数。

### 需求/参考/Android 目标

- Requirement: 实时处理调用链、数据存储方案/格式和代码索引；沿用 `PROTO-001～004`、`SIG-001～006`、`CAP-001～011` 的已实现合同。
- Primary source: 当前 production Kotlin 源码、`docs/03` 架构/数据合同、`docs/07` 实时 causal 规划/实施结果、ADR-0003/0004 及 `docs/08` 剩余审计。
- Android target: 新增 `docs/09_REALTIME_PROCESSING_AND_DATA_STORAGE_GUIDE.md`，并加入 agent 阅读入口。
- Non-goals: 不修改 BLE、协议、滤波、指标、FGS、raw/CSV/session/analysis schema 或用户会话数据；不把未执行的真机门禁描述为已通过。

### 实现事实

- 新指南以端到端 Mermaid 流程说明 notification 在 `BleCoordinator.dispatchRawChunk()` 后分为 preview 与 recording 两个独立有界队列，并逐层映射 GATT callback、profile/generation gate、168-byte/auxiliary stream decode、sequence acceptance、`LivePpgSignalRuntime`、5 Hz waveform 和 800/100 HR/SQI/R。
- 存储部分逐字节记录 `CUPRAW1\0 + <u64 LE hostNs><u32 LE length><payload>`、64 KiB 防御上限、notification boundary、raw-first 顺序、1 s checkpoint、25 列 CSV 字段/空值、session JSON 分组与 atomic metadata、streaming replay/inspection、safe-prefix recovery、SAF/FileProvider export 和 immutable analysis JSON。
- 文档明确区分当前事实与目标合同：录制 worker 目前未把异步 analysis snapshot 传给 CSV writer；metadata `complete` 不是完整性验证；live metadata 的 invalid/discarded 仍写 0 而 inspection 从 raw 重算；20 MiB 是静态最低门槛；`algorithmVersion=unavailable` 和 preprocess profile 连字符/下划线不一致仍待 D-007 关闭。
- `00_AGENT_MIGRATION_BRIEF.md` 的文档入口新增本指南，后续 agent 可先从实现索引定位 production 符号，再回到需求/ADR 判断变更边界。

### 验证

- `git diff --check` → passed。
- 对指南内全部 `../../app/...` 相对链接执行存在性检查 → 0 missing。
- 使用 `rg` 交叉检查文档引用的核心类/函数，包括 transport、Gatt state machine、decoder、sequence、signal runtime、writer、inspection、recovery、export 和 analysis symbols → 均存在于当前源码。
- 本轮为 Markdown/status-only 变更，不运行 Gradle；既有 141 JVM/build/privacy 证据未冒充本轮 fresh test。
- Hardware validation: pending；本轮未运行 emulator/真机，FFF1 properties、auxiliary payload、FFF2、30 min receiving 和 2 h runtime 门禁保持开放。

### 风险与决策变化

- 没有 schema/profile/algorithm/runtime 行为变化。
- 指南暴露的 CSV async metric wiring、capture version/profile 命名和动态容量预算属于既有缺口，后续修复必须单独立项、测试并按需要升级版本，不能通过修改文档掩盖。

### 下一轮

优先关闭正式 capture algorithm/preprocess version 追踪；若产品要求 CSV 保存实时指标，先明确 point-in-time snapshot 与异步 cadence 的可验证绑定语义，再实现而不是回填历史行。随后按真实设备证据继续 FFF1/FFF2 与 30 分钟 receiving 门禁。

## 2026-08-05 · M6 docs · Make the implementation guide directly discoverable

### 本轮目标

修复用户在 Codex 文件浏览中无法打开实时处理/存储指南的问题，使文档位于工作区根目录并可通过短 ASCII 路径直接定位。

### 需求/参考/Android 目标

- Requirement: Codex 文件浏览可发现、可打开指南。
- Primary source: 已提交指南和用户实际文件浏览反馈。
- Android target: 将正文从 `AndroidMigrationPlanning/docs/09_REALTIME_PROCESSING_AND_DATA_STORAGE_GUIDE.md` 迁移为根目录 `REALTIME_AND_STORAGE.md`，同步入口与相对链接。
- Non-goals: 不修改指南技术结论，不修改 app/runtime/schema/算法或用户数据。

### 实现事实

- 原文件经检查为 Git tracked、`0644`、UTF-8、34,146 bytes/427 lines，工具可完整读取，未发现内容损坏；因此优先按 Codex 文件浏览的路径发现/解析兼容性处理。
- 正文迁移到仓库根目录的 `REALTIME_AND_STORAGE.md`，避免较深目录和长文件名；文档内所有 `../../app/...` 链接重写为根目录下的 `app/...`。
- agent brief 与简版状态入口同步到新路径，仓库只保留一个正文真源，不复制两份会漂移的指南。

### 验证

- 新文件为 Git working tree 中的根目录 Markdown；旧路径已移除，不再作为正文入口。
- 全部相对源码链接存在性检查 → 0 missing；`git diff --check` 与 `git diff --cached --check` → passed；Git 将变更识别为单一文档 rename，用户 `.idea` 和 `app/release/` 未暂存。
- 本轮为文档可访问性修复，不运行 Gradle；hardware validation 不适用。

### 风险与决策变化

- 没有数据合同或 runtime 行为变化；若 Codex UI 仍显示旧树，需要刷新 workspace，但根目录文件也可通过交付中的绝对链接直接打开。

### 下一轮

继续按 `REALTIME_AND_STORAGE.md` 维护实现索引；技术下一步仍是正式 capture version/profile 和 CSV metric snapshot 绑定策略。

## 2026-08-05 · M1/M2 · Add Nordic NUS sensor-packet compatibility

### 本轮目标

让现有 Android app 在不破坏 `CUP*` NUS/FFF0 设备的前提下，扫描、连接并捕获精确广播名 `Nordic_UART_Service` 的新设备；按连接设备显式切换新 168-byte UInt32 sequence wire，并让 preview、raw-first recording、CSV/session、replay、inspection/recovery/offline 全链路一致。

### 需求/参考/Android 目标

- Requirement: `BLE-002/003/005`、`PROTO-001～004`、`CAP-001/003～005/009`、`REL-001`；开放决策 `D-001`，风险 `R-001`。
- Primary source: 用户给出的 `sensor_packet_t`；只读 `CollectedData/Log 2026-08-05 17_04_47.txt` nRF Connect 导出；既有 ADR-0002/0003/0004；新 [ADR-0005](../docs/adr/ADR-0005-nordic-nus-sensor-packet-profile.md)。
- Android target: `BleModels`/`CupBleGattStateMachine`/`BleCoordinator`/`BlePreviewRuntime`，`CupSensorPacketProtocolV1`/stream decoder/sequence tracker，FGS/recording/writer/CSV/raw replay/inspection/recovery/offline analysis。
- Non-goals: 不修改用户日志；不接受任意 Nordic/NUS 广播；不猜 RX/FFF2 命令；不宣称通知时间证明真实采样率；不改变 `CUPRAW1`、session JSON、25 列 header、信号算法或旧设备 wire。

### 实现事实

- 日志 97 条 TX notification 全部为 168 bytes，97/97 header `AB BA`/tail `CD DC` 有效；UInt32 LE sequence 从 0 连续到 96。首帧 RED[0]/IR[0] 解为 45376/52318。日志平均通知间隔约 222.9 ms，但这包含 BLE/日志调度，只记录为 transport evidence，不据此改采样合同。
- `CupBleDeviceProfile` 只为 NUS 新增精确名 `Nordic_UART_Service` variant；相似前缀仍过滤。connect 固化 `activeStreamProtocolMode`，service discovery 同时检查身份与 UUID，raw chunk 携带 mode；旧 `CUP*` 仍按 service 选择 NUS/FFF0 和 batch-compatible mode。
- 新 `cup-sensor-168-planar-u32seq-0.1` 固定 `AB BA + UInt32 LE seq + 20 RED + 20 IR + CD DC`；stream decoder 在显式 sensor mode 下处理任意 notification 边界、坏尾与重同步，不读取不存在的 function/length。
- `CupBatchFrame` 新增完整 `sequenceNumber` 和 `wireProfile`，旧 `sequence: UByte` 保留作源码兼容。sequence tracker 按 profile 使用 8-bit/32-bit 模空间，production preview/recording/replay 都改为观察完整 frame。
- FGS 从连接 snapshot 固化 protocol profile；recording decoder 使用该 mode，mode 中途变化以 `protocolError` 停止。raw-first 顺序和 `CUPRAW1` record 不变。
- CSV 25 列/header 不变：batch/legacy 继续 `ppgcollector_samples_v1` 且 parser 限制 sequence ≤255；sensor 使用 `ppgcollector_samples_v2` 保存 UInt32。metadata 记录 sensor profile；inspection、recovery、offline analysis 在重放前按 metadata 选择 decoder，gap break 直接使用 replay sequence event，避免 UInt8 截断。
- 新增 ADR-0005，并同步 agent brief、需求矩阵、架构/数据合同、source index、风险登记和根目录实现指南。

### 验证

- 用户日志只读审计：85,290 bytes/256 lines，SHA-256 `771ce1a7f39062ad4b0db450a14768afaeb584236215097be8b1b368cdefd28f`；97 notifications、`{168=97}`、sequence 0…96、96/96 continuous，源文件未修改且不纳入提交。
- 临时 production-decoder verification test 将日志 97 条 notification 原样送入 `CupBatchStreamDecoder(SENSOR_PACKET_168)`，得到 97 frames、sequence 0…96、0 invalid/discard/pending，`BUILD SUCCESSFUL in 8s`；临时 test 随后删除且不纳入 diff。首次测试使用仓库根相对路径，但 Gradle unit test 工作目录为 `app/`，出现 `NoSuchFileException`；改为 `../CollectedData/...` 后通过，该失败是 verification harness 路径问题，不是 decoder 失败。
- 定向命令：sensor protocol、fake BLE/preview、capture/replay/inspection、CSV v1/v2 tests → `BUILD SUCCESSFUL`。
- 全量 JVM 回归：`:app:testDebugUnitTest` → `BUILD SUCCESSFUL`。
- 完整命令：`env JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew test lintDebug assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy --no-configuration-cache --no-daemon` → `BUILD SUCCESSFUL in 1m 12s`、130 actionable tasks；148 JVM tests/0 failures/0 errors/skips，debug lint 0 errors/9 dependency-version warnings，debug/release/androidTest APK、R8、REL-002/003/004/005/006/007 contracts passed。
- `git diff --check` 与指南 75 个源码链接检查通过。用户 `.idea` 修改和未跟踪 `app/release/` 不纳入本轮；release 目录已预先备份，门禁后 `diff -qr` 确认无差异。
- Hardware validation: pending。本轮未操作真机；扫描、CCCD、首帧 freshness、长稳、断连重连、后台与实际 sampling cadence 待设备验收。

### 风险与决策变化

- D-001 从“未知该 NUS 设备 payload”缩小为“短时结构/序号已有日志证据”；固件/型号、量产身份、真实采样率、RX 命令、MTU/分片与 30 分钟 receiving 未关闭。
- 两个 wire 都恰好 168 bytes，不能仅以长度自动检测。production 必须依赖连接身份与 metadata；如果量产广播名变化，需要硬件方提供稳定 manufacturer/service-data 身份，不能扩大为任意 NUS。
- `ppgcollector_samples_v2` 是 row-level sequence 范围升级；`CUPRAW1` 与 session schema 未升级。跨平台读取方若只支持 v1，需在 D-006 中补充 v2 reader。

### 下一轮

在新设备上执行 exact-name scan→NUS service/TX CCCD→首帧 fresh→录制→保存→inspection 的真机验收，并至少运行 30 分钟 receiving/reconnect；同步采集固件/型号、稳定身份字段、实际采样率、MTU/分片和 RX 控制要求。随后继续正式 capture version/profile 与动态 FGS health 工作。

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
