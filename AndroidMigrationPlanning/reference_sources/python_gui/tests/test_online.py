from tools.ppg_monitor.online import OnlineProcessor, visible_y_range
import math

import numpy as np

from tools.ppg_monitor.device_profile import SEEED_NRF52840_LSM6DS3TRC
from tools.ppg_monitor.protocol.constants import (
    EVENT_SESSION_START,
    EVENT_SESSION_STOP,
    PACKET_EVENT,
    PACKET_IMU_BATCH,
    PACKET_PPG_BATCH,
)
from tools.ppg_monitor.protocol.packets import EVENT_STRUCT, ImuSample, Packet, PpgSample


def test_online_processor_keeps_bounded_ppg_window() -> None:
    processor = OnlineProcessor(sample_rate_hz=100.0, window_s=2.0)
    for index in range(500):
        sample = PpgSample(index, 100_000 + index, 100_000 + index)
        packet = Packet(
            PACKET_PPG_BATCH,
            index,
            index * 10_000,
            10_000_000,
            1,
            0,
            sample.pack(),
        )
        snapshot = processor.feed_packet(packet)

    assert len(snapshot.time_s) == 200
    assert not snapshot.ppg_valid
    assert snapshot.ppg_quality_state in {"LOW_PERFUSION", "LOW_CONFIDENCE"}
    assert snapshot.packet_count == 500


def test_online_processor_reports_integrity_and_device_time() -> None:
    processor = OnlineProcessor(sample_rate_hz=200.0)
    start = Packet(
        PACKET_EVENT,
        0,
        0,
        0,
        0,
        0,
        EVENT_STRUCT.pack(EVENT_SESSION_START, 200, 0),
    )
    processor.process_packet(start)
    processor.process_packet(
        Packet(
            PACKET_PPG_BATCH,
            2,
            1_000_000,
            5_000_000,
            2,
            0,
            PpgSample(0, 100_000, 110_000).pack()
            + PpgSample(2, 100_100, 110_100).pack(),
        )
    )
    processor.process_packet(
        Packet(
            PACKET_IMU_BATCH,
            3,
            1_000_000,
            5_000_000,
            1,
            0,
            ImuSample(0, 0, 0, 250, 0, 0, 0).pack(),
        )
    )
    processor.update_decoder_stats(crc_errors=1, bytes_discarded=4)
    snapshot = processor.snapshot()

    assert snapshot.session_active
    assert snapshot.packet_gaps == 1
    assert snapshot.ppg_sample_gaps == 1
    assert snapshot.ppg_samples == 2
    assert snapshot.imu_samples == 1
    assert snapshot.imu_time_s.tolist() == [1.0]
    assert snapshot.crc_errors == 1
    assert snapshot.bytes_discarded == 4
    assert snapshot.motion_rms_m_s2 == 0.0
    assert abs(snapshot.ppg_timing_max_error_us - 5000.0) < 0.001
    assert snapshot.imu_timing_max_error_us == 0.0

    processor.process_packet(
        Packet(
            PACKET_EVENT,
            4,
            2_000_000,
            0,
            0,
            0,
            EVENT_STRUCT.pack(EVENT_SESSION_STOP, 0, 2_000_000),
        )
    )
    assert processor.snapshot().device_stop_received


def test_online_processor_uses_nrf52840_imu_profile() -> None:
    processor = OnlineProcessor(
        sample_rate_hz=200.0,
        device_profile=SEEED_NRF52840_LSM6DS3TRC,
    )
    processor.process_packet(
        Packet(
            PACKET_IMU_BATCH,
            0,
            0,
            4_807_692,
            1,
            0,
            ImuSample(0, 0, 0, 16384, 0, 0, 114).pack(),
        )
    )
    snapshot = processor.snapshot()

    assert abs(snapshot.accel_magnitude[0] - 9.803) < 0.01
    assert abs(snapshot.gyro_magnitude[0] - math.radians(0.9975)) < 0.0001
    assert processor.max_imu_samples == 2080
    assert snapshot.imu_timing_max_error_us == 0.0


def test_online_processor_repairs_marked_lsm6ds3_word_shift() -> None:
    processor = OnlineProcessor(
        sample_rate_hz=200.0,
        device_profile=SEEED_NRF52840_LSM6DS3TRC,
    )
    true_words = np.array([5200, 700, 15500, 20, -35, 12])
    shifted = np.roll(true_words, -5)
    samples = [
        ImuSample(
            2 + index,
            *[int(value) for value in shifted],
            status=1 if index == 0 else 0,
        )
        for index in range(12)
    ]
    processor.process_packet(
        Packet(
            PACKET_IMU_BATCH,
            0,
            0,
            4_807_692,
            len(samples),
            0,
            b"".join(sample.pack() for sample in samples),
        )
    )

    snapshot = processor.snapshot()
    expected_accel = (
        np.linalg.norm(true_words[:3])
        * SEEED_NRF52840_LSM6DS3TRC.accel_g_per_lsb
        * 9.80665
    )
    assert np.allclose(snapshot.accel_magnitude, expected_accel)
    assert snapshot.imu_word_shift_corrections == 1
    assert snapshot.imu_quality_score > 0.65


def test_online_hr_candidate_for_clean_periodic_signal() -> None:
    sample_rate = 200.0
    processor = OnlineProcessor(sample_rate_hz=sample_rate, window_s=10.0)
    packet_seq = 0
    for batch_start in range(0, int(sample_rate * 12), 10):
        samples = []
        for index in range(batch_start, batch_start + 10):
            pulse = int(1500 * math.sin(2 * math.pi * 1.2 * index / sample_rate))
            samples.append(PpgSample(index, 100_000 + pulse, 110_000 + pulse))
        processor.process_packet(
            Packet(
                PACKET_PPG_BATCH,
                packet_seq,
                int(batch_start / sample_rate * 1_000_000),
                5_000_000,
                len(samples),
                0,
                b"".join(sample.pack() for sample in samples),
            )
        )
        packet_seq += 1

    snapshot = processor.snapshot()
    assert snapshot.hr_candidate_bpm is not None
    assert abs(snapshot.hr_candidate_bpm - 72.0) < 3.0
    assert snapshot.hr_confidence >= 0.6
    assert snapshot.ppg_quality_state == "GOOD"
    assert snapshot.ppg_channel in {"RED", "IR"}


def test_visible_y_range_ignores_oldest_two_seconds_only_for_scaling() -> None:
    time_s = np.arange(0.0, 10.0, 0.5)
    values = np.full_like(time_s, 5.0)
    values[0] = 1000.0

    result = visible_y_range(time_s, (values,))

    assert result is not None
    assert result[1] < 10.0
    assert len(values) == len(time_s)
