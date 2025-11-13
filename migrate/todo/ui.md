# UI TODOs

This file tracks UI-related bugs and missing features (theming, suggestion bar, layouts).

## 🟠 MEDIUM PRIORITY BUGS (From TODO_MEDIUM_LOW.md)

### UI Internationalization (1 bug)
- [x] **Bug #116**: Hardcoded header text ✅ FIXED (2025-11-12)
  - File: ClipboardPinView.kt (File 23)
  - Impact: No i18n support
  - Fix: Replaced hardcoded strings with R.string resources
  - Commit: a1887701
  - Severity: MEDIUM

- [x] **Bug #117**: Hardcoded button text ✅ FIXED (2025-11-12)
  - File: ClipboardPinView.kt (File 23)
  - Impact: No i18n support
  - Fix: Replaced hardcoded strings with R.string resources
  - Commit: a1887701
  - Severity: MEDIUM

- [x] **Bug #119**: Hardcoded emoji icons ✅ FIXED (2025-11-12)
  - File: ClipboardPinView.kt (File 23)
  - Impact: No customization
  - Fix: Replaced emoji buttons (📋, 🗑️) with text buttons using R.string resources
  - Commit: a1887701
  - Severity: MEDIUM

- [ ] **Bug #121**: Hardcoded toast message
  - File: ClipboardHistoryCheckBox.kt (File 27)
  - Impact: No i18n support
  - Severity: MEDIUM

### Performance (1 bug)
- [ ] **Bug #128**: Blocking initialization in lazy property
  - File: ClipboardHistoryService.kt (File 25)
  - Impact: Potential ANR (Application Not Responding)
  - Severity: MEDIUM

## 🟡 LOW PRIORITY BUGS (From TODO_MEDIUM_LOW.md)

### API Consistency (2 bugs)
- [ ] **Bug #129**: Different method name - clear_expired_and_get_history
  - File: ClipboardHistoryService.kt (File 25)
  - Impact: API naming inconsistency
  - Severity: LOW

- [ ] **Bug #130**: Interface moved from inner to top-level
  - File: ClipboardHistoryService.kt (File 25)
  - Impact: Minor architectural difference
  - Severity: LOW

---

## 🟢 UI SYSTEM BUGS

- File 3: 1 critical (text size calculation)
- File 5: 11 critical (SuggestionBar 73% missing, no theme integration)
- File 8: 1 critical (Theme XML loading broken)
- File 9: 5 critical (Keyboard2View - gesture exclusion missing, inset handling, indication rendering)
- File 10: 5 critical (KeyboardData - keysHeight wrong, missing validations)
- File 22: **16 CRITICAL → 9 REMAINING** (LayoutsPreference - ✅ FIXED 7; ⏳ REMAINING: wrong base class, data loss, broken serialization)
- File 23: **2 bugs** (ClipboardPinView - ✅ FIXED Bugs #116-117-119 hardcoded strings/emojis; ⏳ REMAINING: programmatic layout workaround, missing Utils.show_dialog_on_ime, but 5 enhancements)
- File 24: **12 CATASTROPHIC** (ClipboardHistoryView - wrong base class LinearLayout→NonScrollListView, missing AttributeSet, no adapter, broken pin/paste, missing lifecycle, wrong API calls)
- File 29: **1 bug → 0 bugs** (EmojiGroupButtonsBar - ✅ FIXED: wrong resource ID)
- File 30: **3 bugs → 1 bug** (EmojiGridView - ✅ FIXED: missing onDetachedFromWindow() lifecycle; ⏳ REMAINING: inconsistent API, missing accessibility)
- File 38: **0 bugs** (NonScrollListView - ✅ EXEMPLARY: clean utility class)
- File 55: **8 bugs** (EmojiGridView.kt - Bug #244 wrong base class GridLayout→GridView, Bug #245 no adapter pattern, etc.)
- File 56: **3 bugs** (EmojiGroupButtonsBar.kt - Bug #252 nullable AttributeSet, etc.)
- File 106: ✅ **CustomLayoutEditor.java (est. 800-1000 lines) vs CustomLayoutEditor.kt (453 lines) - ⚠️ GOOD (3 TODOs incomplete)**
