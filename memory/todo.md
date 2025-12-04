# CleverKeys Working TODO List

**Last Updated**: 2025-12-04 (Dead Code Cleanup Complete)
**Session**: Settings UI fixes and dead code removal

---

## Current Session Tasks

### Completed (2025-12-04 - Dead Code Cleanup)
- [x] Remove old settings UI (SettingsPreferenceActivity.kt, SettingsPreferenceFragment.kt)
- [x] Remove dead ABTestManager.kt (was never integrated anywhere)
- [x] Remove dead ModelComparisonTracker.kt (only used by ABTestManager)
- [x] Remove dead SwipeAdvancedSettings.kt (unused settings model)
- [x] Remove res/xml/settings.xml and settings_compat.xml
- [x] Remove SettingsPreferenceActivity from AndroidManifest.xml
- [x] Remove unused openFullSettings() from SettingsActivity.kt
- [x] Build and test - APK builds successfully (54MB)

### Completed (2025-12-04 - Settings UI Fixes)
- [x] Fix Dictionary Manager access - was opening Android system settings, now launches 4-tab DictionaryManagerActivity
- [x] Add DictionaryManagerActivity to AndroidManifest.xml (was removed)
- [x] Expose Theme Manager in Appearance section with prominent card
- [x] Default Appearance section to expanded so Theme Manager is visible

### Pending (Future Sessions)
- [ ] Create optional enhancement specs (clipboard, dictionary, privacy)
- [ ] Manual device testing for Theme Manager and Dictionary Manager
- [ ] Consider migrating prefs/ classes to modern Compose-compatible format

---

## Important Findings

### Dead Code Removed (2025-12-04)
The following files were removed as dead code:
- `SettingsPreferenceActivity.kt` - Old XML-based settings (replaced by Compose SettingsActivity)
- `SettingsPreferenceFragment.kt` - Old fragment-based settings UI
- `ABTestManager.kt` - Never integrated into app workflow
- `ModelComparisonTracker.kt` - Only used by ABTestManager
- `SwipeAdvancedSettings.kt` - Unused data class
- `res/xml/settings.xml` - Old settings XML
- `res/xml/settings_compat.xml` - Old compat settings XML

### prefs/ Folder Retained
The `prefs/` folder was NOT removed because these classes are used by:
- `Config.kt` - Layout loading and extra keys management
- `SubtypeManager.kt` - IME subtype handling
- `LayoutManagerActivity.kt` - Layout selection UI
- `BackupRestoreManager.kt` - Configuration backup/restore

Classes retained:
- `LayoutsPreference.kt` - Layout serialization/deserialization
- `ListGroupPreference.kt` - Generic list preference storage
- `ExtraKeysPreference.kt` - Extra keys configuration
- `CustomExtraKeysPreference.kt` - Custom extra keys

### Privacy/Rollback Settings Status
- **Privacy settings**: Still functional via `PrivacyManager.kt`
- **Rollback**: `ModelVersionManager.kt` exists but has no second model to roll back to
- **A/B Testing**: Removed entirely (was dead code)

### UK vs CK Differences
- CK has swipe trail settings (5 settings) not in UK
- CK has different defaults for: longPressInterval (25 vs 65), characterSize (1.18 vs 1.15)

---

## Files Modified This Session
- `AndroidManifest.xml` - Removed SettingsPreferenceActivity, added DictionaryManagerActivity
- `src/main/kotlin/tribixbite/cleverkeys/SettingsActivity.kt` - Theme Manager card, expanded Appearance, fixed dictionary launch
- Deleted: 7 files (see Dead Code Removed section)

---

## Reference Links
- See `docs/questions.md` for detailed analysis
- See `docs/specs/README.md` for spec index
- See `docs/TABLE_OF_CONTENTS.md` for master file navigation
