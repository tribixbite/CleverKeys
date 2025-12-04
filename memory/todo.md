# CleverKeys Working TODO List

**Last Updated**: 2025-12-04
**Session**: Import/Export fixes, default settings fixes, theme system cleanup

---

## Completed This Session (2025-12-04)

### Import/Export System Overhaul
- [x] Fix dictionary import - SettingsActivity now uses BackupRestoreManager
- [x] Fix clipboard import - SettingsActivity now uses ClipboardDatabase.importFromJSON()
- [x] Fix config (kb-config) import - uses BackupRestoreManager.importConfig() with proper validation
- [x] Fix dictionary export - uses BackupRestoreManager.exportDictionaries()
- [x] Fix clipboard export - uses BackupRestoreManager.exportClipboardHistory()
- [x] Fix config export - uses BackupRestoreManager.exportConfig()

### Dictionary Import Storage Fix
- [x] Fixed BackupRestoreManager.importDictionaries() to store in correct format
- [x] CustomDictionarySource reads "custom_words" as JSON object {"word": freq}
- [x] DisabledDictionarySource reads "disabled_words" as StringSet
- [x] Import now uses DirectBootAwarePreferences (same as dictionary sources)
- [x] Export now uses DirectBootAwarePreferences for consistency

### Theme System Cleanup
- [x] Removed broken Theme Manager card from SettingsActivity
- [x] ThemeSettingsActivity used PredefinedThemes system (gemstone_ruby, etc.)
- [x] PredefinedThemes system is NOT connected to actual keyboard rendering
- [x] Actual themes are in res/values/themes.xml (Dark, Light, RosePine, etc.)
- [x] Config.kt getThemeId() maps theme names to R.style resources
- [x] Theme dropdown in SettingsActivity works correctly with all 19 themes

### Previous Session Fixes (carried forward)
- [x] Theme preference key mismatch - ThemeSettingsActivity saved to wrong key
- [x] Default portrait keyboard height to 27% (was 35%)
- [x] Default Swipe Pattern Data collection to OFF
- [x] Default Performance Metrics collection to OFF

---

## Verified Working

### Import/Export (from Settings -> Backup & Restore)
- Config import/export with proper metadata/preferences structure
- Dictionary import handles custom_words object format correctly
- Words appear in Dictionary Manager Custom tab after import
- Clipboard import with duplicate detection

### Theme System
- Theme dropdown in Settings shows all 19 themes from themes.xml
- Theme selection saves to "theme" preference correctly
- Config.kt reads and applies themes correctly

### Default Settings
- Portrait keyboard height defaults to 27%
- Privacy settings default to OFF (data collection disabled by default)

---

## Technical Notes

### Theme Architecture (Important!)
There are TWO separate theme systems in the codebase:

1. **Working System** (res/values/themes.xml + Config.kt):
   - XML styles define all theme colors (Dark, Light, RosePine, etc.)
   - Config.kt getThemeId() maps theme names to R.style resources
   - Theme.kt reads colors from styled attributes
   - Keyboard2View uses Theme.Computed for rendering

2. **Unused System** (PredefinedThemes.kt + ThemeSettingsActivity):
   - KeyboardColorScheme objects with Compose colors
   - NOT connected to actual keyboard rendering
   - ThemeSettingsActivity was saving IDs Config.kt doesn't recognize
   - This system was created but never integrated

### Dictionary Storage Format
- CustomDictionarySource: "custom_words" pref as JSON `{"word": frequency, ...}`
- DisabledDictionarySource: "disabled_words" pref as StringSet
- Both use DirectBootAwarePreferences.get_shared_preferences()

---

## Files Modified This Session
- `src/main/kotlin/tribixbite/cleverkeys/BackupRestoreManager.kt` - Dictionary import/export format fix
- `src/main/kotlin/tribixbite/cleverkeys/SettingsActivity.kt` - Removed broken Theme Manager card
- `memory/todo.md` - Updated documentation

---

## Pending (Future Sessions)

- [ ] Consider deleting unused PredefinedThemes.kt and ThemeSettingsActivity
- [ ] Manual device testing for dictionary import appearing in manager
- [ ] Consider adding keyboard preview to theme dropdown
