# V3.0 Agent 工作约定

本阶段任务是：在**现有 Android/Kotlin 采集 App** 上，按 rebase 后的 V3.0 需求做增量开发。原方案为五轮（`V3.R1` … `V3.R5`）；**`V3.R4.1` 是额外插入的一轮**，收口 R4 直播缺口、ECG 实时显示、采集页卡顿与 R1–R3 口径修正，必须在 R5 之前完成。这不是 iOS 移植任务。

## 每轮开工必读

上下文压缩、新开一轮、或换 agent 后，必须按顺序阅读：

1. 本文件
2. [`01_CODEBASE_AS_IS.md`](01_CODEBASE_AS_IS.md)
3. [`02_REQUIREMENTS_REBASED.md`](02_REQUIREMENTS_REBASED.md)
4. [`03_DEVELOPMENT_PLAN.md`](03_DEVELOPMENT_PLAN.md) 中**本轮**章节
5. [`status/DEVELOPMENT_STATUS.md`](status/DEVELOPMENT_STATUS.md)

需要算法或协议细节时再打开：

- `references/需求V3.0/ref/测试软件需求V3.0.md`
- `references/需求V3.0/ref/综合SQI指标计算流程.md`
- `references/需求V3.0/code/`（综合 SQI 参考实现）
- [`references/carried-forward-constraints.md`](references/carried-forward-constraints.md)

**不要**把 `archive/2026-08-ios-migration/` 当作需求或现状来源。代码与文档冲突时，以 `app/src` 代码为准，并以本目录文档中的 rebase 决策为准。

## 执行规则

- 先看 `git status`，保留用户已有修改；不要回滚无关改动。
- 每轮只做 [`03_DEVELOPMENT_PLAN.md`](03_DEVELOPMENT_PLAN.md) 指定的那一轮，做完可验收再停下。
- 默认验收：JVM unit / fake BLE / 文件契约测试。真机门禁标为待执行，除非用户明确要求真机。
- 每轮结束后更新 `status/DEVELOPMENT_STATUS.md`（当前快照 + 本轮事实）。用户要求提交时再 commit。
- 提交说明格式：`V3.Rn <本轮主题>`，一两句话写清为什么。
- 不要把未校准的 SpO2 / 预测血压宣称为有效结果。V3.0 的参考血压是人工填写，不是模型输出。
- 不要把综合 SQI 的显示状态伪装成已改变 `{stem}.csv` 的 `sqi` 列口径（见 rebase 决策）。
- CUP 168-byte batch 与 Nordic 168-byte sensor packet **都继续作为直播协议**；R4 只在 `Nordic_UART_Service` 身份上增加 `ads1292r` 120-byte。CUP 仍按广播名锁定 batch，禁止改成 ECG 帧。Nordic 的 120/168 无法靠广播名区分，按 R4 的帧几何探测锁定，不要把全部 Nordic 默认切到 120。
- `references/需求V3.0/` 是只读参考，不要改算法 Python 来「迁就」Kotlin；Kotlin 应对齐 Python 口径。

## 版本标识

| 标识 | 含义 |
|---|---|
| V3.R1 … V3.R4、V3.R4.1、V3.R5 | 开发轮次，见 [`03_DEVELOPMENT_PLAN.md`](03_DEVELOPMENT_PLAN.md)；当前待执行 **V3.R4.1** |
| 当前代码基线 | R1–R4 骨架已合入：MB 命名、定时录制、综合 SQI 显示近似、ads1292r 协议/writer；**Nordic 120 预览未切解码器，不能按 R4 验收视为闭环** |

## 证据优先级（本阶段）

1. 当前 `app/src/main` / `app/src/test` 代码
2. `V3Development/02_REQUIREMENTS_REBASED.md` 中的落点决策
3. `references/需求V3.0/` 需求原文与 `combo_sqi.py` 算法
4. 归档的旧移植文档（仅历史，不覆盖 1–3）
