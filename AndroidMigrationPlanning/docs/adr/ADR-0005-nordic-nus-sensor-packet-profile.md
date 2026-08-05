# ADR-0005：Nordic NUS sensor-packet 连接与接收 profile

- 状态：Accepted for bring-up
- 日期：2026-08-05
- 影响：BLE-002、PROTO-001～004、CAP-003～005、CAP-009、REL-001、D-001、R-001

## 背景

用户提供的新设备在 nRF Connect 中广播名为 `Nordic_UART_Service`，发现标准 Nordic UART Service：service `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`、TX notify `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`、RX write `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`。因此 transport 与既有 NUS 相同，但通知 payload 不是 ADR-0003 的 batch frame。

只读审计 `CollectedData/Log 2026-08-05 17_04_47.txt` 得到 97 条通知：全部为 168 bytes、头尾有效，32-bit little-endian `seq_no` 从 0 连续到 96。每帧布局为 `AB BA + UInt32 seq_no + 20×UInt32 RED + 20×UInt32 IR + CD DC`。该证据要求同一 NUS transport 根据已连接设备选择不同 wire decoder，不能仅凭 service UUID 推断 payload。

## 决策

1. 保留现有 `cup-nus-bringup-0.1` transport；扫描除 `CUP*` 外，额外只接受精确名称 `Nordic_UART_Service`，不接受同名前缀的任意广播。
2. 名称 `Nordic_UART_Service` 在连接开始时显式选择 `cup-sensor-168-planar-u32seq-0.1`；`CUP*` 设备继续选择现有 batch-compatible mode。服务发现仍必须验证 NUS UUID 与 TX notify/RX control 特征。
3. sensor packet 固定 168 bytes：offset 0～1 header，2～5 UInt32 LE sequence，6～85 RED[20]，86～165 IR[20]，166～167 tail；没有 function、length 或 checksum 字段。
4. wire mode 随 connection generation 进入 raw notification、preview 和 recording queue。单次连接/录制不允许 mode 静默变化；变化时录制以 protocol error 停止。
5. sequence tracker 对 batch profile 使用 8-bit 模运算，对 sensor packet 使用完整 32-bit 模运算；duplicate/out-of-order/gap acceptance 语义不变。
6. `CUPRAW1` 容器和 session JSON schema 不变。新会话 metadata 固化 sensor protocol profile，inspection/recovery/offline replay 先读取 metadata 再选择 decoder。
7. CSV 仍保持相同 25 列和 header；sensor packet 行使用 `ppgcollector_samples_v2`，唯一 wire 语义变化是 `frame_sequence` 可保存完整 UInt32。旧 profile 继续写/读 `ppgcollector_samples_v1` 的 0～255 sequence。
8. 采样算法暂继续沿用项目既有 100 Hz 合同。nRF Connect 通知时间受连接间隔、日志调度和批量传输影响，不单独作为固件采样率证明；真实 sampling cadence 仍属于 D-001 真机门禁。

## 后果与验证

- 新 decoder 必须覆盖 exact offsets、UInt32 LE、任意分片/粘包/噪声、坏尾和有界 pending buffer。
- fake GATT 必须证明精确设备名进入 NUS、传播 sensor wire mode 且相似的无关名称被过滤。
- preview、raw-first recording、CSV v2、metadata-driven replay、inspection 与 offline gap 必须使用同一 mode/sequence 语义。
- 旧 NUS/FFF0 batch、8-byte FFF1 auxiliary 和历史 408-byte replay 回归必须保持通过。

## 未关闭项

本 ADR 仍是 bring-up 决策，不证明固件/型号、广播名稳定性、实际采样率、MTU/分片、RX 控制命令、30 分钟 receiving、断连重连或后台长录制已经完成真机验收。若量产设备名会变化，应取得稳定 manufacturer/service-data 身份字段后再升级扫描身份策略，不能扩大为接受任意 NUS 设备。
