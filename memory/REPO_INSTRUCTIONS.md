# CleverKeys Repository Instructions

Quick reference for AI assistants and developers working in this codebase.

---

## Quick Start

```bash
# Build and install (ARM64 Termux)
./build-on-termux.sh

# Test compilation only
./gradlew compileDebugKotlin

# Debug logs
adb logcat -s "CleverKeys" "System.err" "AndroidRuntime"
```

---

## Context Management

### Session Startup Checklist
1. **`memory/todo.md`** - Active tasks (keep under 500 lines!)
2. **`docs/TABLE_OF_CONTENTS.md`** - Full documentation index
3. **`docs/specs/README.md`** - Feature specifications

### Todo Management Rules
- **DO**: Track outstanding items, active investigations, quick reference
- **DO**: Archive completed sections to `memory/archive/todo/`
- **DON'T**: Append full session logs - summarize key outcomes
- **DON'T**: Let file exceed 500 lines

### Documentation Locations
| Type | Path |
|------|------|
| Working TODO | `memory/todo.md` |
| Archived TODOs | `memory/archive/todo/` |
| Specs | `docs/specs/*.md` |
| Wiki guides | `docs/wiki/[category]/*.md` |
| Session history | `docs/history/` |

---

## Key Files

| Purpose | File | Notes |
|---------|------|-------|
| **All settings** | `Config.kt` | Defaults + field definitions |
| **Settings UI** | `SettingsActivity.kt` | 5000+ lines, searchable settings |
| **Swipe prediction** | `SwipePredictorOrchestrator.kt` | Neural swipe coordinator |
| **Word prediction** | `WordPredictor.kt` | Touch typing predictions |
| **Gesture handling** | `Pointers.kt` | Short swipe, trackpoint, selection-delete |
| **Neural beam search** | `BeamSearchEngine.kt` | ONNX decoder with trie masking |
| **Vocabulary/Trie** | `OptimizedVocabulary.kt` | Dictionary + language management |
| **Suggestions UI** | `SuggestionHandler.kt` | Prediction display logic |

---

## Spec-Driven Development

1. **Check Spec**: Is there a spec in `docs/specs/` for this feature?
2. **Create Spec**: If missing, copy from `docs/specs/SPEC_TEMPLATE.md`
3. **Implement**: Follow spec's implementation plan
4. **Test**: Use spec's testing strategy
5. **Update**: Mark TODOs complete in `memory/todo.md`

---

## Build System

### Termux ARM64
```bash
./build-on-termux.sh          # Uses custom ARM64 AAPT2
./build-on-termux.sh clean    # Clean build
```

### Version
```groovy
// build.gradle lines 51-53
ext.VERSION_MAJOR = 1
ext.VERSION_MINOR = 2
ext.VERSION_PATCH = 11
```

### ADB Testing
```bash
# Screenshot
adb shell screencap -p /sdcard/screenshot.png

# Install APK
adb install -r build/outputs/apk/release/CleverKeys-*.apk

# Logcat filter
adb logcat -s "CleverKeys" | grep -E "error|Exception"
```

---

## Settings Search Mapping

Settings are searchable via `SettingsActivity.searchableSettings`. To add a new setting:

```kotlin
SearchableSetting(
    title = "Setting Name",
    keywords = listOf("alias1", "alias2"),
    sectionName = "Section Name",
    activityClass = null,  // or SomeActivity::class.java
    expandSection = "section_key",
    gatedBy = "parent_toggle_key",  // optional
    settingId = "pref_key"
)
```

---

## Common Patterns

### Adding a new setting
1. Add default in `Config.Defaults`
2. Add field in `Config` class
3. Add UI in `SettingsActivity`
4. Add to `searchableSettings` list
5. Add to `BackupRestoreManager` if needed

### Adding a gesture
1. Update `Pointers.kt` flag handling
2. Add to `CommandRegistry` if keyboard command
3. Update wiki in `docs/wiki/gestures/`

### Adding a language
1. Generate dictionary with `scripts/build_all_languages.py`
2. Create langpack with `scripts/build_langpack.py`
3. Update `SettingsActivity` language lists
