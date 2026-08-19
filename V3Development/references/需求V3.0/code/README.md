# 综合 SQI 算法代码说明

本目录是**综合 SQI 判定算法的自包含子集**，仅含算法，不含界面、串口/蓝牙传输、录制落盘等内容。

## 文件清单

| 文件 | 内容 |
|---|---|
| `combo_sqi.py` | **入口**。优先级仲裁 `arbitrate_combo`、压力严重度 `_pressure_severity`、输入构造 `compute_sqi_inputs`、常量表与状态颜色；含一步到位的 `evaluate_combo_sqi(raw_ir)` 与合成波自测 |
| `sqi_calculator.py` | SQI 算法：`compute_sqi`（SQI_tm 模板匹配）、`compute_sqi1`（SQI_corr 自相关）、`compute_flat`（平直判别）；另含 PI 等（综合 SQI 未用） |
| `ppg_metrics.py` | `autocorr_sqi`（自相关 SQI 基础实现，被 compute_sqi1 复用） |
| `overpressure_detect.py` | 压力特征检测 `detect_overpressure`：逐拍提取 init_steep / late_rebound / mid_min_vel / slow_frac / **p2_height（主判据）** / n_beats |
| `complete_preprocessing_pipeline.py` | 两个滤波函数：滑动平均 `moving_average_filter_fixed`、带通 `bandpass_filter` |

依赖：`numpy`、`scipy`

## 快速验证

```bash
python combo_sqi.py     # 合成波三路自测：干净周期波→good / 平直→flat / 强噪声→unstable
```

## 使用方式

```python
from combo_sqi import evaluate_combo_sqi

# raw_ir: 一维原始 IR ADC 段（建议 ≥5s，即 500 点 @100Hz）
state, text, color, score, inputs = evaluate_combo_sqi(raw_ir, fs=100)
# state ∈ {"good","unstable","flat","pressure","unknown"}
# text 如 "信号良好 (0.93)"；score 为综合分（信息量展示，不参与触发）
```

## 判定口径速查

| 优先级 | 条件 | 状态 | 颜色 |
|---|---|---|---|
| 1 | 信号平直（且无周期性豁免：n_beats≥2 且 SQI_corr≥0.30 不成立） | `flat` 红 | ⚠ 信号平直 (0.00) |
| 2 | n_beats≥2 且 p2_height≤0.32 且 init_steep≥4.0 且 sev≥0.40 | `pressure` 红 | ⚠ 压力过大[(严重)] (分) |
| 3 | SQI_tm ≥ 0.88 | `good` 绿 | 信号良好 (分) |
| 4 | SQI_tm < 0.88 | `unstable` 橙 | 信号不稳定 (分) |
| 兜底 | 指标全缺 | `unknown` 灰 | 综合SQI: -- |

**关键口径**：SQI_tm 的输入是**滤波后信号**（取负→滑动平均 2/2/10→带通 0.5–12 Hz，无 CPE 增强）；压力判定**特征驱动、不看分数**；综合分不参与触发；去抖须在 1s 指标帧上计数（连续 2 帧切换状态），不得放在渲染层。

完整判定流程与阈值依据见 `../ref/综合SQI指标计算流程.md`。
