---
title: Smart Punctuation - Technical Specification
description: Auto-space tracking, closing-punctuation space swallowing, and opening-punctuation leading-space suppression (SAS-1)
user_guide: ../../typing/smart-punctuation.md
status: implemented
version: v1.5.0
---

# Smart Punctuation Technical Specification

## Overview

Smart punctuation automatically removes the space before closing punctuation so it attaches to the preceding word. As of v1.2.7 this only applies to spaces that were **auto-inserted** (swipe/suggestion commit) rather than manually typed. As of v1.2.8, sentence-ending punctuation (`.` `!` `?`) re-adds a trailing space to enable autocapitalization.

**SAS-1 (v1.5.0)** rebuilt the decision logic around a shared pure-Kotlin object, `SmartAutoSpace`, and added two refinements:

- **Feature A — leading-space suppression after opening punctuation**: a swiped word or tapped suggestion committed right after `( [ { “ ‘ ¿ ¡` (or a straight quote in opening position) gets no leading auto-space: `("` + swipe "word" → `(word`.
- **Feature B — position-stamped swallow verification**: the "pending automatic space" state now carries the absolute cursor position expected right after the commit. At punctuation time the swallow only fires if the flag is set, the char before the cursor is actually `' '`, and the cursor sits exactly at the stamp — verify-at-use, never trust-the-flag. The closer set was extended with `” ’ … '`, and the v1.2.7 chaining clobber (re-added sentence-ending space flag cleared before it could survive) was fixed.

No new setting: behavior is gated by the existing `smart_punctuation`, `auto_space_before_suggestion`, and `auto_space_after_suggestion` prefs.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| SmartAutoSpace | `src/main/kotlin/tribixbite/cleverkeys/SmartAutoSpace.kt` | Pure decision logic: opener/closer sets, `needsLeadingSpace`, `isSwallowEligible` (runs under `runPureTests`) |
| KeyEventHandler | `src/main/kotlin/tribixbite/cleverkeys/KeyEventHandler.kt:357-431` | Swallow execution in `sendText` + invalidation on other input/backspace |
| PredictionContextTracker | `src/main/kotlin/tribixbite/cleverkeys/PredictionContextTracker.kt:118-168` | Flag + position stamp state (`markAutoSpacePending` / `invalidateAutoSpacePending` / `onCursorPositionChanged`) |
| KeyEventReceiverBridge | `src/main/kotlin/tribixbite/cleverkeys/KeyEventReceiverBridge.kt:204-227` | IReceiver bridge to the tracker state |
| SuggestionHandler | `src/main/kotlin/tribixbite/cleverkeys/SuggestionHandler.kt:641-740` | Suggestion-tap commit path: Feature A check + stamp capture |
| InputCoordinator | `src/main/kotlin/tribixbite/cleverkeys/InputCoordinator.kt:766-841` | Swipe commit path: Feature A check + stamp capture; cursor-move invalidation hook at `:145-154` |

## Architecture

```
Commit side (swipe / suggestion tap)                Punctuation side (typed char)
────────────────────────────────────                ──────────────────────────────
SuggestionHandler / InputCoordinator                KeyEventHandler.sendText()
  needsSpaceBefore ← SmartAutoSpace                   eligible ← SmartAutoSpace
    .needsLeadingSpace(prev, beforePrev)  [A]           .isSwallowEligible(flag, stamp,
  commitText(word + trailing space)                       actualPrevChar, actualPos)  [B]
  markAutoSpacePending(preCommitPos + len)            if closer && eligible:
         │                                              deleteSurroundingText(1, 0)
         ▼                                              sentence-ender? re-add " " and
PredictionContextTracker                                re-stamp AFTER commit (chaining)
  lastSpaceWasAutoInserted + stamp        ◄───────── invalidated by: any other input,
  onCursorPositionChanged(newPos)                    backspace (incl. #110 undos),
    (≠ stamp → invalidate)                           cursor move, field switch (clearAll)
```

## Feature A — Leading-Space Suppression

### Opener classification

```kotlin
// SmartAutoSpace.kt:26
private val UNAMBIGUOUS_OPENERS = setOf('(', '[', '{', '“', '‘', '¿', '¡')
```

```kotlin
// SmartAutoSpace.kt:53-61
fun isOpeningPunctuation(prevChar: Char, charBeforePrev: Char?): Boolean {
    if (prevChar in UNAMBIGUOUS_OPENERS) return true
    if (prevChar == '"' || prevChar == '\'') {
        return charBeforePrev == null ||
            charBeforePrev.isWhitespace() ||
            charBeforePrev in UNAMBIGUOUS_OPENERS
    }
    return false
}
```

Straight quotes are disambiguated by the char BEFORE the quote: start-of-field / whitespace / another opener → opening quote (suppress the space); letter, digit, or closer → possessive or closing quote (keep the space: `kids'` + swipe → `kids' toys`).

```kotlin
// SmartAutoSpace.kt:69-70
fun needsLeadingSpace(prevChar: Char, charBeforePrev: Char?): Boolean =
    !prevChar.isWhitespace() && !isOpeningPunctuation(prevChar, charBeforePrev)
```

### Call sites

Both commit paths read TWO chars before the cursor (identical logic; suggestion-tap path shown):

```kotlin
// SuggestionHandler.kt:644-670 (same shape at InputCoordinator.kt:769-789)
val needsSpaceBefore = if (!isSwipeAutoInsert && !config.auto_space_before_suggestion) {
    false  // User disabled leading space before tapped suggestions
} else if (syncSuppressedField) {
    // #151: never inject a leading space into URL/email/etc. fields —
    // after replacing "exa" in "https://exa" the previous char is '/',
    // and " example" would corrupt the URL.
    false
} else {
    try {
        // SAS-1: read TWO chars — straight quotes " and ' are opener vs
        // possessive/closing depending on the char before them
        val textBefore = inputConnection.getTextBeforeCursor(2, 0)
        if (textBefore != null && textBefore.isNotEmpty()) {
            val prevChar = textBefore.last()
            val charBeforePrev =
                if (textBefore.length >= 2) textBefore[textBefore.length - 2] else null
            // SAS-1: no leading auto-space after opening punctuation
            // ( [ { " ' “ ‘ ¿ ¡ — `("` + swipe "word" → `(word`, not `( word`
            SmartAutoSpace.needsLeadingSpace(prevChar, charBeforePrev)
        } else {
            false
        }
    } catch (e: Exception) {
        // If getTextBeforeCursor fails, assume we don't need space before
        false
    }
}
```

The `#151` `syncSuppressedField` URL-bar branch (`SuggestionHandler.kt:646-650`) is untouched by SAS-1 and short-circuits before `SmartAutoSpace` is consulted.

## Feature B — Position-Stamped Space Swallowing

### State (PredictionContextTracker)

```kotlin
// PredictionContextTracker.kt:120
var lastSpaceWasAutoInserted: Boolean = false
```

```kotlin
// PredictionContextTracker.kt:129-130
var autoSpaceStampedPosition: Int = -1
    private set
```

```kotlin
// PredictionContextTracker.kt:138-141
fun markAutoSpacePending(expectedCursorPosition: Int) {
    lastSpaceWasAutoInserted = true
    autoSpaceStampedPosition = expectedCursorPosition
}
```

```kotlin
// PredictionContextTracker.kt:148-151
fun invalidateAutoSpacePending() {
    lastSpaceWasAutoInserted = false
    autoSpaceStampedPosition = -1
}
```

The absolute cursor position is read via `ExtractedText` and degrades to `-1` when the editor doesn't support it:

```kotlin
// PredictionContextTracker.kt:64-76
fun currentCursorPosition(ic: InputConnection?): Int {
    if (ic == null) return -1
    return try {
        val extracted = ic.getExtractedText(
            android.view.inputmethod.ExtractedTextRequest(), 0
        ) ?: return -1
        if (extracted.selectionStart < 0) -1
        else extracted.startOffset + extracted.selectionStart
    } catch (e: Exception) {
        -1
    }
}
```

### Stamp capture at commit

Both commit paths capture the pre-commit position and stamp `preCommit + textToInsert.length` when (and only when) a trailing space was actually added; a re-commit without a fresh trailing space invalidates any stale stamp:

```kotlin
// SuggestionHandler.kt:723-740 (same shape at InputCoordinator.kt:821-841)
val preCommitCursorPos = if (addedTrailingSpace) {
    PredictionContextTracker.currentCursorPosition(inputConnection)
} else {
    -1
}

Log.d(TAG, "Committing text: '$textToInsert' (length=${textToInsert.length})")
inputConnection.commitText(textToInsert, 1)

if (addedTrailingSpace) {
    contextTracker.markAutoSpacePending(
        if (preCommitCursorPos >= 0) preCommitCursorPos + textToInsert.length else -1
    )
} else {
    // SAS-1: a re-commit without a fresh trailing space makes any
    // previously pending auto-space stale — invalidate it
    contextTracker.invalidateAutoSpacePending()
}
```

### Verify-at-use eligibility

```kotlin
// SmartAutoSpace.kt:89-99
fun isSwallowEligible(
    autoSpacePending: Boolean,
    stampedPosition: Int,
    actualPrevChar: Char?,
    actualPosition: Int
): Boolean {
    if (!autoSpacePending) return false
    if (actualPrevChar != ' ') return false
    // Position stamp check — only enforced when both sides are known
    return stampedPosition < 0 || actualPosition < 0 || actualPosition == stampedPosition
}
```

Only a plain `' '` qualifies (tabs/newlines are never eaten). When either position is unknown (`-1`, editors without ExtractedText), eligibility degrades to the legacy v1.2.7 flag-plus-space check rather than breaking those editors.

### Swallow execution (KeyEventHandler.sendText)

```kotlin
// KeyEventHandler.kt:367-401
val smartPuncEnabled = Config.globalConfig().smart_punctuation
val isPunctChar = isSmartPunctuationChar(char)
val isQuote = isQuoteChar(char)

if (smartPuncEnabled && (isPunctChar || isQuote)) {
    val textBefore = conn.getTextBeforeCursor(500, 0)  // Get enough context for quote counting
    val eligible = SmartAutoSpace.isSwallowEligible(
        autoSpacePending = recv.wasLastSpaceAutoInserted(),
        stampedPosition = recv.getAutoSpaceStampedPosition(),
        actualPrevChar = textBefore?.lastOrNull(),
        actualPosition = PredictionContextTracker.currentCursorPosition(conn)
    )

    if (isPunctChar && eligible) {
        // Closing punctuation: swallow the automatic space to attach to the word
        conn.deleteSurroundingText(1, 0)
        // v1.2.8: For sentence-ending punctuation, add space after so autocap triggers
        // "hello " + "." → "hello. " (enables getCursorCapsMode to detect sentence end)
        if (isSentenceEndingPunctuation(char)) {
            textToCommit = "$char "
            // SAS-1: re-mark the re-added space as auto-inserted (with a
            // fresh position stamp) AFTER the commit below — chaining.
            reMarkAutoSpaceAfterCommit = true
        }
    } else if (isQuote && eligible) {
        // Straight double quote: parity disambiguation — an odd count of
        // prior " means an unmatched opener, so this one is CLOSING.
        // (Apostrophes are handled by isSmartPunctuationChar above.)
        if (isClosingQuote(char, textBefore)) {
            // Closing quote: delete space to attach to word
            conn.deleteSurroundingText(1, 0)
        }
        // Opening quote: keep the space (do nothing)
    }
}
```

### Chaining (fix for the v1.2.7 clobber)

In v1.2.7-v1.4.x, the sentence-ending re-added space set the flag *before* the unconditional clear at the end of the punctuation branch, so it never survived and `word.` → `)` produced `word. )`. SAS-1 re-marks AFTER the commit with a fresh stamp:

```kotlin
// KeyEventHandler.kt:416-428
// SAS-1: capture the pre-commit cursor position for the chaining stamp
// (after the swallow deletion above, right before the commit)
val preCommitPos =
    if (reMarkAutoSpaceAfterCommit) PredictionContextTracker.currentCursorPosition(conn)
    else -1
conn.commitText(textToCommit, 1)
if (reMarkAutoSpaceAfterCommit) {
    // SAS-1: fixes the v1.2.7 clobber where the chain flag was set BEFORE the
    // unconditional clear above and thus never survived — mark AFTER commit.
    recv.markAutoSpacePending(
        if (preCommitPos >= 0) preCommitPos + textToCommit.length else -1
    )
}
```

Result: `word` → `.` → `word. ` → `)` → `word.)` — one automatic-space deletion per closer.

### Invalidation matrix

| Trigger | Site |
|---------|------|
| Any other single-char input (cleared AFTER the swallow check) | `KeyEventHandler.kt:406` |
| Multi-char input (macros, emoji strings) | `KeyEventHandler.kt:411` |
| Backspace (plain) | `KeyEventHandler.kt:126` |
| Backspace undo of swiped word (#110) | `KeyEventHandler.kt:538` |
| Backspace undo of autocorrect (#110) | `KeyEventHandler.kt:580` |
| Cursor movement away from the stamp | `InputCoordinator.kt:145-154` → `PredictionContextTracker.onCursorPositionChanged` (`:161-168`) |
| Re-commit without a fresh trailing space | `SuggestionHandler.kt:739` / `InputCoordinator.kt:840` |
| Field switch | `PredictionContextTracker.clearAll()` (`:368`) |

The cursor-movement hook keeps the state alive when the commit's OWN selection callback reports exactly the stamped position:

```kotlin
// PredictionContextTracker.kt:161-168
fun onCursorPositionChanged(newPosition: Int) {
    if (lastSpaceWasAutoInserted &&
        autoSpaceStampedPosition >= 0 &&
        newPosition != autoSpaceStampedPosition
    ) {
        invalidateAutoSpacePending()
    }
}
```

## Key Code Patterns

### Closer set

```kotlin
// SmartAutoSpace.kt:39-40
private val CLOSING_PUNCTUATION =
    setOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '”', '’', '…', '\'')
```

```kotlin
// KeyEventHandler.kt:441
private fun isSmartPunctuationChar(c: Char): Boolean = SmartAutoSpace.isClosingPunctuation(c)
```

Straight double quote `"` is deliberately absent from the closer set — it is parity-resolved. Straight apostrophe `'` IS a closer: mid-word contractions (don't) never reach the swallow path because the char before the cursor there is a letter, not the pending auto-space, so an apostrophe typed right after an auto-space reads as possessive/closing (`kids ` + `'` → `kids'`).

### Quote detection

```kotlin
// KeyEventHandler.kt:462-472
private fun isClosingQuote(quote: Char, textBefore: CharSequence?): Boolean {
    if (quote != '"') return false
    if (textBefore.isNullOrEmpty()) return false

    // Count occurrences of this quote type in the text before cursor
    val quoteCount = textBefore.count { it == quote }

    // Odd count means there's an unmatched opening quote → this is closing
    // Even count (including 0) means no unmatched quote → this is opening
    return quoteCount % 2 == 1
}
```

```kotlin
// KeyEventHandler.kt:477
private fun isQuoteChar(c: Char): Boolean = c == '"'
```

(The pre-v1.5.0 `isLikelyApostrophe` helper is gone — `'` moved into the closer set, and curly quotes are unambiguous.)

### Sentence-ending punctuation

```kotlin
// KeyEventHandler.kt:444-449
private fun isSentenceEndingPunctuation(c: Char): Boolean {
    return when (c) {
        '.', '!', '?' -> true
        else -> false
    }
}
```

### Double-space to period

```kotlin
// KeyEventHandler.kt:343-356
if (config.double_space_to_period && !isKeyRepeat &&
    text.length == 1 && text[0] == ' ' && lastTypedChar == ' ' &&
    (currentTime - lastTypedTimestamp) < doubleSpaceThresholdMs) {
    // Only trigger if the character before the first space was alphanumeric
    // This prevents ". ." or ", ." sequences
    val textBefore = conn.getTextBeforeCursor(2, 0)
    val charBeforeSpace = textBefore?.getOrNull(0)
    if (charBeforeSpace != null && charBeforeSpace.isLetterOrDigit()) {
        // Delete the previous space and insert ". "
        conn.deleteSurroundingText(1, 0)
        textToCommit = ". "
        // Reset tracking to prevent triple-space weirdness
        lastTypedChar = '.'
    }
}
```

## IReceiver Interface

```kotlin
// KeyEventHandler.kt:978-983 (interface defaults)
// v1.2.7: Smart punctuation - track if last space was auto-inserted
fun wasLastSpaceAutoInserted(): Boolean = false
fun setLastSpaceAutoInserted(value: Boolean) {}
// SAS-1: position-stamped auto-space pending state (see
// PredictionContextTracker.markAutoSpacePending / SmartAutoSpace)
fun getAutoSpaceStampedPosition(): Int = -1
fun markAutoSpacePending(expectedCursorPosition: Int) {}
```

### Bridge Implementation

```kotlin
// KeyEventReceiverBridge.kt:205-227
override fun wasLastSpaceAutoInserted(): Boolean {
    return contextTracker?.lastSpaceWasAutoInserted ?: false
}

override fun setLastSpaceAutoInserted(value: Boolean) {
    if (value) {
        contextTracker?.lastSpaceWasAutoInserted = true
    } else {
        // SAS-1: clearing the flag also drops the stale position stamp
        contextTracker?.invalidateAutoSpacePending()
    }
}

// SAS-1: position-stamped auto-space pending state
override fun getAutoSpaceStampedPosition(): Int {
    return contextTracker?.autoSpaceStampedPosition ?: -1
}

override fun markAutoSpacePending(expectedCursorPosition: Int) {
    contextTracker?.markAutoSpacePending(expectedCursorPosition)
}
```

## Configuration

| Setting | Config Key | Default | Source |
|---------|------------|---------|--------|
| Smart Punctuation | `smart_punctuation` | `true` | `Config.kt:581` (default `Config.kt:95`) |
| Auto-space before suggestion | `auto_space_before_suggestion` | `true` | `Config.kt:611` (default `Config.kt:309`) |
| Auto-space after suggestion | `auto_space_after_suggestion` | `true` | `Config.kt:610` (default `Config.kt:308`) |
| Double Space Period | `double_space_to_period` | `true` | `Config.kt:579` (default `Config.kt:104`) |
| Double Space Threshold | `double_space_threshold` | `500` ms | `Config.kt:580` (default `Config.kt:105`) |

SAS-1 introduces no new preference keys.

## Test Coverage

| Suite | File | Cases |
|-------|------|-------|
| Pure JVM (`runPureTests`) | `src/test/kotlin/tribixbite/cleverkeys/SmartAutoSpaceLogicTest.kt` | 22 |
| Instrumented (ew-cli, Pixel7 API 34) | `src/androidTest/kotlin/tribixbite/cleverkeys/SmartAutoSpaceTest.kt` | 28 |

The pure suite covers the opener/closer classification tables and every `isSwallowEligible` branch (no-flag, non-space prev char, empty field, position match/mismatch, unknown-stamp and unknown-position degradation, tab/newline rejection). The instrumented suite drives the real `KeyEventHandler` key path with a `TestInputConnection` implementing `ExtractedText`: opener suppression for swipe and tap commits, all closer swallows (incl. `”` `’` `…` `'`), possessive-apostrophe keep, manual-space preservation, cursor-move/backspace/field-switch invalidation, punctuation chaining, plain-typing regression, and a #151 URL-bar control. Full-suite gate: 1371/1371 instrumented tests green (2026-07-15, run `1c06495a`).

## Related Specifications

- [Autocorrect Spec](autocorrect-spec.md) - Spelling correction
- [Swipe Typing Spec](swipe-typing-spec.md) - Gesture input
- [User Dictionary Spec](user-dictionary-spec.md) - Case preservation

## Version History

| Version | Change |
|---------|--------|
| v1.5.0 | SAS-1: shared `SmartAutoSpace` pure logic; leading-space suppression after opening punctuation; position-stamped verify-at-use swallow eligibility; closer set extended with `”` `’` `…` `'`; chaining clobber fixed |
| v1.2.8 | Auto-space after sentence-ending punctuation for autocap integration |
| v1.2.7 | Added auto-inserted space tracking for manual space preservation |
| v1.2.0 | Added quote handling (opening vs closing detection) |
| v1.1.0 | Initial smart punctuation implementation |
