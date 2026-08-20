"""Verify complete SQI traces and their XCTest resource mirror."""

from __future__ import annotations

import hashlib
import json
import unittest
from pathlib import Path

import numpy as np

import generate_sqi_vectors as generator


HERE = Path(__file__).resolve().parent
FIXTURE = HERE / "sqi_vectors.json"


class SQIVectorTests(unittest.TestCase):
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
            self.payload["preprocess_profile"],
            generator.PREPROCESS_PROFILE,
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
            hashlib.sha256(generator.SQI_SOURCE.read_bytes()).hexdigest(),
        )

    def test_complete_traces_are_reproducible(self):
        module = generator._load_sqi_module()
        for case in self.payload["cases"]:
            with self.subTest(case=case["name"]):
                actual = generator._case(
                    module,
                    case["name"],
                    np.asarray(case["signal"], dtype=float),
                )
                self.assertEqual(actual, case)

    def test_grade_fallback_and_failure_boundaries_are_frozen(self):
        cases = {
            case["name"]: case
            for case in self.payload["cases"]
        }
        self.assertEqual(
            cases["stable_good_8s"]["expected"]["grade"],
            "Good",
        )
        self.assertEqual(
            cases["distorted_fair_8s"]["expected"]["grade"],
            "Fair",
        )
        self.assertEqual(
            cases["noisy_poor_8s"]["expected"]["grade"],
            "Poor",
        )
        self.assertEqual(
            cases["narrow_fallback_good_8s"]["trace"][
                "peak_detection"
            ]["selected_mode"],
            "fallback",
        )
        self.assertEqual(
            cases["single_peak_template_failure_8s"]["expected"][
                "reason"
            ],
            "template_failed:at least two cycle indices required",
        )
        self.assertEqual(
            cases["edge_partial_cycle_failure_8s"]["expected"][
                "reason"
            ],
            (
                "template_failed:need >= 2 valid cycles "
                "to build quality trace"
            ),
        )
        self.assertEqual(
            cases["edge_partial_cycle_failure_8s"]["trace"]["cycles"][
                "cycle_valid_mask"
            ],
            [False, True],
        )
        self.assertEqual(
            cases["constant_8s"]["expected"]["reason"],
            "constant_signal",
        )
        self.assertEqual(
            cases["too_short_3s"]["expected"]["reason"],
            "signal_too_short",
        )

    def test_xctest_resource_is_an_exact_mirror(self):
        self.assertEqual(
            FIXTURE.read_bytes(),
            generator.SWIFT_OUTPUT.read_bytes(),
        )


if __name__ == "__main__":
    unittest.main()
