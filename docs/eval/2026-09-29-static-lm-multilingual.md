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

## es/pt/sv retry — PRE-REGISTRATION (2026-09-29, written and committed before any retry model is built or measured)

Nothing below has been measured yet. The UD **test** splits are not read by anything that
chooses an input; each language's single chosen model is evaluated on its test split exactly
once, after this section and the dev choices are committed.

### What is unchanged

- **Gates** (per language, OOD test, `lm_static` vs `none`, through the Kotlin path in
  `StaticLmTapEvalTest`): prefix-1 top-3 ≥ +5 pt, prefix-2 ≥ +2 pt, prefix-3 ≥ 0, zero
  empty-context deviation. Same thresholds as the pilot.
- **Test populations**: UD Spanish-GSD test, UD Portuguese-Bosque test, UD Swedish-Talbanken test
  at the pinned commits (not swapped for a treebank that "fits better" — that would be choosing
  the exam after failing it).
- Model format and pruning: CKLM v1, `MIN_COUNT` 3 on weighted counts, top-20, 512 KiB cap,
  lexicon ∪ contraction forms, artefact filter, NFC, eval-overlap exclusion.
- Shipping rule: each language ships iff it passes its own gates; a failure is recorded and the
  language stays unshipped. No second test evaluation for any language.

### What changes, and why (hypotheses)

| Lang | Dev composition checked (dev split only) | Hypothesis | Candidate second Leipzig corpus |
|---|---|---|---|
| pt | Bosque dev `sent_id`: 523 `CF` (CETENFolha, **Brazilian** news) / 649 `CP` (CETEMPúblico, European news) — 45 % Brazilian, 100 % newspaper | **Variety + register mismatch.** The pilot trained on Portugal web text only (`por-pt_web_2015`); 45 % of the evaluation variety (pt-BR) and its register (news) are absent. | (a) `por-br_newscrawl_2011_300K` — Brazilian news (variety AND register); (b) `por_news_2023_300K` — news, variety-mixed |
| es | GSD dev: encyclopedic/news-style prose (e.g. "Lo hizo siguiendo las nuevas corrientes …") | **Register mismatch.** GSD is Wikipedia/news-like web prose; `spa_web_2016` is general web. | (a) `spa_news_2023_300K`; (b) `spa_wikipedia_2021_300K` |
| sv | Talbanken dev: professional prose (textbook/informational) | **Register mismatch + Tatoeba weight at the grid edge** (1.0 was the largest weight offered, so the dev optimum may lie beyond it). | (a) `swe_news_2023_300K`; (b) `swe_wikipedia_2021_300K` |

The pilot's Leipzig web corpus stays the PRIMARY corpus in every candidate (it is the closest
register to keyboard text); a second corpus is MIXED in, never substituted blindly.

### Mixing and grid (identical for the three languages)

- Leipzig weights sum to 1: primary × (1 − λ) + second × λ, so the total count mass — and with
  it how many pairs clear `MIN_COUNT` — stays near the pilot's, which keeps the model near the
  cap without changing the pruning rule. Tatoeba × W on top, as before.
- λ ∈ {0, 0.25, 0.5, 0.75, 1.0}; W ∈ {0, 0.05, 0.1, 0.25, 0.5, 1.0, 2.0} (2.0 added for every
  language because sv's pilot optimum sat at the old edge). λ = 0 reproduces the pilot inputs
  (so the pilot model is itself a grid point and can win).
- Grid points whose model exceeds 524,288 B are ineligible.

### Dev metric and selection rule

- **Metric: the gate metric itself, on the OOD dev split** — `StaticLmTapEvalTest` over
  `ood_dev_<lang>.txt` (UD dev `# text` lines, same tokenization), prefix-1 top-3 Δ
  (`lm_static` − `none`), through the same `UnifiedScore.combine` path. (The pilot chose W by a
  Python next-word top-3 proxy; selecting on the gated quantity is the stated change.)
- **Rule:** among eligible grid points of all candidate corpora for a language, take those with
  dev prefix-2 Δ ≥ +2 and dev prefix-3 Δ ≥ 0 and zero empty-context deviation; choose the
  maximum dev prefix-1 Δ. Ties (to 0.01 pt) → smaller λ, then smaller W, then candidate (a)
  before (b). If no point meets the prefix-2/3 constraints, choose the maximum dev prefix-1 Δ
  anyway and record that.
- The chosen point is rebuilt from the committed builder, and that one model per language is
  evaluated on the test split once.
- Disk: each candidate archive is deleted after the grid is counted unless it was chosen
  (chosen archives stay cached so the shipped model can be rebuilt byte-identically).

### Risks stated in advance

- Selecting across 2 corpora × 5 λ × 7 W (63 points) on dev makes the dev number optimistic;
  the test evaluation is the honest check, and the in-domain held-out is reported beside it.
- Mixing in news/encyclopedic text moves the model toward treebank register and possibly away
  from chat register; the λ = 0 points keep the pilot inputs eligible, and the held-out of the
  primary web corpus is reported so a regression there is visible.

### Task 2 (legacy tables in `static_only`) — also pre-registered here

Two candidate fixes for languages WITHOUT an LM: **A** — never use another language's tables,
and within a language's own tables only a LISTED `(prev, word)` pair may move the multiplier,
never below 1 (every unlisted pair is neutral); **B** — no hardcoded multiplier at all (neutral
1.0; the curated next-word seed stays). Chosen on the es/pt/sv OOD **dev** splits by
`legacy_static` prefix-1 top-3 Δ vs `none`: A if it is ≥ 0 at prefixes 1–3 on every language
and > 0 on at least one; otherwise B. Before/after is then reported on the test splits.

### Dev choices (2026-09-29, committed before the test evaluation)

One guarded run of `StaticLmTapEvalTest` (`STATIC_LM_EVAL_SPLIT=dev`, `STATIC_LM_EVAL_CANDIDATES`)
over every grid point within the cap: es 43 of 63, pt 51 of 63, sv 63 of 63 (the rest were
over 524,288 B and were not written). The λ = 0 / pilot-weight point reproduces the pilot model
byte-for-byte (es sha256 `a304a06c…`). The rule from the pre-registration was applied mechanically
(`select_dev.py` over the `GRID` lines). OOD **dev** deltas, `lm_static` − `none`, top-3:

| Lang | Dev n (prefix-1) | Pilot inputs on dev (p1 / p2 / p3) | Chosen point | Chosen on dev (p1 / p2 / p3) | Bytes |
|---|---|---|---|---|---|
| es | 16,211 | +2.89 / +3.78 / +2.54 | `spa_wikipedia_2021_300K`, λ 0.75, W 0.1 | **+5.37** / +6.39 / +4.63 | 405,743 |
| pt | 11,260 | +4.75 / +5.26 / +3.09 | `por-br_newscrawl_2011_300K`, λ 0.5, W 0 | **+5.85** / +5.81 / +3.39 | 351,738 |
| sv | 5,352 | +3.87 / +1.46 / +0.65 | `swe_news_2023_300K`, λ 1.0, W 2.0 | **+5.33** / +1.85 / +1.28 | 443,229 |

- **es**: every one of the top 8 points is the Wikipedia candidate at λ ≥ 0.75 (news peaked lower),
  consistent with the register hypothesis (GSD is encyclopedic prose).
- **pt**: the Brazilian-news candidate (a) at λ 0.5 wins; the variety-mixed 2023 news (b) is 0.10
  pt behind. W = 0, so the pt model counts no Tatoeba sentence (no contributor list needed).
- **sv**: **no grid point met the dev prefix-2 ≥ +2 constraint** (the best is +1.85, the chosen point itself); per the rule the
  max-prefix-1 point is chosen anyway and this is recorded. It REPLACES the web corpus with news
  (λ = 1.0, a pre-registered grid value) and sits at the new W edge (2.0). On dev it would fail
  the prefix-2 gate; the test evaluation still runs once, as registered.
- Dev deltas are optimistic after choosing the maximum of 43–63 points; the test split decides.

Task 2 "before" on dev (unchanged `BigramModel`, same run): `legacy_static` − `none` top-3 at
prefix 1 / 2 / 3 — es −23.40 / −2.26 / +0.02; pt −2.25 / +0.13 / +0.19; sv −0.11 / +0.19 / 0.00.
In `both` with no learned data: es +0.08, pt −0.03, sv −0.07 at prefix 1.

### Test evaluation — once per language (2026-09-29)

One guarded run, `STATIC_LM_MODEL_DIR=<final> STATIC_LM_EVAL_DIR=<final>/eval
STATIC_LM_EVAL_LANGS=es,pt,sv … StaticLmTapEvalTest -PgeoFull=true`, over the three models rebuilt
from the committed configs (`7ca01d6c`; each byte-identical to its dev-chosen grid point). Test
populations are exactly the pilot's (same sentence/position counts). Box load average ~13–15 from
other agents; the counts are deterministic. (The builder's final-build log line also prints a
Python next-word number on the test split; it was printed after the choice was committed and
used for nothing.)

OOD test, top-3, `lm_static` − `none`:

| Lang | Population | prefix-1 (≥ +5) | prefix-2 (≥ +2) | prefix-3 (≥ 0) | empty ctx dev. | **S1** | pilot prefix-1 |
|---|---|---|---|---|---|---|---|
| es | 339 sent.; 5,026 pos / 1,992 prev / 4,318 pairs | **+4.96** | +5.13 | +3.66 | 0 | **FAIL** | +4.69 |
| pt | 1,008 sent.; 13,443 pos / 4,370 prev / 11,374 pairs | **+5.41** | +5.97 | +3.56 | 0 | **PASS** | +4.19 |
| sv | 1,143 sent.; 14,524 pos / 3,728 prev / 11,444 pairs | **+5.98** | +2.95 | +1.94 | 0 | **PASS** | +4.59 (p2 +1.92) |

Levels (none → lm_static): es prefix-1 42.19 → 47.15 %, next-word 15.25 → 22.16 %; pt 33.03 →
38.44 %, next-word 11.41 → 16.81 %; sv 38.06 → 44.04 %, next-word 8.15 → 17.12 %. In-domain
held-out (1/20 sample of each build's own held-out split) passes all three for all three
languages (prefix-1 es +6.32, pt +6.95, sv +7.30).

- **Spanish fails again, by 0.04 pt** (≈ 2 target hits of 4,539). It stays unshipped. The dev
  gain (+5.37) did not fully transfer; per the registration there is no second test look, and
  the next attempt needs a new stated reason (a larger Spanish test population would be the
  honest one — 339 sentences carry ±0.7 pt of sampling error).
- **Portuguese passes** with the Brazilian-news mix (+1.22 pt over the pilot at prefix-1) —
  consistent with the variety + register hypothesis. **Ships.**
- **Swedish passes** on test although no grid point met prefix-2 ≥ +2 on dev (dev +1.85; test
  +2.95). The dev constraint was a selection aid, the test gate is the rule; recorded as a pass
  with the caveat that Talbanken dev and test disagree by 1.1 pt at prefix-2. Its model is
  news-only Leipzig (λ = 1.0) + Tatoeba × 2.0: the web corpus the pilot used contributes nothing.
  **Ships.**
- `lm_both` (the shipped default, no learned data) beats `lm_static` at prefix-1 on all three
  (es +6.08, pt +6.05, sv +6.78 vs none): in `both` the LM's below-1 backoff ratios are floored
  at 1 (see Task 2 below), so the gate as measured in `static_only` is the conservative number.

## Legacy tables in `static_only` — root cause and fix (Task 2, 2026-09-29)

### Why the legacy path lowered prefix-1 top-3

`BigramModel.hardcodedContextMultiplier` computed `clamp((λ·P(w|prev) + (1−λ)·P(w)) / P(w), 0.1, 10)`
with λ = 0.95 over 14 (es/fr/de) or 68 (en) hand-listed pairs and a 14–20-word unigram table.
Three defects, the first two decisive:

1. **Unlisted pairs were treated as impossible.** `P(w|prev)` was 0 for every pair not listed, so
   every table unigram (es `de la que el en y a es se no …` — the commonest words, i.e. the
   likeliest targets) not listed after the previous word got 0.05 × its own probability → the
   0.1 clamp. Spanish "de" after "muy" was a 10× demotion. The hand tables were never a
   conditional distribution; the formula read them as one.
2. **Cross-language fallback.** A language without tables (it, pt, sv, and every other language —
   nl, pl, ru, …) was scored with ENGLISH's tables, twice over: `hardcodedContextMultiplier` fell
   back to `en`, and the device call passed `currentLanguage`, which `setLanguage` rewrites to
   `en` for them. Portuguese "de do" was demoted as the English verb "do"; Italian/Portuguese
   "in a" was boosted by English `in|a`.
3. A listed pair whose word is also a frequent unigram could score BELOW 1 (`todo|el` 0.525).

### Fix (candidate A, selected on dev by the pre-registered rule)

Only a listed `(prev, word)` pair of the language's OWN table moves the multiplier, and never
below 1; everything else is exactly 1.0; the device path uses the requested language
(`seedLanguage`), not the English fallback. Dev, `legacy_static` − `none` top-3 at prefix 1/2/3:
es +0.08 / +0.03 / 0.00, pt 0 / 0 / 0, sv 0 / 0 / 0 → A (≥ 0 everywhere, > 0 on es) rather than B
(no multiplier). The curated next-word seed is untouched. Fail-first: three new
`BigramModelStaticLmTest` cases failed on the old code (0.1, 0.1, 0.525) and pass with the fix.

### Before / after (OOD test)

`legacy_static` and `legacy_both` − `none`, prefix-1 top-3 (pt). "Before" = the pilot run's
published levels (table above: legacy_static / legacy_both vs none); "after" = this change, from
the same run as the test evaluation (the legacy arm does not depend on the model):

| Lang | static_only before → after | both before → after | after, prefix-2 / prefix-3 (both modes) |
|---|---|---|---|
| es | −22.65 → **+0.04** | +0.04 → +0.04 | +0.03 / 0.00 |
| pt | −1.82 → **0.00** | −0.01 → 0.00 | 0.00 / 0.00 |
| sv | −0.12 → **0.00** | −0.04 → 0.00 | 0.00 / 0.00 |

(de −16.92, fr −16.42, it −1.70 before; those languages now ship an LM, so the legacy path only
applies until the LM loads — same fix. Dev "before" at prefix 2/3: es −2.26 / +0.02.) pt and sv
now also ship an LM; the table is what any LM-less language (es, and every other language) gets.

### Why `both` was ≈ 0 — and what it reveals

`UnifiedScore.combine` applies `max(static, learned)` in `both`. The learned boost is
`(1 + p)²` clamped to [1, 5], and exactly 1.0 when the store has nothing — so with an empty
store `both` = `max(static, 1)`: **every static value below 1 is discarded**. The legacy damage
was all sub-1 (the 0.1 clamps), so it vanished in `both`; the residue (pt −0.01, sv −0.04) was
English pairs BOOSTING the wrong word. So `both` with an empty store is not the same as
`static_only`, and that is the formula, not a learned-store bug.

The same flooring applies to the LMs: in the default `both`, an LM's backoff ratio (< 1 for words
outside the previous word's top 20) never bites, and `lm_both` beats `lm_static` at prefix-1 on
every language measured here (es +6.08 vs +4.96, pt +6.05 vs +5.41, sv +6.78 vs +5.98). That
suggests the backoff PENALTY costs prefix-1 accuracy in `static_only`. Not changed here
(`UnifiedScore`/`StaticContextLm.contextRatio` semantics, outside this change); recorded as a
follow-up: measure `contextRatio` floored at 1 on dev before touching it.

### S2 for pt and sv (shipped in `807c56bd`)

| Lang | asset B (cap 524,288) | retained heap B (cap 1.5 MB) | load ms, pure JVM (cold / warm median) | stored in APK (deflated) |
|---|---|---|---|---|
| pt | 351,738 | 576,600 | 4.7 / 2.1 | 237,526 + 3,307 sidecar |
| sv | 443,229 | 823,501 | 4.3 / 4.2 | 307,718 + 3,647 sidecar |

All six models resident at once: 4,545,885 B (`StaticLmAssetDriftTest`; GC deltas agree with the
array accounting to < 100 B). Release APK (`./build-on-termux.sh release --no-install`): arm64-v8a
23,164,285 B, **+556,576 B** against the 22,607,709 B de/fr/it build; the pt/sv models and
sidecars are 552,198 B of that as stored, the rest is other commits since. Load average 13–18
from other agents during these runs; the load times are indicative only. en and de rebuild
byte-identically from the refactored builder (model, sidecar, contributor list).

## LM ratio shape — PRE-REGISTRATION (2026-09-29, written and committed before any shape is measured)

Follow-up to "Why `both` was ≈ 0" above. Nothing in this section has been measured yet.

### Question

`BigramModel.getContextMultiplier` feeds `clamp(StaticContextLm.contextRatio(prev, w), 0.1, 10)`
into `UnifiedScore.combine`. For a word outside `prev`'s listed top 20 that ratio is the backoff
`alpha(prev)` < 1, and a listed word can also sit below 1. In the default `both`,
`max(static, learned)` with a learned boost that is ALWAYS ≥ 1 (`ContextModel.calculateBoost`
clamps to [1, 5], and "no evidence" is exactly 1.0) discards every static value below 1 — with
an empty store AND with a populated one. `static_only` applies them. The test tables above show
`lm_both` > `lm_static` at prefix-1 on es/pt/sv, i.e. the sub-1 part may cost accuracy.

### Candidate shapes (closed set; every shape is then clamped to [0.1, 10] as today)

| id | shape of r = `contextRatio` | note |
|---|---|---|
| `RAW` | r | status quo |
| `FLOOR_ONE` | max(r, 1) | = what `both` applies when the learned boost is 1 |
| `FLOOR_HALF` | max(r, 0.5) | softer floor |
| `SQRT_BELOW_ONE` | r ≥ 1 ? r : √r | tempered penalty, boosts untouched |

All four agree for r ≥ 1, so they differ only in the penalty side; `both` is therefore
invariant to the choice (checked, not assumed: the eval prints `lm_both`, and with no learned
rows `FLOOR_ONE` must equal it exactly).

### Metric, populations, rule

- **Dev metric** = the gate metric: `StaticLmTapEvalTest` with `STATIC_LM_EVAL_SPLIT=dev`, OOD
  **dev** split, top-3 Δ vs `none` at prefix 1/2/3 through the real `UnifiedScore.combine`
  (`static_only`), one extra arm per shape. Models: the six shipped assets (en, de, fr, it, pt,
  sv) and, eval only, the unshipped es candidate (`lmretry/final/es.cklm`, the retry's chosen
  model). es is reported and does NOT enter the rule (it ships no LM, so no user gets the shape).
- **Eligibility:** a shape is eligible iff, on every shipped language and every prefix 1–3,
  Δ(shape) ≥ Δ(`RAW`) − 0.05 pt (no gate cell regresses beyond a rounding-level tolerance), and
  empty-context deviation stays 0.
- **Choice:** the eligible shape with the highest MEAN dev prefix-1 Δ over the six shipped
  languages (unweighted). Ties within 0.01 pt → the earlier in the order `RAW`, `FLOOR_ONE`,
  `FLOOR_HALF`, `SQRT_BELOW_ONE` (status quo, then the simplest change). If no non-`RAW` shape is
  eligible, `RAW` stays and nothing changes in production.
- The choice is committed to this document before the test split is read. The **test** split is
  then read ONCE (all shapes printed, the choice already fixed) to report.
- **Production rule:** the chosen shape replaces `RAW` in `BigramModel` only if, on test, every
  shipped language has Δ(chosen) ≥ Δ(`RAW`) − 0.05 pt at prefix 1, 2 and 3, and every shipped
  language still passes its S1 gate under the chosen shape. Otherwise it is reported and not
  shipped. No second test read either way.

### Risks stated in advance

- A floor removes the only penalty the static LM has; if a language's dev gain comes from
  demoting frequent-but-unlikely words, `FLOOR_ONE` will lose there and be ineligible.
- Dev and test disagree by up to ~1 pt on the smaller treebanks (sv prefix-2, es prefix-1), so
  a dev win smaller than that is weak evidence; the test rule is the check.
