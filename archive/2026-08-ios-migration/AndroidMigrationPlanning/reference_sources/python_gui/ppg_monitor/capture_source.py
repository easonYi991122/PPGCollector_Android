"""Shared serial/BLE connection selection for capture and GUI tools."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from typing import Any

import serial

from .ble_nus import BleNusConnection
from .transport import default_baud


@dataclass(frozen=True, slots=True)
class SourceSpec:
    port: str | None
    transport: str
    baud: int
    ble_name: str | None = None
    ble_address: str | None = None
    scan_timeout_s: float = 15.0
    command_timeout_s: float = 5.0

    @property
    def is_ble(self) -> bool:
        return self.ble_name is not None or self.ble_address is not None

    @property
    def requested_transport(self) -> str:
        return "ble" if self.is_ble else self.transport

    @property
    def automatic_binary_control(self) -> bool:
        return self.is_ble

    @property
    def label(self) -> str:
        if self.is_ble:
            return f"ble:{self.ble_address or self.ble_name}"
        return f"serial:{self.port}"


def add_source_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument(
        "port",
        nargs="?",
        help="Windows COM port for USB CDC or HC-05, for example COM14",
    )
    target = parser.add_mutually_exclusive_group()
    target.add_argument(
        "--ble-name",
        help="BLE advertised name, for example PPG-NRF-9EDA",
    )
    target.add_argument("--ble-address", help="BLE device address")
    parser.add_argument(
        "--transport",
        choices=("auto", "usb", "bluetooth"),
        default="auto",
        help="serial link type; ignored when a BLE target is selected",
    )
    parser.add_argument("--baud", type=int, default=None)
    parser.add_argument("--scan-timeout", type=float, default=15.0)
    parser.add_argument("--command-timeout", type=float, default=5.0)


def source_spec_from_args(
    parser: argparse.ArgumentParser,
    args: argparse.Namespace,
) -> SourceSpec:
    is_ble = bool(args.ble_name or args.ble_address)
    if is_ble and args.port:
        parser.error("do not provide a COM port with --ble-name/--ble-address")
    if not is_ble and not args.port:
        parser.error("provide a COM port or a BLE target")
    if args.scan_timeout <= 0 or args.command_timeout <= 0:
        parser.error("source timeouts must be greater than zero")
    return SourceSpec(
        port=args.port,
        transport=args.transport,
        baud=args.baud or default_baud(args.transport),
        ble_name=args.ble_name,
        ble_address=args.ble_address,
        scan_timeout_s=args.scan_timeout,
        command_timeout_s=args.command_timeout,
    )


def open_source(spec: SourceSpec) -> Any:
    if spec.is_ble:
        return BleNusConnection(
            name=spec.ble_name,
            address=spec.ble_address,
            scan_timeout_s=spec.scan_timeout_s,
            operation_timeout_s=spec.command_timeout_s,
        )
    return serial.Serial(spec.port, spec.baud, timeout=0.2)


def source_metadata(spec: SourceSpec, connection: Any) -> dict[str, object]:
    metadata: dict[str, object] = {
        "kind": "ble_nus" if spec.is_ble else "serial",
        "label": spec.label,
    }
    if spec.is_ble:
        metadata.update(
            {
                "name": getattr(connection, "device_name", None),
                "address": getattr(connection, "device_address", None),
                "mtu": getattr(connection, "mtu_size", None),
            }
        )
    else:
        metadata.update({"port": spec.port, "baud": spec.baud})
    return metadata

