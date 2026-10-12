# Neighbour-key substitution in the autocorrect tie band (`תושה`, todo item 20)

Date: 2026-10-12. Scope: tap-typing space-bar autocorrect (`WordPredictor.autoCorrect`), shipped
defaults, English on the shipped `en_enhanced.bin` (QWERTY) and Hebrew on the shipped
`langpack-he.zip` with the `hebr_1_il` letter grid. Base: origin/main 000502a1.

## 0. The problem

The sweep (step 4 of `autoCorrect`) keeps the best candidate pairwise. A candidate more than
`SCORE_TIEBREAK_GAP` (0.10) better on raw score wins outright. Inside the band, structural
rules run first (alias privilege; one Damerau transposition beats a multi-substitution
candidate), and then frequency decides. Frequency therefore decides between two one-letter
substitutions even when one is a neighbour-key slip and the other changes a distant key.

Device and JVM repro (Pixel, 2026-10-12, release 7eef0b7c; `HebrewLayoutAutoCorrectTest`
harness): typing `תושה` (intended `תודה`, ש↔ד are neighbours) commits `עושה` (ת↔ע, about four
keys apart). Raw scores are about 0.97 and 0.89. The gap is under 0.10 and `עושה` is more
frequent, so it wins. English has the same mechanism.

## 1. Pre-registration (written and committed before any outcome was computed)

When this section was committed, no autocorrect output under the rule had been computed on
any set, and the harness did not exist yet.

### 1.1 Rule NB (no numeric parameter)

Definitions, for a candidate `c` of the same length as the typed word `t` that is **not** an
adjacent transposition (`isAdjacentTransposition`):

- `S(c)` = the positions `i` where `t[i] != c[i]`.
- **Neighbour-substitution (NB) candidate**: every `i` in `S(c)` has
  `KeyAdjacency.areNeighbors(t[i], c[i])` on the active layout.
- **Distant-substitution (DS) candidate**: at least one `i` in `S(c)` is not a neighbour pair.
  A character that is not on the active layout is nobody's neighbour, so it counts as distant.

Length-changing candidates and transpositions are neither NB nor DS. The rule never applies to
them.

Two variants are registered. They differ only in which candidates take part:

- **N1 (single substitution)**: the rule compares only candidates with `|S| = 1`.
- **NA (all substitutions)**: the rule compares any NB and DS candidates, including multi-sub
  candidates (`|S| ≥ 2`), for example a 2-neighbour-sub candidate against a 1-distant-sub one.

**Rule.** When two candidates are inside the ±0.10 band and the rule applies to both, the NB
candidate beats the DS candidate regardless of frequency.

**Placement in the tiebreak order.** The order after this change is:

1. raw score beyond the band (unchanged);
2. alias rules (alias vs alias by raw score; alias privilege only at equal-or-better raw score;
   unchanged and first, so an alias decision is never overridden by NB);
3. transposition beats multi-sub (unchanged);
4. **NB beats DS (new)**;
5. frequency, then raw score on a frequency tie (unchanged).

Interactions:

- **Alias privilege** keeps precedence. A distant-sub alias already loses to a neighbour-sub
  non-alias on raw score in the alias rules, so the two rules agree in that case. Two aliases
  are still compared by raw score.
- **Transposition**: transpositions are outside the rule. Rule 3 still applies before rule 4.
- **Multi-sub**: under N1, a multi-sub candidate is unchanged (frequency against any
  single-sub candidate, as today). Under NA it takes part as NB or DS.
- **NB vs NB and DS vs DS**: unchanged (frequency).
- **DD-M (step 1.45)** compares its insertion target D with W, the sweep's word, using
  `autocorrectSweep`. The rule changes the sweep, so W changes with it. The DD-M margin of
  64 is not re-tuned. The DD-M named cases are gated below (T5).
- The sweep is a pairwise left fold over the dictionary map, and the tiebreak is not a total
  order. That is already true of the existing structural rules. The rule adds one more
  pairwise comparison, and the eval measures the real fold.

### 1.2 Harness

`src/test/kotlin/tribixbite/cleverkeys/autocorrect/NeighbourTiebreakEval.kt` is a MockK-runner
class with no `Test` suffix, so CI never runs it. Its setup is the same as
`DroppedDoubleMarginEval`: `ShippedTapDictionary` (the `.bin`) with the real contraction
aliases, shipped defaults, and no custom or disabled words. Hebrew uses the
`HebrewLayoutAutoCorrectTest` setup: the shipped pack through `BinaryDictionaryLoader`, and
`LayoutLetterGrid.of(hebr_1_il)` installed with `KeyAdjacency.setLayoutLetters`.

The rule is not simulated outside production, because it sits inside the sweep's fold. The
production sweep takes an internal variant argument (`OFF`, `N1`, `NA`) through an
`autoCorrect(word, marginSteps, variant)` seam, the same pattern as the DD-M margin seam.
Baseline = `OFF` = today's shipped behaviour. If a variant ships, the default becomes that
variant and the seam remains for the eval.

Outcomes against the baseline, per input (as in the DD-M docs): **fix** (baseline wrong,
rule right), **break** (baseline right, rule wrong), **swap** (both wrong, different
outputs). They are reported in raw pairs, distinct target words and, for GTC, commit
occurrences.

### 1.3 Data

The data is the same as in `2026-10-11-dropped-double-margin.md` §1.2, with the same files and
SHA-256, local only:

- **GTC**: `gtc-pairs.tsv` from `scripts/gtc_extract_pairs.py` over
  `github-typo-corpus.v1.0.0.jsonl.gz`. DEV is even-last-hex commit SHAs and TEST is odd ones.
  The same dictionary filter applies (target in the dictionary, typo not in it and not an
  alias key, letters only).
- **R-typed** (`wikipedia.dat` + `aspell.dat`) and **R-hand** (`missp.dat` +
  `holbrook-missp.dat`): Birkbeck. These are mostly cognitive spelling errors, not keyboard
  slips. TEST only.
- **S-adj**: every single QWERTY-neighbour substitution of the top-10,000 words of 3 or more
  letters, minus typos that are dictionary words or alias keys. It is split by the FNV-1a hash
  of the base word.
- **C-oov**: `words_alpha.txt` valid words missing from the dictionary. TEST only.
- **Hebrew (no corpus exists)**:
  - The 11 `HebrewLayoutAutoCorrectTest` cases plus `תושה` → `תודה`.
  - **HE-adj**: every single `hebr_1_il`-neighbour substitution of the top-500 pack words
    (all lengths ≥ 2), minus typos that are pack words. This extends the 1d2f1d58 protocol,
    which used one typo per word, to all neighbour typos. Hebrew is not split. There is
    nothing to tune on it.

### 1.4 Variant choice on DEV (the only choice; no numeric parameter)

A variant is **admissible** on DEV when all of these hold:

1. GTC-DEV distinct net fixes > 0, and distinct breaks ≤ 0.25 × distinct fixes.
2. S-adj-DEV distinct net fixes > 0, and breaks ≤ 0.8 % of pairs, in raw pairs and in
   distinct base words.
3. Every named case in T5 below holds.

The chosen variant is the admissible one with more GTC-DEV distinct net fixes. On a tie, N1
wins because it is narrower. If neither is admissible, nothing ships and TEST is not used for
selection.

Why the GTC ratio is 0.25 and not DD-M's 0.10: DD-M added a correction where the baseline
had another word. NB reorders two candidates that both pass the score threshold. Its breaks
are exactly the real distant-substitution typos that the frequency order used to get right.
GTC is typed on physical keyboards and contains cognitive misspellings (9.6 % of its pairs
are non-adjacent substitutions against 6.7 % adjacent, DD-M doc §2.1). The rule must win on
it by at least 4 to 1 in distinct words.

### 1.5 Ship gate on TEST (one evaluation of the chosen variant; all must hold)

- **T1. GTC.** GTC-TEST distinct net fixes > 0, and distinct breaks ≤ 0.25 × distinct fixes.
- **T2. Birkbeck.** R-union (R-typed ∪ R-hand) distinct net ≥ 0. Net-positive is not
  required, because these sets are not keyboard slips. Net harm on them is not allowed.
- **T3. S-adj.** S-adj-TEST distinct net fixes > 0, and breaks ≤ 1.0 % in raw pairs and in
  distinct base words.
- **T4. Valid words.** C-oov new changes ≤ 0.5 %. The rule cannot create a correction where the
  baseline made none, so this is expected to be 0.
- **T5. Named cases**, all must give the stated output under the variant:
  - The six DD-M hijack cases: `bery`→very, `grom`→from, `ghe`→the, `sed`→see, `kne`→one,
    `nany`→many.
  - `helo`→hello.
  - The `SCORE_TIEBREAK_GAP` calibration cases: `donr`→don't, `thier`→their, `thsi`→this,
    `tge`→the, `tfe`→the, `quuestion`→question, `questin`→question, `teh`→the.
  - Hebrew: the 11 `HebrewLayoutAutoCorrectTest` cases, and **`תושה`→`תודה`**.
- **T6. Hebrew sweep.** HE-adj distinct breaks ≤ 1.0 % of base words, and distinct net ≥ 0.
  Before/after accuracy is reported.

Reported, not gated: the GTC class breakdown of fixes and breaks (adjacent-sub vs
non-adjacent-sub), and the DEV/TEST typo-string overlap.

If T1–T6 all pass, the chosen variant ships with fail-first unit tests (`תושה`→`תודה` in
`HebrewLayoutAutoCorrectTest`, an English analogue, and guards). Otherwise nothing ships, and
the numbers are committed here. These thresholds are fixed now.

## 2. DEV (run 2026-10-12, harness and seam at the commit that records this section)

Command: `CK_NB_PHASE=dev GRADLE_GUARD_XMX=1536m scripts/gradle-guard.sh runMockTests
-PtestClass=autocorrect.NeighbourTiebreakEval`. The test phase took about 50 minutes.

**Harness pre-filter (added after the pre-registration, does not change any outcome).** A full
98k-word sweep costs about 25 ms of CPU per input on this device. The first attempt at sweeping
every input was killed after 50 minutes. The harness now runs the sweep only on inputs where
the rule can fire (`Lang.mayFire`): the dictionary must hold both a same-length all-neighbour
candidate and a distant one that pass the sweep's gate 1. Every other input gives OFF's output
by construction. As a soundness check, 400 seeded ruled-out inputs were swept under NA, with
0 mismatches. Outcomes are counted over the eligible inputs only. Accuracy in the table is
over the eligible inputs, not the whole set.

Baseline (OFF) named cases: all pass except **`תושה` → `עושה`**, the reported case.

| variant | set | eligible | fixes (distinct; occ) | breaks (distinct; occ) | swaps (distinct) | net distinct |
|---|---|---|---|---|---|---|
| N1 | GTC-DEV (16,358 pairs, 5,845 targets) | 1,683 | 29 (29; 33) | **62 (60; 145)** | 183 (162) | **−31** |
| N1 | S-adj-DEV (137,871 pairs, 4,925 words) | 32,151 | 1,661 (852) | 5 (4) | 666 (452) | +848 |
| NA | GTC-DEV | 3,838 | 181 (167; 217) | **159 (135; 287)** | 1,077 (815) | +32 |
| NA | S-adj-DEV | 91,420 | 21,313 (2,726) | **172 (84)** | 4,364 (1,020) | +2,642 |

Named cases under both variants: **`תושה` → `תודה` is fixed**, the other Hebrew cases and the
DD-M cases hold, and **`tfe` → `tfw` breaks** (want `the`). w↔e are neighbours, while f↔h are
two keys apart, so the rule overrides the frequency tiebreak that `SCORE_TIEBREAK_GAP` was
calibrated on.

Admissibility (§1.4):

| variant | C1 GTC net > 0, breaks ≤ 0.25 × fixes | C2 S-adj net > 0, breaks ≤ 0.8 % | C3 named | admissible |
|---|---|---|---|---|
| N1 | **fail**: net −31; 60 > 7.25 | pass: 5 / 137,871 raw; 4 / 4,925 distinct (0.08 %) | **fail** (`tfe`) | no |
| NA | **fail**: 135 > 41.75 | **fail**: 84 / 4,925 = 1.7 % distinct | **fail** (`tfe`) | no |

**Decision: no variant is admissible. Nothing ships, and TEST was not run.** Under the
pre-registration (§1.4), TEST is not used when nothing passes DEV, so the Birkbeck, C-oov and
Hebrew HE-adj sweep (T6) numbers were not computed. The production default stays `OFF`.

### 2.1 Why it fails

The GTC breaks are real misspellings whose intended word is a distant one-letter substitution.
Beside it sits a real word one neighbour key away, and frequency was ranking the intended word
first. Under N1, 58 of the 62 breaks are in the 1-sub-distant class. Examples (typo → intended,
what N1 commits; commit occurrences): `compatability` → compatibility, comparability (65);
`releated` → related, repeated (7); `tipically` → typically, topically (4); `unter` → under,
inter; `startet` → started, starter; `seprate` → separate, septate; `thet` → that, they;
`hava` → have, java; `choise` → choice, choose; `zime` → time, some.

Most of these are cognitive errors (a wrong vowel, a phonetic `s`/`c`, a German-style `t`). They
are not fat-finger slips, and keyboard geometry says nothing about them. Frequency was the
better signal. On synthetic neighbour slips, the rule is large and almost free (N1: +852
distinct words on S-adj, 4 breaks), for example `hac`/`haf`/`har` → had (was has), `tham` →
than (was that), `dould` → could (was would). The two error populations disagree, and on the
real one N1 loses by 2 to 1.

GTC was typed on physical keyboards. A phone keyboard plausibly has a higher share of
neighbour slips, which is the S-adj side. That cannot be measured without a phone-typing
corpus, and the pre-registered gate used the corpus that exists.

### 2.2 What remains for `תושה`

The device case is still open. A narrower rule might fix it without the GTC cost, for
example by requiring the frequency ratio to stay inside a bound, or by applying only when the
distant substitution is a consonant↔consonant pair far apart. Either would be a new
pre-registration with a parameter tuned on GTC-DEV, and it would face the same cognitive-error
population. `HebrewLayoutAutoCorrectTest` keeps `תושה` out of its passing cases, and its comment
points at todo item 20.

### 2.3 Artefacts

- The seam stays: `WordPredictor.NeighbourTiebreak` (`OFF`/`N1`/`NA`) and
  `autoCorrect(word, marginSteps, variant)`. `NEIGHBOUR_TIEBREAK = OFF`, and the per-position
  neighbour check is skipped when the variant is OFF, so production behaviour and cost are
  unchanged.
- Harness: `src/test/kotlin/tribixbite/cleverkeys/autocorrect/NeighbourTiebreakEval.kt`.
- Rows (local, not committed): `build/eval/neighbour-tiebreak/rows-dev.tsv`.
