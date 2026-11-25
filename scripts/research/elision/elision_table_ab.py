#!/usr/bin/env python3
"""Gate runner for the shipped elision frequency tables (docs/eval/2026-11-25-elision-own-frequency.md).

Floor arm: the trie as `ctc_injection_ab.build_trie` builds it (every alias key at the floor).
Table arm: the same trie with each key of `contraction_frequencies_<lang>.json` re-inserted at its
table value (LexTrie.insert keeps the max), as `CtcContractionKeys.inject` does in the app.

usage: elision_table_ab.py LANG [ROWS]   (fr also replays the FUTO azerty corpus)
       ELISION_TABLE=<json> overrides the shipped table (an unshipped candidate)
"""
import json
import os
import sys
from pathlib import Path

PROBE_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(PROBE_DIR))
import detre_probe as P  # noqa: E402  (encoder, QWERTY layout, trace(), decode())
ab = P.ab
from eval_altlayout import Layout, load_corpus, decode_corpus  # noqa: E402

LANG = sys.argv[1]
ROWS = int(sys.argv[2]) if len(sys.argv) > 2 else 100_000
PROBES = {
    "fr": [("detre", None), ("dete", None), ("lete", "lee"), ("lecole", None), ("quil", "will")],
    "it": [("dellanno", "dellano"), ("unaltra", "unalta"), ("lanno", None), ("cose", None)],
}
TRACES = [(110, 40), (140, 80), (90, 20)]

floor = max(1.0, ab.min_real_frequency(LANG) - 1.0)
table = json.loads(Path(os.environ.get("ELISION_TABLE") or ab.DICT_DIR / f"contraction_frequencies_{LANG}.json").read_text(encoding="utf-8"))
floor_trie, injected = ab.build_trie(LANG, floor)
arm_trie, _ = ab.build_trie(LANG, floor)
raised = 0
for k, v in table.items():
    # As CtcContractionKeys.inject: an injected key AND a lexicon-native key are raised to the
    # table value (insert keeps the max, so nothing is lowered).
    if k in injected or arm_trie.contains(k):
        arm_trie.insert(k, float(v))
        raised += 1
print(f"{LANG}: floor={floor:.0f} injected={len(injected)} table={len(table)} raised={raised}")
print(f"table values: min={min(table.values()):.2f} max={max(table.values()):.2f}")

for probe, rival in PROBES[LANG]:
    for seed, (mpk, dw) in enumerate(TRACES):
        xs, ys, ts = P.trace(probe, mpk, dw, seed=seed)
        a = [w for w, _ in P.decode(floor_trie, xs, ys, ts)]
        b = [w for w, _ in P.decode(arm_trie, xs, ys, ts)]
        ra = a.index(probe) + 1 if probe in a else None
        rb = b.index(probe) + 1 if probe in b else None
        extra = ""
        if rival:
            ka = a.index(rival) + 1 if rival in a else None
            kb = b.index(rival) + 1 if rival in b else None
            extra = f"  rival {rival}: floor={ka} table={kb}"
        print(f"  {probe:<9} {mpk}/{dw}: floor rank={ra} {a[:4]} | table rank={rb} {b[:4]}{extra}", flush=True)

if LANG != "fr":
    sys.exit(0)

layout = Layout(ab.LAYOUT_DIR / "futo_azerty.json")
keys, mask, cols = layout.slots("az26")
rows = load_corpus(ab.CACHE / "futo_swipe5_azerty.jsonl.gz")[:ROWS]
out = ab.CACHE / "elision-ab"
out.mkdir(parents=True, exist_ok=True)
print(f"human azerty rows: {len(rows)}")


def run(tag, trie):
    dump = out / f"table_{tag}.jsonl"
    with open(dump, "w") as f:
        r = decode_corpus(P.enc, rows, keys, mask, cols, trie, ab.PRESET_CKDT, 100, 8, 0, tag, f)
    print(f"{tag}: decoded={r['decoded']} t1={r['t1']:.2f} t3={r['t3']:.2f}", flush=True)
    return ab.top1(dump)


f_top = run("floor", floor_trie)
a_top = run("table", arm_trie)
reg = sorted({t for t, w in a_top.items() if f_top.get(t) == t and w != t})
imp = sorted({t for t, w in a_top.items() if w == t and f_top.get(t) != t})
print(f"REGRESSIONS {len(reg)}: {[(t, a_top[t]) for t in reg]}")
print(f"improvements {len(imp)}: {imp}")
