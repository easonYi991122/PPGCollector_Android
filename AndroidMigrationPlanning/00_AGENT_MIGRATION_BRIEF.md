# PPGCollector Android 移植：Codex Agent 目标模式说明

版本：1.2（agent 执行基线）
日期：2026-08-08
当前迭代：`M7.0`（采集追溯、被试档案与 UI 增量规划；M7.1～M7.5 尚未实现）

> 这是本项目的长期 agent 入口文档。每次开始新迭代、恢复任务或上下文压缩后，必须从头阅读本文件，再阅读[简版开发状态](status/DEVELOPMENT_STATUS.md)。需要追溯历史时再阅读[详细开发状态](status/DEVELOPMENT_STATUS_DETAILED.md)。没有完成这一步，不得开始修改代码或宣布进展。

## 1. 可直接复制到 Codex 目标模式的 prompt

```text
你是 PPGCollector Android 的长期迁移开发 agent。

总任务：把 AndroidMigrationPlanning/reference_sources 中当前 iOS/Swift PPGCollector 的可执行行为、协议、数据格式、算法和测试，移植成适合 Android 生命周期与后台规则的 Kotlin/Jetpack Compose app。你的目标不是逐行翻译 SwiftUI，而是在跨平台数据契约保持可验证兼容的前提下重写 Android 平台层，并按 MigrationPlanning 的阶段逐步交付。

每次开始工作、恢复工作或上下文压缩后，必须先完整阅读：
1. AndroidMigrationPlanning/00_AGENT_MIGRATION_BRIEF.md
2. AndroidMigrationPlanning/status/DEVELOPMENT_STATUS.md
3. AndroidMigrationPlanning/status/DEVELOPMENT_STATUS_DETAILED.md（需要历史证据时）
4. 与当前切片有关的 docs/01~06 及 docs/05_SOURCE_REFERENCE_INDEX.md
然后检查 git status，保留用户已有修改。

证据优先级固定为：真实产品需求与真实 CUP 固件/GATT 抓包 > 当前 iOS Swift 源码、Swift 测试和 golden fixtures > Python/C++ 交叉参考 > 规划建议。reference_sources 是只读快照；不要直接修改它，也不要把被 EXCLUSIONS.md 排除的旧 BLE/NUS、旧协议、旧 CSV、IMU 或隔壁项目作为 Android V1 依据。资料冲突时先记录 ADR/决策，不要悄悄猜测。

当前 Android 工程事实：Kotlin + Gradle Kotlin DSL + Jetpack Compose；minSdk 26、compileSdk 37、targetSdk 37、Java 11；applicationId 和 app 名仍是基础工程占位值；当前 app 只有默认 Hello Android Compose 壳和示例测试，尚未实现 BLE、CUP 协议、信号处理、会话存储、前台服务或正式页面。不要把 app versionName=1.0 当作迁移完成版本。

必须保持或显式验证的核心契约：
- CUP transport profile 当前包含 NUS `6E400001/3/2` 与 FFF0/FFF1/FFF2 两组 service/notify/control UUID；连接后按设备身份和实际发现的 service 精确选择。两组都仍是 draft/bring-up，被动订阅不等于固件已确认无需控制命令。
- 当前存在两个 168-byte 接收 wire profile：`CUP*` 设备沿用 `AB BA + 0x15 + LE length 161 + UInt8 sequence + 20 RED + 20 IR + CD DC`，并识别 FFF1 的 8-byte auxiliary；精确名称 `Nordic_UART_Service` 使用 NUS 和 `AB BA + UInt32 LE seq_no + 20 RED + 20 IR + CD DC`。两者必须按连接身份显式选择，不能因总长度相同而自动混用。历史 408-byte/50-pair interleaved 仅保留 raw/session 回放兼容，见 ADR-0003/0004/0005。
- decoder 必须支持碎片、粘包、噪声重同步、错误 tail/length/function、有限缓冲；sequence first/continuous/gap 接受，duplicate/out-of-order 不进入样本流并保留诊断。
- raw 是恢复与再分析真源：CUPRAW1\0 + 每个原始 BLE notification chunk 的 host monotonic ns、LE 长度和原始字节；先确认 raw append 成功，再派生 CSV/指标。任何队列、decoder、分析窗口和波形 ring 都必须有上限，不能静默丢 raw。
- CSV 保持当前 25 列顺序、snake_case/session JSON、soft_version/alg_version/profile/version 追踪和兼容读取；batch 行为使用 `ppgcollector_samples_v1`，UInt32 sequence sensor packet 使用 `ppgcollector_samples_v2`，其 `frame_sequence` 不得截断。数据格式或算法语义改变必须升级版本并保留旧版本重放测试。
- 采集链路目标为 100 Hz accepted RED/IR、800 样本/8 秒 live window、每 100 样本分析一次；默认波形快照 5 Hz（支持范围 1–60 Hz），HR/SQI/R 每秒更新。R 只是未校准 ratio-of-ratios 诊断，不能变成 SpO2 或血压。
- 指标必须带 value、valid、provisional、reason、algorithm version、source sample/time；无效、未热身、断流、校准缺失时显示明确状态，不沿用旧数字制造“实时有效”的错觉。
- 录制 gate 至少要求已连接、流 fresh、名称合法、不在录制、存储预检通过；停止采用 first stop reason wins、单一幂等 finalizer。Android 长录制由 connectedDevice 前台服务唯一持有，Activity 只观察/绑定。
- Android UI 用 Compose/StateFlow/生命周期感知收集；BLE callback 不做磁盘 I/O、DFT 或 Compose 更新；先实现纯 Kotlin core，再实现 Android BLE/服务/UI。

工作方式：
1. 从当前 status 和 MigrationPlanning 阶段选择一个最近的未完成的任务，理想状态下，一轮次内完成一个M版本的开发再做相应的验证，不跨越未完成的前置契约。
2. 在改动前列出本轮的版本号（M0/M1/...）、需求 ID、主参考符号、Android 目标文件、非目标范围和验收命令。
3. 先实现/验证最小正确路径，再补错误路径和边界；不为方便而改变 schema、算法阈值、时间轴、序号语义、后台所有权或有效性模型。
4. 优先写纯 Kotlin/JVM 测试，复用 golden frame 和 preprocessing/HR/SQI fixtures；需要 Android API 的部分用 fake clock/fake GATT/instrumented test 隔离。若无法运行 Gradle，记录具体环境原因，不伪造测试通过。
5. 本项目的迭代结束后不会立即进行真机测试。当前轮不得以“没有真机”阻止可在本地完成的工作；把真实设备协议、API/厂商矩阵和长录制作为明确的待执行门禁。只有用户明确要求时才执行上机测试。
6. 完成后追加更新 DEVELOPMENT_STATUS_DETAILED.md，并更新 DEVELOPMENT_STATUS.md 的当前快照、已交付版本、未完成能力和下一步；历史记录不能被重写成“看起来已完成”。
7. 每轮完成后提交 Git。commit message 使用 `<type>(M<版本>): <简洁更新内容>`，例如 `feat(M1): port CUP stream decoder and golden tests`、`docs(M0.1): establish migration agent workflow`。提交内容只包含本轮相关文件；提交前检查 diff、测试结果和未跟踪文件。

最终交接必须简洁说明：本轮版本与功能、修改文件、验证命令及结果、真机测试是否按约定延期、剩余阻塞/风险、下一轮建议、commit hash。除非证据已满足，不要使用“完成移植”“生产协议”“临床结果”等表述。
```

## 2. 项目使命与当前事实

### 2.1 总任务

Android 端最终要提供 CUP BLE 设备扫描/连接、实时 RED/IR 波形和指标、可靠原始采集、会话保存/检查/恢复/导出，以及随后建立在同一 raw replay 管线上的离线分析工作台。跨平台需要保持的是协议、样本、文件、算法和数据完整性语义；权限、GATT 回调、生命周期、前台服务、文件分享和 Compose UI 必须按 Android 重写。

### 2.2 当前工程基线

| 项目 | 当前事实 | 说明 |
|---|---|---|
| 语言/构建 | Kotlin、Gradle Kotlin DSL、version catalog | 目标实现语言；不把 Python/C++ 嵌入 app runtime |
| Android | `minSdk=26`、`compileSdk=37`、`targetSdk=37` | 以 `app/build.gradle.kts` 为事实来源；发布前重新审查平台政策 |
| 工具链 | AGP `9.3.1`、Kotlin `2.2.10`、Gradle `9.6.1`、Compose BOM `2026.02.01`、Java source/target `11` | 版本以当前工程文件为准，更新时同步状态记录 |
| app 身份 | `com.example.ppgcollector_android`、`PPGCollector_Android`、`versionName=1.0` | 基础工程占位值；不是最终产品身份或迁移里程碑 |
| 当前代码 | 默认 `MainActivity`、Material 3 主题、示例 unit/instrumented test | 目前没有 Android 迁移业务能力 |
| 当前验证 | 本轮尝试 `./gradlew test assembleDebug`，因环境没有 Java Runtime 未执行 | 后续安装/配置 JDK 后必须重跑；不能把该命令标为通过 |

### 2.3 参考资料边界

`AndroidMigrationPlanning/reference_sources/` 共 130 个文件，其中 125 个为可读文本/结构化资料；包括 iOS 当前 app 与测试、协议 Python/C++ 参考、Python GUI、产品需求 DOCX/图片和三套信号 fixtures。快照只读。`manifests/EXCLUSIONS.md` 中列出的旧 BLE/NUS、旧协议、旧 CSV、IMU 和隔壁项目不属于 Android V1 依据。

## 3. 文档入口和阅读顺序

1. 本文件：agent 目标、当前事实、不可破坏契约和迭代纪律。
2. [简版开发状态](status/DEVELOPMENT_STATUS.md)：当前版本、已完成能力、下一步和阻塞项；每轮更新。
3. [详细开发状态](status/DEVELOPMENT_STATUS_DETAILED.md)：只追加实际发生的工作、证据、测试和风险；需要历史追溯时阅读。
4. [总移植方案](docs/01_ANDROID_MIGRATION_MASTER_PLAN.md)：产品范围、技术路线和 Definition of Done。
5. [需求与对等矩阵](docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md)：需求 ID、来源和验收。
6. [架构与数据契约](docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md)：所有权、状态机、文件、算法、线程和安全边界。
7. [实施路线与验收](docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md)：阶段、任务和门禁。
8. [源代码参考索引](docs/05_SOURCE_REFERENCE_INDEX.md)：从需求/模块跳转到参考符号和测试。
9. [决策与风险登记](docs/06_OPEN_DECISIONS_AND_RISK_REGISTER.md)：未决证据和风险，不得在代码中偷偷作决定。
10. [实时数据处理与数据存储实现指南](../REALTIME_AND_STORAGE.md)：当前 Android production 调用链、文件格式、函数索引与修改边界。
11. [M7 五轮开发规划](docs/09_M7_CAPTURE_TRACEABILITY_SUBJECT_ARCHIVE_PLAN.md)：2026-08-08 用户增量的权威实施顺序、数据合同、旧文档治理和单轮单次校验要求。

## 4. 证据优先级与冲突处理

1. 产品原始需求、真实 CUP 固件/GATT 行为和真机抓包；
2. 当前 Swift app、Swift 测试、会话格式和 golden fixtures；
3. Python GUI/算法与 C++/Python 协议交叉实现；
4. 规划性建议。

若来源冲突，建立简短 ADR，写明证据、选择、影响的 `schema_version`/`alg_version`/`protocol_profile` 和待验证项。2026-08-04 的新硬件协议说明与 `testdevice1` 真实 FFF1 导出已取代 408-byte/50-pair 作为当前接收布局，并确认短时约 100 Hz 与额外 8-byte 辅助帧；固件、characteristic properties、辅助 payload/FFF2 控制语义和长稳仍未冻结，因此 profile 继续称为 `bring-up`，旧布局保留为历史兼容 profile。

## 5. Migration 版本和当前迭代映射

这里的 `M*` 是迁移里程碑，不等同于 Android `versionName`。commit、详细状态和简版状态必须使用同一个迁移版本。

| Migration 版本 | 对应规划阶段 | 目标交付 | 当前状态 |
|---|---|---|---|
| `M0` | Phase 0 | 契约冻结、工程基础、ADR、测试/CI 基线 | `M0.1/M0.2` 文档、基线 ADR 和 JVM/build 门禁已建立；证据决策仍开放 |
| `M1` | Phase 1 | 纯 Kotlin protocol/raw/signal parity 和 golden tests | protocol/raw/CSV/session/信号/live core、固定种子随机分片/中间噪声 resync evidence 已实现并有 JVM 证据；Android async/lifecycle 仍待接入 |
| `M2` | Phase 2 | BLE 权限、扫描、GATT、订阅、新鲜度和诊断 | core、fake transport、Android adapter、10 s 有限扫描、scan failure/late-result 边界、分步 permission callback merge、NUS/FFF0 service 自动选择、permission/Compose seam 已实现；真机 FFF0 通知内容与控制命令门禁未过 |
| `M3` | Phase 3 | raw-first writer、CSV/session、FGS、停止/恢复/导出 | writer、FGS seam、session/recovery/export/async analysis 已实现；系统后台/重建仍待验收 |
| `M4` | Phase 4 | V1 Compose 实时、录制、历史、详情、重放 | 页面、Swift 对等保序极值双轨折线、有效帧 freshness/录制 gate、连接/断开状态、分组卡片 UI、Sessions/replay 与 instrumentation seam 已实现；真机波形/录制、runtime/SAF provider/可访问性仍待验收 |
| `M5` | Phase 5 | 长稳、API/厂商矩阵、性能、隐私、发布硬化；形成 V1.0 | JVM 长稳模拟与 release shrink/lint/privacy/API/FGS 门禁已实现；2026-08-03 已由 Android Studio 生成并校验 fresh unsigned release APK。API/厂商/真机/正式 identity/签名/隐私仍开放 |
| `M6` | Phase 6 | V1.1 离线稳定段、频谱、周期、对比工作台 | 版本化 raw replay、完整信号/全程 zero-phase、独立 Sessions/compare/横屏工作台均已实现；Live 现由统一 bounded runtime 同步发布 RAW/CAUSAL 0.6–4 Hz 与既有指标窗口。`D-014`、runtime/真机 UI/性能门禁待执行 |
| `M7.0` | M7 规划 | RAW 显示、PI/统一时序、手工参考血压、命名/被试资料、档案/批量导出、0.5～12 Hz fixed-lag 与 UI 收敛 | 五轮规划已形成；M7.1～M7.5 代码均未实现，见 `docs/09` |

## 6. 不可破坏的核心契约

### 6.1 协议与 BLE

- 名称筛选保留 `CUP*`，并只额外接受精确名称 `Nordic_UART_Service`；支持 NUS `6E400001/3/2-...` 与 FFF0 `0000FFF0/1/2-...`。服务发现后精确选择 transport，wire mode 由连接身份固化；Android V1 不猜测 START/STOP payload。
- bring-up wire profile：batch 为 `AB BA + 0x15 + LE length 161 + UInt8 sequence + 20 RED + 20 IR + CD DC`；sensor packet 为 `AB BA + UInt32 LE sequence + 20 RED + 20 IR + CD DC`。两者均 168 bytes/20 samples；项目暂沿用 100 Hz，sensor 设备真实采样率待真机确认。历史 408-byte 仅兼容回放。
- decoder 支持任意通知分片/粘包/噪声重同步，并对 invalid function/length/tail、discarded bytes、pending buffer 计数且有上限。
- sequence `first`、`continuous`、合理 `gap` 进入 accepted stream；duplicate/out-of-order 拒绝但保留诊断；`UInt8` wrap 必须测试。
- GATT callback 只复制通知 bytes、记录 monotonic 时间、投递有界队列；旧 generation 的晚到 callback 不得污染新连接。

### 6.2 Raw、CSV、session 与恢复

- raw 真源格式为 `CUPRAW1\0`，每条记录是 `UInt64 LE host_monotonic_ns`、`UInt32 LE payload_length`、一整块原始 notification bytes；reader 对 chunk length 至少施加 64 KiB 上限。
- raw append 成功后才允许解码/派生 CSV；队列溢出、raw 写失败、CSV 写失败、截尾和强停都必须导致可审计的 incomplete 状态，不能静默标 complete。
- CSV 25 列顺序以 `CaptureCSVSchema.swift` 和 `docs/03` 为准；session JSON 使用 snake_case、ISO-8601、版本/profile、sequence/invalid/discard/writer/recovery 信息。
- 目录和源文件不覆盖；inspection 不修改源；recovery 只复制安全前缀到新目录并记录 provenance/hash。
- `soft_version`、`alg_version`、`preprocess_profile`、`protocol_profile`、`transport_profile`、`schema_version` 和离线 `analysis_profile` 要能追溯本次生成行为。

### 6.3 信号与指标

- accepted samples 为 100 Hz；live runtime 基线 800 sample window、100 sample step、1 Hz 指标计算；gap 清空因果状态和 window generation 并重新 warm up。
- 预处理使用 Swift baseline 的固定 SOS/DC/IR polarity/z-score/gap reset；HR 使用固定窗口、robust scale、Hann/DFT、双极性、peak/prominence/width/distance、RR/confidence；SQI 使用模板周期/Pearson/0.90/0.70 分级；先过 fixtures 再优化。
- 指标携带有效性和来源。SQI 当前保持 provisional；ratio-of-ratios 仅诊断；SpO2、BP 无校准/模型时必须 unavailable。

### 6.4 Android 运行时与 UI

- 录制开始前 gate：连接、fresh stream、合法且不重名的 ASCII 名称、存储预检、未有活动会话。
- 单一 writer、单一 GATT owner、单一幂等 finalizer；first stop reason wins。录制期间由 `connectedDevice` FGS 持有，Activity 重建只重新观察/绑定。
- Compose 通过 StateFlow 和 lifecycle-aware collection 消费不可变快照；波形默认 8 秒、5 Hz、RED/IR 独立动态 Y，Canvas 绘制前做 min/max bucket。
- BLE/API/服务/文件分享为平台重写，不要把 SwiftUI 层级逐行搬到 Kotlin；core protocol/signal 不依赖 Android SDK。

## 7. 每轮工作流程

### 开始前

1. 读取本文件和简版状态；根据任务阅读相应详细状态、规划章节、source index、源文件和测试。
2. 执行 `git status --short --branch`，辨认哪些改动属于用户，禁止覆盖或清理无关改动。
3. 写下本轮 `M*` 版本、目标、非目标、需求 ID、主参考、Android 目标和预计验证命令。
4. 如契约有未知项，标记为 draft/open decision；不把未知项藏进 API 或 UI 默认值。

### 实现中

- 先保持数据契约和可测试性，再做 UI 装饰；任何跨层依赖要遵守 `feature/data → core` 方向。
- 每个关键行为同时补正常路径、边界路径和失败路径；原始数据完整性问题优先于演示效果。
- 对算法、协议和文件格式使用现有 golden/fixture；对 Android 生命周期使用 fake clock/fake GATT/模拟流。大文件和长流采用 streaming/bounded 设计。
- 不直接改 `reference_sources`；若发现资料问题，修改规划/ADR/状态并保留原快照。

### 收尾前

1. 运行能运行的 unit/JVM/instrumented/static/build 命令；记录命令、结果、环境阻塞和未运行的真机门禁。
2. 更新详细状态：只追加实际发生的事实、diff、测试和风险，不重写历史。
3. 更新简版状态：当前 M 版本、已交付能力、尚未交付能力、开放决策、下一步。
4. 检查 `git diff --check`、`git diff`、`git status`，确认没有意外构建产物/临时文件。
5. 提交本轮相关文件，commit message 使用 `type(M版本): 简洁说明`，例如：
   - `docs(M0.1): establish migration agent workflow`
   - `feat(M1): port CUP protocol decoder and golden tests`
   - `feat(M3): add raw-first session writer`

### 真机测试策略

每轮迭代结束不立即上机是本项目约定。没有真机不能成为本地可完成工作的阻塞条件；但 agent 必须把真实 GATT/固件、锁屏/后台、API/厂商矩阵、长时间录制和功耗等门禁列为 `pending hardware validation`，不能用模拟测试替代性地宣称通过。用户明确要求上机时再执行相应门禁。

## 8. 交付和停止条件

每轮最终报告只需回答：完成了哪个 `M*` 切片、改了哪些文件、如何验证、哪些真机门禁延期、有哪些开放决策、下一步是什么、commit hash 是什么。若测试因环境缺 Java/JDK、设备或依赖缺失而未执行，明确写出原因。

只有同时满足对应阶段的需求矩阵、测试证据、数据完整性、版本追踪和 Android 生命周期门禁，才可以把阶段标为完成。没有证据时使用“实现了代码/测试”“待真机验证”“draft profile”“provisional metric”等精确措辞。
