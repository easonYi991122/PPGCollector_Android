"""Reusable protocol-v1 binary-session analysis service."""

from __future__ import annotations

from dataclasses import asdict
import json
from pathlib import Path

from .device_profile import (
    DeviceProfile,
    profile_from_metadata,
)
from .legacy_csv import (
    FLAG_PPG_FIFO_OVERFLOW,
    IntegritySummary,
    LegacyFrame,
)
from .offline import AnalysisResult, analyze_frames, write_report
from .session import SessionData, load_binary_session


def analysis_frames(
    session: SessionData,
    device_profile: DeviceProfile | None = None,
) -> tuple[list[LegacyFrame], IntegritySummary]:
    frames: list[LegacyFrame] = []
    integrity = IntegritySummary()
    previous_seq: int | None = None
    previous_time: float | None = None
    previous_overflow = False

    for record in session.ppg:
        overflow = bool(record.status & 1)
        if previous_seq is not None:
            delta = (record.sample_seq - previous_seq) & 0xFFFFFFFF
            if delta == 0:
                integrity.duplicate_seq += 1
            elif delta > 1:
                integrity.missing_seq += delta - 1
        if previous_time is not None and record.device_time_s <= previous_time:
            integrity.non_monotonic_time += 1

        integrity.rows += 1
        integrity.fifo_overflow_rows += overflow
        if overflow and not previous_overflow:
            integrity.fifo_overflows += 1
        previous_overflow = overflow
        previous_seq = record.sample_seq
        previous_time = record.device_time_s
        frames.append(
            LegacyFrame(
                t_ms=round(record.device_time_s * 1000),
                seq=record.sample_seq,
                board_id=(
                    device_profile.board_id
                    if device_profile is not None
                    else "stm32f411"
                ),
                imu_ok=True,
                ppg_ok=True,
                ax_mg=0,
                ay_mg=0,
                az_mg=0,
                gx_mdps=0,
                gy_mdps=0,
                gz_mdps=0,
                red=record.red,
                ir=record.ir,
                flags=FLAG_PPG_FIFO_OVERFLOW if overflow else 0,
            )
        )
    return frames, integrity


def analyze_binary_file(
    raw_file: Path,
    output_dir: Path | None = None,
    *,
    render_png: bool = True,
) -> AnalysisResult:
    """Load raw bytes, run analysis, persist derived files, and return results."""
    session = load_binary_session(raw_file)
    device_profile = _load_device_profile(raw_file)
    frames, integrity = analysis_frames(session, device_profile)
    result = analyze_frames(
        frames,
        integrity,
        imu_records=session.imu,
        device_profile=device_profile,
    )
    result.summary.update(
        {
            "schema": "ppgbin_analysis_v1",
            "device_profile": (
                asdict(device_profile)
                if device_profile is not None
                else None
            ),
            "ppg_samples": len(session.ppg),
            "imu_samples": len(session.imu),
            "packet_gaps": session.packet_gaps,
            "packet_duplicates": session.packet_duplicates,
            "packet_out_of_order": session.packet_out_of_order,
            "ppg_sample_gaps": session.ppg_sample_gaps,
            "ppg_sample_duplicates": session.ppg_sample_duplicates,
            "ppg_sample_out_of_order": session.ppg_sample_out_of_order,
            "imu_sample_gaps": session.imu_sample_gaps,
            "imu_sample_duplicates": session.imu_sample_duplicates,
            "imu_sample_out_of_order": session.imu_sample_out_of_order,
            "packet_flag_counts": {
                "fifo_overflow": session.packet_fifo_overflow,
                "ring_overflow": session.packet_ring_overflow,
                "transport_stall": session.packet_usb_stall,
                "usb_stall": session.packet_usb_stall,
                "session_incomplete": session.packet_session_incomplete,
            },
            "ppg_fifo_overflow_samples": session.ppg_fifo_overflow_samples,
            "device_timing": {
                "ppg": session.ppg_timing,
                "imu": session.imu_timing,
            },
            "session_timing": session.timing_summary(),
            "decoder": {
                "packets": session.decoder.packets,
                "bytes_discarded": session.decoder.bytes_discarded,
                "invalid_headers": session.decoder.invalid_headers,
                "crc_errors": session.decoder.crc_errors,
            },
        }
    )
    write_report(
        result,
        output_dir or raw_file.with_name("analysis"),
        render_png=render_png,
    )
    return result


def _load_device_profile(
    raw_file: Path,
) -> DeviceProfile | None:
    metadata_path = raw_file.with_name("session.json")
    if not metadata_path.is_file():
        return None
    try:
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    if not isinstance(metadata, dict):
        return None
    return profile_from_metadata(metadata)
