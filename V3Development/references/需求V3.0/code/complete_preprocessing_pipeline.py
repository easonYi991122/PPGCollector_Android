# -*- coding: utf-8 -*-
"""滤波函数（综合 SQI 交付子集）。

仅保留 SQI 输入信号构造用到的两个滤波函数：
  - moving_average_filter_fixed：滑动平均（窗 2/2/10 三连）
  - bandpass_filter：Butterworth 带通（sosfiltfilt 零相位）

SQI_tm 的输入 = 原始 IR 取负 → moving_average(2) → moving_average(2)
→ moving_average(10) → bandpass(0.5–12 Hz, order=2)。
注意：不做 CPE 峰值增强（非线性变换会虚抬弱/坏信号的 SQI 分数）。
"""

import numpy as np
from scipy.signal import butter, sosfiltfilt

def moving_average_filter_fixed(data, window_size):
    """
    修复版滑动平均滤波（无边缘偏差）

    Parameters:
    -----------
    data : array
        输入信号
    window_size : int
        滑动窗口大小

    Returns:
    --------
    filtered : array
        滤波后的信号
    """
    is_numpy = isinstance(data, np.ndarray)
    if is_numpy:
        data = data.tolist()
    if window_size < 1:
        window_size = 1
    data_len = len(data)
    if data_len <= window_size:
        return np.array(data.copy()) if is_numpy else data.copy()

    filtered = data[:window_size-1].copy()
    for i in range(window_size-1, data_len):
        window_data = data[i - window_size + 1 : i + 1]
        avg_value = sum(window_data) / len(window_data)
        filtered.append(avg_value)

    if len(filtered) < data_len:
        filtered.extend(data[len(filtered):])
    return np.array(filtered) if is_numpy else filtered



def bandpass_filter(data, lowcut, highcut, fs, order=4):
    """
    带通滤波器

    Parameters:
    -----------
    data : array
        输入信号
    lowcut : float
        低截止频率
    highcut : float
        高截止频率
    fs : float
        采样率
    order : int
        滤波器阶数

    Returns:
    --------
    filtered_data : array
        滤波后的信号
    """
    nyquist = 0.5 * fs
    low = lowcut / nyquist
    high = highcut / nyquist

    sos = butter(order, [low, high], btype='band', output='sos')
    filtered_data = sosfiltfilt(sos, data)
    return filtered_data

