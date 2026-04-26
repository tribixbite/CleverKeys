# CLAUDE.md - CleverKeys Development Context

## 🚨 **SESSION STARTUP PROTOCOL - ALWAYS CHECK FIRST!**

**BEFORE STARTING ANY SESSION:**
1.  **CHECK `README.md`** - Production status and overview.
2.  **CHECK `memory/todo.md`** - **Active Task List** (The single source of truth).
3.  **CHECK `docs/TABLE_OF_CONTENTS.md`** - Master navigation for project docs.
4.  **CHECK `docs/specs/`** - Feature specifications for the area you are working on.

**CURRENT STATUS (2026-03-26):**
- ✅ Development 100% complete.
- ✅ Production Ready (Grade A).
- ✅ **New Features**: Short Swipe Customization, Profile System, Media Clipboard (v4).
- ✅ Documentation updated and consolidated.

**SPEC-DRIVEN DEVELOPMENT WORKFLOW:**
1. **Check Spec**: Is there a spec in `docs/specs/` for this feature?
2. **Create Spec**: If missing, create from `docs/specs/SPEC_TEMPLATE.md`
3. **Implement**: Follow spec's implementation plan.
4. **Test**: Use spec's testing strategy.
5. **Update**: Mark TODOs complete in `memory/todo.md`.

## 📚 **SKILL FILES (READ BEFORE TASK MATCHES)**

`.claude/skills/` contains task-specific reference docs. **ALWAYS read the relevant skill BEFORE starting work on a matching topic** — they encode hard-won lessons and exact procedures the main context doesn't reproduce.

| Trigger phrase | Skill file |
|---|---|
| "release", "tag", "publish", "version bump", "F-Droid", "fastlane", "changelog" | `.claude/skills/release-process.md` |
| "clipboard", "pinned", "todo", "tag" (clipboard) | `.claude/skills/clipboard-panel-architecture.md`, `clipboard-tag-system.md`, `clipboard-todo-system.md` |
| "IME toast", "feedback", "pulse" | `.claude/skills/ime-visual-feedback.md` |
| "key routing", "edit mode", "search mode" in IME | `.claude/skills/ime-key-routing.md` |
| "ew-cli", "instrumented test", "emulator.wtf" | `.claude/skills/ew-cli-testing.md` |
| "dictionary", "VocabularyTrie", "predictor" | `.claude/skills/dictionary-pipeline.md` |
| "settings", "SharedPreferences" | `.claude/skills/settings-preferences.md` |
| "wiki", "Astro", "site docs" | `.claude/skills/wiki-documentation.md` |
| "emoji panel" | `.claude/skills/emoji-panel.md` |
| "content pane layout" | `.claude/skills/content-pane-layout.md` |

**Release-specific reminder**: When user says any release-related word, READ `.claude/skills/release-process.md` FIRST. It documents the fastlane changelog model (`fastlane/metadata/android/en-US/changelogs/{baseCode}{abi}.txt`), the F-Droid API queries for current state, and the version-code math. Do NOT confuse `metadata/fdroid/tribixbite.cleverkeys.yml` (build recipe) with the fastlane changelogs (release notes).

---

## 🎯 **PROJECT OVERVIEW**

CleverKeys is a **complete Kotlin rewrite** of `Julow/Unexpected-Keyboard` featuring:
- **Pure ONNX neural prediction** (NO CGR, NO fallbacks).
- **Advanced gesture recognition** with sophisticated algorithms.
- **Modern Kotlin architecture** with significant code reduction.
- **Reactive programming** with coroutines and Flow streams.
- **Enterprise-grade** error handling and validation.

---

## 📋 **NAVIGATION GUIDE**

### Essential Files
1. **`memory/todo.md`** - **Current pending tasks and verified working features.**
2. **`docs/TABLE_OF_CONTENTS.md`** - Index of all documentation.
3. **`docs/history/session_log_dec_2025.md`** - Recent completed work log.

### Feature Specifications
*Located in `docs/specs/`*
- `short-swipe-customization.md`: Per-key gesture customization.
- `profile_system_restoration.md`: Layout import/export with gestures.
- `neural-prediction.md`: ONNX AI model architecture.
- `core-keyboard-system.md`: Main keyboard logic.
- `clipboard-privacy.md`: Clipboard privacy features.

---

## 🚨 **CRITICAL DEVELOPMENT PRINCIPLES**

**IMPLEMENTATION STANDARDS:**
- **NEVER** use stubs, placeholders, or mock implementations.
- **NEVER** simplify functionality to make code compile.
- **ALWAYS** implement features properly and completely.
- **ALWAYS** do things the right way, not the expedient way.

**TESTING POLICY:**
- **NEVER** test locally via ADB (screencap, input, am start, etc.). ADB is for build-install only.
- **ALWAYS** write instrumented tests (ew-cli) or pure JVM tests when testing is possible.
- If a scenario cannot be tested via instrumented or pure tests, **ask the user to test manually**.

---

## 📁 **ARCHITECTURE OVERVIEW**

```
src/main/kotlin/tribixbite/keyboard2/
├── core/                           # Core keyboard functionality
├── neural/                         # ONNX neural prediction (NO CGR)
├── data/                           # Data models
├── config/                         # Configuration system
├── ui/                             # User interfaces
├── customization/                  # Customization logic (Short Swipes, Profiles)
├── utils/                          # Utilities
└── testing/                        # Quality assurance
```

---

## 🚀 **DEVELOPMENT COMMANDS**

### **BUILD:**
```bash
# Test compilation
./gradlew compileDebugKotlin

# Full build & install (ALWAYS use this for testing)
./build-on-termux.sh

# Run tests
./gradlew test
```

### **IMPORTANT: Always Install RELEASE APK**
**NEVER install debug APK for testing.** Always use release builds:
- `build/outputs/apk/release/CleverKeys-v*.apk` ✅
- `build/outputs/apk/debug/CleverKeys-v*.apk` ❌

Debug logging is controlled by `BuildConfig.ENABLE_VERBOSE_LOGGING` which is set
in build.gradle - release builds can have debug logging enabled when needed.
This gives best of both worlds: release performance + debug visibility.

### **DEBUGGING:**
```bash
# Check for compilation errors
./gradlew compileDebugKotlin --continue

# Tail logs for debugging
logcat -s "CleverKeys" "System.err" "AndroidRuntime"
```