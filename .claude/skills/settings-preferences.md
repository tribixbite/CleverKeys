# Settings & Preferences Skill

Use this skill when adding, modifying, or removing settings/preferences in the CleverKeys app.

## Architecture

**Hybrid**: AndroidX Preferences (legacy) + Jetpack Compose (current). New settings use Compose.

## Key Files

| File | Purpose |
|------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | All defaults, preference reading, setter methods |
| `src/main/kotlin/tribixbite/cleverkeys/SettingsActivity.kt` | Compose-based settings UI (~4800 lines) |
| `src/main/kotlin/tribixbite/cleverkeys/ConfigurationManager.kt` | Manages Config instance, notifies listeners |

### Config.kt Structure (3 sections to modify)

```
object Defaults {                    // Line ~18: const val declarations
    const val NEW_FEATURE = false    // 1. Add default here
}

class Config {                       // Line ~337
    @JvmField var new_feature = Defaults.NEW_FEATURE  // 2. Add field here (~line 398+)

    fun refresh() {                  // Line ~542
        new_feature = _prefs.getBoolean("new_feature", Defaults.NEW_FEATURE)  // 3. Add read here (~647+)
    }

    // Optional: setter method (~line 798+)
    fun set_new_feature(v: Boolean) {
        new_feature = v
        _prefs.edit().putBoolean("new_feature", v).commit()
    }

    companion object {               // Line ~961
        fun globalConfig(): Config   // Access from anywhere
        fun globalPrefs(): SharedPreferences
    }
}
```

### SettingsActivity.kt Structure (4 sections to modify)

```kotlin
class SettingsActivity : ComponentActivity() {

    // 1. STATE VARIABLES (~line 163-356)
    private var newFeatureEnabled by mutableStateOf(false)
    private var newSectionExpanded by mutableStateOf(false)

    // 2. COLLAPSE ALL (~line 399-411)
    private fun collapseAllSections() {
        newSectionExpanded = false  // Add here
    }

    // 3. SEARCHABLE SETTINGS (~line 525-570)
    SearchableSetting("New Feature", listOf("keyword1"), "SectionName",
        expandSection = { newSectionExpanded = true }, settingId = "new_feature")

    // 4. PREFERENCE CHANGE LISTENER (~line 755-770)
    "new_feature_enabled" -> {
        newFeatureEnabled = prefs.getBoolean(key, Defaults.NEW_FEATURE)
    }

    // 5. UI SECTION (find insertion point among CollapsibleSettingsSection blocks)
    CollapsibleSettingsSection(
        title = "🎬 Section Title",
        expanded = newSectionExpanded,
        onExpandChange = { newSectionExpanded = it }
    ) {
        SettingsSwitch(
            title = "Feature Name",
            description = "What this does",
            checked = newFeatureEnabled,
            onCheckedChange = {
                newFeatureEnabled = it
                saveSetting("new_feature_enabled", it)
            }
        )
    }

    // 6. INIT FROM PREFS (~line 4600+)
    newFeatureEnabled = prefs.getSafeBoolean("new_feature_enabled", Defaults.NEW_FEATURE)
}
```

## Available Compose Components

| Component | Use For |
|-----------|---------|
| `SettingsSwitch(title, description, checked, onCheckedChange)` | Boolean toggles |
| `SettingsSlider(title, description, value, valueRange, steps, onValueChange, displayValue)` | Numeric sliders |
| `SettingsDropdown(title, description, options, selectedIndex, onSelectionChange)` | Selection lists |
| `CollapsibleSettingsSection(title, expanded, onExpandChange) { content }` | Grouped sections |
| `SettingsSection(title) { content }` | Non-collapsible sections |

## Safe Preference Readers (Config.kt)

```kotlin
safeGetInt(prefs, key, default)      // Handles ClassCastException
safeGetFloat(prefs, key, default)    // Recovers from corrupted prefs
safeGetBoolean(prefs, key, default)  // Parses string/int as boolean
safeGetString(prefs, key, default)   // Converts any type to string
```

## `saveSetting()` (SettingsActivity.kt line ~4767)

Accepts `Any` — dispatches to `putBoolean/putInt/putFloat/putString/putLong` based on type.

## Pattern: Feature Toggle with Dependent Settings

```kotlin
// Master toggle
SettingsSwitch(title = "Enable Feature", checked = featureEnabled, ...)

// Only show dependent settings when enabled
if (featureEnabled) {
    SettingsSlider(title = "Sub-setting", ...)
}
```

## Checklist: Adding a New Setting

1. [ ] Add `const val` in `Defaults` object (Config.kt ~line 18)
2. [ ] Add `@JvmField var` in `Config` class (Config.kt ~line 398)
3. [ ] Add read in `Config.refresh()` (Config.kt ~line 647)
4. [ ] Optional: Add setter method in Config (Config.kt ~line 798)
5. [ ] Add `mutableStateOf` in SettingsActivity (~line 163)
6. [ ] Add to `collapseAllSections()` if new section (~line 399)
7. [ ] Add `SearchableSetting` entry (~line 525)
8. [ ] Add preference change handler (~line 755)
9. [ ] Add UI in `SettingsScreen()` composable
10. [ ] Init from prefs in `initSettingsFromPrefs()` (~line 4600)

## Existing Sections (in order)

Activities, Multi-Language, Privacy, Neural, Appearance, Swipe Trail,
Input, Swipe Corrections, Gesture Tuning, Accessibility, **Clipboard**,
Backup & Restore, Advanced, Info, Help & FAQ
