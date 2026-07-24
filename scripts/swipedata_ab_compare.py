#!/usr/bin/env python3
"""A/B compare two per-trace decode caches on their SHARED (positionally-aligned)
prefix. Both files decode the SAME seeded-shuffle cache in the same order, so
line k is the same trace. Reports PROD top-1/3/5 for each on the overlap, plus
per-length strata, plus a paired win/loss breakdown."""
import json
import sys


def load(path, n):
    out = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if line:
                out.append(json.loads(line))
            if len(out) >= n:
                break
    return out


def wlen(w):
    return len(w.replace("'", ""))


def main():
    a_path, b_path = sys.argv[1], sys.argv[2]
    a_label = sys.argv[3] if len(sys.argv) > 3 else "A"
    b_label = sys.argv[4] if len(sys.argv) > 4 else "B"
    # count lines
    import subprocess
    na = sum(1 for _ in open(a_path))
    nb = sum(1 for _ in open(b_path))
    n = min(na, nb)
    A = load(a_path, n)
    B = load(b_path, n)
    n = min(len(A), len(B))
    A, B = A[:n], B[:n]

    def tally(recs):
        t1 = t3 = t5 = 0
        s = {"<=3": [0, 0], "4+": [0, 0]}
        for o in recs:
            r = o.get("prod_rank", -1)
            if r == 0:
                t1 += 1
            if 0 <= r < 3:
                t3 += 1
            if 0 <= r < 5:
                t5 += 1
            k = "<=3" if wlen(o["word"]) <= 3 else "4+"
            s[k][1] += 1
            if r == 0:
                s[k][0] += 1
        return t1, t3, t5, s

    for label, recs in ((a_label, A), (b_label, B)):
        t1, t3, t5, s = tally(recs)
        print(f"{label:<28} n={len(recs)}  top1={100*t1/len(recs):5.2f}%  "
              f"top3={100*t3/len(recs):5.2f}%  top5={100*t5/len(recs):5.2f}%")
        for k in ("<=3", "4+"):
            hit, tot = s[k]
            if tot:
                print(f"    {k:<4} n={tot:<4} top1={100*hit/tot:5.2f}%")

    # paired
    both = same = a_only = b_only = neither = 0
    for oa, ob in zip(A, B):
        assert oa["word"] == ob["word"], f"misalign {oa['word']} vs {ob['word']}"
        ha = oa.get("prod_rank", -1) == 0
        hb = ob.get("prod_rank", -1) == 0
        if ha and hb:
            both += 1
        elif ha:
            a_only += 1
        elif hb:
            b_only += 1
        else:
            neither += 1
    print(f"\npaired top-1 (n={n}): both={both}  {a_label}-only={a_only}  "
          f"{b_label}-only={b_only}  neither={neither}")


if __name__ == "__main__":
    main()
