# ADR-0004：CUP FFF1 8-byte 辅助帧分类

- 状态：Accepted for bring-up
- 日期：2026-08-04
- 影响：PROTO-001/002/004、CAP-003、inspection/replay、D-001、R-001

## 真实导出证据

用户提供的 `CollectedData/testdevice1.zip`（SHA-256 `1b9ba2bfee58191abcadfe03056a80b53b36bc9a00cb6558dbe833dc0700f1bf`）来自 FFF0/FFF1 新设备。其 CUPRAW1 共 272 条完整 notification record：212 条为 168-byte `0x15` PPG 数据帧，60 条为 8-byte 短帧。PPG sequence 从 154 连续回绕到 109，无 missing/duplicate/out-of-order；212×20=4240 个 replay samples，与 CSV 4240 行和 metadata 完全一致。数据帧平均间隔约 199.902 ms，对应约 100.049 Hz。

全部 60 条短帧均为 `AB BA` + function + 3-byte payload + `CD DC`，function 分布为 `0x02` 43 条、`0x06` 4 条、`0x0C` 4 条、`0x0F` 9 条。旧 decoder 把每条计为 invalid function 并丢弃 8 字节，因此产生截图中的 `invalid=60`、`discarded=480`，这是分类缺口，不是 PPG 数据损坏。源 ZIP/raw/CSV/session 在分析与复核中保持只读。

## 决策

1. stream decoder 仅将 function `0x02/0x06/0x0C/0x0F`、总长 8 且头尾完全正确的帧分类为 auxiliary。
2. auxiliary 帧从 stream 中消费并单独累计 `auxiliaryFrames`；不输出 PPG frame、不进入 sequence gate、不产生 CSV sample、不推进 sample index、不解除 freshness。
3. CUPRAW1 继续原样保留这些 notification，inspection/replay UI 显示辅助帧计数。
4. 不解释 3-byte payload，不将其展示为传感器值、状态或控制响应。
5. 未知 function、错误 tail、截断或其他结构继续计 invalid/discard，完整性复核仍报错；禁止泛化为“忽略所有 8-byte/非 0x15 数据”。
6. 168-byte PPG 布局、`cup-batch-168-planar-0.1`、CUPRAW1/CSV/session schema、100 Hz、算法和 UI 数据语义不变，因此本轮不提升这些版本。

## 验证与剩余项

- JVM 覆盖四种已观测 function 的 1～31 字节任意拆包，以及未知 function/坏 tail 仍为结构错误。
- production inspection 直接复核 `testdevice1` 副本后应为 272 raw records、212 data frames、60 auxiliary frames、212 accepted frames、4240 samples、0 invalid、0 structural discard、0 findings。
- D-001 仍需固件版本、FFF1 characteristic properties、四种 payload 的正式语义、FFF2 START/STOP 契约和 30 分钟 receiving/重连证据；新 function 必须先取得真实帧证据再扩充白名单。
