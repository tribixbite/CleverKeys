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
