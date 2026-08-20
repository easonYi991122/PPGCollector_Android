# 归档：iOS → Android 移植期资料（2026-08）

本目录是 **V3.0 截断任务之前** 的移植规划、状态、参考快照和根目录产品说明。它们描述的是当时的 M 版本移植工作，**不再作为开发入口**。

当前工作入口：[`../../V3Development/`](../../V3Development/README.md)

## 为什么归档

- 需求已切到 V3.0 增量；基线是现有 Android 代码，不是 iOS 对等矩阵。
- 根目录散落的 `USER_GUIDE.md` 等会把后续 agent 带到过期「现行为」。
- `AndroidMigrationPlanning/` 体积大（含 iOS/Python 快照），留在仓库根会干扰检索。

## 内容

| 路径 | 原位置 | 说明 |
|---|---|---|
| `AGENTS.md` | 仓库根 | 旧 Codex 移植约定 |
| `AndroidMigrationPlanning/` | 仓库根 | 移植 brief、M7 规划、ADR、`reference_sources/` 只读快照 |
| `FEATURE_OVERVIEW.md` 等 | 仓库根 | M7.7 时期的使用/格式/实时说明 |

仍有用的几条工程约束已摘到 [`../../V3Development/references/carried-forward-constraints.md`](../../V3Development/references/carried-forward-constraints.md)。

单元测试用的 HR/SQI/预处理 JSON 金标已复制到 `app/src/test/resources/signal_fixtures/`，不再依赖本目录路径。需要翻历史 iOS 源码或其它金标时，再打开 `AndroidMigrationPlanning/reference_sources/`，不要当 V3.0 需求。
