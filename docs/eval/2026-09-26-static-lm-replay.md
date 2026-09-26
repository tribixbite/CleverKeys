# Static context LM: tap evaluation (S1) and swipe synthetic-context replay (S3)

**Run date**: 2026-09-26 · **Model**: `src/main/assets/lm/en.cklm` sha256 `8efe036a…114125a4`
(commit `2144770e`) · **Harnesses**: `StaticLmTapEvalTest` (S1), `ContextRescoringReplayTest`
with `-PreplayCorpus=static` (S3) · **S3 tree**: HEAD `e8ea5d34`; `swipe/ctc/**` had no
uncommitted edits at run time (checked with `git status`), so the CTC decode is the committed one.

> Quote the model hash with these numbers. Rebuilding from a newer Tatoeba snapshot produces a
> different model (see `scripts/data/PROVENANCE.md`).

## Verdicts

| Stage | Gate | Result | Verdict |
|---|---|---|---|
| **S1** tap, OOD (UD EWT test) | prefix-1 top-3 ≥ +5 pt vs no context | **+10.51 pt** (31.19 → 41.70 %) | PASS |
| | prefix-2 top-3 ≥ +2 pt | **+4.06 pt** (43.90 → 47.95 %) | PASS |
| | no regression at prefix ≥ 3 | **+2.38 pt** (62.79 → 65.17 %) | PASS |
| | no regression at empty context | **0 cases differ** in any arm, prefixes 1–3 | PASS |
| **S2** loader + wiring | heap ≤ +1.5 MB | **670,002 B** (array accounting); GC delta 670,064 B | PASS |
| | APK Δ (arm64 release) | **+333,230 B** (LM deflated 306,804 B) | reported |
| | load ≤ 30 ms off-main | read+parse median **4.6 ms**, first-in-class 11.9 ms, ~200 ms for the first parse in a cold JVM (load avg ≈ 13 on 4 cores) | PASS warm; cold-JVM first parse over budget (see §2) |
| **S3** swipe, CTC | errRatio < 0.20 ∧ Δtop-1 > 0 | best confirm cell: **1 fixed / 3 broken** (RATIO, W=0.5, R=0.6) | **FAIL** |
| | capture of oracle-fixable traces | **2 (PROB) / 6 (RATIO) of 135**, fixed under ≥ 1 context; 0 under a majority | — |

**Recommendation: ship the LM for tap context and next-word (done — `6b1322f6`); do NOT wire it
into swipe rescoring.** No production swipe code was changed.

## 1. S1 — tap prediction

**Protocol.** The builder writes the eval sentences locally (never committed): the 10 % held-out
split by sentence hash (in-domain; the test reads a 1/20 sample) and UD English-EWT *test*
surface text (`# text =` lines, OOD — weight selection used EWT *dev*, so the gate population was
never looked at while building). Every sentence is tokenized by the device's own
`NextWordPredictor.contextFromEditorText`. Prefix re-rank: every lexicon completion of the typed
prefix is scored through the real `UnifiedScore.combine` with the shipped defaults (context boost
0.5, frequency scale 100), the tap predictor's frequency from `en_enhanced.bin`, and its prefix
score; target in the top 3 is a hit. The fast path scores individually only the words whose
multiplier can differ from the arm's uniform default; all other candidates keep their
context-free order, so the ranking is exact.

Arms: `none`; `legacy_*` = the pre-LM hardcoded en table (frozen in `LegacyEnglishContext`);
`lm_*` = this model; `learned_only` / `*_both` add the maintainer's device export (private;
aggregates only). `both` is the shipped default `context_source`.

### OOD — UD EWT test (1,906 sentences, 16,055 positions with context)

Target outside lexicon ∪ contractions: 2.6 %. Previous word known to the LM: 92.1 %.

```
                 next-word        prefix-1         prefix-2         prefix-3
                 top-1  top-3     top-1  top-3     top-1  top-3     top-1  top-3
none              4.26  10.08      0.00  31.19      0.15  43.90     35.76  62.79
legacy_static     5.46   8.98      4.38  15.96      4.78  30.73     33.62  59.55
lm_static        12.06  20.94     23.07  41.70     24.67  47.95     41.98  65.17
learned_only      4.74   7.67      4.26  32.54      6.14  44.31     35.99  62.91
legacy_both       5.47   8.86      5.33  33.98      7.22  44.55     36.39  62.91
lm_both          10.69  18.72     22.72  41.71     24.45  48.24     42.00  65.20
```

n: next-word 15,641; prefix-1 14,796; prefix-2 12,179; prefix-3 9,021.
Default-mode change (`lm_both` vs `legacy_both`, what a default user sees): prefix-1 +7.73,
prefix-2 +3.69, prefix-3 +2.29 pt top-3.

### In-domain — held-out 1/20 sample (11,848 sentences, 86,966 positions)

```
lm_static vs none, top-3:  next-word +14.43   prefix-1 +15.12   prefix-2 +5.75   prefix-3 +2.50
lm_both vs legacy_both:    prefix-3 +2.52
```

### Reading

- The old hardcoded table **hurt** in `static_only` mode: every one of its 20 unigram words that
  is not a listed continuation gets the 0.1× clamp, which cost 15 pt at prefix 1. In the default
  `both` mode the `max(static, learned)` hid that. The LM replaces it with a real ratio and a
  backoff that sits just under 1.
- `lm_both` is slightly below `lm_static` on next-word. The learned tier fills bar slots first
  (by design), and the maintainer's personal pairs predict EWT text worse than corpus statistics
  do. This is a property of the OOD population, not a defect.
- Empty context is identical in every arm, by construction (the multiplier is 1.0 without a
  previous word), and the measurement confirms it.

## 2. S2 — loader, wiring, budget

- Asset 439,017 B (cap 512 KB). Retained heap 670,002 B: flat UTF-8 vocabulary, word starts,
  unigram bytes, a 32,768-slot `IntArray` hash, and the prev-index/continuation section copied
  verbatim. The GC-measured delta (670,064 B) agrees.
- Load runs on `BigramModel`'s existing `SEED_LOADER` thread (below-normal priority, attempt
  once), never on the main thread. Warm JVM: min 3.3 ms, median 4.6 ms over 15 rounds, first
  round in the class 11.9 ms. The very first parse in a **fresh** JVM took ~200 ms (interpreter
  plus class loading, measured inside the S1 eval). ART on the phone will sit between these, and
  it is off-main either way, so the only cost of a slow first load is that the hardcoded en table
  serves context for that long. This was not measured on a device.
- `BigramModel` is a process singleton, so the model is loaded once per process and survives
  service recreation. It is not duplicated per `WordPredictor`. It is not released with the
  predictor either (deviation from the design note). The ≈0.67 MB lives for the process
  lifetime.
- APK size (release arm64, `./build-on-termux.sh release --no-install`, lintVital passed):
  **21,625,015 B vs the 21,291,785 B reference, +333,230 B.** `assets/lm/en.cklm` is stored
  deflated at 306,804 B and the sidecar at 2,244 B. The remaining ~24 KB is code and strings,
  including other agents' uncommitted work in the shared tree at build time, so it is an upper
  bound for this change.
- Full suites on the shared tree after the wiring: `runPureTests` OK (2,575 tests),
  `runMockTests` OK (843 tests).

## 3. S3 — swipe synthetic-context replay (CTC)

**Protocol.** Every trace in the 2,713-trace pool is decoded once by `CtcReplayEngine`. The slate
does not depend on context. For each trace word, up to 5 held-out sentences containing it at
position ≥ 1 are reservoir-sampled (seeded per word). The context is the preceding word. 2,101
of 2,197 pool words occur. Evidence is the static LM's **listed** pairs only: a word outside
the previous word's top 20 gets `Evidence.NONE`, never a sub-1 boost. The frequency floor is met
by construction (weighted count ≥ 3), and the rank-1 probability floor (0.05) applies to
P(w|prev) itself. Two boost mappings: **PROB** `(1+p)²` (the learned store's curve) and
**RATIO** `P(w|prev)/P(w)`, both clamped to [1, 5]. The shipped `rescoreOrder` runs at
W = 0.5, R_MIN = 0.5, with the W × R_MIN grid selected on the tune half (trace-hash split) and
read on confirm. Arms:

- **real context**: the true word after its sampled real context, plus 2 confusable decoys
  (same first letter, length ±1) swiped after the same context;
- **hub-confusable**: every trace after each of `the, to, a, of, and, in, i, you`. Static
  evidence exists after these hubs for almost every common word, which is the damage surface a
  learned store never had.

### Results (distinct traces beside every count)

```
                         exposed (cases / distinct traces)   fixed  broken  shipped-point bar   tune-selected → CONFIRM
PROB  real context       fav 1468/730  adv 817/310             2       2     errRatio 1.00 FAIL  W1.0 R0.5 → 1 fixed / 5 broken
PROB  hub arm            adv 740/413                            0       1     FAIL                none clears tune
RATIO real context       fav 1435/720  adv 734/305             6       9     errRatio 1.50 FAIL  W0.5 R0.6 → 1 fixed / 3 broken
RATIO hub arm            adv 652/391                            0       6     FAIL                none clears tune
```

Concentration: PROB, 2 fixes from 2 traces and 2 breaks from 1 trace (`thai` → `that`).
RATIO, 6 fixes from 6 traces and 9 breaks from 6 traces. The hub arm breaks 4 traces (`thy`,
`tuner` → `the`; `dui` → `do`; `war` → `was`). Example fixes are proper-noun collocations:
alice's→adventures, jon→stewart, lewis→carroll, palm→tree.

**Why nothing moves.** Of 2,285 exposed real-context cases (PROB), 1,214 have evidence only on
the engine's own top-1, 916 have their contender below R_MIN × top-1, 119 fail the strict
floors, and **36 clear both rank-1 guards**. The static LM mostly agrees with CTC where CTC is
already right. That repeats the learned-store finding (`2026-09-26-context-oracle-and-alternates-replay.md`)
with broader coverage.

**Capture of the oracle headroom.** On this pool the guarded oracle again fixes **135** traces.
126 of them have a held-out context. The static LM fixes **2 (PROB) / 6 (RATIO)** of them under
at least one sampled context, **0** under a majority of their contexts, and 2 or 6 of 546
(context, trace) cases. General bigram statistics do not predict the words CTC misses.

### What this cannot say

1. Synthetic context is one real preceding word. On-device context can be two words (the tap
   path is bigram-only too) and is the user's own text, not Leipzig/Tatoeba prose.
2. The pool is word-uniform, not token-weighted. The arm ratio (real contexts : decoys : hubs)
   is a sampling choice, so COMBINED rates are not usage rates.
3. The slate carries a–z surfaces. The apostrophe gap measured 0 cases here.
4. English only; one model hash.

## 4. Reproduce

```sh
python3 scripts/build_static_lm.py            # rebuilds the model + local eval files (~6 min)
scripts/gradle-guard.sh runPureTests -PtestClass=StaticLmTapEvalTest -PgeoFull=true
scripts/gradle-guard.sh runPureTests -PtestClass=swipe.ContextRescoringReplayTest \
  -PgeoFull=true -PreplayCorpus=static -PreplayDecoys=2        # ~13 min on a loaded box
```

Corpora and eval files stay local (`~/.cache/cleverkeys-corpora/`). This document quotes
aggregates only.
