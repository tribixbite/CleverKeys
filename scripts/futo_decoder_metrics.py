#!/usr/bin/env python3
"""Compute FUTO-decoder eval metrics from the per-trace predictions jsonl.

Reads `futo_decoder_test2400.jsonl` (one {"idx","word","in_vocab","greedy","preds":[...]}
per line) and reports top-1/3/5 for the beam decode + greedy top-1, overall and by
length stratum (<=3 vs 4+), plus OOV handling (overall vs in-vocab-restricted) and a
macro (per-word, >=5 examples) view. Micro = per-trace.
"""

from __future__ import annotations

import argparse
import collections
import json
from pathlib import Path
from typing import Dict, List


def stratum(word: str) -> str:
    return "<=3" if len(word) <= 3 else "4+"


class T:
    __slots__ = ("n", "t1", "t3", "t5", "g1")

    def __init__(self) -> None:
        self.n = self.t1 = self.t3 = self.t5 = self.g1 = 0

    def add(self, rank: int, greedy_hit: bool) -> None:
        self.n += 1
        if 0 <= rank < 1:
            self.t1 += 1
        if 0 <= rank < 3:
            self.t3 += 1
        if 0 <= rank < 5:
            self.t5 += 1
        if greedy_hit:
            self.g1 += 1

    def row(self) -> str:
        if not self.n:
            return "n=0"
        return (f"n={self.n:<5} top1={self.t1 / self.n * 100:6.2f}%  "
                f"top3={self.t3 / self.n * 100:6.2f}%  top5={self.t5 / self.n * 100:6.2f}%  "
                f"greedy_t1={self.g1 / self.n * 100:6.2f}%")


def rank_of(target: str, preds: List[str]) -> int:
    for i, w in enumerate(preds):
        if w == target:
            return i
    return -1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("jsonl")
    args = ap.parse_args()

    overall = T()
    invocab = T()
    strat = {"<=3": T(), "4+": T()}
    strat_invocab = {"<=3": T(), "4+": T()}
    per_word_hits: Dict[str, List[int]] = collections.defaultdict(list)  # word -> [1/0 top1]
    n_err = 0
    n_oov = 0

    for line in open(args.jsonl):
        line = line.strip()
        if not line:
            continue
        r = json.loads(line)
        if "error" in r:
            n_err += 1
        word = r["word"].lower()
        preds = r.get("preds", [])
        greedy = r.get("greedy", "")
        rank = rank_of(word, preds)
        ghit = greedy == word
        in_vocab = bool(r.get("in_vocab", False))
        if not in_vocab:
            n_oov += 1

        overall.add(rank, ghit)
        strat[stratum(word)].add(rank, ghit)
        if in_vocab:
            invocab.add(rank, ghit)
            strat_invocab[stratum(word)].add(rank, ghit)
        per_word_hits[word].append(1 if 0 <= rank < 1 else 0)

    print("=" * 78)
    print(f"FUTO DECODER eval — {overall.n} traces  ({n_oov} OOV, {n_err} errors)")
    print("=" * 78)
    print("MICRO (per-trace):")
    print(f"  OVERALL (incl OOV)   {overall.row()}")
    print(f"  IN-VOCAB only        {invocab.row()}")
    print()
    print("by length stratum (OVERALL, incl OOV):")
    for s in ("<=3", "4+"):
        print(f"  {s:<4} {strat[s].row()}")
    print("by length stratum (IN-VOCAB only):")
    for s in ("<=3", "4+"):
        print(f"  {s:<4} {strat_invocab[s].row()}")
    print()

    # macro: words with >=5 examples, avg per-word top-1
    macro_words = {w: h for w, h in per_word_hits.items() if len(h) >= 5}
    if macro_words:
        macro = sum(sum(h) / len(h) for h in macro_words.values()) / len(macro_words)
        print(f"MACRO (per-word top-1, {len(macro_words)} words w/ >=5 ex): {macro * 100:.2f}%")
    print("=" * 78)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
