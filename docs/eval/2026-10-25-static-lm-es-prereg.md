# Spanish static next-word LM — third attempt: PRE-REGISTRATION (2026-10-25)

Written and committed **before** any model in this document is built or scored. Maintainer
decision 2026-10-25 (`memory/todo.md` Open now 11): "research new method and corpus, try one or
both if promising". Project rule: a new attempt needs a NEW stated reason, not another try.

## What happened before (on record, unchanged)

| Attempt | Model | Gate population | prefix-1 Δ (≥ +5) | prefix-2 (≥ +2) | prefix-3 (≥ 0) | S1 |
|---|---|---|---|---|---|---|
| Pilot | `spa_web_2016_300K` + Tatoeba × 0.5 | UD Spanish-GSD test, 339 sentences, 4,539 gated prefix-1 positions | +4.69 | +4.74 | +3.26 | FAIL |
| Retry | + `spa_wikipedia_2021_300K` λ 0.75, Tatoeba × 0.1 (dev-chosen) | same | **+4.96** | +5.13 | +3.66 | FAIL by 0.04 pt (about 2 hits) |

Both are in `docs/eval/2026-09-29-static-lm-multilingual.md`. GSD test has now been read twice
for es. It will not be a gate again.

## Diagnosis (research done before this registration, no es model scored)

Every number below comes from dev splits, from counts already published, or from sentence keys
(no metric). Scripts are in the session scratchpad: `diag.py`, `diag2.py`, `overlap.py`.

1. **The gate population is too small for the size of the margin.** 339 sentences give about
   ±0.7 pt (1 s.e.) on a 42 % rate. The retry missed by 0.04 pt. Its dev result was +5.37 on
   16,211 positions. The miss is inside the noise, so it does not show the model is worse than
   the line.
2. **Spanish has the highest no-context baseline, which leaves the least headroom for an absolute
   gate.** `none` prefix-1 top-3 on test: es 42.19 %, fr 40.80, sv 38.06, de 34.12, pt 33.03.
   The retry's share of remaining errors removed is (47.15 − 42.19)/(100 − 42.19) = **8.6 %**.
   For the shipped languages: de 7.8 %, pt 8.1 %, sv 9.7 %, fr 12.4 %. So es removes more of its
   remaining errors than de or pt, which shipped. The gate stays absolute and is not changed here.
   This is context for the decision, not a selection input.
3. **The Spanish model cannot condition on Spanish's commonest function words (new finding).**
   The model vocabulary is the typing lexicon ∪ contraction forms. `es_enhanced.bin` holds 50,000
   words and **not one single-letter word**. The en lexicon has all 26. So `y` and `e` (and),
   `o` and `u` (or) and `a` (to/at) are never previous words in the es model. A tap after them
   gets no context (ratio 1) and the next-word bar falls back to the seed. On GSD **dev**, 888 of
   16,198 gated positions (5.5 %) follow `y`/`a`/`o`/`e`: `y` 473, `a` 351, `o` 51, `e` 13. They
   are the most common unknown previous words, and the next is `km²` at 15. The previous word is
   known for only 89.5 % of es positions against 98.1 % for en. (fr `à`/`a`, pt `a`/`o`/`e`/`é`
   and sv `i` have the same gap. They already ship, so they are not touched here.)
4. **Checked and ruled out:**
   - **¿ ¡.** They are not letters. The builder's `RUN` regex and the device's
     `contextFromEditorText` both treat them as separators. A `¿Qué` gives `qué` on both sides,
     and only 10 occur in GSD dev.
   - **Accents.** NFC is on, the lexicon has 9,030 accented entries, and only 51 OOV dev tokens
     have an accent-stripped twin in the lexicon.
   - **`del`, `al`.** They are ordinary lexicon words with no apostrophe, and es ships no REPLACE
     contractions.
   - **Clitics.** `dármelo` is one token on both sides.
   - **Vocabulary cap.** The retry model is 405,743 B, under the 524,288 B cap.
   - **Backoff weight.** The `FLOOR_ONE` question is another agent's pre-registration, and this
     document does not touch `StaticContextLm.RatioShape`.

## The two new reasons

- **R1 — an unused, larger gate population.** The new gate is **UD Spanish-AnCora test**
  (CC BY 4.0, newswire: CESS 1,456 + 3LB 265 sentences), pinned at commit
  `20adddbfcdd773c6dc97ba48ea11ca9364e74185`. Its sha256 is
  `679f1c05ca654aa26801c80319df45f8f97606718fb534b91755b48f3e67fa6f`. The population is built
  exactly as every other one: `# text` lines, NFC, split at `.?!`/newline, 2–30 tokens. That
  gives **1,213 sentences (1,206 distinct)**, 3.6× GSD test, so about ±0.37 pt. No es model has
  ever been scored on AnCora. Shared sentence keys: AnCora ∩ GSD is 0. AnCora ∩ the training
  corpora is spa_web 3 dev / 1 test, spa_wikipedia 4 / 2, Tatoeba 18 / 9.
  **AnCora dev** (sha256 `1a132068062edf2654fb45b41cd4c59ba7e5c18e7c5f642ed5a9cc351fd9f0fe`,
  1,179 sentences) is the new selection population.
- **R2 — context-only single-letter function words (es-scoped method change).** The es config
  gains `a`, `e`, `o`, `u`, `y` as **context-only** words. They are counted as PREVIOUS words, so
  their rows hold top-20 continuations from the lexicon. They are never stored as a continuation,
  so no next-word or tap candidate the app cannot show is ever named. `c(prev, *)`, the unigram
  totals and every existing previous word's listed continuations are mathematically unchanged.
  Only five new rows are added. The linguistic reason: these are the language's coordinating
  conjunctions and its commonest preposition, which every keyboard user types, and the typing
  lexicon omits them only because it holds no single letters.

## Candidates (closed set)

Both use the committed retry configuration (spa_web_2016 × 0.25 + spa_wikipedia_2021 × 0.75 +
Tatoeba × 0.1, MIN_COUNT 3, top-20, CKLM v1, NFC, artefact filter, 512 KiB cap). One input change
applies to both: the training exclusion set grows from GSD dev+test to **GSD dev+test ∪ AnCora
dev+test**. That is the existing leakage rule applied to the new population. It drops about 37
sentences, so C1 is not byte-identical to the retry model; its sha256 is recorded at build.

- **C1** = the retry model, rebuilt with that exclusion. It tests R1 alone.
- **C2** = C1 + the context-only words `a e o u y`. It tests R1 + R2.

## Selection on dev (before any test read)

One guarded `StaticLmTapEvalTest` grid run per dev population
(`STATIC_LM_EVAL_CANDIDATES=<root>`, `STATIC_LM_EVAL_SPLIT=dev`, `STATIC_LM_EVAL_LANGS=es`): AnCora
dev and GSD dev. The metric is the gate metric: `lm_static` − `none` top-3 at prefix 1/2/3, with
the shipped ratio shape (`RAW`, unchanged).

**Rule.** C2 is chosen iff all of these hold on BOTH dev populations:

- prefix-1 Δ(C2) ≥ Δ(C1) + 0.10 pt;
- every prefix 1–3 has Δ(C2) ≥ Δ(C1) − 0.05;
- empty-context deviation is 0;
- the size is ≤ 524,288 B.

Otherwise **C1** is chosen. The choice is committed to this document before the test split is
read.

## Gate (one test read, the chosen model only)

- Population: UD Spanish-AnCora **test**, as above. It is read once.
- S1 as for every language: `lm_static` vs `none` top-3 at prefix-1 ≥ +5 pt, prefix-2 ≥ +2 pt,
  prefix-3 ≥ 0, and empty-context deviation exactly 0, through the Kotlin path
  (`UnifiedScore.combine`, shipped defaults, shipped `RAW` shape).
- The following are reported but not gated, and cannot change the decision: the in-domain
  held-out (1/20 sample), the `lm_both` arm, the other ratio-shape arms, distinct sentence /
  previous-word / pair counts, and **the chosen model on GSD test** (a third GSD read, to keep the
  record complete; a GSD miss there does not block a pass on the larger AnCora population, and
  a GSD pass does not rescue an AnCora fail).
- **Shipping requirements on a PASS** (CLAUDE.md "Static LM language guard"):
  - the model is rebuilt from the committed builder, byte-identical to the evaluated one, into
    `src/main/assets/lm/es.cklm` + `es.json`;
  - `scripts/data/tatoeba-contributors-es.txt` is added (W = 0.1 > 0);
  - a NOTICE line names `spa_web_2016_300K` and `spa_wikipedia_2021_300K` (Leipzig, CC BY 4.0)
    and Tatoeba;
  - `StaticLmAssetDriftTest` is green. For C2 it learns the sidecar's context-only words: they
    are allowed vocabulary, and they never appear as a continuation;
  - the spec, CHANGELOG and README language lists are updated, and the APK size impact is noted.
- **On a FAIL:** the failure is recorded here and es stays unshipped. AnCora test is then spent
  for es, and **UD Spanish-PUD** (1,000 sentences, CC BY-SA 3.0, not read) is the only unused UD
  population left.

## Risks stated in advance

- **Swapping the test population after two failures could be exam shopping.** Four things
  mitigate it:
  - the population is fixed here before any es model is scored on it;
  - it is chosen for size, not fit. AnCora is news, while the retry's Wikipedia mix was chosen for
    GSD's encyclopedic register, so the register shift works against the candidate;
  - the model is either frozen (C1) or carries one pre-declared change (C2);
  - there is one test read, and both GSD failures stay on record.
- **The C1/C2 selection gives C2 an optimistic dev number.** That is why it needs a +0.10 pt win
  on two independent dev populations, not one.
- **The 30-token cap drops long sentences.** AnCora has 1,880 segments and 1,213 are kept; the cap
  applies to every population alike.

## Dev results and choice (2026-10-25, committed before the test split is read)

**Builds.** Builder at `a4b92efd`, built `--offline` into the session scratchpad (never under
`src/main/assets`).

- en rebuilds byte-identically: `en.cklm` sha256 `8efe036a…125a4`, the sidecar, the contributor
  list and all three eval files. The builder change is inert for en.
- **C1:** 405,743 B, vocab 15,572 / prevs 14,817 / pairs 57,762, sha256
  `0ad97cd5426d3f655db4494f83ec50543ed8a73f6cdbfd6b1a7ea028c47d5ee2`.
- **C2:** 406,041 B (+298 B), prevs 14,822 / pairs 57,850 (the 5 new rows, +88 pairs), sha256
  `5dcf812c197b25ce633344a0592fd5bf52ec1aa8e025394c4b27b411a88c4aea`.
- The AnCora exclusion dropped 5 / 29 / 17 training sentences (spa_web / Tatoeba / spa_wikipedia).
- The builder's own log also prints a Python next-word number on GSD test, as it did for the
  retry. That number was used for nothing.

**Runs.** Two guarded `StaticLmTapEvalTest` grid runs (`STATIC_LM_EVAL_SPLIT=dev`). They ran
from a clean `git worktree` at HEAD, because an uncommitted layout edit by another session in the
shared tree failed `processDebugResources`. The box's load average was about 12–15; the counts are
deterministic.

`lm_static` − `none`, top-3, shipped `RAW` shape:

| Dev population | n (prefix-1) | C1 p1 / p2 / p3 | C2 p1 / p2 / p3 | empty dev. |
|---|---|---|---|---|
| AnCora dev: 1,179 sent. (1,161 distinct), 18,142 pos / 5,136 prev / 13,937 pairs | 16,474 | **+3.78** / +4.58 / +2.90 | +3.57 / +4.55 / +2.96 | 0 / 0 |
| GSD dev: 1,215 sent., 18,399 pos / 5,653 prev / 13,921 pairs | 16,211 | **+5.37** / +6.39 / +4.63 | +5.22 / +6.38 / +4.73 | 0 / 0 |

**Choice: C1.** C2 loses at prefix-1 on both dev populations (−0.21, −0.15), so it misses the
+0.10 requirement. C1 on GSD dev reproduces the retry's +5.37 exactly, so the extra exclusion did
not move it.

C2 raised the share of positions whose previous word the LM knows from 83.8 % to 88.5 % (AnCora
dev) and from 82.6 % to 87.9 % (GSD). It still lost, because under `RAW` a known previous word
also brings the backoff PENALTY (< 1) for every unlisted candidate. Under the `FLOOR_ONE` arm,
which is reported and not selected on, C2 beats C1 (AnCora +4.85 vs +4.83, GSD +6.80 vs +6.61).
That is the same penalty effect the ratio-shape follow-up studies. It is recorded here, and it is
not used.

**Expectation, stated before the test read.** C1 is at +3.78 on AnCora dev, 1.2 pt below the line.
A pass on AnCora test is unlikely. The test is still read once, as registered.
