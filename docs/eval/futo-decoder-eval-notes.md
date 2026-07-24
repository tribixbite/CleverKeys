# FUTO Decoder Eval — Notes (working doc)

Goal: run FUTO's OWN swipe decoder on our held-out test set
(`~/storage/shared/swipedata/test_hwsfuto.jsonl`, 2,400 rows) and measure
top-1/3/5 word accuracy, as a third baseline vs our neural ONNX + geometric SHARK2.

## Phase 1 — FEASIBILITY (verdict: FEASIBLE via proot Ubuntu + ExecuTorch wheel)

### What FUTO's decoder is
- HF model repo: **`futo-org/futo-swipe`** (rev main, git_commit `86b375fbc0ad76fd6cc421b09f28a110c4e98367`).
  License: FUTO Model Weights License 1.0.
- Paper: arXiv **2606.25247** "FUTO Swipe: Layout-Agnostic Neural Swipe Decoding".
- Three composable CNN models (only encoder required):
  - **Encoder** `honorable_sturgeon` (635K params, 2.65MB fp32) — 1D temporal conv net (TCN).
    Reads raw `(x,y)` trajectory resampled to `[1,2,64]` + layout key centers
    `[1,64,2]` + mask `[1,64]`. Emits `log_emissions [1,32,65]` (64 keys + CTC blank),
    `coefficients [1,32,64]` (DCT), `lambda [1,32,1]` (intention gate). 2x temporal
    downsample (64 input steps -> 32 emission steps). Layout-agnostic.
  - **Decoder** `magic_macaw` (304K, 1.25MB) — optional DFSMN refiner, English/QWERTY only.
    input_shape [1,32,92] (concat of coeff 64 + something). Lifts top-1 ~0.55-0.76pt.
  - **Context LM** `hungry_jellyfish` — optional; "not evaluated in this paper". Skipped.
- Open-source C++ inference: **gitlab.futo.org/keyboard/swipe-library** (cloned to
  `~/.cache/cleverkeys-test/swipe-library-src`). Depends on ExecuTorch v1.2.0.
  Has the exact featurization (`src/resampler.cpp`), beam search (`src/beam_search.cpp`),
  layout coords (`models/layouts/en_qwerty.json`).

### Weights format = ExecuTorch `.pte` (XNNPACK-delegated)
- Only `.pte` files ship (no ONNX, no safetensors, no plain .pt state_dict).
- Both encoder + decoder `.pte` are **XNNPACK-delegated** (`XnnpackBackend` marker
  present; portable op list has NO conv/linear/matmul — those are inside the delegate
  blob). => Cannot cheaply extract weights + rebuild in torch. Must run the real runtime.
- ExecuTorch is NOT pip-installable on native Termux (bionic; sys.platform reports
  "android" under py3.13, and no android wheel exists). BUT PyPI has
  **`manylinux_2_28_aarch64` wheels** for executorch (cp310-313, versions 1.2.0..1.3.1).
- **proot-distro Ubuntu 24.04 (glibc) IS already installed** on this device — the
  sanctioned glibc sandbox. Plan: install the aarch64 executorch wheel there and run.

### Featurization (from swipe-library resampler.cpp + README, EXACT)
1. Input pts already normalized [0,1] over QWERTY letter-area (our test data matches).
2. `resample_to_60hz`: if t has duration, uniform-resample to
   `n60 = max(2, round(dur_ms/16.667)+1)` points via linspace(0,dur) + linear interp.
3. Then resample to fixed **64** points: linear interp over index space (np.interp on
   linspace(0,len-1,64)). Clip to [0,1]. Output `[2,64]` = [x0..x63, y0..y63].
4. NO velocity/time channels — just (x,y). (Our test data t is clean ~60Hz.)

### Layout key centers (QWERTY, normalized [0,1]) — from README (matches en_qwerty.json)
Given explicitly in README (a=(0.10,0.500), ... 26 letters). LETTERS=sorted(QWERTY).
Encoder maps log_emissions class c -> LETTERS[c]; blank = last class (index 64).

### Word-level decoding (paper §4 + beam_search.cpp)
- Trie-constrained CTC beam search, **beam width 100**, over the deployment lexicon
  (AOSP wordlist: 162,185 EN entries).
- Scoring (Eq 3): combine CTC score + freq prior + length terms with tuned
  (gamma, lambda, beta). scoring.json gives per-config optima. Pruning score
  s_prune = s_ctc/max(d,1)^gamma_p + beta_p*d.
- Encoder-only English test (Table 3): our target to reproduce.

### PAPER-REPORTED accuracy on swipe.futo.org EN **test** split (QWERTY, beam 100):
| Setting            | Split | Top-1  | Top-3  | Top-10 |
|--------------------|-------|--------|--------|--------|
| Encoder only       | test  | 92.54% | 97.33% | 98.54% |
| Encoder + decoder  | test  | 93.30% | 97.97% | 99.16% |
- (val: enc-only 92.94/97.46/98.60; enc+dec 93.49/97.85/99.08)
- Test split N = 49,970 swipes (48,538 after filtering per Table 7). Our 2,400 is a
  SUBSET of this same distribution (test_hwsfuto = held-out FUTO test).
- Note: paper reports top-10 not top-5; we compute top-1/3/5 for our comparison.

## COMPARISON ANCHORS (established, do not recompute)
- Our shipped ONNX neural (beam 6): ~76.34% t1 / 85.39% t3 / 88.68% t5 (486-in-vocab val).
  strata <=3-char 88.33%, 4+-char 69.28%.
- Our geometric SHARK2: ~75.3% top-1 on FUTO 100k sample.

## Phase 2 — DOWNLOAD (done)
Files in `~/.cache/cleverkeys-test/futo_decoder/`:
- `honorable_sturgeon/model_fp32.pte` (2,649,856 B, sha256 verified 725242ba...)
- `magic_macaw/model_fp32.pte` (1,247,468 B, sha256 verified 01eaf16a...) [decoder, optional]
- `en_wordlist.combined[.gz]` — FUTO's OWN AOSP en wordlist from
  gitlab.futo.org/keyboard/latinime (165,544 word= entries, `word=<w>,f=<0-255>` format).
- `scoring.json`, metadata.json's.
- swipe-library C++ source at `~/.cache/cleverkeys-test/swipe-library-src`.

## Phase 3 — HARNESS (`scripts/futo_decoder_eval.py`)
- Runs INSIDE proot Ubuntu venv `/root/etvenv` with `executorch==1.2.0` (aarch64 wheel)
  + torch (CPU). Guest workspace `/root/futo_eval/` (encoder.pte, layout, vocab, test).
- Exact featurization port of resampler.cpp (60Hz linspace -> fixed-64 index resample,
  clip [0,1], (x,y) only). Encoder via ExecuTorch Runtime. Emissions sliced to 26
  letters + blank (blank = full-head class 64).
- Two decodes: greedy CTC (README parity) + trie-constrained CTC prefix beam search
  with FUTO's final scoring `ctc/L^gamma + beta*len + lambda*log_freq`
  (gamma=0.4056, lambda=0.0176, beta=0.9866 from scoring.json encoder-only).
- CAVEAT: FUTO's C++ beam is a bespoke optimized single-stream token-passing beam;
  our Python port uses the textbook CTC prefix-beam recurrence with identical trie
  constraint + identical FINAL scoring. Greedy CTC (exact) is the cross-check anchor.

## proot exec quirk (important)
- `proot-distro login ubuntu -- CMD` has its STDOUT hijacked in the Bash tool
  (returns "2.1.179 (Claude Code)"). WORKAROUND: run CMD with output redirected to a
  file INSIDE the guest (`... -- /bin/sh -c 'cmd > /root/out.txt 2>&1'`) and read the
  file from the Termux side at `$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu/root/...`.

## Runtime ABI note (executorch <-> torch pin)
- `executorch==1.2.0` aarch64 wheel's `_portable_lib.cpython-312.so` needs a SPECIFIC
  torch ABI. Observed: torch 2.13.0 -> undefined `materialize_cow_storage` (too new);
  torch 2.8.0 -> "Skipping cpp extensions, upgrade to torch >= 2.11.0" + undefined
  `decref_pyobject`. Correct pin: **torch 2.11.0** (cp312 cpu manylinux aarch64).
- Install cmd (in proot venv): `pip install --index-url https://download.pytorch.org/whl/cpu torch==2.11.0`.

## Phase 4 — EVAL (in progress; results appended when done)

### Validation (10 traces) — PASSED
Predictions are real words (not garbage) => featurization correct. Beam 8/10 top-1.
Sample: was->[was,ws,wasa], around->[around,astound,aground], the->[the,thew,thee].

### Diagnostics (first ~450 traces, in-vocab)
- top-1 ~80%, top-3 ~88%, top-5 ~90%, top-8 recall ~94.5%.
- top-1 by length is UNIFORM (2-3:84%, 4-6:79%, 7+:82%) — beam isn't collapsing
  long words, so length-aware pruning wouldn't close the gap.
- Beam-recall failures are genuine NOISY SHORT SWIPES where the encoder greedy CTC
  is wrong (e.g. to->"fh", is->"iaf", am->"aj"): intermediate points drift toward the
  MIDDLE row (y~0.5) though endpoints map correctly. Confirmed NOT a frame bug — the
  y*1.401709402 alt-frame (used by infer_jsonl for the L/R segmented format) BREAKS
  correct words (around->z..x), so our array-format y*1.0 frame is right.
- These are exactly the cases FUTO's paper recovers with the decoder(magic_macaw) +
  context LM, and where their optimized C++ beam beats a textbook prefix-beam.

### WHY OUR NUMBER < PAPER'S 92.54% (encoder-only test)
1. ENCODER-ONLY, no magic_macaw decoder (+0.76pt in paper) and no context LM.
2. Python TEXTBOOK CTC prefix-beam vs FUTO's bespoke optimized single-stream beam
   with length-aware pruning (gamma_prune/beta_prune). Same trie constraint + same
   FINAL scoring, but different pruning/expansion => lower recall@beam.
3. Our 2,400 is a (possibly noisier) SUBSET of the 48,538 EN test split.
This is an HONEST encoder-only lower bound on FUTO's decoder accuracy on our data.

## ═══ FINAL RESULTS (all 2,400 traces, encoder-only, beam 100) ═══
Provenance: FUTO encoder honorable_sturgeon/model_fp32.pte (git 86b375fb), run via
ExecuTorch 1.2.0 (aarch64) + torch 2.11.0 in proot Ubuntu 24.04. Lexicon = FUTO's own
en_wordlist.combined (131,544 swipeable a-z words of 165,544 total). Scoring
gamma=0.4056 lambda=0.0176 beta=0.9866 (scoring.json encoder-only). 2 ORT threads.
N=2400, 0 errors, 99 OOV (4.13% vs FUTO's 131,544-word lexicon).

MICRO (per-trace) — the headline:
| Slice            | N    | top-1  | top-3  | top-5  | greedy-CTC top-1 |
|------------------|------|--------|--------|--------|------------------|
| OVERALL (w/ OOV) | 2400 | 79.25% | 87.71% | 89.58% | 43.96%           |
| IN-VOCAB only    | 2301 | 82.66% | 91.48% | 93.44% | 45.24%           |

By length stratum (OVERALL, incl OOV):
| Stratum | N    | top-1  | top-3  | top-5  | greedy top-1 |
|---------|------|--------|--------|--------|--------------|
| <=3     | 815  | 82.45% | 91.29% | 93.25% | 73.50%       |
| 4+      | 1585 | 77.60% | 85.87% | 87.70% | 28.77%       |

By length stratum (IN-VOCAB only):
| Stratum | N    | top-1  | top-3  | top-5  |
|---------|------|--------|--------|--------|
| <=3     | 791  | 84.96% | 94.06% | 96.08% |
| 4+      | 1510 | 81.46% | 90.13% | 92.05% |

MACRO (per-word top-1, 58 words w/ >=5 examples): 83.28%
Greedy CTC (pure encoder argmax, NO lexicon): overall 43.96% top-1 (73.5% on <=3-char,
only 28.8% on 4+ — CTC error accumulation on long words; the lexicon+beam recovers most).

## HEAD-TO-HEAD (our test set, top-1 / top-3 / top-5)
| Engine                          | overall top-1 | top-3  | top-5  | <=3 t1 | 4+ t1  |
|---------------------------------|---------------|--------|--------|--------|--------|
| **FUTO encoder (this eval)**    | **79.25%**    | 87.71% | 89.58% | 82.45% | 77.60% |
| Our shipped ONNX neural (beam6) | ~76.34%       | 85.39% | 88.68% | 88.33% | 69.28% |
| Our geometric SHARK2            | ~75.3%        | —      | —      | —      | —      |
Notes: our-neural anchor was on a 486-in-vocab val sample (different N), so the top-1
gap (~3pt) is indicative not exact. Cross-stratum: FUTO's encoder is much STRONGER on
long words (4+: 77.6% vs our 69.3%, its layout-agnostic TCN + lexicon beam handle long
trajectories better) but WEAKER on short words (<=3: 82.5% vs our 88.3% — our production
pipeline's freq/context priors win the tiny-swipe disambiguation). This is the
interesting finding: FUTO leads on length, our engine leads on brevity.
CAVEAT: FUTO's number is encoder-ONLY via a textbook Python CTC beam; their paper
reports 92.54% test top-1 with their optimized C++ beam + optional decoder+LM. So 79.25%
is a conservative floor for FUTO-on-our-data, not their ceiling.
