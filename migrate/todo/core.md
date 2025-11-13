# Core Logic TODOs

This file tracks bugs and missing features in the core keyboard logic (parsing, event handling, etc.).

## 🟠 MEDIUM PRIORITY BUGS (From TODO_MEDIUM_LOW.md)

### Configuration (1 bug)
- [x] **Bug #75**: CharKey flags hardcoded to emptySet() ✅ FIXED (2025-11-12)
  - File: ComposeKey.kt (File 13)
  - Impact: Some compose key flags lost
  - Fix: Replaced direct CharKey() constructor with KeyValue.makeCharKey() factory method
  - Commit: 876e995e
  - Severity: MEDIUM

- [x] **Bug #80**: TRIGGER_CHARACTERS expanded beyond Java ❌ FALSE (2025-11-13)
  - File: Autocapitalisation.kt (File 15) - Note: todo incorrectly listed as File 14
  - Status: NOT A BUG - Both Java and Kotlin versions only trigger on space character
  - Java: `is_trigger_character(c)` returns true only for ' ' (space)
  - Kotlin: `isTriggerCharacter(c)` returns true only for ' ' (space)
  - Impact: None - identical behavior
  - Severity: N/A (false report)

## 🟡 LOW PRIORITY BUGS (From TODO_MEDIUM_LOW.md)

### Code Quality (1 bug)
- [x] **Bug #77**: LegacyComposeSystem - 90 lines of UNUSED dead code ❌ FALSE (2025-11-13)
  - File: ComposeKey.kt (File 13)
  - Status: NOT A BUG - LegacyComposeSystem IS used in KeyModifier.kt:156
  - Used by: applyComposeOrFallback() method for compose sequence fallback
  - Impact: Code bloat only
  - Severity: LOW

- [x] **Bug #272**: TracePoint comment incorrect ✅ FIXED (2025-11-13)
  - File: SwipeMLData.kt (File 70)
  - Impact: Documentation only
  - Fix: Added proper KDoc with @property annotations for x, y (normalized [0,1]) and tDeltaMs (time delta from previous point)
  - Severity: LOW

---

## 📋 REVIEW STATUS (From REVIEW_TODO_CORE.md - UPDATED)

**Progress**: 141/251 files reviewed (56.2%)
**Remaining**: 110 files (43.8%)

**Next Review Files**: Files 142-251
- Systematic review continuing from File 142
- See `docs/COMPLETE_REVIEW_STATUS.md` for full timeline

**Files Needing Review**:
- [ ] Files 142-170 (next batch of 29 files)
- [ ] Files 171-220 (50 files)
- [ ] Files 221-251 (31 files)

---

## 🟢 CORE SYSTEM BUGS

- File 1: 1 critical (KeyValueParser 96% missing)
- File 2: 23 critical (Keyboard2 ~800 lines missing)
- File 4: 1 critical (Config.handler = null)
- File 7: 8 critical (KeyEventHandler 22% missing - no macros, editing keys, sliders)
- File 11: **11 CATASTROPHIC** (KeyModifier - modify() broken, 335 lines missing, 63% reduction)
- File 12: **✅ 0 bugs** (Modmap - PROPERLY IMPLEMENTED, improvements over Java)
- File 13: **1 bug** (ComposeKey - ✅ FIXED Bug #75 flags hardcoded; ⏳ REMAINING: 90 lines unused code)
- File 14: **✅ 0 bugs** (ComposeKeyData - ✅ FIXED with code generation)
- File 15: **0 bugs** (Autocapitalisation - ❌ Bug #80 FALSE: trigger logic identical to Java)
- File 16: **1 CATASTROPHIC** (ExtraKeys - 95% missing, architectural mismatch)
- File 17: **1 CRITICAL → 0 bugs** (DirectBootAwarePreferences - ✅ FIXED: device-protected storage, migration logic, full implementation)
- File 18: **✅ 0 bugs** (Utils - ✅ EXEMPLARY! 7X expansion with enhancements)
- File 20: **3 bugs → 0 bugs** (Logs - ✅ FIXED: TAG constant, debug_startup_input_view(), trace())
- File 51: **4 CATASTROPHIC bugs** (R.kt - 💀 Manual stub instead of generated R class; CRITICAL - missing 95% resource types, wrong ID format, build system not generating R properly)
- File 52: **5 bugs** (Resources.kt - CRITICAL: entire file is band-aid for R.kt issue; HIGH: silent failures without logging; MEDIUM: wrong type handling for Int, catches all exceptions; LOW: inconsistent fallback API)
- File 101: ✅ **ErrorHandling.java (est. 300-400 lines) vs ErrorHandling.kt (252 lines) - ✅ EXCELLENT**
- File 105: ✅ **ConfigurationManager.java (est. 700-900 lines) vs ConfigurationManager.kt (513 lines) - ✅ EXCELLENT (CRITICAL memory leak Bug #291)**
- File 107: ✅ **UtilityClasses.java (est. 200-300 lines scattered) vs Extensions.kt (104 lines) - ✅ EXCELLENT (ZERO BUGS, FIXES 12 OTHER BUGS)**
- File 108: ✅ **ValidationTests.java (est. 600-800 lines scattered) vs RuntimeValidator.kt (461 lines) - ✅ EXCELLENT**
- File 110: ✅ **SystemIntegrationTests.java (est. 600-800 lines scattered) vs SystemIntegrationTester.kt (448 lines) - ✅ EXCELLENT**
