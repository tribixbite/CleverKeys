#!/usr/bin/env python3
"""
Python reference for the browser CTC engines.

Two jobs:

1. **Synthesise** deterministic swipe trajectories for a word list. The demo's
   automated browser test replays the *exact* point arrays emitted here, so the
   JS and Python sides are never comparing different gestures.
2. **Decode** them with the same pipeline `CleverKeys-ML/ctc` uses — the
   `featurize` / `slice_emissions` / `futo_viterbi_beam` functions are imported
   from there rather than re-implemented, so this harness cannot drift from the
   reference the JS was ported against.

The output JSON is consumed by `browser_test.js`; it carries the raw points,
the reference `features` tensor, and the reference top-8 per engine.

    python3 web_demo/tests/ctc_reference.py --out web_demo/tests/reference.json
"""

from __future__ import annotations

import argparse
import json
import math
import sys
import time
from pathlib import Path
from typing import Dict, List, Sequence, Tuple

import numpy as np

REPO_ROOT = Path(__file__).resolve().parents[2]
MODELS_DIR = REPO_ROOT / "web_demo" / "demo" / "models"
CTC_DIR = Path("/home/will/git/CleverKeys-ML/ctc")

sys.path.insert(0, str(CTC_DIR))
from futo_decoder_eval import featurize, greedy_ctc, load_combined_vocab, load_layout  # noqa: E402
from futo_decoder_ceiling import futo_viterbi_beam, slice_emissions  # noqa: E402

# Scoring preset for the from-scratch CTC encoders (mirrors CTC_SCORING in
# web_demo/demo/ctc-engine.js).
SCORING = dict(gamma=1.05, lambda_=1.1, beta=0.2,
               gamma_prune=0.3734, beta_prune=0.9882)
BEAM_WIDTH = 100
TOP_K = 8

ENGINES = [
    ("ctc_ch128", MODELS_DIR / "ch128_s1234.onnx"),
    ("ctc_resbn80", MODELS_DIR / "fast_resbn80_s1234.onnx"),
]

# Words exercised end-to-end in the browser, including a 3-letter word, a
# double letter ('hello'), an apostrophe-stripped contraction ('dont') and a
# long word.
TEST_WORDS = ["the", "hello", "keyboard", "dont", "world", "this", "about",
              "four", "something"]

# Fixed paths used for the strict JS<->Python parity check. Deliberately
# varied: a short 3-letter gesture, a double letter, and a long one, so the
# 60 Hz stage sees three different durations (and three different
# `round(duration / 16.667)` outcomes).
PARITY_WORDS = ["the", "hello", "something"]

SAMPLE_INTERVAL_MS = 8.0   # ~125 Hz pointer stream, like a real touchscreen
SPEED_UNITS_PER_MS = 0.0016  # normalised units travelled per ms
DWELL_MS = 40.0            # pause on a repeated letter so it isn't a zero-length hop


def catmull_rom(p0: Sequence[float], p1: Sequence[float], p2: Sequence[float],
                p3: Sequence[float], t: float) -> Tuple[float, float]:
    """Uniform Catmull-Rom interpolation — rounds the corners at key centres
    the way a finger does, instead of the hard polyline vertices a naive
    linear interpolation would produce."""
    t2 = t * t
    t3 = t2 * t
    def axis(a0: float, a1: float, a2: float, a3: float) -> float:
        return 0.5 * ((2 * a1)
                      + (-a0 + a2) * t
                      + (2 * a0 - 5 * a1 + 4 * a2 - a3) * t2
                      + (-a0 + 3 * a1 - 3 * a2 + a3) * t3)
    return axis(p0[0], p1[0], p2[0], p3[0]), axis(p0[1], p1[1], p2[1], p3[1])


def synthesize(word: str, centers: Dict[str, Tuple[float, float]]
               ) -> Tuple[List[float], List[float], List[float]]:
    """Deterministically build a swipe trajectory for `word`.

    Key centres become Catmull-Rom control points; the curve is walked at a
    constant speed and sampled every SAMPLE_INTERVAL_MS, so longer words
    naturally produce longer gestures. Repeated letters get a dwell instead of
    a zero-length segment.

    Returns (xs, ys, ts) with xs/ys in the [0,1] letter-area frame and ts in ms.
    """
    waypoints = [centers[ch] for ch in word]
    if len(waypoints) == 1:
        waypoints = [waypoints[0], waypoints[0]]

    # Duplicate the endpoints so Catmull-Rom has p0/p3 for the first/last span.
    padded = [waypoints[0]] + waypoints + [waypoints[-1]]

    # Dense polyline along the spline, with per-span arc length for constant speed.
    dense: List[Tuple[float, float]] = []
    steps_per_span = 24
    for i in range(len(padded) - 3):
        p0, p1, p2, p3 = padded[i], padded[i + 1], padded[i + 2], padded[i + 3]
        for s in range(steps_per_span):
            dense.append(catmull_rom(p0, p1, p2, p3, s / steps_per_span))
    dense.append(waypoints[-1])

    # Cumulative arc length, with a dwell inserted wherever the curve stalls
    # (repeated letters) so time still advances there.
    cumulative = [0.0]
    for i in range(1, len(dense)):
        dx = dense[i][0] - dense[i - 1][0]
        dy = dense[i][1] - dense[i - 1][1]
        cumulative.append(cumulative[-1] + math.hypot(dx, dy))
    total_length = cumulative[-1]

    repeats = sum(1 for i in range(1, len(word)) if word[i] == word[i - 1])
    duration = total_length / SPEED_UNITS_PER_MS + repeats * DWELL_MS
    n_samples = max(2, int(duration / SAMPLE_INTERVAL_MS) + 1)

    xs: List[float] = []
    ys: List[float] = []
    ts: List[float] = []
    for i in range(n_samples):
        frac = i / (n_samples - 1)
        target = frac * total_length
        # Locate the dense segment containing this arc length.
        lo, hi = 0, len(cumulative) - 1
        while lo < hi:
            mid = (lo + hi) // 2
            if cumulative[mid] < target:
                lo = mid + 1
            else:
                hi = mid
        idx = max(1, lo)
        span = cumulative[idx] - cumulative[idx - 1]
        local = (target - cumulative[idx - 1]) / span if span > 1e-12 else 0.0
        x = dense[idx - 1][0] + local * (dense[idx][0] - dense[idx - 1][0])
        y = dense[idx - 1][1] + local * (dense[idx][1] - dense[idx - 1][1])
        xs.append(x)
        ys.append(y)
        ts.append(round(frac * duration, 3))
    return xs, ys, ts


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", type=Path,
                    default=Path(__file__).resolve().parent / "reference.json")
    ap.add_argument("--wordlist", type=Path,
                    default=Path("/home/will/ctc-train/data/futo_en_wordlist.combined"))
    args = ap.parse_args()

    import onnxruntime as ort

    letters, key_centers = load_layout(MODELS_DIR / "en_qwerty.json")
    centers = {letter: (float(c[0]), float(c[1]))
               for letter, c in zip(letters, key_centers)}

    print(f"[load] vocab {args.wordlist}")
    t0 = time.time()
    trie = load_combined_vocab(args.wordlist)
    print(f"[load] trie: {trie.num_words} words in {time.time() - t0:.1f}s")

    mask = np.ones((len(letters),), bool)
    keys_pad = np.zeros((64, 2), np.float32)
    keys_pad[:len(letters)] = key_centers
    mask_pad = np.zeros((64,), bool)
    mask_pad[:len(letters)] = mask

    sessions = {}
    for engine_id, path in ENGINES:
        sessions[engine_id] = ort.InferenceSession(str(path),
                                                   providers=["CPUExecutionProvider"])

    all_words = list(dict.fromkeys(TEST_WORDS + PARITY_WORDS))
    swipes = {}
    for word in all_words:
        xs, ys, ts = synthesize(word, centers)
        swipes[word] = {"x": xs, "y": ys, "t": ts}

    results = {}
    for engine_id, _ in ENGINES:
        session = sessions[engine_id]
        per_word = {}
        for word in all_words:
            swipe = swipes[word]
            feats = featurize(swipe["x"], swipe["y"], swipe["t"])
            outputs = session.run(
                ["log_emissions"],
                {
                    "features": feats[None].astype(np.float32),
                    "layout_keys": keys_pad[None],
                    "layout_mask": mask_pad[None],
                },
            )
            emissions = outputs[0][0]                       # [32, 65]
            log_probs = slice_emissions(emissions, len(letters), 64)  # [32, 27]
            beam = futo_viterbi_beam(log_probs, letters, len(letters), trie,
                                     BEAM_WIDTH, TOP_K,
                                     SCORING["gamma"], SCORING["lambda_"],
                                     SCORING["beta"], SCORING["gamma_prune"],
                                     SCORING["beta_prune"])
            per_word[word] = {
                "top1": beam[0][0] if beam else None,
                "candidates": [{"word": w, "score": float(s)} for w, s in beam],
                "greedy": greedy_ctc(log_probs, letters, len(letters)),
            }
            status = "ok " if per_word[word]["top1"] == word else "MISS"
            print(f"  [{engine_id}] {status} {word:<10} -> "
                  f"{[w for w, _ in beam][:4]}")
        results[engine_id] = per_word

    payload = {
        "scoring": {"gamma": SCORING["gamma"], "lambda": SCORING["lambda_"],
                    "beta": SCORING["beta"], "gammaPrune": SCORING["gamma_prune"],
                    "betaPrune": SCORING["beta_prune"],
                    "beamWidth": BEAM_WIDTH, "topK": TOP_K},
        "testWords": TEST_WORDS,
        "parityWords": PARITY_WORDS,
        "swipes": swipes,
        # float64 for the parity comparison; featurize() itself returns float32,
        # so these are the exact values the JS Float32Array must reproduce.
        "features": {
            word: [float(v) for v in
                   featurize(swipes[word]["x"], swipes[word]["y"], swipes[word]["t"]).ravel()]
            for word in PARITY_WORDS
        },
        "engines": results,
    }
    args.out.write_text(json.dumps(payload))
    print(f"[write] {args.out} ({args.out.stat().st_size} bytes)")

    for engine_id, _ in ENGINES:
        hits = sum(1 for w in TEST_WORDS if results[engine_id][w]["top1"] == w)
        print(f"[python] {engine_id}: top-1 {hits}/{len(TEST_WORDS)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
