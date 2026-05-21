---
title: Autocorrect - Technical Specification
user_guide: ../../typing/autocorrect.md
status: implemented
version: v1.4.0
---

# Autocorrect Technical Specification

## Overview

CleverKeys autocorrect is an adjacency-weighted dictionary scorer with a four-tier selection function. The scoring model uses physical keyboard distance to rank candidate replacements, so fingertip-typical typos (adjacent-key substitutions) outscore arbitrary string-distance matches. Contractions, accented Latin characters, and runtime layout swaps (AZERTY/QWERTZ/Dvorak/custom) are all first-class citizens in the model.

This spec covers v1.4.0 — the major refactor at Tier A (#101) + Tier B (layout-aware) introduced in May 2026. Earlier versions used position-only exact match against a hardcoded QWERTY assumption.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| `KeyAdjacency` | `src/main/kotlin/tribixbite/cleverkeys/autocorrect/KeyAdjacency.kt` | Position table, distance math, layout injection |
| `WordPredictor.autoCorrect` | `WordPredictor.kt:~1750` | The pipeline entry point + selection logic |
| `WordPredictor.loadPrimaryContractionKeys` | `WordPredictor.kt:~966` | Alias map + dict freq injection |
| `Keyboard2View.onLayout` | `Keyboard2View.kt:~1279` | Pushes the active layout's key positions into `KeyAdjacency` |
| `SuggestionHandler` | `SuggestionHandler.kt` | Wires autocorrect into the IME's word-completion flow + undo |
| `PredictionContextTracker` | `PredictionContextTracker.kt` | Tracks `lastAutocorrectOriginalWord` for undo |

## Pipeline

```
User types word + space
        ↓
SuggestionHandler.onWordCompleted()
        ↓
WordPredictor.autoCorrect(typedWord)
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
│ Step 4: iterate dictionary                    │
│   For each (dictWord, candidateFrequency):    │
│     if |len diff| > max_length_diff → skip    │
│     score = scoreCandidate(typed, dictWord)   │
│     if score >= char_match_threshold:         │
│       update bestCandidate via 4-tier rule    │
└──────────────────────────────────────────────┘
        ↓
┌──────────────────────────────────────────────┐
│ Step 5: confirm + reroute                     │
│   if bestCandidate.frequency >= floor:        │
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
    fun keyDistance(a: Char, b: Char): Float       // [0, 1] normalized euclidean
    fun substitutionScore(a: Char, b: Char): Float  // 1 - keyDistance
    fun weightedEditDistance(a: String, b: String): Float  // weighted Levenshtein
    fun setLayout(positions: Map<Char, Pair<Float, Float>>)  // Tier B injection
    fun resetLayout()                                // revert to default QWERTY
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
// Keyboard2View.kt:~1295
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

Two scoring paths depending on length difference:

### Same-Length (Dual-Gate)

```kotlin
// WordPredictor.kt:~1820
val score: Float = if (lengthDiff == 0) {
    var exactCount = 0
    var weightedSum = 0f
    for (i in 0 until wordLength) {
        val a = lowerTypedWord[i]
        val b = dictWord[i]
        if (a == b) exactCount++
        weightedSum += KeyAdjacency.substitutionScore(a, b)
    }
    val exactRatio = exactCount.toFloat() / wordLength
    val weightedScore = weightedSum / wordLength
    // GATE 1: at least 50% of positions must exactly match.
    if (exactRatio >= MIN_SAME_LENGTH_EXACT_RATIO) weightedScore else -1f
}
```

- **Gate 1 (`exactRatio >= 0.50`)** rejects unrelated same-length words. Without it, "every char-pair has SOME adjacency similarity" would let `questin` match `without` (0 exact, all-adjacent-fuzzy).
- **Gate 2 (`weightedScore >= char_match_threshold`)** rewards adjacency-rich matches. `tge → the` passes both (2/3 exact AND weighted ≈ 0.96).

### Different-Length (Weighted Edit Distance)

```kotlin
val score: Float = ... else {
    val ed = KeyAdjacency.weightedEditDistance(lowerTypedWord, dictWord)
    val maxEd = lengthDiff + LENGTH_DIFF_ED_BUDGET  // = lengthDiff + 0.5
    if (ed <= maxEd) {
        val maxLen = maxOf(wordLength, dictWord.length).toFloat()
        (1f - ed / maxLen).coerceAtLeast(0f)
    } else {
        -1f
    }
}
```

Weighted Levenshtein DP: substitution cost = `keyDistance` (0.0 to 1.0), insertion/deletion cost = 1.0. The absolute budget `lengthDiff + 0.5` was calibrated against the bundled English dictionary:

- `questin → question` (lenDiff=1, ed=1.0) → 1.0 ≤ 1.5 ✓
- `quuestion → question` (lenDiff=1, ed=1.0) → 1.0 ≤ 1.5 ✓
- `wuestion → season` (lenDiff=2, ed≈2.95) → 2.95 > 2.5 ✗ rejected
- `wuestion → wuthering` (lenDiff=1, ed≈2.79) → 2.79 > 1.5 ✗ rejected

## Four-Tier Candidate Selection

Each candidate passing the score threshold competes through a four-tier comparator:

```kotlin
// WordPredictor.kt:~1900
val isAlias = dictWord in contractionAliases
val effectiveScore = if (isAlias) score + ALIAS_SCORE_BONUS else score
val bothAliases = isAlias && (bestCandidate?.word in contractionAliases)
val better = when {
    bestCandidate == null -> true
    // Tier 1: strictly better score by more than the gap → win
    effectiveScore > bestCandidate.effectiveScore + SCORE_TIEBREAK_GAP -> true
    // Tier 1 (negative): strictly worse → lose
    effectiveScore < bestCandidate.effectiveScore - SCORE_TIEBREAK_GAP -> false
    // Tier 2: alias vs alias → raw score (structural closeness) wins
    bothAliases -> effectiveScore > bestCandidate.effectiveScore
    // Tier 3: normal close-score case → frequency wins
    candidateFrequency > bestCandidate.frequency -> true
    candidateFrequency < bestCandidate.frequency -> false
    // Tier 4: everything tied → deterministic by score
    else -> effectiveScore > bestCandidate.effectiveScore
}
```

### Why Four Tiers

| Tier | Calibration Case |
|------|------------------|
| **1: big score gap** | `wuestion → question` (0.99) wins over `within` (0.69) by 0.30 gap |
| **1: big score gap** | `tge → the` (0.96) wins over `weve` (0.81 effective) by 0.15 gap |
| **2: alias vs alias** | `hadnr → hadnt` (one adj sub, score 0.978) wins over `hasnt` (two subs, 0.956) |
| **3: close-score freq** | `tfe → the` — `tfw` scores 0.96 but `the` (freq 255) beats `tfw` (freq 162) on freq tiebreaker |
| **3: close-score freq** | `quuestion → question` (0.89) beats `quotation` (0.94) because gap is only 0.05 → freq wins; question freq 243 > quotation 182 |
| **4: deterministic** | Removes hash-map iteration-order dependence |

### Calibrated Constants

```kotlin
private const val MIN_SAME_LENGTH_EXACT_RATIO = 0.50f
private const val LENGTH_DIFF_ED_BUDGET = 0.5f
private const val ALIAS_SCORE_BONUS = 0.15f
private const val SCORE_TIEBREAK_GAP = 0.10f
```

The `ALIAS_SCORE_BONUS = 0.15` is sized to cross the `SCORE_TIEBREAK_GAP = 0.10` boundary: a tied alias-key (`dont` at 0.966) beats a tied non-alias (`done` at 0.966) by score-primary because `1.116 - 0.966 = 0.15 > 0.10`.

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
// WordPredictor.kt:~1024
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

All `Config` fields below are read in `autoCorrect`. Defaults at v1.4.0:

| Setting | Key | Default | Range | Description |
|---------|-----|---------|-------|-------------|
| **Enabled** | `autocorrect_enabled` | `true` | bool | Master toggle |
| **Min word length** | `autocorrect_min_word_length` | `2` | 1–5 | Skip very short words |
| **Required prefix** | `autocorrect_prefix_length` | `0` | 0–5 | Candidates must share leading N chars (`0` = no prefix; allows first-char typos) |
| **Score threshold** | `autocorrect_char_match_threshold` | `0.65` | 0.5–0.95 | Min match score |
| **Max length diff** | `autocorrect_max_length_diff` | `2` | 0–5 | Allow ±N length candidates |
| **Min freq floor** | `autocorrect_confidence_min_frequency` | `100` | 0+ | Winner must clear this dict freq |
| **Diagnostic logging** | `swipe_debug_detailed_logging` | `false` | bool | Gates rejection-reason logs |

## User Freq Control & Beam Search Interaction

The freq-preservation fix at line 1024 is safe for beam search because beam search consumes frequency through `OptimizedVocabulary.WordInfo`, which:

1. Normalizes raw frequency to `[0, 1]`.
2. Multiplies by `Config.neural_frequency_weight` (user-tunable in Neural Settings, default `0.57`).
3. Combines with NN confidence via `VocabularyUtils.calculateCombinedScore`.

Higher input freq → slightly higher final beam score, no breakage. The user's `neural_frequency_weight` knob still drives the relative importance of dict frequency vs. neural confidence.

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

| Suite | File | Cases |
|-------|------|-------|
| Pure JVM — KeyAdjacency | `src/test/kotlin/tribixbite/cleverkeys/autocorrect/KeyAdjacencyTest.kt` | 31 (position math, accents, layout swap) |
| Instrumented — AutocorrectTest | `src/androidTest/kotlin/tribixbite/cleverkeys/AutocorrectTest.kt` | 36 (adjacency typos, length-diff, contractions, prefix gate, case preservation) |
| Instrumented — TypingSimulationTest | `src/androidTest/kotlin/tribixbite/cleverkeys/TypingSimulationTest.kt` | ~60 cases incl. step-0 alias direct (`im → I'm`) |

## Related Specifications

- [Swipe Typing Specification](swipe-typing-spec.md) — neural beam search consumes the same dict + freq
- [Dictionary System](../../../specs/dictionary-and-language-system.md) — word storage, binary format, language packs
- [User Dictionary](../../specs/typing/user-dictionary-spec.md) — custom-word and disabled-word lists
