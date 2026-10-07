# Context-driven apostrophe disambiguation for swipe (2026-10-07)

**Question.** When a swipe decodes a letter surface that has both an apostrophe-free and an
apostrophe display form (`its`/`it's`, `were`/`we're`, `well`/`we'll`, `teams`/`team's`), can the
shipped static context LM (`assets/lm/en.cklm`) choose the auto-inserted form from the
PRECEDING word better than today's context-free placement — without hurting anything else?

Maintainer direction (2026-10-07): no in-stroke apostrophe gesture; grammar/context should
decide ("of its", "the cats"), and a pair that needs the FOLLOWING word (`the cat's` /
`the cats`) must stay a ranking with the alternative offered, never a forced form.

## 0. Pre-registration (written and committed BEFORE any accuracy number was computed)

### What exists today (verified in code at `8eb94e95`)

- Both English swipe engines decode letter-only surfaces; `swipe/ContractionOverlay.kt` adds the
  apostrophe forms afterwards. For a PAIRED base at rank 0 it splices at most one projection
  sibling at slot 1 (non-possessive with a known pairing frequency, or — when the decode is
  confident — a possessive), and puts the sibling AHEAD only when its corpus pairing frequency
  beats the base's lexicon frequency by `PROMOTION_MARGIN` (6 bytes) and it is not a possessive.
  No context of any kind is consulted (`ContractionOverlay.apply` has no context parameter; both
  adapters call it inside the engine, where no context exists).
- `SuggestionHandler.handleSwipePredictionResults` receives the overlaid slate. Its only
  context step is `rescoreWithContext` (learned bigrams; `swipe_context_rescoring`, default OFF
  by maintainer decision — see `SwipeContextRescorer`). The static LM is consulted for TAP
  prediction and next-word only; it never sees a swipe slate.
- REPLACE-bucket keys (`lets`, `cant`, `wont`, `whos`, `thats`, …) show only the apostrophe form
  (none ranks under `REAL_WORD_ORDINAL_MAX`), so there is no second form for context to choose.
  Those surfaces are OUT of scope for this change and are only measured (§3).

### Approach under test (arm B)

A pure function, `ContractionContextChooser`, applied to the overlaid slate: when slots 0 and 1
are two display forms of ONE letter surface (apostrophes/hyphens removed, case-folded), and the
active language is English, and a previous word exists in the current sentence segment, compare
`P(form | prev)` from the shipped static LM (with backoff, aliases installed exactly as
`BigramModel` does). Swap slots 0 and 1 when `ln P(slot1|prev) − ln P(slot0|prev) > τ`.
Never acts when: either form is unknown to the LM, `prev` has no LM context, slot 0 is a user
word (preserves `UserJoinerPreference` rule 0 and the user-`id`-beats-`i'd` contract), or the
slate has no same-surface pair at slots 0–1. Positional scores are kept (monotone). It only ever
SWAPS the two forms the overlay already shows, so the alternative stays one tap away.

Arm C (pre-registered fallback): identical, but a POSSESSIVE (`ContractionOverlay.isPossessive`)
is never swapped into slot 0 — today's "possessives never go ahead" rule kept.

Tuning grid, selected on DEV only (max DEV top-1 accuracy; ties → the more conservative cell,
i.e. larger τ, then `listed`):
`τ ∈ {0, ln 1.5, ln 2, ln 4}` × evidence ∈ {`any` (backoff allowed), `listed` (the challenger's
pair must be one of `prev`'s stored continuations)}.

### Population

Sentences: the static LM builder's 10 % held-out split (`heldout_en.txt`, in-domain, never in
training; sha256 `0e69cf21…`) and UD English-EWT dev/test surface text (OOD; `ood_dev_en.txt`
`f9d2c1ef…`, `ood_test_en.txt` `74ef0f29…`). LM `en.cklm` sha256 `8efe036a…114125a4`.

- **DEV** = even-numbered held-out lines + EWT dev. **TEST** = odd-numbered held-out lines +
  EWT test. τ/evidence are chosen on DEV and TEST is scored once.
- Tokenization: the device's `NextWordPredictor.contextFromEditorText` on the text before the
  token (typographic apostrophes folded to ASCII first, as the builder does). The previous word
  is the last context token of the same sentence segment.
- An **occurrence** is a token whose letter surface is an *ambiguous surface* — the overlay,
  given that surface as a confident rank-0 decode (scores 900/100 with a filler runner-up), shows
  two forms of it at slots 0–1 — and whose gold form (the token as written, lowercased) is one
  of the forms the overlay shows. Gold forms the overlay does not offer are counted separately.
- Swipe decoding is ORACLE (the surface is decoded correctly at rank 0); this isolates the
  display-form decision. The confident-decode assumption is the case where the overlay splices
  possessives today; it is stated, not hidden.

Metric: top-1 form accuracy (slot 0 == gold), arm vs current. Counts reported as occurrences,
distinct sentences and distinct (prev, gold) pairs, per surface and per class (non-possessive
projection vs possessive), plus top-2.

### Success bar (on TEST, positions with a previous word)

Arm B ships only if ALL hold; if B fails only B3/B4 on the possessive class, arm C is checked
against the same bar; otherwise NOTHING ships and only this evaluation is committed.

- **B1** pooled top-1 Δ ≥ +1.0 pt AND wins − losses ≥ 2·√(wins + losses) (sign test ≈ 2σ).
- **B2** OOD (EWT test) pooled top-1 Δ ≥ 0.
- **B3** per class (non-possessive, possessive) top-1 Δ ≥ 0.
- **B4** every surface with ≥ 50 TEST occurrences: top-1 Δ ≥ −1.0 pt.
- **B5** structural: no slate whose slots 0–1 are not same-surface forms is changed (pure test);
  sentence-initial positions unchanged (no previous word ⇒ no action, by construction); every
  existing contraction test green.

The harness is `src/test/kotlin/tribixbite/cleverkeys/swipe/ApostropheContextEvalTest.kt`
(opt-in: `-PgeoFull=true`, needs the local eval files; Assume-skips otherwise).

## 1. Stage 1 result (DEV selection, TEST scored once) — the pre-registered verdict is NOT MET

Run: worktree at `df5cc6b5` + the chooser and harness (the shared tree did not compile — other
agents' in-flight clipboard edits), `runPureTests -PtestClass=swipe.ApostropheContextEvalTest
-PgeoFull=true`. 1,995,751 tokens scanned; DEV 45,906 / TEST 45,996 occurrences.

DEV selection: arm B → τ = ln 1.5, `ANY` (92.19 % vs current 90.18 %); arm C → τ = 0, `ANY`
(91.73 %). `LISTED` was below `ANY` in every cell.

TEST, positions with a previous word (n = occurrences; sent = distinct sentences; pairs =
distinct (prev, gold)):

| Arm | Population | n | sent | pairs | current | arm | Δ pt | wins / losses |
|---|---|---|---|---|---|---|---|---|
| B | pooled | 39,460 | 32,056 | 14,347 | 90.26 % | 92.14 % | **+1.88** | 1,204 / 463 |
| B | OOD (EWT test) | 669 | 525 | 503 | 92.97 % | 92.83 % | **−0.15** | 16 / 17 |
| B | class non-possessive | 6,441 | 6,198 | 1,992 | 78.56 % | 86.99 % | +8.43 | 935 / 392 |
| B | class possessive | 33,019 | 27,479 | 12,355 | 92.55 % | 93.15 % | +0.60 | 269 / 71 |
| C | pooled | 39,460 | 32,056 | 14,347 | 90.26 % | 91.69 % | +1.42 | 998 / 437 |
| C | OOD (EWT test) | 669 | 525 | 503 | 92.97 % | 93.12 % | +0.15 | 15 / 14 |
| C | class non-possessive | 6,441 | 6,198 | 1,992 | 78.56 % | 87.27 % | +8.71 | 998 / 437 |
| C | class possessive | 33,019 | 27,479 | 12,355 | 92.55 % | 92.55 % | 0 | 0 / 0 |

Verdicts: **arm B: B1 PASS, B2 FAIL, B3 PASS, B4 FAIL** (`years` −1.32 pt, n = 760: 5 wins /
15 losses). **Arm C: B1–B4 PASS.** B5: 601,443 non-ambiguous slates checked under context, 0
changed; sentence-initial positions (6,536) unchanged by construction.

**Decision under the pre-registered rule: nothing ships from stage 1.** Arm B failed B2, which is
not one of the two conditions (B3/B4 on the possessive class) that opened the arm-C fallback;
arm C's pass therefore cannot be used as a stage-1 result. Arm C also differs from B in τ (0 vs
ln 1.5), so B's OOD miss cannot be attributed cleanly to possessives. Arm C is carried forward
only as a FIXED hypothesis to an independent confirmation (§2), pre-registered below before it
is run.

Per-surface TEST detail (arm B unless noted; current → arm, wins/losses):

| Surface | n | sent | current | arm B | arm C | B w/l | C w/l |
|---|---|---|---|---|---|---|---|
| its | 1,441 | 1,394 | 47.19 % | 72.17 % | 72.17 % | 728 / 368 | 728 / 368 |
| were | 2,408 | 2,352 | 93.02 % | 93.06 % | 93.06 % | 3 / 2 | 3 / 2 |
| well | 884 | 874 | 88.01 % | 88.35 % | 88.35 % | 3 / 0 | 3 / 0 |
| ill | 334 | 331 | 79.94 % | 82.63 % | 82.63 % | 9 / 0 | 9 / 0 |
| shed | 162 | 162 | 9.26 % | 90.12 % | 90.12 % | 146 / 15 | 146 / 15 |
| hell | 121 | 121 | 37.19 % | 47.93 % | 62.81 % | 13 / 0 | 76 / 45 |
| shell | 40 | 40 | 17.50 % | 82.50 % | 82.50 % | 33 / 7 | 33 / 7 |
| id / wed / hes / shes | 288 / 42 / 447 / 236 | | 94.79 / 95.24 / 100 / 100 % | unchanged | unchanged | 0 / 0 | 0 / 0 |
| years (poss.) | 760 | 749 | 95.53 % | 94.21 % | 95.53 % | 5 / 15 | 0 / 0 |
| others (poss.) | 229 | 228 | 91.27 % | 98.25 % | 91.27 % | 16 / 0 | 0 / 0 |
| fathers (poss.) | 74 | 74 | 16.22 % | 83.78 % | 16.22 % | 60 / 10 | 0 / 0 |
| worlds / peoples / mothers (poss.) | 53 / 44 / 43 | | 22.6 / 18.2 / 30.2 % | 77.4 / 72.7 / 69.8 % | unchanged | 41/12, 32/8, 30/13 | 0 / 0 |

Concentration: of arm C's 998 wins, 728 are `its` and 146 `shed` (87.6 %); of its 437 losses,
368 are `its`. The gain is real on distinct contexts (`its`: 540 distinct (prev, gold) pairs)
but it is mostly ONE surface.

Findings outside the decision:

- **`is` → `i's` and `as` → `a's` are spliced at slot 1 of every confident `is`/`as` swipe**
  (15,039 + 3,684 TEST occurrences; bin-derived possessives, no length floor on the swipe path,
  unlike the tap path's `ContractionInjectionPolicy`). Harmless to top-1 but a visible slot-1
  junk entry; a separate fix candidate. They also dilute the possessive class counts above.
- Possessive bases whose possessive is overwhelmingly what is written (`toms` 452, `marys` 294,
  `todays` 41, `companys` 30: 0 % top-1 now) are untouched by context because the LM does not
  name the bare surface — a data question (REPLACE vs PAIRED), not a ranking one.
- REPLACE keys hide a real bare word: `lets` written bare 53 times vs `let's` 929 (TEST+DEV);
  every bare `lets` currently becomes `let's`. Out of scope (bucket change, tap path too).
- Gold forms the overlay never offers: mostly plural possessives `parents'` (35), `kids'` (13).

## 2. Stage 2 — independent confirmation of the FIXED arm C (pre-registered before running)

Hypothesis (no tuning): arm C exactly as selected — τ = 0, `ANY`, possessives never promoted.

Population: Common Voice `sentence-collector.en.txt` (61,513 lines, sha256 below), every line,
same tokenization and occurrence definition as stage 1. Never used by the LM builder (its
sources are Leipzig + Tatoeba only) nor by stage 1. Caveat: public-domain sentence sources can
overlap Tatoeba text; not checkable without the training tarball's text, stated rather than
hidden. Register: largely literary/conversational, i.e. a third domain.

Bar (all must hold, positions with a previous word): **S2-1** Δ ≥ +1.0 pt AND wins − losses ≥
2·√(wins + losses); **S2-2** non-possessive class Δ ≥ 0; **S2-3** every surface with ≥ 50
occurrences Δ ≥ −1.0 pt; **S2-4** B5 structural (0 changed non-ambiguous slates). If all hold,
arm C ships as `ContractionContextChooser.SHIPPED`; otherwise nothing ships.
