# -*- coding: utf-8 -*-
"""综合 SQI 判定核心：优先级仲裁 + 压力严重度 + 常量表。

判定流程（详细依据见 ref/综合SQI指标计算流程.md）：
  1) flat（信号平直）           —— 最高优先级，带周期性豁免
  2) pressure（压力过大）       —— 特征驱动 4 闸门，不看分数、不看 SQI_tm
  3)/4) SQI_tm 单阈值二分        —— ≥0.88 良好 / <0.88 不稳定
  兜底A  幅度过低（flat 豁免路径 + SQI_tm 缺）
  兜底B  unknown（指标全缺）

综合分公式（信息量展示，不参与触发）：
    score = SQI_tm × flat因子 × 压力因子
    flat → 0；压力 → SQI_tm × (1 − 0.85 × sev)；正常 → ≈ SQI_tm

输入指标（每 1s 一帧、最近 5s 指标窗上计算）：
    SQI_tm   compute_sqi(ppg_filt)   模板匹配；输入为滤波后信号（无 CPE）
    SQI_corr compute_sqi1(seg_ir)    自相关；只看周期性，用于 flat 豁免
    flat     compute_flat(seg_ir)    平直判别（std/DC 或 p2p/DC < 0.0003）
    op_snap  detect_overpressure(...) 压力特征（p2_height/init_steep/sev/n_beats）

去抖（须在指标帧上计数，不得在渲染层）：
    进入任意新状态连续 COMBO_DEBOUNCE(2) 帧；离开 pressure 连续 COMBO_DEBOUNCE_EXIT(2) 帧。
"""

import numpy as np

from sqi_calculator import compute_sqi, compute_sqi1, compute_flat
from overpressure_detect import detect_overpressure
from complete_preprocessing_pipeline import (
    moving_average_filter_fixed, bandpass_filter,
)

# ---- 状态颜色（与需求文档第 4 章一致） ----
C_GOOD = "#2E7D32"     # 绿：信号良好
C_FAIR = "#EF6C00"     # 橙：信号不稳定 / 幅度过低
C_POOR = "#C62828"     # 红：信号平直 / 压力过大
C_GRAY = "#888888"     # 灰：无值 / 热身

# ---- 常量表（V4.4.2 定稿） ----
OP_MIN_BEATS = 2                  # 压力判据最少搏动数（重压下可检拍本就少，2 拍即判）
COMBO_DEBOUNCE = 2                # 去抖：进入新状态所需连续指标帧数（≈秒）
COMBO_DEBOUNCE_EXIT = 2           # 去抖：离开压力状态所需连续帧数
COMBO_OP_FLOOR = 0.40             # sev 最低门槛：确认是压力特征（非噪声）
COMBO_OP_SEVERE = 0.80            # sev ≥ 此值 → 提示加"(严重)"后缀
COMBO_MAX_PEN = 0.85              # sev=1 时最大扣分 → 压力因子下限 0.15
COMBO_STEEP_MIN = 4.0             # 急降下限 init_steep ≥ 此值（挡光滑无 P2 信号；正弦 ~2.5）
COMBO_P2_HEIGHT_MAX = 0.32        # ★主判据：P2 次波峰高度上限（轻压~0.9/正常~0.7/重压 0.10-0.31）
COMBO_FLAT_EXEMPT_CORR = 0.30     # flat 周期性豁免的自相关门（真平直 ≈0.1、真周期波 ≥0.5）
FLAT_REL_THR = 0.0003             # 平直判别阈值（std/DC 或 p2p/DC）
SQI_TM_GOOD = 0.88                # SQI_tm 良好/不稳定分界（滤波后信号口径）


def compute_sqi_inputs(raw_ir, fs=100.0):
    """从一段原始 IR（ADC 原值）构造综合 SQI 的四类输入。

    对应监控工具的 compute_all_metrics（SQI 相关子集）：
      - ppg_filt：取负 → 滑动平均 2/2/10 → 带通 0.5-12 Hz(order=2)，无 CPE/无标准化
      - sqi      ：SQI_tm（模板匹配，喂滤波后信号）
      - sqi1     ：SQI_corr（自相关，喂原始 IR）
      - flat     ：平直判别（原始 IR）
      - op_snap  ：压力特征快照

    Returns:
        dict: {sqi, sqi1, flat, op_snap, ppg_filt}
    """
    ir = np.asarray(raw_ir, dtype=float)

    def _safe(fn, *args, **kwargs):
        try:
            return fn(*args, **kwargs)
        except Exception:  # noqa: BLE001
            return None

    # 尾部子窗指标（原始 ADC）
    sqi1 = _safe(compute_sqi1, ir, None, fs=fs)
    flat = _safe(compute_flat, ir, fs=fs, rel_thr=FLAT_REL_THR)

    # 滤波后信号（SQI_tm 输入；无 CPE —— 非线性变换会虚抬弱/坏信号分数）
    ppg_filt = None
    sqi = None
    try:
        _sig = -ir
        _sig = moving_average_filter_fixed(_sig, 2)
        _sig = moving_average_filter_fixed(_sig, 2)
        _sig = moving_average_filter_fixed(_sig, 10)
        _sig = bandpass_filter(_sig, 0.5, 12.0, fs, order=2)
        ppg_filt = np.asarray(_sig, dtype=float)
        sqi = _safe(compute_sqi, ppg_filt, fs=fs, ratio_pre=0.5)
    except Exception:  # noqa: BLE001
        pass

    # 压力特征
    op_snap = _safe(detect_overpressure, ir, fs)

    return {"sqi": sqi, "sqi1": sqi1, "flat": flat, "op_snap": op_snap,
            "ppg_filt": ppg_filt}


def _pressure_severity(op_snap):
    """连续压力严重度 ∈ [0,1]：4 个压力特征各自线性归一到 [0,1] 取均值。

    归一化区间取标定文档的正常/重度边界（留过渡带，不直接卡阈值点）：
      init_steep   2.4(正常) → 6.0(重度)   越大越重
      late_rebound 10(强P2)  → 2(压平)     越小越重
      mid_min_vel  -1.5(强P2)→ 0(衰减)     越大越重
      slow_frac    0.35(正常)→ 0.7(重度)   越大越重
    取均值而非 AND：单门边界抖动只轻微拉扯均值，比 4 闸门 AND 稳定。

    Returns: float ∈ [0,1]；op_snap 缺失 / 拍数 < OP_MIN_BEATS → None（判不了）
    """
    if not op_snap or op_snap.get("n_beats", 0) < OP_MIN_BEATS:
        return None
    s1 = float(np.clip((op_snap.get("init_steep", 0.0) - 2.4) / (6.0 - 2.4), 0.0, 1.0))
    s2 = float(np.clip((10.0 - op_snap.get("late_rebound", 10.0)) / (10.0 - 2.0), 0.0, 1.0))
    s3 = float(np.clip((op_snap.get("mid_min_vel", -1.5) - (-1.5)) / (0.0 - (-1.5)), 0.0, 1.0))
    s4 = float(np.clip((op_snap.get("slow_frac", 0.35) - 0.35) / (0.7 - 0.35), 0.0, 1.0))
    return float(np.mean([s1, s2, s3, s4]))


def arbitrate_combo(flat_d, op_snap, sqi, good_thr=SQI_TM_GOOD, sqi1=None):
    """综合 SQI 优先级仲裁 + 综合分。

    优先级（命中即短路）：flat > pressure > SQI_tm 二分 > 幅度过低/unknown。

    flat 周期性豁免：flat 命中但检出 ≥2 拍且 SQI_corr ≥ 0.30 → 是"重压低幅
    周期波"而非"未贴/饱和"，不判 flat 继续仲裁。仅看拍数不行——真平直的残余
    噪声经带通也会检出 6-7 个假拍，SQI_corr 才是拍真假的判据。

    压力触发（特征驱动，不看分数）：n_beats≥2 且 p2_height≤0.32 且
    init_steep≥4.0 且 sev≥0.40。持续按压时 SQI_tm 会虚高（回 0.99+），
    故压力判定只认波形特征。

    Args:
        flat_d: compute_flat 的 dict 或 None
        op_snap: detect_overpressure 的 dict 或 None（含 n_beats/特征）
        sqi: compute_sqi 的 dict 或 None（mean_quality）
        good_thr: SQI_tm 合格线（默认 0.88）
        sqi1: compute_sqi1 的 dict 或 None（SQI_corr）

    Returns:
        (state_key, display_text, color, score)
        state_key ∈ {"flat","pressure","unstable","good","unknown"}
    """
    def _mq():
        if sqi is not None and sqi.get("valid"):
            v = sqi.get("mean_quality")
            if v is not None and not (isinstance(v, float) and np.isnan(v)):
                return float(v)
        return None

    def _corr():
        if sqi1 is not None and sqi1.get("valid"):
            v = sqi1.get("sqi1")
            if v is not None and not (isinstance(v, float) and np.isnan(v)):
                return float(v)
        return None

    n_beats = int(op_snap.get("n_beats", 0)) if op_snap else 0
    corr = _corr()
    # 周期性证据 = 检出拍 且 自相关过线（sqi1 缺 → 保守视为无证据 → 照判 flat）
    has_real_beats = (n_beats >= OP_MIN_BEATS
                      and corr is not None and corr >= COMBO_FLAT_EXEMPT_CORR)

    # 1) 平直（未贴好/接触不良/饱和/平直噪声假拍）——最高优先级；综合分=0
    if (flat_d is not None and flat_d.get("valid") and flat_d.get("flat")
            and not has_real_beats):
        return "flat", "⚠ 信号平直 (0.00)", C_POOR, 0.0

    # 2) 压力（特征驱动 4 闸门）
    sev = _pressure_severity(op_snap)
    base = _mq()
    stp = float(op_snap.get("init_steep", 0.0)) if op_snap else 0.0
    p2h = float(op_snap.get("p2_height", 1.0)) if op_snap else 1.0
    p2_gone = (n_beats >= OP_MIN_BEATS and (p2h <= COMBO_P2_HEIGHT_MAX)
               and (stp >= COMBO_STEEP_MIN))
    if sev is not None and sev >= COMBO_OP_FLOOR and p2_gone:
        factor = 1.0 - COMBO_MAX_PEN * sev
        score = round(base * factor, 2) if base is not None else 0.0
        tag = "(严重)" if sev >= COMBO_OP_SEVERE else ""
        return "pressure", f"⚠ 压力过大{tag} ({score:.2f})", C_POOR, score

    # 3)/4) SQI_tm 单阈值二分
    if base is not None:
        if base >= good_thr:
            return "good", f"信号良好 ({base:.2f})", C_GOOD, round(base, 2)
        return "unstable", f"信号不稳定 ({base:.2f})", C_FAIR, round(base, 2)

    # 兜底A：flat 但周期性豁免成立且 SQI_tm 缺 → 幅度过低（不吞掉周期信号）
    if (flat_d is not None and flat_d.get("valid") and flat_d.get("flat")
            and has_real_beats):
        return "unstable", "⚠ 信号幅度过低", C_FAIR, 0.0
    # 兜底B：数据不足
    return "unknown", "综合SQI: --", C_GRAY, None


def evaluate_combo_sqi(raw_ir, fs=100.0, good_thr=SQI_TM_GOOD):
    """一步到位：原始 IR → 全部输入 → 仲裁结果。

    Args:
        raw_ir: 一维原始 IR ADC 段（建议 ≥5s，即 500 点 @100Hz）
        fs: 采样率
        good_thr: SQI_tm 合格线

    Returns:
        (state_key, display_text, color, score, inputs)
        inputs 为 compute_sqi_inputs 的 dict（便于调试观察中间指标）
    """
    inputs = compute_sqi_inputs(raw_ir, fs=fs)
    state, text, color, score = arbitrate_combo(
        inputs["flat"], inputs["op_snap"], inputs["sqi"],
        good_thr=good_thr, sqi1=inputs["sqi1"])
    return state, text, color, score, inputs


# ---- 自测（不接硬件）：合成四路典型信号验证判定 ----
if __name__ == "__main__":
    import sys
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:  # noqa: BLE001
        pass

    fs = 100.0
    t = np.arange(500) / fs
    hr_hz = 75 / 60

    def synth_wave(dc, amp, noise, bpm=75.0, p2_ratio=0.7):
        """单分量正弦 + P2 次波峰合成波。"""
        ph = 2 * np.pi * bpm / 60 * t
        sig = dc + amp * np.sin(ph)
        sig += amp * p2_ratio * 0.5 * np.sin(2 * ph + np.pi * 0.35)
        sig += noise * np.random.randn(t.size)
        return sig

    np.random.seed(0)
    cases = [
        ("干净周期波（应 good/unstable）",   synth_wave(1e5, 4000.0, 50.0, p2_ratio=0.8)),
        ("平直（应 flat）",                  np.full(t.size, 5e4) + 2.0 * np.random.randn(t.size)),
        ("强噪声（应 flat/unstable）",       synth_wave(1e4, 50.0, 400.0)),
    ]
    for name, sig in cases:
        state, text, color, score, _ = evaluate_combo_sqi(sig, fs=fs)
        print(f"{name:24s} -> {state:9s} | {text}")
    print("selftest done")
