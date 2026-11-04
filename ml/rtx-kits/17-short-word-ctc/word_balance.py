"""Word-balanced row sampler for CleverKeys-ML `ctc/train.py` (RTX kit 17).

Implements the sampling half of the short-word recipe
(docs/eval/2026-10-07-short-word-ctc.md §6.1 and final-letter-drops §6.1/§6.4):

* **Word balance.** A real row of word w gets weight count(w)^(tau - 1), so the expected
  per-word exposure is proportional to count(w)^tau. tau = 1 reproduces the plain shuffle;
  tau in {0.5, 0.7} shrinks the encoder's internal word prior (`we` 562x vs `wet` 4x in the
  canonical train tier), which the decoder's lambda * ln f already supplies a second time.
* **End-of-trace emphasis** (final-letter-drops §6.4). Rows whose LAST stroke is short
  (<= 1.1 key pitch between the centres of the last two collapsed letters) are multiplied by
  `short_final_boost` inside the balanced sampler, rather than reweighting CTC frames.
* **Synthetic cap** (short-word §6.2: "mix <= 10 % of steps"). Rows of the pools named in
  `synth_pools` share a fixed total probability mass `synth_frac`, uniform within; they do
  not enter the real word counts.

The sampler only reorders and repeats rows that `SwipeDataset` already holds; it changes no
feature, target or augmentation, and it is fully determined by `seed`.

Wiring: `train_py_word_balance.patch` adds `--word-balance-tau`, `--short-final-boost`,
`--synth-pools` and `--synth-frac` to `train.py` and passes the sampler to the train
DataLoader (shuffle must then be False). Copy this file next to `train.py` first.
"""
from __future__ import annotations

from collections import Counter
from pathlib import Path
from typing import Dict, List, Sequence, Tuple

import numpy as np
import torch
from torch.utils.data import WeightedRandomSampler

#: Device-px letter box used across the short-word work (scripts/short_word_ctc_eval.py).
BOX_W, BOX_H = 1000.0, 470.0
#: One key pitch in px on the 10-key golden rows.
PITCH_PX = 0.1 * BOX_W
SHORT_FINAL_PITCH = 1.1


def _row_labels(tgt_flat: np.ndarray, tgt_off: np.ndarray) -> List[Tuple[int, ...]]:
    """Target index tuple of every row (the a-z normalized label the CTC loss sees)."""
    return [tuple(int(v) for v in tgt_flat[tgt_off[i]:tgt_off[i + 1]]) for i in range(len(tgt_off) - 1)]


def _collapse(label: Tuple[int, ...]) -> Tuple[int, ...]:
    """Drop consecutive repeats: a doubled letter adds no path segment."""
    return tuple(c for i, c in enumerate(label) if i == 0 or c != label[i - 1])


def _short_final(label: Tuple[int, ...], centers: np.ndarray) -> bool:
    """True when the last collapsed stroke is <= SHORT_FINAL_PITCH key pitches long."""
    k = _collapse(label)
    if len(k) < 2 or max(k[-1], k[-2]) >= len(centers):
        return False
    (x0, y0), (x1, y1) = centers[k[-2]], centers[k[-1]]
    return float(np.hypot((x1 - x0) * BOX_W, (y1 - y0) * BOX_H)) <= SHORT_FINAL_PITCH * PITCH_PX


def build_sampler(ds, pool_paths: Sequence[Path], tau: float, short_final_boost: float,
                  synth_pools: Sequence[str], synth_frac: float, seed: int) -> Tuple[WeightedRandomSampler, Dict[str, float]]:
    """Return (sampler, summary) for a `train.SwipeDataset` built from `pool_paths`.

    `ds._src_end` (cumulative row counts per listed pool) and `ds.src_centers` (per-pool
    geometry) are the dataset's own bookkeeping, so a pool repeated in `--train-npz` is
    counted once per listing, exactly as the plain shuffle would see it.
    """
    if not 0.0 < tau <= 1.0:
        raise ValueError(f"--word-balance-tau must be in (0, 1], got {tau}")
    if not 0.0 <= synth_frac < 1.0:
        raise ValueError(f"--synth-frac must be in [0, 1), got {synth_frac}")
    labels = _row_labels(ds.tgt_flat, ds.tgt_off)
    n = len(labels)
    src_of_row = np.asarray(np.searchsorted(ds._src_end, np.arange(n), side="right"))
    synth_names = {s.strip() for s in synth_pools if s.strip()}
    is_synth = np.array([Path(pool_paths[int(s)]).name in synth_names for s in src_of_row.tolist()], bool)
    missing = synth_names - {Path(p).name for p in pool_paths}
    if missing:
        raise SystemExit(f"--synth-pools not in --train-npz: {sorted(missing)}")
    counts = Counter(lab for lab, s in zip(labels, is_synth) if not s)
    w: np.ndarray = np.zeros(n, np.float64)
    n_boost = 0
    for i, lab in enumerate(labels):
        if is_synth[i]:
            continue
        w[i] = counts[lab] ** (tau - 1.0)
        if short_final_boost != 1.0 and _short_final(lab, ds.src_centers[int(src_of_row[i])]):
            w[i] *= short_final_boost
            n_boost += 1
    real_mass = w.sum()
    n_synth = int(is_synth.sum())
    if n_synth:
        if synth_frac <= 0.0:
            raise SystemExit("--synth-pools given but --synth-frac is 0: those rows would never be drawn")
        # Real rows carry (1 - synth_frac) of the mass, synthetic rows synth_frac, uniform within.
        w *= (1.0 - synth_frac) / real_mass
        w[is_synth] = synth_frac / n_synth
    gen = torch.Generator()
    gen.manual_seed(seed)
    sampler = WeightedRandomSampler(torch.as_tensor(w, dtype=torch.double), num_samples=n,
                                    replacement=True, generator=gen)
    summary = {"rows": float(n), "real_words": float(len(counts)), "synth_rows": float(n_synth),
               "short_final_boosted_rows": float(n_boost), "tau": tau, "synth_frac": synth_frac}
    return sampler, summary
