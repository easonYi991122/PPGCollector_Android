# 参考源目录

| 归档目录 | 原始位置 | 纳入内容 | 在 Android 移植中的地位 |
|---|---|---|---|
| `reference_sources/ios_current/PPGCollector/` | `PPGCollector/` | 当前 app Swift 源码与资源目录 | 首要可执行行为；按模块移植 |
| `reference_sources/ios_current/PPGCollectorTests/` | `PPGCollectorTests/` | 单元、集成、长稳测试和测试资源 | Android 测试清单与对等门禁 |
| `reference_sources/ios_current/PPGCollector-Info.plist` | `PPGCollector-Info.plist` | iOS app 元信息 | 仅用于核对版本/权限语义，不移植平台字段 |
| `reference_sources/python_gui/` | `MigrationPlanning/references/python_gui/` | 筛选后的在线处理、离线分析、GUI 和测试 | 算法与工作台参考；不作为 BLE/协议真源 |
| `reference_sources/product_requirements/` | `MigrationPlanning/references/requirements/` | 原始 DOCX、界面/协议示意、SQI 脚本 | 产品需求与 SQI 来源依据 |
| `reference_sources/protocol/` | `MigrationPlanning/protocol/` | Python/C++ 协议实现和 golden frame | 跨语言字节级核验；Swift app 实现仍是当前 app 行为基线 |
| `reference_sources/signal_fixtures/` | `MigrationPlanning/fixtures/` | 预处理、心率、SQI 生成器、向量和 Python 自测 | Kotlin/JVM 数值对等的金标 |

## 使用等级

- **P0/逐语义移植**：协议解码、序号处理、连接/新鲜度状态、raw-first 写入、文件格式、恢复、算法纯函数、指标有效性模型。
- **P1/平台化重写**：BLE API、生命周期、前台服务、权限、Compose 页面、文件分享与后台限制。
- **P2/UX 参考**：Python 分析工作台、波形对比、周期叠加、诊断视图。
- **明确不移植**：旧 Python BLE/协议、IMU、旧 CSV；隔壁 Swift 工程及其说明。

详细到文件和符号的链接见 [源代码参考索引](../docs/05_SOURCE_REFERENCE_INDEX.md)。
