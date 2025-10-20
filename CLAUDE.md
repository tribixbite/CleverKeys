# CLAUDE.md - CleverKeys Development Context

## 🚨 **SESSION STARTUP PROTOCOL - ALWAYS CHECK FIRST!**

**BEFORE STARTING ANY SESSION:**
1.  **CHECK `migrate/project_status.md`** - Current milestone and consolidation status
2.  **CHECK `docs/TABLE_OF_CONTENTS.md`** - Master navigation for all 66+ files
3.  **CHECK `docs/COMPLETE_REVIEW_STATUS.md`** - Review progress: 141/251 files (56.2%)
4.  **CHECK `migrate/todo/critical.md`** - P0 catastrophic bugs (15 remaining)
5.  **CHECK `docs/specs/`** - Feature specifications (gesture, layout, neural systems)

**CURRENT FOCUS (2025-10-20):**
- ✅ Fix #51-53 COMPLETE (keys working, container fixed, text sizing)
- ✅ Documentation consolidated (66 files → organized structure)
- ✅ 3 critical specs created (gesture, layout, neural prediction)
- 🔄 Resume systematic review at File 142/251 (110 files remaining)
- 🔄 Fix P0 bugs as discovered

**SPEC-DRIVEN DEVELOPMENT WORKFLOW:**
1. **Check Spec**: Is there a spec in `docs/specs/` for this feature?
2. **Create Spec**: If missing, create from `docs/specs/SPEC_TEMPLATE.md`
3. **Implement**: Follow spec's implementation plan (with TODOs at top)
4. **Test**: Use spec's testing strategy
5. **Update**: Mark TODOs complete, update status
6. **Commit**: Reference spec in commit message

**REVIEW PROCESS:**
- Compare Java file from `Unexpected-Keyboard` with Kotlin equivalent
- Use Feature & File Structure Mapping below to locate files
- Document in `migrate/todo/` by component (critical/core/features/neural/ui)
- Create specs for major systems in `docs/specs/`
- Commit after each file review or bug fix

---

## 🎯 **PROJECT OVERVIEW**

CleverKeys is a **complete Kotlin rewrite** of `Julow/Unexpected-Keyboard` featuring:
- **Pure ONNX neural prediction** (NO CGR, NO fallbacks).
- **Advanced gesture recognition** with sophisticated algorithms.
- **Modern Kotlin architecture** with significant code reduction.
- **Reactive programming** with coroutines and Flow streams.
- **Enterprise-grade** error handling and validation.

---

## 📋 **CURRENT STATUS & NAVIGATION**

**Review Progress**: 141/251 files (56.2% complete)
**Bugs Documented**: 337 total (25 catastrophic, 12 high, 11 medium, 3 low, 46 fixed)
**Build Status**: ✅ APK builds successfully (49MB)

**📁 NAVIGATION GUIDE:**

### Essential Files (Check First)
1. **`migrate/project_status.md`** - Current milestone, consolidation status
2. **`docs/TABLE_OF_CONTENTS.md`** - Master file index (66 files tracked)
3. **`docs/COMPLETE_REVIEW_STATUS.md`** - Full review timeline (Files 1-141)

### TODO Lists (By Component)
4. **`migrate/todo/critical.md`** - P0 catastrophic bugs (15 remaining)
5. **`migrate/todo/core.md`** - Core keyboard logic bugs
6. **`migrate/todo/features.md`** - Missing user features
7. **`migrate/todo/neural.md`** - ONNX pipeline bugs
8. **`migrate/todo/settings.md`** - Settings system bugs
9. **`migrate/todo/ui.md`** - UI/UX bugs

### Feature Specifications (Spec-Driven Development)
10. **`docs/specs/SPEC_TEMPLATE.md`** - Template for new specs
11. **`docs/specs/gesture-system.md`** - Gesture recognition (Bug #267)
12. **`docs/specs/layout-system.md`** - ExtraKeys customization (Bug #266)
13. **`docs/specs/neural-prediction.md`** - ONNX pipeline (Bugs #273-277)
14. **`docs/specs/architectural-decisions.md`** - 6 ADRs (ONNX, coroutines, etc.)

### Historical Reference
15. **`docs/history/`** - Archived analysis, roadmaps, migration docs (19 files)
16. **`docs/history/reviews/`** - Individual file reviews (Files 82-85)
17. **`migrate/claude_history.md`** - Development timeline

---

## Java-to-Kotlin Feature Parity Review Guide

### **Objective**
Ensure every feature, setting, and behavior from the original `Julow/Unexpected-Keyboard` is perfectly replicated or intentionally improved in `CleverKeys`. The goal is 100% feature parity.

### **Process**
1.  **Select a Feature**: Pick an incomplete task from `memory/todo.md`.
2.  **Locate Files**: Use the mapping table below to identify the relevant files in both the old Java repository and this Kotlin repository.
3.  **Line-by-Line Comparison**: Analyze the logic of the Java implementation.
4.  **Implement in Kotlin**: Write the feature in Kotlin, adhering to the existing architecture (coroutines, reactive data, etc.).
5.  **Verify**: Test the feature to ensure it behaves identically to the original.
6.  **Update Status**: Mark the task as complete in `memory/todo.md`.

### **Feature & File Structure Mapping**

This table maps the core features and file locations from the original Java-based `Unexpected-Keyboard` to their modern Kotlin equivalents in `CleverKeys`.

| Feature / Concept | `Unexpected-Keyboard` (Java/XML) | `cleverkeys` (Kotlin) | Status / Notes |
| :--- | :--- | :--- | :--- |
| **Main Service** | `LatinIME.java` | `src/main/kotlin/tribixbite/keyboard2/CleverKeysService.kt` | **COMPLETE**. Core IME logic rewritten in Kotlin. |
| **Keyboard View** | `KeyboardView.java` | `src/main/kotlin/tribixbite/keyboard2/Keyboard2View.kt` | **COMPLETE**. Custom view, re-implemented with modern drawing. |
| **Settings** | `Settings.java`, `res/xml/prefs.xml` | `src/main/kotlin/tribixbite/keyboard2/SettingsActivity.kt`, `config/*` | **PARTIAL**. UI exists, but features are pending per `memory/todo.md`. |
| **Layout Definition** | `res/xml/qwerty.xml`, etc. | `src/main/layouts/*.xml` | **COMPATIBLE**. The XML format for layouts is the same. |
| **Layout Loading** | `KeyboardLayout.java` (presumed) | `KeyboardLayoutLoader.kt`, `Config.kt` | **COMPLETE**. Modernized, reactive layout loading system. |
| **Swipe/Gesture Logic** | CGR (Native C Code), `SwipeTracker.java` | `neural/*`, `SwipeDetector.kt` | **ARCHITECTURAL REPLACEMENT**. Replaced with pure ONNX model. |
| **Word Predictions** | `WordPredictor.java`, Dictionaries | `neural/OnnxSwipePredictorImpl.kt` | **ARCHITECTURAL REPLACEMENT**. Replaced with pure ONNX model. |
| **Key Event Handling** | `KeyEventHandler.java` (presumed) | `src/main/kotlin/tribixbite/keyboard2/KeyEventHandler.kt` | **COMPLETE**. Core logic ported to Kotlin. |
| **Suggestion Display** | `CandidateView.java` | `src/main/kotlin/tribixbite/keyboard2/SuggestionBar.kt` | **COMPLETE**. Re-implemented as `SuggestionBar`. |
| **Utilities** | `Utils.java` | `utils/Utils.kt`, `utils/Extensions.kt` | **ENHANCED**. Significantly expanded with new gesture and data utilities. |
| **JNI/Native Code** | `jni/*` | `assets/libjni_latinimegoogle.so` | **LEGACY**. The old native library is present but unused by the new ONNX engine. |

---

## 🚨 **CRITICAL DEVELOPMENT PRINCIPLES**

**IMPLEMENTATION STANDARDS (PERMANENT MEMORY):**
- **NEVER** use stubs, placeholders, or mock implementations.
- **NEVER** simplify functionality to make code compile.
- **ALWAYS** implement features properly and completely.
- **ALWAYS** do things the right way, not the expedient way.

---

## 📁 **ARCHITECTURE OVERVIEW**

```
src/main/kotlin/tribixbite/keyboard2/
├── core/                           # Core keyboard functionality
├── neural/                         # ONNX neural prediction (NO CGR)
├── data/                           # Data models
├── config/                         # Configuration system
├── ui/                             # User interfaces
├── utils/                          # Utilities
└── testing/                        # Quality assurance
```

---

## 🚀 **DEVELOPMENT COMMANDS**

### **BUILD:**
```bash
# Test compilation
./gradlew compileDebugKotlin

# Full build & install
./build-on-termux.sh

# Run tests
./gradlew test
```

### **DEBUGGING:**
```bash
# Check for compilation errors
./gradlew compileDebugKotlin --continue

# Tail logs for debugging
logcat -s "CleverKeys" "System.err" "AndroidRuntime"
```
