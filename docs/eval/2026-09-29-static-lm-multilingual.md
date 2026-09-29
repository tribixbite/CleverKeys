# Static context LM — multilingual pilot (es) and follow-through measurements (2026-09-29)

**Outcome: NOTHING SHIPPED.** The Spanish pilot failed the pre-registered S1 gate on its
out-of-domain population (prefix-1 top-3 +4.69 pt; the gate is +5). The plan made every further
language conditional on Spanish passing, so no `lm/<lang>.cklm` beyond `en` was added. The other
five candidates (de/fr/it/pt/sv) had already been built into a staging directory and were measured
in the same Gradle run; their numbers are recorded below as evidence for the next decision, not as
a shipping result. Every non-English language keeps its pre-existing path (hardcoded `BigramModel`
tables or the English fallback, plus `bigrams/<lang>_bigrams.json` seeds), unchanged.

What did land (infrastructure, English byte-identical):

- `scripts/build_static_lm.py` is driven by one `LangConfig` per language (`CONFIGS`): Leipzig +
  Tatoeba + UD dev/test pins, lexicon (`en_enhanced.json` or the CKDT `<lang>_enhanced.bin`
  canonical section), contraction display-form files (REPLACE + PAIRED, e.g. fr `c'est`,
  it `l'acqua`), wordfreq reference language, NFC composition and train/eval overlap exclusion
  (both on for every language except en, whose shipped build predates them).
- Weight selection is now "max OOD-dev next-word top-3 **among weights whose model fits the
  512 KiB cap**". It changes nothing for en (chosen weight 0.1 either way).
- `en.cklm` rebuilt from the refactored builder: sha256 `8efe036a…125a4`, unchanged; the
  contributor list and the three eval files are byte-identical too. `en.json` gained
  `model.lexicon`, `model.contractionFiles` and a `bytes` column in `weightGrid`, and its
  `weightRule` string names the cap.
- `StaticLmTapEvalTest` evaluates any language (env `STATIC_LM_EVAL_LANGS`, candidates via
  `STATIC_LM_MODEL_DIR`), with the legacy arm taken from the REAL `BigramModel` tables through a
  new `hardcodedContextMultiplier(language, …)` seam, and prints distinct sentence / previous-word
  / (prev, target) counts plus a PASS/FAIL verdict. `StaticLmAssetDriftTest` covers every shipped
  `.cklm` and rebuilds each allowed vocabulary from the sidecar.

## Method (unchanged from 2026-09-26 except where noted)

- Model: CKLM v1 bigram, count ≥ 3, top-20 continuations, artefact filter (word share > 20× its
  wordfreq share, ≥ 50 occurrences) per corpus, Leipzig × 1 + Tatoeba × W.
- S1 scoring through the production Kotlin path: `NextWordPredictor.contextFromEditorText`
  tokenization, `UnifiedScore.combine` with shipped defaults, `<lang>_enhanced.bin` frequencies,
  `NextWordPredictor.generate` for next-word. Gate (lm_static vs none, OOD test population):
  prefix-1 top-3 ≥ +5 pt, prefix-2 ≥ +2 pt, prefix-3 ≥ 0, zero deviation at empty context.
- Run: one guarded `runPureTests -PtestClass=StaticLmTapEvalTest -PgeoFull=true` over the six
  staged candidates (36 s of test time; the box's load average was ~11–14 from other agents,
  which does not affect these deterministic counts).
- No device learned data exists for these languages except 99 French rows, below the confidence
  floors, so the learned/`*_both` arms equal their static-only counterparts.

## Inputs

| Lang | Leipzig corpus | Tatoeba (weight chosen) | OOD dev → OOD test | Train sent. (L / T) | Eval-overlap dropped (L / T) |
|---|---|---|---|---|---|
| es | `spa_web_2016_300K` | 0.5 (1.0 was best on dev but 575 KB, over cap) | UD Spanish-GSD dev → test | 268,842 / 402,483 | 1 / 2 |
| de | `deu-de_web_2021_300K` | 0.25 | UD German-GSD dev → test | 287,331 / 727,888 | 18 / 47 |
| fr | `fra-fr_web_2013_300K` | 0.05 | UD French-GSD dev → test | 251,623 / 651,861 | 15 / 5 |
| it | `ita-it_web-public_2019_300K` | 0.1 | UD Italian-TWITTIRO dev → UD Italian-PUD test | 249,705 / 817,646 | 8 / 4 |
| pt | `por-pt_web_2015_300K` | 0.5 | UD Portuguese-Bosque dev → test | 237,411 / 405,248 | 0 / 9 |
| sv | `swe-se_web_2023_300K` | 1.0 (grid edge) | UD Swedish-Talbanken dev → test | 269,698 / 51,068 | 0 / 0 |

Hashes, URLs and licences: `scripts/data/PROVENANCE.md` ("Other languages"). Italian avoids the
CC BY-NC-SA treebanks (ISDT, VIT, ParTUT, PoSTWITA). The Italian dev set is small (296 sentences,
n = 1,721 next-word positions), so its weight choice is noisy.

## S1 — OOD gate population (per language)

Counts: sentences / distinct sentences, token positions with context / distinct previous words /
distinct (prev, target) pairs.

| Lang | Population | prefix-1 Δ (≥ +5) | prefix-2 Δ (≥ +2) | prefix-3 Δ (≥ 0) | empty ctx dev. | **S1** |
|---|---|---|---|---|---|---|
| **es** | 339 / 336; 5,026 pos / 1,992 prev / 4,318 pairs | **+4.69** | +4.74 | +3.26 | 0 | **FAIL** |
| de | 942 / 936; 10,973 / 4,082 / 9,709 | +5.16 | +4.36 | +3.71 | 0 | pass (marginal) |
| fr | 379 / 379; 5,422 / 2,188 / 4,819 | +7.33 | +5.89 | +3.93 | 0 | pass |
| it | 938 / 938; 14,983 / 5,059 / 12,719 | +7.49 | +7.38 | +4.29 | 0 | pass |
| pt | 1,008 / 1,007; 13,443 / 4,370 / 11,374 | **+4.19** | +5.29 | +3.09 | 0 | **FAIL** |
| sv | 1,143 / 1,142; 14,524 / 3,728 / 11,444 | **+4.59** | **+1.92** | +1.04 | 0 | **FAIL** |

Top-3 levels behind those deltas (OOD, target in top-3):

| Lang | next-word none → lm | prefix-1 none → lm | prefix-3 none → lm | legacy_static prefix-1 | legacy_both prefix-1 |
|---|---|---|---|---|---|
| es | 15.25 → 21.66 | 42.19 → 46.88 | 46.78 → 50.04 | 19.54 | 42.23 |
| de | 9.82 → 14.53 | 34.12 → 39.28 | 51.05 → 54.76 | 17.20 | 34.16 |
| fr | 11.97 → 19.32 | 40.80 → 48.13 | 51.08 → 55.01 | 24.38 | 40.89 |
| it | 9.36 → 16.83 | 32.43 → 39.92 | 44.72 → 49.00 | 30.73 | 32.43 |
| pt | 11.41 → 15.71 | 33.03 → 37.23 | 47.15 → 50.23 | 31.21 | 33.02 |
| sv | 5.15 → 13.57 | 38.06 → 42.64 | 54.13 → 55.17 | 37.94 | 38.02 |

In-domain held-out (1/20 sample of each builder's held-out split) passes all three thresholds for
all six languages (prefix-1 +8.46 … +9.96 pt), i.e. the misses are out-of-domain transfer, not a
broken model.

## Reading the failures

- **Spanish (the pilot) misses by 0.31 pt on 4,539 scorable positions** (≈ 14 target hits). With
  ~340 test sentences the sampling error on a 42 % rate is about ±0.7 pt (1 s.e.), so a rerun on a
  different treebank could land either side of the line. That is exactly why the threshold was
  pre-registered: it is not moved after seeing the number, and the test set is not used for any
  tuning. A future attempt should change an input for a stated reason (e.g. evaluate on AnCora
  as well, or a newer/larger web corpus), re-run on dev first, and keep this failure on record.
- **Portuguese** trains on European-Portuguese web (no Brazilian web corpus is offered) and is
  tested on Bosque (PT + BR news); 15.4 % of targets are outside the lexicon and only 76.8 % of
  previous words are known to the LM — the weakest coverage of the six.
- **Swedish** is the smallest Tatoeba (51 K training sentences; weight 1.0 sat at the grid edge)
  and Talbanken is professional prose; prefix-2 +1.92 and prefix-3 +1.04 are the smallest gains.
- **German passes by 0.16 pt** — inside the same sampling error; treat it as borderline.

## Findings independent of the gate

1. **The status quo is worse than no context for every non-English language in
   `context_source = static_only` mode.** `legacy_static` (the hardcoded tables, or the English
   tables `setLanguage` falls back to for it/pt/sv) drops OOD prefix-1 top-3 by 22.7 pt (es),
   16.9 (de), 16.4 (fr), 1.7 (it), 1.8 (pt), 0.1 (sv) against `none`, because the hardcoded
   interpolation clamps every table word not paired with the previous word to 0.1×. In the
   shipped default (`both`) the effect is ≈ 0 (`legacy_both` within 0.1 pt of `none`). Every LM
   candidate beats `legacy_static` by 4.7–27.3 pt prefix-1 and 11.5–15.5 pt next-word top-3.
   This is a separate defect worth its own fix (not in this change: the scoring path it would
   touch is `SuggestionHandler`/`WordPredictor`-adjacent and outside this task's fence).
2. The legacy next-word seed (`bigrams/<lang>_bigrams.json` + hardcoded pairs) scores below the
   plain top-unigram baseline on every language (e.g. es 6.15 % vs 15.25 % top-3).
3. French/Italian elisions: the LM names display forms (`c'est`, `l'eau`), while the tap
   predictor's candidates are apostrophe-free lexicon words (`cest`); the multiplier lookup then
   finds no listed pair for those candidates and applies the backoff ratio. Tap-prefix positions
   whose target is an elided form are not scorable in this eval (the target is not a lexicon
   word). Mapping candidates through the contraction overlay before the LM lookup would be a
   `WordPredictor` change — reported, not made.

## S2 (size / load) — candidates only, none shipped

| Lang | bytes (cap 524,288) | vocab | prevs | pairs | parse in eval (ms) |
|---|---|---|---|---|---|
| es | 456,427 | 16,965 | 16,112 | 69,278 | 42.2 (first language in the JVM: JIT cold) |
| de | 438,403 | 15,007 | 14,292 | 72,488 | 13.0 |
| fr | 458,180 | 16,964 | 15,977 | 71,994 | 10.6 |
| it | 475,337 | 17,488 | 16,023 | 74,532 | 16.7 |
| pt | 454,947 | 15,859 | 14,848 | 77,087 | 4.7 |
| sv | 297,008 | 10,899 | 10,461 | 46,771 | 3.0 |

Heap and cold/warm load were not measured for the candidates: S2 runs only for a model that
passed S1. `StaticLmAssetDriftTest` now reports both per shipped language (cold = first
read+parse, warm = median of 15) and the all-resident total, since `BigramModel` keeps every
language it has loaded.

S3 (swipe) remains failed for every language by design: the LM feeds tap context and next-word
only, never swipe rescoring.

## Reproduce

```sh
python3 scripts/build_static_lm.py --lang es --offline --out-dir <stage> --contributors <stage>/tatoeba-contributors-es.txt
STATIC_LM_MODEL_DIR=<stage> STATIC_LM_EVAL_LANGS=es,de,fr,it,pt,sv \
  scripts/gradle-guard.sh runPureTests -PtestClass=StaticLmTapEvalTest -PgeoFull=true
```

Candidate sha256 (deterministic from the pins): es `a304a06c10e95dc953f25e439008724665dfbd85ae9a79e2717f72ba8d6f1653`.
The Tatoeba exports rotate weekly; a later download needs `--allow-unpinned` and is a different model.
