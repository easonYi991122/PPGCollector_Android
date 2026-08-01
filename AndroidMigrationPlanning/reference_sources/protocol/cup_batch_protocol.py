"""Executable reference for CUP Batch Protocol v1.

This module is migration-only and intentionally independent from the current
nRF52840 Protocol v1. BLE notification boundaries are treated as arbitrary
byte chunks.
"""

from __future__ import annotations

from dataclasses import dataclass
import struct
from typing import Iterable


HEADER = b"\xAB\xBA"
TAIL = b"\xCD\xDC"
FUNCTION_BATCH = 0x15
SAMPLE_RATE_HZ = 100
SAMPLES_PER_FRAME = 50
SAMPLE_WIRE_SIZE = 8
DATA_LENGTH = 1 + SAMPLES_PER_FRAME * SAMPLE_WIRE_SIZE
FRAME_LENGTH = 2 + 1 + 2 + DATA_LENGTH + 2


class CUPProtocolError(ValueError):
    """A complete candidate frame violates CUP Batch Protocol v1."""


@dataclass(frozen=True)
class PPGSample:
    red: int
    ir: int


@dataclass(frozen=True)
class BatchFrame:
    sequence: int
    samples: tuple[PPGSample, ...]


@dataclass
class DecoderStats:
    frames: int = 0
    bytes_discarded: int = 0
    invalid_function: int = 0
    invalid_length: int = 0
    invalid_tail: int = 0


@dataclass
class SequenceStats:
    previous: int | None = None
    missing_frames: int = 0
    duplicates: int = 0
    out_of_order: int = 0

    def observe(self, sequence: int) -> None:
        sequence &= 0xFF
        if self.previous is None:
            self.previous = sequence
            return
        delta = (sequence - self.previous) & 0xFF
        if delta == 0:
            self.duplicates += 1
        elif delta < 0x80:
            self.missing_frames += delta - 1
            self.previous = sequence
        else:
            self.out_of_order += 1


def encode_frame(sequence: int, samples: Iterable[PPGSample]) -> bytes:
    values = tuple(samples)
    if len(values) != SAMPLES_PER_FRAME:
        raise CUPProtocolError(
            f"expected {SAMPLES_PER_FRAME} samples, got {len(values)}"
        )
    payload = bytearray([sequence & 0xFF])
    for sample in values:
        if not 0 <= sample.red <= 0xFFFFFFFF:
            raise CUPProtocolError("red does not fit uint32")
        if not 0 <= sample.ir <= 0xFFFFFFFF:
            raise CUPProtocolError("ir does not fit uint32")
        payload.extend(struct.pack("<II", sample.red, sample.ir))
    assert len(payload) == DATA_LENGTH
    return (
        HEADER
        + bytes([FUNCTION_BATCH])
        + struct.pack("<H", len(payload))
        + payload
        + TAIL
    )


def decode_frame(data: bytes) -> BatchFrame:
    if len(data) != FRAME_LENGTH:
        raise CUPProtocolError(
            f"expected {FRAME_LENGTH} bytes, got {len(data)}"
        )
    if data[:2] != HEADER:
        raise CUPProtocolError("invalid header")
    if data[2] != FUNCTION_BATCH:
        raise CUPProtocolError("invalid function")
    data_length = struct.unpack_from("<H", data, 3)[0]
    if data_length != DATA_LENGTH:
        raise CUPProtocolError("invalid data length")
    if data[-2:] != TAIL:
        raise CUPProtocolError("invalid tail")

    sequence = data[5]
    samples = tuple(
        PPGSample(*struct.unpack_from("<II", data, 6 + index * 8))
        for index in range(SAMPLES_PER_FRAME)
    )
    return BatchFrame(sequence=sequence, samples=samples)


class StreamDecoder:
    """Incrementally decode frames from arbitrary BLE byte chunks."""

    def __init__(self) -> None:
        self._buffer = bytearray()
        self.stats = DecoderStats()

    @property
    def pending_bytes(self) -> int:
        return len(self._buffer)

    def feed(self, chunk: bytes) -> list[BatchFrame]:
        if chunk:
            self._buffer.extend(chunk)
        frames: list[BatchFrame] = []

        while True:
            start = self._buffer.find(HEADER)
            if start < 0:
                keep = 1 if self._buffer.endswith(HEADER[:1]) else 0
                discard = len(self._buffer) - keep
                self.stats.bytes_discarded += discard
                if discard:
                    del self._buffer[:discard]
                break
            if start:
                self.stats.bytes_discarded += start
                del self._buffer[:start]

            if len(self._buffer) < 5:
                break
            if self._buffer[2] != FUNCTION_BATCH:
                self.stats.invalid_function += 1
                self.stats.bytes_discarded += 1
                del self._buffer[0]
                continue

            data_length = struct.unpack_from("<H", self._buffer, 3)[0]
            if data_length != DATA_LENGTH:
                self.stats.invalid_length += 1
                self.stats.bytes_discarded += 1
                del self._buffer[0]
                continue

            total_length = 2 + 1 + 2 + data_length + 2
            if len(self._buffer) < total_length:
                break
            if self._buffer[total_length - 2 : total_length] != TAIL:
                self.stats.invalid_tail += 1
                self.stats.bytes_discarded += 1
                del self._buffer[0]
                continue

            raw = bytes(self._buffer[:total_length])
            del self._buffer[:total_length]
            frame = decode_frame(raw)
            frames.append(frame)
            self.stats.frames += 1

        return frames
