#!/usr/bin/env python3
"""futo100k_metrics.py — join geo + neural per-trace outputs from the FUTO
swipe-1/train 100k clean-timestamp head-to-head and emit the full metric tables.

Inputs (cache dir ~/.cache/cleverkeys-test unless overridden):
  geo_futo100k.jsonl      {"idx","word","preds":[top10]}   (geo, ALL in-dict traces)
  neural_futo100k.jsonl   harness --out format, line k = k-th in-dict trace
                          (neural, first N_neural in-dict traces of the SAME file)

Join: neural line k <-> geo record idx k. Word equality is ASSERTED per line.

Metrics (per engine, on the shared N_neural subset for comparability, plus
geo-only on the full set): top-1/3/5 micro; strata <=3 vs 4+ (and fine-grained
2-3/4-6/7+); macro over words with >=5 examples; union@1/@3/@5 per stratum
(fusion oracle ceiling). Output: markdown to stdout.
"""
import json
import sys
from collections import defaultdict
from pathlib import Path

CACHE = Path.home() / ".cache" / "cleverkeys-test"
GEO_F = CACHE / (sys.argv[1] if len(sys.argv) > 1 else "geo_futo100k.jsonl")
NEU_F = CACHE / (sys.argv[2] if len(sys.argv) > 2 else "neural_futo100k.jsonl")


def wlen(word):
    return len(word.replace("'", ""))


def stratum(word):
    return "<=3" if wlen(word) <= 3 else "4+"


def fine(word):
    n = wlen(word)
    return "2-3" if n <= 3 else ("4-6" if n <= 6 else "7+")


def rank_of(word, preds):
    for i, p in enumerate(preds):
        if p.lower() == word.lower():
            return i
    return -1


class T:
    def __init__(self):
        self.n = self.t1 = self.t3 = self.t5 = 0

    def add(self, r):
        self.n += 1
        if 0 <= r < 1:
            self.t1 += 1
        if 0 <= r < 3:
            self.t3 += 1
        if 0 <= r < 5:
            self.t5 += 1

    def row(self):
        if not self.n:
            return "n=0"
        return (f"n={self.n:>6}  top1={self.t1/self.n*100:5.2f}%  "
                f"top3={self.t3/self.n*100:5.2f}%  top5={self.t5/self.n*100:5.2f}%")

    def pct(self, k):
        v = {1: self.t1, 3: self.t3, 5: self.t5}[k]
        return v / self.n * 100 if self.n else 0.0


def load_geo(path):
    out = {}
    with open(path) as f:
        for line in f:
            o = json.loads(line)
            out[o["idx"]] = (o["word"], o["preds"])
    return out


def load_neu(path):
    out = []
    with open(path) as f:
        for line in f:
            o = json.loads(line)
            out.append((o["word"], o.get("prod_top5", []), o.get("prod_rank", -1)))
    return out


def main():
    geo = load_geo(GEO_F)
    neu = load_neu(NEU_F)
    print(f"geo records: {len(geo)}   neural records: {len(neu)}")

    # ── join + tallies ──────────────────────────────────────────────────────
    tal = {e: defaultdict(T) for e in ("geo", "neu", "union")}
    per_word = defaultdict(lambda: {"n": 0, "geo1": 0, "neu1": 0})
    mismatch = 0
    for k, (nword, npreds, nrank) in enumerate(neu):
        if k not in geo:
            continue
        gword, gpreds = geo[k]
        if gword != nword:
            mismatch += 1
            if mismatch <= 3:
                print(f"WORD MISMATCH at idx {k}: geo='{gword}' neural='{nword}'")
            continue
        gr = rank_of(gword, gpreds)
        nr = nrank if nrank >= 0 else rank_of(nword, npreds)
        ur = min([r for r in (gr, nr) if r >= 0], default=-1)
        for key in ("all", stratum(gword), fine(gword)):
            tal["geo"][key].add(gr)
            tal["neu"][key].add(nr)
            tal["union"][key].add(ur)
        pw = per_word[gword]
        pw["n"] += 1
        pw["geo1"] += 1 if gr == 0 else 0
        pw["neu1"] += 1 if nr == 0 else 0
    if mismatch:
        print(f"!!! {mismatch} word mismatches — JOIN SUSPECT, results below invalid")
        sys.exit(1)

    n_shared = tal["geo"]["all"].n
    print(f"\n## Shared subset (both engines, N={n_shared})\n")
    for key in ("all", "<=3", "4+", "2-3", "4-6", "7+"):
        print(f"### stratum {key}")
        for e, label in (("neu", "neural(prod,clean-t)"), ("geo", "geometric(shipped)"),
                         ("union", "UNION ceiling")):
            print(f"  {label:<22} {tal[e][key].row()}")
        print()

    # ── macro over words with >=5 examples ──────────────────────────────────
    eligible = {w: d for w, d in per_word.items() if d["n"] >= 5}
    if eligible:
        g = sum(d["geo1"] / d["n"] for d in eligible.values()) / len(eligible)
        n = sum(d["neu1"] / d["n"] for d in eligible.values()) / len(eligible)
        print(f"### macro top-1 over words with >=5 examples ({len(eligible)} words)")
        print(f"  neural {n*100:5.2f}%   geo {g*100:5.2f}%\n")

    # ── geo on the FULL set (context) ───────────────────────────────────────
    full = {"all": T(), "<=3": T(), "4+": T()}
    for idx, (w, preds) in geo.items():
        r = rank_of(w, preds)
        full["all"].add(r)
        full[stratum(w)].add(r)
    print("### geo on FULL geo-decoded set (context, not comparable to neural N)")
    for key in ("all", "<=3", "4+"):
        print(f"  {key:<4} {full[key].row()}")


if __name__ == "__main__":
    main()
