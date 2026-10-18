# Noisy-channel ranking for tap autocorrect (`devixe`, `shated`)

Date: 2026-10-18. Scope: tap-typing space-bar autocorrect (`WordPredictor.autoCorrect`), English
on the shipped `en_enhanced.bin` (QWERTY) and Hebrew on the shipped `langpack-he.zip` with the
`hebr_1_il` letter grid. Base: origin/main a40ce7d0.

## 0. The problem

Maintainer report, 2026-10-18: `devixe` commits `decide` instead of `device`, and `shated` is
not corrected to `shared` at all.

**Ranking (`devixe`).** The sweep (step 4 of `autoCorrect`) keeps the best candidate pairwise.
A candidate more than `SCORE_TIEBREAK_GAP` (0.10) better on raw score wins outright. Inside the
band, structural rules run (alias privilege; one transposition beats a multi-substitution
candidate), and then frequency decides. One neighbour-key slip costs about 0.02 of raw score on
a six-letter word, and even the most distant wrong key costs 1/6 = 0.167. So the band treats
0, 1 or 2 nearby errors as a tie. For `devixe`: `device` scores 0.981 (1 neighbour
substitution, rank 115), `decide` 0.961 (2 substitutions, rank 111). A 4-rank frequency
difference, about 1.6 % of the rank scale, beats a whole extra wrong key. The 0.10 band was
calibrated on 29 hand-picked cases (`questin`, `quuestion`, `tfe`) that needed frequency to
win, and the number of edits never enters the ordering.

**Inflection guard (`shated`).** Step 1.5 leaves the typed word alone when stripping a regular
suffix gives a dictionary stem of 4+ letters. `shated` − `ed` = `shat` (rank 188), so the
typed word is accepted as a past tense. The guard was written for long technical inflections
that the dictionary lacks (`immunizations` must not become `organizations`). With the guard
bypassed, the band rule commits `states` (2 substitutions) over `shared` (1, rank 115).

Both figures come from a Python re-implementation of the sweep over the shipped `.bin`
(scratch, not committed), which agreed with the device on `devixe → decide`. The harness below
re-measures everything through production code.

## 1. Pre-registration (written and committed before any outcome under the new rule was computed)

When this section was committed, no autocorrect output under rule C had been computed on any
set, and the harness did not exist yet.

### 1.1 Rule C (noisy-channel cost)

The candidate set is unchanged. It is every dictionary word that passes the sweep's existing
gates: length difference ≤ `autocorrect_max_length_diff`, the required prefix, not disabled,
gate 1 (same length: ≥ 50 % exact positions and ≤ 2 substitutions, or an adjacent
transposition; different length: weighted edit distance ≤ length difference + 0.5) and raw
score ≥ `autocorrect_char_match_threshold`. Rule C only changes which candidate wins.

For typed word `t` and candidate `c`:

```
cost(c) = E(t, c) + λ · r(c)
r(c)    = (F_max − f(c)) · 255 / F_max        rank steps; 0 = the most frequent word
E(t, c) = optimal-string-alignment edit distance with
          substitution  a→b : 1 + s₁ · keyDistance(a, b)    (keyDistance ∈ [0, 1], layout-aware)
          extra letter in t (deletion) : g,   or g₂ when it repeats a neighbouring letter of t
          missing letter of c (insertion) : g, or g₂ when it repeats a neighbouring letter of c
          adjacent transposition : τ
```

`f` is the predictor's frequency for the word (for an alias key, the frequency the alias merge
assigns). On the shipped `.bin`, one rank step is one byte of the rank scale, so `r` is the
rank. The rank histogram grows smoothly and exponentially with rank, which is consistent with a
log-frequency bucketing, so `λ · r` acts as a log-probability term. `1/λ` is the number of
rank steps that equal one wrong key.

**Winner:** minimum cost. Ties (|Δcost| < 1e-6): an alias key beats a non-alias, then the higher
frequency, then the lexicographically smaller word. This is a total order, unlike the band
fold. The frequency floor and the custom-word exemption are applied to the winner afterwards,
exactly as today (`isCommittable`).

**Rule C replaces two steps when it is active.** Step 1.45 (DD-M, dropped doubled letter) is
skipped, because g₂ models that typo inside E. Step 1.4 (doubled-letter collapse) is kept.
Steps 0, 1, 1.6 and 2 are unchanged.

**Guard G (inflection guard under rule C).** When step 1.5 finds a qualifying stem (length ≥ 4,
in the dictionary), the typed word is kept only if

```
γ + λ · r(stem*) ≤ cost(winner)
```

where `stem*` is the most frequent qualifying stem. Otherwise the sweep's winner is committed.
`γ = ∞` is today's guard (always keep).

### 1.2 Data

The data is the same as in `2026-10-11-dropped-double-margin.md` §1.2 and
`2026-10-12-neighbour-substitution-tiebreak.md` §1.3, with the same files, local only, and the
same dictionary filter (target in the dictionary, typo not in it and not an alias key, letters
only):

- **GTC**: `gtc-pairs.tsv`; DEV = even last hex digit of the commit SHA, TEST = odd.
- **S-adj**: every single QWERTY-neighbour substitution of the top-10,000 words of 3+ letters,
  minus typos that are dictionary words or alias keys; DEV when the FNV-1a hash of the base word
  is even.
- **R-typed** (`wikipedia.dat` + `aspell.dat`) and **R-hand** (`missp.dat` +
  `holbrook-missp.dat`): Birkbeck. TEST only.
- **C-oov (changed from the earlier docs: now split)**: `words_alpha.txt` words of 3–20 letters
  that are not in the dictionary and not alias keys. These are valid words the guard and the
  sweep must leave alone. FNV-1a hash of the word: even → C-oov-DEV, odd → C-oov-TEST, and each
  keeps only words whose `(hash >>> 1) % 4 == 0` (a quarter) to bound the run time. C-oov-DEV
  exists because guard G needs a DEV set of valid out-of-dictionary words.
- **Hebrew**: the 11 `HebrewLayoutAutoCorrectTest` cases plus `תושה` → `תודה` (named), and
  **HE-adj** (every single `hebr_1_il`-neighbour substitution of the top-500 pack words), TEST
  only.

Baseline = shipped behaviour today (band fold, DD-M margin 64, `NeighbourTiebreak.OFF`, guard
always keeps). Outcomes per input against the baseline: **fix**, **break**, **swap**, as in the
earlier docs. They are reported in raw pairs, distinct target words and, for GTC, commit
occurrences. On C-oov a "break" is a new change (baseline left the valid word, rule C changed it).

### 1.3 Harness

`src/test/kotlin/tribixbite/cleverkeys/autocorrect/ChannelRankingEval.kt` is a MockK-runner
class with no `Test` suffix, so CI never runs it. Its setup is the same as
`NeighbourTiebreakEval`. Production exposes the steps before the sweep and the gated candidate
list through an internal seam, plus the pure ranking function. The harness computes `E` once per
(candidate, edit-weight combination) with the production cost function and ranks with the
production ranking function, so grid outcomes equal production outcomes by construction.
**Soundness check:** at the chosen configuration, on 2,000 seeded DEV inputs, the full
production `autoCorrect` must give exactly the harness's output (0 mismatches, or the run is
void).

### 1.4 Tuning on DEV

Grid (fixed now):

- `s₁ ∈ {0, 0.5, 1, 2, 4}`
- `g ∈ {0.8, 1.0, 1.25, 1.5}`
- `g₂ ∈ {0.3, 0.6, 1.0}`
- `τ ∈ {0.6, 0.9, 1.2}`
- `λ ∈ {0.005, 0.0075, 0.01, 0.015, 0.02, 0.03, 0.04, 0.06}`

That is 1,440 configurations, all with γ = ∞. A configuration is **admissible** on DEV when:

1. **C1.** GTC-DEV distinct net fixes > 0, and distinct breaks ≤ 0.5 × distinct fixes.
2. **C2.** S-adj-DEV distinct net fixes > 0, and breaks ≤ 0.8 % of pairs, in raw pairs and in
   distinct base words.
3. **C3.** Named cases (T5 below, except `shated`) all hold.

The chosen configuration is the admissible one with the most GTC-DEV distinct net fixes. Ties
go to fewer S-adj-DEV distinct breaks, then the first in grid order (`s₁, g, g₂, τ, λ`
ascending, outermost first).

Why 0.5 and not the 0.25 of the NB doc: NB was one extra rule on top of a calibrated fold. Rule C
replaces the whole within-band ordering, so it reorders many more inputs in both directions.
A 2-to-1 ratio in distinct words with a positive net is the bar for replacing the ranker. The
S-adj limit is unchanged.

**Then γ**, at the chosen configuration: `γ ∈ {0.5, 1, 1.5, 2, 3}` is admissible when C-oov-DEV
new changes ≤ 0.5 % of C-oov-DEV words, GTC-DEV distinct net ≥ the γ = ∞ net, C2 still holds,
and every named case holds, including `shated` → `shared`. The chosen γ is the admissible one
with the most GTC-DEV distinct net. Ties go to the larger γ (more conservative). If none is
admissible, γ = ∞ ships and `shated` stays as typed.

If no configuration is admissible, nothing ships and TEST is not used for selection.

### 1.5 Ship gate on TEST (one evaluation of the chosen configuration; all must hold)

- **T1. GTC.** GTC-TEST distinct net fixes > 0, and distinct breaks ≤ 0.5 × distinct fixes.
- **T2. Birkbeck.** R-union (R-typed ∪ R-hand) distinct net ≥ 0.
- **T3. S-adj.** S-adj-TEST distinct net fixes > 0, and breaks ≤ 1.0 % in raw pairs and in
  distinct base words.
- **T4. Valid words.** C-oov-TEST new changes ≤ 0.5 %.
- **T5. Named cases**, each must give the stated output:
  - The DD-M hijack cases: `bery`→very, `grom`→from, `ghe`→the, `sed`→see, `kne`→one,
    `nany`→many; and `helo`→hello.
  - The `SCORE_TIEBREAK_GAP` calibration cases: `donr`→don't, `thier`→their, `thsi`→this,
    `tge`→the, `tfe`→the, `quuestion`→question, `questin`→question, `teh`→the.
  - The report: `devixe`→device, and (only when γ is finite) `shated`→shared.
  - Hebrew: the 11 `HebrewLayoutAutoCorrectTest` cases and `תושה`→`תודה`.
- **T6. Hebrew sweep.** HE-adj distinct breaks ≤ 1.0 % of base words, and distinct net ≥ 0.

Reported, not gated: the GTC class breakdown of fixes and breaks, and the DEV/TEST typo-string
overlap.

**What ships either way.** The maintainer asked (2026-10-18) for every autocorrect weight and
behaviour to be user-tunable. Rule C, its six weights, guard G and every existing constant of
the band fold (gap, transposition penalty, gate ratios, DD-M margin, neighbour tiebreak,
collapse and guard switches) become settings. This gate decides only the DEFAULT: if T1–T6
pass, rule C with the chosen weights becomes the default ranking; otherwise the band fold stays
the default and rule C is an opt-in mode carrying the DEV-chosen weights (or, when nothing was
admissible on DEV, the grid centre `s₁ = 1, g = 1, g₂ = 0.6, τ = 0.9, λ = 0.02`). These
thresholds are fixed now.
