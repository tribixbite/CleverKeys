#!/usr/bin/env python3
"""swipedata_metrics.py — aggregate top-1/3/5 metrics from the harness --out
per-trace cache(s) produced by tools/test_cli_predict.py over swipedata.

HISTORICAL (2026-08-28, ARC-047): the producing harness was DELETED — its
swipe_{encoder,decoder}_android.onnx models went with ADR-011. This aggregator
only reads already-written caches under ~/.cache/cleverkeys-test/; no new ones
can be produced.

Each record: {"word","len_stratum","raw_top5","raw_rank","filt_top5","filt_rank",
              "prod_top5","prod_rank"}   (rank 0 = top-1 correct, -1 = miss)

The harness only decodes IN-VOCAB targets (it filters to in-dict first), so the
per-trace ranks are IN-VOCAB accuracy. To report OVERALL accuracy (counting OOV
targets as automatic misses), pass --oov-rate R (fraction 0..1): overall = in_vocab * (1-R).

Usage: python3 scripts/swipedata_metrics.py FILE1 [FILE2 ...] [--oov-rate 0.0268]
"""
import json
import sys


class T:
    __slots__ = ("n", "t1", "t3", "t5")

    def __init__(self):
        self.n = self.t1 = self.t3 = self.t5 = 0

    def add(self, r):
        self.n += 1
        if r == 0:
            self.t1 += 1
        if 0 <= r < 3:
            self.t3 += 1
        if 0 <= r < 5:
            self.t5 += 1

    def pct(self, which):
        v = {1: self.t1, 3: self.t3, 5: self.t5}[which]
        return 100.0 * v / self.n if self.n else 0.0

    def row(self):
        return (f"n={self.n:>6}  top1={self.pct(1):5.2f}%  "
                f"top3={self.pct(3):5.2f}%  top5={self.pct(5):5.2f}%")


def fine(word):
    n = len(word.replace("'", ""))
    return "2-3" if n <= 3 else ("4-6" if n <= 6 else "7+")


def coarse(word):
    n = len(word.replace("'", ""))
    return "<=3" if n <= 3 else "4+"


def main():
    oov_rate = 0.0
    oov_idx = -1
    if "--oov-rate" in sys.argv:
        oov_idx = sys.argv.index("--oov-rate")
        oov_rate = float(sys.argv[oov_idx + 1])
    files = [
        a for i, a in enumerate(sys.argv[1:], start=1)
        if not a.startswith("--") and i != oov_idx + 1
    ]

    cols = ["raw", "filt", "prod"]
    overall = {c: T() for c in cols}
    by_coarse = {c: {"<=3": T(), "4+": T()} for c in cols}
    by_fine = {c: {"2-3": T(), "4-6": T(), "7+": T()} for c in cols}

    total = 0
    for path in files:
        with open(path) as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                o = json.loads(line)
                w = o["word"]
                total += 1
                for c in cols:
                    r = o.get(c + "_rank")
                    if r is None:
                        continue
                    overall[c].add(r)
                    by_coarse[c][coarse(w)].add(r)
                    by_fine[c][fine(w)].add(r)

    print(f"=== swipedata neural decode metrics (n={total} in-vocab traces) ===")
    print(f"files: {', '.join(files)}")
    print()
    print("OVERALL (in-vocab targets, top-1/3/5):")
    for c in cols:
        print(f"  {c.upper():<5} {overall[c].row()}")
    print()
    if oov_rate > 0:
        keep = 1.0 - oov_rate
        print(f"OVERALL incl. OOV as misses (OOV rate={oov_rate*100:.2f}%, scale={keep:.4f}):")
        for c in cols:
            t = overall[c]
            print(f"  {c.upper():<5} top1={t.pct(1)*keep:5.2f}%  "
                  f"top3={t.pct(3)*keep:5.2f}%  top5={t.pct(5)*keep:5.2f}%")
        print()
    print("by length stratum (coarse; top-1/3/5) — PRODUCTION col:")
    for s in ("<=3", "4+"):
        print(f"  {s:<4} {by_coarse['prod'][s].row()}")
    print()
    print("by length stratum (fine; top-1/3/5) — PRODUCTION col:")
    for s in ("2-3", "4-6", "7+"):
        print(f"  {s:<4} {by_fine['prod'][s].row()}")
    print()
    print("PRODUCTION per-stratum RAW/FILT/PROD (coarse):")
    for s in ("<=3", "4+"):
        print(f"  {s}: RAW  {by_coarse['raw'][s].row()}")
        print(f"       FILT {by_coarse['filt'][s].row()}")
        print(f"       PROD {by_coarse['prod'][s].row()}")


if __name__ == "__main__":
    main()
