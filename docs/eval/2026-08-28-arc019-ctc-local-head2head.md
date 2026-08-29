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

## 4. UT-7 (v1.5.0 deferral): sentence-start `I'd`, tap path — MEASURED, root cause isolated

Instrumented `ContractionSentenceStartMeasureTest` (real SuggestionHandler + SuggestionBar
wiring, ew-cli run `1a851d40`, Pixel7/API34). Ordering is position-independent by construction
(no sentence-start signal exists in main), so one measurement covers both positions:

| typed | user-visible bar | verdict |
|---|---|---|
| `im` | `[I'm, image, impact, …]` | contraction **leads** (literal absent) |
| `ill` | `[I'll, I'll, ill, illegal, …]` | contraction **leads** — but note the **duplicate I'll** at ranks 0+1 |
| `id` | `[id, idea, ideas, ideal, idiot]` | **`i'd` is ABSENT from the bar entirely** |

**UT-7's complaint was never a ranking problem.** Two of the three I-contractions already lead.
The real defect is isolated to `id`: it receives **no contraction injection at all** — neither
REPLACE (correct: "id" is a real word) nor PAIRED (the its→it's pattern that should apply).
The fix is a contraction-DATA decision (add `id → i'd` as a paired contraction, subject to the
four guards in `.claude/skills/contraction-system.md`), not a ranking signal. A sentence-start
boost would fix nothing here.

Secondary finding: the doubled `I'll` at ranks 0 and 1 for typed `ill` — two injection paths
producing the same surface without dedup. Small, user-visible, worth its own look.

A first revision of this measurement asserted at the WordPredictor layer and failed usefully:
the apostrophe surfaces are produced by SuggestionHandler's injection layer, not the predictor
(`im`/`ill`/`id` are real dictionary words, so the alias-skip guard excludes their alias keys
from the prefix index). The committed test measures the user-visible bar.

### Addendum 2026-08-29 — both defects fixed, and BOTH root-cause guesses above were wrong

The measurement stands; the two diagnoses attached to it did not survive contact with the code.
Recording the miss, because both errors have the same shape — *inferring a cause from a symptom
without reading the layer that produces it*:

1. **"needs a paired `id → i'd` contraction-data decision"** — there was nothing to decide.
   `contraction_pairings.json` has carried `id → [{"contraction": "i'd", "frequency": 200}]` all
   along, and `contractions.bin` derives the same pair independently. The absence was a CODE
   guard: `SuggestionHandler` injected paired variants only for `partial.length >= 3`, and `id`
   is two letters. So the fix touched no data — no regeneration, no collision-sidecar rebuild, no
   new REPLACE key, and none of the four guards had anything to say about it. The floor now lives
   in `ContractionInjectionPolicy`, which admits a first-person contraction (`i'…`, excluding the
   letter-possessive `i's`) at two characters and nothing else; verified against the full shipped
   table, **exactly one** base changes behaviour. The two-letter pronoun bases (`it`, `we`, `he`,
   `do`) stay blocked deliberately — injecting three or four variants ahead of those very
   high-frequency literals is a ranking change that would need its own measurement.

2. **"two injection paths producing the same surface without dedup"** — there is only one
   injection path. `ContractionManager.loadPairedContractions` merged
   `contraction_pairings.json` on top of the pairs `loadBinaryContractions` had already derived,
   using a blind `add()` where the sibling per-language loader had always checked membership. The
   two English sources overlap on **599 of 2,258 bases**, so `getPairedContractions("ill")`
   returned `["i'll", "i'll"]` and the single injection loop emitted it twice. Every doubled
   possessive (`times → time's` twice, `boards → board's` twice) came from the same line. Fixed at
   the loader, with a final-list guard in the injection block so a future third source cannot
   re-open it. The swipe path was never affected — `ContractionOverlay.apply` dedups on emit.

Pinned by `ContractionInjectionPolicyTest` (pure, incl. the one-base blast-radius check),
`ContractionFlickerTest`'s rewritten prefix-guard cases, and
`ContractionSentenceStartMeasureTest`, which keeps logging ranks but now asserts presence and
no-duplicate-surface — the `id` case flipped from documenting the absence to pinning the fix.
Device confirmation is owed on the next ew-cli run.

## Provenance

- Corpus: `~/.cache/cleverkeys-test/combined_english_swipes.jsonl.gz` (local-only, never
  committed; the neural model's held-out set, proshian format, 360×215 px canvas).
- Both engines decoded the byte-identical rows; geometric via
  `GeoLayoutFixtures.loadShipped("latn_qwerty_us")` + shipped `GeometricEngineConfig()`,
  CTC via `CtcReplayEngine` (shipped model, EN_JSON strip-loaded trie, alias keys injected,
  fuzzy rescue below the beam).
- Assertions in the harness are wiring-sanity floors only (top-1 ≥ 0.30 both engines); the
  numbers above are the deliverable and any future run should be appended here with its HEAD.
