# UI TODOs

This file tracks UI-related bugs and missing features (theming, suggestion bar, layouts).

- File 3: 1 critical (text size calculation)
- File 5: 11 critical (SuggestionBar 73% missing, no theme integration)
- File 8: 1 critical (Theme XML loading broken)
- File 9: 5 critical (Keyboard2View - gesture exclusion missing, inset handling, indication rendering)
- File 10: 5 critical (KeyboardData - keysHeight wrong, missing validations)
- File 22: **16 CRITICAL → 9 REMAINING** (LayoutsPreference - ✅ FIXED 7; ⏳ REMAINING: wrong base class, data loss, broken serialization)
- File 23: **5 bugs** (ClipboardPinView - programmatic layout workaround, hardcoded strings/emojis, missing Utils.show_dialog_on_ime, but 5 enhancements)
- File 24: **12 CATASTROPHIC** (ClipboardHistoryView - wrong base class LinearLayout→NonScrollListView, missing AttributeSet, no adapter, broken pin/paste, missing lifecycle, wrong API calls)
- File 29: **1 bug → 0 bugs** (EmojiGroupButtonsBar - ✅ FIXED: wrong resource ID)
- File 30: **3 bugs → 1 bug** (EmojiGridView - ✅ FIXED: missing onDetachedFromWindow() lifecycle; ⏳ REMAINING: inconsistent API, missing accessibility)
- File 38: **0 bugs** (NonScrollListView - ✅ EXEMPLARY: clean utility class)
- File 55: **8 bugs** (EmojiGridView.kt - Bug #244 wrong base class GridLayout→GridView, Bug #245 no adapter pattern, etc.)
- File 56: **3 bugs** (EmojiGroupButtonsBar.kt - Bug #252 nullable AttributeSet, etc.)
- File 106: ✅ **CustomLayoutEditor.java (est. 800-1000 lines) vs CustomLayoutEditor.kt (453 lines) - ⚠️ GOOD (3 TODOs incomplete)**
