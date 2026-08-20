# Python 参考源码

本目录是 2026-07-30 从另一台设备导入的主机工具只读快照，用于 Swift 功能、行为和
数值移植，不是 iOS 运行时依赖。导入材料未提供原仓库 URL/commit；在来源补齐前，
以本目录内容 hash 和 `environment.yml` 作为快照身份。

## 重点文件

- `entry_points/live_gui.py`
  - raw-first；
  - reader thread；
  - decoder/process/UI snapshot 分层；
  - 安全停止和 session metadata。
- `ppg_monitor/online.py`
  - 有界 8/10 秒 buffer；
  - 0.6–4 Hz 因果滤波；
  - 1 Hz HR；
  - 动态 Y 轴范围。
- `ppg_monitor/pulse.py`
  - 峰值 + Welch PSD；
  - RR/MAD；
  - confidence。
- `ppg_monitor/segmented_pulse.py`
  - 8 秒窗、2 秒 hop；
  - stable segments；
  - RED/IR 通道选择；
  - 多窗口聚合。
- `ppg_monitor/offline.py`
  - 采后分析；
  - AC/DC；
  - 只有 unscaled ratio-of-ratios，没有可直接使用的 SpO₂。
- `ppg_monitor/analysis_gui.py`
  - 桌面分析工作台的信息结构和诊断视图。
- `tests/`
  - Python 参考行为的回归测试。
- `../../fixtures/sqi/`
  - 从源码快照分离出的 SQI 确定性输入、生成器和 Python 期望输出；
  - 生成脚本依赖 `ppg-monitor` Conda 环境中的 NumPy/SciPy。

## 新协议边界

这些文件仍使用旧的 Protocol v1、`.ppgbin` 和 BLE NUS 控制流程。CUP App 不应直接
移植旧 decoder/packet layout。新帧解析只使用 `../../protocol/`。

建议移植方式：

1. 从 Python 生成固定输入/中间输出。
2. Swift 实现纯函数。
3. XCTest 对比各中间阶段。
4. 达到容差后再连接 SwiftUI。

原始快照代码不为 iOS 适配而原地修改；移植代码进入 PPGCollector target，测试向量放
在 `../../fixtures/`。
