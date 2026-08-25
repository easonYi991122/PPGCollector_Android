# V3.0 Agent 工作约定

本阶段任务是：在**现有 Android/Kotlin 采集 App** 上，按 rebase 后的 V3.0 需求做增量开发。原方案为五轮（`V3.R1` … `V3.R5`）；后来插入 **`V3.R4.1`**、**`V3.R5.1`**，并追加生命周期/录制稳定性 **`V3.R6`**、真机显示回归修正 **`V3.R7`**、纵轴/指标/链路诊断 **`V3.R8`**、RAW 纵轴保持 **`V3.R8.1`** 与即时 UI/诊断修正 **`V3.R8.2`**。后续双路 BP/表单闭环与详情修复信号限定在 **`V3.R9`–`V3.R10`** 两轮内。这不是 iOS 移植任务。

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
- 每轮结束后更新 `status/DEVELOPMENT_STATUS.md`（当前快照 + 本轮事实），门禁通过后立即 commit；只有用户明确要求本轮不提交时才保留未提交状态。
- 提交说明格式：`V3.Rn <本轮主题>`，一两句话写清为什么。
- 不要把未校准的 SpO2 / 预测血压宣称为有效结果。V3.0 的参考血压是人工填写，不是模型输出。
- 不要把综合 SQI 的显示状态伪装成已改变 `{stem}.csv` 的 `sqi` 列口径（见 rebase 决策）。
- CUP 168-byte batch 与 Nordic 168-byte sensor packet **都继续作为直播协议**。ads1292r 120-byte 可出现在 `Nordic_UART_Service` **以及 CUP 名前缀的开发板**上。CUP **默认**立刻按 batch 解码；仅当 batch 无接受帧时用帧几何探测 120，并允许手动覆盖。锁定 168 时 CUP 必须保持 `BATCH_COMPATIBLE`，禁止把 CUP 名切到 Nordic sensor packet。Nordic 的 120/168 仍靠几何探测，不要把全部 Nordic 默认切到 120。两种 168 不得靠 payload 互猜。
- `references/需求V3.0/` 是只读参考，不要改算法 Python 来「迁就」Kotlin；Kotlin 应对齐 Python 口径。

## 版本标识

| 标识 | 含义 |
|---|---|
| V3.R1 … V3.R10 | 已实施轮次，见 [`03_DEVELOPMENT_PLAN.md`](03_DEVELOPMENT_PLAN.md)；R9/R9.1 与 R10 自动门禁已通过，两个真机节点待测 |
| 当前代码基线 | R1–R10 已落地，R3 综合 SQI 显示口径已按 combo_sqi.py V4.4.2 收口；BLE、录制、连续性、实时/离线显示、表单/gate 与文件契约以当前 `app/src` 代码和 `status/DEVELOPMENT_STATUS.md` 为准。 |

## 证据优先级（本阶段）

1. 当前 `app/src/main` / `app/src/test` 代码
2. `V3Development/02_REQUIREMENTS_REBASED.md` 中的落点决策
3. `references/需求V3.0/` 需求原文与 `combo_sqi.py` 算法
4. 归档的旧移植文档（仅历史，不覆盖 1–3）
