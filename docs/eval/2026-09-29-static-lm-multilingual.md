# Static context LM — multilingual pilot (es) and follow-through measurements (2026-09-29)

> **Superseded outcome (later on 2026-09-29):** after the contraction-lookup fix, the six
> candidates and English were re-evaluated ONCE on the unchanged gates, and under the
> maintainer's per-language rule **de, fr and it now ship**; es, pt and sv stay unshipped. See
> [Contraction-lookup fix and per-language shipping](#contraction-lookup-fix-and-per-language-shipping-2026-09-29-later).
> The sections below are the original pilot record and are left as measured.

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

## Contraction-lookup fix and per-language shipping (2026-09-29, later)

### The bug (affected English as shipped)

The CKLM vocabulary names contractions by their DISPLAY form (en `don't`, fr `c'est`, it
`l'acqua`), but every dictionary stores them apostrophe-free (`dont`, `cest`;
`.claude/skills/contraction-system.md` §1), so the tap predictor's candidate — and a previous
word committed as the key — is `dont`. `StaticContextLm` looked the key up literally. As the
candidate it received only the previous word's backoff ratio (shipped en model: `i → dont`
0.51 against `i → don't` 27.5, which the multiplier clamps to 10); as the previous word it had
no continuations. English was hit hardest where it matters: `dont`, `im`, `ive`, `cant`,
`lets`, `thats` are ALSO vocabulary words of the model (the web corpus' own apostrophe-less
tokens), so they silently read the typo statistics instead of the contraction's.

### The fix — one layer, and why that layer

`StaticContextLm.withReplaceAliases(replace)` adds a primitive alias index (key → display-form
id) that `wordId` consults, so every query — tap multiplier (`contextRatio`), `probability`,
`hasContext`, the next-word seed — resolves `dont` as `don't`; `top()` still returns display
forms, which is what the bar shows. `BigramModel` builds the map at LM load from
`ContractionManager.loadSwipeDisplayMappings(language)` (the single-language load: English =
pairing-reclassified base + `contractions_en.json`, 107 keys; other languages = their REPLACE
file, or an installed pack's file) and discards the manager.

- **Why the model layer, not `WordPredictor`:** the model is the only place that knows which
  display forms it names; the candidate and the previous word both pass through the same
  `wordId`; and the S1 eval exercises the identical code instead of a copy of a call-site
  mapping. No `WordPredictor` change was needed.
- **REPLACE vs PAIRED:** only the REPLACE bucket aliases. PAIRED keys (`well`/`we'll`,
  `hell`/`he'll`, fr `lune`/`l'une`) are words and keep their own statistics — pinned on every
  shipped model by `StaticLmAssetDriftTest`.
- **A REPLACE key that is ALSO a model word** (en `dont`/`im`/`ive`/`cant`/`lets`/`thats`; fr
  `den`/`doc`/`my`/`quest`/`tai`/`ya`; it `nè`/`sè`) still resolves to the display form: the bar
  never shows that key — REPLACE puts the display form in its slot — so the model scores what
  the user sees. This deliberately departs from a "only when the alias is not itself a
  vocabulary word" rule; that rule would have left exactly the six commonest English
  contractions broken.
- **Runtime only.** `en.cklm` bytes are unchanged (sha256 `8efe036a…`).
- Fail-first: 6 new tests failed against a no-op stub (4 fixture, 1 BigramModel adapter, 1 on the
  real en model) and pass with the fix (commit `c957d31a`).

### Re-evaluation — once, same gates, no tuning

Only change: the lookup fix (no corpus, weight, threshold, population or model change). The
harness gained `STATIC_LM_EVAL_ALIASES=off`; the aliases-off run reproduces the earlier table
exactly, so "before" and "after" are the same harness on the same models. Pre-registered before
the run: the GATE population stays the positions whose target is a lexicon word (as before);
contraction targets — the positions the fix helps most — are reported in a separate
**supplementary, ungated** cell (target scored as its REPLACE key over the lexicon plus the
alias keys `WordPredictor` injects at the 5,000 floor). Nothing was selected on the test split.

OOD test, `lm_static` vs `none`, top-3 Δ in points (before → after):

| Lang | prefix-1 (≥ +5) | prefix-2 (≥ +2) | prefix-3 (≥ 0) | empty ctx dev. | aliases | S1 |
|---|---|---|---|---|---|---|
| en | +10.51 → **+10.43** | +4.06 → +4.04 | +2.38 → +2.39 | 0 → 0 | 56 | PASS (ships already) |
| es | +4.69 → **+4.69** | +4.74 → +4.74 | +3.26 → +3.26 | 0 → 0 | 0 | **FAIL** |
| de | +5.16 → **+5.16** | +4.36 → +4.36 | +3.71 → +3.71 | 0 → 0 | 3 | **PASS** |
| fr | +7.33 → **+6.75** | +5.89 → +5.89 | +3.93 → +3.93 | 0 → 0 | 1,357 | **PASS** |
| it | +7.49 → **+7.48** | +7.38 → +7.38 | +4.29 → +4.29 | 0 → 0 | 1,261 | **PASS** |
| pt | +4.19 → **+4.19** | +5.29 → +5.29 | +3.09 → +3.09 | 0 → 0 | 0 | **FAIL** |
| sv | +4.59 → **+4.59** | +1.92 → +1.92 | +1.04 → +1.04 | 0 → 0 | 0 | **FAIL** |

es, pt and sv ship no REPLACE contractions (their files are empty on purpose), so the fix
cannot move them; German has 3 aliased forms and did not move. The small drops (fr −0.58, en
−0.08, it −0.01 at prefix-1) are expected: the gate population excludes contraction targets, so
in it a correctly boosted key like `cest` after `et` is only ever a competitor. In-domain
held-out still passes for all seven (after: en +15.04, es +8.63, de +9.96, fr +8.39, it +9.35,
pt +9.06, sv +8.46 at prefix-1). Next-word top-3 is unchanged to ±0.03 pt (editor-text
context already carries the display form, e.g. `don't`).

Supplementary cell (ungated; OOD; target = a REPLACE display form, scored as its key;
`lm_static` top-3 vs `none`):

| Lang | n (prefix-1/2/3) | prefix-1 | prefix-2 | prefix-3 |
|---|---|---|---|---|
| en | 113 / 97 / 87 | 0.00 → 32.74 % | 10.31 → 43.30 % | 51.72 → 59.77 % |
| fr | 199 / 195 / 170 | 0.00 → 15.58 % | 1.03 → 17.95 % | 13.53 → 27.06 % |
| it | 233 / 229 / 224 | 0.00 → 5.15 % | 0.87 → 6.11 % | 3.13 → 8.48 % |
| de | 0 (no such OOD target) | — | — | — |

Concentration (so the raw n is not over-read): the English positions come from ≈22 distinct
forms (`don't` 25, `i'm` 16, `i've` 10, `wouldn't` 8 …); French ≈150 (`c'est` 14, `d'un` 13,
`qu'il` 9 …); Italian ≈290, mostly one-off elisions (`l'aumento`, `c'è`, `all'interno` 4 each) —
counted over the raw OOD tokens before the model-vocabulary filter, so upper bounds. Before the
fix these positions could not reach the top-3 at prefix-1 at all.

### Shipping decision (maintainer rule: each language on its own gates)

- **Ship: de, fr, it** (commit `4775300e`). The staged candidates were rebuilt from the
  committed builder and are byte-identical (sha256 de `63892b2ce8a5c4c1…`, fr
  `5ced1c6f1bddf41e…`, it `3d6cfb2c31298e4f…`). German passes by 0.16 pt, inside the ±0.7 pt
  sampling error noted above; the rule is the pre-registered threshold, so it ships, and it is
  the first candidate to revisit if a larger German test set becomes available.
- **Not shipped: es, pt, sv** — unchanged failures, recorded above.
- Swipe stays unwired for every language (S3).
- `BigramModel`: for every LM language except English, the LM's continuations are the whole
  next-word seed wherever the LM covers the previous word; the legacy seed (hardcoded pairs +
  `bigrams/<lang>_bigrams.json`) stays the fallback before the LM loads and for previous words
  the LM does not know (commit `cc1426fa`). English keeps its reviewed curated gap-fill. The
  hardcoded multiplier tables were already bypassed once an LM loads.

### S2 for the shipped languages

| Lang | asset B (cap 524,288) | retained heap B (cap 1.5 MB) | of which alias index | load ms, pure JVM (cold / warm median) |
|---|---|---|---|---|
| en | 439,017 | 671,319 | 1,317 | 2.4 / 1.9 |
| de | 438,403 | 683,227 | 138 | 2.3 / 1.9 |
| fr | 458,180 | 885,367 | 38,865 | 7.6 / 5.5 |
| it | 475,337 | 905,871 | 39,116 | 9.2 / 5.3 |

All four resident at once (BigramModel keeps each language it loaded): 3,145,784 B. GC-measured
heap deltas agree with the array accounting to < 100 B. Load = read + parse + alias index in the
`StaticLmAssetDriftTest` JVM (JIT warm by then — the cold eval parse times were 10.6 ms de,
116.8 ms fr, 70.2 ms it on a box at load average ~14, and the device additionally reads the
REPLACE map through `ContractionManager`, ~18–21k JSON entries for fr/it, on the background seed
thread). Device cold-load timing was not measured.

Release APK (`./build-on-termux.sh release --no-install`, 2026-09-29): arm64-v8a
22,607,709 B, **+968,882 B** against the 21,638,827 B reference; the three new models and their
sidecars are 947,427 B of that as stored (deflated ~67–70 % of raw: de 308,053, fr 307,946,
it 323,017 B for the `.cklm`), the rest is other commits since the reference build.

### Known gap found while fixing (not changed here)

**Next-word display forms are filtered out on the device.** `NextWordPredictor.candidatesFor`
admits a continuation only if `WordPredictor.isInDictionary` (or the user vocabulary) knows it,
and no shipped lexicon holds an apostrophe word (0 in en/fr/it). So an LM continuation such as
`c'est` after `et`, or `don't` after `i`, is dropped before the bar — the eval's next-word arm
admits contraction display forms and therefore overstates the device by those positions. The fix
belongs in the next-word allow check (accept a display form of the active language's REPLACE or
PAIRED contractions), which is `NextWordPredictor`/`WordPredictor` territory outside this change.

### Reproduce

```sh
STATIC_LM_MODEL_DIR=<stage> STATIC_LM_EVAL_LANGS=es,de,fr,it,pt,sv \
  scripts/gradle-guard.sh runPureTests -PtestClass=StaticLmTapEvalTest -PgeoFull=true
STATIC_LM_EVAL_LANGS=en scripts/gradle-guard.sh runPureTests -PtestClass=StaticLmTapEvalTest -PgeoFull=true
# add STATIC_LM_EVAL_ALIASES=off to either for the pre-fix lookup
```
