"""Capture Protocol v1 serial or BLE bytes with raw-first session storage."""

from __future__ import annotations

import argparse
from dataclasses import asdict
from datetime import datetime, timezone
import json
from pathlib import Path
import time
import secrets

import serial

from ppg_monitor.ble_nus import BleNusError
from ppg_monitor.capture_source import (
    add_source_arguments,
    open_source,
    source_metadata,
    source_spec_from_args,
)
from ppg_monitor.device_profile import profile_for_board
from ppg_monitor.protocol.constants import (
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
from ppg_monitor.protocol.decoder import PacketDecoder
from ppg_monitor.protocol.packets import decode_batch_samples, decode_event
from ppg_monitor.integrity import SequenceTracker
from ppg_monitor.raw_format import RawWriter
from ppg_monitor.transport import (
    make_start_binary,
    make_stop,
    perform_handshake,
    read_timestamped_chunk,
)


PROJECT_ROOT = Path(__file__).resolve().parents[1]


def default_session_dir() -> Path:
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    return PROJECT_ROOT / "captures" / f"Session_{timestamp}"


def main() -> int:
    parser = argparse.ArgumentParser()
    add_source_arguments(parser)
    parser.add_argument("-o", "--session-dir", type=Path, default=None)
    parser.add_argument("--seconds", type=float, default=0.0)
    parser.add_argument("--status-hz", type=float, default=1.0)
    parser.add_argument("--stop-timeout", type=float, default=15.0)
    args = parser.parse_args()
    source_spec = source_spec_from_args(parser, args)
    if args.seconds < 0 or args.stop_timeout <= 0:
        parser.error("capture duration must be non-negative and timeout positive")

    session_dir = args.session_dir or default_session_dir()
    session_dir.mkdir(parents=True, exist_ok=True)
    raw_path = session_dir / "raw.ppgbin"
    start_utc = datetime.now(timezone.utc).isoformat()
    status_period = 1.0 / args.status_hz if args.status_hz > 0 else None
    next_status = time.monotonic()
    next_flush = time.monotonic() + 1.0
    decoder = PacketDecoder()
    packets = 0
    ppg_samples = 0
    imu_samples = 0
    packet_sequence = SequenceTracker()
    ppg_sequence = SequenceTracker()
    imu_sequence = SequenceTracker()
    packet_fifo_overflow = 0
    packet_ring_overflow = 0
    packet_usb_stall = 0
    packet_session_incomplete = 0
    ppg_fifo_overflow_samples = 0
    stop_reason = "running"
    complete = False
    device_stop_received = False
    protocol_warning_shown = False
    handshake = None
    device_profile = None
    link_metadata = None
    raw_created = False
    automatic_start_sent = False
    nonce = secrets.randbelow(0xFFFFFFFF)

    def process_chunk(data: bytes, host_time_ns: int, raw: RawWriter) -> None:
        nonlocal packets
        nonlocal ppg_samples
        nonlocal imu_samples
        nonlocal packet_fifo_overflow
        nonlocal packet_ring_overflow
        nonlocal packet_usb_stall
        nonlocal packet_session_incomplete
        nonlocal ppg_fifo_overflow_samples
        nonlocal device_stop_received
        nonlocal stop_reason

        raw.write(data, host_time_ns)
        for packet in decoder.feed(data):
            packets += 1
            packet_sequence.observe(packet.packet_seq)
            packet_fifo_overflow += bool(
                packet.packet_flags & FLAG_FIFO_OVERFLOW
            )
            packet_ring_overflow += bool(
                packet.packet_flags & FLAG_RING_OVERFLOW
            )
            packet_usb_stall += bool(
                packet.packet_flags & FLAG_USB_STALL
            )
            packet_session_incomplete += bool(
                packet.packet_flags & FLAG_SESSION_INCOMPLETE
            )
            if packet.packet_type == PACKET_EVENT:
                event = decode_event(packet)
                if event.event_code == EVENT_SESSION_START:
                    print(f"Board recording started at {event.value}Hz.")
                elif event.event_code == EVENT_SESSION_STOP:
                    print("Board recording stopped.")
                    device_stop_received = True
                    stop_reason = "device_stop"
                continue
            try:
                samples = decode_batch_samples(packet)
            except ValueError:
                samples = []
            if packet.packet_type == PACKET_PPG_BATCH:
                ppg_samples += len(samples)
                for sample in samples:
                    ppg_sequence.observe(sample.sample_seq)
                    ppg_fifo_overflow_samples += bool(
                        sample.status & FLAG_FIFO_OVERFLOW
                    )
            elif packet.packet_type == PACKET_IMU_BATCH:
                imu_samples += len(samples)
                for sample in samples:
                    imu_sequence.observe(sample.sample_seq)

    try:
        with open_source(source_spec) as connection:
            link_metadata = source_metadata(source_spec, connection)
            handshake = perform_handshake(
                connection,
                source_spec.requested_transport,
                nonce=nonce,
                timeout_s=source_spec.command_timeout_s,
            )
            if handshake.confirmed_transport is None:
                if source_spec.is_ble or source_spec.transport == "bluetooth":
                    stop_reason = "handshake_failed"
                    print(
                        "ERROR: device handshake timed out. Check that the "
                        "target is connected, TX notifications are enabled, "
                        "and no other host owns the link."
                    )
                    return 2
                print("WARNING: device handshake timed out; using USB fallback.")
            else:
                device_profile = profile_for_board(handshake.board_id)
                print(
                    f"Handshake OK: board={handshake.board_id} "
                    f"rtt={handshake.round_trip_ms:.1f}ms"
                )
            if source_spec.automatic_binary_control:
                connection.write(make_start_binary(nonce))
                automatic_start_sent = True

            capture_started = time.monotonic()
            deadline = (
                capture_started + args.seconds
                if args.seconds > 0
                else None
            )
            with RawWriter(raw_path) as raw:
                raw_created = True
                print(
                    f"Capturing binary {source_spec.label} -> {session_dir}"
                )
                if source_spec.automatic_binary_control:
                    print("BLE BINARY capture started; Ctrl+C stops cleanly.")
                else:
                    print(
                        "Short-press the board KEY for Binary 200Hz; press KEY "
                        "again to stop; Ctrl+C exits."
                    )
                try:
                    while deadline is None or time.monotonic() < deadline:
                        received = read_timestamped_chunk(connection)
                        if received.data:
                            process_chunk(
                                received.data,
                                received.host_time_ns,
                                raw,
                            )
                        if device_stop_received:
                            break

                        now = time.monotonic()
                        if status_period is not None and now >= next_status:
                            print(
                                f"packets={packets} ppg={ppg_samples} "
                                f"imu={imu_samples} "
                                f"packet_gaps={packet_sequence.missing} "
                                f"ppg_gaps={ppg_sequence.missing} "
                                f"imu_gaps={imu_sequence.missing} "
                                f"overflow={ppg_fifo_overflow_samples} "
                                f"ring={packet_ring_overflow} "
                                f"crc={decoder.stats.crc_errors}"
                            )
                            if (
                                packets == 0
                                and decoder.stats.bytes_discarded >= 128
                                and not protocol_warning_shown
                            ):
                                print(
                                    "WARNING: received non-protocol bytes. "
                                    "The board is likely in CSV mode."
                                )
                                protocol_warning_shown = True
                            next_status = now + status_period
                        if now >= next_flush:
                            raw.flush()
                            next_flush = now + 1.0
                except KeyboardInterrupt:
                    print("\nCapture stop requested by user.")
                    stop_reason = "host_interrupt"

                if (
                    source_spec.automatic_binary_control
                    and automatic_start_sent
                    and not device_stop_received
                ):
                    connection.write(make_stop(nonce))
                    stop_deadline = time.monotonic() + args.stop_timeout
                    while (
                        not device_stop_received
                        and time.monotonic() < stop_deadline
                    ):
                        received = read_timestamped_chunk(connection)
                        if received.data:
                            process_chunk(
                                received.data,
                                received.host_time_ns,
                                raw,
                            )
                    if not device_stop_received:
                        stop_reason = "stop_timeout"
                elif not device_stop_received and stop_reason == "running":
                    stop_reason = "deadline"
                complete = device_stop_received
    except KeyboardInterrupt:
        print("\nCapture stopped by user.")
        stop_reason = "host_interrupt"
        complete = device_stop_received
    except (serial.SerialException, BleNusError) as error:
        print(f"\nSource error: {error}")
        stop_reason = "source_error"
        return 1
    finally:
        metadata = {
            "schema": "ppgbin_session_v1",
            "requested_mode": "binary_200hz",
            "requested_transport": source_spec.requested_transport,
            "source": link_metadata,
            "baud": None if source_spec.is_ble else source_spec.baud,
            "handshake": None
            if handshake is None
            else {
                "confirmed_transport": handshake.confirmed_transport,
                "board_id": handshake.board_id,
                "device_baud": handshake.baud,
                "round_trip_ms": handshake.round_trip_ms,
            },
            "device_profile": (
                asdict(device_profile)
                if device_profile is not None
                else None
            ),
            "started_utc": start_utc,
            "ended_utc": datetime.now(timezone.utc).isoformat(),
            "complete": complete,
            "stop_reason": stop_reason,
            "packets": packets,
            "ppg_samples": ppg_samples,
            "imu_samples": imu_samples,
            "packet_gaps": packet_sequence.missing,
            "packet_duplicates": packet_sequence.duplicates,
            "packet_out_of_order": packet_sequence.out_of_order,
            "ppg_sample_gaps": ppg_sequence.missing,
            "ppg_sample_duplicates": ppg_sequence.duplicates,
            "ppg_sample_out_of_order": ppg_sequence.out_of_order,
            "imu_sample_gaps": imu_sequence.missing,
            "imu_sample_duplicates": imu_sequence.duplicates,
            "imu_sample_out_of_order": imu_sequence.out_of_order,
            "packet_flag_counts": {
                "fifo_overflow": packet_fifo_overflow,
                "ring_overflow": packet_ring_overflow,
                "transport_stall": packet_usb_stall,
                "usb_stall": packet_usb_stall,
                "session_incomplete": packet_session_incomplete,
            },
            "ppg_fifo_overflow_samples": ppg_fifo_overflow_samples,
            "device_stop_received": device_stop_received,
            "decoder": {
                "bytes_discarded": decoder.stats.bytes_discarded,
                "invalid_headers": decoder.stats.invalid_headers,
                "crc_errors": decoder.stats.crc_errors,
            },
            "raw_file": raw_path.name if raw_created else None,
        }
        (session_dir / "session.json").write_text(
            json.dumps(metadata, indent=2),
            encoding="utf-8",
        )

    print(f"Session written to {session_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
