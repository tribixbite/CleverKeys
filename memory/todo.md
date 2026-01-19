# CleverKeys TODO

## Code TODOs

### MultiLanguageManager.kt:102
**Load language-specific dictionaries (Phase 8.2)**
- Currently uses same vocabulary for all languages
- Need to load dictionaries per language code
- Files: `MultiLanguageManager.kt`, `OptimizedVocabulary.kt`

### SettingsActivity.kt:3096
**Trigger secondary dictionary loading in service**
- Secondary language selection doesn't trigger dictionary load
- Need to call orchestrator to load secondary dictionary

### SettingsActivity.kt:3434
**Error Reports toggle has no backend**
- Toggle is hidden in UI
- Need async file logging implementation
- Low priority

---

## Investigations

### English Words in French-Only Mode
- **Issue**: English words appear when Primary=French, Secondary=None
- **Diagnostic**: Logging added in commit 83ea45f7
- **Debug**: `adb logcat | grep "getVocabularyTrie\|loadPrimaryDictionary"`
- **Files**: `OptimizedVocabulary.kt`, `SwipePredictorOrchestrator.kt`

### Long Word Prediction
- **Fix applied**: Length normalization in beam search (22fc3279)
- **Verify**: Swipe "dangerously" in SwipeDebugActivity
- **Check**: Confidence values are length-normalized

---

## Features

### Settings UI Polish
- [ ] Standardize units across distance settings (px vs dp vs %)
- [ ] Move Vibration settings to Accessibility section
- [ ] Move Smart Punctuation to Auto-Correction section

### Web Demo P2
- [ ] Lazy loading for 12.5MB models
- [ ] PWA/Service Worker for offline support

### Legacy Code Cleanup
- [ ] Migrate deprecated `android.preference.*` to `androidx.preference.*`

### Documentation
- [ ] Consolidate duplicate specs (top-level → wiki/specs)
- [ ] See `docs/DOCS_AUDIT.md` for full analysis

---

## Reference

### Build
```bash
./build-on-termux.sh          # Full build + install
./gradlew compileDebugKotlin  # Compilation check
```

### Test
```bash
# Instrumented (emulator.wtf)
ew-cli --app build/outputs/apk/debug/CleverKeys-v1.2.5-x86_64.apk \
       --test build/outputs/apk/androidTest/debug/CleverKeys-debug-androidTest.apk \
       --device model=Pixel7,version=35 --use-orchestrator --clear-package-data

# Local JVM
./scripts/run-pure-tests.sh
```

### Key Files
| Purpose | File |
|---------|------|
| Settings | `Config.kt` |
| Settings UI | `SettingsActivity.kt` |
| Swipe prediction | `SwipePredictorOrchestrator.kt` |
| Word prediction | `WordPredictor.kt` |
| Gesture handling | `Pointers.kt` |

---

*Archive: `memory/archive/todo/`*
*Docs: `docs/TABLE_OF_CONTENTS.md`*
