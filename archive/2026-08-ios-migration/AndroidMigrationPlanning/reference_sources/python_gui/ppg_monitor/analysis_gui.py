"""Reusable PySide6 workspace for interactive offline session review."""

from __future__ import annotations

from dataclasses import dataclass
import math
from pathlib import Path
from typing import Iterable

import numpy as np
import pyqtgraph as pg
from PySide6 import QtCore, QtGui, QtWidgets
from scipy import signal

from .binary_analysis import analyze_binary_file
from .imu_cardiac import TEMPLATE_MIN_CORRELATION
from .offline import AnalysisResult


_SERIES_COLORS = (
    "#45caff",
    "#ff5c70",
    "#f1c40f",
    "#55dd88",
    "#bc7cff",
    "#ff9955",
)


@dataclass(frozen=True, slots=True)
class _ComparisonRow:
    key: str
    name: str
    time_s: np.ndarray
    values: np.ndarray
    peak_times_s: np.ndarray
    color: str


def _robust_normalize(values: np.ndarray) -> np.ndarray:
    """Return a finite, zero-centred display signal with robust amplitude."""

    source = np.asarray(values, dtype=np.float64)
    if source.size == 0:
        return source.copy()
    finite = np.isfinite(source)
    if not np.any(finite):
        return np.zeros_like(source)
    fill = float(np.median(source[finite]))
    cleaned = np.where(finite, source, fill)
    centred = cleaned - float(np.median(cleaned))
    scale = float(np.percentile(np.abs(centred), 95))
    if not math.isfinite(scale) or scale <= np.finfo(np.float64).eps:
        scale = float(np.std(centred))
    if not math.isfinite(scale) or scale <= np.finfo(np.float64).eps:
        return np.zeros_like(cleaned)
    return centred / scale


def _average_by_peak_times(
    time_s: np.ndarray,
    values: np.ndarray,
    peak_times_s: np.ndarray,
    *,
    points: int = 200,
) -> tuple[np.ndarray, np.ndarray, int]:
    """Average cycles delimited by reference peak times.

    This mirrors the useful part of the Swift comparison window: every row is
    cut with the same reference beat boundaries so inter-signal phase remains
    visible.
    """

    time = np.asarray(time_s, dtype=np.float64)
    samples = np.asarray(values, dtype=np.float64)
    peaks = np.unique(np.asarray(peak_times_s, dtype=np.float64))
    if (
        time.size < 4
        or samples.size != time.size
        or peaks.size < 3
        or points < 8
    ):
        return np.array([]), np.array([]), 0
    valid = np.isfinite(time) & np.isfinite(samples)
    time = time[valid]
    samples = samples[valid]
    if time.size < 4:
        return np.array([]), np.array([]), 0

    rr = np.diff(peaks)
    rr = rr[np.isfinite(rr) & (rr > 0)]
    if rr.size == 0:
        return np.array([]), np.array([]), 0
    median_rr = float(np.median(rr))
    lower = max(0.3, 0.55 * median_rr)
    upper = min(2.5, 1.65 * median_rr)
    phase = np.linspace(0.0, 1.0, points)
    cycles: list[np.ndarray] = []
    for start, stop in zip(peaks[:-1], peaks[1:]):
        duration = float(stop - start)
        if not lower <= duration <= upper:
            continue
        left = int(np.searchsorted(time, start, side="left"))
        right = int(np.searchsorted(time, stop, side="right"))
        if right - left < 4:
            continue
        local_time = time[left:right]
        local_values = samples[left:right]
        local_phase = (local_time - start) / duration
        finite_cycle = (
            np.isfinite(local_phase)
            & np.isfinite(local_values)
            & (local_phase >= 0.0)
            & (local_phase <= 1.0)
        )
        if np.count_nonzero(finite_cycle) < 4:
            continue
        cycles.append(
            np.interp(
                phase,
                local_phase[finite_cycle],
                local_values[finite_cycle],
            )
        )
    if not cycles:
        return np.array([]), np.array([]), 0
    matrix = np.asarray(cycles, dtype=np.float64)
    mean = np.mean(matrix, axis=0)
    if matrix.shape[0] > 1:
        ci95 = (
            1.96
            * np.std(matrix, axis=0, ddof=1)
            / math.sqrt(matrix.shape[0])
        )
    else:
        ci95 = np.zeros(points, dtype=np.float64)
    return mean, ci95, int(matrix.shape[0])


def _interpolate_at(
    time_s: np.ndarray,
    values: np.ndarray,
    query_s: np.ndarray,
) -> np.ndarray:
    time = np.asarray(time_s, dtype=np.float64)
    samples = np.asarray(values, dtype=np.float64)
    query = np.asarray(query_s, dtype=np.float64)
    if time.size < 2 or samples.size != time.size or query.size == 0:
        return np.array([])
    valid = np.isfinite(time) & np.isfinite(samples)
    if np.count_nonzero(valid) < 2:
        return np.array([])
    return np.interp(query, time[valid], samples[valid])


class _MetricCard(QtWidgets.QFrame):
    def __init__(self, title: str, parent: QtWidgets.QWidget | None = None) -> None:
        super().__init__(parent)
        self.setObjectName("metricCard")
        layout = QtWidgets.QVBoxLayout(self)
        layout.setContentsMargins(12, 8, 12, 8)
        layout.setSpacing(2)
        title_label = QtWidgets.QLabel(title)
        title_label.setObjectName("metricTitle")
        self.value = QtWidgets.QLabel("—")
        self.value.setObjectName("metricValue")
        self.value.setTextInteractionFlags(
            QtCore.Qt.TextInteractionFlag.TextSelectableByMouse
        )
        layout.addWidget(title_label)
        layout.addWidget(self.value)

    def set_value(self, value: str, tooltip: str = "") -> None:
        self.value.setText(value)
        self.setToolTip(tooltip)


class AnalysisWorker(QtCore.QThread):
    result_ready = QtCore.Signal(object, str)
    failed = QtCore.Signal(str)

    def __init__(
        self,
        raw_file: Path,
        output_dir: Path | None = None,
        parent: QtCore.QObject | None = None,
    ) -> None:
        super().__init__(parent)
        self.raw_file = raw_file
        self.output_dir = output_dir

    def run(self) -> None:
        try:
            result = analyze_binary_file(
                self.raw_file,
                self.output_dir,
                render_png=False,
            )
        except Exception as error:  # GUI boundary must report worker failures.
            self.failed.emit(f"{type(error).__name__}: {error}")
            return
        self.result_ready.emit(result, str(self.raw_file))


class AnalysisWorkspace(QtWidgets.QWidget):
    """Display one immutable raw-session analysis result."""

    def __init__(self, parent: QtWidgets.QWidget | None = None) -> None:
        super().__init__(parent)
        self.result: AnalysisResult | None = None
        self.raw_file: Path | None = None
        self._regions: list[pg.LinearRegionItem] = []
        self._explorer_regions: list[pg.LinearRegionItem] = []
        self._comparison_controls: dict[
            str, tuple[QtWidgets.QCheckBox, QtWidgets.QCheckBox, QtWidgets.QCheckBox]
        ] = {}
        self._explorer_time_domain = True
        self._explorer_full_range: tuple[float, float] | None = None
        self._comparison_full_range: tuple[float, float] | None = None

        root = QtWidgets.QVBoxLayout(self)
        root.setContentsMargins(10, 8, 10, 10)
        root.setSpacing(8)

        header = QtWidgets.QHBoxLayout()
        title_box = QtWidgets.QVBoxLayout()
        title = QtWidgets.QLabel("Session analysis workspace")
        title.setObjectName("workspaceTitle")
        self.summary = QtWidgets.QLabel("No analysis loaded.")
        self.summary.setWordWrap(True)
        self.summary.setObjectName("workspaceSummary")
        title_box.addWidget(title)
        title_box.addWidget(self.summary)
        header.addLayout(title_box, stretch=1)
        self.export_button = QtWidgets.QPushButton("Export workspace")
        self.export_button.clicked.connect(self._export_view)
        header.addWidget(self.export_button)
        root.addLayout(header)

        cards = QtWidgets.QHBoxLayout()
        cards.setSpacing(8)
        self.session_card = _MetricCard("SESSION")
        self.ppg_card = _MetricCard("PPG")
        self.imu_card = _MetricCard("IMU CANDIDATE")
        self.duration_card = _MetricCard("SAMPLING")
        self.quality_card = _MetricCard("QUALITY / INTEGRITY")
        for card in (
            self.session_card,
            self.ppg_card,
            self.imu_card,
            self.duration_card,
            self.quality_card,
        ):
            cards.addWidget(card, stretch=1)
        root.addLayout(cards)

        self.main_splitter = QtWidgets.QSplitter(
            QtCore.Qt.Orientation.Horizontal
        )
        self.main_splitter.setChildrenCollapsible(False)
        self.main_splitter.addWidget(self._build_sidebar())

        self.tabs = QtWidgets.QTabWidget()
        self.tabs.setDocumentMode(True)
        self.main_splitter.addWidget(self.tabs)
        self.main_splitter.setSizes((350, 1050))
        self.main_splitter.setStretchFactor(0, 0)
        self.main_splitter.setStretchFactor(1, 1)
        root.addWidget(self.main_splitter, stretch=1)

        self._build_explorer_tab()
        self._build_ppg_tab()
        self._build_imu_tab()
        self._build_comparison_tab()
        self._build_cycle_tab()
        self._build_diagnostics_tab()

    def _build_sidebar(self) -> QtWidgets.QWidget:
        scroll = QtWidgets.QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setHorizontalScrollBarPolicy(
            QtCore.Qt.ScrollBarPolicy.ScrollBarAlwaysOff
        )
        scroll.setMinimumWidth(340)
        scroll.setMaximumWidth(420)
        panel = QtWidgets.QWidget()
        panel.setMinimumWidth(0)
        panel.setSizePolicy(
            QtWidgets.QSizePolicy.Policy.Ignored,
            QtWidgets.QSizePolicy.Policy.Preferred,
        )
        self.sidebar_panel = panel
        layout = QtWidgets.QVBoxLayout(panel)
        layout.setContentsMargins(8, 8, 8, 8)
        layout.setSpacing(10)

        explorer_group = QtWidgets.QGroupBox("Signal workbench")
        explorer_form = QtWidgets.QFormLayout(explorer_group)
        explorer_form.setFieldGrowthPolicy(
            QtWidgets.QFormLayout.FieldGrowthPolicy.AllNonFixedFieldsGrow
        )
        explorer_form.setRowWrapPolicy(
            QtWidgets.QFormLayout.RowWrapPolicy.WrapLongRows
        )
        self.explorer_source = QtWidgets.QComboBox()
        self.explorer_source.addItem("PPG selected", "ppg_selected")
        self.explorer_source.addItem("PPG RED", "ppg_red")
        self.explorer_source.addItem("PPG IR", "ppg_ir")
        self.explorer_source.addItem("Accelerometer", "accel")
        self.explorer_source.addItem("Gyroscope", "gyro")
        self.explorer_source.currentIndexChanged.connect(
            self._update_explorer_stages
        )
        self.explorer_stage = QtWidgets.QComboBox()
        self.explorer_stage.currentIndexChanged.connect(
            self._explorer_stage_changed
        )
        explorer_form.addRow("Signal", self.explorer_source)
        explorer_form.addRow("Stage", self.explorer_stage)
        layout.addWidget(explorer_group)

        range_group = QtWidgets.QGroupBox("Review range")
        range_form = QtWidgets.QFormLayout(range_group)
        range_form.setFieldGrowthPolicy(
            QtWidgets.QFormLayout.FieldGrowthPolicy.AllNonFixedFieldsGrow
        )
        range_form.setRowWrapPolicy(
            QtWidgets.QFormLayout.RowWrapPolicy.WrapLongRows
        )
        self.channel = QtWidgets.QComboBox()
        self.channel.addItems(("Selected channel", "RED", "IR"))
        self.channel.currentIndexChanged.connect(self._refresh_ppg_detail)
        self.segment = QtWidgets.QComboBox()
        self.segment.addItem("All stable segments", -1)
        self.segment.currentIndexChanged.connect(self._select_segment)
        self.zoom_slider = QtWidgets.QSlider(QtCore.Qt.Orientation.Horizontal)
        self.zoom_slider.setRange(1, 20)
        self.zoom_slider.setValue(1)
        self.zoom_slider.valueChanged.connect(self._apply_explorer_zoom)
        zoom_buttons = QtWidgets.QHBoxLayout()
        for value in (1, 2, 5, 10):
            button = QtWidgets.QPushButton(f"{value}×")
            button.setProperty("compact", True)
            button.clicked.connect(
                lambda _checked=False, zoom=value: self.zoom_slider.setValue(
                    zoom
                )
            )
            zoom_buttons.addWidget(button)
        self.fit_button = QtWidgets.QPushButton("Fit selected range")
        self.fit_button.clicked.connect(self._fit_selected_range)
        range_form.addRow("PPG channel", self.channel)
        range_form.addRow("Stable segment", self.segment)
        range_form.addRow("Horizontal zoom", self.zoom_slider)
        range_form.addRow("", zoom_buttons)
        range_form.addRow("", self.fit_button)
        layout.addWidget(range_group)

        overlay_group = QtWidgets.QGroupBox("Display overlays")
        overlay_layout = QtWidgets.QVBoxLayout(overlay_group)
        self.show_peaks = QtWidgets.QCheckBox("Show accepted peaks")
        self.show_peaks.setChecked(True)
        self.show_peaks.toggled.connect(self._refresh_all_views)
        self.show_segments = QtWidgets.QCheckBox("Show stable regions")
        self.show_segments.setChecked(True)
        self.show_segments.toggled.connect(self._refresh_all_views)
        self.invert_signal = QtWidgets.QCheckBox("Invert workbench signal")
        self.invert_signal.toggled.connect(self._refresh_explorer)
        self.auto_y = QtWidgets.QCheckBox("Auto-range Y while changing views")
        self.auto_y.setChecked(True)
        overlay_layout.addWidget(self.show_peaks)
        overlay_layout.addWidget(self.show_segments)
        overlay_layout.addWidget(self.invert_signal)
        overlay_layout.addWidget(self.auto_y)
        layout.addWidget(overlay_group)

        info_group = QtWidgets.QGroupBox("Analysis contract")
        info_layout = QtWidgets.QVBoxLayout(info_group)
        self.contract_text = QtWidgets.QLabel(
            "Offline results are immutable.\n"
            "View controls do not rewrite raw.ppgbin or change detector output."
        )
        self.contract_text.setWordWrap(True)
        self.contract_text.setObjectName("mutedText")
        info_layout.addWidget(self.contract_text)
        layout.addWidget(info_group)
        layout.addStretch(1)
        scroll.setWidget(panel)
        self._update_explorer_stages()
        return scroll

    def _make_plot(
        self,
        title: str,
        left_label: str,
        left_units: str | None = None,
    ) -> pg.PlotWidget:
        plot = pg.PlotWidget(title=title)
        plot.setBackground("#10151b")
        plot.showGrid(x=True, y=True, alpha=0.22)
        plot.setLabel("bottom", "Device time", units="s")
        plot.setLabel("left", left_label, units=left_units)
        plot.getPlotItem().setDownsampling(auto=True, mode="peak")
        plot.getPlotItem().setClipToView(True)
        return plot

    def _build_explorer_tab(self) -> None:
        tab = QtWidgets.QWidget()
        layout = QtWidgets.QVBoxLayout(tab)
        self.explorer_caption = QtWidgets.QLabel(
            "Choose a signal and processing stage from the left panel."
        )
        self.explorer_caption.setWordWrap(True)
        self.explorer_caption.setObjectName("mutedText")
        self.explorer_plot = self._make_plot(
            "Signal workbench", "Amplitude"
        )
        self.explorer_plot.addLegend()
        layout.addWidget(self.explorer_plot, stretch=1)
        layout.addWidget(self.explorer_caption)
        self.tabs.addTab(tab, "Workbench")

    def _update_explorer_stages(self) -> None:
        source = self.explorer_source.currentData()
        if source in {"ppg_selected", "ppg_red", "ppg_ir"}:
            stages = (
                ("Raw", "raw"),
                ("Band-pass cardiac", "bandpass"),
                ("Accepted peaks", "peaks"),
                ("Welch spectrum", "spectrum"),
                ("Average cycle", "cycle"),
            )
        elif source == "accel":
            stages = (
                ("Raw XYZ", "raw_xyz"),
                ("Gravity-separated XYZ", "corrected_xyz"),
                ("Magnitude comparison", "magnitude"),
                ("Mechanical axis", "mechanical"),
                ("Cardiac candidates", "peaks"),
                ("PPG-referenced cycle", "cycle"),
            )
        else:
            stages = (
                ("Raw XYZ", "raw_xyz"),
                ("Bias-corrected XYZ", "corrected_xyz"),
                ("Magnitude comparison", "magnitude"),
                ("Mechanical axis", "mechanical"),
                ("Cardiac candidates", "peaks"),
                ("PPG-referenced cycle", "cycle"),
            )
        previous = self.explorer_stage.currentData()
        self.explorer_stage.blockSignals(True)
        self.explorer_stage.clear()
        for label, key in stages:
            self.explorer_stage.addItem(label, key)
        match = self.explorer_stage.findData(previous)
        self.explorer_stage.setCurrentIndex(max(0, match))
        self.explorer_stage.blockSignals(False)
        self._refresh_explorer()
        self._sync_imu_from_workbench()

    @QtCore.Slot()
    def _explorer_stage_changed(self) -> None:
        self._refresh_explorer()
        self._sync_imu_from_workbench()

    def _selected_segment_range(self) -> tuple[float, float] | None:
        if self.result is None or self.segment.currentData() is None:
            return None
        selected = int(self.segment.currentData())
        if selected < 0:
            return None
        for segment in self.result.pulse_analysis.segments:
            if segment.index == selected:
                return float(segment.start_s), float(segment.stop_s)
        return None

    def _plot_series(
        self,
        plot: pg.PlotWidget,
        x: np.ndarray,
        series: Iterable[tuple[str, np.ndarray, str]],
        *,
        invert: bool = False,
    ) -> list[np.ndarray]:
        plotted: list[np.ndarray] = []
        direction = -1.0 if invert else 1.0
        for name, values, color in series:
            data = direction * np.asarray(values, dtype=np.float64)
            count = min(len(x), len(data))
            if count == 0:
                continue
            plot.plot(
                np.asarray(x[:count], dtype=np.float64),
                data[:count],
                pen=pg.mkPen(color, width=1.35),
                name=name,
            )
            plotted.append(data[:count])
        return plotted

    @QtCore.Slot()
    def _refresh_explorer(self) -> None:
        if not hasattr(self, "explorer_plot") or self.result is None:
            return
        result = self.result
        source = str(self.explorer_source.currentData())
        stage = str(self.explorer_stage.currentData())
        plot = self.explorer_plot
        plot.clear()
        legend = plot.getPlotItem().legend
        if legend is not None:
            legend.clear()
        else:
            plot.addLegend()
        self._explorer_regions.clear()
        self._explorer_time_domain = stage not in {"spectrum", "cycle"}
        self._explorer_full_range = None
        invert = self.invert_signal.isChecked()
        caption = ""
        peaks_x = np.array([])
        peaks_y = np.array([])
        time = result.time_s
        series_data: list[tuple[str, np.ndarray, str]] = []

        if source in {"ppg_selected", "ppg_red", "ppg_ir"}:
            channel = (
                (result.peak_channel or "IR")
                if source == "ppg_selected"
                else source.removeprefix("ppg_").upper()
            )
            raw = result.red if channel == "RED" else result.ir
            filtered = (
                result.red_bandpass if channel == "RED" else result.ir_bandpass
            )
            if stage == "raw":
                series_data = [(f"{channel} raw", raw, "#ff5c70")]
                caption = f"{channel} ADC samples before cardiac filtering."
            elif stage in {"bandpass", "peaks"}:
                series_data = [(f"{channel} filtered", filtered, "#45caff")]
                caption = (
                    f"{channel} segmented 0.6–4 Hz cardiac band-pass."
                    if stage == "bandpass"
                    else (
                        f"{channel} accepted detector peaks; detector output "
                        "is unchanged by display options."
                    )
                )
                if (
                    stage == "peaks"
                    and channel == result.peak_channel
                    and self.show_peaks.isChecked()
                ):
                    indices = result.peak_indices[
                        (result.peak_indices >= 0)
                        & (result.peak_indices < len(filtered))
                    ]
                    peaks_x = result.time_s[indices]
                    peaks_y = filtered[indices] * (-1.0 if invert else 1.0)
            elif stage == "spectrum":
                sample_rate = float(
                    result.summary.get("sample_rate_hz_estimated", 0.0)
                )
                selected = np.asarray(filtered, dtype=np.float64)
                selected_range = self._selected_segment_range()
                if selected_range is not None:
                    mask = (time >= selected_range[0]) & (
                        time <= selected_range[1]
                    )
                    selected = selected[mask]
                selected = selected[np.isfinite(selected)]
                if len(selected) >= 32 and sample_rate > 0:
                    frequencies, power = signal.welch(
                        selected,
                        fs=sample_rate,
                        nperseg=min(
                            len(selected), max(32, int(sample_rate * 8))
                        ),
                        detrend="linear",
                    )
                    cardiac_band = (frequencies >= 0.3) & (
                        frequencies <= 8.0
                    )
                    time = frequencies[cardiac_band]
                    series_data = [
                        ("Welch PSD", power[cardiac_band], "#45caff")
                    ]
                plot.setLabel("bottom", "Frequency", units="Hz")
                plot.setLabel("left", "Power spectral density")
                caption = (
                    f"{channel} Welch spectrum for the selected stable range."
                )
            else:
                if channel == result.peak_channel and len(result.waveform_mean):
                    time = result.waveform_phase
                    series_data = [
                        ("Mean cycle", result.waveform_mean, "#45caff"),
                        (
                            "+95% CI",
                            result.waveform_mean + result.waveform_ci95,
                            "#80bfff",
                        ),
                        (
                            "−95% CI",
                            result.waveform_mean - result.waveform_ci95,
                            "#80bfff",
                        ),
                    ]
                    cycles = result.waveform_cycle_count
                else:
                    peak_times = result.time_s[result.peak_indices]
                    mean, ci95, cycles = _average_by_peak_times(
                        result.time_s, filtered, peak_times
                    )
                    time = np.linspace(0.0, 1.0, len(mean))
                    series_data = [
                        ("Mean cycle", mean, "#45caff"),
                        ("+95% CI", mean + ci95, "#80bfff"),
                        ("−95% CI", mean - ci95, "#80bfff"),
                    ]
                plot.setLabel("bottom", "Normalized cardiac cycle")
                plot.setLabel("left", "Amplitude")
                caption = f"{channel} average cycle from {cycles} accepted beats."
        else:
            imu = result.imu_analysis
            is_accel = source == "accel"
            time = imu.time_s
            raw_xyz = imu.accel_m_s2 if is_accel else imu.gyro_rad_s
            corrected_xyz = (
                imu.linear_accel_m_s2
                if is_accel
                else imu.gyro_corrected_rad_s
            )
            raw_magnitude = (
                imu.accel_magnitude if is_accel else imu.gyro_magnitude
            )
            corrected_magnitude = (
                imu.linear_accel_magnitude
                if is_accel
                else imu.gyro_corrected_magnitude
            )
            mechanical = (
                imu.accel_mechanical if is_accel else imu.gyro_mechanical
            )
            source_name = "Acceleration" if is_accel else "Angular rate"
            unit = "m/s²" if is_accel else "rad/s"
            if stage in {"raw_xyz", "corrected_xyz"}:
                matrix = raw_xyz if stage == "raw_xyz" else corrected_xyz
                label = "raw" if stage == "raw_xyz" else "gravity/bias corrected"
                series_data = [
                    (f"{label} {axis}", matrix[:, index], color)
                    for index, (axis, color) in enumerate(
                        zip(("x", "y", "z"), _SERIES_COLORS)
                    )
                ]
                caption = f"{source_name} {label} sensor-frame axes."
            elif stage == "magnitude":
                series_data = [
                    ("raw magnitude", raw_magnitude, "#45caff"),
                    ("corrected magnitude", corrected_magnitude, "#f1c40f"),
                ]
                caption = (
                    f"{source_name} raw and corrected magnitudes shown in "
                    f"their physical unit ({unit})."
                )
            elif stage in {"mechanical", "peaks"}:
                series_data = [
                    ("mechanical axis", mechanical, "#55dd88")
                ]
                cardiac = result.imu_cardiac
                caption = (
                    f"{source_name} PCA mechanical axis."
                    if stage == "mechanical"
                    else (
                        "PPG-synchronous inertial candidates; these are not "
                        "validated SCG/JVP fiducials."
                    )
                )
                if (
                    stage == "peaks"
                    and cardiac.source == source
                    and self.show_peaks.isChecked()
                    and len(cardiac.peak_indices)
                ):
                    indices = cardiac.peak_indices[
                        (cardiac.peak_indices >= 0)
                        & (cardiac.peak_indices < len(cardiac.signal))
                    ]
                    time = cardiac.time_s
                    series_data = [
                        ("selected cardiac signal", cardiac.signal, "#55dd88")
                    ]
                    peaks_x = cardiac.time_s[indices]
                    peaks_y = cardiac.signal[indices] * (
                        -1.0 if invert else 1.0
                    )
            else:
                peak_times = result.time_s[result.peak_indices]
                mean, ci95, cycles = _average_by_peak_times(
                    time, mechanical, peak_times
                )
                time = np.linspace(0.0, 1.0, len(mean))
                series_data = [
                    ("PPG-referenced mean", mean, "#55dd88"),
                    ("+95% CI", mean + ci95, "#a0e8b8"),
                    ("−95% CI", mean - ci95, "#a0e8b8"),
                ]
                plot.setLabel("bottom", "Normalized PPG-reference cycle")
                plot.setLabel("left", "Amplitude")
                caption = (
                    f"{source_name} average cut by the same PPG beat "
                    f"boundaries ({cycles} cycles)."
                )
            if self._explorer_time_domain:
                plot.setLabel("bottom", "Device time", units="s")
                plot.setLabel("left", source_name, units=unit)

        self._plot_series(plot, time, series_data, invert=invert)
        if peaks_x.size:
            plot.addItem(
                pg.ScatterPlotItem(
                    x=peaks_x,
                    y=peaks_y,
                    size=8,
                    brush=pg.mkBrush("#ff334f"),
                    pen=pg.mkPen("#ffffff", width=0.5),
                    name="accepted peaks",
                )
            )
        if (
            self._explorer_time_domain
            and self.show_segments.isChecked()
            and source.startswith("ppg")
        ):
            for segment in result.pulse_analysis.segments:
                region = pg.LinearRegionItem(
                    values=(segment.start_s, segment.stop_s),
                    movable=False,
                    brush=pg.mkBrush(70, 190, 110, 22),
                    pen=pg.mkPen(70, 190, 110, 70),
                )
                region.setZValue(-20)
                plot.addItem(region)
                self._explorer_regions.append(region)
        plot.setTitle(
            f"{self.explorer_source.currentText()} · "
            f"{self.explorer_stage.currentText()}"
        )
        self.explorer_caption.setText(caption)
        if len(time):
            self._explorer_full_range = (float(time[0]), float(time[-1]))
        if self.auto_y.isChecked():
            plot.enableAutoRange(axis=pg.ViewBox.YAxis, enable=True)
        self._apply_explorer_zoom()

    @QtCore.Slot()
    def _fit_selected_range(self) -> None:
        self.zoom_slider.blockSignals(True)
        self.zoom_slider.setValue(1)
        self.zoom_slider.blockSignals(False)
        self._apply_explorer_zoom()

    @QtCore.Slot()
    def _apply_explorer_zoom(self) -> None:
        if (
            not hasattr(self, "explorer_plot")
            or self._explorer_full_range is None
        ):
            return
        start, stop = self._explorer_full_range
        if self._explorer_time_domain:
            selected = self._selected_segment_range()
            if selected is not None:
                start, stop = selected
        span = max(np.finfo(float).eps, stop - start)
        zoom = max(1, self.zoom_slider.value())
        current = self.explorer_plot.getPlotItem().getViewBox().viewRange()[0]
        center = (
            0.5 * (current[0] + current[1])
            if current[1] > current[0] and zoom > 1
            else 0.5 * (start + stop)
        )
        width = span / zoom
        center = min(max(center, start + width / 2), stop - width / 2)
        self.explorer_plot.setXRange(
            center - width / 2,
            center + width / 2,
            padding=0.01,
        )

    def _build_comparison_tab(self) -> None:
        tab = QtWidgets.QWidget()
        layout = QtWidgets.QHBoxLayout(tab)
        splitter = QtWidgets.QSplitter(QtCore.Qt.Orientation.Horizontal)

        controls_scroll = QtWidgets.QScrollArea()
        controls_scroll.setWidgetResizable(True)
        controls_scroll.setMinimumWidth(250)
        controls_scroll.setMaximumWidth(360)
        controls = QtWidgets.QWidget()
        controls_layout = QtWidgets.QVBoxLayout(controls)

        layout_group = QtWidgets.QGroupBox("Stacked layout")
        layout_form = QtWidgets.QFormLayout(layout_group)
        self.compare_spacing = QtWidgets.QSlider(
            QtCore.Qt.Orientation.Horizontal
        )
        self.compare_spacing.setRange(65, 180)
        self.compare_spacing.setValue(100)
        self.compare_amplitude = QtWidgets.QSlider(
            QtCore.Qt.Orientation.Horizontal
        )
        self.compare_amplitude.setRange(12, 80)
        self.compare_amplitude.setValue(32)
        self.compare_zoom = QtWidgets.QSlider(
            QtCore.Qt.Orientation.Horizontal
        )
        self.compare_zoom.setRange(1, 18)
        self.compare_zoom.setValue(3)
        for slider in (
            self.compare_spacing,
            self.compare_amplitude,
            self.compare_zoom,
        ):
            slider.valueChanged.connect(self._refresh_comparison)
        layout_form.addRow("Row spacing", self.compare_spacing)
        layout_form.addRow("Amplitude", self.compare_amplitude)
        layout_form.addRow("Horizontal zoom", self.compare_zoom)
        controls_layout.addWidget(layout_group)

        signals_group = QtWidgets.QGroupBox("Signals")
        signals_grid = QtWidgets.QGridLayout(signals_group)
        signals_grid.addWidget(QtWidgets.QLabel("Name"), 0, 0)
        signals_grid.addWidget(QtWidgets.QLabel("Show"), 0, 1)
        signals_grid.addWidget(QtWidgets.QLabel("Invert"), 0, 2)
        signals_grid.addWidget(QtWidgets.QLabel("Peaks"), 0, 3)
        for row_index, (key, name) in enumerate(
            (
                ("ppg_red", "PPG RED"),
                ("ppg_ir", "PPG IR"),
                ("accel", "Accel mechanical"),
                ("gyro", "Gyro mechanical"),
            ),
            start=1,
        ):
            visible = QtWidgets.QCheckBox()
            visible.setChecked(True)
            inverted = QtWidgets.QCheckBox()
            show_cycle = QtWidgets.QCheckBox()
            show_cycle.setChecked(True)
            for checkbox in (visible, inverted, show_cycle):
                checkbox.toggled.connect(self._refresh_comparison)
            signals_grid.addWidget(QtWidgets.QLabel(name), row_index, 0)
            signals_grid.addWidget(visible, row_index, 1)
            signals_grid.addWidget(inverted, row_index, 2)
            signals_grid.addWidget(show_cycle, row_index, 3)
            self._comparison_controls[key] = (
                visible,
                inverted,
                show_cycle,
            )
        controls_layout.addWidget(signals_group)

        reference_group = QtWidgets.QGroupBox("Reference")
        reference_layout = QtWidgets.QVBoxLayout(reference_group)
        self.show_reference_lines = QtWidgets.QCheckBox(
            "Show PPG beat boundaries"
        )
        self.show_reference_lines.setChecked(True)
        self.show_reference_lines.toggled.connect(self._refresh_comparison)
        reference_note = QtWidgets.QLabel(
            "Cycle rows use one PPG reference peak sequence. This keeps "
            "relative inertial timing visible without claiming an SCG/JVP "
            "fiducial."
        )
        reference_note.setWordWrap(True)
        reference_note.setObjectName("mutedText")
        reference_layout.addWidget(self.show_reference_lines)
        reference_layout.addWidget(reference_note)
        controls_layout.addWidget(reference_group)

        reset = QtWidgets.QPushButton("Reset comparison options")
        reset.clicked.connect(self._reset_comparison_options)
        controls_layout.addWidget(reset)
        controls_layout.addStretch(1)
        controls_scroll.setWidget(controls)
        splitter.addWidget(controls_scroll)

        compare_tabs = QtWidgets.QTabWidget()
        self.compare_tabs = compare_tabs
        full_page = QtWidgets.QWidget()
        full_layout = QtWidgets.QVBoxLayout(full_page)
        self.full_compare_plot = self._make_plot(
            "Full cardiac-signal comparison", "Signals"
        )
        self.full_compare_caption = QtWidgets.QLabel(
            "Load an analysis result to compare full signals."
        )
        self.full_compare_caption.setWordWrap(True)
        self.full_compare_caption.setObjectName("mutedText")
        full_layout.addWidget(self.full_compare_plot, stretch=1)
        full_layout.addWidget(self.full_compare_caption)
        compare_tabs.addTab(full_page, "Full signals")

        cycle_page = QtWidgets.QWidget()
        cycle_layout = QtWidgets.QVBoxLayout(cycle_page)
        self.cycle_compare_plot = self._make_plot(
            "Unified cardiac-cycle comparison", "Signals"
        )
        self.cycle_compare_plot.setLabel(
            "bottom", "Reference cycles", units="cycle"
        )
        self.cycle_compare_caption = QtWidgets.QLabel(
            "Load an analysis result to compare PPG-referenced cycles."
        )
        self.cycle_compare_caption.setWordWrap(True)
        self.cycle_compare_caption.setObjectName("mutedText")
        cycle_layout.addWidget(self.cycle_compare_plot, stretch=1)
        cycle_layout.addWidget(self.cycle_compare_caption)
        compare_tabs.addTab(cycle_page, "Unified cycles")
        splitter.addWidget(compare_tabs)
        splitter.setSizes((290, 900))
        splitter.setStretchFactor(1, 1)
        layout.addWidget(splitter)
        self.tabs.addTab(tab, "Compare")

    def _comparison_rows(self) -> list[_ComparisonRow]:
        if self.result is None:
            return []
        result = self.result
        ppg_peaks = result.peak_indices[
            (result.peak_indices >= 0)
            & (result.peak_indices < len(result.time_s))
        ]
        ppg_peak_times = result.time_s[ppg_peaks]
        cardiac = result.imu_cardiac
        imu_peak_times = (
            cardiac.time_s[cardiac.peak_indices]
            if len(cardiac.peak_indices)
            else np.array([])
        )
        imu = result.imu_analysis
        return [
            _ComparisonRow(
                "ppg_red",
                "PPG RED",
                result.time_s,
                result.red_bandpass,
                ppg_peak_times,
                "#ff5c70",
            ),
            _ComparisonRow(
                "ppg_ir",
                "PPG IR",
                result.time_s,
                result.ir_bandpass,
                ppg_peak_times,
                "#f1c40f",
            ),
            _ComparisonRow(
                "accel",
                "Accel mechanical",
                imu.time_s,
                imu.accel_mechanical,
                imu_peak_times if cardiac.source == "accel" else np.array([]),
                "#45caff",
            ),
            _ComparisonRow(
                "gyro",
                "Gyro mechanical",
                imu.time_s,
                imu.gyro_mechanical,
                imu_peak_times if cardiac.source == "gyro" else np.array([]),
                "#bc7cff",
            ),
        ]

    @QtCore.Slot()
    def _refresh_comparison(self) -> None:
        if (
            not hasattr(self, "full_compare_plot")
            or self.result is None
        ):
            return
        rows = self._comparison_rows()
        visible_rows = [
            row
            for row in rows
            if self._comparison_controls[row.key][0].isChecked()
        ]
        spacing = self.compare_spacing.value() / 100.0
        amplitude = self.compare_amplitude.value() / 100.0
        segment_range = self._selected_segment_range()

        full = self.full_compare_plot
        full.clear()
        ticks: list[tuple[float, str]] = []
        reference_times = (
            self.result.time_s[self.result.peak_indices]
            if len(self.result.peak_indices)
            else np.array([])
        )
        all_starts: list[float] = []
        all_stops: list[float] = []
        for index, row in enumerate(visible_rows):
            offset = float(len(visible_rows) - index - 1) * spacing
            controls = self._comparison_controls[row.key]
            direction = -1.0 if controls[1].isChecked() else 1.0
            normalized = np.clip(
                direction * _robust_normalize(row.values),
                -1.5,
                1.5,
            )
            full.plot(
                row.time_s,
                offset + amplitude * normalized,
                pen=pg.mkPen(row.color, width=1.2),
            )
            ticks.append((offset, row.name))
            if len(row.time_s):
                all_starts.append(float(row.time_s[0]))
                all_stops.append(float(row.time_s[-1]))
            if controls[2].isChecked() and len(row.peak_times_s):
                marker_values = _interpolate_at(
                    row.time_s, normalized, row.peak_times_s
                )
                if len(marker_values) == len(row.peak_times_s):
                    full.addItem(
                        pg.ScatterPlotItem(
                            x=row.peak_times_s,
                            y=offset + amplitude * marker_values,
                            size=6,
                            brush=pg.mkBrush(row.color),
                            pen=pg.mkPen("#ffffff", width=0.4),
                        )
                    )
        if (
            self.show_reference_lines.isChecked()
            and len(reference_times)
            and visible_rows
        ):
            if segment_range is None:
                reference_visible = reference_times
            else:
                reference_visible = reference_times[
                    (reference_times >= segment_range[0])
                    & (reference_times <= segment_range[1])
                ]
            for beat_time in reference_visible:
                full.addItem(
                    pg.InfiniteLine(
                        pos=float(beat_time),
                        angle=90,
                        pen=pg.mkPen(255, 65, 85, 45, width=0.7),
                    )
                )
        full.getAxis("left").setTicks([ticks])
        full.setLabel("bottom", "Device time", units="s")
        full.setLabel("left", "Stacked normalized signals")
        if all_starts and all_stops:
            self._comparison_full_range = (
                min(all_starts),
                max(all_stops),
            )
        if visible_rows:
            full.setYRange(
                -0.65,
                max(0.65, (len(visible_rows) - 1) * spacing + 0.65),
                padding=0.03,
            )
        self._apply_comparison_zoom()
        self.full_compare_caption.setText(
            f"{len(visible_rows)} visible rows · amplitude "
            f"{amplitude:.2f} · row spacing {spacing:.2f}. "
            "Red vertical lines are accepted PPG beat boundaries; colored "
            "points are each row's available detector peaks."
        )

        cycle_plot = self.cycle_compare_plot
        cycle_plot.clear()
        cycle_ticks: list[tuple[float, str]] = []
        cycle_messages: list[str] = []
        for index, row in enumerate(visible_rows):
            offset = float(len(visible_rows) - index - 1) * spacing
            controls = self._comparison_controls[row.key]
            direction = -1.0 if controls[1].isChecked() else 1.0
            mean, _ci95, count = _average_by_peak_times(
                row.time_s,
                row.values,
                reference_times,
            )
            if len(mean) == 0:
                continue
            normalized = np.clip(
                direction * _robust_normalize(mean),
                -1.5,
                1.5,
            )
            repeated = np.concatenate((normalized, normalized[1:]))
            phase = np.linspace(0.0, 2.0, len(repeated))
            cycle_plot.plot(
                phase,
                offset + amplitude * repeated,
                pen=pg.mkPen(row.color, width=1.5),
            )
            if controls[2].isChecked():
                own_index = int(np.argmax(np.abs(normalized)))
                own_phase = own_index / max(1, len(normalized) - 1)
                for repeat in (0.0, 1.0):
                    cycle_plot.addItem(
                        pg.ScatterPlotItem(
                            x=[own_phase + repeat],
                            y=[
                                offset
                                + amplitude * normalized[own_index]
                            ],
                            size=7,
                            brush=pg.mkBrush(row.color),
                            pen=pg.mkPen("#ffffff", width=0.5),
                        )
                    )
            cycle_ticks.append((offset, row.name))
            cycle_messages.append(f"{row.name}: {count} cycles")
        if self.show_reference_lines.isChecked():
            for boundary in (0.0, 1.0, 2.0):
                cycle_plot.addItem(
                    pg.InfiniteLine(
                        pos=boundary,
                        angle=90,
                        pen=pg.mkPen(255, 65, 85, 150, width=1.2),
                    )
                )
        cycle_plot.getAxis("left").setTicks([cycle_ticks])
        cycle_plot.setLabel("bottom", "PPG-reference cycle")
        cycle_plot.setLabel("left", "Stacked normalized waveforms")
        cycle_plot.setXRange(0.0, 2.0, padding=0.01)
        if visible_rows:
            cycle_plot.setYRange(
                -0.65,
                max(0.65, (len(visible_rows) - 1) * spacing + 0.65),
                padding=0.03,
            )
        reference_name = self.result.peak_channel or "PPG"
        self.cycle_compare_caption.setText(
            f"Unified boundaries: {reference_name} accepted peaks. "
            + " · ".join(cycle_messages)
        )

    def _apply_comparison_zoom(self) -> None:
        if (
            not hasattr(self, "full_compare_plot")
            or self._comparison_full_range is None
        ):
            return
        start, stop = self._comparison_full_range
        selected = self._selected_segment_range()
        if selected is not None:
            start, stop = selected
        span = max(np.finfo(float).eps, stop - start)
        zoom = max(1, self.compare_zoom.value())
        current = self.full_compare_plot.getViewBox().viewRange()[0]
        center = (
            0.5 * (current[0] + current[1])
            if current[1] > current[0] and zoom > 1
            else 0.5 * (start + stop)
        )
        width = span / zoom
        center = min(max(center, start + width / 2), stop - width / 2)
        self.full_compare_plot.setXRange(
            center - width / 2,
            center + width / 2,
            padding=0.01,
        )

    @QtCore.Slot()
    def _reset_comparison_options(self) -> None:
        self.compare_spacing.setValue(100)
        self.compare_amplitude.setValue(32)
        self.compare_zoom.setValue(3)
        self.show_reference_lines.setChecked(True)
        for visible, inverted, peaks in self._comparison_controls.values():
            visible.setChecked(True)
            inverted.setChecked(False)
            peaks.setChecked(True)
        self._refresh_comparison()

    def _build_diagnostics_tab(self) -> None:
        tab = QtWidgets.QWidget()
        layout = QtWidgets.QVBoxLayout(tab)
        splitter = QtWidgets.QSplitter(QtCore.Qt.Orientation.Vertical)
        self.diagnostic_tree = QtWidgets.QTreeWidget()
        self.diagnostic_tree.setHeaderLabels(("Field", "Value"))
        self.diagnostic_tree.setAlternatingRowColors(True)
        self.diagnostic_tree.header().setStretchLastSection(True)
        self.messages_box = QtWidgets.QPlainTextEdit()
        self.messages_box.setReadOnly(True)
        self.messages_box.setPlaceholderText(
            "Analysis warnings and interpretation notes appear here."
        )
        splitter.addWidget(self.diagnostic_tree)
        splitter.addWidget(self.messages_box)
        splitter.setSizes((580, 190))
        layout.addWidget(splitter)
        self.tabs.addTab(tab, "Diagnostics")

    def _build_ppg_tab(self) -> None:
        tab = QtWidgets.QWidget()
        layout = QtWidgets.QVBoxLayout(tab)
        splitter = QtWidgets.QSplitter(QtCore.Qt.Orientation.Vertical)
        plots = QtWidgets.QWidget()
        plots_layout = QtWidgets.QVBoxLayout(plots)
        self.overview_plot = self._make_plot(
            "Raw PPG overview and stable segments", "ADC counts"
        )
        self.overview_plot.addLegend()
        self.red_raw_curve = self.overview_plot.plot(
            pen="#ff5555", name="RED raw"
        )
        self.ir_raw_curve = self.overview_plot.plot(
            pen="#f1c40f", name="IR raw"
        )
        self.detail_plot = self._make_plot(
            "Segmented band-pass and accepted peaks", "AC"
        )
        self.detail_plot.addLegend()
        self.detail_curve = self.detail_plot.plot(
            pen="#45caff", name="filtered"
        )
        self.peak_scatter = pg.ScatterPlotItem(
            size=7,
            brush=pg.mkBrush("#ff3333"),
            pen=None,
            name="accepted peaks",
        )
        self.detail_plot.addItem(self.peak_scatter)
        plots_layout.addWidget(self.overview_plot)
        plots_layout.addWidget(self.detail_plot)
        splitter.addWidget(plots)

        self.window_table = QtWidgets.QTableWidget()
        headers = (
            "Segment",
            "Start",
            "Stop",
            "Channel",
            "Peak BPM",
            "Welch BPM",
            "Confidence",
            "SNR dB",
            "Motion",
            "IMU quality",
            "Result",
        )
        self.window_table.setColumnCount(len(headers))
        self.window_table.setHorizontalHeaderLabels(headers)
        self.window_table.setSelectionBehavior(
            QtWidgets.QAbstractItemView.SelectionBehavior.SelectRows
        )
        self.window_table.setEditTriggers(
            QtWidgets.QAbstractItemView.EditTrigger.NoEditTriggers
        )
        self.window_table.itemSelectionChanged.connect(
            self._select_window_from_table
        )
        self.window_table.horizontalHeader().setStretchLastSection(True)
        splitter.addWidget(self.window_table)
        splitter.setSizes((560, 240))
        layout.addWidget(splitter)
        self.tabs.addTab(tab, "PPG")

    def _build_imu_tab(self) -> None:
        tab = QtWidgets.QWidget()
        layout = QtWidgets.QVBoxLayout(tab)
        controls = QtWidgets.QHBoxLayout()
        self.imu_source = QtWidgets.QComboBox()
        self.imu_source.addItem("Selected source", "selected")
        self.imu_source.addItem("Accelerometer", "accel")
        self.imu_source.addItem("Gyroscope", "gyro")
        self.imu_stage = QtWidgets.QComboBox()
        self.imu_stage.addItem("Physical overview", "physical")
        self.imu_stage.addItem("Raw XYZ", "raw_xyz")
        self.imu_stage.addItem("Corrected XYZ", "corrected_xyz")
        self.imu_stage.addItem("Mechanical axis", "mechanical")
        self.imu_stage.addItem("Cardiac diagnostic", "cardiac")
        self.imu_align_polarity = QtWidgets.QCheckBox(
            "Align selected polarity upward"
        )
        self.imu_align_polarity.setChecked(True)
        self.imu_show_initial = QtWidgets.QCheckBox(
            "Show threshold-consensus peaks"
        )
        self.imu_show_initial.setChecked(True)
        self.imu_show_ppg_reference = QtWidgets.QCheckBox(
            "Show PPG reference"
        )
        self.imu_show_ppg_reference.setChecked(True)
        self.imu_zoom_slider = QtWidgets.QSlider(
            QtCore.Qt.Orientation.Horizontal
        )
        self.imu_zoom_slider.setRange(1, 20)
        self.imu_zoom_slider.setValue(1)
        self.imu_zoom_slider.setMaximumWidth(150)
        self.imu_fit_button = QtWidgets.QPushButton("Fit")
        self.imu_fit_button.setProperty("compact", True)
        self.imu_source.currentIndexChanged.connect(self._refresh_imu_view)
        self.imu_stage.currentIndexChanged.connect(self._refresh_imu_view)
        self.imu_align_polarity.toggled.connect(self._refresh_imu_view)
        self.imu_show_initial.toggled.connect(self._refresh_imu_view)
        self.imu_show_ppg_reference.toggled.connect(self._refresh_imu_view)
        self.imu_zoom_slider.valueChanged.connect(self._apply_imu_zoom)
        self.imu_fit_button.clicked.connect(self._fit_imu_range)
        controls.addWidget(QtWidgets.QLabel("Source"))
        controls.addWidget(self.imu_source)
        controls.addWidget(QtWidgets.QLabel("Main view"))
        controls.addWidget(self.imu_stage)
        controls.addWidget(self.imu_align_polarity)
        controls.addWidget(self.imu_show_initial)
        controls.addWidget(self.imu_show_ppg_reference)
        controls.addStretch(1)
        controls.addWidget(QtWidgets.QLabel("Zoom"))
        controls.addWidget(self.imu_zoom_slider)
        controls.addWidget(self.imu_fit_button)
        layout.addLayout(controls)

        self.imu_summary = QtWidgets.QLabel(
            "No inertial cardiac-candidate analysis loaded."
        )
        self.imu_summary.setWordWrap(True)
        self.imu_summary.setObjectName("mutedText")
        layout.addWidget(self.imu_summary)
        splitter = QtWidgets.QSplitter(QtCore.Qt.Orientation.Vertical)
        self.imu_main_plot = self._make_plot(
            "IMU source and processing stage", "Amplitude"
        )
        self.imu_main_plot.addLegend()
        self.imu_candidate_plot = self._make_plot(
            "Polarity-aligned cardiac candidate", "Normalized amplitude"
        )
        self.imu_candidate_plot.addLegend()
        self.imu_similarity_plot = self._make_plot(
            "Template normalized cross-correlation", "NCC"
        )
        self.imu_similarity_plot.addLegend()
        splitter.addWidget(self.imu_main_plot)
        splitter.addWidget(self.imu_candidate_plot)
        splitter.addWidget(self.imu_similarity_plot)
        splitter.setSizes((330, 330, 180))
        layout.addWidget(splitter, stretch=1)
        self.tabs.addTab(tab, "IMU")

    def _build_cycle_tab(self) -> None:
        tab = QtWidgets.QWidget()
        layout = QtWidgets.QVBoxLayout(tab)
        self.psd_plot = pg.PlotWidget(title="Selected-segment Welch PSD")
        self.psd_plot.setBackground("#10151b")
        self.psd_plot.showGrid(x=True, y=True, alpha=0.22)
        self.psd_plot.setLabel("bottom", "Frequency", units="Hz")
        self.psd_plot.setLabel("left", "PSD")
        self.psd_curve = self.psd_plot.plot(pen="#45caff")
        self.cycle_plot = pg.PlotWidget(title="Average cardiac cycle")
        self.cycle_plot.setBackground("#10151b")
        self.cycle_plot.showGrid(x=True, y=True, alpha=0.22)
        self.cycle_plot.setLabel("bottom", "Normalized phase")
        self.cycle_plot.setLabel("left", "AC")
        self.cycle_mean_curve = self.cycle_plot.plot(
            pen=pg.mkPen("#45caff", width=2)
        )
        self.cycle_upper_curve = self.cycle_plot.plot(
            pen=pg.mkPen("#80bfff", style=QtCore.Qt.PenStyle.DashLine)
        )
        self.cycle_lower_curve = self.cycle_plot.plot(
            pen=pg.mkPen("#80bfff", style=QtCore.Qt.PenStyle.DashLine)
        )
        self.imu_cycle_plot = pg.PlotWidget(
            title="Average inertial cardiac-candidate cycle"
        )
        self.imu_cycle_plot.setBackground("#10151b")
        self.imu_cycle_plot.showGrid(x=True, y=True, alpha=0.22)
        self.imu_cycle_plot.setLabel("bottom", "Normalized phase")
        self.imu_cycle_plot.setLabel("left", "Normalized amplitude")
        self.imu_cycle_mean_curve = self.imu_cycle_plot.plot(
            pen=pg.mkPen("#55dd88", width=2)
        )
        self.imu_cycle_upper_curve = self.imu_cycle_plot.plot(
            pen=pg.mkPen("#a0e8b8", style=QtCore.Qt.PenStyle.DashLine)
        )
        self.imu_cycle_lower_curve = self.imu_cycle_plot.plot(
            pen=pg.mkPen("#a0e8b8", style=QtCore.Qt.PenStyle.DashLine)
        )
        layout.addWidget(self.psd_plot)
        layout.addWidget(self.cycle_plot)
        layout.addWidget(self.imu_cycle_plot)
        self.tabs.addTab(tab, "Spectrum / cycle")

    def set_result(self, result: AnalysisResult, raw_file: Path) -> None:
        self.result = result
        self.raw_file = raw_file
        pulse = result.pulse_analysis
        bpm = (
            f"{pulse.bpm:.2f} bpm" if pulse.bpm is not None else "unavailable"
        )
        if pulse.spectral_bpm is not None:
            summary_text = (
                f"{raw_file.name} | HR {bpm} | Welch "
                f"{pulse.spectral_bpm:.2f} bpm | confidence "
                f"{pulse.confidence:.0%} | {len(pulse.segments)} stable "
                f"segments | "
                f"{sum(window.accepted for window in pulse.windows)}/"
                f"{len(pulse.windows)} accepted windows"
            )
        else:
            summary_text = f"{raw_file.name} | no usable pulse estimate"
        if result.imu_cardiac.bpm is not None:
            summary_text += (
                f" | IMU {result.imu_cardiac.source} "
                f"{result.imu_cardiac.bpm:.2f} bpm"
            )
        self.summary.setText(summary_text)
        self.channel.blockSignals(True)
        self.channel.setCurrentIndex(0)
        self.channel.blockSignals(False)
        self.segment.blockSignals(True)
        self.segment.clear()
        self.segment.addItem("All stable segments", -1)
        for segment in pulse.segments:
            self.segment.addItem(
                f"{segment.index}: {segment.start_s:.2f}–"
                f"{segment.stop_s:.2f} s",
                segment.index,
            )
        self.segment.blockSignals(False)
        self.zoom_slider.setValue(1)
        self._populate_metric_cards()
        self._populate_diagnostics()
        self._refresh_all_views()

    def _populate_metric_cards(self) -> None:
        if self.result is None or self.raw_file is None:
            return
        result = self.result
        summary = result.summary
        pulse = result.pulse_analysis
        cardiac = result.imu_cardiac
        self.session_card.set_value(
            self.raw_file.parent.name,
            str(self.raw_file),
        )
        ppg_value = (
            f"{pulse.bpm:.2f} bpm · {len(pulse.peak_indices)} peaks"
            if pulse.bpm is not None
            else "No usable estimate"
        )
        self.ppg_card.set_value(
            ppg_value,
            f"Welch={_format_number(pulse.spectral_bpm)} bpm; "
            f"confidence={pulse.confidence:.1%}; channel={pulse.channel}",
        )
        if cardiac.bpm is None:
            imu_value = "No reliable candidate"
        else:
            delta = (
                f" · Δ {cardiac.ppg_bpm_difference:.2f}"
                if cardiac.ppg_bpm_difference is not None
                else ""
            )
            imu_value = f"{cardiac.bpm:.2f} bpm{delta}"
        self.imu_card.set_value(
            imu_value,
            f"{cardiac.source or 'n/a'} / {cardiac.polarity or 'n/a'} / "
            f"{cardiac.method or 'n/a'}; confidence={cardiac.confidence:.1%}",
        )
        sample_rate = float(summary.get("sample_rate_hz_estimated", 0.0))
        duration = float(summary.get("duration_s", 0.0))
        session_timing = summary.get("session_timing", {})
        if not isinstance(session_timing, dict):
            session_timing = {}
        ppg_session_timing = session_timing.get("ppg", {})
        imu_session_timing = session_timing.get("imu", {})
        if not isinstance(ppg_session_timing, dict):
            ppg_session_timing = {}
        if not isinstance(imu_session_timing, dict):
            imu_session_timing = {}
        ppg_count_rate = ppg_session_timing.get(
            "session_count_rate_hz"
        )
        imu_count_rate = imu_session_timing.get(
            "session_count_rate_hz"
        )
        session_duration = session_timing.get("session_duration_s")
        if ppg_count_rate is not None and imu_count_rate is not None:
            sampling_value = (
                f"PPG {float(ppg_count_rate):.1f} · "
                f"IMU {float(imu_count_rate):.1f} Hz"
            )
            duration_tooltip = (
                f"session={float(session_duration):.3f} s; "
                if session_duration is not None
                else ""
            )
            sampling_tooltip = (
                duration_tooltip
                + f"PPG={len(result.time_s)} samples; "
                f"IMU={len(result.imu_time_s)} samples; "
                "rates use SESSION_START/STOP duration"
            )
        else:
            sampling_value = f"{sample_rate:.1f} Hz · {duration:.1f} s"
            sampling_tooltip = (
                f"PPG={len(result.time_s)} samples; "
                f"IMU={len(result.imu_time_s)} samples"
            )
        self.duration_card.set_value(
            sampling_value,
            sampling_tooltip,
        )
        integrity = summary.get("integrity", {})
        if not isinstance(integrity, dict):
            integrity = {}
        gap_total = sum(
            int(summary.get(key, 0) or 0)
            for key in (
                "packet_gaps",
                "ppg_sample_gaps",
                "imu_sample_gaps",
            )
        )
        crc_errors = int(
            (summary.get("decoder", {}) or {}).get("crc_errors", 0)
            if isinstance(summary.get("decoder", {}), dict)
            else 0
        )
        usable = bool(summary.get("ppg_usable", False))
        integrity_ok = (
            gap_total == 0
            and crc_errors == 0
            and int(integrity.get("malformed_rows", 0) or 0) == 0
        )
        self.quality_card.set_value(
            f"{'USABLE' if usable else 'REVIEW'} · "
            f"{'clean' if integrity_ok else 'check integrity'}",
            f"gaps={gap_total}; CRC={crc_errors}; "
            f"PPG confidence={pulse.confidence:.1%}",
        )

    def _populate_diagnostics(self) -> None:
        if self.result is None:
            return
        self.diagnostic_tree.clear()

        def add_value(
            parent: QtWidgets.QTreeWidgetItem | None,
            key: str,
            value: object,
        ) -> None:
            if isinstance(value, dict):
                item = QtWidgets.QTreeWidgetItem((str(key), ""))
                if parent is None:
                    self.diagnostic_tree.addTopLevelItem(item)
                else:
                    parent.addChild(item)
                for child_key, child_value in value.items():
                    add_value(item, str(child_key), child_value)
                return
            if isinstance(value, (list, tuple)):
                text = ", ".join(str(entry) for entry in value)
            elif isinstance(value, float):
                text = (
                    f"{value:.8g}" if math.isfinite(value) else str(value)
                )
            else:
                text = str(value)
            item = QtWidgets.QTreeWidgetItem((str(key), text))
            if parent is None:
                self.diagnostic_tree.addTopLevelItem(item)
            else:
                parent.addChild(item)

        for key, value in self.result.summary.items():
            if key == "warnings":
                continue
            add_value(None, str(key), value)
        self.diagnostic_tree.resizeColumnToContents(0)
        warnings = self.result.summary.get("warnings", [])
        warning_lines = (
            [str(entry) for entry in warnings]
            if isinstance(warnings, (list, tuple))
            else [str(warnings)]
        )
        warning_lines.extend(
            (
                "",
                "Interpretation notes:",
                "• PPG and IMU peaks are algorithm outputs, not medical annotations.",
                "• The IMU candidate is not a validated SCG/JVP fiducial.",
                "• Display inversion, visibility, amplitude and zoom never change saved analysis.",
            )
        )
        self.messages_box.setPlainText(
            "\n".join(warning_lines)
            if warning_lines
            else "No analysis warnings."
        )

    @QtCore.Slot()
    def _refresh_all_views(self) -> None:
        self._populate_ppg()
        self._populate_imu()
        self._populate_cycle()
        self._populate_window_table()
        self._refresh_explorer()
        self._refresh_comparison()

    def _populate_ppg(self) -> None:
        if self.result is None:
            return
        result = self.result
        self.red_raw_curve.setData(result.time_s, result.red)
        self.ir_raw_curve.setData(result.time_s, result.ir)
        for region in self._regions:
            self.overview_plot.removeItem(region)
        self._regions.clear()
        if self.show_segments.isChecked():
            for segment in result.pulse_analysis.segments:
                region = pg.LinearRegionItem(
                    values=(segment.start_s, segment.stop_s),
                    movable=False,
                    brush=pg.mkBrush(70, 190, 110, 28),
                    pen=pg.mkPen(70, 190, 110, 90),
                )
                region.setZValue(-10)
                self.overview_plot.addItem(region)
                self._regions.append(region)
        self._refresh_ppg_detail()

    def _display_channel(self) -> str:
        if self.result is None:
            return "IR"
        if self.channel.currentIndex() == 1:
            return "RED"
        if self.channel.currentIndex() == 2:
            return "IR"
        return self.result.peak_channel or "IR"

    @QtCore.Slot()
    def _refresh_ppg_detail(self) -> None:
        if self.result is None:
            return
        channel = self._display_channel()
        values = (
            self.result.red_bandpass
            if channel == "RED"
            else self.result.ir_bandpass
        )
        self.detail_curve.setData(
            self.result.time_s,
            values,
            name=f"{channel} filtered",
        )
        if (
            self.show_peaks.isChecked()
            and channel == self.result.peak_channel
            and len(self.result.peak_indices)
        ):
            indices = self.result.peak_indices
            self.peak_scatter.setData(
                x=self.result.time_s[indices],
                y=values[indices],
            )
        else:
            self.peak_scatter.setData(x=[], y=[])
        self._update_psd()

    def _populate_imu(self) -> None:
        if self.result is None:
            return
        cardiac = self.result.imu_cardiac
        if cardiac.bpm is None:
            self.imu_summary.setText(
                "No reliable PPG-synchronous inertial cardiac candidates."
            )
        else:
            delta_text = (
                f"{cardiac.ppg_bpm_difference:.2f} bpm"
                if cardiac.ppg_bpm_difference is not None
                else "unavailable"
            )
            self.imu_summary.setText(
                f"Selected {cardiac.source} / {cardiac.polarity} | "
                f"{cardiac.method} | {cardiac.bpm:.2f} bpm | confidence "
                f"{cardiac.confidence:.0%} | {len(cardiac.peak_indices)} "
                f"final peaks (threshold {cardiac.initial_peak_count}) | "
                f"ΔPPG {delta_text} | NCC median "
                f"{_format_number(cardiac.template_median_correlation)}. "
                "Candidate timing only; not a validated SCG/JVP fiducial."
            )
        self._refresh_imu_view()

    def _clear_dynamic_plot(self, plot: pg.PlotWidget) -> None:
        plot.clear()
        legend = plot.getPlotItem().legend
        if legend is not None:
            legend.clear()
        else:
            plot.addLegend()

    def _resolved_imu_source(self) -> str:
        requested = str(self.imu_source.currentData())
        if requested != "selected":
            return requested
        if self.result is not None and self.result.imu_cardiac.source is not None:
            return str(self.result.imu_cardiac.source)
        return "accel"

    @QtCore.Slot()
    def _refresh_imu_view(self) -> None:
        if not hasattr(self, "imu_main_plot") or self.result is None:
            return
        result = self.result
        imu = result.imu_analysis
        cardiac = result.imu_cardiac
        source = self._resolved_imu_source()
        is_accel = source == "accel"
        source_name = "Acceleration" if is_accel else "Angular rate"
        unit = "m/s²" if is_accel else "rad/s"
        time = imu.time_s
        raw_xyz = imu.accel_m_s2 if is_accel else imu.gyro_rad_s
        corrected_xyz = (
            imu.linear_accel_m_s2 if is_accel else imu.gyro_corrected_rad_s
        )
        raw_magnitude = (
            imu.accel_magnitude if is_accel else imu.gyro_magnitude
        )
        corrected_magnitude = (
            imu.linear_accel_magnitude
            if is_accel
            else imu.gyro_corrected_magnitude
        )
        mechanical = (
            imu.accel_mechanical if is_accel else imu.gyro_mechanical
        )
        selected_source = source == cardiac.source
        polarity_direction = (
            -1.0
            if (
                selected_source
                and self.imu_align_polarity.isChecked()
                and cardiac.polarity == "negative"
            )
            else 1.0
        )

        main = self.imu_main_plot
        self._clear_dynamic_plot(main)
        stage = str(self.imu_stage.currentData())
        if stage == "physical":
            main.plot(
                time,
                raw_magnitude,
                pen=pg.mkPen("#45caff", width=1.1),
                name="raw magnitude",
            )
            main.plot(
                time,
                corrected_magnitude,
                pen=pg.mkPen("#f1c40f", width=1.1),
                name="corrected magnitude",
            )
            main.setLabel("left", source_name, units=unit)
        elif stage in {"raw_xyz", "corrected_xyz"}:
            matrix = raw_xyz if stage == "raw_xyz" else corrected_xyz
            prefix = "raw" if stage == "raw_xyz" else "corrected"
            for index, (axis, color) in enumerate(
                zip(("x", "y", "z"), _SERIES_COLORS)
            ):
                main.plot(
                    time,
                    matrix[:, index],
                    pen=pg.mkPen(color, width=1.0),
                    name=f"{prefix} {axis}",
                )
            main.setLabel("left", source_name, units=unit)
        elif stage == "mechanical":
            main.plot(
                time,
                polarity_direction * mechanical,
                pen=pg.mkPen("#55dd88", width=1.25),
                name="mechanical axis",
            )
            main.setLabel("left", "Mechanical axis", units=unit)
        else:
            candidate = (
                cardiac.normalized_signal
                if selected_source
                else _robust_normalize(mechanical)
            )
            main.plot(
                time,
                polarity_direction * candidate,
                pen=pg.mkPen("#55dd88", width=1.25),
                name=(
                    "selected normalized candidate"
                    if selected_source
                    else "non-selected normalized axis"
                ),
            )
            main.setLabel("left", "Normalized amplitude")
        main.setLabel("bottom", "Device time", units="s")
        main.setTitle(
            f"{source_name} · {self.imu_stage.currentText()}"
        )

        candidate_plot = self.imu_candidate_plot
        self._clear_dynamic_plot(candidate_plot)
        candidate_values = (
            polarity_direction * cardiac.normalized_signal
            if selected_source
            else _robust_normalize(mechanical)
        )
        candidate_plot.plot(
            time,
            candidate_values,
            pen=pg.mkPen("#55dd88", width=1.15),
            name=(
                "polarity-aligned selected signal"
                if selected_source
                else "non-selected mechanical axis"
            ),
        )
        if selected_source:
            if (
                self.show_peaks.isChecked()
                and self.imu_show_initial.isChecked()
                and len(cardiac.initial_peak_indices)
            ):
                indices = cardiac.initial_peak_indices[
                    (cardiac.initial_peak_indices >= 0)
                    & (cardiac.initial_peak_indices < len(candidate_values))
                ]
                candidate_plot.addItem(
                    pg.ScatterPlotItem(
                        x=time[indices],
                        y=candidate_values[indices],
                        size=6,
                        symbol="t",
                        brush=pg.mkBrush("#ff9955"),
                        pen=None,
                        name="threshold consensus",
                    )
                )
            if self.show_peaks.isChecked() and len(cardiac.peak_indices):
                indices = cardiac.peak_indices[
                    (cardiac.peak_indices >= 0)
                    & (cardiac.peak_indices < len(candidate_values))
                ]
                correlations = cardiac.template_similarity[indices]
                strong = np.isfinite(correlations) & (
                    correlations >= TEMPLATE_MIN_CORRELATION
                )
                for mask, color, name in (
                    (
                        strong,
                        "#45caff",
                        f"final NCC ≥ {TEMPLATE_MIN_CORRELATION:.2f}",
                    ),
                    (
                        ~strong,
                        "#ff4f6d",
                        "final threshold fallback",
                    ),
                ):
                    selected = indices[mask]
                    if len(selected):
                        candidate_plot.addItem(
                            pg.ScatterPlotItem(
                                x=time[selected],
                                y=candidate_values[selected],
                                size=8,
                                brush=pg.mkBrush(color),
                                pen=pg.mkPen("#ffffff", width=0.4),
                                name=name,
                            )
                        )
            if self.imu_show_ppg_reference.isChecked():
                self._add_ppg_reference_lines(candidate_plot)
        if self.show_segments.isChecked():
            self._add_imu_stable_regions(candidate_plot)
        candidate_plot.setLabel("bottom", "Device time", units="s")
        candidate_plot.setLabel("left", "Normalized amplitude")
        candidate_plot.setTitle(
            (
                f"Selected {cardiac.source} / {cardiac.polarity} / "
                f"{cardiac.method}"
            )
            if selected_source
            else f"{source_name} was not retained by the detector"
        )

        similarity_plot = self.imu_similarity_plot
        self._clear_dynamic_plot(similarity_plot)
        if selected_source and np.any(np.isfinite(cardiac.template_similarity)):
            similarity_plot.plot(
                time,
                cardiac.template_similarity,
                pen=pg.mkPen("#bc7cff", width=1.0),
                name="template NCC",
            )
            similarity_plot.addItem(
                pg.InfiniteLine(
                    pos=TEMPLATE_MIN_CORRELATION,
                    angle=0,
                    pen=pg.mkPen(
                        "#ff9955",
                        width=1.0,
                        style=QtCore.Qt.PenStyle.DashLine,
                    ),
                    label="accepted NCC threshold",
                )
            )
            if self.show_peaks.isChecked() and len(cardiac.peak_indices):
                indices = cardiac.peak_indices
                similarity_plot.addItem(
                    pg.ScatterPlotItem(
                        x=time[indices],
                        y=cardiac.template_similarity[indices],
                        size=6,
                        brush=pg.mkBrush("#45caff"),
                        pen=None,
                        name="selected NCC peaks",
                    )
                )
        similarity_plot.setLabel("bottom", "Device time", units="s")
        similarity_plot.setLabel("left", "Normalized correlation")
        similarity_plot.setTitle(
            "Template quality at candidate locations"
            if selected_source
            else "Template similarity is available only for the selected source"
        )
        self._apply_imu_zoom()

    def _add_ppg_reference_lines(self, plot: pg.PlotWidget) -> None:
        if self.result is None:
            return
        valid = self.result.peak_indices[
            (self.result.peak_indices >= 0)
            & (self.result.peak_indices < len(self.result.time_s))
        ]
        for beat_time in self.result.time_s[valid]:
            plot.addItem(
                pg.InfiniteLine(
                    pos=float(beat_time),
                    angle=90,
                    pen=pg.mkPen(255, 195, 30, 55, width=0.7),
                )
            )

    def _add_imu_stable_regions(self, plot: pg.PlotWidget) -> None:
        if self.result is None:
            return
        for segment in self.result.pulse_analysis.segments:
            region = pg.LinearRegionItem(
                values=(segment.start_s, segment.stop_s),
                movable=False,
                brush=pg.mkBrush(70, 190, 110, 18),
                pen=pg.mkPen(70, 190, 110, 55),
            )
            region.setZValue(-20)
            plot.addItem(region)

    @QtCore.Slot()
    def _fit_imu_range(self) -> None:
        self.imu_zoom_slider.blockSignals(True)
        self.imu_zoom_slider.setValue(1)
        self.imu_zoom_slider.blockSignals(False)
        self._apply_imu_zoom()

    @QtCore.Slot()
    def _apply_imu_zoom(self) -> None:
        if self.result is None or not len(self.result.imu_time_s):
            return
        start = float(self.result.imu_time_s[0])
        stop = float(self.result.imu_time_s[-1])
        selected = self._selected_segment_range()
        if selected is not None:
            start, stop = selected
        zoom = max(1, self.imu_zoom_slider.value())
        span = max(np.finfo(float).eps, stop - start)
        width = span / zoom
        current = (
            self.imu_candidate_plot.getPlotItem()
            .getViewBox()
            .viewRange()[0]
        )
        center = (
            0.5 * (current[0] + current[1])
            if current[1] > current[0] and zoom > 1
            else 0.5 * (start + stop)
        )
        center = min(max(center, start + width / 2), stop - width / 2)
        for plot in (
            self.imu_main_plot,
            self.imu_candidate_plot,
            self.imu_similarity_plot,
        ):
            plot.setXRange(
                center - width / 2,
                center + width / 2,
                padding=0.01,
            )

    def _sync_imu_from_workbench(self) -> None:
        if not hasattr(self, "imu_source"):
            return
        source = str(self.explorer_source.currentData())
        if source not in {"accel", "gyro"}:
            return
        source_index = self.imu_source.findData(source)
        stage_map = {
            "raw_xyz": "raw_xyz",
            "corrected_xyz": "corrected_xyz",
            "magnitude": "physical",
            "mechanical": "mechanical",
            "peaks": "cardiac",
            "cycle": "cardiac",
        }
        stage = stage_map.get(
            str(self.explorer_stage.currentData()), "physical"
        )
        stage_index = self.imu_stage.findData(stage)
        self.imu_source.blockSignals(True)
        self.imu_stage.blockSignals(True)
        self.imu_source.setCurrentIndex(max(0, source_index))
        self.imu_stage.setCurrentIndex(max(0, stage_index))
        self.imu_source.blockSignals(False)
        self.imu_stage.blockSignals(False)
        self._refresh_imu_view()

    def _populate_cycle(self) -> None:
        if self.result is None:
            return
        result = self.result
        self.cycle_mean_curve.setData(
            result.waveform_phase, result.waveform_mean
        )
        self.cycle_upper_curve.setData(
            result.waveform_phase,
            result.waveform_mean + result.waveform_ci95,
        )
        self.cycle_lower_curve.setData(
            result.waveform_phase,
            result.waveform_mean - result.waveform_ci95,
        )
        cardiac = result.imu_cardiac
        self.imu_cycle_mean_curve.setData(
            cardiac.waveform_phase, cardiac.waveform_mean
        )
        self.imu_cycle_upper_curve.setData(
            cardiac.waveform_phase,
            cardiac.waveform_mean + cardiac.waveform_ci95,
        )
        self.imu_cycle_lower_curve.setData(
            cardiac.waveform_phase,
            cardiac.waveform_mean - cardiac.waveform_ci95,
        )
        self.imu_cycle_plot.setTitle(
            "Average inertial cardiac-candidate cycle"
            if cardiac.source is None
            else (
                f"Average {cardiac.source} cardiac-candidate cycle "
                f"({cardiac.waveform_cycle_count} cycles)"
            )
        )
        self._update_psd()

    def _populate_window_table(self) -> None:
        if self.result is None:
            return
        windows = self.result.pulse_analysis.windows
        self.window_table.setRowCount(len(windows))
        for row, window in enumerate(windows):
            data = window.as_dict()
            values = (
                data["segment_index"],
                f"{window.start_s:.2f}",
                f"{window.stop_s:.2f}",
                data["used_channel"] or "",
                _format_number(data["peak_bpm"]),
                _format_number(data["spectral_bpm"]),
                f"{float(data['confidence']):.0%}",
                _format_number(data["snr_db"]),
                f"{window.motion_rms_m_s2:.3f}",
                f"{window.imu_quality_score:.0%}",
                "accepted"
                if window.accepted
                else str(window.rejection_reason or "rejected"),
            )
            for column, value in enumerate(values):
                item = QtWidgets.QTableWidgetItem(str(value))
                if not window.accepted:
                    item.setForeground(QtGui.QColor("#999999"))
                self.window_table.setItem(row, column, item)
        self.window_table.resizeColumnsToContents()

    @QtCore.Slot()
    def _select_window_from_table(self) -> None:
        if self.result is None:
            return
        rows = self.window_table.selectionModel().selectedRows()
        if not rows:
            return
        window = self.result.pulse_analysis.windows[rows[0].row()]
        self.detail_plot.setXRange(
            window.start_s, window.stop_s, padding=0.02
        )
        for plot in (
            self.imu_main_plot,
            self.imu_candidate_plot,
            self.imu_similarity_plot,
        ):
            plot.setXRange(window.start_s, window.stop_s, padding=0.02)
        if self._explorer_time_domain:
            self.explorer_plot.setXRange(
                window.start_s, window.stop_s, padding=0.02
            )
        self.full_compare_plot.setXRange(
            window.start_s, window.stop_s, padding=0.02
        )

    @QtCore.Slot()
    def _select_segment(self) -> None:
        if self.result is None:
            return
        selected = int(self.segment.currentData())
        if selected < 0:
            if len(self.result.time_s):
                self.detail_plot.setXRange(
                    float(self.result.time_s[0]),
                    float(self.result.time_s[-1]),
                    padding=0.01,
                )
                if len(self.result.imu_time_s):
                    start = float(self.result.imu_time_s[0])
                    stop = float(self.result.imu_time_s[-1])
                    for plot in (
                        self.imu_main_plot,
                        self.imu_candidate_plot,
                        self.imu_similarity_plot,
                    ):
                        plot.setXRange(start, stop, padding=0.01)
        else:
            segment = next(
                (
                    item
                    for item in self.result.pulse_analysis.segments
                    if item.index == selected
                ),
                None,
            )
            if segment is None:
                return
            self.detail_plot.setXRange(
                segment.start_s, segment.stop_s, padding=0.02
            )
            for plot in (
                self.imu_main_plot,
                self.imu_candidate_plot,
                self.imu_similarity_plot,
            ):
                plot.setXRange(
                    segment.start_s, segment.stop_s, padding=0.02
                )
        self._update_psd()
        self._refresh_explorer()
        self._refresh_comparison()

    def _update_psd(self) -> None:
        if self.result is None:
            return
        values = (
            self.result.red_bandpass
            if self._display_channel() == "RED"
            else self.result.ir_bandpass
        )
        selected_segment = int(self.segment.currentData())
        mask = np.isfinite(values)
        if selected_segment >= 0:
            mask &= (
                self.result.pulse_analysis.sample_segment_index
                == selected_segment
            )
        selected = values[mask]
        sample_rate = float(
            self.result.summary.get("sample_rate_hz_estimated", 0.0)
        )
        if len(selected) < 32 or sample_rate <= 0.0:
            self.psd_curve.setData([], [])
            return
        frequencies, power = signal.welch(
            selected,
            fs=sample_rate,
            nperseg=min(len(selected), int(sample_rate * 8)),
            detrend="linear",
        )
        cardiac = (frequencies >= 0.5) & (frequencies <= 4.0)
        self.psd_curve.setData(frequencies[cardiac], power[cardiac])

    @QtCore.Slot()
    def _export_view(self) -> None:
        if self.result is None:
            return
        default = (
            self.raw_file.with_name("analysis_view.png")
            if self.raw_file is not None
            else Path("analysis_view.png")
        )
        filename, _ = QtWidgets.QFileDialog.getSaveFileName(
            self,
            "Export current analysis view",
            str(default),
            "PNG image (*.png)",
        )
        if filename:
            self.grab().save(filename, "PNG")


class AnalysisWindow(QtWidgets.QMainWindow):
    def __init__(self, raw_file: Path | None = None) -> None:
        super().__init__()
        self.setWindowTitle("PPG Analysis Studio")
        self.resize(1580, 980)
        self.worker: AnalysisWorker | None = None
        self.setStyleSheet(
            """
            QMainWindow, QWidget {
                font-family: "Segoe UI";
                font-size: 10pt;
            }
            QLabel#workspaceTitle {
                font-size: 18pt;
                font-weight: 700;
            }
            QLabel#workspaceSummary, QLabel#mutedText {
                color: palette(mid);
            }
            QFrame#metricCard {
                border: 1px solid palette(midlight);
                border-radius: 7px;
                background: palette(base);
            }
            QLabel#metricTitle {
                color: palette(mid);
                font-size: 8pt;
                font-weight: 700;
            }
            QLabel#metricValue {
                font-size: 11pt;
                font-weight: 600;
            }
            QGroupBox {
                font-weight: 600;
                margin-top: 8px;
                padding-top: 8px;
            }
            QPushButton {
                padding: 5px 10px;
            }
            QPushButton[compact="true"] {
                padding: 3px 6px;
            }
            QTabBar::tab {
                padding: 7px 13px;
            }
            """
        )

        central = QtWidgets.QWidget()
        layout = QtWidgets.QVBoxLayout(central)
        layout.setContentsMargins(10, 8, 10, 10)
        actions = QtWidgets.QHBoxLayout()
        self.open_button = QtWidgets.QPushButton("Open raw.ppgbin")
        self.open_button.clicked.connect(self.open_dialog)
        self.status = QtWidgets.QLabel("Open a raw session to begin.")
        self.status.setTextInteractionFlags(
            QtCore.Qt.TextInteractionFlag.TextSelectableByMouse
        )
        self.progress = QtWidgets.QProgressBar()
        self.progress.setRange(0, 0)
        self.progress.setMaximumWidth(220)
        self.progress.setVisible(False)
        actions.addWidget(self.open_button)
        actions.addWidget(self.status, stretch=1)
        actions.addWidget(self.progress)
        layout.addLayout(actions)
        self.workspace = AnalysisWorkspace()
        layout.addWidget(self.workspace, stretch=1)
        self.setCentralWidget(central)

        if raw_file is not None:
            QtCore.QTimer.singleShot(0, lambda: self.analyze(raw_file))

    @QtCore.Slot()
    def open_dialog(self) -> None:
        filename, _ = QtWidgets.QFileDialog.getOpenFileName(
            self,
            "Open binary PPG session",
            "captures",
            "PPG binary session (raw.ppgbin *.ppgbin)",
        )
        if filename:
            self.analyze(Path(filename))

    def analyze(self, raw_file: Path) -> None:
        if self.worker is not None and self.worker.isRunning():
            return
        self.open_button.setEnabled(False)
        self.progress.setVisible(True)
        self.status.setText(f"Analyzing {raw_file} ...")
        self.worker = AnalysisWorker(raw_file, raw_file.with_name("analysis"), self)
        self.worker.result_ready.connect(self._analysis_ready)
        self.worker.failed.connect(self._analysis_failed)
        self.worker.finished.connect(self._analysis_finished)
        self.worker.start()

    @QtCore.Slot(object, str)
    def _analysis_ready(self, result: AnalysisResult, filename: str) -> None:
        self.workspace.set_result(result, Path(filename))
        self.status.setText(f"Analysis ready: {Path(filename).parent}")

    @QtCore.Slot(str)
    def _analysis_failed(self, message: str) -> None:
        self.status.setText(f"Analysis failed: {message}")

    @QtCore.Slot()
    def _analysis_finished(self) -> None:
        self.open_button.setEnabled(True)
        self.progress.setVisible(False)


def _format_number(value: object) -> str:
    if value is None:
        return ""
    number = float(value)
    return f"{number:.2f}" if math.isfinite(number) else ""
