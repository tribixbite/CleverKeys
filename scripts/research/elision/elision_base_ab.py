#!/usr/bin/env python3
"""A/B: elision alias keys at the shipped floor vs at their BASE word's frequency.

An elision display form `X'base` (d'être, l'été) whose `base` is a real lexicon word gets
`freq(base) - margin` instead of the floor; every other alias key keeps the floor. Measures
(1) regressions on the human azerty corpus (a real word's own trace lost) and (2) the rank of
probe elision keys on synthetic QWERTY traces.
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import detre_probe as P  # noqa: E402  (loads encoder, layout, the shipped trie)
ab = P.ab
from eval_altlayout import Layout, load_corpus, decode_corpus, project_az  # noqa: E402

MARGIN = float(sys.argv[1]) if len(sys.argv) > 1 else 1.0
ROWS = int(sys.argv[2]) if len(sys.argv) > 2 else 400

real = {w: float(max(1, 255 - r)) for w, r in ab.read_ckdt_v2(ab.DICT_DIR / "fr_enhanced.bin")}
display = json.loads((ab.DICT_DIR / "contractions_fr.json").read_text(encoding="utf-8"))
pairs = json.loads((ab.DICT_DIR / "contraction_pairs_fr.json").read_text(encoding="utf-8"))


def base_freq(value: str):
    if "'" not in value:
        return None
    base = project_az(value.split("'", 1)[1].lower())
    return real.get(base) if base else None


def build_base_trie():
    trie, injected = ab.build_trie("fr", P.floor)
    raised = 0
    for key, value in list(display.items()) + [(k, v) for k, vs in pairs.items() for v in vs]:
        k = project_az(key)
        if k not in injected:
            continue
        f = base_freq(value)
        if f is not None and f - MARGIN > P.floor:
            trie.insert(k, f - MARGIN)  # LexTrie.insert keeps the MAX log-freq
            raised += 1
    return trie, injected, raised


trie_b, injected, raised = build_base_trie()
print(f"margin={MARGIN}  raised {raised} of {len(injected)} injected keys")

for probe in ["detre", "dete", "lete", "lecole", "jetais", "dame"]:
    for seed, (mpk, dw) in enumerate([(110, 40), (140, 80), (90, 20)]):
        xs, ys, ts = P.trace(probe, mpk, dw, seed=seed)
        a = [w for w, _ in P.decode(P.trie, xs, ys, ts)]
        b = [w for w, _ in P.decode(trie_b, xs, ys, ts)]
        ra = a.index(probe) + 1 if probe in a else None
        rb = b.index(probe) + 1 if probe in b else None
        print(f"  {probe:<7} {mpk}/{dw}: floor rank={ra} top={a[:3]}  |  base rank={rb} top={b[:3]}")

layout = Layout(ab.LAYOUT_DIR / "futo_azerty.json")
keys, mask, cols = layout.slots("az26")
rows = load_corpus(ab.CACHE / "futo_swipe5_azerty.jsonl.gz")[:ROWS]
out = {}
for tag, trie in (("floor", P.trie), ("base", trie_b)):
    dump = ab.CACHE / "elision-ab" / f"ab_{tag}.jsonl"  # outside the repo
    dump.parent.mkdir(parents=True, exist_ok=True)
    with open(dump, "w") as f:
        r = decode_corpus(P.enc, rows, keys, mask, cols, trie, ab.PRESET_CKDT, 100, 8, 0, tag, f)
    out[tag] = ab.top1(dump)
    print(f"{tag}: decoded={r['decoded']} t1={r['t1']:.2f} t3={r['t3']:.2f}")
reg = [(t, out['floor'][t], w) for t, w in out["base"].items()
       if out["floor"].get(t) == t and w != t]
imp = [(t, out['floor'].get(t), w) for t, w in out["base"].items()
       if w == t and out["floor"].get(t) != t]
print(f"REGRESSIONS {len(reg)}: {reg[:15]}")
print(f"improvements {len(imp)}: {imp[:15]}")
