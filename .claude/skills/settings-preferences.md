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

## Notable Config Chunks

The Config file groups settings into "Chunks" — informal section markers
in comments. When adding a setting, find the right Chunk for locality:

- **Chunk 3** (Config.kt:464 and refresh block at 743): URL sanitization
  toggles for the clipboard pipeline. Keys:
  `clipboard_sanitize_links_enabled`, `clipboard_embed_enrich_enabled`,
  `clipboard_custom_rules_enabled`, `clipboard_custom_rules_uri`. All
  default OFF. Changes broadcast `SettingsActivity.ACTION_SANITIZATION_RULES_CHANGED`
  so `ClipboardHistoryService` rebuilds its cached `UrlSanitizer`. See
  `clipboard-panel-architecture.md` and `docs/wiki/specs/clipboard/
  url-sanitization-spec.md` for the full pipeline.

## Autocorrect Knobs (v1.4.0 + KeyAdjacency)

Calibrated thresholds — don't change without re-running `AutocorrectTest`
(36 instrumented + 31 pure-JVM `KeyAdjacencyTest` cases):

| Key | Default | Range | Controls |
|-----|---------|-------|----------|
| `autocorrect_enabled` | true | bool | Master toggle |
| `autocorrect_min_word_length` | 2 | 1-5 | Skip very short words |
| `autocorrect_prefix_length` | 0 | 0-5 | Required leading-char match (0 allows first-char typos) |
| `autocorrect_char_match_threshold` | 0.65 | 0.5-0.95 | Min KeyAdjacency weighted score |
| `autocorrect_max_length_diff` | 2 | 0-5 | Allow ±N length candidates |
| `autocorrect_confidence_min_frequency` | 100 | 0+ | Winner must clear this dict frequency |

Selection has four hardcoded tiers in `WordPredictor.kt:~1900` —
`SCORE_TIEBREAK_GAP=0.10f`, `ALIAS_SCORE_BONUS=0.15f`,
`MIN_SAME_LENGTH_EXACT_RATIO=0.50f`, `LENGTH_DIFF_ED_BUDGET=0.5f`. See
`docs/wiki/specs/typing/autocorrect-spec.md` for the calibration rationale.

## Backup/Restore + Spec Site Integration

For settings that need backup/restore + a live-site spec page, BOTH
`backup/SettingsValidation.kt` and `backup/SettingsDefaults.kt` must
classify the key. The `SettingsDefaultsDriftTest` scans source for any
`prefs.get*` / `editor.put*` call and asserts every key is classified —
build fails if a new setting is added without classification.

The companion spec at `docs/wiki/specs/settings/settings-system-
architecture-spec.md` documents the full pipeline: Defaults singleton,
Config constructor, ConfigurationManager listener, observer pattern,
backup serialization format.
