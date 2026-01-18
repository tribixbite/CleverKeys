# CleverKeys Working TODO

**Last Updated**: 2026-01-18
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

**Status**: Phase 1 in progress (9c45b13a)
**Spec**: `docs/specs/testing-strategy.md`

### Completed
- [x] Add MockK + Truth dependencies to build.gradle
- [x] Create AccentNormalizerTest.kt (30+ tests)
- [x] Create VocabularyTrieTest.kt (30+ tests)
- [x] Update CI to upload test results

### Pending
- [ ] Create DictionaryWord tests
- [ ] Create BeamSearchEngine tests (extract pure logic)
- [ ] Create GestureClassifier tests
- [ ] Add JUnit 5 for modern test features

**Note**: Tests run on CI (ubuntu-latest x86_64). ARM64/Termux skips Robolectric tests.

---

## Recent Fixes (2026-01-17/18)

| Commit | Description |
|--------|-------------|
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
