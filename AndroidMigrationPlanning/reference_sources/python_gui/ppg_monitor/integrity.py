"""Sequence integrity helpers shared by live capture and replay."""

from __future__ import annotations

from dataclasses import dataclass


UINT32_MASK = 0xFFFFFFFF
UINT32_HALF_RANGE = 0x80000000


@dataclass(slots=True)
class SequenceTracker:
    previous: int | None = None
    missing: int = 0
    duplicates: int = 0
    out_of_order: int = 0

    def observe(self, value: int) -> None:
        value &= UINT32_MASK
        if self.previous is None:
            self.previous = value
            return

        delta = (value - self.previous) & UINT32_MASK
        if delta == 0:
            self.duplicates += 1
            return
        if delta < UINT32_HALF_RANGE:
            self.missing += delta - 1
            self.previous = value
            return

        self.out_of_order += 1

