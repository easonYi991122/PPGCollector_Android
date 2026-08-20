import math

import numpy as np

from tools.ppg_monitor.pulse import estimate_pulse


def test_peak_and_welch_estimate_clean_pulse() -> None:
    sample_rate = 200.0
    time_s = np.arange(int(sample_rate * 12)) / sample_rate
    values = 1200.0 * np.sin(2.0 * math.pi * 1.2 * time_s)

    estimate = estimate_pulse(values, time_s, sample_rate)

    assert estimate.bpm is not None
    assert abs(estimate.bpm - 72.0) < 1.0
    assert estimate.spectral_bpm is not None
    assert abs(estimate.spectral_bpm - 72.0) < 4.0
    assert estimate.confidence > 0.75


def test_spectral_disagreement_rejects_irregular_peaks() -> None:
    sample_rate = 200.0
    time_s = np.arange(int(sample_rate * 8)) / sample_rate
    rng = np.random.default_rng(7)
    values = rng.normal(0.0, 1.0, len(time_s))

    estimate = estimate_pulse(values, time_s, sample_rate)

    assert estimate.bpm is None or estimate.confidence < 0.5
