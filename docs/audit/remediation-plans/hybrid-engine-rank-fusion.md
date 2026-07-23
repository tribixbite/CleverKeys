# Hybrid Engine Rank Fusion — Phase-2 Proposal (WP9 follow-on)

**Date:** 2026-07-23
**Status:** PROPOSAL — needs user sign-off + its own oracle round before any wiring
**Prereqs (all landed):** R-1 unification (single SH pipeline), steps 7-9 (SwipeEngineRouter,
GeometricEngineAdapter, `swipe_engine_mode`), ContractionOverlay (display parity), the
2026-07-23 multilingual audit fixes.

## What "Hybrid" means today vs. what this proposes

Today's **Hybrid** mode is layout-routed: ONE engine owns each swipe (neural on
QWERTY-Latin, geometric elsewhere). This proposal adds **per-swipe fusion** on layouts
where both engines can decode (QWERTY-Latin): both engines run, a rank-based merger fuses
their candidate lists, and the fused list rides the existing SH seam.

## Why fusion should win (measured, not hoped)

From the production-equivalent head-to-head on the 8.5k neural-testset corpus
(`docs/specs/geometric-swipe-engine.md` § Neural head-to-head):

| Stratum | Winner | Margin |
|---|---|---|
| ≤3-letter words, top-1 | **neural** | +21.6 pts |
| 4+ letter words, top-1 and depth (top-3/5) | **geometric** | consistent win |
| Overall top-1 | geometric | +1.5 pts |

The engines have COMPLEMENTARY error profiles — a length-aware fusion should dominate
both, not average them. That is the phase-2 hypothesis to validate offline first.

## Architecture

```
Keyboard2View.onSwipeEnd
  └► IC.handleSwipeTyping ── SwipeEngineRouter.route(layout, mode)
        ├─ NEURAL      → AsyncPredictionHandler (today, unchanged)
        ├─ GEOMETRIC   → GeometricEngineAdapter (today, unchanged)
        └─ FUSED (new; QWERTY + Hybrid mode + swipe_engine_fusion pref)
              ├─ geo decode  (adapter thread, ~3 ms warm; result HELD)
              ├─ neural async (~100-300 ms, unchanged)
              └─ on neural arrival → RankFusion.merge(geoResult, neuralResult)
                                        └► IC.handlePredictionResults → SH seam (UNCHANGED)
```

- **`swipe/RankFusion.kt`** (pure JVM, unit-tested like ContractionOverlay): scores are
  NUMERICALLY INCOMPARABLE across engines (geo = engine-relative softmax×1000; spec OQ-5),
  so fusion is RANK-based:
  - v1 merger: **Reciprocal Rank Fusion** `score(w) = Σ_e w_e / (k + rank_e(w))` (k=60),
    with a length-conditioned weight from the measured data: when the top candidates are
    ≤3 letters (the neural-dominant stratum), `w_neural` ≫ `w_geo`; otherwise balanced.
    Case-insensitive dedupe keeps each word's best rank. Contraction/possessive display is
    already applied per-engine BEFORE fusion (geo: ContractionOverlay; neural: vocab layer)
    so the merger sees final display forms.
  - Output scores are fused-rank-derived ints (bar display only — never re-compared to
    engine thresholds).
- **Timing model — wait-both (v1)**: the fused post happens when neural lands, so
  perceived latency is IDENTICAL to today (geo's 3 ms hides inside neural's window).
  A progressive variant (post geo bar instantly, merge on neural arrival) is explicitly
  DEFERRED: the bar post and the auto-insert commit are coupled in the SH seam, so
  progressive display either delays the commit relative to the bar or risks visible
  editor churn via the replace-tracking machinery. Separate experiment, separate pref.
- **Resilience win (free)**: neural timeout/failure → fall back to the already-computed
  geo result instead of an empty bar (today a neural error clears suggestions). Geo
  failure → neural-only (today's behavior). This alone may justify the wiring.
- **Gating**: new Boolean pref `swipe_engine_fusion` (default OFF, classified in
  SETTINGS_DEFAULTS), honored only when `swipe_engine_mode == "hybrid"` on QWERTY-Latin.
  Modes Neural/Geometric never fuse (user chose a single engine). No UI until validated;
  then a switch inside Full Geometric Settings or the Swipe Typing section.

## Validation plan (offline BEFORE wiring — cheap, the harnesses exist)

1. Implement `RankFusion` pure + JVM table tests (determinism, dedupe, weight gating).
2. **Corpus replay**: extend the existing replay harness (GeoRealCorpusReplayTest +
   the neural head-to-head decode cache at `~/.cache/cleverkeys-test/…jsonl`) to compute
   fused top-1/top-3 on the 8.5k corpus, stratified by word length. **Go/no-go gate:
   fused ≥ max(neural, geo) on BOTH short and 4+ strata** (dominance, not averaging).
   Tune (k, w_neural(len)) against this before any device wiring.
3. Wire behind the pref; oracle round: fusion-off byte-identical (flag pins), fusion-on
   short-word neural-top pin, long-word geo-influence pin, neural-timeout → geo fallback
   pin, merge perf gate (<1 ms), full-suite green.

## Effort

RankFusion + JVM tests + replay validation ≈ 1 day; wiring + oracle round ≈ 1-1.5 days.
No SH/commit changes at all — the single-pipeline unification did the hard part already.

## Explicit non-goals

- No cross-engine score comparison, ever (rank domain only).
- No progressive/two-phase bar in v1 (deferred experiment).
- No fusion on non-QWERTY (geo is alone there; nothing to fuse until a second engine
  supports those layouts).
