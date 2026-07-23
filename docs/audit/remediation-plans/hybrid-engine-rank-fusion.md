# Hybrid Engine Rank Fusion — Phase-2 Proposal (WP9 follow-on)

**Date:** 2026-07-23
**Status:** PROPOSAL (amended 2026-07-23 after adversarial review vs FUTO, arXiv 2606.25247)
— needs user sign-off + its own oracle round before any wiring
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

## Strategic alternative (read before committing to fusion)

**The measured complementarity is a property of OUR neural model, not of
neural-vs-geometric in general.** FUTO's layout-agnostic model (arXiv 2606.25247, Table 2)
beats SHARK2 on EVERY stratum: 92.94 vs 80.05 top-1 QWERTY, 83.11 vs 59.31 zero-shot
ЙЦУКЕН, 96.84 vs 92.18 ClearFlow — there is no stratum where a geometric decoder wins
against a competent neural decode. Our fusion opportunity exists because our production
neural scores ~54% top-1 on the local corpus where theirs scores ~93%. Fusion is therefore
a NEAR-TERM local optimization between two engines both far below demonstrated SOTA. The
higher-ceiling path is evaluating the MIT-licensed FUTO model (635K params, 2.5 MB fp16,
layout-agnostic — it would obsolete the layout routing entirely) as a future engine or
replacement, with their 1M-swipe MIT dataset available for fine-tuning. Fusion (~2.5 days)
can still be the right near-term move — but it must be chosen with this alternative on the
table, not against a strawman ceiling.

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
    with a SMOOTH length-conditioned weight `w_neural(L) = σ(a·(L₀ − L))` (logistic, tuned
    in replay) rather than a hard ≤3-letter gate — FUTO's length handling is continuous
    (`L^γ`, `β·L` in their Eq. 3 rescore; Table 12 shows these terms carry large deltas),
    and a cliff at L=4 would sit exactly on our biggest strata boundary.
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

1. Implement `RankFusion` pure + JVM table tests (determinism, dedupe, weight curve).
2. **Oracle-union ceiling FIRST** (fusion's mathematical maximum): on each corpus and
   stratum, report union@1/@3 — a trace counts if EITHER engine has the word in top-k.
   This directly measures error correlation: FUTO §4.3 shows colinear-trigram blindness
   ("stream"/"steam") is GEOMETRIC in origin and hits both engines on the SAME traces.
   **If union headroom over max(engine) is < ~2 pts on a stratum, fusion is a no-go there.**
3. **Two corpora, both must pass**: (a) the local 8.5k corpus (KNOWN BIAS: corrupt
   timestamps → position-only features systematically underrate neural, which FUTO shows
   benefits from 8D kinematic features — weights tuned here over-weight geo); (b) the
   FUTO swipe QWERTY-EN slice (MIT, clean timestamps, fetch scripts exist) where neural
   is unhandicapped. **Go/no-go gate: fused ≥ max(neural, geo) on BOTH length strata on
   BOTH corpora.** Evaluation hygiene copied from FUTO §4.1: session-aware splits (verify
   our corpus is session-stratified), macro accuracy restricted to words with ≥5 examples
   alongside micro, top-10 reported.
4. CONSIDER (before freezing merger params): beam-width sensitivity — neural beam 6/8/16
   and geo maxResults 10/15 in replay; a word absent from both thin candidate lists cannot
   be fused, so input recall may matter more than merger constants.
5. Wire behind the pref; oracle round: fusion-off byte-identical (flag pins), fusion-on
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
- Fusion weights are EN-QWERTY-ONLY and must be RE-TUNED, never reused, for any other
  language: FUTO Table 12 shows scoring-term importance INVERTS across languages (the
  frequency prior carries English; on Russian it falls below raw CTC and the length
  exponent dominates).

## Known risks (from the FUTO review)

- **Correlated blindness caps headroom**: colinear-trigram traces (middle letter
  contributes no visible feature — FUTO §4.3, Fig. 6, "stream"/"steam") defeat BOTH
  engines on the same swipes; the union-ceiling gate exists to catch this before wiring.
- **Thin candidate lists bound fusion**: production neural runs beam 6 (top-5 66.7%);
  FUTO evaluates at beam width 100. See validation step 4.
