"""Shared peak/RR and spectral pulse-candidate estimation."""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from scipy import signal


@dataclass(frozen=True, slots=True)
class PulseEstimate:
    bpm: float | None
    peak_bpm: float | None
    spectral_bpm: float | None
    confidence: float
    snr_db: float
    polarity: str | None
    peak_indices: np.ndarray
    rr_mad_s: float | None


def estimate_pulse(
    values: np.ndarray,
    time_s: np.ndarray,
    sample_rate_hz: float,
    *,
    min_bpm: float = 35.0,
    max_bpm: float = 200.0,
) -> PulseEstimate:
    """Estimate a conservative pulse candidate using peaks plus Welch PSD."""
    values = np.asarray(values, dtype=float)
    time_s = np.asarray(time_s, dtype=float)
    if len(values) != len(time_s) or len(values) < max(32, int(sample_rate_hz * 4)):
        return _empty()

    edge = min(int(sample_rate_hz), max(0, len(values) // 10))
    stop = len(values) - edge
    if stop - edge < 16:
        return _empty()
    work = values[edge:stop]
    work_time = time_s[edge:stop]
    work = work - np.median(work)
    robust_scale = float(np.median(np.abs(work)) * 1.4826)
    if not np.isfinite(robust_scale) or robust_scale < 1.0:
        return _empty()

    spectral_bpm, snr_db, spectral_concentration = _spectral_candidate(
        work, sample_rate_hz, min_bpm, max_bpm
    )
    min_interval_s = 60.0 / max_bpm
    max_interval_s = 60.0 / min_bpm
    guided_interval_s = min_interval_s
    if spectral_bpm is not None:
        guided_interval_s = max(
            min_interval_s,
            min(0.5, 0.55 * 60.0 / spectral_bpm),
        )
    candidates: list[tuple[float, str, np.ndarray, float, float]] = []

    for polarity, sign in (("positive", 1.0), ("negative", -1.0)):
        peaks, _ = signal.find_peaks(
            sign * work,
            distance=max(1, int(sample_rate_hz * guided_interval_s)),
            prominence=robust_scale,
        )
        if len(peaks) < 3:
            continue
        intervals = np.diff(work_time[peaks])
        in_range = (intervals >= min_interval_s) & (intervals <= max_interval_s)
        if spectral_bpm is not None:
            spectral_rr = 60.0 / spectral_bpm
            spectral_tolerance = max(0.12, 0.22 * spectral_rr)
            spectral_match = (
                np.abs(intervals - spectral_rr) <= spectral_tolerance
            )
            if np.count_nonzero(in_range & spectral_match) >= 2:
                in_range &= spectral_match
        valid_intervals = intervals[in_range]
        if len(valid_intervals) < 2:
            continue
        median_rr = float(np.median(valid_intervals))
        mad = float(np.median(np.abs(valid_intervals - median_rr)))
        mad_floor = max(1.0 / sample_rate_hz, mad)
        valid_mask = in_range & (
            np.abs(intervals - median_rr) <= 3.0 * mad_floor
        )
        valid_peaks, cleaned = _longest_valid_run(peaks, intervals, valid_mask)
        if len(cleaned) < 2:
            continue
        median_rr = float(np.median(cleaned))
        mad = float(np.median(np.abs(cleaned - median_rr)))
        peak_bpm = 60.0 / median_rr
        regularity = 1.0 / (1.0 + 8.0 * mad / max(median_rr, 1e-12))
        coverage = min(1.0, len(cleaned) / 6.0)
        agreement = (
            max(0.0, 1.0 - abs(peak_bpm - spectral_bpm) / 15.0)
            if spectral_bpm is not None
            else 0.0
        )
        score = coverage * regularity * agreement * (
            0.5 + 0.5 * spectral_concentration
        )
        candidates.append((score, polarity, valid_peaks + edge, peak_bpm, mad))

    if not candidates:
        return PulseEstimate(
            bpm=None,
            peak_bpm=None,
            spectral_bpm=spectral_bpm,
            confidence=0.0,
            snr_db=snr_db,
            polarity=None,
            peak_indices=np.array([], dtype=int),
            rr_mad_s=None,
        )

    confidence, polarity, peaks, peak_bpm, rr_mad = max(
        candidates, key=lambda item: item[0]
    )
    bpm = peak_bpm if confidence >= 0.35 else None
    return PulseEstimate(
        bpm=bpm,
        peak_bpm=peak_bpm,
        spectral_bpm=spectral_bpm,
        confidence=float(min(1.0, confidence)),
        snr_db=snr_db,
        polarity=polarity,
        peak_indices=peaks,
        rr_mad_s=rr_mad,
    )


def _spectral_candidate(
    values: np.ndarray,
    sample_rate_hz: float,
    min_bpm: float,
    max_bpm: float,
) -> tuple[float | None, float, float]:
    frequencies, power = signal.welch(
        values,
        fs=sample_rate_hz,
        nperseg=min(len(values), max(64, int(sample_rate_hz * 8))),
        detrend="linear",
    )
    cardiac = (frequencies >= min_bpm / 60.0) & (
        frequencies <= max_bpm / 60.0
    )
    if not np.any(cardiac) or float(np.sum(power[cardiac])) <= 0.0:
        return None, float("-inf"), 0.0
    cardiac_indices = np.flatnonzero(cardiac)
    peak_index = int(cardiac_indices[np.argmax(power[cardiac])])
    peak_frequency = float(frequencies[peak_index])
    near_peak = cardiac & (np.abs(frequencies - peak_frequency) <= 0.15)
    signal_power = float(np.sum(power[near_peak]))
    noise_power = max(float(np.sum(power[cardiac])) - signal_power, 1e-12)
    concentration = signal_power / max(signal_power + noise_power, 1e-12)
    snr_db = float(10.0 * np.log10(max(signal_power, 1e-12) / noise_power))
    return peak_frequency * 60.0, snr_db, concentration


def _longest_valid_run(
    peaks: np.ndarray,
    intervals: np.ndarray,
    valid_mask: np.ndarray,
) -> tuple[np.ndarray, np.ndarray]:
    best_start = 0
    best_length = 0
    current_start = 0
    current_length = 0
    for index, valid in enumerate(valid_mask):
        if valid:
            if current_length == 0:
                current_start = index
            current_length += 1
            if current_length > best_length:
                best_start = current_start
                best_length = current_length
        else:
            current_length = 0
    if best_length == 0:
        return np.array([], dtype=int), np.array([], dtype=float)
    return (
        peaks[best_start : best_start + best_length + 1],
        intervals[best_start : best_start + best_length],
    )


def _empty() -> PulseEstimate:
    return PulseEstimate(
        bpm=None,
        peak_bpm=None,
        spectral_bpm=None,
        confidence=0.0,
        snr_db=float("-inf"),
        polarity=None,
        peak_indices=np.array([], dtype=int),
        rr_mad_s=None,
    )
