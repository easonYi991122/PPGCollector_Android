"""Append-only raw capture container for protocol v1 byte chunks."""

from __future__ import annotations

from pathlib import Path
import struct
import time
from typing import Iterator
import os


MAGIC = b"PPGBIN1\0"
RECORD_STRUCT = struct.Struct("<QI")


class RawWriter:
    def __init__(self, path: Path) -> None:
        self.path = path
        self._stream = path.open("wb")
        self._stream.write(MAGIC)
        self.bytes_written = len(MAGIC)
        self.records = 0

    def write(self, data: bytes, host_time_ns: int | None = None) -> None:
        if not data:
            return
        timestamp = time.monotonic_ns() if host_time_ns is None else host_time_ns
        self._stream.write(RECORD_STRUCT.pack(timestamp, len(data)))
        self._stream.write(data)
        self.bytes_written += RECORD_STRUCT.size + len(data)
        self.records += 1

    def flush(self) -> None:
        self._stream.flush()
        os.fsync(self._stream.fileno())

    def close(self) -> None:
        self.flush()
        self._stream.close()

    def __enter__(self) -> "RawWriter":
        return self

    def __exit__(self, *_: object) -> None:
        self.close()


def iter_chunks(path: Path) -> Iterator[tuple[int, bytes]]:
    with path.open("rb") as stream:
        if stream.read(len(MAGIC)) != MAGIC:
            raise ValueError("invalid PPGBIN magic")
        while True:
            header = stream.read(RECORD_STRUCT.size)
            if not header:
                return
            if len(header) != RECORD_STRUCT.size:
                return
            host_time_ns, length = RECORD_STRUCT.unpack(header)
            data = stream.read(length)
            if len(data) != length:
                return
            yield host_time_ns, data
