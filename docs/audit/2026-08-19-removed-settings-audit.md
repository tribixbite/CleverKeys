# Audit: were the 2026-08-18/19 pref deprecations actually decoder-specific?

**Date:** 2026-08-19 · **HEAD:** `ad6d299f` · **Read-only audit — no code changed.**

**Scope:** every key added to `SettingsValidation.DEPRECATED_KEYS` by the neural engine
removal (`a7d03bc8`..`83220634`, then `ed2ead3a`/`716f7be9`) — the 18 `neural_*` knobs +
`neural_preset`, `finger_occlusion_offset`, `swipe_beam_autocorrect_enabled`, the two
raw-beam debug toggles, and the six word-scoring keys added 2026-08-19 — plus an inverse
sweep of the deleted `NeuralSettingsActivity.kt` for controls whose prefs are NOT
deprecated. Consumer behaviour was recovered from git history (`64f401d2^`, `018d94f7^`),
not inferred from names.

**Method note:** the pre-removal parity audit
`docs/audit/2026-08-17-neural-vs-ctc-parity.md` (§1.4, §"Is CTC genuinely universal?")
already listed most of these as MISSING in CTC — the removal plan then dropped them
anyway (`docs/plans/2026-08-18-neural-engine-removal.md:179`: *"`finger_occlusion_offset`
— dropped; swipe tuning becomes `ctc_beam_width` + geo knobs"*). So the per-pref evidence
existed; the classification error was in the removal plan's blanket verdict, not in a lack
of information.

---

## Verdict table

Classes: **A** decoder-specific (correctly removed) · **B** general input/gesture
(wrongly removed) · **C** general scoring/presentation (judge per-engine) ·
**D** needs investigation.

| pref | what it actually did (file:line from history) | class | current status | verdict | if restore: where it applies today |
|---|---|---|---|---|---|
| `neural_beam_width` | transformer beam width (`onnx/SwipePredictorOrchestrator.kt:123` → `BeamSearchEngine`) | A | deprecated | correctly removed | CTC equivalent exists: `ctc_beam_width`, user-tunable, coerced 10..300 (`CtcEngineAdapter.kt:727`) |
| `neural_max_length` | max decoded token length (`Orchestrator.kt:124`) | A | deprecated | correctly removed | CTC word length is bounded by the lexicon trie — no knob needed |
| `neural_confidence_threshold` | beam/post-filter confidence floor (`Orchestrator.kt:125,516`) | A | deprecated | correctly removed | none needed (CTC candidates are softmax-ranked, all lexicon words) |
| `neural_batch_beams` | batched decoder inference toggle (`Orchestrator.kt:134,483`) | A | deprecated | correctly removed | n/a |
| `neural_greedy_search` | greedy-vs-beam switch (`Orchestrator.kt:439,457` → `GreedySearchEngine`) | A | deprecated | correctly removed | CTC has greedy argmax internally as a fast path, not user-facing — fine |
| `neural_beam_alpha` | GNMT length-normalisation α (`Orchestrator.kt:126`) | A | deprecated | correctly removed | concept survives hard-coded: `CtcScoringParams.gamma` (per-language, corpus-tuned — `ctc/CtcScoringParams.kt:37,62`) |
| `neural_beam_prune_confidence` | in-beam pruning threshold (`Orchestrator.kt:127`) | A | deprecated | correctly removed | hard-coded `gammaPrune`/`betaPrune` (`CtcScoringParams.kt:41-42`) |
| `neural_beam_score_gap` | beam score-gap prune (`Orchestrator.kt:128`) | A | deprecated | correctly removed | n/a |
| `neural_adaptive_width_step` | adaptive width schedule (`Orchestrator.kt:129`) | A | deprecated | correctly removed | n/a |
| `neural_score_gap_step` | score-gap schedule (`Orchestrator.kt:130`) | A | deprecated | correctly removed | n/a |
| `neural_temperature` | logit temperature (`Orchestrator.kt:131`) | A | deprecated | correctly removed | n/a |
| `neural_frequency_weight` | multiplier on dictionary-frequency weight in candidate ranking, default 0.57 (`OptimizedVocabulary.kt:206,539,702`) | C | deprecated | acceptable removal, **silent loss of user control** | concept survives hard-coded: `CtcScoringParams.lambda` (0.006–0.022 per language, corpus-tuned); geometric keeps a USER knob: `geo_frequency_weight` (`GeometricSettingsActivity.kt:254`) |
| `neural_prefix_boost_multiplier` / `_max` (+ `_<lang>` variants) | Aho-Corasick prefix-boost trie applied INSIDE the beam, data from langpack `prefix_boost.bin` (`Orchestrator.kt:150-151`; `BeamSearchEngine.kt:441-467` per parity audit) | A | deprecated (+ prefix rule) | correctly removed | CTC gets language frequency shaping from λ·logFreq over the lexicon; NOTE `LanguagePackManager` still accepts `prefix_boost.bin` that nothing reads (deliberate, `64f401d2` message) |
| `neural_max_cumulative_boost` | cap on cumulative prefix boost (`Orchestrator.kt:450`) | A | deprecated | correctly removed | n/a |
| `neural_strict_start_char` | HARD constraint: only keep beams whose word starts with the first detected key (`Orchestrator.kt:445-451,479` → `BeamSearchEngine:136`), **default false** (`Config.kt@64f401d2^:158`) | C | deprecated | acceptable removal | user-meaningful concept a trie-constrained decoder could honour (filter first trie edge), but it was opt-in and default-off, so no default-user regression; candidate future `ctc_*` knob, not a restore obligation |
| `neural_resampling_mode` (discard/interpolate/average) | how the trace was cut down to the TRANSFORMER's max seq length (`Orchestrator.kt:145` → `SwipeTrajectoryProcessor.setResamplingMode` → `SwipeResampler`) | A | deprecated | correctly removed | resampling is decoder-front-end, matched to training: CTC's is fixed FUTO two-stage 60 Hz→64 (`CtcFeaturizer.kt:58-122`), geometric ports its own arc-length resampler. NOT general. Leftover: `SwipeResampler.kt` is now consumer-less dead code |
| `neural_user_max_seq_length` | user clamp on encoder seq length (`Orchestrator.kt:140-141`) | A | deprecated | correctly removed | CTC input is fixed `[2,64]` — n/a |
| `neural_preset` | preset picker state for the deleted screen (`NeuralSettingsActivity.kt`) | A | deprecated | correctly removed | n/a |
| **`finger_occlusion_offset`** | shifted RAW touch Y down by `rowHeight × pref/100` (default 12.5%) BEFORE normalisation/featurisation (`NeuralLayoutHelper.kt:274-285` → `SwipeTrajectoryProcessor.kt:364-366`); UI lived in **Gesture Tuning**, not Neural Settings (`GestureTuningSection.kt@018d94f7^:270-277`) | **B** | deprecated | **capability wrongly removed** — but see §1: restore as a default-0 knob, NOT verbatim at 12.5% | `CtcEngineAdapter.decodeAsync` rawY→py affine (`CtcEngineAdapter.kt:724-725`, offset derivable from the mapped letter-row pitch); geometric: `GesturePreprocessor` entry. Neither applies any equivalent today |
| `swipe_beam_autocorrect_enabled` | gated fuzzy RESCUE of out-of-vocabulary beam strings against custom words + dictionary (`OptimizedVocabulary.kt:203,361,631-720`) | A | deprecated | correctly removed | concept is vacuous for CTC/geometric: their candidates are lexicon-constrained by construction, an OOV beam string cannot occur. Custom words survived via CTC's merged lexicon (content-hash keyed). See §3 for the `swipe_final_autocorrect_enabled` distinction |
| `swipe_debug_show_raw_output` | debug: bypass vocab filter, show raw beam strings (`Orchestrator.kt:133` → `PredictionPostProcessor`) | A | deprecated | correctly removed | no raw/filtered distinction exists in CTC |
| `swipe_show_raw_beam_predictions` | debug: append raw beam predictions to the slate (`Orchestrator.kt:517`) | A | deprecated | correctly removed | same |
| `swipe_prediction_source` | 0–100 slider → `swipe_confidence_weight`/`swipe_frequency_weight` blend in ranking (`Config.kt@64f401d2^:928-930`; `OptimizedVocabulary.kt:194-195,540`) | C | deprecated, **but Config.kt:824-826 still reads it into two dead fields** | acceptable removal | concept survives: CTC λ (hard-coded), geometric `geo_frequency_weight` (user knob). Cleanup: delete the dead read (TODO already at `Config.kt:815`) |
| `swipe_common_words_boost` | tier-2 (common) score boost, default 1.0 (`OptimizedVocabulary.kt:198,508-510`) | C | deprecated | acceptable removal | CTC has no word tiers; continuous λ·logFreq supersedes the tier model |
| `swipe_top5000_boost` | tier-1 boost, default 1.0 (`OptimizedVocabulary.kt:199`) | C | deprecated | acceptable removal | same |
| `swipe_rare_words_penalty` | tier-0 penalty, default 1.0 (`OptimizedVocabulary.kt:200`) | C | deprecated | acceptable removal | same |
| `swipe_fuzzy_match_mode` | edit-distance vs positional algorithm for the fuzzy rescue above (`OptimizedVocabulary.kt:213,703`) | A | deprecated; **Config.kt:813 still reads it, dead** | correctly removed | no fuzzy matcher exists to configure; orphaned strings `autocorrect_fuzzy_algorithm_*` remain in `strings.xml:481-482` |
| `autocorrect_max_beam_candidates` | how many top REJECTED beam strings the fuzzy rescue tried, default 3 (`OptimizedVocabulary.kt:210,645`) — despite the `autocorrect_` prefix it had **no tap-path consumer** | A | deprecated — **but its "Correction Search Depth" slider STILL RENDERS and still writes the key** (`AutoCorrectionSection.kt:171-185`) | correctly deprecated, **live UI defect** | delete the slider (it is exactly the "responds to touch, changes nothing" defect `ed2ead3a` set out to fix — it removed 5 of the 6 controls and missed this one) |

### Inverse sweep — deleted `NeuralSettingsActivity` controls whose prefs are NOT deprecated

| pref | control | finding |
|---|---|---|
| **`swipe_smoothing_window`** | "Touch Smoothing" slider 1..7 (`NeuralSettingsActivity.kt@018d94f7^:259-270,618`) — its **ONLY** UI surface | **inverse defect, MUST RESTORE a control.** The pref is fully live: `ImprovedSwipeGestureRecognizer.kt:40` reads it on every swipe (coerce 1..7, default 3) and the SMOOTHED path is what both engines decode — `Keyboard2View.kt:1575` `recognizer.getSwipePath()` → `InputCoordinator.kt:634/739` → `decodeAsync`. Since `018d94f7` users can change it only via backup import |
| `onnx_xnnpack_threads` | thread slider (`NSA:324-331`) | fine — duplicate control; survives in `CtcSettingsActivity.kt:222-242` and is read by `CtcEngineAdapter.kt:193` |

---

## 1. `finger_occlusion_offset` — the maintainer's example, examined properly

**It is class B, confirmed.** The deleted chain (`NeuralLayoutHelper.kt:274-285` →
`SwipeTrajectoryProcessor.kt:364-366`) shifted **raw touch Y** down by
`rowHeight × (pref/100)` before any normalisation or featurisation. It modelled the human
("users typically touch ~74 px above key center because the fingertip obscures the
target"), not the decoder. The settings UI itself filed it under **Gesture Tuning**
(`gesture_finger_occlusion_title`), and `9eded444` (2026-01-13) shows it began life as a
hard-coded 12.5% in v1.32.466 that was promoted to a user pref precisely because it is a
per-user/per-device quantity. The `SettingsValidation.kt:130-131` rationale for deprecating
it — *"CTC and geometric take key geometry as an input"* — is a mischaracterisation: the
offset transformed the TRACE, not the key geometry. Neither `CtcEngineAdapter` (rawY used
untransformed at `:725`) nor `GeometricEngineAdapter` applies anything equivalent today
(geometric's `geo_endpoint_inset_kw` is an endpoint-slop tolerance, not a Y bias). The
capability is simply gone.

**But "restore verbatim at 12.5%" is NOT established.** Two findings cut against it:

1. **No measurement ever existed.** The "~74 px" and "recommended 10-15% range" are
   assertions in comments; `9eded444`, `fbeea671` ("verify training match" — it verified
   velocity/timestamp featurisation, not the offset) and the release commit `b5d9dbbe`
   contain no accuracy data. The 12.5% default shipped on intuition — scenario (c) of the
   coordinator's trichotomy, with a dash of (a) (it was at least made per-user tunable).
2. **CTC's training distribution already embeds human occlusion bias.** The shipped
   encoder was trained from scratch on swipe.futo.org + How-We-Swipe — real recorded human
   traces, fed RAW (`docs/specs/ctc-swipe-engine.md:390-392`; `CtcFeaturizer` is a
   bit-faithful port of the training featuriser, fixed 60 Hz→64 letter-box normalisation
   with the 4/3 vertical-aspect factor, no Y correction anywhere). The engine's measured
   89.31 t1 (test-2400, parity audit) — which BEAT neural-with-offset's 74.62 on the same
   set — was achieved on uncorrected traces. Applying a blanket +12.5%-of-row-height shift
   at runtime moves device input AWAY from that training/eval distribution.

Scenario (b) — "the letter-box normalisation absorbs it" — is **false** as an absolute:
the affine is linear, so a constant Y bias survives normalisation as a constant offset in
model space. What absorbs the *corpus-average* bias is the training data (point 2); what
nothing absorbs is *this user's deviation from the corpus average* — which is exactly the
part a per-user calibration knob is for.

**Verdict:** restore the *capability* as a user knob with **default 0** (not 12.5%),
applied to `rawY` before the letter-box affine in `CtcEngineAdapter.decodeAsync`
(`CtcEngineAdapter.kt:724-725`; the row pitch is derivable from the mapped a–z key
centers) and at `GesturePreprocessor`'s ingest for geometric. Default-0 restores user
control with zero behaviour change for everyone who doesn't touch it. Promoting a nonzero
default requires an A/B: the D5 `SwipeMLData` capture already tags per-engine device
traces (`InputCoordinator.beginSwipeCapture`, `ENGINE_CTC`), so replaying captured traces
through `CtcBeamDecoder` at offset 0 vs 8/12.5/16% is a pure-JVM experiment the repo is
already equipped for (same pattern as the FUTO eval scripts).

## 2. Touch smoothing (question 2)

`swipe_smoothing_window` **genuinely still applies on the CTC path** — verified end to
end: `ImprovedSwipeGestureRecognizer.applySmoothing` (`:152`) builds `_smoothedPath`,
`getSwipePath()` (`:438`) returns it, `Keyboard2View.kt:1575` reads it, and
`InputCoordinator` hands exactly that list to `CtcEngineAdapter.decodeAsync:739` and
`GeometricEngineAdapter.decodeAsync:634`. The pref was never neural-specific and was
correctly NOT deprecated. What was lost is its **only UI control** (see inverse-sweep
table). The other trace-shaping pref, `neural_resampling_mode`, was genuinely
decoder-specific (resampling must match each decoder's training regime) — correctly
removed.

## 3. `swipe_beam_autocorrect_enabled` vs `swipe_final_autocorrect_enabled` (question 4)

Different pipeline stages, not the same concept twice:

- **beam** (deprecated, default was true): inside `OptimizedVocabulary.filterPredictions`,
  rescued raw transformer outputs that were NOT dictionary words ("proxity" → "proximity")
  by fuzzy-matching them against custom words and length-bucketed dictionary words
  (`OptimizedVocabulary.kt:631-720`). Only meaningful for a decoder that can emit
  non-words. CTC and geometric are lexicon-trie-constrained — an OOV candidate cannot
  exist — so the stage is vacuous, not missing. **Correctly removed.**
- **final** (kept, default true, live): `SuggestionHandler.kt:817` runs WordPredictor
  autocorrect over the top suggestion after any engine. Untouched.

No silent autocorrect change occurred at deprecation time: the beam stage died with the
engine that fed it (already unreachable since `a7d03bc8`), and the final stage's default
(`true`) is unchanged (`Config.kt:176`).

## 4. Tap typing (question 5)

**Clean.** No deprecated key had a tap-path consumer. WordPredictor's autocorrect reads
`autocorrect_min_word_length`, `autocorrect_max_length_diff`, `autocorrect_prefix_length`,
`autocorrect_char_match_threshold`, `autocorrect_confidence_min_frequency`
(`WordPredictor.kt:2143-2280`) — all still live with live sliders. The pre-removal
`WordPredictor.kt:1294` mention of `neural_frequency_weight` was a comment, not a read.
`autocorrect_max_beam_candidates`, despite its tap-sounding name, was only ever read by
`OptimizedVocabulary` (verified by rev-grep at `64f401d2^`).

---

## Prioritised RESTORE / FIX list

1. **`swipe_smoothing_window` UI control** (inverse defect, zero-risk): add a slider
   (1..7, default 3) to `ui/settings/sections/GestureTuningSection.kt` + the settings
   search index. The pref, validation (`SettingsValidation.kt:287`), defaults entry and
   consumer all already exist.
2. **Delete the zombie "Correction Search Depth" slider**
   (`AutoCorrectionSection.kt:171-185`): it writes `autocorrect_max_beam_candidates`,
   a key that is simultaneously in `DEPRECATED_KEYS` — the exact defect class `ed2ead3a`
   was fixing; it removed 5 of the 6 controls and missed this one. Also drop the dead
   `Config.kt:810` read and the now-orphaned `autocorrect_search_depth_*` strings.
3. **`finger_occlusion_offset`**: restore as described in §1 — knob back in Gesture
   Tuning, applied in `CtcEngineAdapter.decodeAsync` (`:724-725`) and
   `GesturePreprocessor`, **default 0**, un-deprecate the key so v1.5.x backups restore a
   user's calibrated value (a user who had tuned it away from 12.5% was expressing a real
   per-device measurement). Gate any nonzero default on the trace-replay A/B.
4. **Cleanup (no user impact)**: remove the dead `Config.kt:815-829` reads of
   `swipe_prediction_source`/boosts (fields `swipe_confidence_weight`/`swipe_frequency_weight`
   have no readers), the dead `swipe_fuzzy_match_mode` read (`Config.kt:813`), the
   deprecated-pref plumbing in `SettingsPersistence.kt:195,347,353`, orphaned strings
   (`autocorrect_fuzzy_algorithm_*`, `autocorrect_source_balance_*`,
   `strings.xml:481-490`), and consumer-less `SwipeResampler.kt`.

## Formerly user-controllable, now hard-coded (silent even where defensible)

- **Frequency-vs-shape balance**: `neural_frequency_weight` (default 0.57) +
  `swipe_prediction_source` (default 80) → CTC `CtcScoringParams.lambda`, hard-coded
  per language (corpus-tuned, documented in the spec). Geometric alone keeps a user knob
  (`geo_frequency_weight`).
- **Beam behaviour**: 11 neural knobs → one (`ctc_beam_width`); γ/λ/β/γp/βp fixed.
- **First-letter hard constraint** (`neural_strict_start_char`): gone entirely, no
  equivalent; was default-off.
- **Resampling strategy**: 3-mode choice → fixed training-matched pipeline.
- The hard-coding is defensible in every case (the CTC constants are measured, the neural
  knobs were not), but it should be acknowledged as a deliberate reduction of the tuning
  surface, not an equivalence.

## Could not determine

- **Whether a nonzero occlusion offset helps or hurts CTC on real devices.** Settled by:
  replay captured `SwipeMLData` device traces (ENGINE_CTC) through `CtcBeamDecoder` with
  offset ∈ {0, 8%, 12.5%, 16%} and compare top-1/top-3. No corpus-side answer exists —
  the FUTO/HWS corpora embed *their* users' bias, not this user's.
- **Whether the neural 12.5% default was ever validated.** No measurement found anywhere
  in history (`9eded444`, `fbeea671`, `b5d9dbbe`, both audits). Treat it as never
  validated.
