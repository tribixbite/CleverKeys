---
title: Swipe Typing - Technical Specification
user_guide: ../../typing/swipe-typing.md
status: implemented
version: v1.2.7
---

# Swipe Typing Technical Specification

## Overview

Swipe typing routes each completed gesture to one of two decode engines — the CTC trie-beam
engine (default) or the geometric (SHARK2-style) engine — selected by the `swipe_engine_mode`
preference plus the active layout and language. Both engines feed the same downstream
suggestion pipeline.

> The ONNX transformer ("neural") engine that used to be the default was removed on
> 2026-08-18. CTC replaced it on measured accuracy (89.31 vs 74.62 top-1 on test-2400,
> `docs/audit/2026-08-17-neural-vs-ctc-parity.md`) while geometric covers every cell CTC does
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
    ├─ CTC       → CtcEngineAdapter (en/fr/de/es/it/pt/sv; other languages fall through to geometric)
    └─ GEOMETRIC → GeometricEngineAdapter
    ↓ (top-k candidates, engine-relative scores)
SuggestionHandler.handleSwipePredictionResults → UI
```

The router is TOTAL: every layout resolves to an engine, so no configuration can leave a
layout without swipe typing.

## Engine Routing (`swipe_engine_mode`)

The router (`swipe/SwipeEngineRouter.kt`) is layout-only; the `ctc` mode's language gate lives
in `InputCoordinator.performCtcSwipeTyping`, which reads the active dictionary language before
dispatch and falls through to the geometric engine for anything CTC does not serve:

| Mode | Latin layout + served language | Latin layout + other language | Non-Latin layout |
|------|--------------------------------|-------------------------------|------------------|
| `ctc` (default) | CTC | GEOMETRIC | GEOMETRIC |
| `geometric` | GEOMETRIC | GEOMETRIC | GEOMETRIC |

Served languages are `swipe/ctc/CtcLanguageSupport.SUPPORTED` — **seven**: en, fr, de, es plus
it, pt, sv. The last three are in `CtcLanguageSupport.PROVISIONAL`: they were enabled on
2026-08-18 on scale-transferred evidence (they read the same CKDT `.bin` frequency scale the
λ sweep fitted, and the encoder never sees a language) and have **no per-language accuracy
bar**, because no swipe corpus exists for them. Their numbers are val-tier at best and must
never be quoted beside the test-validated four. A Latin layout
missing an a–z letter cannot build a `CtcLayout` and also falls through to geometric, checked
at dispatch time by `CtcEngineAdapter.supportsLayout`.

`Mode.fromPref` maps any unrecognised stored value — including the removed `"neural"` and
`"hybrid"` — onto `CTC`, so a pre-v1.6.0 backup imports without error.

One engine owns each swipe end-to-end; scores are engine-relative and never compared across
engines. Suggestion provenance tags the engine that actually decoded
(`SuggestionProvenance.forRoutedEngine`). The CTC engine maps contraction aliases to display
forms ("dont" → "don't") inside its adapter before the shared pipeline. Full CTC engine
internals: `docs/specs/ctc-swipe-engine.md` (engineering spec).

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

- [CTC Swipe Engine](../../../specs/ctc-swipe-engine.md) - Deeper architectural reference for the shipping decoder (trie beam, lexicon merge, per-language λ)
- [Gesture System Overview](../gestures/gesture-system-overview-spec.md) - Touch event routing and `hasLeftStartingKey` gatekeeper
- [Autocorrect Specification](autocorrect-spec.md)
