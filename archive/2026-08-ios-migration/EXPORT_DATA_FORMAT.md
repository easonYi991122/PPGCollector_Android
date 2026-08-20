# PPGCollector Android 导出数据格式

本文对应当前 M7.7 存储与导出代码。所有文本文件为 UTF-8；CSV 使用逗号分隔、首行固定 header，并按 RFC 4180 规则转义逗号、引号和换行。时间字符串使用 `Instant` 的 ISO-8601 UTC 形式。

## 1. 导出方式与 ZIP 层级

### 1.1 单会话 ZIP

从会话详情点击“导出源会话 ZIP”，ZIP 根目录直接包含该会话的源文件：

```text
{stem}.cupraw
{stem}.csv
{stem}.session.json
{stem}.metrics.csv          # 产生过实时指标 epoch 时存在
{stem}.blood-pressure.csv   # 保存过参考血压时存在
```

导出按 metadata/约定文件清单读取，流式写入用户选择的位置。raw、sample CSV 和 session JSON 为必需文件；已声明的可选 sidecar 若缺失，单会话导出会报错，不会生成看似完整的 ZIP。

### 1.2 多会话/档案 ZIP

从“已保存会话”选择会话或 subject 后导出：

```text
export_manifest.json
subjects/{subject}/{stem}/{session files...}
unclassified/{stem}/{session files...}
subject_profiles/{subject}.profile.json
```

- canonical 会话进入 `subjects/`，其它会话进入 `unclassified/`。
- 选择某个 canonical 会话也会带上对应 subject profile；profile 缺失时写入 manifest 的 `missing`，不伪造文件。
- 同一路径冲突时文件名追加 `-2`、`-3` 等后缀。
- 批量导出跳过实际缺失的文件，并在 `missing` 中标注 `required` 或 `optional`。
- 每个已写 entry 的未压缩大小和 SHA-256 记录在 manifest。

当前两个 ZIP exporter 都不包含会话内部的 `analysis/` 目录；ZIP 以源采集数据、sidecar 和被试 profile 为主。

## 2. 时间轴与关联键

| 字段 | 语义 |
|---|---|
| `session_id` | UUID 式会话身份；同一会话各文件的首要关联键 |
| `sample_index` | sample CSV 中从 0 开始的接受样本序号；gap 不补空行 |
| `device_time_s` | `sample_index / 100`；是接受样本轴，不是设备硬件时钟 |
| `source_sample_index` | 指标或事件所锚定的 PPG 样本 |
| `source_time_s` | 当前合同下 `source_sample_index / 100`，用于与 PPG 对齐 |
| `host_frame_time_ns` | 接收 BLE notification 时的 Android monotonic 纳秒；同一 notification 派生的样本可相同 |
| `measured_utc` / `dialog_open_utc` / `saved_utc` | 审计用 UTC；对齐应优先使用 source sample/time |

建议以 `session_id + source_sample_index` 关联指标或 BP 与 PPG；画图时可直接使用 `source_time_s`。`host_*_ns` 只在同一设备启动周期内单调，不能当跨设备 UTC。

## 3. `{stem}.cupraw`

`.cupraw` 是 append-only 原始 BLE notification 容器，是恢复和再分析真源。

```text
offset  size  type                 meaning
0       8     bytes                "CUPRAW1\0"
8       ...   repeated record:
              8 bytes UInt64 LE    host_monotonic_nanoseconds
              4 bytes UInt32 LE    chunk_length
              chunk_length bytes   original BLE notification bytes
```

- 单个 chunk 最大 64 KiB。
- 文件保留 notification 原始边界和字节，包括辅助帧、重复/乱序帧、噪声或停止边界尾部。
- `.cupraw` 不包含显示反相、去趋势、滤波、插值或派生指标。
- 扫描器以完整 record 为安全前缀；截断 header/chunk 会在检查或恢复中明确报告。

## 4. `{stem}.csv`：接受样本表

Schema 为 `ppgcollector_samples_v1` 或 `ppgcollector_samples_v2`，固定 25 列：

| # | 字段 | 类型/含义 |
|---:|---|---|
| 1 | `schema_version` | batch/legacy 为 v1；UInt32 sequence sensor packet 为 v2 |
| 2 | `session_id` | 会话身份 |
| 3 | `sample_index` | 从 0 开始的接受样本序号 |
| 4 | `device_time_s` | `sample_index / 100`，6 位小数 |
| 5 | `host_frame_time_ns` | 原 notification monotonic 纳秒，无符号十进制 |
| 6 | `frame_sequence` | v1 允许 0–255；v2 保留完整 UInt32 |
| 7 | `sample_in_frame` | 该 PPG 帧内样本位置 |
| 8 | `red` | 设备原始 UInt32 RED ADC，不取负 |
| 9 | `ir` | 设备原始 UInt32 IR ADC，不取负 |
| 10–12 | `heart_rate_bpm`, `heart_rate_valid`, `heart_rate_time_s` | 兼容的写入时快照字段；当前 production controller 通常不在此写异步 HR |
| 13–15 | `spo2_percent`, `spo2_valid`, `spo2_time_s` | 兼容列；当前无标定，通常为空/false |
| 16–18 | `sqi`, `sqi_valid`, `sqi_time_s` | 兼容的写入时 SQI；无效时值写 `0`、valid=false、time 为空 |
| 19 | `soft_version` | 录制开始时固化的软件版本 |
| 20 | `alg_version` | 录制开始时固化的采集算法版本 |
| 21 | `preprocess_profile` | 录制开始时固化的预处理 profile |
| 22 | `protocol_profile` | 实际解码协议 profile |
| 23–25 | `ratio_of_ratios`, `ratio_of_ratios_valid`, `ratio_of_ratios_time_s` | 兼容的写入时 RR 诊断字段 |

sample CSV 每个接受 PPG 样本一行；duplicate/out-of-order 和 8-byte auxiliary 不产生行。指标列只允许保存 raw 写入时传入的只读快照，已写行不会被后来完成的异步分析回填；当前 production raw-first controller 通常让这些 legacy cells 保持 unavailable。权威 1 Hz HR/SQI/R/PI 序列应读取 `{stem}.metrics.csv`。PI 没有加入这份 25 列兼容表。

## 5. `{stem}.metrics.csv`：1 Hz 指标序列

Schema：`ppgcollector_metrics_v1`。文件在首个指标 epoch 到达时创建，固定 27 列：

```text
schema_version,session_id,connection_generation,metric_epoch,
source_sample_index,source_time_s,measured_utc,
heart_rate_bpm,heart_rate_valid,heart_rate_provisional,heart_rate_reason,heart_rate_alg_version,
sqi,sqi_valid,sqi_provisional,sqi_reason,sqi_alg_version,
ratio_of_ratios,ratio_valid,ratio_provisional,ratio_reason,ratio_alg_version,
perfusion_index_percent,pi_valid,pi_provisional,pi_reason,pi_alg_version
```

- 每行是一次原子发布的 epoch；同一行四个指标共享 source sample/time。
- `metric_epoch` 在每个 `connection_generation` 内从 1 递增；断点会进入新 generation、重新热身并重新计 epoch。`source_sample_index` 沿会话接受样本轴递增，epoch 身份应使用 `(connection_generation, metric_epoch)`。
- 数值保留 6 位小数。不可用值为空，`*_valid=false`，原因写 `MetricUnavailableReason` 的 wire value。
- `*_provisional` 区分可计算但尚未正式准入的结果；当前 SQI、R、PI 通常为 provisional。
- 当前算法版本通常为 HR `ppg-ios-hr-0.1`、SQI `ppg-ios-sqi-0.1`、R `ppg-ios-rr-0.1`、PI `ppg-pi-red-acdc-0.1`。
- 本 sidecar 不含 SpO₂ 和预测 BP。

## 6. `{stem}.blood-pressure.csv`：手工参考血压

Schema：`ppgcollector_manual_bp_v1`，固定 10 列：

| # | 字段 | 含义 |
|---:|---|---|
| 1 | `schema_version` | 固定 schema |
| 2 | `session_id` | 会话身份 |
| 3 | `event_index` | 会话内从 0 递增的事件序号 |
| 4 | `dialog_open_source_sample_index` | 弹窗打开时最新接受 PPG 样本 |
| 5 | `dialog_open_source_time_s` | 对应 PPG 时间，9 位小数 |
| 6 | `dialog_open_host_monotonic_ns` | 弹窗打开时 host monotonic 纳秒 |
| 7 | `dialog_open_utc` | 弹窗打开 UTC |
| 8 | `saved_utc` | 用户点击存储 UTC |
| 9 | `systolic_mm_hg` | 手工收缩压整数 |
| 10 | `diastolic_mm_hg` | 手工舒张压整数 |

事件时间以弹窗打开时为准，`saved_utc` 只用于审计填写耗时。runtime 还会校验 connection generation，但 v1 sidecar 没有 generation 列；持久化关联键是 session 和 source cursor。事件按 `event_index` 严格递增，source sample 单调不减。

## 7. `{stem}.session.json`：会话 metadata

Schema：`ppgcollector_session_v2`。主要结构如下：

| 对象 | 字段 |
|---|---|
| 顶层身份/版本 | `schema_version`, `session_id`, `base_name`, `started_utc`, `ended_utc`, `soft_version`, `alg_version`, `preprocess_profile`, `protocol_profile`, `transport_profile` |
| 采样合同 | `sample_rate_hz`, `samples_per_frame` |
| `device` | `name`, `identifier`, `service_uuid`, `notify_characteristic_uuid`, `firmware_version`, `calibration_id` |
| 完成与计数 | `complete`, `stop_reason`, `frame_count`, `sample_count`, `raw_chunk_count`, `missing_frames`, `duplicate_frames`, `out_of_order_frames`, `invalid_frames`, `discarded_bytes` |
| `writer` | `last_flush_utc`, `raw_bytes`, `csv_rows`, `error`, `metrics_rows`, `blood_pressure_rows` |
| `files` | `raw`, `samples`, `metrics`, `blood_pressure`；未创建的 sidecar 为 `null` |
| 档案身份 | `canonical_subject_id`, `canonical_sequence` |
| `participant` | `subject_id`, `sequence`, `profile_revision_id`, `profile_complete`, `sex`, `age_years`, `height_cm`, `weight_kg`, `additional_fields` |
| `recovery` | 普通会话为 `null`；恢复副本记录策略、恢复 UTC/版本、源目录/session、各源文件 SHA-256、总字节/复制字节和 CSV session-id 保留标志 |

`complete=true` 表示 writer 以允许的停止原因且无写入错误完成；“已验证完整”还要求 metadata 可读且 metadata 声明的文件实际存在。停止原因 wire value 包括 `user`、`viewExit`、`sceneBackground`、`deviceDisconnect`、`dataTimeout`、`crashRecovery`、`writeError`、`protocolError`、`resourcePressure` 和 `unknown`。

participant 是录制时快照，不应以导出时的最新 subject profile 覆盖。

## 8. `{subject}.profile.json`：被试资料修订

仅批量/档案 ZIP 包含独立 profile。Schema：`ppgcollector_subject_profile_v1`。

```text
schema_version
subject
current_revision
updated_utc
revisions[]:
  revision_id, created_utc, sex, age_years, height_cm, weight_kg, additional_fields
```

最多保留最近 128 个修订；扩展字段最多 32 个，key 最长 64 字符，value 最长 512 字符。会话自身的 participant snapshot 仍是判断历史录制资料的首选证据。

## 9. `export_manifest.json`

Schema：`ppgcollector_archive_export_v1`。

| 字段 | 含义 |
|---|---|
| `schema_version` | manifest schema |
| `exported_utc` | 生成时间 |
| `selection_subjects` | 用户直接选择的 subject 列表 |
| `sessions[]` | 每个实际导出会话的 `session_id`, `base_name`, `subject` |
| `entries[]` | 每个源文件的 ZIP `entry`、未压缩 `size`、小写十六进制 `sha256` |
| `missing[]` | 缺失的 required/optional 会话文件或 subject profile |

`export_manifest.json` 本身不列入 `entries[]`，因为其内容在所有源文件哈希完成后生成。

## 10. 应用内部离线分析 JSON

离线结果保存在会话目录：

```text
analysis/{yyyyMMdd'T'HHmmss.SSS'Z'}_{analysis_profile}_{analysis_id}.json
```

Schema：`ppgcollector_analysis_v1`。顶层包含 source session/base/raw SHA-256、analysis/algorithm/preprocess profile、开始/结束 UTC、warnings；并包含：

- `input`：raw record/payload、解码/接受帧和样本、sequence/结构/边界计数；
- `metrics`：稳定段/窗口/接受窗口、拒绝计数、通道/极性、峰、HR/频谱 HR、confidence、SNR、RR MAD、平均周期数；
- `segments[]`, `windows[]`, `peaks[]`；
- `spectrum` 的 `frequencies_hz/power`；
- `average_cycle` 的 `phase/mean/standard_deviation/ci95/cycle_count`；
- `preview` 的局部 time/raw/filtered/peak arrays。

重新分析创建新 JSON，不修改 raw/CSV/session，也不覆盖历史。当前导出服务没有把 `analysis/` 加入 ZIP；如需把离线结果作为标准导出内容，应先升级导出 manifest 合同并补兼容测试。

## 11. 读取建议

1. 先读 `export_manifest.json`（批量 ZIP）并核对每个 entry 的 size/SHA-256。
2. 读 `{stem}.session.json` 确认 schema/profile、完整状态和实际 sidecar 名称。
3. 用 `{stem}.csv` 的 `sample_index/device_time_s/red/ir` 重建原始接受样本轴。
4. 用 `{stem}.metrics.csv` 的 `source_sample_index/source_time_s` 左连接 1 Hz 指标。
5. 用 `{stem}.blood-pressure.csv` 的 `dialog_open_source_*` 叠加参考 BP；不要用 `saved_utc` 代替采集位置。
6. 将所有 `*_valid=false` 或空值视为不可用；`*_provisional=true` 不应在下游自动提升为正式生理测量。
