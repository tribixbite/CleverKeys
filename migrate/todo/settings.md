# Settings TODOs

This file tracks bugs and TODOs for the settings and preferences UI.

- File 6: 6 critical (Config.kt hardcoded resources, missing migrations, wrong defaults)
- File 21: **2 bugs** (FoldStateTracker - isFoldableDevice missing, Flow vs callback API)
- File 31: **3 low-priority i18n issues** (CustomExtraKeysPreference - ⚠️ SAFE STUB: intentional placeholder)
- File 32: **1 medium i18n issue** (ExtraKeysPreference - ✅ EXEMPLARY: ~30 hardcoded descriptions)
- File 33: **2 bugs → 1 bug** (IntSlideBarPreference - ✅ FIXED: String.format crash; ⏳ REMAINING: hardcoded padding in pixels)
- File 34: **3 bugs → 1 bug** (SlideBarPreference - ✅ FIXED: String.format crash, division by zero; ⏳ REMAINING: hardcoded padding in pixels)
- File 39: **1 medium bug** (NeuralConfig - ⏳ DOCUMENTED: copy() method doesn't create true independent copy)
- File 82: ✅ **ExtraKeysPreference.java (est. 300-400 lines) vs ExtraKeysPreference.kt (337 lines) + ExtraKeys.kt (18 lines) - ✅ EXCELLENT**
- File 92: ✅ **SwipeAdvancedSettings.java (est. 400-500 lines) vs SwipeAdvancedSettings.kt (282 lines) - ✅ EXCELLENT**
- File 97: ✅ **SettingsActivity.java (est. 700-900 lines) vs SettingsActivity.kt (935 lines) - ✅ EXCELLENT**
- File 104: ✅ **SettingsActivity.java (est. 800-1000 lines) vs CleverKeysSettings.kt (257 lines) - ⚠️ DUPLICATE (SUPERSEDED BY File 97, GlobalScope leak Bug #283)**
