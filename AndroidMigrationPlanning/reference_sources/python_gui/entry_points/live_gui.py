"""Live Binary 200 Hz monitor with raw-first session storage."""

from __future__ import annotations

import argparse
from dataclasses import asdict
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import secrets
import sys
import threading
import time

import serial

from ppg_monitor.ble_nus import BleNusError
from ppg_monitor.capture_source import (
    SourceSpec,
    add_source_arguments,
    open_source,
    source_metadata,
    source_spec_from_args,
)
from ppg_monitor.device_profile import profile_for_board
from ppg_monitor.online import (
    OnlineProcessor,
    OnlineSnapshot,
    visible_y_range,
)
from ppg_monitor.protocol.decoder import PacketDecoder
from ppg_monitor.raw_format import RawWriter
from ppg_monitor.transport import (
    make_start_binary,
    perform_handshake,
    read_timestamped_chunk,
)
from ppg_monitor.transport import ReceivedChunk, stop_binary_and_drain


def run(
    source_spec: SourceSpec,
    session_dir: Path,
    sample_rate_hz: float,
    refresh_hz: float,
    stop_timeout_s: float,
) -> int:
    try:
        from PySide6 import QtCore, QtGui, QtWidgets
        import pyqtgraph as pg
        from ppg_monitor.analysis_gui import AnalysisWorker, AnalysisWorkspace
    except ImportError as error:
        print(
            "Live GUI dependencies are missing. Update tools/environment.yml "
            "and recreate/update the ppg-monitor environment."
        )
        print(error)
        return 2

    class Reader(QtCore.QThread):
        snapshot_ready = QtCore.Signal(object)
        notice = QtCore.Signal(str)
        error = QtCore.Signal(str)
        capture_done = QtCore.Signal(str)

        def __init__(self) -> None:
            super().__init__()
            self._stop_requested = threading.Event()

        def request_stop(self) -> None:
            self._stop_requested.set()

        def run(self) -> None:
            decoder = PacketDecoder()
            processor = OnlineProcessor(sample_rate_hz)
            session_dir.mkdir(parents=True, exist_ok=True)
            started_utc = datetime.now(timezone.utc).isoformat()
            raw_path = session_dir / "raw.ppgbin"
            raw_created = False
            handshake = None
            device_profile = None
            link_metadata = None
            stop_reason = "host_close"
            error_text: str | None = None
            next_snapshot = time.monotonic()
            next_flush = time.monotonic() + 1.0
            refresh_period = 1.0 / max(refresh_hz, 1.0)
            nonce = secrets.randbelow(0xFFFFFFFF)
            automatic_start_sent = False

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
                        if (
                            source_spec.is_ble
                            or source_spec.transport == "bluetooth"
                        ):
                            stop_reason = "handshake_failed"
                            error_text = "Device handshake timed out"
                            self.error.emit(error_text)
                            return
                        self.notice.emit(
                            "Handshake timed out; waiting on the USB fallback link."
                        )
                    else:
                        device_profile = profile_for_board(handshake.board_id)
                        if device_profile is not None:
                            processor.set_device_profile(device_profile)
                        self.notice.emit(
                            f"Handshake OK: {handshake.board_id}, "
                            f"RTT {handshake.round_trip_ms:.1f} ms"
                        )
                    if source_spec.automatic_binary_control:
                        connection.write(make_start_binary(nonce))
                        automatic_start_sent = True
                        self.notice.emit(
                            "BLE connected; BINARY capture started."
                        )

                    with RawWriter(raw_path) as raw:
                        raw_created = True

                        def consume_received(
                            received: ReceivedChunk,
                        ) -> None:
                            raw.write(
                                received.data,
                                received.host_time_ns,
                            )
                            for packet in decoder.feed(received.data):
                                processor.process_packet(packet)

                        while not self._stop_requested.is_set():
                            received = read_timestamped_chunk(connection)
                            if not received.data:
                                continue
                            consume_received(received)

                            processor.update_decoder_stats(
                                crc_errors=decoder.stats.crc_errors,
                                bytes_discarded=decoder.stats.bytes_discarded,
                            )
                            now = time.monotonic()
                            if now >= next_snapshot or processor.device_stop_received:
                                self.snapshot_ready.emit(processor.snapshot())
                                next_snapshot = now + refresh_period
                            if now >= next_flush:
                                raw.flush()
                                next_flush = now + 1.0
                            if processor.device_stop_received:
                                stop_reason = "device_stop"
                                break

                        if (
                            source_spec.automatic_binary_control
                            and automatic_start_sent
                            and not processor.device_stop_received
                        ):
                            stopped = stop_binary_and_drain(
                                connection,
                                nonce,
                                timeout_s=stop_timeout_s,
                                consume=consume_received,
                                stopped=lambda: processor.device_stop_received,
                            )
                            stop_reason = (
                                "device_stop" if stopped else "stop_timeout"
                            )
            except (serial.SerialException, BleNusError) as exc:
                stop_reason = "source_error"
                error_text = str(exc)
                self.error.emit(error_text)
            finally:
                processor.update_decoder_stats(
                    crc_errors=decoder.stats.crc_errors,
                    bytes_discarded=decoder.stats.bytes_discarded,
                )
                snapshot = processor.snapshot()
                self.snapshot_ready.emit(snapshot)
                metadata = {
                    "schema": "ppgbin_live_session_v1",
                    "requested_mode": "binary_200hz",
                    "requested_transport": (
                        source_spec.requested_transport
                    ),
                    "source": link_metadata,
                    "baud": (
                        None if source_spec.is_ble else source_spec.baud
                    ),
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
                    "started_utc": started_utc,
                    "ended_utc": datetime.now(timezone.utc).isoformat(),
                    "complete": snapshot.device_stop_received,
                    "stop_reason": stop_reason,
                    "packets": snapshot.packet_count,
                    "ppg_samples": snapshot.ppg_samples,
                    "imu_samples": snapshot.imu_samples,
                    "packet_gaps": snapshot.packet_gaps,
                    "ppg_sample_gaps": snapshot.ppg_sample_gaps,
                    "imu_sample_gaps": snapshot.imu_sample_gaps,
                    "overflow_events": snapshot.overflow_events,
                    "decoder": {
                        "bytes_discarded": snapshot.bytes_discarded,
                        "crc_errors": snapshot.crc_errors,
                    },
                    "device_timing": {
                        "ppg_max_abs_error_us": snapshot.ppg_timing_max_error_us,
                        "imu_max_abs_error_us": snapshot.imu_timing_max_error_us,
                    },
                    "last_hr_candidate_bpm": snapshot.hr_candidate_bpm,
                    "last_hr_spectral_bpm": snapshot.hr_spectral_bpm,
                    "last_hr_confidence": snapshot.hr_confidence,
                    "last_ppg_channel": snapshot.ppg_channel,
                    "last_ppg_quality_state": snapshot.ppg_quality_state,
                    "last_ppg_snr_db": (
                        snapshot.ppg_snr_db
                        if math.isfinite(snapshot.ppg_snr_db)
                        else None
                    ),
                    "last_red_ac_dc_percent": snapshot.red_ac_dc_percent,
                    "last_ir_ac_dc_percent": snapshot.ir_ac_dc_percent,
                    "last_motion_rms_m_s2": snapshot.motion_rms_m_s2,
                    "last_motion_correlation": snapshot.motion_correlation,
                    "last_imu_quality_score": snapshot.imu_quality_score,
                    "imu_word_shift_corrections": (
                        snapshot.imu_word_shift_corrections
                    ),
                    "error": error_text,
                    "raw_file": raw_path.name if raw_created else None,
                }
                (session_dir / "session.json").write_text(
                    json.dumps(metadata, indent=2),
                    encoding="utf-8",
                )
                self.capture_done.emit(stop_reason)

    class Window(QtWidgets.QMainWindow):
        def __init__(self) -> None:
            super().__init__()
            self.setWindowTitle(f"PPG Monitor - {source_spec.label}")
            self.resize(1400, 950)
            self.tabs = QtWidgets.QTabWidget()
            live_page = QtWidgets.QWidget()
            layout = QtWidgets.QVBoxLayout(live_page)
            self.status = QtWidgets.QLabel(
                "Connecting; BLE capture starts automatically after READY."
            )
            self.status.setFont(QtGui.QFont("Segoe UI", 11))
            self.metrics = QtWidgets.QLabel("Signal quality: warming up")
            self.integrity = QtWidgets.QLabel("Integrity: waiting")
            layout.addWidget(self.status)
            layout.addWidget(self.metrics)
            layout.addWidget(self.integrity)
            actions = QtWidgets.QHBoxLayout()
            self.stop_button = QtWidgets.QPushButton(
                "Stop and finalize capture"
            )
            self.stop_button.setToolTip(
                "For BLE, send the protocol STOP command and keep draining "
                "notifications through SESSION_STOP before closing raw.ppgbin."
            )
            self.stop_button.clicked.connect(self.stop_capture)
            self.auto_analyze = QtWidgets.QCheckBox(
                "Analyze automatically after stop"
            )
            self.auto_analyze.setChecked(True)
            self.analyze_button = QtWidgets.QPushButton(
                "Analyze captured session"
            )
            self.analyze_button.setEnabled(False)
            self.analyze_button.clicked.connect(self.analyze_current_session)
            self.open_analysis_button = QtWidgets.QPushButton(
                "Open existing session"
            )
            self.open_analysis_button.clicked.connect(
                self.open_existing_session
            )
            actions.addWidget(self.stop_button)
            actions.addWidget(self.auto_analyze)
            actions.addSpacing(16)
            actions.addWidget(self.analyze_button)
            actions.addWidget(self.open_analysis_button)
            actions.addStretch(1)
            layout.addLayout(actions)

            self.ppg_plot = pg.PlotWidget(title="PPG band-pass (0.6-4 Hz)")
            self.accel_plot = pg.PlotWidget(title="Acceleration magnitude")
            self.gyro_plot = pg.PlotWidget(title="Angular-rate magnitude")
            self.ppg_plot.addLegend()
            self.accel_plot.addLegend()
            self.gyro_plot.addLegend()
            self.ppg_plot.showGrid(x=True, y=True, alpha=0.25)
            self.accel_plot.showGrid(x=True, y=True, alpha=0.25)
            self.gyro_plot.showGrid(x=True, y=True, alpha=0.25)
            self.ppg_plot.setLabel("bottom", "Device time", units="s")
            self.accel_plot.setLabel("bottom", "Device time", units="s")
            self.accel_plot.setLabel("left", "Acceleration", units="m/s²")
            self.gyro_plot.setLabel("bottom", "Device time", units="s")
            self.gyro_plot.setLabel("left", "Angular rate", units="rad/s")
            self.red_curve = self.ppg_plot.plot(pen="#ff5555", name="RED AC")
            self.ir_curve = self.ppg_plot.plot(pen="#f1c40f", name="IR AC")
            self.accel_curve = self.accel_plot.plot(
                pen="#45caff", name="|accel|"
            )
            self.gyro_curve = self.gyro_plot.plot(
                pen="#dd77ff", name="|gyro|"
            )
            layout.addWidget(self.ppg_plot, stretch=3)
            layout.addWidget(self.accel_plot, stretch=1)
            layout.addWidget(self.gyro_plot, stretch=1)
            self.analysis_workspace = AnalysisWorkspace()
            self.tabs.addTab(live_page, "Live capture")
            self.tabs.addTab(self.analysis_workspace, "Offline analysis")
            self.setCentralWidget(self.tabs)
            self.analysis_worker: AnalysisWorker | None = None

            self.reader = Reader()
            self.reader.snapshot_ready.connect(self.update_snapshot)
            self.reader.notice.connect(self.status.setText)
            self.reader.error.connect(
                lambda text: self.status.setText(f"Capture error: {text}")
            )
            self.reader.capture_done.connect(self.capture_finished)
            self.reader.start()

        @QtCore.Slot()
        def stop_capture(self) -> None:
            if not self.reader.isRunning():
                return
            self.stop_button.setEnabled(False)
            self.status.setText(
                "Stopping capture; waiting for the board's SESSION_STOP..."
            )
            self.reader.request_stop()

        @QtCore.Slot(object)
        def update_snapshot(self, snapshot: OnlineSnapshot) -> None:
            if len(snapshot.time_s):
                origin = snapshot.time_s[0]
                ppg_time = snapshot.time_s - origin
                self.red_curve.setData(ppg_time, snapshot.red_ac)
                self.ir_curve.setData(ppg_time, snapshot.ir_ac)
                ppg_range = visible_y_range(
                    ppg_time,
                    (snapshot.red_ac, snapshot.ir_ac),
                )
                if ppg_range is not None:
                    self.ppg_plot.setYRange(*ppg_range, padding=0.0)
            if len(snapshot.imu_time_s):
                origin = snapshot.imu_time_s[0]
                imu_time = snapshot.imu_time_s - origin
                self.accel_curve.setData(imu_time, snapshot.accel_magnitude)
                self.gyro_curve.setData(imu_time, snapshot.gyro_magnitude)
                accel_range = visible_y_range(
                    imu_time,
                    (snapshot.accel_magnitude,),
                )
                if accel_range is not None:
                    self.accel_plot.setYRange(
                        *accel_range,
                        padding=0.0,
                    )
                gyro_range = visible_y_range(
                    imu_time,
                    (snapshot.gyro_magnitude,),
                )
                if gyro_range is not None:
                    self.gyro_plot.setYRange(
                        *gyro_range,
                        padding=0.0,
                    )

            hr = (
                f"{snapshot.hr_candidate_bpm:.1f} bpm "
                f"({snapshot.hr_confidence:.0%})"
                if snapshot.hr_candidate_bpm is not None
                else "warming / low quality"
            )
            state = "recording" if snapshot.session_active else "waiting"
            self.status.setText(
                f"{state} | HR candidate: {hr} | "
                f"motion RMS: {snapshot.motion_rms_m_s2:.2f} m/s² | "
                f"PPG/IMU: {snapshot.ppg_samples}/{snapshot.imu_samples}"
            )
            spectral = (
                f"{snapshot.hr_spectral_bpm:.1f} bpm"
                if snapshot.hr_spectral_bpm is not None
                else "n/a"
            )
            snr = (
                f"{snapshot.ppg_snr_db:.1f} dB"
                if math.isfinite(snapshot.ppg_snr_db)
                else "n/a"
            )
            self.metrics.setText(
                f"Signal quality: {snapshot.ppg_quality_state} | selected "
                f"{snapshot.ppg_channel or 'n/a'} | Welch {spectral} | "
                f"SNR {snr} | RED/IR AC/DC "
                f"{snapshot.red_ac_dc_percent:.3f}/"
                f"{snapshot.ir_ac_dc_percent:.3f}% | motion corr "
                f"{snapshot.motion_correlation:.2f} | IMU quality "
                f"{snapshot.imu_quality_score:.0%} | word repairs "
                f"{snapshot.imu_word_shift_corrections}"
            )
            total_gaps = (
                snapshot.packet_gaps
                + snapshot.ppg_sample_gaps
                + snapshot.imu_sample_gaps
            )
            integrity_ok = (
                total_gaps == 0
                and snapshot.crc_errors == 0
                and snapshot.overflow_events == 0
                and snapshot.bytes_discarded == 0
            )
            self.integrity.setText(
                f"Integrity: {'OK' if integrity_ok else 'CHECK'} | "
                f"packet/PPG/IMU gaps "
                f"{snapshot.packet_gaps}/{snapshot.ppg_sample_gaps}/"
                f"{snapshot.imu_sample_gaps} | CRC {snapshot.crc_errors} | "
                f"overflow {snapshot.overflow_events} | discarded "
                f"{snapshot.bytes_discarded} B | timing max "
                f"{snapshot.ppg_timing_max_error_us:.1f}/"
                f"{snapshot.imu_timing_max_error_us:.1f} us"
            )

        @QtCore.Slot(str)
        def capture_finished(self, reason: str) -> None:
            self.stop_button.setEnabled(False)
            raw_exists = (session_dir / "raw.ppgbin").is_file()
            if reason == "device_stop":
                self.status.setText(
                    f"Board stopped; session saved to {session_dir}. "
                    "Choose Analyze captured session or close the window."
                )
            elif raw_exists:
                self.status.setText(
                    f"Capture ended ({reason}); saved to {session_dir}."
                )
            self.analyze_button.setEnabled(raw_exists)
            if (
                reason == "device_stop"
                and raw_exists
                and self.auto_analyze.isChecked()
            ):
                QtCore.QTimer.singleShot(
                    0,
                    self.analyze_current_session,
                )

        @QtCore.Slot()
        def analyze_current_session(self) -> None:
            self._start_analysis(session_dir / "raw.ppgbin")

        @QtCore.Slot()
        def open_existing_session(self) -> None:
            filename, _ = QtWidgets.QFileDialog.getOpenFileName(
                self,
                "Open binary PPG session",
                "captures",
                "PPG binary session (raw.ppgbin *.ppgbin)",
            )
            if filename:
                self._start_analysis(Path(filename))

        def _start_analysis(self, raw_file: Path) -> None:
            if self.analysis_worker is not None and self.analysis_worker.isRunning():
                self.status.setText("An offline analysis is already running.")
                return
            self.analyze_button.setEnabled(False)
            self.open_analysis_button.setEnabled(False)
            self.status.setText(f"Analyzing {raw_file} ...")
            self.analysis_worker = AnalysisWorker(
                raw_file,
                raw_file.with_name("analysis"),
                self,
            )
            self.analysis_worker.result_ready.connect(self.analysis_ready)
            self.analysis_worker.failed.connect(self.analysis_failed)
            self.analysis_worker.finished.connect(self.analysis_finished)
            self.analysis_worker.start()

        @QtCore.Slot(object, str)
        def analysis_ready(
            self, result: object, filename: str
        ) -> None:
            self.analysis_workspace.set_result(result, Path(filename))
            self.tabs.setCurrentWidget(self.analysis_workspace)
            self.status.setText(f"Analysis ready: {Path(filename).parent}")

        @QtCore.Slot(str)
        def analysis_failed(self, message: str) -> None:
            self.status.setText(f"Analysis failed: {message}")

        @QtCore.Slot()
        def analysis_finished(self) -> None:
            self.open_analysis_button.setEnabled(True)
            if (session_dir / "raw.ppgbin").is_file():
                self.analyze_button.setEnabled(True)

        def closeEvent(self, event: object) -> None:
            self.reader.request_stop()
            self.reader.wait(int((stop_timeout_s + 2.0) * 1000))
            if (
                self.analysis_worker is not None
                and self.analysis_worker.isRunning()
            ):
                self.analysis_worker.wait(10000)
            super().closeEvent(event)

    app = QtWidgets.QApplication(sys.argv)
    window = Window()
    window.show()
    return app.exec()


def main() -> int:
    parser = argparse.ArgumentParser()
    add_source_arguments(parser)
    parser.add_argument("--session-dir", type=Path, default=None)
    parser.add_argument(
        "--refresh-hz",
        type=float,
        default=10.0,
        help="maximum GUI refresh rate; packet decoding is never throttled",
    )
    parser.add_argument("--stop-timeout", type=float, default=15.0)
    args = parser.parse_args()
    source_spec = source_spec_from_args(parser, args)
    if args.refresh_hz <= 0 or args.stop_timeout <= 0:
        parser.error("refresh rate and stop timeout must be positive")
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    session_dir = args.session_dir or Path("captures") / f"Live_{timestamp}"
    return run(
        source_spec,
        session_dir,
        200.0,
        args.refresh_hz,
        args.stop_timeout,
    )


if __name__ == "__main__":
    raise SystemExit(main())
