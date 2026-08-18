---
title: Autocorrect - Technical Specification
description: Adjacency-weighted dictionary scorer with structural guards (non-prose context, possessives, inflections), Damerau transpositions, and a rule-based tiebreak.
user_guide: ../../typing/autocorrect.md
status: implemented
version: v1.5.0
---

# Autocorrect Technical Specification

## Overview

CleverKeys autocorrect is an adjacency-weighted dictionary scorer with a rule-based selection function. The scoring model uses physical keyboard distance to rank candidate replacements, so fingertip-typical typos (adjacent-key substitutions and adjacent transpositions) outscore arbitrary string-distance matches. Contractions, accented Latin characters, and runtime layout swaps (AZERTY/QWERTZ/Dvorak/custom) are all first-class citizens in the model.

This spec covers the v1.5.0 pipeline: the Tier A (#101) + Tier B (layout-aware) adjacency model from v1.4.0, plus the guard layer added since — non-prose context suppression (URLs/emails/paths), possessive and inflection guards, doubled-letter elongation collapse, the Damerau transposition fast path, a dictionary-scaled frequency floor, and disabled-word exclusion.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| `KeyAdjacency` | `src/main/kotlin/tribixbite/cleverkeys/autocorrect/KeyAdjacency.kt` | Position table, distance math, layout injection |
| `AutocorrectContextGuard` | `src/main/kotlin/tribixbite/cleverkeys/autocorrect/AutocorrectContextGuard.kt` | Detects URL/email/path tokens at the cursor; suppresses autocorrect |
| `Morphology` | `src/main/kotlin/tribixbite/cleverkeys/autocorrect/Morphology.kt` | `inflectionStems()` for the valid-inflection guard |
| `FrequencyFloor` | `src/main/kotlin/tribixbite/cleverkeys/autocorrect/FrequencyFloor.kt` | Maps the 100–2000 slider onto the loaded dictionary's frequency scale |
| `WordPredictor.autoCorrect` | `WordPredictor.kt:1855` | The pipeline entry point + selection logic |
| `WordPredictor.isAdjacentTransposition` | `WordPredictor.kt:1831` | Damerau swap detector |
| `Keyboard2View.onLayout` | `Keyboard2View.kt:1303` | Pushes the active layout's key positions into `KeyAdjacency` |
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
    fun setLayout(positions: Map<Char, Pair<Float, Float>>)  // Tier B injection (:115)
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

### Layout Injection (Tier B)

`Keyboard2View.onLayout` extracts key positions in pixel coordinates and pushes them to `KeyAdjacency`:

```kotlin
// Keyboard2View.kt:~1303
override fun onLayout(changed: Boolean, ...) {
    if (!changed) return
    // ...gesture exclusion rects...
    try {
        val realPositions = getRealKeyPositions()
        val adjacencyPositions = realPositions.mapValues { (_, pt) -> pt.x to pt.y }
        KeyAdjacency.setLayout(adjacencyPositions)
    } catch (e: Exception) {
        Log.w("Keyboard2View", "Failed to push layout to KeyAdjacency: ${e.message}")
    }
}
```

Thread safety is `@Volatile` + local snapshot inside `keyDistance`:

```kotlin
@Volatile private var positions: Map<Char, Pair<Float, Float>> = DEFAULT_POSITIONS
@Volatile private var maxDistance: Float = computeMaxDistance(DEFAULT_POSITIONS)

fun keyDistance(a: Char, b: Char): Float {
    if (a == b) return 0f
    val p = positions  // local snapshot — dodges mid-call layout swap
    val pa = p[a.lowercaseChar()] ?: return 1f
    val pb = p[b.lowercaseChar()] ?: return 1f
    val d = hypot(pa.first - pb.first, pa.second - pb.second)
    return (d / maxDistance).coerceIn(0f, 1f)
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

## Rule-Based Candidate Selection

Each candidate passing the score threshold competes through a rule-based comparator. Raw score dominates beyond a ±0.10 band; inside the band, structural rules apply before frequency:

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
    showTemporaryMessage("Added '$originalWord' to dictionary")
}
```

Adding to the user dictionary on undo ensures the same correction won't fire again.

## Test Coverage

| Suite | File | Coverage |
|-------|------|----------|
| Pure JVM — KeyAdjacency | `src/test/kotlin/tribixbite/cleverkeys/autocorrect/KeyAdjacencyTest.kt` | Position math, accents, layout swap |
| Pure JVM — Context guard | `src/test/kotlin/tribixbite/cleverkeys/autocorrect/AutocorrectContextGuardTest.kt` | Non-prose token detection |
| Pure JVM — Frequency floor | `src/test/kotlin/tribixbite/cleverkeys/autocorrect/FrequencyFloorTest.kt` | Slider→floor mapping, unloaded-dict guard |
| Pure JVM — Morphology | `src/test/kotlin/tribixbite/cleverkeys/autocorrect/MorphologyTest.kt` | Inflection stem generation |
| Pure JVM — End to end | `src/test/kotlin/tribixbite/cleverkeys/autocorrect/AutoCorrectEndToEndTest.kt` | Full pipeline against the shipped dictionary |
| Instrumented — AutocorrectTest | `src/androidTest/kotlin/tribixbite/cleverkeys/AutocorrectTest.kt` | Adjacency typos, length-diff, contractions, prefix gate, case preservation |
| Instrumented — URL guard | `src/androidTest/kotlin/tribixbite/cleverkeys/AutocorrectUrlGuardTest.kt` | Guard behavior in real input connections |
| Instrumented — TypingSimulationTest | `src/androidTest/kotlin/tribixbite/cleverkeys/TypingSimulationTest.kt` | Typing scenarios incl. step-0 alias direct (`im → I'm`) |

## Related Specifications

- [Swipe Typing Specification](swipe-typing-spec.md) — the swipe decoders consume the same dict + freq
- [Dictionary System](../../../specs/dictionary-and-language-system.md) — word storage, binary format, language packs
- [User Dictionary](../../specs/typing/user-dictionary-spec.md) — custom-word and disabled-word lists
