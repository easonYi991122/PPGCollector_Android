"""Streaming device-timestamp cadence checks."""

from __future__ import annotations

from dataclasses import dataclass
import math

from .integrity import UINT32_HALF_RANGE, UINT32_MASK


@dataclass(slots=True)
class TimingTracker:
    expected_period_us: float
    previous_seq: int | None = None
    previous_time_s: float | None = None
    intervals: int = 0
    _absolute_error_sum_us: float = 0.0
    _squared_error_sum_us: float = 0.0
    max_abs_error_us: float = 0.0

    def observe(self, sample_seq: int, device_time_s: float) -> None:
        sample_seq &= UINT32_MASK
        if self.previous_seq is None or self.previous_time_s is None:
            self.previous_seq = sample_seq
            self.previous_time_s = device_time_s
            return

        delta = (sample_seq - self.previous_seq) & UINT32_MASK
        if delta == 0 or delta >= UINT32_HALF_RANGE:
            return

        elapsed_us = (device_time_s - self.previous_time_s) * 1_000_000.0
        error_us = elapsed_us - delta * self.expected_period_us
        absolute_error_us = abs(error_us)
        self.intervals += 1
        self._absolute_error_sum_us += absolute_error_us
        self._squared_error_sum_us += error_us * error_us
        self.max_abs_error_us = max(self.max_abs_error_us, absolute_error_us)
        self.previous_seq = sample_seq
        self.previous_time_s = device_time_s

    def as_dict(self) -> dict[str, float | int]:
        if self.intervals == 0:
            return {
                "intervals": 0,
                "expected_period_us": self.expected_period_us,
                "mean_abs_error_us": 0.0,
                "rms_error_us": 0.0,
                "max_abs_error_us": 0.0,
            }
        return {
            "intervals": self.intervals,
            "expected_period_us": self.expected_period_us,
            "mean_abs_error_us": self._absolute_error_sum_us / self.intervals,
            "rms_error_us": math.sqrt(self._squared_error_sum_us / self.intervals),
            "max_abs_error_us": self.max_abs_error_us,
        }
