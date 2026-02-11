# CleverKeys TODO

## Completed (2026-02-11)
- ✅ **Tier 2 instrumented tests**: 58 new tests on emulator.wtf (542 → 600 total)
  - SwipeMLDataStoreTest (36): SQLite CRUD, async store/load, search, pagination, statistics,
    batch operations, SwipeMLData model (JSON round-trip, validation, dedup, normalization)
  - ClipboardDatabaseTest (39): add/retrieve, expiry, pin/todo management, size limits,
    export/import round-trip, ClipboardEntry model, ordering, storage stats
  - LanguageDetectorTest enhanced (18 → 30): detectLanguageWithConfidence,
    detectLanguageFromWordsWithConfidence, unsupported language checks, DetectionResult data class
- ✅ **Production bug fix**: PersonalizationManager.applyFrequencyDecay
  - Fixed ConcurrentHashMap.Entry.setValue UnsupportedOperationException on API 34
  - Replaced removeIf+setValue with explicit iterate+put/remove pattern
- ✅ **OOM test failures fixed**: WordPredictorTest + SwipePredictionTest (12 failures → 0)
  - WordPredictorTest: reflection-based 57-word test dictionary injection
  - SwipePredictionTest: OOM guard on NeuralSwipeTypingEngine construction
- **Total test coverage**: ~898 local (770 pure + 128 mock) + 600 instrumented = ~1,498 tests

## Completed (2026-02-10)
- ✅ **Major feature instrumented tests**: 94 new tests on emulator.wtf (427 → 521 total)
  - SuggestionRankerTest (20): scoring formula, ranking, merge/dedup, language context, prefix boost
  - PersonalizationManagerTest (23): word frequency, bigrams, predictions, decay bug documentation
  - ContractionManagerTest (23): binary/JSON loading, lookup, possessives, language contractions
  - EditorInfoHelperTest (22): action extraction, label/resource mapping, swap enter flag
  - UserAdaptationManagerTest (22): singleton, selection tracking, multipliers, persistence
  - BackupRestoreManagerTest (12): config/dict export-import round-trip, metadata, screen mismatch
- ✅ **Instrumented gap-fill tests**: 36 new tests on emulator.wtf (391 → 427 total)
  - PrivacyManagerInstrumentedTest (13): org.json audit trail, exportSettings JSON, full lifecycle
  - DirectBootInstrumentedTest (11): PreferenceManager paths, device-protected storage,
    copy_preferences all types, checkNeedMigration, DirectBootManager singleton/callback/cleanup
  - DebugLoggingManagerInstrumentedTest (12): BroadcastReceiver register/unregister, debug mode
    toggle via broadcast, sendDebugLog with real Intent, listener notification, full lifecycle
  - Fixed EmojiSearchTest: testGetEmojiNameForUnknownReturnsNull was wrong — isEmoticon heuristic
    classifies ASCII strings >2 chars as emoticons, returning "emoticon" not null
  - All 13 MockK-excluded paths now covered by instrumented tests on real Android
- ✅ **MockK-based test suite**: 128 tests for Android-dependent code (699 → 827 total)
  - PrivacyManagerTest (43): consent, data collection, anonymization, retention, audit trail
  - DirectBootManagerTest (15): singleton lifecycle, unlock detection, DE preferences
  - DirectBootAwarePreferencesTest (4): copy to protected storage, API level branching
  - DebugLoggingManagerTest (9): debug state, listener management, close safety
  - AutocapitalisationTest (12): state machine (started/typed/event_sent/selection/pause)
  - DictionaryManagerTest (14): user word CRUD, legacy migration, language switching, JSON format
  - VibratorCompatTest (11): master/per-event haptic toggles, feedback constants, vibrator fallback
  - SwipePrunerTest (20): already existed, moved from pure to mock runner
  - Added `runMockTests` Gradle task (JavaExec + android.jar on classpath)
  - Added `runAllTests` task combining pure (699) + MockK (128) = 827 total
  - Key techniques: Unsafe reflection for SDK_INT on Java 21, mockkStatic with function refs
    for @JvmStatic, direct field assignment for @JvmField var, Objenesis constructor bypass
  - Excluded paths (require Robolectric): BroadcastReceiver anonymous subclasses,
    Intent constructors, org.json stubs, PreferenceManager AAR dependency
- ✅ **Comprehensive test suite upgrade**: 5-agent team, 287 new tests (412 → 699 total)
  - GestureTest: state machine directions, edge cases
  - ModmapTest: modifier key mapping (Shift/Fn/Ctrl)
  - ComposeKeyPureTest: compose key sequence constants
  - KeyValueParserTest: 73 tests for key definition parsing (named keys, keyevents, macros, flags)
  - CoordinateNormalizerTest: coordinate normalization, key sequence extraction, QWERTY bounds
  - TrajectoryFeatureCalculatorTest: velocity/acceleration from trajectory points
  - NeuralPredictionPureTest: 32 tests salvaged from Robolectric (data classes, quality tiers)
  - IntegrationPureTest: 14 tests salvaged from Robolectric (pipeline integration)
  - onnx/PrefixBoostTrieTest: binary trie loading, prefix boost, failure links
  - onnx/BroadcastSupportTest: tensor shape broadcasting rules
  - SwipePrunerTest: written but excluded from pureTestClasses (requires android.util.Log + MockK)
  - Fixed 12 test failures: parser syntax mismatches, reflection exception wrapping, dedup logic
  - All 699 tests pass on ARM64 via `./gradlew runPureTests`

## Completed (2026-02-09)
- ✅ **Website, Wiki & CI review and fixes**: 3-agent review + 4-agent fix team
  - Wiki: Added 6 missing pages to wiki-config.json (FAQ, quick-settings, password-fields,
    smart-punctuation, user-dictionary, profiles)
  - Wiki: Created docs/wiki/layouts/profiles.md from orphaned HTML (was missing source)
  - Wiki: Regenerated all 42 wiki HTML pages + updated search-index.json (was 36, now 42)
  - Workflows: Deleted redundant setup-pages.yml (conflicted with deploy-web-demo.yml)
  - Workflows: Pinned aquasecurity/trivy-action@0.28.0 (was @master, security risk)
  - Workflows: Added permissions blocks to build.yml and build-apk.yml
  - Specs: Fixed generate-specs.js template (meta viewport, table rendering, code blocks)
  - Specs: Regenerated all 12 spec HTML pages with improved quality
  - Homepage: Synced content with README.md, improved demo placeholder
  - Deploy: Updated workflow to copy search-index.json and all wiki assets
- ✅ **Intent action type review fixes**: addressed 13 issues from 3-agent code review
  - CRITICAL: Fixed setData/setType mutual clearing → setDataAndType()
  - CRITICAL: Fixed executeCommand always returning true (discarded when result)
  - Added null-safe Gson deserialization (IntentDefinition.parseFromGson) for Unsafe bypass
  - Added INTENT_PREFIX constant to IntentDefinition (DRY with KeyValueParser)
  - Fixed URI validation: scheme check instead of useless try/catch on Uri.parse()
  - Extended package validation to SERVICE and BROADCAST targets
  - Fixed single-quote escaping in XmlAttributeMapper TEXT round-trip
  - Fixed CommandPaletteDialog label max (hardcoded 8 → MAX_DISPLAY_LENGTH=4)
  - IntentEditorDialog: removed unused imports, .values()→.entries, variable shadowing fix,
    added contentDescription for accessibility, deduplicate extras before add
  - Compilation verified on ARM64 Termux (1186 classes, 0 errors)
- ✅ **Intent feature unit tests**: comprehensive pure JVM test coverage
  - Created ShortSwipeIntentTest.kt with 55 tests covering all non-Android functionality
  - IntentDefinition.parseFromGson: null-safety, default values, malformed JSON handling
  - IntentDefinition.PRESETS: validation of all 11 preset intents
  - ActionType: fromString case-insensitivity, enum values, display info
  - ShortSwipeMapping: constants, factory methods, getIntentDefinition(), storage keys
  - XmlAttributeMapper.toXmlValue: TEXT quotes, COMMAND mapping, KEY_EVENT prefix, INTENT JSON escaping
  - ShortSwipeCustomizations: round-trip storage format conversion
  - AvailableCommand: fromString, groupedByCategory, display properties
  - All tests follow existing patterns (Truth assertions, @Test annotations, pure JVM)
  - Registered in build.gradle pureTestClasses list for ARM64 runner
- ✅ **Spec updated with INTENT action type**: `docs/specs/short-swipe-customization.md`
  - Added IntentDefinition data class, IntentTargetType enum, parseFromGson docs
  - Added intent validation rules (action/package, installed check, URI scheme)
  - Added execution flow (setDataAndType pitfall, dispatch by target type)
  - Added 11 intent presets table, XML round-trip format, factory/accessor methods
  - Updated architecture diagram, key files table, ActionType enum, storage format example
- ✅ **Cleanup**: restored squoosh submodule to committed pointer, added `.tmp-*.sh` to .gitignore

## Completed (2026-02-04)
- ✅ **Intent action type for short swipe customization**: complete feature implementation
  - Added ActionType.INTENT enum with full serialization support
  - IntentDefinition data class with 11 common presets (browser, share, dial, email, settings, camera, maps, search, termux command)
  - IntentEditorDialog with edit mode, preset selection via FlowRow chips
  - KeyValueParser intent: syntax for XML import/export round-trip
  - Intent validation: package existence check, URI validation, activity resolution
  - canExecuteIntent() for UI validation before saving
  - getIntentDefinition() helper on ShortSwipeMapping
  - XmlAttributeMapper uses quoted JSON: intent:'json'
  - All 357 tests pass

## Completed (2026-02-02)
- ✅ **BeamSearchEngine testability refactor**: extracted DecoderSessionInterface, OrtDecoderSession adapter
  - BeamSearchEngine now fully ONNX-free (all tensor ops behind interface)
  - Shared processLogitsForBeam() eliminates seq/batch duplication
  - Replaced android.util.Log with debugLogger callback
  - 12 beam search tests: scoring, trie masking, batched parity, temperature, dedup
- ✅ **calculateMatchQuality fix**: non-edit-distance mode now uses maxLen denominator
  - "cat" vs "caterpillar" was 1.0 (wrong), now 3/11 ≈ 0.27 (correct)
- ✅ **selectMiddleIndices exposed**: internal visibility + 5 weighted distribution tests
- ✅ **Pipeline integration tests**: 7 tests chaining SwipeResampler → BeamSearchEngine → VocabularyTrie → VocabularyUtils → AccentNormalizer
- ✅ **Gradle runPureTests task**: JavaExec-based JVM test runner, no proot needed
  - Usage: `./gradlew runPureTests [-PtestClass=ClassName]`
- ✅ **ONNX Runtime aarch64 native libs**: tested, blocked by glibc dependency (libdl.so.2 not available on Termux/bionic). Real inference benchmarks require Android instrumented tests.
- ✅ Test suite: 357 tests across 12 classes, all passing

## Completed (2026-01-29)
- ✅ Settings UI: moved Batch Processing, Greedy Search, ONNX Threads from main settings to NeuralSettingsActivity
- ✅ Settings UI: replaced Advanced Neural Settings expander with "Full Neural Settings" button
- ✅ Settings UI: replaced Reset Defaults with Cancel button in NeuralSettingsActivity
- ✅ Wiki audit: completed full pass of ALL ~35 wiki pages against source code
- ✅ Wiki audit: fixed ALL 39 HTML bottom nav buttons (broken relative paths)
- ✅ Wiki audit: fixed installation page (minSdk 21 not 26, ~25MB not 65MB, correct permissions)
- ✅ Wiki audit: added Obtainium and F-Droid installation instructions
- ✅ Wiki audit: fixed first-time-setup default height (30%/40% not 100%)
- ✅ Wiki audit: fixed neural-settings (ONNX threads default 2 not "auto", max length 20 not "varies")
- ✅ Wiki audit: fixed common-issues (removed nonexistent "Export Debug Info", removed "Android 16")
- ✅ Wiki audit: fixed basic-typing (suggestion bar best-match-on-left, removed nonexistent long-press)
- ✅ Wiki audit: fixed smart-punctuation (double-space location → Gesture Tuning)
- ✅ Wiki audit: updated neural-settings page for removed Advanced section → Full Neural Settings button
- ✅ Wiki audit: verified all remaining pages against source code (typing, gestures, customization, settings, clipboard, troubleshooting, layouts, FAQ)
- ✅ Spec files: created neural-settings-spec.md with source references
- ✅ Spec files: updated installation-spec, setup-spec, haptics-spec, appearance-spec with source location tables
- ✅ Deep line-by-line wiki audit (batches 7-10, 35+ pages re-verified):
  - swipe-typing.md: fixed suggestion bar layout (Center/Left/Right → left-to-right by confidence)
  - swipe-typing.md: fixed "Encoder-only" → "Encoder-decoder transformer"
  - autocorrect.md: removed fabricated "tap prediction twice to never autocorrect"
  - special-characters.md: major rewrite — removed fabricated long-press popup section entirely
  - emoji.md: fixed tooltip duration 2.5s → 2s (code: 2000ms)
  - short-swipes.md: fixed long press comparison ("Popup menu" → "Key repeat")
  - extra-keys.md: removed fabricated "Drag to reorder", "Key Order/Size" settings
  - per-key-actions.md: removed fabricated "Reset Customizations Only", "Export Profile"
  - neural-settings.md: fixed "Max Sequence Length" → "Max Word Length", encoder-decoder
  - clipboard-history.md: fixed Per-Key Customization path (added "Activities >")
  - shortcuts.md: removed fabricated "Long-press settings key", "Long-press paste", "Double-tap paste"
  - text-selection.md: fixed TrackPoint activation ("Long swipe from spacebar" → "Long-press nav key")
  - performance.md: removed fabricated "key popup" reference
  - backup-restore.md: fixed all paths to include "Activities >" prefix
  - reset-defaults.md: fixed "Export/Import Settings" → "Export/Import Config"
  - multi-language.md: removed "Long-press or" from subkey access

## Completed (2026-01-22)
- ✅ Emoji long-press tooltip (PopupWindow, positioned above pressed emoji)
- ✅ Emoji name lookup with Unicode fallback for unmapped emojis
- ✅ Emoticon search keywords (shrug, lenny, tableflip, kaomoji, etc)
- ✅ Emoticon grid overlap fix (padding, minHeight, grid spacing)
- ✅ Flag emoji name mappings (260+ countries, shows "japan" not "regional indicator")
- ✅ Emoticon name mappings (100+ smileys/kaomoji, shows "shrug" not "emoticon")
- ✅ Cleanup: removed debug logs from KeyboardReceiver, unused tooltip XML files
- ✅ Updated wiki (emoji.md) and spec (suggestion-bar-content-pane.md)
- ✅ Created skills: emoji-panel.md, content-pane-layout.md
- ✅ Created release-process.md skill (F-Droid API, fastlane changelogs, version codes)
- ✅ Released v1.2.6 - Emoji Panel Polish (tooltip, name mappings, gap fixes)
- ✅ Fixed clipboard history limit slider (0-500, 0 = unlimited) (#85)
- ✅ GitHub issues triage (memory/issue-triage-2026-01-22.md)
- ✅ Clipboard tabs: History (📋), Pinned (📌), Todos (✓) with icon-only UI
- ✅ Close buttons for emoji/clipboard panes (#80)
- ✅ Database migration v2: added is_todo column
- ✅ Backwards-compatible todo export/import (export_version 2)
- ✅ Fixed emoji close button triggering wrong event
- ✅ Clipboard pagination (100 items per page, search all items)
- ✅ Fixed clipboard import (fresh expiry, correct count indices)
- ✅ Option to disable auto-space after suggestion (#82)
- ✅ Separate backspace key repeat option (#81)
- ✅ Respect Greek/Math disabled in numeric layer (#77)
- ✅ Password manager clipboard exclusions: KeePassDX Libre, Chrome, Edge, Firefox (#86)
- ✅ Android 13+ IS_SENSITIVE flag support (user setting, defaults on) (#86)
- ✅ Created ew-cli testing skill (.claude/skills/ew-cli-testing.md)
- ✅ Settings toggles now update Config immediately (fixes #81, #82 taking effect)
- ✅ Added SettingsToggleTest for #81, #82, #86 verification
- ✅ Fixed auto-space after tap suggestion (#82) - bug was in SuggestionHandler.kt
- ✅ Fixed autocapitalization toggle not updating Config immediately
- ✅ Added missing "Capitalize I Words" toggle UI (#72)
- ✅ Fixed swipe capitalization after period (_mods not updated in set_shift_state)
- ✅ Debug settings defaults: show_raw_output and show_raw_beam_predictions now OFF
- ✅ Fixed raw predictions appearing at front of suggestions (now stay at end)
- ✅ Added SettingsToggleTest tests for debug defaults, autocapitalization, I-words
- ✅ All ew-cli instrumented tests passing
- ✅ Fixed swipe predictions not applying user word case preservation (proper nouns)
- ✅ Created wiki-documentation.md skill for consistent docs
- ✅ Created user-dictionary wiki + spec pair (proper noun case preservation)
- ✅ Fixed Android user dictionary case preservation (both sync/async loading paths)
- ✅ Test keyboard fields use KeyboardCapitalization.Sentences (splash + settings)
- ✅ Splash screen animation pauses when keyboard opens (eliminates input lag)
- ✅ Smart punctuation respects manual spacebar (only attaches if space was auto-inserted)
- ✅ Created smart punctuation wiki + spec pair (user guide + technical spec)
- ✅ Fixed swipe capitalization at swipe START (captures shift state when swipe begins)
- ✅ Smart punct adds space after sentence-ending punct (. ! ?) for autocap trigger
- ✅ Fixed Capitalize I Words for swipe (use globalConfig in InputCoordinator/SuggestionHandler)
- ✅ Renamed "Prediction Tap" to "Suggestion Tap" in haptic settings
- ✅ Fixed master vibration toggle not updating Config.haptic_enabled immediately
- ✅ Added SWIPE_COMPLETE haptic trigger when swipe word is auto-inserted
- ✅ Moved vibration/haptic settings to Accessibility section
- ✅ Fixed vibration not triggering (vibrate_custom must be true for duration to work)
- ✅ v1.2.8 released (includes all v1.2.6 changes for F-Droid)
- ✅ Verified Direct Boot safety: swipe_on_password_fields, dictionary loading all use DirectBootAwarePreferences

## Completed (2026-01-27)
- ✅ Wiki hallucination fixes (batch 4 - 6 pages from subagent audit):
  - privacy.md: removed fabricated Personal Dictionary/Usage Patterns sections
  - privacy.md: fixed clipboard settings (50 items, not 24h duration)
  - language-packs.md: removed fabricated download UI and server browsing
  - language-packs.md: corrected to file import via Backup & Restore
  - autocorrect.md: fixed settings path to Word Prediction section
  - autocorrect.md: removed fabricated "Suggest Contact Names" setting
  - user-dictionary.md: removed fabricated "Long-Press on Suggestion" method
  - common-issues.md: fixed all settings paths to actual section names
  - common-issues.md: removed fabricated "Prediction Bar Height" setting
  - performance.md: removed fabricated "Background Processing" setting
  - performance.md: removed fabricated "Prediction Count" setting
- ✅ Final verification audit (batch 5 - 3 pages):
  - adding-layouts.md: removed fabricated "Language Packs" activity
  - per-key-actions.md: fixed path "Customization" → "Activities"
  - extra-keys.md: fixed path to "Activities > Extra Keys"
- ✅ Comprehensive line-by-line audit (batch 6 - 10 fixes from deep verification):
  - short-swipes.md: fixed path "Settings > Customization" → "Settings > Activities > Per-Key Customization"
  - short-swipes.md: fixed location "Input Behavior" → "Gesture Tuning" for Enable Short Swipes
  - clipboard-history.md: fixed default capacity "25 items" → "50 items"
  - timestamp-keys.md: fixed path "Settings → Layout → Extra Keys" → "Settings → Activities → Extra Keys"
  - timestamp-keys.md: fixed path "Settings → Per-Key Customization" → "Settings → Activities → Per-Key Customization"
  - language-packs.md: fixed 3 paths missing "Activities" level for Backup & Restore
  - adding-layouts.md: removed fabricated "Language Packs activity" reference
  - backup-restore.md: fixed fabricated "View Collected Data" path → "Privacy & Data section"
  - themes.md: fixed path "Settings > Backup & Restore" → "Settings > Activities > Backup & Restore"
- ✅ All 69 wiki pages audited, all settings paths verified against SettingsActivity.kt

## Completed (2026-01-26)
- ✅ Added "Calibrate Per-Key Gestures" as third setup box on launcher screen
- ✅ Updated calibration activity text: "trigger up to 8 subkey actions per key based on direction"
- ✅ Created FAQ document (docs/wiki/FAQ.md) covering common questions
- ✅ Added Help & FAQ section to Settings (bottom, collapsible with expandable FAQ items)
- ✅ Added "Open Full Wiki" button linking to https://tribixbite.github.io/CleverKeys/wiki
- ✅ Made FAQ searchable via Settings search
- ✅ Fixed 240px overflow in calibration slider (50dp → 60dp)
- ✅ Added shared PerKeyCustomizationButton composable (DRY between Settings/Calibration)
- ✅ Corrected FAQ content with verified code behavior:
  - Q subkey: NORTHEAST not north
  - Spacebar cursor: proportional to distance
  - TrackPoint: long-press nav keys, not spacebar
  - Emoji: switch_emoji event, layout-dependent
  - Removed non-existent long-press popup references
- ✅ Wiki audit: identified extensive hallucinations in multiple pages (accessibility, themes, backup-restore, layouts)
- ✅ Further FAQ corrections:
  - TrackPoint: only nav key (between spacebar and enter), not generic "nav keys"
  - Language switch: primary/secondary toggle, not switch_forward/switch_backward
  - Emoji: SW on Fn key, not Ctrl
  - Added clipboard FAQ (Fn swipe, History/Pinned/Todos tabs)
- ✅ Calibration step persistence: shows checked forever after first click
- ✅ Wiki hallucination fixes (batch 1 - 5 pages):
  - FAQ.md synced with SettingsActivity (DRY)
  - installation.md: Android 5.0 not 8.0
  - accessibility.md: rewrote with actual features (haptics/sound only)
  - backup-restore.md: removed fabricated auto-backup, QR, cloud features
  - themes.md: corrected to 35+ themes
- ✅ Wiki hallucination fixes (batch 2 - 7 pages):
  - appearance.md: removed fabricated presets, animation, pop-up settings
  - input-behavior.md: removed fabricated auto-cap modes, delete behavior
  - cursor-navigation.md: removed fabricated Home/End on 'A' key
  - first-time-setup.md: fixed Haptics→Accessibility, beam max 10→20
  - swipe-typing.md: removed Neural Profile, Prediction Count
  - custom-layouts.md: removed fabricated visual editor (it's text-based XML)
  - adding-layouts.md: removed fabricated "Programmer" layout
- ✅ Wiki hallucination fixes (batch 3 - 6 pages):
  - haptics.md: section name Haptics→Accessibility
  - neural-settings.md: removed Show Suggestions, Neural Profiles
  - trackpoint-mode.md: fixed trigger to nav key (not arrow keys)
  - emoji.md: removed fabricated long-press Enter/comma access
  - clipboard-history.md: fixed NW→SW, removed long-press features
  - short-swipes.md: fixed numbers direction N→NE

## In Progress
- 🔄 Subkey System Unification (Option D) - awaiting user answers to clarifying questions
  - See: `memory/subkey-unification-research.md`
- 🔄 **GIF Panel (Offline)** - infrastructure complete, data pipeline ready
  - Branch: `feature/gif-panel` (git worktree at `../cleverkeys-gif-module`)
  - Spec: `docs/specs/gif-panel-spec.md`
  - Kotlin: `gif/Gif.kt`, `gif/GifCategory.kt`, `gif/GifDatabase.kt`
  - Python pipeline: `tools/gif_pipeline/` (download, process, build DB)
  - Data sources: TGIF (100K), Video2GIF (100K), GIFGIF+ (23K), ReactionGIF (4K)
  - Next steps:
    - [ ] Download TGIF dataset from Hugging Face
    - [ ] Process to WebP format (thumbnails + animated)
    - [ ] Build database with FTS5 search index
    - [ ] Create GifGridView.kt and UI components
    - [ ] Add GIF tab to keyboard panel

## Completed (2026-01-25)
- ✅ Subkey system investigation: XML subkeys, ShortSwipeCustomizationActivity, ExtraKeys
- ✅ Created research doc with Options A-D analysis
- ✅ Documented 5 clarifying questions for Option D implementation

## Completed (2026-01-24)
- ✅ French contraction frequency ordering (qu'est can now appear before quest if higher frequency)
- ✅ Full AndroidX migration (ExtraKeysPreference, ListGroupPreference)
- ✅ Added ONNX JVM runtime for unit tests (onnxruntime:1.20.0)

## Completed (2026-01-23)
- ✅ Fixed French contractions showing both forms (quest + qu'est)
- ✅ Fixed CI test failures (ComposeKeyTest, OnnxPredictionTest)
- ✅ Partial AndroidX migration (PreferenceManager classes)
- ✅ Fixed 3 broken wiki links

## Completed (2026-01-20)
- ✅ Swedish language pack (sv_enhanced.bin, sv.bin, sv_unigrams.txt)
- ✅ Emoticons category in emoji picker (#76) - 119 text emoticons
- ✅ Tests for resolved issues (#41 emoji search, #71 clipboard, #72 I-words)
- ✅ Tests for swipe sensitivity presets (Low/Medium/High/Custom)
- ✅ Emoticons display fix (length-based text scaling for kaomoji)
- ✅ Emoji/clipboard toggle behavior (tap to open/close)
- ✅ Gap fix: ViewFlipper swaps suggestion bar/content pane with dynamic height
- ✅ App-switch reset: Content pane state resets when keyboard hides

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
- [x] Move Vibration settings to Accessibility section (v1.2.8)
- [ ] Move Smart Punctuation to Auto-Correction section

### Web Demo P2
- [ ] Lazy loading for 12.5MB models
- [ ] PWA/Service Worker for offline support

### Legacy Code Cleanup
- [x] Migrate deprecated `android.preference.*` to `androidx.preference.*` (v1.2.10)

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
# Instrumented (emulator.wtf) — 600 tests, 0 failures
ew-cli --app build/outputs/apk/debug/CleverKeys-v1.2.9-x86_64.apk \
       --test build/outputs/apk/androidTest/debug/CleverKeys-debug-androidTest.apk \
       --device model=Pixel7,version=34

# Local JVM (Gradle — preferred)
./gradlew runPureTests                           # 770 pure JVM tests
./gradlew runMockTests                           # 128 MockK tests (needs android.jar)
./gradlew runAllTests                            # all ~898 tests
./gradlew runPureTests -PtestClass=ClassName     # single class (pure)
./gradlew runMockTests -PtestClass=ClassName     # single class (mock)
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
