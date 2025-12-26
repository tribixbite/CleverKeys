# CleverKeys Working TODO List

**Last Updated**: 2025-12-26
**Status**: v1.1.78 - ONNX Init Retry Fix

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

**Current Version**: 1.1.78 (versionCode 101783 for x86_64)
**GitHub Release**: https://github.com/tribixbite/CleverKeys/releases/tag/v1.1.78
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
- [ ] `tribixbite.cleverkeys.NeuralBrowserActivity` - declared but doesn't exist
- [ ] `tribixbite.cleverkeys.neural.NeuralBrowserActivityM3` - declared but doesn't exist
- [ ] `tribixbite.cleverkeys.TestActivity` - declared but doesn't exist

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