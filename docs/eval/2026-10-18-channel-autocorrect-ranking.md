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

## 2. DEV (run 2026-10-18, harness at 685fc567, in a separate worktree)

Command: `CK_CH_PHASE=dev GRADLE_GUARD_XMX=1536m GRADLE_GUARD_METASPACE=384m
scripts/gradle-guard.sh runMockTests -PtestClass=autocorrect.ChannelRankingEval`. The test phase
took 93 minutes (GTC-DEV prepare 320 s, S-adj-DEV prepare 5,152 s, grid 82 s).

Sets: GTC-DEV 16,358 pairs (5,845 targets, 16,095 distinct typos), S-adj-DEV 137,871 pairs (4,925
base words), C-oov-DEV 36,241 words. These match the NB and DD-M docs exactly. Band baseline named
cases: everything holds except **`devixe` → `decide`** and **`shated` → `shated`** (the report).

**Harness changes after the pre-registration (neither changes an outcome).**

- *Contender pre-filter.* The sweep gates pass far more words than expected: GTC-DEV averages
  **204** gated candidates per input, mostly length-changing ones (`autocorrect_max_length_diff`
  = 2). The first attempt, which extracted 17 alignments for every gated candidate, was stopped
  after 11 minutes. Every alignment's cost is non-decreasing in each edit weight, so a
  candidate's edit cost under any grid weights lies between its cost at the grid's minimum
  weights and at its maximum. A candidate is dropped when, at every grid λ, its lower bound plus
  `λ · rank` exceeds the best upper bound by more than the tie tolerance. Such a candidate cannot
  win under any configuration. That leaves 6.8 (GTC) and 5.6 (S-adj) contenders per input.
- *Alignment pricing.* A grid configuration prices each contender as the minimum, over its
  optimal alignments at the 16 grid corners and the centre, of the linear cost of that alignment
  (`ChannelEditCost.alignmentFeatures` / `priced`). The pre-registered soundness check against
  full production would have measured this, but it never ran, because no configuration was
  admissible (it runs only at the chosen configuration). The diagnostic below uses full
  production throughout.

### 2.1 Grid (γ = ∞; distinct target / base words)

**No configuration is admissible.** C1 (GTC) holds in 1,151 of 1,440 configurations. C2 fails
everywhere on its distinct-word ceiling (0.8 % = 39 base words; the minimum over the grid is 73).
C3 fails everywhere (the minimum is 1 failing named case).

| configuration (s₁, g, g₂, τ, λ) | GTC-DEV fixes / breaks / net | S-adj-DEV fixes / breaks (raw) | named failing |
|---|---|---|---|
| 1, 1, 0.6, 0.6, 0.005 (most GTC net) | 2,818 / 293 / **+2,525** | 2,826 / 1,981 (5,983) | 12 |
| 1, 1.5, 1.0, 1.2, 0.005 (fewest S-adj breaks) | 2,501 / 293 / **+2,208** | 3,070 / **73 (100)** | 2 |
| 2, 1.5, 1.0, 1.2, 0.005 (1 named failure) | 2,646 / 254 / **+2,392** | 3,101 / 144 (204) | 1 |
| 1, 1, 0.6, 0.9, 0.02 (grid centre) | 2,476 / 449 / +2,027 | 2,574 / 1,739 (6,303) | 7 |

GTC prefers the smallest λ in the grid (frequency matters little once edits are priced).
S-adj breaks fall as g and τ rise and λ falls. The full grid is in
`build/eval/channel-ranking/grid-dev.tsv` (local only).

**Decision (§1.4): nothing ships as the default, and TEST was not run.**

### 2.2 Diagnosis (full production, DEV only; `CK_CH_PHASE=inspect`, seeded 3,000-pair samples)

Band baseline accuracy on the samples: GTC-DEV **1,637 / 3,000 (54.6 %)**, S-adj-DEV **2,087 /
3,000 (69.6 %)**. The band ranking is "the most frequent word within 0.10 of the best score".
Among about 200 gated candidates that is often a common unrelated word: `reponses` → exposed,
`exeption` → election, `desig` → feel, `djano` → thanks. Rule C fixes those.

| configuration | GTC-DEV sample accuracy | S-adj-DEV sample accuracy (breaks) | named failing |
|---|---|---|---|
| band baseline | 54.6 % | 69.6 % | `devixe`, `shated` |
| 1, 1.5, 1.0, 1.2, 0.005, γ = ∞ | 76.0 % | 94.4 % (1) | `helo`, `donr`, `shated` |
| 1, 1.5, 1.0, 1.2, 0.005, γ = 1 | 76.6 % | 95.1 % (1) | `helo`, `donr` |
| 1, 1, 0.6, 0.6, 0.005, γ = ∞ | 81.3 % | 84.8 % (128) | 13, incl. 7 Hebrew |
| grid centre, γ = ∞ | 76.0 % | 82.2 % (132) | 8 |

Three mechanisms explain every gate failure. All of them come from the rule's specification,
not from noise:

1. **Guard order (a specification error in §1.1).** In the band path, DD-M (step 1.45) runs
   *before* the inflection guard, so `spaming`, `formating`, `refering`, `wraped`, `grabed`,
   `defered`, `instals` and `proces` were re-doubled before the guard could read them as
   `spam` + `-ing` and so on. Rule C skips DD-M, so at γ = ∞ the guard keeps them as typed.
   About 15 of the 64 GTC breaks at the S-adj-best point are this one class. §1.1's sentence
   "γ = ∞ is today's guard" is therefore false for dropped-double inflections.
2. **Alias privilege.** The band ranking let a contraction key win at equal-or-better raw score
   regardless of frequency. Rule C's alias rule only breaks exact total-cost ties, so `donr`
   gives `done`, not `don't`.
3. **Doubled-letter cost and Hebrew.** `helo` → `help` fails whenever g₂ is not well below one
   neighbour substitution. The configurations that fail the Hebrew named cases (short words:
   `החא`, `אתכ`, `ךמה`) are the low-g ones, where a deletion is cheaper than the intended
   neighbour substitution.

The S-adj distinct-word ceiling (0.8 %) was inherited from DD-M and NB. For those rules S-adj
was a control set with almost no possible fixes. Rule C fixes about 3,000 of its 4,925 base
words, and its best points break 73–144. Whether a breaks-to-fixes ratio should replace the
absolute ceiling is a gate change, so it is the maintainer's decision. It is recorded here and
not applied.

### 2.3 What ships from this round

As pre-registered (§1.5): the band ranking stays the default. Rule C ships as an opt-in
ranking mode with the grid-centre weights (s₁ = 1, g = 1, g₂ = 0.6, τ = 0.9, λ = 0.02,
γ = ∞), and every weight is a setting. The DEV numbers above are the evidence for anyone who
switches it on and moves the sliders. A second round needs a new pre-registration (a guard-order
fix, an alias term, joint γ, and any gate change), DEV re-run, and then the first TEST
evaluation. TEST has not been touched.

## 3. Round 2 pre-registration (written and committed before any round-2 outcome was computed)

Maintainer go-ahead: 2026-10-18 ("continue" after §2's question, read as approval of the
recommended round 2, including the gate change below). No output under the round-2 rule had been
computed when this section was committed. TEST is still untouched.

### 3.1 Rule C2 (changes to rule C, from §2.2's diagnosis only)

1. **Re-doubling bypasses the inflection guard.** When step 1.5 deferred with a stem (§1.1
   guard G) and the winner `w` is a *double reduction* of the typed word `t`, the guard does not
   apply and `w` is committed (subject to the floor). `t` is a double reduction of `w` when
   deleting some letters of `w`, each one equal to the letter before it in `w`, gives `t`
   (`spaming` ← `spamming`, `helo` ← `hello`). This is structural and independent of the
   weights. It restores the band path's order, where DD-M ran before the guard.
2. **Alias bonus β.** A contraction alias key's total cost is `E + λ·r − β`. This replaces the
   band's alias privilege (`donr` → `don't`). Ties are unchanged from §1.1.
3. **γ joins the grid**, including ∞.

Everything else in §1.1 is unchanged: the candidate set, E, `λ · r`, the tie order, the floor,
DD-M skipped and step 1.4 kept.

### 3.2 Grid (fixed now)

- `s₁ ∈ {0.5, 1, 2, 4}`, `g ∈ {1.25, 1.5, 2.0}`, `g₂ ∈ {0.3, 0.45, 0.6, 1.0}`,
  `τ ∈ {0.9, 1.2, 1.5}`, `λ ∈ {0.0025, 0.005, 0.0075, 0.01, 0.015}`, `β ∈ {0, 0.25, 0.5}`,
  `γ ∈ {0.5, 1, 1.5, 2, 3, ∞}`.

That is 12,960 configurations. The region follows round 1, where GTC favoured the smallest λ
and S-adj favoured larger g and τ. Data, split and harness are as in §1.2, §1.3 and §2 (the
contender pre-filter extends to β: an alias key's lower bound subtracts the largest β).

### 3.3 Admissibility on DEV

1. **C1.** GTC-DEV distinct net fixes > 0, and distinct breaks ≤ 0.5 × distinct fixes (as §1.4).
2. **C2 (changed, maintainer-approved).** S-adj-DEV distinct net fixes > 0, distinct breaks
   ≤ **0.05 × distinct fixes**, and raw breaks ≤ 0.8 % of pairs. The absolute distinct-word
   ceiling is replaced by the ratio, because rule C replaces the ranking and fixes thousands of
   S-adj words (§2.2). The raw-pair ceiling stays.
3. **C3.** Every named case of §1.5 T5 holds, **including `shated` → `shared`**, for every
   configuration (γ = ∞ therefore cannot be admissible).
4. **C4.** C-oov-DEV new changes ≤ 0.5 % of C-oov-DEV words.

Choice: the admissible configuration with the most GTC-DEV distinct net. Ties go to fewer
S-adj-DEV distinct breaks, then the larger γ, then grid order. Soundness check as §1.3 (2,000
seeded DEV inputs, replica = production, or the run is void).

### 3.4 Ship gate on TEST (one evaluation of the chosen configuration; all must hold)

T1, T2, T4, T5 (with `shated`) and T6 as §1.5. **T3 (changed)**: S-adj-TEST distinct net > 0,
distinct breaks ≤ 0.05 × distinct fixes, and raw breaks ≤ 1.0 %.

If T1–T6 pass, rule C2 with the chosen weights becomes the **default** ranking. If DEV has no
admissible configuration, or TEST fails, the band ranking stays the default. The opt-in channel
defaults then become the DEV configuration with the most GTC-DEV distinct net among those that
pass C2, C3 and C4 (any C1), or stay at the round-1 grid centre if there is none. These
thresholds are fixed now.
