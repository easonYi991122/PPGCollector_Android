"""Generate deterministic CUP Batch Protocol v1 cross-language fixtures."""

from __future__ import annotations

import json
from pathlib import Path
import sys


FIXTURE_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(FIXTURE_DIR.parent))

from cup_batch_protocol import (  # noqa: E402
    DATA_LENGTH,
    FRAME_LENGTH,
    SAMPLE_RATE_HZ,
    SAMPLES_PER_FRAME,
    PPGSample,
    encode_frame,
)


def main() -> None:
    samples = tuple(
        PPGSample(red=100_000 + index * 17, ir=120_000 + index * 23)
        for index in range(SAMPLES_PER_FRAME)
    )
    frame = encode_frame(42, samples)
    assert len(frame) == FRAME_LENGTH

    (FIXTURE_DIR / "golden_seq42.bin").write_bytes(frame)
    (FIXTURE_DIR / "golden_seq42.json").write_text(
        json.dumps(
            {
                "protocol": "cup_batch_protocol_v1",
                "sample_rate_hz": SAMPLE_RATE_HZ,
                "samples_per_frame": SAMPLES_PER_FRAME,
                "data_length": DATA_LENGTH,
                "frame_length": FRAME_LENGTH,
                "sequence": 42,
                "samples": [
                    {"red": sample.red, "ir": sample.ir}
                    for sample in samples
                ],
                "frame_hex": frame.hex(),
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()

