---
title: Input Behavior Settings - Technical Specification
user_guide: ../../settings/input-behavior.md
status: implemented
version: v1.2.7
---

# Input Behavior Settings Technical Specification

## Overview

The Input Behavior section owns text-processing behavior (capitalization, smart punctuation,
suggestion insertion), the touch thresholds that separate a tap from a swipe from a
long-press, and the number-row/numpad layout selectors. Its controls live in
`ui/settings/sections/InputBehaviorSection.kt`; the runtime consumers are
`Autocapitalisation`, `KeyEventHandler`, `SuggestionHandler` and `Pointers`.

Two neighbouring sections carry closely related keys and are cross-referenced where relevant:
**Gesture Tuning** (`GestureTuningSection.kt` — short-gesture bounds, double-space-to-period,
swipe-detection floors, selection-delete) and **Auto-Correction** (`AutoCorrectionSection.kt`
— including `backspace_undo_autocorrect`).

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| `Autocapitalisation` | `Autocapitalisation.kt` | Shift-state automation driven by the editor's caps mode |
| `KeyEventHandler` | `KeyEventHandler.kt` | Double-space-to-period, smart punctuation, backspace undo |
| `SuggestionHandler` | `SuggestionHandler.kt` | Commit-time capitalization, auto-spacing, exact-typed suggestion |
| `Pointers` | `Pointers.kt` | Touch handling: swipe/short-gesture/long-press classification |
| `Config` | `Config.kt` | Reads the preferences; derives px thresholds from them |
| `InputBehaviorSection` | `ui/settings/sections/InputBehaviorSection.kt` | The Settings UI |

## Auto-Capitalization

`autocapitalisation` is a **boolean**, not a mode enum. There is no per-app override of the
editor's own hint: CleverKeys *defers* to it. `Autocapitalisation.started` reads
`EditorInfo.inputType`, and when the editor requests no caps mode at all the feature turns
itself off for that field regardless of the preference:

```kotlin
// Autocapitalisation.kt:34-53
fun started(info: EditorInfo, ic: InputConnection) {
    this.ic = ic
    capsMode = info.inputType and SUPPORTED_CAPS_MODES
    val autocapEnabled = Config.globalConfig().autocapitalisation

    if (!autocapEnabled || capsMode == 0) {
        enabled = false
        return
    }

    enabled = true
    shouldEnableShift = info.initialCapsMode != 0
    shouldUpdateCapsMode = started_should_update_state(info.inputType)
    callback_now(true)
}
```

From then on the class tracks typed characters, sent key events (`KEYCODE_DEL`,
`KEYCODE_ENTER`) and selection changes, and calls back into the keyboard view to raise or
lower shift. `pause`/`unpause` exist so gesture input can suspend it mid-word.

Word commits take a second, independent path: `SuggestionHandler` re-checks
`Autocapitalisation.shouldCapitalizeAtCursor(ic, editorInfo, config.autocapitalisation)` at
commit time (`SuggestionHandler.kt:792`), because a tapped suggestion bypasses the
per-character flow.

`autocapitalize_i_words` (#72) is separate and unconditional on the editor's caps mode: it
uppercases a committed `i`, `i'm`, `i'll`, `i'd`, `i've` (`SuggestionHandler.kt:321`).

## Double-Space Period

Gated by `double_space_to_period` with a `double_space_threshold` timing window — **both of
which live in the Gesture Tuning section**, not Input Behavior. The implementation is inline
in `KeyEventHandler`, and it verifies the text actually in the field rather than trusting its
own memory of the last keystroke:

```kotlin
// KeyEventHandler.kt:373-392
if (config.double_space_to_period && !isKeyRepeat &&
    text.length == 1 && text[0] == ' ' && lastTypedChar == ' ' &&
    (currentTime - lastTypedTimestamp) < doubleSpaceThresholdMs) {
    // Audit A-5: verify at use that a space ACTUALLY precedes the cursor.
    val textBefore = conn.getTextBeforeCursor(2, 0)
    val spacePrecedesCursor = textBefore?.length == 2 && textBefore[1] == ' '
    val charBeforeSpace = textBefore?.getOrNull(0)
    if (spacePrecedesCursor && charBeforeSpace != null && charBeforeSpace.isLetterOrDigit()) {
        conn.deleteSurroundingText(1, 0)
        textToCommit = ". "
        lastTypedChar = '.'
    }
}
```

The `charBeforeSpace.isLetterOrDigit()` guard is what prevents `". ."` and `", ."` runs; the
`spacePrecedesCursor` re-read is what stopped space→backspace→space from eating a letter
(audit A-5).

## Smart Punctuation

`smart_punctuation` governs **auto-space swallowing**, not quote curling. When the previous
space was inserted automatically (after a swipe or a tapped suggestion) and the user then
types closing punctuation, that space is deleted so the punctuation attaches to the word:

```kotlin
// KeyEventHandler.kt:403-419
val smartPuncEnabled = Config.globalConfig().smart_punctuation
val isPunctChar = isSmartPunctuationChar(char)
val isQuote = isQuoteChar(char)

if (smartPuncEnabled && (isPunctChar || isQuote)) {
    val textBefore = conn.getTextBeforeCursor(500, 0)
    val eligible = SmartAutoSpace.isSwallowEligible(
        autoSpacePending = recv.wasLastSpaceAutoInserted(),
        stampedPosition = recv.getAutoSpaceStampedPosition(),
        actualPrevChar = textBefore?.lastOrNull(),
        actualPosition = PredictionContextTracker.currentCursorPosition(conn)
    )

    if (isPunctChar && eligible) {
        conn.deleteSurroundingText(1, 0)
        // sentence-ending punctuation re-adds a space so autocap can trigger
```

Eligibility is decided by the pure `SmartAutoSpace.isSwallowEligible`, which compares the
cursor position stamped when the auto-space was committed against the live cursor — so a
manually typed space or a cursor move can never be swallowed. There is no `smart_quotes`
preference; quote handling rides the same `smart_punctuation` flag.

## Gesture Thresholds

### Swipe detection

`swipe_dist` is a **stringly-typed** preference (legacy XML-preference compatibility) holding
a number, scaled at read time into a device-independent pixel threshold:

```kotlin
// Config.kt:771-774
val dpi_ratio = maxOf(dm.xdpi, dm.ydpi) / minOf(dm.xdpi, dm.ydpi)
val swipe_scaling = minOf(dm.widthPixels, dm.heightPixels) / 10f * dpi_ratio
val swipe_dist_value = safeGetString(_prefs, "swipe_dist", Defaults.SWIPE_DIST).toFloatOrNull()
    ?: Defaults.SWIPE_DIST_FALLBACK
swipe_dist_px = swipe_dist_value / 25f * swipe_scaling
```

`Pointers` compares an L1 (Manhattan) displacement against it — not Euclidean distance — and
there is no velocity requirement:

```kotlin
// Pointers.kt:971-975
val dx = x - ptr.downX
val dy = adjustedY - ptr.downY
val dist = abs(dx) + abs(dy)

if (dist >= snap.swipe_dist_px && ptr.gesture == null) {
```

`slider_sensitivity` (space-slider) and `circle_sensitivity` (circle gesture) are string
preferences read the same way. The slider percent is floored at 1, never 0: `slide_step_px`
is a divisor in `Pointers.Sliding`, and 0 produced ±Infinity and a cursor that moved the wrong
way (audit F-3, `SettingsRanges.SLIDER_SENSITIVITY_PERCENT`).

### Long press and key repeat

```kotlin
// Config.kt:795-799
longPressTimeout = safeGetInt(_prefs, "longpress_timeout", Defaults.LONGPRESS_TIMEOUT).toLong()
longPressInterval = safeGetInt(_prefs, "longpress_interval", Defaults.LONGPRESS_INTERVAL)
    .coerceIn(SettingsRanges.LONGPRESS_INTERVAL.first, SettingsRanges.LONGPRESS_INTERVAL.last).toLong()
keyrepeat_enabled = _prefs.getBoolean("keyrepeat_enabled", Defaults.KEYREPEAT_ENABLED)
keyrepeat_backspace_only = _prefs.getBoolean("keyrepeat_backspace_only", Defaults.KEYREPEAT_BACKSPACE_ONLY)
```

`longpress_timeout` is the delay before long-press fires; `longpress_interval` is the repeat
period after it does. With `keyrepeat_backspace_only` (default **true**) only backspace and
the navigation keys repeat — letters do not, matching Gboard/SwiftKey (#81).

### Short gestures

The short-swipe/word-swipe boundary is not a three-value enum. It is a pair of percentages
**of the starting key's diagonal**, carried by the `PercentOfKey` value class
(`Units.kt:17-22`, `toPx(keyDiagonalPx) = keyDiagonalPx * v / 100f`):

```kotlin
// Pointers.kt:951-962
if (ptr.key != null && !ptr.hasLeftStartingKey) {
    val keyHypotenuse = _handler.getKeyHypotenuse(ptr.key)
    val maxAllowedDistance = snap.shortGestureMaxDistancePx(keyHypotenuse)
    val distanceFromStart = sqrt((x - keyCenterX) * (x - keyCenterX) + (y - keyCenterY) * (y - keyCenterY))
    if (distanceFromStart > maxAllowedDistance) {
        ptr.hasLeftStartingKey = true
    }
}
```

`short_gestures_enabled`, `short_gesture_min_distance` (default 28%, slider 10-60) and
`short_gesture_max_distance` (default 141%, slider 50-200) are configured in the **Gesture
Tuning** section. See [Gesture System](../../../specs/gesture-system.md).

## Delete Behavior

There is no delete-mode or delete-word-mode preference. Backspace is a single path with two
opt-out undo behaviors layered on top:

- **`backspace_undo_swipe`** (Input Behavior, default true) — a backspace immediately after a
  swipe-committed word deletes the whole word plus its trailing auto-space rather than one
  character. `KeyEventHandler.handleBackspaceUndoSwipe` (`KeyEventHandler.kt:569`) returns
  false to fall through to normal backspace when the feature is off, when an autocorrect is
  pending (that handler owns the press instead), or when there is no recorded swiped word.
  The control is only shown when swipe typing is enabled.
- **`backspace_undo_autocorrect`** (default true) — reverts an autocorrection to the word the
  user actually typed. Its switch lives in the **Auto-Correction** section
  (`AutoCorrectionSection.kt:42-46`); the handler is `KeyEventHandler.kt:613`.

Swipe-and-hold on backspace enters a selection-delete mode whose two tunables
(`selection_delete_vertical_threshold`, `selection_delete_vertical_speed`) are in Gesture
Tuning and consumed at `Pointers.kt:1227-1295`.

## Configuration

Every row is a preference key the app actually reads, with the control's own section noted
where it is not Input Behavior. "Range" gives the Settings slider bound; where the import
validator (`backup/SettingsValidation.kt`) accepts a wider band, both are shown.

### Typing and text processing

| Setting | Key | Default | Range |
|---------|-----|---------|-------|
| **Auto-Capitalization** | `autocapitalisation` | true | bool — inert when the editor requests no caps mode |
| **Capitalize "I" Words** | `autocapitalize_i_words` | true | bool (#72) |
| **Smart Punctuation** | `smart_punctuation` | true | bool — auto-space swallowing before punctuation |
| **Word Prediction** | `word_prediction_enabled` | true | bool — gates the whole prediction block below |
| **Suggestion Bar Opacity** | `suggestion_bar_opacity` | 80 | 0-100% |
| **Auto-Space After Suggestion** | `auto_space_after_suggestion` | true | bool (#82) |
| **Auto-Space Before Suggestion** | `auto_space_before_suggestion` | true | bool |
| **Show Exact Typed Word** | `show_exact_typed_word` | true | bool — appends the exact typed string (2+ chars, not already a prediction/dictionary/user word) as a tap-to-add `ExactAdd` suggestion (`SuggestionHandler.kt:2345`; switch added 2026-09-08, F-8/#42) |
| **Backspace Undoes Swipe** | `backspace_undo_swipe` | true | bool — shown only when swipe typing is on (#110) |

### Touch thresholds

| Setting | Key | Default | Range |
|---------|-----|---------|-------|
| **Swipe Distance Threshold** | `swipe_dist` | `"23"` | slider 5-30 (stored as a string; scaled to px at `Config.kt:774`) |
| **Circle Gesture Sensitivity** | `circle_sensitivity` | `"2"` | 1-5 (string) |
| **Space Slider Sensitivity** | `slider_sensitivity` | `"30"` | 1-100% (`SettingsRanges.SLIDER_SENSITIVITY_PERCENT`; string). Floor is 1, not 0 — F-3 |
| **Long-Press Timeout** | `longpress_timeout` | 600 | slider 200-1000 ms; validator 50-2000 |
| **Key-Repeat Interval** | `longpress_interval` | 25 | 25-200 ms (`SettingsRanges.LONGPRESS_INTERVAL`) |
| **Key Repeat** | `keyrepeat_enabled` | true | bool |
| **Backspace-Only Repeat** | `keyrepeat_backspace_only` | true | bool (#81) — shown only when key repeat is on |
| **Double-Tap Shift Lock** | `lock_double_tap` | true | bool |
| **Immediate Input Switching** | `switch_input_immediate` | false | bool |

### Layout selectors

| Setting | Key | Default | Values |
|---------|-----|---------|--------|
| **Number Row** | `number_row` | `no_number_row` | `no_number_row` / `no_symbols` / `symbols` |
| **Show Numpad** | `show_numpad` | `never` | `never` / `landscape` / `always` |
| **Numpad Layout** | `numpad_layout` | `default` | `default` (7-8-9 on top) / `low_first` (1-2-3 on top) |
| **Pin Entry Layout** | `number_entry_layout` | `pin` | `pin` / `number` — the switch writes this key directly; the old `pin_entry_enabled` write drove nothing (F-6, 2026-09-06) |

### Word Prediction subsection (added 2026-08-06)

The Advanced Prediction block of the Word Prediction group hosts the context-learning
controls and the Learning & Data manager (`LearningDataSection.kt`):

| Setting | Key | Default | Values |
|---------|-----|---------|--------|
| **Context-Aware Predictions** | `context_aware_predictions_enabled` | true | bool — prerequisite for next-word |
| **Next-Word Prediction** | `next_word_prediction_enabled` | false | bool — disabled (not hidden) while context-aware is off |
| **Context Source** | `context_source` | `both` | `both` / `learned_only` / `static_only` |
| **Personalized Learning** | `personalized_learning_enabled` | true | bool |
| **Personalization Strength** | `personalization_weight` | 1.0 | 0.0-2.0 |
| **Learning Aggression** | `learning_aggression` | `BALANCED` | `CONSERVATIVE` / `BALANCED` / `AGGRESSIVE` |
| **Context Boost** | `prediction_context_boost` | 0.5 | 0.5-5.0 |
| **Frequency Scale** | `prediction_frequency_scale` | 100.0 | 100-5000 |
| **Max Learned Words** | `personalization_max_words` | 5000 | 1000-20000 (500 steps; least-value eviction) |

The master learning gate `on_device_learning_enabled` (default true) lives in the
Privacy & Data section and overrides all of the above at the write AND read layers.

## Related Specifications

- [Gesture System](../../../specs/gesture-system.md) - Gesture recognition
- [Settings System](../../../specs/settings-system.md) - Preferences
- [Autocorrect](../typing/autocorrect-spec.md) - Text correction
- [Next-Word Prediction](../typing/next-word-prediction-spec.md) - Learned-phrase suggestions
