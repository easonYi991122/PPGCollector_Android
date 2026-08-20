# V3.0 开发资料（当前工作入口）

本目录是 **V3.0 增量开发** 的唯一规划入口。对代码现状的判断以 `app/src` 为准，不以旧移植文档为准。

V3.0 需求原文写的是「在 V2.0 上增量」，但当前 Android 代码并不是一份完整的 V2.0 实现。后续开发必须按本目录中的** rebase 后需求**执行，而不是按需求文档里的「现行为」字面描述执行。

## 必读顺序

1. [00_AGENT_BRIEF.md](00_AGENT_BRIEF.md) — 每轮开工约定
2. [01_CODEBASE_AS_IS.md](01_CODEBASE_AS_IS.md) — 当前代码事实（2026-08-19 代码阅读）
3. [02_REQUIREMENTS_REBASED.md](02_REQUIREMENTS_REBASED.md) — 相对当前代码的 V3.0 差距与落点决策
4. [03_DEVELOPMENT_PLAN.md](03_DEVELOPMENT_PLAN.md) — 开发方案（R1–R5 + 修正轮 R4.1 / R5.1）；当前待执行 **R5.1**
5. [status/DEVELOPMENT_STATUS.md](status/DEVELOPMENT_STATUS.md) — 当前快照（每轮结束后更新）

## 参考资料

| 路径 | 用途 |
|---|---|
| [references/需求V3.0/](references/需求V3.0/) | V3.0 需求原文 + 综合 SQI Python 算法子集 |
| [references/carried-forward-constraints.md](references/carried-forward-constraints.md) | 从旧移植工作中抽出的、仍然有效的工程约束 |

## 不在这里的内容

iOS → Android 移植期文档、M7 规划、旧产品说明已移到仓库根目录 [`archive/2026-08-ios-migration/`](../archive/2026-08-ios-migration/README.md)。那些材料**不是**本阶段开发依据；需要历史背景时再去翻，不要当需求源。
