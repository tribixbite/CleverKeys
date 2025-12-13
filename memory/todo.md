# CleverKeys Working TODO List

**Last Updated**: 2025-12-13
**Status**: F-Droid Submission (MR !30449)

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
- [ ] Address any maintainer feedback (MR !30449)

**Current Version**: 1.0.0 (versionCode 100002 for arm64-v8a)
**GitHub Release**: https://github.com/tribixbite/CleverKeys/releases/tag/v1.0.0
**F-Droid MR**: https://gitlab.com/fdroid/fdroiddata/-/merge_requests/30449

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

*See `docs/history/session_log_dec_2025.md` for completed items from recent sprints.*