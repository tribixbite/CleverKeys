# ARC-019: CTC vs geometric — same-inputs head-to-head on the LOCAL combined corpus

**Date:** 2026-08-28 · **HEAD:** `dfe3b7df` (+ the measurement harness committed with this doc)
· **Harness:** `CtcVsGeoLocalCorpusTest` (pure JVM, real ONNX via `extractOrtNative`,
EP=xnnpack(2)) · **Run:** `sh gradlew runPureTests -PtestClass=swipe.geometric.CtcVsGeoLocalCorpusTest -PgeoFull=true`

## Why this run existed

The LOCAL combined corpus (the deleted neural model's held-out set) was the one corpus where
the geometric engine **beat** neural (55.2 vs ~53.7 top-1, full 8.6k rows). CTC — the shipping
default — had never been contested on it, nor on any robustness tier (archived parity audit
§3.3(b)/§5.3). This run closes both gaps, plus the UT-5 contraction-rank deferral.

## 1. Same-inputs head-to-head (real traces)

Subset: rows with monotone timestamps (4,557 of the pool; ~47% of the corpus stores a
non-timestamp third column that CTC's 60 Hz resampler cannot use) ∩ the 98k geo dictionary
= **4,526 identical traces through both engines**.

| engine | top-1 | top-3 | top-5 |
|---|---|---|---|
| **CTC (shipping default)** | **90.7%** | **95.4%** | **96.1%** |
| geometric (shipped config) | 63.0% | 75.2% | 78.3% |

Top-1 agreement: both 2,781 · **ctc-only 1,325** · geo-only 70 · neither 350.

**Verdict: the last accuracy argument for geometric-on-Latin is gone.** CTC leads by 27.7 pts
top-1 on the corpus that used to be geometric's best showing; geometric uniquely recovers only
70/4,526 (1.5%) of traces. (Geometric's 63.0 here vs the historical 55.2 is the subset: the
timestamp-usable rows are the cleaner half of the corpus.) Geometric's remaining role is
exactly what the router assigns it: non-Latin scripts, letter-incomplete layouts, and the
dead-ONNX fallback — not Latin accuracy.

## 2. CTC on the synthetic degradation tiers (150 words × 2 seeds, en/QWERTY)

| tier | top-1 | top-3 | top-5 |
|---|---|---|---|
| CLEAN | 67.0% | 82.7% | 87.0% |
| TYPICAL | 69.3% | 83.7% | 88.3% |
| SLOPPY | 58.0% | 74.0% | 78.7% |

Geo reference (same synthesizer, its own harness): TYPICAL 83.4 / SLOPPY 63.8 top-1.

**Read the drop, not the level.** The absolute CTC numbers are depressed ~20 pts below its
real-corpus 90.7 — and CLEAN < TYPICAL is impossible for a real robustness curve — because the
synthesizer's 8 ms-step synthetic timing feeds CTC's 60 Hz resampler with motion statistics the
encoder never saw in training (geometric ignores timestamps, so its numbers don't carry this
artifact). Within that caveat: TYPICAL→SLOPPY costs CTC 11.3 pts vs geometric's 19.6 — CTC
degrades **more gracefully** under noise. A trustworthy absolute CTC robustness number would
need real-timing degraded traces, which no current corpus provides; not worth building given
the real-corpus margin above.

## 3. UT-5 (v1.5.0 deferral): contraction-alias ranks — CLOSED

All 12 real `dont` traces decode at **rank 0** (`[dont, dint, done, …]`). Synthetic TYPICAL,
3 seeds each: `dont`/`doesnt`/`cant`/`wont`/`isnt`/`didnt`/`ive` — **rank 0 in every seed**
(`doesnt` → `[doesnt, spent, forest, …]`); `im` 0/1/0. `id` ranks 2–4 behind `is/if/of`, which
is frequency-appropriate for a 2-letter trace, not the UT-5 defect. The slate surface is the
a–z alias form; the apostrophe display (`don't`) is applied by the adapter overlay downstream.
**The contraction rework fixed UT-5's ranking complaint outright.**

(UT-7 — sentence-start `I'd` on the TAP path — is measured separately by the instrumented
`ContractionSentenceStartMeasureTest` in the ew-cli run; see its logcat `UT7Measure` lines.)

## Provenance

- Corpus: `~/.cache/cleverkeys-test/combined_english_swipes.jsonl.gz` (local-only, never
  committed; the neural model's held-out set, proshian format, 360×215 px canvas).
- Both engines decoded the byte-identical rows; geometric via
  `GeoLayoutFixtures.loadShipped("latn_qwerty_us")` + shipped `GeometricEngineConfig()`,
  CTC via `CtcReplayEngine` (shipped model, EN_JSON strip-loaded trie, alias keys injected,
  fuzzy rescue below the beam).
- Assertions in the harness are wiring-sanity floors only (top-1 ≥ 0.30 both engines); the
  numbers above are the deliverable and any future run should be appended here with its HEAD.
