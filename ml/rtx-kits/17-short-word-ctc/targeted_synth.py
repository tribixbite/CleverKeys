#!/usr/bin/env python3
"""Geometry-selected synthetic rows for the short-word fine-tune (RTX kit 17).

Recipe items: short-word §6.2 and final-letter-drops §6.2/§6.3
(docs/eval/2026-10-07-short-word-ctc.md, docs/eval/2026-10-07-final-letter-drops.md).
The generator is CleverKeys-ML's audited `english_synth.py` transplant (donor residual ->
target polyline via `layout_aug.warp_path`), imported, not copied; only the WORD SELECTION
and a final-stop variant are new here. Selection is a geometry rule plus a real-count cap —
there is no word list anywhere:

  class A (`wet`/`ad`, short-word §6.2): 2..6 letters, real train count <= --max-real-a, and
          mechanism M1 (collinear interior key, turn < 35 deg) or M2 (final stroke passes a
          key that makes a more frequent word) from select_target_class.py, or M3 (first two
          collapsed keys adjacent, <= 1.2 key pitch).
  class B (short final hooks, final-letter-drops §6.3): <= 8 letters ending in s / ed / er,
          last collapsed stroke <= 1.1 key pitch, real train count < --max-real-b.

Endpoint realism: every row carries a REAL donor's residual, so overshoot/undershoot is drawn
from the real along-track distribution by construction. A fraction --stop-frac of rows gets
a precise-stop variant: the path is compressed into the first (1 - f) of the 64 time-uniform
feature points and the last point is held for the remaining f, f ~ U(--stop-lo, --stop-hi).
That is what a final dwell looks like after `CtcFeaturizer`'s 60 Hz + 64-point resample.

Donors must be the contract-rewritten TRAIN caches (contract_rows.py + prepare_data.py), never
val/test. Output schema = prepare_data.py's, so the npz goes straight into --train-npz and is
named in --synth-pools so the word-balance sampler caps its mass (--synth-frac <= 0.10).

Usage (from CleverKeys-ML/ctc, on the RTX box):
  python3 $KIT/targeted_synth.py --app $APP --ctc . --workdir $WD --rows 120000 \
      --donors train_t3_fix.npz,train_t3hws_fix.npz,tier_sw234_fix.npz,tier_sw5q_fix.npz \
      --out synth_en_shortword.npz
Exit code 2 if any of english_synth's three acceptance gates fails (the file is still written
for inspection, as english_synth does; do not train on it).
"""
from __future__ import annotations

import argparse
import json
import math
import sys
import time
from pathlib import Path
from typing import Dict, List, Tuple

import numpy as np

KIT = Path(__file__).resolve().parent


def adjacent_start(word: str, keys: Dict[str, Tuple[float, float]], pitch_px: float) -> bool:
    """M3: the first two collapsed keys are neighbours (<= 1.2 key pitch apart)."""
    from select_target_class import collapse
    k = collapse(word)
    if len(k) < 2:
        return False
    (x0, y0), (x1, y1) = keys[k[0]], keys[k[1]]
    return math.hypot(x1 - x0, y1 - y0) <= 1.2 * pitch_px


def last_stroke_px(word: str, keys: Dict[str, Tuple[float, float]]) -> float:
    from select_target_class import collapse
    k = collapse(word)
    if len(k) < 2:
        return 0.0
    (x0, y0), (x1, y1) = keys[k[-2]], keys[k[-1]]
    return math.hypot(x1 - x0, y1 - y0)


def precise_stop(feat: np.ndarray, frac: float) -> np.ndarray:
    """Hold the final point for `frac` of the 64 time-uniform samples (a final dwell)."""
    n = feat.shape[1]
    m = int(round(frac * n))
    if m <= 0 or m >= n - 1:
        return feat
    src = np.linspace(0.0, n - 1.0, n - m)
    out = np.empty_like(feat)
    for ax in range(feat.shape[0]):
        out[ax, : n - m] = np.interp(src, np.arange(n), feat[ax])
        out[ax, n - m:] = feat[ax, -1]
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--app", type=Path, required=True)
    ap.add_argument("--ctc", type=Path, required=True)
    ap.add_argument("--workdir", type=Path, required=True)
    ap.add_argument("--donors", required=True, help="contract-rewritten TRAIN npz names in <workdir>/cache")
    ap.add_argument("--vocab", default="data/futo_en_wordlist.combined")
    ap.add_argument("--rows", type=int, default=120_000)
    ap.add_argument("--seed", type=int, default=20261104)
    ap.add_argument("--max-real-a", type=int, default=50, dest="max_real_a")
    ap.add_argument("--max-real-b", type=int, default=3, dest="max_real_b")
    ap.add_argument("--no-m3", action="store_true", dest="no_m3",
                    help="pre-registered fallback if the ru_precedent gate fails: drop M3 words")
    ap.add_argument("--stop-frac", type=float, default=0.5, dest="stop_frac")
    ap.add_argument("--stop-lo", type=float, default=0.15, dest="stop_lo")
    ap.add_argument("--stop-hi", type=float, default=0.35, dest="stop_hi")
    ap.add_argument("--validate-rows", type=int, default=5000, dest="validate_rows")
    ap.add_argument("--wrong-geom", default="futo_dvorak.json", dest="wrong_geom")
    ap.add_argument("--out", default="synth_en_shortword.npz")
    a = ap.parse_args()
    sys.path.insert(0, str(KIT))
    sys.path.insert(0, str(a.ctc.resolve()))
    import select_target_class as stc  # noqa: E402  shared geometry rule
    from english_synth import (gate_report, load_en_lexicon, real_endpoint_band,  # noqa: E402
                               real_word_counts, synthesize_en)
    from cyrillic_synth import build_donor_index  # noqa: E402
    from futo_decoder_eval import load_layout  # noqa: E402
    from layout_aug import endpoint_stats, load_az_centers  # noqa: E402

    if stc.APP.resolve() != a.app.resolve():
        raise SystemExit(f"kit lives in {stc.APP}, --app is {a.app}: run the kit from the app checkout")
    ctc = a.ctc.resolve()
    cache = a.workdir / "cache"
    donor_paths = [cache / p.strip() for p in a.donors.split(",") if p.strip()]
    for p in donor_paths:
        if p.name in ("val.npz", "test.npz") or not p.exists():
            raise SystemExit(f"--donors: {p} is missing or an eval split")
    rng = np.random.default_rng(a.seed)
    _, qwerty = load_layout(ctc / "en_qwerty.json")
    qwerty = np.asarray(qwerty, np.float32)[:26]

    keys, app_lex = stc.key_px(), stc.lexicon()
    counts = real_word_counts(donor_paths)
    sel: List[Tuple[str, float]] = []
    n_a = n_b = 0
    for w, _f in load_en_lexicon(a.workdir / a.vocab):
        c = counts.get(w, 0)
        in_a = (2 <= len(w) <= 6 and c <= a.max_real_a
                and (bool(stc.classify(w, keys, app_lex)[0])
                     or (not a.no_m3 and adjacent_start(w, keys, stc.PITCH_PX))))
        in_b = (len(w) <= 8 and c < a.max_real_b and w.endswith(("s", "ed", "er"))
                and 0.0 < last_stroke_px(w, keys) <= 1.1 * stc.PITCH_PX)
        if in_a or in_b:
            sel.append((w, 1.0))  # uniform per word: the sampler, not the lexicon, sets exposure
            n_a += in_a; n_b += in_b
    print(f"selected {len(sel)} words (class A {n_a}, class B {n_b})", flush=True)

    donor_feats, donor_seqs, by_count = build_donor_index(donor_paths)
    feats, words, st = synthesize_en(a.rows, rng, sel, donor_feats, donor_seqs, by_count, qwerty)
    ep_real = real_endpoint_band(donor_paths, donor_feats, qwerty, a.validate_rows, rng)
    del donor_feats
    stop = rng.random(len(feats)) < a.stop_frac
    for i in np.flatnonzero(stop):
        feats[i] = precise_stop(feats[i], float(rng.uniform(a.stop_lo, a.stop_hi)))

    n = min(a.validate_rows, len(words))
    ep = endpoint_stats(feats[:n], words[:n], qwerty)
    wrong = np.asarray(load_az_centers(ctc / "layouts" / a.wrong_geom), np.float32)
    gates, deltas = gate_report(ep, ep_real, endpoint_stats(feats[:n], words[:n], wrong))
    for g, ok in gates.items():
        print(f"gate {g}: {'PASS' if ok else 'FAIL'}")
    tgt_flat = np.concatenate([np.frombuffer(w.encode(), np.uint8).astype(np.int64) - 97 for w in words])
    prov = dict(generator="cleverkeys ml/rtx-kits/17-short-word-ctc/targeted_synth.py", seed=a.seed,
                rows=a.rows, donors=a.donors, vocab=a.vocab, max_real_a=a.max_real_a,
                max_real_b=a.max_real_b, no_m3=a.no_m3, stop_frac=a.stop_frac, stop_range=[a.stop_lo, a.stop_hi],
                n_words=len(sel), n_class_a=n_a, n_class_b=n_b, stop_rows=int(stop.sum()), stats=st,
                gates=gates, gate_deltas=deltas, created_utc=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))
    out = cache / a.out
    np.savez_compressed(out, features=feats, targets=tgt_flat,
                        target_lengths=np.array([len(w) for w in words], np.int64),
                        words=np.array(words), provenance=np.array(json.dumps(prov, sort_keys=True)))
    (cache / (Path(a.out).stem + "_stats.json")).write_text(json.dumps(prov, indent=1))
    print(f"wrote {out}: {st}")
    return 0 if all(gates.values()) else 2


if __name__ == "__main__":
    raise SystemExit(main())
