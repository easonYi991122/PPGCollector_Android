"""Verify the frozen preprocessing vectors and their XCTest mirror."""

from __future__ import annotations

import hashlib
import json
import math
import unittest
from pathlib import Path

import numpy as np

import generate_preprocessing_vectors as generator


HERE = Path(__file__).resolve().parent
FIXTURE = HERE / "preprocessing_vectors.json"


class PreprocessingVectorTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.payload = json.loads(FIXTURE.read_text(encoding="utf-8"))

    def test_schema_profile_environment_and_source_identity(self):
        self.assertEqual(self.payload["schema"], generator.SCHEMA)
        self.assertEqual(self.payload["profile"], generator.PROFILE)
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
            hashlib.sha256(generator.ONLINE_SOURCE.read_bytes()).hexdigest(),
        )

    def test_filter_contract_is_frozen(self):
        config = self.payload["config"]
        self.assertEqual(config["sample_rate_hz"], 100.0)
        self.assertEqual(config["filter_order"], 3)
        self.assertEqual(config["polarity_transform"], "invert")
        self.assertEqual(config["zscore_ddof"], 0)
        np.testing.assert_allclose(
            np.asarray(config["sos"], dtype=float),
            generator._sos(),
            rtol=0.0,
            atol=1e-15,
        )
        self.assertAlmostEqual(
            config["dc_alpha"],
            1.0 - math.exp(-1.0 / 50.0),
            places=15,
        )

    def test_expected_intermediate_arrays_are_reproducible(self):
        for case in self.payload["cases"]:
            with self.subTest(case=case["name"]):
                actual = generator._causal_preprocess(
                    np.asarray(case["raw"], dtype=float),
                    reset_indices=tuple(case["reset_indices"]),
                )
                expected = case["expected"]
                for key in ("dc", "ac", "bandpassed", "peak_up"):
                    np.testing.assert_allclose(
                        np.asarray(actual[key], dtype=float),
                        np.asarray(expected[key], dtype=float),
                        rtol=0.0,
                        atol=1e-12,
                    )
                self.assertEqual(
                    actual["normalization"]["valid"],
                    expected["normalization"]["valid"],
                )
                self.assertEqual(
                    actual["normalization"]["reason"],
                    expected["normalization"]["reason"],
                )
                if expected["normalization"]["values"] is not None:
                    np.testing.assert_allclose(
                        np.asarray(
                            actual["normalization"]["values"],
                            dtype=float,
                        ),
                        np.asarray(
                            expected["normalization"]["values"],
                            dtype=float,
                        ),
                        rtol=0.0,
                        atol=1e-12,
                    )

    def test_xctest_resource_is_an_exact_mirror(self):
        self.assertEqual(
            FIXTURE.read_bytes(),
            generator.SWIFT_OUTPUT.read_bytes(),
        )


if __name__ == "__main__":
    unittest.main()
