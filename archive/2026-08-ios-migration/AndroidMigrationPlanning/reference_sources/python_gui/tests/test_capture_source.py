import argparse

import pytest

from ppg_monitor.capture_source import (
    SourceSpec,
    add_source_arguments,
    source_spec_from_args,
)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser()
    add_source_arguments(parser)
    return parser


def test_serial_source_spec_remains_backward_compatible() -> None:
    parser = _parser()
    args = parser.parse_args(["COM14", "--transport", "bluetooth"])
    spec = source_spec_from_args(parser, args)

    assert spec == SourceSpec(
        port="COM14",
        transport="bluetooth",
        baud=115200,
    )
    assert spec.label == "serial:COM14"
    assert not spec.automatic_binary_control


def test_ble_source_spec_enables_automatic_control() -> None:
    parser = _parser()
    args = parser.parse_args(["--ble-name", "PPG-NRF-9EDA"])
    spec = source_spec_from_args(parser, args)

    assert spec.requested_transport == "ble"
    assert spec.label == "ble:PPG-NRF-9EDA"
    assert spec.automatic_binary_control
    assert spec.baud == 115200


def test_source_rejects_port_and_ble_target() -> None:
    parser = _parser()
    args = parser.parse_args(["COM14", "--ble-name", "PPG-NRF-9EDA"])
    with pytest.raises(SystemExit):
        source_spec_from_args(parser, args)
