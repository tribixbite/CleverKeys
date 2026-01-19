# CleverKeys Working TODO

**Last Updated**: 2026-01-19
**Current Version**: v1.2.11
**Status**: Production Ready

> **Context Management**: Keep this file under 500 lines. Archive completed work to `memory/archive/todo/`.

---

## Code TODOs

| File | Line | Description | Priority |
|------|------|-------------|----------|
| `EmojiGridView.kt` | 43 | Remove `migrateOldPrefs()` migration code | Low |
| `EmojiGridView.kt` | 90 | Optimize `saveLastUsed()` | Low |
| `MultiLanguageManager.kt` | 102 | Phase 8.2: Load language-specific dictionaries | Medium |
| `MultiLanguageManager.kt` | 186 | Add confidence score to language detector | Low |
| `SettingsActivity.kt` | 3096 | Trigger secondary dictionary loading in service | Medium |
| `SettingsActivity.kt` | 3434 | Error Reports toggle - no logging implementation | Low |

---

## Active Investigations

### English Words in French-Only Mode
**Status**: Diagnostic logging added (83ea45f7)
**Issue**: English words appearing when Primary=French, Secondary=None
**Next**: Check logcat after swipe: `adb logcat | grep "getVocabularyTrie\|loadPrimaryDictionary"`
**Key files**: `OptimizedVocabulary.kt`, `SwipePredictorOrchestrator.kt`

### Long Word Prediction
**Status**: Critical fix applied (22fc3279) - length normalization in beam search
**Testing needed**:
- [ ] Swipe "dangerously" in SwipeDebugActivity
- [ ] Verify confidence values are length-normalized

---

## Pending Features

### Settings UI Polish
- [ ] Add "Swipe Sensitivity" preset (Low/Medium/High)
- [ ] Standardize units across distance settings
- [ ] Move Vibration to Accessibility section
- [ ] Move Smart Punctuation to Auto-Correction

### Web Demo P2
- [ ] Lazy loading for 12.5MB models
- [ ] PWA/Service Worker for offline support

### Legacy Code Cleanup
- [ ] Migrate `DictionaryManagerActivity` → ComponentActivity
- [ ] Migrate `SwipeCalibrationActivity` → ComponentActivity
- [ ] Migrate deprecated `android.preference.*` to `androidx.preference.*`

### Documentation Consolidation
See `docs/DOCS_AUDIT.md` for full analysis:
- [ ] Create wiki guide for `password-field-mode.md`
- [ ] Create wiki guide for `quick-settings-tile.md`
- [ ] Consolidate duplicate specs (top-level → wiki/specs)

---

## Testing Infrastructure

**Status**: Phase 1 complete (7ad3c4ad)
**Spec**: `docs/specs/testing-strategy.md`
**Local runner**: `./scripts/run-pure-tests.sh`

### Completed
- [x] Add MockK + Truth dependencies to build.gradle
- [x] Create AccentNormalizerTest.kt (29 tests)
- [x] Create VocabularyTrieTest.kt (34 tests)
- [x] Create DictionaryWordTest.kt (12 tests)
- [x] Create SwipeResamplerTest.kt (19 tests)
- [x] Create MemoryPoolTest.kt (23 tests)
- [x] Create VocabularyUtilsTest.kt (24 tests)
- [x] Create BeamSearchModelsTest.kt (21 tests)
- [x] Update CI to upload test results
- [x] Create `run-pure-tests.sh` for ARM64/Termux local testing

### Test Results (162 total)
```
AccentNormalizerTest:  29 tests OK (0.08s)
VocabularyTrieTest:    34 tests OK (0.07s)
DictionaryWordTest:    12 tests OK (0.05s)
SwipeResamplerTest:    19 tests OK (0.04s)
MemoryPoolTest:        23 tests OK (0.08s)
VocabularyUtilsTest:   24 tests OK (0.05s)
BeamSearchModelsTest:  21 tests OK (0.09s)
```

### Pending
- [ ] Create GestureClassifier tests (requires mock framework)

**Local testing**: Uses proot-distro Ubuntu to run pure JVM tests on ARM64.

### Instrumented Tests (emulator.wtf)
```bash
ew-cli --app build/outputs/apk/debug/CleverKeys-v1.2.5-x86_64.apk \
       --test build/outputs/apk/androidTest/debug/CleverKeys-debug-androidTest.apk \
       --device model=Pixel7,version=35 --use-orchestrator --clear-package-data
```

**261 tests pass** (170s on Pixel7 API 35):
| Test Class | Tests | Notes |
|------------|-------|-------|
| AccentNormalizerIntegrationTest | 18 | Accent normalization |
| AutocapitalizationTest | 7 | Caps modes, EditorInfo |
| AutocorrectTest | 27 | Typo correction, thresholds |
| BasicInstrumentedTest | 9 | Package, settings launch |
| ConfigIntegrationTest | 25 | Settings properties |
| DictionaryManagerTest | 18 | User dictionary ops |
| KeyEventTest | 36 | KeyValue, modifiers, config |
| LanguageDetectorTest | 18 | Language detection |
| ShortSwipeGestureTest | 31 | Customizable gestures |
| SubkeyTest | 19 | Long-press popups |
| SwipePredictionTest | 22 | Gesture recognition, NeuralSwipeTypingEngine |
| WordPredictorTest | 31 | Prediction with real dictionary |

`TestConfigHelper.ensureConfigInitialized()` provides real Config for tests without requiring full keyboard service.

---

## Recent Fixes (2026-01-17/18/19)

| Commit | Description |
|--------|-------------|
| `7873e465` | TestConfigHelper: 0 skipped tests, real Config init |
| `9faf36d8` | Instrumented tests: 148 tests pass on emulator.wtf |
| `7ad3c4ad` | Local test runner for ARM64/proot |
| `9c45b13a` | Pure JVM tests: AccentNormalizer, VocabularyTrie |
| `70986e85` | Workflow rules, release script, docs audit |
| `7dab5a27` | Context management - split todo.md into archives |
| `fd6c7747` | Settings search: 38 → 120 entries (~80% coverage) |
| `5d18e039` | Swipe I-words capitalize, add-to-dictionary case fix |
| `05050b47` | Proper noun case preserved in user dictionary |
| `3ef810ca` | Auto-capitalize in CAP_WORDS text fields |
| `17203125` | Clipboard TransactionTooLargeException fix |

---

## Quick Reference

### Build Commands
```bash
./build-on-termux.sh          # Full build + install
./gradlew compileDebugKotlin  # Test compilation only
adb logcat -s "CleverKeys"    # Debug logs
```

### Key Files
| Purpose | File |
|---------|------|
| All settings | `Config.kt` |
| Settings UI | `SettingsActivity.kt` |
| Swipe prediction | `SwipePredictorOrchestrator.kt` |
| Word prediction | `WordPredictor.kt` |
| Gesture handling | `Pointers.kt` |
| Neural beam search | `BeamSearchEngine.kt` |

### Version Info
```
build.gradle (lines 51-53):
  ext.VERSION_MAJOR = 1
  ext.VERSION_MINOR = 2
  ext.VERSION_PATCH = 11
```

### F-Droid Status
- MR #30449: **MERGED** - CleverKeys is on F-Droid
- Auto-update enabled via tag detection

---

## Archive

Historical session logs and completed feature details archived to:
- `memory/archive/todo/split1.md` - Sessions 2026-01-17/16
- `memory/archive/todo/split2.md` - Sessions 2026-01-14/13
- `memory/archive/todo/split3.md` - Versions v1.2.1 to v1.1.85
- `memory/archive/todo/split4.md` - F-Droid, investigations, roadmap

---

*For full documentation: `docs/TABLE_OF_CONTENTS.md`*
*For specs: `docs/specs/README.md`*
*For wiki: `docs/wiki/TABLE_OF_CONTENTS.md`*
