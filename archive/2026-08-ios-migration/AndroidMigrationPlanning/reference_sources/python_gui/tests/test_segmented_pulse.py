import math

import numpy as np

from tools.ppg_monitor.segmented_pulse import analyze_segmented_pulse


def test_segmented_analysis_excludes_contact_change_and_recovers_rate() -> None:
    sample_rate = 200.0
    time_s = np.arange(int(sample_rate * 40.0)) / sample_rate
    pulse = np.sin(2.0 * math.pi * 1.2 * time_s)
    red = 100_000.0 + 800.0 * pulse
    ir = 120_000.0 + 1000.0 * pulse
    dropout = (time_s >= 15.0) & (time_s < 19.0)
    red[dropout] = 8_000.0
    ir[dropout] = 10_000.0
    red[int(30.0 * sample_rate)] += 35_000.0

    result = analyze_segmented_pulse(time_s, red, ir, sample_rate)

    assert len(result.segments) >= 2
    assert result.bpm is not None
    assert abs(result.bpm - 72.0) < 2.0
    peak_times = time_s[result.peak_indices]
    assert not np.any((peak_times >= 13.5) & (peak_times <= 20.5))
    assert not np.any((peak_times >= 28.5) & (peak_times <= 31.5))
    assert result.summary()["accepted_window_count"] >= 2


def test_segmented_analysis_suppresses_close_secondary_peaks() -> None:
    sample_rate = 200.0
    time_s = np.arange(int(sample_rate * 24.0)) / sample_rate
    fundamental = np.sin(2.0 * math.pi * 1.25 * time_s)
    harmonic = 0.75 * np.sin(
        2.0 * math.pi * 2.5 * time_s + 0.3
    )
    red = 95_000.0 + 900.0 * (fundamental + harmonic)
    ir = 120_000.0 + 1100.0 * (fundamental + harmonic)

    result = analyze_segmented_pulse(time_s, red, ir, sample_rate)

    assert result.bpm is not None
    assert abs(result.bpm - 75.0) < 2.0
    intervals = np.diff(time_s[result.peak_indices])
    assert len(intervals) >= 10
    assert float(np.min(intervals)) > 0.55


def test_integrity_break_prevents_cross_gap_filtering() -> None:
    sample_rate = 100.0
    time_s = np.arange(int(sample_rate * 30.0)) / sample_rate
    pulse = np.sin(2.0 * math.pi * 1.1 * time_s)
    red = 90_000.0 + 700.0 * pulse
    ir = 110_000.0 + 800.0 * pulse
    break_index = int(15.0 * sample_rate)

    result = analyze_segmented_pulse(
        time_s,
        red,
        ir,
        sample_rate,
        break_indices=np.array([break_index]),
    )

    assert len(result.segments) == 2
    assert result.segments[0].stop_s < 14.0
    assert result.segments[1].start_s > 16.0


def test_initial_baseline_drift_is_excluded_until_sustained_stability() -> None:
    sample_rate = 200.0
    time_s = np.arange(int(sample_rate * 40.0)) / sample_rate
    pulse = np.sin(2.0 * math.pi * 1.2 * time_s)
    settling = np.maximum(0.0, 1.0 - time_s / 12.0)
    red = 100_000.0 + 9_000.0 * settling + 700.0 * pulse
    ir = 125_000.0 + 16_000.0 * settling + 900.0 * pulse

    result = analyze_segmented_pulse(time_s, red, ir, sample_rate)

    assert result.segments
    assert result.segments[0].start_s >= 10.0
    assert np.all(result.sample_segment_index[time_s < 10.0] < 0)
    assert result.bpm is not None
    assert abs(result.bpm - 72.0) < 2.0


def test_good_ppg_can_bypass_motion_from_unreliable_imu() -> None:
    sample_rate = 200.0
    time_s = np.arange(int(sample_rate * 24.0)) / sample_rate
    pulse = np.sin(2.0 * math.pi * 1.2 * time_s)
    red = 100_000.0 + 900.0 * pulse
    ir = 120_000.0 + 1200.0 * pulse
    false_motion = np.full(len(time_s), 2.0)
    false_gyro = np.full(len(time_s), 1.0)

    trusted = analyze_segmented_pulse(
        time_s,
        red,
        ir,
        sample_rate,
        imu_time_s=time_s,
        linear_accel_magnitude=false_motion,
        gyro_corrected_magnitude=false_gyro,
        imu_quality=np.ones(len(time_s)),
    )
    untrusted = analyze_segmented_pulse(
        time_s,
        red,
        ir,
        sample_rate,
        imu_time_s=time_s,
        linear_accel_magnitude=false_motion,
        gyro_corrected_magnitude=false_gyro,
        imu_quality=np.zeros(len(time_s)),
    )

    assert trusted.bpm is None
    assert trusted.summary()["rejection_counts"]["motion"] > 0
    assert untrusted.bpm is not None
    assert abs(untrusted.bpm - 72.0) < 2.0
    assert untrusted.summary()["accepted_window_count"] > 0
