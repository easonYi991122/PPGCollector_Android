# 仍有效的工程约束（从旧移植工作抽出）

以下条目在代码里仍然成立，V3.0 开发应继续遵守。完整旧文档已归档，不必再当工作入口。

## 产品边界

- HR、现有 CSV `sqi` 列、ratio-of-ratios（UI 名 RR）可以显示；RR 不是呼吸率，也不是 SpO2。
- SpO2 与**预测血压**在没有校准/模型前必须显示不可用，不得填假数。V3.0 允许的是**人工参考血压**（sbp/dbp）。
- 设备 GATT profile 仍是 bring-up：被动订阅 notify，不写尚未确认的 START/STOP 控制命令。

## 录制与存储

- 长录制由 `connectedDevice` 前台服务持有；Activity 只绑定和观察。
- 原始 BLE notification 先落 `.cupraw`，再解码派生 CSV / 指标 sidecar。
- 会话目录即权威索引，没有独立数据库。
- 文本 UTF-8；CSV RFC 4180；时间 ISO-8601 UTC。
- 数据格式不兼容变化必须升 schema / algorithm version，并保留旧文件可读。

## 协议与信号

- CUP 指尖：168-byte batch，`AB BA` / `CD DC`，function/length + u8 seq，RED/IR 各 20 样本/帧，100 Hz。广播名 `CUP*` **默认**连接开始即按 batch 预览。若 batch 无接受帧（例如 CUP 名模拟腕带发 ads1292r 120），用与 Nordic 相同的帧几何探测 120，并允许手动覆盖。CUP 锁定 168 时必须保持 `BATCH_COMPATIBLE`，禁止切到 Nordic sensor packet。
- 广播名精确 `Nordic_UART_Service` 走 NUS UUID，连接方案保留。该身份上同时存在 168-byte sensor packet 与 ads1292r 120-byte；靠帧几何探测锁定，不能默认全部切到 120。
- 两种 168-byte 帧总长相同，解码器必须按连接身份显式选择，禁止用 payload 在 CUP batch 与 Nordic sensor 之间猜测。120 vs 168 可以按帧头到帧尾距离区分几何（footer 在 118 还是 166），但 168 几何必须再映射到 CUP batch 或 Nordic sensor，不得互猜。
- 同一时刻只维持一条 GATT 连接（状态机 `activeDeviceId`）。这已经构成「不能同时测」的底层约束。
- 实时波形显示滤波（0.5–12 Hz）与指标预处理（现 0.6–4 Hz）分开；显示变换不写回落盘。
- 序列 gap / 重复 / 乱序：重复和乱序拒绝；gap 打断连续窗口。

## 测试习惯

- 优先 JVM unit 与 fake BLE；真机作为用户明确要求时的门禁。
- 缓冲区必须有上限（录制队列、预览队列皆然）。
