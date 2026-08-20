"""Verify frozen heart-rate traces and their XCTest resource mirror."""

from __future__ import annotations

import hashlib
import json
import unittest
from pathlib import Path

import numpy as np

import generate_heart_rate_vectors as generator


HERE = Path(__file__).resolve().parent
FIXTURE = HERE / "heart_rate_vectors.json"


class HeartRateVectorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.payload = json.loads(FIXTURE.read_text(encoding="utf-8"))

    def test_schema_environment_and_source_identity(self):
        self.assertEqual(self.payload["schema"], generator.SCHEMA)
        self.assertEqual(
            self.payload["algorithm_version"],
            generator.ALGORITHM_VERSION,
        )
        self.assertEqual(
            self.payload["environment"],
            {
                "python": "3.11.15",
                "numpy": "2.4.6",
                "scipy": "1.17.1",
            },
        )
        self.assertEqual(
            self.payload["source"]["sha256"],
            hashlib.sha256(generator.PULSE_SOURCE.read_bytes()).hexdigest(),
        )

    def test_all_intermediate_traces_are_reproducible(self):
        for case in self.payload["cases"]:
            with self.subTest(case=case["name"]):
                actual = generator._case(
                    case["name"],
                    np.asarray(case["time_s"], dtype=float),
                    np.asarray(case["values"], dtype=float),
                )
                self.assertEqual(actual, case)

    def test_expected_boundary_outcomes_are_frozen(self):
        cases = {
            case["name"]: case
            for case in self.payload["cases"]
        }
        self.assertEqual(
            cases["positive_72bpm_8s"]["expected"]["polarity"],
            "positive",
        )
        self.assertEqual(
            cases["negative_72bpm_8s"]["expected"]["polarity"],
            "negative",
        )
        self.assertIsNotNone(
            cases["positive_150bpm_4s"]["expected"]["bpm"]
        )
        self.assertEqual(
            cases["deterministic_noise_8s"]["expected"][
                "unavailable_reason"
            ],
            "lowConfidence",
        )
        self.assertEqual(
            cases["constant_4s"]["expected"]["unavailable_reason"],
            "insufficientAmplitude",
        )
        self.assertEqual(
            cases["too_short_3_99s"]["expected"]["unavailable_reason"],
            "insufficientSamples",
        )

    def test_xctest_resource_is_an_exact_mirror(self):
        self.assertEqual(
            FIXTURE.read_bytes(),
            generator.SWIFT_OUTPUT.read_bytes(),
        )


if __name__ == "__main__":
    unittest.main()
