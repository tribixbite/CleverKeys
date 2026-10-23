---
title: Settings System Architecture - Technical Specification
description: Architectural overview of the CleverKeys settings system — Defaults singleton, Config, ConfigurationManager, and Material 3 Compose UI.
status: implemented
version: v1.5.0
---

# Settings System Architecture

## Overview

The settings system manages user preferences through SharedPreferences, provides a Material 3 Compose UI for configuration, and applies settings at runtime via the `Config` singleton. All compile-time default values are centralized in the `Defaults` object inside `Config.kt` to prevent mismatches between UI display and actual behavior.

This document covers the cross-cutting architecture (defaults, Config singleton, ConfigurationManager, UI shell). Per-area details — labels, ranges, validation, and screenshots — live in the dedicated area specs (see [Per-Area Settings Specs](#per-area-settings-specs)).

## Key Components

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | `Config`, `Defaults` | Global configuration class, centralized defaults |
| `src/main/kotlin/tribixbite/cleverkeys/activities/SettingsActivity.kt` | `SettingsActivity` | Settings shell — hosts the Compose screen, launchers, and search index |
| `src/main/kotlin/tribixbite/cleverkeys/ui/settings/` | `SettingsScreen`, `SettingsSearch`, controls/dialogs/persistence | Decomposed settings UI layer (v1.5.0 refactor) |
| `src/main/kotlin/tribixbite/cleverkeys/ui/settings/sections/` | 17 per-section composables (`AppearanceSection`, `ClipboardSection`, ...) | One file per collapsible section |
| `src/main/kotlin/tribixbite/cleverkeys/ConfigurationManager.kt` | `ConfigurationManager` | Owns Config + FoldStateTracker; observes prefs and notifies listeners |
| `src/main/kotlin/tribixbite/cleverkeys/DirectBootAwarePreferences.kt` | `DirectBootAwarePreferences` | Device-protected preferences for direct-boot scenarios |
| `src/main/kotlin/tribixbite/cleverkeys/theme/KeyboardTheme.kt` | `KeyboardTheme` | Theme data and application |
| `src/main/kotlin/tribixbite/cleverkeys/backup/SettingsDefaults.kt` | `SETTINGS_DEFAULTS` | Backup/restore default snapshot (derived from `Defaults` via `PrefValue.toExportableValue()`) |

## Architecture

```
SettingsActivity (Material 3 Compose)
    ├── PreferenceScreen (collapsible sections)
    │   ├── Appearance Section
    │   ├── Input Behavior Section
    │   ├── Swipe Typing Section
    │   ├── Gestures Section
    │   ├── Layout Section
    │   ├── Clipboard Section
    │   └── Advanced Section
    ├── Config (reads SharedPreferences)
    └── ConfigurationManager (observes prefs, notifies ConfigChangeListeners)

Defaults Architecture:
    └── Defaults object (Config.kt:18)
        ├── Single source of truth for all compile-time defaults
        ├── Referenced by Config refresh
        ├── Referenced by SettingsActivity loadCurrentSettings()
        └── Referenced by SETTINGS_DEFAULTS via PrefValue.toExportableValue()

Storage Strategy:
    ├── SharedPreferences (settings data)
    ├── DirectBootAwarePreferences (device-protected storage)
    ├── App-specific storage (getExternalFilesDir)
    └── Scoped storage (Android 11+)
```

## Configuration

### Defaults Object

The `Defaults` object (`Config.kt:18`) centralizes all app default values:

```kotlin
// Config.kt (abridged; line numbers per current source)
object Defaults {
    // Appearance
    const val THEME = "cleverkeysdark"          // :20
    const val KEYBOARD_HEIGHT_PORTRAIT = 27    // :23
    const val KEYBOARD_HEIGHT_LANDSCAPE = 40   // :24
    const val KEY_OPACITY = 100                // :27

    // Input Behavior / Haptics
    const val LONGPRESS_TIMEOUT = 600          // :85
    const val HAPTIC_ENABLED = true            // :75
    const val VIBRATE_CUSTOM = false           // :72
    const val VIBRATE_DURATION = 20            // :73

    // Swipe decoding
    const val SWIPE_ENGINE_MODE = "ctc"
    const val CTC_BEAM_WIDTH = 100
    const val ONNX_XNNPACK_THREADS = 2
    const val SWIPE_TYPING_ENABLED = true      // :164

    // Gestures (units: % of key diagonal)
    const val SHORT_GESTURE_MIN_DISTANCE = 28  // :119
    const val SHORT_GESTURE_MAX_DISTANCE = 141 // :120
    const val TAP_DURATION_THRESHOLD = 150     // :103

    // Clipboard
    const val CLIPBOARD_HISTORY_ENABLED = true              // :206
    const val CLIPBOARD_HISTORY_LIMIT = "0"                 // :215 (unlimited)
    const val CLIPBOARD_EXCLUDE_PASSWORD_MANAGERS = true    // :227

    // ... ~100 more constants organized by category
}
```

For exact current values per area, see the per-area specs ([Appearance](appearance-spec.md), [Input Behavior](input-behavior-spec.md), [Haptics](haptics-spec.md)).

### Settings Categories

| Category | Settings Count | Key Settings |
|----------|----------------|--------------|
| Appearance | ~15 | theme, keyboard_height, opacity, borders |
| Input Behavior | ~10 | longpress_timeout, vibration, key_repeat |
| Swipe Typing | ~4 | swipe_engine_mode, ctc_beam_width, swipe_typing_enabled |
| Gestures | ~12 | short_swipe distances, slider sensitivity |
| Layout | ~8 | margins, number_row, extra_keys |
| Clipboard | ~5 | history_enabled, history_size, exclusions |
| Accessibility | ~6 | sticky_keys, voice_guidance |
| Debug | ~4 | debug_mode, logging |

## Settings Search

The settings screen has a search box that finds any control by title or keyword and scrolls to it:

- `SearchableSetting` (`ui/settings/SettingsSearch.kt:60`) carries `title`, `keywords`, `sectionName`, an `expandSection` lambda, and an optional `gatedBy` key (e.g. a setting hidden behind the swipe-typing toggle).
- The index is **generated from the actual control titles** — `scripts/generate_settings_search_index.py` scans the section composables so every rendered control is findable; a drift test keeps the index complete.
- Selecting a result expands the owning collapsible section and animates the scroll to the control's measured position (`SettingsSearch.kt:27`: `scrollState.animateScrollTo(maxOf(0, position - 16))`).
- Gated results (`gatedBy != null`) are handled at `SettingsSearch.kt:136-139`: when the gate is disabled, search navigates to the gate toggle instead of a hidden control.

## Public API

### Config

`Config` is constructed by `ConfigurationManager` and exposed as a global singleton via the companion object (`Config.kt:1101-1121`):

```kotlin
class Config private constructor(
    private val _prefs: SharedPreferences,
    res: Resources,
    @JvmField val handler: IKeyEventHandler?,
    foldableUnfolded: Boolean?
) {
    // From preferences
    @JvmField var layouts: List<KeyboardData?> = emptyList()
    @JvmField var show_numpad = false
    @JvmField var haptic_enabled = Defaults.HAPTIC_ENABLED
    // ... ~100 @JvmField vars sourced from Defaults

    companion object {
        private var _globalConfig: Config? = null

        fun globalConfig(): Config = _globalConfig!!
        fun globalPrefs(): SharedPreferences = _globalConfig!!._prefs
    }
}
```

### ConfigurationManager

`ConfigurationManager` owns the `Config` instance, observes SharedPreferences, and uses the observer pattern to decouple config refresh from config propagation:

```kotlin
class ConfigurationManager(
    private val context: Context,
    private val config: Config,
    private val foldStateTracker: FoldStateTracker
) : SharedPreferences.OnSharedPreferenceChangeListener {

    private val listeners = mutableListOf<ConfigChangeListener>()

    init {
        foldStateTracker.setChangedCallback {
            refresh(context.resources)
        }
    }
    // refresh, addListener, onSharedPreferenceChanged, etc.
}
```

Components that need to respond to config changes implement `ConfigChangeListener` and register with `ConfigurationManager`.

### SettingsActivity

`SettingsActivity` is a `ComponentActivity` that uses Material 3 Compose:

```kotlin
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CleverKeysSettingsTheme {
                SettingsScreen(
                    onNavigateBack = { finish() }
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(onNavigateBack: () -> Unit) {
    // Collapsible sections for each category
    var appearanceExpanded by remember { mutableStateOf(false) }
    var inputExpanded by remember { mutableStateOf(false) }
    // ...

    LazyColumn {
        item { SettingsSection("Appearance", appearanceExpanded, { appearanceExpanded = it }) {
            ThemePicker()
            HeightSlider()
            OpacitySlider()
        }}
        item { SettingsSection("Input Behavior", inputExpanded, { inputExpanded = it }) {
            VibrationToggle()
            LongpressSlider()
        }}
        // ... other sections
    }
}
```

## Implementation Details

### Settings UI Components

```kotlin
@Composable
fun SettingsSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ui/settings/SettingsControls.kt:225
@Composable
internal fun SettingsActivity.SettingsSlider(
    title: String,
    description: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    /** Distance between two stops, in [value]'s unit; the Compose step count is derived from it. */
    increment: Float,
    onValueChange: (Float) -> Unit,
    displayValue: String,
    /** False = visible but inert and dimmed; see [SettingsSwitch]'s `enabled` for the rationale. */
    enabled: Boolean = true
)
```

Callers pass an `increment`, never a raw Compose step count: Compose's `Slider(steps = n)`
counts the stops *between* the ends, so a 0..100 slider in 1s needs 99. The conversion is
`sliderSteps(range, increment)` in `Config.kt` (`((end − start) / increment).roundToInt() − 1`,
floored at 0); raw `Slider`s elsewhere must call it too (audit 2026-10-20: about 40 sliders had
passed the interval count and every stop fell between two values). Settings search addresses a
control by a locale-independent `settingId` (`settingIdsFor`, `SettingsSearch.kt`), not by its
title.

### Storage Permissions

`AndroidManifest.xml` declares **no** storage permission (`READ_/WRITE_EXTERNAL_STORAGE` are
absent, as is `INTERNET`). Backups, language packs, dictionaries and GIF packs are read and
written through the Storage Access Framework (document picker) and the app's own
`filesDir`, neither of which needs a permission on any supported API level.

### Theme Application

There is no `applyTheme()`. The `theme` preference is a theme id string; `Config.kt` resolves
it to an `R.style` (the `when (themeName)` table near `Config.kt:1960`: built-in ids such as
`light`, `dark`, `jewel`, `cleverkeysdark`; `monet*` ids fall back to `Light`/`Dark` without
Material You; `decorative_*`/`custom_*` ids render through `ThemeProvider`, see
[Themes](../customization/themes-spec.md)). A change reaches the keyboard like every other
preference: `ConfigurationManager` observes `SharedPreferences`, refreshes `Config`, and the
registered `ConfigChangeListener`s rebuild their views.

### Settings Change Listener

`Config` reacts to preference changes through `ConfigurationManager`, which implements `SharedPreferences.OnSharedPreferenceChangeListener` and notifies registered `ConfigChangeListener` instances. Components that depend on a specific subset of preferences (theme, keyboard height, swipe-engine parameters, etc.) implement the listener interface rather than each reading SharedPreferences directly.

### Collapsible Sections Pattern

Settings UI uses collapsible sections (not hierarchical navigation):

```kotlin
@Composable
fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Row(
            modifier = Modifier.clickable { onExpandedChange(!expanded) }
                .fillMaxWidth().padding(16.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(content = content)
        }
    }
}
```

This pattern means settings paths are "Settings > [expand section] > [setting]" rather than hierarchical navigation like "Settings > Appearance > Theme".

## Per-Area Settings Specs

This architectural spec covers the shared scaffolding. For exact values, ranges, validation rules, and per-key behavior, refer to:

- [Appearance Settings](appearance-spec.md) — theme, keyboard height, opacity, borders, label brightness
- [Input Behavior Settings](input-behavior-spec.md) — long-press timeout, key repeat, swipe geometry
- [Haptics Settings](haptics-spec.md) — master haptic toggle and per-event vibration
- [CTC Swipe Engine](https://github.com/tribixbite/CleverKeys/blob/main/docs/specs/ctc-swipe-engine.md) — beam width and the ONNX-threads knob

For backup/restore and the import-preview pipeline (which exercises `SETTINGS_DEFAULTS`, `PrefValue`, and the diff engine), see the backup/restore specs.
