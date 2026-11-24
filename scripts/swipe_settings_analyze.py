#!/usr/bin/env python3
"""Tables for swipe_settings_replay.py output: per-config routing, CTC-only and end-to-end accuracy, paired
gains/losses vs the default config with an exact one-sided sign test, distinct-word counts.

  end-to-end top-k = the gesture reaches a word decode (route latched/short_word/return_trip)
                     AND the target is in the CTC slate's first k
  CTC-only top-k   = the CTC slate alone, whatever the route (isolates the noise threshold)
"""
from __future__ import annotations

import collections
import json
import sys
from math import comb
from typing import Callable, Dict, List

WORD = {"latched", "short_word", "return_trip"}


def sign_p(g: int, l: int) -> float:
    """One-sided exact sign test: P(X <= min(g, l)) under Binomial(g + l, 1/2)."""
    m = g + l
    return 1.0 if m == 0 else sum(comb(m, i) for i in range(0, min(g, l) + 1)) / 2 ** m


def ctc_ok(c: dict, w: str, k: int) -> bool:
    return w in c["top"][:k]


def e2e_ok(c: dict, w: str, k: int) -> bool:
    return c["route"] in WORD and ctc_ok(c, w, k)


def pct(n: int, d: int) -> str:
    return f"{100 * n / d:.2f}" if d else "-"


def strata(recs: List[dict]) -> Dict[str, List[dict]]:
    return {
        "all": recs,
        "120Hz rows (median dt<=10ms)": [r for r in recs if r["mdt"] <= 10],
        "60Hz rows (median dt>=14ms)": [r for r in recs if r["mdt"] >= 14],
        "<=3 letters": [r for r in recs if len(r["w"]) <= 3],
    }


def paired(recs: List[dict], a: str, b: str, ok: Callable[[dict, str, int], bool], k: int):
    gains = [r for r in recs if ok(r["cfg"][b], r["w"], k) and not ok(r["cfg"][a], r["w"], k)]
    losses = [r for r in recs if ok(r["cfg"][a], r["w"], k) and not ok(r["cfg"][b], r["w"], k)]
    return gains, losses


def main(path: str) -> None:
    recs = [json.loads(l) for l in open(path)]
    cfgs = list(recs[0]["cfg"].keys())
    print(f"# {path}: {len(recs)} traces, {len({r['w'] for r in recs})} distinct words")
    for name, rs in strata(recs).items():
        print(f"\n## stratum: {name}  traces={len(rs)} words={len({r['w'] for r in rs})}")
        print("cfg  | word-route | dropped | short_subkey | nonword | CTC t1 | CTC t3 | E2E t1 | E2E t3")
        for c in cfgs:
            routes = collections.Counter(r["cfg"][c]["route"] for r in rs)
            nw = sum(routes[x] for x in WORD)
            print(f"{c:4} | {nw:5} ({pct(nw, len(rs))}) | {routes['dropped']:4} | {routes['short_subkey']:4} | "
                  f"{routes['nonword']:4} | {pct(sum(ctc_ok(r['cfg'][c], r['w'], 1) for r in rs), len(rs))} | "
                  f"{pct(sum(ctc_ok(r['cfg'][c], r['w'], 3) for r in rs), len(rs))} | "
                  f"{pct(sum(e2e_ok(r['cfg'][c], r['w'], 1) for r in rs), len(rs))} | "
                  f"{pct(sum(e2e_ok(r['cfg'][c], r['w'], 3) for r in rs), len(rs))}")
        print("paired vs D (B-only gains / D-only losses, one-sided sign p; distinct words in brackets)")
        for c in cfgs:
            if c == "D":
                continue
            cells = []
            for label, ok, k in (("CTC t1", ctc_ok, 1), ("CTC t3", ctc_ok, 3), ("E2E t1", e2e_ok, 1), ("E2E t3", e2e_ok, 3)):
                g, l = paired(rs, "D", c, ok, k)
                cells.append(f"{label} +{len(g)}[{len({r['w'] for r in g})}]/-{len(l)}[{len({r['w'] for r in l})}] "
                             f"p={sign_p(len(g), len(l)):.3f}")
            print(f"  {c:3} " + " | ".join(cells))
    # route changes vs D (all traces)
    print("\n## route changes vs D (all traces)")
    for c in cfgs:
        if c == "D":
            continue
        ch = collections.Counter((r["cfg"]["D"]["route"], r["cfg"][c]["route"]) for r in recs
                                 if r["cfg"]["D"]["route"] != r["cfg"][c]["route"])
        keysdiff = sum(1 for r in recs if r["cfg"]["D"]["keys"] != r["cfg"][c]["keys"])
        tracediff = sum(1 for r in recs if r["cfg"]["D"]["h"] != r["cfg"][c]["h"])
        blk = sum(r["cfg"][c]["blk"] for r in recs)
        print(f"  {c:3} CTC-input changed={tracediff} key-list changed={keysdiff} gate-2 rejections={blk} "
              f"route changes={dict(ch)}")
    print(f"  D   gate-2 rejections={sum(r['cfg']['D']['blk'] for r in recs)}")


if __name__ == "__main__":
    main(sys.argv[1])
