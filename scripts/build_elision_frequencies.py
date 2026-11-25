#!/usr/bin/env python3
"""
Elision alias keys ranked by their OWN corpus frequency (2026-11-25, todo item 35).

The CTC swipe lexicon spells an elision by its apostrophe-free alias key (`detre` for `d'être`),
injected by `CtcContractionKeys` one below the rarest real word (the floor), so every elision
ties with every other and a short or common-looking path wins (`d'été` over `d'être`, Italian
`dell'ano` over `dell'anno`). This builder writes
`src/main/assets/dictionaries/contraction_frequencies_<lang>.json`, alias key -> frequency on the
lexicon's 1..255 scale, and the app injects a key at `max(floor, table[key])`.

Counts: the static LM's pinned Leipzig corpus of the language (`*-words.txt` keeps an elision as
one token). Calibration (the 2026-11-09 arm, docs/eval/2026-11-09-elision-base-frequency.md):
an elision seen c times gets the median lexicon frequency of the lexicon words whose own count
lies within +-0.1 of ln c (the window widens x1.5 until it holds MIN_N words).

Two modes, chosen per language by the evidence available (docs/eval/2026-11-25-elision-own-frequency.md):
- `full` (fr: gated on the FUTO human azerty corpus): the calibrated frequency, kept only where
  it is above the floor;
- `band` (it: no human swipe corpus): values strictly inside (floor, floor + 1), ordered by
  count, so elisions are ordered among themselves while every real word still outranks them.

Usage:
    python3 scripts/build_elision_frequencies.py --lang fr [--write]

License: GPL-3.0 (part of CleverKeys)
"""

from __future__ import annotations

import argparse
import bisect
import json
import math
import statistics
import struct
import sys
from pathlib import Path
from typing import Callable, Mapping

SCRIPT_DIR = Path(__file__).resolve().parent
REPO = SCRIPT_DIR.parent
DICT_DIR = REPO / "src" / "main" / "assets" / "dictionaries"

#: Lexicon words a calibration window must hold before its median is trusted.
MIN_N = 25
#: Per-language mode; a language not listed gets no table.
MODES = {"fr": "full", "it": "band"}


def read_ckdt_frequencies(path: Path) -> "dict[str, float]":
    """word -> frequency (255 - rank, at least 1) from a CKDT v2 bin, the app's CKDT scale."""
    data = path.read_bytes()
    magic, version = struct.unpack_from("<II", data, 0)
    assert magic == 0x54444B43 and version == 2, (path, hex(magic), version)
    count, p = struct.unpack_from("<I", data, 12)[0], struct.unpack_from("<I", data, 16)[0]
    out: "dict[str, float]" = {}
    for _ in range(count):
        (n,) = struct.unpack_from("<H", data, p)
        word = data[p + 2:p + 2 + n].decode("utf-8")
        rank = data[p + 2 + n]
        out[word] = float(max(1, 255 - rank))
        p += 2 + n + 1
    return out


def elision_counts(counts: "Mapping[str, int]") -> "dict[str, int]":
    """Elision tokens (with an apostrophe), lowercased, typographic apostrophes folded, summed."""
    out: "dict[str, int]" = {}
    for tok, c in counts.items():
        t = tok.lower().replace("’", "'")
        if "'" in t:
            out[t] = out.get(t, 0) + c
    return out


class Calibration:
    """Corpus count -> lexicon frequency, from the lexicon words' own counts."""

    def __init__(self, real: "Mapping[str, float]", counts: "Mapping[str, int]", min_n: int = MIN_N):
        self.points = sorted((math.log(counts[w]), f) for w, f in real.items() if counts.get(w))
        self.logs = [c for c, _ in self.points]
        self.min_n = min_n
        if len(self.points) < min_n:
            raise ValueError(f"calibration needs {min_n} counted lexicon words, has {len(self.points)}")

    def frequency(self, count: int) -> float:
        lc, half = math.log(count), 0.1
        while True:
            lo = bisect.bisect_left(self.logs, lc - half)
            hi = bisect.bisect_right(self.logs, lc + half)
            if hi - lo >= self.min_n or half > 5:
                return float(statistics.median(f for _, f in self.points[lo:hi]))
            half *= 1.5


def attested_keys(display: "Mapping[str, str]", pairs: "Mapping[str, list]",
                  counts: "Mapping[str, int]", lexicon: "set[str]") -> "dict[str, int]":
    """Alias key -> the corpus count of its most attested elision display form.

    Keys the lexicon already holds are kept: the classifier keeps contraction keys as words, at
    the wordfreq frequency of the apostrophe-free token (`quil` 96, though `qu'il` is written
    5,601 times in the corpus), and `CtcContractionKeys.inject` RAISES such a key to its table
    value (never lowers it). [lexicon] only names them for the report.
    """
    out: "dict[str, int]" = {}
    entries = list(display.items()) + [(k, v) for k, vs in pairs.items() for v in vs]
    for key, value in entries:
        k = key.lower()
        v = str(value).lower().replace("’", "'")
        c = counts.get(v, 0)
        if "'" not in v or c == 0:
            continue
        if c > out.get(k, 0):
            out[k] = c
    return out


def full_table(keys: "Mapping[str, int]", calibrate: "Callable[[int], float]", floor: float) -> "dict[str, float]":
    """The calibrated frequency of each key, kept only where it raises the key above the floor."""
    return {k: round(f, 2) for k, c in sorted(keys.items()) if (f := calibrate(c)) > floor}


def band_table(keys: "Mapping[str, int]", floor: float) -> "dict[str, float]":
    """Values strictly inside (floor, floor + 1), increasing with the count (log scale)."""
    if not keys:
        return {}
    top = math.log(max(keys.values()) + 1)
    return {k: round(floor + 0.01 + 0.98 * math.log(c + 1) / top, 4) for k, c in sorted(keys.items())}


def build(lang: str) -> "tuple[dict[str, float], dict]":
    """The table for [lang] and a report of how it was made."""
    from build_static_lm import CACHE, CONFIGS, ensure_source  # noqa: E402 (pinned corpora)
    from sharp_s import leipzig_word_counts  # noqa: E402

    mode = MODES[lang]
    source = CONFIGS[lang].leipzig
    tarball, sha = ensure_source(source, CACHE, allow_unpinned=False, offline=True)
    raw = leipzig_word_counts(tarball)
    real = read_ckdt_frequencies(DICT_DIR / f"{lang}_enhanced.bin")
    floor = max(1.0, min(real.values()) - 1.0)  # CtcContractionKeys.derivedFloor
    display = json.loads((DICT_DIR / f"contractions_{lang}.json").read_text(encoding="utf-8"))
    pairs_path = DICT_DIR / f"contraction_pairs_{lang}.json"
    pairs = json.loads(pairs_path.read_text(encoding="utf-8")) if pairs_path.is_file() else {}
    keys = attested_keys(display, pairs, elision_counts(raw), set(real))
    native = sum(1 for k in keys if k in real)
    if mode == "full":
        cal = Calibration(real, raw)
        table = full_table(keys, cal.frequency, floor)
        # A lexicon-native key is only ever RAISED (CtcContractionKeys.inject keeps the max): an
        # entry at or below its own lexicon frequency (`la` for the rarer `l'a`) does nothing.
        table = {k: v for k, v in table.items() if v > real.get(k, 0.0)}
    else:
        table = band_table(keys, floor)
    report = {"lang": lang, "mode": mode, "corpus": source.filename, "sha256": sha, "floor": floor,
              "attested_keys": len(keys), "lexicon_native": native, "table_keys": len(table)}
    return table, report


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--lang", required=True, choices=sorted(MODES))
    ap.add_argument("--write", action="store_true", help="write the asset (else report only)")
    args = ap.parse_args()
    table, report = build(args.lang)
    print(json.dumps(report))
    for k, v in sorted(table.items(), key=lambda kv: -kv[1])[:15]:
        print(f"  {k:<16} {v}")
    if args.write:
        out = DICT_DIR / f"contraction_frequencies_{args.lang}.json"
        out.write_text(json.dumps(table, ensure_ascii=False, sort_keys=True, indent=0) + "\n", encoding="utf-8")
        print(f"wrote {out} ({len(table)} keys)")


if __name__ == "__main__":
    sys.path.insert(0, str(SCRIPT_DIR))
    main()
