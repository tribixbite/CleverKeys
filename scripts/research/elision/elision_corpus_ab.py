#!/usr/bin/env python3
"""A/B: elision alias keys at the shipped floor vs at their CORPUS-ATTESTED frequency.

Variant of probe/elision_base_ab.py. An alias whose display form contains an apostrophe
(d'être, c'est) is ranked by the count of that exact token in a Leipzig French corpus
(words.txt keeps elisions as one token), mapped onto the lexicon's 1..255 scale by a
count-to-count calibration against the plain lexicon words' own counts in the same corpus:

    mapped(c) = median lexicon frequency of the lexicon words whose corpus count lies in a
                log-window around c (window +-0.1 in ln, widened x1.5 until >= MIN_N words).

The key is raised to mapped(c) - margin when that exceeds the floor (insert keeps the max).

usage: elision_corpus_ab.py WORDS_TXT MARGINS(comma) ROWS [--dry]
"""
import json
import math
import statistics
import sys
from pathlib import Path

PROBE_DIR = Path(__file__).resolve().parent  # detre_probe.py lives beside this script
sys.path.insert(0, str(PROBE_DIR))
import detre_probe as P  # noqa: E402  (loads encoder, layout, the shipped trie)
ab = P.ab
from eval_altlayout import Layout, load_corpus, decode_corpus, project_az  # noqa: E402

WORDS_TXT = Path(sys.argv[1])
MARGINS = [float(m) for m in sys.argv[2].split(",")]
ROWS = int(sys.argv[3])
DRY = "--dry" in sys.argv
MIN_N = 25
OUT = ab.CACHE / "elision-ab"  # dumps outside the repo
OUT.mkdir(parents=True, exist_ok=True)

real = {w: float(max(1, 255 - r)) for w, r in ab.read_ckdt_v2(ab.DICT_DIR / "fr_enhanced.bin")}
display = json.loads((ab.DICT_DIR / "contractions_fr.json").read_text(encoding="utf-8"))
pairs = json.loads((ab.DICT_DIR / "contraction_pairs_fr.json").read_text(encoding="utf-8"))


def norm(tok: str) -> str:
    return tok.lower().replace("’", "'")


# --- corpus counts -------------------------------------------------------------------------
el_count: dict[str, int] = {}     # normalised elision token -> count
plain_count: dict[str, int] = {}  # a-z projection of an apostrophe-free token -> summed count
for line in WORDS_TXT.read_text(encoding="utf-8").splitlines():
    parts = line.split("\t")
    if len(parts) != 3:
        continue
    tok, cnt = parts[1], int(parts[2])
    t = norm(tok)
    if "'" in t:
        el_count[t] = el_count.get(t, 0) + cnt
    else:
        k = project_az(t)
        if k:
            plain_count[k] = plain_count.get(k, 0) + cnt

# --- calibration: corpus count -> lexicon frequency ----------------------------------------
calib = sorted((math.log(plain_count[w]), f) for w, f in real.items() if plain_count.get(w))
logs = [c for c, _ in calib]


def mapped(count: int) -> float:
    import bisect
    lc, half = math.log(count), 0.1
    while True:
        lo, hi = bisect.bisect_left(logs, lc - half), bisect.bisect_right(logs, lc + half)
        if hi - lo >= MIN_N or half > 5:
            return statistics.median(f for _, f in calib[lo:hi])
        half *= 1.5


# --- per-key attested frequency (max over the key's elision display forms) -----------------
key_info: dict[str, tuple[float, int, str]] = {}
for key, value in list(display.items()) + [(k, v) for k, vs in pairs.items() for v in vs]:
    v = norm(value)
    if "'" not in v:
        continue
    k = project_az(key)
    c = el_count.get(v, 0)
    if not k or c == 0:
        continue
    f = mapped(c)
    if k not in key_info or f > key_info[k][0]:
        key_info[k] = (f, c, value)

print(f"corpus: {len(el_count)} elision types, {len(plain_count)} plain types; "
      f"calibration over {len(calib)} lexicon words; floor={P.floor:.0f}")
for c in (1, 2, 3, 5, 10, 30, 100, 300, 1000, 10000):
    print(f"  calib count {c:>5} -> freq {mapped(c):.0f}")
for probe in ("detre", "dete", "lete", "lecole", "jetais", "dame", "cest", "jai", "dabord",
              "dunion", "quautre", "laise", "dailleurs", "dencore", "jadore", "dutile"):
    print(f"  {probe:<10} {key_info.get(probe)}  real={real.get(probe)}")

trie_cache = {}


def build_arm(margin: float):
    trie, injected = ab.build_trie("fr", P.floor)
    raised = []
    for k, (f, c, v) in key_info.items():
        if k in injected and f - margin > P.floor:
            trie.insert(k, f - margin)  # LexTrie.insert keeps the MAX log-freq
            raised.append((f - margin, c, k, v))
    raised.sort(reverse=True)
    return trie, injected, raised


for m in MARGINS:
    _, injected, raised = build_arm(m)
    print(f"\nmargin={m}: raised {len(raised)} of {len(injected)} injected keys; top 30:")
    for f, c, k, v in raised[:30]:
        print(f"    {k:<14} {v:<16} count={c:<6} freq={f:.0f}")
if DRY:
    sys.exit(0)

layout = Layout(ab.LAYOUT_DIR / "futo_azerty.json")
keys, mask, cols = layout.slots("az26")
rows = load_corpus(ab.CACHE / "futo_swipe5_azerty.jsonl.gz")[:ROWS]
print(f"\nhuman rows: {len(rows)}")


def run(tag, trie):
    dump = OUT / f"ab_{tag}.jsonl"
    with open(dump, "w") as f:
        r = decode_corpus(P.enc, rows, keys, mask, cols, trie, ab.PRESET_CKDT, 100, 8, 0, tag, f)
    print(f"{tag}: decoded={r['decoded']} t1={r['t1']:.2f} t3={r['t3']:.2f}", flush=True)
    return ab.top1(dump)


floor_top = run("floor", P.trie)
for m in MARGINS:
    trie_b, _, raised = build_arm(m)
    print(f"\n=== margin={m} raised={len(raised)}", flush=True)
    for probe in ["detre", "dete", "lete", "lecole", "jetais", "dame"]:
        for seed, (mpk, dw) in enumerate([(110, 40), (140, 80), (90, 20)]):
            xs, ys, ts = P.trace(probe, mpk, dw, seed=seed)
            a = [w for w, _ in P.decode(P.trie, xs, ys, ts)]
            b = [w for w, _ in P.decode(trie_b, xs, ys, ts)]
            ra = a.index(probe) + 1 if probe in a else None
            rb = b.index(probe) + 1 if probe in b else None
            print(f"  {probe:<7} {mpk}/{dw}: floor rank={ra} top={a[:3]}  |  arm rank={rb} top={b[:3]}")
    arm_top = run(f"corpus_m{int(m)}", trie_b)
    reg = [(t, floor_top[t], w) for t, w in arm_top.items() if floor_top.get(t) == t and w != t]
    imp = [(t, floor_top.get(t), w) for t, w in arm_top.items() if w == t and floor_top.get(t) != t]
    print(f"REGRESSIONS {len(reg)}: {reg}")
    print(f"improvements {len(imp)}: {imp}", flush=True)
