---
title: Settings System Architecture - Technical Specification
description: Architectural overview of the CleverKeys settings system — Defaults singleton, Config, ConfigurationManager, and Material 3 Compose UI.
status: implemented
version: v1.4.0
---

# Settings System Architecture

## Overview

The settings system manages user preferences through SharedPreferences, provides a Material 3 Compose UI for configuration, and applies settings at runtime via the `Config` singleton. All compile-time default values are centralized in the `Defaults` object inside `Config.kt` to prevent mismatches between UI display and actual behavior.

This document covers the cross-cutting architecture (defaults, Config singleton, ConfigurationManager, UI shell). Per-area details — labels, ranges, validation, and screenshots — live in the dedicated area specs (see [Per-Area Settings Specs](#per-area-settings-specs)).

## Key Components

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | `Config`, `Defaults` | Global configuration class, centralized defaults |
| `src/main/kotlin/tribixbite/cleverkeys/SettingsActivity.kt` | `SettingsActivity` | Material 3 Compose settings UI (collapsible sections) |
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
    │   ├── Neural Prediction Section
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
// Config.kt
object Defaults {
    // Appearance
    const val THEME = "cleverkeysdark"
    const val KEYBOARD_HEIGHT_PORTRAIT = 28
    const val KEYBOARD_HEIGHT_LANDSCAPE = 50
    const val KEY_OPACITY = 1.0f
    const val KEY_BORDER_ENABLED = false

    // Input Behavior
    const val LONGPRESS_TIMEOUT = 600
    const val KEY_REPEAT_DELAY = 50
    const val VIBRATION_ENABLED = true
    const val VIBRATION_STRENGTH = 10

    // Neural Prediction
    const val NEURAL_BEAM_WIDTH = 6
    const val NEURAL_MAX_LENGTH = 20
    const val NEURAL_CONFIDENCE_THRESHOLD = 0.3f
    const val SWIPE_ENABLED = true

    // Gestures
    const val SHORT_GESTURE_MIN_DISTANCE = 15
    const val SHORT_GESTURE_MAX_DISTANCE = 50
    const val SLIDER_SENSITIVITY = 30
    const val TAP_DURATION_THRESHOLD = 200L

    // Clipboard
    const val CLIPBOARD_HISTORY_ENABLED = true
    const val CLIPBOARD_HISTORY_SIZE = 25
    const val CLIPBOARD_EXCLUDE_PASSWORD_MANAGERS = true

    // ... ~100 constants organized by category
}
```

For exact current values per area, see the per-area specs ([Appearance](appearance-spec.md), [Input Behavior](input-behavior-spec.md), [Haptics](haptics-spec.md), [Neural Settings](neural-settings-spec.md)).

### Settings Categories

| Category | Settings Count | Key Settings |
|----------|----------------|--------------|
| Appearance | ~15 | theme, keyboard_height, opacity, borders |
| Input Behavior | ~10 | longpress_timeout, vibration, key_repeat |
| Neural | ~8 | beam_width, confidence, swipe_enabled |
| Gestures | ~12 | short_swipe distances, slider sensitivity |
| Layout | ~8 | margins, number_row, extra_keys |
| Clipboard | ~5 | history_enabled, history_size, exclusions |
| Accessibility | ~6 | sticky_keys, voice_guidance |
| Debug | ~4 | debug_mode, logging |

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

@Composable
fun SettingsSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Slider(value = value, valueRange = range, onValueChange = onValueChange)
    }
}
```

### Storage Permissions (Android 11+)

```xml
<!-- AndroidManifest.xml -->
<!-- Legacy permissions for Android 10 and below -->
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="29" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
    android:maxSdkVersion="29" />

<!-- For Android 11+, use scoped storage via MediaStore or SAF -->
```

App-specific storage doesn't require permissions:

```kotlin
val appDir = context.getExternalFilesDir(null)  // No permission needed
```

### Theme Application

```kotlin
// ConfigurationManager.kt
fun applyTheme(themeName: String) {
    val theme = KeyboardTheme.loadTheme(context, themeName)
    KeyboardTheme.current = theme

    // Notify keyboard view to redraw
    CleverKeysService.getInstance()?.requestKeyboardRedraw()
}
```

### Settings Change Listener

`Config` reacts to preference changes through `ConfigurationManager`, which implements `SharedPreferences.OnSharedPreferenceChangeListener` and notifies registered `ConfigChangeListener` instances. Components that depend on a specific subset of preferences (theme, keyboard height, neural parameters, etc.) implement the listener interface rather than each reading SharedPreferences directly.

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
- [Neural Settings](neural-settings-spec.md) — beam width, confidence threshold, prefix boost

For backup/restore and the import-preview pipeline (which exercises `SETTINGS_DEFAULTS`, `PrefValue`, and the diff engine), see the backup/restore specs.
