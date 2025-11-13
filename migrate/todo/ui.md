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

- [x] **Bug #121**: Hardcoded toast message ❌ FALSE (2025-11-13)
  - File: ClipboardHistoryCheckBox.kt (File 27)
  - Status: NOT A BUG - No toast message exists in file (verified lines 1-46)
  - Impact: None - no hardcoded strings found
  - Severity: N/A (false report)

### Performance (1 bug)
- [x] **Bug #128**: Blocking initialization in lazy property ✅ FIXED (2025-11-13)
  - File: ClipboardHistoryService.kt (File 25)
  - Impact: Prevented ANR (Application Not Responding)
  - Fix: Changed from `lazy { runBlocking { } }` to `lateinit var` with async initialization in init block
  - Before: `private val database by lazy { runBlocking { ClipboardDatabase.getInstance(context) } }`
  - After: `private lateinit var database: ClipboardDatabase` + async init in scope.launch
  - Severity: MEDIUM

## 🟡 LOW PRIORITY BUGS (From TODO_MEDIUM_LOW.md)

### API Consistency (2 bugs)
- [x] **Bug #129**: Different method name - clear_expired_and_get_history ❌ FALSE (2025-11-13)
  - File: ClipboardHistoryService.kt (File 25)
  - Status: NOT A BUG - Kotlin uses camelCase naming convention (clearExpiredAndGetHistory)
  - Java: `clear_expired_and_get_history()` (snake_case)
  - Kotlin: `clearExpiredAndGetHistory()` (camelCase - CORRECT Kotlin convention)
  - Impact: None - follows language idioms
  - Severity: N/A (false report)

- [x] **Bug #130**: Interface moved from inner to top-level ❌ FALSE (2025-11-13)
  - File: ClipboardHistoryService.kt (File 25)
  - Status: NOT A BUG - Top-level interface is Kotlin best practice
  - Java: `public interface ClipboardPasteCallback` (inner interface)
  - Kotlin: `interface ClipboardPasteCallback` (top-level - BETTER design)
  - Impact: None - improves reusability and separation of concerns
  - Severity: N/A (intentional improvement)

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
- File 27: **2 bugs → 0 bugs** (ClipboardHistoryCheckBox - ✅ FIXED Bug #122: missing updateData() method; ✅ FIXED Bug #123: missing onAttachedToWindow() lifecycle hook; ✅ VERIFIED FALSE Bug #121: no hardcoded toast exists)
- File 29: **1 bug → 0 bugs** (EmojiGroupButtonsBar - ✅ FIXED: wrong resource ID)
- File 30: **3 bugs → 0 bugs** (EmojiGridView - ✅ FIXED: missing onDetachedFromWindow() lifecycle (lines 177-180), missing accessibility (line 121 adds contentDescription); ✅ VERIFIED FALSE: inconsistent API is intentional - showGroup(String) for group names vs setEmojiGroup(Int) for indices - different purposes, compatibility layer)
- File 38: **0 bugs** (NonScrollListView - ✅ EXEMPLARY: clean utility class)
- File 55: **8 bugs → 0 bugs** (EmojiGridView.kt - ⚠️ DUPLICATE OF FILE 30 - All bugs already fixed in File 30; same file reviewed twice)
- File 56: **3 bugs → 0 bugs** (EmojiGroupButtonsBar.kt - ⚠️ DUPLICATE OF FILE 29 - Bug #252 FALSE: AttributeSet already nullable `attrs: AttributeSet? = null`; same file reviewed twice)
- File 106: ✅ **CustomLayoutEditor.java (est. 800-1000 lines) vs CustomLayoutEditor.kt (453 lines) - ⚠️ GOOD (3 TODOs incomplete)**
