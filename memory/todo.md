# CleverKeys TODO

## Completed (2026-01-19)
- ✅ Swedish language pack (sv_enhanced.bin, sv.bin, sv_unigrams.txt)
- ✅ Emoticons category in emoji picker (#76) - 119 text emoticons
- ✅ Tests for resolved issues (#41 emoji search, #71 clipboard, #72 I-words)
- ✅ Tests for swipe sensitivity presets (Low/Medium/High/Custom)
- ✅ Emoticons display fix (length-based text scaling for kaomoji)
- ✅ Emoji/clipboard toggle behavior (tap to open/close)
- ⚠️ Transparent bar between kb and emoji/clipboard pane (needs investigation)

## Code TODOs

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
