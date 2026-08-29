# CleverKeys Release Record

**An append-only book. Every user-facing claim CleverKeys has ever published in a release
note gets one row, anchored to the code that implements it today and the test that pins it.**

## Contract

1. **Append-only.** New releases are appended at the **bottom**, in ascending version order.
   Existing sections are history and are **immutable** — their exact markdown bytes are pinned
   by SHA-256 in `ReleaseRecordDriftTest.versionBlockSha256`. Editing a released section turns
   the suite red on purpose. If a recorded item's anchor genuinely moves, add a **superseding
   row in the current release's section**; do not retcon the old one.
2. **Anchors are `path#Symbol`, never `path:line`.** Line numbers rot on the first unrelated
   edit above them, which would make the guard a high-frequency false alarm and train people
   to bump hashes without reading. `ReleaseRecordDriftTest` checks that each anchor's file
   exists and still contains the named symbol.
3. **Completeness is enforced.** The set of releases is derived from
   `fastlane/metadata/android/en-US/changelogs/` (the load-bearing changelog channel — see
   `.claude/skills/release-process.md`). Ship a changelog without appending a section here and
   the suite goes red.
4. **Source of truth for the note text** is the fastlane changelog for that version's
   arm64 ABI code (`{baseCode}2.txt`). The three per-ABI copies are byte-identical; two legacy
   files (`1.txt` for v1.0.0, the unsuffixed `10209.txt` for v1.2.9) predate the convention.

## Status vocabulary

| status | meaning | code anchor | test anchor |
|---|---|---|---|
| `GUARDED` | a test pins the behaviour | required | required |
| `PRESENT-UNTESTED` | the code exists, nothing pins it | required | must be `—` |
| `REMOVED (…)` | superseded or deleted; cites the ADR / superseding release | `—` | `—` |
| `UNATTRIBUTABLE` | the published note was too vague to attribute to anything | `—` | `—` |

`PRESENT-UNTESTED` is a legitimate and useful answer — collectively those rows are the backlog
of shipped promises that nothing defends. Do not invent a test citation to make a row look
better; the drift test resolves every anchor and will catch it.

`REMOVED` is dominated by one event: **ADR-011 (2026-08-18) deleted the neural swipe engine**
(`docs/specs/architectural-decisions.md`). Every neural-era tuning knob, beam-search parameter
and ONNX-decoder fix announced between v1.0.0 and v1.2.9 is gone with it. Swipe is now CTC
(default) plus a geometric fallback.

---

## v1.0.0 (versionCode 10000, 2025-12-12)

Launch release.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| neural swipe typing | feature | Neural network swipe/gesture typing | REMOVED (ADR-011) | — | — |
| unlimited clipboard history | feature | Unlimited clipboard history | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Config.kt#clipboard_history_limit` | `src/test/kotlin/tribixbite/cleverkeys/ClipboardFixesJvmTest.kt#slider at max (100) saves as 0 sentinel` |
| predefined themes + DIY creator | feature | 18+ themes with DIY theme creator | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/theme/PredefinedThemes.kt#themeGemstoneRuby` | `src/androidTest/kotlin/tribixbite/cleverkeys/ThemeSettingsActivityComposeTest.kt#openCreateThemeDialog_showsTitle` |
| layout catalogue | feature | 100+ keyboard layouts | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/LayoutManager.kt#LayoutManager` | — |
| short-swipe action catalogue | feature | 208 short-swipe gesture actions | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/customization/CommandRegistry.kt#CommandRegistry` | — |
| per-ABI APK splits | chore | Per-ABI APKs for smaller downloads | PRESENT-UNTESTED | `build.gradle#abiCodes` | — |
| no network access | feature | Complete privacy (no network access) — the manifest declares no INTERNET permission; nothing asserts its absence | PRESENT-UNTESTED | `AndroidManifest.xml#uses-permission` | — |

## v1.0.3 (versionCode 10003, 2025-12-13)

Proguard fixes.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| neural pipeline obfuscation | fix | Fixed neural prediction pipeline obfuscation | REMOVED (ADR-011) | — | — |
| ONNX runtime keeps | fix | Improved ONNX runtime compatibility — the R8 keep rule survived ADR-011 because CTC still runs ONNX | PRESENT-UNTESTED | `proguard-rules.pro#onnxruntime` | — |
| gesture recognition stability | fix | Better gesture recognition stability | UNATTRIBUTABLE | — | — |

## v1.0.4 (versionCode 10004, 2025-12-13)

UI improvements.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| launcher setup steps | feature | Enhanced launcher activity with setup steps | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/activities/LauncherActivity.kt#LauncherActivity` | `src/androidTest/kotlin/tribixbite/cleverkeys/LauncherSelectKeyboardSafetyTest.kt#selectKeyboard_guard_checksEnabledStateBeforePicker` |
| theme consistency | chore | Improved theme consistency | UNATTRIBUTABLE | — | — |
| keyboard visibility detection | fix | Better keyboard visibility detection | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/IMEStatusHelper.kt#isDefaultIME` | — |

## v1.0.5 (versionCode 10005, 2025-12-14)

Stability release.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| ONNX inner-class keeps | fix | Additional proguard rules for ONNX inner classes | PRESENT-UNTESTED | `proguard-rules.pro#onnxruntime` | — |
| reflection-based class loading | fix | Fixed reflection-based class loading | UNATTRIBUTABLE | — | — |
| neural provider stability | fix | Improved neural provider stability | REMOVED (ADR-011) | — | — |

## v1.0.6 (versionCode 10006, 2025-12-15)

Version sync fix.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| defaultConfig version sync | fix | Fixed defaultConfig version mismatch with ext values | GUARDED | `build.gradle#versionNameStr` | `src/test/kotlin/tribixbite/cleverkeys/ReleaseMetadataDriftTest.kt#versionPart` |
| APK version reporting | fix | All APK versions now correctly report 1.0.6 | GUARDED | `build.gradle#versionCodeOverride` | `src/test/kotlin/tribixbite/cleverkeys/ReleaseMetadataDriftTest.kt#abiChangelogFiles` |

## v1.0.7 (versionCode 10007, 2025-12-17)

System-bar and reproducibility fixes.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| OEM status/nav bar overlay | fix | Fixed status/navigation bar overlay on OEM devices (Samsung, Xiaomi) | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/WindowLayoutUtils.kt#updateSoftInputWindowLayoutParams` | — |
| nav bar transparency | fix | Fixed keyboard navigation bar transparency | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/WindowLayoutUtils.kt#configureEdgeToEdge` | — |
| suggestion bar collapse when empty | fix | Fixed suggestion bar collapse when empty | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/SuggestionBar.kt#alwaysVisible` | — |
| F-Droid build reproducibility | chore | Improved build reproducibility for F-Droid | PRESENT-UNTESTED | `build.gradle#profileinstaller` | — |

## v1.1.70 (versionCode 10170, 2025-12-19)

Reproducibility and metadata.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| F-Droid verification reproducibility | chore | Reproducibility improvements for F-Droid verification | PRESENT-UNTESTED | `build.gradle#profileinstaller` | — |
| no postbuild fixes | chore | Simplified build process - no postbuild fixes needed | UNATTRIBUTABLE | — | — |
| metadata descriptions | chore | Updated metadata with improved descriptions | PRESENT-UNTESTED | `metadata/fdroid/tribixbite.cleverkeys.yml#AutoName` | — |

## v1.1.71 (versionCode 10171, 2025-12-20)

Swipe-data export and dictionary tweaks.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| SAF swipe-data export | feature | SAF file picker for swipe data export (saves anywhere) | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/ui/settings/io/SettingsSwipeDataHandlers.kt#exportSwipeDataJSON` | — |
| own words in dictionary | chore | Added cleverkeys and tribixbite to dictionary | UNATTRIBUTABLE | — | — |
| swipe data collection toggle | fix | Fixed swipe data collection toggle not working | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/ml/SwipeMLDataStore.kt#SwipeMLDataStore` | — |
| max word length default 15 | chore | Default max word length reduced to 15 characters | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/Config.kt#Defaults` | — |

## v1.1.72 (versionCode 10172, 2025-12-23)

Margin and customization polish.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| portrait bottom margin range | chore | Increase portrait bottom margin max from 30dp to 80dp | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Config.kt#margin_bottom` | `src/test/kotlin/tribixbite/cleverkeys/ConfigDefaultsTest.kt#margin bottom portrait default is 0` |
| custom sublabel colour | fix | Fix custom sublabel color to match default sublabels | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/Theme.kt#subLabelColor` | — |
| icon preview in customization dialog | fix | Improve icon preview in customization dialog | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/customization/KeyboardPreviewView.kt#KeyboardPreviewView` | — |

## v1.1.73 (versionCode 10173, 2025-12-24)

Nav-bar overlap fix.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| API 30-34 keyboard positioning | fix | Fix keyboard positioning on API 30-34 devices | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/WindowLayoutUtils.kt#updateSoftInputWindowLayoutParams` | — |
| nav bar no longer overlapped | fix | Keyboard no longer overlaps system navigation bar | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/WindowLayoutUtils.kt#configureEdgeToEdge` | — |
| API 21-29 insets fallback | fix | Added insets fallback for API 21-29 | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/WindowLayoutUtils.kt#WindowLayoutUtils` | — |

## v1.1.74 (versionCode 10174, 2025-12-24)

Percentage-based margins.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| percent margins | feature | Margin settings now use % instead of dp for device independence | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Config.kt#get_percent_pref_oriented_width` | `src/test/kotlin/tribixbite/cleverkeys/ConfigDefaultsTest.kt#margin bottom portrait default is 0` |
| bottom margin as % of height | feature | Bottom margin: % of screen height (0-30%) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Config.kt#margin_bottom` | `src/test/kotlin/tribixbite/cleverkeys/ConfigDefaultsTest.kt#margin bottom portrait default is 0` |
| split left/right margins | feature | Split horizontal margin into separate left/right controls | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Config.kt#margin_left` | `src/test/kotlin/tribixbite/cleverkeys/backup/SettingsImportPlanBuilderTest.kt#modifiedKey_intValueChanged` |
| left/right margin caps | feature | Left/right margins: % of screen width (0-45% each) with a 90% total cap | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/Config.kt#margin_right` | — |
| old-config migration | fix | Old config imports automatically migrated to new format | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Config.kt#horizontal_margin_portrait` | `src/test/kotlin/tribixbite/cleverkeys/backup/SettingsDefaultsDriftTest.kt#noSourceFileWritesADeprecatedKey` |

## v1.1.75 (versionCode 10175, 2025-12-24)

Direct Boot fix.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| lock-screen crash on fresh boot | fix | Fix keyboard crash at lock screen on fresh boot | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/DirectBootManager.kt#DirectBootManager` | `src/test/kotlin/tribixbite/cleverkeys/DirectBootManagerTest.kt#DirectBootManagerTest` |
| PrivacyManager device-protected storage | fix | PrivacyManager now uses device-protected storage | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/PrivacyManager.kt#PrivacyManager` | `src/test/kotlin/tribixbite/cleverkeys/PrivacyManagerTest.kt#PrivacyManagerTest` |
| keyboard works before unlock | fix | Keyboard works before user unlocks device | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/DirectBootAwarePreferences.kt#get_shared_preferences` | `src/test/kotlin/tribixbite/cleverkeys/DirectBootAwarePreferencesTest.kt#DirectBootAwarePreferencesTest` |

## v1.1.76 (versionCode 10176, 2025-12-24)

Direct Boot and security.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| comprehensive Direct Boot support | feature | Comprehensive Direct Boot support for lock screen use | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/DirectBootManager.kt#getDeviceProtectedPreferences` | `src/androidTest/kotlin/tribixbite/cleverkeys/DirectBootInstrumentedTest.kt#DirectBootInstrumentedTest` |
| PII deferred until unlock | feature | DirectBootManager defers PII until device unlock | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/DirectBootManager.kt#registerUnlockCallback` | `src/test/kotlin/tribixbite/cleverkeys/DirectBootManagerTest.kt#DirectBootManagerTest` |
| clipboard blocked while locked | feature | Clipboard pane blocked while device is locked | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardHistoryService.kt#ClipboardHistoryService` | — |
| device-encrypted storage for non-sensitive managers | chore | Non-sensitive managers use device-encrypted storage | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/DirectBootAwarePreferences.kt#copy_preferences_to_protected_storage` | `src/test/kotlin/tribixbite/cleverkeys/DirectBootAwarePreferencesTest.kt#DirectBootAwarePreferencesTest` |
| PII in credential-encrypted storage | feature | PII stays secure in credential-encrypted storage | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/PrivacyManager.kt#PrivacyManager` | `src/androidTest/kotlin/tribixbite/cleverkeys/PrivacyManagerInstrumentedTest.kt#PrivacyManagerInstrumentedTest` |

## v1.1.79 (versionCode 10179, 2025-12-31)

Password field mode.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| predictions off in password fields | feature | Disable predictions/autocorrect in password/PIN fields | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/SuggestionBar.kt#isPasswordMode` | `src/androidTest/kotlin/tribixbite/cleverkeys/SuggestionBarAutofillTest.kt#passwordMode_removesPadding` |
| password show/hide toggle | feature | Eye toggle to show/hide password text in suggestion bar | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/SuggestionBar.kt#SuggestionBar` | — |
| Material visibility icons | chore | Material Design visibility icons | UNATTRIBUTABLE | — | — |
| scrollable password display | feature | Scrollable password display with fixed icon position | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/SuggestionBar.kt#SuggestionBar` | — |
| InputConnection sync | feature | Syncs with InputConnection for accurate tracking | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/PredictionContextTracker.kt#currentCursorPosition` | `src/androidTest/kotlin/tribixbite/cleverkeys/ContractionFlickerTest.kt#expectingSelectionUpdate_suppressesSynchronizeWithCursor` |

## v1.1.80 (versionCode 10180, 2026-01-03)

Neural prediction tuning for long words.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| beam search defaults | fix | Fixed beam search defaults for better long word prediction | REMOVED (ADR-011) | — | — |
| configurable touch smoothing | feature | Added configurable Touch Smoothing in Neural Settings | REMOVED (ADR-011) | — | — |
| consolidated neural settings | chore | Consolidated duplicate settings (Pruning Confidence, Early Stop Gap) | REMOVED (ADR-011) | — | — |
| frequency weight and early-stop tuning | fix | Tuned frequency weight and early stopping thresholds | REMOVED (ADR-011) | — | — |

## v1.1.81 (versionCode 10181, 2026-01-03)

Long-word prediction and neural tuning.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| long word prediction | fix | Fixed long word prediction (e.g. dangerously now works) | REMOVED (ADR-011) | — | — |
| length-normalized beam scoring | fix | Length-normalized beam search scoring for fair word comparison | REMOVED (ADR-011) | — | — |
| neural presets | feature | Neural presets: Speed, Balanced, Accuracy modes | REMOVED (ADR-011) | — | — |
| temperature and frequency weight controls | feature | Temperature and frequency weight controls in Neural Settings | REMOVED (ADR-011) | — | — |
| swipe debug tool redesign | feature | Swipe Debug tool redesigned with copy/save actions | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/activities/SwipeDebugActivity.kt#SwipeDebugActivity` | — |
| manage local training samples | feature | On-device learning: manage your local training samples | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/ml/SwipeMLDataStore.kt#SwipeMLDataStore` | `src/androidTest/kotlin/tribixbite/cleverkeys/SwipeMLDataStoreTest.kt#SwipeMLDataStoreTest` |
| beam dedup and early termination | chore | Beam search deduplication and early termination tuning | REMOVED (ADR-011) | — | — |
| touch smoothing control | feature | Touch smoothing control for swipe trajectories | REMOVED (ADR-011) | — | — |
| feature extraction pipeline | chore | Optimized feature extraction pipeline | REMOVED (ADR-011) | — | — |

## v1.1.95 (versionCode 10195, 2026-01-06)

Experimental multilanguage swipe typing.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| primary language selection | feature | Primary language selection (6 bundled: EN, ES, FR, PT, IT, DE) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/MultiLanguageManager.kt#switchLanguage` | `src/test/kotlin/tribixbite/cleverkeys/LanguageSlotCoverageDriftTest.kt#LanguageSlotCoverageDriftTest` |
| secondary language | feature | Secondary language for bilingual predictions | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/MultiLanguageManager.kt#MultiLanguageManager` | `src/test/kotlin/tribixbite/cleverkeys/LanguageSlotCoverageDriftTest.kt#LanguageSlotCoverageDriftTest` |
| per-language custom words | feature | Per-language custom word dictionaries | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/LanguagePreferenceKeys.kt#customWordsKey` | `src/test/kotlin/tribixbite/cleverkeys/LanguagePreferenceKeysReverseLookupTest.kt#LanguagePreferenceKeysReverseLookupTest` |
| per-language disabled words | feature | Per-language disabled words lists | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/LanguagePreferenceKeys.kt#disabledWordsKey` | `src/test/kotlin/tribixbite/cleverkeys/LanguagePreferenceKeysReverseLookupTest.kt#LanguagePreferenceKeysReverseLookupTest` |
| downloadable language packs | feature | Downloadable language packs (NL, ID, MS, SW, TL) | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/langpack/LanguagePackManager.kt#importLanguagePack` | — |
| secondary language weight slider | feature | Configurable secondary language weight slider (0.5x-1.5x) | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/Config.kt#secondary_prediction_weight` | — |
| auto language detection | feature | Auto language detection with sensitivity slider | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/LanguageDetector.kt#LanguageDetector` | `src/androidTest/kotlin/tribixbite/cleverkeys/LanguageDetectorTest.kt#testGetSupportedLanguages` |
| bilingual neural prediction | feature | Neural network predicts from BOTH primary and secondary dictionaries | REMOVED (ADR-011) | — | — |
| custom words in swipe pipeline | feature | Custom words now available in the swipe pipeline | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/swipe/ctc/CtcLexiconMerge.kt#CtcLexiconMerge` | `src/test/kotlin/tribixbite/cleverkeys/swipe/ctc/CtcLexiconMergeTest.kt#custom words come first with frequency clamped to 1-255` |
| locale-filtered user dictionary | fix | Android user dictionary filtered by locale | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/UserDictionaryObserver.kt#UserDictionaryObserver` | — |
| language-specific contractions | feature | Language-specific contraction support (French c'est, Italian l'uomo) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/ContractionManager.kt#loadLanguageContractions` | `src/test/kotlin/tribixbite/cleverkeys/swipe/SwipeContractionLanguageIsolationTest.kt#SwipeContractionLanguageIsolationTest` |

## v1.1.96 (versionCode 10196, 2026-01-06)

Multilanguage bug fixes.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| large language pack OOM | fix | Fix crash when importing large language packs (Spanish 236k words caused OOM) | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/langpack/LanguagePackManager.kt#importLanguagePack` | — |
| secondary trie insertion cap | fix | Limited secondary dictionary trie insertions to top 30k most frequent words | REMOVED (ADR-011) | — | — |
| dictionary frequency display | fix | Fix frequency display in Dictionary Manager (was showing 100 for all) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/DictionaryWord.kt#DictionaryWord` | `src/test/kotlin/tribixbite/cleverkeys/DictionaryWordTest.kt#compare sorts by frequency descending` |
| V2 binary frequency ranks | fix | Now properly reads and displays frequency ranks from V2 binary format | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/BinaryDictionaryLoader.kt#loadDictionary` | `src/test/kotlin/tribixbite/cleverkeys/DictionaryBinFormatTest.kt#englishBinary_isV2CkdtFormat` |

## v1.1.97 (versionCode 10197, 2026-01-07)

Multilanguage swipe typing, V3 dictionary.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| 11-language primary selection | feature | Primary language selection: 11 languages supported | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/MultiLanguageManager.kt#getSupportedLanguages` | `src/test/kotlin/tribixbite/cleverkeys/LanguageSlotCoverageDriftTest.kt#LanguageSlotCoverageDriftTest` |
| weighted secondary predictions | feature | Secondary language mode with weighted predictions | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/Config.kt#secondary_prediction_weight` | — |
| downloadable packs | feature | Downloadable language packs (Dutch, Indonesian, Malay, Swahili, Tagalog) | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/langpack/LanguagePackManager.kt#getInstalledPacks` | — |
| per-language custom dictionaries | feature | Per-language custom dictionaries | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/LanguagePreferenceKeys.kt#customWordsKey` | `src/test/kotlin/tribixbite/cleverkeys/LanguagePreferenceKeysReverseLookupTest.kt#LanguagePreferenceKeysReverseLookupTest` |
| V3 English dictionary | feature | V3 English Dictionary (52,042 curated words) | REMOVED (superseded by the 98,140-word dictionary in v1.5.0) | — | — |
| typo removal preserving contractions | chore | Removed common typos, preserved contractions and custom words | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/ContractionManager.kt#isKnownContraction` | `src/androidTest/kotlin/tribixbite/cleverkeys/ContractionManagerTest.kt#ContractionManagerTest` |
| OOM on large packs | fix | FIXED: OOM crash on large language packs | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/langpack/LanguagePackManager.kt#importLanguagePack` | — |
| frequency display scale | fix | FIXED: Dictionary frequency display (now 1-10000 scale) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/DictionaryWord.kt#DictionaryWord` | `src/test/kotlin/tribixbite/cleverkeys/DictionaryWordTest.kt#compare sorts by frequency descending` |
| missing contractions | fix | FIXED: Missing contractions (im to i'm, ive to i've) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/ContractionManager.kt#getNonPairedMapping` | `src/test/kotlin/tribixbite/cleverkeys/ContractionInjectionPolicyTest.kt#ContractionInjectionPolicyTest` |
| beam search trie races | fix | FIXED: Race conditions in beam search trie initialization | REMOVED (ADR-011) | — | — |

## v1.1.98 (versionCode 10198, 2026-01-08)

Per-key customization fixes.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| event commands | fix | Event commands now work (settings, clipboard, voice, numeric) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/customization/CustomShortSwipeExecutor.kt#CustomShortSwipeExecutor` | `src/test/kotlin/tribixbite/cleverkeys/customization/CustomShortSwipeExecutorTest.kt#returns false when InputConnection is null` |
| editing commands | fix | Editing commands now work (replaceText, textAssist) | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/customization/CommandRegistry.kt#replaceText` | — |
| icon characters render correctly | fix | Icon characters render correctly (was showing Chinese) | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/customization/ShortSwipeMapping.kt#ShortSwipeMapping` | — |
| custom sublabel icon sizing | fix | Custom sublabel icons match built-in icon sizes | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/Theme.kt#Theme` | — |
| README rewrite | chore | Comprehensive README update (multi-language guide, per-key docs, comparison table) | UNATTRIBUTABLE | — | — |

## v1.1.99 (versionCode 10199, 2026-01-09)

Text Assist and Replace Text fix.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| ACTION_PROCESS_TEXT dispatch | fix | Uses ACTION_PROCESS_TEXT intent instead of unsupported context menu | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/customization/CustomShortSwipeExecutor.kt#textAssist` | — |
| app chooser | feature | Shows app chooser (Google Assistant, translators, etc.) | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/customization/CustomShortSwipeExecutor.kt#CustomShortSwipeExecutor` | — |
| works with any app selection | feature | Works when text is selected in any app | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/customization/CommandRegistry.kt#textAssist` | — |
| graceful no-selection fallback | fix | Falls back gracefully if no text selected | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/customization/CustomShortSwipeExecutor.kt#showTextMenu` | — |

## v1.2.0 (versionCode 10200, 2026-01-09)

Language toggle and text menu.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| primary language toggle | feature | Primary Language Toggle: swap between two primary languages instantly | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/customization/CommandRegistry.kt#primaryLangToggle` | `src/test/kotlin/tribixbite/cleverkeys/LanguageSlotCoverageDriftTest.kt#LanguageSlotCoverageDriftTest` |
| secondary language toggle | feature | Secondary Language Toggle: swap between two secondary languages | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/customization/CommandRegistry.kt#secondaryLangToggle` | `src/test/kotlin/tribixbite/cleverkeys/LanguageSlotCoverageDriftTest.kt#LanguageSlotCoverageDriftTest` |
| assignable to any key | feature | Assign to any key's short swipe for fast switching | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/customization/ShortSwipeCustomizationManager.kt#ShortSwipeCustomizationManager` | `src/test/kotlin/tribixbite/cleverkeys/customization/ShortSwipeByKeyIndexTest.kt#ShortSwipeByKeyIndexTest` |
| show text menu | feature | Selects word at cursor and triggers the native cut/copy/paste toolbar | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/customization/CommandRegistry.kt#showTextMenu` | — |
| no-selection toast | fix | Text Assist and Replace Text now show No text selected when no selection exists | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/customization/CustomShortSwipeExecutor.kt#CustomShortSwipeExecutor` | — |

## v1.2.1 (versionCode 10201, 2026-01-09)

Contraction fix after language toggle.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| contractions after language toggle | fix | English contractions (don't, can't, won't) work after language toggle | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/ContractionManager.kt#loadTypingMappings` | `src/test/kotlin/tribixbite/cleverkeys/swipe/SwipeContractionLanguageIsolationTest.kt#SwipeContractionLanguageIsolationTest` |
| swipe contraction after FR to EN | fix | Swipe typing: dont to don't works after toggling French to English | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/swipe/ctc/CtcContractionKeys.kt#CtcContractionKeys` | `src/test/kotlin/tribixbite/cleverkeys/swipe/ctc/CtcContractionKeysTest.kt#CtcContractionKeysTest` |
| touch-typing contractions after switch | fix | Touch typing: contraction suggestions work after any language switch | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/ContractionInjectionPolicy.kt#ContractionInjectionPolicy` | `src/test/kotlin/tribixbite/cleverkeys/ContractionInjectionPolicyTest.kt#ContractionInjectionPolicyTest` |
| trie contraction-key insertion | fix | Root cause: contraction keys were not added to the vocabulary trie on language change | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/ContractionManager.kt#isContractionKey` | `src/test/kotlin/tribixbite/cleverkeys/swipe/ctc/CtcContractionRankingTest.kt#CtcContractionRankingTest` |

## v1.2.4 (versionCode 10204, 2026-01-14)

TrackPoint and selection-delete modes.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| TrackPoint mode | feature | Hold nav key to enter joystick cursor control | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt#startTrackPointRepeat` | — |
| TrackPoint diagonal + speed scaling | feature | Diagonal movement support and speed scaling with distance from centre | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt#handleTrackPointRepeat` | — |
| selection-delete mode | feature | Short swipe + hold backspace to select then delete text | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt#startSelectionDeleteRepeat` | — |
| selection-delete tuning | feature | Configurable vertical threshold and speed | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Config.kt#selection_delete_vertical_threshold` | `src/test/kotlin/tribixbite/cleverkeys/GesturePrefAccessDriftTest.kt#GesturePrefAccessDriftTest` |
| settings search | feature | Find any setting instantly with real-time filtering and keyword synonyms | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/ui/settings/SettingsSearch.kt#getFilteredSettings` | `src/test/kotlin/tribixbite/cleverkeys/SettingsSearchCoverageTest.kt#everyGeneratedEntryIsSearchable` |
| scroll-to-setting pulse | feature | Scrolls to setting with pulse highlight | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/ui/settings/SettingsSearch.kt#scrollToSetting` | `src/androidTest/kotlin/tribixbite/cleverkeys/SettingsSearchTest.kt#SettingsSearchTest` |
| granular haptic feedback | feature | Per-event haptic controls (key press, predictions, TrackPoint) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/VibratorCompat.kt#VibratorCompat` | `src/test/kotlin/tribixbite/cleverkeys/HapticsBehaviorDriftTest.kt#accessibilitySection_masterToggle_doesNotForce_vibrateCustom` |
| short swipe calibration | feature | Practice and tune gestures | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/activities/ShortSwipeCalibrationActivity.kt#ShortSwipeCalibrationActivity` | `src/androidTest/kotlin/tribixbite/cleverkeys/ShortSwipeCalibrationActivityComposeTest.kt#ShortSwipeCalibrationActivityComposeTest` |
| auto-capitalization after periods | fix | Auto-capitalization now works after periods | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Autocapitalisation.kt#Autocapitalisation` | `src/test/kotlin/tribixbite/cleverkeys/AutocapitalisationTest.kt#AutocapitalisationTest` |
| double-space-to-period toggle | feature | Double-space-to-period toggle added | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/KeyEventHandler.kt#doubleSpaceThresholdMs` | `src/test/kotlin/tribixbite/cleverkeys/backup/SettingsDefaultsDriftTest.kt#everyPrefReadKeyIsClassified` |
| US QWERTY subkeys | chore | Updated US QWERTY layout subkeys | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/LayoutManager.kt#LayoutManager` | — |
| suggestion selection returns wrong word | fix | Fixed suggestion selection returning wrong word (issue #63) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/SuggestionBridge.kt#SuggestionBridge` | `src/test/kotlin/tribixbite/cleverkeys/Issue78SuggestionReplaceTest.kt#BUG A — Termux tap suggestion REPLACES typed prefix not appends` |
| WordPredictor language-pack dictionaries | fix | Fixed WordPredictor not loading language pack dictionaries | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/BinaryDictionaryLoader.kt#loadDictionaryWithPrefixIndexFromFile` | `src/test/kotlin/tribixbite/cleverkeys/DictionaryBinFormatTest.kt#everyBundledBinary_isV2CkdtWithPlausibleWordCount` |

## v1.2.5 (versionCode 10205, 2026-01-15)

Language-pack autocorrect fix. Re-publishes the v1.2.4 feature list "since v1.2.2" and adds a
layout note; every re-published claim is recorded again so this section stands alone.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| US QWERTY subkey repositioning | chore | LAYOUT NOTE: default US QWERTY moves some subkeys to the perimeter to reduce short-swipe/word-swipe conflicts; the Julow layout restores the classic arrangement | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/LayoutManager.kt#LayoutManager` | — |
| TrackPoint mode | feature | Hold nav key to enter joystick cursor control (re-published from v1.2.4) | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt#startTrackPointRepeat` | — |
| selection-delete mode | feature | Short swipe + hold backspace to select then delete (re-published from v1.2.4) | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt#startSelectionDeleteRepeat` | — |
| settings search | feature | Find any setting instantly (re-published from v1.2.4) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/ui/settings/SettingsSearch.kt#getFilteredSettings` | `src/test/kotlin/tribixbite/cleverkeys/SettingsSearchCoverageTest.kt#everyGeneratedEntryIsSearchable` |
| granular haptic feedback | feature | Per-event haptic controls (re-published from v1.2.4) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/VibratorCompat.kt#VibratorCompat` | `src/test/kotlin/tribixbite/cleverkeys/HapticsBehaviorDriftTest.kt#accessibilitySection_masterToggle_doesNotForce_vibrateCustom` |
| short swipe calibration | feature | Practice and tune gestures (re-published from v1.2.4) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/activities/ShortSwipeCalibrationActivity.kt#ShortSwipeCalibrationActivity` | `src/androidTest/kotlin/tribixbite/cleverkeys/ShortSwipeCalibrationActivityComposeTest.kt#ShortSwipeCalibrationActivityComposeTest` |
| auto-capitalization after periods | fix | Auto-capitalization works after periods (re-published from v1.2.4) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Autocapitalisation.kt#Autocapitalisation` | `src/test/kotlin/tribixbite/cleverkeys/AutocapitalisationTest.kt#AutocapitalisationTest` |
| double-space-to-period toggle | feature | Double-space-to-period toggle (re-published from v1.2.4) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/KeyEventHandler.kt#doubleSpaceThresholdMs` | `src/test/kotlin/tribixbite/cleverkeys/backup/SettingsDefaultsDriftTest.kt#everyPrefReadKeyIsClassified` |
| suggestion selection returns wrong word | fix | Fixed suggestion selection returning wrong word (issue #63) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/SuggestionBridge.kt#SuggestionBridge` | `src/test/kotlin/tribixbite/cleverkeys/Issue78SuggestionReplaceTest.kt#BUG A — Termux tap suggestion REPLACES typed prefix not appends` |
| language-pack dictionary loading | fix | Fixed WordPredictor not loading language pack dictionaries — the release's headline fix | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/BinaryDictionaryLoader.kt#loadDictionaryWithPrefixIndexFromFile` | `src/test/kotlin/tribixbite/cleverkeys/DictionaryBinFormatTest.kt#everyBundledBinary_isV2CkdtWithPlausibleWordCount` |

## v1.2.6 (versionCode 10206, 2026-01-21)

Major feature update. Tagged but never published as a GitHub release; the fastlane changelog is
the only channel that carried these notes, and v1.2.8 re-published them.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| emoji long-press tooltip | feature | See emoji names on long-press; 260+ flag names, 100+ emoticon names, Unicode fallback | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/emoji/EmojiTooltipManager.kt#EmojiTooltipManager` | `src/androidTest/kotlin/tribixbite/cleverkeys/EmoticonsTest.kt#testEmoticonsGroupExists` |
| emoticons category | feature | 119 text emoticons in the emoji picker, searchable (shrug, lenny, tableflip) (#76) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/emoji/Emoji.kt#isEmoticon` | `src/androidTest/kotlin/tribixbite/cleverkeys/EmoticonsTest.kt#testEmoticonsGroupHasExpectedSize` |
| emoji search | feature | Search 500+ emoji by name, context-aware, rendered in the suggestion bar (#41) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/emoji/EmojiKeywordIndex.kt#search` | `src/androidTest/kotlin/tribixbite/cleverkeys/EmojiSearchTest.kt#testSearchReturnsResults` |
| Swedish, Greek, Turkish packs | feature | Language packs added — sv ships bundled, el and tr as importable packs | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/langpack/LanguagePackManager.kt#importLanguagePack` | — |
| swipe sensitivity presets | feature | Low/Medium/High/Custom options | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/ui/settings/SettingsResetPresets.kt#applySwipeSensitivityPreset` | `src/test/kotlin/tribixbite/cleverkeys/GesturePrefAccessDriftTest.kt#GesturePrefAccessDriftTest` |
| settings search expansion | feature | Expanded from 38 to 120+ searchable entries | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/ui/settings/SettingsSearch.kt#SearchableSetting` | `src/test/kotlin/tribixbite/cleverkeys/SettingsSearchCoverageTest.kt#everyLiteralControlHasAGeneratedEntry` |
| quick settings tile | feature | Switch keyboards from the notification shade (#1113) | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/KeyboardTileService.kt#KeyboardTileService` | — |
| timestamp keys | feature | Insert formatted date/time with shortcuts (#1103) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/KeyValue.kt#makeTimestampKey` | `src/test/kotlin/tribixbite/cleverkeys/customization/CustomShortSwipeExecutorTest.kt#executes TIMESTAMP and commits formatted current date` |
| tap-to-add dictionary | feature | Add typed words with a single tap (#42) | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/DictionaryManager.kt#DictionaryManager` | — |
| clipboard entry delete button | feature | Delete button for history entries (#940) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardHistoryView.kt#ClipboardHistoryView` | `src/androidTest/kotlin/tribixbite/cleverkeys/ClipboardFeatureTest.kt#copySemantics_deleteFromHistoryDoesNotAffectPinnedOrTodo` |
| password manager exclusion | feature | Clipboard skips password-manager copies (#62) | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardHistoryService.kt#clipboard_exclude_password_managers` | — |
| test keyboard field | feature | Practice typing inside settings (#1134) | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/ui/settings/sections/TestKeyboardSection.kt#TestKeyboardSection` | — |
| larger numpad keys | feature | Numpad/PIN keyboard keys 20% larger (#58) | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/Theme.kt#Theme` | — |
| dictionary manager sort | feature | Sort by Frequency/Match/A-Z/Z-A | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/activities/DictionaryManagerActivity.kt#DictionaryManagerActivity` | — |
| cursor-aware predictions | feature | Better mid-word editing | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/PredictionContextTracker.kt#onCursorPositionChanged` | `src/androidTest/kotlin/tribixbite/cleverkeys/ContractionFlickerTest.kt#expectingSelectionUpdate_defaultsFalse` |
| system theme following | feature | Auto dark/light mode (#35) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/theme/CleverKeysTheme.kt#CleverKeysTheme` | `src/androidTest/kotlin/tribixbite/cleverkeys/CustomThemeBackgroundTest.kt#testLightColorSchemeHasNonTransparentBackground` |
| auto-capitalize I and contractions | fix | Auto-capitalize I and contractions (#72) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Autocapitalisation.kt#Autocapitalisation` | `src/androidTest/kotlin/tribixbite/cleverkeys/AutocapitalizationTest.kt#AutocapitalizationTest` |
| proper-noun case preserved | fix | Preserve proper noun case in dictionary (#72) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/DictionaryManager.kt#DictionaryManager` | `src/androidTest/kotlin/tribixbite/cleverkeys/DictionaryManagerTest.kt#testProperNounCasePreserved` |
| clipboard TransactionTooLargeException | fix | Clipboard TransactionTooLargeException (#71) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardHistoryService.kt#ClipboardHistoryService` | `src/test/kotlin/tribixbite/cleverkeys/ClipboardPaginationTest.kt#101 items = 2 pages` |
| vibration toggle disables haptics | fix | Vibration toggle now properly disables haptics (#46) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/VibratorCompat.kt#VibratorCompat` | `src/test/kotlin/tribixbite/cleverkeys/HapticsBehaviorDriftTest.kt#updateConfigFromSettings_doesNotMap_vibrationEnabled_to_vibrateCustom` |
| space key with selection | fix | Space key types a space when text is selected (#1142) | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/KeyModifier.kt#KeyModifier` | — |
| nav bar icons on Android 8-9 | fix | Nav bar icons on Android 8-9 light themes (#1116) | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/Keyboard2View.kt#Keyboard2View` | — |
| Monet theme crash below API 31 | fix | Monet theme crash on Android < 12 (#1107) | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/theme/CleverKeysTheme.kt#dynamicColor` | — |
| nav bar overlap on Android 15 | fix | Nav bar overlap on Android 15 | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/WindowLayoutUtils.kt#configureEdgeToEdge` | — |
| emoji/clipboard panel gap | fix | Emoji/clipboard panel gap eliminated | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/emoji/EmojiGridView.kt#EmojiGridView` | — |
| panels empty after app switch | fix | Panels no longer empty after app switch | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/emoji/EmojiSearchManager.kt#onPaneOpened` | `src/test/kotlin/tribixbite/cleverkeys/EmojiKeywordIndexLifecycleTest.kt#EmojiKeywordIndexLifecycleTest` |

## v1.2.8 (versionCode 10208, 2026-01-22)

Major feature update since v1.2.5 — 200+ commits. Re-publishes the v1.2.6 list and adds the
items below; the repeated claims are recorded again so this section stands alone.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| emoji long-press tooltip | feature | See emoji names on long-press (re-published from v1.2.6) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/emoji/EmojiTooltipManager.kt#show` | `src/androidTest/kotlin/tribixbite/cleverkeys/EmoticonsTest.kt#testEmoticonsGroupExists` |
| emoticons category | feature | 119 text emoticons, searchable (#76) (re-published from v1.2.6) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/emoji/Emoji.kt#isEmoticon` | `src/androidTest/kotlin/tribixbite/cleverkeys/EmoticonsTest.kt#testEmoticonsGroupHasExpectedSize` |
| emoji search | feature | Search 500+ emoji by name with context-aware results (#41) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/emoji/EmojiKeywordIndex.kt#search` | `src/androidTest/kotlin/tribixbite/cleverkeys/EmojiSearchTest.kt#testSearchIsCaseInsensitive` |
| Swedish, Greek, Turkish packs | feature | Language packs added (re-published from v1.2.6) | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/langpack/LanguagePackManager.kt#getInstalledPacks` | — |
| swipe sensitivity presets | feature | Low/Medium/High/Custom (re-published from v1.2.6) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/ui/settings/SettingsResetPresets.kt#getSwipeSensitivityPreset` | `src/test/kotlin/tribixbite/cleverkeys/GesturePrefAccessDriftTest.kt#GesturePrefAccessDriftTest` |
| auto-capitalize I and contractions | feature | Auto-capitalize I and contractions (#72) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Autocapitalisation.kt#Autocapitalisation` | `src/test/kotlin/tribixbite/cleverkeys/AutocapitalisationCallbackDedupeTest.kt#AutocapitalisationCallbackDedupeTest` |
| cursor-aware predictions | feature | Better mid-word editing (re-published from v1.2.6) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/PredictionContextTracker.kt#getCurrentWord` | `src/androidTest/kotlin/tribixbite/cleverkeys/ContractionFlickerTest.kt#expectingSelectionUpdate_defaultsFalse` |
| swipe on password fields | feature | Optional swipe predictions in password fields (#39) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/SuggestionBar.kt#allowSwipeInPasswordMode` | `src/androidTest/kotlin/tribixbite/cleverkeys/SuggestionBarAutofillTest.kt#passwordMode_removesPadding` |
| smart punctuation | feature | Respects a manually typed spacebar | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/SmartAutoSpace.kt#isSwallowEligible` | `src/test/kotlin/tribixbite/cleverkeys/SmartAutoSpaceLogicTest.kt#unambiguous openers suppress leading space regardless of preceding char` |
| swipe capitalization at gesture start | feature | Captures the shift state at swipe START | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/SwipeInput.kt#SwipeInput` | — |
| settings search expansion | feature | Expanded to 120+ searchable entries | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/ui/settings/SettingsSearch.kt#getFilteredSettings` | `src/test/kotlin/tribixbite/cleverkeys/SettingsSearchCoverageTest.kt#everyControlNameWordIsSearchable` |
| quick settings tile | feature | Switch keyboards from the shade | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/KeyboardTileService.kt#KeyboardTileService` | — |
| test keyboard field | feature | Practice typing in settings | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/ui/settings/sections/TestKeyboardSection.kt#TestKeyboardSection` | — |
| system theme following | feature | Auto dark/light mode (#35) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/theme/CleverKeysTheme.kt#CleverKeysTheme` | `src/androidTest/kotlin/tribixbite/cleverkeys/CustomThemeBackgroundTest.kt#testDarkColorSchemeHasNonTransparentBackground` |
| larger numpad keys | feature | Numpad/PIN keyboard keys 20% larger (#58) | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/Theme.kt#Theme` | — |
| haptics moved to Accessibility | chore | Haptic feedback moved to the Accessibility section with per-event toggles and SWIPE_COMPLETE vibration | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/ui/settings/sections/AccessibilitySection.kt#AccessibilitySection` | `src/test/kotlin/tribixbite/cleverkeys/HapticsBehaviorDriftTest.kt#accessibilitySection_durationSlider_setsVibrateCustom_whenDragged` |
| clipboard tabs | feature | History, Pinned and Todos tabs with icons | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/clipboard/PinnedEntry.kt#PinnedEntry` | — |
| panel close buttons | feature | Close buttons for the emoji and clipboard panes (#80) | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/emoji/EmojiSearchManager.kt#setOnCloseCallback` | — |
| clipboard pagination | feature | 100 items per page with search | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardHistoryView.kt#ClipboardHistoryView` | `src/test/kotlin/tribixbite/cleverkeys/ClipboardPaginationTest.kt#99 items = 1 page` |
| history limit slider | feature | 0-500 entries, 0 means unlimited (#85) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Config.kt#set_clipboard_history_limit` | `src/test/kotlin/tribixbite/cleverkeys/ClipboardFixesJvmTest.kt#load 0 sentinel maps to slider 100` |
| tap-to-add dictionary | feature | Single tap to add words (#42) | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/DictionaryManager.kt#DictionaryManager` | — |
| clipboard delete button | feature | Remove individual entries | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardDatabase.kt#ClipboardDatabase` | `src/androidTest/kotlin/tribixbite/cleverkeys/ClipboardFeatureTest.kt#copySemantics_deleteFromHistoryDoesNotAffectPinnedOrTodo` |
| password manager exclusion | feature | Password manager exclusion (#62, #86) | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardHistoryService.kt#clipboard_exclude_password_managers` | — |
| Android 13+ IS_SENSITIVE flag | feature | Respect the IS_SENSITIVE clip flag (#86) | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardHistoryService.kt#isSensitive` | — |
| dictionary manager sort | feature | Sort by Frequency/Match/A-Z/Z-A | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/activities/DictionaryManagerActivity.kt#DictionaryManagerActivity` | — |
| timestamp keys | feature | Insert formatted date/time | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/KeyValue.kt#makeTimestampKey` | `src/test/kotlin/tribixbite/cleverkeys/customization/ShortSwipeMappingTest.kt#isValidTimestampPattern accepts yyyy-MM-dd` |
| disable auto-space after suggestion | feature | Option to disable auto-space after suggestion (#82) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Config.kt#auto_space_after_suggestion` | `src/test/kotlin/tribixbite/cleverkeys/AutoSpaceLogicTest.kt#AutoSpaceLogicTest` |
| separate backspace repeat option | feature | Separate backspace key repeat option (#81) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/KeyEventHandler.kt#KeyEventHandler` | `src/test/kotlin/tribixbite/cleverkeys/KeyRepeatLogicTest.kt#keyrepeat backspace only is enabled by default` |
| capitalize I words for swipe | fix | Capitalize I words for swipe (#72) | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/SuggestionHandler.kt#SuggestionHandler` | — |
| proper-noun case preserved | fix | Preserve proper noun case in dictionary (#72) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/DictionaryManager.kt#DictionaryManager` | `src/androidTest/kotlin/tribixbite/cleverkeys/DictionaryManagerTest.kt#testProperNounCasePreserved` |
| swipe capitalization after period | fix | Swipe capitalization after a period | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Autocapitalisation.kt#Autocapitalisation` | `src/androidTest/kotlin/tribixbite/cleverkeys/AutocapitalizationTest.kt#AutocapitalizationTest` |
| vibration not triggering | fix | Vibration not triggering (vibrate_custom fix) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/VibratorCompat.kt#VibratorCompat` | `src/test/kotlin/tribixbite/cleverkeys/HapticsBehaviorDriftTest.kt#migration_clears_bugForced_vibrateCustom` |
| settings toggles update Config immediately | fix | Settings toggles update Config immediately | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/ConfigPropagator.kt#ConfigPropagator` | `src/androidTest/kotlin/tribixbite/cleverkeys/SettingsToggleTest.kt#SettingsToggleTest` |
| Greek/Math disabled in numeric layer | fix | Greek/Math disabled in the numeric layer unless enabled in extra keys (#77) | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/KeyModifier.kt#switch_greekmath` | — |
| clipboard TransactionTooLargeException | fix | Clipboard TransactionTooLargeException (#71) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardHistoryService.kt#ClipboardHistoryService` | `src/test/kotlin/tribixbite/cleverkeys/ClipboardPaginationTest.kt#0 items = 1 page` |
| vibration toggle disables haptics | fix | Vibration toggle properly disables haptics (#46) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/VibratorCompat.kt#VibratorCompat` | `src/test/kotlin/tribixbite/cleverkeys/VibratorCompatTest.kt#VibratorCompatTest` |
| space key with selection | fix | Space key types a space when text is selected | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/KeyModifier.kt#KeyModifier` | — |
| nav bar icons on Android 8-9 | fix | Nav bar icons on Android 8-9 light themes | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/Keyboard2View.kt#Keyboard2View` | — |
| Monet theme crash below API 31 | fix | Monet theme crash on Android < 12 | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/theme/CleverKeysTheme.kt#dynamicColor` | — |
| nav bar overlap on Android 15 | fix | Nav bar overlap on Android 15 | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/WindowLayoutUtils.kt#configureEdgeToEdge` | — |
| emoji/clipboard panel gap | fix | Emoji/clipboard panel gap eliminated | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/emoji/EmojiGridView.kt#EmojiGridView` | — |
| panels empty after app switch | fix | Panels no longer empty after app switch | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/emoji/EmojiSearchManager.kt#onPaneClosed` | `src/test/kotlin/tribixbite/cleverkeys/EmojiKeywordIndexLifecycleTest.kt#EmojiKeywordIndexLifecycleTest` |
| splash animation pauses | fix | Splash animation pauses when the keyboard opens | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/activities/LauncherActivity.kt#LauncherActivity` | — |

## v1.2.9 (versionCode 10209, 2026-01-28)

Performance and polish. The last release before the neural engine was deleted; its whole
"Neural Network Optimization" block went with ADR-011.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| beam-search tensor reuse | chore | Tensor reuse in beam search reduces memory allocations by 90% | REMOVED (ADR-011) | — | — |
| configurable XNNPACK threads | feature | XNNPACK thread count now user-configurable (1-8 threads) | REMOVED (ADR-011) | — | — |
| batched beam decoding toggle | feature | Batched beam decoding toggle for advanced users | REMOVED (ADR-011) | — | — |
| ONNX native memory leak | fix | Fixed native memory leak in ONNX inference | REMOVED (ADR-011) | — | — |
| launcher gestures box | feature | Third setup step guides per-key calibration | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/activities/LauncherActivity.kt#LauncherActivity` | — |
| in-app help and FAQ | feature | Searchable FAQ section in Settings | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/ui/settings/sections/HelpSection.kt#HelpSection` | `src/test/kotlin/tribixbite/cleverkeys/SettingsSearchCoverageTest.kt#advancedPanelSlugSetMatchesThePanelContents` |
| ONNX threads setting | feature | Fine-tune inference performance | REMOVED (ADR-011) | — | — |
| backup and reset for neural settings | feature | Backup and Reset support for neural settings | REMOVED (ADR-011) | — | — |
| French contraction frequency | fix | qu'est now ranks correctly against quest | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/ContractionCollisionDemotion.kt#demote` | `src/test/kotlin/tribixbite/cleverkeys/ContractionCollisionDemotionTest.kt#ContractionCollisionDemotionTest` |
| short gesture max distance restored | fix | Short gesture max distance check restored (was accidentally removed) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Config.kt#short_gesture_max_distance` | `src/test/kotlin/tribixbite/cleverkeys/GesturePrefAccessDriftTest.kt#GesturePrefAccessDriftTest` |
| AndroidX preference migration | chore | Full AndroidX migration: ExtraKeysPreference, ListGroupPreference | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/prefs/ExtraKeysPreference.kt#ExtraKeysPreference` | — |
| wiki audit | chore | All 69 wiki pages audited and verified against source code | UNATTRIBUTABLE | — | — |
| settings-path corrections | chore | Fixed 40+ incorrect settings paths and fabricated features in the docs | UNATTRIBUTABLE | — | — |
| FAQ content verification | chore | FAQ content verified against actual code behavior | UNATTRIBUTABLE | — | — |

## v1.3.0 (versionCode 10300, 2026-03-16)

GIF panel and prediction fixes.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| offline GIF panel | feature | Search, categories and pack import, fully offline | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/gif/GifDatabase.kt#GifDatabase` | `src/test/kotlin/tribixbite/cleverkeys/gif/GifTest.kt#default construction uses sensible defaults` |
| GIF categories | feature | Category browsing in the GIF panel | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/gif/GifCategory.kt#GifCategory` | `src/test/kotlin/tribixbite/cleverkeys/gif/GifCategoryTest.kt#GifCategoryTest` |
| GIF pack import | feature | Import GIF packs from a file picker — no network | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/gif/GifPackManager.kt#getInstalledPacks` | `src/test/kotlin/tribixbite/cleverkeys/gif/GifPackThumbnailValidationTest.kt#GifPackThumbnailValidationTest` |
| backspace undo autocorrect | feature | Backspace undoes an autocorrection (#110) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Config.kt#backspace_undo_autocorrect` | `src/test/kotlin/tribixbite/cleverkeys/BackspaceUndoTest.kt#BACKSPACE_UNDO_AUTOCORRECT default exists and is true` |
| auto space before suggestion toggle | feature | Auto space before suggestion toggle (#82) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Config.kt#auto_space_before_suggestion` | `src/test/kotlin/tribixbite/cleverkeys/AutoSpaceLogicTest.kt#AutoSpaceLogicTest` |
| swipe disabled on non-QWERTY layouts | feature | Swipe auto-disabled on non-QWERTY layouts (#9) | REMOVED (superseded in v1.6.0 — the CTC encoder is layout-agnostic, so Latin non-QWERTY layouts swipe again) | — | — |
| contraction flicker | fix | Contraction flicker fix (pipeline symmetry) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/PredictionContextTracker.kt#invalidateAutoSpacePending` | `src/androidTest/kotlin/tribixbite/cleverkeys/ContractionFlickerTest.kt#expectingSelectionUpdate_suppressesSynchronizeWithCursor` |
| backspace undo swipe | fix | Backspace undo of a swipe was broken (#110) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Config.kt#backspace_undo_swipe` | `src/test/kotlin/tribixbite/cleverkeys/BackspaceUndoTest.kt#BACKSPACE_UNDO_SWIPE default exists and is true` |
| clipboard dedup | fix | Duplicate copies move to the top instead of being ignored (#108) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardDatabase.kt#ClipboardDatabase` | `src/androidTest/kotlin/tribixbite/cleverkeys/ClipboardDatabaseTest.kt#testDuplicateEntryMovedToTop` |
| terminal paste | fix | Paste works in terminal emulators (#113) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/TerminalUtils.kt#isTerminalApp` | `src/androidTest/kotlin/tribixbite/cleverkeys/TerminalUtilsInstrumentedTest.kt#isTerminalApp_nullEditorInfo_returnsFalse` |
| autofill cutoff | fix | Inline autofill suggestions no longer cut off (#109) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/autofill/InlineAutofillUtils.kt#createInlineSuggestionsRequest` | `src/androidTest/kotlin/tribixbite/cleverkeys/SuggestionBarAutofillTest.kt#normalMode_hasPadding` |
| theme background | fix | Custom theme background fix (#92) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/theme/CustomThemeManager.kt#CustomThemeManager` | `src/androidTest/kotlin/tribixbite/cleverkeys/CustomThemeBackgroundTest.kt#testDarkColorSchemeHasNonTransparentBackground` |

## v1.4.0 (versionCode 10400, 2026-04-26)

Media clipboard and tabs overhaul.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| copy/paste media | feature | Copy and paste images, video and PDFs | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardMediaManager.kt#ClipboardMediaManager` | `src/androidTest/kotlin/tribixbite/cleverkeys/ClipboardMediaDatabaseTest.kt#testAddMediaEntry` |
| pinned and todo tables | feature | Pinned and Todos get their own tables with tags and a status cycle | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/clipboard/TodoEntry.kt#TodoEntry` | `src/androidTest/kotlin/tribixbite/cleverkeys/ClipboardDatabaseV5MigrationTest.kt#ClipboardDatabaseV5MigrationTest` |
| inline edit for text entries | feature | Edit a text entry in place | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardDatabase.kt#ClipboardDatabase` | `src/test/kotlin/tribixbite/cleverkeys/ClipboardEditJvmTest.kt#EditEntryResult Success is singleton` |
| regex search | feature | Regex search with * and ? globs | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardSearchUtils.kt#expandGlobShorthand` | `src/test/kotlin/tribixbite/cleverkeys/ClipboardSearchRegexTest.kt#bare star becomes dot-star wildcard` |
| per-tab filters | feature | Tags, status and match mode per tab | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardTagDialog.kt#ClipboardTagPanel` | `src/androidTest/kotlin/tribixbite/cleverkeys/ClipboardFilterDialogTest.kt#filterDialog_inflatesAndMeasures_underProductionDialogTheme` |
| tab visibility toggles | feature | Toggles for text-only, Pinned and Todos tabs | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/ui/settings/sections/ClipboardSection.kt#ClipboardSection` | `src/test/kotlin/tribixbite/cleverkeys/backup/SettingsDefaultsDriftTest.kt#everyPrefReadKeyIsClassified` |
| clipboard freeze on open | fix | Clipboard freeze on open (#71) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardHistoryView.kt#ClipboardHistoryView` | `src/test/kotlin/tribixbite/cleverkeys/ClipboardPaginationTest.kt#1 item = 1 page` |
| never-expire duration honoured | fix | Duration Never expire is honoured | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/ui/settings/sections/ClipboardSection.kt#ClipboardSection` | `src/test/kotlin/tribixbite/cleverkeys/ClipboardFixesJvmTest.kt#load negative value maps to slider 100` |
| 1MB Binder cap | fix | A 1MB cap prevents Binder crashes | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardMediaManager.kt#DEFAULT_MAX_MEDIA_BYTES` | `src/test/kotlin/tribixbite/cleverkeys/ClipboardMediaManagerZipSlipTest.kt#ClipboardMediaManagerZipSlipTest` |
| scoped-storage import | fix | Scoped-storage import (#70) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/BackupRestoreManager.kt#BackupRestoreManager` | `src/test/kotlin/tribixbite/cleverkeys/backup/HeadlessPayloadLimitsTest.kt#HeadlessPayloadLimitsTest` |

## v1.5.0 (versionCode 10500, 2026-07-15)

Smarter autocorrect and the 98k dictionary.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| 98,140-word English dictionary | feature | 98,140-word English dictionary, up from 52k | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/BinaryDictionaryLoader.kt#loadDictionaryWithPrefixIndex` | `src/test/kotlin/tribixbite/cleverkeys/DictionaryBinFormatTest.kt#everyBundledBinary_isV2CkdtWithPlausibleWordCount` |
| smart spacing around quotes | feature | Smart spacing around quotes and punctuation | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/SmartAutoSpace.kt#isOpeningPunctuation` | `src/test/kotlin/tribixbite/cleverkeys/SmartAutoSpaceLogicTest.kt#closing punctuation before cursor still gets a leading space` |
| URL tracker cleaner | feature | URL cleaner strips trackers from links | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/clipboard/sanitize/UrlSanitizer.kt#UrlSanitizer` | `src/test/kotlin/tribixbite/cleverkeys/clipboard/sanitize/UrlSanitizerTest.kt#globalRules_stripsTrackingParam` |
| one-tap full backup ZIP | feature | Full backup ZIP in one tap (#142) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/BackupRestoreManager.kt#BackupRestoreManager` | `src/test/kotlin/tribixbite/cleverkeys/BackupRestoreFullBackupTest.kt#headlessMandatory_plaintextZipImport_isRejectedAtManagerSeam` |
| import preview | feature | Import preview lets you choose what to restore | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/backup/SettingsImportPlanBuilder.kt#SettingsImportPlanBuilder` | `src/test/kotlin/tribixbite/cleverkeys/backup/SettingsImportPlanBuilderTest.kt#modifiedKey_intValueChanged` |
| light mode follows system theme | feature | Light mode follows the system theme (#35) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/theme/CleverKeysTheme.kt#CleverKeysTheme` | `src/androidTest/kotlin/tribixbite/cleverkeys/CustomThemeBackgroundTest.kt#testLightColorSchemeHasNonTransparentBackground` |
| autocorrect possessives and plurals | fix | Autocorrect handles possessives and plurals | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/autocorrect/Morphology.kt#Morphology` | `src/test/kotlin/tribixbite/cleverkeys/autocorrect/MorphologyTest.kt#plural_ies_yieldsYStem` |
| autocorrect leaves URLs alone | fix | Autocorrect no longer mangles URLs | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/autocorrect/AutocorrectContextGuard.kt#AutocorrectContextGuard` | `src/androidTest/kotlin/tribixbite/cleverkeys/AutocorrectUrlGuardTest.kt#urlToken_notAutocorrected` |
| suggestion tap in URL bars | fix | Suggestion tap in URL bars (#151) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/SuggestionBridge.kt#SuggestionBridge` | `src/androidTest/kotlin/tribixbite/cleverkeys/Issue151UrlBarSuggestionTapTest.kt#urlBar_bareToken_tapReplacesWholeToken` |
| instant keypress haptics | fix | Instant keypress haptics (#154) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/VibratorCompat.kt#VibratorCompat` | `src/test/kotlin/tribixbite/cleverkeys/HapticsBehaviorDriftTest.kt#accessibilitySection_durationSlider_setsVibrateCustom_whenDragged` |
| swipe overshoot | fix | Swipe overshoot fixed | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt#Pointers` | `src/androidTest/kotlin/tribixbite/cleverkeys/PointersGestureRoutingTest.kt#overshoot_towardAssignedSubkey_emitsSubkey_notWord` |
| settings search completeness | fix | Settings search finds everything | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/ui/settings/SettingsSearch.kt#getFilteredSettings` | `src/test/kotlin/tribixbite/cleverkeys/SettingsSearchCoverageTest.kt#everyLiteralControlHasAGeneratedEntry` |

## v1.6.0 (versionCode 10600, unreleased)

Swipe and privacy. The fastlane changelogs are written but the tag does not exist yet, so this
section is still editable — it is listed in `ReleaseRecordDriftTest.PENDING_RELEASES` and is not
hash-pinned. Tagging v1.6.0 freezes it.

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| CTC is the default engine | feature | CTC is the default: en/fr/de/es validated, it/pt/sv provisional | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/swipe/ctc/CtcLanguageSupport.kt#CtcLanguageSupport` | `src/test/kotlin/tribixbite/cleverkeys/ReleaseMetadataDriftTest.kt#releaseChannelsAgreeWithRuntimeCtcPolicy` |
| geometric fallback | feature | Other languages and layouts use the geometric fallback | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/swipe/SwipeEngineRouter.kt#SwipeEngineRouter` | `src/test/kotlin/tribixbite/cleverkeys/swipe/SwipeEngineRouterTest.kt#qwerty routes ctc in ctc mode` |
| English top-1 accuracy | feature | English top-1: 89.3% vs 74.6% on 2,400 swipes | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/swipe/CtcEngineAdapter.kt#CtcEngineAdapter` | `src/test/kotlin/tribixbite/cleverkeys/swipe/geometric/CtcVsGeoLocalCorpusTest.kt#headToHead_localCombinedCorpus` |
| learn from my typing | feature | On-device learning from what you type | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/LearningGate.kt#LearningGate` | `src/test/kotlin/tribixbite/cleverkeys/LearningGateTest.kt#context learning requires master AND feature gate` |
| next-word prediction | feature | Next-word prediction from the context model | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/NextWordPredictor.kt#NextWordPredictor` | `src/test/kotlin/tribixbite/cleverkeys/NextWordPredictorTest.kt#disabled feature never shows - the opt-in default` |
| private copy | feature | Private copy bypasses the OS clipboard | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/clipboard/PrivateCopyDispatch.kt#PrivateCopyDispatch` | `src/test/kotlin/tribixbite/cleverkeys/PrivateCopyServiceTest.kt#privateCopy_delegatesToAddPrivateClip_withTextAndProvenance` |
| encrypted backups | feature | Encrypted backup archives | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/backup/crypto/BackupCrypto.kt#BackupCrypto` | `src/test/kotlin/tribixbite/cleverkeys/backup/crypto/BackupCryptoRoundTripTest.kt#roundTripsUtf8JsonWithEmojiByteExact` |
| TalkBack support | feature | TalkBack support for the custom keyboard | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/a11y/KeyboardAccessibilityHelper.kt#KeyboardAccessibilityHelper` | `src/test/kotlin/tribixbite/cleverkeys/a11y/KeyLabelsTest.kt#everyDefaultSpecialKeyIsNonEmptyAndNonPua` |
| bounded rollback-safe restore | fix | Bounded, rollback-safe backup restore | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/backup/HeadlessPayloadLimits.kt#HeadlessPayloadLimits` | `src/test/kotlin/tribixbite/cleverkeys/BackupRestoreDbFailureTest.kt#clipboardZipImport_dbFailure_restoresExistingMedia_deletesNewMedia_andClearsStaging` |
| n-gram rollback | fix | Learned n-gram stores roll back cleanly | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/contextaware/BigramStore.kt#BigramStore` | `src/test/kotlin/tribixbite/cleverkeys/contextaware/NgramRollbackTest.kt#unrecord decrements frequency and renormalizes siblings` |
| dual-language CTC | fix | CTC decodes against a merged two-language lexicon | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/swipe/ctc/CtcLexiconMerge.kt#CtcLexiconMerge` | `src/test/kotlin/tribixbite/cleverkeys/swipe/ctc/CtcLexiconMergeTest.kt#base frequency is floored to 1` |
| fuzzy rescue | fix | Fuzzy rescue recovers the nearest dictionary surface | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/swipe/ctc/CtcFuzzyRescue.kt#CtcFuzzyRescue` | `src/test/kotlin/tribixbite/cleverkeys/swipe/ctc/CtcFuzzyRescueTest.kt#recoversNearestDictionarySurface` |
| non-ASCII Latin letters | fix | ß, œ, æ and ø project onto the a-z encoder alphabet | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/swipe/ctc/CtcAzProjection.kt#CtcAzProjection` | `src/test/kotlin/tribixbite/cleverkeys/swipe/ctc/CtcScriptProjectionTest.kt#russian keeps short i, which NFD would destroy` |
| learning isolation | fix | Learned data stays isolated per language and per gate | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/persist/SharedPrefsLearnedStorage.kt#SharedPrefsLearnedStorage` | `src/test/kotlin/tribixbite/cleverkeys/OnDeviceLearningPrivacyTest.kt#master off - nothing recorded in RAM and nothing persisted` |
| selection history | fix | Selection history boosts only above its activation floor | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/SelectionHistory.kt#SelectionHistory` | `src/test/kotlin/tribixbite/cleverkeys/SelectionHistoryTest.kt#multiplier is neutral below the activation floor and boosts above it` |
| leaks and crashes | fix | Executor and coordinator teardown leaks closed | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/CleanupHandler.kt#CleanupHandler` | `src/test/kotlin/tribixbite/cleverkeys/CleanupHandlerTeardownTest.kt#cleanupShutsDownExecutorOwnersBeforePredictionCoordinator` |
