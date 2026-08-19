# 综合 SQI 判定流程（V4.4.2 算法口径）

> 本文档描述**算法判定口径**；配套代码为 `code/` 下的算法子集（不含界面/串口/录制）。
> 核心函数：`arbitrate_combo` / `_pressure_severity` / `compute_sqi_inputs`（combo_sqi.py）、
> `detect_overpressure` / `compute_rapid_decline`（overpressure_detect.py）、
> `compute_sqi` / `compute_sqi1` / `compute_flat`（sqi_calculator.py）、
> `autocorr_sqi`（ppg_metrics.py）、滤波（complete_preprocessing_pipeline.py）。

---

## 0. 一句话概括

综合 SQI 把三类故障按优先级仲裁成**单一状态**（提示文本 + 颜色），同时给一个**综合分**（0~1）：

- **状态机**（决定"显示哪句提示"）：`flat（无有效信号）> 压力过大 > SQI_tm（单阈值二分）`
- **综合分**（决定"指示数多大"，信息量展示、**不参与触发**）：`score = SQI_tm × flat因子 × 压力因子`

判定（仲裁+去抖）按**每 1s 一帧的指标节律**执行；界面渲染只读结果，不参与判定。

---

## 1. 数据流与节律

```
原始 IR/Red (100Hz)
   │
   ├─ 指标窗 = 最近 5s（500 样本），每 1s 重算
   │     ├─ 尾部子窗(5s) ──► compute_flat / compute_sqi1(SQI_corr)
   │     ├─ 全窗 ──► 滤波后信号(取负→滑动平均2/2/10→带通0.5-12Hz) ──► compute_sqi(SQI_tm)
   │     └─ 全窗 ──► detect_overpressure ──► {init_steep, late_rebound, mid_min_vel, slow_frac,
   │                                            p2_height★, dn_area, n_beats}
   │                        │
   │                        ▼
   │     arbitrate_combo(flat, op_snap, sqi, sqi_tm_good=0.88, sqi1)   ← 每 1s 一次，含去抖计数
```

### 输入指标明细

| 输入 | 来源 | 口径与注意 |
|------|------|-----------|
| **SQI_tm** | `compute_sqi(ppg_filt)` | 模板匹配 mean_quality。输入是**滤波后信号**（取负→滑动平均 2/2/10→带通 0.5-12Hz，**无 CPE、无标准化**）。★CPE 峰值增强是非线性变换、会虚抬弱/坏信号的分数；标准化是线性变换、对相关系数无影响 |
| **SQI_corr** | `compute_sqi1(seg_ir)` | 自相关（40-150bpm 内最大自相关值）。**幅度/形态不敏感、只看周期性**。真平直≈0.1、真周期波≥0.5。★不能被 SQI_tm 替代：真平直噪声上 SQI_tm 可虚高至 0.49-0.73（带通把各段噪声频谱"美化"得相似），SQI_corr 才贴 0 |
| **flat** | `compute_flat(seg_ir)` | 平直判别：`std/DC < 0.0003 或 p2p/DC < 0.0003`（`flat_rel_thr`） |
| **op_snap** | `detect_overpressure(metric_window)` | 压力特征，见 §4 |

---

## 2. 优先级状态机 `arbitrate_combo`

签名：`arbitrate_combo(flat_d, op_snap, sqi, good_thr, sqi1=None)` → `(state_key, display_text, color, score)`

| 优先级 | 条件 | state_key | 提示文本 | 颜色 | score |
|--------|------|-----------|----------|------|-------|
| 1 | flat 命中 且 周期性豁免不成立 | `flat` | `⚠ 信号平直 (0.00)` | 红 | 0.0 |
| 2 | n_beats≥2 且 p2h≤0.32 且 steep≥4.0 且 sev≥0.40 | `pressure` | `⚠ 压力过大[(严重)] (分)` | 红 | SQI_tm×(1−0.85×sev) |
| 3 | SQI_tm ≥ 0.88 | `good` | `信号良好 (分)` | 绿 | SQI_tm |
| 4 | SQI_tm < 0.88 | `unstable` | `信号不稳定 (分)` | 橙 | SQI_tm |
| 兜底A | flat 豁免路径 + sqi 缺 | `unstable` | `⚠ 信号幅度过低` | 橙 | 0.0 |
| 兜底B | 全缺（采集中/预处理失败） | `unknown` | `综合SQI: --` | 灰 | None |

（sev≥0.80 时 pressure 加"(严重)"后缀）

### 2.1 flat 分支与周期性豁免（优先级 1）

**平直短路**：`flat=是` 且豁免不成立 → 直接返回平直、score=0（同时屏蔽平直噪声上可能的 SQI_tm 虚高）。

**周期性豁免**（治"重压低幅周期波被吞成平直"）：

```
豁免成立 ⟺ n_beats ≥ 2 且 SQI_corr ≥ 0.30 (COMBO_FLAT_EXEMPT_CORR)
```

豁免成立 → 不判 flat，继续往下仲裁（落到压力或 SQI_tm 分支）。

**为什么必须双条件**：
- 只看 `n_beats≥2` 不行：真平直（未贴手指）的残余噪声经带通放大后，特征点检测也会"检出"多个**假拍**，但 SQI_corr 只有纯噪声水平（≈0.1）→ 平直会被误豁免。
- SQI_corr 是拍真假的判据：自相关问"信号在心跳周期延迟处是否重复自己"，带通噪声在任何固定延迟都不重复 → 贴 0；真低幅周期波 ≥0.5。
- `sqi1` 缺失（算不了）时保守视为无周期证据 → 照判 flat。

### 2.2 pressure 分支（优先级 2，四闸门特征驱动）

```
触发 ⟺ n_beats ≥ 2
      且 p2_height ≤ 0.32   (COMBO_P2_HEIGHT_MAX，主判据)
      且 init_steep ≥ 4.0   (COMBO_STEEP_MIN)
      且 sev ≥ 0.40         (COMBO_OP_FLOOR)
```

**为什么压力高于 SQI_tm**：按压时波形仍周期但 P2 被压塌 → 模板匹配 SQI_tm 可能虚高（持续按压时 SQI_tm 可回到 0.99+）→ 压力判定完全特征驱动、不看分数、不看 SQI_tm。

**四道闸门**：

| 闸门 | 作用 | 理由 |
|------|------|------|
| `p2_height ≤ 0.32` | **主判据**：P2（次波峰）被压塌程度 | 随压力单调降（轻压~0.9 → 正常~0.7 → 渐进 0.55/0.40 → 重压 0.14-0.25）。**P2 健在一律不报**（治"主峰陡但有次波峰仍报压力"） |
| `init_steep ≥ 4.0` | 主峰陡降在 | 挡"光滑无P2信号"（正弦 lrb≈0 但 steep≈2.5）；正常段 steep≤3.9 |
| `sev ≥ 0.40` | 4 特征综合严重度 | 见 §3，防单特征噪声 |
| `n_beats ≥ 2` | 可检拍数下限 | 重压压扁波形可检拍数本就少，2 拍即判 |

**已退役**：`late_rebound ≤ 6.0`（lrb 量"从谷回升多少"，在重压的"深谷回填"形态下被深谷污染、与 p2h 矛盾，系统性不可靠）。其原职责已被接管：挡光滑信号 → steep；挡轻中压 → p2h。（lrb 仍参与 sev 计算，只是不再有一票否决权。）

**分界线提示**：p2h 0.32 为"中等力度不报、重压才报"的语义线；新人群/设备若误报漏报，优先复查 `COMBO_P2_HEIGHT_MAX`。

### 2.3 SQI_tm 二分（优先级 3/4）

单阈值 `sqi_tm_good = 0.88`：≥0.88 良好（绿）、<0.88 不稳定（橙），无中间档。

标定依据：真正良好信号 SQI_tm 恒 ≥0.91、轻压力不稳定段 0.75-0.86 → 0.88 卡两组之间、良好段零误判。**注意此 SQI_tm 是滤波后信号口径**（CPE 口径下不稳定段会虚高，0.88 不适用）。

---

## 3. 压力严重度 `_pressure_severity`

4 特征各自线性归一到 [0,1] 取均值 = sev ∈ [0,1]（**取均值而非 AND**：单门边界抖动只轻微拉扯均值，AND 任一门跳变即整条翻转）：

```
s1 = clip((init_steep  − 2.4) / (6.0 − 2.4), 0, 1)   # 陡降：2.4(正常)→6.0(重度)，越大越重
s2 = clip((10 − late_rebound) / (10 − 2),     0, 1)   # P2回升：10(强P2)→2(压平)，越小越重
s3 = clip((mid_min_vel −(−1.5)) / (0 −(−1.5)), 0, 1)   # 重搏窗：−1.5(强P2)→0(衰减)，越大越重
s4 = clip((slow_frac − 0.35) / (0.7 − 0.35),   0, 1)   # 平稳尾：0.35(正常)→0.7(重度)，越大越重
sev = mean(s1..s4)      # n_beats<2 → None（判不了）
```

sev 用途：压力闸门（≥0.40）、"(严重)"标注（≥0.80）、综合分压力因子（`1 − 0.85×sev`）。

---

## 4. 压力特征计算（`detect_overpressure` → `compute_rapid_decline`）

预处理：`raw IR 取负（收缩峰朝上）→ 带通 0.5-12Hz(order=2)` → `detect_fiducials` 找 P1/T → 逐拍计算 → 帧值汇总。

| 特征 | 定义（逐拍） | 正常 | 压力过大 | 帧值 |
|------|-------------|------|----------|------|
| `init_steep` | P1 后 80ms 内最陡单样本下降（%amp/sample） | 1.5-3.9 | 5-15 | 均值 |
| `late_rebound` | 急降段后 [P1+80ms, 下一拍−3] 相对前向 min 的最大回升（%amp） | 10-155（强P2） | 0-6（仅参与 sev，不入闸门） | 均值 |
| `mid_min_vel` | 重搏窗 [P1+120, P1+350]ms 滑动 5 样本窗最小下降速率 | <−1（强上扬） | ≈0 | 均值 |
| `slow_frac` | 一拍内"非陡降"样本占比 | 0.15-0.40 | 0.5-0.9 | 均值 |
| **`p2_height`** ★主判据 | **生理窗** [P1+80ms, min(P1+0.8拍长, P1+0.6s, 下一拍P1)] 内原始信号**最高局部极大**（`_find_peaks_local`），相对 (P1 − 拍内谷) 归一 | 0.34-0.9 | 0.10-0.31 | **中位** |
| `dn_area` | 下降支下方面积（P1→拍内谷的归一化均值高度） | 0.34-0.51 | 0.19-0.29 | 均值（保留参考，未入闸门） |
| `n_beats` | 有效拍数（搏动 <350ms 跳过） | — | — | 计数 |

**p2_height 两个关键实现细节**：
1. **归一基准必须用拍内 min**（`amp_beat = P1 − 拍内最小值`），不能用上一拍 foot——压力波拍内谷比 foot 深，用 foot 会稀释 amp、p2h 系统性抬高 0.06-0.09、破坏 0.32 分界。
2. **生理窗双约束**：`min(0.8×拍长, 0.6s)` 防止拍数少的窗里比例约束失效跨拍误检；窗内无局部极大 → 该拍记 0（P2 完全消失）。

**dn_area 细节**（保留参考）：分段须用**下一拍 P1**（否则会截掉拍尾平坦段、面积偏小）。

---

## 5. 去抖（1s 指标帧计数）

```
进入任意新状态：连续 COMBO_DEBOUNCE(2) 帧同一新状态
离开 pressure：连续 COMBO_DEBOUNCE_EXIT(2) 帧
```

- 计数与仲裁同处，每**指标帧**（1s）计一次。
- ★**架构要点**：去抖必须在指标帧上计数、不得放在渲染层——渲染频率（如 200ms）高于指标帧时，同一份快照会被连刷多次，"连续 N 帧"实际不存在，会造成状态交替闪烁。
- 首帧 `unknown`：去抖计数器起步，属预热，约 1-2s。

---

## 6. 计算节律模型（实现约定）

```
指标计算（1s 一帧）：
    compute_sqi_inputs → {sqi, sqi1, flat, op_snap, ...}
    arbitrate_combo + 去抖计数 → 当前状态/文本/颜色
界面渲染（独立节律）：
    读判定结果 → 落显示控件（零判定逻辑）
```

- 判定逻辑只在指标帧节律内跑，与渲染频率解耦。
- 断开连接 / 数据停顿超时 / 重新连接：状态与计数器一并重置为 unknown。

---

## 7. 常量表

### 去抖 / 拍数

| 常量 | 值 | 含义 |
|------|----|------|
| `COMBO_DEBOUNCE` | 2 | 进入新状态所需连续帧数 |
| `COMBO_DEBOUNCE_EXIT` | 2 | 离开 pressure 所需连续帧数 |
| `OP_MIN_BEATS` | 2 | 最少可检拍数（压力闸门 + sev 有效条件 + flat 豁免条件共用） |

### 压力闸门（4 道，lrb 已退役）

| 常量 | 值 | 含义 |
|------|----|------|
| `COMBO_P2_HEIGHT_MAX` | **0.32** | ★主判据：p2_height 上限 |
| `COMBO_STEEP_MIN` | 4.0 | init_steep 下限 |
| `COMBO_OP_FLOOR` | 0.40 | sev 下限 |
| `COMBO_OP_SEVERE` | 0.80 | "(严重)"标注线 |
| `COMBO_MAX_PEN` | 0.85 | 综合分压力因子最大扣分（因子下限 0.15） |

### flat / SQI_tm

| 常量 | 值 | 含义 |
|------|----|------|
| `COMBO_FLAT_EXEMPT_CORR` | 0.30 | flat 豁免自相关门 |
| `FLAT_REL_THR` | 0.0003 | 平直判别阈值（std/DC 或 p2p/DC） |
| `SQI_TM_GOOD` | 0.88 | 良好/不稳定分界 |

---

## 附：完整仲裁伪代码

```python
def arbitrate_combo(flat_d, op_snap, sqi, good_thr=0.88, sqi1=None):
    base = sqi.mean_quality          # SQI_tm（滤波后信号口径）
    corr = sqi1.sqi1                 # SQI_corr（自相关）
    n_beats = op_snap.n_beats

    # 周期性证据 = 检出拍 且 自相关过线（sqi1 缺 → 保守无证据）
    has_real_beats = n_beats >= 2 and corr is not None and corr >= 0.30

    # 1) 平直（含"平直噪声检出假拍"）
    if flat_d.flat and not has_real_beats:
        return "flat", "⚠ 信号平直 (0.00)", 红, 0.0

    # 2) 压力（4 闸门特征驱动；lrb 已退役）
    sev = mean(clip4(init_steep, late_rebound, mid_min_vel, slow_frac))  # None 若 nB<2
    if (n_beats >= 2 and p2_height <= 0.32
            and init_steep >= 4.0 and sev >= 0.40):
        score = base × (1 − 0.85 × sev)
        return "pressure", f"⚠ 压力过大[(严重)] ({score})", 红, score

    # 3)/4) SQI_tm 单阈值二分
    if base >= 0.88: return "good",     "信号良好",   绿, base
    else:            return "unstable", "信号不稳定", 橙, base

    # 兜底
    if flat_d.flat and has_real_beats: return "unstable", "⚠ 信号幅度过低", 橙, 0.0
    return "unknown", "综合SQI: --", 灰, None

# 去抖（1s 指标帧，独立于渲染频率）：
#   新状态连续 2 帧 → 切换显示；离开 pressure 连续 2 帧
```
