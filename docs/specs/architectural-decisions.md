# Architectural Decisions

## Overview

This document records intentional architectural changes from the original Unexpected-Keyboard Java implementation. These are design decisions, not bugs.

## ADR-001: Pure ONNX Neural Prediction
**Status**: ⛔ SUPERSEDED by [ADR-011](#adr-011-remove-the-neural-swipe-engine) (2026-08-18). Kept verbatim as the record of the decision that was made and why.


**Decision**: Replace dictionary-based prediction with pure ONNX transformer neural networks.

**Rationale**:
- Superior prediction accuracy with learned patterns
- Better handling of complex gestures
- Single model architecture vs multiple heuristics
- Modern ML approach vs legacy statistical methods

**Consequences**:
- Training now external (Python/PyTorch) vs on-device
- Requires ONNX Runtime dependency
- Model updates require full retraining

## ADR-002: Template Generation → Neural Training
**Status**: ⛔ SUPERSEDED by [ADR-011](#adr-011-remove-the-neural-swipe-engine) (2026-08-18). Kept verbatim as the record of the decision that was made and why.


**Decision**: Replace gesture template generation with neural network training on real swipe data.

**Rationale**:
- Learns from actual user behavior vs synthetic templates
- Captures natural gesture variations
- Automatic feature learning vs manual template engineering
- Transformer architecture superior to template matching

**Consequences**:
- Requires training dataset of real swipe gestures
- External training pipeline needed
- Cannot generate predictions for arbitrary words without retraining

## ADR-003: External ML Training

**Decision**: Move ML training to external Python/PyTorch pipeline with GPU acceleration, export to ONNX.

**Rationale**:
- Real neural networks (transformers) vs statistical heuristics
- GPU acceleration for complex models
- Separation of training (offline) from inference (on-device)
- Modern ML tooling (PyTorch) vs custom Java code
- Can use large training datasets

**Consequences**:
- Cannot train models on device
- Requires separate training infrastructure
- Model updates require rebuild/redeploy
- Better prediction quality

## ADR-004: Coroutines Over HandlerThread

**Decision**: Replace HandlerThread pattern with Kotlin Coroutines + Flow for async predictions.

**Rationale**:
- Modern Kotlin async/await patterns
- Structured concurrency vs manual thread lifecycle
- Flow streams for reactive prediction updates
- Better cancellation and error handling
- Reduced boilerplate code

**Consequences**:
- All prediction code uses suspend functions
- Lifecycle-aware coroutine scopes
- Better integration with Android lifecycle

## ADR-005: Neural Feature Learning
**Status**: ⛔ SUPERSEDED by [ADR-011](#adr-011-remove-the-neural-swipe-engine) (2026-08-18). Kept verbatim as the record of the decision that was made and why.


**Decision**: Replace manual feature engineering with neural network automatic feature learning.

**Rationale**:
- Transformer models learn optimal features from data
- Reduced from 40+ manual features to 6 input features (x, y, t, pressure, key, finger)
- Model discovers complex patterns humans might miss
- Less code to maintain
- Better generalization to unseen data

**Consequences**:
- Feature engineering complexity moved into neural network
- Harder to debug "why" predictions work
- Requires quality training data

## ADR-006: Gaussian Key Model Replacement
**Status**: ⛔ SUPERSEDED by [ADR-011](#adr-011-remove-the-neural-swipe-engine) (2026-08-18). Kept verbatim as the record of the decision that was made and why.


**Decision**: Replace Gaussian key modeling with neural network spatial encoding.

**Rationale**:
- Neural network learns optimal spatial representations
- Transformer attention mechanism handles spatial relationships
- Simpler architecture vs manual probability calculations

**Consequences**:
- Spatial encoding handled by neural network
- Less interpretable than Gaussian distributions

## ADR-007: Component Initialization Order Dependencies

**Decision**: Enforce strict initialization order for components with dependencies in CleverKeysService.

**Implementation**:
```kotlin
// CORRECT ORDER
// Step 1: Initialize dependencies first
languageDetector = LanguageDetector()
userAdaptationManager = UserAdaptationManager(context)

// Step 2: Initialize components that depend on them
wordPredictor = initializeWordPredictor(
    languageDetector = languageDetector,
    userAdaptationManager = userAdaptationManager
)
```

**Rationale**:
- Prevents null reference bugs at initialization
- Makes dependency relationships explicit
- Enables proper feature integration
- Follows dependency injection principles

**Best Practice**:
When adding new components:
1. Identify all dependencies
2. Place initialization AFTER all dependencies
3. Pass dependencies explicitly via constructor/method parameters
4. Document dependency chain in comments if complex

## ADR-008: Dual Prediction Pipeline Symmetry

**Decision**: Both SuggestionHandler (typing path) and InputCoordinator (cursor sync path) maintain independent prediction executors that post to the same SuggestionBar. Both must implement identical contraction/exact_add/capitalization logic.

**Rationale**:
- `onUpdateSelection()` fires asynchronously after `commitText()` — triggers InputCoordinator ~100ms after SuggestionHandler
- If pipelines produce different results, the cursor sync path overwrites the typing path, causing visible flicker
- Suppression-based approaches (flags) failed: cross-app leaking, incomplete coverage, timing brittleness

**Implementation**:
- Both paths: paired contractions, non-paired contractions, I-word capitalization, exact_add, prefix guard
- SuggestionBar deduplicates identical content to prevent redundant re-renders
- `contextTracker.clearAll()` in `onFinishInputView()` prevents state from leaking across apps

**Consequences**:
- Features added to one pipeline MUST be mirrored in the other
- Last pipeline to post wins — but with symmetric output, this is invisible to users
- Source-scanning JVM tests verify both pipelines contain required logic

## ADR-009: Paired Contraction Prefix Guard

**Decision**: Minimum 3 characters required for paired contraction injection in both prediction pipelines.

**Rationale**:
- `contraction_pairings.json` has 19 single-character entries (a→a's, t→t's, etc.) — all possessive forms
- These inject at score +500 above top prediction, corrupting frequency ranking
- Typing "t" showed "t's" above "the" — frequency ranking was bypassed

**Implementation**:
```kotlin
val pairedVariants = if (prefix.length >= 3) contractionManager.getPairedContractions(prefix) else null
```

**Consequences**:
- Single/double-char prefixes no longer get possessive form injection
- Real contraction bases (its, hes, wed, well) at 3+ chars still work correctly
- Non-paired contractions (dont → don't) are unaffected — they transform predictions, not inject new ones

## ADR-010: Context Tracker Clearance on Input Finish

**Decision**: Call `contextTracker.clearAll()` in `onFinishInputView()` to reset all prediction state when switching apps or text fields.

**Rationale**:
- Without clearing, text typed in app A leaked into predictions for app B
- Example: type "t" in app A, switch to app B, type "h" → got "th" predictions instead of "h"
- The `expectingSelectionUpdate` flag approach persisted across field switches, suppressing legitimate cursor syncs

**Implementation**: Single line in `CleverKeysService.onFinishInputView()`:
```kotlin
_contextTracker.clearAll()
```

**Consequences**:
- Clean prediction state for every new input field
- No cross-app text contamination
- Context words from previous field are lost (acceptable — new field means new context)

## Summary

## ADR-011: Remove the Neural Swipe Engine

**Date**: 2026-08-18
**Status**: ✅ Accepted — supersedes ADR-001, ADR-002, ADR-005, ADR-006.

**Decision**: Delete the ONNX transformer swipe decoder, its vocabulary stack, its settings
screen and its bundled models. Swipe typing is served by the CTC trie-beam engine
(`swipe/ctc/`) on the languages and layouts it is validated for, and by the geometric
SHARK2-style decoder (`swipe/geometric/`) everywhere else. `swipe_engine_mode` becomes
`{ctc, geometric}` with `ctc` as the default; stored `neural`/`hybrid` values resolve to
`ctc`.

**Rationale**:
- **It lost on its own measured ground.** 74.62 vs CTC's 89.31 top-1 on test-2400 — the
  full comparison is `docs/history/audits/2026-08-17-neural-vs-ctc-parity.md`, which is the record of
  exactly what this removal gives up and is preserved verbatim for that reason.
- **It only worked on QWERTY.** The transformer was trained on QWERTY-US key positions, so
  every other layout was routed to "no swipe engine at all" in the default mode — the
  long-standing issue #9. CTC takes key geometry as a model *input* and geometric decodes
  shape against any layout, so no configuration can now leave a layout without swipe typing.
- **It was expensive.** 10.3 MB of bundled `.onnx` (plus ~21 MB of prefix-boost tries that
  only it read) and ~30–45 MB of Java heap whenever it was built — a measured contributor to
  the 2026-08-12..17 startup `OutOfMemoryError` on a 256 MB-growth-limit device.
- **Two engines is already one more than is comfortable.** Keeping a third, worse engine
  alive meant every pipeline change had to be reasoned about three times.

**Consequences**:
- Release APK (arm64-v8a) drops ~26 MB.
- Main-dictionary fuzzy rescue (`OptimizedVocabulary`'s sloppy-swipe recovery) is gone. This
  is the single largest behavioural deletion and is **deferred, not silently dropped**: the
  algorithm is model-independent and can be re-implemented as a post-beam rescue inside
  `CtcEngineAdapter`, which already holds the merged lexicon and the raw beam.
- Secondary-language swipe blending is gone (it was already absent in ctc mode). Tap-path
  blending is unaffected.
- it/pt/sv and the langpack languages move from neural to geometric on QWERTY. The
  per-language delta is unmeasured; the honest statement is "likely a few points down until
  CTC serves them" (English proxy on the same corpus: 74.62 neural vs 67.50 geometric).
- ~25 `neural_*` preferences are deprecated (still filtered out of backup imports, never
  written). The Neural Settings screen and `SwipeCalibrationActivity` are gone.
- ADR-003 (external ML training) still holds: CTC is also trained externally and exported to
  ONNX. ADR-004 and ADR-007..010 are unaffected.

**Reversal cost**: high but bounded — the engine is recoverable from git history, and the
models are republishable from CleverKeys-ML. Nothing about the surviving pipeline forecloses
adding a third engine later; `SwipeEngineRouter` is still the single insertion point.

**References**: `docs/plans/2026-08-18-neural-engine-removal.md` (the implementation plan),
`docs/history/audits/2026-08-17-neural-vs-ctc-parity.md` (the evidence),
`docs/history/neural-engine/` (the archived specs).


**Philosophy** (as amended by ADR-011):
- **Measured ML**: an engine ships because it wins a head-to-head, not because of its class
- **Modern Kotlin**: Coroutines > HandlerThread callbacks
- **No dead ends**: every layout and language must reach a working decoder
- **External Training**: GPU acceleration > device CPU "training"

**Trade-offs Accepted**:
- External training dependency (Python/PyTorch required)
- Cannot train on-device (by design)
- Less interpretable models (neural networks are black boxes)
- Requires quality training datasets
- Model updates need rebuild/redeploy

**Benefits Gained**:
- Superior prediction accuracy
- Modern codebase with Kotlin best practices
- Reduced code complexity
- Better async patterns with coroutines
- Scalable to larger models with external training
