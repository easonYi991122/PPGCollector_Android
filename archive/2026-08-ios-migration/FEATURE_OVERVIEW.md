# PPGCollector Android 功能介绍

本文概括当前 M7.7 代码已经提供的产品能力及其边界。操作步骤见 [使用手册](USER_GUIDE.md)，算法常量见 [滤波与分析参数](FILTER_AND_ANALYSIS_PARAMETERS.md)，文件字段见 [导出数据格式](EXPORT_DATA_FORMAT.md)。

## 1. 设备与采集

| 功能 | 当前实现 |
|---|---|
| BLE 发现与权限 | 支持 Android 版本分支的扫描/连接权限、10 秒有限扫描、重试和连接状态提示 |
| 设备 profile | 支持 CUP NUS 与 FFF0/FFF1 bring-up transport；按广播身份和实际 service 精确选择 |
| 协议 | 当前 168-byte batch planar UInt8 sequence、168-byte sensor planar UInt32 sequence；保留历史 408-byte raw 回放 |
| 流式解析 | 支持通知拆帧、粘包、噪声重同步、8-byte auxiliary 帧、有限缓冲和 sequence 异常统计 |
| 数据新鲜度 | 只有接受的 PPG 帧推进 freshness；重复/乱序帧不进入样本流，gap 建立断点并重置连续窗口 |
| 录制所有权 | `connectedDevice` 前台服务持有长录制；Activity 只绑定和观察 |
| 写入顺序 | 每个 BLE notification 先写原始 `.cupraw`，再解码派生 CSV 和指标 sidecar |

设备 profile 仍标记为 bring-up；真实固件控制命令、跨厂商/API 长稳和最终产品签名身份不由当前实现宣称完成。

## 2. 实时显示

- 同时显示 RED、IR，两路最多保留最近 800 个接受样本，即当前 100 Hz 合同下的 8 秒。
- 波形默认最多约 5 Hz 刷新，避免为每个采样点触发 Compose 重组。
- `RAW` 仅在绘图时把原始 ADC 取负，使收缩峰朝上，并移除当前视窗线性趋势；落盘不取负、不去趋势。
- `CAUSAL` 对取负后的显示信号执行实时 0.5–12 Hz 一阶高通加一阶低通，无前视、存在相位变化。
- `FIXED` 对取负后的显示信号执行 201-tap 对称 FIR 0.5–12 Hz，以约 1 秒固定延迟换取接近零相位的形态。
- 连接断点、sequence gap 或非法连续性会清空显示滤波和指标窗口，防止跨断点连接波形。
- 录制开始后默认进入简洁模式，使 RED/IR、五项指标、连接/断开、参考血压和停止操作尽量同屏；详细模式保留状态与算法说明。

## 3. 实时指标

所有可计算指标共享 800 点窗口、每 100 点一次的 epoch，以及同一个 `source_sample_index/source_time_s`。

| UI 名称 | 含义 | 状态 |
|---|---|---|
| 心率 HR | IR 预处理后结合峰间期、频谱和置信度得到 bpm | 有质量门控；证据不足显示不可用 |
| RR | `(RED AC/DC) / (IR AC/DC)` | 暂定诊断比值；不是呼吸率，也不直接换算 SpO₂ |
| PI | `100 × RED AC/DC` | 复用 RR 同一次 RED AC/DC；暂定 |
| SQI | 周期与模板 Pearson 相似度的 0–1 评分 | 暂定，未提升为正式质量判定 |
| BP | 收缩压/舒张压预测 | 实时模型未接入，始终显示横杠 |

SpO₂ 字段仍保留在底层兼容模型和 sample CSV 中，但由于没有正式标定，不在当前五指标 UI 发布数值。

## 4. 会话、命名与被试资料

- 会话可自由命名，限制为 1–64 个英文大小写字母、数字、`_`、`-`，并做跨平台保留名和大小写不敏感重名检查。
- `PPG-{subject}-{seq}` 会话被识别为 canonical 会话；前缀大小写自动归一化为 `PPG-`，序号从 1 开始。
- 建议名依据最近实际写入 raw 的 canonical 会话生成；全新安装显示 `PPG-subject-seq` 示例。
- canonical subject 资料采用 revision store，包含性别、年龄、身高、体重和扩展字段；每个会话另存录制时快照，保证历史可追溯。
- 资料表单在名称合法、不重复后启用，同一 subject 的后续 seq 自动预填最近修订。
- 会话不依赖数据库索引；应用从私有 sessions 目录和 metadata 重建列表与档案。

## 5. 参考血压

- 仅在正式录制中启用“血压记录”。
- 弹窗打开时立即冻结 session、connection generation、事件序号、PPG sample/time、host monotonic time 和 UTC；保存时间另行记录。
- 填写过程与弹窗不会暂停 BLE、波形、指标、raw 或 CSV 写入。
- 一次会话可追加多组收缩压/舒张压，形成按 `event_index` 和 PPG 时间排序的 sidecar 序列。
- 该功能记录外部参考真值，不是应用对血压的计算或验证。

## 6. 已保存会话与档案

- “已保存会话”是统一外层页面，默认显示被试档案，也可切换逐文件视图。
- canonical 会话按 subject 分组，seq 按数值排序；subject 摘要包含录制数、最大序号、完整/异常数、BP 组数、日期和可用 HR 证据。
- 非 canonical 或历史会话完整保留在未归档区域。
- 选择模式支持单会话、整个 subject、全选、批量 ZIP 导出和带二次确认的永久删除；同一会话会按目录去重。
- 逐文件视图额外显示后台离线分析任务和双会话平均周期对比入口。

## 7. 会话详情、复核与恢复

- 详情面板默认收起，只显示标题和关键摘要；可分别展开会话概览、来源版本、被试快照、参考血压、完整性复核、重放、离线分析、导出/恢复。
- 完整性复核从 raw 重放并核对 raw/CSV/metadata 计数、sequence 异常、截尾与边界，不修改源会话。
- 完整信号加载上限为 1,500,000 个接受样本；离开详情后释放大数组。
- 不完整会话可以创建 safe-prefix 恢复副本。恢复记录源文件 SHA-256 和复制边界，原目录保持不变。
- 单会话导出通过系统文件选择器流式写 ZIP，不暴露应用私有路径。

## 8. 波形重放与离线分析

- 重放提供同一 source-time 横轴下的 RAW、ZERO 0.5–12 Hz、FIXED 0.5–12 Hz、RED/IR、gap、稳定段、峰、1 Hz 指标和参考 BP。
- ZERO 使用每个连续段内的前向/反向 Butterworth 滤波；FIXED 重放与实时 201-tap FIR 使用同一 profile，并按源样本索引对齐。
- 离线分析只读取 `.cupraw`，识别稳定段后按 8 秒窗口、2 秒步长计算通道/极性、心率、频谱、SNR、RR MAD、AC/DC、峰和平均周期。
- 每次分析保存独立、不可覆盖的版本化 JSON，带 source raw SHA-256、算法/profile、输入计数、warnings 和分析证据；取消不会留下不完整 JSON。
- 全屏横屏工作台支持完整信号拖动、缩放、8 秒/全幅、通道和阶段切换、稳定段聚焦、窗口审计、可见范围频谱与平均周期 95% CI。
- 双会话对比对平均周期独立标准化，仅用于比较形态，不比较原始 DC/AC 绝对幅值。

## 9. 数据与性能边界

- `.cupraw` 是原始 BLE notification 真源；sample CSV 的 `red/ir` 也是未取负的原始 ADC。
- 指标和 BP sidecar 均带 PPG source cursor，可与 sample CSV 对齐。
- 波形 ring、decoder、preview/recording/analysis 队列、离线样本数和分析 JSON 大小均有界；raw 录制入口发生资源压力时不会静默丢数据，而会停止并标记不完整。
- release 构建启用 R8/resource shrinking，并打包 Baseline Profile seed；真实设备上的性能收益仍需 Macrobenchmark 度量。
- 当前不提供正式 SpO₂、预测 BP、IMU 分析、云同步、加密导出或应用内回收站。
