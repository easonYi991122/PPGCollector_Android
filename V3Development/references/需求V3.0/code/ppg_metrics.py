# -*- coding: utf-8 -*-
"""信号度量基础函数（综合 SQI 交付子集）。

仅保留综合 SQI 链路用到的自相关 SQI（autocorr_sqi，即 SQI_corr）及其依赖；
其余离线分析函数（SNR/相关/偏度/峰度/批处理）不在本交付包范围内。
"""

import numpy as np
from scipy.signal import find_peaks

DEFAULT_SAMPLE_RATE = 100.0   # Hz
HR_MIN_BPM = 40.0             # 心率搜索下限
HR_MAX_BPM = 150.0            # 心率搜索上限

def autocorr_sqi(signal, fs=DEFAULT_SAMPLE_RATE, hr_min=HR_MIN_BPM, hr_max=HR_MAX_BPM):
    """
    基于自相关计算信号质量指数(SQI)

    通过检测信号在心率范围内的周期性峰值来评估信号质量

    参数:
        signal: PPG信号（一维数组）
        fs: 采样率
        hr_min: 最小心率 (bpm)
        hr_max: 最大心率 (bpm)

    返回:
        tuple: (sqi, peak_idx)
            - sqi: 信号质量指数 [0, 1]
            - peak_idx: 峰值位置（采样点）
    """
    signal = signal - np.mean(signal)

    # 计算自相关
    autocorr = np.correlate(signal, signal, mode='full')
    autocorr = autocorr[len(autocorr)//2:]  # 只取正延迟部分

    # 归一化
    if len(autocorr) > 0 and autocorr[0] != 0:
        autocorr = autocorr / autocorr[0]
    else:
        return 0, 0

    # 计算搜索范围
    min_delay = 60.0 / hr_max  # 最大心率对应的最小延迟
    max_delay = 60.0 / hr_min  # 最小心率对应的最大延迟

    min_idx = int(min_delay * fs)
    max_idx = int(max_delay * fs)

    if len(autocorr) <= max_idx:
        return 0, 0

    # 在心率范围内找峰值
    peaks, _ = find_peaks(autocorr[min_idx:max_idx], height=0.1)

    if len(peaks) > 0:
        peak_idx = peaks[np.argmax(autocorr[min_idx + peaks])] + min_idx
        return autocorr[peak_idx], peak_idx

    return 0, 0

