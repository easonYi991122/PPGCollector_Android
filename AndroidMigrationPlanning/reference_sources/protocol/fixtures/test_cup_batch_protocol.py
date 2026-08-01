from __future__ import annotations

import json
from pathlib import Path
import sys
import unittest


FIXTURE_DIR = Path(__file__).resolve().parent
REPOSITORY_ROOT = FIXTURE_DIR.parents[2]
SWIFT_FIXTURE_DIR = (
    REPOSITORY_ROOT / "PPGCollectorTests" / "Resources" / "Protocol"
)
sys.path.insert(0, str(FIXTURE_DIR.parent))

from cup_batch_protocol import (  # noqa: E402
    FRAME_LENGTH,
    PPGSample,
    SAMPLES_PER_FRAME,
    SequenceStats,
    StreamDecoder,
    decode_frame,
    encode_frame,
)


def sample_frame(sequence: int) -> bytes:
    return encode_frame(
        sequence,
        (
            PPGSample(red=1000 + index, ir=2000 + index)
            for index in range(SAMPLES_PER_FRAME)
        ),
    )


class CUPBatchProtocolTests(unittest.TestCase):
    def test_xctest_resources_are_exact_mirrors(self) -> None:
        for name in ("golden_seq42.bin", "golden_seq42.json"):
            with self.subTest(name=name):
                self.assertEqual(
                    (FIXTURE_DIR / name).read_bytes(),
                    (SWIFT_FIXTURE_DIR / name).read_bytes(),
                )

    def test_committed_golden_vector_matches_binary_and_decoder(self) -> None:
        metadata = json.loads(
            (FIXTURE_DIR / "golden_seq42.json").read_text(encoding="utf-8")
        )
        wire = (FIXTURE_DIR / "golden_seq42.bin").read_bytes()
        self.assertEqual(len(wire), FRAME_LENGTH)
        self.assertEqual(wire.hex(), metadata["frame_hex"])
        decoded = decode_frame(wire)
        self.assertEqual(decoded.sequence, metadata["sequence"])
        self.assertEqual(
            [
                {"red": sample.red, "ir": sample.ir}
                for sample in decoded.samples
            ],
            metadata["samples"],
        )

    def test_fixed_layout_roundtrip(self) -> None:
        wire = sample_frame(42)
        self.assertEqual(len(wire), FRAME_LENGTH)
        self.assertEqual(wire[:6], bytes.fromhex("AB BA 15 91 01 2A"))
        self.assertEqual(wire[-2:], bytes.fromhex("CD DC"))
        decoded = decode_frame(wire)
        self.assertEqual(decoded.sequence, 42)
        self.assertEqual(decoded.samples[0], PPGSample(1000, 2000))
        self.assertEqual(decoded.samples[-1], PPGSample(1049, 2049))

    def test_all_fragment_sizes(self) -> None:
        wire = sample_frame(1) + sample_frame(2)
        for chunk_size in range(1, FRAME_LENGTH + 1):
            with self.subTest(chunk_size=chunk_size):
                decoder = StreamDecoder()
                frames = []
                for offset in range(0, len(wire), chunk_size):
                    frames.extend(decoder.feed(wire[offset : offset + chunk_size]))
                self.assertEqual([frame.sequence for frame in frames], [1, 2])
                self.assertEqual(decoder.stats.frames, 2)

    def test_noise_and_bad_tail_resynchronize(self) -> None:
        broken = bytearray(sample_frame(3))
        broken[-1] ^= 0xFF
        decoder = StreamDecoder()
        frames = decoder.feed(b"noise" + bytes(broken) + sample_frame(4))
        self.assertEqual([frame.sequence for frame in frames], [4])
        self.assertGreaterEqual(decoder.stats.invalid_tail, 1)
        self.assertGreaterEqual(decoder.stats.bytes_discarded, 5)

    def test_sequence_wrap_and_gap(self) -> None:
        stats = SequenceStats()
        for sequence in (254, 255, 0, 2, 2, 1):
            stats.observe(sequence)
        self.assertEqual(stats.missing_frames, 1)
        self.assertEqual(stats.duplicates, 1)
        self.assertEqual(stats.out_of_order, 1)


if __name__ == "__main__":
    unittest.main()
