# Dropped doubled letter in tap-typing autocorrect (`helo` → `help`, not `hello`)

Date: 2026-10-11. Scope: English, QWERTY, tap-typing space-bar autocorrect
(`WordPredictor.autoCorrect`), shipped defaults. Release under test: 051dca51 / origin/main ddfeeac4.

Device report (Pixel, 2026-10-11): tap-typing `Helo` + space commits `Help`, while the
suggestion bar's top (highlighted) entry for the same input was `Hello`. In the sweep,
same-length `help` scores ≈0.96 (one adjacent substitution o→p) and length+1 `hello` 0.80
(edit distance 1 over 5 letters). The gap is wider than `SCORE_TIEBREAK_GAP` (0.10), so `help`
wins on score before frequency or structure is consulted. Step 1.4 already handles the
opposite slip, an extra doubled letter (`gamees` → `games`). Nothing handles a dropped one
(`helo` → `hello`, `tomorow` → `tomorrow`, `begining` → `beginning`).

## 1. Pre-registration (written 2026-10-11 14:14 EDT, before any measurement was run)

Nothing below has been computed yet. The corpora were downloaded and their formats read; no
autocorrect output on them has been looked at.

### 1.1 Candidate rules

Both variants share the **insertion rule**. For each position `i` where `typed[i]` is a letter,
form `typed[0..i] + typed[i] + typed[i..]`, i.e. duplicate that letter in place. Keep the
results that are dictionary words and are not disabled by the user (`isWordDisabled`, which
already lets custom words override a disable). If several qualify, take the most frequent. The
frequency floor applies (`FrequencyFloor.effective`); custom/user words are exempt, as in
step 5. A contraction-alias key that wins is re-routed through `contractionAliases`, with the
same custom-word guard (C-5) and `I'` capitalization as step 1.4. Gates are the same as step
1.4: `typed.length > autocorrect_min_word_length` and `autocorrect_max_length_diff >= 1`.

The winner takes **outright precedence**, as the collapse step does. It does not compete with
the sweep inside a widened band. A band-only variant cannot fix the reported case
(`help` 0.96 vs `hello` 0.80 is 0.16 apart), unless the band itself is widened for every
length+1 candidate. That would be a much larger ranking change than this rule.

- **Variant A (step 1.45, before the morphology guard).** Runs after the collapse step
  (step 1.4), so a typed word that is already a word, a contraction alias, or a doubled-letter
  elongation keeps its current handling. It runs before the morphology guard because
  `begining` would otherwise be frozen as a valid `-ing` inflection of `begin`.
- **Variant A-post (after the morphology guard, before the possessive guard).** Identical,
  but a typed word the morphology guard treats as a valid inflection is left alone. This is
  safer for real inflections that are missing from the dictionary, and loses `begining`-type
  fixes.

### 1.2 Data

All corpora are for **local evaluation only**. None of them is committed to the repo or
packaged in assets. They live in `~/.cache/cleverkeys-test/misspell/`.

| set | source | licence / terms | role |
|---|---|---|---|
| R-typed | `wikipedia.dat` (2,455 misspellings, Wikipedia editors) + `aspell.dat` (531, Atkinson's Aspell test set), Mitton's versions from https://www.dcs.bbk.ac.uk/~ROGER/corpora.html | Wikipedia list CC BY-SA; Aspell test set GNU; distributed by Birkbeck for research | **primary**: real misspellings from adults typing |
| R-hand | `missp.dat` (Birkbeck, 36,133 misspellings, mostly handwritten, many by schoolchildren) + `holbrook-missp.dat` (1,791) | Oxford Text Archive corpus, free for research use | secondary real data (noisier, mostly handwritten) |
| C-oov | `words_alpha.txt`, dwyl/english-words (370k words) | public domain (Unlicense) | control: real English words that are missing from the bundled dictionary must not be newly changed |
| S-adj | synthetic: each of the 10,000 most frequent dictionary words (length ≥ 3), with one letter replaced by each QWERTY neighbour (`KeyAdjacency.areNeighbors`, default layout) | n/a | control: the `help`-for-`helo` direction, i.e. adjacent-key slips that the new rule could steal |
| S-dd | synthetic: each of the 10,000 most frequent dictionary words that contains a doubled letter, with one letter of the pair dropped | n/a | reported only, never gated (synthetic) |

SHA-256 of the downloaded files is recorded in §2.

Pair filter for R-typed and R-hand: lowercase both sides; letters only (no `_`, `-`, `'`,
digits); the target is in the bundled `en_enhanced` dictionary; the misspelling is **not** in
the dictionary and is not a contraction-alias key. Real-word errors are excluded because step 1
returns them untouched under both baseline and candidate; their count is reported. Duplicate
(misspelling, target) pairs are counted once. A misspelling listed under several targets is
scored against each of them, and that multiplicity is reported.

C-oov is the `words_alpha` entries of length 3–20 that are letters only, are not in the
dictionary and are not alias keys. S-adj and S-dd keep only typed strings that are not in the
dictionary.

### 1.3 Harness and metrics

The harness is a MockK-runner JUnit class, `autocorrect/DroppedDoubleAutocorrectEval`. It has
no `Test` suffix, so CI never runs it; it is run on demand with
`-PtestClass=autocorrect.DroppedDoubleAutocorrectEval`. Its setup is the same as
`AutoCorrectEndToEndTest`: the real 98k `en_enhanced` dictionary at the device frequency scale
(`1_000_000 − rank × 3900`), real contraction aliases, and the shipped autocorrect defaults
(min length 2, threshold 0.65, max length diff 2, prefix 0, slider floor 100). No custom or
disabled words.

- **Baseline** is the real `autoCorrect` at HEAD.
- **Candidates** are simulated exactly. The rule can only change an outcome when `autoCorrect`
  reaches the new step without having returned earlier. The earlier returns are the alias
  rewrite (step 0), already a word (step 1), and collapse fires (step 1.4); for A-post, also
  the morphology freeze. The insertion rule must also produce a target. Outside that case the
  candidate output equals the baseline output. After implementation, the production result
  must equal the simulated variant on every eligible input, or the eval is void.

Per set and variant, comparing against the target:

- `fix` = the baseline is wrong and the candidate is right
- `break` = the baseline is right and the candidate is wrong
- `swap` = both are wrong, with different outputs

All three are reported in **raw pairs and distinct target words**. Baseline and candidate
accuracy are reported too. For C-oov, `new change` = the baseline leaves the word unchanged and
the candidate changes it. The CLAUDE.md concentration rule applies throughout: the counts of
distinct units are the ones the gate uses.

**Bar agreement (reported, not gated):** for R-typed pairs, how often the space-bar
autocorrect commit differs from the tap-typing bar's first entry
(`predictWordsWithContext(typed, [])`, with the real prefix index), under baseline and under
the chosen variant.

### 1.4 Ship gate (all must hold; distinct target words unless stated)

- **G1. Real benefit.** Net fixes (fixes − breaks) > 0 on R-typed **and** on R-hand.
- **G2. Real precision.** On R-typed ∪ R-hand, breaks ≤ 0.10 × fixes.
- **G3. Adjacent-slip harm (S-adj).** Breaks ≤ 1.0 % of S-adj pairs, in both raw pairs and
  distinct base words.
- **G4. Valid-word harm (C-oov).** New changes ≤ 0.5 % of C-oov words.

**Choice of variant.** If both variants pass, ship the one with more distinct net fixes on
R-typed ∪ R-hand; on a tie, ship A-post (the safer one). If neither passes, nothing ships: the
numbers and this decision are committed and the device finding stays open.

These thresholds are fixed now and will not be changed after seeing results.
