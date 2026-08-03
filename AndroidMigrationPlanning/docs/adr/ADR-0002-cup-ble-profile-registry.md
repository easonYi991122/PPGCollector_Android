# ADR-0002：CUP BLE transport profile registry

- 状态：Accepted for M2 bring-up；wire protocol 与控制命令仍开放
- 日期：2026-08-03
- 影响：BLE-002、BLE-003、BLE-005、CAP-005、D-001、R-001

## 背景

既有 iOS/旧硬件使用 Nordic UART Service：service `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`、notify `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`、control `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`。2026-08-03 的新硬件 `CUP_FEAE89AB24A9` 可以被 Android 名称扫描发现，但不提供 NUS；硬件侧提供的新证据是 service `0000FFF0-0000-1000-8000-00805F9B34FB`、notify `0000FFF1-0000-1000-8000-00805F9B34FB`、write `0000FFF2-0000-1000-8000-00805F9B34FB`。

当前只有 UUID，没有固件版本、characteristic properties、FFF1 通知十六进制样本或 FFF2 START/STOP payload。名称前缀只能说明它是候选 CUP，不能证明 transport 或 408-byte wire 协议。

## 决策

1. 保留 NUS profile，并新增 FFF0/FFF1/FFF2 bring-up profile；不按设备名称或型号硬编码替换旧 profile。
2. 扫描仍按 `CUP` 名称筛选；连接并完成全部 service discovery 后，按 service UUID 从有序 registry 精确选择 profile，再发现该组 notify/control 特征并写 CCCD。
3. 两组 bring-up profile 都只订阅 notify。没有命令契约前不向 NUS control 或 FFF2 推测发送 START/STOP；`isPassiveStream=true` 表示当前 app 的安全 bring-up 策略，不是固件行为证明。
4. 协调器暴露实际选中的 profile 与发现的 service/characteristic 诊断；录制开始时把实际 profile identifier、service UUID 和 notify UUID 固化到 session metadata。
5. transport 选择不改变现有 408-byte CUP wire draft。只有成功解码并通过 sequence gate 的样本才令 freshness 变为 fresh；仅连接/订阅不会解除录制 gate。

## 后果与门禁

- 旧 NUS 与新 FFF0 硬件可使用同一状态机，未知 service 的错误会同时报告期望与实际 UUID。
- 若 FFF0 设备订阅后没有合法数据，UI 将保持 waiting/stale，录制不会开始；这通常意味着需要 FFF2 命令或 wire 协议不同，不能靠猜测修复。
- fake GATT 必须覆盖两组 service 的选择、特征发现、CCCD、通知路径、旧 generation/越序回调和“无 control write”边界。
- 真机门禁：记录固件/型号、完整 service/characteristic properties、FFF1 原始通知 hex、是否必须写 FFF2 及准确 payload/时序；随后验证 30 分钟 receiving、分片/MTU、断连重连和 raw/decoder 计数。取得这些证据前 D-001 不关闭。
