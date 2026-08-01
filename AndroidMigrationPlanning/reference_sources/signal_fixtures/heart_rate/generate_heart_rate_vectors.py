"""Generate deterministic heart-rate traces for Swift numerical parity."""

from __future__ import annotations

import hashlib
import json
import math
import platform
import sys
from pathlib import Path
from typing import Any

import numpy as np
import scipy
from scipy import signal


HERE = Path(__file__).resolve().parent
PLANNING_ROOT = HERE.parents[1]
REPOSITORY_ROOT = PLANNING_ROOT.parent
PYTHON_REFERENCE_ROOT = PLANNING_ROOT / "references" / "python_gui"
PULSE_SOURCE = PYTHON_REFERENCE_ROOT / "ppg_monitor" / "pulse.py"
OUTPUT = HERE / "heart_rate_vectors.json"
SWIFT_OUTPUT = (
    REPOSITORY_ROOT
    / "PPGCollectorTests"
    / "Resources"
    / "SignalProcessing"
    / "heart_rate_vectors.json"
)

SCHEMA = "cup.heart-rate.parity.v1"
ALGORITHM_VERSION = "ppg-ios-hr-0.1"
SAMPLE_RATE_HZ = 100.0
MIN_BPM = 35.0
MAX_BPM = 200.0
MINIMUM_WINDOW_SECONDS = 4.0
MAXIMUM_WINDOW_SECONDS = 8.0
MINIMUM_ROBUST_SCALE = 1.0
CONFIDENCE_THRESHOLD = 0.35


def _load_reference():
    sys.path.insert(0, str(PYTHON_REFERENCE_ROOT))
    try:
        from ppg_monitor.pulse import estimate_pulse
    finally:
        sys.path.pop(0)
    return estimate_pulse


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _json_number(value: float | np.floating[Any] | None) -> float | None:
    if value is None:
        return None
    converted = float(value)
    return converted if math.isfinite(converted) else None


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


def _spectral_trace(work: np.ndarray) -> dict[str, Any]:
    nperseg = min(
        len(work),
        max(64, int(SAMPLE_RATE_HZ * MAXIMUM_WINDOW_SECONDS)),
    )
    frequencies, power = signal.welch(
        work,
        fs=SAMPLE_RATE_HZ,
        nperseg=nperseg,
        detrend="linear",
    )
    cardiac = (frequencies >= MIN_BPM / 60.0) & (
        frequencies <= MAX_BPM / 60.0
    )
    cardiac_indices = np.flatnonzero(cardiac)
    total_cardiac_power = float(np.sum(power[cardiac]))
    if not np.any(cardiac) or total_cardiac_power <= 0.0:
        return {
            "nperseg": nperseg,
            "frequencies_hz": frequencies.tolist(),
            "power": power.tolist(),
            "cardiac_indices": cardiac_indices.tolist(),
            "peak_index": None,
            "near_peak_indices": [],
            "spectral_bpm": None,
            "snr_db": None,
            "concentration": 0.0,
        }

    peak_index = int(cardiac_indices[np.argmax(power[cardiac])])
    peak_frequency = float(frequencies[peak_index])
    near_peak = cardiac & (np.abs(frequencies - peak_frequency) <= 0.15)
    signal_power = float(np.sum(power[near_peak]))
    noise_power = max(total_cardiac_power - signal_power, 1e-12)
    concentration = signal_power / max(signal_power + noise_power, 1e-12)
    snr_db = 10.0 * np.log10(max(signal_power, 1e-12) / noise_power)
    return {
        "nperseg": nperseg,
        "frequencies_hz": frequencies.tolist(),
        "power": power.tolist(),
        "cardiac_indices": cardiac_indices.tolist(),
        "peak_index": peak_index,
        "near_peak_indices": np.flatnonzero(near_peak).tolist(),
        "spectral_bpm": peak_frequency * 60.0,
        "snr_db": float(snr_db),
        "concentration": float(concentration),
    }


def _candidate_trace(
    work: np.ndarray,
    work_time: np.ndarray,
    *,
    polarity: str,
    sign: float,
    edge: int,
    robust_scale: float,
    spectral: dict[str, Any],
) -> dict[str, Any]:
    min_interval_s = 60.0 / MAX_BPM
    max_interval_s = 60.0 / MIN_BPM
    guided_interval_s = min_interval_s
    spectral_bpm = spectral["spectral_bpm"]
    if spectral_bpm is not None:
        guided_interval_s = max(
            min_interval_s,
            min(0.5, 0.55 * 60.0 / spectral_bpm),
        )
    distance_samples = max(1, int(SAMPLE_RATE_HZ * guided_interval_s))
    peaks, properties = signal.find_peaks(
        sign * work,
        distance=distance_samples,
        prominence=robust_scale,
    )
    prominences = np.asarray(
        properties.get("prominences", np.array([], dtype=float)),
        dtype=float,
    )
    intervals = np.diff(work_time[peaks])
    in_range = (intervals >= min_interval_s) & (
        intervals <= max_interval_s
    )
    spectral_match = np.ones(intervals.size, dtype=bool)
    spectral_filter_applied = False
    if spectral_bpm is not None:
        spectral_rr = 60.0 / spectral_bpm
        spectral_tolerance = max(0.12, 0.22 * spectral_rr)
        spectral_match = (
            np.abs(intervals - spectral_rr) <= spectral_tolerance
        )
        if np.count_nonzero(in_range & spectral_match) >= 2:
            in_range &= spectral_match
            spectral_filter_applied = True

    trace: dict[str, Any] = {
        "polarity": polarity,
        "distance_samples": distance_samples,
        "detected_local_peak_indices": peaks.tolist(),
        "detected_global_peak_indices": (peaks + edge).tolist(),
        "prominences": prominences.tolist(),
        "intervals_s": intervals.tolist(),
        "in_range_mask": in_range.tolist(),
        "spectral_match_mask": spectral_match.tolist(),
        "spectral_filter_applied": spectral_filter_applied,
        "valid_mask": [False] * intervals.size,
        "longest_run_local_peak_indices": [],
        "longest_run_global_peak_indices": [],
        "cleaned_intervals_s": [],
        "median_rr_s": None,
        "rr_mad_s": None,
        "peak_bpm": None,
        "score": None,
        "rejection_reason": None,
    }
    if len(peaks) < 3:
        trace["rejection_reason"] = "insufficientPeaks"
        return trace

    valid_intervals = intervals[in_range]
    if len(valid_intervals) < 2:
        trace["rejection_reason"] = "insufficientIntervals"
        return trace

    median_rr = float(np.median(valid_intervals))
    mad = float(np.median(np.abs(valid_intervals - median_rr)))
    mad_floor = max(1.0 / SAMPLE_RATE_HZ, mad)
    valid_mask = in_range & (
        np.abs(intervals - median_rr) <= 3.0 * mad_floor
    )
    valid_peaks, cleaned = _longest_valid_run(
        peaks,
        intervals,
        valid_mask,
    )
    trace["valid_mask"] = valid_mask.tolist()
    trace["longest_run_local_peak_indices"] = valid_peaks.tolist()
    trace["longest_run_global_peak_indices"] = (
        valid_peaks + edge
    ).tolist()
    trace["cleaned_intervals_s"] = cleaned.tolist()
    if len(cleaned) < 2:
        trace["rejection_reason"] = "insufficientLongestRun"
        return trace

    median_rr = float(np.median(cleaned))
    mad = float(np.median(np.abs(cleaned - median_rr)))
    peak_bpm = 60.0 / median_rr
    regularity = 1.0 / (
        1.0 + 8.0 * mad / max(median_rr, 1e-12)
    )
    coverage = min(1.0, len(cleaned) / 6.0)
    agreement = (
        max(0.0, 1.0 - abs(peak_bpm - spectral_bpm) / 15.0)
        if spectral_bpm is not None
        else 0.0
    )
    score = coverage * regularity * agreement * (
        0.5 + 0.5 * spectral["concentration"]
    )
    trace.update(
        {
            "median_rr_s": median_rr,
            "rr_mad_s": mad,
            "peak_bpm": peak_bpm,
            "score": score,
        }
    )
    return trace


def _trace_estimate(
    values: np.ndarray,
    time_s: np.ndarray,
) -> dict[str, Any]:
    if len(values) != len(time_s):
        raise AssertionError("fixture values/time lengths must match")
    minimum_samples = max(
        32,
        int(SAMPLE_RATE_HZ * MINIMUM_WINDOW_SECONDS),
    )
    if len(values) < minimum_samples:
        return {
            "unavailable_reason": "insufficientSamples",
            "edge_trim_count": 0,
            "work_median": None,
            "robust_scale": None,
            "work_centered": [],
            "spectral": None,
            "candidates": [],
        }

    edge = min(int(SAMPLE_RATE_HZ), max(0, len(values) // 10))
    stop = len(values) - edge
    if stop - edge < 16:
        return {
            "unavailable_reason": "insufficientWorkWindow",
            "edge_trim_count": edge,
            "work_median": None,
            "robust_scale": None,
            "work_centered": [],
            "spectral": None,
            "candidates": [],
        }
    work = values[edge:stop]
    work_time = time_s[edge:stop]
    work_median = float(np.median(work))
    work = work - work_median
    robust_scale = float(np.median(np.abs(work)) * 1.4826)
    base = {
        "edge_trim_count": edge,
        "work_median": work_median,
        "robust_scale": robust_scale,
        "work_centered": work.tolist(),
    }
    if not np.isfinite(robust_scale) or robust_scale < MINIMUM_ROBUST_SCALE:
        return {
            **base,
            "unavailable_reason": "insufficientAmplitude",
            "spectral": None,
            "candidates": [],
        }

    spectral = _spectral_trace(work)
    candidates = [
        _candidate_trace(
            work,
            work_time,
            polarity=polarity,
            sign=sign,
            edge=edge,
            robust_scale=robust_scale,
            spectral=spectral,
        )
        for polarity, sign in (("positive", 1.0), ("negative", -1.0))
    ]
    accepted = [
        candidate
        for candidate in candidates
        if candidate["score"] is not None
    ]
    reason = None
    if not accepted:
        reason = "noPeakCandidate"
    elif max(accepted, key=lambda item: item["score"])["score"] < (
        CONFIDENCE_THRESHOLD
    ):
        reason = "lowConfidence"
    return {
        **base,
        "unavailable_reason": reason,
        "spectral": spectral,
        "candidates": candidates,
    }


def _reference_result(
    values: np.ndarray,
    time_s: np.ndarray,
) -> dict[str, Any]:
    estimate = _load_reference()(
        values,
        time_s,
        SAMPLE_RATE_HZ,
        min_bpm=MIN_BPM,
        max_bpm=MAX_BPM,
    )
    return {
        "bpm": _json_number(estimate.bpm),
        "peak_bpm": _json_number(estimate.peak_bpm),
        "spectral_bpm": _json_number(estimate.spectral_bpm),
        "confidence": float(estimate.confidence),
        "snr_db": _json_number(estimate.snr_db),
        "polarity": estimate.polarity,
        "peak_indices": estimate.peak_indices.tolist(),
        "rr_mad_s": _json_number(estimate.rr_mad_s),
    }


def _pulse_train(
    bpm: float,
    *,
    polarity: float,
    seconds: float,
) -> tuple[np.ndarray, np.ndarray]:
    time_s = np.arange(int(SAMPLE_RATE_HZ * seconds)) / SAMPLE_RATE_HZ
    values = (
        60.0 * np.sin(2.0 * np.pi * 0.18 * time_s)
        + 20.0 * np.sin(2.0 * np.pi * 0.07 * time_s)
    )
    for center_s in np.arange(0.35, seconds, 60.0 / bpm):
        values += polarity * 3_000.0 * np.exp(
            -0.5 * ((time_s - center_s) / 0.045) ** 2
        )
        values += polarity * 650.0 * np.exp(
            -0.5 * ((time_s - center_s - 0.18) / 0.065) ** 2
        )
    return time_s, values


def _case(
    name: str,
    time_s: np.ndarray,
    values: np.ndarray,
) -> dict[str, Any]:
    rounded_time = np.round(np.asarray(time_s, dtype=float), 10)
    rounded_values = np.round(np.asarray(values, dtype=float), 10)
    result = _reference_result(rounded_values, rounded_time)
    trace = _trace_estimate(rounded_values, rounded_time)

    accepted = [
        candidate
        for candidate in trace["candidates"]
        if candidate["score"] is not None
    ]
    if accepted:
        selected = max(accepted, key=lambda item: item["score"])
        expected_bpm = (
            selected["peak_bpm"]
            if selected["score"] >= CONFIDENCE_THRESHOLD
            else None
        )
        if result["bpm"] != expected_bpm:
            raise AssertionError(f"{name}: final bpm mirror mismatch")
        for key in ("peak_bpm", "rr_mad_s"):
            if result[key] != selected[key]:
                raise AssertionError(f"{name}: final {key} mirror mismatch")
        if result["polarity"] != selected["polarity"]:
            raise AssertionError(f"{name}: final polarity mirror mismatch")
        if result["peak_indices"] != (
            selected["longest_run_global_peak_indices"]
        ):
            raise AssertionError(f"{name}: final peak mirror mismatch")
        if not math.isclose(
            result["confidence"],
            min(1.0, selected["score"]),
            rel_tol=0.0,
            abs_tol=1e-15,
        ):
            raise AssertionError(f"{name}: confidence mirror mismatch")
    else:
        if result["peak_bpm"] is not None or result["peak_indices"]:
            raise AssertionError(f"{name}: empty result mirror mismatch")

    spectral = trace["spectral"]
    expected_spectral = (
        None if spectral is None else spectral["spectral_bpm"]
    )
    if result["spectral_bpm"] != expected_spectral:
        raise AssertionError(f"{name}: spectral bpm mirror mismatch")
    result["unavailable_reason"] = trace["unavailable_reason"]
    return {
        "name": name,
        "sample_rate_hz": SAMPLE_RATE_HZ,
        "time_s": rounded_time.tolist(),
        "values": rounded_values.tolist(),
        "expected": result,
        "trace": trace,
    }


def build_payload() -> dict[str, Any]:
    positive_time, positive = _pulse_train(
        72.0,
        polarity=1.0,
        seconds=8.0,
    )
    negative_time, negative = _pulse_train(
        72.0,
        polarity=-1.0,
        seconds=8.0,
    )
    boundary_time, boundary = _pulse_train(
        150.0,
        polarity=1.0,
        seconds=4.0,
    )
    noise_time = np.arange(800, dtype=float) / SAMPLE_RATE_HZ
    noise = np.random.default_rng(7).normal(0.0, 3.0, noise_time.size)
    constant_time = np.arange(400, dtype=float) / SAMPLE_RATE_HZ
    short_time = np.arange(399, dtype=float) / SAMPLE_RATE_HZ
    return {
        "schema": SCHEMA,
        "algorithm_version": ALGORITHM_VERSION,
        "source": {
            "path": "../../references/python_gui/ppg_monitor/pulse.py",
            "sha256": _sha256(PULSE_SOURCE),
            "function": "estimate_pulse",
        },
        "environment": {
            "python": platform.python_version(),
            "numpy": np.__version__,
            "scipy": scipy.__version__,
        },
        "config": {
            "sample_rate_hz": SAMPLE_RATE_HZ,
            "min_bpm": MIN_BPM,
            "max_bpm": MAX_BPM,
            "minimum_window_seconds": MINIMUM_WINDOW_SECONDS,
            "maximum_window_seconds": MAXIMUM_WINDOW_SECONDS,
            "minimum_robust_scale": MINIMUM_ROBUST_SCALE,
            "confidence_threshold": CONFIDENCE_THRESHOLD,
            "welch_window": "hann_periodic",
            "welch_detrend": "linear",
            "welch_scaling": "density",
            "welch_average": "mean",
            "welch_near_peak_hz": 0.15,
        },
        "notes": [
            "Synthetic preprocessed PPG; not physiological validation data.",
            "V1 channel policy is fixed IR; this fixture does not choose RED.",
            "At 100 Hz each allowed 4--8 second window has one Welch segment.",
            "A candidate below 0.35 keeps diagnostics but bpm remains invalid.",
        ],
        "cases": [
            _case(
                "positive_72bpm_8s",
                positive_time,
                positive,
            ),
            _case(
                "negative_72bpm_8s",
                negative_time,
                negative,
            ),
            _case(
                "positive_150bpm_4s",
                boundary_time,
                boundary,
            ),
            _case(
                "deterministic_noise_8s",
                noise_time,
                noise,
            ),
            _case(
                "constant_4s",
                constant_time,
                np.full(constant_time.size, 500_000.0),
            ),
            _case(
                "too_short_3_99s",
                short_time,
                np.zeros(short_time.size, dtype=float),
            ),
        ],
    }


def main() -> None:
    payload = build_payload()
    encoded = (
        json.dumps(
            payload,
            ensure_ascii=False,
            indent=2,
            allow_nan=False,
        )
        + "\n"
    )
    OUTPUT.write_text(encoded, encoding="utf-8")
    SWIFT_OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    SWIFT_OUTPUT.write_text(encoded, encoding="utf-8")
    print(f"wrote {OUTPUT}")
    print(f"mirrored {SWIFT_OUTPUT}")


if __name__ == "__main__":
    main()
