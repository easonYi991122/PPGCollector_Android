# PPGCollector Android 剩余移植工作审计

日期：2026-08-02

审计基线：`main` / `a2ab0c4`；2026-08-03 M5 unsigned release artifact 证据另见双层 status

范围：`00_AGENT_MIGRATION_BRIEF` 的 M0–M7、需求矩阵 P0/P1、Definition of Done、开放决策以及当前 production/test/manifest/build 文件。

## 1. 结论

协议/raw/CSV/session、信号算法、BLE owner、raw-first 录制、Sessions/replay、M6 离线分析和本轮实时因果波形均已有本地实现与 JVM/静态构建证据。当前不能称为“整个移植完成”，主要不是还缺一套核心算法，而是以下三类工作尚未闭环：

1. **代码缺口**：正式版本/profile 追踪、动态录制通知、FileProvider 分享入口、按会话时长估算存储、可持续 CI/benchmark；`D-014` 若要求跨进程离线分析，还需 WorkManager 或用户可见 FGS。
2. **运行时验收缺口**：真实 CUP 协议、BLE/权限/FGS/SAF、完整信号触控/横屏、可访问性、API/厂商矩阵以及真实 30 min/2 h 长稳均没有最终证据。
3. **产品/发布输入缺口**：包名/显示名/签名/分发、隐私/保留/加密/设备 ID、后台策略、正式版本命名和 SpO2/BP 文案仍待确认。

因此，M1–M6 可描述为“主要代码切片已实现，本地证据通过”；M0 的真实契约冻结、M5 的 release/runtime gate 和 M7 的证据支持扩展仍未完成。

## 2. 已实现且不应重复开发的核心能力

| 范围 | 当前事实 | 尚缺证据 |
|---|---|---|
| M1 protocol/data/signal | 当前 168-byte planar decoder、历史 408-byte replay、sequence gate、CUPRAW1、25 列 CSV、session JSON、inspection/recovery、preprocess/HR/SQI/R fixtures、800/100 runtime | `D-001` 真实固件认证；`D-006/D-007` 正式跨平台/version 裁决 |
| M2 BLE | API permission policy、有限扫描、NUS/FFF0 profile 自动选择、Android GATT/CCCD、generation/deadline/freshness、fake 20-cycle | 新硬件 FFF1 properties/通知 hex/FFF2 命令契约、真机 CUP receiving、权限撤销、adapter off、目标 OEM/API 矩阵 |
| M3 capture | 256 有界队列、raw-first writer、1 s checkpoint、first reason/single finalizer、connectedDevice FGS、SAF/recovery/FileProvider service | 系统 lifecycle/锁屏/task removed/强停/低存储/provider runtime |
| M4 UI | Live/Capture/Sessions、RAW 波形、指标状态、连接/断开、录制 gate、详情/replay | emulator/device screen tests、TalkBack、动态字号、深浅主题、触控与重建 |
| M5 local hardening | 30 min/2 h JVM 模拟、release shrink/lint/privacy、manifest/API/lifecycle 静态报告 | 真实 2 h、CPU/heap/jank/温升/功耗、签名发布、RC 合规材料 |
| M6 analysis | raw replay、完整信号、按连续段 zero-phase、稳定段/窗口/频谱/周期、历史/取消/compare、横屏工作台、实时 CAUSAL/RAW | runtime 触控/旋转/大数据性能；`D-014` 长任务策略 |

## 3. 尚未闭环的代码工作

### 3.1 高优先级：数据追溯与录制状态

| 项目 | 需求/决策 | 当前证据 | 剩余实现/验收 |
|---|---|---|---|
| 正式 live version/profile | CAP-004/005、D-007 | 每行/session 会固化配置，metric 自身已有 HR/SQI/R algorithm version | production `CaptureForegroundService` 仍写 `algorithmVersion="unavailable"`，且 `preprocessProfile="ios-baseline-0.1"` 与 runtime `ios_baseline_0.1` 不一致；需定义正式组合版本并做 metadata/CSV golden 升级规则 |
| 录制通知时长/健康 | UI-008 | FGS、低优先级通知和“停止并保存”已存在 | 通知目前只从“准备录制”切为静态“录制中”；需从 service-owned snapshot 显示 elapsed、写入/queue/freshness 健康，并控制更新频率，补 finalizer/重建测试 |
| 录制页运行状态 | UI-008、REL-003/004 | 可开始/停止和错误状态已显示 | 录制中尚未呈现 elapsed、pending writes、overflow/health；应与通知读取同一 service snapshot，不另建计时/owner |

### 3.2 中优先级：导出、存储与后台分析

| 项目 | 需求/决策 | 当前证据 | 剩余实现/验收 |
|---|---|---|---|
| FileProvider 分享闭环 | CAP-010 | cache-only provider、ZIP staging service 和 manifest contract 已实现 | Sessions UI/ViewModel 未调用 `CaptureFileProviderExportService.createShare`，没有 `ACTION_SEND` chooser 与可靠 staging cleanup；SAF 导出已可用但不能替代规划中的分享验收 |
| 动态空间预算 | CAP-006、D-012 | 20 MiB 最低 gate、2 h 模拟已存在 | 需按 notification bitrate/预计时长估算会话空间并在开始前/录制中提示；20 MiB 静态值不是 2 h 容量承诺 |
| 离线分析跨进程策略 | CAP-011、D-014 | ViewModel app-scope coroutine 支持进度/取消/历史且不留伪产物 | 若产品要求长任务后台/进程恢复，先 ADR，再迁移 WorkManager 或用户可见 FGS；若确认仅前台短任务，则关闭 D-014 并补取消/进程终止产品文案 |
| 会话保留/删除/加密 | D-005、D-013 | sessions 排除 cloud/device backup，UI 提示卸载删除，显式 SAF 导出 | 需产品确认 retention、用户删除入口、是否加密、完整设备 identifier 是否入 metadata；确认前不擅自删除或迁移现有文件 |

### 3.3 工程化与发布实现

| 项目 | 依据 | 当前事实 | 剩余工作 |
|---|---|---|---|
| CI/nightly/benchmark | Phase 5 §8.1/§8.2 | Gradle 本地门禁可运行 | repo 中没有持续 CI、nightly、macrobenchmark/leak/frame-time runner；需把现有命令和 30 min/2 h tests 分层自动化 |
| 正式 app identity/release | D-004、REL-005/006/007 | 2026-08-03 已通过 Android Studio fresh build 生成并校验 unsigned shrink release APK | 仍是 `com.example.ppgcollector_android`、`versionCode=1`、`versionName=1.0`；需正式 app 名/包名、签名 owner、版本策略、symbols/rollback/internal channel；unsigned APK 不能代替正式签名发布包 |
| 发布合规材料 | Phase 5 §8.3 | privacy/APK 静态扫描和 backup exclusion 已有 | 需隐私政策、Data Safety、权限说明、依赖/许可证审阅、固件兼容表和 release checklist；发布时重新核查 target API 政策 |

## 4. 必须在 emulator/真机执行的验收

这些不是本地 unit test 可以替代的代码完成证据：

1. **协议与 BLE（D-001、UI-001、BLE-001…006）**：分别对 NUS 与 FFF0 CUP 抓包确认 services/characteristic properties、FFF2 是否需命令、通知 hex、当前 168/20 planar 布局、历史设备 wire、端序、sequence、实际 100 Hz、分片/MTU；执行无设备扫描超时、发现/连接/CCCD/receiving、断开/重连、拔电、adapter off、权限拒绝/撤销、stale。
2. **生命周期与 FGS（UI-008、REL-003/004/007）**：旋转、Activity 重建、页面切换、后台、锁屏、task removed、Active apps stop、notification action、系统回收；证明始终只有一个 GATT/writer/finalizer。
3. **文件系统（CAP-008…010）**：真实 SAF provider 创建/取消/慢写/断开，FileProvider chooser，卸载/backup-restore 边界，低空间和恢复入口。
4. **UI 与可访问性（M4/M6）**：实时 RAW/CAUSAL 切换、settling/gap、replay 指尖拖动/双指缩放、完整信号 fit、横屏沉浸/旋转恢复、TalkBack、动态字号、深浅主题、最小触控区域。
5. **性能/长稳（REL-001/002/006）**：API 26/30/31/33/34/35/36/37 emulator，Pixel/原生系加至少一类目标 OEM；真实 receiving 30 min 与 capture 2 h，记录 missing/invalid/overflow、flush p95、metric latency、heap/native heap、GC、CPU、jank、温升、电量和服务存活。
6. **跨平台（D-006）**：Android 生成 raw/CSV/session/analysis 交给当前 iOS/Python reader；iOS/Python 产物由 Android repository/inspection/replay 接受，形成双向报告，而不只依赖同语言 round-trip。

## 5. 仍需外部确认的决策

- **发布阻断**：D-001 真实协议、D-002 设备矩阵、D-003 后台策略、D-004 identity/signing/distribution、D-005 数据政策、D-006 双向兼容、D-007 version/profile、D-008 SpO2/BP 文案。
- **需用真机/产品关闭**：D-009 `neverForLocation`（代码已启用）、D-010 页面退出继续录制（代码默认 FGS 继续）、D-011 是否开放 5/10 Hz（当前固定 5 Hz）、D-012 动态空间、D-013 device ID、D-014 分析后台、D-016 SQI 何时取消 provisional。
- **明确延后**：D-015 IMU 无数据契约，不应为了“功能数量”伪造入口。

## 6. M7 与推荐顺序

M7 当前只有“专家诊断及经证据支持的扩展”的方向，没有新的固件/校准/产品证据；SpO2、BP、IMU、医疗结论、云同步和远程控制都不能自行实现。M6 已覆盖大量专家工作台交互，但这不等于可以关闭 M7。建议顺序：

1. **下一代码轮（M3/M5）**：修复正式 live version/profile 追溯；增加 service-owned elapsed/write-health notification 与录制页状态。
2. **随后（M4/M5）**：接通 FileProvider 分享、动态空间估算，补对应 JVM/instrumentation seam。
3. **验收轮（M2–M6/M5）**：执行 API/emulator、真实 CUP、生命周期、SAF、可访问性和 30 min/2 h 矩阵，按证据修正缺陷。
4. **发布轮（M0/M5）**：关闭 D-001…D-013 的产品/固件输入，完成 identity、签名、隐私、许可证和分发。
5. **最后评审 M7**：只有获得新的协议、IMU、校准或专家诊断验收需求后才建立可测试版本；没有证据的扩展继续明确 unavailable/deferred。

本审计是当前事实快照，不把静态报告当作系统运行证据，也不把开放产品决策伪装成 Android 缺陷。
