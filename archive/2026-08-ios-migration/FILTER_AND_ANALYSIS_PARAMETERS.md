# PPGCollector Android 滤波与分析参数

本文记录当前 M7.7 代码中的实际常量和算法边界。显示滤波、实时指标预处理和离线分析是三套不同合同，不应因 UI 都显示 PPG 而混用。

## 1. 公共时间与极性合同

| 项目 | 当前值 |
|---|---:|
| 接受样本率 | 100 Hz |
| 实时波形尾窗 | 800 samples / 8 s |
| 波形发布上限 | 约 5 Hz |
| 实时指标窗口 | 800 samples / 8 s |
| 实时指标步长 | 100 samples / 1 s |
| 连续性断点 | sequence gap、样本索引跳变、非有限输入或时间差 `<= 0` / `> 0.015 s` |

设备原始 ADC 的收缩峰默认向下。当前显示合同为：

- RAW 绘图使用 `-raw`，并只对当前有限视窗去除最佳拟合线性趋势。
- CAUSAL、实时 FIXED 和完整重放的 ZERO/FIXED 都以取负后的 raw 作为显示输入，使收缩峰朝上。
- `.cupraw` 和 sample CSV 的 `red/ir` 保持原始 ADC，不取负、不去趋势、不滤波。
- 实时指标继续使用独立的原始 ADC/`ios_baseline_0.1` 链路；SQI 按自身 profile 做极性变换。显示操作不回写指标输入。

## 2. 实时波形显示

| 模式 | Profile | 结构 | 频带 | 延迟/用途 |
|---|---|---|---|---|
| RAW | display transform | 取负；当前绘图视窗线性去趋势 | 无带通 | 仅视觉呈现 |
| CAUSAL | `causal-display-0.5-12hz-0.1` | 一阶高通串联一阶低通 | 0.5–12 Hz | 零前视；有因果相位改变 |
| FIXED | `fixed-lag-fir-0.5-12hz-0.1` | Hamming 窗 windowed-sinc 对称 FIR | 0.5–12 Hz | 201 taps、右侧 100 samples、约 1 s 固定延迟 |

FIXED 的通带中心频率增益归一化为 1，`publishBlockSamples = 20`。输出带原始 `sourceSampleIndex`，不会把滞后波形伪装为当前样本。每次断点会清空 CAUSAL 和 FIXED 状态；FIXED 至少需要 201 个同段样本才产生首个输出。

CAUSAL 的离散系数由采样率和 RC 公式运行时计算：高通 `alpha = RC/(RC+dt)`，低通 `alpha = dt/(RC+dt)`，其中 `dt=0.01 s`。它用于低延迟观察，不等同于离线零相位结果。

## 3. 实时指标预处理

实时 HR/SQI/R/PI 使用 `ios_baseline_0.1`，不是 0.5–12 Hz 显示滤波：

| 参数 | 当前值 |
|---|---:|
| 采样率 | 100 Hz |
| DC tracker 时间常数 | 0.5 s |
| DC `alpha` | `0.019801326693244747` |
| 带通 | 0.6–4 Hz |
| 阶数/实现 | 3 阶固定 SOS，逐样本 causal |
| 标准差下限 | `1e-8` |
| SQI 极性 | invert |

流程为 raw → DC tracker → `AC = raw - DC` → 固定 SOS。连接 generation、gap、样本索引断点或非有限输入会清空 DC/SOS 和 8 秒窗口。首次连续 800 样本后计算，此后每 100 样本产生一个原子 epoch；HR、SQI、R、PI 共用窗口结束的 sample/time。

## 4. 实时心率 HR

Profile：`ppg-ios-hr-0.1`。

| 参数 | 当前值 |
|---|---:|
| 输入通道 | 预处理后的 IR |
| 允许心率 | 35–200 bpm |
| 工作窗口 | 4–8 s；实时请求固定为 8 s |
| 最小 robust scale | 1.0 |
| 主置信度门限 | 0.35 |
| 频谱峰邻域 | ±0.15 Hz |
| 接受回退 | 峰值/频谱差 `<= 8 bpm` 且 confidence `>= 0.25` |
| 边缘裁剪 | `min(1 s, 窗口长度的 10%)` |

估计器同时检查正/负极性候选、峰间期连续性、Welch-style 频谱和 RR 稳定性。证据不足、振幅不足、无候选或低置信度时不发布旧数值。

## 5. 实时 SQI

Profile：`ppg-ios-sqi-0.1`；预处理 profile 为 `ios_baseline_0.1`。结果范围被限制在 0–1，当前始终标记 provisional。

| 参数 | 当前值 |
|---|---:|
| 最短输入 | 4 s |
| 最高心率假设 | 180 bpm |
| 周期峰前比例 `ratioPre` | 0.5 |
| 主峰高度 | `mean + 0.5 × SD` |
| 主峰 prominence | `0.3 × SD` |
| 主峰最小宽度 | 5 samples |
| 回退峰高度 | `mean + 0.2 × SD` |
| 回退 prominence | `0.2 × SD` |
| Good / Fair 门限 | `>= 0.90` / `>= 0.70` |

每个完整周期与平均模板计算 Pearson 相关系数；周期不足、常量信号或模板失败时 SQI 不可用。UI 的 Good/Fair/Poor 只是当前暂定分级。

## 6. RR 与 PI

RR profile：`ppg-ios-rr-0.1`；PI profile：`ppg-pi-red-acdc-0.1`。两者来自同一次计算，均为 provisional。

1. 至少需要 400 个同长度、有限样本。
2. 在窗口两端各裁剪 10%。
3. `DC = abs(mean(raw))`，要求 RED/IR DC 均大于 1 ADC。
4. `AC = RMS(bandpassed)`，要求大于 0。
5. `RED AC/DC % = 100 × RED_AC / RED_DC`；IR 同理。
6. `RR = (RED AC/DC) / (IR AC/DC)`；`PI = RED AC/DC %`。

这里的 RR 是 ratio-of-ratios，不是 respiration rate；当前没有从 R 到 SpO₂ 的标定，也没有从 PI/R 到血压的映射。

## 7. 完整重放滤波

### 7.1 ZERO

Profile：`offline-biquad-filtfilt-0.5-12hz-0.1`。

- 100 Hz；0.5 Hz 二阶 Butterworth 高通串联 12 Hz 二阶 Butterworth 低通，profile `filterOrder = 4`。
- 对每个完整连续段分别前向/反向执行，得到零相位显示；不跨 gap。
- 连续段少于 32 个样本时不生成 ZERO，输出位置保持无效。
- 完整重放以取负 raw 为输入；该显示结果不写回源会话。

### 7.2 FIXED

使用与实时相同的 `fixed-lag-fir-0.5-12hz-0.1`。每个连续段独立处理，并把中心输出放回对应 source index；段首和段尾各约 100 点没有完整上下文，因此保持无效。这样可在同一横轴直接比较 ZERO 与固定时延结果。

## 8. 离线稳定段与窗口

离线分析 profile：

- `analysis_profile = ppg-offline-segmented-0.2`
- `algorithm_version = segmented-pulse-0.5-12hz-0.4`
- `preprocess_profile = offline-biquad-filtfilt-0.5-12hz-0.1`
- 采样率 100 Hz，窗口 8 s，步长 2 s。

### 8.1 初始稳定性

- 起始至少保护 2 s，之后按 0.5 s bin 计算 RED/IR 中位数。
- 每个 bin 至少 13 个样本。
- 用后续 8 s horizon 检查稳定：两通道 `(P90-P10)/level <= 0.025`，且 8 s 线性趋势相对量 `<= 0.015`。
- 找到首个稳定 horizon 后，从该 bin 中点前移 0.25 s 作为允许起点；找不到时不强行发布稳定起点。

### 8.2 中断与伪影排除

- 单样本相对跳变：RED 或 IR `> 0.02`。
- 25-sample 平滑信号相对变化：`> 0.05`。
- 显式 sequence break，或相邻时间差 `<= 0` / `> 0.015 s`。
- 每个转变点前后各排除 1.5 s。
- 剩余连续段至少 8 s；接触相对强度需 `>= 0.15`。

### 8.3 窗口接受与会话汇总

每个稳定段内用 ZERO 0.5–12 Hz 计算 RED/IR 心率候选，并选择累计证据更强的通道。窗口按以下条件拒绝：

| 原因 | 门限 |
|---|---|
| `no_regular_peaks` | 无规律峰或无极性 |
| `no_spectral_peak` | 无频谱峰 |
| `peak_spectral_disagreement` | 峰法与频谱相差 `> 15 bpm` |
| `low_confidence` | confidence `< 0.20` |
| `low_perfusion` | 所选通道 AC/DC `< 0.01%` |
| `bpm_outlier` | 不属于主 BPM 聚类 |

主聚类先寻找 ±10 bpm 内权重最大的种子，再以加权中位数和 MAD 得到容差 `min(12, max(8, 3 × MAD)) bpm`。会话心率至少需要 2 个接受窗口且最终 confidence `>= 0.25`；证据不足时保留诊断但不发布会话 bpm。

接受峰的最小间距为 `0.65 × 60 / bpm` 秒，prominence 至少为 `0.5 × robustScale`。稳定样本、窗口、拒绝计数、峰、心率、频谱心率、confidence、SNR 和 RR MAD 都写入版本化分析 JSON。

## 9. 频谱与平均周期

- 分析结果频谱来自最佳接受窗口。
- 交互工作台的当前可见范围频谱为 bounded Welch-style：每段最多 800 点、50% overlap、最多均匀选 32 段、线性去趋势、Hann 窗，显示 0.3–8 Hz；心率读数使用约 0.58–3.33 Hz（35–200 bpm）范围。
- 平均周期统一插值为 200 个 phase 点。
- 周期长度需位于中位周期的 0.5–1.8 倍，并且不能跨稳定段。
- 与初始模板相关系数通常需 `>= 0.45`；样本足够但剩余不足 2 个时，保留相关性最高的一半且至少 2 个。
- 图中 `95% CI = 1.96 × SEM`。

## 10. 资源与血压占位

- 单次完整 signal/analysis 最多接受 1,500,000 个样本；分析 JSON 最大读取限制为 8 MiB。
- 离线分析任务可取消；取消不提交临时 JSON。重新分析生成新文件，不覆盖历史结果。
- 实时 BP 不生成数值。离线参考 BP 图为便于检查时间对齐，可在 800-sample 热身后按 100 samples/1 Hz 生成固定 120/80 mmHg 的 UI-only 占位序列；它不来自模型、不落盘，并有明确“预测算法未接入”说明。
