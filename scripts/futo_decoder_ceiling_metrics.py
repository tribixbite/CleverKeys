#!/usr/bin/env python3
"""3-way floor -> ceiling decomposition for the FUTO decoder eval.

Joins the three per-trace prediction files by idx and reports, for each config,
top-1/3/5 (overall incl OOV, and in-vocab-only), by length stratum (<=3 vs 4+),
and greedy-CTC top-1 — plus floor->beamB (lever 2: beam), beamB->beamD (lever 1:
decoder), and floor->beamD (total) deltas.

  A = floor  : encoder-only, textbook logaddexp CTC prefix beam (no length prune)
  B = beamB  : encoder-only, FUTO Viterbi trie beam + length-aware pruning
  D = beamD  : encoder + magic_macaw decoder, FUTO Viterbi beam (decoder scoring)

Usage: futo_decoder_ceiling_metrics.py <floor.jsonl> <beamB.jsonl> <beamD.jsonl>
"""

from __future__ import annotations

import json
import sys
from typing import Dict, List


def stratum(word: str) -> str:
    return "<=3" if len(word) <= 3 else "4+"


class T:
    __slots__ = ("n", "t1", "t3", "t5", "g1")

    def __init__(self) -> None:
        self.n = self.t1 = self.t3 = self.t5 = self.g1 = 0

    def add(self, rank: int, ghit: bool) -> None:
        self.n += 1
        if 0 <= rank < 1:
            self.t1 += 1
        if 0 <= rank < 3:
            self.t3 += 1
        if 0 <= rank < 5:
            self.t5 += 1
        if ghit:
            self.g1 += 1

    def p1(self) -> float:
        return self.t1 / self.n * 100 if self.n else 0.0

    def p3(self) -> float:
        return self.t3 / self.n * 100 if self.n else 0.0

    def p5(self) -> float:
        return self.t5 / self.n * 100 if self.n else 0.0

    def pg(self) -> float:
        return self.g1 / self.n * 100 if self.n else 0.0


def rank_of(target: str, preds: List[str]) -> int:
    for i, w in enumerate(preds):
        if w == target:
            return i
    return -1


def load(path: str) -> Dict[int, dict]:
    out = {}
    for line in open(path):
        line = line.strip()
        if not line:
            continue
        r = json.loads(line)
        if "idx" in r:
            out[r["idx"]] = r
    return out


def score(recs: Dict[int, dict]):
    overall, invocab = T(), T()
    strat = {"<=3": T(), "4+": T()}
    strat_iv = {"<=3": T(), "4+": T()}
    n_oov = n_err = 0
    for r in recs.values():
        if "error" in r:
            n_err += 1
        w = r["word"].lower()
        preds = r.get("preds", [])
        ghit = r.get("greedy", "") == w
        iv = bool(r.get("in_vocab", False))
        if not iv:
            n_oov += 1
        rk = rank_of(w, preds)
        overall.add(rk, ghit)
        strat[stratum(w)].add(rk, ghit)
        if iv:
            invocab.add(rk, ghit)
            strat_iv[stratum(w)].add(rk, ghit)
    return overall, invocab, strat, strat_iv, n_oov, n_err


def main() -> int:
    floor_p, b_p, d_p = sys.argv[1], sys.argv[2], sys.argv[3]
    A, B, D = load(floor_p), load(b_p), load(d_p)
    n = min(len(A), len(B), len(D))
    print(f"joined idx counts: floor={len(A)} beamB={len(B)} beamD={len(D)}\n")

    configs = [("A floor (enc, logaddexp beam)", A),
               ("B beamB (enc, FUTO Viterbi beam)", B),
               ("D beamD (enc+decoder, FUTO beam)", D)]
    res = {}
    for name, recs in configs:
        res[name] = score(recs)

    print("=" * 92)
    print(f"{'config':36} | {'OVERALL t1/t3/t5 (incl OOV)':30} | {'IN-VOCAB t1/t3/t5':22} | greedy")
    print("-" * 92)
    for name, _ in configs:
        ov, iv, _, _, noov, nerr = res[name]
        print(f"{name:36} | {ov.p1():6.2f} {ov.p3():6.2f} {ov.p5():6.2f}          "
              f"| {iv.p1():6.2f} {iv.p3():6.2f} {iv.p5():6.2f}   | {ov.pg():6.2f}%  "
              f"(N={ov.n} OOV={noov} err={nerr})")
    print("=" * 92)

    print("\nBy length stratum (OVERALL incl OOV) — top-1 / top-3 / top-5:")
    print(f"{'config':36} | {'<=3 (t1/t3/t5)':26} | {'4+ (t1/t3/t5)':26}")
    print("-" * 92)
    for name, _ in configs:
        _, _, st, _, _, _ = res[name]
        s3, s4 = st["<=3"], st["4+"]
        print(f"{name:36} | {s3.p1():6.2f} {s3.p3():6.2f} {s3.p5():6.2f} (n={s3.n:<4}) "
              f"| {s4.p1():6.2f} {s4.p3():6.2f} {s4.p5():6.2f} (n={s4.n:<4})")

    print("\nBy length stratum (IN-VOCAB only) — top-1 / top-3 / top-5:")
    print(f"{'config':36} | {'<=3 (t1/t3/t5)':26} | {'4+ (t1/t3/t5)':26}")
    print("-" * 92)
    for name, _ in configs:
        _, _, _, st, _, _ = res[name]
        s3, s4 = st["<=3"], st["4+"]
        print(f"{name:36} | {s3.p1():6.2f} {s3.p3():6.2f} {s3.p5():6.2f} (n={s3.n:<4}) "
              f"| {s4.p1():6.2f} {s4.p3():6.2f} {s4.p5():6.2f} (n={s4.n:<4})")

    a_ov = res[configs[0][0]][0]
    b_ov = res[configs[1][0]][0]
    d_ov = res[configs[2][0]][0]
    print("\n" + "=" * 92)
    print("PER-LEVER DECOMPOSITION (overall top-1, incl OOV):")
    print(f"  A floor                         : {a_ov.p1():6.2f}%")
    print(f"  Lever 2 (FUTO beam)   B - A      : {b_ov.p1() - a_ov.p1():+6.2f}pt  -> B = {b_ov.p1():6.2f}%")
    print(f"  Lever 1 (magic_macaw) D - B      : {d_ov.p1() - b_ov.p1():+6.2f}pt  -> D = {d_ov.p1():6.2f}%")
    print(f"  TOTAL floor->ceiling  D - A      : {d_ov.p1() - a_ov.p1():+6.2f}pt")
    print("  (same for top-3 / top-5):")
    print(f"    top-3:  A={a_ov.p3():.2f}  B={b_ov.p3():.2f} ({b_ov.p3()-a_ov.p3():+.2f})  "
          f"D={d_ov.p3():.2f} ({d_ov.p3()-b_ov.p3():+.2f})  total {d_ov.p3()-a_ov.p3():+.2f}")
    print(f"    top-5:  A={a_ov.p5():.2f}  B={b_ov.p5():.2f} ({b_ov.p5()-a_ov.p5():+.2f})  "
          f"D={d_ov.p5():.2f} ({d_ov.p5()-b_ov.p5():+.2f})  total {d_ov.p5()-a_ov.p5():+.2f}")
    print("=" * 92)
    print("\nPAPER (encoder-only test 92.54 t1 / 97.33 t3 ; enc+dec 93.30 / 97.97):")
    print(f"  our B (enc-only)  = {b_ov.p1():.2f} t1  (gap to paper enc-only: {92.54 - b_ov.p1():+.2f}pt)")
    print(f"  our D (enc+dec)   = {d_ov.p1():.2f} t1  (gap to paper enc+dec:  {93.30 - d_ov.p1():+.2f}pt)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
