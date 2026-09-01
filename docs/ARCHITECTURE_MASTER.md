# CleverKeys Master Architecture Document

**Version**: 1.3.0
**Last Updated**: 2026-08-18 (neural engine removed — ADR-011; §1 and the former §5.1/5.2 retired, CTC promoted to §5.1 and to the default mode)
**Status**: Complete (Triple-Checked)

This document contains all parameters, weights, coefficients, thresholds, and configuration values used in CleverKeys.

---

## Table of Contents

1. [Swipe Prediction Parameters](#1-swipe-prediction-parameters)
2. [Swipe Detection Parameters](#2-swipe-detection-parameters)
3. [Gesture Recognition Parameters](#3-gesture-recognition-parameters)
4. [ONNX Model Configuration](#4-onnx-model-configuration)
5. [Beam Search Parameters](#5-beam-search-parameters)
6. [Vocabulary & Dictionary](#6-vocabulary--dictionary)
7. [UI Configuration](#7-ui-configuration)
8. [Performance Tuning](#8-performance-tuning)
9. [Data Flow Diagram](#9-data-flow-diagram)

---

## 1. Swipe Prediction Parameters

> **Removed 2026-08-18 (ADR-011)**: §1.1–1.3 documented the ONNX transformer's
> `neural_*` preferences — beam width, max length, confidence threshold, batch/greedy
> toggles, beam alpha/prune/score-gap, model versioning and resampling. That engine and all
> ~25 of those preferences are gone; the keys are in
> `backup/SettingsValidation.DEPRECATED_KEYS` so old backups import cleanly, and nothing
> reads them. The archived reference is `docs/history/neural-engine/`.
>
> The surviving swipe parameters are the CTC engine's (§5.1) and the geometric engine's
> (`GeometricEngineConfig.kt`, documented in `docs/specs/geometric-swipe-engine.md`).

### 1.4 Debug Settings (Config.kt)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `swipe_debug_detailed_logging` | Boolean | false | Enable detailed swipe logging |
| `swipe_show_debug_scores` | Boolean | false | Show debug scores in UI |
| `termux_mode_enabled` | Boolean | false | Enable Termux compatibility mode |

### 1.5 Token Mapping

> **Removed 2026-08-18 (ADR-011)**: the fixed token table above lived in the transformer's
> `BeamSearchEngine.kt` (PAD/UNK/SOS/EOS = 0–3, a–z = 4–29, space/apostrophe/hyphen =
> 30–32), deleted with that engine. The CTC engine has **no static token table**: emission
> class column `c` means "the `c`-th key of the layout the caller passed in", plus one
> trailing blank column (`swipe/ctc/CtcEmissions.kt`, `CtcLayout.kt` — key geometry is a
> model INPUT). For every layout the shipped adapter builds, the column order is
> alphabetical a–z (`CtcEngineAdapter.ALPHABET`).

### 1.6 Word Prediction Settings (Config.kt)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `word_prediction_enabled` | Boolean | true | Enable word prediction |
| `context_aware_predictions_enabled` | Boolean | true | Dynamic N-gram learning |
| `personalized_learning_enabled` | Boolean | true | Personalized word frequency learning |
| `learning_aggression` | String | "BALANCED" | Learning level: CONSERVATIVE, BALANCED, AGGRESSIVE |
| `prediction_context_boost` | Float | 0.5 | Context boost multiplier (0.5-5.0) |
| `prediction_frequency_scale` | Float | 100.0 | Frequency scaling factor (100-5000) |

### 1.7 Multi-Language Settings (Config.kt)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `enable_multilang` | Boolean | false | Enable multi-language support |
| `primary_language` | String | "en" | Primary language code |
| `auto_detect_language` | Boolean | true | Auto-detect language from context |
| `language_detection_sensitivity` | Float | 0.6 | Detection sensitivity (0.0-1.0) |

---

## 2. Swipe Detection Parameters

### 2.1 Core Swipe Settings (Config.kt)

| Parameter | Type | Default | Range | Description |
|-----------|------|---------|-------|-------------|
| `swipe_typing_enabled` | Boolean | true | - | Enable swipe typing |
| `swipe_min_distance` | Float | 46.4 | 20-100 | Minimum swipe distance (px) |
| `swipe_min_key_distance` | Float | 35.15 | 15-80 | Minimum distance between keys (px) |
| `swipe_min_dwell_time` | Long | 7 | 0-50 | Minimum key dwell time (ms) |
| `swipe_noise_threshold` | Float | 1.26 | 0.5-10.0 | Movement noise filter (px) |
| `swipe_high_velocity_threshold` | Float | 1000.0 | 200-2000 | High velocity threshold (px/sec) |

### 2.2 Swipe Scoring Weights (Config.kt)

| Parameter | Type | Default | Range | Description |
|-----------|------|---------|-------|-------------|
| `swipe_confidence_weight` | Float | 0.8 | 0.0-1.0 | Decoder confidence weight (orphaned — see the TODO in `Config.refresh`) |
| `swipe_frequency_weight` | Float | 0.2 | 0.0-1.0 | Dictionary frequency weight |
| `swipe_common_words_boost` | Float | 1.0 | 0.5-2.0 | Boost for common words |
| `swipe_top5000_boost` | Float | 1.0 | 0.5-2.0 | Boost for top 5000 words |
| `swipe_rare_words_penalty` | Float | 1.0 | 0.5-2.0 | Penalty for rare words |

### 2.3 Swipe Auto-Correction (Config.kt)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `swipe_beam_autocorrect_enabled` | Boolean | true | Apply corrections during beam search |
| `swipe_final_autocorrect_enabled` | Boolean | true | Apply corrections to final output |
| `swipe_fuzzy_match_mode` | String | "edit_distance" | Algorithm: edit_distance, positional |
| `autocorrect_max_length_diff` | Int | 0 | Max length difference for correction |
| `autocorrect_prefix_length` | Int | 0 | Required matching prefix length |
| `autocorrect_max_beam_candidates` | Int | 0 | Max candidates to consider |

### 2.4 Auto-Correction General (Config.kt)

| Parameter | Type | Default | Range | Description |
|-----------|------|---------|-------|-------------|
| `autocorrect_enabled` | Boolean | true | - | Enable auto-correction |
| `autocorrect_min_word_length` | Int | 3 | 2-5 | Minimum word length |
| `autocorrect_char_match_threshold` | Float | 0.67 | 0.5-0.9 | Character match threshold |
| `autocorrect_confidence_min_frequency` | Int | 100 | 100-5000 | Minimum frequency threshold |

### 2.5 Slider Settings (Config.kt)

| Parameter | Type | Default | Range | Description |
|-----------|------|---------|-------|-------------|
| `slider_speed_smoothing` | Float | 0.54 | 0.1-0.95 | Smoothing factor for slider speed |
| `slider_speed_max` | Float | 4.0 | 1.0-10.0 | Maximum slider speed multiplier |

### 2.6 Swipe Trail Appearance (Config.kt)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `swipe_trail_enabled` | Boolean | true | Show swipe trail |
| `swipe_trail_effect` | String | "glow" | Effect: none, solid, glow, rainbow, fade |
| `swipe_trail_color` | Int | 0xFF9B59B6 | Trail color (jewel purple) |
| `swipe_trail_width` | Float | 8.0 | Trail stroke width (dp) |
| `swipe_trail_glow_radius` | Float | 6.0 | Glow radius (dp) |

---

## 3. Gesture Recognition Parameters

### 3.1 CGR Constants

> **Removed 2026-06-10** (`25f6bcd1`, "delete dead recognizers"): `ContinuousGestureRecognizer.kt`
> and its σ/β/λ/κ constants were dead code and were deleted along with
> `SwipeGestureRecognizer` and `LoopGestureDetector`. No CGR exists anywhere in the app.
> Live gesture classification is `GestureClassifier` (tap vs swipe decision) +
> `EnhancedSwipeGestureRecognizer` (swipe-path capture), both driven from `Pointers.kt`;
> swipe *decoding* is the CTC/geometric engines (§5.1 and `GeometricEngineConfig.kt`).

### 3.2 Gesture Timing (Config.kt)

| Parameter | Type | Default | Range | Description |
|-----------|------|---------|-------|-------------|
| `tap_duration_threshold` | Long | 150 | 50-500 | Max tap duration (ms) |
| `double_space_threshold` | Long | 500 | 0-1000 | Double-space to period time (ms) |
| `longPressTimeout` | Long | 600 | 200-1500 | Long press activation (ms) |
| `longPressInterval` | Long | 25 | 10-100 | Key repeat interval (ms) |

### 3.3 Loop Gesture Detection

> **Removed 2026-06-10** (`25f6bcd1`): `LoopGestureDetector.kt` was dead code, deleted with
> the other dead recognizers. Repeated-letter handling in swipe decoding is now the
> geometric engine's duplicate-collapse + loop-variant templates
> (`swipe/geometric/TemplateGenerator.kt`); the CTC beam needs no loop detection (its
> decoder allows repeated characters without an intervening blank — `CtcBeamDecoder.kt`).

### 3.4 Rotation Detection (Gesture.kt)

| Constant | Value | Description |
|----------|-------|-------------|
| `ROTATION_THRESHOLD` | 2 | Rotation sensitivity |

---

## 4. ONNX Model Configuration

### 4.1 Model Files

| Model | File | Size | Description |
|-------|------|------|-------------|
| CTC encoder | `models/ctc_swipe_encoder.onnx` | 2.91 MB | Per-frame character emissions from a swipe trajectory |

Loaded through `onnx/ModelLoader.kt` (XNNPACK-first, NNAPI/QNN providers probed), thread
count from `onnx_xnnpack_threads`.

> **Removed 2026-08-18 (ADR-011)**: the transformer's `swipe_encoder_android.onnx` (5.3 MB)
> and `swipe_decoder_android.onnx` (5.0 MB), their tokenizer/model configs and the 11
> `prefix_boosts/*.bin` tries are deleted. Their tensor signatures and feature layout are
> archived in `docs/history/neural-engine/ONNX_DECODE_PIPELINE.md`.

### 4.2 CTC Encoder I/O

The CTC encoder is LAYOUT-AGNOSTIC: key geometry is a model input, not a training-time
assumption. Exact tensor names, shapes and the featurizer contract are in
`docs/specs/ctc-swipe-engine.md` (kept there so this document cannot drift from the spec).

The geometric engine uses no ONNX model at all.

---

## 5. Beam Search Parameters

> **Scope note (2026-07-20, updated 2026-08-18)**: the autoregressive ONNX decoder's beam
> parameters (former §5.1) and its greedy-search constants (former §5.2) were deleted with
> that engine on 2026-08-18 — see ADR-011 and `docs/history/neural-engine/`. Two engines
> remain and neither shares those parameters. The geometric engine
> (`swipe/geometric/`, spec: `docs/specs/geometric-swipe-engine.md`) does NOT use beam
> search at all — it is a SHARK2-style whole-word template matcher (prune → score → top-K),
> and its full tunable surface lives in `GeometricEngineConfig.kt`, documented in the spec's
> Config Surface + As-Built Notes sections (values are not duplicated here to avoid drift).
> The CTC engine (§5.1 below) uses its OWN trie beam with its own parameters.

### 5.1 CTC Swipe Engine (`swipe/ctc/` + `CtcEngineAdapter.kt`, DEFAULT `ctc` mode)

The default swipe engine (spec: `docs/specs/ctc-swipe-engine.md`): a non-autoregressive
CTC trie-beam decoder over a 2.91 MB CleverKeys-trained ONNX emission encoder
(`models/ctc_swipe_encoder.onnx`, `OnnxCtcEmissionModel`). Selected by
`swipe_engine_mode = "ctc"` (the DEFAULT since 2026-08-18); routing is CTC on any
a-z-complete Latin layout for a served language (`CtcLanguageSupport.SUPPORTED` — seven:
en/fr/de/es, plus it/pt/sv which are `PROVISIONAL`, enabled 2026-08-18 on scale-transferred
evidence with **no per-language accuracy bar**), geometric for every other
language and every non-Latin layout (`SwipeEngineRouter.Mode.CTC` +
`InputCoordinator.performCtcSwipeTyping`'s language fallthrough) — the router is total, so
no layout is left without an engine. Test-validated at 89.31/93.79/94.50 top-1/3/5 on the
**English** FUTO test-2400 split
(see `docs/eval/2026-07-24-test2400-head2head.md` addendum). That is an English number; the
provisional languages have none.

Ship preset `CtcScoringParams.tunedV2` (scoring constants deliberately not user-exposed):

| Parameter | Value | Description |
|-----------|-------|-------------|
| `gamma` | 0.9 | Final length-normalization exponent |
| `lambda` | **per lexicon scale** | Log-frequency weight. **4.0** for the `en_enhanced.json` scale (compressed 134–255 byte scores) and **2.0** for the CKDT `.bin` scale (`freq = 255 − rank`, ~8× the log spread), selected by `CtcScoringParams.presetFor(language)` off `CtcLanguageSupport.SUPPORTED` — i.e. 4.0 for en, 2.0 for fr/de/es/it/pt/sv. λ is calibrated against the lexicon's frequency SCALE, not the language |
| `beta` | 0.25 | Final length bonus |
| `alpha` | 0.0 | (unused in ship preset) |
| `gammaPrune` | 0.25 | Length-aware prune exponent |
| `betaPrune` | 0.9882 | Length-aware prune bonus |
| `beamWidth` | 100 | Default; user-tunable via `ctc_beam_width` (10–300, `CtcSettingsActivity`) |
| `topK` | 8 | Adapter slate size handed to the suggestion pipeline |

Lexicon, per language (`CtcLanguageSupport.assetFor`): **en** reads bundled
`dictionaries/en_enhanced.json` a-z-stripped; **fr/de/es/it/pt/sv** read the bundled CKDT
`dictionaries/<lang>_enhanced.bin` — the same asset the geometric engine uses — at
`freq = max(1, 255 − rank)` (`CtcCkdtLexicon`), then projected onto a–z with the canonical
accented form kept for display (`CtcAzProjection`). Either source is then merged with the
active language's user custom words − disabled words (`CtcLexiconMerge`), rebuilt on
content-hash change. Contraction aliases
are overlaid to display forms in the adapter (`ContractionOverlay`). Suggestion
provenance tags `SuggestionOrigin.CTC` for CTC-decoded swipes
(`SuggestionProvenance.forRoutedEngine`).

---

## 6. Vocabulary & Dictionary

### 6.1 Dictionary Loader (BinaryDictionaryLoader.kt)

| Constant | Value | Description |
|----------|-------|-------------|
| `MAGIC` | 0x54434944 | "DICT" magic number |
| `EXPECTED_VERSION` | 1 | Binary format version |
| `HEADER_SIZE` | 32 | Header size in bytes |

### 6.2 Dictionary Data Source (DictionaryDataSource.kt)

| Constant | Value | Description |
|----------|-------|-------------|
| `PREFIX_INDEX_MAX_LENGTH` | 3 | Max prefix index length |

### 6.3 Bigram Model (BigramModel.kt)

| Constant | Value | Description |
|----------|-------|-------------|
| `LAMBDA` | 0.95 | Interpolation weight for bigram |
| `MIN_PROB` | 0.0001 | Minimum probability for unseen words |

### 6.4 Language Detection (LanguageDetector.kt)

| Constant | Value | Description |
|----------|-------|-------------|
| `MIN_CONFIDENCE_THRESHOLD` | 0.6 | Minimum detection confidence |

### 6.5 Learned Context LM (contextaware/, 2026-08-06)

Persistent, language-keyed, process-singleton learned n-gram stores. Full spec:
`docs/specs/context-learning-and-next-word.md`.

| Constant | Value | Source | Description |
|----------|-------|--------|-------------|
| `DEFAULT_MIN_FREQUENCY` | 2 | `BigramStore.kt` / `TrigramStore.kt` | Surface floor — ignore single occurrences |
| `MAX_BIGRAMS_PER_WORD` | 20 | `BigramStore.kt` | Top continuations kept per previous word |
| `MAX_TOTAL_BIGRAMS` | 10000 | `BigramStore.kt` | Per-language storage cap |
| `MAX_TRIGRAMS_PER_PREFIX` | 10 | `TrigramStore.kt` | Continuations per (w1,w2) prefix |
| `MAX_TOTAL_TRIGRAMS` | 10000 | `TrigramStore.kt` | Per-language storage cap |
| `DEFAULT_DEBOUNCE_MS` | 5000 | `persist/DebouncedPersister.kt` | Write-back debounce |
| `DEFAULT_MAX_DELAY_MS` | 30000 | `persist/DebouncedPersister.kt` | Max delay before forced flush |
| `CONTEXT_WINDOW` | 4 | `LearningGate.kt` | Trailing word window handed to the context LM |
| `MAX_BOOST` / `BOOST_EXPONENT` | 5.0 / 2.0 | `ContextModel.kt` | Context boost = (1 + prob)^2, capped 5x |
| Storage keys | `bigrams_json_<lang>` / `trigrams_json_<lang>` | SharedPrefs files `bigram_store` / `trigram_store` | Language-keyed persistence |

### 6.6 Next-Word Prediction (NextWordPredictor.kt, 2026-08-06)

| Constant | Value | Description |
|----------|-------|-------------|
| `MAX_SUGGESTIONS` | 3 | Whole-bar candidate cap |
| `MAX_SWIPE_APPEND` | 2 | Candidates appended after swipe alternates |
| `MIN_LEARNED_FREQUENCY` | 2 | Confidence floor (learned count) |
| `MIN_LEARNED_PROBABILITY` | 0.05 | Confidence floor (conditional probability) |

### 6.7 Learning Privacy Gate & Provenance (2026-08-06)

| Parameter | Key | Default | Description |
|-----------|-----|---------|-------------|
| Master learning gate | `on_device_learning_enabled` | true | Opt-OUT switch over ALL typing-behavior learning (write + read paths); `LearningGate.kt` |
| Next-word prediction | `next_word_prediction_enabled` | false | Opt-in learned next-word suggestions |
| Context source | `context_source` | "both" | `both` \| `learned_only` \| `static_only` — which context LM feeds `UnifiedScore.combine` |
| Personalization weight | `personalization_weight` | 1.0 | 0–2 continuous strength; multiplier = 1 + boost×weight/4 |
| Origin markers | `suggestion_provenance_markers` | false | Colored per-origin dot on suggestions (long-press sheet always available) |
| Incognito flag | `IME_FLAG_NO_PERSONALIZED_LEARNING` | 0x1000000 | Per-field learning suppression (mirrored in `LearningGate.kt`) |

---

## 7. UI Configuration

### 7.1 Keyboard Layout (Config.kt)

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `keyboardHeightPercent` | Int | 35 (portrait) / 50 (landscape) | Keyboard height % |
| `characterSize` | Float | 1.18 | Character size multiplier |
| `labelBrightness` | Int | 100% → 255 | Label brightness (0-255) |
| `keyboardOpacity` | Int | 100% → 255 | Keyboard opacity (0-255) |
| `keyOpacity` | Int | 100% → 255 | Key opacity (0-255) |

### 7.2 Clipboard (ClipboardDatabase.kt / ClipboardHistoryService.kt)

| Constant | Value | Description |
|----------|-------|-------------|
| `DATABASE_VERSION` | 5 | SQLite schema version (`ClipboardDatabase.kt:1869` — v5 added `is_private`/`source_package` for #156 Private copy) |

> There is no `HISTORY_TTL_MS` constant (verified 2026-08-21 — a "7 days" constant was
> listed here in error). Retention is user-configured: `clipboard_history_duration`
> (minutes; **-1 = never expire, the default**) is converted at read time by
> `ClipboardHistoryService.getHistoryTtlMs()` (returns `Long.MAX_VALUE` for -1).

### 7.3 Wide Screen Detection (Config.kt)

| Constant | Value | Description |
|----------|-------|-------------|
| `WIDE_DEVICE_THRESHOLD` | 600dp | Width threshold for wide layouts |

---

## 8. Performance Tuning

### 8.1 Async Handling (PredictionTaskRunner, `InputCoordinator.kt:47`)

> `AsyncPredictionHandler` (a Handler/message-based queue, `MSG_PREDICT`/`MSG_CANCEL_PENDING`)
> was deleted with the neural engine on 2026-08-18 (ADR-011). Off-main decode now runs on
> `PredictionTaskRunner` (defined in `InputCoordinator.kt`): a single decode thread with a
> FOREGROUND slot (`cancelAndSubmit` — a new swipe cancels the previous decode) and a
> BACKGROUND slot (prewarm work that never cancels a running decode). Both engine adapters
> (`swipe/CtcEngineAdapter.kt`, `swipe/GeometricEngineAdapter.kt`) own one instance each;
> behavior pinned by `PredictionTaskRunnerTest`.

### 8.2 Dictionary Manager (DictionaryManagerActivity.kt)

| Constant | Value | Description |
|----------|-------|-------------|
| `SEARCH_DEBOUNCE_MS` | 300 | Search debounce delay |

### 8.3 Model Load Failure Latch (`swipe/CtcEngineAdapter.kt`)

> `ModelVersionManager` (multi-version model fallback, `MAX_CONSECUTIVE_FAILURES = 3`) was
> deleted with the neural engine on 2026-08-18 (ADR-011). There is now ONE bundled model
> and no version fallback; the surviving failure handling is the adapter's load latch:

| Constant | Value | Description |
|----------|-------|-------------|
| `MAX_MODEL_LOAD_ATTEMPTS` | 3 | ONNX session load attempts before the adapter latches `isModelPermanentlyUnavailable()`; `InputCoordinator.performCtcSwipeTyping` then routes the swipe to the geometric engine |

### 8.4 Swipe Gesture Recognizer (ImprovedSwipeGestureRecognizer.kt)

| Constant | Value | Description |
|----------|-------|-------------|
| `MAX_POINT_INTERVAL_MS` | 500 | Max time between points |

---

## 9. Data Flow Diagram

```
                              USER INPUT
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────┐
│         TOUCH EVENTS (Keyboard2View.onTouch → Pointers)          │
│  There is no `SwipeDetector` class (verified 2026-08-21).        │
│  ┌─────────────┐   ┌───────────────────────┐   ┌────────────┐  │
│  │ ACTION_DOWN │──▶│ Pointers.onTouchDown  │──▶│ start path │  │
│  └─────────────┘   │  (+ EnhancedSwipe-    │   │ capture    │  │
│                    │   GestureRecognizer)  │   └────────────┘  │
│  ┌─────────────┐   ┌───────────────────────┐   ┌────────────┐  │
│  │ ACTION_MOVE │──▶│ Pointers.onTouchMove  │──▶│ collect    │  │
│  └─────────────┘   │  path points          │   │ TouchedKeys│  │
│                    └───────────────────────┘   └────────────┘  │
│  ┌─────────────┐   ┌───────────────────────┐   ┌────────────┐  │
│  │ ACTION_UP   │──▶│ Pointers.onTouchUp →  │──▶│ swipe →    │  │
│  └─────────────┘   │ GestureClassifier     │   │ decode;    │  │
│                    │ (TAP vs SWIPE)        │   │ tap → key  │  │
│                    └───────────────────────┘   └────────────┘  │
└─────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────┐
│                    ENGINE ROUTING                                │
│  SwipeEngineRouter.route(layout, swipe_engine_mode)             │
│    ├── Latin script + ctc mode  ─────────▶ CTC                  │
│    └── otherwise                ─────────▶ GEOMETRIC            │
│  InputCoordinator.performCtcSwipeTyping then falls through to   │
│  GEOMETRIC for any language CtcLanguageSupport does not serve,  │
│  and for a Latin layout missing an a–z key.                     │
└─────────────────────────────────────────────────────────────────┘
                    │                            │
                    ▼                            ▼
┌───────────────────────────────┐  ┌──────────────────────────────┐
│         CTC ENGINE            │  │      GEOMETRIC ENGINE        │
│  CtcFeaturizer                │  │  Template index per          │
│   └─ trajectory + live key    │  │  (layout, language)          │
│      geometry (layout-agnostic)│  │  SHARK2-style shape match:  │
│  ctc_swipe_encoder.onnx       │  │   prune → score → top-K      │
│   └─ per-frame emissions      │  │                              │
│  CtcBeamDecoder               │  │  GeometricEngineConfig.kt    │
│   └─ Viterbi trie beam over   │  │  (28 knobs, 3 user-exposed)  │
│      the merged lexicon       │  │                              │
│      (ctc_beam_width, tunedV2)│  │                              │
└───────────────────────────────┘  └──────────────────────────────┘
                    │                            │
                    └────────────┬───────────────┘
                                 ▼
┌─────────────────────────────────────────────────────────────────┐
│                    POST-PROCESSING                               │
│  ContractionOverlay  ("dont" → "don't", accented forms)          │
│  SuggestionHandler.handleSwipePredictionResults                  │
│   └─ password guard, possessive augmentation, shift/caps,        │
│      the single commit engine                                    │
└─────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────┐
│                       OUTPUT                                     │
│  PredictionResult(                                               │
│    words = ["hello", "hallo", "help"],                          │
│    scores = [950, 820, 780]   // engine-relative, never mixed    │
│  )                                                               │
└─────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
                           SUGGESTION BAR
```

---

## Appendix A: Configuration File Locations

| File | Description |
|------|-------------|
| `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | Main configuration class |
| `src/main/kotlin/tribixbite/cleverkeys/swipe/ctc/CtcBeamDecoder.kt` | CTC trie-beam decoder |
| `src/main/kotlin/tribixbite/cleverkeys/swipe/geometric/GeometricEngineConfig.kt` | Geometric engine constants |
| `src/main/kotlin/tribixbite/cleverkeys/swipe/OnnxCtcEmissionModel.kt` | ONNX tensor creation for the CTC encoder (`TensorFactory`, `ContinuousGestureRecognizer` and `LoopGestureDetector` are deleted — §3.1/§3.3/§1.5 notes) |
| `src/main/kotlin/tribixbite/cleverkeys/GestureClassifier.kt` | Tap-vs-swipe classification |
| `src/main/kotlin/tribixbite/cleverkeys/BigramModel.kt` | Language model |
| `src/main/kotlin/tribixbite/cleverkeys/activities/SettingsActivity.kt` + `ui/settings/sections/` | Settings UI (Compose — there is no `res/xml/settings.xml`) |

---

## Appendix B: SharedPreferences Keys

All settings are stored in SharedPreferences with these keys:

### Swipe Keys
- `swipe_typing_enabled`, `onnx_xnnpack_threads`
- `swipe_typing_enabled`, `swipe_min_distance`, `swipe_trail_enabled`
- `swipe_engine_mode` (ctc/geometric), `ctc_beam_width`

### Auto-correction Keys
- `autocorrect_enabled`, `autocorrect_min_word_length`
- `swipe_beam_autocorrect_enabled`, `swipe_final_autocorrect_enabled`

### UI Keys
- `keyboard_height`, `theme`, `character_size`
- `keyboard_opacity`, `key_opacity`, `label_brightness`

### Clipboard Keys
- `clipboard_history_enabled`, `clipboard_history_limit`
- `clipboard_pane_height_percent`, `clipboard_max_item_size_kb`

---

**Document End**
