# 开放决策与风险登记

## 1. 开工决策

状态说明：`Open/Block` 阻断相应里程碑；`Open/Assumption` 可按建议继续，但发布前需确认。

| ID | 状态 | 最晚时间 | 需要决定/取得的证据 | 当前建议/默认 |
|---|---|---|---|---|
| D-001 | Open/Block | Phase 0 | 已知旧设备使用 NUS；新 `CUP_FEAE89AB24A9` 报告 service/notify/write 为 `FFF0/FFF1/FFF2`。仍缺固件版本、特征 properties、通知十六进制抓包、是否需向 FFF2 发命令，以及 length/端序/RED-IR/sequence/checksum | transport profile registry 按 service 选择且不猜测 control write；当前 50 对/408-byte 只作 wire draft，见 ADR-0002 |
| D-002 | Open/Block | Phase 0 | Android 目标设备/OS/厂商清单 | 当前工程 `minSdk=26`、target/compile 37；发布前按设备矩阵和官方政策确认，并做更高 API 前向测 |
| D-003 | Open/Block | Phase 0 | 录制在后台/锁屏/划掉任务时是否继续 | 开始后由 `connectedDevice` FGS 继续；notification 明确停止；`START_NOT_STICKY` |
| D-004 | Open/Block | Phase 0 | applicationId、app 名、签名 owner、Play/企业分发 | 独立于 iOS bundle；正式 keystore 不进 repo |
| D-005 | Open/Block | Phase 3 | 内部保留、卸载删除提示、导出、是否加密/备份 | 内部 app-specific storage；显式 SAF 导出；不默认云备份；加密另 ADR |
| D-006 | Open/Block | Phase 1 | Android 是否必须与现有 iOS raw/CSV/session 双向兼容 | V1 保持兼容；新字段仅 additive，未知字段可忽略 |
| D-007 | Open/Block | Phase 1 | `soft_version`/`alg_version`/profile 正式命名和升级规则 | 开始录制固化；算法证明对等后用 `ppg-live-parity-0.1` 类名称 |
| D-008 | Open/Block | Phase 4 | SpO2/BP 的产品文案与入口 | 均为 unavailable；R 值仅诊断，不映射 SpO2 |
| D-009 | Open/Assumption | Phase 2 | 是否声明 `neverForLocation`，会否过滤 CUP | 真机覆盖 CUP 广播后再启用；按实际用途写合规说明 |
| D-010 | Open/Assumption | Phase 4 | 页面退出是否停止录制 | 默认不停止，由 FGS 持有；页面显示持续录制状态 |
| D-011 | Open/Assumption | Phase 4 | UI 刷新是否给用户配置 | V1 固定 5 Hz；内部 config 支持 1–60，专家设置以后开放 |
| D-012 | Open/Assumption | Phase 3 | 预期最长会话与最低空闲空间 | 支持至少 2 h；20 MiB 仅最低 preflight，应按 bitrate/预计时长动态提示 |
| D-013 | Open/Assumption | Phase 3 | 设备 identifier 是否可保存完整 MAC | UI/log 默认别名化；metadata 是否保留由隐私政策决定 |
| D-014 | Open/Assumption | V1.1 | 离线分析执行策略 | 短任务 app scope；需跨进程/长时则 WorkManager 或用户可见 FGS，另 ADR |
| D-015 | Open/Assumption | V1.1 | Python IMU 功能是否进入 Android | 不进入，直到有固件数据契约与需求/fixture |
| D-016 | Open/Assumption | V1.1 | SQI `provisional` 何时取消 | 获得完整 preprocessing/CPE、产品阈值与对等金标后提升版本 |

## 2. 技术风险

概率/影响：H 高、M 中、L 低。

| ID | 风险 | 概率 | 影响 | 早期信号 | 缓解/应急 | Owner 建议 |
|---|---|---:|---:|---|---|---|
| R-001 | 当前协议来自示意且硬件存在 NUS/FFF0 transport 变体 | H | H | FFF1 不主动通知、需 FFF2 命令，或 payload length/MTU/seq 与 408-byte 不符 | service 精确选 profile、禁止猜测写入；抓取 properties/通知/控制流程；wire version 化并保留 decoder fixture | Firmware + Android |
| R-002 | Android GATT 厂商差异、133、callback 晚到 | H | H | 快速重连后旧状态污染、订阅偶发失败 | generation gate、严格 close、分阶段 timeout、目标厂商矩阵 | Android |
| R-003 | 后台启动/FGS 权限随 API 演进导致 crash | M | H | API34+ SecurityException/StartNotAllowed | 可见启动、connectedDevice type、API matrix、官方规则复核 | Android/Release |
| R-004 | 通知速度超过文件 writer，raw queue 溢出 | M | H | queue high-water/flush latency 上升 | callback 极简、有界 actor、批量 buffer、性能 trace；溢出安全 stop | Android |
| R-005 | SQLite/Compose/日志在热路径造成抖动 | M | H | missing frame 与 UI jank/GC 同时出现 | raw path 不写 Room、不逐样本状态、5 Hz snapshot、基准 | Android |
| R-006 | Kotlin 数学与 Swift/Python 有数值漂移 | M | H | fixture debug 字段先偏离 | exact 算法顺序/Double/固定系数；字段级 tolerance；版本化差异 | DSP |
| R-007 | 内部数据因卸载/清理永久丢失 | M | H | 用户认为“保存”等于长期归档 | UI 提示、SAF 导出、可选备份策略；不暗示云保存 | Product |
| R-008 | 崩溃/强停时 metadata 与 raw/CSV 不一致 | M | H | incomplete session 或 JSON 截尾 | 每秒 sync、atomic metadata、文件系统扫描、inspection/recovery | Android |
| R-009 | Android 两个组件同时持有 GATT/writer | M | H | 重复目录、双倍计数、GATT busy | FGS 单一 owner、session reservation、bind-only Activity、并发测试 | Android |
| R-010 | 目标厂商电池优化终止长录制 | H | H | 锁屏/后台数十分钟后服务消失 | FGS、真机 2 h、用户可见说明；只在证据需要时引导设置 | QA/Android |
| R-011 | 未校准 R 值被误读为 SpO2/医疗值 | M | H | UI/导出出现看似合理百分数 | typed unavailable、命名“R 诊断”、产品审阅、版本/校准 gate | Product/DSP |
| R-012 | 需求名称/版本字段歧义导致不可比数据 | M | M | 同一会话版本随 app 更新漂移 | start 时固化；schema 定义；metadata golden | Android/DSP |
| R-013 | 大会话 replay/inspection 整文件载入 OOM | M | H | 内存随文件时长线性增长 | streaming reader、64 KiB record 上限、800 sample ring、2 h 门禁 | Android |
| R-014 | SAF provider 慢/断开导致录制损坏 | M | H | 直接录到云盘 provider 延迟 | 只录内部；结束后导出；导出可取消/校验 | Android |
| R-015 | 原始 PPG/设备 ID 泄漏到日志或测试包 | M | H | bug report/release APK 含数据 | release logging policy、fixtures 合成、artifact scan、FileProvider 最小 path | Security/Release |
| R-016 | Python UX 被逐控件照搬，Android 交互/性能差 | M | M | 单页巨大、主线程分析、难用 | 只提取信息架构；Compose feature 拆分；工作台 V1.1 | Product/Android |
| R-017 | full preprocessing/CPE 缺失但 SQI 被宣称最终 | H | M | provisional 标记被隐藏 | 永久保留 valid/provisional；获得金标后新算法版本 | DSP/Product |
| R-018 | target API/Play 政策在发布前变化 | M | M | 发布控制台警告 | 以官方文档为准；RC 再核查；API37 前向测 | Release |

## 3. 数据完整性威胁模型

| 威胁 | 必须观察到 | 不允许的行为 |
|---|---|---|
| BLE notification 丢失 | queue overflow/missing/decoder discard 计数和 stop reason | 静默继续并标 complete |
| 重复/乱序 frame | sequence diagnostics；拒绝进入 accepted samples | 写成新的样本导致 time/index 漂移 |
| 写 raw 失败 | 立即停止派生；保留之前 sync 的前缀 | CSV 比 raw 多却标 verified |
| 写 CSV 失败 | raw 继续已确认部分；session incomplete/可重放 | 删除 raw 或覆盖旧文件 |
| metadata 截断 | repository finding + recovery 入口 | app 启动崩溃/忽略目录 |
| 恶意导入 length | 上限/安全停止/无巨型分配 | 按声明长度无界分配 |
| 两次开始 | 第二次被 gate 拒绝 | 两个 writer 指向同目录 |
| 多个 stop 竞争 | first reason + single finalizer | reason 随线程时序随机改变 |

## 4. 产品风险与表述

- 本 app 是采集/分析工具还是医疗器械功能，决定隐私、验证、文案和发布范围。没有正式认定前，结果不应给诊断/治疗建议。
- SQI 是采集质量指标且当前 provisional；0 分可能表示无效。UI 必须同时显示有效性。
- 心率也要暴露置信度/不可用，不应在 gap 后延用旧值让用户误以为实时。
- `soft_version`、`alg_version` 和校准/profile 是可追溯数据的一部分，不是“关于”页面装饰。
- Android 内部存储在卸载时删除。录制成功提示应说明“已保存于本 app”，并给导出动作。

## 5. 每周风险审阅模板

```text
Date / build / firmware:
New evidence:
Decision changes:
Top 3 risks and trend:
Long-run counters (missing/invalid/overflow/flush p95/heap):
Parity failures by fixture/field:
Blocked milestone and owner/date:
Schema/algorithm/profile version impact:
```

任何风险通过改变文件格式、算法或后台语义来缓解时，都必须先写 ADR，再更新[需求矩阵](02_REQUIREMENTS_AND_PARITY_MATRIX.md)和[总方案](01_ANDROID_MIGRATION_MASTER_PLAN.md)。
