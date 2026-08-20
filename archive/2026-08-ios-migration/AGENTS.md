# PPGCollector Android agent 工作约定

本项目是把 `AndroidMigrationPlanning/reference_sources/` 中的 iOS/Swift、Python 和协议参考移植为 Android/Kotlin app。任何 Codex agent 在开始新一轮工作、恢复工作或发生上下文压缩后，必须先完整阅读：

1. [`AndroidMigrationPlanning/00_AGENT_MIGRATION_BRIEF.md`](AndroidMigrationPlanning/00_AGENT_MIGRATION_BRIEF.md)
2. [`AndroidMigrationPlanning/status/DEVELOPMENT_STATUS.md`](AndroidMigrationPlanning/status/DEVELOPMENT_STATUS.md)
3. 与本轮任务直接相关的 [`AndroidMigrationPlanning/docs/`](AndroidMigrationPlanning/docs/) 和 [`AndroidMigrationPlanning/docs/05_SOURCE_REFERENCE_INDEX.md`](AndroidMigrationPlanning/docs/05_SOURCE_REFERENCE_INDEX.md)

执行规则：

- 先检查 `git status`，保留用户已有修改；`reference_sources/` 是只读快照，不直接修改。
- 每轮完成一个可验收的任务，理想状态下，一轮迭代应该至少完成一个M版本的移植开发再做相应验收检查，说明需求 ID、主参考文件、Android 目标和测试证据。
- 每轮不要求立即上机真机测试；优先完成 JVM/unit、fake BLE、静态检查、文件格式和模拟长稳验证，并把真机门禁标为待执行。除非用户明确要求，不把真机操作当作当前轮的默认步骤。
- 每轮完成后更新详细状态（追加事实记录）和简版状态（当前快照），然后用与 MigrationPlanning 版本映射一致的简洁提交说明提交本轮变更。
- 不把未完成的 Compose 壳、模拟数据或未经校准的 ratio-of-ratios 宣称为已完成产品能力；不把 SpO2/BP 伪装成有效结果。

具体版本映射、证据优先级、数据契约、验收门禁、状态模板和 commit 格式以 `00_AGENT_MIGRATION_BRIEF.md` 为准。

> 已归档。当前约定见仓库根目录 `AGENTS.md` 与 `V3Development/`。
