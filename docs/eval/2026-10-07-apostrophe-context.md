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
