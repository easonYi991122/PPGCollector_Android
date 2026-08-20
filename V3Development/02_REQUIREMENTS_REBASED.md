# V3.0 需求 rebase（相对当前代码，而不是相对 V2.0 文档）

需求原文：`references/需求V3.0/ref/测试软件需求V3.0.md`。原文假设 App 已是完整 V2.0。当前代码不是。下表以 **01 的代码事实** 为起点，重写「要做什么」和「落到哪套现有契约」。

---

## 0. 总原则

1. **不把现有文件契约改成需求文档里的 V2.0 文件名。** `{stem}.json` 继续实现为 `{stem}.session.json`；主 PPG 文件仍是 `{stem}.csv` + `{stem}.cupraw`。训练侧若按 `{stem}.json` 对接，在文档中写明映射即可，不要为对齐文档去改已经在用的目录。
2. **V3.0 新增键写进现有 JSON，缺键当默认。** 旧会话必须仍能读。
3. **需求里的「现行为」若与代码不符，以代码为准描述差距，以 V3.0 目标为准改代码。**
4. **综合 SQI 是新的显示/仲裁模块**，不替换 CSV `sqi` 列的现有模板匹配口径（对齐需求 7.4）。
5. **腕部连接方案保留**：继续用 NUS + `Nordic_UART_Service` 扫描订阅，不重做 GATT。R4 在该身份上增加 120-byte 解析，并用帧几何探测在 120 / 既有 168 sensor packet 之间锁定；CUP 168 batch 与 Nordic 168 sensor **直播路径都保留**。

---

## 1. 字段与文件映射

### 1.1 需求 `{stem}.json` → 代码 `{stem}.session.json`

| V3.0 键 | 落到现有结构 | 说明 |
|---|---|---|
| （无设备类型字段） | stem 前缀 `PPG-` / `MB-` | 不新增 JSON 设备类型 |
| `smoking_freq` / `drinking_freq` | `participant` 内一等字段，并写入 subject profile | 空为 `""`；旧文件缺键当未填 |
| `gender` / `gender_code` | 继续用已有 `participant.sex`；新增 `gender_code`（男=1，女=0） | 不把 `sex` 改名为 `gender`（会破坏旧 profile） |
| `sbp` / `dbp` | **会话级** metadata 顶层（或 `reference_bp` 对象），不是 profile | 录前选填；回看可改；不进被试档案库 |
| `bp_updated_at` | 同会话 metadata，选做但 R5 一起做 | 回看修改时写入 |
| `planned_duration_s` | 会话 metadata；手动模式 `null` | R2 |
| `ecg_file` / `ecg_sample_rate` | `files.ecg` + 顶层 `ecg_sample_rate`；无 ECG 为 `null` | R4 |
| 需求未写但代码已有 | `base_name`, `session_id`, device, writer, recovery, metrics… | 全部保留 |

### 1.2 血压双轨

| 旧实现 | V3.0 目标 | 决策 |
|---|---|---|
| 录制中弹窗，多条 `{stem}.blood-pressure.csv` 事件 | 录前选填一对 sbp/dbp；录中无入口；回看可改 | **新会话以 session.json 的 sbp/dbp 为准**；去掉录中「血压记录」按钮。旧 CSV 仍可读、仍可导出。新会话不再为参考血压写事件 CSV（避免两套真值）。 |

### 1.3 ECG 文件

按需求新增 `{stem}_ecg.csv`（schema `ppgcollector_ecg_v1`）。主 `{stem}.csv` 列定义不改为塞 ECG。导出/删除/恢复必须带上该文件（若存在）。

---

## 2. 增量项 → 代码差距

### 2.1 命名 `MB-{subject}-{seq}` 与双命名空间

**代码现在：** 只 parse / 建议 `PPG-`；唯一性只有目录名。

**要做：**

- 用户在命名表单选择前缀：指尖 PPG / 腕部脉搏，默认上次选择（SharedPreferences 即可），**不根据已连接设备自动改前缀**。
- Canonical 支持 `PPG|MB`，小写前缀归一成大写。
- 文件名全局唯一（已有）。
- 新增逻辑唯一性：`(prefix, subject, seq)`。`PPG-S001-1` 与 `MB-S001-1` 允许共存。
- 建议序号：当前所选前缀下该 subject 的 max(seq)+1。空 raw 会话仍不占序号。
- 失焦提示区分【文件名重复】与【次数重复】（文案带前缀）。
- 自由命名（非 canonical）仍允许，只做语法 + 文件名唯一；逻辑序号规则不适用。

### 2.2 性别 / 吸烟 / 饮酒

**代码现在：** `sex` 自由文本；无吸烟饮酒。

**要做：**

- 性别：男 / 女单选，必填（开始录制拦截）。存 `sex=男|女`，`gender_code=1|0`。档案回填非法/空则清空待重选。
- 吸烟频率：`不吸烟` / `偶尔` / `经常` / `每天`，选填，默认「请选择」。
- 饮酒频率：`不饮酒` / `偶尔` / `经常` / `每天`，选填。
- 写入会话 snapshot 与 subject profile；不落编码列到 CSV。

### 2.3 血压时机

**代码现在：** 与 V3.0 相反（录中录入、回看只读）。

**要做：** 录前表单选填 sbp/dbp（20–300 / 10–250，收缩压须高于舒张压；未填不拦截）。录中只展示已填值。回看可改并只写该会话 JSON（R1 写字段与录前 UI，R5 做回看编辑，避免 R1 同时改采集页和详情写回路径过宽）。

### 2.4 录制就绪提示

**代码现在：** 单条短路失败；不检查被试必填。

**要做：** 门控改为**收集全部未满足项**，按钮下方逐项列出。全部满足显示「✓ 可以开始录制」。

rebase 后的就绪清单（对齐需求 6.1，并补上代码里已有的 Android 约束）：

| 条件 | 未满足提示（方向） | 相对代码 |
|---|---|---|
| 名称合法 | 字符/长度/保留名 | 已有，改成可并列 |
| 文件名不重复 | `文件名重复：{stem} 已存在` | 已有 |
| (prefix,subject,seq) 不重复 | `次数重复：…` | **新增** |
| 设备已连接且进入 Subscribed/Receiving | `未连接设备，请先扫描连接` | 已有 DeviceNotReady |
| 数据流 FRESH | `等待设备数据读入，波形出现后可开始录制` | 已有 StreamNotFresh，改文案 |
| 未在录制（含 STOPPING） | `正在录制中` | 已有 |
| 被试必填：性别单选 + 年龄 + 身高 + 体重 | `请补齐被试信息必填项：…` | **新增拦截**（代码今天不拦） |
| 存储 ≥ 20 MB | `存储空间不足，请清理后重试` | 已有 |
| 通知权限 / FGS | 保留现有 Android 文案 | 已有，一并列出 |

sbp/dbp、吸烟、饮酒**不拦截**。

### 2.5 录制模式：定时 / 手动

**代码现在：** 只有手动停止。

**要做：** 录前二选一。定时默认 60 s，范围 10 s–3600 s。模式与时长开始后锁定。停止键：定时显示「停止 (剩 m:ss)」；手动显示「手动停止」。倒计时按**已接受 PPG 样本数 / 采样率**，断流暂停。到时 finalize 与手动停止同一路径。metadata `planned_duration_s`。到时过程中禁止再 start。异常断连不等待补时长。

### 2.6 综合 SQI 颜色与提示

**代码现在：** 8 s 窗、0.6–4 Hz、标准化、阈值 0.90/0.70、UI 只显示两位小数。

**要做：** 按 `combo_sqi.py` V4.4.2 在**后台 1 s 指标帧**上仲裁 + 去抖（进 2 帧 / 离 pressure 2 帧）。显示「颜色 + 提示 + 综合分」。输入口径：滤波后 SQI_tm（取负 → 滑动平均 2/2/10 → 0.5–12 Hz，无 CPE、无标准化）；SQI_corr；flat；压力 4 闸门。窗长 5 s。CSV `sqi` 列保持现有 `TemplateMatchSqi` 口径。ECG 不参与。腕部压力分支可留配置开关，默认先与 CUP 同一套，避免 R3 再分叉。

`sqi_state` 列：需求选做。五轮内**不做**，以免改 CSV schema；状态可离线用算法重算。

### 2.7 腕部 ECG 与 ads1292r（三条直播协议，因设备而异）

**代码现在：**

直播协议已经按设备身份选择，连接开始时固化 `activeStreamProtocolMode`，同一 connection generation 不切换：

| 广播名 | GATT | 直播协议 |
|---|---|---|
| 前缀 `CUP` | NUS 或 FFF0 | `BATCH_COMPATIBLE`（168，function/length + u8 seq，RED×20 / IR×20） |
| 精确 `Nordic_UART_Service` | NUS | `SENSOR_PACKET_168`（168，u32 LE seq，RED×20 / IR×20） |

两种 168 帧总长相同，代码**禁止**按 payload 互猜。没有 ECG、没有 120-byte、没有第三种广播名或 UUID。

**需求现在：** 第 5 章把腕部帧换成 `ads1292r_packet_t`（120 B）。第 1.1 / 1.3 节写明腕部**暂无类型码**、连接方案保持现状、**不要求** App 按设备类型自动判定。该「不自动判定」原文针对的是命名前缀 `PPG-`/`MB-`，不是线协议。需求与代码都**没有**用广播名、GATT UUID 或类型码把 ECG 120 与既有 Nordic 168 区分开的方案。

**要做（R4）：**

- 连接：仍 NUS + `Nordic_UART_Service`（及现有 CUP 扫描）。CUP **默认**连接开始即按 batch 预览。
- 新模式 `ADS1292R_120`：帧 120 B，`AB BA` + u32 seq + ECG×20 + RED×4 + IR×4 + `CD DC`；小端；25 帧/s；ECG 500 Hz，PPG 100 Hz。
- **三条直播协议都要能采：** CUP batch 168、Nordic sensor 168、ads1292r 120。禁止把 `Nordic_UART_Service` 一律默认切到 120（会弄坏仍吐 168 的 Nordic 设备）。168 sensor 解码器不仅留给旧 cupraw 回放，也留给新的 Nordic 168 录制。
- **Nordic 120 vs 168 的选择：** 不能靠设备身份。不要用命名表单的指尖/腕部前缀去绑解码器（需求 1.3：前缀由用户选，与线协议无关）。R4 采用 **帧几何探测 + 超时手动覆盖**，详见 [`03_DEVELOPMENT_PLAN.md`](03_DEVELOPMENT_PLAN.md) V3.R4。
- **R5.1：** ads1292r 120 也会出现在 **CUP 广播名的开发板**上（模拟腕带走 CUP 身份、payload 却是 120 字节）。CUP 不得一开始就当 ECG；仅当 batch 无接受帧时探测 120 / 出示手动覆盖。CUP 的 168 锁定仍是 `BATCH_COMPATIBLE`，禁止把 CUP 名切到 `SENSOR_PACKET_168`。
- 可录制仍只看 PPG 新鲜度；探测未锁定前视为非 FRESH。ECG 丢帧只计数。
- 实时增加 ECG 波形（并列或通道切换）；显示可抽点，落盘原样 ADC、不取负。无 ECG 协议不画第三条。
- `{stem}_ecg.csv` + metadata 指针；CUP 与 Nordic 168 会话 `ecg_file=null`。

### 2.8 双设备互斥

**代码现在：** 单连接已经互斥。

**要做：** 不新做双连接。补产品文案：已连接时必须先断开再换机；换机后重新确认命名/表单。不要根据设备自动改 `PPG-`/`MB-`。

### 2.9 档案与导出

**代码现在：** `subjects/{subject}/{stem}/`，无前缀子目录，无 ECG。

**要做：** 档案视图按 `PPG` / `MB` 分小节（同一 subject 不拆库）。批量导出可再分 `subjects/{subject}/PPG|MB/{stem}/`。三件套（csv + session.json + 可选 _ecg.csv）以及现有 cupraw/metrics/旧血压 CSV 一起走。删除会话删除 ECG。

---

## 3. 明确不做（五轮内）

- 把 `{stem}.session.json` 重命名为 `{stem}.json`
- 把 `sex` 重命名为 `gender`
- 训练侧 `subject_info.csv` 聚合脚本（需求写明非 App 范围）
- ECG-SQI
- 预测血压 / 校准 SpO2
- 自动识别腕部类型码（广播名 / UUID 不够用来分 120 与 Nordic 168；R4 用帧几何探测，不是类型码）
- 用命名前缀 `PPG-`/`MB-` 选择线协议
- 把全部 `Nordic_UART_Service` 默认切到 120-byte（会丢掉既有 Nordic 168 直播）
- 用 payload 在两种 168-byte 帧之间猜测（CUP batch vs Nordic sensor 总长相同，必须靠广播名）
- 同时连接两台设备
- 改 CUP 168-byte 帧格式
- 用综合 SQI 覆盖 CSV `sqi` 列
- 重写离线分析工作台（除非文件清单迫使导出/恢复修改）

---

## 4. 需求原文「现行为」纠错（避免后续 agent 按错基线）

| 原文假设 | 代码实际 |
|---|---|
| 腕部已可采集，协议待升级 | Nordic 可连，直播仍是 168-byte sensor packet；与未来 120-byte ECG 共用 `Nordic_UART_Service`，无类型码可分 |
| 录中弹窗录血压 | 是，且回看不能改 |
| 开始按钮原因不透明 | 已有**单条**原因，缺逐项列表和被试必填 |
| SQI 为纯数值 | 是；另有未使用的 Good/Fair/Poor 颜色常量，**不是**综合 SQI |
| V2.0 双维度唯一性已存在 | 只有文件名唯一，没有 (prefix,subject,seq) |
| 被试必填已拦截录制 | 未拦截 |
| `{stem}.json` | `{stem}.session.json` |
