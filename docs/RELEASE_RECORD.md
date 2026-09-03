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

### Burn-down 2026-09-03 — superseding rows for pre-v1.6.0 PRESENT-UNTESTED claims

Waves UA-UD wrote tests pinning 78 previously unguarded published claims (1 stays
PRESENT-UNTESTED as visual-only, 1 is REMOVED, and the 82nd — custom sublabel icon
sizing — was a live regression, FIXED the same day; its GUARDED row is below). Original rows above are
immutable; these rows supersede them. Three original code anchors were found wrong and are
corrected here (shift-capture, icon-preview, panel-gap ×2).

| item | kind | note | status | code anchor | test anchor |
|---|---|---|---|---|---|
| short-swipe action catalogue (v1.0.0) | feature | supersedes the v1.0.0 PRESENT-UNTESTED row: 208 short-swipe gesture actions | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/customization/CommandRegistry.kt#CommandRegistry` | `src/test/kotlin/tribixbite/cleverkeys/customization/ReleaseClaimCommandCatalogueTest.kt#catalogue still offers at least the 208 announced actions` |
| icon preview in customization dialog (v1.1.72) | fix | supersedes the v1.1.72 PRESENT-UNTESTED row: Improve icon preview in customization dialog. visual-only Compose rendering with no observable seam; the font DECISION is pinned by ReleaseClaimCommandCatalogueTest. Original anchor was wrong — KeyboardPreviewView.kt contains no icon/font code | PRESENT-UNTESTED | `src/main/kotlin/tribixbite/cleverkeys/customization/CommandPaletteDialog.kt#CommandPaletteDialog` | — |
| editing commands (v1.1.98) | fix | supersedes the v1.1.98 PRESENT-UNTESTED row: Editing commands now work (replaceText, textAssist) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/customization/CommandRegistry.kt#replaceText` | `src/test/kotlin/tribixbite/cleverkeys/customization/ReleaseClaimCommandCatalogueTest.kt#replaceText and textAssist resolve to their Editing key values` |
| icon characters render correctly (v1.1.98) | fix | supersedes the v1.1.98 PRESENT-UNTESTED row: Icon characters render correctly (was showing Chinese) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/customization/ShortSwipeMapping.kt#ShortSwipeMapping` | `src/test/kotlin/tribixbite/cleverkeys/customization/ReleaseClaimCommandCatalogueTest.kt#every private-use glyph in the catalogue asks for the key font` |
| ACTION_PROCESS_TEXT dispatch (v1.1.99) | fix | supersedes the v1.1.99 PRESENT-UNTESTED row: Uses ACTION_PROCESS_TEXT intent instead of unsupported context menu | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/customization/CustomShortSwipeExecutor.kt#textAssist` | `src/test/kotlin/tribixbite/cleverkeys/ReleaseClaimTextActionsTest.kt#the policy action is the framework's ACTION_PROCESS_TEXT` |
| app chooser (v1.1.99) | feature | supersedes the v1.1.99 PRESENT-UNTESTED row: Shows app chooser (Google Assistant, translators, etc.) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/customization/CustomShortSwipeExecutor.kt#CustomShortSwipeExecutor` | `src/test/kotlin/tribixbite/cleverkeys/ReleaseClaimTextActionsTest.kt#text assist dispatches a process-text request with its own chooser` |
| works with any app selection (v1.1.99) | feature | supersedes the v1.1.99 PRESENT-UNTESTED row: Works when text is selected in any app | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/customization/CommandRegistry.kt#textAssist` | `src/test/kotlin/tribixbite/cleverkeys/ReleaseClaimTextActionsTest.kt#the selection is forwarded verbatim, whatever the source app put in it` |
| graceful no-selection fallback (v1.1.99) | fix | supersedes the v1.1.99 PRESENT-UNTESTED row: Falls back gracefully if no text selected | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/customization/CustomShortSwipeExecutor.kt#showTextMenu` | `src/test/kotlin/tribixbite/cleverkeys/ReleaseClaimTextActionsTest.kt#a cursor surrounded by whitespace selects nothing` |
| show text menu (v1.2.0) | feature | supersedes the v1.2.0 PRESENT-UNTESTED row: Selects word at cursor and triggers the native cut/copy/paste toolbar | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/customization/CommandRegistry.kt#showTextMenu` | `src/test/kotlin/tribixbite/cleverkeys/ReleaseClaimTextActionsTest.kt#a cursor inside a word selects the whole word` |
| no-selection toast (v1.2.0) | fix | supersedes the v1.2.0 PRESENT-UNTESTED row: Text Assist and Replace Text now show No text selected when no selection exists | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/customization/CustomShortSwipeExecutor.kt#CustomShortSwipeExecutor` | `src/test/kotlin/tribixbite/cleverkeys/ReleaseClaimTextActionsTest.kt#no-selection messages name the action that was invoked` |
| TrackPoint mode (v1.2.4) | feature | supersedes the v1.2.4 PRESENT-UNTESTED row: Hold nav key to enter joystick cursor control | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt#startTrackPointRepeat` | `src/test/kotlin/tribixbite/cleverkeys/ReleaseClaimGestureModesTest.kt#holding a key with nav sub-keys enters TrackPoint mode` |
| TrackPoint diagonal + speed scaling (v1.2.4) | feature | supersedes the v1.2.4 PRESENT-UNTESTED row: Diagonal movement support and speed scaling with distance from centre | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt#handleTrackPointRepeat` | `src/test/kotlin/tribixbite/cleverkeys/ReleaseClaimGestureModesTest.kt#a diagonal finger position fires both axes in one repeat` |
| selection-delete mode (v1.2.4) | feature | supersedes the v1.2.4 PRESENT-UNTESTED row: Short swipe + hold backspace to select then delete text | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt#startSelectionDeleteRepeat` | `src/test/kotlin/tribixbite/cleverkeys/ReleaseClaimGestureModesTest.kt#a short swipe then hold on backspace enters selection-delete mode` |
| TrackPoint mode (v1.2.5) | feature | supersedes the v1.2.5 PRESENT-UNTESTED row: Hold nav key to enter joystick cursor control (re-published from v1.2.4) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt#startTrackPointRepeat` | `src/test/kotlin/tribixbite/cleverkeys/ReleaseClaimGestureModesTest.kt#holding a key with nav sub-keys enters TrackPoint mode` |
| selection-delete mode (v1.2.5) | feature | supersedes the v1.2.5 PRESENT-UNTESTED row: Short swipe + hold backspace to select then delete (re-published from v1.2.4) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt#startSelectionDeleteRepeat` | `src/test/kotlin/tribixbite/cleverkeys/ReleaseClaimGestureModesTest.kt#a short swipe then hold on backspace enters selection-delete mode` |
| space key with selection (v1.2.6) | fix | supersedes the v1.2.6 PRESENT-UNTESTED row: Space key types a space when text is selected (#1142) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/KeyModifier.kt#KeyModifier` | `src/test/kotlin/tribixbite/cleverkeys/ReleaseClaimKeyModifierTest.kt#space passes through selection mode unchanged` |
| nav bar icons on Android 8-9 (v1.2.6) | fix | supersedes the v1.2.6 PRESENT-UNTESTED row: Nav bar icons on Android 8-9 light themes (#1116) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Keyboard2View.kt#Keyboard2View` | `src/test/kotlin/tribixbite/cleverkeys/ReleaseClaimNavBarTest.kt#Android 8-9 with a light theme paints the theme colour and asks for dark icons` |
| swipe capitalization at gesture start (v1.2.8) | feature | supersedes the v1.2.8 PRESENT-UNTESTED row: Captures the shift state at swipe START. original anchor was wrong — SwipeInput carries no shift state; the capture site is Pointers.onTouchDown, the store is gesture/ImprovedSwipeGestureRecognizer | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt#onTouchDown` | `src/test/kotlin/tribixbite/cleverkeys/ReleaseClaimGestureModesTest.kt#a latched shift is captured when the swipe begins` |
| Greek/Math disabled in numeric layer (v1.2.8) | fix | supersedes the v1.2.8 PRESENT-UNTESTED row: Greek/Math disabled in the numeric layer unless enabled in extra keys (#77) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/KeyModifier.kt#switch_greekmath` | `src/test/kotlin/tribixbite/cleverkeys/ReleaseClaimKeyModifierTest.kt#Fn leaves the numeric switch alone when Greek-Math is not an extra key` |
| space key with selection (v1.2.8) | fix | supersedes the v1.2.8 PRESENT-UNTESTED row: Space key types a space when text is selected | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/KeyModifier.kt#KeyModifier` | `src/test/kotlin/tribixbite/cleverkeys/ReleaseClaimKeyModifierTest.kt#space passes through selection mode unchanged` |
| nav bar icons on Android 8-9 (v1.2.8) | fix | supersedes the v1.2.8 PRESENT-UNTESTED row: Nav bar icons on Android 8-9 light themes | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Keyboard2View.kt#Keyboard2View` | `src/test/kotlin/tribixbite/cleverkeys/ReleaseClaimNavBarTest.kt#Android 8-9 with a light theme paints the theme colour and asks for dark icons` |
| layout catalogue (v1.0.0) | feature | supersedes the v1.0.0 PRESENT-UNTESTED row: 100+ keyboard layouts. the announced count NEVER held — 83 selectable at v1.0.0, 84 today; the ratchet pins the catalogue at or above the true v1.0.0 baseline | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/LayoutManager.kt#LayoutManager` | `src/test/kotlin/tribixbite/cleverkeys/LayoutCatalogueTest.kt#catalogue_selectableLayoutCount_neverRegressesBelowTheV100Baseline` |
| OEM status/nav bar overlay (v1.0.7) | fix | supersedes the v1.0.7 PRESENT-UNTESTED row: Fixed status/navigation bar overlay on OEM devices (Samsung, Xiaomi) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/WindowLayoutUtils.kt#updateSoftInputWindowLayoutParams` | `src/test/kotlin/tribixbite/cleverkeys/WindowLayoutUtilsTest.kt#softInputWindow_isWrapContentAndBottomAligned_whenNotFullscreen` |
| nav bar transparency (v1.0.7) | fix | supersedes the v1.0.7 PRESENT-UNTESTED row: Fixed keyboard navigation bar transparency | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/WindowLayoutUtils.kt#configureEdgeToEdge` | `src/test/kotlin/tribixbite/cleverkeys/WindowLayoutUtilsTest.kt#edgeToEdge_clearsTheWindowBackgroundOnEveryApiLevel` |
| max word length default 15 (v1.1.71) | chore | supersedes the v1.1.71 PRESENT-UNTESTED row: Default max word length reduced to 15 characters. the setting was Defaults.NEURAL_MAX_LENGTH, deleted with the neural engine; CTC has no per-word length setting — its ceiling is the 32-frame emission budget | REMOVED (ADR-011) | — | — |
| custom sublabel colour (v1.1.72) | fix | supersedes the v1.1.72 PRESENT-UNTESTED row: Fix custom sublabel color to match default sublabels | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Theme.kt#subLabelColor` | `src/test/kotlin/tribixbite/cleverkeys/CustomSubLabelRenderingTest.kt#customMappings_takeTheirColourFromTheSameThemeFieldAsBuiltInSubLabels` |
| API 30-34 keyboard positioning (v1.1.73) | fix | supersedes the v1.1.73 PRESENT-UNTESTED row: Fix keyboard positioning on API 30-34 devices | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/WindowLayoutUtils.kt#updateSoftInputWindowLayoutParams` | `src/test/kotlin/tribixbite/cleverkeys/WindowLayoutUtilsTest.kt#edgeToEdge_api30To34_usesShortEdgesAndStillOptsOutOfDecorFitting` |
| nav bar no longer overlapped (v1.1.73) | fix | supersedes the v1.1.73 PRESENT-UNTESTED row: Keyboard no longer overlaps system navigation bar | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/WindowLayoutUtils.kt#configureEdgeToEdge` | `src/test/kotlin/tribixbite/cleverkeys/WindowLayoutUtilsTest.kt#edgeToEdge_api35_drawsThroughTheCutoutAndOptsOutOfAllFittedInsets` |
| API 21-29 insets fallback (v1.1.73) | fix | supersedes the v1.1.73 PRESENT-UNTESTED row: Added insets fallback for API 21-29 | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/WindowLayoutUtils.kt#WindowLayoutUtils` | `src/test/kotlin/tribixbite/cleverkeys/WindowLayoutUtilsTest.kt#edgeToEdge_api29_setsTheCutoutModeButNeverCallsTheApi30OnlyDecorApi` |
| left/right margin caps (v1.1.74) | feature | supersedes the v1.1.74 PRESENT-UNTESTED row: Left/right margins: % of screen width (0-45% each) with a 90% total cap | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Config.kt#margin_right` | `src/test/kotlin/tribixbite/cleverkeys/MarginPercentPolicyTest.kt#storedValueAboveTheCap_isClampedNotHonoured` |
| secondary language weight slider (v1.1.95) | feature | supersedes the v1.1.95 PRESENT-UNTESTED row: Configurable secondary language weight slider (0.5x-1.5x) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Config.kt#secondary_prediction_weight` | `src/test/kotlin/tribixbite/cleverkeys/SecondaryPredictionWeightTest.kt#slider_exposesTheAnnouncedZeroPointFiveToOnePointFiveRange` |
| weighted secondary predictions (v1.1.97) | feature | supersedes the v1.1.97 PRESENT-UNTESTED row: Secondary language mode with weighted predictions | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Config.kt#secondary_prediction_weight` | `src/test/kotlin/tribixbite/cleverkeys/SecondaryPredictionWeightTest.kt#predictor_multipliesSecondaryDictionaryScoresByTheConfiguredWeight` |
| US QWERTY subkeys (v1.2.4) | chore | supersedes the v1.2.4 PRESENT-UNTESTED row: Updated US QWERTY layout subkeys | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/LayoutManager.kt#LayoutManager` | `src/test/kotlin/tribixbite/cleverkeys/LayoutCatalogueTest.kt#defaultQwerty_shippedSubkeyMapIsTheOneAnnouncedInV124` |
| US QWERTY subkey repositioning (v1.2.5) | chore | supersedes the v1.2.5 PRESENT-UNTESTED row: LAYOUT NOTE: default US QWERTY moves some subkeys to the perimeter to reduce short-swipe/word-swipe conflicts; the Julow layout restores the classic arrangement | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/LayoutManager.kt#LayoutManager` | `src/test/kotlin/tribixbite/cleverkeys/LayoutCatalogueTest.kt#defaultQwerty_movesConflictProneSubkeysToThePerimeterKeys` |
| larger numpad keys (v1.2.6) | feature | supersedes the v1.2.6 PRESENT-UNTESTED row: Numpad/PIN keyboard keys 20% larger (#58) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Theme.kt#Theme` | `src/test/kotlin/tribixbite/cleverkeys/NumpadKeySizeTest.kt#pinLayout_keysAreExactlyTwentyPercentLargerThanBeforeTheFix` |
| Monet theme crash below API 31 (v1.2.6) | fix | supersedes the v1.2.6 PRESENT-UNTESTED row: Monet theme crash on Android < 12 (#1107) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/theme/CleverKeysTheme.kt#dynamicColor` | `src/test/kotlin/tribixbite/cleverkeys/theme/MonetDynamicColorGateTest.kt#belowApi31_anEnabledDynamicColourPreferenceIsIgnoredAndCannotReachTheSystemPalette` |
| nav bar overlap on Android 15 (v1.2.6) | fix | supersedes the v1.2.6 PRESENT-UNTESTED row: Nav bar overlap on Android 15 | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/WindowLayoutUtils.kt#configureEdgeToEdge` | `src/test/kotlin/tribixbite/cleverkeys/WindowLayoutUtilsTest.kt#edgeToEdge_api35_drawsThroughTheCutoutAndOptsOutOfAllFittedInsets` |
| larger numpad keys (v1.2.8) | feature | supersedes the v1.2.8 PRESENT-UNTESTED row: Numpad/PIN keyboard keys 20% larger (#58) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Theme.kt#Theme` | `src/test/kotlin/tribixbite/cleverkeys/NumpadKeySizeTest.kt#pinLayout_keysAreExactlyTwentyPercentLargerThanBeforeTheFix` |
| Monet theme crash below API 31 (v1.2.8) | fix | supersedes the v1.2.8 PRESENT-UNTESTED row: Monet theme crash on Android < 12 | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/theme/CleverKeysTheme.kt#dynamicColor` | `src/test/kotlin/tribixbite/cleverkeys/theme/MonetDynamicColorGateTest.kt#belowApi31_anEnabledDynamicColourPreferenceIsIgnoredAndCannotReachTheSystemPalette` |
| nav bar overlap on Android 15 (v1.2.8) | fix | supersedes the v1.2.8 PRESENT-UNTESTED row: Nav bar overlap on Android 15 | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/WindowLayoutUtils.kt#configureEdgeToEdge` | `src/test/kotlin/tribixbite/cleverkeys/WindowLayoutUtilsTest.kt#edgeToEdge_api35_drawsThroughTheCutoutAndOptsOutOfAllFittedInsets` |
| suggestion bar collapse when empty (v1.0.7) | fix | supersedes the v1.0.7 PRESENT-UNTESTED row: Fixed suggestion bar collapse when empty | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/SuggestionBar.kt#alwaysVisible` | `src/androidTest/kotlin/tribixbite/cleverkeys/SuggestionBarVisibilityAndPasswordTest.kt#emptySuggestionsLeaveTheBarVisible` |
| swipe data collection toggle (v1.1.71) | fix | supersedes the v1.1.71 PRESENT-UNTESTED row: Fixed swipe data collection toggle not working | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/ml/SwipeMLDataStore.kt#SwipeMLDataStore` | `src/test/kotlin/tribixbite/cleverkeys/MLDataCollectionToggleTest.kt#withCollectionDisabledNothingIsStoredAndTheCallerIsTold` |
| clipboard blocked while locked (v1.1.76) | feature | supersedes the v1.1.76 PRESENT-UNTESTED row: Clipboard pane blocked while device is locked | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardHistoryService.kt#ClipboardHistoryService` | `src/test/kotlin/tribixbite/cleverkeys/clipboard/ClipboardLockedStartupTest.kt#onALockedDeviceTheServiceIsNotConstructedAtAll` |
| password show/hide toggle (v1.1.79) | feature | supersedes the v1.1.79 PRESENT-UNTESTED row: Eye toggle to show/hide password text in suggestion bar | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/SuggestionBar.kt#SuggestionBar` | `src/androidTest/kotlin/tribixbite/cleverkeys/SuggestionBarVisibilityAndPasswordTest.kt#theEyeToggleSwitchesBetweenBulletsAndThePlaintextPassword` |
| scrollable password display (v1.1.79) | feature | supersedes the v1.1.79 PRESENT-UNTESTED row: Scrollable password display with fixed icon position | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/SuggestionBar.kt#SuggestionBar` | `src/androidTest/kotlin/tribixbite/cleverkeys/SuggestionBarVisibilityAndPasswordTest.kt#aLongPasswordScrollsInsteadOfMovingTheIcon` |
| downloadable language packs (v1.1.95) | feature | supersedes the v1.1.95 PRESENT-UNTESTED row: Downloadable language packs (NL, ID, MS, SW, TL) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/langpack/LanguagePackManager.kt#importLanguagePack` | `src/test/kotlin/tribixbite/cleverkeys/langpack/LanguagePackImportTest.kt#theFiveAnnouncedPacksImportAndAreThenInstalled` |
| locale-filtered user dictionary (v1.1.95) | fix | supersedes the v1.1.95 PRESENT-UNTESTED row: Android user dictionary filtered by locale | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/UserDictionaryObserver.kt#UserDictionaryObserver` | `src/test/kotlin/tribixbite/cleverkeys/UserDictionaryLocaleFilterTest.kt#theProviderQueryBindsTheActiveLanguageAsAnExactAndAPrefixMatch` |
| large language pack OOM (v1.1.96) | fix | supersedes the v1.1.96 PRESENT-UNTESTED row: Fix crash when importing large language packs (Spanish 236k words caused OOM) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/langpack/LanguagePackManager.kt#importLanguagePack` | `src/test/kotlin/tribixbite/cleverkeys/langpack/LanguagePackImportTest.kt#aPackFarLargerThanAnyBufferImportsByStreaming` |
| downloadable packs (v1.1.97) | feature | supersedes the v1.1.97 PRESENT-UNTESTED row: Downloadable language packs (Dutch, Indonesian, Malay, Swahili, Tagalog) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/langpack/LanguagePackManager.kt#getInstalledPacks` | `src/test/kotlin/tribixbite/cleverkeys/langpack/LanguagePackImportTest.kt#theFiveAnnouncedPacksImportAndAreThenInstalled` |
| OOM on large packs (v1.1.97) | fix | supersedes the v1.1.97 PRESENT-UNTESTED row: FIXED: OOM crash on large language packs. the note's second mechanism (top-30k trie cap) lived in the neural beam and went with ADR-011; what survives and is pinned is the streaming import path | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/langpack/LanguagePackManager.kt#importLanguagePack` | `src/test/kotlin/tribixbite/cleverkeys/langpack/LanguagePackImportTest.kt#theImportPathNeverReadsAWholeEntryIntoMemory` |
| Swedish, Greek, Turkish packs (v1.2.6) | feature | supersedes the v1.2.6 PRESENT-UNTESTED row: Language packs added — sv ships bundled, el and tr as importable packs | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/langpack/LanguagePackManager.kt#importLanguagePack` | `src/test/kotlin/tribixbite/cleverkeys/langpack/LanguagePackImportTest.kt#greekAndTurkishImportAsPacksEvenThoughOnlySvIsBundled` |
| tap-to-add dictionary (v1.2.6) | feature | supersedes the v1.2.6 PRESENT-UNTESTED row: Add typed words with a single tap (#42) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/DictionaryManager.kt#DictionaryManager` | `src/test/kotlin/tribixbite/cleverkeys/SuggestionTapAddAndIWordTest.kt#tappingTheExactWordChipCommitsItAndAddsItToTheDictionary` |
| password manager exclusion (v1.2.6) | feature | supersedes the v1.2.6 PRESENT-UNTESTED row: Clipboard skips password-manager copies (#62) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardHistoryService.kt#clipboard_exclude_password_managers` | `src/test/kotlin/tribixbite/cleverkeys/clipboard/ClipboardCaptureExclusionTest.kt#aCopyMadeInAPasswordManagerIsNotStored` |
| dictionary manager sort (v1.2.6) | feature | supersedes the v1.2.6 PRESENT-UNTESTED row: Sort by Frequency/Match/A-Z/Z-A | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/activities/DictionaryManagerActivity.kt#DictionaryManagerActivity` | `src/test/kotlin/tribixbite/cleverkeys/DictionarySortOrderTest.kt#matchPutsTheExactHitFirstThenPrefixHitsThenTheRestEachByFrequency` |
| emoji/clipboard panel gap (v1.2.6) | fix | supersedes the v1.2.6 PRESENT-UNTESTED row: Emoji/clipboard panel gap eliminated. original anchor was wrong — EmojiGridView has no pane-height code; the fix lives in KeyboardReceiver.switchToContentPaneMode | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/KeyboardReceiver.kt#switchToContentPaneMode` | `src/androidTest/kotlin/tribixbite/cleverkeys/ContentPaneGapTest.kt#theContentPaneBottomMeetsTheKeyboardWithNoGap` |
| Swedish, Greek, Turkish packs (v1.2.8) | feature | supersedes the v1.2.8 PRESENT-UNTESTED row: Language packs added (re-published from v1.2.6) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/langpack/LanguagePackManager.kt#getInstalledPacks` | `src/test/kotlin/tribixbite/cleverkeys/langpack/LanguagePackImportTest.kt#availableLanguagesAreTheBundledPairPlusEveryInstalledPackSortedByName` |
| clipboard tabs (v1.2.8) | feature | supersedes the v1.2.8 PRESENT-UNTESTED row: History, Pinned and Todos tabs with icons | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/clipboard/PinnedEntry.kt#PinnedEntry` | `src/test/kotlin/tribixbite/cleverkeys/clipboard/ClipboardTabsAndPaneCloseTest.kt#switchingTabRetargetsTheListAndMovesTheHighlight` |
| panel close buttons (v1.2.8) | feature | supersedes the v1.2.8 PRESENT-UNTESTED row: Close buttons for the emoji and clipboard panes (#80) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/emoji/EmojiSearchManager.kt#setOnCloseCallback` | `src/test/kotlin/tribixbite/cleverkeys/clipboard/ClipboardTabsAndPaneCloseTest.kt#theEmojiPaneCloseButtonInvokesTheRegisteredCallback` |
| tap-to-add dictionary (v1.2.8) | feature | supersedes the v1.2.8 PRESENT-UNTESTED row: Single tap to add words (#42) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/DictionaryManager.kt#DictionaryManager` | `src/test/kotlin/tribixbite/cleverkeys/SuggestionTapAddAndIWordTest.kt#theWordIsAddedExactlyAsTypedNotLowercased` |
| password manager exclusion (v1.2.8) | feature | supersedes the v1.2.8 PRESENT-UNTESTED row: Password manager exclusion (#62, #86) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardHistoryService.kt#clipboard_exclude_password_managers` | `src/test/kotlin/tribixbite/cleverkeys/clipboard/ClipboardCaptureExclusionTest.kt#theBlockedPackageListNamesTheManagersTheNotesClaim` |
| Android 13+ IS_SENSITIVE flag (v1.2.8) | feature | supersedes the v1.2.8 PRESENT-UNTESTED row: Respect the IS_SENSITIVE clip flag (#86) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/clipboard/ClipboardHistoryService.kt#isSensitive` | `src/test/kotlin/tribixbite/cleverkeys/clipboard/ClipboardCaptureExclusionTest.kt#aClipFlaggedSensitiveIsNotStoredEvenFromAnOrdinaryApp` |
| dictionary manager sort (v1.2.8) | feature | supersedes the v1.2.8 PRESENT-UNTESTED row: Sort by Frequency/Match/A-Z/Z-A | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/activities/DictionaryManagerActivity.kt#DictionaryManagerActivity` | `src/test/kotlin/tribixbite/cleverkeys/DictionarySortOrderTest.kt#theSpinnerLabelsLineUpWithTheEnumDeclarationOrder` |
| capitalize I words for swipe (v1.2.8) | fix | supersedes the v1.2.8 PRESENT-UNTESTED row: Capitalize I words for swipe (#72) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/SuggestionHandler.kt#SuggestionHandler` | `src/test/kotlin/tribixbite/cleverkeys/SuggestionTapAddAndIWordTest.kt#everyIFormIsCapitalizedAndNothingElseIs` |
| emoji/clipboard panel gap (v1.2.8) | fix | supersedes the v1.2.8 PRESENT-UNTESTED row: Emoji/clipboard panel gap eliminated. original anchor was wrong — see the v1.2.6 gap row | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/KeyboardReceiver.kt#switchToContentPaneMode` | `src/androidTest/kotlin/tribixbite/cleverkeys/ContentPaneGapTest.kt#openingAContentPaneResizesTopPaneAndGivesTheChildTheSameExplicitHeight` |
| AndroidX preference migration (v1.2.9) | chore | supersedes the v1.2.9 PRESENT-UNTESTED row: Full AndroidX migration: ExtraKeysPreference, ListGroupPreference | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/prefs/ExtraKeysPreference.kt#ExtraKeysPreference` | `src/test/kotlin/tribixbite/cleverkeys/prefs/AndroidXPreferenceMigrationTest.kt#extraKeysPreferenceIsAnAndroidXPreferenceCategory` |
| per-ABI APK splits (v1.0.0) | chore | supersedes the v1.0.0 PRESENT-UNTESTED row: Per-ABI APKs for smaller downloads | GUARDED | `build.gradle#abiCodes` | `src/test/kotlin/tribixbite/cleverkeys/ReleasePackagingDriftTest.kt#perAbiSplitsAreConfigured` |
| no network access (v1.0.0) | feature | supersedes the v1.0.0 PRESENT-UNTESTED row: Complete privacy (no network access) — the manifest declares no INTERNET permission; nothing asserts its absence | GUARDED | `AndroidManifest.xml#uses-permission` | `src/test/kotlin/tribixbite/cleverkeys/ReleasePackagingDriftTest.kt#manifestRequestsNoNetworkPermission` |
| ONNX runtime keeps (v1.0.3) | fix | supersedes the v1.0.3 PRESENT-UNTESTED row: Improved ONNX runtime compatibility — the R8 keep rule survived ADR-011 because CTC still runs ONNX | GUARDED | `proguard-rules.pro#onnxruntime` | `src/test/kotlin/tribixbite/cleverkeys/ReleasePackagingDriftTest.kt#onnxKeepRulesExistAndAreConsumedByTheReleaseBuild` |
| keyboard visibility detection (v1.0.4) | fix | supersedes the v1.0.4 PRESENT-UNTESTED row: Better keyboard visibility detection | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/IMEStatusHelper.kt#isDefaultIME` | `src/test/kotlin/tribixbite/cleverkeys/ImeDefaultDetectionTest.kt#isDefaultIME is true only for our exact component id` |
| ONNX inner-class keeps (v1.0.5) | fix | supersedes the v1.0.5 PRESENT-UNTESTED row: Additional proguard rules for ONNX inner classes | GUARDED | `proguard-rules.pro#onnxruntime` | `src/test/kotlin/tribixbite/cleverkeys/ReleasePackagingDriftTest.kt#onnxKeepRulesExistAndAreConsumedByTheReleaseBuild` |
| F-Droid build reproducibility (v1.0.7) | chore | supersedes the v1.0.7 PRESENT-UNTESTED row: Improved build reproducibility for F-Droid | GUARDED | `build.gradle#profileinstaller` | `src/test/kotlin/tribixbite/cleverkeys/ReleasePackagingDriftTest.kt#reproducibilityGuardsAreEffective` |
| F-Droid verification reproducibility (v1.1.70) | chore | supersedes the v1.1.70 PRESENT-UNTESTED row: Reproducibility improvements for F-Droid verification | GUARDED | `build.gradle#profileinstaller` | `src/test/kotlin/tribixbite/cleverkeys/ReleasePackagingDriftTest.kt#reproducibilityGuardsAreEffective` |
| metadata descriptions (v1.1.70) | chore | supersedes the v1.1.70 PRESENT-UNTESTED row: Updated metadata with improved descriptions | GUARDED | `metadata/fdroid/tribixbite.cleverkeys.yml#AutoName` | `src/test/kotlin/tribixbite/cleverkeys/ReleasePackagingDriftTest.kt#storeDescriptionsAreValidAndAgreeWithTheManifest` |
| SAF swipe-data export (v1.1.71) | feature | supersedes the v1.1.71 PRESENT-UNTESTED row: SAF file picker for swipe data export (saves anywhere) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/ui/settings/io/SettingsSwipeDataHandlers.kt#exportSwipeDataJSON` | `src/test/kotlin/tribixbite/cleverkeys/SwipeDataExportSafTest.kt#exportRoutesThroughTheStorageAccessFramework` |
| swipe debug tool redesign (v1.1.81) | feature | supersedes the v1.1.81 PRESENT-UNTESTED row: Swipe Debug tool redesigned with copy/save actions | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/activities/SwipeDebugActivity.kt#SwipeDebugActivity` | `src/test/kotlin/tribixbite/cleverkeys/SwipeDebugActionsTest.kt#toolbarOffersCopyClearAndSave` |
| quick settings tile (v1.2.6) | feature | supersedes the v1.2.6 PRESENT-UNTESTED row: Switch keyboards from the notification shade (#1113) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/KeyboardTileService.kt#KeyboardTileService` | `src/test/kotlin/tribixbite/cleverkeys/KeyboardTileServiceTest.kt#tileStateFollowsTheSelectedIme` |
| test keyboard field (v1.2.6) | feature | supersedes the v1.2.6 PRESENT-UNTESTED row: Practice typing inside settings (#1134) | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/ui/settings/sections/TestKeyboardSection.kt#TestKeyboardSection` | `src/test/kotlin/tribixbite/cleverkeys/TestKeyboardSectionTest.kt#panelIsRenderedInSettings` |
| quick settings tile (v1.2.8) | feature | supersedes the v1.2.8 PRESENT-UNTESTED row: Switch keyboards from the shade | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/KeyboardTileService.kt#KeyboardTileService` | `src/test/kotlin/tribixbite/cleverkeys/KeyboardTileServiceTest.kt#tileStateFollowsTheSelectedIme` |
| test keyboard field (v1.2.8) | feature | supersedes the v1.2.8 PRESENT-UNTESTED row: Practice typing in settings | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/ui/settings/sections/TestKeyboardSection.kt#TestKeyboardSection` | `src/test/kotlin/tribixbite/cleverkeys/TestKeyboardSectionTest.kt#panelIsRenderedInSettings` |
| splash animation pauses (v1.2.8) | fix | supersedes the v1.2.8 PRESENT-UNTESTED row: Splash animation pauses when the keyboard opens | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/activities/LauncherActivity.kt#LauncherActivity` | `src/test/kotlin/tribixbite/cleverkeys/LauncherSetupFlowTest.kt#splashAnimationPausesWhileTheImeIsUp` |
| launcher gestures box (v1.2.9) | feature | supersedes the v1.2.9 PRESENT-UNTESTED row: Third setup step guides per-key calibration | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/activities/LauncherActivity.kt#LauncherActivity` | `src/test/kotlin/tribixbite/cleverkeys/LauncherSetupFlowTest.kt#thirdSetupStepOpensPerKeyCalibration` |
| custom sublabel icon size (v1.1.98) | fix | supersedes the v1.1.98 PRESENT-UNTESTED row: regressed since 3a705775 (unconditional 0.75x), fixed 2026-09-03 with flag-parity | GUARDED | `src/main/kotlin/tribixbite/cleverkeys/Keyboard2View.kt#commandCarriesSmallerFont` | `src/test/kotlin/tribixbite/cleverkeys/CustomSubLabelRenderingTest.kt#iconCommands_matchBuiltInSizeAcrossAllIconCommands` |
| own words in dictionary (v1.1.71) | chore | supersedes the v1.1.71 UNATTRIBUTABLE row: Added cleverkeys and tribixbite to dictionary. The claim IS attributable — both entries are present in the shipped English dictionary at frequency 134 (`"cleverkeys": 134`, `"tribixbite": 134`), so the row was under-classified, not vague | PRESENT-UNTESTED | `src/main/assets/dictionaries/en_enhanced.json#cleverkeys` | — |
| Material visibility icons (v1.1.79) | chore | supersedes the v1.1.79 UNATTRIBUTABLE row: Material Design visibility icons. Attributable — `res/drawable/ic_visibility.xml` and `ic_visibility_off.xml` are the Material vector drawables backing the suggestion-bar password eye toggle | PRESENT-UNTESTED | `res/drawable/ic_visibility.xml#vector` | — |
