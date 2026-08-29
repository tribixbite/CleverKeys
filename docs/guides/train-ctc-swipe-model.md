# Training a From-Scratch CTC Swipe Model (GPU box → ONNX → Kotlin `swipe/ctc/`)

**Date:** 2026-08-06
**Status:** Runnable end-to-end recipe (documentation only — no app code changed by this guide).
**Decision basis:** `docs/history/audits/2026-08-06-futo-engine-integration-decision.md` (option D:
retrain-to-ONNX; gates G2/G3/G4), architecture ground truth
`docs/history/audits/2026-08-06-futo-decoder-integration-study.md` ("study"), consuming decoder
`src/main/kotlin/tribixbite/cleverkeys/swipe/ctc/` + spec `docs/specs/ctc-swipe-engine.md`.

This guide takes a fresh CUDA machine from zero to a trained, ONNX-exported CTC
swipe-emission encoder whose I/O signature drops directly into the already-committed,
parity-tested Kotlin decoder (`CtcSwipeDecoder` / `CtcEmissionModel` seam). It also
covers the offline acceptance evaluation that reproduces our published FUTO-comparison
numbers, and the phase-2 refinement head.

---

## 0. Licensing rules (read first — these are hard constraints)

Verified 2026-08-06 against primary sources (decision doc §2):

- **Training DATA is open**: `futo-org/swipe.futo.org` (the corpus our local
  `{train,val,test}_hwsfuto.jsonl` splits derive from) is **MIT**;
  `futo-org/swipe-negatives` is **Apache-2.0**. Weights we train on this data are
  **wholly ours** — GPL-3.0-clean, F-Droid-clean. Add a one-line MIT attribution for the
  corpus to the repo `NOTICE` when the model ships.
- **NEVER initialize from, fine-tune, or distill FUTO's published weights** (`futo-swipe`
  `.pte` files) or *any outputs of those models*. The FUTO Model Weights License 1.0
  defines "Derivative Models" to include "any model trained in whole or in part on Model
  Outputs" — distillation, teacher-labeling, output-matching losses, or weight init from
  their models would re-import their license (mandatory "powered by FUTO Swipe" notice,
  custom non-OSI license, F-Droid risk). **Train from scratch on the data only.**
  FUTO ceiling-eval outputs stay offline as benchmarks; they never enter the training loop.
- The decode *algorithms* were ported from FUTO's GPL-3.0 `swipe-library` — same license
  as CleverKeys, already committed, no issue.
- Consequence: everything in this guide (architecture, hyperparameters, augmentation) is
  our own reimplementation of the *published ideas* (paper arXiv 2606.25247, "FUTO Swipe:
  Layout-Agnostic Neural Swipe Decoding") with only the **I/O contract** copied so the
  model is drop-in swappable. Ideas and I/O shapes are not copyrightable subject matter;
  weights and outputs are the boundary.

---

## 1. The contract the trained model must satisfy

The committed Kotlin module (`src/main/kotlin/tribixbite/cleverkeys/swipe/ctc/`) already
implements and golden-parity-tests everything except the emission model. The exported
ONNX model fills the `CtcEmissionModel` seam (`CtcSwipeDecoder.kt`). Exact contract:

### Inputs (mirroring FUTO `honorable_sturgeon`, study §1a)

| Name | Shape | dtype | Meaning |
|---|---|---|---|
| `features` | `[1, 2, 64]` | float32 | Resampled path: row 0 = `x0..x63`, row 1 = `y0..y63`, all in `[0,1]`. Produced by `CtcFeaturizer.featurize` (60 Hz linspace → fixed-64 index-uniform, clamp `[0,1]`). |
| `layout_keys` | `[1, 64, 2]` | float32 | Key centers `(cx, cy)` in `[0,1]`, one per slot; slots `[0..K)` hold the K real keys in **emission-column order** (`CtcLayout.alphabet` order), remaining slots `(0,0)`. Produced by `CtcFeaturizer.buildPaddedLayout`. |
| `layout_mask` | `[1, 64]` | bool | `true` for the K real key slots, `false` for pad. |

### Outputs

| Name | Shape | dtype | Meaning |
|---|---|---|---|
| `log_emissions` | `[1, 32, 65]` | float32 | Per-frame **log-softmaxed** distribution over the 64 key slots + CTC **blank at index 64**. T' = 32 (2× temporal downsample of T=64). |
| `coefficients` | `[1, 32, 64]` | float32 | DCT spatial coefficients per frame (8×8 = 64). Consumed only by the phase-2 refinement head; export from day one so the signature never changes. |
| `lambda` | `[1, 32, 1]` | float32 | Per-frame gate scalar (same role as FUTO's; internals are ours). Phase-2 input. |

### How Kotlin consumes it

```kotlin
// Future CtcEmissionModel impl (Phase G3 — NOT part of this guide's changes):
// run session -> fullHead FloatArray of length 32*65, then:
val emissions = CtcEmissions.sliceFromHead(fullHead, frames = 32, maxKeys = 64, numLetters = 26)
// -> [32][27], blank moved from column 64 to column 26; fed to CtcBeamDecoder.decode(...)
```

Hard invariants (violating any one breaks the drop-in):
1. Emission class column `c` (for `c < K`) corresponds to `alphabet[c]` of the
   `CtcLayout` — for en_qwerty that ordering is **alphabetical a..z** (Appendix A).
2. **Blank lives at full-head column 64** (`MAX_KEYS`), never at 0.
3. `log_emissions` is already log-softmaxed inside the graph (the Kotlin beam adds
   log-probs; it never re-normalizes).
4. Masked (pad) key slots must carry ~zero probability (large negative logits before the
   in-graph softmax).
5. Featurization must match `CtcFeaturizer` exactly — we guarantee this by training with
   the **same Python port** (`scripts/futo_decoder_eval.py::featurize`) that the Kotlin
   featurizer is bit-identical to (`CtcParityTest.featurizer_matchesPythonPort_bitIdentical`).

### Coordinate-frame note (important, easy to get wrong)

The `hwsfuto` JSONL points are **already in the canonical model frame**: `x, y ∈ [0,1]`
over the letter area, matching the canonical en_qwerty key centers
(cx 0.05–0.95, cy ∈ {0.1667, 0.5, 0.8333} — Appendix A). Do **not** apply the 4/3
vertical aspect correction during training — that correction
(`CtcFeaturizer.normalizeRawY`) is an *on-device* mapping from raw screen pixels into
this frame and is already accounted for in the dataset.

---

## 2. Environment (fresh CUDA box)

### Hardware

- **Single consumer GPU is plenty.** The model is ≲1 M parameters (FUTO's encoder is
  635 K / 2.65 MB fp32). Anything with ≥6 GB VRAM works (RTX 3060/4060 and up; an A10/T4
  cloud instance is overkill but fine). Training is data-loader-bound, not compute-bound.
- **Wall-clock**: on the local 110,876-row split, ~5–10 min/epoch → a full 80-epoch run
  is **an evening**. On the full ~1 M-swipe HF corpus, ~10×, i.e. **overnight**.
  Budget 3–6 runs for the G2 spike (recipe iteration), so a weekend of GPU time total.
- CPU-only is possible (tiny model) but 10–30× slower — not recommended for iteration.

### Software

```bash
# Python 3.10–3.12. CUDA 12.x driver assumed.
python3 -m venv ~/ctc-train/venv
source ~/ctc-train/venv/bin/activate
pip install --upgrade pip
pip install torch --index-url https://download.pytorch.org/whl/cu121   # torch >= 2.4
pip install numpy onnx onnxruntime tqdm
# optional, only if pulling the corpus from HF instead of copying local splits:
pip install datasets huggingface_hub
```

Version pins that matter: `torch>=2.4` (stable ONNX exporter for the ops we use:
`atan2`, `cumsum`, `where`, `arange`, `log_softmax` — the same op family FUTO's exported
graph uses, study §1a), `onnx>=1.16`, `onnxruntime>=1.18` (parity checks; the app runs
`onnxruntime-android:1.20.0`, and opset 17 is comfortably supported there).

### Directory layout on the training box

```
~/ctc-train/
├── venv/
├── futo_decoder_eval.py        # COPY from repo scripts/ (featurizer + trie + loaders)
├── futo_decoder_ceiling.py     # COPY from repo scripts/ (futo_viterbi_beam — the eval beam)
├── en_qwerty.json              # canonical layout (Appendix A, or copy from swipe-library)
├── data/
│   ├── train_hwsfuto.jsonl     # 110,876 rows
│   ├── val_hwsfuto.jsonl       #   9,918 rows
│   ├── test_hwsfuto.jsonl      #   2,400 rows  (NEVER trained on; final numbers only)
│   └── futo_en_wordlist.combined   # 131,544-word AOSP lexicon for the eval beam
├── cache/                      # prepare_data.py output (npz feature cache)
├── ckpt/                       # checkpoints
├── prepare_data.py             # §4
├── model.py                    # §5
├── train.py                    # §6
├── eval_beam.py                # §7 (the acceptance metric)
├── export_onnx.py              # §8
└── make_golden.py              # §9 (golden traces for the Kotlin parity test)
```

Copy from this repo / the phone:

```bash
# from the repo working tree
scp phone:~/git/swype/cleverkeys/scripts/futo_decoder_eval.py    ~/ctc-train/
scp phone:~/git/swype/cleverkeys/scripts/futo_decoder_ceiling.py ~/ctc-train/
# data (local canonical splits — these are the splits every committed eval number uses)
scp phone:~/storage/shared/swipedata/{train,val,test}_hwsfuto.jsonl ~/ctc-train/data/
scp phone:~/storage/shared/swipedata/futo_en_wordlist.combined      ~/ctc-train/data/
```

`futo_decoder_eval.py` imports only stdlib + numpy at module level (the ExecuTorch
import is lazy inside its `Encoder` class, which we never construct), so it is safe to
import on a box with no ExecuTorch.

---

## 3. Data

### 3a. Primary: the local hwsfuto splits (recommended)

Row format (one JSON object per line):

```json
{"word": "four", "points": [{"t": 0.0, "x": 0.408, "y": 0.558}, ...]}
```

- `t` in ms from swipe start; `x, y ∈ [0,1]` already in the canonical layout frame (§1).
- Splits: train 110,876 / val 9,918 / test 2,400. **Use these exact splits** — every
  committed baseline number (FUTO floor 79.25, ceiling 84.83, our neural 74.62 on
  test-2400; see decision doc §3) is measured on them, so G2/G4 comparisons are
  apples-to-apples. Keep `test_hwsfuto.jsonl` untouched until the final report.

### 3b. Alternative / scale-up: the HF corpus

`futo-org/swipe.futo.org` (MIT) is the full ~1 M-swipe corpus the local splits derive
from. Use it for the post-G2 scale-up run:

```python
from datasets import load_dataset
ds = load_dataset("futo-org/swipe.futo.org")   # inspect fields; normalize to the
                                               # {word, points:[{t,x,y}]} row shape
```

Inspect the field names/coordinate conventions on first load and convert rows into the
same JSONL shape as §3a, then reuse the identical pipeline. **Critical**: if you scale
up, re-derive train/val so that the local val-9,918 and test-2,400 rows are **excluded
from training** (dedupe on exact point-sequence hash, not just word), otherwise the gate
numbers are contaminated. `futo-org/swipe-negatives` (Apache-2.0) is for discriminative/
rejection training — not needed for phase 1 or 2; ignore it for now.

### 3c. Target encoding

- Word → lowercase → strip every char outside `a-z` (`don't` → `dont`) — the same
  normalization the lexicon loaders use (`futo_decoder_eval.load_combined_vocab`) and
  the Kotlin `CtcLexiconTrie.loadStrippingNonAlphabet` mirrors.
- Letter → class index = position in the layout alphabet (a=0 … z=25 for en_qwerty).
- CTC blank = **the full-head index 64** (not 26!) — training runs over the full 65-class
  head so the export needs no post-hoc index surgery.
- Drop rows whose normalized word is empty or longer than 28 chars (T'=32 frames must
  cover the CTC alignment; standard CTC needs one extra frame per adjacent double
  letter, so 28 is a safe cap; in practice the corpus max is far below).

---

## 4. Data prep — `prepare_data.py`

Featurization is done ONCE with the exact port and cached; augmentation (§6) is applied
per-batch on the cached `[2,64]` tensors (linear resampling commutes with the affine
augmentations, so caching before augmentation is lossless).

```python
#!/usr/bin/env python3
"""Cache exact-port featurization of hwsfuto JSONL -> npz.

Uses futo_decoder_eval.featurize — the SAME function the Kotlin CtcFeaturizer is
bit-identical to (CtcParityTest), so train-time and app-time featurization agree.
"""
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from futo_decoder_eval import featurize  # exact resampler port (60Hz linspace -> fixed 64)

MAX_WORD_LEN = 28

def normalize_word(w: str) -> str:
    return "".join(c for c in w.lower() if "a" <= c <= "z")

def prepare(jsonl_path: Path, out_path: Path) -> None:
    feats, words = [], []
    dropped = 0
    with open(jsonl_path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            obj = json.loads(line)
            w = normalize_word(obj["word"])
            if not w or len(w) > MAX_WORD_LEN:
                dropped += 1
                continue
            pts = obj["points"]
            xs = [float(p["x"]) for p in pts]
            ys = [float(p["y"]) for p in pts]
            ts = [float(p["t"]) for p in pts]
            feats.append(featurize(xs, ys, ts))   # [2, 64] float32
            words.append(w)
    features = np.stack(feats).astype(np.float32)             # [N, 2, 64]
    # ragged targets -> flat + lengths (CTC-loss friendly)
    tgt_flat = np.concatenate([np.frombuffer(w.encode(), np.uint8) - ord("a")
                               for w in words]).astype(np.int64)
    tgt_len = np.array([len(w) for w in words], np.int64)
    np.savez_compressed(out_path, features=features, targets=tgt_flat,
                        target_lengths=tgt_len, words=np.array(words))
    print(f"{jsonl_path.name}: {len(words)} rows cached ({dropped} dropped) -> {out_path}")

if __name__ == "__main__":
    data = Path("data"); cache = Path("cache"); cache.mkdir(exist_ok=True)
    for split in ("train", "val", "test"):
        prepare(data / f"{split}_hwsfuto.jsonl", cache / f"{split}.npz")
```

Run: `python prepare_data.py` (~2–5 min for the 110 k split; featurization is pure
Python but only runs once).

---

## 5. Model — `model.py`

Design goals, in order: (1) match the §1 I/O contract exactly; (2) be layout-agnostic —
key identity must come from the `layout_keys` geometry, never from slot index (the
augmentation in §6 enforces this); (3) stay in the ONNX-clean op family the study
verified (`atan2`, `cumsum`, `where`, `arange`, `log_softmax` — all standard);
(4) stay ≲1 M params. Internals are **our own design** (TCN + DCT spatial head); only
the tensor signature copies FUTO.

```python
#!/usr/bin/env python3
"""Layout-agnostic CTC swipe-emission encoder.

I/O contract (must match src/main/kotlin/.../swipe/ctc/ exactly — see the guide §1):
  in : features    [B, 2, 64]  float32   (x row, y row; [0,1])
       layout_keys [B, 64, 2]  float32   (key centers, pad = (0,0))
       layout_mask [B, 64]     bool      (true for real keys)
  out: log_emissions [B, 32, 65] float32 (log-softmaxed; blank = index 64)
       coefficients  [B, 32, 64] float32 (8x8 DCT spatial coefficients)
       lambda        [B, 32, 1]  float32 (per-frame positive gate)
"""
import math

import torch
import torch.nn as nn
import torch.nn.functional as F

MAX_KEYS = 64
T_IN = 64
T_OUT = 32          # one stride-2 stem: 64 -> 32
NUM_FREQ = 8        # 8x8 DCT basis -> 64 coefficients
MASK_NEG = -1.0e4   # finite (fp16-safe) "off" logit for pad key slots


def path_features(features: torch.Tensor) -> torch.Tensor:
    """[B,2,64] raw path -> [B,8,64] derived channels, all computed in-graph so the
    exported ONNX consumes the same raw [2,64] tensor CtcFeaturizer produces."""
    x = features[:, 0, :]
    y = features[:, 1, :]
    dx = F.pad(x[:, 1:] - x[:, :-1], (1, 0))
    dy = F.pad(y[:, 1:] - y[:, :-1], (1, 0))
    speed = torch.sqrt(dx * dx + dy * dy + 1e-8)
    ang = torch.atan2(dy, dx + 1e-8)
    arc = torch.cumsum(speed, dim=1)                    # cumulative arc length
    return torch.stack(
        [x, y, dx, dy, speed, torch.sin(ang), torch.cos(ang), arc], dim=1)


class ResBlock(nn.Module):
    """Dilated temporal-conv residual block (the TCN body)."""

    def __init__(self, ch: int, dilation: int) -> None:
        super().__init__()
        pad = 2 * dilation
        self.conv1 = nn.Conv1d(ch, ch, 5, padding=pad, dilation=dilation)
        self.conv2 = nn.Conv1d(ch, ch, 5, padding=pad, dilation=dilation)
        self.norm1 = nn.GroupNorm(8, ch)
        self.norm2 = nn.GroupNorm(8, ch)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        h = F.gelu(self.norm1(self.conv1(x)))
        h = self.norm2(self.conv2(h))
        return F.gelu(x + h)


class CtcSwipeEncoder(nn.Module):
    def __init__(self, ch: int = 96, dilations: tuple = (1, 2, 4, 8)) -> None:
        super().__init__()
        self.stem = nn.Conv1d(8, ch, 5, stride=2, padding=2)      # 64 -> 32 frames
        self.stem_norm = nn.GroupNorm(8, ch)
        self.blocks = nn.ModuleList(ResBlock(ch, d) for d in dilations)
        self.coeff_head = nn.Linear(ch, NUM_FREQ * NUM_FREQ)      # DCT coefficients
        self.lambda_head = nn.Linear(ch, 1)                       # positive gate
        self.blank_head = nn.Linear(ch, 1)                        # CTC blank logit
        freq = torch.arange(NUM_FREQ, dtype=torch.float32)
        self.register_buffer("freq", freq)

    def forward(self, features: torch.Tensor, layout_keys: torch.Tensor,
                layout_mask: torch.Tensor):
        h = F.gelu(self.stem_norm(self.stem(path_features(features))))  # [B,ch,32]
        for blk in self.blocks:
            h = blk(h)
        h = h.transpose(1, 2)                                     # [B,32,ch]

        coeff = self.coeff_head(h)                                # [B,32,64]
        lam = F.softplus(self.lambda_head(h))                     # [B,32,1] > 0
        blank = self.blank_head(h)                                # [B,32,1]

        # DCT-II spatial basis evaluated at key centers (layout-agnostic scoring):
        # basis[b,k,(u*8+v)] = cos(pi*u*cx_k) * cos(pi*v*cy_k)
        cx = layout_keys[..., 0]                                  # [B,64]
        cy = layout_keys[..., 1]
        bx = torch.cos(math.pi * self.freq[None, None, :] * cx[:, :, None])  # [B,64,8]
        by = torch.cos(math.pi * self.freq[None, None, :] * cy[:, :, None])  # [B,64,8]
        basis = (bx[:, :, :, None] * by[:, :, None, :]).reshape(
            cx.shape[0], MAX_KEYS, NUM_FREQ * NUM_FREQ)           # [B,64,64]

        # per-frame spatial field sampled at each key center, gated by lambda
        key_logits = torch.einsum("btc,bkc->btk", coeff, basis) * lam  # [B,32,64]
        key_logits = torch.where(layout_mask[:, None, :], key_logits,
                                 torch.full_like(key_logits, MASK_NEG))

        logits = torch.cat([key_logits, blank], dim=-1)           # [B,32,65]
        log_emissions = F.log_softmax(logits, dim=-1)
        return log_emissions, coeff, lam
```

Parameter count with `ch=96`, 4 blocks: ~0.45 M (each block ≈ 2×(5·96·96) ≈ 92 K) —
right in the honorable_sturgeon budget class. If G2 misses by a small margin, the first
knobs are `ch=128` and 6 blocks (still < 1.5 M) before touching anything structural.

Why a DCT head instead of a plain per-key MLP: the coefficients are (a) how the model
generalizes across layouts — it predicts a continuous spatial log-density and *samples*
it at whatever key centers it is given; and (b) exactly the auxiliary signal the phase-2
refinement head consumes (§11), matching the `[T',64]` `coefficients` output the ceiling
harness (`futo_decoder_ceiling.py::build_decoder_input`) already expects.

---

## 6. Training — `train.py`

### Augmentation (this is what buys layout-agnosticism)

All training data is en_qwerty, so the model would otherwise memorize "slot 0 = a at
(0.1, 0.5)". Three augmentations break that:

1. **Slot permutation** (essential): per sample, scatter the 26 keys into 26 random slots
   of the 64 (identity with prob 0.5 so the inference-time layout — keys in slots
   `[0..26)` alphabetically — stays in-distribution). Targets are remapped to the
   permuted slot indices. This forces the model to read key *geometry*, not slot index.
2. **Shared geometric jitter**: one affine (scale 0.85–1.15 per axis, translate ±0.05,
   x-mirror with prob 0.25) applied to **both** the path and the key centers, plus
   independent per-key center noise σ=0.01 and per-point path noise σ=0.005, clamp [0,1].
   Mirroring both together is geometrically consistent (a mirrored layout with a
   mirrored swipe decodes to the same word).
3. Optional later: sampling entirely different layout geometries (other language packs'
   key grids) with synthetic ideal paths — only needed when multi-language ships.

### The script

```python
#!/usr/bin/env python3
"""Train the CTC swipe encoder from scratch. Single GPU.

Usage: python train.py [--epochs 80] [--batch 256] [--lr 3e-3] [--ch 96]
"""
import argparse
import json
import math
import random
from pathlib import Path

import numpy as np
import torch
import torch.nn.functional as F
from torch.utils.data import DataLoader, Dataset

from model import MAX_KEYS, T_OUT, CtcSwipeEncoder

BLANK = MAX_KEYS  # 64 — full-head blank index (the Kotlin slice relocates it to K)


def load_layout_centers(path: str = "en_qwerty.json") -> np.ndarray:
    obj = json.loads(Path(path).read_text())
    letters = list(obj["letters"])                      # 'a'..'z'
    by_letter = {k["letter"]: (k["cx"], k["cy"]) for k in obj["keys"]}
    return np.array([by_letter[c] for c in letters], np.float32)   # [26,2]


class SwipeDataset(Dataset):
    def __init__(self, npz_path: str, centers: np.ndarray, augment: bool) -> None:
        d = np.load(npz_path, allow_pickle=True)
        self.features = d["features"]                   # [N,2,64]
        self.tgt_flat = d["targets"]
        self.tgt_len = d["target_lengths"]
        self.tgt_off = np.concatenate([[0], np.cumsum(self.tgt_len)])
        self.centers = centers                          # [26,2]
        self.augment = augment
        self.k = centers.shape[0]                       # 26

    def __len__(self) -> int:
        return len(self.tgt_len)

    def __getitem__(self, i: int):
        feats = self.features[i].astype(np.float32).copy()          # [2,64]
        target = self.tgt_flat[self.tgt_off[i]:self.tgt_off[i + 1]].copy()
        centers = self.centers.copy()                               # [26,2]

        if self.augment:
            # shared affine on path + centers
            sx, sy = np.random.uniform(0.85, 1.15, 2)
            tx, ty = np.random.uniform(-0.05, 0.05, 2)
            mirror = np.random.rand() < 0.25
            for arr_x, arr_y in ((feats[0], feats[1]),
                                 (centers[:, 0], centers[:, 1])):
                arr_x[:] = (arr_x - 0.5) * sx + 0.5 + tx
                arr_y[:] = (arr_y - 0.5) * sy + 0.5 + ty
                if mirror:
                    arr_x[:] = 1.0 - arr_x
            feats += np.random.normal(0.0, 0.005, feats.shape).astype(np.float32)
            centers += np.random.normal(0.0, 0.01, centers.shape).astype(np.float32)
            np.clip(feats, 0.0, 1.0, out=feats)
            np.clip(centers, 0.0, 1.0, out=centers)

        # slot assignment (identity or random permutation into 64 slots)
        keys = np.zeros((MAX_KEYS, 2), np.float32)
        mask = np.zeros((MAX_KEYS,), bool)
        if self.augment and np.random.rand() < 0.5:
            slots = np.random.permutation(MAX_KEYS)[: self.k]
        else:
            slots = np.arange(self.k)                   # inference-time layout
        keys[slots] = centers
        mask[slots] = True
        target_slots = slots[target]                    # letters -> slot indices

        return (torch.from_numpy(feats), torch.from_numpy(keys),
                torch.from_numpy(mask), torch.from_numpy(target_slots.astype(np.int64)))


def collate(batch):
    feats = torch.stack([b[0] for b in batch])
    keys = torch.stack([b[1] for b in batch])
    mask = torch.stack([b[2] for b in batch])
    tlens = torch.tensor([len(b[3]) for b in batch], dtype=torch.long)
    targets = torch.cat([b[3] for b in batch])
    return feats, keys, mask, targets, tlens


@torch.no_grad()
def greedy_accuracy(model, loader, device) -> float:
    """Val metric: greedy-CTC collapse == target word. FUTO-floor anchor: ~44 %
    greedy on test-2400 corresponded to 79.25 % beam top-1 (study §5a)."""
    model.eval()
    hit = n = 0
    for feats, keys, mask, targets, tlens in loader:
        log_e, _, _ = model(feats.to(device), keys.to(device), mask.to(device))
        am = log_e.argmax(-1).cpu().numpy()             # [B,32]
        off = 0
        for b in range(am.shape[0]):
            tgt = targets[off:off + tlens[b]].numpy().tolist()
            off += int(tlens[b])
            out, prev = [], -1
            for c in am[b]:
                c = int(c)
                if c != prev and c != BLANK:
                    out.append(c)
                prev = c
            hit += int(out == tgt)
            n += 1
    model.train()
    return hit / max(n, 1)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--epochs", type=int, default=80)
    ap.add_argument("--batch", type=int, default=256)
    ap.add_argument("--lr", type=float, default=3e-3)
    ap.add_argument("--weight-decay", type=float, default=0.01)
    ap.add_argument("--warmup", type=int, default=1000)
    ap.add_argument("--ch", type=int, default=96)
    ap.add_argument("--patience", type=int, default=15)
    ap.add_argument("--seed", type=int, default=1234)
    ap.add_argument("--workers", type=int, default=8)
    args = ap.parse_args()

    torch.manual_seed(args.seed)
    np.random.seed(args.seed)
    random.seed(args.seed)
    device = "cuda" if torch.cuda.is_available() else "cpu"

    centers = load_layout_centers()
    train_ds = SwipeDataset("cache/train.npz", centers, augment=True)
    val_ds = SwipeDataset("cache/val.npz", centers, augment=False)
    train_dl = DataLoader(train_ds, args.batch, shuffle=True, collate_fn=collate,
                          num_workers=args.workers, pin_memory=True, drop_last=True)
    val_dl = DataLoader(val_ds, args.batch, shuffle=False, collate_fn=collate,
                        num_workers=2)

    model = CtcSwipeEncoder(ch=args.ch).to(device)
    n_params = sum(p.numel() for p in model.parameters())
    print(f"params: {n_params / 1e6:.2f}M  device: {device}")

    ctc = torch.nn.CTCLoss(blank=BLANK, zero_infinity=True)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr,
                            weight_decay=args.weight_decay)
    total_steps = args.epochs * len(train_dl)

    def lr_at(step: int) -> float:
        if step < args.warmup:
            return step / args.warmup
        p = (step - args.warmup) / max(1, total_steps - args.warmup)
        return 0.5 * (1.0 + math.cos(math.pi * p))

    sched = torch.optim.lr_scheduler.LambdaLR(opt, lr_at)
    # bf16 autocast needs no GradScaler (unlike fp16); keep the loop simple.

    Path("ckpt").mkdir(exist_ok=True)
    best, best_epoch = -1.0, -1
    step = 0
    for epoch in range(args.epochs):
        running = 0.0
        for feats, keys, mask, targets, tlens in train_dl:
            feats, keys, mask = feats.to(device), keys.to(device), mask.to(device)
            with torch.autocast(device_type=device, dtype=torch.bfloat16,
                                enabled=(device == "cuda")):
                log_e, _, _ = model(feats, keys, mask)          # [B,32,65]
            log_e = log_e.float().permute(1, 0, 2)              # [T=32,B,65] for CTCLoss
            in_lens = torch.full((log_e.shape[1],), T_OUT, dtype=torch.long)
            loss = ctc(log_e, targets, in_lens, tlens)
            opt.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            opt.step()
            sched.step()
            running += loss.item()
            step += 1
        acc = greedy_accuracy(model, val_dl, device)
        print(f"epoch {epoch:3d}  ctc_loss {running / len(train_dl):.4f}  "
              f"val_greedy {acc * 100:.2f}%  lr {sched.get_last_lr()[0]:.2e}")
        torch.save({"model": model.state_dict(), "ch": args.ch, "epoch": epoch,
                    "val_greedy": acc}, "ckpt/last.pt")
        if acc > best:
            best, best_epoch = acc, epoch
            torch.save({"model": model.state_dict(), "ch": args.ch, "epoch": epoch,
                        "val_greedy": acc}, "ckpt/best.pt")
        elif epoch - best_epoch >= args.patience:
            print(f"early stop (best val_greedy {best * 100:.2f}% @ epoch {best_epoch})")
            break
    print(f"done. best val_greedy {best * 100:.2f}% -> ckpt/best.pt")


if __name__ == "__main__":
    main()
```

### Hyperparameters (starting point; iterate at G2)

| Knob | Value | Notes |
|---|---|---|
| Optimizer | AdamW, lr 3e-3, wd 0.01 | tiny model tolerates high lr |
| Schedule | 1 k-step linear warmup → cosine to 0 | |
| Batch | 256 | fits any GPU; loss is per-batch-mean CTC |
| Epochs | 80, early stop patience 15 on val greedy | ~35 k steps on the 110 k split |
| Precision | bf16 autocast, fp32 master + fp32 CTC loss | CTC loss in fp32 for stability |
| Grad clip | 1.0 | CTC can spike early |
| Val metric | greedy-CTC exact-word accuracy | cheap; beam eval (§7) at milestones only |

**What "good" looks like mid-training**: greedy-CTC val accuracy is the fast proxy.
FUTO's encoder-only floor measured **43.96 % greedy** on test-2400 while its trie-beam
top-1 was **79.25 %** (study §5a) — so do not panic at "only ~40 %" greedy; the beam +
lexicon recovers roughly +35 pts. Run the full beam eval (§7) whenever greedy plateaus.

---

## 7. Validation — `eval_beam.py` (the acceptance metric)

This reuses the **exact committed harness code** — `futo_viterbi_beam` from
`futo_decoder_ceiling.py` (the beam the Kotlin `CtcBeamDecoder` is golden-parity-tested
against) and the trie/lexicon loaders from `futo_decoder_eval.py` — so the number it
prints is directly comparable to every baseline in the decision doc and is exactly what
the Kotlin decoder will reproduce on-device.

```python
#!/usr/bin/env python3
"""Beam-eval a trained checkpoint (or exported ONNX) through the SAME harness used
for all committed FUTO-comparison numbers.

Usage:
  python eval_beam.py --ckpt ckpt/best.pt --test data/val_hwsfuto.jsonl
  python eval_beam.py --onnx ctc_swipe_encoder.onnx --test data/test_hwsfuto.jsonl
"""
import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from futo_decoder_eval import (featurize, greedy_ctc, len_stratum,
                               load_combined_vocab, load_layout, load_test,
                               rank_of, Tally)
from futo_decoder_ceiling import (ENC_BETA, ENC_BETA_PRUNE, ENC_GAMMA,
                                  ENC_GAMMA_PRUNE, ENC_LAMBDA, futo_viterbi_beam,
                                  slice_emissions)
from model import MAX_KEYS, CtcSwipeEncoder


class TorchEncoder:
    def __init__(self, ckpt_path: str) -> None:
        ck = torch.load(ckpt_path, map_location="cpu")
        self.model = CtcSwipeEncoder(ch=ck.get("ch", 96)).eval()
        self.model.load_state_dict(ck["model"])

    @torch.no_grad()
    def forward(self, feats: np.ndarray, keys: np.ndarray, mask: np.ndarray) -> np.ndarray:
        k = keys.shape[0]
        kp = np.zeros((MAX_KEYS, 2), np.float32); kp[:k] = keys
        mp = np.zeros((MAX_KEYS,), bool); mp[:k] = mask
        log_e, _, _ = self.model(torch.from_numpy(feats[None]),
                                 torch.from_numpy(kp[None]),
                                 torch.from_numpy(mp[None]))
        return log_e.numpy()[0]                          # [32, 65]


class OnnxEncoder:
    def __init__(self, onnx_path: str) -> None:
        import onnxruntime as ort
        self.sess = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])

    def forward(self, feats: np.ndarray, keys: np.ndarray, mask: np.ndarray) -> np.ndarray:
        k = keys.shape[0]
        kp = np.zeros((MAX_KEYS, 2), np.float32); kp[:k] = keys
        mp = np.zeros((MAX_KEYS,), bool); mp[:k] = mask
        out = self.sess.run(["log_emissions"],
                            {"features": feats[None], "layout_keys": kp[None],
                             "layout_mask": mp[None]})
        return out[0][0]                                 # [32, 65]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt", default="")
    ap.add_argument("--onnx", default="")
    ap.add_argument("--layout", default="en_qwerty.json")
    ap.add_argument("--vocab", default="data/futo_en_wordlist.combined")
    ap.add_argument("--test", default="data/val_hwsfuto.jsonl")
    ap.add_argument("--beam-width", type=int, default=100, dest="beam_width")
    ap.add_argument("--top-k", type=int, default=8, dest="top_k")
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()
    assert bool(args.ckpt) != bool(args.onnx), "pass exactly one of --ckpt / --onnx"

    letters, key_centers = load_layout(Path(args.layout))
    num_letters = len(letters)
    trie = load_combined_vocab(Path(args.vocab))
    print(f"trie: {trie.num_words} words")
    enc = TorchEncoder(args.ckpt) if args.ckpt else OnnxEncoder(args.onnx)
    rows = load_test(Path(args.test))
    if args.limit:
        rows = rows[: args.limit]
    mask = np.ones((num_letters,), bool)

    g_tal, b_tal = Tally(), Tally()
    strat = {"<=3": Tally(), "4+": Tally()}
    t0 = time.time()
    for i, (word, xs, ys, ts) in enumerate(rows):
        target = word.lower()
        feats = featurize(xs, ys, ts)
        full = enc.forward(feats, key_centers, mask)          # [32, 65]
        lp = slice_emissions(full, num_letters, MAX_KEYS)     # [32, 27], blank -> 26
        greedy = greedy_ctc(lp, letters, num_letters)
        beam = futo_viterbi_beam(lp, letters, num_letters, trie,
                                 args.beam_width, args.top_k,
                                 ENC_GAMMA, ENC_LAMBDA, ENC_BETA,
                                 ENC_GAMMA_PRUNE, ENC_BETA_PRUNE)
        words = [w for w, _ in beam]
        g_tal.add(0 if greedy == target else -1)
        r = rank_of(target, words)
        b_tal.add(r)
        strat[len_stratum(target)].add(r)
        if (i + 1) % 200 == 0:
            print(f"  [{i + 1}/{len(rows)}] beam {b_tal.row()}  "
                  f"({(i + 1) / (time.time() - t0):.1f} tr/s)")
    print("=" * 70)
    print(f"n={b_tal.n}  GREEDY t1 {g_tal.t1 / g_tal.n * 100:.2f}%")
    print(f"BEAM top-1/3/5   {b_tal.row()}")
    for s in ("<=3", "4+"):
        print(f"  {s:<4} n={strat[s].n:<5} {strat[s].row()}")
    print("=" * 70)
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

Notes:
- Scoring params are the committed **encoder-only** preset (`CtcScoringParams.encoderOnly`
  == `scoring.json` "encoder:honorable_sturgeon": gamma 0.4056, lambda 0.0176,
  beta 0.9866, gammaPrune 0.4234, betaPrune 1.0382). These were tuned for FUTO's
  emissions; after G2 passes, a small grid sweep of (gamma, beta, lambda) on **val**
  (never test) is a legitimate free win for *our* emissions — record the tuned preset,
  it becomes a new `CtcScoringParams` companion when the model ships.
- Beam width 100 matches the committed offline baselines; FUTO ships 300 at commit-time.
  Width is ≈neutral for top-1 (study §5a) — do not burn time sweeping it.
- The pure-Python beam runs ~2–10 traces/s; val-9,918 takes tens of minutes on a
  desktop. Use `--limit 2000` for quick iteration, full val for gate decisions.

---

## 8. ONNX export — `export_onnx.py`

**Fixed shapes, batch 1, opset 17.** The offline decoder speedup investigation
(`docs/eval/2026-08-06-offline-decoder-speedup.md`) found that fully-dynamic shapes are
what blocked graph optimization in our transformer decoder; the CTC engine makes exactly
one NN call per swipe with constant shapes, so there is zero benefit to dynamic axes and
a real cost — export everything static.

```python
#!/usr/bin/env python3
"""Export ckpt/best.pt -> ctc_swipe_encoder.onnx (fixed shapes, opset 17) + parity check."""
import numpy as np
import onnxruntime as ort
import torch

from model import MAX_KEYS, T_IN, CtcSwipeEncoder

OUT = "ctc_swipe_encoder.onnx"

ck = torch.load("ckpt/best.pt", map_location="cpu")
model = CtcSwipeEncoder(ch=ck.get("ch", 96)).eval()
model.load_state_dict(ck["model"])

feats = torch.rand(1, 2, T_IN)
keys = torch.rand(1, MAX_KEYS, 2)
mask = torch.zeros(1, MAX_KEYS, dtype=torch.bool)
mask[:, :26] = True

torch.onnx.export(
    model, (feats, keys, mask), OUT,
    input_names=["features", "layout_keys", "layout_mask"],
    output_names=["log_emissions", "coefficients", "lambda"],
    opset_version=17,
    dynamic_axes=None,          # fully static: [1,2,64] / [1,64,2] / [1,64]
    do_constant_folding=True,
)

# ── parity: ONNX vs torch on 100 random inputs ─────────────────────────────────
sess = ort.InferenceSession(OUT, providers=["CPUExecutionProvider"])
worst = 0.0
agree = 0
for _ in range(100):
    f = torch.rand(1, 2, T_IN)
    k = torch.rand(1, MAX_KEYS, 2)
    with torch.no_grad():
        ref, _, _ = model(f, k, mask)
    out = sess.run(["log_emissions"],
                   {"features": f.numpy(), "layout_keys": k.numpy(),
                    "layout_mask": mask.numpy()})[0]
    worst = max(worst, float(np.abs(out - ref.numpy()).max()))
    agree += int((out[0].argmax(-1) == ref.numpy()[0].argmax(-1)).all())
print(f"max |onnx - torch| = {worst:.2e}   argmax agreement {agree}/100")
assert worst < 1e-4 and agree == 100, "export parity FAILED"
print(f"exported {OUT}")
```

Acceptance for the export step itself:
1. `max |onnx − torch| < 1e-4` and 100/100 per-frame argmax agreement (above).
2. Re-run `eval_beam.py --onnx ctc_swipe_encoder.onnx --test data/val_hwsfuto.jsonl`
   and confirm top-1 matches the `--ckpt` run to within noise (±0.1 pt). This is the
   "encoder-argmax probe" pattern the decision doc's Phase 2 (plan O2) calls for.

Do **not** quantize for the first ship: fp32 is ~2 MB (smaller than either of our
current transformer ONNX files) and the model runs once per swipe. Int8 dynamic
quantization is a later, optional size play — re-run gate evals if applied.

### Placing the model in the app

```bash
# name is a proposal — align with whatever the G3 implementation PR chooses
cp ctc_swipe_encoder.onnx  <repo>/src/main/assets/models/ctc_swipe_encoder.onnx
```

The app side (Phase G3, **not** this guide's scope) then implements `CtcEmissionModel`
over `onnxruntime-android` (mirroring `SwipePredictorOrchestrator`'s session/XNNPACK
setup — `onnx_xnnpack_threads` pref already exists), feeding:
- `features`: `CtcFeaturizer.featurize(px, py, pt)` (already bit-identical to training),
- `layout_keys`/`layout_mask`: `CtcFeaturizer.buildPaddedLayout(layout)` (`PaddedLayout.keys`
  is the interleaved `[64*2]` array reshaped to `[1,64,2]`; the `BooleanArray` mask maps
  to an ORT BOOL tensor),
- output: flatten `log_emissions` `[1,32,65]` → `CtcEmissions.sliceFromHead(values, 32, 64, 26)`
  → `CtcBeamDecoder.decode(emissions, trie, params)`.

---

## 9. Golden traces for the Kotlin parity test — `make_golden.py`

The committed `CtcParityTest` validates the Kotlin beam/featurizer against fixtures
frozen from the Python harness (`src/test/resources/ctc/ctc_golden.json`; the generator lived in
a session-ephemeral `scratchpad/` and is GONE — the fixture itself is committed, and
`CtcParityTest` is the authority on its JSON shape). Once a real model exists, extend the same pattern with
**model-backed** golden cases so (a) the committed beam provably reproduces the
harness decode of *real* emissions and (b) the future Kotlin `CtcEmissionModel` ONNX
implementation can be asserted against frozen `features → emissions` pairs.

```python
#!/usr/bin/env python3
"""Freeze model-backed golden cases: synthetic paths -> features -> ONNX emissions ->
harness greedy+beam. Output shape matches ctc_golden.json 'beam' cases, plus the raw
features/emissions pair for a future CtcEmissionModel parity test."""
import json
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort

sys.path.insert(0, str(Path(__file__).resolve().parent))
from futo_decoder_eval import featurize, greedy_ctc, load_layout, LexTrie
from futo_decoder_ceiling import (ENC_BETA, ENC_BETA_PRUNE, ENC_GAMMA,
                                  ENC_GAMMA_PRUNE, ENC_LAMBDA, futo_viterbi_beam,
                                  slice_emissions)

LEXICON = [("cat", 150.0), ("car", 180.0), ("cart", 120.0), ("care", 140.0),
           ("the", 250.0), ("hello", 160.0), ("keyboard", 110.0)]
WORDS = ["cat", "the", "hello", "keyboard"]

letters, centers = load_layout(Path("en_qwerty.json"))
by_letter = {l: centers[i] for i, l in enumerate(letters)}
sess = ort.InferenceSession("ctc_swipe_encoder.onnx",
                            providers=["CPUExecutionProvider"])

def ideal_path(word: str, pts_per_seg: int = 12):
    """Deterministic straight-line path through the word's key centers, 60 Hz stamps."""
    cs = [by_letter[c] for c in word]
    xs, ys = [], []
    for a, b in zip(cs[:-1], cs[1:]):
        for j in range(pts_per_seg):
            f = j / pts_per_seg
            xs.append(float(a[0] + f * (b[0] - a[0])))
            ys.append(float(a[1] + f * (b[1] - a[1])))
    xs.append(float(cs[-1][0])); ys.append(float(cs[-1][1]))
    ts = [i * (1000.0 / 60.0) for i in range(len(xs))]
    return xs, ys, ts

trie = LexTrie()
for w, f in LEXICON:
    trie.insert(w, f)

cases = []
for word in WORDS:
    xs, ys, ts = ideal_path(word)
    feats = featurize(xs, ys, ts)                                 # [2,64]
    keys = np.zeros((64, 2), np.float32); keys[:26] = centers
    mask = np.zeros((64,), bool); mask[:26] = True
    full = sess.run(["log_emissions"],
                    {"features": feats[None], "layout_keys": keys[None],
                     "layout_mask": mask[None]})[0][0]            # [32,65]
    lp = slice_emissions(full, 26, 64)                            # [32,27]
    greedy = greedy_ctc(lp, letters, 26)
    topk = futo_viterbi_beam(lp, letters, 26, trie, 32, 4,
                             ENC_GAMMA, ENC_LAMBDA, ENC_BETA,
                             ENC_GAMMA_PRUNE, ENC_BETA_PRUNE)
    cases.append({
        "kind": "beam", "name": f"model_{word}",
        "alphabet": "".join(letters), "frames": 32, "numClasses": 27,
        "points": {"x": xs, "y": ys, "t": ts},
        "features": [float(v) for v in feats.reshape(-1)],        # [128] x-row then y-row
        "emissions": [[float(v) for v in row] for row in lp],
        "lexicon": [[w, f] for w, f in LEXICON],
        "params": {"gamma": ENC_GAMMA, "lambda": ENC_LAMBDA, "beta": ENC_BETA,
                   "alpha": 0.0, "gammaPrune": ENC_GAMMA_PRUNE,
                   "betaPrune": ENC_BETA_PRUNE, "beamWidth": 32, "topK": 4},
        "greedy": greedy,
        "topk": [[w, s] for w, s in topk],
    })
Path("ctc_model_golden.json").write_text(json.dumps({"cases": cases}, indent=1))
print(f"wrote ctc_model_golden.json ({len(cases)} cases)")
```

How it is consumed back in the repo (at G3 time, not now):
- The `"beam"`-shaped fields plug straight into the existing `CtcParityTest` fixture
  format (`src/test/resources/ctc/`), proving `CtcBeamDecoder` decodes the *real*
  model's emissions identically to the harness (top-k words exact, scores within 1e-4 —
  the committed tolerances).
- The stored `points → features` pairs re-verify `CtcFeaturizer` bit-identity, and the
  `features → emissions` pairs become the assertion target for the future ONNX-backed
  `CtcEmissionModel` (tolerance ~1e-4 abs, since onnxruntime-android may fuse
  differently than desktop ORT — argmax-per-frame must match exactly).

---

## 10. Acceptance gates (from the decision doc, §4)

Run every gate on **val-9,918** during iteration; touch **test-2,400 only once** per
milestone for the reportable number.

| Gate | Command | Bar | On miss |
|---|---|---|---|
| **G2 — training feasibility (the program go/no-go)** | `eval_beam.py --ckpt ckpt/best.pt --test data/val_hwsfuto.jsonl` (full, then confirm on test) | top-1 **within ~2 pt of the FUTO floor ≈ 77–79 %** (FUTO enc-only floor on test-2400: 79.25 t1 / 87.71 t3 / 89.58 t5, re-measured 79.29 post-apostrophe-fix — ⚠ note below; greedy anchor 43.96 %) | Iterate the recipe (ch/blocks, augmentation strength, lr, more data via §3b). A large miss means recipe, not idea — do not proceed to export. |
| **Export parity** | `export_onnx.py` then `eval_beam.py --onnx ...` | <1e-4 max-abs vs torch, 100 % argmax agreement, val top-1 within ±0.1 pt of ckpt run | Fix export (op decomposition, fp32 everywhere) before anything downstream. |
| **G3 — on-device latency** (app-side, post-guide) | commit-phase decode: 1 ONNX call + Kotlin beam 300 over the `en_enhanced`-derived trie | ≤ our neural engine's 100–300 ms/swipe (expected far under: one small NN call + pure-CPU beam) | Profile Kotlin beam; JNI beam only if it genuinely misses (spec open decision 4). |
| **G4 — refinement head (phase 2, §11)** | ceiling-style eval with refined emissions + `CtcScoringParams.encoderDecoder` params | **≥ +4 pt** top-1 over our own enc-only on the same split (FUTO's measured lever: +5.88 pt, pre-apostrophe-fix — see the ⚠ note below) | Ship enc-only as long-word complement behind a router instead of as replacement (decision §3b). |
| **Golden-trace check** | `make_golden.py` output vs the committed Kotlin decode path | top-k words exact, scores within 1e-4 (CtcParityTest tolerances) | Any mismatch = contract violation (class order, blank index, softmax) — fix before shipping. |

> **⚠ CORRECTED 2026-08-29 (second-pass verification): these bars are MEASURED-STABLE.**
> The 2026-08-28 revision of this note claimed the post-apostrophe-fix re-run "was never
> performed" — that was **false**. It was measured on 2026-08-06 (commit `3b94b2b2`,
> recorded at `docs/eval/2026-07-24-test2400-head2head.md:144-157`): overall floor
> **79.25 → 79.29**, ceiling **84.83 unchanged**. The apostrophe fix moved the contraction
> subset dramatically (42.9 → 67.9 floor / 53.6 → 85.7 ceiling) but ~29k a-z possessive
> forms entering the lexicon offset it — net +0.04 pt overall. **Clearing G2 means what it
> says**; the FUTO floor is a sound gate for from-scratch runs (per-script models
> included). The +5.88 pt G4 lever predates the fix but G4's own bar (≥ +4 pt over OUR
> enc-only on OUR split) is self-referential and unaffected.
>
> The shipped CTC encoder scored **89.31 t1** (93.79 t3 / 94.50 t5) on the same test-2400
> split — clear of both bars by a wide margin; it remains the better number to beat.

Context for the bars: at floor-equivalent quality (~79 %) the engine already beats our
shipped neural overall (74.62) and crushes it on 4+-char words (77.6 vs 67.0) but loses
short words (82.5 vs 89.5) — hence the router hedge; at ceiling-equivalent (~84–85, G4)
it is replacement-grade (decision doc §3).

---

## 11. Phase 2 — the refinement head (the measured +5.88 pt lever)

> The +5.88 pt figure is a pre-apostrophe-fix measurement (⚠ note in §10) — a real lever,
> but its magnitude is approximate. G4's own bar (≥ +4 pt over OUR enc-only on OUR split)
> is self-referential and unaffected.

Once the base model clears G2, train a `magic_macaw`-analogue: a tiny per-frame head
(FUTO's is literally layer-norm + linear + log_softmax, study §1b) that consumes
`concat(sliced_emissions[27] | coefficients[64] | lambda[1]) = [T'=32, 92]` per frame
and outputs refined `log_probs [32, 27]` that **replace** the emissions before the beam.
The ceiling harness already implements the exact consumption
(`futo_decoder_ceiling.py::build_decoder_input` + `Decoder`), and
`CtcScoringParams.encoderDecoder` carries the matching scoring preset.

Recipe sketch:
1. Freeze the trained base encoder. For each training sample, run it to get
   `(sliced_emissions, coefficients, lambda)` — from OUR model only (never FUTO's; §0).
2. Head: `LayerNorm(92) → Linear(92, h≈128) → GELU → Linear(h, 27) → log_softmax`
   (~15–30 K params; FUTO's whole head is 1.25 MB fp32 including whatever width they use).
3. Train with the same CTC loss (blank now at index 26 of the 27-class refined head —
   this head operates on the *sliced* view), same schedule, ~20 epochs; optionally then
   unfreeze and fine-tune end-to-end at 10× lower lr.
4. Because the head sees `coefficients` (the spatial field) alongside the collapsed
   emissions, it can sharpen per-frame decisions using spatial context the softmax
   discarded — this is where FUTO's +5.88 pt (greedy 43.96 % → 69.12 %) came from.
5. Export as a **second ONNX** with fixed input `[1, 32, 92]` → `[1, 32, 27]`, or fold
   both into one graph with dual outputs. Note the head is layout-fingerprint-gated in
   FUTO (en_qwerty only); ours is trained with the same slot-permutation augmentation,
   so evaluate whether it generalizes — if not, gate it to canonical-QWERTY like FUTO
   does (study §4d) and fall back to enc-only elsewhere.
6. Evaluate with `futo_viterbi_beam` + the `encoderDecoder` preset (gamma 0.5949,
   lambda 0.0134, beta 0.7271, gammaPrune 0.1902, betaPrune 1.2727). Gate G4: ≥ +4 pt.

A third optional lever, independent of this guide: the `hungry_jellyfish`-style
context-LM reranker (`alpha·lm` over top-200, keep CTC top-1) — modular, layers on
`CtcCandidate`, and has its own recommendation doc
(`docs/history/audits/2026-08-06-context-lm-nextword-rec.md`).

---

## 12. Run-order checklist (condensed)

```bash
# 0. one-time setup (§2): venv, pip installs, copy scripts + data, en_qwerty.json
python prepare_data.py                     # §4  cache featurized splits
python train.py                            # §6  ~evening on 110k; watch val_greedy
python eval_beam.py --ckpt ckpt/best.pt --test data/val_hwsfuto.jsonl   # §7  G2 gate
#   -> if < ~77%: iterate (aug strength, ch=128, more epochs, HF-corpus scale-up §3b)
python export_onnx.py                      # §8  export + torch/ONNX parity
python eval_beam.py --onnx ctc_swipe_encoder.onnx --test data/val_hwsfuto.jsonl
python eval_beam.py --onnx ctc_swipe_encoder.onnx --test data/test_hwsfuto.jsonl  # report
python make_golden.py                      # §9  golden traces for the Kotlin side
# 11. phase 2 (§11): refinement head, G4 gate, re-export
# then: copy .onnx into src/main/assets/models/, hand golden JSON to the G3 app work
```

## Appendix A — canonical en_qwerty layout (embed as `en_qwerty.json`)

Alphabet/emission-column order is **alphabetical** `a..z`; cy rows are
{0.1667, 0.5, 0.8333}; source: FUTO `swipe-library/models/layouts/en_qwerty.json`
(the file itself is part of the GPL-3.0 library; the numbers below are the canonical
frame the MIT dataset is normalized to).

```json
{
  "name": "QWERTY",
  "letters": "abcdefghijklmnopqrstuvwxyz",
  "keys": [
    {"letter": "a", "cx": 0.10, "cy": 0.5},    {"letter": "b", "cx": 0.60, "cy": 0.8333},
    {"letter": "c", "cx": 0.40, "cy": 0.8333}, {"letter": "d", "cx": 0.30, "cy": 0.5},
    {"letter": "e", "cx": 0.25, "cy": 0.1667}, {"letter": "f", "cx": 0.40, "cy": 0.5},
    {"letter": "g", "cx": 0.50, "cy": 0.5},    {"letter": "h", "cx": 0.60, "cy": 0.5},
    {"letter": "i", "cx": 0.75, "cy": 0.1667}, {"letter": "j", "cx": 0.70, "cy": 0.5},
    {"letter": "k", "cx": 0.80, "cy": 0.5},    {"letter": "l", "cx": 0.90, "cy": 0.5},
    {"letter": "m", "cx": 0.80, "cy": 0.8333}, {"letter": "n", "cx": 0.70, "cy": 0.8333},
    {"letter": "o", "cx": 0.85, "cy": 0.1667}, {"letter": "p", "cx": 0.95, "cy": 0.1667},
    {"letter": "q", "cx": 0.05, "cy": 0.1667}, {"letter": "r", "cx": 0.35, "cy": 0.1667},
    {"letter": "s", "cx": 0.20, "cy": 0.5},    {"letter": "t", "cx": 0.45, "cy": 0.1667},
    {"letter": "u", "cx": 0.65, "cy": 0.1667}, {"letter": "v", "cx": 0.50, "cy": 0.8333},
    {"letter": "w", "cx": 0.15, "cy": 0.1667}, {"letter": "x", "cx": 0.30, "cy": 0.8333},
    {"letter": "y", "cx": 0.55, "cy": 0.1667}, {"letter": "z", "cx": 0.20, "cy": 0.8333}
  ]
}
```

(`futo_decoder_eval.load_layout` reads only `letters` + `keys[].letter/cx/cy`; the
original file also carries `rx`/`ry` half-extents of 0.05/0.1667 per key, unused here.)

## Appendix B — baseline numbers to beat (same 2,400-row test split)

| Engine | overall t1 | ≤3-char t1 | 4+-char t1 | greedy |
|---|---|---|---|---|
| **CleverKeys CTC (SHIPPED) — the real bar** | **89.31** | 93.70 | 87.05 | — |
| FUTO ceiling (enc + refine) ⚠ stale | 84.83 | 89.57 | 82.40 | 69.12 % |
| FUTO floor (enc only) — **G2 reference** ⚠ stale | 79.25 | 82.45 | 77.60 | 43.96 % |
| our removed neural engine (beam 6) | 74.62 | 89.45 | 67.00 | — |
| our geometric SHARK2 | 67.50 | 69.33 | 66.56 | — |

⚠ **pre-apostrophe-fix** (2026-08-06, `scripts/futo_decoder_eval.py:236-244`) — but the
post-fix re-run WAS measured (`3b94b2b2`; overall floor 79.25 → 79.29, ceiling unchanged;
head2head `:144-157`), so these rows are stable exact bars: the contraction-subset shift is
offset by the possessive forms the fix admits (§10 ⚠ note, corrected 2026-08-29). The
shipped-CTC row cleared them regardless and is the number a from-scratch replacement has to
beat.

Source: `docs/eval/2026-07-24-test2400-head2head.md` via the decision doc §3; shipped-CTC
row from the same file's 2026-08-08 addendum (`:185`) / `docs/specs/ctc-swipe-engine.md:402`.

---
*Documentation only. When the trained model lands, the app-side work is Phase G3/G4 of
the decision doc plus the "Engine-selector integration" section of
`docs/specs/ctc-swipe-engine.md` — none of it is authorized or performed by this guide.*
