"""Causal signal processing and integrity metrics for live binary capture."""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass
import math

import numpy as np
from scipy import signal

from .device_profile import DeviceProfile, STM32F411_ADXL345_ITG3205
from .integrity import SequenceTracker
from .imu_quality import classify_lsm6ds3_rotation
from .protocol.constants import (
    EVENT_SESSION_START,
    EVENT_SESSION_STOP,
    FLAG_FIFO_OVERFLOW,
    FLAG_RING_OVERFLOW,
    FLAG_SESSION_INCOMPLETE,
    FLAG_USB_STALL,
    PACKET_EVENT,
    PACKET_IMU_BATCH,
    PACKET_PPG_BATCH,
)
from .protocol.packets import (
    Packet,
    ProtocolError,
    decode_batch_samples,
    decode_event,
)
from .pulse import estimate_pulse
from .timing import TimingTracker


def visible_y_range(
    time_s: np.ndarray,
    series: tuple[np.ndarray, ...],
    *,
    exclude_leading_s: float = 2.0,
    padding_ratio: float = 0.08,
) -> tuple[float, float] | None:
    """Return a live-plot Y range without the oldest settling interval."""

    x = np.asarray(time_s, dtype=float)
    if len(x) == 0:
        return None
    cutoff = float(x[0]) + max(0.0, exclude_leading_s)
    use_tail = float(x[-1] - x[0]) > exclude_leading_s
    chunks: list[np.ndarray] = []
    for values in series:
        y = np.asarray(values, dtype=float)
        count = min(len(x), len(y))
        if count == 0:
            continue
        valid = np.isfinite(x[:count]) & np.isfinite(y[:count])
        if use_tail:
            valid &= x[:count] >= cutoff
        if np.any(valid):
            chunks.append(y[:count][valid])
    if not chunks:
        return None
    combined = np.concatenate(chunks)
    low = float(np.min(combined))
    high = float(np.max(combined))
    span = high - low
    if span <= 0.0:
        span = max(abs(low) * 0.05, 1.0)
    padding = span * max(0.0, padding_ratio)
    return low - padding, high + padding


@dataclass(frozen=True, slots=True)
class OnlineSnapshot:
    time_s: np.ndarray
    red_ac: np.ndarray
    ir_ac: np.ndarray
    imu_time_s: np.ndarray
    accel_magnitude: np.ndarray
    gyro_magnitude: np.ndarray
    hr_candidate_bpm: float | None
    hr_spectral_bpm: float | None
    hr_confidence: float
    ppg_channel: str | None
    ppg_quality_state: str
    ppg_snr_db: float
    red_ac_dc_percent: float
    ir_ac_dc_percent: float
    ppg_valid: bool
    motion_rms_m_s2: float
    motion_correlation: float
    imu_quality_score: float
    imu_word_shift_corrections: int
    packet_count: int
    ppg_samples: int
    imu_samples: int
    packet_gaps: int
    ppg_sample_gaps: int
    imu_sample_gaps: int
    overflow_events: int
    crc_errors: int
    bytes_discarded: int
    ppg_timing_max_error_us: float
    imu_timing_max_error_us: float
    session_active: bool
    device_stop_received: bool


class OnlineProcessor:
    """Consume typed packets and expose bounded, GUI-friendly snapshots."""

    def __init__(
        self,
        sample_rate_hz: float,
        window_s: float = 10.0,
        device_profile: DeviceProfile | None = None,
    ) -> None:
        self.sample_rate_hz = sample_rate_hz
        self.window_s = window_s
        self.device_profile = device_profile or STM32F411_ADXL345_ITG3205
        imu_rate_hz = (
            self.device_profile.imu_rate_hz
            if device_profile is not None
            else sample_rate_hz
        )
        self.max_samples = max(32, int(sample_rate_hz * window_s))
        self.max_imu_samples = max(32, int(imu_rate_hz * window_s))
        self._times: deque[float] = deque(maxlen=self.max_samples)
        self._red_ac: deque[float] = deque(maxlen=self.max_samples)
        self._ir_ac: deque[float] = deque(maxlen=self.max_samples)
        self._red_raw: deque[float] = deque(maxlen=self.max_samples)
        self._ir_raw: deque[float] = deque(maxlen=self.max_samples)
        self._accel: deque[tuple[float, float]] = deque(maxlen=self.max_imu_samples)
        self._gyro: deque[tuple[float, float]] = deque(maxlen=self.max_imu_samples)
        self._imu_quality: deque[tuple[float, float]] = deque(
            maxlen=self.max_imu_samples
        )
        self._red_dc: float | None = None
        self._ir_dc: float | None = None
        self._red_filter = self._make_filter()
        self._ir_filter = self._make_filter()
        self._red_state = signal.sosfilt_zi(self._red_filter) * 0.0
        self._ir_state = signal.sosfilt_zi(self._ir_filter) * 0.0
        self._last_hr_update = -math.inf
        self._hr_candidate: float | None = None
        self._hr_spectral: float | None = None
        self._hr_confidence = 0.0
        self._ppg_channel: str | None = None
        self._ppg_snr_db = float("-inf")
        self._motion_correlation_value = 0.0
        self._imu_rotation = 0
        self._imu_rotation_quality = 1.0
        self._imu_word_shift_corrections = 0
        self.packet_count = 0
        self.ppg_samples = 0
        self.imu_samples = 0
        self.packet_sequence = SequenceTracker()
        self.ppg_sequence = SequenceTracker()
        self.imu_sequence = SequenceTracker()
        expected_period_us = 1_000_000.0 / sample_rate_hz
        self.ppg_timing = TimingTracker(expected_period_us)
        self.imu_timing = TimingTracker(1_000_000.0 / imu_rate_hz)
        self.overflow_events = 0
        self.crc_errors = 0
        self.bytes_discarded = 0
        self.session_active = False
        self.device_stop_received = False

    def set_device_profile(self, profile: DeviceProfile) -> None:
        """Select raw IMU scaling after READY identifies the board.

        The live reader calls this before binary packets arrive, so resetting
        the IMU-only buffers and timing tracker cannot discard capture data.
        """
        self.device_profile = profile
        self.max_imu_samples = max(32, int(profile.imu_rate_hz * self.window_s))
        self._accel = deque(maxlen=self.max_imu_samples)
        self._gyro = deque(maxlen=self.max_imu_samples)
        self._imu_quality = deque(maxlen=self.max_imu_samples)
        self._imu_rotation = 0
        self._imu_rotation_quality = 1.0
        self._imu_word_shift_corrections = 0
        self.imu_timing = TimingTracker(1_000_000.0 / profile.imu_rate_hz)

    def _make_filter(self) -> np.ndarray:
        high = min(4.0, self.sample_rate_hz * 0.45)
        return signal.butter(
            3,
            [0.6, high],
            btype="bandpass",
            fs=self.sample_rate_hz,
            output="sos",
        )

    def update_decoder_stats(self, *, crc_errors: int, bytes_discarded: int) -> None:
        self.crc_errors = crc_errors
        self.bytes_discarded = bytes_discarded

    def process_packet(self, packet: Packet) -> None:
        """Update state without allocating a snapshot for every packet."""
        self.packet_count += 1
        self.packet_sequence.observe(packet.packet_seq)
        if packet.packet_flags & (
            FLAG_FIFO_OVERFLOW
            | FLAG_RING_OVERFLOW
            | FLAG_USB_STALL
            | FLAG_SESSION_INCOMPLETE
        ):
            self.overflow_events += 1

        if packet.packet_type == PACKET_EVENT:
            try:
                event = decode_event(packet)
            except ProtocolError:
                return
            if event.event_code == EVENT_SESSION_START:
                self.session_active = True
                self.device_stop_received = False
            elif event.event_code == EVENT_SESSION_STOP:
                self.session_active = False
                self.device_stop_received = True
            return

        try:
            samples = decode_batch_samples(packet)
        except ProtocolError:
            return

        period_s = packet.sample_period_ns / 1_000_000_000.0
        for index, sample in enumerate(samples):
            time_s = packet.t0_us / 1_000_000.0 + index * period_s
            if packet.packet_type == PACKET_PPG_BATCH:
                self.ppg_samples += 1
                self.ppg_sequence.observe(sample.sample_seq)
                self.ppg_timing.observe(sample.sample_seq, time_s)
                if sample.status & FLAG_FIFO_OVERFLOW:
                    self.overflow_events += 1
                self._push_ppg(sample.red, sample.ir, time_s)
            elif packet.packet_type == PACKET_IMU_BATCH:
                self.imu_samples += 1
                previous = self.imu_sequence.previous
                self.imu_sequence.observe(sample.sample_seq)
                self.imu_timing.observe(sample.sample_seq, time_s)
                sequence_break = (
                    previous is not None
                    and ((sample.sample_seq - previous) & 0xFFFFFFFF) != 1
                )
                words = np.array(
                    [[
                        sample.ax,
                        sample.ay,
                        sample.az,
                        sample.gx,
                        sample.gy,
                        sample.gz,
                    ]],
                    dtype=float,
                )
                event_quality = self._imu_rotation_quality
                if (
                    "LSM6DS3" in self.device_profile.imu_model.upper()
                    and (sequence_break or sample.status != 0)
                ):
                    rotation, confidence, _ = classify_lsm6ds3_rotation(
                        words, self.device_profile
                    )
                    if confidence >= 0.55:
                        self._imu_rotation = rotation
                        self._imu_rotation_quality = confidence
                        event_quality = confidence
                        if rotation != 0:
                            self._imu_word_shift_corrections += 1
                    else:
                        self._imu_rotation = 0
                        self._imu_rotation_quality = 0.0
                        event_quality = 0.0
                if self._imu_rotation:
                    words = np.roll(
                        words, self._imu_rotation, axis=1
                    )
                self._push_imu(
                    *[int(value) for value in words[0]],
                    time_s,
                    quality=(
                        0.0 if sample.status != 0 else event_quality
                    ),
                )

    def feed_packet(self, packet: Packet) -> OnlineSnapshot:
        """Compatibility helper for tests and non-GUI users."""
        self.process_packet(packet)
        return self.snapshot()

    def _push_ppg(self, red: int, ir: int, time_s: float) -> None:
        alpha = 1.0 - math.exp(-1.0 / max(self.sample_rate_hz * 0.5, 1.0))
        self._red_dc = (
            float(red)
            if self._red_dc is None
            else self._red_dc + alpha * (red - self._red_dc)
        )
        self._ir_dc = (
            float(ir)
            if self._ir_dc is None
            else self._ir_dc + alpha * (ir - self._ir_dc)
        )
        red_ac = float(red - self._red_dc)
        ir_ac = float(ir - self._ir_dc)
        red_filtered, self._red_state = signal.sosfilt(
            self._red_filter, [red_ac], zi=self._red_state
        )
        ir_filtered, self._ir_state = signal.sosfilt(
            self._ir_filter, [ir_ac], zi=self._ir_state
        )
        self._times.append(time_s)
        self._red_raw.append(float(red))
        self._ir_raw.append(float(ir))
        self._red_ac.append(float(red_filtered[0]))
        self._ir_ac.append(float(ir_filtered[0]))

        if (
            time_s - self._last_hr_update >= 1.0
            and len(self._ir_ac) >= int(self.sample_rate_hz * 4)
        ):
            self._last_hr_update = time_s
            self._update_hr()

    def _push_imu(
        self,
        ax: int,
        ay: int,
        az: int,
        gx: int,
        gy: int,
        gz: int,
        time_s: float,
        quality: float = 1.0,
    ) -> None:
        accel = (
            math.sqrt(ax * ax + ay * ay + az * az)
            * self.device_profile.accel_g_per_lsb
            * 9.80665
        )
        gyro = (
            math.sqrt(gx * gx + gy * gy + gz * gz)
            * self.device_profile.gyro_dps_per_lsb
            * math.pi
            / 180.0
        )
        self._accel.append((time_s, accel))
        self._gyro.append((time_s, gyro))
        self._imu_quality.append(
            (time_s, max(0.0, min(1.0, quality)))
        )

    def _motion_rms(self) -> float:
        if not self._accel:
            return 0.0
        accel = np.fromiter((item[1] for item in self._accel), dtype=float)
        recent_count = min(
            len(accel),
            max(1, int(self.device_profile.imu_rate_hz * 2)),
        )
        recent = accel[-recent_count:]
        dynamic = recent - np.median(recent)
        return float(np.sqrt(np.mean(dynamic**2)))

    def _imu_quality_score(self) -> float:
        if not self._imu_quality:
            return 0.0
        quality = np.fromiter(
            (item[1] for item in self._imu_quality), dtype=float
        )
        recent_count = min(
            len(quality),
            max(1, int(self.device_profile.imu_rate_hz * 2)),
        )
        return float(np.mean(quality[-recent_count:]))

    def _update_hr(self) -> None:
        red_values = np.asarray(self._red_ac, dtype=float)
        ir_values = np.asarray(self._ir_ac, dtype=float)
        times = np.asarray(self._times, dtype=float)
        window = int(self.sample_rate_hz * 8)
        if len(times) > window:
            red_values = red_values[-window:]
            ir_values = ir_values[-window:]
            times = times[-window:]

        red_estimate = estimate_pulse(red_values, times, self.sample_rate_hz)
        ir_estimate = estimate_pulse(ir_values, times, self.sample_rate_hz)
        channel, estimate, values = max(
            (("RED", red_estimate, red_values), ("IR", ir_estimate, ir_values)),
            key=lambda item: item[1].confidence,
        )
        self._ppg_channel = channel
        self._hr_spectral = estimate.spectral_bpm
        self._ppg_snr_db = estimate.snr_db
        self._motion_correlation_value = self._motion_correlation(values, times)
        strong_ppg = (
            estimate.peak_bpm is not None
            and estimate.spectral_bpm is not None
            and estimate.confidence >= 0.45
            and abs(estimate.peak_bpm - estimate.spectral_bpm) <= 8.0
            and (
                estimate.rr_mad_s is None
                or estimate.rr_mad_s <= 0.12
            )
        )
        trust_motion = self._imu_quality_score() >= 0.65 or not strong_ppg
        motion_factor = (
            max(0.0, 1.0 - self._motion_rms() / 2.0)
            if trust_motion
            else 1.0
        )
        correlation_factor = (
            max(
                0.0,
                1.0
                - max(0.0, self._motion_correlation_value - 0.4)
                / 0.6,
            )
            if trust_motion
            else 1.0
        )
        confidence = estimate.confidence * motion_factor * correlation_factor
        self._hr_candidate = estimate.bpm if confidence >= 0.35 else None
        self._hr_confidence = confidence

    def _motion_correlation(self, values: np.ndarray, times: np.ndarray) -> float:
        if len(values) < 8 or len(self._accel) < 8:
            return 0.0
        accel = np.asarray(self._accel, dtype=float)
        overlap = (times >= accel[0, 0]) & (times <= accel[-1, 0])
        if np.count_nonzero(overlap) < 8:
            return 0.0
        aligned = np.interp(times[overlap], accel[:, 0], accel[:, 1])
        aligned -= np.median(aligned)
        ppg = values[overlap] - np.median(values[overlap])
        if np.std(aligned) < 1e-9 or np.std(ppg) < 1e-9:
            return 0.0
        return float(abs(np.corrcoef(ppg, aligned)[0, 1]))

    def _ac_dc_percent(self, values: deque[float], dc: float | None) -> float:
        if dc is None or abs(dc) < 1.0 or not values:
            return 0.0
        count = min(len(values), max(1, int(self.sample_rate_hz * 2)))
        recent = np.asarray(values, dtype=float)[-count:]
        return float(100.0 * np.sqrt(np.mean(recent**2)) / abs(dc))

    def _quality_state(
        self, red_ac_dc_percent: float, ir_ac_dc_percent: float
    ) -> str:
        if (
            self.packet_sequence.missing
            or self.ppg_sequence.missing
            or self.overflow_events
            or self.crc_errors
            or self.bytes_discarded
        ):
            return "INCOMPLETE"
        warmup_samples = min(self.max_samples, int(self.sample_rate_hz * 4))
        if len(self._ir_ac) < warmup_samples:
            return "WARMING_UP"
        recent_count = min(
            len(self._ir_raw), max(1, int(self.sample_rate_hz * 2))
        )
        red_raw = np.asarray(self._red_raw, dtype=float)[-recent_count:]
        ir_raw = np.asarray(self._ir_raw, dtype=float)[-recent_count:]
        if np.mean((red_raw >= 0.99 * 0x3FFFF) | (ir_raw >= 0.99 * 0x3FFFF)) > 0.01:
            return "SATURATED"
        if max(red_ac_dc_percent, ir_ac_dc_percent) < 0.02:
            return "LOW_PERFUSION"
        motion_detected = (
            self._motion_rms() > 1.0
            or self._motion_correlation_value > 0.55
        )
        if motion_detected and self._imu_quality_score() >= 0.65:
            return "MOTION"
        if self._hr_candidate is None:
            return "LOW_CONFIDENCE"
        if motion_detected:
            return "GOOD_IMU_BYPASS"
        return "GOOD"

    def snapshot(self) -> OnlineSnapshot:
        accel = np.asarray(self._accel, dtype=float)
        gyro = np.asarray(self._gyro, dtype=float)
        red_ac_dc_percent = self._ac_dc_percent(self._red_ac, self._red_dc)
        ir_ac_dc_percent = self._ac_dc_percent(self._ir_ac, self._ir_dc)
        quality_state = self._quality_state(red_ac_dc_percent, ir_ac_dc_percent)
        ppg_valid = quality_state in ("GOOD", "GOOD_IMU_BYPASS")
        return OnlineSnapshot(
            time_s=np.asarray(self._times, dtype=float),
            red_ac=np.asarray(self._red_ac, dtype=float),
            ir_ac=np.asarray(self._ir_ac, dtype=float),
            imu_time_s=accel[:, 0] if len(accel) else np.array([], dtype=float),
            accel_magnitude=accel[:, 1] if len(accel) else np.array([], dtype=float),
            gyro_magnitude=gyro[:, 1] if len(gyro) else np.array([], dtype=float),
            hr_candidate_bpm=self._hr_candidate,
            hr_spectral_bpm=self._hr_spectral,
            hr_confidence=self._hr_confidence,
            ppg_channel=self._ppg_channel,
            ppg_quality_state=quality_state,
            ppg_snr_db=self._ppg_snr_db,
            red_ac_dc_percent=red_ac_dc_percent,
            ir_ac_dc_percent=ir_ac_dc_percent,
            ppg_valid=ppg_valid,
            motion_rms_m_s2=self._motion_rms(),
            motion_correlation=self._motion_correlation_value,
            imu_quality_score=self._imu_quality_score(),
            imu_word_shift_corrections=self._imu_word_shift_corrections,
            packet_count=self.packet_count,
            ppg_samples=self.ppg_samples,
            imu_samples=self.imu_samples,
            packet_gaps=self.packet_sequence.missing,
            ppg_sample_gaps=self.ppg_sequence.missing,
            imu_sample_gaps=self.imu_sequence.missing,
            overflow_events=self.overflow_events,
            crc_errors=self.crc_errors,
            bytes_discarded=self.bytes_discarded,
            ppg_timing_max_error_us=self.ppg_timing.max_abs_error_us,
            imu_timing_max_error_us=self.imu_timing.max_abs_error_us,
            session_active=self.session_active,
            device_stop_received=self.device_stop_received,
        )
