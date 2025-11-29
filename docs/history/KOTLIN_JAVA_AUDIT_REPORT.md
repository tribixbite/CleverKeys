# CleverKeys Comprehensive Audit: Kotlin vs Java Implementation Discrepancies

**Date:** 2025-11-19
**Auditor:** Claude Code
**Reference:** Julow/Unexpected-Keyboard (Java)

## Executive Summary

After comparing the 6 core Kotlin files against their Java counterparts in Julow/Unexpected-Keyboard, I have identified **47 total discrepancies** across all priority levels.

---

## P0: Crashes/Blocking Issues (3 items)

### P0-1: Missing Autocapitalisation Class Integration
**File:** `src/main/kotlin/tribixbite/keyboard2/KeyEventHandler.kt`
**Issue:** The Java KeyEventHandler has a full `Autocapitalisation` class that tracks cursor position, capitalization mode, and uses delayed callbacks to update shift state. The Kotlin implementation only has a simple `shouldCapitalizeNext` boolean.
**Missing:**
- `Autocapitalisation` class with cursor tracking
- `_autocap.typed()` callbacks after sending text
- `_autocap.event_sent()` after key events
- `_autocap.selection_updated()` on selection changes
- Delayed callback mechanism for shift state updates
**Impact:** Autocapitalization will not work correctly in many text fields.

### P0-2: Missing `can_set_selection` Check
**File:** `src/main/kotlin/tribixbite/keyboard2/KeyEventHandler.kt`
**Issue:** The Kotlin `moveCursor()` method lacks the `can_set_selection` check that determines if `InputConnection.setSelection` is supported. Java implementation checks for Ctrl/Alt/Meta modifiers being active and certain input types.
**Missing:**
- Check for system modifiers (Ctrl, Alt, Meta) before using setSelection
- Proper fallback logic for terminals/special input types
**Impact:** Cursor movement will fail in terminal emulators and when modifiers are pressed.

### P0-3: Missing Vertical Cursor Movement
**File:** `src/main/kotlin/tribixbite/keyboard2/KeyEventHandler.kt`
**Issue:** Java has `move_cursor_vertical(int d)` for UP/DOWN movement. Kotlin slider handling only does horizontal movement.
**Missing:**
- `move_cursor_vertical()` method
- Vertical slider key handling for DPAD_UP/DPAD_DOWN
**Impact:** Vertical cursor movement slider keys will not work.

---

## P1: Core Functionality Broken (12 items)

### P1-1: Macro Evaluation Missing Delays
**File:** `src/main/kotlin/tribixbite/keyboard2/KeyEventHandler.kt`
**Issue:** Java `evaluate_macro` uses `postDelayed` with ~33ms delays between keys to prevent race conditions. Kotlin `handleMacroKey()` executes all keys synchronously.
**Missing:**
- Asynchronous macro execution with Handler
- `wait_after_macro_key()` check for KeyEvent/Editing keys
- 33ms delays between certain key types
**Impact:** Macros with KeyEvents or Editing operations will have race conditions and incorrect behavior.

### P1-2: Missing Selection-Aware Cursor Movement
**File:** `src/main/kotlin/tribixbite/keyboard2/KeyEventHandler.kt`
**Issue:** Java `move_cursor_sel()` moves one end of selection while keeping the other. Kotlin lacks this entirely.
**Missing:**
- `move_cursor_sel(int d, boolean sel_left, boolean key_down)` method
- Selection expansion/shrinking on slider movement
**Impact:** Slider-based text selection will not work.

### P1-3: Incomplete Slider Handling
**File:** `src/main/kotlin/tribixbite/keyboard2/KeyEventHandler.kt`
**Issue:** Java `handle_slider()` has sophisticated dispatch based on slider type (horizontal, vertical, selection). Kotlin only handles horizontal.
**Missing:**
- Proper slider type detection
- Dispatch to `move_cursor`, `move_cursor_vertical`, or `move_cursor_sel`
- Selection-mode awareness
**Impact:** Only basic cursor movement works; selection and vertical sliders broken.

### P1-4: Missing `send_context_menu_action` Wrapper
**File:** `src/main/kotlin/tribixbite/keyboard2/KeyEventHandler.kt`
**Issue:** Java has `send_context_menu_action()` that wraps `performContextMenuAction` and notifies autocapitalization. Kotlin calls `performContextMenuAction` directly.
**Missing:**
- Wrapper method that updates `_autocap` after operations
**Impact:** Autocapitalization state becomes incorrect after editing operations.

### P1-5: Missing `get_cursor_pos` Helper
**File:** `src/main/kotlin/tribixbite/keyboard2/KeyEventHandler.kt`
**Issue:** Java has `get_cursor_pos(InputConnection conn)` that extracts text and handles failures gracefully.
**Missing:**
- Dedicated method for safe cursor position retrieval
- Proper error handling for `getExtractedText` failures
**Impact:** Less robust cursor handling.

### P1-6: Config Missing `pin_entry_enabled` Setting
**File:** `src/main/kotlin/tribixbite/keyboard2/Config.kt`
**Issue:** Java Config has `pin_entry_enabled` for special PIN entry layouts.
**Missing:**
- `pin_entry_enabled` boolean property
- Loading from preferences
**Impact:** PIN entry mode will not work.

### P1-7: Config Missing `bottomInsetMin` Calculation
**File:** `src/main/kotlin/tribixbite/keyboard2/Config.kt`
**Issue:** Java `refresh()` calculates `bottomInsetMin` based on navigation bar type (pill, buttons, gesture).
**Missing:**
- Navigation bar type detection
- Dynamic bottom inset calculation
**Impact:** Keyboard may overlap navigation bar on some devices.

### P1-8: Pointers Missing Safe Key Retrieval
**File:** `src/main/kotlin/tribixbite/keyboard2/Pointers.kt`
**Issue:** The `onTouchDown` method receives a `key` parameter but key value lookup may not handle null keys properly.
**Present but incomplete:**
- `key.keys.getOrNull(0)` returns null silently
**Impact:** Null pointer issues on empty key positions.

### P1-9: KeyboardView Missing `onLayout` Back Gesture Exclusion
**File:** `src/main/kotlin/tribixbite/keyboard2/Keyboard2View.kt`
**Issue:** Java `onLayout` has code to disable back-gesture on keyboard area for Android 29+.
**Missing:**
- `onLayout` override with `setSystemGestureExclusionRects()`
**Impact:** Back gesture may interfere with keyboard swipes on edges.

### P1-10: Theme Missing Full TypedArray Color Loading
**File:** `src/main/kotlin/tribixbite/keyboard2/Theme.kt`
**Issue:** Java Theme loads colors from `TypedArray` based on theme XML. Kotlin hardcodes colors based on dark/light mode.
**Missing:**
- Loading from `context.obtainStyledAttributes(attrs, R.styleable.keyboard)`
- Proper XML theme integration
**Impact:** Custom themes from XML will not work; only basic dark/light.

### P1-11: KeyValue Missing All Named Keys
**File:** `src/main/kotlin/tribixbite/keyboard2/KeyValue.kt`
**Issue:** Java `getSpecialKeyByName` has a massive switch with hundreds of named keys. Kotlin only has ~50.
**Missing named keys include:**
- Editing keys: "copy", "paste", "cut", "selectAll", "undo", "redo"
- Many locale-specific keys
- Currency symbols
- Mathematical operators
- Combining diacritics
**Impact:** Many layout XML files will fail to parse keys correctly.

### P1-12: KeyValue Missing Hangul Final Key Support
**File:** `src/main/kotlin/tribixbite/keyboard2/KeyValue.kt`
**Issue:** Kotlin has `makeHangulFinal` but returns `CharKey` instead of proper Hangul final handling.
**Present but incomplete:**
- Returns CharKey directly without proper Hangul processing
**Impact:** Korean Hangul input will not complete syllables correctly.

---

## P2: Features Incomplete (18 items)

### P2-1: KeyEventHandler Missing IReceiver Handler Method
**File:** `src/main/kotlin/tribixbite/keyboard2/KeyEventHandler.kt`
**Issue:** Java `IReceiver.getHandler()` returns Handler for async operations.
**Missing:**
- `getHandler(): Handler` in IReceiver interface
**Impact:** Cannot use Handler.postDelayed for macro delays.

### P2-2: KeyEventHandler Missing `set_compose_pending` Handling
**File:** `src/main/kotlin/tribixbite/keyboard2/KeyEventHandler.kt`
**Issue:** Java `key_up` for `Compose_pending` calls `_recv.set_compose_pending()`.
**Missing:**
- IReceiver method for compose pending state
- Callback after compose key press
**Impact:** Compose/dead key indicator won't show.

### P2-3: KeyEventHandler Missing Hangul Processing
**File:** `src/main/kotlin/tribixbite/keyboard2/KeyEventHandler.kt`
**Issue:** Java has `handle_hangul_initial` and `handle_hangul_medial` methods.
**Missing:**
- `handle_hangul_initial()` for Korean consonants
- `handle_hangul_medial()` for Korean vowels
- Precomposed character calculation
**Impact:** Korean Hangul input completely broken.

### P2-4: Config Missing Layout Orientation State Persistence
**File:** `src/main/kotlin/tribixbite/keyboard2/Config.kt`
**Issue:** Java tracks 4 layout states for foldables.
**Missing:**
- `current_layout_unfolded_portrait`
- `current_layout_unfolded_landscape`
**Impact:** Foldable devices won't maintain separate layouts.

### P2-5: Config Missing `extra_keys_subtype` Processing
**File:** `src/main/kotlin/tribixbite/keyboard2/Config.kt`
**Issue:** `extra_keys_subtype` declared but never initialized.
**Missing:**
- Loading from InputMethodSubtype
**Impact:** Subtype-specific extra keys won't work.

### P2-6: Pointers Simplified Circle Gesture Detection
**File:** `src/main/kotlin/tribixbite/keyboard2/Pointers.kt`
**Issue:** Uses `totalRotation >= 6` approximation vs Java's precise algorithm.
**Impact:** Circle gestures may be harder to trigger.

### P2-7: Keyboard2View Missing Display Cutout Insets
**File:** `src/main/kotlin/tribixbite/keyboard2/Keyboard2View.kt`
**Issue:** Only calculates system window insets, not display cutout.
**Missing:**
- Display cutout insets
- Stable insets
**Impact:** Incorrect margins on notched phones.

### P2-8: Theme Missing Actual Style Loading
**File:** `src/main/kotlin/tribixbite/keyboard2/Theme.kt`
**Issue:** Hardcodes colors instead of loading from styleable attributes.
**Missing:**
- `R.styleable.keyboard` loading
**Impact:** Built-in themes use wrong colors.

### P2-9: Theme.Computed Different Row Height Calculation
**File:** `src/main/kotlin/tribixbite/keyboard2/Theme.kt`
**Issue:** Different formula for row_height cap.
**Impact:** Row height may be wrong on some screens.

### P2-10: KeyValue Missing `getSymbol()` Method
**File:** `src/main/kotlin/tribixbite/keyboard2/KeyValue.kt`
**Issue:** No distinction between display symbol and output character.
**Impact:** Some keys may display/output wrong character.

### P2-11: KeyValue Missing Editing Keys in namedKeys
**File:** `src/main/kotlin/tribixbite/keyboard2/KeyValue.kt`
**Missing:**
```
"copy", "paste", "cut", "selectAll", "share",
"pasteAsPlainText", "undo", "redo", "replaceText",
"textAssist", "autofill"
```
**Impact:** Cannot use in layout XML.

### P2-12: KeyValue Missing Cursor Movement Event Registration
**File:** `src/main/kotlin/tribixbite/keyboard2/KeyValue.kt`
**Issue:** Events exist but not in namedKeys.
**Impact:** Cannot use cursor keys in layout XML.

### P2-13: KeyValue Missing Placeholder Registration
**File:** `src/main/kotlin/tribixbite/keyboard2/KeyValue.kt`
**Issue:** Placeholder enum exists but not in namedKeys.
**Impact:** Cannot use placeholders in layout XML.

### P2-14: KeyValue Missing Zero-Width Characters
**File:** `src/main/kotlin/tribixbite/keyboard2/KeyValue.kt`
**Missing:**
```
"zwj", "zwnj", "cgj"
```
**Impact:** RTL text editing limited.

### P2-15: KeyboardView Missing Popup Key Support
**File:** `src/main/kotlin/tribixbite/keyboard2/Keyboard2View.kt`
**Issue:** No popup window for long-press key variants.
**Missing:**
- Popup window creation
- Long-press key grid display
**Impact:** Cannot show alternate characters on long press.

### P2-16: Missing Pointers getLatched Verification
**File:** `src/main/kotlin/tribixbite/keyboard2/Pointers.kt`
**Issue:** Verify matching logic with Java.
**Impact:** Minor - modifier latching may differ.

### P2-17: Keyboard2View Missing Row Helper
**File:** `src/main/kotlin/tribixbite/keyboard2/Keyboard2View.kt`
**Issue:** `getRowAtPosition` not extracted as helper.
**Impact:** Code quality.

### P2-18: Config Missing Navigation Bar Type Detection
**File:** `src/main/kotlin/tribixbite/keyboard2/Config.kt`
**Issue:** No detection of pill/button/gesture nav bar.
**Impact:** Incorrect bottom margins.

---

## P3: Minor Discrepancies (14 items)

1. **P3-1:** KeyEventHandler logging uses different tag
2. **P3-2:** Config integer preference try-catch handling
3. **P3-3:** Config missing default value comments
4. **P3-4:** Pointers uses data class (copy overhead)
5. **P3-5:** Pointers DIRECTION_TO_INDEX needs verification
6. **P3-6:** Theme hardcoded vs resource colors
7. **P3-7:** Theme getKeyFont static loading timing
8. **P3-8:** KeyValue comparison order may differ
9. **P3-9:** KeyValue Flag as Set vs int (safer but slower)
10. **P3-10:** KeyValue some Unicode points may differ
11. **P3-11:** Keyboard2View has MaterialThemeManager (improvement)
12. **P3-12:** Keyboard2View uses CoroutineScope vs Handler
13. **P3-13:** Config WIDE_DEVICE_THRESHOLD needs verification
14. **P3-14:** Pointers speed constants need verification

---

## Summary

| Priority | Count | Description |
|----------|-------|-------------|
| P0 | 3 | Crashes or blocking issues |
| P1 | 12 | Core functionality broken |
| P2 | 18 | Features incomplete |
| P3 | 14 | Minor discrepancies |
| **Total** | **47** | All discrepancies |

---

## Recommended Fix Order

### Immediate (P0) - Must fix before release:
1. Implement `Autocapitalisation` class
2. Add `can_set_selection` check
3. Add vertical cursor movement

### High Priority (P1) - Fix for basic usability:
4. Add macro delays with Handler.postDelayed
5. Implement selection-aware cursor movement
6. Complete slider handling
7. Add all missing named keys to KeyValue
8. Load Theme from XML TypedArray
9. Add onLayout back gesture exclusion
10. Add pin_entry_enabled to Config
11. Add bottomInsetMin calculation
12. Fix Hangul final key support

### Medium Priority (P2) - Complete feature parity:
13-30. All P2 items

### Low Priority (P3) - Polish:
31-47. All P3 items
