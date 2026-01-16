# CleverKeys Working TODO List

**Last Updated**: 2026-01-15
**Status**: v1.2.9 - Timestamp key feature + Android 15 fixes

---

## Session Progress (2026-01-15 Evening)

- ✅ Updated CHANGELOG.md with v1.2.4, v1.2.8, v1.2.9 releases (315d8662)
- ✅ Regenerated wiki pages with improved generator (37 pages)
- ✅ Fixed wiki-config.json to match existing content files
- ✅ Updated wiki generator with better styling and navigation
- ✅ Simplified macro delay logic per #1108 (e55629ed)

**Recent Commits**:
- `e55629ed` fix: always apply delay between macro keys (#1108)
- `722cfb9a` docs: update wiki generator and regenerate 37 pages
- `315d8662` docs: update CHANGELOG with v1.2.4, v1.2.8, v1.2.9 releases

---

## GitHub Issue Triage (2026-01-15)

**Issues Addressed**:
- #51 ✅ Transparent background on Android 16 - User guidance (opacity setting)
- #55 ✅ Crashes on Nexus 6 - User guidance (disable swipe, older device)
- #56 ✅ First-time user tutorial - Pointed to new wiki documentation
- #67 ✅ Script error (build_all_languages.py) - User error, provided fix
- #68 ✅ Greek dictionary request - Engaged with contributor

**Completed Feature Requests** (GitHub comments added 2026-01-15):
- #62 ✅ Password manager clipboard exclusion - IMPLEMENTED (2026-01-15)
- #1134 ✅ Test keyboard field in settings - IMPLEMENTED (2026-01-15) - Comment added
- #940 ✅ Clipboard delete individual items - IMPLEMENTED (2026-01-15)
- #1113 ✅ Quick Settings tile for keyboard - IMPLEMENTED (2026-01-15) - Comment added
- #1107 ✅ Monet theme crash fix (Android < 12) - FIXED (2026-01-15) - Comment added
- #1131 ✅ Clipboard/emoji nav bar overlap (Android 15) - FIXED (2026-01-15) - Comment added
- #1116 ✅ White nav bar buttons (Android 9) - FIXED (2026-01-15) - Comment added
- #1103 ✅ Insert timestamp key - IMPLEMENTED (2026-01-15)

**Open Feature Requests** (for future consideration):
- #1153 Floating keyboard - Larger feature, needs design
- #1151 Vertical gradient button theme - Design decision
- #1145 Fast typer overlay - Larger feature
- #1142 Space while selected = Space not Esc - Discussed, PR #1141 merged
- #1122 Image pasting - Complex feature
- #1121 Smart key hit detection - Algorithm enhancement
- #1109 Keyboard under nav bar - Android system race condition, not code bug
- #61 Active multi-language switching
- #58 Scaling number keyboard
- #52 MessageEase layout contribution

---

## Password Manager Clipboard Exclusion - IMPLEMENTED (2026-01-15)

**Feature**: Don't store clipboard entries from password managers for privacy.

**Implementation**:
- **Config.kt**: Added `CLIPBOARD_EXCLUDE_PASSWORD_MANAGERS` default (true) + package list
- **ClipboardHistoryService.kt**: Foreground app detection via UsageStatsManager/ActivityManager
- **SettingsActivity.kt**: UI toggle in Clipboard section

**Supported Apps** (20+ packages):
- Bitwarden, 1Password, LastPass, Dashlane
- KeePass variants (keepass2android, KunziSoft Free/Pro, OpenKeePass)
- Enpass, NordPass, RoboForm, Keeper
- Proton Pass, SafeInCloud, mSecure, Zoho Vault, Sticky Password

**Settings UI**: Clipboard → "Exclude Password Managers" toggle

**Commit**: `edfac50f feat: add password manager clipboard exclusion`

---

## Timestamp Key Feature - IMPLEMENTED (2026-01-15)

**Feature**: Insert current date/time formatted with custom patterns (#1103).

**Syntax**:
- Short: `timestamp:'yyyy-MM-dd'` or `📅:timestamp:'yyyy-MM-dd'`
- Long: `:timestamp symbol='📅':'yyyy-MM-dd HH:mm'`

**Implementation**:
- **KeyValue.kt**: Added `Kind.Timestamp` enum + `TimestampFormat` data class + `makeTimestampKey()` factory
- **KeyValueParser.kt**: Added `timestamp:` prefix parsing + `:timestamp` kind in legacy syntax
- **KeyEventHandler.kt**: Added `handleTimestampKey()` using DateTimeFormatter (API 26+) with SimpleDateFormat fallback

**Pre-defined Keys**:
| Key Name | Output |
|----------|--------|
| `timestamp_date` | 2026-01-15 |
| `timestamp_time` | 14:30 |
| `timestamp_datetime` | 2026-01-15 14:30 |
| `timestamp_date_long` | Wednesday, January 15, 2026 |
| `timestamp_iso` | 2026-01-15T14:30:45 |

**Custom Patterns**: Use `timestamp:'pattern'` with DateTimeFormatter syntax

**Spec**: `docs/specs/timestamp-keys.md`

---

## Test Keyboard Field in Settings - IMPLEMENTED (2026-01-15)

**Feature**: Test keyboard without leaving settings (#1134).

**Implementation**:
- **SettingsActivity.kt**:
  - Added `testKeyboardExpanded` and `testKeyboardText` state variables
  - Added collapsible "⌨️ Test Keyboard" section after search bar
  - OutlinedTextField with 3-5 lines, placeholder text, Clear button

**Commit**: `39a3214f feat: add test keyboard field in settings`

---

## Clipboard Delete Individual Items - IMPLEMENTED (2026-01-15)

**Feature**: Delete individual clipboard history entries (#940).

**Implementation**:
- **clipboard_history_entry.xml**: Added delete button with ic_delete icon
- **ClipboardHistoryView.kt**: Added `delete_entry(pos: Int)` method
  - Calls `service.removeHistoryEntry(clip)` to remove from database
  - Clears expanded state and refreshes list

**Commit**: `bd9403d6 feat: add delete button for clipboard history entries`

---

## Quick Settings Tile - IMPLEMENTED (2026-01-15)

**Feature**: Add Quick Settings tile for keyboard switching (#1113).

**Implementation**:
- **KeyboardTileService.kt**: TileService implementation (Android 7.0+)
  - Shows active/inactive state based on current input method
  - Tapping opens system input method picker
- **AndroidManifest.xml**: Register service with BIND_QUICK_SETTINGS_TILE permission

**Commit**: `7db976d1 feat: add Quick Settings tile for keyboard switching`

---

## White Nav Bar Buttons Fix (Android 8-9) - FIXED (2026-01-15)

**Bug**: White nav bar buttons invisible on light theme on Android 9 (#1116).

**Cause**: Transparent nav bar + light theme = white icons on white background.

**Fix**:
- **Keyboard2View.kt**: Use theme's nav bar color instead of transparent on API < 29 for light themes

**Commit**: `c9cbefe1 fix: prevent invisible nav bar icons on light theme for Android 8-9`

---

## Android 15 Nav Bar Overlap Fix - FIXED (2026-01-15)

**Bug**: Clipboard/emoji pane bottom row obscured by nav bar on Android 15 (#1131).

**Cause**: `contentPaneContainer` height was percentage of screen without nav bar insets.

**Fix**:
- **SuggestionBarInitializer.kt**: Added WindowInsets listener to apply bottom padding
- **KeyboardReceiver.kt**: Request insets when pane becomes visible

**Commit**: `c4cd5368 fix: prevent clipboard/emoji pane from overlapping nav bar on Android 15`

---

## Monet Theme Crash Fix - FIXED (2026-01-15)

**Bug**: Monet theme crashed on Android 9 (#1107).

**Cause**: Monet/Material You requires Android 12+ (API 31).

**Fix**:
- **ThemeSettingsActivity.kt**: Filter Monet themes from list on Android < 12
- **Config.kt**: Fallback to Light/Dark if Monet selected on older devices

**Commit**: `551b2250 fix: prevent Monet theme crash on Android < 12`

---

## User Guide Wiki System - COMPLETE & VERIFIED (2026-01-15)

**Goal**: Create comprehensive user guide wiki with paired technical specs.

**Deployment Verified**: https://tribixbite.github.io/CleverKeys/wiki/
- ✅ Wiki index loads with 8 category navigation
- ✅ All 36 user guides accessible
- ✅ Search index with 28 indexed articles functional
- ✅ Main site has green "Wiki" button in navigation

**Completed** (61 pages total):
- ✅ Wiki directory structure (`docs/wiki/[8 categories]/`)
- ✅ TABLE_OF_CONTENTS.md with all 61 pages
- ✅ wiki-config.json with categories, search, navigation
- ✅ generate-wiki.js with callouts, TOC sidebar, prev/next nav, search
- ✅ Main site updated with Wiki link (green button)
- ✅ GitHub Pages deployment workflow updated

**User Guides Created (36/36)**:
- Getting Started (4): installation, enabling-keyboard, first-time-setup, basic-typing
- Typing (4): swipe-typing, autocorrect, special-characters, emoji
- Gestures (5): short-swipes, cursor-navigation, selection-delete, trackpoint-mode, circle-gestures
- Customization (4): per-key-actions, extra-keys, themes, command-palette
- Layouts (6): adding-layouts, switching-layouts, multi-language, language-packs, custom-layouts, profiles
- Settings (6): appearance, input-behavior, haptics, neural-settings, privacy, accessibility
- Clipboard (3): clipboard-history, text-selection, shortcuts
- Troubleshooting (4): common-issues, reset-defaults, backup-restore, performance

**Tech Specs Created (25/25)**:
- getting-started (2): installation-spec, setup-spec
- typing (4): swipe-typing-spec, autocorrect-spec, special-characters-spec, emoji-spec
- gestures (5): short-swipes-spec, cursor-navigation-spec, selection-delete-spec, trackpoint-mode-spec, circle-gestures-spec
- customization (4): per-key-actions-spec, extra-keys-spec, themes-spec, command-palette-spec
- layouts (4): adding-layouts-spec, switching-layouts-spec, multi-language-spec, language-packs-spec
- settings (3): appearance-spec, input-behavior-spec, haptics-spec
- clipboard (2): clipboard-history-spec, text-selection-spec
- troubleshooting (1): common-issues-spec

**Files Created**:
- `docs/wiki/TABLE_OF_CONTENTS.md`
- `docs/wiki/[category]/*.md` (36 user guides)
- `docs/wiki/specs/[category]/*-spec.md` (25 tech specs)
- `web_demo/wiki-config.json`
- `web_demo/generate-wiki.js`
- `web_demo/wiki/*.html` (38 generated files including index and search)

**URL**: https://tribixbite.github.io/CleverKeys/wiki/

---

## Cursor-Aware Predictions - IMPLEMENTED (2026-01-15)

**Problem**: Predictions didn't work when cursor moved mid-word (via tap, cut/paste, arrow keys). Selecting a prediction mid-word left word fragments behind.

**Root Cause**:
- `PredictionContextTracker` had no cursor sync - `currentWord` stayed stale
- `onUpdateSelection()` only notified Autocapitalisation
- `deleteSurroundingText(n, 0)` only deleted BEFORE cursor

**Solution Implemented** (see `docs/specs/cursor-aware-predictions.md`):

**PredictionContextTracker.kt** (+250 lines):
- Added `currentWordSuffix` StringBuilder for chars after cursor
- Added `rawPrefixForDeletion`/`rawSuffixForDeletion` for accurate deletion
- Added `expectingSelectionUpdate` flag to skip programmatic changes
- Added `wasSyncedFromCursor` flag to track sync state
- Implemented `synchronizeWithCursor(ic, language, editorInfo)`:
  - Reads text before/after cursor via InputConnection
  - Extracts word prefix/suffix with `isWordChar()` logic
  - Handles contractions (apostrophe between letters)
  - Normalizes accents for lookup (café→cafe), keeps raw for deletion
- Added CJK detection to skip sync for non-space-delimited languages
- Added input type filtering (skip password, URL, email fields)

**InputCoordinator.kt** (+80 lines):
- Added `onCursorMoved()` with 100ms debouncing via Handler
- Added `triggerPredictionsForPrefix()` for cursor-synced predictions
- Modified `onSuggestionSelected()` to use `getCharsToDeleteForPrediction()`
  - Now deletes BOTH prefix AND suffix when cursor is mid-word
- Added `resetCursorSyncState()` call when user starts typing

**CleverKeysService.kt** (+10 lines):
- Extended `onUpdateSelection()` to call `_inputCoordinator.onCursorMoved()`
- Added `cancelPendingCursorSync()` in `onFinishInputView()`

**Multi-Language Support**:
- ✅ CJK scripts: Skip sync entirely (HAN, HIRAGANA, KATAKANA, THAI, HANGUL)
- ✅ RTL languages: InputConnection positions are logical, no special handling
- ✅ Contractions: Apostrophe within word is NOT a boundary (don't, l'homme)
- ✅ Accents: NFD normalization for lookup, raw char count for deletion

**Bug Fixes (v1.2.6)**:
1. ✅ Mid-word selection left fragments - fixed immediate sync in `onSuggestionSelected()`
2. ✅ Autocorrect undo/add-to-dict prompt disappeared - fixed race condition in SuggestionHandler:
   - Added `specialPromptActive` flag to prevent async prediction task from overwriting
   - Cancel pending task before showing special prompts
   - Check flag in async task before posting to UI
3. ✅ Mid-word suffix not deleted - SuggestionHandler now calls `synchronizeWithCursor()` before deletion
   and uses `deleteSurroundingText(prefixDelete, suffixDelete)` for both-sided deletion
4. ✅ Swipe predictions disappeared quickly - InputCoordinator now checks `lastCommitSource == NEURAL_SWIPE`
   before clearing suggestions on cursor move
5. ✅ Toast "Added to dictionary" too fast - SuggestionBar now checks `isShowingTemporaryMessage` before clearing/overwriting
6. ✅ Double space on mid-sentence replacement - SuggestionHandler checks `hasSpaceAfter` cursor to skip trailing space
7. ✅ Capitalized words cursor-synced not capitalized - InputCoordinator now uses `rawPrefix` (not normalized) for case check
8. ✅ Contractions cursor past apostrophe no suggestions - InputCoordinator combines prefix+suffix for full word lookup,
   also searches de-apostrophed form (e.g., "dont" finds "don't")
9. ✅ Secondary language contractions not showing - WordPredictor now loads contraction keys into secondary NormalizedPrefixIndex

**Bug Fixes (v1.2.7)**:
10. ✅ Suffix not deleted when cursor mid-word (ca|n't → canteen n't) - CRITICAL fix:
    - SuggestionHandler and InputCoordinator now clear `expectingSelectionUpdate` flag before calling `synchronizeWithCursor()`
    - Stale flag from previous deletion could cause sync to skip, leaving `rawSuffixForDeletion` empty
11. ✅ Contraction predictions not showing for PRIMARY language - WordPredictor now loads:
    - `loadPrimaryContractionKeys()` adds apostrophe-free forms to prefix index (e.g., "cant" → "can't")
    - `loadContractionKeysIntoMaps()` for async loading path
    - Both sync and async dictionary loading now include contraction keys
12. ✅ Dictionary Manager Card moved to TOP of Activities section
13. ✅ Cursor mid-word showed wrong predictions (per|fect → "perfect" instead of "per" words):
    - `onCursorMoved()` now uses PREFIX ONLY for prediction lookup, not prefix+suffix
    - When cursor at "per|fect", predictions show "person", "perhaps", etc. (prefix matches)
    - Suffix still tracked for deletion when prediction is selected
14. ✅ Dictionary Manager dropdown changed from filter to sort mechanism:
    - Options: Freq (default), Match, A-Z, Z-A
    - Freq: Sort by frequency (highest first)
    - Match: Sort by match quality (exact > prefix > other), then by frequency
    - A-Z/Z-A: Alphabetical ascending/descending

**Settings UI Changes**:
- Removed redundant Dictionary section (Dictionary Manager accessible from Activities section)

**Status**: Implemented and testing

---

## Defaults & Reset Settings Fix - COMPLETE (2026-01-15)

**Config.kt Default Changes**:
- `SHORT_GESTURE_MIN_DISTANCE`: 37 → 28%
- `KEYBOARD_HEIGHT_PORTRAIT`: 28 → 30%
- `KEYBOARD_HEIGHT_LANDSCAPE`: 50 → 40%
- `MARGIN_BOTTOM_PORTRAIT`: 2 → 0%
- `MARGIN_BOTTOM_LANDSCAPE`: 2 → 0%

**resetAllSettings() Fix** (`SettingsActivity.kt:4576`):
- Now uses `Defaults.*` constants instead of hardcoded values
- Applies BALANCED neural profile (beam_width=6, maxLength=20, etc.)
- Sets correct theme (`cleverkeysdark` instead of `jewel`)
- Sets all margin, haptic, and input behavior defaults properly

**Release Recreation**:
- Deleted old v1.2.5 release and tag
- Created new tag pointing to fix commit (07142bf4)
- Uploaded new APKs with fix
- Updated release notes with new Default Settings section

---

## Web Demo & Spec Documentation System - COMPLETE (2026-01-14)

**Web Demo Updates**:
1. **Under Construction Page** (`web_demo/demo/index.html`)
   - Replaced broken neural demo with styled placeholder
   - Explains demo is being rebuilt with new architecture
   - Links to F-Droid and GitHub releases for app download

**Spec Documentation System**:
1. **New Specs Created**:
   - `selection-delete-mode.md`: Backspace swipe-hold text selection
   - `trackpoint-navigation-mode.md`: Nav key joystick cursor control

2. **Webpage Automation** (`web_demo/generate-specs.js`):
   - Node.js script generates HTML pages from markdown specs
   - Creates category-organized index page
   - Consistent dark theme styling matching main site

3. **Configuration** (`web_demo/specs-config.json`):
   - Defines which specs are reviewed and published
   - Category colors and metadata for each spec
   - Easy to add new specs by editing JSON

4. **Generated Pages** (`web_demo/specs/`):
   - 8 spec detail pages + 1 index page
   - Responsive design with breadcrumb navigation
   - Syntax-highlighted code blocks

**Files Created**:
- `web_demo/demo/index.html`: Under construction page
- `web_demo/specs-config.json`: Spec publishing config
- `web_demo/generate-specs.js`: Page generation script
- `web_demo/specs/*.html`: Generated spec pages
- `docs/specs/selection-delete-mode.md`: New spec
- `docs/specs/trackpoint-navigation-mode.md`: New spec

---

## Settings Activities Update - COMPLETE (2026-01-14)

**New Activity Shortcuts in Settings > Activities**:

1. **Short Swipe Calibration**
   - Card with 📐 icon
   - Launches `ShortSwipeCalibrationActivity`
   - Description: "Practice and tune gesture sensitivity"
   - Also added to searchable settings

2. **What's New**
   - Card with ✨ icon
   - Opens external URL: `https://github.com/tribixbite/CleverKeys/releases/latest`
   - Description: "See latest features and changelog"
   - Added to searchable settings (keywords: changelog, release, update, features, version)
   - Special handling in `navigateToSetting()` for settingId "whats_new"

**Release Workflow Update**:
- Modified `.github/workflows/release.yml` to read from fastlane changelogs
- Changelog file: `fastlane/metadata/android/en-US/changelogs/{versionCode}.txt`
- Falls back to commit-based changelog if fastlane file not found
- GitHub release notes now match F-Droid changelog format

**Files Modified**:
- `SettingsActivity.kt`: Added two Activity cards, searchable setting, URL handling
- `.github/workflows/release.yml`: Read from fastlane instead of commits

---

## Issue #63 Fix - Suggestion Selection Bug - COMPLETE (2026-01-14)

**Bug Report**: User swipes "deze" → sees correct suggestions → taps "deze" → gets "dede" instead

### Fix 1: Skip Final Autocorrect for Manual Selections

**Root Cause (Surface)**: Final autocorrect was incorrectly modifying manually selected neural predictions

**Analysis**:
- Final autocorrect feature (v1.33.7) runs on ALL suggestion selections
- When user explicitly taps a suggestion, final autocorrect found similar dictionary words
- "deze" vs "dede": 3/4 character match (75%) ≥ 66% threshold → incorrect correction

**Fix**:
- Added `isManualSelection: Boolean` parameter to `SuggestionHandler.onSuggestionSelected()`
- Skip final autocorrect when user explicitly taps a suggestion
- `SuggestionBridge.onSuggestionSelected()` now passes `isManualSelection = true`

**Files Modified**:
- `SuggestionHandler.kt`: Added parameter, skip logic for manual selections
- `SuggestionBridge.kt`: Pass `isManualSelection = true` for manual taps

### Fix 2: WordPredictor Language Pack Loading (Root Cause)

**Root Cause (Deep)**: WordPredictor's dictionary didn't load from installed language packs

**Analysis**:
- `OptimizedVocabulary.loadPrimaryDictionary()` loads from language packs ✓
- `WordPredictor.loadDictionary()` only loaded from bundled assets ✗
- For Dutch (nl): no `nl_enhanced.bin` in assets → dictionary empty!
- Autocorrect checked empty dictionary → word not found → "corrected" to similar English word

**Why autocorrect thought "deze" wasn't valid**:
1. Neural predictions came from language pack (Dutch vocabulary) ✓
2. WordPredictor dictionary was empty (no Dutch assets bundled)
3. `dictionary.containsKey("deze")` = false
4. Autocorrect ran and found similar word

**Fix**:
- `WordPredictor.loadDictionary()` now checks `LanguagePackManager.getDictionaryPath()` first
- Added `BinaryDictionaryLoader.loadDictionaryWithPrefixIndexFromFile()` for file-based loading
- WordPredictor now correctly loads Dutch/other language pack dictionaries

**Files Modified**:
- `WordPredictor.kt`: Try language pack before assets
- `BinaryDictionaryLoader.kt`: Added file-based loading method

---

## Selection-Delete Mode for Backspace - COMPLETE (2026-01-14)

**Features Implemented**:

1. **Selection-Delete Gesture Mode**
   - Short swipe + hold on backspace → activates selection mode
   - Works like TrackPoint joystick: bidirectional movement
   - X axis: move left/right → Shift+Left/Right for character selection
   - Y axis: move up/down → Shift+Up/Down for line selection
   - Diagonal movement supported (both axes fire independently)
   - Direction changes dynamically - can reverse selection by moving opposite
   - Speed proportional to finger distance from activation center
   - On release: deletes all selected text
   - Short swipe + release: normal subkey action (delete_last_word)
   - Regular hold (no movement): normal key repeat
   - Files Modified: `Pointers.kt`

2. **Configurable Vertical Selection** (v2: 2026-01-14)
   - Vertical Threshold setting: % of key height to trigger line selection (20-80%, default 40%)
   - Vertical Speed setting: multiplier for line selection (0.1x-1.0x, default 0.4x)
   - Settings UI in Settings > Gesture Tuning > Selection-Delete Mode
   - Files Modified: `Config.kt`, `SettingsActivity.kt`, `Pointers.kt`

**Implementation Details**:
- New `FLAG_P_SELECTION_DELETE_MODE` flag for state tracking
- `selectionDeleteWhat` for timer identification
- `handleSelectionDeleteRepeat()` tracks X/Y axes independently (like TrackPoint)
- Uses `makeInternalModifier(Modifier.SHIFT)` + `with_extra_mod()` for shift state
- Vertical dead zone: `keyHeight * (threshold / 100.0f)` - adapts to key size
- Separate delay calculation for horiz (full speed) vs vert (speed multiplier)
- Config: `selection_delete_vertical_threshold`, `selection_delete_vertical_speed`
- Deferred backspace handling in `onTouchDown` for gesture detection

---

## TrackPoint Mode & Granular Haptics - COMPLETE (2026-01-13)

**Features Implemented**:

1. **TrackPoint Mode for Navigation Keys**
   - Touch and hold arrow key (↑↓←→) without moving → enter TrackPoint mode
   - Move finger in any direction → cursor moves that direction
   - Distinct haptic feedback on mode activation (CLOCK_TICK pattern)
   - Short swipe still works for single cursor movement
   - Files Modified: `Pointers.kt`

2. **Granular Haptic Feedback Settings**
   - New HapticEvent enum: KEY_PRESS, PREDICTION_TAP, TRACKPOINT_ACTIVATE, LONG_PRESS, SWIPE_COMPLETE
   - Per-event enable/disable toggles in Settings > Accessibility
   - Uses system haptic patterns (KEYBOARD_TAP, TEXT_HANDLE_MOVE, CLOCK_TICK, GESTURE_END, LONG_PRESS)
   - Defaults: Key Press, Prediction Tap, TrackPoint, Long Press enabled; Swipe Complete disabled
   - Full backup/restore support
   - Files Modified: `VibratorCompat.kt`, `Config.kt`, `Pointers.kt`, `Keyboard2View.kt`, `SettingsActivity.kt`, `BackupRestoreManager.kt`

3. **Prediction Bar Tap Haptic** (v2 fix: 2026-01-13)
   - Added `triggerHaptic()` method to Keyboard2View for external components
   - SuggestionBridge.onSuggestionSelected() now triggers PREDICTION_TAP haptic
   - Uses TEXT_HANDLE_MOVE constant (lighter tick) to distinguish from typing
   - Files Modified: `Keyboard2View.kt`, `SuggestionBridge.kt`

**Architecture Changes**:
- `onPointerFlagsChanged(Boolean)` → `onPointerFlagsChanged(HapticEvent?)`
- `lockPointer(ptr, Boolean)` → `lockPointer(ptr, HapticEvent?)`
- VibratorCompat uses modern VibrationEffect API on Android O+, VibratorManager on Android S+
- API-appropriate haptic constants with fallbacks (O_MR1, LOLLIPOP, R)
- System haptic settings cached to avoid IPC overhead

**TrackPoint Fixes** (v2: 2026-01-13):
- Excluded nav keys from short gesture path collection to prevent interference
- Increased movement tolerance from 15px to 30px for nav keys during hold
- Files Modified: `Pointers.kt`

---

## Settings Search & Short Swipe Calibration - COMPLETE (2026-01-13)

**Features Implemented**:

1. **Settings Search Bar**
   - Real-time filtering of ~35 searchable settings
   - Results shown in dropdown with section indicators
   - Tapping result expands parent section and scrolls to setting
   - Keywords include synonyms (e.g., "haptic" matches "vibration")
   - Files Modified: `SettingsActivity.kt`

2. **Short Swipe Calibration Activity**
   - Tutorial section with Canvas-drawn graphic showing tap vs short vs long swipe
   - Configuration section with min/max distance threshold sliders
   - Interactive practice area with real-time gesture type feedback
   - Files: `ShortSwipeCalibrationActivity.kt`, `AndroidManifest.xml`

3. **Double-Space-to-Period Toggle**
   - New `double_space_to_period` setting in Config.kt
   - Contextual guardrail: only triggers after alphanumeric characters
   - Fixes rapid space tapping incorrectly inserting periods
   - Files Modified: `Config.kt`, `KeyEventHandler.kt`, `SettingsActivity.kt`

4. **Navigation Key Hold-to-Repeat** (v2 fix: 2026-01-13)
   - Short swipe over arrow keys (↑↓←→) activates cursor movement
   - Detection now happens during onTouchMove (not onTouchUp)
   - If finger held still (<8px movement), cursor repeats with acceleration
   - Moving finger or lifting stops repeat correctly
   - Uses FLAG_P_NAV_HOLD_REPEAT flag and longpress timer
   - Files Modified: `Pointers.kt`

5. **Settings Search** (v2 fix: 2026-01-13)
   - Replaced ExposedDropdownMenu with inline Card + scrollable Column
   - Results always appear below search field (never overlay)
   - Max height 200dp with vertical scroll
   - Activities (Theme, Dictionary, Layout, etc.) navigate directly
   - Section settings expand the appropriate section
   - Added more searchable entries (layout manager, neural settings, etc.)
   - Files Modified: `SettingsActivity.kt`

6. **Settings Search Scroll Fix** (v6 scroll-to-top: 2026-01-14)
   - Replaced BringIntoViewRequester with manual scroll positioning
   - `onGloballyPositioned` tracks Y position of each setting
   - `scrollToSetting()` uses `animateScrollTo()` for precise control
   - Target setting now scrolls to TOP of screen, not just into view
   - Added NestedScrollConnection barrier to search results Card
   - Prevents search results from scrolling parent settings when list reaches end
   - 200ms delay after section expand before scroll triggers
   - Files Modified: `SettingsActivity.kt`

7. **TrackPoint Joystick Mode** (v6 long-press activation: 2026-01-14)
   - Changed from `isNavigationKey(ptr.value)` to `hasNavigationSubkeys(ptr)`
   - The "nav key" is actually the compose key with nav subkeys in positions 5-8
   - bottom_row.xml: `key0="loc compose" key5="left" key6="right" key7="up" key8="down"`
   - **Joystick-style movement**: speed proportional to distance from key center
   - Dead zone (15px) at center - no movement when finger near center
   - Speed: 200ms delay at edge of dead zone, 30ms at key border
   - **v5 diagonal**: X and Y axes tracked independently
   - NE position triggers BOTH up AND right keys in same cycle
   - **v6 long-press**: TrackPoint activates on hold regardless of finger movement
   - Quick swipe + release = short gesture (e.g., SE for page_down)
   - Hold past longPressTimeout = TrackPoint (even after moving finger)
   - Center set to CURRENT finger position when activated
   - Visual fix: clear highlight on exit via onPointerFlagsChanged(null)
   - Files Modified: `Pointers.kt`

---

## NN Beam Search Accuracy Investigation - IN PROGRESS (2026-01-12)

**Problem**: User reports swiping "doesnt" yields "downstream", "downtrodden" and swiping "gesture" yields "feature", "heating", "gearing".

**Investigation Findings**:

1. **CONFIRMED: Direction changes DON'T affect NN inputs**
   - `getNearestKeyAtDirection` (Pointers.kt) → short swipe subkey detection ONLY
   - `KeyboardGrid.getNearestKeyToken()` (SwipeTrajectoryProcessor.kt) → NN swipe input
   - These are **completely separate code paths**

2. **Recent changes reviewed (no obvious cause found)**:
   - Strict start char feature (default: false) - shouldn't cause issues
   - Cumulative boost cap (default: 15.0) - same as before
   - Contraction preservation changes - post-processing only

3. **Diagnostic logging added** (commit 4d21a12e):
   - Verifies critical words in trie after loading: `doesnt`, `dont`, `gesture`, `feature`
   - Will log `🚨 TRIE MISSING WORDS:` if any are missing
   - Will log `✅ Trie verification passed` if all present

**Next Steps**:
- Install build with diagnostics and check logcat for trie verification messages
- Enable debug logging in Neural Settings to see raw beam search output
- Compare detected key sequences vs expected trajectories

**Files Modified**: `OptimizedVocabulary.kt`

---

## Privacy Settings Status

| Setting | Works? | Notes |
|---------|--------|-------|
| Swipe Pattern Data | ✅ | Fixed in InputCoordinator to check setting |
| Performance Metrics | ✅ | NeuralPerformanceStats checks setting |
| Error Reports | 🔇 | Hidden from UI - no implementation yet |

**Performance Metrics Storage:**
- File: `neural_performance_stats` (device-protected SharedPreferences)
- Path: `/data/user_de/0/tribixbite.cleverkeys/shared_prefs/neural_performance_stats.xml`
- Data: prediction counts, inference times, top-1/top-3 accuracy, model load time
- Access: `NeuralPerformanceStats.getInstance(context).formatSummary()`
- UI: Settings > Privacy > Performance Metrics (View/Export buttons)

**Swipe Data Export (fixed OOM):**
- JSON/NDJSON exports now stream from DB cursor (no memory buildup)
- View dialog: search, pagination (20/page), tap to copy trace JSON

---

## Autocorrect UX Improvements - COMPLETE (2026-01-12)

**Problem 1**: After autocorrect (e.g., "subkeys" → "surveys"), tapping original word in suggestions inserted ANOTHER word instead of replacing.

**Fix 1**: Autocorrect undo functionality
- Track autocorrect state in PredictionContextTracker (lastAutocorrectOriginalWord)
- When user taps original word in suggestions, detect autocorrect undo scenario
- Delete the autocorrected word + space, insert original word + space
- Automatically add original word to user dictionary (prevents future autocorrection)
- Show confirmation message "Added 'word' to dictionary"

**Problem 2**: Unknown words typed without triggering autocorrect had no way to add to dictionary.

**Fix 2**: "Add to dictionary?" prompt
- When unknown word (not in dictionary) is completed with space but not autocorrected
- Show "Add 'word' to dictionary?" prompt in suggestion bar
- Tapping prompt adds word to custom dictionary
- Shows confirmation message "Added 'word' to dictionary"

**Files Modified**:
- `PredictionContextTracker.kt`: Added autocorrect tracking fields and methods
- `WordPredictor.kt`: Added `isInDictionary()` method
- `SuggestionHandler.kt`: Added `handleAutocorrectUndo()` and `handleAddToDictionary()`
- `SuggestionBar.kt`: Transform `dict_add:` prefix to friendly prompt text

**Technical Details**:
- `dict_add:word` prefix format for dictionary prompt (hidden from user)
- Uses existing `showTemporaryMessage()` for confirmations
- Clears tracking when new word started (prevents stale undo state)
- Works with existing DictionaryManager for user word storage

**Problem 3** (2026-01-12): Legacy custom words from older versions wouldn't delete in Dictionary Manager.

**Fix 3**: Storage migration
- Old format: `user_dictionary` SharedPrefs with `user_words` StringSet
- New format: DirectBootAwarePreferences with `custom_words_{lang}` JSON map
- Added `migrateLegacyCustomWords()` to DictionaryManager.kt
- Runs once at init, merges legacy words to new format, clears old data
- Files Modified: `DictionaryManager.kt`

**Code Review** (2026-01-12): Dictionary management system audit (validated by Gemini 2.5 Pro via PAL MCP)

**Issues Fixed**:
1. **HIGH**: Removed vestigial `LanguagePreferenceKeys.migrateUserDictionary()` - duplicated
   DictionaryManager's migration with bugs (hardcoded "en", didn't clear legacy data)
2. **MEDIUM**: Removed redundant calls to deleted function in OptimizedVocabulary & BackupRestoreManager
3. **LOW**: Removed dead code - redundant null check in `OptimizedVocabulary.loadDisabledWords()`

**Verified Working**:
- Autocorrect UX: `dict_add:` prefix, `handleAutocorrectUndo()`, `clearAfter=true`
- Import/Export: Legacy + new format support, proper merge logic
- Storage: Consistent use of DirectBootAwarePreferences + `custom_words_{lang}` JSON

**Layout Update** (2026-01-12): QWERTY US layout reorganization

**Changes**:
- 'a': nw=home, sw=end (navigation keys)
- 'l': nw=(, ne=) (parentheses moved here)
- 'p': nw=| (pipe moved from 'l')
- 'o': nw=_ (underscore moved from 'g')
- shift: nw=esc, se=tab (from 'a' and 'q')
- Number row: special chars reorganized to nw positions

**Bug Fix** (2026-01-12): Short swipe over shift causing uppercase display

**Problem**: When doing a short swipe over shift to activate a subkey (esc, tab, capslock),
the keyboard would display uppercase letters because shift was being included in `getModifiers()`.

**Fix**: Modified `Pointers.getModifiers()` to skip non-latched latchable keys.
A modifier should only be "active" if it's LATCHED or LOCKED, not just touched/swiped over.

**Files Modified**: `Pointers.kt`, `latn_qwerty_us.xml`, `ExtraKeysPreference.kt`

**Bug Fix** (2026-01-12): Horizontal swipe direction detection

**Problem**: Swiping horizontally from 'w' to 'e' triggered NE subkey ('2') instead of swipe typing.

**Root Cause**:
1. `DIRECTION_TO_INDEX` mapped direction 4 (E) to SE (4) instead of E (6)
2. `getNearestKeyAtDirection` searched ±3 directions (135°), too wide for precise direction

**Fix** (revised after consensus with Gemini 3 Pro + Gemini 2.5 Pro):
- Corrected DIRECTION_TO_INDEX: dir 4 now maps to E (6) instead of SE (4)
- Reduced fallback range from ±3 to ±1 directions (~67° arc)
  - ±2 was still too wide: dir 4 - 2 = dir 2 (NE)
- Fixed DIRECTION_TO_SWIPE_DIRECTION[4] from SE to E for consistency

**Files Modified**: `Pointers.kt`

**Bug Fix** (2026-01-12): Contraction system preserving base words

**Problem**: Swiping "were" only showed "we're" prediction, missing the base word "were".
Same issue in French: "dans" only showing "d'ans".

**Root Cause** (revised after debugging): At line 447, `displayWord` was changed
from "were" to "we're" BEFORE the prediction was added to validPredictions. So
"were" was never in the list for the later contraction handling to preserve.

**Fix** (revised):
1. **Primary fix (line 449)**: Skip early contraction mapping for paired contraction
   bases (words in `contractionPairings`). These keep their original form.
2. **Safety net (line 782)**: Skip non-paired replacement for:
   - Paired bases (contractionPairings)
   - Real vocabulary words (frequency > 0.65)

**Files Modified**: `OptimizedVocabulary.kt`

---

## v1.2.1 Language-Specific Prefix Boosts - COMPLETE

**Feature**: Boost prefixes common in target language but rare in English

**Problem**: French word "veux" (rank=79, very common) never appeared in predictions,
but "vérification" (rank=140) did. Root cause: English-biased NN.
- NN gives low P(u|ve) because "veu" is rare in English training data
- Beam width of 6 prunes the "veu" path before it can reach "veux"
- Previous approach (LM fusion with word count) FAILED - it boosted "ver" (79 words)
  over "veu" (9 words), making predictions worse

**Solution** (validated by Gemini 3 Pro + GPT-5.2 via PAL MCP):
Log-odds prefix boosting - boost prefixes where P_target >> P_english:
```
delta = log(P_fr(c|prefix)) - log(P_en(c|prefix))
boost = C * delta (clamped to max B), threshold 1.5
```

Key insight: We need to boost prefixes that are RARE in English but COMMON in French,
not prefixes with more reachable words.

**Implementation v2 (Aho-Corasick Trie - Zero Allocation)**:
Per Gemini 3 Pro consultation: JSON-based lookups caused GC pauses from string allocation
during beam search. Replaced with memory-mapped Aho-Corasick trie for O(1) lookups.

- [x] `scripts/compute_prefix_boosts.py`: Generates sparse binary Aho-Corasick tries
      - Aho-Corasick failure links for longest-suffix backoff
      - Sparse format: ~85% smaller than dense (10MB → 1.5MB per language)
      - Binary format: PBST v2 (NodeOffsets, EdgeKeys, EdgeTargets, FailureLinks, Boosts)
- [x] `assets/prefix_boosts/*.bin`: Binary tries for de, es, fr, it, pt
      - fr.bin: 1.5MB, 90k nodes, threshold 1.5, "ve"+"u" boost = 7.97
      - es.bin: 2.2MB, 132k nodes
      - de.bin: 1.7MB, 100k nodes
      - it.bin: 1.5MB, 88k nodes
      - pt.bin: 1.5MB, 93k nodes
- [x] `onnx/PrefixBoostTrie.kt`: Memory-mapped sparse trie loader (NEW)
      - Zero heap allocation during lookup
      - `getNextState(state, char)`: O(1) amortized Aho-Corasick traversal
      - `getBoost(state, char)`: O(1) boost lookup using state
- [x] `onnx/BeamSearchEngine.kt`: State-based trie integration
      - Added `boostState: Int = 0` to BeamState for tracking trie position
      - `applyPrefixBoosts()` now uses beam.boostState for O(1) lookups
      - Beam expansion advances boostState for child beams
- [x] `onnx/SwipePredictorOrchestrator.kt`: Updated for PrefixBoostTrie
- [x] DELETED: `onnx/PrefixBoostLoader.kt` (replaced by trie)
- [x] `Config.kt`: Add `NEURAL_PREFIX_BOOST_MULTIPLIER` (1.0f) and `NEURAL_PREFIX_BOOST_MAX` (5.0f)
- [x] **Cumulative Boost Cap** (2026-01-11): Prevent runaway boosting on long words
      - Added `cumulativeBoost` field to BeamState for tracking total boost per beam path
      - Now configurable via `neural_max_cumulative_boost` setting (5-30 range, default 15.0)
      - `applyPrefixBoosts()` returns applied boosts array and respects remaining budget
      - Individual boosts capped to `maxCumulativeBoost - beam.cumulativeBoost`
      - English latency unaffected (prefix boosts not loaded for "en")
      - Validated by expert analysis via PAL MCP (Gemini 2.5 Pro)
- [x] **Strict Start Character Toggle** (2026-01-11): Helps short swipes return accurate predictions
      - Added `neural_strict_start_char` toggle (default: false)
      - When enabled, filters beams after step 0 to only keep those matching detected first key
      - First key extracted from `features.nearestKeys` in SwipePredictorOrchestrator
      - Addresses issue where short swipes with boosted prefixes yield extra long words
      - Configurable via main Settings > Multi-Language > Prefix Boost section

**Files Modified/Added**:
- MOD: `scripts/compute_prefix_boosts.py` - sparse binary Aho-Corasick trie generation
- NEW: `src/main/assets/prefix_boosts/{de,es,fr,it,pt}.bin` - binary tries (~1.5-2.2MB each)
- NEW: `onnx/PrefixBoostTrie.kt` - zero-allocation memory-mapped trie loader
- DEL: `onnx/PrefixBoostLoader.kt` - replaced by trie
- MOD: `onnx/BeamSearchEngine.kt` - state-based prefix boost application
- MOD: `onnx/SwipePredictorOrchestrator.kt` - trie integration
- MOD: `Config.kt` - prefix boost settings

**Testing Needed**:
- [ ] Test French "veux" appears in predictions with prefix boosts enabled
- [ ] Test other French words with "veu" prefix: veut, veulent
- [ ] Verify English predictions not affected (boosts only load for non-English primary)
- [ ] Test boost multiplier tuning if needed (default 1.0)
- [ ] Verify zero GC pauses during beam search with trie

---

## v1.2.0 Language Toggle & Text Menu - COMPLETE

**New Features for v1.2.0**:
- [x] "No text selected" toast for Text Assist and Replace Text when no selection
- [x] Show Text Menu command - selects word at cursor, triggers native toolbar
- [x] Primary Language Toggle - swap between two configured primary languages
- [x] Secondary Language Toggle - swap between two configured secondary languages
- [x] New preference keys: `pref_primary_language_alt`, `pref_secondary_language_alt`
- [x] Added 5 new AvailableCommand entries for per-key customization
- [x] Settings UI: "Quick Language Toggle" section with alternate language dropdowns
- [x] **FIX**: Command execution order - check custom commands BEFORE KeyValue lookup
  - Root cause: KeyValue.getKeyByName() has fallback creating String KeyValue for any name
  - This intercepted custom commands (primaryLangToggle, etc.) before they could execute
  - Fix: Check actionValue against custom commands FIRST, return if handled
- [x] **FIX**: DirectBootAwarePreferences for toggle persistence
  - Root cause: Toggle functions used `PreferenceManager.getDefaultSharedPreferences()` (credential-encrypted)
  - But service listens on `DirectBootAwarePreferences.get_shared_preferences()` (device-protected)
  - These are DIFFERENT SharedPreferences files on Android 24+ (Direct Boot)
  - Fix: Changed toggle functions to use DirectBootAwarePreferences
- [x] **FIX**: Suggestion bar message feedback (Android 13+ Toast suppression)
  - Root cause: Android 13+ (API 33+) suppresses Toast from IME services
  - Fix: Added `SuggestionBar.showTemporaryMessage()` for in-keyboard feedback
  - Shows message briefly, then restores previous suggestions
- [x] **FIX**: Touch typing predictions showing English when not primary/secondary
  - Root cause: UserDictionary words with NULL locale were included in all languages
  - Android adds typed words without locale tagging, causing cross-language contamination
  - Fix: Only include null-locale words when language is English
  - For other languages, strictly filter by matching locale only
- [x] **FIX**: Contractions not working after language toggle round-trip (2026-01-09)
  - Root cause: ContractionManager only loaded contractions at startup, not on language change
  - When toggling English → French → English, contractions (dont → don't) stopped working
  - Fix: Added contraction reload in PreferenceUIUpdateHandler when primary language changes
  - Now reloads base contractions + language-specific contractions on each toggle
- [x] **FIX**: English contractions not working for swipe/touch typing after language toggle (2026-01-09)
  - Root cause: Contraction keys (like "dont", "cant") not inserted into vocabularyTrie on reload
  - If app launched with non-English primary, vocabularyTrie never had contraction keys
  - Beam search rejected contraction words before they could reach contraction processing
  - Fix 1: OptimizedVocabulary.unloadPrimaryDictionary() now inserts contraction keys to trie
  - Fix 2: OptimizedVocabulary.loadPrimaryDictionary("en") now inserts contraction keys + sets state
  - Fix 3: ContractionManager.loadMappings() now clears maps before loading to prevent stale data
- [x] **FEAT**: Contraction suggestions in touch typing predictions (2026-01-09)
  - Touch typing now shows contraction forms (e.g., "don't") when user types key like "dont"
  - Contraction appears as first suggestion with higher score
  - Same contraction awareness as swipe typing now available for touch typing
- [x] **FIX**: English word contamination in touch typing (2026-01-09)
  - Root cause: Dictionary reload was blocked if previous load was in progress
  - WordPredictor.loadDictionaryAsync() had `isLoadingState` check that ignored reload requests
  - When user toggled language during initial English dictionary load, new language was ignored
  - Fix: Reset isLoadingState flag and let AsyncDictionaryLoader cancel previous task
  - Added debug logging for verification: sample words, language, dict size

**Technical Details**:
- `showNoTextSelectedToast(actionName)` - toast helper with try/catch
- `showTextContextMenu(ic)` - word boundary detection + setSelection
- `togglePrimaryLanguage()` - swaps pref_primary_language with pref_primary_language_alt
- `toggleSecondaryLanguage()` - swaps pref_secondary_language with pref_secondary_language_alt
- `getLanguageDisplayName(code)` - maps language codes to display names
- `SuggestionBar.showTemporaryMessage(msg, duration)` - temporary feedback display
- `CleverKeysService.showSuggestionBarMessage(msg, duration)` - public API for feedback

---

## v1.1.99 Text Processing Actions Fix - COMPLETE

**textAssist and replaceText Not Triggering Activities**:
- [x] Issue: performContextMenuAction for textAssist/replaceText not supported by most apps
- [x] Root cause: android.R.id.textAssist and android.R.id.replaceText context menu actions are rarely implemented
- [x] Fix: Use ACTION_PROCESS_TEXT intent which is widely supported
- [x] Shows app chooser (Google Assistant, translators, search engines, etc.)
- [x] Falls back to context menu action if no text selected

---

## v1.1.97+ Custom Per-Key Actions Fixes - COMPLETE

**Custom Short Swipe Commands Not Working**:
- [x] Issue: Event-type commands (config, clipboard, voice, numeric) did nothing when assigned
- [x] Root cause: CustomShortSwipeExecutor only handled InputConnection-based commands
- [x] Fix: Added KeyValue-based execution in Keyboard2View.onCustomShortSwipe()
- [x] Event-type commands now call service.triggerKeyboardEvent()
- [x] Editing-type commands call InputConnection.performContextMenuAction()

**PUA Characters Showing as Chinese in Customization UI**:
- [x] Issue: Icon characters (PUA range) rendered as Chinese in per-key selection
- [x] Root cause: Compose Text() uses system font, not special_font.ttf
- [x] Fix: Use AndroidView with Theme.getKeyFont() for icon preview
- [x] Fixed in: ShortSwipeCustomizationActivity (MappingListItem) and CommandPaletteDialog (preview)

**Custom Sublabel Icons Larger Than Built-in Icons**:
- [x] Issue: Custom per-key action icons appeared 33% larger than built-in sublabels
- [x] Root cause: Built-in sublabels with FLAG_SMALLER_FONT use _subLabelSize * 0.75f
- [x] Custom sublabels in drawCustomSubLabel() always used full _subLabelSize
- [x] Fix: Apply 0.75f scaling when useKeyFont is true
- [x] Code change: `val textSize = if (useKeyFont) _subLabelSize * 0.75f else _subLabelSize`

---

## v1.1.96 Fixes - COMPLETE

**OOM Crash on Large Language Packs**:
- [x] Issue: Importing large dictionaries (Spanish 236k words) caused OutOfMemoryError
- [x] Crash occurred in VocabularyTrie.insert when adding ALL secondary words to beam trie
- [x] Fix: Added `getTopNormalizedWords(maxCount, maxRank)` to NormalizedPrefixIndex
- [x] Limited secondary trie insertions to top 30k most frequent words
- [x] Large dictionaries still work - just uses most common words for NN predictions

**Frequency Display in Dictionary Manager**:
- [x] Issue: All words showed frequency=100 regardless of actual frequency
- [x] Root cause: MainDictionarySource.loadBinaryDictionary() hardcoded frequency=100
- [x] V2 binary format DOES store frequency rank (0-255) but it wasn't being read
- [x] Fix: Read bestFrequencyRank from LookupResult and convert to display frequency
- [x] Conversion: rank 0 → 10000, rank 255 → ~50

**Language Dictionary Regeneration**:
- [x] Regenerated all 11 languages with wordfreq for proper frequency ranks
- [x] Bundled: en, es, fr, pt, it, de (50k/25k words)
- [x] Downloadable: nl, id, ms, tl (20k words) [sw removed - wordfreq unsupported]

**Dictionary Manager Not Loading from Language Packs**:
- [x] Issue: Imported lang packs showed empty tabs in Dictionary Manager
- [x] Root cause: MainDictionarySource.getAllWords() only checked bundled assets, not installed packs
- [x] Fix: Added lang pack check via LanguagePackManager.getDictionaryPath() before assets fallback
- [x] Added loadBinaryDictionaryFromFile() and extractWordsFromIndex() helper methods

**Swahili wordfreq Fallback Bug**:
- [x] Issue: Swahili (sw) predictions were all English words
- [x] Root cause: wordfreq silently falls back to English for unsupported languages
- [x] Swahili isn't properly supported - "jambo" has freq 1.2e-7, "the" has 5.4e-2
- [x] Fix: Removed 'sw' from SUPPORTED_LANGUAGES in build_all_languages.py
- [x] Added UNSUPPORTED_LANGUAGES check in get_wordlist.py
- [x] Properly supported via wordfreq: en, es, fr, pt, it, de, nl, id, ms, tl

**Swahili External Corpus Solution (v1.1.97)**:
- [x] wordfreq doesn't support Swahili, but real frequency data IS available
- [x] Source: Kwici Swahili Wikipedia Corpus (2.8M words, CC-BY-SA)
- [x] URL: https://kevindonnelly.org.uk/swahili/swwiki/
- [x] Created parse_swahili_ods.py to extract 168k words with frequencies from ODS file
- [x] Built langpack-sw.zip with 20k words and proper frequency ranks
- [x] Top words verified as real Swahili: ya, na, wa, kwa, katika, ni, la, za, kama, cha
- [x] Rank 0 (ya) = 10000 display freq, properly distributed down to rare words

**English Dictionary V2 Binary Fix (v1.1.97)**:
- [x] Issue: English showed max frequency 255 in Dictionary Manager, other languages showed 10000
- [x] Root cause: DictionaryDataSource.kt line 59 had `if (languageCode != "en")`
- [x] English was SKIPPING V2 binary loading and falling through to JSON with raw 128-255 frequencies
- [x] Fix: Changed `if (languageCode != "en")` to `run {` so all languages use V2 binary path
- [x] Now English properly loads from V2 binary format with normalized 1-10000 scale

**English Frequency Comparison Analysis (v1.1.97)**:
- [x] Created langpack-en-opensubtitles-50k.zip (50k words from OpenSubtitles 2018 corpus)
- [x] Created langpack-en-norvig-50k.zip (50k words from Norvig/Google web corpus)
- [x] 3-way comparison: wordfreq (balanced) vs OpenSubtitles (spoken) vs Norvig (web/forum)
- [x] OpenSubtitles: casual words ranked higher (yeah 7.2x, gonna 5.4x, okay 11.8x)
- [x] Norvig: web/forum text with profanity and technical terms
- [x] Word comparison files saved: norvig_missing.txt, opensub_missing.txt
- [x] Norvig-only words mostly spam/adult content from 2008 web crawl
- [x] OpenSubtitles-only words mostly contractions as separate tokens ('s, 't, 'm, etc.)

**English Contractions Bug Fix (v1.1.97)**:
- [x] Issue: contractions_en.json was missing 18 essential contractions from contractions_non_paired.json
- [x] Missing: im->i'm, ive->i've, hes->he's, shes->she's, thats->that's, etc.
- [x] OptimizedVocabulary only loads contractions_en.json for English (not contractions_non_paired.json)
- [x] Fix: Merged 18 missing entries into both files (now 120 entries each)
- [x] Regenerated contractions.bin (120 non-paired, 1183 paired, 13KB)
- [x] Swipe predictions "im", "ive", "hes" now correctly convert to "i'm", "i've", "he's"

**English Dictionary V3 Creation (v1.1.97)**:
- [x] Issue: V2 dictionary was pure wordfreq, losing V1's curated features
- [x] V2 was missing: single letters, contractions, possessives, custom words
- [x] V2 added 14 new slurs not in V1 (wordfreq tracks actual web usage)
- [x] V3 Strategy: V1 base + V2 valuable additions - typos - specified offensive
- [x] Removed 15 common typos (dissapointed, recieved, thier, definately, etc.)
- [x] Removed only 4 offensive words per user request (retard*, shemale)
- [x] Preserved all V1 features: single letters, contractions, possessives, custom words
- [x] Final V3: 52,042 words (V1: 49,297 + V2 additions: 2,763 - removed: 18)
- [x] JSON and binary now in sync
- [x] Saved 197 accented words for later review: scripts/accented_words_for_review.txt

---

## v1.1.94/95 Features - COMPLETE

**English Duplicate in Primary Dropdown**:
- [x] Issue: English appeared twice (manually added + from availableSecondaryLanguages)
- [x] Fix: Filter out "en" when building primaryOptions

**Custom Words for Secondary Language (Touch Typing)**:
- [x] Issue: Secondary dictionary only loaded binary file, not custom words
- [x] Added `loadSecondaryCustomWords()` to WordPredictor
- [x] Custom words from `custom_words_${lang}` now added to secondary NormalizedPrefixIndex
- [x] Frequency converted to rank (0-255) for proper scoring

**Custom Words for Secondary Language (Swipe Typing)**:
- [x] Issue: Same as touch typing - secondary dict missing custom words
- [x] Added `loadSecondaryCustomWords()` to OptimizedVocabulary
- [x] Custom words now included in swipe beam search secondary lookups

**Secondary Prediction Weight Slider**:
- [x] Added `SECONDARY_PREDICTION_WEIGHT = 0.9f` constant to Config.Defaults
- [x] Added `secondary_prediction_weight` field to Config class
- [x] Added slider UI in SettingsActivity (Multi-Language section, 0.5x-1.5x range)
- [x] Updated WordPredictor.predictInternal() to use config value
- [x] Updated OptimizedVocabulary with cached `_secondaryPredictionWeight` field
- [x] Updated `updateLanguageMultiplier()` and `setAutoSwitchConfig()` to use config value

**Secondary Dictionary for Swipe Typing (True Bilingual)**:
- [x] Issue: Secondary dictionary only did accent recovery, not true bilingual predictions
- [x] NN beam search trie only contained primary language words
- [x] Fix: Add secondary dictionary words to `activeBeamSearchTrie` in `loadSecondaryDictionary()`
- [x] Log confirms: "+20847 added to beam trie" for Italian secondary

**Custom Words for NN Swipe Pipeline**:
- [x] Issue: Custom words added to vocabulary HashMap (for scoring) but NOT to beam search trie
- [x] NN couldn't predict custom words during swipe typing because they weren't in trie
- [x] Fix: Collect custom/user words into list, insert into `activeBeamSearchTrie` at end of `loadCustomAndUserWords()`
- [x] Log confirms: "Custom/user words: 5 words, +2 added to beam trie"

**Android User Dictionary Locale Filter (Swipe Pipeline)**:
- [x] Issue: OptimizedVocabulary loaded ALL Android user dictionary words regardless of language
- [x] This caused cross-language contamination (English words in French mode)
- [x] Fix was already applied to WordPredictor (v1.1.90) but NOT to OptimizedVocabulary
- [x] Fix: Add LOCALE filter to user dictionary query: `LOCALE = ? OR LOCALE LIKE ? OR LOCALE IS NULL`
- [x] Matches exact language code, or locale prefix (fr_FR), or global (null locale)

---

## v1.1.93 Fixes - COMPLETE

**English in Secondary Language Dropdown**:
- [x] Issue: English was explicitly excluded from secondary language options
- [x] Fix: Removed exclusion filter in `detectAvailableV2Dictionaries()`
- [x] Note: English uses V1 format, needs V2 conversion to work as secondary (TODO)

**Secondary Dictionary for Touch Typing**:
- [x] Issue: WordPredictor had no secondary dictionary support
- [x] Added `secondaryIndex: NormalizedPrefixIndex?` field to WordPredictor
- [x] Added `loadSecondaryDictionary()`, `unloadSecondaryDictionary()` methods
- [x] Modified `predictInternal()` to query secondary dictionary and merge results
- [x] Added `reloadWordPredictorSecondaryDictionary()` to PredictionCoordinator
- [x] Wired up preference change handler for secondary language changes

---

## v1.1.92 Fixes - COMPLETE

**Language-Specific Custom Words Keys**:
- [x] Issue: Custom words used legacy global key `"custom_words"` for ALL languages
- [x] This mixed French custom words with English custom words
- [x] Fix: Use `LanguagePreferenceKeys.customWordsKey(language)` → `"custom_words_${lang}"`
- [x] Updated WordPredictor.loadCustomAndUserWordsIntoMap() to use language-specific key
- [x] Updated WordPredictor.loadDisabledWords() to use language-specific key
- [x] Updated UserDictionaryObserver.loadCustomWordsCache() to use language-specific key
- [x] Updated UserDictionaryObserver.checkCustomWordsChanges() to use language-specific key
- [x] Updated SharedPreferences listener to watch for language-specific key changes

**Files Modified**:
- WordPredictor.kt: loadCustomAndUserWordsIntoMap(), loadDisabledWords(), setLanguage()
- UserDictionaryObserver.kt: prefsListener, loadCustomWordsCache(), checkCustomWordsChanges()

---

## v1.1.91 Fixes - COMPLETE

**Locale Format Matching Fix (19b10d9e)**:
- [x] Issue: v1.1.90 used exact locale match (`LOCALE = 'fr'`)
- [x] But Android uses full locale codes like `"en_US"`, `"fr_FR"`, `"fr_CA"`
- [x] Fix: Use `LIKE` for partial match: `LOCALE = ? OR LOCALE LIKE ? OR LOCALE IS NULL`
- [x] Now matches: `"fr"`, `"fr_FR"`, `"fr_CA"`, and `null` (global words)

**UserDictionaryObserver Locale Filtering (19b10d9e)**:
- [x] Issue: Observer had NO language filter - loaded ALL words from system UserDictionary
- [x] This caused English words to appear when user switched to French-only
- [x] Fix: Added `setLanguage(language)` method to observer
- [x] Observer now filters `loadUserDictionaryCache()` and `checkUserDictionaryChanges()` by locale
- [x] `WordPredictor.setLanguage()` now propagates to observer

## v1.1.90 Fixes - COMPLETE

**Touch Typing UserDictionary Contamination Fix (a94ab90d)**:
- [x] Root cause: `WordPredictor` loaded ALL words from Android UserDictionary regardless of language
- [x] This contaminated French-only touch typing with English user dictionary words
- [x] Swipe typing was fixed in v1.1.89 (dictionary regeneration)
- [x] Fix: Filter UserDictionary query by LOCALE column
- [x] Added `language` parameter to `loadCustomAndUserWords()` and `loadCustomAndUserWordsIntoMap()`
- [x] Query: `LOCALE = ? OR LOCALE IS NULL` (matches current lang or global words)
- [x] Pass language through async loading callback chain
- [x] Test: touch typing with French primary language

**Dictionary Regeneration (from previous session)**:
- [x] Root cause: fr/de/pt/it_enhanced.bin contained English "cognate" words
- [x] French: regenerated with 24722 pure words (removed 278 English-only words)
- [x] German: removed 168 English-only words
- [x] Portuguese: removed 180 English-only words
- [x] Italian: removed 153 English-only words
- [x] Swipe typing confirmed fixed by user

## v1.1.93-94 Fixes - COMPLETE

**Race Condition Fix (CRITICAL - 776ce3e8)**:
- [x] Root cause: `_primaryLanguageCode` was set BEFORE `activeBeamSearchTrie` was updated
- [x] Created window where `getVocabularyTrie()` saw:
  - `_primaryLanguageCode = "fr"` (already set by setPrimaryLanguageConfig)
  - `activeBeamSearchTrie` still pointing to English trie (loading not done)
- [x] Language mismatch check correctly returned null (safety)
- [x] Null trie = unconstrained beam search = English-sounding words
- [x] Fix: Set `_primaryLanguageCode` AFTER `activeBeamSearchTrie` in `loadPrimaryDictionary()`
- [x] `loadVocabulary()`: Use local variable for contraction logic, don't set `_primaryLanguageCode`
- [x] `setPrimaryLanguageConfig()`: Only set `_primaryLanguageCode` for English
- [x] `unloadPrimaryDictionary()`: Reset `_primaryLanguageCode` to "en"
- [x] Swipe typing confirmed fixed by user

## v1.1.92 Fixes - COMPLETED

**Thread Visibility Bug (b9a66bfa)**:
- [x] Root cause: `activeBeamSearchTrie` was NOT @Volatile
- [x] `loadPrimaryDictionary()` runs on init thread, writes new French trie
- [x] `getVocabularyTrie()` runs on main thread, reads stale cached English trie reference
- [x] Without @Volatile, CPU caching prevented cross-thread visibility
- [x] Added @Volatile to:
  - `activeBeamSearchTrie` (critical - beam search trie reference)
  - `normalizedIndex`, `secondaryNormalizedIndex` (accent lookups)
  - `_primaryLanguageCode`, `_secondaryLanguageCode`, `_englishFallbackEnabled` (language config)

## v1.1.91 Fixes - COMPLETE

**V2 Dictionary Format Support for WordPredictor (Touch Typing)**:
- [x] `BinaryDictionaryLoader.loadDictionary()` now supports both V1 and V2 formats
- [x] `BinaryDictionaryLoader.loadDictionaryWithPrefixIndex()` now supports V2 format (33979adb)
  - V1: loads pre-built prefix index from file
  - V2: loads canonical words and builds prefix index at runtime
  - This was the CRITICAL missing piece - AsyncDictionaryLoader uses this method
- [x] V1 format (magic 'DICT'): English dictionary, word+frequency pairs
- [x] V2 format (magic 'CKDT'): Non-English dictionaries with canonical words and frequency ranks
- [x] Rank-to-frequency conversion: rank 0 → ~1M, rank 255 → ~5K
- [x] Enables French, German, Italian, Portuguese, Spanish touch typing predictions

**Previous Fixes (v1.1.89-v1.1.90)**:
- [x] `PredictionCoordinator.initialize()` now called from ManagerInitializer (was missing)
- [x] `PreferenceUIUpdateHandler` reloads WordPredictor dictionary when language changes
- [x] Added `reloadWordPredictorDictionary()` method to PredictionCoordinator

**English Words in Beam Search (Diagnosis)**:
- [x] BeamSearchEngine runs UNCONSTRAINED when vocabTrie is null
- [x] `getVocabularyTrie()` returns null on language mismatch (primary≠en but trie is English)
- [x] Root cause identified: Thread visibility - @Volatile was missing (fixed in v1.1.92)

## v1.1.89 Fixes - COMPLETED

**Dictionary Manager Language Fix**:
- [x] `MainDictionarySource` was hardcoded to load `en_enhanced.json`
- [x] Added `languageCode` parameter to load correct language dictionary
- [x] `WordListFragment` now passes language code to `MainDictionarySource`
- [x] Binary dictionary loading added for non-English languages

**Beam Search Trie Defensive Check**:
- [x] `getVocabularyTrie()` now verifies trie matches expected language
- [x] If Primary=non-English but trie is still English, returns null (disables constraining)
- [x] Logs error message to help diagnose initialization issues
- [x] Added diagnostic logging to check for English words in non-English trie

**Autocorrect Language Contamination Fix**:
- [x] `WordPredictor.autoCorrect()` now skips when primary language is non-English
- [x] Fixes: "bereits" being inserted as "berries" in German mode
- [x] Root cause: autocorrect was fuzzy matching against English dictionary
- [x] Solution: Check `config.primary_language` before allowing autocorrect

**English Fuzzy Matching Fix**:
- [x] `OptimizedVocabulary.filterPredictions()` skips English vocab fuzzy matching
- [x] Skip condition: `_primaryLanguageCode != "en" && !_englishFallbackEnabled`
- [x] Prevents English words from "rescuing" rejected beam outputs

**Touch Typing Dictionary Fix**:
- [x] `PredictionCoordinator.initializeWordPredictor()` now loads `config.primary_language`
- [x] Previously hardcoded to "en", causing English predictions in French/German mode
- [x] `PredictionCoordinator.setConfig()` detects language changes and reloads dictionary
- [x] `WordPredictor.autoCorrect()` now uses loaded language dictionary

**Diagnostic Logging**:
- [x] BeamSearchEngine: Logs trie status (null vs active) on first masking call
- [x] BeamSearchEngine: Logs prefix masking details for debugging
- [x] OptimizedVocabulary: Logs if English test words found in non-English trie

---

## v1.1.88 Fixes - RELEASED

**Spanish Accent Key Fix (#40)**:
- [x] Fixed short gesture handling for dead keys (accent modifiers)
- [x] Dead keys like `accent_aigu` now LATCH instead of producing no output
- [x] Swipe SW on "d" → latches accent → tap "a" → produces "á"
- [x] Root cause: `onPointerDown/onPointerUp` doesn't latch modifiers
- [x] Fix: Detect `FLAG_P_LATCHABLE` and create latched pointer instead

**French Contraction Fix**:
- [x] Fixed "mappelle" → "m'appelle" not working when Primary=French, Secondary=None
- [x] Root cause #1: `_englishFallbackEnabled` was false, skipping vocabulary lookup
- [x] Fix #1: Check `nonPairedContractions` BEFORE filtering out the word
- [x] Root cause #2: `loadPrimaryDictionary()` created new trie, discarding contractions
- [x] Fix #2: Add contraction keys to the new language trie after creating it
- [x] Now beam search can discover "mappelle" and convert to "m'appelle"

**Legacy Dictionary Migration (v1.1.88)**:
- [x] `LanguagePreferenceKeys.migrateUserDictionary()` migrates legacy `user_dictionary` SharedPreferences
- [x] `BackupRestoreManager` export now uses language-specific format (`custom_words_by_language`)
- [x] `BackupRestoreManager` import handles both old (array) and new (language map) formats
- [x] Old JSON imports automatically migrate to English language-specific keys
- [x] Migration runs on app startup via `OptimizedVocabulary.loadCustomAndUserWords()`

---

## Language Pack Contractions (v1.1.87) - COMPLETE

**Problem**: Imported language packs (NL, ID, MS, SW, TL) didn't load contractions.

**Implementation**:
- [x] Updated `build_langpack.py` to include `contractions.json` in ZIP if present
- [x] Updated `extract_apostrophe_words.py` with Dutch 's plural handling
- [x] Created contraction files: nl (118 mappings), id/ms/sw/tl (empty - no apostrophes)
- [x] `LanguagePackManager` now extracts `contractions.json` from ZIP during import
- [x] Added `getContractionsPath(code)` method to LanguagePackManager
- [x] `ContractionManager` tries language pack first, falls back to assets
- [x] `OptimizedVocabulary` also updated to load from language packs

**Dictionary Manager Improvements (v1.1.87)**:
- [x] Added language change broadcast from SettingsActivity
- [x] DictionaryManagerActivity listens for `LANGUAGE_CHANGED` broadcasts
- [x] Tabs rebuild automatically when primary/secondary languages change
- [x] Added support for user-imported language pack Custom tabs
- [x] Modularized `setupViewPager()` with helper methods

**Testing Note**: Users with OLD imported langpacks need to re-import to get contractions.json extracted.

---

## Multilanguage Contractions/Apostrophes (v1.1.87) - COMPLETE

**Problem**: Swiping "cest" outputs "cest" instead of "c'est" in French mode.

**Root Causes Fixed**:
1. **preprocess_aosp.py** - `word.isalpha()` was filtering out apostrophe words
2. **Autocorrect** - WordPredictor was matching "cest" to "cent" (75% char match)
3. **OptimizedVocabulary** - `_primaryLanguageCode` defaulted to "en" before contractions loaded

**Implementation**:
- [x] Fixed preprocess_aosp.py to allow apostrophe characters in words
- [x] Created extract_apostrophe_words.py to extract from ASK dictionaries
- [x] Generated language contraction files:
  - contractions_fr.json: 27,494 mappings (cest→c'est, jai→j'ai, etc.)
  - contractions_it.json: 22,474 mappings (luomo→l'uomo, etc.)
  - contractions_de.json: 24 mappings (gehts→geht's, etc.)
  - contractions_en.json: 122 mappings (dont→don't, etc.)
- [x] Added isContractionKey() to ContractionManager for autocorrect bypass
- [x] InputCoordinator now skips autocorrect for contraction keys
- [x] ManagerInitializer loads language-specific contractions at startup
- [x] OptimizedVocabulary.loadVocabulary() now accepts primaryLanguageCode parameter
- [x] SwipePredictorOrchestrator passes primary language from prefs to loadVocabulary
- [x] Added cache reload logic for non-English language contractions

**Testing Verified**:
- [x] Verify "cest" → "c'est" transformation in French mode ✓
- [x] Verify "jai" → "j'ai" transformation ✓
- [x] Verify "dont" → "don't" in English mode ✓
- [x] Verify autocorrect doesn't corrupt contraction keys to similar words ✓

**Key Files Modified**:
- `scripts/preprocess_aosp.py` - Allow apostrophe words
- `scripts/extract_apostrophe_words.py` - NEW: Extract from ASK dictionaries
- `src/main/kotlin/.../ContractionManager.kt` - isContractionKey(), loadLanguageContractions()
- `src/main/kotlin/.../InputCoordinator.kt` - Skip autocorrect for contraction keys
- `src/main/kotlin/.../ManagerInitializer.kt` - Load language-specific contractions
- `src/main/kotlin/.../OptimizedVocabulary.kt` - loadVocabulary(primaryLanguageCode)
- `src/main/kotlin/.../onnx/SwipePredictorOrchestrator.kt` - Pass language to loadVocabulary

---

## Multilanguage Full Support (v1.1.85) - COMPLETE

**Implementation Summary**:
- [x] Primary Language selector (any QWERTY-compatible language)
- [x] Neural network outputs 26 English letters; dictionary provides accent recovery
- [x] 6 bundled languages: EN, ES, FR, PT, IT, DE
- [x] 9 downloadable language packs: FR, PT, IT, DE, NL, ID, MS, SW, TL
- [x] Primary dictionary loads accent mappings (e.g., "cafe" → "café")
- [x] Secondary dictionary unchanged (bilingual support)

**Key Files Modified**:
- `scripts/build_all_languages.py` - Master script to generate all dictionaries
- `scripts/get_wordlist.py` - wordfreq extraction with fallback (large→small→best)
- `src/main/kotlin/tribixbite/cleverkeys/OptimizedVocabulary.kt` - loadPrimaryDictionary(), getPrimaryAccentedForm()
- `src/main/kotlin/tribixbite/cleverkeys/SettingsActivity.kt` - Primary Language dropdown
- `src/main/kotlin/tribixbite/cleverkeys/onnx/SwipePredictorOrchestrator.kt` - loadPrimaryDictionaryFromPrefs()

**Bundled Dictionaries** (in assets/dictionaries/):
| Language | File | Size | Words |
|----------|------|------|-------|
| English | en_enhanced.bin | 1.2MB | ~50k |
| Spanish | es_enhanced.bin | 6.7MB | ~236k |
| French | fr_enhanced.bin | 616KB | 25k |
| Portuguese | pt_enhanced.bin | 619KB | 25k |
| Italian | it_enhanced.bin | 630KB | 25k |
| German | de_enhanced.bin | 650KB | 25k |

**Language Packs** (in scripts/dictionaries/):
| Language | File | Size | Source |
|----------|------|------|--------|
| Dutch | langpack-nl.zip | 244KB | wordfreq |
| Indonesian | langpack-id.zip | 232KB | wordfreq |
| Malay | langpack-ms.zip | 228KB | wordfreq |
| Swahili | langpack-sw.zip | 231KB | Kwici Wikipedia Corpus (CC-BY-SA) |
| Tagalog | langpack-tl.zip | 237KB | wordfreq |

**Bugs Fixed (2026-01-04)**:
- [x] Primary dictionary lookup order: Now checks primary FIRST, then falls back to English
- [x] English fallback disabled: When Primary=French, Secondary=None → only French predictions
- [x] Multilang toggle bug: Dictionary now loads for ANY non-English primary
- [x] **CRITICAL FIX**: Language-specific beam search tries (each language has own trie)
- [x] **CRITICAL FIX**: Language dictionary not reloading on preference change (47cc00ef)
  - PreferenceUIUpdateHandler now triggers reloadPrimaryDictionary/reloadSecondaryDictionary
  - Previously dictionaries only loaded at initialization
  - Changing language in settings now immediately updates beam search trie

**Dictionary Verification (2026-01-04)**:
- [x] FR: V2 format, 29k canonical, 23.7k normalized (être, café, français ✓)
- [x] ES: V2 format, 236k canonical, 223k normalized (niño, español, años ✓)
- [x] PT: V2 format, 30k canonical, 24.5k normalized (você, não, também ✓)
- [x] IT: V2 format, 30k canonical, 24.8k normalized (perché, più, città ✓)
- [x] DE: V2 format, 26k canonical, 24.8k normalized (für, über, größe ✓)

**Architecture Documentation**:
- NEW: `docs/specs/neural-multilanguage-architecture.md` - Complete pipeline documentation

**The Fix Explained** (Refactored - cleaner architecture):
Each language now has its own beam search trie built from normalized words:

```
vocabularyTrie (English)      ← always loaded
activeBeamSearchTrie          ← points to current language's trie

Primary=French:
  French Dict → normalize → French Trie → activeBeamSearchTrie
  Beam search uses ONLY French words
  etre → être (post-processing)

Primary=English:
  activeBeamSearchTrie = vocabularyTrie (English)
```

No mixing of languages in a single trie - clean separation.

**Testing Needed** (manual test required - device locked):
- [x] Dictionary verification completed programmatically (2026-01-04)
- [ ] Verify Primary Language dropdown shows EN, ES, FR, PT, IT, DE
- [ ] Test accent recovery: swipe "cafe" → "café" with French primary
- [ ] Test French-only words: swipe "etre" → "être" (architecture now supports this)
- [ ] Test French-only words: swipe "francais" → "français"
- [ ] Confirm no English-only words appear when Primary=French, Secondary=None
- [ ] Test language reload on preference change (settings → keyboard)

**Completed (v1.1.86)**:
- [x] Split custom/disabled words by language in storage layer (061fc67e)
  - `LanguagePreferenceKeys.kt` - Helper for language-specific preference keys
  - `OptimizedVocabulary.kt` - Uses `custom_words_{lang}` and `disabled_words_{lang}` keys
  - `DisabledDictionarySource` - Accepts optional languageCode parameter
  - Automatic migration from global keys to English keys on first run
  - Spec: `docs/specs/language-specific-dictionary-manager.md`
- [x] Add language-specific tabs to Dictionary Manager UI (32856e92)
  - Multilang mode: Active [EN], Disabled [EN], Custom [EN], User Dict, Active [ES], Disabled [ES], Custom [ES]
  - Single language mode: Standard tabs with language label if non-English
  - Tab layout uses MODE_SCROLLABLE when >4 tabs
  - WordListFragment accepts optional languageCode parameter

---

## F-Droid Submission Status

### MR !30449 - In Progress
- [x] Remove pre-built binaries (JAR, .so, .bin files)
- [x] Add compose source files (srcs/compose/)
- [x] Create scripts/generate_compose_bin.py for build-time generation
- [x] Add generateComposeData gradle task
- [x] Update .gitignore for F-Droid compliance
- [x] Add 512x512 icon.png for fastlane metadata
- [x] Fix python → python3 for F-Droid build environment
- [x] Fix Groovy spread operator incompatibility
- [x] Remove duplicate compileComposeSequences task
- [x] Fix shift constant case mismatch
- [x] Lower SDK from 35 to 34 for androguard compatibility
- [x] Downgrade androidx.core to 1.13.1 for SDK 34 compatibility
- [x] Add novcheck to bypass androguard APK version parsing issue
- [x] Implemented semantic versioning system (vMAJOR.MINOR.PATCH)
  - versionCode = MAJOR * 10000 + MINOR * 100 + PATCH
  - ABI versionCode = base * 10 + abiCode (1=armv7, 2=arm64, 3=x86_64)
- [x] Updated GitHub Actions release workflow for semantic versions
- [x] Created docs/VERSIONING.md documentation
- [x] Fixed F-Droid schema validation (AutoUpdateMode, VercodeOperation array format)
- [x] Fixed APK output pattern (wildcard for arm64-v8a)
- [x] Clean start: deleted old releases (v1.0.0, v1.1.0, v2.0.0) for fresh submission
- [x] Added static version variables in build.gradle for F-Droid checkupdates parsing
- [x] Enabled auto-update (UpdateCheckMode: Tags ^v[0-9]+\.[0-9]+\.[0-9]+$)
- [x] Created first official release: v1.0.0
- [x] GitHub Actions v1.0.0 release succeeded (3 APKs published)
- [x] F-Droid pipeline 2212215842: ALL 8 JOBS SUCCESS with auto-update enabled!
- [x] Fix permission warnings for clean install experience (2025-12-13)
  - Removed RECORD_AUDIO (voice typing uses external IME)
  - Removed REQUEST_INSTALL_PACKAGES (F-Droid handles updates)
  - Removed RECEIVE_BOOT_COMPLETED, WAKE_LOCK, storage permissions
  - Only VIBRATE and READ_USER_DICTIONARY remain
- [x] Remove self-update feature (2025-12-13)
  - Removed legacy external storage APIs (Environment.getExternalStorageDirectory)
  - Removed checkForUpdates, installUpdateFromDefault, APK picker functions
  - F-Droid handles updates - no need for in-app update mechanism
  - Storage usage now fully scoped storage compliant
- [x] Fix neural settings defaults mismatch (2025-12-13)
  - NeuralSettingsActivity had hardcoded defaults (4, 35) instead of Defaults.* (6, 20)
  - SwipePredictorOrchestrator had hardcoded fallbacks instead of Defaults.*
  - All neural parameter defaults now use Defaults.* constants for consistency
- [x] Add short swipe customizations to backup system (2025-12-13)
  - Short swipe customizations stored in separate JSON file were NOT backed up
  - Now exported as `short_swipe_customizations` in config backup JSON
  - Import restores customizations automatically
- [x] Fix resetToDefaults() in NeuralSettingsActivity (2025-12-13)
  - resetToDefaults() had hardcoded values (4, 35, 0.1f, etc.)
  - Now uses Defaults.* constants for consistency with all settings screens
- [x] Rebase F-Droid MR (2025-12-13)
  - Rebased cleverkeys branch against upstream/master
  - Added v1.0.1 build entry to metadata
  - Pipeline passed successfully
- [x] Fix app name bug in release builds (2025-12-13)
  - resValue was using "@string/app_name_release" literal (doesn't resolve)
  - Fixed to use literal strings: "CleverKeys" for release, "CleverKeys (Debug)" for debug
  - Released as v1.0.2
- [x] Fix version mismatch bug in build.gradle (2025-12-13)
  - defaultConfig had hardcoded versionCode 10000/versionName "1.0.0"
  - ext.versionCode was updated to newer versions
  - APKs were built with wrong versionCode, breaking F-Droid reproducibility
  - Fixed by syncing both defaultConfig and ext values
  - Removed v1.0.2 from F-Droid metadata (had the bug)
  - Released as v1.0.3 with fix
- [x] Play Protect now shows "App safe" (2025-12-13)
  - After v1.0.2 fix, Play Protect no longer flags as suspicious
- [x] F-Droid pipeline SUCCESS (2025-12-13)
  - Removed binary verification (source builds don't match GitHub release byte-for-byte)
  - Added output/novcheck for proper ABI-specific APK handling
  - All 3 APKs (armv7, arm64, x86_64) built successfully
  - Pipeline 2213182520: ALL JOBS PASSED
- [x] Fix neural prediction not working in release builds (2025-12-13)
  - Root cause: proguard-rules.pro had wrong package name (tribixbite.keyboard2 → tribixbite.cleverkeys)
  - R8 was stripping ONNX/neural classes in release APKs
  - Added comprehensive keep rules for all neural prediction classes
- [x] Fix APK naming for Obtainium (2025-12-13)
  - Changed arm64.apk → arm64-v8a.apk, armv7.apk → armeabi-v7a.apk
  - Proper ABI names for app store compatibility
- [x] Released v1.0.4 (2025-12-13)
- [x] Fix swipe prediction accuracy regression in release builds (2025-12-13)
  - Root cause: R8 stripping ONNX inner classes (PredictionPostProcessor.Result, BeamSearchEngine.BeamState)
  - Added `$**` pattern to keep all inner classes in onnx package
  - Added Keyboard2View field preservation for NeuralLayoutHelper reflection access
  - Added comprehensive rules for dictionary, vocabulary, customization, theme classes
  - Added JNI-specific rules and Kotlin metadata attributes
  - 128 new proguard rules total
- [x] Verified ONNX execution provider configuration (2025-12-13)
  - ModelLoader tries XNNPACK first (FP32, most stable), then NNAPI as fallback
  - NNAPI's potential FP16 precision issues are avoided
  - SessionConfigurator.kt is dead code (not used by prediction pipeline)
- [x] Released v1.0.5 (2025-12-13)
- [x] Added fastlane changelogs for v1.0.3-1.0.5 (2025-12-14)
- [x] Fixed version mismatch: synced defaultConfig with ext values (2025-12-14)
  - linsui flagged that build.gradle#L143 had wrong versionCode/versionName
  - ext had 1.0.6 but defaultConfig had 1.0.4 - now both are 1.0.6
- [x] Released v1.0.6 with correct versions (2025-12-14)
- [x] Added v1.0.6 builds to fdroiddata metadata (2025-12-14)
- [x] Rebased fdroiddata fork onto upstream/master (2025-12-14)
- [x] Implemented single source of truth versioning system (2025-12-16)
  - VERSION_MAJOR/MINOR/PATCH as single source in build.gradle ext block
  - defaultConfig references ext values (no duplication)
  - CI verification step in release.yml to fail if tag doesn't match version
- [x] Fixed compose_data.bin determinism issue (2025-12-16)
  - Added sorted() to compose_files iteration in generate_compose_bin.py
  - os.listdir() returned arbitrary filesystem-dependent order
- [x] Removed novcheck from all fdroiddata metadata entries (2025-12-16)
- [x] Fixed status/navigation bar color overlay on OEM devices (2025-12-16)
  - Added enableEdgeToEdge() API in LauncherActivity
  - Updated launcherTheme to use transparent system bars
  - Restructured Compose layout for proper edge-to-edge display
- [x] Fixed keyboard navigation bar showing background color (2025-12-16)
  - Set keyboard navigation bar to transparent in Keyboard2View
  - Allows keyboard to extend behind nav bar on gesture nav devices
- [x] Fixed suggestion bar collapse when empty (2025-12-16)
  - Added minimum width (200dp) to SuggestionBar
  - Enabled fillViewport on suggestion scroll view
- [x] Moved ui-tooling to debugImplementation for reproducible builds (2025-12-16)
  - Jetpack Compose ui-tooling can embed machine-specific paths
  - Now excluded from release APKs, only included in debug builds
- [x] **REPRODUCIBILITY SPRINT** (2025-12-18)
  - Identified build-tools 35.0.0 breaks apksigcopier (F-Droid issue #3299)
  - Downgraded to build-tools 34.0.0 in all environments
  - Released v1.1.27 with fixed toolchain
  - Got Gemini second opinion via zen-mcp on reproducibility config
  - Implemented Gemini recommendations:
    - [x] Exact Temurin 21.0.9+10 JDK download in F-Droid metadata
    - [x] TZ=UTC, LANG=en_US.UTF-8, LC_ALL=en_US.UTF-8 env vars
    - [x] Updated GitHub workflow with same locale/timezone settings
    - [x] Updated build-on-termux.sh for local consistency
  - F-Droid metadata ready in fdroiddata_temp/ (not tracked in git)
- [x] Submit updated F-Droid metadata MR
- [x] Multiple reproducibility fixes (v1.1.56-v1.1.70)
  - v1.1.65: Removed inline python script per linsui feedback
  - v1.1.66: Added `--internal` flag to fix zipalign compatibility
  - v1.1.67: Removed META-INF rm-files step (not needed for reproducibility)
  - v1.1.68: Tested without fix-pg-map-id - build passed, checkupdates timing issue
  - v1.1.69: Restored fix-pg-map-id as fallback
  - v1.1.70: Confirmed fix-pg-map-id NOT needed! Simplest possible config works!
- [x] Comprehensive F-Droid metadata update (2025-12-20)
  - Updated short_description (48 chars, emphasizes Termux)
  - Rewrote full_description with Termux focus, per-key gestures emphasis
  - Added featureGraphic.jpg for F-Droid Latest tab
  - Reorganized screenshots (Termux first, clean numbered names)
  - Added v1.1.70 changelogs
  - Added video.txt with per-key customization demo
  - Added Spanish (es) translation for F-Droid Latest tab visibility
  - Fixed "Sub-200ms" → "Sub-100ms" for accurate latency claim
- [x] F-Droid MR #30449 merged (2025-12-21) - CleverKeys now on F-Droid!
- [x] Cron monitoring: checks MR status every 5 min, notifies on merge
- [x] Fix web demo model loading on CDN edge cases (2025-12-21)
  - validateFile() now falls back to range request when HEAD lacks content-length
  - Fixes "Tokenizer config appears incomplete (0KB < 0.5KB)" error on some CDNs
- [x] Fix decoder src_mask mismatch (2025-12-21)
  - Decoder was using all-zeros src_mask (attending to padded garbage)
  - Now passes actualSrcLength from encoder to decoder
  - Decoder creates matching mask: 1 for padded positions, 0 for real data
- [x] Expand CommandRegistry with 75+ new commands (2025-12-21)
  - Added 32 new Android KeyEvent codes (media, volume, brightness, zoom, system/app keys)
  - Added 5 new categories: MEDIA, SYSTEM, DIACRITICS_SLAVONIC, DIACRITICS_ARABIC, HEBREW
  - Added 10 Slavonic, 14 Arabic, 20 Hebrew diacritical marks
  - Updated CustomShortSwipeExecutor with fallback for character-based commands
  - Updated XmlAttributeMapper for XML export compatibility
  - Total commands now 200+ (up from 137)
- [x] Add icon font support for custom swipe mappings (2025-12-22)
  - ShortSwipeMapping now has useKeyFont field for icon rendering
  - DirectionMapping storage updated to v2 schema with useKeyFont
  - CommandRegistry.getDisplayInfo() extracts icon + font flag from KeyValue
  - KeyMagnifierView renders custom mappings with proper special_font.ttf
  - Keyboard2View uses theme's sublabel_paint for consistent sizing
  - CommandPaletteDialog auto-detects icon mode from command's KeyValue
  - Custom mappings now match font size/style of layout's default subkeys
- [x] Fix custom sublabel color and icon preview (2025-12-23)
  - Keyboard2View: use subLabelColor instead of activatedColor for custom mappings
  - KeyMagnifierView: use consistent subLabelColor for both custom and built-in sublabels
  - CommandPaletteDialog: show readable description [Tab], [Home] for PUA icons
  - Clear UX guidance in label dialog for icon vs text mode
- [x] Fix keyboard overlapping navigation bar on API 30-34 (2025-12-24)
  - onApplyWindowInsets() only processed insets on API 35+, but edge-to-edge was enabled on API 29+
  - API 30+: Use modern WindowInsets.Type.systemBars() API
  - API 21-29: Fall back to deprecated systemWindowInsets
  - Keyboard now properly accounts for nav bar height on all supported API levels
- [x] Switch margin settings from dp to percentages (2025-12-24)
  - Changed margin_bottom to % of screen height (0-30%)
  - Split horizontal_margin into margin_left and margin_right (0-45% each)
  - Added 90% total horizontal margin cap with dynamic slider ranges
  - Keyboard2View uses Config.margin_left/margin_right instead of horizontal_margin
  - BackupRestoreManager migrates legacy dp-based configs to percentages
  - Migration converts old horizontal_margin to symmetric left/right percentages
- [x] Fix Direct Boot crash in PrivacyManager (2025-12-24)
  - SharedPreferences in credential-encrypted storage unavailable at lock screen
  - Caused keyboard crash before device unlock, locking users out
  - Now uses createDeviceProtectedStorageContext() on API 24+
  - Matches DirectBootAwarePreferences pattern used elsewhere
- [x] Comprehensive Direct Boot compatibility fix (2025-12-24)
  - Bug present since v1.0.0 - multiple SharedPreferences classes crashed at lock screen
  - Created DirectBootManager utility for deferred PII initialization
  - Moved non-PII managers to Device Encrypted storage:
    - CustomThemeManager, MaterialThemeManager
    - ModelVersionManager, NeuralModelMetadata, NeuralPerformanceStats
  - Deferred PII components until user unlock via ACTION_USER_UNLOCKED:
    - DictionaryManager, UserAdaptationManager, WordPredictor
    - ClipboardHistoryService (uses SQLite, needs CE storage)
  - Privacy: PII data stays in secure CE storage, only deferred until unlock
  - Added cleanup in CleverKeysService.onDestroy()
- [x] Block clipboard pane access on lock screen (2025-12-24)
  - Security fix: clipboard history contains PII, was accessible on lock screen
  - Initial fix used isUserUnlocked (Direct Boot state) - only blocked before first unlock
  - Fixed: Added isDeviceLocked property using KeyguardManager.isKeyguardLocked()
  - isUserUnlocked: false only before FIRST unlock since boot (Direct Boot)
  - isDeviceLocked: true whenever screen is currently locked (keyguard showing)
  - KeyboardReceiver now uses isDeviceLocked to block clipboard on lock screen
- [x] Fix margin prefs restored by Android Auto-Backup (2025-12-24)
  - Bug: Old dp-based margin values restored from Google Drive backup
  - Interpreted as percentages (14dp → 14%, way too large)
  - Added `margin_prefs_version` flag to track if migration occurred
  - Added `migrateMarginPrefs()` that runs on every startup
  - If flag missing, ALL margin values converted from dp to percentages
  - No threshold guessing needed - flag distinguishes old vs new installs
- [x] Touch typing suggestion bar improvements (2025-12-25)
  - Added trailing space after tapping suggestion (better touch typing flow)
  - Only skip trailing space when actually IN Termux app, not just mode enabled
  - Applied shift/capitalization to touch typing predictions in suggestion bar
  - First letter capitalized if user started typing with Shift
  - Fixed potential word deletion bug: clear lastAutoInsertedWord when starting new typed word
  - Prevents incorrectly deleting swiped word when user types then taps prediction
- [x] Fix keyboard below nav bar on first load (2025-12-25)
  - onApplyWindowInsets wasn't triggering re-layout after insets changed
  - Added requestLayout() call when insets change
  - Added onAttachedToWindow() override that calls requestApplyInsets()
  - Keyboard now correctly positions above nav bar immediately
- [x] Fix keyboard height setting not applying (2025-12-25)
  - ROOT CAUSE: Settings saved to "keyboard_height_percent" but Config read from "keyboard_height"
  - Fixed key name mismatch in SettingsActivity.kt (3 locations)
  - Also: Theme cache key didn't include config.version
  - Added config.version to cache key to invalidate on any config change
- [x] Fix landscape margins not applying on rotation (2025-12-25)
  - ROOT CAUSE: Missing onConfigurationChanged() override in CleverKeysService
  - Config.refresh() was never called on orientation change
  - Added override that calls refresh_config() to update landscape margin values
- [x] Fix swipe NN key coordinate mapping for non-uniform margins (2025-12-25)
  - **Part 1: ProbabilisticKeyDetector (nearest key detection during swipe)**
    - ROOT CAUSE: Key positions calculated starting at x=0 instead of marginLeft
    - Added marginLeft parameter to constructor and key position calculations
    - Fixed width calculation: pass key area width only (excluding margins)
  - **Part 2: SwipeTrajectoryProcessor (neural network input normalization)**
    - ROOT CAUSE: X normalization divided by total width, not key area width
    - Before: `x = rawX / keyboardWidth` (wrong when margins present)
    - After: `x = (rawX - marginLeft) / keyAreaWidth`
    - Added setMargins(left, right) method threaded through orchestrator chain
    - NeuralLayoutHelper now passes config.margin_left/margin_right to neural engine
  - Both fixes required for correct swipe typing with non-uniform margins
- [x] Fix ONNX init not retrying after Direct Boot failure (2025-12-26)
  - ROOT CAUSE: SwipePredictorOrchestrator set isInitialized=true in finally block
  - Even when model loading failed (e.g., during lock screen), flag prevented retry
  - After device unlock, subsequent keyboard opens returned cached failure result
  - FIX: Only set isInitialized=true when isModelLoaded is true
  - Also reset isInitialized in cleanup() to allow re-initialization
  - Symptoms: swipe typing not working until manually toggled off/on in settings
- [x] Password field eye toggle feature (2025-12-30)
  - Detect password/PIN input fields (all Android InputType variations)
  - Disable predictions and autocorrect in password fields
  - Material Design visibility icons (ic_visibility.xml, ic_visibility_off.xml)
  - Eye toggle in suggestion bar with theme colors
  - RelativeLayout with START_OF constraint for fixed icon position
  - HorizontalScrollView with requestDisallowInterceptTouchEvent for scrolling
  - InputConnectionProvider syncs with actual field content
  - Dots (●) when hidden, actual text when visible
  - Centered when short, scrollable when long
  - Files: SuggestionBar.kt, SuggestionHandler.kt, CleverKeysService.kt
  - Spec: docs/specs/password-field-mode.md

- [x] SwipeDebugActivity UI overhaul (2025-12-30)
  - Added back arrow, title "Swipe Debug Log", auto-focus input
  - Single-line scrollable input with debug log viewer
  - Copy and save icons for debug log output
  - Save to file uses Storage Access Framework file picker
- [x] Wire debug logger through inference pipeline (2025-12-31)
  - Fixed setDebugLogger in SwipePredictorOrchestrator (was TODO stub)
  - Chain: CleverKeysService → PredictionCoordinator → NeuralSwipeTypingEngine → SwipePredictorOrchestrator
  - Added debugModeActive flag to gate expensive string building
  - Propagation through DebugModePropagator → NeuralSwipeTypingEngine → SwipePredictorOrchestrator → SwipeTrajectoryProcessor
- [x] Comprehensive debug logging for swipe inference (2025-12-31)
  - Touch trace coordinates (first/last 5 points)
  - Detected key sequence with start/end key analysis
  - Out-of-bounds point counting
  - Normalization parameters (keyboard dims, margins, QWERTY bounds, Y-offset)
  - Raw-to-normalized coordinate transformations with clamping warnings
  - Timing breakdown (feature extraction, encoder, decoder, post-processing)
  - Raw beam search output before vocabulary filtering
- [x] Deep analysis: Training vs Android feature calculation (2026-01-01)
  - Restored training files from git history to `model/` folder
  - Verified feature calculation matches Python training:
    - Timestamps: milliseconds with 1e-6 minimum ✓
    - Velocity: dx/dt (normalized coords / ms) ✓
    - Acceleration: dvx/dt ✓
    - Clipping: [-10, 10] ✓
    - Token mapping: a=4..z=29 ✓
  - Small velocities (0.0001 range) are EXPECTED - model was trained on this
  - Attempted ms→s conversion made predictions WORSE (confirmed training used ms)
- [x] Fix debug logging to use proper debugLogger pattern (2026-01-01)
  - Previous logging used android.util.Log.e() which goes to logcat
  - Replaced with debugLogger?.invoke() to send to SwipeDebugActivity
  - Added debugLogger field and setDebugLogger() to InputCoordinator
  - Wired up in CleverKeysService.onCreate() via DebugLoggingManager
  - Debug messages now gated behind user's debug mode setting
- [x] Beam search deduplication fix (2026-01-01)
  - Added HashSet<List<Long>> to deduplicate beams by token sequence
  - Prevents identical words appearing multiple times in predictions
  - Fixed SOS/PAD token masking (set to -infinity instead of skipping)
- [x] Beam search early termination fix for long words (2026-01-01)
  - Root cause: ADAPTIVE_WIDTH_STEP=5 and SCORE_GAP_STEP=3 terminated too early
  - Short words like "danger" (6 chars) finished before "dangerously" (11 chars) could complete
  - Increased ADAPTIVE_WIDTH_STEP: 5→12 (don't prune width until longest common words done)
  - Increased SCORE_GAP_STEP: 3→10 (don't early-stop until long words have a chance)
  - Increased scoreGapThreshold: 5.0→8.0 (wider gap before triggering early stop)
- [x] SwipeDebugActivity text overflow fix (2026-01-01)
  - Changed input field from right-aligned to left-aligned (gravity: start)
  - Added HorizontalScrollView with proper scroll calculation
  - Text scrolls to show cursor position as user types
- [x] Custom short swipe support for Event and Editing commands (2026-01-07)
  - Issue: Per-key customization commands not working: settings, clipboard, voice typing, numeric switch, AI assistant, text replace
  - Root cause: CustomShortSwipeExecutor only handled InputConnection-based commands
  - Event-type commands (config, switch_clipboard, switch_numeric, voice_typing, voice_typing_chooser) require service.triggerKeyboardEvent()
  - Editing-type commands (replaceText, textAssist, autofill) require InputConnection.performContextMenuAction()
  - Fix: Added KeyValue-based execution path in onCustomShortSwipe() for Event and Editing kinds
  - Also fixed VOICE_INPUT in legacy AvailableCommand fallback
- [x] Icon preview fix in per-key customization UI (2026-01-07)
  - Issue: PUA characters (icons for settings, clipboard, voice, etc.) displayed as Chinese characters
  - Root cause: Compose Text() uses system font which doesn't support Private Use Area chars
  - Fix: Use AndroidView with Theme.getKeyFont() for icon rendering in:
    - MappingListItem: Shows icon when useKeyFont=true
    - CommandPaletteDialog preview: Renders actual icon instead of [Icon: name]

## Active Investigation: English Words in French-Only Mode

**Status**: DIAGNOSTIC LOGGING ADDED (83ea45f7) - Awaiting test results

**Problem**: User reports English words like "every", "word", "this" appear when Primary=French, Secondary=None (French-only mode), even after service restart.

**Investigation Progress**:
1. Verified French contraction fix works ("mappelle" → "m'appelle") ✓
2. Verified Italian contractions use same generic fix ✓
3. French dictionary (fr_enhanced.bin) exists and has 616KB (~25k words)
4. `loadPrimaryDictionary()` code path looks correct:
   - Creates new `languageTrie` from French normalized words
   - Adds contraction keys to trie
   - Replaces `activeBeamSearchTrie` with French trie
5. `getVocabularyTrie()` dynamically returns `activeBeamSearchTrie`
6. `BeamSearchEngine` gets trie on every prediction (not cached)

**Diagnostic Logging Added**:
- `getVocabularyTrie()`: logs isLanguageTrie, trieWords, primaryLanguage, englishFallback
- `loadPrimaryDictionary()`: logs before/after trie replacement status

**Potential Root Causes to Verify**:
1. Preference `pref_primary_language` not being read correctly
2. French dictionary not loading (file missing or corrupt)
3. Something resetting `activeBeamSearchTrie` after initialization
4. Race condition between async init and first prediction

**User Testing Required**:
1. Force stop app
2. Start app and open keyboard
3. Make a swipe gesture
4. Check logcat: `adb logcat | grep "getVocabularyTrie\|loadPrimaryDictionary"`
5. Expected logs should show:
   - `loadPrimaryDictionary() called with language='fr'`
   - `isLanguageTrie=true` (NOT false)
   - `trieWords=~25000` (French trie, NOT ~50000 English)

**Key Files**:
- `OptimizedVocabulary.kt`: Trie initialization and getVocabularyTrie()
- `SwipePredictorOrchestrator.kt`: loadPrimaryDictionaryFromPrefs()
- `BeamSearchEngine.kt`: applyTrieMasking() using vocabTrie

---

## Active Investigation: Long Word Prediction

**Status**: CRITICAL FIX APPLIED (22fc3279) - Length normalization in beam search confidence

**Previous Bug**: "dangerously" (11 chars) couldn't beat shorter words like "dames" (5 chars)

**Root Cause #1 (Fixed 2026-01-01)**: Beam search early termination too aggressive
- ADAPTIVE_WIDTH_STEP: 5→12, SCORE_GAP_STEP: 3→10, scoreGapThreshold: 5.0→8.0

**Root Cause #2 (CRITICAL - Fixed 2026-01-02)**: Final confidence NOT length-normalized!
- Length normalization was only applied during beam search SORTING (to keep candidates alive)
- But final confidence in `convertToCandidate()` used raw score: `exp(-score)`
- Longer words accumulate more NLL (negative log-likelihood) over more decoding steps
- Even with perfect per-step probability, longer words ALWAYS had lower confidence

**Before Fix**:
- "dames" (5 chars, NLL ~1.05) → confidence = exp(-1.05) = **0.35**
- "dangerously" (11 chars, NLL ~1.97) → confidence = exp(-1.97) = **0.14**

**After Fix** (same normalization formula as beam sorting):
- normFactor = (5 + len)^alpha / 6^alpha
- "dames": exp(-1.05/1.87) = exp(-0.56) = **0.57**
- "dangerously": exp(-1.97/3.58) = exp(-0.55) = **0.58**

Now confidence values are COMPARABLE across word lengths!

**Cleanup Complete (2026-01-02)**:
- [x] Removed length bonus feature entirely (was redundant after core fix)
- [x] Kept beam alpha (Length Penalty) as the proper GNMT tuning knob
- [x] Removed vestigial neural_model_version setting (stored but never used)

**Neural Settings Enhancements (2026-01-02)**:
- [x] Fixed NEURAL_MAX_LENGTH default: 15→20 (match model config)
- [x] Added Temperature setting (0.1-3.0) for softmax confidence tuning
- [x] Added Frequency Weight setting (0-2) for NN vs vocabulary frequency balance
- [x] Fixed repair defaults to use Defaults.* constants consistently
- [x] Added NeuralPreset enum (Speed/Balanced/Accuracy) in Config.kt
- [x] Added preset selector UI with FilterChips in NeuralSettingsActivity
- [x] Written KV cache optimization spec (docs/specs/kv-cache-optimization.md)
- [x] Written MemoryPool optimization spec (docs/specs/memory-pool-optimization.md)
- [x] Wired temperature into BeamSearchEngine logSoftmax (c40ec131)
  - Applied as `logits / temperature` before softmax
  - Lower temp = sharper, higher = more uniform distribution
- [x] Wired neural_frequency_weight into OptimizedVocabulary scoring (c40ec131)
  - Applied as multiplier on existing frequency weight
  - 0.0 = NN only, 1.0 = normal, 2.0 = heavy frequency influence
  - Applied consistently in main scoring and dictionary fuzzy matching

**Defaults Aligned (2026-01-02 ed79d668)**:
- NEURAL_FREQUENCY_WEIGHT: 1.0f → 0.57f (trust NN more, less freq bias)
- NEURAL_SCORE_GAP_STEP: 10 → 12 (delay early stopping)
- Matched values from working config export

**Testing Needed**:
- [ ] Test first-load NN fix: clear app data, verify swipe works on first try
- [ ] Test: swipe "dangerously" in SwipeDebugActivity
- [ ] Verify confidence values are now length-normalized
- [ ] Confirm long words now competitive with short words
- [ ] Test neural presets (Speed/Balanced/Accuracy) in NeuralSettingsActivity
- [ ] Test temperature slider effect on prediction sharpness
- [ ] Test frequency weight slider (0=pure NN, 2=heavy frequency)

**Completed (2026-01-03)**:
- [x] Add View and Delete buttons to Collected Data settings (27a5bccc)
  - View: Opens dialog showing all collected swipes with stats
  - Delete: Confirmation dialog to clear all data
  - Dialog shows: target word, date, keys traversed, trace points, collection source

**Completed (2026-01-04)**:
- [x] Fix NN/swipe typing not working on very first app load (7aebb6a1)
  - Root cause: Race condition between async engine init and layout listener
  - OnGlobalLayoutListener fired before neural engine finished loading
  - Listener removed itself after first layout, never calling setNeuralKeyboardLayout()
  - Fix: Keep listener active until BOTH conditions met (engine ready AND layout done)
  - Added post-initialization requestLayout() to trigger listener after engine loads
- [x] Reverted unnecessary GC optimization in trajectory processor (263ec18f)
  - User questioned added complexity; optimization was imperceptible to users
- [x] Fix keyboard behind nav bar on first display (system resource fallback)
  - Root cause: onMeasure runs before onApplyWindowInsets callback
  - Fix: Get nav bar height from `navigation_bar_height` system resource in onAttachedToWindow
- [x] Fix GitHub issue #34: Android 8.1 (API 27) crash
  - Error: NoSuchMethodError for getSystemWindowInsets()
  - Root cause: wi.systemWindowInsets compiles to getSystemWindowInsets() returning Insets (API 29+)
  - Fix: Split API branches - API 29 uses systemWindowInsets, API 21-28 uses individual systemWindowInsetLeft/Right/Bottom
- [x] Multilanguage architecture finalized with Gemini consultation
  - Accent handling: Normalize NN output (cafe) → lookup canonical (café)
  - Binary format v2: Trie-based with normalized keys, frequency ranks (0-255)
  - Multi-dict merging: SuggestionRanker with unified scoring
  - Language detection: Word-based unigram frequency model
  - Dictionary sources: AOSP (CC BY 4.0) + wordfreq/FrequencyWords (CC BY-SA 4.0)
  - Updated docs/specs/dictionary-and-language-system.md with full implementation plan

---

## Multilanguage Implementation Roadmap

### Phase 1: Foundation (v1.2.0) ✅ COMPLETE
- [x] Implement `AccentNormalizer` (Unicode NFD + accent stripping)
- [x] Create `NormalizedPrefixIndex` mapping normalized → canonical
- [x] Update `BinaryDictionaryLoader` for v2 format
- [x] Build script: `scripts/build_dictionary.py`
- [x] Generate Spanish dictionary from AOSP (236k words, 31.5% accented)

### Phase 2: Multi-Dictionary (v1.2.1) ✅ COMPLETE
- [x] Implement `SuggestionRanker` for unified scoring
  - WordSource enum with priority weights (CUSTOM > USER > SECONDARY > MAIN)
  - Scoring formula: nnConfidence × rankScore × langMultiplier × sourcePriority
  - rankAndMerge() combines primary + secondary with deduplication
- [x] Wire NormalizedPrefixIndex into OptimizedVocabulary
  - loadSecondaryDictionary() loads V2 binary format
  - createSecondaryCandidates() generates ranker candidates from NN predictions
  - getAccentedForm() maps 26-letter NN output to accented canonical forms
- [x] Add V2 support to MultiLanguageDictionaryManager
  - normalizedIndexes ConcurrentHashMap for V2 dictionaries
  - loadNormalizedIndex() with caching and error handling
  - createCandidatesFromNnPredictions() for SuggestionRanker integration
- [x] Spanish dictionary included in assets (es_enhanced.bin - 236k words)
- [x] UI: Settings → Multi-Language → Secondary Language picker (Phase 2b)
  - Dynamically detects available V2 dictionaries in assets
  - Shows display names for 25+ supported languages
  - Persists to pref_secondary_language preference
- [x] Wire preference to load secondary dictionary on startup
  - SwipePredictorOrchestrator.loadSecondaryDictionaryFromPrefs()
  - Automatically loads/unloads based on user preference
- [x] Integrate secondary dictionary into filterPredictions
  - Maps 26-letter NN output to accented forms
  - Scoring: NN confidence × 0.6 + frequency rank × 0.3 × secondary penalty
  - Deduplication prevents duplicates from both dictionaries
  - Debug logging with 🌍 emoji for secondary matches

### Phase 3: Language Detection (v1.2.2) ✅ COMPLETE
- [x] Implement `UnigramLanguageDetector` (word-based)
  - Sliding window of 10 recent words
  - Weighted scoring by frequency rank
  - Cached scores with invalidation
- [x] Ship unigram lists for bundled languages
  - `generate_unigrams.py` script using wordfreq library
  - EN: 5000 words (top frequency, alphabetic only)
  - ES: 5000 words (top frequency, alphabetic only)
- [x] Wire language detection into prediction flow
  - SuggestionHandler.updateContext() → trackCommittedWord()
  - CleverKeysService.onStartInputView() → clearLanguageHistory()
  - SwipePredictorOrchestrator manages detector lifecycle
  - Debug logging with 🌍 emoji for language scores

### Phase 4: Auto-Switching (v1.2.3) ✅ COMPLETE
- [x] Add dynamic language multiplier in OptimizedVocabulary
  - `updateLanguageMultiplier()` adjusts scoring based on detected language
  - `setAutoSwitchConfig()` configures threshold and secondary language
- [x] Wire to existing Settings UI
  - "Auto-Detect Language" toggle → `pref_auto_detect_language`
  - "Detection Sensitivity" slider → `pref_language_detection_sensitivity`
- [x] Multiplier formula:
  - Secondary language > threshold: boost (1.1 + bonus)
  - Primary language > threshold: penalty (0.85)
  - Balanced: neutral (1.0)
- [x] Update language scores after each committed word

### Phase 5: Language Packs (v1.1.84) ✅ COMPLETE
- [x] Language Pack ZIP format spec
  - manifest.json: code, name, version, author, wordCount
  - dictionary.bin: V2 binary dictionary with accent normalization
  - unigrams.txt: word frequency list for language detection
- [x] LanguagePackManager: import, validation, storage
  - ZIP import via Storage Access Framework (no internet needed)
  - Validates manifest.json and dictionary.bin magic/version
  - Stores in app internal storage: files/langpacks/{code}/
- [x] Settings UI: Import Pack + Manage dialog
- [x] Build scripts:
  - build_langpack.py: creates language pack ZIPs from word lists
  - get_wordlist.py: extracts word lists from wordfreq library
- [x] Dictionary loading integration (v1.1.84):
  - BinaryDictionaryLoader.loadIntoNormalizedIndexFromFile() for file paths
  - OptimizedVocabulary.loadSecondaryDictionary() checks packs first, then assets

---

## Previously Verified (Feature Calculation)

| Aspect | Python | Android | Match |
|--------|--------|---------|-------|
| Timestamps | ms, min 1e-6 | ms, min 1e-6 | ✅ |
| Velocity | dx/dt | dx/dt | ✅ |
| Acceleration | dvx/dt | dvx/dt | ✅ |
| Clipping | [-10, 10] | [-10, 10] | ✅ |
| Token map | a=4..z=29 | a=4..z=29 | ✅ |
| Coordinates | [0,1] normalized | [0,1] normalized | ✅ |

**Current Version**: 1.1.84 (versionCode 101843 for x86_64)
**GitHub Release**: https://github.com/tribixbite/CleverKeys/releases/tag/v1.1.84
**F-Droid MR**: https://gitlab.com/fdroid/fdroiddata/-/merge_requests/30449
**Final Config**: No srclibs, no postbuild - just gradle + prebuild sed!

---

## Release Process Quick Reference

### Version Locations (Single Source of Truth)
```
build.gradle (lines 51-53):
  ext.VERSION_MAJOR = 1
  ext.VERSION_MINOR = 1
  ext.VERSION_PATCH = 78
```

### VersionCode Formula
```
ABI versionCodes (per-APK for F-Droid):
  armeabi-v7a: MAJOR * 100000 + MINOR * 1000 + PATCH * 10 + 1  (e.g., 101721)
  arm64-v8a:   MAJOR * 100000 + MINOR * 1000 + PATCH * 10 + 2  (e.g., 101722)
  x86_64:      MAJOR * 100000 + MINOR * 1000 + PATCH * 10 + 3  (e.g., 101723)
```

### New Release Workflow
```bash
# 1. Bump VERSION_PATCH in build.gradle
# 2. Add changelogs: fastlane/metadata/android/en-US/changelogs/{versionCode}.txt

# 3. Commit and tag
git add -A && git commit -m "v1.1.XX: description"
git tag v1.1.XX && git push && git push origin v1.1.XX

# 4. Wait for GitHub Actions Release workflow to complete

# 5. Update F-Droid metadata
cd ~/git/fdroiddata-fork
git fetch origin cleverkeys && git reset --hard FETCH_HEAD
# Edit metadata/tribixbite.cleverkeys.yml - add new version build entries
git add . && git commit -m "Update CleverKeys to vX.X.XX" && git push origin cleverkeys

# 6. Monitor F-Droid pipeline
curl -s "https://gitlab.com/api/v4/projects/fdroid%2Ffdroiddata/merge_requests/30449/pipelines" | jq '.[0]'
```

### F-Droid Metadata Location
```
~/git/fdroiddata-fork/metadata/tribixbite.cleverkeys.yml
```

### F-Droid Build Entry Format
```yaml
  - versionName: 1.1.72
    versionCode: 101721  # or 101722, 101723 for other ABIs
    commit: {full-commit-hash}
    gradle:
      - yes
    binary: https://github.com/tribixbite/CleverKeys/releases/download/v%v/CleverKeys-v%v-{abi}.apk
    prebuild: sed -i -e "s/include 'armeabi-v7a'.*/include '{abi}'/" build.gradle
```

### Fastlane Changelogs
```
fastlane/metadata/android/en-US/changelogs/{versionCode}.txt
```
One changelog file per ABI versionCode (101711.txt, 101712.txt, 101713.txt)

### Legacy Code Audit (2025-12-17)
Technical debt identified but not blocking F-Droid submission:

**Activities using legacy base classes:**
- [ ] `DictionaryManagerActivity` → `AppCompatActivity` (should migrate to ComponentActivity)
- [ ] `SwipeCalibrationActivity` → `Activity` (very old base class)
- [ ] `SwipeDebugActivity` → `Activity`
- [ ] `TemplateBrowserActivity` → `Activity`

**Ghost Activities in AndroidManifest (no source files):**
- [x] `tribixbite.cleverkeys.NeuralBrowserActivity` - REMOVED from manifest
- [x] `tribixbite.cleverkeys.neural.NeuralBrowserActivityM3` - REMOVED from manifest
- [x] `tribixbite.cleverkeys.TestActivity` - REMOVED from manifest

**Legacy Themes:**
- [ ] `appTheme` uses `Theme.AppCompat.DayNight.DarkActionBar` (used by settingsTheme)
- [x] Fixed `launcherTheme` to use `Theme.Material3.Dark.NoActionBar` (2025-12-17)
- [x] Fixed `windowDrawsSystemBarBackgrounds=true` for proper edge-to-edge (2025-12-17)

**Deprecated APIs in prefs package:**
- Multiple `android.preference.*` deprecation warnings (ListGroupPreference, LayoutsPreference)
- Should migrate to `androidx.preference.*` eventually

### Versioning Workflow
1. Development: `dev-{sha}` with versionCode 1
2. Release: Tag with `vX.Y.Z` and push
3. GitHub Actions automatically creates release with APKs
4. F-Droid automatically detects new tags and builds

---

## Pending Items

### Web Demo Fixes (P0 - Critical) ✅ COMPLETED
*See full analysis: `docs/specs/web_demo_flaws.md`*

**Architecture Mismatch (Model Input) - FIXED 2025-12-12**:
- [x] Fix velocity calc: use `dx/dt` not just `dx` (time-normalized)
- [x] Fix acceleration calc: use `dv/dt` not just `dv`
- [x] Add value clipping to [-10, 10] range
- [x] Collect timestamps during swipe tracking
- [x] Change MAX_SEQUENCE_LENGTH from 150 to 250
- [x] Update model_config.json max_seq_length to 250

**UI Bugs - FIXED 2025-12-12**:
- [x] Delete empty file `web_demo/niche-word-loader.js` (0 bytes duplicate)
- [x] Fix shift key - now produces uppercase correctly
- [x] Fix number mode - fixed CSS selector pattern
- [x] Gate console.log behind DEBUG flag (global wrapper)

### Web Demo P1 Fixes ✅ COMPLETED
*See full analysis: `docs/specs/web_demo_flaws_v2.md`*

**State Management - FIXED 2025-12-12**:
- [x] handleBackspace state sync (inputText vs currentTypedWord)
- [x] handleSpace commits currentTypedWord properly, prevents double spaces
- [x] handleReturn commits pending typed word before newline

**Mode Toggle Conflicts - FIXED 2025-12-12**:
- [x] toggleNumberMode/toggleEmojiMode mutual exclusion
- [x] resetModeButtons() helper for consistent styling

**Keyboard Layout - FIXED 2025-12-12**:
- [x] Number mode row count (was 10 items, fixed to 9)
- [x] resizeCanvas updates keyboardBounds (orientation changes)

### Custom Dictionary Fixes ✅ COMPLETED (2025-12-13)
- [x] Fix constructor not calling mergeIntoVocabulary on page load
- [x] Fix removeWord not unboosting from vocabulary (added originalFrequencies tracking)
- [x] Allow boosting existing vocabulary words (removed rejection)
- [x] Fix clearAll to properly reset vocabulary state

### Web Demo Improvements (P2) - PARTIALLY COMPLETED
- [x] Add accessibility attributes (aria-*, role, tabindex) - 2025-12-13
- [x] Remove debug test functions from global scope - 2025-12-13
- [x] Improve model loading error handling - 2025-12-13
  - Pre-flight validation with size checks (catches incomplete LFS downloads)
  - Better error categorization (404, incomplete, network, WASM, memory)
  - Retry button in error UI
- [ ] Consider lazy loading for 12.5MB of models
- [ ] Add PWA/Service Worker for offline support

### Settings UI Polish (from settings_audit.md)
- [ ] Add "Swipe Sensitivity" preset (Low/Medium/High) to simplify 5 distance settings
- [ ] Standardize units across distance settings (all pixels or all % of key size)
- [ ] Consider further section merges (14 → 11 sections per audit proposal)
- [ ] Move Vibration setting from Input to Appearance or Accessibility
- [ ] Move Smart Punctuation from Input to Auto-Correction
- [ ] Move Pin Entry Layout from Input to Appearance

### Documentation
- [ ] Update `docs/specs` with any new architectural changes

---

## Verified Working (Dec 2025)

### Import/Export (from Settings -> Backup & Restore)
- Config import/export with proper metadata/preferences structure
- Dictionary import handles both old (user_words array) and new (custom_words object) formats
- Clipboard import with duplicate detection
- **New**: Layout Profile Import/Export (with Custom Gestures)

### Theme Manager (from Settings -> Appearance -> Theme Manager card)
- Theme selection now applies correctly (saves to "theme" preference)
- Gemstone themes: Ruby, Sapphire, Emerald
- Neon themes: Electric Blue, Hot Pink, Lime Green

### Short Swipe Customization
- Full 8-direction customization per key
- Colored direction zones
- Shift key support
- "Select All" and other commands fully functional

---

## Session Notes (Dec 20, 2025)

### Fixed: Spacebar Subkey Gestures Blocked by Swipe Typing
**Commits**: `17b0d301`, `c6c89705`

**Problem**: Horizontal and vertical swipes on spacebar (cursor_left/right, switch_forward/backward) only produced 2-3 actions instead of the expected range (15-88 for cursor, layout switch for vertical).

**Root Cause**: Spacebar's `key0="space"` is `Char` kind, so `shouldCollectPath=true` for swipe typing. This caused an early return in `onTouchMove()` before Slider or Event key activation could occur.

**Fix**: Added pre-swipe-typing check in `Pointers.kt` that detects Slider and Event keys BEFORE swipe typing path collection:
- Slider keys (cursor_left/right): Enter sliding mode immediately
- Event keys (switch_forward/backward): Trigger event immediately

**Layout Switching Note**: `switch_forward`/`switch_backward` require 2+ named layouts configured in Settings → Layouts. The default `SystemLayout` returns null and doesn't count as switchable.

---

*See `docs/history/session_log_dec_2025.md` for completed items from recent sprints.*