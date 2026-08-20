# V3.0 开发状态（简版）

更新：2026-08-20
当前轮次：**V3.R7（代码与完整门禁已完成；真机复验待执行）**

> 本文件后面的 R6/R5.1 记录保留作历史基线；以下先记录 R7 实际状态。

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
- R1–R7 产品与稳定性修正已落地；当前实现以 `app/src` 与本状态文件为准。
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
