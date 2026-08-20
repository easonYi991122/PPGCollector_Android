"""Inspect a protocol-v1 raw session without modifying the raw file."""

from __future__ import annotations

import argparse
from pathlib import Path

from ppg_monitor.binary_analysis import analyze_binary_file


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("raw_file", type=Path, help="session raw.ppgbin file")
    parser.add_argument("-o", "--output-dir", type=Path, default=None)
    args = parser.parse_args()

    output_dir = args.output_dir or args.raw_file.with_name("analysis")
    result = analyze_binary_file(args.raw_file, output_dir)
    print(result.summary_json())
    print(f"Analysis written to {output_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
