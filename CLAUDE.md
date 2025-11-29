# CLAUDE.md - CleverKeys Development Context

## 🚨 **SESSION STARTUP PROTOCOL - ALWAYS CHECK FIRST!**

**BEFORE STARTING ANY SESSION:**
1.  **CHECK `README.md`** - Production status (86/100 score, Grade A)
2.  **CHECK `00_START_HERE_FIRST.md`** - Testing guide (3 minutes)
3.  **CHECK `docs/TABLE_OF_CONTENTS.md`** - Master navigation for all 66+ files
4.  **CHECK `memory/todo.md`** - Current tasks (all development complete)
5.  **CHECK `docs/specs/`** - Feature specifications (10 system specs)

**CURRENT STATUS (2025-11-29):**
- ✅ Development 100% complete (181 Kotlin files, zero compilation errors)
- ✅ Full settings parity with Unexpected-Keyboard (Nov 28)
- ✅ UK file review: 163 Kotlin + 84 layouts verified (see review section below)
- ✅ Production ready (Score: 86/100, Grade A)
- ✅ APK builds successfully (52MB)
- ✅ Unit tests compile (Robolectric requires x86_64 host for runtime)
- ⏳ Manual device testing for new settings features

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

**Development Status**: ✅ 100% complete (183 Kotlin files, zero compilation errors)
**Build Status**: ✅ APK builds successfully (52MB)
**Production Score**: 86/100 (Grade A) - Ready for manual device testing

**📁 NAVIGATION GUIDE:**

### Essential Files (Check First)
1. **`README.md`** - Production ready status (86/100, Grade A)
2. **`00_START_HERE_FIRST.md`** - Manual testing guide (3 minutes)
3. **`docs/TABLE_OF_CONTENTS.md`** - Master file index (66 files tracked)

### TODO Lists (By Component)
4. **`memory/todo.md`** - Current tasks (all development complete)
5. **`migrate/todo/critical.md`** - Historical P0 bugs (all resolved)
6. **`CRITICAL_MISSING_FEATURES.md`** - Bug #471 and #472 (both fixed)

### Feature Specifications
7. **`docs/specs/README.md`** - Index of all 10 system specifications
8. **`docs/specs/ui-material3-modernization.md`** - Material 3 UI (complete)
9. **`docs/specs/architectural-decisions.md`** - 7 ADRs (ONNX, coroutines, etc.)

### Historical Reference
10. **`docs/history/`** - Archived analysis, roadmaps, migration docs (19 files)
11. **`SESSION_FINAL_NOV_16_2025.md`** - Complete Nov 16 session summary
12. **`PRODUCTION_READY_NOV_16_2025.md`** - Production readiness (86/100)

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

## 🔍 **UK Feature Parity Audit (Nov 28, 2025)**

### Settings Synced from Unexpected-Keyboard

The following settings were added to achieve full feature parity:

**Word Prediction (Advanced)**:
- Context-aware predictions with N-gram learning
- Personalized learning with frequency tracking
- Learning aggression levels (Conservative/Balanced/Aggressive)
- Context boost multiplier (0.5-5.0)
- Frequency scaling factor (100-5000)

**Auto-Correction**:
- Enable/disable toggle
- Minimum word length (2-5)
- Character match threshold (0.5-0.9)
- Minimum frequency threshold (100-5000)

**Swipe Corrections**:
- Beam search corrections toggle
- Final output corrections toggle
- Correction style presets (Strict/Balanced/Lenient)
- Fuzzy matching algorithm (Edit Distance/Positional)
- Typo forgiveness (0-5 chars)
- Starting letter accuracy (0-4 letters)
- Correction search depth (1-10 candidates)
- Prediction source balance (0-100%)
- Common/frequent word boosts
- Rare words penalty

**Neural Prediction (Advanced)**:
- Batch processing toggle
- Greedy search toggle
- Length normalization alpha (0.0-5.0)
- Pruning confidence (0.0-1.0)
- Early stop gap (0.0-20.0)
- Model version selection
- Quantized models toggle
- Trajectory resampling mode
- A/B testing controls
- Rollback and recovery
- Privacy and data collection controls

**Advanced Gesture Tuning**:
- Tap duration threshold (50-500ms)
- Double-space-to-period threshold (0-1000ms)
- Minimum swipe distance (20-100px)
- Minimum key distance (15-80px)
- Minimum key dwell time (0-50ms)
- Movement noise filter (0.5-10px)
- High velocity threshold (200-2000px/sec)
- Slider speed smoothing (0.1-0.95)
- Maximum speed multiplier (1.0-10.0)

**Multi-Language Support**:
- Enable/disable toggle
- Primary language selection
- Auto-detect language toggle
- Detection sensitivity (0.4-0.9)

**Clipboard History (Enhanced)**:
- Limit type (count/size)
- Size limits (1-100MB)
- Pane height percent (10-50%)
- Pinned section rows
- Maximum item size (100KB-5MB)

**Backup and Restore**:
- Export/import configuration
- Export/import custom dictionary
- Export/import clipboard history

### Layout Fixes Applied
- `delete_last_word` on backspace NW corner (was `loc f1_word`)
- Period on C key SW corner (was missing)
- Bottom row synced with UK (`switch_back_clipboard`, `action` key)

### Files Modified (Nov 28, 2025)
- `res/xml/settings.xml` - Added 100+ preferences
- `res/values/strings.xml` - Added 25+ new strings
- `res/values/arrays.xml` - Added 15+ array resources
- `res/xml/bottom_row.xml` - Synced with UK
- `src/main/layouts/latn_qwerty_us.xml` - Fixed keys

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

---

## 📋 **UK CONFIG FEATURE PARITY VERIFICATION**

**Working Memory File:** `uk-config-todos.md`

This file tracks 600+ verification tasks for ensuring complete feature parity between Unexpected-Keyboard and CleverKeys. Use this file as working memory during parity verification sessions.

### Workflow:
1. **Select a setting** from `uk-config-todos.md`
2. **Verify in UK source** (`../Unexpected-Keyboard`) that the setting is actually used
3. **Verify in CleverKeys** that we have matching functionality in both settings UI and keyboard operation
4. **Verify import/export** handles the setting correctly
5. **Mark with status prefix:**
   - `✓` = Complete/Verified (setting works correctly in both apps)
   - `!` = Needs clarification (requires user input to resolve)
   - `✗` = Not properly implemented in UK (include explanation)

### Example:
```markdown
### swipe_min_distance: 46.4
✓ 1. UK: Verified in SwipeDetector.java line 145 - controls minimum swipe travel
✓ 2. CK: CleverKeys uses in SwipeDetector.kt line 89 - same threshold logic
✓ 3. CK: Import/export tested - value correctly saved/loaded in ConfigManager.kt
```

### Progress Tracking:
- After completing all 600+ TODOs, test importing original UK config
- Extract only verified (✓) lines to create default values JSON
- Set these as CleverKeys app defaults

### Commands:
```bash
# Count completed todos
grep -c "^✓" uk-config-todos.md

# Count todos needing clarification
grep -c "^!" uk-config-todos.md

# Count UK implementation issues
grep -c "^✗" uk-config-todos.md
```
