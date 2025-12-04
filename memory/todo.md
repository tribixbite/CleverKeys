# CleverKeys Working TODO List

**Last Updated**: 2025-12-04
**Session**: Theme Manager migration, DirectBootAwarePreferences fix

---

## Completed This Session (2025-12-04)

### Theme Manager Migration (Major UI Overhaul)
- [x] Add 18 built-in XML themes to ThemeSettingsActivity with preview cards
- [x] Each theme shows name, description, and mini keyboard preview
- [x] Selected theme highlighted with purple border and checkmark
- [x] Built-in themes save correct IDs that Config.kt recognizes
- [x] Remove theme dropdown from SettingsActivity Appearance section
- [x] Remove unused theme helper functions (getThemeIndexFromName, etc.)
- [x] Theme Manager card is now the sole entry point for theme selection

### Theme Application Bug Fix (CRITICAL)
- [x] Root cause: ThemeSettingsActivity used `PreferenceManager.getDefaultSharedPreferences()`
- [x] But CleverKeysService uses `DirectBootAwarePreferences.get_shared_preferences()`
- [x] On API 24+, these are TWO DIFFERENT FILES (default vs device protected storage)
- [x] Fix: Call `DirectBootAwarePreferences.copy_preferences_to_protected_storage()` after saving theme

### Import/Export System Overhaul (Previous)
- [x] Fix dictionary import - SettingsActivity now uses BackupRestoreManager
- [x] Fix clipboard import - SettingsActivity now uses ClipboardDatabase.importFromJSON()
- [x] Fix config (kb-config) import - uses BackupRestoreManager.importConfig() with proper validation
- [x] Fix dictionary export - uses BackupRestoreManager.exportDictionaries()
- [x] Fix clipboard export - uses BackupRestoreManager.exportClipboardHistory()
- [x] Fix config export - uses BackupRestoreManager.exportConfig()

### Default Settings Fixes
- [x] Set default portrait keyboard height to 27% (was 35%)
- [x] Set default Swipe Pattern Data collection to OFF (privacy-friendly default)
- [x] Set default Performance Metrics collection to OFF (privacy-friendly default)

### BackupRestoreManager Dictionary Import Fix (Previous Session)
- [x] Handle both `custom_words` (object format) and `user_words` (array format)
- [x] Export format uses `custom_words: { "word": frequency }` object structure

### ClipboardDatabase Import Fix (Previous Session)
- [x] Fix duplicate check logic - was using `return@use` which didn't skip loop iteration

---

## Verified Working

### Import/Export (from Settings -> Backup & Restore)
- Config import/export with proper metadata/preferences structure
- Dictionary import handles both old (user_words array) and new (custom_words object) formats
- Clipboard import with duplicate detection

### Theme Manager (from Settings -> Appearance -> Theme Manager card)
- Theme selection now applies correctly (saves to "theme" preference)
- Gemstone themes: Ruby, Sapphire, Emerald
- Neon themes: Electric Blue, Hot Pink, Lime Green

### Default Settings
- Portrait keyboard height defaults to 27%
- Privacy settings default to OFF (data collection disabled by default)

---

## Pending (Future Sessions)

- [ ] Manual device testing for all import/export features
- [ ] Create optional enhancement specs (clipboard, dictionary, privacy)
- [ ] Consider full Material 3 theme migration (requires Theme.MaterialComponents as base)

---

## Important Technical Notes

### Import/Export Architecture
SettingsActivity uses BackupRestoreManager for all import/export operations:
- `performConfigImport/Export` -> BackupRestoreManager.importConfig/exportConfig
- `performDictionaryImport/Export` -> BackupRestoreManager.importDictionaries/exportDictionaries
- `performClipboardImport/Export` -> BackupRestoreManager.exportClipboardHistory / ClipboardDatabase.importFromJSON

### Theme System (IMPORTANT)
- ThemeSettingsActivity saves to SharedPreferences key `"theme"`
- **CRITICAL**: Must also call `DirectBootAwarePreferences.copy_preferences_to_protected_storage()`
  after saving for keyboard to see the change (API 24+ uses device protected storage)
- Config.kt reads from `"theme"` preference via `getThemeId()`
- Theme IDs: cleverkeysdark, cleverkeyslight, dark, light, black, altblack, white,
  rosepine, cobalt, pine, desert, jungle, epaper, epaperblack, monet, monetlight,
  monetdark, everforestlight

### Privacy Defaults
PrivacyManager defaults changed in 4 places:
- canCollectSwipeData() default: false
- canCollectPerformanceData() default: false
- getSettings().collectSwipeData default: false
- getSettings().collectPerformanceData default: false

---

## Files Modified This Session
- `src/main/kotlin/tribixbite/cleverkeys/ThemeSettingsActivity.kt` - Theme Manager UI, DirectBootAwarePreferences fix
- `src/main/kotlin/tribixbite/cleverkeys/SettingsActivity.kt` - Removed theme dropdown, kept Theme Manager card
- `src/main/kotlin/tribixbite/cleverkeys/Config.kt` - Portrait height default 27%
- `src/main/kotlin/tribixbite/cleverkeys/PrivacyManager.kt` - Privacy defaults to OFF
- `src/main/kotlin/tribixbite/cleverkeys/BackupRestoreManager.kt` - Dictionary import format fix (previous session)
- `src/main/kotlin/tribixbite/cleverkeys/ClipboardDatabase.kt` - Duplicate check fix (previous session)
