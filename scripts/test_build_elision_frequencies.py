#!/usr/bin/env python3
"""Tests for build_elision_frequencies.py (elision alias keys ranked by their own corpus count).

Run: python3 -m unittest scripts/test_build_elision_frequencies.py  (from the repo root)
"""

from __future__ import annotations

import math
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import build_elision_frequencies as bef  # noqa: E402


class CalibrationTest(unittest.TestCase):
    def setUp(self) -> None:
        # 60 lexicon words: count 10**k for k in 0..5 (10 words each), frequency 70 + 30k.
        self.real = {f"w{k}_{i}": 70.0 + 30 * k for k in range(6) for i in range(10)}
        self.counts = {f"w{k}_{i}": 10 ** k for k in range(6) for i in range(10)}
        self.cal = bef.Calibration(self.real, self.counts, min_n=10)

    def test_a_count_maps_to_the_frequency_of_words_seen_that_often(self) -> None:
        self.assertEqual(self.cal.frequency(100), 130.0)
        self.assertEqual(self.cal.frequency(10_000), 190.0)

    def test_the_mapping_never_decreases_with_the_count(self) -> None:
        fs = [self.cal.frequency(c) for c in (1, 3, 10, 30, 100, 300, 1000, 10_000, 100_000)]
        self.assertEqual(fs, sorted(fs))


class ElisionCountsTest(unittest.TestCase):
    def test_typographic_apostrophes_and_case_add_up(self) -> None:
        counts = bef.elision_counts({"d'être": 10, "d’être": 5, "D'être": 1, "être": 99})
        self.assertEqual(counts, {"d'être": 16})


class KeyFrequenciesTest(unittest.TestCase):
    def test_a_key_takes_its_most_attested_display_form_lexicon_words_included(self) -> None:
        # `quil` is a lexicon word at the apostrophe-free token's frequency; the app raises it.
        display = {"detre": "d'être", "dete": "d'été", "quil": "qu'il", "cest": "c'est"}
        pairs = {"lune": ["l'une"]}
        counts = {"d'être": 1000, "d'été": 10, "qu'il": 5601, "l'une": 50}
        lexicon = {"quil", "lune", "cest"}
        got = bef.attested_keys(display, pairs, counts, lexicon)
        self.assertEqual(got, {"detre": 1000, "dete": 10, "quil": 5601, "lune": 50})


class TableTest(unittest.TestCase):
    def test_full_mode_keeps_only_keys_raised_above_the_floor(self) -> None:
        table = bef.full_table({"detre": 1000, "dete": 1}, lambda c: 75.0 + 20 * math.log10(c), floor=80.0)
        self.assertEqual(table, {"detre": 135.0})

    def test_band_mode_orders_keys_strictly_inside_the_floor_band(self) -> None:
        table = bef.band_table({"dellanno": 900, "dellano": 3, "unaltra": 400}, floor=71.0)
        for v in table.values():
            self.assertGreater(v, 71.0)
            self.assertLess(v, 72.0)
        self.assertGreater(table["dellanno"], table["unaltra"])
        self.assertGreater(table["unaltra"], table["dellano"])


if __name__ == "__main__":
    unittest.main()
