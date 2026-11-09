#!/usr/bin/env python3
"""Where does the d'être alias key `detre` rank for a synthetic QWERTY trace?

Builds the French trie exactly as scripts/ctc_injection_ab.py does (shipped injection floor),
decodes synthetic traces through the shipped encoder at the app's CKDT preset, and prints the
beam's top-k with scores. A second arm re-inserts the probed key at its display word's
frequency to separate "unreachable" from "outranked on frequency".
"""
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]  # repo root (scripts/research/elision/..)
sys.path.insert(0, str(REPO / "scripts"))
import ctc_injection_ab as ab  # noqa: E402  (also puts the ML ctc dir on sys.path)
from eval_altlayout import Layout, OnnxEncoder, MAX_KEYS, LETTERS_AZ, featurize  # noqa: E402
from eval_altlayout import futo_viterbi_beam  # noqa: E402
import numpy as np  # noqa: E402

layout = Layout(ab.LAYOUT_DIR / "futo_qwerty.json")
keys, mask, cols = layout.slots("az26")
centers = {chr(ord("a") + i): layout.az_centers[i] for i in range(26)}


def trace(word, ms_per_key=110.0, dwell_ms=40.0, step_ms=10.0, wobble=0.004, seed=0):
    """Points along the key centres at constant speed, a short dwell on every key."""
    rng = np.random.default_rng(seed)
    xs, ys, ts, t = [], [], [], 0.0
    pts = [centers[c] for c in word]
    def add(p):
        nonlocal t
        xs.append(float(p[0] + rng.normal(0, wobble)))
        ys.append(float(p[1] + rng.normal(0, wobble)))
        ts.append(t)
        t += step_ms
    for i, p in enumerate(pts):
        for _ in range(int(dwell_ms / step_ms)):
            add(p)
        if i + 1 < len(pts):
            q = pts[i + 1]
            n = max(2, int(ms_per_key * max(np.linalg.norm(q - p) / 0.1, 0.5) / step_ms))
            for s in range(1, n):
                add(p + (q - p) * s / n)
    return xs, ys, ts


def decode(trie, xs, ys, ts, top_k=12):
    feats = featurize(xs, ys, ts)
    full = enc.forward(feats, keys, mask)
    lp = np.empty((full.shape[0], 27), np.float32)
    lp[:, :26] = full[:, cols]
    lp[:, 26] = full[:, MAX_KEYS]
    g, l, b, gp, bp = ab.PRESET_CKDT
    return futo_viterbi_beam(lp, LETTERS_AZ, 26, trie, 100, top_k, g, l, b, gp, bp)


enc = OnnxEncoder([ab.MODEL])
floor = max(1.0, ab.min_real_frequency("fr") - 1.0)
trie, injected = ab.build_trie("fr", floor)
words = {w: max(1, 255 - r) for w, r in ab.read_ckdt_v2(ab.DICT_DIR / "fr_enhanced.bin")}
if __name__ == "__main__":
  print(f"floor={floor:.0f}  detre injected={'detre' in injected}  freq(etre)={words.get('etre')}")
  for probe in sys.argv[1:] or ["detre", "etre", "lecole", "dete"]:
    for seed, (mpk, dw) in enumerate([(110, 40), (140, 80), (90, 20)]):
        res = decode(trie, *trace(probe, mpk, dw, seed=seed))
        names = [w for w, _ in res]
        rank = names.index(probe) + 1 if probe in names else None
        print(f"{probe:<8} ms/key={mpk} dwell={dw}: rank={rank}  " +
              "  ".join(f"{w}:{s:.2f}" for w, s in res[:8]))
