from __future__ import annotations

import os

import numpy as np
import pytest


os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
pytest.importorskip("PySide6")
pytest.importorskip("pyqtgraph")

from PySide6 import QtWidgets

from ppg_monitor.analysis_gui import (
    AnalysisWorkspace,
    _average_by_peak_times,
    _robust_normalize,
)

_QT_APP = QtWidgets.QApplication.instance() or QtWidgets.QApplication([])


def test_robust_normalize_handles_nonfinite_and_constant_values() -> None:
    normalized = _robust_normalize(
        np.array([np.nan, 5.0, 5.0, np.inf], dtype=np.float64)
    )

    assert np.all(np.isfinite(normalized))
    assert np.allclose(normalized, 0.0)


def test_average_by_peak_times_preserves_reference_cycle() -> None:
    sample_rate = 200.0
    time_s = np.arange(0.0, 8.0, 1.0 / sample_rate)
    values = np.sin(2.0 * np.pi * time_s)
    peak_times = np.arange(0.25, 7.26, 1.0)

    mean, ci95, count = _average_by_peak_times(
        time_s,
        values,
        peak_times,
        points=160,
    )

    assert mean.shape == (160,)
    assert ci95.shape == (160,)
    assert count == len(peak_times) - 1
    assert np.max(ci95) < 1e-10


def test_analysis_workspace_exposes_workbench_and_comparison_controls() -> None:
    workspace = AnalysisWorkspace()
    workspace.resize(1400, 900)
    workspace.show()
    _QT_APP.processEvents()

    sidebar = workspace.main_splitter.widget(0)
    assert sidebar.minimumWidth() >= 340
    assert workspace.main_splitter.sizes()[0] >= 340
    assert sidebar.horizontalScrollBar().maximum() == 0
    assert workspace.sidebar_panel.width() == sidebar.viewport().width()
    assert [workspace.tabs.tabText(index) for index in range(workspace.tabs.count())] == [
        "Workbench",
        "PPG",
        "IMU",
        "Compare",
        "Spectrum / cycle",
        "Diagnostics",
    ]
    assert workspace.explorer_source.count() == 5
    assert workspace.imu_source.count() == 3
    assert workspace.imu_stage.count() == 5
    assert set(workspace._comparison_controls) == {
        "ppg_red",
        "ppg_ir",
        "accel",
        "gyro",
    }

    workspace.close()
    workspace.deleteLater()
    _QT_APP.processEvents()
