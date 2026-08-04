# ADR-0003：CUP 168-byte planar 接收协议

> 后续证据：`testdevice1` 已确认 168-byte PPG 布局与约 100 Hz，并暴露额外 8-byte 辅助帧；其分类规则见 [ADR-0004](ADR-0004-cup-eight-byte-auxiliary-frames.md)。

- 状态：Accepted for bring-up
- 日期：2026-08-04
- 影响：PROTO-001～004、CAP-003～005、REL-001、D-001、R-001

## 背景

用户为新硬件提供了明确的接收帧说明：总长 168 字节，头 `AB BA`，功能码 `0x15`，长度字段 `A1 00`（little-endian 161），1 字节序列号，随后是 20 个 little-endian UInt32 RED 和 20 个 little-endian UInt32 IR，尾 `CD DC`。该证据优先于只读 reference snapshot 中历史 408-byte、50-pair interleaved 草案。

用户同时要求只更改接收协议，其余行为保持不变。因此采样率 100 Hz、accepted sample 时间轴、800/100 live window、CUPRAW1 notification 容器、25 列 CSV、session schema、sequence 接受规则、信号算法和 UI 语义均不改变。

## 决策

1. 当前 encoder 和 golden fixture 使用 `cup-batch-168-planar-0.1`：offset 0 `AB BA`，2 为 `15`，3～4 为 `A1 00`，5 为 sequence，6～85 为 RED[0..19]，86～165 为 IR[0..19]，166～167 为 `CD DC`。
2. production stream decoder 按 length 识别当前 168-byte planar 或历史 408-byte interleaved；首个合法帧后锁定 wire profile，重置/重连前不允许静默切换布局。
3. 历史 `cup-batch-408-interleaved-legacy-0.1` 仅用于读取既有 raw/session。当前 encoder 不再产生 408-byte 帧。
4. sequence gap 的 missing sample 数按实际帧内样本数计算；当前为 20，legacy 为 50。
5. CUPRAW1、CSV 和 session schema 不升级。CSV `protocol_profile` 与 session `protocol_profile`/`samples_per_frame` 记录实际观察到的布局；BLE `transport_profile` 仍独立记录 NUS 或 FFF0。
6. 当前说明不含 checksum，因此 decoder 不新增推测性校验；FFF2 也不发送未经证实的控制命令。

## 后果与验证

- 新 168-byte golden 必须逐字节验证 header/function/length/offset/tail 和 20 组样本。
- 任意 BLE notification 分片、粘包、噪声重同步、sequence wrap/gap/duplicate/out-of-order 与有界缓冲行为保持不变。
- 历史 408-byte fixture 必须通过 direct decode、stream decode、CUPRAW1 replay 和 metadata/profile 测试。
- 30 min/2 h 模拟按 20 samples/frame 增加帧数，以保持 100 Hz、800/100 和总 accepted sample 数不变。

## 未关闭项

本 ADR 不把该布局宣称为已认证 production protocol。D-001 仍需新硬件的固件/型号、FFF1 characteristic properties 与原始通知 hex、FFF2 START/STOP 契约（若存在）、实际采样率、分片/MTU 和 30 分钟 receiving 证据。旧 NUS 硬件的真实 wire profile 也需在兼容矩阵中登记。
