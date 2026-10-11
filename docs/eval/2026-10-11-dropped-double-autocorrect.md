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

## 2. Results (run 2026-10-11 14:20–15:10 EDT, origin/main ddfeeac4)

Harness: `src/test/kotlin/tribixbite/cleverkeys/autocorrect/DroppedDoubleAutocorrectEval.kt`,
run with `GRADLE_GUARD_XMX=1536m scripts/gradle-guard.sh runMockTests
-PtestClass=autocorrect.DroppedDoubleAutocorrectEval` (49 min on a box at load ~11).

Corpus SHA-256 (all in `~/.cache/cleverkeys-test/misspell/`, not committed):

| file | sha256 |
|---|---|
| wikipedia.dat | `061ec33aa12a4aea718a7bb121360812c8348770833e730124af1eecdc2cd380` |
| aspell.dat | `0198fa5c343f82f0541ae39a0116b534e14bfadc54144377cadee2bd6d288988` |
| missp.dat | `ed7d8c91961a1201632351943571e77af2011cdf45d24304c4d7cad6cf77ea15` |
| holbrook-missp.dat | `e2f0f0564954d049a1f663f3c7e72899382570a4fe575015169b1117de85ae3c` |
| words_alpha.txt | `3ed0c94610d8bcf7c11bbb49c56aa49c7234d32b66824df91f554169e572da48` |

The GitHub Typo Corpus (keystroke-level typos from commits) was also tried, but its S3
download no longer resolves. No keystroke-slip corpus was available.

### 2.1 Sets after the §1.2 filter

| set | pairs | distinct targets | distinct misspellings | real-word errors excluded | dropped-double pairs |
|---|---|---|---|---|---|
| R-typed | 2,780 | 2,078 | 2,529 | 149 | 297 |
| R-hand | 30,545 | 5,095 | 29,570 | 5,472 | 655 |
| S-adj | 278,631 | 10,000 base words | | | |
| S-dd | 8,986 | 8,571 base words | | | |
| C-oov | 292,027 words | | | | |

Baseline accuracy on R-typed: 1,436 / 2,780 (51.7 %). On its dropped-double subset it is
97 / 297 (32.7 %).

### 2.2 Outcomes (raw pairs, with distinct target / base words in parentheses)

| set | variant | eligible | fixes | breaks | swaps | net distinct |
|---|---|---|---|---|---|---|
| R-typed | A | 321 (286) | 199 (182) | 5 (5) | 17 (16) | **+177** |
| R-typed | A-post | 289 (260) | 167 (155) | 5 (5) | 17 (16) | +150 |
| R-hand | A | 1,036 (878) | 505 (483) | 24 (24) | 354 (278) | **+459** |
| R-hand | A-post | 845 (733) | 407 (391) | 24 (24) | 261 (216) | +367 |
| R-union | A | | 611 distinct | 27 distinct | | +584 |
| R-union | A-post | | 506 distinct | 27 distinct | | +479 |
| S-adj | A | 1,037 (804) | 0 | **198 (179)** | 839 (648) | −179 |
| S-adj | A-post | 863 (682) | 0 | **198 (179)** | 665 (525) | −179 |
| S-dd (not gated) | A | 8,962 (8,554) | 7,389 (7,168) | 0 | 39 (39) | +7,168 |
| S-dd (not gated) | A-post | 8,110 (7,736) | 6,551 (6,355) | 0 | 25 (25) | +6,355 |
| C-oov | A | 818 | 241 new changes (0.083 %) | | 526 already changed by baseline | |
| C-oov | A-post | 577 | 0 new changes (0.000 %) | | 526 | |

### 2.3 Gate

| gate | A | A-post |
|---|---|---|
| G1. Net > 0 on R-typed and on R-hand | pass (+177 / +459) | pass (+150 / +367) |
| G2. R-union breaks ≤ 0.10 × fixes | pass (27 ≤ 61.1) | pass (27 ≤ 50.6) |
| G3. S-adj breaks ≤ 1.0 %, raw **and** distinct | **FAIL**: raw 198 / 278,631 = 0.071 %, distinct 179 / 10,000 = **1.79 %** | **FAIL**: same, 1.79 % |
| G4. C-oov new changes ≤ 0.5 % | pass (0.083 %) | pass (0.000 %) |

**Decision: neither variant passes the pre-registered gate. Nothing ships.** The device
finding (`helo` → `help` on commit while the bar shows `hello`) stays open.

### 2.4 What failed and why it matters

The real-misspelling data is strongly in favour of the rule: 611 distinct words fixed against
27 broken. The concentration check holds, because fixes and breaks are both almost one per
target word. Most of the real breaks are label noise or British/American pairs
(`jewelery` → `jewellery`, `milenium` → `millenium`, `personel` → `personnel`).

The failure is the exact mirror of the reported bug. One adjacent-key slip in 179 of the 10,000
most frequent words produces a string that a doubled letter turns into a different word, and
those are high-frequency words that the baseline corrects correctly today:

- `ghe`→the (rule: ghee), `wity`→with (witty), `grom`/`vrom`→from (groom/vroom)
- `bery`/`fery`/`gery`→very (berry/ferry/gerry), `kne`→one (knee)
- `sed`→see (seed), `gime`→time (gimme), `nany`→many (nanny), `boty`→both (booty)
- `ner`→her (`ne'er` via the alias table), and `helo` → help (hello) itself

Measured per occurrence in running text, these would weigh far more than the raw 0.071 %
suggests, because `the`, `with`, `from` and `very` dominate real typing. The raw number treats
every word of the top 10,000 equally.

Exploratory only (not pre-registered, and it decides nothing): raising the minimum typed length
does not separate the two effects. At length ≥ 5, S-adj still breaks 97 distinct words (0.97 %),
while `helo` (4 letters) would no longer be fixed. Any follow-up needs a different rule shape,
for example one gated on the frequency ratio between the insertion target and the sweep
winner, or on the bar's own scoring. It also needs a fresh pre-registration, and ideally real
keystroke-slip data. These corpora are cognitive spelling errors (R-hand mostly handwritten),
not touch-typing slips, and the relative rate of dropped-double versus adjacent-key slips on a
phone keyboard is unmeasured.

### 2.5 Bar agreement (R-typed, reported only)

The first entry of the tap-typing bar (`predictWordsWithContext(typed, [])`) equals the target
on 935 / 2,780 pairs. The space-bar commit differs from that first entry on 2,053 pairs at
baseline, 1,996 under A and 2,010 under A-post. Most of the disagreement is structural: for an
out-of-dictionary input, the bar's first entry is often a prefix completion or a whole-word
fuzzy correction from `FuzzyPrefixMatcher`, which is a different ranker from the
`autoCorrect` sweep. On these corpora the commit is right more often than the bar's first entry
(1,436 vs 935). Caveat: the harness reads `WordPredictor`'s list. What `SuggestionHandler`
highlights on the device can differ (for example, the typed-word slot).

### 2.6 Named examples

| typed | baseline | A | A-post |
|---|---|---|---|
| helo | help | hello | hello |
| tomorow | comoros | tomorrow | tomorrow |
| ocasion | location | occasion | occasion |
| begining | begining (morphology freeze) | beginning | begining |
| adress | across | address | address |
| sucess | access | success | success |

The baseline's misses on long words (`tomorow` → `comoros`, `sucess` → `access`, `adress` →
`across`) are a separate weakness that this rule does not have to solve alone. On R-typed the
dropped-double class is only 32.7 % correct at baseline.
