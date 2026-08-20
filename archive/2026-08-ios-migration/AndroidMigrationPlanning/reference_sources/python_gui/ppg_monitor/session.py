"""Typed session loader for protocol-v1 raw captures."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

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
from .protocol.decoder import DecoderStats, PacketDecoder
from .protocol.packets import (
    ImuSample,
    PpgSample,
    ProtocolError,
    decode_batch_samples,
    decode_event,
)
from .integrity import SequenceTracker
from .raw_format import iter_chunks
from .timing import TimingTracker


@dataclass(frozen=True, slots=True)
class PpgRecord:
    sample_seq: int
    device_time_s: float
    host_time_ns: int
    red: int
    ir: int
    status: int


@dataclass(frozen=True, slots=True)
class ImuRecord:
    sample_seq: int
    device_time_s: float
    host_time_ns: int
    ax: int
    ay: int
    az: int
    gx: int
    gy: int
    gz: int
    status: int


@dataclass(slots=True)
class SessionData:
    ppg: list[PpgRecord] = field(default_factory=list)
    imu: list[ImuRecord] = field(default_factory=list)
    session_start_us: int | None = None
    session_stop_us: int | None = None
    session_nominal_rate_hz: int | None = None
    packet_gaps: int = 0
    packet_duplicates: int = 0
    packet_out_of_order: int = 0
    ppg_sample_gaps: int = 0
    ppg_sample_duplicates: int = 0
    ppg_sample_out_of_order: int = 0
    imu_sample_gaps: int = 0
    imu_sample_duplicates: int = 0
    imu_sample_out_of_order: int = 0
    packet_fifo_overflow: int = 0
    packet_ring_overflow: int = 0
    packet_usb_stall: int = 0
    packet_session_incomplete: int = 0
    ppg_fifo_overflow_samples: int = 0
    ppg_timing: dict[str, float | int] = field(default_factory=dict)
    imu_timing: dict[str, float | int] = field(default_factory=dict)
    decoder: DecoderStats = field(default_factory=DecoderStats)

    @property
    def session_duration_s(self) -> float | None:
        if (
            self.session_start_us is None
            or self.session_stop_us is None
            or self.session_stop_us <= self.session_start_us
        ):
            return None
        return (self.session_stop_us - self.session_start_us) / 1_000_000.0

    def timing_summary(self) -> dict[str, object]:
        duration_s = self.session_duration_s
        return {
            "session_start_us": self.session_start_us,
            "session_stop_us": self.session_stop_us,
            "session_duration_s": duration_s,
            "nominal_ppg_rate_hz": self.session_nominal_rate_hz,
            "ppg": _stream_timing_summary(
                self.ppg,
                self.ppg_timing,
                duration_s,
                self.session_stop_us,
            ),
            "imu": _stream_timing_summary(
                self.imu,
                self.imu_timing,
                duration_s,
                self.session_stop_us,
            ),
        }


def load_binary_session(path: Path) -> SessionData:
    result = SessionData()
    decoder = PacketDecoder()
    packet_sequence = SequenceTracker()
    ppg_sequence = SequenceTracker()
    imu_sequence = SequenceTracker()
    ppg_timing: TimingTracker | None = None
    imu_timing: TimingTracker | None = None

    for host_time_ns, chunk in iter_chunks(path):
        for packet in decoder.feed(chunk):
            packet_sequence.observe(packet.packet_seq)
            result.packet_fifo_overflow += bool(packet.packet_flags & FLAG_FIFO_OVERFLOW)
            result.packet_ring_overflow += bool(packet.packet_flags & FLAG_RING_OVERFLOW)
            result.packet_usb_stall += bool(packet.packet_flags & FLAG_USB_STALL)
            result.packet_session_incomplete += bool(
                packet.packet_flags & FLAG_SESSION_INCOMPLETE
            )

            if packet.packet_type == PACKET_EVENT:
                try:
                    event = decode_event(packet)
                except ProtocolError:
                    continue
                if event.event_code == EVENT_SESSION_START:
                    result.session_start_us = event.t_us
                    result.session_nominal_rate_hz = event.value
                elif event.event_code == EVENT_SESSION_STOP:
                    result.session_stop_us = event.t_us
                continue

            try:
                samples = decode_batch_samples(packet)
            except ProtocolError:
                continue

            period_s = packet.sample_period_ns / 1_000_000_000.0
            if packet.packet_type == PACKET_PPG_BATCH:
                if ppg_timing is None:
                    ppg_timing = TimingTracker(
                        expected_period_us=packet.sample_period_ns / 1000.0
                    )
                for index, sample in enumerate(samples):
                    ppg_sequence.observe(sample.sample_seq)
                    ppg_timing.observe(
                        sample.sample_seq,
                        packet.t0_us / 1_000_000.0 + index * period_s,
                    )
                    result.ppg_fifo_overflow_samples += bool(
                        sample.status & FLAG_FIFO_OVERFLOW
                    )
                result.ppg.extend(
                    _ppg_record(sample, packet.t0_us / 1_000_000.0 + index * period_s, host_time_ns)
                    for index, sample in enumerate(samples)
                )
            elif packet.packet_type == PACKET_IMU_BATCH:
                if imu_timing is None:
                    imu_timing = TimingTracker(
                        expected_period_us=packet.sample_period_ns / 1000.0
                    )
                for index, sample in enumerate(samples):
                    imu_sequence.observe(sample.sample_seq)
                    imu_timing.observe(
                        sample.sample_seq,
                        packet.t0_us / 1_000_000.0 + index * period_s,
                    )
                result.imu.extend(
                    _imu_record(sample, packet.t0_us / 1_000_000.0 + index * period_s, host_time_ns)
                    for index, sample in enumerate(samples)
                )

    result.packet_gaps = packet_sequence.missing
    result.packet_duplicates = packet_sequence.duplicates
    result.packet_out_of_order = packet_sequence.out_of_order
    result.ppg_sample_gaps = ppg_sequence.missing
    result.ppg_sample_duplicates = ppg_sequence.duplicates
    result.ppg_sample_out_of_order = ppg_sequence.out_of_order
    result.imu_sample_gaps = imu_sequence.missing
    result.imu_sample_duplicates = imu_sequence.duplicates
    result.imu_sample_out_of_order = imu_sequence.out_of_order
    result.ppg_timing = (
        ppg_timing or TimingTracker(expected_period_us=5000.0)
    ).as_dict()
    result.imu_timing = (
        imu_timing or TimingTracker(expected_period_us=4807.692)
    ).as_dict()
    result.decoder = decoder.stats
    return result


def _ppg_record(sample: PpgSample, device_time_s: float, host_time_ns: int) -> PpgRecord:
    return PpgRecord(
        sample_seq=sample.sample_seq,
        device_time_s=device_time_s,
        host_time_ns=host_time_ns,
        red=sample.red,
        ir=sample.ir,
        status=sample.status,
    )


def _imu_record(sample: ImuSample, device_time_s: float, host_time_ns: int) -> ImuRecord:
    return ImuRecord(
        sample_seq=sample.sample_seq,
        device_time_s=device_time_s,
        host_time_ns=host_time_ns,
        ax=sample.ax,
        ay=sample.ay,
        az=sample.az,
        gx=sample.gx,
        gy=sample.gy,
        gz=sample.gz,
        status=sample.status,
    )


def _stream_timing_summary(
    records: list[PpgRecord] | list[ImuRecord],
    nominal_timing: dict[str, float | int],
    session_duration_s: float | None,
    session_stop_us: int | None,
) -> dict[str, object]:
    expected_period_us = float(
        nominal_timing.get("expected_period_us", 0.0) or 0.0
    )
    nominal_rate_hz = (
        1_000_000.0 / expected_period_us
        if expected_period_us > 0.0
        else None
    )
    effective_rate_hz = (
        len(records) / session_duration_s
        if session_duration_s is not None and session_duration_s > 0.0
        else None
    )
    rate_error_percent = (
        100.0 * (effective_rate_hz / nominal_rate_hz - 1.0)
        if effective_rate_hz is not None
        and nominal_rate_hz is not None
        and nominal_rate_hz > 0.0
        else None
    )
    timeline_end_offset_us = (
        records[-1].device_time_s * 1_000_000.0 - session_stop_us
        if records and session_stop_us is not None
        else None
    )
    return {
        "samples": len(records),
        "nominal_rate_hz": nominal_rate_hz,
        "session_count_rate_hz": effective_rate_hz,
        "rate_error_percent": rate_error_percent,
        "first_device_time_s": (
            records[0].device_time_s if records else None
        ),
        "last_device_time_s": (
            records[-1].device_time_s if records else None
        ),
        "timeline_end_offset_us": timeline_end_offset_us,
    }
