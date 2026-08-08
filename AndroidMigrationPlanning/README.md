# PPGCollector Android 移植资料包

本目录是 PPGCollector 从 iOS/Swift 移植到 Android/Android Studio 的实施基线。它把规划文档、当前 iOS 可执行行为、Python GUI 中仍有参考价值的实现、产品原始需求、协议与算法金标集中在一个可审计的位置。

## 从这里开始

1. [Codex Agent 移植入口](00_AGENT_MIGRATION_BRIEF.md)：每轮/上下文压缩后必读的目标 prompt、当前事实、版本映射、契约和工作流程。
2. [简版开发状态](status/DEVELOPMENT_STATUS.md)：当前版本、已交付能力、下一步和开放阻塞。
3. [详细开发状态](status/DEVELOPMENT_STATUS_DETAILED.md)：只追加实际工作和验证证据。
4. [总移植方案](docs/01_ANDROID_MIGRATION_MASTER_PLAN.md)：范围、技术路线、模块、关键决策与最终形态。
5. [需求与对等矩阵](docs/02_REQUIREMENTS_AND_PARITY_MATRIX.md)：每条需求的来源、Android 行为与验收方法。
6. [架构与数据契约](docs/03_ARCHITECTURE_AND_DATA_CONTRACTS.md)：BLE、协议、并发、存储、算法、前台服务及状态机设计。
7. [实施路线与验收](docs/04_IMPLEMENTATION_ROADMAP_AND_ACCEPTANCE.md)：分阶段任务、门禁、测试矩阵、估算与交付定义。
8. [源代码参考索引](docs/05_SOURCE_REFERENCE_INDEX.md)：规划条目到归档源文件和关键符号的索引。
9. [决策与风险登记](docs/06_OPEN_DECISIONS_AND_RISK_REGISTER.md)：开工前必须确认的事项、风险、缓解与负责人建议。
10. [M7 五轮开发规划](docs/09_M7_CAPTURE_TRACEABILITY_SUBJECT_ARCHIVE_PLAN.md)：RAW 显示反相、PI/指标时间轴、手工参考血压、自由命名/被试资料、档案/批量导出、0.5～12 Hz fixed-lag 与最终 UI 收敛。

## 目录约定

```text
AndroidMigrationPlanning/
├── README.md
├── docs/                       # Android 移植规划
├── manifests/                  # 来源、排除项和文件校验信息
└── reference_sources/
    ├── ios_current/            # 当前 PPGCollector Swift 源码、测试和金标
    ├── python_gui/             # 筛选后的 Python GUI/算法参考
    ├── product_requirements/   # 原始 DOCX、示意图和 SQI 参考
    ├── protocol/               # CUP 协议跨语言参考及金标
    └── signal_fixtures/        # 预处理、心率、SQI 跨语言向量
```

`reference_sources/` 是只读快照；Android 工程实现不得直接在其中修改。开发时应把需要的契约或算法移植进新的 Android 工程，并继续用这里的金标验证。归档文件的来源与用途见 [SOURCE_CATALOG.md](manifests/SOURCE_CATALOG.md)，内容校验见 [SHA256SUMS.txt](manifests/SHA256SUMS.txt)。

## 证据优先级

出现冲突时按以下顺序处理，并在 ADR 中记录：

1. 产品原始需求 DOCX 与经真机确认的 CUP 固件/GATT 行为；
2. 当前 PPGCollector Swift 代码、测试、会话文件格式和金标；
3. Python GUI 中的算法、离线分析和工作台行为；
4. 本资料包中的规划性建议。

Python 快照中的旧 BLE、旧协议、旧 `.ppgbin`/NUS 流程不是 Android 协议依据。隔壁 Swift 项目及其迁移说明没有被纳入、复制或用于推导本方案，详见 [EXCLUSIONS.md](manifests/EXCLUSIONS.md)。

## 当前建议基线

- Kotlin、Jetpack Compose、Coroutines/Flow、单向数据流；Android Studio 工程。
- 当前 Android 工程实际为 `compileSdk = 37`、`targetSdk = 37`、`minSdk = 26`；发布前仍需按官方政策和目标设备清单复核，并保留更高 API 的前向兼容测试。
- CUP 协议、`CUPRAW1`、CSV 与 session JSON 首先保持 iOS 兼容。
- 原始通知块先落盘，再解码、派生 CSV 和指标；任何缓冲区都必须有上限。
- 录制由 `connectedDevice` 类型前台服务单一持有，必须从可见界面启动；通知提供明确停止操作。
- V1 发布 HR、SQI 和 R（ratio-of-ratios）诊断；SpO2、BP 在没有校准或有效模型前明确显示“不可用”，不得用占位数值伪装结果。R 不是呼吸率，也不是未经校准的 SpO2。

## 维护规则

- 每次更新快照都要更新 `SOURCE_CATALOG.md`、`SHA256SUMS.txt` 和文档中的相关链接。
- 数据格式或算法输出发生不兼容变化时，必须提升 schema/algorithm version，并保留旧版本重放测试。
- 任何“仅 Android 特有”的生命周期、权限或后台策略变更必须写 ADR，不得隐含在 UI 代码中。
- 每轮迭代完成后更新双层 development status，并用与 [Codex Agent 移植入口](00_AGENT_MIGRATION_BRIEF.md)一致的 `M*` 版本提交 Git；真机门禁按约定延期到用户明确要求的测试轮。
