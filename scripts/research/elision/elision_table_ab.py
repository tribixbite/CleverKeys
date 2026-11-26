#!/usr/bin/env python3
"""Gate runner for the shipped elision frequency tables (docs/eval/2026-11-25-elision-own-frequency.md).

Floor arm: the trie as `ctc_injection_ab.build_trie` builds it (every alias key at the floor).
Table arm: the same trie with each key of `contraction_frequencies_<lang>.json` re-inserted at its
table value (LexTrie.insert keeps the max), as `CtcContractionKeys.inject` does in the app.

usage: elision_table_ab.py LANG [ROWS]   (fr also replays the FUTO azerty corpus)
       elision_table_ab.py fr --corpus fr_qwerty:qwerty --corpus fr_qwertz:qwertz
       ELISION_TABLE=<json> overrides the shipped table (an unshipped candidate)

--corpus STEM:LAYOUT replays `futo_swipe5_<STEM>.jsonl.gz` on `futo_<LAYOUT>.json` INSTEAD of
the azerty default (repeatable; several are also reported pooled). These runs report per-row,
per-target and per-session counts, the units of
docs/eval/2026-11-25-french-table-heldout-confirmation.md. Without --corpus nothing changes.
"""
import argparse
import gzip
import json
import os
import sys
from pathlib import Path

PROBE_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(PROBE_DIR))
import detre_probe as P  # noqa: E402  (encoder, QWERTY layout, trace(), decode())
ab = P.ab
from eval_altlayout import Layout, load_corpus, decode_corpus, project_az  # noqa: E402

_ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
_ap.add_argument("lang")
_ap.add_argument("rows", nargs="?", type=int, default=100_000)
_ap.add_argument("--corpus", action="append", default=[], metavar="STEM:LAYOUT")
_args = _ap.parse_args()
LANG = _args.lang
ROWS = _args.rows
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

out = ab.CACHE / "elision-ab"
out.mkdir(parents=True, exist_ok=True)


def sessions_of(path):
    """The `session` of each line of a sample (None where the fetch did not keep it)."""
    with gzip.open(path, "rt", encoding="utf-8") as f:
        return [json.loads(line).get("session") for line in f if line.strip()]


def decode_arm(stem, layout_name, tag, trie):
    """Decode one sample under one trie -> per-row dicts (word, target, topk, session)."""
    lay = Layout(ab.LAYOUT_DIR / f"futo_{layout_name}.json")
    k, m, c = lay.slots("az26")
    path = ab.CACHE / f"futo_swipe5_{stem}.jsonl.gz"
    corpus = load_corpus(path)[:ROWS]
    sess = sessions_of(path)[:ROWS]
    dump = out / f"heldout_{stem}_{tag}.jsonl"
    with open(dump, "w") as f:
        r = decode_corpus(P.enc, corpus, k, m, c, trie, ab.PRESET_CKDT, 100, 8, 0, tag, f)
    # decode_corpus skips untypeable and out-of-vocabulary rows; the same predicate here
    # lines the dump up with the rows' sessions.
    kept = [s for (w, *_), s in zip(corpus, sess)
            if (t := project_az(w)) is not None and trie.contains(t)]
    recs = [json.loads(line) for line in dump.read_text().splitlines()]
    assert len(recs) == len(kept) == r["decoded"], (len(recs), len(kept), r["decoded"])
    for rec, s in zip(recs, kept):
        rec["session"] = s
        rec["corpus"] = stem
    print(f"  [{stem}/{tag}] rows={r['rows']} decoded={r['decoded']} oov={r['oov']} "
          f"untypeable={r['untypeable']} sessions={len(set(sess))}", flush=True)
    return recs


def is_elision(word):
    return "'" in word or "\u2019" in word


def report(label, fl, tb):
    """Floor vs table on aligned per-row records: rows, distinct targets, sessions."""
    assert [r["word"] for r in fl] == [r["word"] for r in tb]
    n = len(fl)
    h1 = lambda r: r["topk"][:1] == [r["target"]]  # noqa: E731
    h3 = lambda r: r["target"] in r["topk"][:3]  # noqa: E731
    f1, t1 = sum(map(h1, fl)), sum(map(h1, tb))
    f3, t3 = sum(map(h3, fl)), sum(map(h3, tb))
    print(f"== {label}: decoded rows={n} distinct targets={len({r['target'] for r in fl})} "
          f"sessions={len({r['session'] for r in fl})}")
    if not n:
        return
    print(f"   top-1 floor {f1}/{n} = {100 * f1 / n:.2f}  table {t1}/{n} = {100 * t1 / n:.2f}  (net {t1 - f1:+d} rows)")
    print(f"   top-3 floor {f3}/{n} = {100 * f3 / n:.2f}  table {t3}/{n} = {100 * t3 / n:.2f}  (net {t3 - f3:+d} rows)")
    delta, changed = {}, []
    for a, b in zip(fl, tb):
        d = int(h1(b)) - int(h1(a))
        delta[a["target"]] = delta.get(a["target"], 0) + d
        if a["topk"][:1] != b["topk"][:1]:
            changed.append((a, b))
    imp = sorted(t for t, d in delta.items() if d > 0)
    reg = sorted(t for t, d in delta.items() if d < 0)
    print(f"   improved targets {len(imp)}: {[(t, delta[t]) for t in imp]}")
    print(f"   regressed targets {len(reg)}: {[(t, delta[t]) for t in reg]}")
    print(f"   rows whose top-1 changed: {len(changed)} over "
          f"{len({a['target'] for a, _ in changed})} targets, {len({a['session'] for a, _ in changed})} sessions")
    for a, b in changed:
        print(f"     {a['corpus']} s={str(a['session'])[:8]} {a['word']!r}: {a['topk'][:3]} -> {b['topk'][:3]}")
    ef = [r for r in fl if is_elision(r["word"])]
    et = [r for r in tb if is_elision(r["word"])]
    print(f"   elision rows={len(ef)} targets={len({r['target'] for r in ef})} "
          f"sessions={len({r['session'] for r in ef})}: top-1 floor {sum(map(h1, ef))} table {sum(map(h1, et))}; "
          f"top-3 floor {sum(map(h3, ef))} table {sum(map(h3, et))}")


if _args.corpus:
    pooled_f, pooled_t = [], []
    for spec in _args.corpus:
        stem, layout_name = spec.split(":")
        fl = decode_arm(stem, layout_name, "floor", floor_trie)
        tb = decode_arm(stem, layout_name, "table", arm_trie)
        report(stem, fl, tb)
        pooled_f += fl
        pooled_t += tb
    if len(_args.corpus) > 1:
        report("POOLED", pooled_f, pooled_t)
    sys.exit(0)

layout = Layout(ab.LAYOUT_DIR / "futo_azerty.json")
keys, mask, cols = layout.slots("az26")
rows = load_corpus(ab.CACHE / "futo_swipe5_azerty.jsonl.gz")[:ROWS]
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
