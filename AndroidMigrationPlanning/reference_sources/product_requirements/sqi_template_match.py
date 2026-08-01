# -*- coding: utf-8 -*-
"""
SQI 模板匹配质量评分 —— APP 端调用脚本（自包含，无项目内依赖）。

用途：在【预处理后的 PPG】上计算信号质量指数（SQI），用于判断本段波形是否
      规整可信、可否送入血压模型。阈值与上位机离线分析一致：mean_quality >= 0.90 合格。

算法：
  1. 在峰朝上的 PPG 上检测收缩期峰值；
  2. 按平均心拍周期切出等长心拍窗口（ratio_pre=0.5，即峰值居中）；
  3. 取所有心拍的逐点均值为"模板"；
  4. 每个心拍与模板做 Pearson 相关 → 得到逐心拍质量分 cycle_quality；
  5. 取 cycle_quality 的均值 mean_quality 作为该窗口的最终 SQI。


================================================================================
输入要求
================================================================================
  signal : 1D numpy 数组，**预处理后 PPG**：
           - 峰朝上（取负后）
           - 已完成平滑 + 带通 + CPE 增强 + 标准化（即 full_preprocessing_pipeline 的
             multi_channel[0]，长度 800 @ 100Hz）
           - 若仅有"取负+平滑+带通"信号也可计算，但与上位机预处理深度不同，数值会有偏差
  fs     : 采样率，默认 100

================================================================================
对外函数（按粒度从细到粗）
================================================================================
  detect_systolic_peaks(signal, fs)         → 收缩峰索引（find_peaks，轻量）
  build_template(signal, peaks, fs, ratio_pre) → (template, cycles, time_axis, valid_peaks)
  quality_templatematch(signal, peaks, fs, ratio_pre)
                                            → (quality, cycle_quality, template, cycles, ...)
  compute_sqi(signal, fs, ratio_pre)       ★ 主入口 → dict(mean_quality, n_cycles, ...)
  sqi_grade(mean_quality, good, fair)      → 按阈值给颜色标签

最快上手：
    from sqi_template_match import compute_sqi
    res = compute_sqi(preprocessed_ppg, fs=100)
    if res["valid"] and res["mean_quality"] >= 0.90:
        ...  # 合格
"""

import numpy as np
from scipy.signal import find_peaks


# ============ 基础：心拍切窗（NeuroKit2 signal_cyclesegment 的 numpy 版） ============

def _as_1d_float(x):
    arr = np.asarray(x, dtype=float)
    if arr.ndim != 1:
        raise ValueError("signal must be 1-D")
    return arr


def _sorted_unique_inds(inds):
    idx = np.asarray(inds, dtype=int).reshape(-1)
    if idx.size < 2:
        raise ValueError("at least two cycle indices required")
    return np.unique(np.sort(idx))


def _cycle_rate_bpm(inds, fs):
    inds = _sorted_unique_inds(inds)
    diffs = np.diff(inds)
    diffs = diffs[diffs > 0]
    if diffs.size == 0:
        raise ValueError("cycle indices must be increasing")
    return 60.0 * fs / float(np.mean(diffs))


def _segment_window(inds, fs, ratio_pre):
    """返回 (峰前秒数, 峰后秒数, 平均心率bpm) —— 决定每个心拍窗的宽度。"""
    rate = _cycle_rate_bpm(inds, fs)
    win_sec = 60.0 / rate
    return -ratio_pre * win_sec, (1.0 - ratio_pre) * win_sec, rate


def _cycle_segment(signal, inds, fs, ratio_pre):
    """按平均心拍周期，在每个峰值周围切等长窗口；越界用 NaN 填充。"""
    signal = _as_1d_float(signal)
    inds = _sorted_unique_inds(inds)
    if signal.size < fs * 4:
        raise ValueError("signal too short (< 4s) to segment")

    pre_sec, post_sec, rate = _segment_window(inds, fs, ratio_pre)
    pre = int(round(abs(pre_sec) * fs))
    post = int(round(post_sec * fs))
    n = pre + post + 1
    time_axis = (np.arange(n) - pre) / float(fs)

    cycles = np.full((inds.size, n), np.nan, dtype=float)
    for i, pk in enumerate(inds):
        s = pk - pre
        e = pk + post + 1
        ss = max(s, 0)                 # 源区起点（截到信号范围内）
        se = min(e, signal.size)       # 源区终点
        ds = ss - s                    # 目标区起点（与源区对齐）
        if se > ss:
            cycles[i, ds:ds + (se - ss)] = signal[ss:se]
    return cycles, time_axis, rate


def build_template(signal, peaks, fs=100, ratio_pre=0.5):
    """
    切心拍 + 算平均模板。

    Returns
    -------
    template : np.ndarray, 1D      平均脉搏模板
    cycles : np.ndarray, 2D        所有有效心拍（含 NaN 的已剔除）
    time_axis : np.ndarray, 1D     每心拍窗的相对时间轴(秒)
    valid_peaks : np.ndarray       与 cycles 行一一对应的有效峰位置
    rate_bpm : float               平均心率
    """
    cycles, time_axis, rate = _cycle_segment(signal, peaks, fs, ratio_pre)
    mask = ~np.isnan(cycles).any(axis=1)
    indiv = cycles[mask]
    valid_peaks = _sorted_unique_inds(peaks)[mask]
    if indiv.size == 0:
        raise ValueError("no complete cycle after NaN filtering")
    template = np.mean(indiv, axis=0)
    return template, indiv, time_axis, valid_peaks, rate


def _pearson(x, y):
    x = _as_1d_float(x)
    y = _as_1d_float(y)
    if x.size != y.size:
        raise ValueError("x and y must have equal length")
    xc = x - np.mean(x)
    yc = y - np.mean(y)
    denom = np.sqrt(np.sum(xc ** 2) * np.sum(yc ** 2))
    if denom <= 0:
        return np.nan
    return float(np.sum(xc * yc) / denom)


# ============ 核心：模板匹配质量评分 ============

def quality_templatematch(signal, peaks, fs=100, ratio_pre=0.5):
    """
    模板匹配质量评分。

    Returns
    -------
    quality : np.ndarray        与 signal 等长的逐样本质量曲线（前值保持）
    cycle_quality : np.ndarray  逐心拍质量分（Pearson）—— 心拍不足时本函数抛 ValueError
    template : np.ndarray       平均模板
    cycles : np.ndarray         有效心拍矩阵
    valid_peaks : np.ndarray    有效峰位置
    time_axis : np.ndarray      心拍窗时间轴
    """
    signal = _as_1d_float(signal)
    template, indiv, _tax, valid_peaks, _rate = build_template(
        signal, peaks, fs=fs, ratio_pre=ratio_pre)
    if valid_peaks.size < 2:
        raise ValueError("need >= 2 valid cycles to build quality trace")

    cycle_quality = np.empty(valid_peaks.size - 1, dtype=float)
    for i in range(cycle_quality.size):
        cycle_quality[i] = _pearson(indiv[i], template)

    # 逐样本展开（前值保持）
    quality = np.empty(signal.size, dtype=float)
    anchor = valid_peaks[:-1]
    quality[:anchor[0]] = cycle_quality[0]
    for i in range(anchor.size - 1):
        quality[anchor[i]:anchor[i + 1]] = cycle_quality[i]
    quality[anchor[-1]:] = cycle_quality[-1]
    return quality, cycle_quality, template, indiv, valid_peaks, _tax


# ============ 峰值检测（轻量，参数对齐离线） ============

def detect_systolic_peaks(signal, fs=100, hr_max=180):
    """
    在【峰朝上】的 PPG 上检测收缩期峰值。
    参数与上位机 detect_peaks / local_preprocess detect_systolic_peaks 对齐。
    """
    sig = _as_1d_float(signal)
    std = np.std(sig)
    if std < 1e-8:
        return np.array([], dtype=int)
    mean = np.mean(sig)
    min_distance = max(1, int(fs * 60 / hr_max))

    peaks, _ = find_peaks(sig, distance=min_distance,
                          height=mean + 0.5 * std,
                          prominence=0.3 * std, width=5)
    if peaks.size < 2:
        peaks, _ = find_peaks(sig, distance=min_distance,
                              height=mean + 0.2 * std,
                              prominence=0.2 * std)
    return peaks


# ============ ★ 主入口 ============

def compute_sqi(signal, fs=100, ratio_pre=0.5):
    """
    计算单段预处理后 PPG 的模板匹配质量分。

    Parameters
    ----------
    signal : array-like    预处理后 PPG（峰朝上，800 点 @ 100Hz）
    fs : int               采样率
    ratio_pre : float      心拍窗中峰值前的占比（0.5 = 峰居中）

    Returns
    -------
    dict:
      valid        (bool)    是否成功评分
      mean_quality (float)   ★ 最终 SQI，逐心拍 Pearson 均值；>=0.90 合格
      n_cycles     (int)     有效心拍数（即 cycle_quality 长度）
      n_peaks      (int)     检测到的峰数
      hr_est       (float)   估计心率 bpm（基于峰间距中位数）
      reason       (str)     失败原因（valid=False 时）；心拍/峰不足由
                             quality_templatematch 的 cycle_quality 体现
                             （算不出会落到 template_failed:*）
    """
    res = {"valid": False, "mean_quality": np.nan,
           "n_cycles": 0, "n_peaks": 0, "hr_est": np.nan, "reason": None}
    sig = _as_1d_float(signal)

    if sig.size < fs * 4:
        res["reason"] = "signal_too_short"
        return res
    if np.std(sig) < 1e-8:
        res["reason"] = "constant_signal"
        return res

    peaks = detect_systolic_peaks(sig, fs=fs)
    res["n_peaks"] = int(peaks.size)

    diffs = np.diff(peaks)
    diffs = diffs[diffs > 0]
    if diffs.size:
        res["hr_est"] = float(60.0 * fs / float(np.median(diffs)))

    # 不再为 peaks<2 单独返回 insufficient_peaks：心拍不足时 cycle_quality 无法
    # 构建，quality_templatematch 会抛异常，由 cq 的缺失自然体现，落到 template_failed。
    try:
        _q, cq, _template, _cycles, _vp, _tax = quality_templatematch(
            sig, peaks, fs=fs, ratio_pre=ratio_pre)
    except ValueError as exc:
        res["reason"] = f"template_failed:{exc}"
        return res
    except Exception as exc:  # noqa: BLE001
        res["reason"] = f"template_error:{type(exc).__name__}"
        return res

    if cq is None or cq.size == 0:
        res["reason"] = "no_cycle_quality"
        return res

    res.update({"valid": True,
                "mean_quality": float(np.mean(cq)),
                "n_cycles": int(cq.size),
                "reason": "ok"})
    return res


# ============ 阈值工具（与上位机一致） ============

def sqi_grade(mean_quality, good=0.90, fair=0.70):
    """按 mean_quality 给颜色标签：>=good 绿 / >=fair 橙 / 否则红。"""
    if mean_quality is None or np.isnan(mean_quality):
        return "—", "#888888"
    if mean_quality >= good:
        return "Good", "#2E7D32"
    if mean_quality >= fair:
        return "Fair", "#EF6C00"
    return "Poor", "#C62828"
