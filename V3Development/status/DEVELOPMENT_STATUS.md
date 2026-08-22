# V3.0 开发状态（简版）

更新：2026-08-22
当前轮次：**V3.R10（编码与完整自动门禁已完成；R9/R10 真机节点待测）**

> 本文件后面的 R9/R9.1、R8.2/R8.1/R8/R7/R6/R5.1 记录保留作历史基线；以下先记录 R10 实际状态。

## R10 本轮落地

- 离线重放显式拆为 raw evidence input 与 repaired analysis input：两者共享原 accepted ADC 和顺序；时间统一为零基 `index / 100 Hz`。gap 只保留 break 位置与 missing 统计，不插值、不补点、不回写 `.cupraw`；ZERO、FIXED、离线窗口和指标后备消费连续 repaired input，RAW 可继续显示断点证据。
- 新 analysis artifact 在既有 raw SHA-256 之外记录 `accepted-order-gap-compression-v1`、repair gap 数与 repaired 点数；三项均 optional decode，旧 artifact 可读。
- metrics sidecar 加载区分 `PERSISTED_VALID`、`PERSISTED_NO_VALID_VALUES`、`SIDECAR_MISSING`、`SIDECAR_INVALID`。有效录制期指标优先；其余状态基于 repaired input 离线重算并展示来源/有界失败原因，无结果时明确显示不可用。详情波形区无需 artifact 也可显示该指标时间轴；指标与人工 BP 均按 accepted cursor 对齐。
- 详情与横屏工作台增加「显示缺帧标记」开关：低密度默认开、高密度默认关。marker 先以低 alpha、像素去重方式绘制，波形后绘制；只有 RAW path 保留断点，ZERO/FIXED 连续。诊断数字不受开关影响。

## R10 JVM/构建验收

- fixture 覆盖无 gap、单 gap、高密度 gap、duplicate/out-of-order/坏帧混合、raw hash 不变、repaired 点数/时钟、ZERO/FIXED、8 s 窗口、metrics 四态、BP 对齐、旧 artifact 和 marker policy。
- `env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew test lintDebug assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy --no-configuration-cache --no-daemon`：通过；231 JVM tests、0 failure/error/skip；lint、Debug、AndroidTest、Release 与 API/lifecycle/BLE/privacy contracts 均成功。
- 未执行真机。用户在开发板检修期间明确要求继续完成 R10；因此 R9 真机节点和 R10 真机节点都仍标记待测。R10 节点应分别导入/打开开发板修复前高 gap 会话与修复后会话，对照 RAW 诊断、ZERO/FIXED、指标来源、BP 对齐和 marker 开关。

## R9/R9.1 本轮落地

- Android GATT 在连接成功后、服务发现前请求 ATT `MTU=247`；记录实际回调值。请求被拒、回调失败或 5 s 超时均显示 FALLBACK 并继续服务发现，不因 MTU 单项失败断开。fake BLE 覆盖请求顺序、实际 517、拒绝、超时和迟到回调。
- 录前完整 BP 在会话 writer 接受时写成第 0 条手工 BP 事件（source index/time 为 0，UTC 使用 started time），录中事件从第 1 条继续；metadata BP 仍保留，二者可同会话共存。
- BP、notes 与未提交 BP dialog 在实际录制进入 FINALIZED/FAILED 后清空；notes 从 subject profile 保存与回填中过滤。人口学和生活方式资料仍可按同一 subject 继承。
- gate 磁盘事实带被评估名称，录制终态立即 invalidate/刷新；service duplicate failure 仅在当前名称仍匹配事实时参与 gate，改名/表单修改会清除事务失败。无效定时时长进入 typed gate，不再回落 60 s；dirty draft 只在同一 canonical subject 内保留。
- metadata 新增 optional `record_mode`，恢复副本保留；详情页补齐 PPG/MB、模式、计划/实际时长、吸烟/饮酒、notes、录前会话级 BP 和手工时间轴事件数，旧会话模式显示「未记录」。

## R9/R9.1 JVM/构建验收

- `env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew test lintDebug assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy --no-configuration-cache --no-daemon`：通过；223 JVM tests、0 failure/error/skip；lint、Debug、AndroidTest、Release 与 API/lifecycle/BLE/privacy contracts 均成功。
- 未执行真机。真机节点需同一会话录前 BP + 两条录中 BP，核对停止后 BP/notes 清空、原名即时重复、改名解除、详情三条事件，并同时记录 MTU 请求/实际/FALLBACK 和 gap 诊断。用户已要求在开发板检修期间先完成 R10 编码；该 R9 节点仍未被自动门禁替代。

## R8.2 本轮落地

- 录制中手工 BP 的 M7.6 底层契约一直存在，本轮恢复底栏「血压记录」入口。录制前 BP 转开始时事件、表单清理和 gate 失效在 R9 实施。
- 实时波形不再在每个 sequence gap 处主动切断 path；高 gap 滚动时的大量短段闪烁因素已移除。gap 依然以独立图下短刻度带和诊断数字呈现，不覆盖生理曲线。
- 诊断行增加缺帧率和异常帧率估算；无帧证据时显示「—」。App 丢块和 decoder 弃字节仍单独显示，不做不可证的帧数换算。
- 开发规划已将剩余任务限定为 R9–R10 两轮：R9 收口双路 BP/表单/gate/详情元数据，R10 收口 gap-aware repaired signal、指标来源和详情 marker 层级。每轮各有一个真机验证节点。

## R8.2 JVM/构建验收

- 定向 `LiveWaveformComponentsTest` 通过；覆盖 marker 映射、诊断证据、比例分母和空证据。
- `env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew test lintDebug assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy --no-configuration-cache --no-daemon`：通过；214 JVM tests、0 failure/error/skip，lint 通过，Debug、AndroidTest、Release 与 release privacy/lifecycle/BLE contracts 均成功。
- 未执行真机。R9 编码完成后执行第一个真机节点，未通过前不进入 R10。

## R8.1 本轮落地

- 剩余 RAW“漂移”仍来自坐标轴：R8 虽只参考最近 200/600 点，但 `WaveformPanel` 每约 200 ms 直接采用新 min/max，滚动极值会移动纵轴中心，使未改变的历史 ADC 点在屏幕上缓慢上移或偶发回跳。
- RED/IR RAW 现使用有状态坐标轴：按 preview/recording 来源、signal generation、模式和通道隔离；首次候选建立固定中心和 15% 余量；范围内波动不改上下界；越界时保持中心、至少按 25% 阶梯对称扩展；本来源内不自动缩轴。
- R8 的 200/600 候选窗、RAW 逐点取负、800 点显示 ring 均保留。CAUSAL/FIXED/ECG、指标、BLE、录制和 CSV 未改变。

## R8.1 JVM/构建验收

- 新增纯状态测试：候选轻微双向移动时范围逐位保持；单边越界时中心不变并对称阶梯扩展；后续候选收窄不缩轴；新状态重新定标。
- `env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew test lintDebug assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy --no-configuration-cache --no-daemon`：通过；212 JVM tests、0 failure/error/skip，lint 0 issue，Debug、AndroidTest、Release 与 release privacy/lifecycle/BLE contracts 均成功。
- 未执行真机。复验时应同时观察 RAW 右上角 ADC 数值与曲线：ADC 稳定而历史曲线整体移动才属于坐标轴问题；ADC 同步变化则是真实输入变化。

## R8 本轮落地

- RAW 数据值继续保持仅取负显示，没有重新引入逐窗口去趋势。实时面板仍绘制最新 800 点，但纵轴在未满 800 点时只参考最近最多 200 点，满 800 点后只参考最近 600 点；较老异常值退出参考窗后不再持续牵动整窗缩放。
- 波形与指标确认共用 `LivePpgSignalRuntime` 的同一 accepted-sample ring。普通 wire sequence gap 现在只累计 gap/missing 并切断绘图 path，不再清空 800 点指标 warmup、预处理或 fixed-lag；本地 accepted cursor 丢失、App preview/analysis 队列丢输入、协议/连接 generation 切换或处理失败仍硬重置，并立即清除旧指标。Preview 溢出会清掉其过期 backlog，独立 recording raw sink 不受影响。
- 原先贯穿波形的“竖线”已明确为 sequence-gap marker，并移到每个 RED/IR/ECG 面板下方的独立短刻度带；marker 不参与纵轴，波形本身仍在断点处断开。
- Preview 与 recording 快照新增一行低频诊断：已解码帧、最近完整 UInt32 `seq/Δ`、gap/缺帧、重复/乱序、decoder 丢弃字节/坏帧、App 队列丢块。诊断随既有波形/录制进度节流发布，没有逐包日志或无界集合。
- 真机判读：`App 丢块 > 0` 指向软件消费压力；decoder 丢弃/坏帧大于 0 指向字节流/帧边界；两者为 0 但 `Δ != 1` 表示 App 收到的完整帧序号已不连续，需结合板端日志再区分开发板发送和 BLE 链路丢通知。
- 未扫描时残留卡顿依用户决定暂缓，本轮没有继续修改该路径。

## R8 JVM/构建验收

- 定向覆盖：纵轴 200/600 边界；普通 gap 后指标计数继续增长并在 800 点产出；本地 cursor 丢失仍硬重置；高频 ADS gap 下 preview/recording 均填满波形并生成指标；序号、decoder 损伤与 App queue overflow 诊断；图下 marker 映射与诊断文案。
- `env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew test lintDebug assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy --no-configuration-cache --no-daemon`：通过；211 JVM tests、0 failure/error/skip，lint 0 issue，Debug、AndroidTest、Release 与 release privacy/lifecycle/BLE contracts 均成功。
- 未执行真机。下一次真机需要在问题发生时记录详细模式下的诊断行，并导出对应问题会话；ECG 异常仍需把屏幕时刻与 `_ecg.csv` 同时间点对照。

## R7 本轮落地

- 根因已按代码路径分层：未扫描时 preview 没有 worker/tick，残留卡顿主要来自空页面仍首组装完整波形/指标、记录表单的冷组合、重复启动 I/O bootstrap，以及空闲 `BIND_AUTO_CREATE` 录制 service；不是 BLE decoder 或实时算法在无数据时空转。
- 空波形改为轻量等待内容；记录区把设备/模式和名称拆为两个 lazy item；建议名/profile/gate disk 首次加载合为一次 I/O bootstrap。空闲 Activity 不再创建录制 service，开始录制后才启动并绑定。
- 实时 RAW 恢复为稳定的仅取负显示，不再对增长/滑动视窗反复拟合趋势。普通 sequence gap 只重置指标算法 warmup，保留 display causal/fixed 状态和三窗历史；FIXED warmup 时可选择，准备好后自动生效，断点按 fixed source cursor 绘制。
- ECG parser 与 CSV 的 uint32 原始口径未改；显示由每 5 点硬抽样改为跨 chunk 有状态的 5 点均值。App 输出不会超出对应 raw 五点范围；若 `_ecg.csv` 同一时刻存在尖峰，归入开发板/ADS1292R 前端排查。

## R7 JVM/构建验收

- 定向测试覆盖：实时 RAW 视窗增长不改历史点；CAUSAL/FIXED 普通 gap 连续性；FIXED warmup 选择；fixed 断点 cursor；ECG 5:1 跨 chunk 与范围；空信号轻量组合策略；fake ADS gap 后三窗和 FIXED 保持。
- `env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew test lintDebug assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy --no-configuration-cache --no-daemon`：通过；204 JVM tests、0 failure、0 skip，lint 0 error，Debug/AndroidTest/Release 构建和 release privacy/lifecycle/BLE contract 均通过。
- 未执行真机与 Macrobenchmark。仍需真机复验：冷启动/未扫描滚动、记录表单首次出现、连续与带 gap 的 RAW/CAUSAL/FIXED、ECG 屏幕尖峰和 `_ecg.csv` 同时间点对照。

## R7 后结构整理

- 项目主代码规模为 76 个 Kotlin 文件、约 2.14 万行，现有按 BLE、协议、信号和会话数据分包的粒度合理；未做批量合并或跨包搬迁。
- 将录制设置表单及输入辅助组件从 1287 行的 `LiveCaptureScreen.kt` 移到 `CaptureSetupComponents.kt`，并删除无调用的 `LegacyCaptureSetupCard`；采集页主文件降至约 638 行，运行时状态和 UI 调用契约不变。
- `:app:compileDebugKotlin` 通过；完整 `test lintDebug assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy` 门禁通过，包含 Release 隐私、生命周期与 BLE transport contracts。

## R6 本轮落地

- Android BLE transport 由应用创建专用 `HandlerThread`，Coordinator 用单一 owner lock 串行化平台事件、控制操作和 preview freshness；`ValueReceived` 不再把逐包诊断推回 Compose。Activity 不可见时 preview worker/clock 停止，录制 raw sink 仍独立接收。
- Preview/analysis worker 改为按需启动；连接离开接收态、换 generation 或关闭时清空有界队列并请求退出。`BlePreviewRuntime.diagnostics()` 提供测试用队列/worker/clock/发布计数，不进入根 UI StateFlow。
- ADS 120 录制和 preview 共用序号决定；duplicate/out-of-order 不进入派生 PPG/ECG，accepted PPG×4 与 ECG×20 在同一 `LivePpgSignalRuntime.ingest` 事务中发布。Gap 保留三通道显示历史、记录共同 segment-break 偏移，增加 continuity epoch/gap 统计并重置算法 warmup。
- 录制进度 StateFlow 限制为最多 5 Hz；FGS 的 writer/容量检查放到 I/O dispatcher，并在事务线程重新校验 BLE generation/phase/protocol/freshness；连接 generation/phase/stale grace 监视会以 `DEVICE_DISCONNECT` 或 `DATA_TIMEOUT` 确定性 finalize。profile revision 在录制开始被 writer 接受后才写入。
- 会话名建议/门控扫盘、profile 读取移到 I/O；主 Activity 不再在每次 `onStart` 全量刷新 sessions，保存会话页首次可见时刷新。metrics/BP/ECG sidecar 扫描改为有界流式读取，ECG 增加 header/session/time/row-count inspection；recovery 复制 ECG 安全前缀。
- ADS metadata 记录观察到的 PPG samples/frame；Subject archive 按 `PPG`/`MB` 分节并保留 metadata identity prefix。参考 BP UI 与 metadata editor 均阻止 `SBP <= DBP`；CaptureStartGate 使用 typed participant validation，不以文案前缀分流。
- Combo SQI pressure height 改为按拍内 P2/P1 估计，不再恒等于 0.5；离线 PPG/BP 两项历史 JVM 回归已修复。Compose 记录表单拆成 identity、participant、reference、start 四个有稳定 key 的 LazyColumn item。

## R6 JVM 验收

- `env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew :app:testDebugUnitTest --no-configuration-cache --no-daemon`：通过（197 tests，0 failure）。
- 完整门禁 `env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew test lintDebug assembleDebug assembleDebugAndroidTest :app:verifyReleasePrivacy --no-configuration-cache --no-daemon`：通过；包含 `verifyReleaseBleTransportContract`、`verifyReleaseLifecycleContract`、`verifyReleasePrivacy`，共 197 tests、lint 0 errors，Debug/Release 与 AndroidTest APK 均成功构建。
- 已额外通过 BLE/protocol/recording/inspection/Combo 定向测试；未执行真机、后台 30 s Macrobenchmark 与真实 BLE 长流，均待测。

## 当前事实

- Android 采集 App 已能扫描/连接 CUP（名称前缀 `CUP`）和 `Nordic_UART_Service`（NUS），解析 CUP batch 168、Nordic sensor packet 168、以及 ads1292r 120（CUP 名前缀与 Nordic 精确名均可锁定）。
- R1–R8.1 产品与稳定性修正已落地；当前实现以 `app/src` 与本状态文件为准。
- Combo SQI Python 全量对齐、部分离线分析回归失败不在本轮。
- 规划与参考已收口到 `V3Development/`。旧 iOS 移植文档在 `archive/2026-08-ios-migration/`。

## R5.1 本轮落地

### A. CUP 默认 batch；失效则测 120；手动覆盖

- CUP 连接立刻按 `BATCH_COMPATIBLE` 预览；同时跑帧几何 probe。
- 任一接受的 batch 帧取消 probe，保持 batch，不出覆盖按钮。
- 连续 3 个独占 120 票且尚无接受 batch → 锁 `ADS1292R_120`，reset 预览并回放 pending。
- 订阅后 2 s 无接受 batch 且未锁 120 → `protocolProbePending/TimedOut`，文案「无法识别数据协议」，按钮「腕部 ECG (120) / CUP PPG (168)」；168 覆盖强制 `BATCH_COMPATIBLE`，禁止 `SENSOR_PACKET_168`。
- Nordic 仍立刻 pending，probe 120 vs sensor packet 168；按钮「腕部 ECG (120) / Nordic PPG (168)」。
- `selectStreamProtocol` 按身份映射；`selectNordicProtocol` 为包装。
- Probe：缓冲区不足以否定 168 时不投 120；双 footer 丢 1 字节不计票。
- `CupBatchFrame` 允许 ads1292r 的 4 点 PPG，否则 120 预览会在构造帧时抛错。

### B. 采集页卡顿收口

- `ValueReceived` 只在 UI 切片变化时 `publish()`；设备列表按 epoch 拷贝。
- `markValidFrame` 仅 freshness/probe 标志变化时返回 true。
- 预览不再因 `processedSampleCount++` 发射；波形 5 Hz，指标分析在独立线程。
- `LiveSignalCard` collect `publicationSequence` 与 analysis epoch。
- 门控 `combine` 用 `serviceStatus` + BLE `phase+freshness`；`CaptureGateDiskCache` 限制扫盘为改名/resume/≤1 Hz。
- 第三窗标签为「ECG」。

### 改动文件

- `BleGattStateMachine.kt` / `BleCoordinator.kt` / `BlePreviewRuntime.kt` / `BleModels.kt`
- `NordicWireProbe.kt` / `CupBatchProtocol.kt`
- `LiveCaptureScreen.kt` / `MainActivity.kt` / `CaptureServiceViewModel.kt` / `LiveWaveformComponents.kt`
- `CaptureStartGate.kt`
- 测试：`NordicWireProbeTest`、`CupBleGattStateMachineTest`、`BleCoordinatorTest`、`BlePreviewRuntimeTest`、`NordicSensorDeviceCompatibilityTest`、`CaptureStartGateTest`

### 本轮 JVM 验收

- `:app:compileDebugKotlin` 通过。
- 焦点测试通过：`NordicWireProbeTest`、`Ads1292rPacketProtocolTest`、`CupBleGattStateMachineTest`、`BleCoordinatorTest`、`BlePreviewRuntimeTest`、`NordicSensorDeviceCompatibilityTest`、`CaptureStartGateTest`。
- `OfflinePpgAnalysisTest` / `OfflineBloodPressurePreviewTest` 仍视为既有离线回归，不当本轮失败。

## 残留（本轮不做）

- Combo SQI Python 全量对齐。
- 上述两个离线分析回归。
- 档案 UI 可能仍缺 PPG/MB 分段标题；扫描文案仍偏 CUP。

## 真机门禁

默认不把真机操作算作完成条件。CUP 名模拟腕带 120 路径已由 JVM fake 覆盖；真机复验仍待用户执行。
