# Settings System

> **Note:** As of v1.4.0, the canonical version of this specification lives at
> [`docs/wiki/specs/settings/settings-system-architecture-spec.md`](../wiki/specs/settings/settings-system-architecture-spec.md) and renders at <https://cleverkeys.app/specs/settings/settings-system-architecture-spec/>.
> This file is preserved for cross-references but may not be kept in sync.

## Overview

The settings system manages user preferences through SharedPreferences, provides a Material 3 Compose UI for configuration, and applies settings at runtime via the Config singleton. All default values are centralized in the `Defaults` object within Config.kt to prevent mismatches between UI display and actual behavior.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | `Config`, `Defaults` | Global configuration singleton, centralized defaults |
| `src/main/kotlin/tribixbite/cleverkeys/SettingsActivity.kt` | `SettingsActivity` | Activity shell: state + SAF launchers + `setContent { SettingsScreen() }` (801 lines as of 2026-08-21 — the old "~3000-line" monolith was decomposed into `ui/settings/`) |
| `src/main/kotlin/tribixbite/cleverkeys/ui/settings/` | `SettingsScreen` + sections/io | The actual Material 3 Compose settings UI (see Architecture tree below) |
| `src/main/kotlin/tribixbite/cleverkeys/ConfigurationManager.kt` | `ConfigurationManager` | Runtime configuration application |
| `src/main/kotlin/tribixbite/cleverkeys/Theme.kt` + `theme/ThemeProvider.kt` | `Theme`, `ThemeProvider` | Keyboard-view theme data and loading (`theme/KeyboardTheme.kt` is only a retained composable alias for `CleverKeysTheme`, the Compose settings-UI wrapper — not a theme store) |

## Architecture

Real file layout (verified 2026-08-21 via `rg --files src/main/kotlin/tribixbite/cleverkeys/ui/settings/`
— an earlier revision showed a fictional `PreferenceScreen` tree):

```
SettingsActivity.kt (root package — 801-line shell: state, SAF launchers, listeners)
    └── setContent { SettingsScreen() }

ui/settings/                       # 10 files — screen scaffolding
    SettingsScreen.kt              #   top-level composable, section list
    SettingsControls.kt            #   shared switch/slider/dropdown composables
    SettingsDialogs.kt, SettingsInfoCards.kt, SettingsSearch.kt,
    SettingsNavigation.kt, SettingsLifecycle.kt, SettingsPersistence.kt,
    SettingsResetPresets.kt, SettingsPrefsExt.kt

ui/settings/sections/              # 20 files — one composable per section
    AppearanceSection, InputBehaviorSection, SwipeTypingSection,
    SwipeTrailSection, GestureTuningSection, AutoCorrectionSection,
    MultiLanguageSection, ClipboardSection, GifPanelSection, PrivacySection,
    LearningDataSection, BackupRestoreSection (+ BackupPasswordBlock,
    BackupPassphrasePromptDialog), AccessibilitySection, AdvancedSection,
    ActivitiesSection, TestKeyboardSection, VersionActionsSection, HelpSection

ui/settings/io/                    # 8 files — import/export handlers
    SettingsBackupHandlers, SettingsClipboardHandlers, SettingsDictionaryHandlers,
    SettingsGifHandlers, SettingsLanguagePackHandlers, SettingsPrivacyDataHandlers,
    SettingsSwipeDataHandlers, SettingsCollisionScanHandlers

Supporting singletons:
    Config (reads SharedPreferences)
    ConfigurationManager (applies settings)

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

    // Swipe decoding
    const val SWIPE_ENGINE_MODE = "ctc"
    const val CTC_BEAM_WIDTH = 100
    const val ONNX_XNNPACK_THREADS = 2
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
| Swipe Typing | ~4 | swipe_engine_mode, ctc_beam_width, swipe_typing_enabled |
| Gestures | ~12 | short_swipe distances, slider sensitivity |
| Layout | ~8 | margins, number_row, extra_keys |
| Clipboard | ~5 | history_enabled, history_size, exclusions |
| Accessibility | ~6 | sticky_keys, voice_guidance |
| Debug | ~4 | debug_mode, logging |

## Public API

### Config Singleton

> **Rewritten from live code 2026-08-21.** The illustrative block previously here was invented and
> wrong in every signature: `Config` does not take a `Context`, there is no `initialize(context)`,
> `globalConfig()` does not throw `IllegalStateException`, `saveSetting` is not a `Config` method,
> and `keyboard_height_portrait` is not a pref key. Code written against it would not have
> compiled — but the pref-key errors would have compiled and silently read defaults forever.

```kotlin
// Config.kt — constructed from prefs/resources, NOT from a Context.
fun initGlobalConfig(
    prefs: SharedPreferences,
    res: Resources,
    handler: IKeyEventHandler?,
    foldableUnfolded: Boolean?,
)                                   // runs pref migrations, then builds and installs the instance

fun globalConfig(): Config          // NPEs if uninitialised — deliberate, not IllegalStateException
fun globalConfigOrNull(): Config?   // the null-safe accessor
```

Preferences are obtained through `DirectBootAwarePreferences`, not a hardcoded
`getSharedPreferences("cleverkeys_prefs", …)`, so they are readable before first unlock.

Saving is **not** a `Config` method. The settings UI uses an extension:

```kotlin
// ui/settings/SettingsPersistence.kt:423
internal fun SettingsActivity.saveSetting(key: String, value: Any)
```

It is asynchronous (`lifecycleScope.launch`) and type-dispatches on the value. A `Config` field is
refreshed from prefs by the loader in the same file, not written back per-key from `Config`.


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

There is no `KeyboardTheme.loadTheme`/`KeyboardTheme.current` API. The real flow
(verified 2026-08-21):

```kotlin
// ConfigurationManager.kt — refresh() compares theme before/after the Config reload and
// fires the dedicated theme callback (theme changes require view recreation):
val themeChanged = prevTheme != config.theme || prevThemeName != config.themeName
if (themeChanged) listener.onThemeChanged(prevTheme, config.theme)

// Keyboard2View — loads the theme on (re)creation:
_theme = if (_config.isRuntimeTheme()) {
    ThemeProvider.getInstance(context).getTheme(_config.themeName)  // custom/decorative
} else {
    Theme(getContext(), attrs)  // XML style-based themes
}
```

The Compose settings UI is themed separately via `theme/CleverKeysTheme.kt`
(`theme/KeyboardTheme.kt` is a legacy composable alias for it).

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
