# CLAUDE.md - CleverKeys Development Context

## 🚨 **SYSTEMATIC REVIEW INSTRUCTIONS - ALWAYS CHECK FIRST!**

**BEFORE STARTING ANY SESSION:**
1.  **CHECK `migrate/project_status.md`** - This file tracks the overall status of the project.
2.  **CHECK `migrate/completed_reviews.md`** - This file contains detailed file-by-file comparison results from previous reviews.
3.  **CHECK `memory/todo.md`** - This file contains the comprehensive list of outstanding work required to achieve full feature parity.
4.  **PROCEED** with the systematic Java-to-Kotlin feature parity review, guided by the `todo.md` and the file structure mapping below.
5.  **FIX SIMULTANEOUSLY** when bugs are found that can be fixed immediately (don't just document).

**REVIEW PROCESS:**
- Compare the original Java file from `Unexpected-Keyboard` with its Kotlin equivalent in this repository.
- Use the **Feature & File Structure Mapping** guide below to locate corresponding files.
- Document missing features, architectural changes, and bugs.
- Update `memory/todo.md` with any new findings.
- Commit after each significant feature implementation or bug fix.

---

## 🎯 **PROJECT OVERVIEW**

CleverKeys is a **complete Kotlin rewrite** of `Julow/Unexpected-Keyboard` featuring:
- **Pure ONNX neural prediction** (NO CGR, NO fallbacks).
- **Advanced gesture recognition** with sophisticated algorithms.
- **Modern Kotlin architecture** with significant code reduction.
- **Reactive programming** with coroutines and Flow streams.
- **Enterprise-grade** error handling and validation.

---

## 📋 **CURRENT STATUS & GUIDES**

- **Primary Goal**: Achieve 100% feature parity with the original `Unexpected-Keyboard`.
- **Outstanding Tasks**: See **`memory/todo.md`** for a detailed, prioritized list of missing features and bugs.
- **Historical Logs**: See **`migrate/claude_history.md`** for a detailed archive of past development, bug fixes, and milestones.
- **Build Status**: The APK builds successfully, and the core neural prediction pipeline is functional. The immediate focus is on implementing the remaining user-facing features.

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
