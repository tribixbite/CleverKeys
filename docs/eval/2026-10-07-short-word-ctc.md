# Short-word CTC misrecognition (`ad` → `as`, `wet` → `we`) — reinvestigation

Date: 2026-10-07. Scope: English, QWERTY, default CTC engine. This note re-derives the
failure from first principles; the October 5 findings in
`docs/plans/2026-10-05-recommended-features-and-gaps.md` were treated as hypotheses only.

**Status (2026-10-07):** §5's app-side fix is WIRED — CTC now featurizes the recognizer's
unsmoothed samples plus one finger-lift sample (the "proposed" row); geometric keeps the
smoothed path. The shipped Kotlin code path reproduces the "proposed" held-out numbers trace for
trace (§5.1). The encoder-side causes (§3, §6) are unchanged: a dwell-less `ad`/`wet` still
fails.

## 1. Pre-registered evaluation protocol (written BEFORE any rescoring result was seen)

Recorded at the time the dev/held-out dumps were started, before a single rescored
number existed.

**Decode path.** `scripts/short_word_ctc_eval.py` mirrors the shipped stack exactly:
`CtcFeaturizer` (60 Hz + 64-point resample) → shipped `models/ctc_swipe_encoder.onnx` on the
golden layout → `CtcBeamDecoder` (tunedV2/presetFor("en"): γ 0.9, λ 4.0, β 0.25,
γ_prune 0.25, β_prune 0.9882, beam 100) over the EN_JSON strip-loaded `en_enhanced.json`
plus contraction alias keys at the derived floor. Parity against the Kotlin replay log was
checked on the canonical traces (identical slates; scores within ±1 of 1000).

**Data.**
- *Dev (tuning only):* deterministic 3,000-row SHA-256 sample (5 rows are duplicate traces) (seed `20261007`) of
  `val_ordered.jsonl.gz` (hwsfuto val). Any parameter is chosen here and only here.
- *Held-out gate:* every usable row of `futo_swipe1_test_sample.jsonl.gz` (FUTO swipe-1
  TEST split, never in any training tier), plus every FUTO swipe-1 TEST row labelled
  `ad`, `wet` and the controls `we as wt at set sad add ads`, fetched from HF.
- *Secondary report (not a gate):* the local proshian-format corpus, which earlier sessions
  inspected repeatedly.

Rows with non-monotonic timestamps, non-a–z labels or <2 points are excluded and counted.

**Bar (all must hold on held-out, else nothing ships):**
1. Targets: `ad` and `wet` top-1 each improve, combined target top-1 +20 pt or more.
2. Broad held-out top-1 change ≥ −0.3 pt, and the paired loss/gain split is not
   significantly worse (exact sign test, p < 0.05 counts as a fail).
3. Broad held-out top-3 change ≥ −0.3 pt; ≤3-letter stratum top-1 change ≥ −0.5 pt.
4. Controls (`we as wt at set sad add ads`, pooled) top-1 change ≥ −2 pt; no single control
   with ≥10 traces loses more than 5 pt.
5. No per-word rules, no word lists in production code.

Counts are reported as distinct traces AND distinct words.

### 1.1 Addendum — second hypothesis, pre-registered before its held-out read

Dev measurement (3,000 val rows) found that the app's touch-path pre-processing — the
recognizer's trailing 3-point moving average plus the 1.26 px noise drop, whose output is
what `CtcEngineAdapter` featurizes — costs the encoder accuracy relative to the raw samples
it was trained on. Bar for "CTC should featurize the unsmoothed path", fixed before reading
held-out:
1. Held-out (`futo_swipe1_test_sample`, all usable rows) top-1 of raw vs app-emulated input:
   raw ≥ app, with gains > losses and an exact sign test p < 0.05 in favour of raw.
2. ≤3-letter stratum: raw ≥ app − 0.2 pt.
3. Real `ad`/`wet` traces: reported per trace, not gated (too few to gate on).

## 2. Reproduction

Harness: `scripts/short_word_ctc_eval.py` (sub-commands below). Real traces available:
`ad`/`wet` are rare in every human corpus on this machine — FUTO swipe-1 TEST has 1 `ad` and
1 `wet` (counted, but the HF filter endpoint failed before the rows were retrieved);
VALIDATION 1 `Ad` (1 point, unusable) and 1 `wet`; the local FUTO train-100k sample
1 `ad` + 5 `wet`; the hwsfuto training tier 5 `ad` / 4 `wet`. All 7 usable real traces
(1 `ad`, 6 `wet`; writer identity is not recorded in the 100k sample) were decoded; none is top-1 on the shipped stack:

| trace | greedy | top-3 | target rank |
|---|---|---|---|
| ad, 26 pts, ends ON `d` centre | as | as, ad, af | 2 |
| wet, 29 pts | ey | ey, wy, et | 7 |
| wet, 24 pts | wy | we, wy, wt | 5 |
| wet, 18 pts | wt | we, wt, qt | 7 |
| wet, 47 pts (slow end) | wt | wt, wet, we | 2 |
| wet, 67 pts (slow end) | wt | wt, wet, were | 2 |
| wet, 16 pts (FUTO val) | wt | we, et, wt | 8 |

Canonical straight-line traces reproduce the report exactly (`wet` → `we`, rank 7; `ad` →
`as`, rank 2), identical to the Kotlin `CtcReplayEngine` log of October 5.

## 3. Stage-by-stage localization

**Lexicon / filters — ruled out.** `ad` (byte 199) and `wet` (195) are present at sane
frequencies; `as` 237, `we` 233, `wt` 173. Frequency explains only 0.70–0.71 nat of
λ·Δln f for `as`/`ad` and `we`/`wet`. No filter, overlay or rescue touches these words (the
replay slate already fails before display overlays).

**λ (frequency weight) — ruled out as a lever.** The final beam is λ-independent (the
prune key has no frequency term), so an exact λ sweep over stored beams is possible. Dev
(3,000 val rows) top-1: λ 0 → 85.83, 1 → 87.30, 2 → 88.03, 3 → 88.87, **4 → 89.00**,
5 → 88.80, 6 → 88.70. The shipped λ is the dev optimum.

**Featurizer — correct, but the app feeds it a distorted path (§5).** Short traces are
not padded or truncated oddly: the 60 Hz + 64-point resample handles 9–60-point traces
the same way as long ones; emissions are 32 frames regardless.

**Encoder emissions — the primary cause.** The encoder is extremely peaky: first letter at
frame 0, LAST letter always at frame 31, intermediate letters only where the path turns.
Sliding the endpoint of a straight swipe along the row (`sweep`):

| path | ends at | last-frame posterior | beam top-1 |
|---|---|---|---|
| a → | `s` centre (x .20) | s .94 | as |
| a → | `d` centre (x .30) | **s .78**, d .18 | as |
| a → | `f` centre (x .40) | s .34, f .32, d .21 | as |
| w → | `r` centre (x .35) | **e .97** | we |
| w → | `t` centre (x .45) | **e .63**, t .23 | we |
| w → | `y` centre (x .55) | y .59, t .23 | we |

A trace ending exactly on `d` is read as an overshooting `as`; one ending on `t` (two keys
past `e`) as an overshooting `we`. This is the encoder's learned word prior: in the
hwsfuto training tier `we` occurs 562× vs `wet` 4×, `as` 120× vs `ad` 5×. Real human
endpoints do overshoot (median along-track offset +0.3 key for `as`/`was`, +0.5 for `we`,
90th percentile +0.74 / +1.34), so the encoder's reading is partly the Bayes-correct one
for the population; the decoder's λ·ln f then adds the frequency prior a second time.

**Second mechanism, `wet` only — collinear pass-through.** w, e, r, t are collinear on the
top row and `e` is adjacent to the start key. The encoder emits a letter only where the
path turns or ends, so `e` never gets a frame of its own (on the canonical trace frames
1–30 are blank ≥ .98); forcing it costs 3.0–4.6 nats of raw CTC. With a slow/dwelled ending the last frame does
become `t`, and the contest is then `wt` vs `wet` (final 20.80 vs 20.44 and 20.77 vs 20.38
on the two slow real traces) — lost by that insertion cost.

**Dwell evidence.** A deliberate stop on the last key flips the encoder: straight `ad`
with a 200 ms end dwell (sub-pixel jitter) decodes `ad`; without, `as`. For `wet` a dwell
moves the encoder to `t` but `e` remains un-emitted (`wt`/`we`).

**Prior-claim audit (October 5 note).**
- "wet and wt have identical collinear templates" — **holds** geometrically.
- "both survive in the beam" — **holds**: `ad` rank 2, `wet` rank 2–8 on real traces.
- "greedy emissions already wrong" — **holds** (`as`, `we`/`wt`/`wy`/`ey`).
- "geometry/timing rescoring fails on real traces" — **holds in direction**, re-measured
  here on 7,000 traces with a different (endpoint-only) term: neutral at best (§4).
- "encoder retraining with new data is required" — **partly right, for a different
  reason**: the binding constraint is the encoder's internal word prior and its silence on
  collinear pass-through letters, which word-balanced sampling and targeted synthetic
  traces address; it is not a shortage of human data alone. The October 5 note also
  missed the app-side defect in §5.

## 4. Decoder-side candidates (hypothesis 1) — rejected against the pre-registered bar

| candidate | dev top-1 (3,000) | held-out top-1 (4,000 traces / 1,751 words) | targets |
|---|---|---|---|
| shipped | 89.00 | 92.20 | 0 / 7 |
| endpoint term, isotropic, dev-best weight 0.2 | 89.33 | 92.22 (11 gains / 10 losses, p = 1.0) | 0 / 7 |
| endpoint term, overshoot-aware (fitted μ .145, σ .36/.21) | ≤ 89.27, falls fast above 0.1 | — | — |
| geometric emission floor κ .2 r .5 key | — | — | 2 / 7 `wet`, 2 others → `were` |
| λ sweep | λ 4 optimal | — | — |

Bar 1 (targets +20 pt) fails for every decoder arm: the weight that keeps broad accuracy
(endpoint 0.2 ≈ 0.2 nat for a one-key miss) is an order of magnitude below the 1.2–2.7 nat
gaps. No decoder change ships.

## 5. App-side defect (hypothesis 2) — validated; fixed 2026-10-07 (§5.1)

The CTC engine featurizes `SwipeResult.path`, which is `ImprovedSwipeGestureRecognizer`'s
**smoothed** path (trailing 3-point moving average, `SWIPE_SMOOTHING_WINDOW = 3`) after a
1.26 px noise drop; and no sample is recorded at lift, so a final dwell — exactly the
evidence the encoder uses to choose `ad` over `as` — is erased (stationary samples are
dropped and the timestamps stop at the last moving sample). The encoder was trained on raw
samples.

Held-out, emulating that pre-processing on the raw FUTO test traces (pre-registered §1.1):

| input | top-1 | ≤3-letter top-1 (1,597) | vs current app |
|---|---|---|---|
| current app (noise + smoothing) | 91.83 | 95.24 | — |
| raw samples (what the model was trained on) | **92.20** | **95.74** | 35 gains / 20 losses, one-sided p = 0.029 |
| proposed: noise drop, no smoothing, + lift sample | 92.12 | 95.62 | 34 / 22, p = 0.070 |

Dev agreed (smoothing −0.51 pt, 26 gains / 41 losses). Bar §1.1 is met. Concentration:
the 55 changed traces cover 53 distinct words. Smoothing losses are
dominated by dropped/blurred final letters (`her→he`, `ours→our`, `towards→toward`,
`two→to`, `art→at`). On targets: 200 ms-dwell `ad` decodes `ad` raw, `as` through the
current app path, `ad` again with the lift sample; one real `wet` trace flips `wt → we`
under smoothing.

**Recommended change (implemented 2026-10-07, §5.1):**
give `SwipeResult` an unsmoothed `rawPath` (the recognizer's `_rawPath`, which already has
a parallel `_timestamps`), append one terminal sample at ACTION_UP time (last position,
event time) to the CTC copy, and route only CTC to it. Keep geometric on the smoothed path
until its own replay says otherwise. `SwipeMLData` capture should store the raw path too,
so on-device training data matches the corpus format. This does not by itself make a
dwell-less `ad`/`wet` swipe correct.

### 5.1 As wired (2026-10-07)

Which configuration shipped: the "proposed" row (noise drop kept, no smoothing, one lift
sample), not the "raw samples" row. The recognizer's own unsmoothed list (`_rawPath`, with the
parallel `_timestamps`) already applies the 1.26 px drop; dropping it as well would mean a
second, unfiltered sample list, and its measured value over "proposed" is 0.08 pt on 4,000
traces (not separable from noise). The lift sample restores what the drop removes for a final
stop — its duration. The lift is not clamped (the eval measured the unclamped form).

- `ImprovedSwipeGestureRecognizer.ctcTrace()` → `SwipeResult.rawTrace` (`RawSwipeTrace`):
  unsmoothed accepted samples + at most one lift sample (finite, strictly later than the last
  sample). `Pointers.onTouchUp` records the ACTION_UP position/time immediately before every
  word-swipe end.
- `InputCoordinator.performCtcSwipeTyping` hands `rawTrace` to `CtcEngineAdapter.decodeAsync`;
  every geometric hand-off, and the ML capture, keep the smoothed `path`.
- Continuous swipe: segment samples were already unsmoothed (both engines keep them); the FINAL
  segment additionally carries the lift sample for CTC when the finger lifted on letters.

Integrated check — the real recognizer driven over the same 4,000 held-out traces
(`CtcRawTraceReplayExport`, scaled to the emulation's 1000 × 470 px box), decoded by the
Python decoder:

| comparison | A top-1 | B top-1 | B-only / A-only |
|---|---|---|---|
| emulated "smooth" vs Kotlin smoothed path | 91.83 | 91.83 | 0 / 0 |
| emulated "proposed" vs Kotlin `ctcTrace()` | 92.12 | 92.12 | 0 / 0 |
| Kotlin smoothed vs Kotlin `ctcTrace()` (before → after) | 91.83 | 92.12 | 34 / 22, p = 0.070; ≤3 letters 95.24 → 95.62 |

Straight synthetic traces through the same Kotlin path: `ad` with a 200 ms stop → `ad` (was
`as`); `ad` without a stop → `as` (unchanged); `wet` with or without a stop → `we` (unchanged);
controls `as`, `we` unchanged.

## 6. Model-side recipe (not feasible to ship from this device)

The shipped graph is BN-folded fp16w (1.51 M params) with no checkpoint on this machine;
training needs the `~/ctc-train` workdir (RTX 5080 box). Recipe, in CleverKeys-ML `ctc/`:

1. **Word-balanced sampling** (new `train.py` flag, TODO): sample rows with weight
   `count(word)^(τ−1)`, τ ∈ {0.5, 0.7}, so per-word exposure ∝ count^τ. Target: shrink the
   encoder's internal word prior; the decoder's λ already supplies frequency. Re-sweep λ on
   val after training (expected to rise).
2. **Targeted synthetic rows** via `english_synth.py` / SYNTH_V2 for low-count words whose
   path is collinear through an interior letter or whose first two letters are adjacent
   (`wet`, `ad`, `add`, `sad`, `wee`, …: select by geometry rule, not by list), with
   endpoint offsets drawn from the measured real along-track distribution AND precise-stop
   / dwell variants; mix ≤ 10 % of steps.
3. Fine-tune from the shipped member (`--init-from`), keep KD to the shipped teacher
   (`--kd-teacher … --kd-weight 1.0`) to hold broad accuracy, `--short-loss-weight` 1.5,
   T_out 32 (unchanged I/O contract), 3 seeds.
4. **Bars (pre-register):** val t1 ≥ shipped − 0.2; ≤3 stratum ≥ shipped; real target
   traces from fresh writers/sessions ≥ 50 % top-1 for `ad`/`wet`-class words, distinct
   words and writers reported; FUTO test sample (this note's held-out) non-regression; then
   golden fixture regeneration at the ship preset (fixture-and-preset rule).

Data collection is still useful: the seven real traces above are the only ones in existence
here and are development cases.

## 7. Reproduce

```bash
S=build/short-word-eval; mkdir -p $S
python3 scripts/short_word_ctc_eval.py sweep
python3 scripts/short_word_ctc_eval.py dump ~/.cache/cleverkeys-test/val_ordered.jsonl.gz $S/dev.jsonl --sample 3000
python3 scripts/short_word_ctc_eval.py lambda $S/dev.jsonl
python3 scripts/short_word_ctc_eval.py endpoint $S/dev.jsonl
for e in raw smooth fix; do python3 scripts/short_word_ctc_eval.py dump \
  ~/.cache/cleverkeys-test/futo_swipe1_test_sample.jsonl.gz $S/held_$e.jsonl --emu $e; done
python3 scripts/short_word_ctc_eval.py compare $S/held_smooth.jsonl $S/held_raw.jsonl
python3 scripts/short_word_ctc_eval.py traces <file of real ad/wet rows>
# §5.1: the SHIPPED recognizer over the same held-out rows
python3 scripts/short_word_ctc_eval.py export-rows ~/.cache/cleverkeys-test/futo_swipe1_test_sample.jsonl.gz $S/rows.jsonl
CK_REPLAY_IN=$PWD/$S/rows.jsonl CK_REPLAY_OUT=$PWD/$S/kt \
  scripts/gradle-guard.sh runMockTests -PtestClass=CtcRawTraceReplayExport
for m in ctc smooth; do python3 scripts/short_word_ctc_eval.py dump-trace $S/kt.$m.jsonl $S/held_kt_$m.jsonl; done
python3 scripts/short_word_ctc_eval.py compare $S/held_kt_smooth.jsonl $S/held_kt_ctc.jsonl
```
