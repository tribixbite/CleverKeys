# Static LM ratio shape: FLOOR_ONE vs RAW on fresh populations (pre-registration, 2026-10-25)

Maintainer-approved re-run (2026-10-25) of the question left open by
[2026-09-29 "LM ratio shape"](2026-09-29-static-lm-multilingual.md#lm-ratio-shape--pre-registration-2026-09-29-written-and-committed-before-any-shape-is-measured):
should `StaticContextLm.RatioShape.FLOOR_ONE` (max(r, 1)) replace `RAW` as the shape
`BigramModel.getContextMultiplier` applies to the static LM's `contextRatio` in
`context_source = static_only`?

**This section was written and committed before any shape was measured on the populations below.**
The commit that adds this file is the proof; results are appended under "Results" afterwards,
in a later commit. The 09-29 OOD dev and test splits are SPENT and play no part in the decision.

## Why a new run, and what was already known

- 09-29 dev (OOD UD dev splits): `FLOOR_ONE` +0.48 pt mean prefix-1 top-3 over the six shipped
  languages, but English prefix-1 −0.15 pt, below the −0.05 tolerance, so it was ineligible.
- 09-29 test (read once, after the choice): `FLOOR_ONE` ahead in every shipped cell beyond
  tolerance; English prefix-1 +0.03. In-domain held-out 1/20 sample: en −0.16, every other
  language +0.31 … +0.86.
- The hypothesis tested here was therefore formed on spent data: **the backoff penalty helps (or
  is neutral for) English, whose model is the strongest, and costs accuracy in the other five
  languages**. Fresh data tests it; the spent splits cannot.
- Default users are unaffected either way: `context_source = both` already applies
  `max(static, learned) ≥ max(static, 1)`. Only `static_only` users see the shape.

## Populations (fresh; chosen by the rule below before any was evaluated)

Two populations per shipped language (en de fr it pt sv), plus their union, written by the
committed `scripts/build_static_lm_fresh_eval.py` (sources pinned by sha256 there):

- **`ud`** — a UD treebank no static-LM round has read: the language's **PUD** treebank (1,000
  news/wiki sentences; the one treebank all six languages have; CC BY-SA 3.0, sv 4.0). Italian's
  PUD was its 09-29 gate test split (spent), so Italian uses the two permissively licensed Italian
  treebanks of contemporary prose not used before: **ParlaMint** (train + test, parliament) and
  **Valico** (learner essays), both CC BY-SA 4.0. NC-licensed treebanks (GUM, LinES, ParTUT,
  ISDT, …) are excluded, as in 09-29.
- **`web`** — a **Leipzig 10K web corpus** (CC BY 4.0) of a different national domain than the
  language's training web corpus, nearest in year: en `eng-uk_web-public_2018` (training: .com
  2018), de `deu-at_web_2019` (training: .de 2021), fr `fra-be_web_2013` (training: .fr 2013;
  .be and .ca tie on year, alphabetical first), it `ita-ch_web_2016` (training: .it 2019; .ch and
  .sm tie, alphabetical first), pt `por-mz_web_2016` (training: .pt 2015 + Brazilian news), sv
  `swe-se_web_2020` (Leipzig offers no other Swedish domain, so a different year; the training
  `swe-se_web_2023` carries weight 0 in the shipped sv model, which counts news + Tatoeba).
- **`pooled`** — `ud` followed by `web`, one file per language. **The decision population.**

Leakage control on the eval side (the shipped models are not rebuilt): every candidate segment is
segmented and tokenized exactly as `build_static_lm.py` does and dropped if its sentence key occurs
anywhere in that language's build inputs (Leipzig, Tatoeba, second Leipzig corpus; every segment,
held-out or not), in that language's earlier eval files (09-29 OOD dev/test, held-out), or earlier
in the fresh populations (so `web` never repeats a `ud` sentence).

| Lang | `ud` sentences / tokens | `web` sentences / tokens | dropped as overlap (ud / web) | `pooled` sentences |
|---|---|---|---|---|
| en | 980 / 16,438 | 9,099 / 152,169 | 2 / 13 | 10,079 |
| de | 1,042 / 16,358 | 10,698 / 146,559 | 10 / 33 | 11,740 |
| fr | 903 / 15,995 | 9,540 / 145,992 | 1 / 41 | 10,443 |
| it | 940 / 12,654 | 9,556 / 154,833 | 7 / 19 | 10,496 |
| pt | 962 / 16,255 | 8,829 / 148,758 | 2 / 18 | 9,791 |
| sv | 985 / 15,777 | 9,986 / 130,021 | 0 / 20 | 10,971 |

Sizes: the English pooled population has ~158K positions with context (tokens − sentences), about
10× the spent English dev split (15,544 prefix-1 positions); English `ud` alone (~15.5K positions
before the lexicon filter) is at most the old size. The harness reports the exact scorable n per
cell. Only these sizes and the overlap counts were looked at before this commit; no model was run
on any fresh file.

## Candidates

- **Decided:** `FLOOR_ONE` vs `RAW` (status quo), per language.
- **Reported only, cannot be chosen:** `FLOOR_HALF`, `SQRT_BELOW_ONE` (the harness prints them).

## Metric

The 09-29 gate metric, unchanged code: `StaticLmTapEvalTest` with `STATIC_LM_EVAL_SPLIT=dev`,
`STATIC_LM_EVAL_DIR=~/.cache/cleverkeys-corpora/static-lm-fresh/<pop>`, shipped `lm/<lang>.cklm`
assets, top-3 Δ vs `none` at prefix 1, 2 and 3 through the real `UnifiedScore.combine` in
`static_only` (the `SHAPE` lines). For language L and prefix k:
d_k(L) = Δ_k(`FLOOR_ONE`) − Δ_k(`RAW`), in points, on `pooled`.

## Rule (per language — the registered choice)

`FLOOR_ONE` becomes L's shape iff ALL of:

1. **Gain:** d_1(L) ≥ **+0.10** pt on `pooled` (a clear gain at prefix-1, ≈ 2–3 standard errors of
   a paired difference at ~150K positions, not a tie);
2. **No regression elsewhere:** d_2(L) ≥ −0.05 and d_3(L) ≥ −0.05 on `pooled` (the 09-29
   tolerance, now on a population ~10× larger);
3. **No register-specific loss:** on `ud` and on `web` separately, d_1 ≥ **−0.30** (about two
   standard errors of the ~14K-position `ud` population);
4. **Empty context:** deviation 0 for both shapes;
5. **S1 still holds:** L passes its S1 gate under `FLOOR_ONE` on its standing gate population
   (prefix-1 ≥ +5, prefix-2 ≥ +2, prefix-3 ≥ 0). Those numbers were read on 09-29 for every shape
   (FLOOR_ONE: en +10.46/+4.37/+2.42, de +5.55/+4.98/+3.73, fr +7.29/+6.23/+4.00, it
   +7.85/+7.53/+4.27, pt +6.05/+6.23/+3.63, sv +6.78/+3.69/+1.96 — all pass); stated openly,
   this condition is already known to hold and is a safety constraint, not a selection input.
   The S1 gate on `pooled` is reported beside it.

Otherwise L keeps `RAW`. Each language is decided independently; `es` ships no LM and is not run.

**Why per language rather than one global shape.** Each language's model is a separate artefact
built from different corpora, weights and sizes (en: the largest corpus and curated contraction
aliases; sv: news + Tatoeba × 2), so how much the backoff penalty is worth is a property of each
model, and a `static_only` user of L only ever experiences L's shape. A single global rule lets
one language's sampling noise veto the others (the 09-29 outcome hinged on one English cell at
≈ 1 s.e.) or imposes the others' gain on a model it may hurt. The cost — six decisions instead
of one — is bounded by deciding a single candidate per language, requiring a prefix-1 gain well
above noise on a ~150K-position population, and making `RAW` the default when evidence is short.

**Reported, not deciding:** what the 09-29 global rule would conclude on `pooled` (FLOOR_ONE
eligible iff every language and prefix has d_k ≥ −0.05; chosen iff the mean d_1 > +0.01).

## Production consequence (fixed now)

- If no language passes: nothing changes; `SHIPPED_RATIO_SHAPE` stays `RAW`.
- If every language passes: `SHIPPED_RATIO_SHAPE = FLOOR_ONE`.
- If some pass: a per-language shape (`StaticContextLm.shippedRatioShape(language)`, a fixed map
  of the passing languages to `FLOOR_ONE`, `RAW` otherwise) used by
  `BigramModel.getContextMultiplier` for the requested language and by the eval harness's
  shipped arms; tests pin the map; spec and CHANGELOG 2.0.0 record it.

## Risks stated in advance

- **`web` is near-in-domain.** It comes from the same Leipzig pipeline as every training web
  corpus, and the 09-29 in-domain sample (also Leipzig + Tatoeba) showed en −0.16 under
  `FLOOR_ONE`. English may well stay `RAW` for that reason; this is accepted, not avoided: web
  text is the register closest to keyboard text that is available fresh in all six languages.
- **`pooled` is ~90 % `web`,** so the decision mostly reflects web text; rule 3 guards the
  news/wiki register separately, with a tolerance sized for its smaller n.
- **PUD is translated** (from English, German and others) — translationese for de/fr/pt/sv.
  Italian `ud` mixes parliament speech and learner essays; learner misspellings fall out as
  out-of-lexicon targets, not as errors.
- **Near-duplicates** are not removed (only exact sentence keys); sv `web` is the same domain as
  its training web corpus, three years earlier, but that corpus has weight 0 in the sv model.
- **Thresholds are judgement:** +0.10 and −0.30 are set from the expected standard errors before
  seeing data; a true effect just under +0.10 keeps `RAW` (asymmetric toward the status quo).
- **Multiplicity:** six per-language decisions; a false switch needs a ≥ +0.10 gain by chance on
  ~150K positions, which the size makes unlikely, and the losing direction defaults to `RAW`.

## Finality

The decision is final for this question on this evidence: the rule is applied mechanically to the
first and only run on these populations, and there is no second read, no re-run with changed
populations or thresholds, and no reuse of the 09-29 splits. A later attempt would need new
populations and its own pre-registration.

## Results

(appended after the run)
