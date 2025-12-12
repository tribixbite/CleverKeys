# CleverKeys Working TODO List

**Last Updated**: 2025-12-12
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
- [x] Fix shift constant case mismatch (v1.1.1586-747b7082)
- [x] Disable ABI splits for F-Droid compatibility (v1.1.1588-9ac39331)
- [x] Lower SDK from 35 to 34 for androguard compatibility (v1.1.1590-6aa9b9ef)
- [x] Downgrade androidx.core to 1.13.1 for SDK 34 compatibility (v1.1.1593-c3af5334)
- [x] Add novcheck to bypass androguard APK version parsing issue
- [ ] Wait for F-Droid CI pipeline to pass
- [ ] Address any maintainer feedback

**Note**: F-Droid does support ABI splits with different versionCodes per ABI (see [issue #2115](https://gitlab.com/fdroid/fdroiddata/-/issues/2115)). Currently disabled, could re-enable with proper per-ABI versioning if needed for APK size reduction.

---

## Pending Items

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