---
title: Autocorrect - Technical Specification
description: Adjacency-weighted dictionary scorer with structural guards (non-prose context, possessives, inflections), Damerau transpositions, and a rule-based tiebreak.
user_guide: /wiki/typing/autocorrect/
status: implemented
version: v1.5.0
---

# Autocorrect Technical Specification

## Overview

CleverKeys autocorrect is an adjacency-weighted dictionary scorer with a rule-based selection function. The scoring model uses physical keyboard distance to rank candidate replacements, so fingertip-typical typos (adjacent-key substitutions and adjacent transpositions) outscore arbitrary string-distance matches. Contractions and accented Latin characters are first-class citizens in the model; Latin letters are scored on a built-in QWERTY table, and the letters of non-Latin boards on the active layout's own key grid.

This spec covers the v1.5.0 pipeline: the Tier A (#101) + Tier B (layout-aware) adjacency model from v1.4.0, plus the guard layer added since — non-prose context suppression (URLs/emails/paths), possessive and inflection guards, doubled-letter elongation collapse, dropped-doubled-letter insertion with a frequency margin, the Damerau transposition fast path, a dictionary-scaled frequency floor, and disabled-word exclusion.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| `KeyAdjacency` | `src/main/kotlin/tribixbite/cleverkeys/autocorrect/KeyAdjacency.kt` | Position table, distance math, layout injection |
| `AutocorrectContextGuard` | `src/main/kotlin/tribixbite/cleverkeys/autocorrect/AutocorrectContextGuard.kt` | Detects URL/email/path tokens at the cursor; suppresses autocorrect |
| `Morphology` | `src/main/kotlin/tribixbite/cleverkeys/autocorrect/Morphology.kt` | `inflectionStems()` for the valid-inflection guard |
| `FrequencyFloor` | `src/main/kotlin/tribixbite/cleverkeys/autocorrect/FrequencyFloor.kt` | Maps the 100–2000 slider onto the loaded dictionary's frequency scale |
| `WordPredictor.autoCorrect` | `WordPredictor.kt:1855` | The pipeline entry point + selection logic |
| `WordPredictor.isAdjacentTransposition` | `WordPredictor.kt:1831` | Damerau swap detector |
| `LayoutLetterGrid` | `src/main/kotlin/tribixbite/cleverkeys/autocorrect/LayoutLetterGrid.kt` | Letter-key centres of a layout in key-grid units |
| `Keyboard2View.setKeyboard` | `Keyboard2View.kt` | Installs the live layout's non-Latin letter grid into `KeyAdjacency` |
| `SuggestionHandler` | `SuggestionHandler.kt:999-1004` | Wires autocorrect into the IME's word-completion flow (context guard + undo) |
| `PredictionContextTracker` | `PredictionContextTracker.kt` | Tracks `lastAutocorrectOriginalWord` for undo; `shouldSyncForInputType` (`:612`) detects URI/email/password fields |

## Pipeline

```
User types word + space
        ↓
SuggestionHandler word-completion path
        ↓
┌──────────────────────────────────────────────┐
│ Context gate (SuggestionHandler.kt:999-1004) │
│   AutocorrectContextGuard.isNonProseContext( │
│     ic.getTextBeforeCursor(72, 0))           │
│   token contains ./:@#?&=%~\ or digits       │
│   → SKIP autocorrect for this token          │
└──────────────────────────────────────────────┘
        ↓ (prose)
WordPredictor.autoCorrect(typedWord)   (WordPredictor.kt:1855)
        ↓
┌──────────────────────────────────────────────┐
│ Step 0: contractionAliases[typedWord]        │  ← exact alias hit
│   "dont" → "don't" (direct map lookup)       │
└──────────────────────────────────────────────┘
        ↓ (miss)
┌──────────────────────────────────────────────┐
│ Step 1: dictionary.containsKey(typedWord)    │  ← already valid
│   "hello" → "hello"                          │
└──────────────────────────────────────────────┘
        ↓ (miss)
┌──────────────────────────────────────────────┐
│ Step 1.4: elongation collapse (:1889)        │
│   doubled letter whose removal yields a      │
│   dictionary word → correct directly          │
│   "gamees" → "games", "embeer" → "ember"     │
├──────────────────────────────────────────────┤
│ Step 1.45: dropped doubled letter (DD-M)     │
│   double one letter → dictionary word D;     │
│   D wins over the sweep's word W only if     │
│   D is <= 32 rank steps rarer (or no W)      │
│   "helo" → "hello", "bery" stays → "very"    │
├──────────────────────────────────────────────┤
│ Step 1.5: morphological guard (:1946)        │
│   inflectionStems(word) hits a dict word of  │
│   length >= 4 → return typedWord unchanged   │
├──────────────────────────────────────────────┤
│ Step 1.6: possessive guard (AC-4, :1951)     │
│   base's / bases' of a known word → keep;    │
│   possessive of a TYPO → autoCorrect(base) + │
│   reattach suffix ("embeer's" → "ember's")   │
└──────────────────────────────────────────────┘
        ↓
┌──────────────────────────────────────────────┐
│ Step 2: length >= autocorrect_min_word_length│
└──────────────────────────────────────────────┘
        ↓ (pass)
┌──────────────────────────────────────────────┐
│ Step 3: build prefix gate from config         │
│   autocorrect_prefix_length:                  │
│     0 = no prefix (first-char typos OK)       │
│     N = candidate must share N leading chars  │
└──────────────────────────────────────────────┘
        ↓
┌──────────────────────────────────────────────┐
│ Step 4: iterate dictionary (:2048)            │
│   For each (dictWord, candidateFrequency):    │
│     if |len diff| > max_length_diff → skip    │
│     if isWordDisabled(dictWord) → skip (:2062)│
│     score = transposition | same-length |     │
│             weighted edit distance            │
│     if score >= char_match_threshold:         │
│       update bestCandidate via rule-based     │
│       tiebreak (:2163-2185)                   │
└──────────────────────────────────────────────┘
        ↓
┌──────────────────────────────────────────────┐
│ Step 5: confirm + reroute (:2202-2217)        │
│   if winner is custom/user word OR            │
│      frequency >= FrequencyFloor.effective(): │
│     if bestCandidate.word in aliases:         │
│       return aliases[bestCandidate.word]      │
│     else:                                     │
│       return bestCandidate.word               │
└──────────────────────────────────────────────┘
```

## `KeyAdjacency` Module

Pure-JVM, no Android deps. Three public functions:

```kotlin
object KeyAdjacency {
    fun keyDistance(a: Char, b: Char): Float       // [0, 1] normalized euclidean (:170)
    fun substitutionScore(a: Char, b: Char): Float  // 1 - keyDistance (:220)
    fun weightedEditDistance(a: String, b: String): Float  // weighted Levenshtein (:231)
    fun weightedEditDistance(a: String, b: String, maxDistance: Float): Float  // early-abandon overload (:248)
    fun setLayout(positions: Map<Char, Pair<Float, Float>>)  // replace the Latin table (tests only today)
    fun setLayoutLetters(gridPositions: Map<Char, Pair<Float, Float>>)  // non-Latin letters of the live layout
    fun resetLayout()                                // revert to default QWERTY (:131)
}
```

### Position Table

The default table uses key-width grid coordinates:

```
Row 0:   q  w  e  r  t  y  u  i  o  p     (x = 0..9, y = 0)
Row 1:    a  s  d  f  g  h  j  k  l       (x = 0.5..8.5, y = 1)
Row 2:     z  x  c  v  b  n  m            (x = 1..7, y = 2)
```

Accented Latin characters share their unaccented base's position:

```
á à â ä ã å → a's position
é è ê ë     → e's position
í ì î ï     → i's position
ó ò ô ö õ ø → o's position
ú ù û ü     → u's position
ñ           → n's position
ç           → c's position
ß           → s's position
ý ÿ         → y's position
```

### Distance Normalization

The denominator is the pairwise maximum distance in the active position table:

```kotlin
private fun computeMaxDistance(p: Map<Char, Pair<Float, Float>>): Float {
    val values = p.values.toList()
    var max = 0f
    for (i in values.indices) for (j in i+1 until values.size) {
        val d = hypot(values[i].first - values[j].first,
                      values[i].second - values[j].second)
        if (d > max) max = d
    }
    return max
}
```

For the default QWERTY layout this is `q ↔ p = 9.0` (opposite ends of the top row). The previous hardcoded value of `q ↔ m = 7.28` was mathematically incorrect; the refactor at v1.4.0 fixed this and updated calibrated thresholds accordingly.

### Layout Injection

**Latin letters.** `Keyboard2View.onLayout` calls `KeyAdjacency.resetLayout()`: every Latin layout is scored on the default QWERTY (+ accents) table. Pushing live pixel geometry for Latin letters (AZERTY/QWERTZ/Dvorak/custom) is a TODO in `onLayout` — it must be measured on the autocorrect replay first (no accent rows, row stagger in the distances).

**Non-Latin letters (2026-10-08).** `Keyboard2View.setKeyboard` (live keyboard only, never a preview) calls `KeyAdjacency.setLayoutLetters(LayoutLetterGrid.of(kw))`. `LayoutLetterGrid` takes every centre-letter key (`KeyLetter.centreLetterOf`) from the shared hit-test geometry (`KeyboardGeometry.computeKeyRects`) at unit key width / row height, i.e. in the same key-grid units as the QWERTY table. `KeyAdjacency` keeps only letters the QWERTY table does not position, in a separate table:

- both letters in the QWERTY table → QWERTY distance (unchanged; `a`–`z` keep the precomputed fast path);
- both letters in the layout table → grid distance divided by the QWERTY span (9.0), so one key pitch costs ≈ 0.11 on any board, the calibration all thresholds were tuned on;
- anything else (a letter in neither table, or one in each) → 1.0.

`areNeighbors` uses the layout table's own median key pitch × 1.5 for two layout letters. `setKeyboard` rather than `onLayout` because the grid depends only on the layout and `onLayout` returns early on same-size passes.

Why it matters: before this, every substitution between two Hebrew letters cost 1.0, so a one-letter neighbour typo scored no better than any one-letter change and lost to transpositions and length changes. Measured on the shipped Hebrew pack at the shipped defaults (one random neighbour typo for each of the top-500 words, typos that are themselves words excluded): 3+ letter words corrected to the intended word 189/352 → 231/352, two-letter words 0/12 → 9/12. The same protocol on English (unchanged by the fix) corrects 296/403. The remaining misses are the frequency tiebreak between several equally close words, the same class as English's.

Thread safety is `@Volatile` + local snapshot inside `keyDistance`:

```kotlin
@Volatile private var positions: Map<Char, Pair<Float, Float>> = DEFAULT_POSITIONS
@Volatile private var maxDistance: Float = computeMaxDistance(DEFAULT_POSITIONS)

fun keyDistance(a: Char, b: Char): Float {
    if (a == b) return 0f
    // a–z × a–z: precomputed table (fast path)
    val p = positions  // local snapshot — dodges mid-call layout swap
    val pa = p[a.lowercaseChar()]; val pb = p[b.lowercaseChar()]
    if (pa != null && pb != null) return (hypot(pa.first - pb.first, pa.second - pb.second) / maxDistance).coerceIn(0f, 1f)
    val l = layoutLetters      // non-Latin letters of the live layout
    val la = l[a.lowercaseChar()] ?: return 1f
    val lb = l[b.lowercaseChar()] ?: return 1f
    return (hypot(la.first - lb.first, la.second - lb.second) / GRID_SCALE).coerceIn(0f, 1f)
}
```

Coordinates can be in any unit (pixels, key-widths, anything) — only relative distances matter.

## Scoring

Three scoring paths: an adjacent-transposition fast path, same-length dual-gate scoring, and weighted edit distance for length differences.

### Damerau Transposition Fast Path

A swap of two neighboring letters has only `wordLength - 2` exact positions, so short swaps like `teh` (1/3 exact) can never pass the 50% exact-ratio gate — even though transpositions are among the most common typo classes. `isAdjacentTransposition` (`WordPredictor.kt:1831`) detects an exact single-swap and scores it just below a perfect match:

```kotlin
// WordPredictor.kt:2087-2100
if (isAdjacentTransposition(lowerTypedWord, dictWord)) {
    // Damerau transposition fast path ("teh" → "the", "becuase"
    // → "because", "recieve" → "receive").
    isTransposition = true
    1f - TRANSPOSITION_PENALTY / wordLength
}
```

With `TRANSPOSITION_PENALTY = 0.15f` (`WordPredictor.kt:153`), `teh → the` scores `1 - 0.15/3 = 0.950` — just below a single adjacent-key substitution (`ten` at 0.959), so the within-gap frequency tiebreak resolves the rest (`the` wins on frequency).

### Same-Length (Dual-Gate + Substitution Cap)

```kotlin
// WordPredictor.kt:2107-2122
var exactCount = 0
for (i in 0 until wordLength) {
    if (lowerTypedWord[i] == dictWord[i]) exactCount++
}
val substitutions = wordLength - exactCount
isMultiSub = substitutions >= 2
if (exactCount.toFloat() / wordLength >= MIN_SAME_LENGTH_EXACT_RATIO &&
    substitutions <= MAX_SAME_LENGTH_SUBSTITUTIONS
) {
    // Pass 2: adjacency-weighted score, only for gate survivors.
    var weightedSum = 0f
    for (i in 0 until wordLength) {
        weightedSum += KeyAdjacency.substitutionScore(lowerTypedWord[i], dictWord[i])
    }
    weightedSum / wordLength
} else -1f
```

- **Gate 1a (`exactRatio >= 0.50`)** rejects unrelated same-length words. Without it, "every char-pair has SOME adjacency similarity" would let `questin` match `without` (0 exact, all-adjacent-fuzzy).
- **Gate 1b (`substitutions <= MAX_SAME_LENGTH_SUBSTITUTIONS = 2`)** caps how different a same-length candidate may be, so a single-typo match beats a higher-frequency lookalike that needs 3+ substitutions.
- **Gate 2 (`weightedScore >= char_match_threshold`)** rewards adjacency-rich matches. `tge → the` passes (2/3 exact AND weighted ≈ 0.95).
- The exact-count pass runs first with no `keyDistance` calls, so the large majority of the 98k dictionary that fails the gates skips the adjacency math entirely (`WordPredictor.kt:2102-2106`).

### Different-Length (Weighted Edit Distance, Early-Abandon)

```kotlin
// WordPredictor.kt:2124-2137
val maxEd = lengthDiff + LENGTH_DIFF_ED_BUDGET
val ed = KeyAdjacency.weightedEditDistance(lowerTypedWord, dictWord, maxEd)
if (ed <= maxEd) {
    val maxLen = maxOf(wordLength, dictWord.length).toFloat()
    (1f - ed / maxLen).coerceAtLeast(0f)
} else {
    -1f
}
```

Weighted Levenshtein DP: substitution cost = `keyDistance` (0.0 to 1.0), insertion/deletion cost = 1.0. The `maxDistance` overload (`KeyAdjacency.kt:248`) abandons the DP early once every cell in a row exceeds the budget — most dictionary words in the ±length band are unrelated and blow past `maxEd` after 2-3 rows. The absolute budget `lengthDiff + 0.5` was calibrated against the bundled English dictionary:

- `questin → question` (lenDiff=1, ed=1.0) → 1.0 ≤ 1.5 ✓
- `quuestion → question` (lenDiff=1, ed=1.0) → 1.0 ≤ 1.5 ✓
- `wuestion → season` (lenDiff=2, ed≈2.95) → 2.95 > 2.5 ✗ rejected
- `wuestion → wuthering` (lenDiff=1, ed≈2.79) → 2.79 > 1.5 ✗ rejected

## Channel Ranking (default since 2026-10-19)

The candidates that pass the gates above are ranked by **rule C2**
(`autocorrect/ChannelRanking.kt`, `WordPredictor.channelDecide`;
eval `docs/eval/2026-10-18-channel-autocorrect-ranking.md`):

```
total(c) = E(t, c) + λ · rank(c) − (alias ? β : 0)          lowest total wins
E        = optimal-string-alignment edit cost:
           substitution a→b   1 + s₁ · keyDistance(a, b)     (layout-aware)
           missing/extra      g,  or g₂ when the letter repeats its neighbour
           swapped pair       τ
rank(c)  = (F_max − f(c)) · 255 / F_max                      (rank steps, 0 = most common)
```

Ties go to an alias key, then the higher frequency, then the lexicographically smaller word.
That is a total order, so the result never depends on dictionary iteration order.

| Weight | Setting key | Shipped | Meaning |
|---|---|---|---|
| s₁ | `autocorrect_channel_distance_cost` | 2.0 | extra cost per unit of key distance |
| g | `autocorrect_channel_indel_cost` | 1.5 | missing or extra letter |
| g₂ | `autocorrect_channel_double_cost` | 1.0 | missing or extra doubled letter |
| τ | `autocorrect_channel_transposition_cost` | 1.2 | swapped neighbours |
| λ | `autocorrect_channel_frequency_weight` | 0.005 | cost per rank step (1 wrong key ≈ 200 ranks) |
| β | `autocorrect_channel_alias_bonus` | 0.25 | contraction preference (`donr` → don't) |
| δ | `autocorrect_channel_short_deletion_cost` | 0.5 | extra cost of deleting onto a word of ≤ 2 letters |
| δ scope | `autocorrect_channel_short_deletion_scope` | `hebrew` | `hebrew` (Hebrew-script words only) or `all`; English extra-letter slips around `in`/`to` are real typos (eval §10) |
| γ | `autocorrect_channel_inflection_cost` | 1.0 | inflection guard; 10 = always keep |

Steps that differ from the band ranking:

- **Guard G.** When the typed word is a regular inflection of a known stem (`shated` = `shat` +
  -ed), it is kept only if `γ + λ·rank(stem)` is no worse than the best correction's total.
  A winner that only **re-doubles letters** (`spaming` → spamming) bypasses the guard, which
  matches the band path's DD-M-before-guard order.
- **DD-M (step 1.45) is skipped.** g₂ models the dropped doubled letter. The user's own word
  still beats a re-doubling guess: with `help` added by hand, `helo` stays `help`.
- The elongation collapse (1.4), contractions (0), the possessive guard (1.6), the floor and
  finalization are shared with the band ranking.

Measured (round-2 TEST, held out): GTC 54.1 % → 79.0 %, S-adj one-key slips 67.7 % → 95.2 %,
Birkbeck net +1,616 words, valid unlisted words newly changed 0.23 %. Hebrew one-key slips
improved 67.3 % → 90.3 % but broke 42 words, mostly prefix-letter deletions onto common short
words (`לןא` → `לא`, want `ללא`). That failed the pre-registered T6 ceiling, and the maintainer
overrode it (eval doc §6). The Hebrew-scoped δ (rounds 3–4, §7–§12) then removed that class: 0
Hebrew breaks on four sets, English outputs identical.

## Band Ranking (selectable: "Score band, then frequency")

The pre-2026-10-19 default, kept as `autocorrect_ranking = band`, with every constant below a
setting (`AutocorrectTuning.Keys`). Each candidate passing the score threshold competes through
a rule-based comparator. Raw score dominates beyond a ±0.10 band; inside the band, structural rules apply before frequency:

```kotlin
// WordPredictor.kt:2163-2185
val isAlias = dictWord in contractionAliases
val bestIsAlias = bestCandidate?.isAlias == true
val better = when {
    bestCandidate == null -> true
    // Raw-score dominance beyond the gap.
    score > bestCandidate.score + SCORE_TIEBREAK_GAP -> true
    score < bestCandidate.score - SCORE_TIEBREAK_GAP -> false
    // Alias vs alias: structural closeness (raw score) wins,
    // NOT frequency — sibling contractions (`hadnt` vs `hasnt`)
    // sit at similar freqs and typing `hadnr` means `hadnt`.
    isAlias && bestIsAlias -> score > bestCandidate.score
    // Alias privilege: only at equal-or-better raw score.
    isAlias && !bestIsAlias -> score >= bestCandidate.score
    bestIsAlias && !isAlias -> score > bestCandidate.score
    // One Damerau swap beats two independent substitutions.
    isTransposition && bestCandidate.isMultiSub -> true
    bestCandidate.isTransposition && isMultiSub -> false
    // Within the band, normal case → frequency wins.
    candidateFrequency > bestCandidate.frequency -> true
    candidateFrequency < bestCandidate.frequency -> false
    // Score-close AND freq-tied → deterministic by score.
    else -> score > bestCandidate.score
}
```

### Why These Rules

| Rule | Calibration Case |
|------|------------------|
| **Score primary** | `wuestion → question` (0.986) wins over the freq-popular but distant `within` |
| **Alias vs alias** | `hadnr → hadnt` (one adj sub) wins over `hasnt` (two subs) on raw score |
| **Alias privilege (ties only)** | `donr`: `dont`/`done` tie on score → the contraction wins (`don't`). Unlike the pre-v1.5.0 `+0.15` score bonus — which could beat candidates up to 0.15 *stronger* — an alias can no longer override a structurally better match (`thier → their`, not `this'd`: the transposition outscores the 2-sub alias) |
| **Transposition > 2-sub** | `thsi → this`, not the more frequent 2-sub `that`: one swap is almost always the intent vs two independent wrong keys |
| **Close-score freq** | `tfe`: `tfw` scores 0.96 but `the` beats it on frequency inside the band |
| **Deterministic** | Removes hash-map iteration-order dependence |

### Calibrated Constants

```kotlin
// WordPredictor.kt:47-153
private const val MIN_SAME_LENGTH_EXACT_RATIO = 0.50f   // :47
private const val MAX_SAME_LENGTH_SUBSTITUTIONS = 2     // :73
private const val LENGTH_DIFF_ED_BUDGET = 0.5f          // :100
private const val SCORE_TIEBREAK_GAP = 0.10f            // :135
private const val TRANSPOSITION_PENALTY = 0.15f         // :153
```

The `ALIAS_SCORE_BONUS` additive bonus from v1.4.0 was removed: alias preference is now a *tiebreak rule* (equal-or-better raw score inside the gap band) instead of a score inflation, so it can no longer overtake stronger matches.

## Guard Layer (pre-scan short circuits)

### Non-Prose Context Guard (URLs, emails, paths)

The word tracker only sees letters, so `teh` inside `foo.teh`, `user@teh`, or `https://teh…` looks identical to prose `teh`. The editor text reveals the real token — `SuggestionHandler` consults `AutocorrectContextGuard` before invoking `autoCorrect`:

```kotlin
// AutocorrectContextGuard.kt:22
private const val NON_PROSE_CHARS = "./:@#?&=%~\\"
```

`isNonProseContext(textBeforeCursor)` (`AutocorrectContextGuard.kt:32`) extracts the whitespace-delimited token ending at the cursor (ignoring one trailing space) and returns `true` if any character is a digit or in `NON_PROSE_CHARS`. The call site passes the last 72 chars before the cursor:

```kotlin
// SuggestionHandler.kt:999-1004
val inNonProseToken = AutocorrectContextGuard.isNonProseContext(
    ic?.getTextBeforeCursor(72, 0)
)
if (config.autocorrect_enabled && predictionCoordinator.getWordPredictor() != null &&
    text == " " && !inTermuxApp && !inNonProseToken) {
```

This is distinct from (and unrelated to) the clipboard [URL sanitization](../clipboard/url-sanitization-spec.md) feature — the guard suppresses *typing* corrections; the sanitizer strips tracking parameters from *copied* URLs.

### Elongation Collapse

Before the dictionary scan, a doubled letter whose removal yields a dictionary word is corrected directly (`WordPredictor.kt:1889-1934`): each `cc` pair is tried with one half removed; the highest-frequency surviving dictionary word wins (disabled words excluded), and contraction aliases reroute as usual. Structural certainty exempts this path from the frequency floor. Handles `gamees → games` and the base step of `embeer's → ember's`.

### Dropped Doubled Letter (DD-M, step 1.45)

This step is the mirror of the collapse. It runs after the collapse and before the morphology
guard, which would otherwise freeze `begining` as begin + -ing. For a letters-only typed
word, each letter is doubled in place. The most frequent result that is in the dictionary, is
not disabled, and clears the frequency floor becomes the insertion target D. Custom words are
exempt from the floor. The gates are the same as the collapse: typed length above
`autocorrect_min_word_length`, and `autocorrect_max_length_diff >= 1`.

D does **not** win outright. An earlier version that did failed its evaluation, because it
replaced one-key slips of common words (`bery` → berry, `grom` → groom). Instead, the normal
sweep (steps 3–4) runs once and gives the word W that step 5 would commit. Then:

- If there is no committable W, D wins.
- If W is the user's own (custom) word, W wins.
- Otherwise D wins only when `freq(D) ≥ freq(W) − DROPPED_DOUBLE_MARGIN_STEPS · maxFreq / 255`,
  i.e. when D is at most **64** steps rarer than W. On the bundled `en_enhanced.bin` one step
  is about one rank, since `BinaryDictionaryLoader` reads rank r as `1 000 000 − r · 3 900`.

When D wins, it is re-routed through the alias table like any other winner. When D loses, the
input continues down the unchanged path, and step 4 reuses the same sweep.

Examples on the device dictionary: `helo` → hello (39 ranks rarer than help), `tomorow` →
tomorrow, `adress` → address, and `sucess` → success. `bery`, `grom`, `ghe`, `sed`, `kne` and
`nany` still give very, from, the, see, one and many, because their doubled forms are 66–191
ranks rarer. see/seed (66) is the narrowest.

**Frequency scale.** The margin is only meaningful on the dictionary the device loads. The tap
predictor loads `en_enhanced.bin`. `en_enhanced.json` is the CTC swipe lexicon's compressed
134..255 scale, and it is not `255 − rank` of the `.bin`. The first tuning (M = 32, 2026-10-11)
used a JSON-derived map. There help/hello were 20 steps apart, so phones kept `help`. JVM
harnesses must load `ShippedTapDictionary` (`src/test/kotlin/tribixbite/cleverkeys/`), which
parses the `.bin` with the production `BinaryDictionaryParser`; `ShippedTapDictionaryDriftTest`
enforces this.

**Prefix length.** With `autocorrect_prefix_length` > 0, the sweep only considers words that
share the typed prefix. For example, `bery` cannot reach `very` with prefix length 1. Its W is
then a b- word, and the insertion `berry` can win. That is what a Pixel with prefix length 1
showed. The shipped default is 0, and no evaluation covers prefix lengths above 0.

M was tuned once on DEV commits of the GitHub Typo Corpus plus half of a synthetic
adjacent-slip control, then gated on the held-out half, both on the device dictionary.
Held-out results: +280 distinct net fixes against 3 breaks, 0.67 % of adjacent-slip base
words broken, and 0.036 % of valid out-of-dictionary words newly changed. Details:
`docs/eval/2026-10-11-dropped-double-margin.md` §5.

### Morphological Guard (valid inflections)

```kotlin
// WordPredictor.kt:1946
if (Morphology.inflectionStems(lowerTypedWord).any { it.length >= 4 && dict.containsKey(it) }) {
    Log.d(TAG, "AUTO-CORRECT skip (valid inflection): '$typedWord'")
    return typedWord
}
```

`Morphology.inflectionStems` generates candidate stems for regular suffixes (`-s/-es/-ies`, `-ed/-ied`, `-ing`, `-er/-est`, `-ly/-ily`). Stems shorter than 4 chars don't qualify, so short words remain correctable.

### Possessive Guard + Possessive-Typo Correction (AC-4)

A possessive of a known noun (`ember's`, `dogs'`) is valid English but never stored in the dictionary, so without this guard it would be treated as a typo. `WordPredictor.kt:1951-1993`: if the token ends in `'s` or a bare trailing apostrophe and the base is a dictionary/custom word, return unchanged. If the base is itself a typo, recurse on the base alone and reattach the suffix — preserving the original apostrophe character (typewriter `'` or curly `’`): `embeer's → ember's`.

### Disabled Words and Custom Words

- A user-disabled word is never offered as a correction target (`WordPredictor.kt:2062`); `isWordDisabled` (`:378-382`) lets custom/user-added words override disabled status.
- Custom/user words are exempt from the frequency floor (`WordPredictor.kt:2202-2204`): they are injected at a low placeholder frequency far below the binary dictionary's runtime scale, so any non-zero floor would silently exclude every custom word (AC-2).

## Frequency Floor (dictionary-scaled)

The `autocorrect_confidence_min_frequency` slider (100–2000, default 100) no longer compares against raw stored frequencies — dictionary scales vary wildly per language and format. `FrequencyFloor` maps the slider position onto the loaded dictionary's own maximum frequency:

```kotlin
// FrequencyFloor.kt:40-59
const val SLIDER_MIN = 100
const val SLIDER_MAX = 2000
const val MAX_STRICTNESS = 0.6f

fun effective(sliderValue: Int, maxFreq: Int): Int {
    if (maxFreq <= 0) return 0 // dictionary not loaded yet → don't gate
    val span = (SLIDER_MAX - SLIDER_MIN).toFloat()
    val t = ((sliderValue - SLIDER_MIN).toFloat() / span).coerceIn(0f, 1f)
    return (t * MAX_STRICTNESS * maxFreq).toInt()
}
```

Slider 100 → floor 0 (any dictionary word can win); slider 2000 → floor = 60% of the dictionary's max frequency. `MAX_STRICTNESS < 1` guarantees the most common words always clear the floor. Applied at `WordPredictor.kt:2023` via `FrequencyFloor.effective(configFloor, dictMaxFrequency(dict))`.

## Bar Highlight = Space Commit (2026-10-11)

The tap-typing bar's highlighted entry is exactly what space will commit for the composing
word (maintainer decision after the Pixel `Helo` → bar `Hello` / commit `Help` report). The
bar follows the autocorrect ranking and does not rank on its own; the dropped-double step
(above) is what now makes that commit `Hello`.

- **One decision.** `SpaceCommitResolver.resolve` (`autocorrect/SpaceCommitPreview.kt`) answers
  "what does space commit for this word": I-word capitalization first (`CAPITALIZE_I`), then
  `autoCorrect` gated on `autocorrect_enabled`, the terminal-editor exclusion, the non-prose
  token guard and the unmerged joiner-stem guard (`AUTOCORRECT`), else `KEEP`. The space path in
  `SuggestionHandler.handleRegularTyping` and the bar both call it. The non-prose editor read is
  lazy: it happens only when a correction would otherwise fire.
- **Bar arrangement.** `BarHighlightPlanner.plan` puts a replacement in slot 0 and highlights
  it: an already-listed entry (any casing) is moved with its score and provenance and shown in
  the committed casing; an absent one is inserted with `TYPO_CORRECTION` /
  `TypoCorrectionOf(typed)`. With `KEEP`, only an entry equal to the typed text is highlighted,
  or nothing. Protocol entries (add-to-dictionary, exact-add, swipe preference) are never
  targets. `SuggestionBar.setSuggestionsWithScores(..., highlightIndex)` renders the highlight
  (default `0` keeps every other caller, e.g. swipe results, unchanged).
- **Cost and reuse.** `autoCorrect` sweeps the dictionary, so it runs on the prediction worker
  with the bar's predictions, never on the UI thread per keystroke. `AutocorrectMemo` keeps the
  result keyed on the exact word, the predictor instance and a fingerprint of the autocorrect
  settings; the bar post (main thread) and the space path read it, so space reuses the bar's
  sweep and recomputes only when the word or inputs differ. The memo is cleared at each new word
  and on every cursor-sync re-read, so a dictionary edit between words is never served stale.
  Measured in `SpaceCommitPreviewTest` (see the test table).

## Suggestion Taps in URI/Email Fields (#151)

Browser URL bars, email fields, and password fields never get cursor-sync (`PredictionContextTracker.shouldSyncForInputType`, `PredictionContextTracker.kt:612`, skips `TYPE_TEXT_VARIATION_URI`/`EMAIL_ADDRESS`/password variants), so the tracker's deletion counts stay `(0,0)` there. `SuggestionHandler.onSuggestionSelected` detects these fields up front (`SuggestionHandler.kt:491`: `syncSuppressedField = !contextTracker.shouldSyncForInputType(editorInfo)`) and:

- forces the editor-scan fallback that measures the typed partial token via `getTextBeforeCursor` and deletes it before committing the suggestion (`SuggestionHandler.kt:585-596`), so tapping `example` after typing `exa` produces `example`, not `exa example`;
- never injects a leading/trailing space, which would corrupt a URL or address value.

## Contraction Handling

### Alias Map

`contractionAliases: Map<String, String>` maps apostrophe-free base forms to their contracted forms. Loaded from `dictionaries/contractions_<lang>.json`:

```json
{
  "dont": "don't",
  "cant": "can't",
  "im": "i'm",
  "hadnt": "hadn't",
  "couldnt": "couldn't"
}
```

Real-word bases that ALSO appear in the contractions file are filtered out via `REAL_WORD_CONTRACTION_BASES`:

```kotlin
private val REAL_WORD_CONTRACTION_BASES = setOf(
    "well", "were", "hell", "shed", "shell", "wed",
    "editors", "girls", "readers", "states", "whore"
)
```

This stops `well → we'll`, `were → we're`, `shed → she'd`, etc.

### Dictionary Injection (freq-preservation fix)

```kotlin
// WordPredictor.kt:1120-1122
// PRESERVES existing freq (was destructive `?: 5000` before v1.4.0)
currentDict[withoutApostrophe] = currentDict[withApostrophe]
    ?: currentDict[withoutApostrophe]
    ?: 5000
```

The earlier `?: 5000` form silently downgraded binary-loaded freqs (e.g., `hadnt` from ~789K to 5000). The fix preserves whichever value is already present, falling back to 5000 only when neither form exists.

### Alias Rerouting

Two layers:

1. **Step 0 direct path** — exact `typedWord` lookup in `contractionAliases` (handles `dont → don't`).
2. **Step 5 dict-scan reroute** — after the four-tier selection picks a winner, if that winner is an alias-key, return the contracted form. Reuses the same I-capitalization rule from Step 0:

```kotlin
val winnerWord = bestCandidate.word
val aliasTarget = contractionAliases[winnerWord]
val outputWord = aliasTarget ?: winnerWord
val corrected = if (aliasTarget != null && aliasTarget.startsWith("i'")) {
    aliasTarget.replaceFirstChar { it.uppercase() }
} else {
    preserveCapitalization(typedWord, outputWord)
}
```

This handles `donr → don't`, `hadnr → hadn't`, `couldnr → couldn't`.

## Configuration

All `Config` fields below are read in `autoCorrect`. Defaults at v1.5.0 (`Config.kt:179-189` in `object Defaults`):

| Setting | Key | Default | Range | Description |
|---------|-----|---------|-------|-------------|
| **Enabled** | `autocorrect_enabled` | `true` | bool | Master toggle |
| **Min word length** | `autocorrect_min_word_length` | `2` | 1–5 | Skip very short words |
| **Required prefix** | `autocorrect_prefix_length` | `0` | 0–5 | Candidates must share leading N chars (`0` = no prefix; allows first-char typos) |
| **Score threshold** | `autocorrect_char_match_threshold` | `0.65` | 0.5–0.95 | Min match score |
| **Max length diff** | `autocorrect_max_length_diff` | `2` | 0–5 | Allow ±N length candidates |
| **Min freq floor** | `autocorrect_confidence_min_frequency` | `100` | 100–2000 | Slider mapped onto the dictionary's frequency scale via `FrequencyFloor.effective()`; 100 = no floor. Custom/user words exempt |
| **Diagnostic logging** | `swipe_debug_detailed_logging` | `false` | bool | Gates rejection-reason logs |
| **Ranking & tuning** | `autocorrect_ranking` + 18 more | `channel` | — | Every ranking weight, gate and step switch: `autocorrect/AutocorrectTuning.kt` `Keys` (single definition of key, default and range, shared by prefs, backup validation and the Settings group) |

## User Freq Control & Beam Search Interaction

The freq-preservation fix at line 1024 is safe for beam search because beam search consumes frequency through `OptimizedVocabulary.WordInfo`, which:

1. Normalizes raw frequency to `[0, 1]`.
2. Multiplied by a frequency weight (a `neural_frequency_weight` knob, removed with the transformer engine on 2026-08-18).
3. Combines with NN confidence via `VocabularyUtils.calculateCombinedScore`.

Higher input freq → slightly higher final beam score, no breakage. The CTC engine now applies a per-language λ over `ln(freq)` instead, calibrated offline.

## Undo Mechanism

State tracked in `PredictionContextTracker`:

```kotlin
var lastAutocorrectOriginalWord: String? = null
var lastAutocorrectReplacementWord: String? = null
var lastAutocorrectPosition: Int = -1

fun trackAutocorrect(original: String, replacement: String, position: Int)
fun clearAutocorrectTracking()
```

When the user taps the original word in the suggestion bar:

```kotlin
// SuggestionHandler.kt
fun handleAutocorrectUndo(ic: InputConnection, originalWord: String) {
    val replacement = contextTracker.lastAutocorrectReplacementWord ?: return
    ic.deleteSurroundingText(replacement.length + 1, 0)
    ic.commitText("$originalWord ", 1)
    dictionaryManager.addCustomWord(originalWord, config.primary_language)
    contextTracker.clearAutocorrectTracking()
    confirmDictionaryAdd(originalWord, inserted, R.string.suggestion_added_to_dictionary)
}
```

Adding to the user dictionary on undo ensures the same correction won't fire again. The
confirmation (`confirmDictionaryAdd` → `SuggestionBar.showUndoableMessage`, commit 4ca6c499)
is a tappable bar message: the first tap arms "Tap again to undo", the second removes the
word from the personal dictionary again (only when this add actually inserted it) and shows
"Removed “x” from dictionary". The text in the field is not touched. It times out after 3 s
and any typing, swipe or other bar tap dismisses it. The same confirmation is used by every
IME add path (add-to-dictionary prompt, "+word" chip, autocorrect undo, accepting the
"Prefer … when swiping?" offer). State machine: `UndoableBarMessage`.

## Test Coverage

| Suite | File | Coverage |
|-------|------|----------|
| Pure JVM — KeyAdjacency | `src/test/kotlin/tribixbite/cleverkeys/autocorrect/KeyAdjacencyTest.kt` | Position math, accents, layout swap |
| Mock JVM — Hebrew board | `src/test/kotlin/tribixbite/cleverkeys/autocorrect/HebrewLayoutAutoCorrectTest.kt` | Real he pack + real `hebr_1_il` grid: neighbour typos correct, Latin distances unchanged, live wiring |
| Pure JVM — Context guard | `src/test/kotlin/tribixbite/cleverkeys/autocorrect/AutocorrectContextGuardTest.kt` | Non-prose token detection |
| Pure JVM — Frequency floor | `src/test/kotlin/tribixbite/cleverkeys/autocorrect/FrequencyFloorTest.kt` | Slider→floor mapping, unloaded-dict guard |
| Pure JVM — Morphology | `src/test/kotlin/tribixbite/cleverkeys/autocorrect/MorphologyTest.kt` | Inflection stem generation |
| Mock JVM — End to end | `src/test/kotlin/tribixbite/cleverkeys/autocorrect/AutoCorrectEndToEndTest.kt` | Full pipeline against the shipped tap dictionary (`en_enhanced.bin` via `ShippedTapDictionary`), incl. the DD-M margin pin |
| Mock JVM — Tap dictionary drift | `src/test/kotlin/tribixbite/cleverkeys/ShippedTapDictionaryDriftTest.kt` | Harness dictionary == the production `.bin` asset load; independent V2 decode; no tap harness built from `en_enhanced.json` |
| Mock JVM — Bar highlight | `src/test/kotlin/tribixbite/cleverkeys/autocorrect/SpaceCommitPreviewTest.kt` | Real 98k dictionary: highlighted bar entry == space commit (`helo` → `hello`), no replacement highlighted when autocorrect is off / terminal / non-prose / valid word, memo reuse, per-keystroke cost |
| Instrumented — AutocorrectTest | `src/androidTest/kotlin/tribixbite/cleverkeys/AutocorrectTest.kt` | Adjacency typos, length-diff, contractions, prefix gate, case preservation |
| Instrumented — URL guard | `src/androidTest/kotlin/tribixbite/cleverkeys/AutocorrectUrlGuardTest.kt` | Guard behavior in real input connections |
| Instrumented — TypingSimulationTest | `src/androidTest/kotlin/tribixbite/cleverkeys/TypingSimulationTest.kt` | Typing scenarios incl. step-0 alias direct (`im → I'm`) |

## Related Specifications

- [Swipe Typing Specification](swipe-typing-spec.md) — the swipe decoders consume the same dict + freq
- [Dictionary System](https://github.com/tribixbite/CleverKeys/blob/main/docs/specs/dictionary-and-language-system.md) — word storage, binary format, language packs
- [User Dictionary](../../specs/typing/user-dictionary-spec.md) — custom-word and disabled-word lists
