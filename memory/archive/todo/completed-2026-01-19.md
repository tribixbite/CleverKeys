# Completed Work - 2026-01-19

## Testing Infrastructure

### Pure JVM Tests (162 tests)
| Test Class | Tests | Time |
|------------|-------|------|
| AccentNormalizerTest | 29 | 0.08s |
| VocabularyTrieTest | 34 | 0.07s |
| DictionaryWordTest | 12 | 0.05s |
| SwipeResamplerTest | 19 | 0.04s |
| MemoryPoolTest | 23 | 0.08s |
| VocabularyUtilsTest | 24 | 0.05s |
| BeamSearchModelsTest | 21 | 0.09s |

### Instrumented Tests (277 tests, 180s on Pixel7 API 35)
| Test Class | Tests | Notes |
|------------|-------|-------|
| AccentNormalizerIntegrationTest | 18 | Accent normalization |
| AutocapitalizationTest | 7 | Caps modes, EditorInfo |
| AutocorrectTest | 27 | Typo correction, thresholds |
| BasicInstrumentedTest | 9 | Package, settings launch |
| ConfigIntegrationTest | 25 | Settings properties |
| DictionaryManagerTest | 18 | User dictionary ops |
| GestureClassifierTest | 16 | TAP vs SWIPE classification |
| KeyEventTest | 36 | KeyValue, modifiers, config |
| LanguageDetectorTest | 18 | Language detection |
| ShortSwipeGestureTest | 31 | Customizable gestures |
| SubkeyTest | 19 | Long-press popups |
| SwipePredictionTest | 22 | Gesture recognition |
| WordPredictorTest | 31 | Prediction with real dictionary |

## Code Cleanup
- Removed `migrateOldPrefs()` emoji migration code
- Optimized `saveLastUsed()` with 500ms debounce
- Added confidence score API to LanguageDetector

## Resolved TODOs (already implemented)
- **MultiLanguageManager.kt:102**: Language-specific dictionaries load via `SwipePredictorOrchestrator.loadPrimaryDictionaryFromPrefs()` which calls `OptimizedVocabulary.loadPrimaryDictionary()`
- **SettingsActivity.kt:3110**: Secondary dictionary loading triggers via `PreferenceUIUpdateHandler.reloadLanguageDictionaryIfNeeded()` when `pref_secondary_language` changes

## Swedish Language Pack
- **sv_enhanced.bin**: 40,000 words, 26.6% accented (ä, ö, å)
- **sv.bin**: Prefix boost trie (2.5MB, 154K nodes)
- **sv_unigrams.txt**: 5,000 words for language detection
- **langpack-sv.zip**: Importable language pack (1.7MB)

## Features
- Added Swipe Sensitivity preset (Low/Medium/High/Custom)

## Documentation
- Created `docs/wiki/settings/password-fields.md`
- Created `docs/wiki/getting-started/quick-settings.md`

## Recent Commits
| Commit | Description |
|--------|-------------|
| `2c937213` | Swipe sensitivity presets, cleanup, tests |
| `8f9447de` | 113 new instrumented tests (261 total) |
| `7873e465` | TestConfigHelper: 0 skipped tests |
| `9faf36d8` | Instrumented tests: 148 tests pass |
| `7ad3c4ad` | Local test runner for ARM64/proot |
| `fd6c7747` | Settings search: 38 → 120 entries |
| `5d18e039` | Swipe I-words capitalize fix |
| `17203125` | Clipboard TransactionTooLargeException fix |
