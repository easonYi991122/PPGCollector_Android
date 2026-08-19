"""
PPG 信号质量指数（SQI）实时计算模块

NeuroKit2 "模板匹配"质量评分的纯 numpy 实现，
并配一个轻量的 scipy.find_peaks 收缩期峰值检测器，封装成 ``compute_sqi()`` 供
后端实时调用（单次约 0.3ms，配合 2s 滑窗毫无压力）。

评分口径：
  - 检测心拍峰值 → 按峰值切出心拍窗口 → 取所有心拍均值为模板
  - 每个心拍与模板做 Pearson 相关 → 取均值/最小/标准差作为该窗口的质量分
  - 输入信号为预处理后 PPG（CPE 增强、峰朝上、800 点 @100Hz）

（综合 SQI 内的合格线为 0.88，见 combo_sqi.py。）
"""

import numpy as np
from scipy.signal import find_peaks

# 自相关 SQI 复用 ppg_metrics.py（口径一致）
from ppg_metrics import autocorr_sqi


# ============ 嵌入式两步评估（sqi1） ============
# 三级阈值 + flat 直接判噪声
SQI1_EXCELLENT = 0.70
SQI1_GOOD = 0.525     # 嵌入式 good/noise 门
SQI1_POOR = 0.30


def _moving_average_fixed(x, w):
    """3 次滑动平均中的单次（与 complete_preprocessing_pipeline.moving_average_filter_fixed 同实现）。"""
    x = np.asarray(x, dtype=float)
    if w < 1:
        w = 1
    n = len(x)
    if n <= w:
        return x.copy()
    out = np.empty(n, dtype=float)
    # 前 w-1 点：非对称口径（保留原值，避免边界伪迹）
    out[:w - 1] = x[:w - 1]
    csum = np.cumsum(np.insert(x, 0, 0.0))
    out[w - 1:] = (csum[w:] - csum[:n - w + 1]) / w
    return out


def _smooth_three(x):
    """sqi1 专用预处理：仅 3 次滑动平均（窗 2,2,10），无带通。"""
    x = _moving_average_fixed(x, 2)
    x = _moving_average_fixed(x, 2)
    x = _moving_average_fixed(x, 10)
    return x


def _check_flat(signal, rel_thr=0.001):
    """相对 DC 阈值判定直线。"""
    s = np.asarray(signal, dtype=float)
    if len(s) < 2:
        return True
    dc = abs(np.nanmean(s)) + 1e-9
    std = float(np.nanstd(s))
    p2p = float(np.nanmax(s)) - float(np.nanmin(s))
    return (std / dc < rel_thr) or (p2p / dc < rel_thr)


def compute_sqi1(raw_ir, raw_red=None, fs=100, hr_min=40, hr_max=150):
    """
    嵌入式自相关 SQI（sqi1）。

    取负 + 仅 3 次平滑（无带通）后，在心率范围 [hr_min, hr_max] bpm 内取最大自相关值；
    ours=red+ir 平均，红光缺失时退化为 IR 单通道。

    注意：直线判别已拆为独立函数 compute_flat，不再在此预判；flat 状态由调用方单独取。
    入参用**原始 ADC**（内部取负后算）。
    """
    res = {"valid": False, "sqi1": np.nan, "grade": "—", "ok": False, "reason": None}
    raw_ir = np.asarray(raw_ir, dtype=float)
    if raw_ir.size < fs * 2:  # 至少 2s 才能算自相关
        res["reason"] = "signal_too_short"
        return res

    # 取负 + 仅 3 次平滑（无带通）
    ir_s = _smooth_three(-raw_ir)
    s1_ir, _ = autocorr_sqi(ir_s, fs=fs, hr_min=hr_min, hr_max=hr_max)

    if raw_red is not None:
        raw_red = np.asarray(raw_red, dtype=float)
        red_s = _smooth_three(-raw_red) if raw_red.size >= fs * 2 else None
        if red_s is not None:
            s1_red, _ = autocorr_sqi(red_s, fs=fs, hr_min=hr_min, hr_max=hr_max)
            sqi1 = float(np.nanmean([s1_ir, s1_red]))
        else:
            sqi1 = float(s1_ir)
    else:
        sqi1 = float(s1_ir)

    if sqi1 >= SQI1_EXCELLENT:
        grade, ok = "优秀", True
    elif sqi1 >= SQI1_GOOD:
        grade, ok = "良好", True
    elif sqi1 >= SQI1_POOR:
        grade, ok = "较差", False
    else:
        grade, ok = "噪声", False

    return {"valid": True, "sqi1": sqi1, "grade": grade, "ok": ok, "reason": "ok"}


def compute_pi(raw_ir):
    """
    灌注指数 PI = (AC 峰峰值 / |DC|) × 100%。
    AC = 原始波形（取负、峰朝上）的 max - min；
    DC = 原始信号均值（取 abs 避免 ADC 倒置出现负值除法）。
    PI 峰谷对法。

    信息性指标，无硬门槛；跨设备绝对值不可直接比。
    """
    seg = np.asarray(raw_ir, dtype=float)
    if seg.size < 2:
        return {"valid": False, "pi": np.nan}
    # 取负后峰朝上，AC=p2p 直观
    seg_n = -seg
    dc = float(np.mean(seg_n))
    ac = float(np.nanmax(seg_n)) - float(np.nanmin(seg_n))
    if abs(dc) < 1e-9:
        return {"valid": False, "pi": np.nan}
    return {"valid": True, "pi": ac / abs(dc) * 100.0}


def compute_pi2(raw_ir, fs=100):
    """
    PI2 灌注指数（峰谷对版，与 compute_pi / PI1 并存的验证方案）：

      AC = mean(各峰谷对的峰峰值)         # 一个脉搏的幅度，逐对求均值（抗单峰异常）
      DC = mean(窗口内所有谷值)            # 谷值=舒张期基线，比全窗口均值更稳
      PI2 = AC / |DC| × 100 %

    峰谷【位置】在「取负+3次平滑」信号上检测（避免高频抖动伪峰）；
    峰谷【幅值】取自原始取负信号（平滑不改均值量级；切勿用带通信号——带通改幅值，PI 失真）。
    配对：每个峰配其前一个最近的谷。

    信息性指标，无硬门槛；与 PI1 并排显示用于对比验证。
    """
    res = {"valid": False, "pi": np.nan, "n_pairs": 0, "reason": None}
    seg = -np.asarray(raw_ir, dtype=float)   # 取负，峰朝上
    if seg.size < fs * 2:
        res["reason"] = "signal_too_short"
        return res

    smooth = _smooth_three(seg)              # 仅 3 次平滑，无带通（保幅值）
    peaks = detect_peaks(smooth, fs=fs)
    valleys = detect_peaks(-smooth, fs=fs)
    if peaks.size < 1 or valleys.size < 1:
        res["reason"] = "no_peaks_or_valleys"
        return res

    # 每个峰配其前一个最近的谷；峰峰值取自原始 seg（非平滑信号）
    pair_amps = []
    vi = 0
    for p in peaks:
        while vi < len(valleys) and valleys[vi] < p:
            vi += 1
        if vi > 0:
            v = valleys[vi - 1]
            pair_amps.append(float(seg[p] - seg[v]))
    if len(pair_amps) < 1:
        res["reason"] = "no_pairs"
        return res

    ac = float(np.mean(pair_amps))
    dc = float(np.mean(seg[valleys]))        # 所有谷值均值
    if abs(dc) < 1e-9:
        res["reason"] = "dc_zero"
        return res
    res.update({"valid": True, "pi": ac / abs(dc) * 100.0,
                "n_pairs": len(pair_amps), "reason": "ok"})
    return res


def _skew_kurt(x):
    """样本偏度（Fisher-Pearson, bias-corrected）与超额峰度（Fisher, bias-corrected）。

    纯 numpy 实现，口径匹配 ``scipy.stats.skew(bias=False)`` / ``scipy.stats.kurtosis(bias=False,
    fisher=True)``（注意 scipy 默认 bias=True，这里用更标准的样本无偏估计），但不引入 scipy.stats 依赖。
    - n<3 → 偏度 NaN；n<4 → 峰度 NaN；方差≈0 → 均 NaN。
    - 返回 (skew, kurt)；kurt 为超额峰度（正态分布=0）。
    """
    x = np.asarray(x, dtype=float)
    n = x.size
    if n < 3:
        return np.nan, np.nan
    d = x - x.mean()
    m2 = float(np.mean(d ** 2))
    if m2 < 1e-12:
        return np.nan, np.nan
    m3 = float(np.mean(d ** 3))
    g1 = m3 / (m2 ** 1.5)
    skew = g1 * np.sqrt(n * (n - 1)) / (n - 2)
    kurt = np.nan
    if n >= 4:
        m4 = float(np.mean(d ** 4))
        g2 = m4 / (m2 ** 2) - 3.0
        kurt = ((n - 1) / ((n - 2) * (n - 3))) * ((n + 1) * g2 + 6.0)
    return float(skew), float(kurt)


def compute_skew_kurt(raw_ir, fs=100, min_cycle_pts=10):
    """对每个脉搏周期计算偏度/峰度，取跨周期均值（信息性形态指标，无硬门槛）。

    分割复用 compute_pi2 的口径：取负 + 3 次平滑（无带通，保形态）→
    detect_peaks 检测谷值 → 以【相邻谷】为周期边界切出每个脉搏周期（谷=舒张期基线，
    谷-谷即一个完整脉搏）→ 在【平滑】信号上算偏度/峰度。

    高阶矩对高频噪声极敏感，故用平滑后信号（非原始取负、更非带通）算矩——
    平滑只去抖不改形态，峰度/偏度反映的是脉搏波形形状而非噪声尖刺。

    Returns:
        dict{valid(bool), skew(float), kurt(float), n_cycles(int), reason(str)}
        - skew: 样本偏度（>0 右偏=收缩峰偏前/陡升缓降；<0 左偏）
        - kurt: 超额峰度（>0 比正态尖=主峰突出；<0 平坦）
    """
    res = {"valid": False, "skew": np.nan, "kurt": np.nan, "n_cycles": 0, "reason": None}
    seg = -np.asarray(raw_ir, dtype=float)   # 取负，峰朝上（与 compute_pi2 一致）
    if seg.size < fs * 2:
        res["reason"] = "signal_too_short"
        return res

    smooth = _smooth_three(seg)              # 仅 3 次平滑，无带通（保形态）
    valleys = detect_peaks(-smooth, fs=fs)   # 谷=平滑信号取负后的峰
    if valleys.size < 2:
        res["reason"] = "no_cycles"
        return res

    sks, kus = [], []
    for a, b in zip(valleys[:-1], valleys[1:]):
        if b - a < min_cycle_pts:
            continue
        s, k = _skew_kurt(smooth[a:b + 1])
        if not (np.isnan(s) or np.isnan(k)):
            sks.append(s)
            kus.append(k)
    if len(sks) < 1:
        res["reason"] = "no_valid_cycles"
        return res

    res.update({"valid": True, "skew": float(np.mean(sks)),
                "kurt": float(np.mean(kus)), "n_cycles": len(sks), "reason": "ok"})
    return res


def compute_flat(raw_ir, fs=100, rel_thr=0.001):
    """
    直线判别（独立函数，从 compute_sqi1 拆出；供 GUI 单独显示用）。
    判据：std/|DC| < rel_thr 或 p2p/|DC| < rel_thr（与 _check_flat / 嵌入式口径一致）。

    Returns: dict{valid(bool), flat(bool), reason(str)}
    """
    seg = np.asarray(raw_ir, dtype=float)
    if seg.size < 2:
        return {"valid": False, "flat": True, "reason": "signal_too_short"}
    is_flat = _check_flat(seg, rel_thr=rel_thr)
    return {"valid": True, "flat": bool(is_flat),
            "reason": "flat" if is_flat else "ok"}


# ============ 纯 numpy 模板匹配 ============

def _as_1d_float_array(x):
    arr = np.asarray(x, dtype=float)
    if arr.ndim != 1:
        raise ValueError("Input signal must be 1D.")
    return arr


def _as_sorted_unique_int_indices(cycle_indices):
    idx = np.asarray(cycle_indices, dtype=int).reshape(-1)
    if idx.size < 2:
        raise ValueError("At least two cycle indices are required.")
    return np.unique(np.sort(idx))


def _estimate_cycle_rate_bpm(cycle_indices, sampling_rate):
    cycle_indices = _as_sorted_unique_int_indices(cycle_indices)
    diffs = np.diff(cycle_indices)
    diffs = diffs[diffs > 0]
    if diffs.size == 0:
        raise ValueError("Cycle indices must contain increasing positions.")
    mean_period_samples = float(np.mean(diffs))
    return 60.0 * float(sampling_rate) / mean_period_samples


def _segment_window(cycle_indices=None, sampling_rate=1000, ratio_pre=0.5,
                    cycle_rate_bpm=None):
    if not 0.0 < ratio_pre < 1.0:
        raise ValueError("`ratio_pre` must be between 0 and 1.")
    if cycle_rate_bpm is None:
        if cycle_indices is None:
            raise ValueError("Either cycle_rate_bpm or cycle_indices must be provided.")
        cycle_rate_bpm = _estimate_cycle_rate_bpm(cycle_indices, sampling_rate)
    else:
        cycle_rate_bpm = float(np.mean(np.asarray(cycle_rate_bpm, dtype=float)))
    window_size_sec = 60.0 / cycle_rate_bpm
    return (-ratio_pre * window_size_sec,
            (1.0 - ratio_pre) * window_size_sec,
            cycle_rate_bpm)


def _signal_cyclesegment(signal_cleaned, cycle_indices, ratio_pre=0.5,
                         sampling_rate=1000):
    signal_cleaned = _as_1d_float_array(signal_cleaned)
    cycle_indices = _as_sorted_unique_int_indices(cycle_indices)

    if signal_cleaned.size < sampling_rate * 4:
        raise ValueError("The data length is too small to be segmented.")

    epochs_start_sec, epochs_end_sec, avg_rate = _segment_window(
        cycle_indices=cycle_indices, sampling_rate=sampling_rate, ratio_pre=ratio_pre,
    )

    pre_samples = int(round(abs(epochs_start_sec) * sampling_rate))
    post_samples = int(round(epochs_end_sec * sampling_rate))
    n_samples = pre_samples + post_samples + 1

    time_axis = (np.arange(n_samples) - pre_samples) / float(sampling_rate)
    cycles = np.full((cycle_indices.size, n_samples), np.nan, dtype=float)

    for i, peak in enumerate(cycle_indices):
        start = peak - pre_samples
        end = peak + post_samples + 1
        src_start = max(start, 0)
        src_end = min(end, signal_cleaned.size)
        dst_start = src_start - start
        dst_end = dst_start + (src_end - src_start)
        if src_end > src_start:
            cycles[i, dst_start:dst_end] = signal_cleaned[src_start:src_end]
    return cycles, time_axis, avg_rate


def _calc_template_morph(signal, cycle_inds, sampling_rate=1000, ratio_pre=0.5):
    cycles, time_axis, avg_rate = _signal_cyclesegment(
        signal, cycle_inds, ratio_pre=ratio_pre, sampling_rate=sampling_rate,
    )
    valid_mask = ~np.isnan(cycles).any(axis=1)
    individual_cycles = cycles[valid_mask]
    valid_cycle_inds = _as_sorted_unique_int_indices(cycle_inds)[valid_mask]
    if individual_cycles.size == 0:
        raise ValueError("No complete cycles remain after NaN filtering.")
    template_pw = np.mean(individual_cycles, axis=0)
    return template_pw, individual_cycles, valid_cycle_inds, time_axis, avg_rate


def _pearson_corr(x, y):
    x = _as_1d_float_array(x)
    y = _as_1d_float_array(y)
    if x.size != y.size:
        raise ValueError("Arrays must have the same length.")
    xc = x - np.mean(x)
    yc = y - np.mean(y)
    denom = np.sqrt(np.sum(xc ** 2) * np.sum(yc ** 2))
    if denom <= 0:
        return np.nan
    return float(np.sum(xc * yc) / denom)


def quality_templatematch(signal, cycle_inds, sampling_rate=1000, ratio_pre=0.5):
    """模板匹配质量评分。返回 (quality, cycle_quality, template, cycles, valid_peaks, time_axis)。"""
    signal = _as_1d_float_array(signal)
    (template_pw, individual_cycles, valid_cycle_inds,
     time_axis, _) = _calc_template_morph(signal, cycle_inds, sampling_rate, ratio_pre)

    if valid_cycle_inds.size < 2:
        raise ValueError("At least two valid cycles are required to build a quality trace.")

    cycle_quality = np.empty(valid_cycle_inds.size - 1, dtype=float)
    for i in range(cycle_quality.size):
        cycle_quality[i] = _pearson_corr(individual_cycles[i], template_pw)

    quality = np.empty(signal.size, dtype=float)
    anchor_inds = valid_cycle_inds[:-1]
    quality[: anchor_inds[0]] = cycle_quality[0]
    for i in range(anchor_inds.size - 1):
        quality[anchor_inds[i]: anchor_inds[i + 1]] = cycle_quality[i]
    quality[anchor_inds[-1]:] = cycle_quality[-1]
    return quality, cycle_quality, template_pw, individual_cycles, valid_cycle_inds, time_axis


# ============ 轻量峰值检测（参数对齐离线 detect_systolic_peaks） ============

def detect_peaks(signal, fs=100, hr_max=180):
    """
    在峰朝上的 PPG 上检测收缩期峰值。
    参数与 PPG 预处理管线的 detect_systolic_peaks 对齐，
    保证实时评分与离线分析口径一致。
    """
    sig = np.asarray(signal, dtype=float)
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


# ============ 对外接口 ============

def compute_sqi(signal, fs=100, ratio_pre=0.5):
    """
    计算单段 PPG 的模板匹配质量分。

    Args:
        signal: 预处理后 PPG（峰朝上），1D 数组。
        fs: 采样率 Hz。
        ratio_pre: 心拍窗口中峰值前的占比。

    Returns:
        dict:
          valid (bool), mean_quality, min_quality, std_quality,
          n_cycles, n_peaks, hr_est, template (np.ndarray or None), reason (str)
    """
    res = {
        "valid": False, "mean_quality": np.nan, "min_quality": np.nan,
        "std_quality": np.nan, "n_cycles": 0, "n_peaks": 0,
        "hr_est": np.nan, "template": None, "reason": None,
    }
    sig = np.asarray(signal, dtype=float)

    if sig.size < fs * 4:
        res["reason"] = "signal_too_short"
        return res
    if np.std(sig) < 1e-8:
        res["reason"] = "constant_signal"
        return res

    peaks = detect_peaks(sig, fs=fs)
    res["n_peaks"] = int(peaks.size)
    if peaks.size < 2:
        res["reason"] = "insufficient_peaks"
        return res

    diffs = np.diff(peaks)
    diffs = diffs[diffs > 0]
    if diffs.size:
        res["hr_est"] = float(60.0 * fs / float(np.median(diffs)))

    try:
        _q, cq, template, _cycles, _vp, _tax = quality_templatematch(
            sig, peaks, sampling_rate=fs, ratio_pre=ratio_pre,
        )
    except ValueError as exc:
        res["reason"] = f"template_failed:{exc}"
        return res
    except Exception as exc:  # noqa: BLE001
        res["reason"] = f"template_error:{type(exc).__name__}"
        return res

    if cq is None or cq.size == 0:
        res["reason"] = "no_cycle_quality"
        return res

    res.update({
        "valid": True,
        "mean_quality": float(np.mean(cq)),
        "min_quality": float(np.min(cq)),
        "std_quality": float(np.std(cq)),
        "n_cycles": int(cq.size),
        "template": template,
        "reason": "ok",
    })
    return res
