"""Generate deterministic online-preprocessing vectors for Swift parity.

The causal path mirrors ``ppg_monitor.online.OnlineProcessor._push_ppg``:
stateful exponential DC tracking followed by SciPy's third-order 0.6--4 Hz
Butterworth SOS filter.  ``ios_baseline_0.1`` then explicitly inverts the
filtered window (the supplied SQI contract requires peak-up input) and applies
population z-score normalization.
"""

from __future__ import annotations

import hashlib
import json
import math
import platform
import sys
from pathlib import Path

import numpy as np
import scipy
from scipy import signal


HERE = Path(__file__).resolve().parent
PLANNING_ROOT = HERE.parents[1]
REPOSITORY_ROOT = PLANNING_ROOT.parent
PYTHON_REFERENCE_ROOT = PLANNING_ROOT / "references" / "python_gui"
ONLINE_SOURCE = PYTHON_REFERENCE_ROOT / "ppg_monitor" / "online.py"
OUTPUT = HERE / "preprocessing_vectors.json"
SWIFT_OUTPUT = (
    REPOSITORY_ROOT
    / "PPGCollectorTests"
    / "Resources"
    / "SignalProcessing"
    / "preprocessing_vectors.json"
)

SCHEMA = "cup.preprocessing.parity.v1"
PROFILE = "ios_baseline_0.1"
SAMPLE_RATE_HZ = 100.0
DC_TIME_CONSTANT_SECONDS = 0.5
LOW_CUTOFF_HZ = 0.6
HIGH_CUTOFF_HZ = 4.0
FILTER_ORDER = 3
STANDARD_DEVIATION_EPSILON = 1e-8


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _sos() -> np.ndarray:
    return signal.butter(
        FILTER_ORDER,
        [LOW_CUTOFF_HZ, HIGH_CUTOFF_HZ],
        btype="bandpass",
        fs=SAMPLE_RATE_HZ,
        output="sos",
    )


def _causal_preprocess(
    raw: np.ndarray,
    *,
    reset_indices: tuple[int, ...] = (),
) -> dict[str, object]:
    values = np.asarray(raw, dtype=float)
    coefficients = _sos()
    state = np.zeros((len(coefficients), 2), dtype=float)
    alpha = 1.0 - math.exp(
        -1.0 / max(SAMPLE_RATE_HZ * DC_TIME_CONSTANT_SECONDS, 1.0)
    )
    reset_set = set(reset_indices)
    dc_value: float | None = None
    dc_trace = np.empty(values.size, dtype=float)
    ac_trace = np.empty(values.size, dtype=float)
    filtered = np.empty(values.size, dtype=float)

    for index, raw_value in enumerate(values):
        if index in reset_set:
            dc_value = None
            state.fill(0.0)
        dc_value = (
            float(raw_value)
            if dc_value is None
            else dc_value + alpha * (float(raw_value) - dc_value)
        )
        ac_value = float(raw_value) - dc_value
        output, state = signal.sosfilt(
            coefficients,
            np.asarray([ac_value], dtype=float),
            zi=state,
        )
        dc_trace[index] = dc_value
        ac_trace[index] = ac_value
        filtered[index] = output[0]

    peak_up = -filtered
    mean = float(np.mean(peak_up)) if peak_up.size else None
    standard_deviation = float(np.std(peak_up)) if peak_up.size else None
    if (
        mean is None
        or standard_deviation is None
        or not np.isfinite(standard_deviation)
        or standard_deviation < STANDARD_DEVIATION_EPSILON
    ):
        normalization = {
            "valid": False,
            "reason": "constantSignal",
            "mean": mean,
            "standard_deviation": standard_deviation,
            "values": None,
        }
    else:
        normalization = {
            "valid": True,
            "reason": None,
            "mean": mean,
            "standard_deviation": standard_deviation,
            "values": ((peak_up - mean) / standard_deviation).tolist(),
        }

    return {
        "dc": dc_trace.tolist(),
        "ac": ac_trace.tolist(),
        "bandpassed": filtered.tolist(),
        "peak_up": peak_up.tolist(),
        "normalization": normalization,
    }


def _assert_online_reference_match(raw: np.ndarray, expected: np.ndarray) -> None:
    sys.path.insert(0, str(PYTHON_REFERENCE_ROOT))
    try:
        from ppg_monitor.online import OnlineProcessor
    finally:
        sys.path.pop(0)

    processor = OnlineProcessor(sample_rate_hz=SAMPLE_RATE_HZ, window_s=60.0)
    for index, value in enumerate(raw):
        processor._push_ppg(float(value), float(value), index / SAMPLE_RATE_HZ)
    actual = np.asarray(processor._ir_ac, dtype=float)
    if not np.allclose(actual, expected, rtol=0.0, atol=1e-12):
        maximum_error = float(np.max(np.abs(actual - expected)))
        raise AssertionError(
            f"mirror diverged from OnlineProcessor._push_ppg: {maximum_error}"
        )


def _pulse_down_8s() -> np.ndarray:
    time_s = np.arange(800, dtype=float) / SAMPLE_RATE_HZ
    values = (
        510_000.0
        + 650.0 * time_s
        + 1_800.0 * np.sin(2.0 * np.pi * 0.12 * time_s)
    )
    for center_s in np.arange(0.55, 7.8, 60.0 / 72.0):
        values -= 12_500.0 * np.exp(
            -0.5 * ((time_s - center_s) / 0.055) ** 2
        )
        values -= 2_200.0 * np.exp(
            -0.5 * ((time_s - center_s - 0.20) / 0.075) ** 2
        )
    return values


def _mixed_frequency_6s() -> np.ndarray:
    time_s = np.arange(600, dtype=float) / SAMPLE_RATE_HZ
    return (
        420_000.0
        + 8_000.0 * np.sin(2.0 * np.pi * 1.5 * time_s)
        + 3_500.0 * np.sin(2.0 * np.pi * 0.2 * time_s)
        + 1_200.0 * np.sin(2.0 * np.pi * 8.0 * time_s)
    )


def _gap_reset_5s() -> np.ndarray:
    time_s = np.arange(500, dtype=float) / SAMPLE_RATE_HZ
    values = (
        600_000.0
        + 7_000.0 * np.sin(2.0 * np.pi * 1.2 * time_s)
        + 1_100.0 * np.sin(2.0 * np.pi * 0.15 * time_s)
    )
    values[310:] += 45_000.0
    return values


def _case(
    name: str,
    raw: np.ndarray,
    *,
    reset_indices: tuple[int, ...] = (),
    compare_with_online_reference: bool = True,
) -> dict[str, object]:
    rounded = np.round(np.asarray(raw, dtype=float), 10)
    expected = _causal_preprocess(
        rounded,
        reset_indices=reset_indices,
    )
    if compare_with_online_reference:
        _assert_online_reference_match(
            rounded,
            np.asarray(expected["bandpassed"], dtype=float),
        )
    return {
        "name": name,
        "raw": rounded.tolist(),
        "reset_indices": list(reset_indices),
        "expected": expected,
    }


def build_payload() -> dict[str, object]:
    coefficients = _sos()
    return {
        "schema": SCHEMA,
        "profile": PROFILE,
        "source": {
            "path": "../../references/python_gui/ppg_monitor/online.py",
            "sha256": _sha256(ONLINE_SOURCE),
            "function": "OnlineProcessor._push_ppg",
        },
        "environment": {
            "python": platform.python_version(),
            "numpy": np.__version__,
            "scipy": scipy.__version__,
        },
        "config": {
            "sample_rate_hz": SAMPLE_RATE_HZ,
            "dc_time_constant_seconds": DC_TIME_CONSTANT_SECONDS,
            "dc_alpha": (
                1.0
                - math.exp(
                    -1.0
                    / (
                        SAMPLE_RATE_HZ
                        * DC_TIME_CONSTANT_SECONDS
                    )
                )
            ),
            "low_cutoff_hz": LOW_CUTOFF_HZ,
            "high_cutoff_hz": HIGH_CUTOFF_HZ,
            "filter_order": FILTER_ORDER,
            "sos": coefficients.tolist(),
            "polarity_transform": "invert",
            "zscore_ddof": 0,
            "standard_deviation_epsilon":
                STANDARD_DEVIATION_EPSILON,
            "filter_state_initialization": "zeros",
            "gap_behavior": "reset_before_current_sample",
        },
        "notes": [
            "Synthetic numerical parity data; not physiological validation.",
            "Raw recording and CSV remain unfiltered.",
            "Gap reset is an iOS baseline extension; the imported online.py "
            "does not define discontinuity reset behavior.",
            "Offline sosfiltfilt output is intentionally outside this profile.",
        ],
        "cases": [
            _case("pulse_down_8s", _pulse_down_8s()),
            _case("mixed_frequency_6s", _mixed_frequency_6s()),
            _case(
                "gap_reset_5s",
                _gap_reset_5s(),
                reset_indices=(310,),
                compare_with_online_reference=False,
            ),
            _case(
                "constant_4s",
                np.full(400, 500_000.0),
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
