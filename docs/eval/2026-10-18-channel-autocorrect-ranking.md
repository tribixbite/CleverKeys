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

## 4. Round 2 DEV (run 2026-10-18, harness at 47f4bbc2, separate worktree)

Command: `CK_CH_ROUND=2 CK_CH_PHASE=dev … runMockTests -PtestClass=autocorrect.ChannelRankingEval`.
It took 111 minutes: prepare 469 s (GTC), 3,894 s (S-adj) and 1,111 s (C-oov); grid 770 s. Mean
contenders after the pre-filter were 4.0 (GTC), 2.3 (S-adj) and 10.3 (C-oov).

Grid (12,960 configurations): C1 holds in all of them, C2 in 934, C3 in 235, C4 in 4,629, and
**12 are admissible**. Every admissible configuration has γ = 1 and g₂ = 1.0.

| admissible configuration (s₁, g, g₂, τ, λ, β, γ) | GTC-DEV fixes / breaks / net | S-adj-DEV fixes / breaks (raw) | C-oov-DEV new |
|---|---|---|---|
| **2, 1.5, 1.0, 1.2, 0.005, 0.25, 1 (chosen)** | 2,670 / 182 / **+2,488** | 3,217 / 146 (207) | 98 |
| 2, 1.5, 1.0, 1.2, 0.005, 0.5, 1 | 2,665 / 184 / +2,481 | 3,216 / 155 (226) | 100 |
| 4, 2.0, 1.0, 1.5, 0.01, 0.5, 1 | 2,639 / 199 / +2,440 | 3,217 / 140 (206) | 99 |
| 1, 1.5, 1.0, 1.2, 0.0025, 0.25, 1 (fewest S-adj breaks) | 2,403 / 285 / +2,118 | 3,239 / 52 (59) | 86 |

The highest GTC net in the grid (+2,735, at γ = 3 and β = 0) is inadmissible. It breaks 1,114
S-adj words and changes 2,994 C-oov words, because large γ with a small λ corrects valid
unlisted inflections.

**Chosen (§3.3): s₁ = 2, g = 1.5, g₂ = 1.0, τ = 1.2, λ = 0.005, β = 0.25, γ = 1.** Production
named failures at the choice: none (all 15 calibration cases, `devixe` → device, `shated` →
shared and the 12 Hebrew cases hold). **Soundness check: 2,000 inputs, 0 mismatches.**

Full DEV outcomes at the choice (production-equivalent):

- GTC-DEV accuracy **8,944 → 13,039** of 16,358 pairs (54.7 % → 79.7 %). Fixes 4,295 (2,670
  distinct; 6,864 occurrences), breaks 200 (182; 327 occurrences), swaps 1,457. Fixes by class:
  length change 3,538, 1-sub distant 359, 1-sub adjacent 282, transposition 83, multi-sub 33.
  Breaks by class: length change 79, 1-sub distant 72, multi-sub 39, transposition 7, 1-sub
  adjacent 3.
- S-adj-DEV accuracy **96,948 → 131,790** of 137,871 (70.3 % → 95.6 %). Distinct breaks are
  146 against 3,217 fixes (4.5 %, limit 5 %); raw breaks 207 (0.15 %, limit 0.8 %).
- C-oov-DEV new changes 98 (0.27 %, limit 0.5 %).

The opt-in fallback (§3.4) is the same configuration. TEST is next and runs once:
`CK_CH_CONFIG=2.0,1.5,1.0,1.2,0.005,0.25,1.0`.

## 5. Round 2 TEST (run 2026-10-18/19, harness at 11141a41, production code at 47f4bbc2)

Command: `JAVA_TOOL_OPTIONS=-Xmx1200m CK_CH_ROUND=2 CK_CH_PHASE=test
CK_CH_CONFIG=2.0,1.5,1.0,1.2,0.005,0.25,1.0 … runMockTests -PtestClass=autocorrect.ChannelRankingEval`.

**One evaluation, run twice for a technical reason.** The first run (at 47f4bbc2) was SIGKILLed
(exit 137, Android low-memory pressure on the dev phone) after it had computed all five English
sets and before it printed or saved any outcome. No TEST number was seen. The harness then gained
per-set checkpoints (11141a41, outputs unchanged), the test JVM heap was capped at 1.2 GB, and
the identical configuration was evaluated again. That second run is the only TEST result.

| set | band → rule C2 accuracy | fixes (distinct) | breaks (distinct) | net distinct |
|---|---|---|---|---|
| GTC-TEST (16,210 pairs, 5,845 targets) | 8,772 → 12,805 (54.1 % → 79.0 %) | 4,235 (2,643; 6,873 occ) | 202 (187; 334 occ) | **+2,456** |
| R-typed (2,780) | 1,609 → 2,104 (57.9 % → 75.7 %) | 551 (510) | 56 (53) | +457 |
| R-hand (30,545) | 8,284 → 10,760 (27.1 % → 35.2 %) | 3,306 (1,729) | 830 (462) | +1,267 |
| S-adj-TEST (140,971; 5,075 words) | 95,499 → 134,273 (67.7 % → 95.2 %) | 39,001 (3,455) | 227 (144) | +3,311 |
| C-oov-TEST (36,491 valid words) | unchanged 9,449 → 9,365 | 1 | **85 new changes (0.23 %)** | — |
| HE-adj (6,536; 490 base words) | 4,401 → 5,904 (67.3 % → 90.3 %) | 1,566 (312) | **63 (42)** | +270 |

GTC-TEST class breakdown: fixes are length change 3,512, 1-sub distant 340, 1-sub adjacent 271,
transposition 81, multi-sub 31. Breaks are length change 78, 1-sub distant 78, multi-sub 33,
transposition 10, 1-sub adjacent 3. R-union distinct: fixes 2,119, breaks 503, net +1,616. Named
cases: none fail. 5,493 GTC-TEST pairs share a typo string with DEV, through different commits.

### 5.1 Gate (§3.4)

| | criterion | result |
|---|---|---|
| T1 | GTC net > 0, breaks ≤ 0.5 × fixes | +2,456; 187 ≤ 1,321 — **pass** |
| T2 | R-union net ≥ 0 | +1,616 — **pass** |
| T3 | S-adj net > 0, breaks ≤ 0.05 × fixes, raw ≤ 1 % | +3,311; 144 ≤ 172; 227 = 0.16 % — **pass** |
| T4 | C-oov new changes ≤ 0.5 % | 0.23 % — **pass** |
| T5 | named cases | all hold — **pass** |
| T6 | HE-adj distinct breaks ≤ 1 % of base words, net ≥ 0 | **42 > 4.9**; net +270 — **FAIL** |

**Decision: FAIL. The band ranking stays the default.** Rule C2 ships as the opt-in ranking with
the round-2 weights (§3.4 fallback = the chosen configuration). T6 kept round 1's absolute
distinct-word ceiling, the same inherited form that §3.3 replaced for S-adj. It was fixed before
TEST and is not changed after seeing TEST. Overriding it is the maintainer's decision.

**The Hebrew breaks share one mechanism.** A short typo whose slipped letter is a one-letter
Hebrew prefix is corrected by deleting that letter to reach a very common shorter word
(`לןא`/`לחא`/`לצא` → `לא`, want `ללא`; `טאת`/`יאת` → `את`, want `ואת`; `סעל`/`געל` → `על`, want
`בעל`; `אןת`/`אצת` → `את`, want `אחת`). At these weights a deletion (g = 1.5) plus the common
word's small rank cost undercuts a neighbour substitution (≈ 1 + 2 · 0.14) plus the rarer
target's rank cost. The English sets do not exercise this, because English has no one-letter
prefixes. A length-aware deletion cost (a deletion that leaves a word of two or fewer letters
costs more) is the obvious round-3 candidate. It needs its own pre-registration and fresh
Hebrew data, because HE-adj has now been seen.

**A finding outside the gate.** On C-oov-TEST both rankings change most valid unlisted words: the
band ranking leaves only 9,449 of 36,491 (26 %) as typed, and 16,902 are changed by both rankings
to different words. `words_alpha.txt` is full of rare words, but the space-bar autocorrect
replacing three in four unlisted valid words is a property of the shared candidate gates and
the frequency floor (0 at the default slider), not of either ranking.

## 6. Maintainer override (2026-10-19)

The maintainer read §5 and decided: **"override T6, make channel the default."** The channel
ranking (rule C2, the round-2 weights) is now `Defaults.AUTOCORRECT_RANKING`. This is a
maintainer decision against a failed pre-registered criterion, not a gate pass. The record:

- **What was overridden:** T6's absolute ceiling, at most 1 % of HE-adj base words broken
  (4.9 words). Rule C2 broke 42 (63 raw pairs) and fixed 312 (1,566 raw), with distinct net
  +270 and accuracy 67.3 % → 90.3 %. The breaks are one mechanism (§5.1): prefix-letter
  deletions onto common short words.
- **What still holds:** T1–T5 all passed on TEST, and every named case holds, including the 12
  Hebrew ones.
- **Known cost accepted:** Hebrew short typos of the `לןא` → `לא` (want `ללא`) class. A round 3
  with a length-aware deletion cost needs fresh Hebrew data, because HE-adj has been seen.
- **Users keep the old behaviour one tap away:** Settings → Auto-Correction → Ranking & Tuning →
  "Score band, then frequency".

## 7. Round 3 pre-registration: short-word deletion cost δ (written before any round-3 outcome)

Motivation: the §5.1 Hebrew break class. A short typo is corrected by deleting a letter (often a
mistyped one-letter prefix) onto a very common word of two letters or fewer. No round-3 output
had been computed on any set when this section was committed.

### 7.1 Rule C3

Rule C2 at the shipped weights (§4), plus one term. When the candidate is **shorter** than the
typed word **and has at most 2 letters**, its edit cost gains **δ**
(`ChannelWeights.shortWordDeletionCost`). The term is structural, applies to every language, and
δ = 0 is the shipped rule C2. Grid: `δ ∈ {0.25, 0.5, 0.75, 1.0, 1.5}`. Every other weight stays
fixed at the shipped values, so there is no re-tuning on data seen in rounds 1–2.

### 7.2 Data

- **HE-adj2 (fresh, never evaluated)**: every single `hebr_1_il`-neighbour substitution of the
  shipped Hebrew pack words ranked **501–2,500** by frequency (letters all on the grid, length
  ≥ 2), minus typos that are pack words. Split by the FNV-1a hash of the base word: even → DEV,
  odd → TEST. HE-adj (top 500) is disjoint by construction and is reported, not gated, because
  it has been seen.
- English: GTC, S-adj and C-oov with the §1.2 / §3 splits. **The English TEST sets are used a
  second time** (round 2 used them once). They act here only as no-harm checks. δ is chosen on
  DEV.

Baseline = shipped rule C2 (the current default). Outcomes (fix, break, swap) are measured
against that baseline.

### 7.3 Choice on DEV

δ is **admissible** when:

1. **H1.** HE-adj2-DEV distinct net fixes > 0, and distinct breaks ≤ 0.25 × distinct fixes.
2. **E1.** GTC-DEV and S-adj-DEV distinct net ≥ 0, and C-oov-DEV new changes ≤ 0.1 % of
   C-oov-DEV words.
3. **N.** Every §1.5 T5 named case holds, including `shated` and the 12 Hebrew cases.

Choice: the admissible δ with the most HE-adj2-DEV distinct net; ties go to the smaller δ. If
none is admissible, δ stays 0 and nothing ships. Soundness: on 2,000 seeded DEV inputs the
harness output must equal full production at the chosen δ.

### 7.4 Ship gate on TEST (one evaluation of the chosen δ)

- **R1.** HE-adj2-TEST distinct net > 0, and distinct breaks ≤ 0.25 × distinct fixes.
- **R2.** GTC-TEST and S-adj-TEST distinct net ≥ 0.
- **R3.** C-oov-TEST new changes ≤ 0.1 %.
- **R4.** Named cases hold.

All four must hold for the chosen δ to become the shipped default. Reported, not gated:
HE-adj (seen) and Birkbeck. These thresholds are fixed now.

## 8. Exploratory: why most valid unlisted words get changed (2026-10-19; selects nothing)

On C-oov-TEST both rankings change about 74 % of `words_alpha.txt` words that are not in the
dictionary (§5). The round-2 TEST rows (`rows2-test.tsv`, already-seen data) were inspected to
explain this. **They were looked at, so any later rule that uses this finding must be chosen
on DEV, and its TEST result must disclose that this inspection happened.**

- The changed words are mostly obscure (`keelsons`, `tyrannicide`, `octoroons`, `semeiotic`),
  6–10 letters long, and 70 % of them become a shorter word with letters dropped
  (`unsteps` → steps, `agreat` → great, `asslike` → alike). Rule C2 picks closer words than the
  band ranking did (`missuppose`: band `missiles`, C2 `misspoke`), but it still changes them.
- Edit cost at the shipped weights, recomputed from the rows: **real-typo fixes (GTC-TEST, 4,235)
  have median 1.50 and 90th percentile 1.80. Valid-word changes (16,987) have median 3.00,
  25th percentile 2.20 and 90th percentile 4.80.**

| cap on the winner's edit cost | GTC fixes kept | C-oov changes still made |
|---|---|---|
| 1.5 | 89.0 % | 20.7 % |
| 2.0 | 91.1 % | 24.0 % |
| 2.5 | 94.1 % | 29.8 % |
| 3.0 | 99.8 % | 62.7 % |

The cause is that the shared candidate gates accept long words up to two edits away (match
score ≥ 0.65 and edit budget = length difference + 0.5), and nothing in either ranking asks
whether the best correction is close enough to be worth making. A **maximum correction cost κ**
(commit only when the winner's edit cost ≤ κ) is the candidate rule. It would be its own
pre-registered round: κ chosen on GTC-DEV / S-adj-DEV / C-oov-DEV, with this section disclosed in
the TEST result. Not built yet.
