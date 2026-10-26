# Autocorrect ranking (tap-typing space-bar autocorrect)

**Status (2026-10-23):** as built. Scope: `WordPredictor.autoCorrect` — what the space bar
commits for a tap-typed word. Swipe decoding is a different pipeline (`ctc-swipe-engine.md`).
Every number below is read from `Config.kt` `object Defaults`, `autocorrect/AutocorrectTuning.kt`
and `autocorrect/ChannelRanking.kt` at HEAD; the measurements that chose them are in the two
evaluation documents named at the end.

## 1. Where things live

| File | Symbol | Role |
|------|--------|------|
| `src/main/kotlin/tribixbite/cleverkeys/WordPredictor.kt` | `autoCorrect(typedWord)` (`:3136`), `autoCorrect(typedWord, tuning, probe)` (`:3186`), `gatedCandidates` (`:3506`), the band fold (`:3698-3720`), the channel decision (`:3737-3795`) | the pipeline |
| `src/main/kotlin/tribixbite/cleverkeys/autocorrect/AutocorrectTuning.kt` | `AutocorrectTuning`, `ChannelWeights`, `AutocorrectRanking { BAND, CHANNEL }`, `ShortDeletionScope { HEBREW, ALL }`, `AutocorrectTuning.Keys` | every weight as one immutable snapshot; keys, defaults and ranges in one place |
| `src/main/kotlin/tribixbite/cleverkeys/autocorrect/ChannelRanking.kt` | `ChannelEditCost.cost`, `ChannelRanker.totalCost` / `pick` / `rankSteps` | the noisy-channel cost and the argmin |
| `src/main/kotlin/tribixbite/cleverkeys/autocorrect/KeyAdjacency.kt`, `LayoutLetterGrid.kt` | `keyDistance`, `substitutionScore`, `weightedEditDistance`, `areNeighbors` | key geometry (QWERTY and the Hebrew `hebr_1_il` grid) |
| `src/main/kotlin/tribixbite/cleverkeys/autocorrect/FrequencyFloor.kt` | `FrequencyFloor.effective(sliderValue, dictMaxFrequency)` | maps the 100..2000 slider onto the loaded dictionary's frequency scale |
| `src/main/kotlin/tribixbite/cleverkeys/autocorrect/Morphology.kt` | inflection candidates for guard G / step 1.5 | English suffix stripping, candidate generator not a lemmatizer |
| `src/main/kotlin/tribixbite/cleverkeys/autocorrect/AutocorrectContextGuard.kt` | URL / path / identifier detection | skips autocorrect in non-prose tokens |
| `src/main/kotlin/tribixbite/cleverkeys/autocorrect/SpaceCommitPreview.kt` | what SPACE will commit | the bar highlights the real space commit |
| `src/main/kotlin/tribixbite/cleverkeys/WordChars.kt` | `crossesHalfSpace`, `hasEdgeZwnj` | the Persian half-space rules (§7) |
| `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | `Defaults.AUTOCORRECT_*` (`:215-278`), `autocorrect_tuning`, `autocorrect_prefix_length`, `SettingsRanges.AUTOCORRECT_MIN_WORD_LENGTH = 2..5` | defaults, live values, ranges |
| `src/main/kotlin/tribixbite/cleverkeys/ui/settings/sections/AutoCorrectionSection.kt`, `ui/settings/AutocorrectTuningSliders.kt` | the basic settings and the "Ranking & Tuning" group | every weight is user-tunable (maintainer request, 2026-10-18); slider increments make the shipped defaults exact positions |

`Config.refresh()` builds `Config.autocorrect_tuning` with `AutocorrectTuning.fromPrefs(prefs)`,
clamping each value into its key's range; a null field (an Objenesis test `Config`, early
startup) means `AutocorrectTuning.DEFAULT`. The tuning is part of the autocorrect memo key, so a
settings change never serves a correction computed under old values.

## 2. The pipeline (`autoCorrect`, step numbers as in the source comments)

0. **Contraction alias** (`im` → `I'm`, `dont` → `don't`) through `ContractionManager`; a user
   word is never rewritten (`SuggestionHandler.replaceModeContractionFor` guards the display
   path the same way).
1. **Known word**: a word in the dictionary or the user's vocabulary is left alone. A dangling
   Persian half-space (`می‌` + space, `WordChars.hasEdgeZwnj`) is never a word: return as typed.
   1.4 **Elongation collapse** (`gamees` → `games`; `elongationCollapseEnabled`, both rankings).
   1.45 **Dropped doubled letter, frequency margin** (DD-M, `helo` → `hello`; BAND ranking
   only — `droppedDoubleEnabled`, `droppedDoubleMarginSteps` = 64 rank steps; the channel
   ranking prices the same typo with `doubleCost` instead). A word the user added by hand keeps
   it (`help` stays `help`).
   1.5 **Inflection guard** (guard G): with `inflectionGuardEnabled` and a stem of at least
   `inflectionGuardMinStem` (4) letters after stripping a regular suffix (`Morphology`), the
   typed word is taken as an unlisted inflection. Under BAND this is absolute; under CHANNEL
   the guard becomes a priced alternative (γ, §4).
   1.6 **Possessive guard + possessive-typo correction** (AC-4).
2. **Minimum word length**: `autocorrect_min_word_length` (default 2, range 2..5).
3. **Required prefix** ("Starting Letter Accuracy", §6).
4. **Candidate sweep** over the loaded dictionary (§3), ranked by BAND or CHANNEL (§4).
5. **Commit test**: the winner must clear the frequency floor
   (`FrequencyFloor.effective(autocorrect_confidence_min_frequency, dictMaxFrequency)`;
   slider 100..2000, default 100) or be the user's own word (AC-2); then
   `finalizeCorrection` restores the typed word's capitalization pattern.

`AutocorrectContextGuard` short-circuits the whole thing inside URLs, e-mail addresses, paths
and identifiers.

## 3. The candidate gates (`gatedCandidates`)

For every dictionary word, in this order:

| Gate | Rule | Setting (default, range) |
|------|------|--------------------------|
| length | `|len(cand) − len(typed)| ≤ autocorrect_max_length_diff` | `autocorrect_max_length_diff` (2, 0..5) |
| prefix | `cand.startsWith(typed[0 until p])` when `p > 0` | `autocorrect_prefix_length` (0, 0..4), §6 |
| half-space | `!WordChars.crossesHalfSpace(typed, cand)` — a candidate that adds, removes or moves a U+200C is a different spelling, never a typo fix (no-op without a U+200C) | none (policy, §7) |
| same length | exact-position matches: `exact/len ≥ minExactRatio` **and** `substitutions ≤ maxSubstitutions`; survivors get the adjacency-weighted score `Σ KeyAdjacency.substitutionScore / len` (1.0 exact, ≈0.86 neighbour key, → 0 distant); a Damerau transposition scores `1 − transpositionPenalty / len` | `autocorrect_min_exact_ratio` (0.50, 0.3..0.8), `autocorrect_max_substitutions` (2, 1..3), `autocorrect_transposition_penalty` (0.15, 0..0.5) |
| different length | `KeyAdjacency.weightedEditDistance(typed, cand, maxEd) ≤ maxEd` with `maxEd = lengthDiff + lengthEditBudget`; score `1 − ed / maxLen` | `autocorrect_length_edit_budget` (0.5, 0..1.5) |
| char-match | the legacy `autocorrect_char_match_threshold` still gates the raw score | (0.65, 0.5..0.9) |

The gates pass about 200 candidates per input at length difference 2 (eval §1.3), which is
why the offline grid needs a bound-based pre-filter (`ChannelRankingEval`).

## 4. Ranking the survivors

`autocorrect_ranking` (`AutocorrectRanking`, default **`channel`** since the 2026-10-19
maintainer decision; `band` is the previous behaviour, one tap away in Settings →
Auto-Correction → Ranking & Tuning → "Score band, then frequency").

### 4.1 CHANNEL — rule C (shipped default)

Minimise `edit cost + λ · rank steps`, so frequency trades against the number and kind of edits
on one scale instead of overriding them inside a band.

```
ChannelEditCost.cost(typed, cand, w) =
    OSA alignment with
        substitution a→b : 1 + distanceCost · keyDistance(a, b)      (keyDistance ∈ [0,1])
        insertion/deletion : indelCost                                  (doubleCost when the letter repeats its neighbour)
        adjacent transposition : transpositionCost
  + shortWordDeletionCost · shortDeletion(typed, cand, scope)          (δ, §5)

ChannelRanker.totalCost = editCost + frequencyWeight · rankSteps(freq, maxFreq) − (aliasBonus if contraction alias)
rankSteps = (maxFreq − freq) · 255 / maxFreq        — the unit of the shipped .bin rank byte
ChannelRanker.pick  = argmin totalCost; ties (within TIE_EPSILON): alias first, then higher frequency, then lexical
```

Then, in `WordPredictor`'s channel decision:

- a re-doubling winner yields to the best non-re-doubling candidate when that is a custom/user
  word (the band path's DD-M rule, unmeasured — the eval corpora carry no user words);
- **guard G as a price** (rule C2): when step 1.5 found a stem, the typed word is kept if
  `inflectionCost + λ · rankSteps(stemFreq) ≤ winner's totalCost` (a re-doubling bypasses the
  guard, as DD-M ran before it in the band path);
- **κ** (`maxCorrectionCost`): if the winner's *edit* cost exceeds κ, no correction (§5);
- the frequency floor / user-word commit test of step 5.

`ChannelWeights` defaults and slider ranges (`AutocorrectTuning.Keys`, `Defaults`):

| Weight | Pref key | Default | Range | Meaning |
|--------|----------|---------|-------|---------|
| s₁ `distanceCost` | `autocorrect_channel_distance_cost` | 2.0 | 0..4 | extra cost per unit of key distance (0 = every wrong key equal; 4 = a far corner costs five neighbour slips) |
| g `indelCost` | `autocorrect_channel_indel_cost` | 1.5 | 0.5..2 | a missing or extra letter |
| g₂ `doubleCost` | `autocorrect_channel_double_cost` | 1.0 | 0.1..2 | a missing/extra letter that repeats its neighbour (`helo`, `gamees`) |
| τ `transpositionCost` | `autocorrect_channel_transposition_cost` | 1.2 | 0.3..2 | two adjacent letters swapped (`teh`) |
| λ `frequencyWeight` | `autocorrect_channel_frequency_weight` | 0.005 | 0..0.1 | cost per rank step of rarity; `1/λ` = 200 rank steps equal one wrong key |
| β `aliasBonus` | `autocorrect_channel_alias_bonus` | 0.25 | 0..1 | subtracted for a contraction alias key (`donr` → `don't` over `done`) |
| δ `shortWordDeletionCost` | `autocorrect_channel_short_deletion_cost` | 0.5 | 0..2 | added when the candidate is shorter than the typed word and ≤ `SHORT_WORD_MAX_LENGTH` = 2 letters |
| δ scope `shortDeletionScope` | `autocorrect_channel_short_deletion_scope` | `hebrew` | `hebrew` \| `all` | where δ applies (§5) |
| κ `maxCorrectionCost` | `autocorrect_channel_max_correction_cost` | 3.0 | 1..10 (10 = off, ∞) | a correction whose edit cost exceeds κ is not made |
| γ `inflectionCost` | `autocorrect_channel_inflection_cost` | 1.0 | 0..10 (10 = always keep, ∞) | the cost of believing the typed word is an unlisted inflection |

### 4.2 BAND — the original fold ("Score band, then frequency")

Candidates are kept pairwise on raw score. One more than `bandGap` better wins outright;
inside the band: alias-vs-alias by raw score; alias privilege at equal-or-better score; one
Damerau swap beats two independent substitutions; then **frequency**; then score as the
deterministic tiebreak. (A neighbour-key rule, `autocorrect_neighbour_tiebreak`, was evaluated
and kept off on 2026-10-12; the setting and the rule were deleted on 2026-10-26, and old
backups carrying the key hit `SettingsValidation.DEPRECATED_KEYS`.)

| Setting | Pref key | Default | Range |
|---------|----------|---------|-------|
| band gap | `autocorrect_band_gap` | 0.10 (`SCORE_TIEBREAK_GAP`) | 0..0.3 |
| transposition penalty | `autocorrect_transposition_penalty` | 0.15 | 0..0.5 |
| DD-M enabled / margin | `autocorrect_dropped_double_enabled` / `autocorrect_dropped_double_margin` | true / 64 steps | — / 0..128 |

Why it lost (eval §0): one neighbour slip costs ≈0.02 of raw score on a six-letter word, so
0, 1 or 2 nearby errors are a tie inside the 0.10 band and a 4-rank frequency difference beat
a whole extra wrong key (`devixe` → `decide` instead of `device`).

## 5. κ and δ

- **δ, short-word deletion cost (rounds 3–4).** Hebrew's attached one-letter prefixes make
  "delete onto a two-letter word" a wrong correction (`לןא` → `לא` when `ללא` was meant); in
  English an extra letter around `in`/`to`/`it` is a real typo (round 3 broke English). δ = 0.5
  therefore applies **only to typed words written in Hebrew script**
  (`ChannelEditCost.isHebrewScript`, every letter in U+05D0–U+05EA;
  `Defaults.AUTOCORRECT_CHANNEL_SHORT_DELETION_SCOPE = "hebrew"`). Round-4 TEST: fresh HE-adj3,
  69,128 pairs, fixes 86 distinct, **0 breaks**, English 0 differences over 35,825 inputs
  (eval §12). Arabic has the same prefixes; the scope is not extended until an Arabic
  evaluation exists (`ChannelEditCost` TODO).
- **κ, maximum correction cost (round 5).** A valid word the dictionary lacks must not be
  rewritten into a distant one. κ = 3.0 on the *edit* cost (not the total with λ·rank).
  Round-5 TEST: C-oov-TEST valid unlisted words left alone 9,365 → 21,238 (+11,873), S-adj-TEST
  0 breaks, GTC-TEST gives up 48 of 5,845 distinct typo targets (eval §15). Across the shipped
  stack the space bar leaves 58 % of valid unlisted words alone, against 26 % before.

## 6. Prefix length ("Starting Letter Accuracy")

`autocorrect_prefix_length` (default **0**, slider 0..4, `AutoCorrectionSection.kt:173`): the
number of leading letters a candidate must share with the typed word, clamped to the word's
length. 0 skips the prefix filter, so a wrong first letter (`wuestion` → `question`) is
correctable; 1 or more never fixes the first letter. The 2026-10-21 run (descriptive, seen
corpora, nothing tuned) measured prefix 1 against 0 on the shipped channel tuning: GTC real
typos 79.0 % → 77.8 % (fixes 305, breaks 553 distinct), S-adj one-key slips 95.4 % → 82.7 %
(breaks 9,459 distinct — every first-letter slip loses its right word). The shipped 0 stands;
the setting's description in all 22 locales now says what the trade is
(`autocorrect_starting_letter_desc`). C-oov at prefix 1 and prefix 2 were not run (the run held
the device-wide Gradle lock for three hours; checkpoints under `build/eval/prefix/`).

## 7. Persian half-space: no rewrite, offer instead (decision (d), 2026-10-23)

Persian writes many words with an internal U+200C (`می‌شود`); the joined spelling (`میشود`) is
also a real word and the joined string cannot say where a split belongs. So:

- **Autocorrect never crosses the half-space.** `gatedCandidates` drops any candidate for which
  `WordChars.crossesHalfSpace(typed, cand)` is true — a different number of U+200C, or the same
  letters with the half-space elsewhere (`نامه‌ای` vs `نام‌های`). A typed word with a dangling
  edge ZWNJ is returned untouched (`WordChars.hasEdgeZwnj`).
- **The bar offers the other spellings.** `WordPredictor.addHalfSpaceVariants(seq, context,
  candidates)` (`WordPredictor.kt:2491`) adds, as whole-word matches with the direct-match
  prefix score, every dictionary word that is a half-space variant of the typed word
  (`halfSpaceVariantsFor(dict).variantsOf(seq)`), skipping disabled words and duplicates. Space
  keeps the typed word; these are bar candidates only. Not yet done (TODO in the source):
  variants for a joined *prefix* (`میش` → `می‌شود`) and for the secondary dictionary.

Word-character rules for the half-space (ZWNJ is a word character only between Arabic-script
letters) are in `dictionary-and-language-system.md` and `.claude/skills/dictionary-pipeline.md`;
the Persian word list in `docs/eval/2026-10-23-fa-wordlist.md`.

## 8. Evidence and tests

- `docs/eval/2026-10-18-channel-autocorrect-ranking.md` — pre-registered rounds 1–5: rule C,
  C2 (alias bonus β, guard G as γ), the 2026-10-19 maintainer override that made `channel`
  the default against a failed T6 ceiling (Hebrew short-prefix deletions, since fixed by δ),
  δ (rounds 3–4) and κ (round 5). Data: GTC (GitHub Typo Corpus), S-adj (synthetic one-key
  slips), C-oov (valid unlisted words), HE-adj/HE-adj2/HE-adj3 (Hebrew). Counts are reported
  as raw pairs with distinct target words — report both.
- `docs/eval/2026-10-21-autocorrect-prefix-length.md` — prefix length 1 vs 0 (§6).
- `docs/eval/2026-10-11-dropped-double-margin.md`, `2026-10-11-dropped-double-autocorrect.md`,
  `2026-10-12-neighbour-substitution-tiebreak.md` — the band-era rules (DD-M shipped; the
  first dropped-double rule and the neighbour tiebreak failed their gates).
- Pure JVM tests under `src/test/kotlin/tribixbite/cleverkeys/autocorrect/`:
  `ChannelRankingTest`, `KeyAdjacencyTest`, `FrequencyFloorTest`, `MorphologyTest`,
  `AutocorrectContextGuardTest`, `SpaceCommitPreviewTest`, `FuzzyPrefixMatcherTest`,
  `HebrewLayoutAutoCorrectTest`, `AutoCorrectEndToEndTest`; `AutocorrectTuningSlidersTest`
  pins that every default and every grid value is an exact slider position. The evals
  (`ChannelRankingEval`, `DroppedDoubleMarginEval`; `NeighbourTiebreakEval` was deleted with the
  neighbour rule on 2026-10-26) run under
  `runMockTests` with `CK_CH_PHASE`, from a separate worktree — they hold the device-wide
  Gradle lock for hours.

Dictionary scale trap: the tap predictor loads `en_enhanced.bin` (rank 0..255, full spread);
the CTC swipe lexicon uses `en_enhanced.json` (compressed 134..255). An autocorrect harness
that reads the JSON measures the wrong dictionary (2026-10-12).
