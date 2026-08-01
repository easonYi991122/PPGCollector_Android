"""Generate complete template-match SQI traces for Swift parity."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import math
import platform
from pathlib import Path
from typing import Any

import numpy as np
import scipy
from scipy import signal as scipy_signal


HERE = Path(__file__).resolve().parent
PLANNING_ROOT = HERE.parents[1]
REPOSITORY_ROOT = PLANNING_ROOT.parent
SQI_SOURCE = (
    PLANNING_ROOT / "references" / "requirements" / "sqi_template_match.py"
)
OUTPUT = HERE / "sqi_vectors.json"
SWIFT_OUTPUT = (
    REPOSITORY_ROOT
    / "PPGCollectorTests"
    / "Resources"
    / "SignalProcessing"
    / "sqi_vectors.json"
)

SCHEMA = "cup.sqi.parity.v2"
ALGORITHM_VERSION = "ppg-ios-sqi-0.1"
PREPROCESS_PROFILE = "ios_baseline_0.1"
FS = 100
RATIO_PRE = 0.5
HR_MAX = 180
GOOD_THRESHOLD = 0.90
FAIR_THRESHOLD = 0.70
STANDARD_DEVIATION_EPSILON = 1e-8


def _load_sqi_module():
    spec = importlib.util.spec_from_file_location("supplied_sqi", SQI_SOURCE)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {SQI_SOURCE}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _standardize(values: np.ndarray) -> np.ndarray:
    return (values - np.mean(values)) / np.std(values)


def _stable_ppg() -> np.ndarray:
    time_s = np.arange(800, dtype=float) / FS
    values = np.zeros_like(time_s)
    for center_s in np.arange(0.55, 7.8, 0.8):
        values += np.exp(-0.5 * ((time_s - center_s) / 0.055) ** 2)
        values += 0.22 * np.exp(
            -0.5 * ((time_s - center_s - 0.20) / 0.075) ** 2
        )
    return _standardize(values)


def _distorted_ppg() -> np.ndarray:
    rng = np.random.default_rng(20260730)
    time_s = np.arange(800, dtype=float) / FS
    values = np.zeros_like(time_s)
    centers = np.arange(0.55, 7.8, 0.8)
    for index, center_s in enumerate(centers):
        amplitude = 1.0 + 0.35 * np.sin(index * 1.7)
        width_s = 0.045 + 0.025 * (index % 3)
        values += amplitude * np.exp(
            -0.5 * ((time_s - center_s) / width_s) ** 2
        )
        if index % 2 == 0:
            values += 0.35 * np.exp(
                -0.5 * ((time_s - center_s - 0.25) / 0.055) ** 2
            )
    values += 0.12 * np.sin(2 * np.pi * 2.8 * time_s)
    values += 0.08 * rng.normal(size=time_s.size)
    return _standardize(values)


def _poor_ppg() -> np.ndarray:
    rng = np.random.default_rng(505)
    time_s = np.arange(800, dtype=float) / FS
    values = np.zeros_like(time_s)
    for index, center_s in enumerate(np.arange(0.55, 7.8, 0.8)):
        values += (1.0 + 0.2 * np.sin(index)) * np.exp(
            -0.5 * ((time_s - center_s) / 0.055) ** 2
        )
        values += (0.2 if index % 2 == 0 else -0.2) * np.exp(
            -0.5 * ((time_s - center_s - 0.20) / 0.070) ** 2
        )
    values += 0.5 * rng.normal(size=time_s.size)
    return _standardize(values)


def _narrow_fallback_ppg() -> np.ndarray:
    time_s = np.arange(800, dtype=float) / FS
    values = np.zeros_like(time_s)
    for center_s in np.arange(0.55, 7.8, 0.8):
        values += np.exp(
            -0.5 * ((time_s - center_s) / 0.012) ** 2
        )
    return _standardize(values)


def _single_pulse_ppg() -> np.ndarray:
    time_s = np.arange(800, dtype=float) / FS
    values = np.exp(-0.5 * ((time_s - 4.0) / 0.055) ** 2)
    return _standardize(values)


def _edge_partial_cycles_ppg() -> np.ndarray:
    time_s = np.arange(800, dtype=float) / FS
    values = (
        np.exp(-0.5 * ((time_s - 0.10) / 0.055) ** 2)
        + np.exp(-0.5 * ((time_s - 4.00) / 0.055) ** 2)
    )
    return _standardize(values)


def _json_number(value: Any) -> Any:
    if isinstance(value, (bool, np.bool_)):
        return bool(value)
    if isinstance(value, (float, np.floating)):
        return None if not np.isfinite(value) else float(value)
    if isinstance(value, (int, np.integer)):
        return int(value)
    return value


def _peak_result(
    peaks: np.ndarray,
    properties: dict[str, np.ndarray],
) -> dict[str, Any]:
    result: dict[str, Any] = {
        "peak_indices": peaks.tolist(),
        "peak_heights": np.asarray(
            properties.get("peak_heights", []),
            dtype=float,
        ).tolist(),
        "prominences": np.asarray(
            properties.get("prominences", []),
            dtype=float,
        ).tolist(),
        "left_bases": np.asarray(
            properties.get("left_bases", []),
            dtype=int,
        ).tolist(),
        "right_bases": np.asarray(
            properties.get("right_bases", []),
            dtype=int,
        ).tolist(),
        "widths": np.asarray(
            properties.get("widths", []),
            dtype=float,
        ).tolist(),
        "width_heights": np.asarray(
            properties.get("width_heights", []),
            dtype=float,
        ).tolist(),
        "left_ips": np.asarray(
            properties.get("left_ips", []),
            dtype=float,
        ).tolist(),
        "right_ips": np.asarray(
            properties.get("right_ips", []),
            dtype=float,
        ).tolist(),
    }
    return result


def _peak_trace(values: np.ndarray, module: Any) -> dict[str, Any]:
    mean = float(np.mean(values))
    standard_deviation = float(np.std(values))
    minimum_distance = max(1, int(FS * 60 / HR_MAX))
    primary_height = mean + 0.5 * standard_deviation
    primary_prominence = 0.3 * standard_deviation
    primary_peaks, primary_properties = scipy_signal.find_peaks(
        values,
        distance=minimum_distance,
        height=primary_height,
        prominence=primary_prominence,
        width=5,
    )
    fallback_height = mean + 0.2 * standard_deviation
    fallback_prominence = 0.2 * standard_deviation
    fallback_peaks = np.array([], dtype=int)
    fallback_properties: dict[str, np.ndarray] = {}
    selected_mode = "primary"
    selected_peaks = primary_peaks
    if primary_peaks.size < 2:
        selected_mode = "fallback"
        fallback_peaks, fallback_properties = scipy_signal.find_peaks(
            values,
            distance=minimum_distance,
            height=fallback_height,
            prominence=fallback_prominence,
        )
        selected_peaks = fallback_peaks

    reference_peaks = module.detect_systolic_peaks(values, fs=FS)
    if not np.array_equal(selected_peaks, reference_peaks):
        raise AssertionError("peak trace differs from detect_systolic_peaks")
    return {
        "mean": mean,
        "standard_deviation": standard_deviation,
        "minimum_distance_samples": minimum_distance,
        "primary": {
            "minimum_height": primary_height,
            "minimum_prominence": primary_prominence,
            "minimum_width_samples": 5.0,
            **_peak_result(primary_peaks, primary_properties),
        },
        "fallback": {
            "was_evaluated": selected_mode == "fallback",
            "minimum_height": fallback_height,
            "minimum_prominence": fallback_prominence,
            "minimum_width_samples": None,
            **_peak_result(fallback_peaks, fallback_properties),
        },
        "selected_mode": selected_mode,
        "selected_peak_indices": selected_peaks.tolist(),
    }


def _empty_cycle_trace(error: str | None = None) -> dict[str, Any]:
    return {
        "error": error,
        "rate_bpm": None,
        "pre_samples": None,
        "post_samples": None,
        "window_length": None,
        "time_axis_s": [],
        "cycle_valid_mask": [],
        "dropped_peak_indices": [],
        "valid_peak_indices": [],
        "cycles": [],
        "template": [],
        "quality_anchor_peak_indices": [],
        "cycle_quality": [],
        "quality_trace": [],
    }


def _cycle_trace(
    values: np.ndarray,
    peaks: np.ndarray,
    module: Any,
) -> dict[str, Any]:
    try:
        sorted_peaks = module._sorted_unique_inds(peaks)
        pre_sec, post_sec, rate = module._segment_window(
            sorted_peaks,
            FS,
            RATIO_PRE,
        )
        pre = int(round(abs(pre_sec) * FS))
        post = int(round(post_sec * FS))
        segmented, time_axis, segment_rate = module._cycle_segment(
            values,
            sorted_peaks,
            FS,
            RATIO_PRE,
        )
        valid_mask = ~np.isnan(segmented).any(axis=1)
    except ValueError as exc:
        return _empty_cycle_trace(str(exc))

    np.testing.assert_allclose(segment_rate, rate, rtol=0, atol=0)
    cycles = segmented[valid_mask]
    valid_peaks = sorted_peaks[valid_mask]
    base = {
        "rate_bpm": float(rate),
        "pre_samples": pre,
        "post_samples": post,
        "window_length": pre + post + 1,
        "time_axis_s": time_axis.tolist(),
        "cycle_valid_mask": valid_mask.tolist(),
        "dropped_peak_indices": sorted_peaks[~valid_mask].tolist(),
        "valid_peak_indices": valid_peaks.tolist(),
        "cycles": cycles.tolist(),
    }
    if cycles.size == 0:
        return {
            "error": "no complete cycle after NaN filtering",
            **base,
            "template": [],
            "quality_anchor_peak_indices": [],
            "cycle_quality": [],
            "quality_trace": [],
        }
    template = np.mean(cycles, axis=0)
    if valid_peaks.size < 2:
        return {
            "error": "need >= 2 valid cycles to build quality trace",
            **base,
            "template": template.tolist(),
            "quality_anchor_peak_indices": [],
            "cycle_quality": [],
            "quality_trace": [],
        }
    quality, cycle_quality, quality_template, quality_cycles, (
        quality_valid_peaks
    ), quality_axis = module.quality_templatematch(
        values,
        sorted_peaks,
        fs=FS,
        ratio_pre=RATIO_PRE,
    )
    np.testing.assert_allclose(quality_axis, time_axis, rtol=0, atol=0)
    np.testing.assert_allclose(quality_template, template, rtol=0, atol=0)
    np.testing.assert_allclose(quality_cycles, cycles, rtol=0, atol=0)
    if not np.array_equal(quality_valid_peaks, valid_peaks):
        raise AssertionError("quality/build valid peaks differ")
    return {
        "error": None,
        **base,
        "template": template.tolist(),
        "quality_anchor_peak_indices": valid_peaks[:-1].tolist(),
        "cycle_quality": cycle_quality.tolist(),
        "quality_trace": quality.tolist(),
    }


def _trace(values: np.ndarray, module: Any) -> dict[str, Any]:
    if values.size < FS * 4:
        return {
            "unavailable_reason": "signal_too_short",
            "peak_detection": None,
            "cycles": _empty_cycle_trace(),
        }
    if float(np.std(values)) < STANDARD_DEVIATION_EPSILON:
        return {
            "unavailable_reason": "constant_signal",
            "peak_detection": None,
            "cycles": _empty_cycle_trace(),
        }

    peak_detection = _peak_trace(values, module)
    peaks = np.asarray(
        peak_detection["selected_peak_indices"],
        dtype=int,
    )
    cycles = _cycle_trace(values, peaks, module)
    reason = (
        None
        if cycles["error"] is None
        else f"template_failed:{cycles['error']}"
    )
    return {
        "unavailable_reason": reason,
        "peak_detection": peak_detection,
        "cycles": cycles,
    }


def _case(module: Any, name: str, values: np.ndarray) -> dict[str, Any]:
    rounded = np.round(np.asarray(values, dtype=float), 10)
    result = module.compute_sqi(
        rounded,
        fs=FS,
        ratio_pre=RATIO_PRE,
    )
    expected = {key: _json_number(value) for key, value in result.items()}
    raw_quality = expected["mean_quality"]
    expected["display_sqi"] = (
        None
        if raw_quality is None
        else min(1.0, max(0.0, raw_quality))
    )
    grade, color = module.sqi_grade(
        raw_quality,
        good=GOOD_THRESHOLD,
        fair=FAIR_THRESHOLD,
    )
    expected["grade"] = grade
    expected["grade_color_hex"] = color
    trace = _trace(rounded, module)
    if expected["reason"] == "ok":
        if trace["unavailable_reason"] is not None:
            raise AssertionError(f"{name}: valid trace has failure reason")
        cycles = trace["cycles"]
        if expected["n_cycles"] != len(cycles["cycle_quality"]):
            raise AssertionError(f"{name}: n_cycles mismatch")
        if expected["n_peaks"] != len(
            trace["peak_detection"]["selected_peak_indices"]
        ):
            raise AssertionError(f"{name}: n_peaks mismatch")
        if not math.isclose(
            expected["mean_quality"],
            float(np.mean(cycles["cycle_quality"])),
            rel_tol=0,
            abs_tol=1e-15,
        ):
            raise AssertionError(f"{name}: mean quality mismatch")
    elif expected["reason"] != trace["unavailable_reason"]:
        raise AssertionError(f"{name}: failure reason mismatch")
    return {
        "name": name,
        "fs_hz": FS,
        "input_kind": "preprocessed_peak_up_ppg",
        "signal": rounded.tolist(),
        "expected": expected,
        "trace": trace,
    }


def build_payload() -> dict[str, Any]:
    module = _load_sqi_module()
    return {
        "schema": SCHEMA,
        "algorithm_version": ALGORITHM_VERSION,
        "preprocess_profile": PREPROCESS_PROFILE,
        "source": {
            "path": "../../references/requirements/sqi_template_match.py",
            "sha256": _sha256(SQI_SOURCE),
            "function": "compute_sqi",
        },
        "environment": {
            "python": platform.python_version(),
            "numpy": np.__version__,
            "scipy": scipy.__version__,
        },
        "config": {
            "sample_rate_hz": FS,
            "ratio_pre": RATIO_PRE,
            "hr_max_bpm": HR_MAX,
            "minimum_window_seconds": 4.0,
            "standard_deviation_epsilon":
                STANDARD_DEVIATION_EPSILON,
            "primary_height_std_factor": 0.5,
            "primary_prominence_std_factor": 0.3,
            "primary_minimum_width_samples": 5.0,
            "fallback_height_std_factor": 0.2,
            "fallback_prominence_std_factor": 0.2,
            "good_threshold": GOOD_THRESHOLD,
            "fair_threshold": FAIR_THRESHOLD,
        },
        "notes": [
            "Synthetic preprocessed peak-up inputs; not clinical validation.",
            "ios_baseline_0.1 lacks the referenced CPE/full preprocessing.",
            "cycle_quality intentionally has valid_peaks.count - 1 values.",
            "display_sqi clamps raw Pearson mean to 0...1.",
        ],
        "cases": [
            _case(module, "stable_good_8s", _stable_ppg()),
            _case(module, "distorted_fair_8s", _distorted_ppg()),
            _case(module, "noisy_poor_8s", _poor_ppg()),
            _case(
                module,
                "narrow_fallback_good_8s",
                _narrow_fallback_ppg(),
            ),
            _case(
                module,
                "single_peak_template_failure_8s",
                _single_pulse_ppg(),
            ),
            _case(
                module,
                "edge_partial_cycle_failure_8s",
                _edge_partial_cycles_ppg(),
            ),
            _case(module, "constant_8s", np.zeros(800, dtype=float)),
            _case(module, "too_short_3s", np.zeros(300, dtype=float)),
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
