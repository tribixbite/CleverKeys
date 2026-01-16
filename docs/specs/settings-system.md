# Settings System

## Overview

The settings system manages user preferences through SharedPreferences, provides a Material 3 Compose UI for configuration, and applies settings at runtime via the Config singleton. All default values are centralized in the `Defaults` object within Config.kt to prevent mismatches between UI display and actual behavior.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | `Config`, `Defaults` | Global configuration singleton, centralized defaults |
| `src/main/kotlin/tribixbite/cleverkeys/SettingsActivity.kt` | `SettingsActivity` | Material 3 Compose settings UI (~3000 lines) |
| `src/main/kotlin/tribixbite/cleverkeys/ConfigurationManager.kt` | `ConfigurationManager` | Runtime configuration application |
| `src/main/kotlin/tribixbite/cleverkeys/theme/KeyboardTheme.kt` | `KeyboardTheme` | Theme data and application |

## Architecture

```
SettingsActivity (Material 3 Compose)
    ├── PreferenceScreen
    │   ├── Appearance Section
    │   ├── Input Behavior Section
    │   ├── Neural Prediction Section
    │   ├── Gestures Section
    │   ├── Layout Section
    │   ├── Clipboard Section
    │   └── Advanced Section
    ├── Config (reads SharedPreferences)
    └── ConfigurationManager (applies settings)

Defaults Architecture:
    └── Defaults object (Config.kt)
        ├── Single source of truth for all ~100 default values
        ├── Referenced by Config.kt refresh()
        ├── Referenced by SettingsActivity.kt loadCurrentSettings()
        └── Referenced by onSharedPreferenceChanged()

Storage Strategy:
    ├── SharedPreferences (settings data)
    ├── DirectBootAwarePreferences (device-protected storage)
    ├── App-specific storage (getExternalFilesDir)
    └── Scoped storage (Android 11+)
```

## Configuration

### Defaults Object

The `Defaults` object centralizes all app default values:

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

### Config Singleton

```kotlin
class Config private constructor(context: Context) {
    companion object {
        private var instance: Config? = null

        fun globalConfig(): Config = instance
            ?: throw IllegalStateException("Config not initialized")

        fun initialize(context: Context) {
            instance = Config(context)
        }
    }

    // Refresh from SharedPreferences
    fun refresh() {
        val prefs = context.getSharedPreferences("cleverkeys_prefs", MODE_PRIVATE)
        theme = prefs.getString("theme", Defaults.THEME)!!
        keyboardHeightPortrait = prefs.getInt("keyboard_height_portrait", Defaults.KEYBOARD_HEIGHT_PORTRAIT)
        neuralBeamWidth = prefs.getInt("neural_beam_width", Defaults.NEURAL_BEAM_WIDTH)
        // ... all other settings
    }

    // Save individual setting
    fun saveSetting(key: String, value: Any) {
        val prefs = context.getSharedPreferences("cleverkeys_prefs", MODE_PRIVATE)
        prefs.edit().apply {
            when (value) {
                is String -> putString(key, value)
                is Int -> putInt(key, value)
                is Boolean -> putBoolean(key, value)
                is Float -> putFloat(key, value)
            }
            apply()
        }
        refresh()
    }
}
```

### SettingsActivity

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

```kotlin
class Config(context: Context) : SharedPreferences.OnSharedPreferenceChangeListener {
    init {
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        when (key) {
            "theme" -> {
                theme = prefs.getString("theme", Defaults.THEME)!!
                ConfigurationManager.applyTheme(theme)
            }
            "keyboard_height_portrait" -> {
                keyboardHeightPortrait = prefs.getInt(key, Defaults.KEYBOARD_HEIGHT_PORTRAIT)
                CleverKeysService.getInstance()?.requestKeyboardResize()
            }
            // ... handle other settings
        }
    }
}
```

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
