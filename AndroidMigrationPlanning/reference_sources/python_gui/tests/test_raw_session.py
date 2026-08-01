from pathlib import Path
import json
import math

from tools.ppg_monitor.protocol.constants import (
    EVENT_SESSION_START,
    EVENT_SESSION_STOP,
    FLAG_FIFO_OVERFLOW,
    FLAG_RING_OVERFLOW,
    PACKET_EVENT,
    PACKET_IMU_BATCH,
    PACKET_PPG_BATCH,
)
from tools.ppg_monitor.protocol.packets import (
    EVENT_STRUCT,
    ImuSample,
    Packet,
    PpgSample,
    encode_packet,
)
from tools.ppg_monitor.raw_format import RawWriter
from tools.ppg_monitor.session import load_binary_session
from tools.ppg_monitor.binary_analysis import (
    analysis_frames,
    analyze_binary_file,
)


def test_raw_session_loader_decodes_typed_ppg_samples(tmp_path: Path) -> None:
    packet = Packet(
        packet_type=PACKET_PPG_BATCH,
        packet_seq=0,
        t0_us=1_000_000,
        sample_period_ns=10_000_000,
        sample_count=2,
        packet_flags=FLAG_RING_OVERFLOW,
        payload=PpgSample(0, 100, 200).pack()
        + PpgSample(1, 110, 210, status=FLAG_FIFO_OVERFLOW).pack(),
    )
    raw_path = tmp_path / "raw.ppgbin"
    with RawWriter(raw_path) as writer:
        writer.write(b"noise" + encode_packet(packet), host_time_ns=123)

    session = load_binary_session(raw_path)

    assert len(session.ppg) == 2
    assert session.ppg[0].red == 100
    assert session.ppg[1].device_time_s == 1.01
    assert session.decoder.packets == 1
    assert session.decoder.bytes_discarded == 5
    assert session.packet_ring_overflow == 1
    assert session.ppg_fifo_overflow_samples == 1
    assert session.ppg_timing["expected_period_us"] == 10_000.0
    assert abs(
        session.timing_summary()["imu"]["nominal_rate_hz"] - 208.0
    ) < 0.001

    frames, integrity = analysis_frames(session)
    assert len(frames) == 2
    assert integrity.rows == 2
    assert integrity.missing_seq == 0

    output_dir = tmp_path / "analysis"
    result = analyze_binary_file(
        raw_path,
        output_dir,
        render_png=False,
    )
    assert result.summary["schema"] == "ppgbin_analysis_v1"
    assert (output_dir / "summary.json").is_file()
    assert (output_dir / "signals.npz").is_file()
    assert (output_dir / "pulse_windows.csv").is_file()


def test_raw_session_loader_uses_imu_packet_period(tmp_path: Path) -> None:
    packet = Packet(
        packet_type=PACKET_IMU_BATCH,
        packet_seq=0,
        t0_us=1_000_000,
        sample_period_ns=4_807_692,
        sample_count=2,
        packet_flags=0,
        payload=ImuSample(0, 0, 0, 16384, 0, 0, 0).pack()
        + ImuSample(1, 1, 2, 16383, 3, 4, 5).pack(),
    )
    raw_path = tmp_path / "imu.ppgbin"
    with RawWriter(raw_path) as writer:
        writer.write(encode_packet(packet), host_time_ns=123)

    session = load_binary_session(raw_path)

    assert len(session.imu) == 2
    assert session.imu_timing["expected_period_us"] == 4807.692
    assert session.imu_timing["max_abs_error_us"] < 0.001

    (tmp_path / "session.json").write_text(
        json.dumps(
            {
                "handshake": {
                    "board_id": "seeed_nrf52840",
                }
            }
        ),
        encoding="utf-8",
    )
    result = analyze_binary_file(
        raw_path,
        tmp_path / "imu_analysis",
        render_png=False,
    )

    assert abs(result.accel_magnitude[0] - 9.803) < 0.01
    expected_gyro = (
        math.sqrt(3 * 3 + 4 * 4 + 5 * 5)
        * 0.00875
        * math.pi
        / 180.0
    )
    assert abs(result.gyro_magnitude[1] - expected_gyro) < 0.0001
    assert result.summary["device_profile"]["board_id"] == "seeed_nrf52840"


def test_binary_analysis_reports_session_count_rates_and_timeline_offset(
    tmp_path: Path,
) -> None:
    start_us = 1_000_000
    stop_us = 1_010_000
    packets = (
        Packet(
            packet_type=PACKET_EVENT,
            packet_seq=0,
            t0_us=start_us,
            sample_period_ns=0,
            sample_count=0,
            packet_flags=0,
            payload=EVENT_STRUCT.pack(
                EVENT_SESSION_START,
                200,
                start_us,
            ),
        ),
        Packet(
            packet_type=PACKET_PPG_BATCH,
            packet_seq=1,
            t0_us=start_us,
            sample_period_ns=5_000_000,
            sample_count=2,
            packet_flags=0,
            payload=(
                PpgSample(0, 100, 200).pack()
                + PpgSample(1, 110, 210).pack()
            ),
        ),
        Packet(
            packet_type=PACKET_IMU_BATCH,
            packet_seq=2,
            t0_us=start_us,
            sample_period_ns=4_807_692,
            sample_count=2,
            packet_flags=0,
            payload=(
                ImuSample(0, 0, 0, 16384, 0, 0, 0).pack()
                + ImuSample(1, 1, 2, 16383, 3, 4, 5).pack()
            ),
        ),
        Packet(
            packet_type=PACKET_EVENT,
            packet_seq=3,
            t0_us=stop_us,
            sample_period_ns=0,
            sample_count=0,
            packet_flags=0,
            payload=EVENT_STRUCT.pack(
                EVENT_SESSION_STOP,
                0,
                stop_us,
            ),
        ),
    )
    raw_path = tmp_path / "timed.ppgbin"
    with RawWriter(raw_path) as writer:
        writer.write(
            b"".join(encode_packet(packet) for packet in packets),
            host_time_ns=123,
        )

    session = load_binary_session(raw_path)
    timing = session.timing_summary()

    assert session.session_duration_s == 0.01
    assert timing["nominal_ppg_rate_hz"] == 200
    assert timing["ppg"]["session_count_rate_hz"] == 200.0
    assert timing["imu"]["session_count_rate_hz"] == 200.0
    assert abs(timing["imu"]["nominal_rate_hz"] - 208.0) < 0.001
    assert timing["imu"]["timeline_end_offset_us"] < 0.0

    result = analyze_binary_file(
        raw_path,
        tmp_path / "timed_analysis",
        render_png=False,
    )
    assert result.summary["session_timing"] == timing
