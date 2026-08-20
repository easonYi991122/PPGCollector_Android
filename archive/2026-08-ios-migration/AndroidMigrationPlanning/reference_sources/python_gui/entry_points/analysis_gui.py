"""Open an interactive viewer for a captured binary PPG session."""

from __future__ import annotations

import argparse
from pathlib import Path
import sys


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "raw_file",
        type=Path,
        nargs="?",
        help="captured raw.ppgbin; omit to choose a file in the GUI",
    )
    args = parser.parse_args()

    try:
        from PySide6 import QtWidgets

        from ppg_monitor.analysis_gui import AnalysisWindow
    except ImportError as error:
        print(
            "Analysis GUI dependencies are missing. Update tools/environment.yml "
            "inside the ppg-monitor environment."
        )
        print(error)
        return 2

    app = QtWidgets.QApplication(sys.argv)
    window = AnalysisWindow(args.raw_file)
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
