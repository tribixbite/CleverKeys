# CleverKeys Working TODO List

**Last Updated**: 2025-12-04 (Session Complete)
**Session**: Settings UI fixes, dead code removal, Material 3 compatibility fixes

---

## Completed This Session (2025-12-04)

### Dead Code Removal
- [x] Remove SettingsPreferenceActivity.kt (old XML-based settings)
- [x] Remove SettingsPreferenceFragment.kt (old fragment-based settings)
- [x] Remove ABTestManager.kt (never integrated - dead code)
- [x] Remove ModelComparisonTracker.kt (only used by ABTestManager)
- [x] Remove SwipeAdvancedSettings.kt (unused settings model)
- [x] Remove res/xml/settings.xml and settings_compat.xml
- [x] Remove unused openFullSettings() from SettingsActivity.kt

### Settings UI Fixes
- [x] Fix Dictionary Manager access - now launches 4-tab DictionaryManagerActivity
- [x] Add DictionaryManagerActivity to AndroidManifest.xml
- [x] Expose Theme Manager in Appearance section with prominent card
- [x] Default Appearance section to expanded

### Material 3 Compatibility Fixes
- [x] Replace MaterialButton with standard Button in dictionary layouts
- [x] Replace MaterialSwitch with SwitchCompat
- [x] Replace ?attr/colorSurface with ?android:attr/colorBackground
- [x] Replace ?attr/colorOnSurface with ?android:attr/textColorPrimary
- [x] Replace Widget.Material3 styles with AppCompat-compatible colors

---

## Verified Working

### Dictionary Manager (from Settings -> Dictionary -> Manage Custom Words)
- 4 tabs: Active (49059), Disabled (0), User Dict (0), Custom (1)
- Search input with filter dropdown
- Word list with frequency and toggle switches
- Dark theme UI

### Theme Manager (from Settings -> Appearance -> Theme Manager card)
- Gemstone themes: Ruby, Sapphire, Emerald
- Neon themes: Electric Blue, Hot Pink, Lime Green
- Keyboard preview with Trail button
- Create custom theme with + button

---

## Pending (Future Sessions)

- [ ] Create optional enhancement specs (clipboard, dictionary, privacy)
- [ ] Manual device testing for all settings features
- [ ] Consider full Material 3 theme migration (requires Theme.MaterialComponents as base)

---

## Important Technical Notes

### Theme Compatibility
The app uses `Theme.AppCompat.DayNight.DarkActionBar` as the base theme. Material 3 components (MaterialButton, MaterialSwitch, colorSurface attributes) require `Theme.MaterialComponents` or `Theme.Material3` as the base theme. All Dictionary Manager layouts have been converted to use AppCompat-compatible components.

### prefs/ Folder Retained
The `prefs/` folder was NOT removed because these classes are used by:
- `Config.kt` - Layout loading and extra keys management
- `SubtypeManager.kt` - IME subtype handling
- `LayoutManagerActivity.kt` - Layout selection UI

---

## Files Modified This Session
- `AndroidManifest.xml` - Removed SettingsPreferenceActivity, added DictionaryManagerActivity
- `src/main/kotlin/tribixbite/cleverkeys/SettingsActivity.kt` - Theme Manager card, fixed dictionary launch
- `res/layout/activity_dictionary_manager.xml` - AppCompat components
- `res/layout/fragment_word_list.xml` - AppCompat attributes
- `res/layout/item_word_editable.xml` - Standard Button, AppCompat colors
- `res/layout/item_word_toggle.xml` - SwitchCompat
- `src/main/kotlin/tribixbite/cleverkeys/DictionaryManagerActivity.kt` - Button import

### Deleted Files
- `src/main/kotlin/tribixbite/cleverkeys/SettingsPreferenceActivity.kt`
- `src/main/kotlin/tribixbite/cleverkeys/SettingsPreferenceFragment.kt`
- `src/main/kotlin/tribixbite/cleverkeys/ABTestManager.kt`
- `src/main/kotlin/tribixbite/cleverkeys/ModelComparisonTracker.kt`
- `src/main/kotlin/tribixbite/cleverkeys/SwipeAdvancedSettings.kt`
- `res/xml/settings.xml`
- `res/xml/settings_compat.xml`
