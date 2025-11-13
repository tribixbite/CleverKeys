# Settings TODOs

This file tracks bugs and TODOs for the settings and preferences UI.

- File 6: 6 critical (Config.kt hardcoded resources, missing migrations, wrong defaults)
- File 21: **2 bugs → 0 bugs** (FoldStateTracker - ✅ VERIFIED FALSE: detectDeviceSpecificFoldState() exists at FoldStateTrackerImpl.kt:124-148 for Samsung/Google/Huawei/Microsoft foldables; getFoldStateFlow() provides modern Flow API at line 240, which is BETTER than callbacks)
- File 31: **3 low-priority i18n issues** (CustomExtraKeysPreference - ⚠️ SAFE STUB: intentional placeholder)
- File 32: **0 bugs** (ExtraKeysPreference - ✅ FIXED Bug #639: replaced ~30 hardcoded descriptions with R.string resources; commit 4cf39084)
- File 33: **0 bugs** (IntSlideBarPreference - ✅ FIXED Bug #146: String.format crash, hardcoded padding → density-independent pixels; commit 3d1ee849)
- File 34: **0 bugs** (SlideBarPreference - ✅ FIXED Bug #147: String.format crash, division by zero, hardcoded padding → dp; commit 3d1ee849)
- File 39: **1 medium bug** (NeuralConfig - ⏳ DOCUMENTED: copy() method doesn't create true independent copy)
- File 82: ✅ **ExtraKeysPreference.java (est. 300-400 lines) vs ExtraKeysPreference.kt (337 lines) + ExtraKeys.kt (18 lines) - ✅ EXCELLENT**
- File 92: ✅ **SwipeAdvancedSettings.java (est. 400-500 lines) vs SwipeAdvancedSettings.kt (282 lines) - ✅ EXCELLENT**
- File 97: ✅ **SettingsActivity.java (est. 700-900 lines) vs SettingsActivity.kt (935 lines) - ✅ EXCELLENT**
- File 104: ✅ **SettingsActivity.java (est. 800-1000 lines) vs CleverKeysSettings.kt (257 lines) - ⚠️ DUPLICATE (SUPERSEDED BY File 97, GlobalScope leak Bug #283)**
