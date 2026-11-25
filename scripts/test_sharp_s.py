#!/usr/bin/env python3
"""Tests for sharp_s.py: German ß restored from wordfreq's casefolded (ß→ss) stream.

Run: python3 -m unittest scripts/test_sharp_s.py  (from the repo root)
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import sharp_s  # noqa: E402


class VariantsTest(unittest.TestCase):
    def test_every_ss_may_be_a_sharp_s(self) -> None:
        self.assertEqual(sharp_s.sharp_s_variants("strasse"), ["straße"])
        self.assertEqual(sorted(sharp_s.sharp_s_variants("aussenmass")),
                         ["aussenmaß", "außenmass", "außenmaß"])

    def test_a_triple_s_compound_offers_both_readings(self) -> None:
        # Schlossstraße (Schloss + Straße) and the old Schloßstraße are both spellings of it.
        vs = sharp_s.sharp_s_variants("schlossstrasse")
        self.assertIn("schlossstraße", vs)
        self.assertIn("schloßstraße", vs)
        self.assertNotIn("schlossstrasse", vs)

    def test_no_variant_shorter_than_two_letters(self) -> None:
        self.assertEqual(sharp_s.sharp_s_variants("ss"), [])

    def test_a_word_without_ss_has_none(self) -> None:
        self.assertEqual(sharp_s.sharp_s_variants("hund"), [])


class RespellTest(unittest.TestCase):
    def test_a_word_only_spelled_with_sharp_s_takes_it(self) -> None:
        r = sharp_s.respell("strasse", {"straße"}, {"straße": 458, "strasse": 4})
        self.assertEqual(r.spellings, (("straße", 1.0),))
        self.assertEqual(r.reason, "ß")

    def test_a_reformed_ss_word_stays(self) -> None:
        # dass is the 1996 spelling; daß is the old one the oracle rejects.
        r = sharp_s.respell("dass", {"dass"}, {"dass": 12371, "daß": 99})
        self.assertEqual(r.spellings, (("dass", 1.0),))
        self.assertEqual(r.reason, "ss")

    def test_two_real_words_split_the_frequency_by_the_corpus(self) -> None:
        r = sharp_s.respell("masse", {"masse", "maße"}, {"masse": 42, "maße": 140})
        self.assertEqual(r.reason, "split")
        self.assertEqual([s for s, _ in r.spellings], ["maße", "masse"])
        self.assertAlmostEqual(r.spellings[0][1], 140 / 182)
        self.assertAlmostEqual(r.spellings[1][1], 42 / 182)

    def test_a_real_twin_the_corpus_never_shows_is_left_out(self) -> None:
        r = sharp_s.respell("ass", {"ass", "aß"}, {"ass": 8})
        self.assertEqual(r.spellings, (("ass", 1.0),))
        self.assertEqual(r.reason, "ss")
        r = sharp_s.respell("assen", {"assen", "aßen"}, {"aßen": 5})
        self.assertEqual(r.spellings, (("aßen", 1.0),))
        self.assertEqual(r.reason, "ß")

    def test_two_real_words_without_corpus_evidence_keep_the_stream_spelling(self) -> None:
        r = sharp_s.respell("körpermasse", {"körpermasse", "körpermaße"}, {})
        self.assertEqual(r.spellings, (("körpermasse", 1.0),))

    def test_names_the_oracle_does_not_know_follow_a_clear_corpus_majority(self) -> None:
        r = sharp_s.respell("gauss", set(), {"gauß": 9, "gauss": 1})
        self.assertEqual(r.spellings, (("gauß", 1.0),))
        self.assertEqual(r.reason, "corpus-ß")

    def test_thin_corpus_evidence_keeps_the_stream_spelling(self) -> None:
        # Below the minimum count (Preuß 2) or a name spelled both ways (Grasser is Grasser).
        self.assertEqual(sharp_s.respell("preuss", set(), {"preuß": 2, "preuss": 1}).spellings,
                         (("preuss", 1.0),))
        self.assertEqual(sharp_s.respell("grasser", set(), {"graßer": 2}).spellings,
                         (("grasser", 1.0),))
        # A majority that is not twice the stream spelling's count is not clear.
        self.assertEqual(sharp_s.respell("reuss", set(), {"reuß": 5, "reuss": 3}).spellings,
                         (("reuss", 1.0),))

    def test_a_name_written_both_ways_splits_even_where_the_oracle_knows_only_one(self) -> None:
        # The oracle has Heß, but Hess is the more common spelling of the name.
        r = sharp_s.respell("hess", {"heß"}, {"hess": 5, "heß": 3})
        self.assertEqual(r.reason, "split")
        self.assertEqual([s for s, _ in r.spellings], ["hess", "heß"])
        # A Swiss or typed-without-ß minority does not: strasse is 4 of 462.
        self.assertEqual(sharp_s.respell("strasse", {"straße"}, {"straße": 458, "strasse": 4}).spellings,
                         (("straße", 1.0),))
        # Nor do one or two stray hits.
        self.assertEqual(sharp_s.respell("reiss", {"reiß"}, {"reiss": 2, "reiß": 2}).spellings,
                         (("reiß", 1.0),))

    def test_several_valid_readings_take_the_corpus_favourite(self) -> None:
        r = sharp_s.respell("aussenmass", {"außenmaß", "außenmass"}, {"außenmaß": 6, "außenmass": 1})
        self.assertEqual(r.spellings, (("außenmaß", 1.0),))

    def test_a_word_without_ss_is_plain(self) -> None:
        r = sharp_s.respell("hund", {"hund"}, {})
        self.assertEqual(r.spellings, (("hund", 1.0),))
        self.assertEqual(r.reason, "plain")


class RespellRankedTest(unittest.TestCase):
    def test_spellings_take_their_stream_rank(self) -> None:
        decisions = {
            "strasse": sharp_s.Respelling((("straße", 1.0),), "ß"),
            "masse": sharp_s.Respelling((("maße", 0.75), ("masse", 0.25)), "split"),
        }
        out = sharp_s.respell_ranked(["der", "strasse", "masse", "dass"], decisions)
        self.assertEqual(out.ranked, ["der", "straße", "maße", "masse", "dass"])
        self.assertEqual(out.rank_of, {"der": 0, "straße": 1, "maße": 2, "masse": 2, "dass": 3})
        self.assertEqual(out.share, {"maße": 0.75, "masse": 0.25})
        self.assertEqual(out.respelled, {"strasse": ("straße",), "masse": ("maße", "masse")})

    def test_a_spelling_reached_twice_keeps_its_best_rank(self) -> None:
        decisions = {
            "schliess": sharp_s.Respelling((("schließ", 1.0),), "corpus-ß"),
            "schliesz": sharp_s.Respelling((("schließ", 1.0),), "ß"),
        }
        out = sharp_s.respell_ranked(["schliess", "und", "schliesz"], decisions)
        self.assertEqual(out.ranked, ["schließ", "und"])
        self.assertEqual(out.rank_of["schließ"], 0)


if __name__ == "__main__":
    unittest.main()
