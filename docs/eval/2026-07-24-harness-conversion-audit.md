# Neural-eval harness input-conversion audit — 2026-07-24

Audit of the input-conversion layers of the neural-eval harnesses for four
suspected defect classes: (A) timestamps, (B) x/y positions, (C) keyboard grid,
(D) padding/mask semantics. Read-only audit; no harness scripts were modified.

**Scope of harnesses**
- **FUTO head-to-head** — `scripts/fetch_futo_train100k.mjs` /
  `scripts/sample_futo_train100k_parquet.py` (build the cache
  `~/.cache/cleverkeys-test/futo_train100k.jsonl.gz`) →
  `tools/test_cli_predict.py --corpus … --frame-remap futo [--production]` →
  `scripts/futo100k_metrics.py`.
- **swipedata** — `scripts/convert_swipedata_futo.mjs`
  (`~/storage/shared/swipedata/{val,test,train}_hwsfuto.jsonl` →
  `swipedata_eval20k.jsonl.gz`) → `scripts/run_swipedata_20k.sh` which calls the
  **same** `tools/test_cli_predict.py --corpus --frame-remap futo --production`
  → `scripts/swipedata_metrics.py` / `swipedata_ab_compare.py`.

Because both harnesses funnel their converted caches through **the same
`test_cli_predict.py` featurizer with `--frame-remap futo`**, defects B/C/D in
that featurizer apply to BOTH harnesses identically. Only the dataset-side
converters (A) differ.

---

## Authoritative featurizer contract (the reference both harnesses must match)

Android production path
(`SwipeTrajectoryProcessor.kt` → `TrajectoryFeatureCalculator.kt` →
`KeyboardGrid.kt` → `TensorFactory.kt` / `EncoderWrapper.kt`) and the
post-`a22b76ad` web demo (`web_demo/demo/index.html`, the commit that brought the
demo to production parity):

- **Coordinate frame** — position `x` = fraction of full keyboard width `[0,1]`;
  position `y` = fraction of full keyboard **height** `[0,1]`. Web demo:
  `prepareSwipeFeatures` (index.html:1594-1596) does `point.x/360`, `point.y/215`
  where the 360×215 space is itself `(rawX/kbWidth)*360`, `(rawY/kbHeight)*215`
  (getNormalizedCoords, index.html:1122-1123) — the 360 and 215 cancel, so both
  x and y are the raw `[0,1]` key-area fractions. **There is NO `/280` band.**
- **Nearest keys** — `KeyboardGrid.getNearestKeyToken(nx,ny)` on the SAME
  normalized `[0,1]` coords, squared-Euclidean nearest of the fixed grid:
  rows `qwertyuiop`/`asdfghjkl`/`zxcvbnm`, X offsets `0.0/0.05/0.15`,
  `KEY_WIDTH=0.1`, `ROW_HEIGHT=1/3`. Key centers therefore at
  `cy = 1/6, 1/2, 5/6` (0.1667/0.5/0.8333) and `cx = off + i·0.1 + 0.05`.
  The web demo ports this verbatim (a22b76ad, index.html:544-570). The **FUTO
  official layout `src/test/resources/layouts/futo_qwerty.json` matches this grid
  to ≤0.0005** in normalized space (verified: q=(0.05,0.1667), a=(0.10,0.5),
  z=(0.20,0.8333)).
- **Velocities/accel** (`TrajectoryFeatureCalculator.kt:57-128`) — `dt[i] =
  ts[i]-ts[i-1]` in **ms** (kept as ms, `max(dt,1e-6)`); `v = Δpos/dt`,
  `a = Δv/dt`, both clipped `[-10,10]` AFTER both computed; `v[0]=a[0]=0`,
  `a[1]=v[1]/dt[1]` (accel starts at i=1, not i=2).
- **Padding/mask (D)** — pad/truncate to `MAX_SEQUENCE_LENGTH=250`.
  `actual_length` int32 scalar = **true unpadded (post-resample) point count**
  (`SwipeTrajectoryProcessor.kt:293`, `EncoderWrapper.kt:129`). Padded trajectory
  positions = **zeros** `[0,0,0,0,0,0]` (`TensorFactory.kt:58-66`); padded
  nearest_keys = **PAD token 0** (`TensorFactory.kt:93-95`). Decoder
  `actual_src_length` = same value. Traces >250 points are **RESAMPLED**
  (DISCARD mode, preserves start+end) before feature calc
  (`SwipeTrajectoryProcessor.kt:172-209`), NOT head-truncated. There is no
  boolean src_mask in the shipped models (encoder input is `actual_length`).

---

## VERDICT MATRIX

Legend: ✅ correct · ❌ defective · ⚠️ minor/edge · ℹ️ cannot-determine.

| Harness | A timestamps | B x/y frame | C grid | D padding/mask |
|---|---|---|---|---|
| **FUTO head-to-head** | ✅ correct | ❌ defective (y-band) | ❌ defective (aspect) | ⚠️ truncate-vs-resample |
| **swipedata** | ✅ correct | ❌ defective (inherited) | ❌ defective (inherited) | ⚠️ truncate-vs-resample |

---

## (A) TIMESTAMPS — ✅ CORRECT (both harnesses)

**FUTO** raw HF rows carry per-point `t` as monotonically-increasing cumulative
ms. Verified from `futo_train100k.jsonl.gz` (3 rows):
- `broad`: t first5 `[0,13,29,46,63]`, deltas `[13,16,17,17,16]` (min 12, max 18).
- `chapter`: t first5 `[0,2,14,25,36]`, deltas ~11ms.
- All monotonic-increasing, start at 0.

These are **absolute-relative (accumulated)**, NOT per-point deltas. The fetch
scripts store `max(0, round(t − t0))` (`fetch_futo_train100k.mjs` processRows;
`sample_futo_train100k_parquet.py`) → cumulative-from-zero preserved. The
featurizer consumes them correctly: `--velocity` path does `dt = ts[i]-ts[i-1]`
(`test_cli_predict.py:136`), recovering true per-step deltas. No accumulation
mismatch with the in-repo `SwipeMLData.tDeltaMs` trap — that trap is for OUR
collector; these datasets are already cumulative.

**swipedata** raw `val_hwsfuto.jsonl`: first-point t=0 for **all** traces,
per-point deltas median 16ms, **0 negative deltas** (fully monotonic).
`convert_swipedata_futo.mjs` stores `(p.t||0) − t0` (cumulative). Verified in the
consumed cache `swipedata_eval20k.jsonl.gz`: `be` → t `[0,46,63,80,97]…329`
monotonic. Correct.

Default harness mode is **position-only** (velocity/accel zeroed) — so timestamps
are unused by default anyway. When `--velocity` is enabled the conversion is
correct at the t level (but see B for the y-scale contamination that also hits
velocity-y).

---

## (B) X/Y POSITIONS — ❌ DEFECTIVE (both harnesses)

Datasets provide normalized `[0,1]` coords over the FUTO **letter-area** canvas
(no bottom/space row) — matching the model's 3-row grid. Verified ranges:
- FUTO: x `[0.09,0.98]` typical, y p5=0.113 / median=0.384 / p95=0.859 (overshoot
  to −0.52…1.35 at extremes).
- swipedata: x `[0.00,1.01]`, y `[−0.33,1.26]`; only 0.45% of points fall outside
  `[0,1]` (over-swipe). Sources 50% `hws` + 50% `futo`.

**The defect:** `--frame-remap futo` (`load_corpus_cache`,
`test_cli_predict.py:754-756`) maps `x = 360·nx`, `y = 4.5 + 177·ny`, then
`extract_features` (line 149-150) normalizes `x_norm = x/360 = nx` (✅) but
`y_norm = y/280 = (4.5 + 177·ny)/280` (❌). This is a made-up "squash band" that
does NOT exist in the authoritative pipeline (web demo uses the raw `[0,1]`
height fraction `ny`). It **compresses and shifts position-y downward**:

| row | authoritative y | harness y = (4.5+177·ny)/280 | Δ |
|---|---|---|---|
| top (ny≈0.167) | 0.167 | 0.122 | −0.045 |
| mid (ny≈0.5) | 0.500 | 0.332 | −0.168 |
| bottom (ny≈0.833) | 0.833 | 0.543 | −0.290 |
| ny=1.0 | 1.000 | 0.648 | −0.352 |

The whole keyboard collapses into the upper ~65% of the frame, with the bottom
row landing where the model expects the middle. When `--velocity` is on, the same
`/280` also mis-scales velocity-y (`vys[i] = Δy/NORM_Y/dt` uses NORM_Y=280 so
`vy = 0.632·(Δny)/dt`), so the distortion propagates into the velocity features
too.

The `/280` is defended in `GeoLocalCorpusReplayTest.kt:53-55` as "the model-input
squash band the encoder was trained on," but that claim is **contradicted by the
post-a22b76ad web demo** (the production-parity reference), which uses the raw
`ny` height fraction with row centers at 0.167/0.5/0.833. Empirical test (below)
confirms the raw `[0,1]` frame beats `/280`.

**Recommended patch** (fixes B and C together — see C for the grid half):
`tools/test_cli_predict.py`, `extract_features` (lines 148-152) and
`load_corpus_cache` frame_remap. Feed position + nearest-key from the SAME
normalized `[0,1]` coords the data already provides. Concretely, prefer a new
`--frame-remap norm01` (or make `futo` behave like it) that passes `nx, ny`
straight through with NO px reconstruction and NO `/280`:

```python
# load_corpus_cache — futo/swipedata branch  (before → after)
# BEFORE (lines 754-756):
if frame_remap == 'futo':
    xs.append(nx * 360.0)
    ys.append(4.5 + ny * 177.0)
# AFTER: keep normalized [0,1] directly; downstream must consume as [0,1].
if frame_remap in ('futo', 'norm01'):
    xs.append(nx)          # already [0,1] width fraction
    ys.append(ny)          # already [0,1] height fraction (NO 4.5/177 squash)
```

```python
# extract_features (lines 148-152) — when coords are already [0,1], do NOT re-normalize:
# BEFORE:
x_norm = xs[i] / NORM_X
y_norm = ys[i] / NORM_Y
...
nearest_keys.append(get_nearest_key(xs[i], ys[i]))     # pixel grid
# AFTER (normalized-coords mode):
x_norm = xs[i]                                          # already [0,1]
y_norm = ys[i]                                          # already [0,1], full-height fraction
...
nearest_keys.append(get_nearest_key_norm(xs[i], ys[i]))  # normalized KeyboardGrid (see C)
```

(If a single code path must serve both the legacy raw-pixel corpus and the
normalized caches, gate on `frame_remap`/a new flag so the pixel-corpus path is
untouched.)

---

## (C) KEYBOARD GRID — ❌ DEFECTIVE (both harnesses)

The harness `get_nearest_key` (`test_cli_predict.py:102-111`) uses a **pixel
centroid grid** `QWERTY_KEYS` (q=(18,34), a=(36,93), z=(72,152), row pitch 59px,
key pitch 36px) applied to the reconstructed pixels, with **isotropic** squared
distance. The authoritative grid is the normalized `KeyboardGrid`
(cy=1/6,1/2,5/6; row pitch 1/3, key pitch 0.1).

X geometry matches (harness `px/360` == authoritative `cx` for every key). But
the **vertical:horizontal pitch ratio is wrong**:
- authoritative: `(1/3)/0.1 = 3.333`
- harness pixel:  `59/36 = 1.639`

Because nearest-key uses isotropic Euclidean distance, the harness **under-weights
vertical (row) separation by ~2×**, so points between rows snap to the wrong-row
horizontal neighbor. Measured on 2000 FUTO traces (harness grid vs authoritative
grid, both on the same coords):
- per-point key mismatch 872/149996 = **0.6%**
- **16.2% of traces produce a different dedup key-sequence**, e.g.
  `broad`: harness `…uioiuytres…` vs auth `…uioiuytrds…`;
  `quantum`: `…uytrds…` vs `…uytfds…`;
  `for`: `fghjhygtr` vs `fghjhgtr`.
These wrong `nearest_keys` tokens go straight into the encoder, biasing decoding
toward neighbor-of-target words — exactly the flagged grid-offset signature
(e.g. sanity cache `simply → stimplist`, a spurious inserted `t`).

**Recommended patch** — replace the pixel grid with the normalized
`KeyboardGrid` port (a direct transcription of `KeyboardGrid.kt`, already present
in the web demo as `KEYBOARD_GRID`):

```python
# NEW near the top of test_cli_predict.py, alongside QWERTY_KEYS:
_ROWS = [('qwertyuiop', 0.0), ('asdfghjkl', 0.05), ('zxcvbnm', 0.15)]
_KEY_W, _ROW_H = 0.1, 1.0 / 3.0
_GRID_NORM = {}
for _r, (_keys, _off) in enumerate(_ROWS):
    for _i, _c in enumerate(_keys):
        _GRID_NORM[_c] = (_off + _i * _KEY_W + _KEY_W / 2, _r * _ROW_H + _ROW_H / 2)

def get_nearest_key_norm(nx, ny):
    """KeyboardGrid.getNearestKeyToken — normalized [0,1] coords (matches Android)."""
    x = min(1.0, max(0.0, nx)); y = min(1.0, max(0.0, ny))
    best, bd = 'a', float('inf')
    for c, (cx, cy) in _GRID_NORM.items():
        d = (x - cx) ** 2 + (y - cy) ** 2
        if d < bd: bd, best = d, c
    return CHAR_TO_KEY_IDX.get(best, 1)
```
and call `get_nearest_key_norm(nx, ny)` in the normalized-coords path (C is only
correct once B feeds it `[0,1]` coords — the two fixes are coupled).

---

## (D) PADDING / MASK — ⚠️ mostly correct; one edge divergence (both harnesses)

1. **actual_length = true unpadded count** — ✅. `create_tensors`
   (`test_cli_predict.py:163-174`) sets `actual_length = len(trajectory_features)`
   (the real point count), copies only `min(len,250)` rows into the `[1,250,6]`
   zero tensor, and passes `actual_length_tensor = [min(len,250)]`. Decoder gets
   the same value as `actual_src_length` (line 945/199). Matches
   `EncoderWrapper.kt:129` / `TensorFactory.kt:194`. NOT the padded buffer length.
2. **Fixed seq length / pad values** — ✅. `MAX_SEQUENCE_LENGTH=250`; padded
   positions stay **zero** (np.zeros init), padded keys stay **PAD=0** (np.full
   PAD_IDX). Exact parity with `TensorFactory.kt:58-66/93-95` and the web demo
   (a22b76ad note: "zero-features + PAD-keys parity"). No repeated-last-point,
   no sentinel.
3. **Resampling interaction** — ❌ **divergence (minor).** Production RESAMPLES
   traces >250 points to 250 (DISCARD mode, keeps start+end) BEFORE feature calc
   (`SwipeTrajectoryProcessor.kt:172-209`). The harness does **head-keep
   truncation**: it copies the first 250 points and drops the tail
   (`create_tensors` loop `for i in range(min(actual_length,250))`), and
   `actual_length` is clamped to 250. So long swipes lose their **ending** (the
   final key), whereas production preserves it. Affects the longest ~2.6% of
   traces (FUTO 542/20000 = 2.71%, swipedata 247/9918 = 2.49% exceed 250 pts;
   max 951/790 pts). Small in aggregate but systematically wrong on exactly the
   traces most likely to already be hard.
4. **Truncation policy** — as above, head-keep vs production resample-down.

**Recommended patch (D3/D4)** — resample >250-point traces to 250 (preserve
start+end) before `create_tensors`, mirroring `SwipeResampler` DISCARD. Minimal
uniform-resample stand-in:

```python
# in run_head_to_head / run_legacy_smoke, before extract_features, when len(xs)>250:
def _resample_discard(xs, ys, ts, target=MAX_SEQUENCE_LENGTH):
    n = len(xs)
    if n <= target: return xs, ys, ts
    idx = [round(i * (n - 1) / (target - 1)) for i in range(target)]
    return ([xs[j] for j in idx], [ys[j] for j in idx],
            [ts[j] for j in idx] if ts else ts)
```
(Exact SwipeResampler DISCARD semantics preferred if fidelity matters; the
uniform index map above at least keeps the endpoints, unlike head-truncation.)

---

## Decisive empirical confirmation (real ONNX inference)

ONNX Runtime 1.26.0 loads and runs the shipped
`swipe_{encoder,decoder}_android.onnx` natively on this device. Decoded FUTO
in-dict traces two ways (position-only, beam=8, same beam-search code):

- **HARNESS** (pixel grid + `y/280`):        top-1 **73.3%**, top-3 83.3%  (N=300)
- **AUTHORITATIVE** (normalized `[0,1]` grid, no `/280`): top-1 **75.7%**, top-3 83.7%

**+2.4 pts top-1** for the authoritative frame. Representative top-1 flips the
harness LOSES and the authoritative wins: `songs`(H:`sims`), `engaged`(H:`dnaged`),
`allele`(H:`alle`), `avenue`(H:`accemute`), `factory`(H:`facory`),
`became`(H:`necame`) — all vertical/row-confusion errors consistent with the
aspect-distortion + y-compression mechanism. A 4-way factor-isolation run
(harness / grid-fix-only / yband-fix-only / both) to split the gain between B
and C could not complete on this device — CPU was saturated by the two
concurrently-running eval harnesses, and the isolation process was killed before
finishing (re-run with `~/.cache/cleverkeys-test/audit_scratch/ab_isolate.py`
when the harnesses are idle). It is not needed for the verdict: the two defects
are COUPLED (fixing the grid alone still feeds it the distorted `ny`, and fixing
the y-band alone still routes nearest-key through the aspect-wrong pixel grid),
so both must be fixed together — the +2.4-pt net result already isolates their
combined effect against the current harness.

> NET IMPACT: every current FUTO and swipedata neural result is measured on a
> mis-framed input (position-y compressed ~30% at the bottom row + row-confused
> nearest-keys on 16% of traces), understating the neural engine's true accuracy
> by ~2+ top-1 points. Re-run both harnesses after the B+C patch.

---

## Files
- Authoritative featurizer: `src/main/kotlin/tribixbite/cleverkeys/SwipeTrajectoryProcessor.kt`,
  `TrajectoryFeatureCalculator.kt`, `KeyboardGrid.kt`, `CoordinateNormalizer.kt`,
  `onnx/TensorFactory.kt`, `onnx/EncoderWrapper.kt`.
- Web-demo reference (post-a22b76ad): `web_demo/demo/index.html` (getNormalizedCoords
  :1122, prepareSwipeFeatures :1594, KEYBOARD_GRID :544).
- FUTO official grid: `src/test/resources/layouts/futo_qwerty.json`.
- Harness featurizer (DEFECTIVE B/C, edge D): `tools/test_cli_predict.py`
  (NORM_Y :84, QWERTY_KEYS :88, get_nearest_key :102, extract_features :148,
  create_tensors :161, load_corpus_cache frame_remap :754).
- FUTO converters (A ✅): `scripts/fetch_futo_train100k.mjs`,
  `scripts/sample_futo_train100k_parquet.py`.
- swipedata converter (A ✅): `scripts/convert_swipedata_futo.mjs`; runner
  (inherits B/C/D): `scripts/run_swipedata_20k.sh`.
- Scratch: `~/.cache/cleverkeys-test/audit_scratch/` (dump/compare/AB scripts).
</content>
