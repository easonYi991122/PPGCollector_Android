# -*- coding: utf-8 -*-
"""压力过大 / 无 P2 波形判据

来源（逐字复制 + 极性/口径封装）：
  - find_peaks_method3   ← based_feature/preprocessing/extract_pulses.py
  - detect_fiducials     ← 小波分解特征点检测（自包含实现）
  - compute_rapid_decline← 小波分解特征点检测（自包含实现）（v5 四闸门）

判据阈值在 **bandpass filtered_data（0.5–12Hz, order=2, 零均值, 无 CPE 无 z-score）**
上 200 窗验证（precision 0.879 / recall 0.806）。本模块只依赖 numpy/scipy，
**不 import pywt / matplotlib / torch**，纯 numpy/scipy 自包含。

判定方法详见本文档头部说明（v5 四闸门）。
"""

import sys
from pathlib import Path

import numpy as np
from scipy.signal import find_peaks, argrelextrema

# 自包含 bootstrap：保证 standalone（如 selftest 直接 import）也能找到同目录依赖
_PKG_DIR = str(Path(__file__).resolve().parent)
if _PKG_DIR not in sys.path:
    sys.path.insert(0, _PKG_DIR)
from complete_preprocessing_pipeline import bandpass_filter  # noqa: E402


# ============================================================
# 差分上升沿法：假设信号收缩峰朝上（上升沿 R1 → 顶 P1 → 下降）。
# 内部 bare except 原样保留以与上游行为完全一致。
# ============================================================
def find_peaks_method3(signal, fs=100.0):
    """
    方法3：基于差分信号的特征点检测

    步骤：
    1. 计算脉搏波的一阶差分信号，识别差分>0部分的极大值点（R1点 - 最大斜率点）
    2. 以R1为分界，识别R1前后差分=0的点，分别对应周期起始点(T)和主波峰(P1)
    3. 在P1和下一个T点之间寻找最大峰值作为潮波峰(P2)
       - 如果P2与P1距离小于0.1秒，则在P2后继续寻找第二个峰值

    Returns: dict(t_R1/t_T/t_P1/t_P2/t_V1/t_diff_zero/diff_signal, 全为 np.array)
    """
    T = 1 / fs
    pulse_relative = np.array(signal)

    result = {
        't_R1_index': [],       # R1点：一阶差分极大值点（最大斜率）
        't_T_index': [],         # T点：周期起始点（R1前差分=0点）
        't_P1_index': [],        # P1点：主波峰（R1后差分=0点）
        't_P2_index': [],        # P2点：潮波峰（P1后最大峰值）
        't_V1_index': [],       # V1点：重搏切迹
        't_diff_zero': [],        # 差分信号过零点
        'diff_signal': []        # 一阶差分信号
    }

    if len(pulse_relative) < 10:
        return {k: np.array(v) for k, v in result.items()}

    # 步骤1：计算一阶差分信号
    diff_signal = np.zeros_like(pulse_relative)
    diff_signal[1:] = np.diff(pulse_relative)
    result['diff_signal'] = diff_signal.copy()

    # 找差分信号大于0的区间中的极大值点（R1点 - 最大斜率点）
    positive_mask = diff_signal > 0

    hr_distance = int(0.4 * fs)
    min_distance = int(0.3 * fs)

    if np.any(positive_mask):
        positive_indices = np.where(positive_mask)[0]
        if len(positive_indices) > 2:
            try:
                r1_candidates = find_peaks(diff_signal, height=np.mean(diff_signal)+1*np.std(diff_signal), distance=hr_distance)[0]
                r1_candidates = r1_candidates[positive_mask[r1_candidates]]

                if len(r1_candidates) > 0:
                    if len(r1_candidates) > 1:
                        r1_diff = np.diff(r1_candidates)
                        valid_mask = r1_diff >= min_distance
                        valid_indices = np.concatenate([[True], valid_mask])
                        r1_candidates = r1_candidates[valid_indices]

                    result['t_R1_index'] = r1_candidates.tolist()
            except:
                pass

    if len(result['t_R1_index']) == 0:
        return {k: np.array(v) for k, v in result.items()}

    # 步骤2：找每个R1点前后的差分=0点（T点和P1点）
    t_T_list = []
    t_P1_list = []

    for r1_idx in result['t_R1_index']:
        # 找R1点前的差分过零点（T点）
        before_r1 = diff_signal[:r1_idx]

        # 找差分从负变正或从正变负的过零点
        sign_before = np.sign(before_r1)
        zero_crossings = np.where(np.diff(sign_before) != 0)[0]

        if len(zero_crossings) > 0:
            # 找最后一个过零点，且该点之后差分>0（上升开始）
            for zc in reversed(zero_crossings):
                if zc < len(before_r1) - 1 and diff_signal[zc + 1] > 0 and zc != 0:
                    t_T = zc + 1
                    t_T_list.append(t_T)
                    break

        # 找R1点后的差分过零点（P1点）
        after_r1 = diff_signal[r1_idx:]

        # 找差分从正变负的过零点（峰值处）
        sign_after = np.sign(after_r1)
        zero_crossings_after = np.where(np.diff(sign_after) != 0)[0]

        if len(zero_crossings_after) > 0:
            # 找第一个过零点，且该点之前差分>0
            for zc in zero_crossings_after:
                if zc > 0 and diff_signal[r1_idx + zc - 1] > 0:
                    t_P1 = r1_idx + zc
                    t_P1_list.append(t_P1)
                    break
        else:
            # 如果没有找到过零点，找差分最大值点
            if len(after_r1) > 0:
                t_P1 = r1_idx + np.argmax(after_r1)
                t_P1_list.append(t_P1)
            else:
                t_P1_list.append(r1_idx + int(0.1 * fs))

    result['t_T_index'] = t_T_list
    result['t_P1_index'] = t_P1_list

    result['t_P2_index'] = []
    t_P2_list = []

    for i in range(1, len(t_T_list)):
        for p1 in t_P1_list:
            if p1 > t_T_list[i-1] and p1 < t_T_list[i]:
                break

        signal = pulse_relative[p1 : t_T_list[i]]
        temp_p2_list = find_peaks(signal)[0]

        if len(temp_p2_list) == 0:
            continue

        temp_p2_values = [pulse_relative[peaks + p1] for peaks in temp_p2_list]
        temp_p2 = temp_p2_list[np.argmax(temp_p2_values)]
        temp_p2 = temp_p2 + p1

        if temp_p2 - p1 < 0.01 * fs:
            signal = pulse_relative[temp_p2 : t_T_list[i]]
            temp_p2_2_list = find_peaks(signal)[0]

            if len(temp_p2_2_list) == 0:
                continue

            temp_p2_values = [pulse_relative[peaks + temp_p2] for peaks in temp_p2_2_list]
            temp_p2_2 = temp_p2_2_list[np.argmax(temp_p2_values)]
            temp_p2 = temp_p2_2 + temp_p2

        p2 = temp_p2
        t_P2_list.append(p2)

    result['t_P2_index'] = t_P2_list

    # 差分过零点
    diff_zero_crossings = np.where(np.diff(np.sign(diff_signal)) != 0)[0]
    result['t_diff_zero'] = diff_zero_crossings.tolist()

    # 找V1点（重搏切迹）
    result['t_V1_index'] = []
    t_V1_list = []

    for i in range(len(t_P1_list)-1):
        p1 = t_P1_list[i]
        for p2 in t_P2_list:
            if p2 > p1 and p2 < t_P1_list[i+1]:
                signal_segment = pulse_relative[p1 : p2]
                temp_v1_list = find_peaks(-signal_segment)[0]

                if len(temp_v1_list) == 0:
                    continue

                temp_v1_values = [pulse_relative[v + p1] for v in temp_v1_list]
                temp_v1 = temp_v1_list[np.argmin(temp_v1_values)]
                temp_v1 = temp_v1 + p1

                v1 = temp_v1
                t_V1_list.append(v1)
                break

    result['t_V1_index'] = t_V1_list

    return {k: np.array(v) for k, v in result.items()}


# ============================================================
# compute_rapid_decline
# ============================================================
def _find_peaks_local(x):
    """纯 numpy 局部极大索引（与 scipy.find_peaks 无 prominence 口径一致：
    严格大于左邻、大于等于右邻的内部点）。"""
    x = np.asarray(x, dtype=float)
    if x.size < 3:
        return np.array([], dtype=int)
    idx = np.where((x[1:-1] > x[:-2]) & (x[1:-1] >= x[2:]))[0] + 1
    return idx


def compute_rapid_decline(
    sig: np.ndarray,
    fid: dict,
    fs: float = 100.0,
    dicrotic_lo_ms: int = 120,
    dicrotic_hi_ms: int = 350,
    win_samples: int = 5,
    mid_min_vel_thr: float = 0.6,
    init_steep_thr: float = 3.4,
    init_steep_ms: int = 80,
    late_rebound_thr: float = 1.5,
    slow_frac_alpha: float = 0.3,
    slow_frac_thr: float = 0.35,
) -> dict:
    """判别"压力过大/急速下降"样波形（P1 后急降、无明显重搏波 P2 肩部）。

    v5 复合判据（四条**全部**满足）→ rapid_decline=True：
      init_steep≥3.4 AND late_rebound≤1.5 AND mid_min_vel≥0.6 AND slow_frac≥0.35
    见模块头部说明。

    Returns: dict(init_steep, late_rebound, mid_min_vel, slow_frac, t100_mean,
                  rapid_decline, n_beats)
    """
    p1 = fid.get("t_P1_index", np.array([], dtype=int)).astype(int)
    tT = fid.get("t_T_index", np.array([], dtype=int)).astype(int)
    out = {"init_steep": 0.0, "late_rebound": 0.0, "mid_min_vel": 0.0,
           "slow_frac": 0.0, "t100_mean": 0.0, "rapid_decline": False, "n_beats": 0}
    if len(p1) == 0:
        return out
    # 极性归一（让 P1 朝上，下降为负），仅作用于本地副本（不 savgol，匹配验证口径）
    sgn = 1 if sig[p1[0]] >= sig[max(0, p1[0] - 80)] else -1
    s = sgn * np.asarray(sig, dtype=np.float64)
    deriv = np.zeros_like(s)
    deriv[1:] = np.diff(s)  # 一阶导
    lo = max(1, int(dicrotic_lo_ms / 1000.0 * fs))
    hi = max(lo + win_samples + 1, int(dicrotic_hi_ms / 1000.0 * fs))
    n_init = max(2, int(init_steep_ms / 1000.0 * fs))
    reb_lo = max(n_init, 8)  # 急降段后开始查二次峰
    mid_vel_list, steep_list, reb_list, t100_list, slow_list, area_list, p2_list, n_beats = \
        [], [], [], [], [], [], [], 0
    for bi, p in enumerate(p1):
        nxt_a = tT[tT > p]
        nxt = int(nxt_a[0]) if len(nxt_a) > 0 else len(s) - 1
        prev = tT[tT < p]
        foot = int(prev[-1]) if len(prev) > 0 else max(0, p - 80)
        if nxt - p < hi:  # 搏动过短（<350ms / >170BPM）跳过
            continue
        n_beats += 1
        amp = s[p] - s[foot]
        if amp < 1e-6:
            continue
        # 一阶导归一（%amp/sample）
        dn = deriv / amp * 100.0
        # 相对下降曲线（%amp：0=P1 高度，负=下降量占 amp 的百分比）
        rel = (s[p:nxt] - s[p]) / amp * 100.0
        # dn_area：下降支下方面积（P1→下一拍P1 全拍分段、拍内最小值=0/P1=1 归一化后
        # P1→谷的均值高度，无量纲）。压力越大下降支越"陡峭到底"→面积越小；正常压力
        # 下降支"厚"→面积大。★分段须用下一拍 P1（与标定口径一致），不能用 tT 终点
        # （tT 提前截断会让拍尾平坦段丢失、面积系统性偏小、分离度变弱）。
        # 标定（逐拍中位）：典型正常 0.34-0.51 / 无P2但压力正常 0.29 /
        # 压力过大(重) 0.19-0.25 / 轻压 0.39。
        nxt_p1 = int(p1[bi + 1]) if bi + 1 < len(p1) else nxt
        segA = s[p:nxt_p1]
        if len(segA) >= 50:
            vi = int(np.argmin(segA))
            if vi > 4:
                norm_dn = (segA[:vi + 1] - segA[vi]) / amp
                area_list.append(float(np.mean(norm_dn)))
        # p2_height：method3 生理窗内原始信号峰分支的 P2 高度（相对 P1、拍内谷=0 归一化）。
        # 窗 = [P1+80ms, min(P1+0.8拍长, P1+0.6s, 下一拍P1)]（与 extract_fiducials_demo.py
        # 的 max_p2_offset_ratio/max_dt_sec 双约束一致）；窗内取最高局部极大为 P2。
        # P2 被按压压塌 → 高度单调下降：轻压力~0.9 / 正常~0.7 / 按压渐进 0.55→0.40 /
        # 压力过大(重) 0.14-0.25。无峰 → 该拍记 0（P2 完全消失）。
        if len(segA) >= 40:
            beat = nxt_p1 - p
            p2_hi = min(p + int(0.8 * beat), p + int(0.6 * fs), nxt_p1)
            p2_lo = p + int(0.08 * fs)
            if p2_hi > p2_lo:
                # ★归一化基准与标定口径一致：amp_beat = P1 − 拍内最小值（非上一拍 foot）。
                # 压力波拍内谷比上一拍 foot 深，用 foot 会稀释 amp、抬高 p2h。
                vmin = float(segA.min())
                amp_beat = s[p] - vmin
                if amp_beat > 1e-6:
                    w = s[p2_lo:p2_hi]
                    pk_idx = _find_peaks_local(w)
                    if len(pk_idx) > 0:
                        p2i = p2_lo + int(pk_idx[int(np.argmax(w[pk_idx]))])
                        p2_list.append(float(max((s[p2i] - vmin) / amp_beat, 0.0)))
                    else:
                        p2_list.append(0.0)   # 生理窗内无峰 = P2 完全消失
        # 重搏窗内滑动 win_samples 窗的平均下降速率（%amp/sample，正=下降）
        hi_eff = min(hi, len(rel) - win_samples)
        if hi_eff > lo:
            rates = [-(rel[i + win_samples] - rel[i]) / win_samples
                     for i in range(lo, hi_eff)]
            mid_vel_list.append(min(rates))
        # init_steep：前 n_init 样本内最陡的单样本下降（%amp/sample，正）
        seg_steep = dn[p:min(p + n_init, nxt)]
        if len(seg_steep) > 0:
            ipk = float(-seg_steep.min())
            steep_list.append(ipk)
            # slow_frac：本拍"非陡降"样本占比（陡降 = 下降速率 > alpha×峰值陡度）
            # 与 label_overpressure.slow_fraction 同口径；峰值过小（≈平直）的拍不计入
            dn_beat = dn[p:nxt]
            if ipk > 1e-6 and len(dn_beat) > 0:
                n_steep = int(np.sum((-dn_beat) > slow_frac_alpha * ipk))
                slow_list.append(1.0 - n_steep / len(dn_beat))
        # late_rebound：急降段后 [P1+reb_lo, next-3] 内相对前向最小值的最大回升（%amp）
        seg_reb = rel[reb_lo:max(reb_lo + 1, len(rel) - 3)]
        if len(seg_reb) > 1:
            run_min = np.minimum.accumulate(seg_reb)
            reb_list.append(float((seg_reb - run_min).max()))
        # 辅助量 T100（前 100ms 完成总下降的比例）
        i100 = min(p + int(0.1 * fs), nxt - 1)
        total = s[p] - s[p:nxt].min()
        if total > 0:
            t100_list.append((s[p] - s[i100]) / total)
    init_steep = float(np.mean(steep_list)) if steep_list else 0.0
    late_rebound = float(np.mean(reb_list)) if reb_list else 0.0
    mid_min_vel = float(np.mean(mid_vel_list)) if mid_vel_list else 0.0
    t100_mean = float(np.mean(t100_list)) if t100_list else 0.0
    slow_frac = float(np.mean(slow_list)) if slow_list else 0.0
    dn_area = float(np.mean(area_list)) if area_list else 0.0
    p2_height = float(np.median(p2_list)) if p2_list else 1.0
    rapid = bool(mid_vel_list and steep_list and reb_list and slow_list
                 and init_steep >= init_steep_thr
                 and late_rebound <= late_rebound_thr
                 and mid_min_vel >= mid_min_vel_thr
                 and slow_frac >= slow_frac_thr)
    return {"init_steep": init_steep, "late_rebound": late_rebound,
            "mid_min_vel": mid_min_vel, "slow_frac": slow_frac,
            "t100_mean": t100_mean, "dn_area": dn_area, "p2_height": p2_height,
            "rapid_decline": rapid, "n_beats": n_beats}


# ============================================================
# detect_fiducials
# ============================================================
def detect_fiducials(sig: np.ndarray, fs: float = 100.0) -> tuple:
    """在信号上找特征点（R1/T/P1/P2/V1）。**输入须收缩峰朝上**（find_peaks_method3 极性敏感）。

    主路径 find_peaks_method3（差分上升沿法）；Fallback：P1=0 且峰朝上时用宽松阈值重检。

    Returns:
      result: dict with t_R1/t_T/t_P1/t_P2/t_V1 arrays + 'fallback' (bool)
      polarity: "up" 或 "down"（仅作记录，不用于翻信号）
    """
    polarity = "down" if np.abs(np.min(sig)) > np.max(sig) else "up"
    fallback = False
    try:
        result = find_peaks_method3(sig, fs)
    except Exception:
        result = {k: np.array([]) for k in
                  ['t_R1_index', 't_T_index', 't_P1_index', 't_P2_index',
                   't_V1_index', 't_diff_zero', 'diff_signal']}

    # Fallback: 仅在峰朝上的信号上生效（峰朝下差分无正向峰，强制重检无意义）
    if (len(result.get('t_P1_index', np.array([]))) == 0
            and len(sig) >= 10 and polarity == "up"):
        try:
            diff = np.zeros_like(sig)
            diff[1:] = np.diff(sig)
            pos_mask = diff > 0
            thr = 0.25 * np.max(np.abs(diff)) if np.max(np.abs(diff)) > 0 else 0.0
            if thr > 0 and pos_mask.sum() > 2:
                r1_cands = find_peaks(diff, height=thr, distance=int(0.4 * fs))[0]
                r1_cands = r1_cands[pos_mask[r1_cands]]
                if len(r1_cands) > 1:
                    diffs = np.diff(r1_cands)
                    valid = np.concatenate([[True], diffs >= int(0.3 * fs)])
                    r1_cands = r1_cands[valid]
                if len(r1_cands) > 0:
                    t_T_list, t_P1_list = [], []
                    for r1_idx in r1_cands:
                        before = diff[:r1_idx]
                        zc_before = np.where(np.diff(np.sign(before)) != 0)[0]
                        for zc in reversed(zc_before):
                            if zc < len(before) - 1 and diff[zc + 1] > 0 and zc != 0:
                                t_T_list.append(zc + 1)
                                break
                        after = diff[r1_idx:]
                        zc_after = np.where(np.diff(np.sign(after)) != 0)[0]
                        if len(zc_after) > 0:
                            for zc in zc_after:
                                if zc > 0 and diff[r1_idx + zc - 1] > 0:
                                    t_P1_list.append(r1_idx + zc)
                                    break
                        else:
                            t_P1_list.append(r1_idx + np.argmax(after))
                    result['t_R1_index'] = r1_cands
                    result['t_T_index'] = np.array(t_T_list, dtype=int)
                    result['t_P1_index'] = np.array(t_P1_list, dtype=int)
                    fallback = True
        except Exception:
            pass

    result['fallback'] = fallback
    return result, polarity


# ============================================================
# detect_overpressure：薄封装 —— 对齐判据验证口径
# raw_ir_chunk 收缩峰朝下（MAX30102 原始 IR）→ 取负（收缩朝上，满足
# find_peaks_method3 极性要求）→ bandpass(0.5-12Hz, order=2) → 找特征点 → 四闸门。
# ============================================================
def detect_overpressure(raw_ir_chunk, fs=100.0):
    """对一段原始 IR（ADC，收缩朝下）判别压力过大波形。

    Returns: compute_rapid_decline dict（含 rapid_decline / init_steep / late_rebound /
             mid_min_vel / slow_frac / t100_mean / n_beats）。
    """
    sig = bandpass_filter(-np.asarray(raw_ir_chunk, dtype=float), 0.5, 12.0, fs, order=2)
    fid, _ = detect_fiducials(sig, fs)
    return compute_rapid_decline(sig, fid, fs=fs)
