# Swipe pre-processing settings: defaults vs the S25U's (2026-11-24)

Maintainer, 2026-11-23: "any other Typing and swipe settings either i or default should adopt?
dwell, smoothing, noise?" The S25U holds:

| setting | S25U | default |
|---|---|---|
| `swipe_noise_threshold` | 1.45 px | 1.26 |
| `swipe_min_dwell_time` | 9 ms | 7 |
| `swipe_high_velocity_threshold` | 957.9 px/s | 1000 |
| `swipe_smoothing_window` | 1 | 3 |

Measured at HEAD `4112648a` with `scripts/swipe_settings_replay.py`, a port of the gesture layer
these settings act on. It decodes with the parity-checked `scripts/short_word_ctc_eval.py`.

## What each setting can reach (code, HEAD `4112648a`)

- **Noise:** a sample closer than the threshold to the last KEPT sample is dropped
  (`ImprovedSwipeGestureRecognizer.kt:149-157`). This applies to the CTC trace too.
- **Smoothing:** a trailing average into `SwipeResult.path` only (`:169-170`, `:190-209`). The
  default CTC engine featurizes the unsmoothed `ctcTrace()` (`:458`; spec
  `ctc-swipe-engine.md` "Input contract"), so smoothing cannot change a CTC result. The geometric
  engine reads the smoothed path (`InputCoordinator.kt:766-767`).
- **Dwell and velocity:** `SwipeKeyRegistrar.kt:87` refuses a key when the time since the last
  sample is under the dwell time AND the velocity is over the threshold. CTC never reads the touched
  keys (`CtcEngineAdapter.decodeAsync`), but the key count gates whether a gesture reaches CTC at
  all: word candidacy needs ≥ 2 keys (`:176-178`), and `onSwipeEnd` decodes only if
  `isSwipeTyping()` (`Keyboard2View.kt:1045`). On a 120 Hz screen samples arrive 8, 8, 9 ms apart:
  a 7 ms dwell almost never fires; at 9 ms two of every three fast samples cannot register a key.

## Method

The geometry assumed is the S25U's portrait 1080 × 2340 at keyboard height 27 %: a row of 159.95 px
(`Theme.kt:629`), a 1080 × 479.85 px letter area, QWERTY.

Three clocks:
- native FUTO timestamps;
- **vsync**: each trace re-timed onto 120 Hz frames, the S25U case;
- an upsampled 7–8 ms stress case.

Data:
- held-out: the FUTO swipe-1 test sample, 4,000 traces of 1,751 words;
- dev: `val_ordered`, 9,917 traces;
- routing only, without decoding: `futo_train100k`, 100,000 traces.

The port's CTC traces match the 2026-10-07 emulation on 3,968 of 4,000 traces; the other 32 differ
by the pause rule the old emulation lacked.

## Results

| config | held-out CTC t1 / t3 (native) | end-to-end t1 (native) | CTC t1 (vsync) | end-to-end t1 (vsync) |
|---|---|---|---|---|
| defaults | 92.12 / 96.10 | 90.78 | 92.17 | 90.83 |
| S25U | identical | identical | 92.15 | 90.80 |

Paired against the defaults (gains / losses, distinct words in brackets):

| change | held-out native | held-out vsync | dev native | dev vsync | 100k routing (vsync) |
|---|---|---|---|---|---|
| noise 1.45 | 0 / 0 | 0 / 1 [1] | 0 / 1 [1] | 3 / 3 [3 / 3], p = 0.66 | no route changes |
| dwell 9 | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 1 [1] | **0 / 10** [6 words], p = 0.001 |
| velocity 957.9 | 0 / 0 | 0 / 0 | 0 / 0 | not run | 0 |
| smoothing 1 | 0 / 0; CTC input identical on every trace | | | | 0 |

**Noise 1.45.** It changes the CTC input of 13–60 % of traces, yet every flip is a near-tie at rank
1–2. Losses: `misery` → `most`, `pooled` → `polled`, `war` → `wear`. Gains: `travel`, `eating`.

**Dwell 9.** Pooled, 0 gains / 11 losses (one-sided p ≈ 0.0005, about 0.01 % of swipes). Every loss
is a fast short flick of 19–234 ms (`in`, `as`, `is`, `a`, `was`, `at`): the second key arrives only
on 8 ms frames, so it never registers and the gesture types one letter or nothing.

**Sensitivity.** The replay does detect real effects, on 1,000 traces:
- dwell 20 ms drops 37 swipes (90.50 → 86.90);
- dwell 50 ms with velocity 200 recognises only 74.8 % of swipes;
- noise 3, 5 and 10 px stay within ±0.3 pt.

## Decision

Defaults unchanged, and the S25U's values should go back to them:
- **dwell 7:** 9 only costs;
- **noise 1.26:** either value is fine, but 1.45 is not a slider stop;
- **velocity 1000:** no effect on its own;
- **smoothing 3:** no effect on CTC, and it is what the geometric fallback was tuned on.

The sliders move noise in 0.5 px steps and velocity in 100 px/s steps, so 1.26, 1.45 and 957.9
were not set with them. Where the S25U's values came from was not traced.

**Not measured:** the geometric engine (smoothing 1 vs 3). To run it:
`python3 -I scripts/swipe_settings_geo_corpus.py <D|M|S> <dir>`, then
`CLEVERKEYS_TEST_CACHE=<dir> … runPureTests -PtestClass=swipe.geometric.GeoRealCorpusReplayTest
-PgeoFull=true`. That test prints totals only; a paired comparison needs a per-trace output.

**Side finding, open (`memory/todo.md`):** when the touch-up classifier routes a gesture as SWIPE it
calls `endWordSwipe()` without `promoteWordCandidacy()` (`Pointers.kt:531-538`). A swipe longer
than 272 px with fewer than two keys registered at its last sample therefore types nothing (5 of
100,000 traces at the defaults).

## Commands

```
python3 -I scripts/swipe_settings_replay.py run ~/.cache/cleverkeys-test/futo_swipe1_test_sample.jsonl.gz held_native.jsonl --cache ctc_cache.jsonl
python3 -I scripts/swipe_settings_replay.py run … held_vsync.jsonl --vsync …
python3 -I scripts/swipe_settings_replay.py run ~/.cache/cleverkeys-test/val_ordered.jsonl.gz dev_native.jsonl …
python3 -I scripts/swipe_settings_replay.py routes ~/.cache/cleverkeys-test/futo_train100k.jsonl.gz routes.json --vsync --cfgs D,M,W,V,WV,N,S
python3 -I scripts/swipe_settings_analyze.py <file>.jsonl
```
