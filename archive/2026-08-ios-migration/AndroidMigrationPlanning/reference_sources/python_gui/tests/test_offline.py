import math

from tools.ppg_monitor.legacy_csv import IntegritySummary, LegacyFrame
import numpy as np

from tools.ppg_monitor.offline import _average_waveform, analyze_frames


def test_empty_analysis_keeps_empty_imu_result() -> None:
    result = analyze_frames([], IntegritySummary())

    assert result.imu_analysis.accel_m_s2.shape == (0, 3)
    assert result.imu_analysis.static_window_s is None


def test_robust_peak_detection_ignores_large_transient() -> None:
    sample_rate = 200
    frames = []
    for index in range(sample_rate * 20):
        value = 100_000 + int(1000 * math.sin(2 * math.pi * 1.2 * index / sample_rate))
        if index == 400:
            value += 40_000
        frames.append(
            LegacyFrame(
                t_ms=index * 5,
                seq=index,
                board_id="test",
                imu_ok=True,
                ppg_ok=True,
                ax_mg=0,
                ay_mg=0,
                az_mg=0,
                gx_mdps=0,
                gy_mdps=0,
                gz_mdps=0,
                red=value,
                ir=value,
                flags=0,
            )
        )

    integrity = IntegritySummary(rows=len(frames))
    result = analyze_frames(frames, integrity)

    assert result.summary["hr_candidate_bpm"] is not None
    assert abs(float(result.summary["hr_candidate_bpm"]) - 72.0) < 3.0
    assert result.summary["hr_spectral_candidate_bpm"] is not None
    assert int(result.summary["waveform_cycle_count"]) >= 5
    assert result.summary["imu_processing"]["algorithm_version"] == "imu-sensor-frame-0.1"


def test_average_waveform_rejects_bad_cycle_and_returns_ci() -> None:
    phase = np.linspace(0.0, 2.0 * math.pi, 101)
    good = np.sin(phase)
    values = np.concatenate((good, good, -good, good))
    peaks = np.array([0, 101, 202, 303, 403])

    result_phase, mean, std, ci95, count = _average_waveform(values, peaks)

    assert len(result_phase) == 200
    assert len(mean) == 200
    assert len(std) == 200
    assert len(ci95) == 200
    assert count >= 2
