# PPGCollector Android agent 工作约定

当前阶段是 **V3.0 增量开发**（在现有 Android 采集 App 上迭代），不是 iOS 移植。

任何 agent 在开始新一轮、恢复工作或上下文压缩后，必须先读：

1. [`V3Development/00_AGENT_BRIEF.md`](V3Development/00_AGENT_BRIEF.md)
2. [`V3Development/01_CODEBASE_AS_IS.md`](V3Development/01_CODEBASE_AS_IS.md)（对代码的认识以源码和这份纪要为准，不以归档文档为准）
3. [`V3Development/03_DEVELOPMENT_PLAN.md`](V3Development/03_DEVELOPMENT_PLAN.md) 中的**本轮**章节
4. [`V3Development/status/DEVELOPMENT_STATUS.md`](V3Development/status/DEVELOPMENT_STATUS.md)

需求原文与综合 SQI 参考在 [`V3Development/references/需求V3.0/`](V3Development/references/需求V3.0/)。相对当前代码 rebase 后的差距在 [`V3Development/02_REQUIREMENTS_REBASED.md`](V3Development/02_REQUIREMENTS_REBASED.md)。

## 执行规则

- 先检查 `git status`，保留用户已有修改。
- 每轮只做开发方案中的一个轮次（`V3.Rn` 或 `V3.R4.1`），完成 JVM/fake BLE/文件契约验收后再停下。当前待执行见 `V3Development/status/DEVELOPMENT_STATUS.md`。
- 每一开发轮次在门禁通过并更新开发状态文档后，必须立即按现有风格创建 git commit；除非用户明确要求本轮不提交。提交只包含该轮项目文件，不混入 `.idea`、本地产物或用户无关修改。
- 默认不做真机操作；用户明确要求时再做。
- 不要把未校准的 SpO2 / 预测血压宣称为有效结果。
- 不要修改 `V3Development/references/需求V3.0/` 中的参考实现来迁就 Kotlin。
- iOS 移植期文档在 [`archive/2026-08-ios-migration/`](archive/2026-08-ios-migration/README.md)，不是本阶段需求源。
- 现有 HR/SQI/预处理回归金标在 `app/src/test/resources/signal_fixtures/`。
