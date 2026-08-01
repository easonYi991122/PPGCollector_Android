"""Segment-aware offline pulse analysis for non-stationary PPG sessions."""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, replace
import math

import numpy as np
from scipy import signal

from .pulse import PulseEstimate, estimate_pulse


@dataclass(frozen=True, slots=True)
class SignalSegment:
    index: int
    start_index: int
    stop_index: int
    start_s: float
    stop_s: float

    @property
    def duration_s(self) -> float:
        return self.stop_s - self.start_s

    def as_dict(self) -> dict[str, object]:
        return {
            "index": self.index,
            "start_s": self.start_s,
            "stop_s": self.stop_s,
            "duration_s": self.duration_s,
        }


@dataclass(frozen=True, slots=True)
class PulseWindow:
    segment_index: int
    start_index: int
    stop_index: int
    start_s: float
    stop_s: float
    red_estimate: PulseEstimate
    ir_estimate: PulseEstimate
    best_channel: str
    used_channel: str | None
    red_ac_dc_percent: float
    ir_ac_dc_percent: float
    motion_rms_m_s2: float
    gyro_rms_rad_s: float
    imu_quality_score: float
    accepted: bool
    rejection_reason: str | None

    def estimate_for(self, channel: str) -> PulseEstimate:
        return self.red_estimate if channel == "RED" else self.ir_estimate

    def ac_dc_for(self, channel: str) -> float:
        return (
            self.red_ac_dc_percent
            if channel == "RED"
            else self.ir_ac_dc_percent
        )

    def as_dict(self) -> dict[str, object]:
        estimate = (
            self.estimate_for(self.used_channel)
            if self.used_channel is not None
            else None
        )
        return {
            "segment_index": self.segment_index,
            "start_s": self.start_s,
            "stop_s": self.stop_s,
            "best_channel": self.best_channel,
            "used_channel": self.used_channel,
            "peak_bpm": None if estimate is None else estimate.peak_bpm,
            "spectral_bpm": None if estimate is None else estimate.spectral_bpm,
            "confidence": 0.0 if estimate is None else estimate.confidence,
            "snr_db": None
            if estimate is None or not math.isfinite(estimate.snr_db)
            else estimate.snr_db,
            "red_ac_dc_percent": self.red_ac_dc_percent,
            "ir_ac_dc_percent": self.ir_ac_dc_percent,
            "motion_rms_m_s2": self.motion_rms_m_s2,
            "gyro_rms_rad_s": self.gyro_rms_rad_s,
            "imu_quality_score": self.imu_quality_score,
            "accepted": self.accepted,
            "rejection_reason": self.rejection_reason,
        }


@dataclass(frozen=True, slots=True)
class SegmentedPulseAnalysis:
    red_bandpass: np.ndarray
    ir_bandpass: np.ndarray
    sample_segment_index: np.ndarray
    segments: tuple[SignalSegment, ...]
    windows: tuple[PulseWindow, ...]
    channel: str | None
    polarity: str | None
    peak_indices: np.ndarray
    bpm: float | None
    spectral_bpm: float | None
    confidence: float
    snr_db: float
    rr_mad_s: float | None

    def summary(self) -> dict[str, object]:
        reasons = Counter(
            window.rejection_reason
            for window in self.windows
            if not window.accepted and window.rejection_reason is not None
        )
        return {
            "algorithm_version": "segmented-pulse-0.3",
            "segment_count": len(self.segments),
            "segments": [segment.as_dict() for segment in self.segments],
            "stable_sample_ratio": float(
                np.mean(self.sample_segment_index >= 0)
            )
            if len(self.sample_segment_index)
            else 0.0,
            "window_count": len(self.windows),
            "accepted_window_count": sum(
                window.accepted for window in self.windows
            ),
            "rejection_counts": dict(sorted(reasons.items())),
            "selected_channel": self.channel,
            "selected_polarity": self.polarity,
            "peak_count": int(len(self.peak_indices)),
            "hr_candidate_bpm": self.bpm,
            "hr_spectral_candidate_bpm": self.spectral_bpm,
            "confidence": self.confidence,
            "snr_db": self.snr_db
            if math.isfinite(self.snr_db)
            else None,
            "rr_mad_s": self.rr_mad_s,
        }


def analyze_segmented_pulse(
    time_s: np.ndarray,
    red: np.ndarray,
    ir: np.ndarray,
    sample_rate_hz: float,
    *,
    imu_time_s: np.ndarray | None = None,
    linear_accel_magnitude: np.ndarray | None = None,
    gyro_corrected_magnitude: np.ndarray | None = None,
    imu_quality: np.ndarray | None = None,
    valid_mask: np.ndarray | None = None,
    break_indices: np.ndarray | None = None,
    window_s: float = 8.0,
    hop_s: float = 2.0,
) -> SegmentedPulseAnalysis:
    """Analyze stable PPG segments and aggregate consistent local windows."""
    time_s = np.asarray(time_s, dtype=float)
    red = np.asarray(red, dtype=float)
    ir = np.asarray(ir, dtype=float)
    count = min(len(time_s), len(red), len(ir))
    time_s = time_s[:count]
    red = red[:count]
    ir = ir[:count]
    if count == 0 or sample_rate_hz <= 0.0:
        return _empty(count)

    segments = _stable_segments(
        time_s,
        red,
        ir,
        sample_rate_hz,
        minimum_duration_s=window_s,
        valid_mask=valid_mask,
        break_indices=break_indices,
    )
    red_bandpass = np.full(count, np.nan, dtype=float)
    ir_bandpass = np.full(count, np.nan, dtype=float)
    sample_segment_index = np.full(count, -1, dtype=int)
    raw_windows: list[PulseWindow] = []
    window_samples = max(32, int(round(window_s * sample_rate_hz)))
    hop_samples = max(1, int(round(hop_s * sample_rate_hz)))

    for segment in segments:
        segment_slice = slice(segment.start_index, segment.stop_index)
        segment_time = time_s[segment_slice]
        segment_red = _bandpass(red[segment_slice], sample_rate_hz)
        segment_ir = _bandpass(ir[segment_slice], sample_rate_hz)
        red_bandpass[segment_slice] = segment_red
        ir_bandpass[segment_slice] = segment_ir
        sample_segment_index[segment_slice] = segment.index
        starts = list(
            range(
                segment.start_index,
                segment.stop_index - window_samples + 1,
                hop_samples,
            )
        )
        final_start = segment.stop_index - window_samples
        if final_start >= segment.start_index and (
            not starts or final_start - starts[-1] >= hop_samples // 2
        ):
            starts.append(final_start)
        for start in starts:
            stop = start + window_samples
            local = slice(start, stop)
            red_estimate = estimate_pulse(
                red_bandpass[local], time_s[local], sample_rate_hz
            )
            ir_estimate = estimate_pulse(
                ir_bandpass[local], time_s[local], sample_rate_hz
            )
            best_channel, _ = max(
                (("RED", red_estimate), ("IR", ir_estimate)),
                key=lambda item: item[1].confidence,
            )
            raw_windows.append(
                PulseWindow(
                    segment_index=segment.index,
                    start_index=start,
                    stop_index=stop,
                    start_s=float(time_s[start]),
                    stop_s=float(time_s[stop - 1]),
                    red_estimate=red_estimate,
                    ir_estimate=ir_estimate,
                    best_channel=best_channel,
                    used_channel=None,
                    red_ac_dc_percent=_ac_dc_percent(
                        red_bandpass[local], red[local]
                    ),
                    ir_ac_dc_percent=_ac_dc_percent(
                        ir_bandpass[local], ir[local]
                    ),
                    motion_rms_m_s2=_window_rms(
                        time_s[start],
                        time_s[stop - 1],
                        imu_time_s,
                        linear_accel_magnitude,
                    ),
                    gyro_rms_rad_s=_window_rms(
                        time_s[start],
                        time_s[stop - 1],
                        imu_time_s,
                        gyro_corrected_magnitude,
                    ),
                    imu_quality_score=_window_mean(
                        time_s[start],
                        time_s[stop - 1],
                        imu_time_s,
                        imu_quality,
                    ),
                    accepted=False,
                    rejection_reason=None,
                )
            )

    channel = _select_channel(raw_windows)
    if channel is None:
        return SegmentedPulseAnalysis(
            red_bandpass=red_bandpass,
            ir_bandpass=ir_bandpass,
            sample_segment_index=sample_segment_index,
            segments=segments,
            windows=tuple(raw_windows),
            channel=None,
            polarity=None,
            peak_indices=np.array([], dtype=int),
            bpm=None,
            spectral_bpm=None,
            confidence=0.0,
            snr_db=float("-inf"),
            rr_mad_s=None,
        )

    windows = [
        replace(
            window,
            used_channel=channel,
            rejection_reason=_basic_rejection(window, channel),
        )
        for window in raw_windows
    ]
    candidate_indices = [
        index
        for index, window in enumerate(windows)
        if window.rejection_reason is None
    ]
    cluster_indices, center_bpm = _dominant_bpm_cluster(
        windows, candidate_indices, channel
    )
    cluster_set = set(cluster_indices)
    for index in candidate_indices:
        if index not in cluster_set:
            windows[index] = replace(
                windows[index], rejection_reason="bpm_outlier"
            )
    for index in cluster_indices:
        windows[index] = replace(windows[index], accepted=True)

    accepted = [windows[index] for index in cluster_indices]
    if not accepted or center_bpm is None:
        return SegmentedPulseAnalysis(
            red_bandpass=red_bandpass,
            ir_bandpass=ir_bandpass,
            sample_segment_index=sample_segment_index,
            segments=segments,
            windows=tuple(windows),
            channel=channel,
            polarity=None,
            peak_indices=np.array([], dtype=int),
            bpm=None,
            spectral_bpm=None,
            confidence=0.0,
            snr_db=float("-inf"),
            rr_mad_s=None,
        )

    weights = np.array(
        [max(window.estimate_for(channel).confidence, 1e-6) for window in accepted]
    )
    spectral_values = np.array(
        [
            window.estimate_for(channel).spectral_bpm
            for window in accepted
            if window.estimate_for(channel).spectral_bpm is not None
        ],
        dtype=float,
    )
    spectral_weights = np.array(
        [
            max(window.estimate_for(channel).confidence, 1e-6)
            for window in accepted
            if window.estimate_for(channel).spectral_bpm is not None
        ],
        dtype=float,
    )
    spectral_bpm = (
        _weighted_median(spectral_values, spectral_weights)
        if len(spectral_values)
        else None
    )
    snr_values = np.array(
        [window.estimate_for(channel).snr_db for window in accepted],
        dtype=float,
    )
    finite_snr = np.isfinite(snr_values)
    snr_db = (
        _weighted_median(snr_values[finite_snr], weights[finite_snr])
        if np.any(finite_snr)
        else float("-inf")
    )
    rr_values = np.array(
        [
            window.estimate_for(channel).rr_mad_s
            for window in accepted
            if window.estimate_for(channel).rr_mad_s is not None
        ],
        dtype=float,
    )
    rr_weights = np.array(
        [
            max(window.estimate_for(channel).confidence, 1e-6)
            for window in accepted
            if window.estimate_for(channel).rr_mad_s is not None
        ],
        dtype=float,
    )
    rr_mad = (
        _weighted_median(rr_values, rr_weights) if len(rr_values) else None
    )
    polarity = _select_polarity(accepted, channel)
    peak_indices = _collect_peaks(
        accepted,
        polarity,
        red_bandpass if channel == "RED" else ir_bandpass,
        sample_segment_index,
        sample_rate_hz,
        center_bpm,
    )
    bpm_values = np.array(
        [window.estimate_for(channel).peak_bpm for window in accepted],
        dtype=float,
    )
    bpm_mad = _weighted_median(
        np.abs(bpm_values - center_bpm), weights
    )
    mean_confidence = float(np.average(
        [window.estimate_for(channel).confidence for window in accepted],
        weights=weights,
    ))
    evidence = min(1.0, len(accepted) / 6.0)
    stability = 1.0 / (1.0 + (bpm_mad / 6.0) ** 2)
    confidence = min(
        1.0,
        mean_confidence * (0.75 + 0.25 * evidence) * stability,
    )
    bpm = center_bpm if len(accepted) >= 2 and confidence >= 0.25 else None

    return SegmentedPulseAnalysis(
        red_bandpass=red_bandpass,
        ir_bandpass=ir_bandpass,
        sample_segment_index=sample_segment_index,
        segments=segments,
        windows=tuple(windows),
        channel=channel,
        polarity=polarity,
        peak_indices=peak_indices,
        bpm=bpm,
        spectral_bpm=spectral_bpm,
        confidence=confidence,
        snr_db=snr_db,
        rr_mad_s=rr_mad,
    )


def _stable_segments(
    time_s: np.ndarray,
    red: np.ndarray,
    ir: np.ndarray,
    sample_rate_hz: float,
    *,
    minimum_duration_s: float,
    valid_mask: np.ndarray | None,
    break_indices: np.ndarray | None,
    guard_s: float = 1.5,
) -> tuple[SignalSegment, ...]:
    count = len(time_s)
    values = np.column_stack((red, ir))
    invalid = ~np.all(np.isfinite(values), axis=1)
    if valid_mask is not None:
        provided_valid = np.asarray(valid_mask, dtype=bool)
        provided_count = min(count, len(provided_valid))
        invalid[:provided_count] |= ~provided_valid[:provided_count]
        invalid[provided_count:] = True

    # MAX30102 startup settling is not always a single sharp step. Some nRF
    # sessions drift for ten seconds or more before reaching a stable DC
    # baseline, so a fixed guard or adjacent-sample threshold alone admits the
    # distorted beginning. Keep a short location prior, then scan forward for
    # the first full analysis window whose half-second RED/IR medians have both
    # low spread and low trend.
    initial_stable_start = _initial_stable_start(
        time_s,
        values,
        sample_rate_hz,
        horizon_s=minimum_duration_s,
    )
    if initial_stable_start is not None:
        invalid[time_s < initial_stable_start] = True
    transitions: set[int] = set()
    if break_indices is not None:
        transitions.update(
            int(index)
            for index in np.asarray(break_indices, dtype=int)
            if 0 < index < count
        )

    denominator = np.maximum(
        0.5 * (np.abs(values[:-1]) + np.abs(values[1:])),
        1000.0,
    )
    normalized_step = np.abs(np.diff(values, axis=0)) / denominator
    transitions.update((np.flatnonzero(np.any(normalized_step > 0.02, axis=1)) + 1).tolist())

    smooth_samples = max(3, int(round(sample_rate_hz * 0.25)))
    lag = max(1, smooth_samples)
    smooth = np.column_stack(
        [
            signal.savgol_filter(
                values[:, axis],
                window_length=_odd_window(smooth_samples, count),
                polyorder=1,
                mode="interp",
            )
            if count >= 7
            else values[:, axis]
            for axis in range(2)
        ]
    )
    if count > lag:
        smooth_level = np.maximum(
            0.5 * (np.abs(smooth[:-lag]) + np.abs(smooth[lag:])),
            1000.0,
        )
        smooth_change = np.abs(smooth[lag:] - smooth[:-lag]) / smooth_level
        transitions.update(
            (np.flatnonzero(np.any(smooth_change > 0.05, axis=1)) + lag // 2).tolist()
        )

    expected_period = 1.0 / sample_rate_hz
    time_delta = np.diff(time_s)
    transitions.update(
        (
            np.flatnonzero(
                (time_delta <= 0.0) | (time_delta > 1.5 * expected_period)
            )
            + 1
        ).tolist()
    )
    for index in sorted(transitions):
        left = int(np.searchsorted(time_s, time_s[index] - guard_s, side="left"))
        right = int(
            np.searchsorted(time_s, time_s[index] + guard_s, side="right")
        )
        invalid[left:right] = True

    reference = np.maximum(np.nanpercentile(values, 75.0, axis=0), 1.0)
    changes = np.diff(np.r_[False, ~invalid, False].astype(int))
    starts = np.flatnonzero(changes == 1)
    stops = np.flatnonzero(changes == -1)
    segments: list[SignalSegment] = []
    for start, stop in zip(starts, stops):
        if stop - start < 2:
            continue
        duration_s = float(time_s[stop - 1] - time_s[start])
        if duration_s < minimum_duration_s:
            continue
        contact_ratio = np.median(values[start:stop], axis=0) / reference
        if float(np.max(contact_ratio)) < 0.15:
            continue
        segments.append(
            SignalSegment(
                index=len(segments),
                start_index=int(start),
                stop_index=int(stop),
                start_s=float(time_s[start]),
                stop_s=float(time_s[stop - 1]),
            )
        )
    return tuple(segments)


def _initial_stable_start(
    time_s: np.ndarray,
    values: np.ndarray,
    sample_rate_hz: float,
    *,
    horizon_s: float,
    guard_s: float = 2.0,
    bin_s: float = 0.5,
    maximum_level_spread: float = 0.025,
    maximum_level_trend: float = 0.015,
) -> float | None:
    """Find the first sustained, baseline-stable PPG analysis window."""

    count = min(len(time_s), len(values))
    if count < 2 or sample_rate_hz <= 0.0:
        return None
    start_s = float(time_s[0])
    stop_s = float(time_s[count - 1])
    if stop_s <= start_s:
        return None
    guard_start_s = min(stop_s, start_s + guard_s)
    horizon_s = max(2.0, float(horizon_s))
    if stop_s - guard_start_s < horizon_s:
        return guard_start_s

    bin_count = int(np.floor((stop_s - start_s) / bin_s))
    if bin_count <= 0:
        return guard_start_s
    bin_times = start_s + (np.arange(bin_count) + 0.5) * bin_s
    bin_medians = np.full((bin_count, 2), np.nan, dtype=float)
    minimum_bin_samples = max(
        4,
        int(round(sample_rate_hz * bin_s * 0.25)),
    )
    for index in range(bin_count):
        left = int(
            np.searchsorted(
                time_s,
                start_s + index * bin_s,
                side="left",
            )
        )
        right = int(
            np.searchsorted(
                time_s,
                start_s + (index + 1) * bin_s,
                side="left",
            )
        )
        if right - left >= minimum_bin_samples:
            bin_medians[index] = np.nanmedian(
                values[left:right],
                axis=0,
            )

    horizon_bins = max(4, int(np.ceil(horizon_s / bin_s)))
    first_bin = int(
        np.searchsorted(bin_times, guard_start_s, side="left")
    )
    for index in range(
        first_bin,
        bin_count - horizon_bins + 1,
    ):
        local = bin_medians[index : index + horizon_bins]
        if not np.all(np.isfinite(local)):
            continue
        level = np.maximum(
            np.median(np.abs(local), axis=0),
            1000.0,
        )
        level_spread = (
            np.percentile(local, 90.0, axis=0)
            - np.percentile(local, 10.0, axis=0)
        ) / level
        local_time = bin_times[index : index + horizon_bins]
        slopes = np.array(
            [
                np.polyfit(local_time, local[:, axis], 1)[0]
                for axis in range(2)
            ],
            dtype=float,
        )
        level_trend = np.abs(slopes) * horizon_s / level
        if (
            np.all(level_spread <= maximum_level_spread)
            and np.all(level_trend <= maximum_level_trend)
        ):
            return float(local_time[0] - 0.5 * bin_s)
    return None


def _odd_window(requested: int, count: int) -> int:
    window = min(count if count % 2 else count - 1, requested | 1)
    return max(3, window)


def _bandpass(values: np.ndarray, sample_rate_hz: float) -> np.ndarray:
    if len(values) < 32 or sample_rate_hz <= 8.0:
        return np.zeros_like(values, dtype=float)
    high = min(4.0, sample_rate_hz * 0.45)
    sos = signal.butter(
        3,
        [0.6, high],
        btype="bandpass",
        fs=sample_rate_hz,
        output="sos",
    )
    pad_length = min(len(values) - 1, max(12, 3 * len(sos)))
    return signal.sosfiltfilt(sos, values, padlen=pad_length)


def _ac_dc_percent(filtered: np.ndarray, raw: np.ndarray) -> float:
    if not len(filtered):
        return 0.0
    edge = min(len(filtered) // 10, max(0, len(filtered) // 2 - 1))
    selected = slice(edge, len(filtered) - edge or None)
    return float(
        100.0
        * np.sqrt(np.mean(filtered[selected] ** 2))
        / max(abs(float(np.mean(raw[selected]))), 1.0)
    )


def _window_rms(
    start_s: float,
    stop_s: float,
    imu_time_s: np.ndarray | None,
    values: np.ndarray | None,
) -> float:
    if imu_time_s is None or values is None:
        return 0.0
    imu_time = np.asarray(imu_time_s, dtype=float)
    values = np.asarray(values, dtype=float)
    count = min(len(imu_time), len(values))
    selected = (
        (imu_time[:count] >= start_s)
        & (imu_time[:count] <= stop_s)
        & np.isfinite(values[:count])
    )
    return (
        float(np.sqrt(np.mean(values[:count][selected] ** 2)))
        if np.any(selected)
        else 0.0
    )


def _window_mean(
    start_s: float,
    stop_s: float,
    imu_time_s: np.ndarray | None,
    values: np.ndarray | None,
) -> float:
    if imu_time_s is None or values is None:
        return 0.0
    imu_time = np.asarray(imu_time_s, dtype=float)
    quality = np.asarray(values, dtype=float)
    count = min(len(imu_time), len(quality))
    selected = (
        (imu_time[:count] >= start_s)
        & (imu_time[:count] <= stop_s)
        & np.isfinite(quality[:count])
    )
    return (
        float(np.mean(np.clip(quality[:count][selected], 0.0, 1.0)))
        if np.any(selected)
        else 0.0
    )


def _select_channel(windows: list[PulseWindow]) -> str | None:
    scores: dict[str, float] = {}
    for channel in ("RED", "IR"):
        score = 0.0
        for window in windows:
            estimate = window.estimate_for(channel)
            if estimate.peak_bpm is None:
                continue
            if (
                estimate.spectral_bpm is not None
                and abs(estimate.peak_bpm - estimate.spectral_bpm) > 15.0
            ):
                continue
            score += estimate.confidence
        scores[channel] = score
    channel, score = max(scores.items(), key=lambda item: item[1], default=(None, 0.0))
    return channel if score > 0.0 else None


def _basic_rejection(window: PulseWindow, channel: str) -> str | None:
    estimate = window.estimate_for(channel)
    if estimate.peak_bpm is None or estimate.polarity is None:
        return "no_regular_peaks"
    if estimate.spectral_bpm is None:
        return "no_spectral_peak"
    if abs(estimate.peak_bpm - estimate.spectral_bpm) > 15.0:
        return "peak_spectral_disagreement"
    if estimate.confidence < 0.20:
        return "low_confidence"
    if window.ac_dc_for(channel) < 0.01:
        return "low_perfusion"
    motion_detected = (
        window.motion_rms_m_s2 > 1.0
        or window.gyro_rms_rad_s > 0.5
    )
    if motion_detected and window.imu_quality_score < 0.65:
        strong_ppg = (
            estimate.confidence >= 0.45
            and abs(estimate.peak_bpm - estimate.spectral_bpm) <= 8.0
            and window.ac_dc_for(channel) >= 0.02
            and (
                estimate.rr_mad_s is None
                or estimate.rr_mad_s <= 0.12
            )
        )
        return None if strong_ppg else "motion_untrusted_imu"
    if motion_detected:
        return "motion"
    return None


def _dominant_bpm_cluster(
    windows: list[PulseWindow],
    candidate_indices: list[int],
    channel: str,
) -> tuple[list[int], float | None]:
    if not candidate_indices:
        return [], None
    values = np.array(
        [windows[index].estimate_for(channel).peak_bpm for index in candidate_indices],
        dtype=float,
    )
    weights = np.array(
        [
            max(windows[index].estimate_for(channel).confidence, 1e-6)
            for index in candidate_indices
        ],
        dtype=float,
    )
    scores = np.array(
        [
            np.sum(weights[np.abs(values - center) <= 10.0])
            for center in values
        ]
    )
    seed = values[int(np.argmax(scores))]
    initial = np.abs(values - seed) <= 10.0
    center = _weighted_median(values[initial], weights[initial])
    mad = _weighted_median(np.abs(values[initial] - center), weights[initial])
    tolerance = min(12.0, max(8.0, 3.0 * mad))
    selected = np.abs(values - center) <= tolerance
    selected_indices = [
        index for index, keep in zip(candidate_indices, selected) if keep
    ]
    if not selected_indices:
        return [], None
    selected_values = values[selected]
    selected_weights = weights[selected]
    return (
        selected_indices,
        _weighted_median(selected_values, selected_weights),
    )


def _select_polarity(windows: list[PulseWindow], channel: str) -> str | None:
    scores: Counter[str] = Counter()
    for window in windows:
        estimate = window.estimate_for(channel)
        if estimate.polarity is not None:
            scores[estimate.polarity] += estimate.confidence
    return scores.most_common(1)[0][0] if scores else None


def _collect_peaks(
    windows: list[PulseWindow],
    polarity: str | None,
    filtered: np.ndarray,
    segment_index: np.ndarray,
    sample_rate_hz: float,
    bpm: float,
) -> np.ndarray:
    if polarity is None:
        return np.array([], dtype=int)
    accepted_mask = np.zeros(len(filtered), dtype=bool)
    for window in windows:
        accepted_mask[window.start_index : window.stop_index] = True

    minimum_distance = max(
        1, int(round(sample_rate_hz * 0.65 * 60.0 / bpm))
    )
    sign = 1.0 if polarity == "positive" else -1.0
    peaks: list[int] = []
    for current_segment in np.unique(segment_index[segment_index >= 0]):
        indices = np.flatnonzero(segment_index == current_segment)
        if not len(indices):
            continue
        start = int(indices[0])
        stop = int(indices[-1]) + 1
        values = filtered[start:stop]
        centered = values - np.nanmedian(values)
        robust_scale = float(
            np.nanmedian(np.abs(centered)) * 1.4826
        )
        if not np.isfinite(robust_scale) or robust_scale <= 0.0:
            continue
        local_peaks, _ = signal.find_peaks(
            sign * values,
            distance=minimum_distance,
            prominence=max(1e-9, 0.5 * robust_scale),
        )
        global_peaks = local_peaks + start
        peaks.extend(
            int(index)
            for index in global_peaks
            if accepted_mask[index]
        )
    return np.asarray(sorted(peaks), dtype=int)


def _weighted_median(values: np.ndarray, weights: np.ndarray) -> float:
    order = np.argsort(values)
    ordered_values = np.asarray(values, dtype=float)[order]
    ordered_weights = np.asarray(weights, dtype=float)[order]
    cumulative = np.cumsum(ordered_weights)
    threshold = 0.5 * float(np.sum(ordered_weights))
    return float(ordered_values[np.searchsorted(cumulative, threshold, side="left")])


def _empty(count: int) -> SegmentedPulseAnalysis:
    empty = np.array([], dtype=float)
    return SegmentedPulseAnalysis(
        red_bandpass=np.full(count, np.nan, dtype=float),
        ir_bandpass=np.full(count, np.nan, dtype=float),
        sample_segment_index=np.full(count, -1, dtype=int),
        segments=(),
        windows=(),
        channel=None,
        polarity=None,
        peak_indices=np.array([], dtype=int),
        bpm=None,
        spectral_bpm=None,
        confidence=0.0,
        snr_db=float("-inf"),
        rr_mad_s=None,
    )
