"""Offline diagnostics and conservative PPG analysis for legacy CSV sessions."""

from __future__ import annotations

import csv
from dataclasses import dataclass
import json
from pathlib import Path

import numpy as np
from scipy import signal

from .device_profile import (
    DeviceProfile,
    STM32F411_ADXL345_ITG3205,
)
from .imu import ImuAnalysis, analyze_imu
from .imu_cardiac import ImuCardiacAnalysis, analyze_imu_cardiac
from .imu_quality import ImuWordOrderRepair, repair_imu_word_order
from .legacy_csv import (
    FLAG_PPG_FIFO_OVERFLOW,
    FLAG_PPG_READ_ERROR,
    FLAG_SAMPLE_SKIPPED,
    IntegritySummary,
    LegacyFrame,
)
from .segmented_pulse import SegmentedPulseAnalysis, analyze_segmented_pulse


@dataclass(frozen=True, slots=True)
class AnalysisResult:
    summary: dict[str, object]
    time_s: np.ndarray
    red: np.ndarray
    ir: np.ndarray
    red_bandpass: np.ndarray
    ir_bandpass: np.ndarray
    peak_indices: np.ndarray
    peak_channel: str | None
    waveform_phase: np.ndarray
    waveform_mean: np.ndarray
    waveform_std: np.ndarray
    waveform_ci95: np.ndarray
    waveform_cycle_count: int
    imu_time_s: np.ndarray
    accel_magnitude: np.ndarray
    gyro_magnitude: np.ndarray
    imu_analysis: ImuAnalysis
    imu_input_quality: ImuWordOrderRepair
    imu_cardiac: ImuCardiacAnalysis
    pulse_analysis: SegmentedPulseAnalysis

    def summary_json(self) -> str:
        return json.dumps(self.summary, indent=2, ensure_ascii=False)


def _estimate_rate(time_s: np.ndarray) -> float:
    if len(time_s) < 2:
        return 0.0
    deltas = np.diff(time_s)
    deltas = deltas[deltas > 0]
    return float(1.0 / np.median(deltas)) if len(deltas) else 0.0


def _bandpass(values: np.ndarray, sample_rate_hz: float) -> np.ndarray:
    if len(values) < 32 or sample_rate_hz <= 8.0:
        return np.zeros_like(values, dtype=float)

    high = min(4.0, sample_rate_hz * 0.45)
    low = 0.6
    if high <= low:
        return np.zeros_like(values, dtype=float)

    sos = signal.butter(3, [low, high], btype="bandpass", fs=sample_rate_hz, output="sos")
    pad_length = min(len(values) - 1, max(12, 3 * len(sos)))
    return signal.sosfiltfilt(sos, values, padlen=pad_length)


def _analyze_imu_records(
    frames: list[LegacyFrame],
    imu_records: object | None,
    device_profile: DeviceProfile | None = None,
) -> tuple[ImuAnalysis, ImuWordOrderRepair]:
    if imu_records is None:
        time_s = np.array([frame.device_time_s for frame in frames], dtype=float)
        raw_values = np.array(
            [
                [
                    frame.ax_mg,
                    frame.ay_mg,
                    frame.az_mg,
                    frame.gx_mdps,
                    frame.gy_mdps,
                    frame.gz_mdps,
                ]
                for frame in frames
            ],
            dtype=float,
        ).reshape((-1, 6))
        repair = repair_imu_word_order(
            raw_values,
            np.array([frame.seq for frame in frames], dtype=np.uint32),
            np.zeros(len(frames), dtype=np.uint16),
            STM32F411_ADXL345_ITG3205,
        )
        accel = repair.values[:, :3] * (9.80665 / 1000.0)
        gyro = repair.values[:, 3:] * (np.pi / (180.0 * 1000.0))
        return (
            analyze_imu(time_s, accel, gyro, _estimate_rate(time_s)),
            repair,
        )

    records = list(imu_records)
    profile = device_profile or STM32F411_ADXL345_ITG3205
    time_s = np.array([record.device_time_s for record in records], dtype=float)
    raw_values = np.array(
        [
            [
                record.ax,
                record.ay,
                record.az,
                record.gx,
                record.gy,
                record.gz,
            ]
            for record in records
        ],
        dtype=float,
    ).reshape((-1, 6))
    repair = repair_imu_word_order(
        raw_values,
        np.array([record.sample_seq for record in records], dtype=np.uint32),
        np.array([record.status for record in records], dtype=np.uint16),
        profile,
    )
    accel = repair.values[:, :3] * (profile.accel_g_per_lsb * 9.80665)
    gyro = repair.values[:, 3:] * (
        profile.gyro_dps_per_lsb * np.pi / 180.0
    )
    return (
        analyze_imu(time_s, accel, gyro, _estimate_rate(time_s)),
        repair,
    )


def analyze_frames(
    frames: list[LegacyFrame],
    integrity: IntegritySummary,
    imu_records: object | None = None,
    device_profile: DeviceProfile | None = None,
) -> AnalysisResult:
    imu_analysis, imu_input_quality = _analyze_imu_records(
        frames,
        imu_records,
        device_profile,
    )
    imu_time_s = imu_analysis.time_s
    accel_magnitude = imu_analysis.accel_magnitude
    gyro_magnitude = imu_analysis.gyro_magnitude
    if not frames:
        empty = np.array([], dtype=float)
        pulse_analysis = analyze_segmented_pulse(
            empty, empty, empty, 0.0
        )
        imu_cardiac = analyze_imu_cardiac(imu_analysis, pulse_analysis)
        return AnalysisResult(
            summary={"integrity": integrity.as_dict(), "warnings": ["no valid CSV frames"]},
            time_s=empty,
            red=empty,
            ir=empty,
            red_bandpass=empty,
            ir_bandpass=empty,
            peak_indices=np.array([], dtype=int),
            peak_channel=None,
            waveform_phase=empty,
            waveform_mean=empty,
            waveform_std=empty,
            waveform_ci95=empty,
            waveform_cycle_count=0,
            imu_time_s=imu_time_s,
            accel_magnitude=accel_magnitude,
            gyro_magnitude=gyro_magnitude,
            imu_analysis=imu_analysis,
            imu_input_quality=imu_input_quality,
            imu_cardiac=imu_cardiac,
            pulse_analysis=pulse_analysis,
        )

    time_s = np.array([frame.device_time_s for frame in frames], dtype=float)
    red = np.array([frame.red if frame.ppg_ok else np.nan for frame in frames], dtype=float)
    ir = np.array([frame.ir if frame.ppg_ok else np.nan for frame in frames], dtype=float)
    valid = np.isfinite(red) & np.isfinite(ir)
    unusable_flags = (
        FLAG_PPG_FIFO_OVERFLOW | FLAG_PPG_READ_ERROR | FLAG_SAMPLE_SKIPPED
    )
    analysis_valid = valid & np.array(
        [(frame.flags & unusable_flags) == 0 for frame in frames],
        dtype=bool,
    )
    break_indices = np.array(
        [
            index
            for index in range(1, len(frames))
            if (
                ((frames[index].seq - frames[index - 1].seq) & 0xFFFFFFFF)
                != 1
                or frames[index].device_time_s
                <= frames[index - 1].device_time_s
                or not analysis_valid[index]
            )
        ],
        dtype=int,
    )
    warnings: list[str] = []

    if not np.any(valid):
        warnings.append("no valid PPG frames")
        red_clean = np.zeros_like(red)
        ir_clean = np.zeros_like(ir)
    else:
        red_fill = float(np.nanmedian(red[valid]))
        ir_fill = float(np.nanmedian(ir[valid]))
        red_clean = np.nan_to_num(red, nan=red_fill)
        ir_clean = np.nan_to_num(ir, nan=ir_fill)

    sample_rate_hz = _estimate_rate(time_s)
    pulse_analysis = analyze_segmented_pulse(
        time_s,
        red_clean,
        ir_clean,
        sample_rate_hz,
        imu_time_s=imu_analysis.time_s,
        linear_accel_magnitude=imu_analysis.linear_accel_magnitude,
        gyro_corrected_magnitude=imu_analysis.gyro_corrected_magnitude,
        imu_quality=imu_input_quality.sample_quality,
        valid_mask=analysis_valid,
        break_indices=break_indices,
    )
    imu_cardiac = analyze_imu_cardiac(imu_analysis, pulse_analysis)
    red_bandpass = pulse_analysis.red_bandpass
    ir_bandpass = pulse_analysis.ir_bandpass
    peak_channel = pulse_analysis.channel
    peaks = pulse_analysis.peak_indices
    peak_rate_bpm = pulse_analysis.bpm
    peak_polarity = pulse_analysis.polarity
    peak_signal = red_bandpass if peak_channel == "RED" else ir_bandpass
    (
        waveform_phase,
        waveform_mean,
        waveform_std,
        waveform_ci95,
        waveform_cycle_count,
    ) = _average_waveform(
        peak_signal,
        peaks,
        segment_ids=pulse_analysis.sample_segment_index,
    )

    if sample_rate_hz == 0.0:
        warnings.append("cannot estimate sample rate")
    if integrity.missing_seq or integrity.non_monotonic_time:
        warnings.append("integrity gaps or non-monotonic time require segmented analysis")
    if integrity.fifo_overflows:
        warnings.append(
            "PPG FIFO overflow detected "
            f"({integrity.fifo_overflows} episode(s), "
            f"{integrity.fifo_overflow_rows} flagged row(s)); results are degraded"
        )
    if not pulse_analysis.segments:
        warnings.append("no stable PPG segment long enough for analysis")

    imu_rate_hz = _estimate_rate(imu_time_s)
    accel_rms = (
        float(np.sqrt(np.mean(imu_analysis.linear_accel_magnitude**2)))
        if len(imu_analysis.linear_accel_magnitude)
        else 0.0
    )
    gyro_rms = (
        float(np.sqrt(np.mean(imu_analysis.gyro_corrected_magnitude**2)))
        if len(imu_analysis.gyro_corrected_magnitude)
        else 0.0
    )
    raw_gyro_rms = (
        float(np.sqrt(np.mean(gyro_magnitude**2))) if len(gyro_magnitude) else 0.0
    )
    ppg_valid_ratio = float(np.mean(valid))
    metric_mask = (
        (pulse_analysis.sample_segment_index >= 0)
        & np.isfinite(red_bandpass)
        & np.isfinite(ir_bandpass)
    )
    if np.any(metric_mask):
        red_ac_dc_percent = float(
            100.0
            * np.sqrt(np.mean(red_bandpass[metric_mask] ** 2))
            / max(abs(np.mean(red_clean[metric_mask])), 1.0)
        )
        ir_ac_dc_percent = float(
            100.0
            * np.sqrt(np.mean(ir_bandpass[metric_mask] ** 2))
            / max(abs(np.mean(ir_clean[metric_mask])), 1.0)
        )
    else:
        red_ac_dc_percent = 0.0
        ir_ac_dc_percent = 0.0
    ratio_of_ratios = (
        red_ac_dc_percent / ir_ac_dc_percent
        if ir_ac_dc_percent > 0.0
        else None
    )
    integrity_ok = not (
        integrity.missing_seq
        or integrity.non_monotonic_time
        or integrity.fifo_overflows
    )
    summary = {
        "schema": "legacy_csv_v1",
        "algorithm_version": "offline-migrated-0.7",
        "sample_rate_hz_estimated": sample_rate_hz,
        "duration_s": float(time_s[-1] - time_s[0]) if len(time_s) > 1 else 0.0,
        "ppg_valid_ratio": ppg_valid_ratio,
        "red_min": float(np.min(red_clean)),
        "red_max": float(np.max(red_clean)),
        "ir_min": float(np.min(ir_clean)),
        "ir_max": float(np.max(ir_clean)),
        "ir_peak_count": int(len(peaks)) if peak_channel == "IR" else 0,
        "selected_peak_count": int(len(peaks)),
        "selected_ppg_channel": peak_channel,
        "ir_peak_polarity": peak_polarity if peak_channel == "IR" else None,
        "selected_peak_polarity": peak_polarity,
        "hr_candidate_bpm": peak_rate_bpm,
        "hr_spectral_candidate_bpm": pulse_analysis.spectral_bpm,
        "hr_confidence": pulse_analysis.confidence,
        "ppg_snr_db": pulse_analysis.snr_db,
        "rr_mad_s": pulse_analysis.rr_mad_s,
        "waveform_cycle_count": waveform_cycle_count,
        "red_ac_dc_percent": red_ac_dc_percent,
        "ir_ac_dc_percent": ir_ac_dc_percent,
        "ratio_of_ratios_unscaled": ratio_of_ratios,
        "ppg_usable": bool(
            ppg_valid_ratio > 0.9
            and integrity_ok
            and peak_rate_bpm is not None
        ),
        "imu_sample_count": int(len(imu_time_s)),
        "imu_sample_rate_hz_estimated": imu_rate_hz,
        "accel_dynamic_m_s2_rms": accel_rms,
        "accel_magnitude_m_s2_min": float(np.min(accel_magnitude)) if len(accel_magnitude) else None,
        "accel_magnitude_m_s2_max": float(np.max(accel_magnitude)) if len(accel_magnitude) else None,
        "gyro_magnitude_rad_s_rms": raw_gyro_rms,
        "gyro_corrected_rad_s_rms": gyro_rms,
        "gyro_magnitude_rad_s_max": float(np.max(gyro_magnitude)) if len(gyro_magnitude) else None,
        "motion_level": "high" if accel_rms > 1.0 or gyro_rms > 0.5 else "low_or_static",
        "pulse_processing": pulse_analysis.summary(),
        "imu_processing": imu_analysis.summary(),
        "imu_input_quality": imu_input_quality.summary(),
        "imu_cardiac": imu_cardiac.summary(),
        "warnings": warnings,
        "integrity": integrity.as_dict(),
    }

    return AnalysisResult(
        summary=summary,
        time_s=time_s,
        red=red_clean,
        ir=ir_clean,
        red_bandpass=red_bandpass,
        ir_bandpass=ir_bandpass,
        peak_indices=peaks,
        peak_channel=peak_channel,
        waveform_phase=waveform_phase,
        waveform_mean=waveform_mean,
        waveform_std=waveform_std,
        waveform_ci95=waveform_ci95,
        waveform_cycle_count=waveform_cycle_count,
        imu_time_s=imu_time_s,
        accel_magnitude=accel_magnitude,
        gyro_magnitude=gyro_magnitude,
        imu_analysis=imu_analysis,
        imu_input_quality=imu_input_quality,
        imu_cardiac=imu_cardiac,
        pulse_analysis=pulse_analysis,
    )


def write_report(
    result: AnalysisResult,
    output_dir: Path,
    *,
    render_png: bool = True,
) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "summary.json").write_text(result.summary_json(), encoding="utf-8")

    if len(result.time_s) == 0:
        return

    np.savez_compressed(
        output_dir / "signals.npz",
        time_s=result.time_s,
        red=result.red,
        ir=result.ir,
        red_bandpass=result.red_bandpass,
        ir_bandpass=result.ir_bandpass,
        ppg_segment_index=result.pulse_analysis.sample_segment_index,
        peak_indices=result.peak_indices,
        peak_channel=result.peak_channel or "",
        pulse_window_start_s=np.array(
            [window.start_s for window in result.pulse_analysis.windows]
        ),
        pulse_window_stop_s=np.array(
            [window.stop_s for window in result.pulse_analysis.windows]
        ),
        pulse_window_accepted=np.array(
            [window.accepted for window in result.pulse_analysis.windows],
            dtype=bool,
        ),
        pulse_window_peak_bpm=np.array(
            [
                window.estimate_for(window.used_channel).peak_bpm
                if window.used_channel is not None
                and window.estimate_for(window.used_channel).peak_bpm is not None
                else np.nan
                for window in result.pulse_analysis.windows
            ]
        ),
        waveform_phase=result.waveform_phase,
        waveform_mean=result.waveform_mean,
        waveform_std=result.waveform_std,
        waveform_ci95=result.waveform_ci95,
        imu_time_s=result.imu_time_s,
        accel_magnitude=result.accel_magnitude,
        gyro_magnitude=result.gyro_magnitude,
        accel_xyz_m_s2=result.imu_analysis.accel_m_s2,
        gyro_xyz_rad_s=result.imu_analysis.gyro_rad_s,
        gravity_xyz_m_s2=result.imu_analysis.gravity_m_s2,
        linear_accel_xyz_m_s2=result.imu_analysis.linear_accel_m_s2,
        linear_accel_magnitude=result.imu_analysis.linear_accel_magnitude,
        gyro_corrected_xyz_rad_s=result.imu_analysis.gyro_corrected_rad_s,
        gyro_corrected_magnitude=result.imu_analysis.gyro_corrected_magnitude,
        imu_input_quality=result.imu_input_quality.sample_quality,
        imu_word_rotation=result.imu_input_quality.rotations,
        accel_mechanical=result.imu_analysis.accel_mechanical,
        gyro_mechanical=result.imu_analysis.gyro_mechanical,
        accel_mechanical_weights=result.imu_analysis.accel_mechanical_weights,
        gyro_mechanical_weights=result.imu_analysis.gyro_mechanical_weights,
        accel_cardiac_axis_scores=result.imu_analysis.accel_cardiac_axis_scores,
        gyro_cardiac_axis_scores=result.imu_analysis.gyro_cardiac_axis_scores,
        imu_cardiac_signal=result.imu_cardiac.signal,
        imu_cardiac_normalized=result.imu_cardiac.normalized_signal,
        imu_cardiac_source=result.imu_cardiac.source or "",
        imu_cardiac_polarity=result.imu_cardiac.polarity or "",
        imu_cardiac_method=result.imu_cardiac.method or "",
        imu_cardiac_peak_indices=result.imu_cardiac.peak_indices,
        imu_cardiac_peak_group_indices=result.imu_cardiac.peak_group_indices,
        imu_cardiac_initial_peak_indices=result.imu_cardiac.initial_peak_indices,
        imu_cardiac_initial_peak_group_indices=(
            result.imu_cardiac.initial_peak_group_indices
        ),
        imu_cardiac_template_similarity=result.imu_cardiac.template_similarity,
        imu_waveform_phase=result.imu_cardiac.waveform_phase,
        imu_waveform_mean=result.imu_cardiac.waveform_mean,
        imu_waveform_std=result.imu_cardiac.waveform_std,
        imu_waveform_ci95=result.imu_cardiac.waveform_ci95,
    )

    with (output_dir / "peaks.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.writer(stream)
        writer.writerow(
            ("sample_index", "device_time_s", "channel", "segment_index")
        )
        for index in result.peak_indices:
            writer.writerow(
                (
                    int(index),
                    float(result.time_s[index]),
                    result.peak_channel,
                    int(result.pulse_analysis.sample_segment_index[index]),
                )
            )

    with (output_dir / "pulse_windows.csv").open(
        "w", newline="", encoding="utf-8"
    ) as stream:
        fieldnames = (
            "segment_index",
            "start_s",
            "stop_s",
            "best_channel",
            "used_channel",
            "peak_bpm",
            "spectral_bpm",
            "confidence",
            "snr_db",
            "red_ac_dc_percent",
            "ir_ac_dc_percent",
            "motion_rms_m_s2",
            "gyro_rms_rad_s",
            "imu_quality_score",
            "accepted",
            "rejection_reason",
        )
        writer = csv.DictWriter(stream, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(
            window.as_dict() for window in result.pulse_analysis.windows
        )

    with (output_dir / "imu_peaks.csv").open(
        "w", newline="", encoding="utf-8"
    ) as stream:
        writer = csv.writer(stream)
        writer.writerow(
            (
                "sample_index",
                "device_time_s",
                "source",
                "polarity",
                "accepted_run_index",
            )
        )
        for index, group in zip(
            result.imu_cardiac.peak_indices,
            result.imu_cardiac.peak_group_indices,
        ):
            writer.writerow(
                (
                    int(index),
                    float(result.imu_time_s[index]),
                    result.imu_cardiac.source,
                    result.imu_cardiac.polarity,
                    int(group),
                )
            )

    with (output_dir / "waveform.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.writer(stream)
        writer.writerow(("phase", "mean", "standard_deviation", "ci95"))
        writer.writerows(
            zip(
                result.waveform_phase,
                result.waveform_mean,
                result.waveform_std,
                result.waveform_ci95,
            )
        )

    with (output_dir / "imu_waveform.csv").open(
        "w", newline="", encoding="utf-8"
    ) as stream:
        writer = csv.writer(stream)
        writer.writerow(("phase", "mean", "standard_deviation", "ci95"))
        writer.writerows(
            zip(
                result.imu_cardiac.waveform_phase,
                result.imu_cardiac.waveform_mean,
                result.imu_cardiac.waveform_std,
                result.imu_cardiac.waveform_ci95,
            )
        )

    if not render_png:
        return

    import matplotlib.pyplot as plt

    figure, axes = plt.subplots(6, 1, figsize=(12, 14), sharex=False)
    axes[0].plot(result.time_s, result.red, label="RED", linewidth=0.8)
    axes[0].plot(result.time_s, result.ir, label="IR", linewidth=0.8)
    axes[0].set_ylabel("ADC counts")
    axes[0].legend()
    axes[1].plot(result.time_s, result.red_bandpass, label="RED bandpass", linewidth=0.8)
    axes[1].plot(result.time_s, result.ir_bandpass, label="IR bandpass", linewidth=0.8)
    axes[1].set_ylabel("AC")
    axes[1].legend()
    peak_signal = (
        result.red_bandpass if result.peak_channel == "RED" else result.ir_bandpass
    )
    axes[2].plot(
        result.time_s,
        peak_signal,
        label=f"{result.peak_channel or 'PPG'} bandpass",
        linewidth=0.8,
    )
    if len(result.peak_indices):
        axes[2].plot(
            result.time_s[result.peak_indices],
            peak_signal[result.peak_indices],
            "ro",
            label="candidate peaks",
        )
    axes[2].set_xlabel("PPG device time (s)")
    axes[2].set_ylabel("AC")
    axes[2].legend()
    if len(result.imu_time_s):
        axes[3].plot(
            result.imu_time_s,
            result.accel_magnitude,
            label="|accel|",
            linewidth=0.8,
        )
        axes[3].plot(
            result.imu_time_s,
            result.imu_analysis.linear_accel_magnitude,
            label="|linear accel|",
            linewidth=0.8,
        )
        axes[3].plot(
            result.imu_time_s,
            result.imu_analysis.accel_mechanical,
            label="accel cardiac axis",
            linewidth=0.8,
        )
        axes[4].plot(
            result.imu_time_s,
            result.gyro_magnitude,
            label="|gyro|",
            linewidth=0.8,
        )
        axes[4].plot(
            result.imu_time_s,
            result.imu_analysis.gyro_corrected_magnitude,
            label="|gyro - static bias|",
            linewidth=0.8,
        )
        axes[4].plot(
            result.imu_time_s,
            result.imu_analysis.gyro_mechanical,
            label="gyro cardiac axis",
            linewidth=0.8,
        )
        if len(result.imu_cardiac.peak_indices):
            cardiac_axes = (
                axes[3] if result.imu_cardiac.source == "accel" else axes[4]
            )
            cardiac_axes.plot(
                result.imu_time_s[result.imu_cardiac.peak_indices],
                result.imu_cardiac.signal[result.imu_cardiac.peak_indices],
                "ro",
                markersize=3,
                label="inertial cardiac candidates",
            )
    axes[3].set_xlabel("IMU device time (s)")
    axes[3].set_ylabel("Acceleration (m/s²)")
    axes[3].legend()
    axes[4].set_xlabel("IMU device time (s)")
    axes[4].set_ylabel("Angular rate (rad/s)")
    axes[4].legend()
    if len(result.waveform_phase):
        axes[5].plot(
            result.waveform_phase,
            result.waveform_mean,
            label=f"Mean {result.peak_channel} pulse",
        )
        axes[5].fill_between(
            result.waveform_phase,
            result.waveform_mean - result.waveform_ci95,
            result.waveform_mean + result.waveform_ci95,
            alpha=0.25,
            label="95% CI",
        )
    axes[5].set_xlabel("Normalized cardiac-cycle phase")
    axes[5].set_ylabel("AC")
    axes[5].legend()
    figure.tight_layout()
    figure.savefig(output_dir / "report.png", dpi=150)
    plt.close(figure)


def _average_waveform(
    values: np.ndarray,
    peaks: np.ndarray,
    points: int = 200,
    segment_ids: np.ndarray | None = None,
) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray, int]:
    empty = np.array([], dtype=float)
    if len(peaks) < 3:
        return empty, empty, empty, empty, 0
    intervals = np.diff(peaks)
    median_interval = float(np.median(intervals))
    if median_interval <= 2.0:
        return empty, empty, empty, empty, 0

    phase = np.linspace(0.0, 1.0, points)
    cycles: list[np.ndarray] = []
    for start, stop in zip(peaks[:-1], peaks[1:]):
        if (
            segment_ids is not None
            and (
                segment_ids[start] < 0
                or segment_ids[start] != segment_ids[stop]
            )
        ):
            continue
        length = stop - start + 1
        if length < 0.5 * median_interval or length > 1.8 * median_interval:
            continue
        cycle = values[start : stop + 1]
        if not np.all(np.isfinite(cycle)):
            continue
        source_phase = np.linspace(0.0, 1.0, len(cycle))
        cycles.append(np.interp(phase, source_phase, cycle))
    if len(cycles) < 2:
        return empty, empty, empty, empty, 0

    matrix = np.vstack(cycles)
    template = np.mean(matrix, axis=0)
    if np.std(template) < 1e-12:
        return empty, empty, empty, empty, 0
    correlations = np.array(
        [
            np.corrcoef(cycle, template)[0, 1]
            if np.std(cycle) >= 1e-12
            else -1.0
            for cycle in matrix
        ]
    )
    keep = correlations >= 0.45
    if np.count_nonzero(keep) < 2 and len(matrix) >= 3:
        keep = np.zeros(len(matrix), dtype=bool)
        keep[np.argsort(correlations)[-max(2, round(len(matrix) / 2)) :]] = True
    kept = matrix[keep]
    if len(kept) == 0:
        return empty, empty, empty, empty, 0
    mean = np.mean(kept, axis=0)
    std = np.std(kept, axis=0, ddof=1) if len(kept) > 1 else np.zeros(points)
    ci95 = 1.96 * std / np.sqrt(len(kept))
    return phase, mean, std, ci95, len(kept)
