# Cursor-Aware Predictions

## Overview

System for synchronizing neural prediction context with cursor position. When user moves cursor mid-word, the prediction system rebuilds state from InputConnection to enable accurate predictions and proper deletion of both prefix and suffix when selecting a suggestion.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/PredictionContextTracker.kt` | `synchronizeWithCursor()` | Reads text around cursor, rebuilds prefix/suffix |
| `src/main/kotlin/tribixbite/cleverkeys/InputCoordinator.kt` | `onSuggestionSelected()` | Deletes both prefix AND suffix |
| `src/main/kotlin/tribixbite/cleverkeys/CleverKeysService.kt` | `onUpdateSelection()` | Triggers cursor sync on position change |

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     onUpdateSelection()                      │
│  Fires when cursor position changes (tap, arrow, paste)     │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼ (100ms debounce)
┌─────────────────────────────────────────────────────────────┐
│              PredictionContextTracker.synchronizeWithCursor  │
│  1. getTextBeforeCursor(50)  →  extract prefix              │
│  2. getTextAfterCursor(50)   →  extract suffix              │
│  3. Store both normalized (for lookup) and raw (for delete) │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│                     Neural Predictions                       │
│  Uses normalized prefix for neural model lookup             │
└─────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────┐
│               onSuggestionSelected(word)                     │
│  deleteSurroundingText(prefixLength, suffixLength)          │
│  commitText(selectedWord)                                   │
└─────────────────────────────────────────────────────────────┘
```

## Configuration

| Setting | Value | Description |
|---------|-------|-------------|
| `SYNC_DEBOUNCE_MS` | 100 | Delay before syncing after cursor move |
| Max text fetch | 50 chars | Characters fetched in each direction |

## Implementation Details

### Word Boundary Detection

```kotlin
private val WORD_BOUNDARIES = setOf(
    ' ', '\t', '\n', '\r',       // Whitespace
    '.', ',', ';', ':', '!', '?' // Sentence punctuation
)

fun isWordChar(char: Char, language: String, text: String, pos: Int): Boolean {
    if (char.isLetter()) return true
    if (char == '\'') {
        val before = text.getOrNull(pos - 1)
        val after = text.getOrNull(pos + 1)
        return before?.isLetter() == true && after?.isLetter() == true
    }
    return false
}
```

### Prefix/Suffix Extraction

```kotlin
fun synchronizeWithCursor(ic: InputConnection?, language: String) {
    ic ?: return
    if (!shouldSyncForInputType(editorInfo)) return
    if (isCJKLanguage(language)) return  // Skip for CJK scripts

    val beforeText = ic.getTextBeforeCursor(50, 0)?.toString() ?: ""
    val afterText = ic.getTextAfterCursor(50, 0)?.toString() ?: ""

    val (prefix, rawPrefix) = extractWordPrefix(beforeText, language)
    val (suffix, rawSuffix) = extractWordSuffix(afterText, language)

    currentWord.clear().append(prefix)
    currentWordSuffix.clear().append(suffix)
    rawPrefixForDeletion = rawPrefix
    rawSuffixForDeletion = rawSuffix
}
```

### Dual Deletion on Selection

```kotlin
fun onSuggestionSelected(word: String, ic: InputConnection) {
    val (prefixDelete, suffixDelete) = contextTracker.getCharsToDeleteForPrediction()

    if (prefixDelete > 0 || suffixDelete > 0) {
        contextTracker.expectingSelectionUpdate = true
        ic.deleteSurroundingText(prefixDelete, suffixDelete)
    }

    ic.commitText(word, 1)
}
```

### Accent Handling

Normalized matching for prediction lookup, raw character count for deletion:

```kotlin
val (normalizedPrefix, rawPrefix) = extractWordPrefix(beforeText, language)

// Use normalized for prediction
predictions = wordPredictor.predict(normalizedPrefix)

// Use raw for deletion (preserves actual char count)
ic.deleteSurroundingText(rawPrefix.length, rawSuffix.length)
```

## Cursor Sync Prediction Pipeline

When cursor sync fires, `InputCoordinator.triggerPredictionsForPrefix()` runs a full
prediction pipeline that mirrors `SuggestionHandler.updatePredictionsForCurrentWord()`:

1. **Predict**: `wordPredictor.predictWordsWithContext(prefix, contextWords)`
2. **Non-paired contractions**: `contractionManager.getNonPairedMapping(prefix)` (e.g., dont → don't)
3. **Paired contractions**: `contractionManager.getPairedContractions(prefix)` if prefix >= 3 chars
4. **Transform predictions**: Map all results through non-paired contraction mapping + I-word capitalization
5. **Merge & deduplicate**: Contraction words first, then filtered predictions
6. **Capitalize**: Apply sentence-start capitalization if raw prefix started uppercase
7. **exact_add**: Append `exact_add:$word` for non-dictionary words (if `config.show_exact_typed_word`)
8. **Post to SuggestionBar**: Via `Handler(Looper.getMainLooper()).post{}`

### Safety Mechanisms

- `ic ?: return` at the top of `synchronizeWithCursor()` — null InputConnection = early return
- `contextTracker.clearAll()` in `onFinishInputView()` — prevents cross-app text leaking
- `Handler.post()` instead of `View.post()` — detached views silently drop runnables
- SuggestionBar deduplication — skips re-render if new suggestions match existing ones

## Edge Cases

| Case | prefix | suffix | Behavior |
|------|--------|--------|----------|
| Cursor at end: `hello\|` | "hello" | "" | Normal predictions |
| Cursor mid-word: `hel\|lo` | "hel" | "lo" | Delete both on select |
| Cursor at start: `\|hello` | "" | "hello" | Clear predictions |
| After space: `hello \|` | "" | "" | Next-word predictions |
| After emoji: `hi 👋 \|` | "" | "" | Reset prediction |
| Numbers: `test\|123` | "test" | "" | Numbers break word |
| Contraction: `don'\|t` | "don'" | "t" | Treated as single word |
| Short prefix: `t\|` | "t" | "" | Predictions but no paired contractions (< 3 chars) |
| Non-dict word: `xyzq\|` | "xyzq" | "" | Predictions + exact_add entry |
| App switch: new field | "" | "" | contextTracker.clearAll() resets state |

## Language Handling

| Language Type | Behavior |
|--------------|----------|
| Space-delimited (Latin) | Standard word boundary detection |
| CJK (Chinese, Japanese, Thai) | Skip cursor sync entirely |
| RTL (Arabic, Hebrew) | Normal - InputConnection is logical order |
| German compounds | Treated as single word |
| French elision (l'homme) | Single unit treatment |

## Performance

- **Debouncing**: 100ms delay prevents rapid fire during drag selection
- **IPC Optimization**: Single call per direction, max 50 chars
- **State Caching**: Skip sync if position unchanged
