---
title: Swipe Typing - Technical Specification
description: CTC and geometric routing, canonical display, and commit boundaries
user_guide: /wiki/typing/swipe-typing/
status: implemented
version: v2.0.0 development
---

# Swipe Typing Technical Specification

## Overview

Swipe typing routes each completed gesture to one of two decode engines — the CTC trie-beam
engine (default) or the geometric (SHARK2-style) engine — selected by the `swipe_engine_mode`
preference plus the active layout and language. Both engines feed the same downstream
suggestion pipeline.

> The ONNX transformer ("neural") engine that used to be the default was removed on
> 2026-08-18. CTC replaced it on measured accuracy (89.31 vs 74.62 top-1 on test-2400,
> `docs/history/audits/2026-08-17-neural-vs-ctc-parity.md`) while geometric covers every cell CTC does
> not serve. The archived spec is `docs/history/neural-engine/neural-prediction-spec.md`.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| Engine Router | `swipe/SwipeEngineRouter.kt` | Mode + layout → engine selection (`Mode.CTC/GEOMETRIC`) |
| CTC Adapter | `swipe/CtcEngineAdapter.kt` | CTC engine boundary: layout/trie/session memos, contraction display, warm-up |
| CTC Core | `swipe/ctc/` (`CtcBeamDecoder.kt`, `CtcFeaturizer.kt`, `CtcLexiconTrie.kt`, ...) | Pure-JVM CTC Viterbi trie beam over ONNX emissions |
| ONNX Session Loader | `onnx/ModelLoader.kt` | Builds the CTC encoder's OrtSession (XNNPACK-first) |
| Geometric Adapter | `swipe/GeometricEngineAdapter.kt` | Geometric engine boundary |
| Geometric Core | `swipe/geometric/` | SHARK2-style shape decoder |
| Keyboard Grid | `KeyboardGrid.kt` | Map coordinates to keys |

## Architecture

```
Touch Events (Pointers.kt)
    ↓
InputCoordinator.handleSwipeTyping
    ↓ SwipeEngineRouter.route(layout, mode)
    ├─ CTC       → CtcEngineAdapter (runtime language, pack, model, and alphabet gates)
    └─ GEOMETRIC → GeometricEngineAdapter
    ↓ (top-k candidates, engine-relative scores)
SuggestionHandler.handleSwipePredictionResults → UI
```

The router always selects an engine. Successful predictions still require a usable
dictionary, letter geometry, and a valid trace.

## Engine Routing (`swipe_engine_mode`)

The layout-only router consults `CtcScriptSupport`. Runtime dispatch additionally
checks active-language support, primary-key alphabet coverage, dictionary availability,
and the encoder. Failure or unavailable CTC prerequisites fall through to geometric.

| Mode | Complete registered CTC script/language/model/dictionary | Other layouts or unavailable CTC prerequisites |
|------|---------------------------------------------------------|-----------------------------------------------|
| `ctc` (default) | CTC | Geometric |
| `geometric` | Geometric | Geometric |

`CtcLanguageSupport` contains the seven bundled Latin languages en/fr/de/es/it/pt/sv
and pack-backed non-Latin rows. `CtcScriptSupport` registers ru, el, uk, bg, mk, and he;
their encoders arrive through language packs and must match the registry's approved
digests. Imported Latin packs can also qualify through `CtcImportedPackSupport`,
which measures a–z projection eligibility. Support is therefore a per-device answer,
not a fixed seven-language list.

A layout must expose every active-language emission character as a primary/centre
key; directional subkeys do not satisfy this geometry requirement. The adapter checks
both compact and numbered layout schemas. A missing script, incomplete alphabet,
missing pack/model, or failed encoder routes to geometric.

Evidence tiers remain separate: it/pt/sv use scale-transferred provisional evidence;
ru's human probe is validation-only; el/uk/bg/mk/he have synthesis-holdout evidence.
Do not label those synthetic results as measured human-language accuracy. Bangla is
not a registered CTC script; existing tap layouts do not establish swipe support.

`Mode.fromPref` maps any unrecognised stored value — including the removed `"neural"` and
`"hybrid"` — onto `CTC`, so a pre-v1.6.0 backup imports without error.

One engine owns each swipe end-to-end; scores are engine-relative and never compared across
engines. Suggestion provenance tags the engine that actually decoded
(`SuggestionProvenance.forRoutedEngine`). The CTC engine maps contraction aliases to display
forms ("dont" → "don't") inside its adapter before the shared pipeline. Full CTC engine
internals: `docs/specs/ctc-swipe-engine.md` (engineering spec).

## Accepted insertion

`SuggestionHandler.onSuggestionSelected` returns the actual inserted spelling only
when the editor acknowledges `commitText`. False/throwing/missing connections clear
failed ownership and pending learning. Manual adaptation is recorded after acceptance.
The swipe caller preserves its slate on failure, resets transient ML data and does not
fall back to an offered prediction for word/source/ML/correction tracking or a success
haptic. A preceding typed separator must be accepted before its word is completed.

An acknowledgement is separate from exact text readback; legacy replacement deletion
is not atomic. Verified suffix ownership and ordered phrase commits need additional
session/selection/learning receipts. See the internal
[cursor/commit spec](https://github.com/tribixbite/CleverKeys/blob/main/docs/specs/cursor-aware-predictions.md).

## Gesture Sampling Robustness

There is **no minimum-speed gate** on swipe typing — a slow swipe is not rejected for being slow. Word activation depends on registering ≥2 keys plus `swipe_min_distance` of path, which a slow-but-complete swipe satisfies just like a fast one (path length is bounded by key geometry, not by speed).

`ImprovedSwipeGestureRecognizer.addPoint` does, however, drop samples whose inter-sample gap exceeds `MAX_POINT_INTERVAL_MS` (500 ms). To keep a **mid-gesture pause** (a deliberate swiper holding still to aim) from permanently stalling the gesture, the recognizer re-anchors `_lastPointTime` to the resume timestamp when a long gap is seen, then resumes on the next sample. Without this re-anchor a single >500 ms gap left `_lastPointTime` stale, so every later sample's delta grew larger and the remainder of the swipe was dropped — the second key never registered and no word was produced.

## Engine Configuration

From `Config.kt`:

| Setting | Key | Default | Range | Source |
|---------|-----|---------|-------|--------|
| **Prediction Engine** | `swipe_engine_mode` | `"ctc"` | `ctc` / `geometric` (case-canonicalized at read; anything else resolves to `ctc`) | `Config.kt` (SWIPE_ENGINE_MODE), `swipe/SwipeEngineRouter.kt` |
| **CTC Beam Width** | `ctc_beam_width` | 100 | 10-300 (clamped at load and per decode) | `Config.kt` (CTC_BEAM_WIDTH), `CtcSettingsActivity.kt` |
| **ONNX Threads** | `onnx_xnnpack_threads` | 2 | 1-8 | `Config.kt` (ONNX_XNNPACK_THREADS), `CtcSettingsActivity.kt` |
| **ONNX Model** | bundled asset | — | — | `src/main/assets/models/ctc_swipe_encoder.onnx` (2.91 MB) |

The CTC scoring constants (gamma/lambda/beta/prune) are `CtcScoringParams.tunedV2`, fitted
offline against the shipped lexicon, and are deliberately not user-tunable.

Geometric engine knobs live in `GeometricSettingsActivity` (`geo_max_results`,
`geo_frequency_weight`, `geo_endpoint_inset_kw`).

## Related Specifications

- [CTC Swipe Engine](https://github.com/tribixbite/CleverKeys/blob/main/docs/specs/ctc-swipe-engine.md) - Deeper architectural reference for the shipping decoder (trie beam, lexicon merge, per-language λ)
- [Gesture System Overview](../gestures/gesture-system-overview-spec.md) - Touch event routing and `hasLeftStartingKey` gatekeeper
- [Autocorrect Specification](autocorrect-spec.md)

## October 6 boundary coverage

Native custom-gesture cold-start and SmartAutoSpace regressions now cover model-free
startup with swipe disabled, literal curly apostrophes, manual spaces, and replacement
of a selected range. Reported noncollapsed selections cannot reclaim a prior space;
editors without selection data retain the legacy text/ownership fallback. Results and
actual full-suite counts live in [testing strategy](https://github.com/tribixbite/CleverKeys/blob/main/docs/specs/testing-strategy.md).

Continuous multiword swipe and explicit suffix transactions are implemented below
and in the per-key specification. Rejected/throwing `commitText` does not create new
swipe ownership, learning or correction/ML labels; the accepted-commit prerequisite
is complete.
Reported `ad`/`wet` accuracy remains unresolved; frozen geometric/timing heuristics
were rejected, and fresh writer/session-separated calibration evidence is required.

## Continuous phrase pipeline (development)

`continuous_swipe_enabled` defaults false and is included in Config, immutable
pointer-down ConfigSnapshot, Compose settings/search and typed backup defaults.
`Pointers` starts the view-side session, and cancellation/multitouch invalidate it.
`ContinuousSwipe` stores immutable letter samples separately from recognizer/trail
state. A physical-space interior dwell of 280 ms establishes a boundary; the timer
runs independently of MOVE/noise filtering. Space excursions are excluded, the next
segment starts on its first letter, and final lift emits no empty segment. Bounds:
2,048 samples per segment, 32 segments per gesture, 32 queued pending segments.
Overflow aborts instead of silently truncating a phrase.

`ContinuousSwipeQueue` dispatches one request at a time. InputCoordinator threads
`SwipeCommitControl` through routed CTC/geometric decoding, including fallback. A
result is guarded before the existing shared SuggestionHandler commit, then the queue
observes accepted-word and editor readback. The next dispatch happens only after that
callback, with the updated prediction context and a separate ML segment capture.
An explicit boundary supplies one verified separator if the normal commit lacked it.
English one-key a/I segments are explicit observed-key input, not decoder-error guesses.

The session captures editor/field identity, collapsed selection and prefix/suffix,
layout identity, Config snapshot generation, language, secondary language, engine and
initial shift/caps state. Every pending result revalidates these; cancellation leaves
accepted text in place. The feature refuses password/unreadable/selected or inline
editors. The ordinary single-word path remains authoritative when no boundary occurred.
Native `ContinuousSwipeTest` pins mechanics; synthetic evidence does not establish
human word accuracy or device latency. Final run evidence is kept in the testing strategy.
