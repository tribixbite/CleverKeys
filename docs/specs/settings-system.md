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
| `src/main/kotlin/tribixbite/cleverkeys/activities/SettingsActivity.kt` | `SettingsActivity` | Activity shell: state + SAF launchers + `setContent { SettingsScreen() }` (942 lines as of 2026-10-23 — the old "~3000-line" monolith was decomposed into `ui/settings/`) |
| `src/main/kotlin/tribixbite/cleverkeys/ui/settings/` | `SettingsScreen` + sections/io | The actual Material 3 Compose settings UI (see Architecture tree below) |
| `src/main/kotlin/tribixbite/cleverkeys/ConfigurationManager.kt` | `ConfigurationManager` | Runtime configuration application |
| `src/main/kotlin/tribixbite/cleverkeys/Theme.kt` + `theme/ThemeProvider.kt` | `Theme`, `ThemeProvider` | Keyboard-view theme data and loading (`theme/KeyboardTheme.kt` is only a retained composable alias for `CleverKeysTheme`, the Compose settings-UI wrapper — not a theme store) |

## Architecture

Real file layout (re-derived 2026-10-23 from `git ls-tree HEAD src/main/kotlin/tribixbite/cleverkeys/ui/settings/`
— an earlier revision showed a fictional `PreferenceScreen` tree; counts below are exact, not approximate):

```
SettingsActivity.kt (activities/ — 942-line shell: state, SAF launchers, listeners)
    └── setContent { SettingsScreen() }

ui/settings/                       # 13 files — screen scaffolding
    SettingsScreen.kt              #   top-level composable, section list
    SettingsControls.kt            #   shared switch/slider/dropdown composables
    SettingsDialogs.kt, SettingsInfoCards.kt, SettingsSearch.kt, SettingsSearchMatch.kt,
    SettingsNavigation.kt, SettingsLifecycle.kt, SettingsPersistence.kt,
    SettingsResetPresets.kt, SettingsResetPolicy.kt, SettingsPrefsExt.kt,
    AutocorrectTuningSliders.kt    #   autocorrect weight sliders (autocorrect-ranking.md)

ui/settings/sections/              # 20 files — one composable per section
    AppearanceSection, InputBehaviorSection, SwipeTypingSection,
    SwipeTrailSection, GestureTuningSection, AutoCorrectionSection,
    MultiLanguageSection, ClipboardSection, GifPanelSection, PrivacySection,
    LearningDataSection, BackupRestoreSection (+ BackupPasswordBlock,
    BackupPassphrasePromptDialog), AccessibilitySection, AdvancedSection,
    ActivitiesSection, TestKeyboardSection, VersionActionsSection, HelpSection

ui/settings/io/                    # 9 files — import/export handlers
    SettingsBackupHandlers, SettingsClipboardHandlers, SettingsDictionaryHandlers,
    SettingsGifHandlers, SettingsLanguagePackHandlers, SettingsPrivacyDataHandlers,
    SettingsSwipeDataHandlers, SettingsCollisionScanHandlers, CreateBackupDocument

Supporting singletons:
    Config (reads SharedPreferences; `object Defaults` + `object SettingsRanges` live in Config.kt)
    ConfigurationManager (SharedPreferences listener → Config.refresh → onConfigChanged/onThemeChanged)

Defaults Architecture:
    └── Defaults object (Config.kt)
        ├── Single source of truth for the default values (~200 constants)
        ├── Referenced by Config.kt refresh()
        ├── Referenced by the settings loader in ui/settings/SettingsPersistence.kt
        └── Referenced by the typed backup/reset maps

Storage Strategy:
    ├── SharedPreferences via DirectBootAwarePreferences (device-protected storage)
    └── Storage Access Framework for every file import/export (ui/settings/io/; no storage permission)
```

## Configuration

### Defaults Object

The `Defaults` object centralizes all app default values:

```kotlin
// Config.kt — values copied from HEAD on 2026-10-23; the object is the source of truth
object Defaults {
    // Appearance
    const val THEME = "cleverkeysdark"
    const val KEYBOARD_HEIGHT_PORTRAIT = 27      // % of screen height (lowered from 30, 2026-05-15)
    const val KEYBOARD_HEIGHT_LANDSCAPE = 40
    const val KEYBOARD_OPACITY = 100             // #51: fully opaque by default
    const val KEY_OPACITY = 100
    const val KEY_ACTIVATED_OPACITY = 80
    const val BORDER_CONFIG = false

    // Input behavior
    const val LONGPRESS_TIMEOUT = 600
    const val LONGPRESS_INTERVAL = 25
    const val KEYREPEAT_ENABLED = true
    const val KEYREPEAT_BACKSPACE_ONLY = true
    const val HAPTIC_ENABLED = true              // master haptic gate
    const val VIBRATE_CUSTOM = false
    const val VIBRATE_DURATION = 20

    // Swipe decoding
    const val SWIPE_ENGINE_MODE = "ctc"
    const val CTC_BEAM_WIDTH = 100
    const val ONNX_XNNPACK_THREADS = 2
    const val SWIPE_TYPING_ENABLED = true
    const val CONTINUOUS_SWIPE_ENABLED = false

    // Gestures
    const val SHORT_GESTURE_MIN_DISTANCE = 28
    const val SHORT_GESTURE_MAX_DISTANCE = 141
    const val SHORT_SWIPES_ONLY = false          // GH #147/#87
    const val SLIDER_SENSITIVITY = "30"          // stored as a string; SLIDER_SENSITIVITY_FALLBACK = 30f
    const val TAP_DURATION_THRESHOLD = 150

    // Suggestions
    const val SUGGESTION_BAR_OPACITY = 80
    const val WORD_PREDICTION_ENABLED = true
    const val NEXT_WORD_PREDICTION_ENABLED = true   // default ON since 2026-09-26

    // Clipboard
    const val CLIPBOARD_HISTORY_ENABLED = true
    const val CLIPBOARD_HISTORY_LIMIT = "0"      // 0 = unlimited (SettingsRanges.CLIPBOARD_HISTORY_LIMIT = 0..500)
    const val CLIPBOARD_MAX_ITEM_SIZE_KB = "512"
    const val CLIPBOARD_PANE_HEIGHT_PERCENT = 30
    const val CLIPBOARD_EXCLUDE_PASSWORD_MANAGERS = true

    // ... ~200 constants organized by category; several numeric prefs are stored as
    // strings and carry a `_FALLBACK` numeric twin for unparseable stored values.
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
// activities/SettingsActivity.kt — the shell; state, SAF launchers and listeners live here
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KeyboardTheme { SettingsScreen() } }   // KeyboardTheme = alias of theme/CleverKeysTheme.kt
    }
}

// ui/settings/SettingsScreen.kt — extension composable; the sections are
// ui/settings/sections/*Section.kt composed inside
// SettingsActivity.CollapsibleSettingsSection / SettingsSection (SettingsControls.kt)
@Composable
internal fun SettingsActivity.SettingsScreen()
```

(Sketch, not a transcript: the exact `onCreate` body also wires search, deep links and
lifecycle; see the files named above.)

## Implementation Details

### Settings UI Components

Real signatures (`ui/settings/SettingsControls.kt`, 2026-10-23). Both are extension
composables on `SettingsActivity`; the receiver supplies `highlightedSettingId`,
`recordSettingPosition` and `contentYOf` for settings search.

```kotlin
@Composable
internal fun SettingsActivity.SettingsSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    highlightId: String? = null,   // extra search-highlight id for gated entries
    enabled: Boolean = true,       // false = visible but inert and dimmed (never hidden)
)

@Composable
internal fun SettingsActivity.SettingsSlider(
    title: String,
    description: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    increment: Float,              // distance between two stops, in value's unit
    onValueChange: (Float) -> Unit,
    displayValue: String,
    enabled: Boolean = true,
)
```

- A slider takes an **increment**, never a raw Compose step count: the control derives it with
  `sliderSteps(range, increment)` (`Config.kt`), which returns the number of stops BETWEEN the
  ends (`(span / increment) - 1`, never negative). Compose's `Slider(steps = n)` convention
  had put every stop between two values on about 40 sliders (audit 2026-10-20). A raw
  `Slider` elsewhere must call `sliderSteps` the same way.
- Every control registers its scroll position under the locale-independent ids returned by
  `settingIdsFor(title)` (`SettingsSearch.kt`), so a search hit can scroll to and pulse it.


### Storage: no storage permission

`AndroidManifest.xml` declares only `VIBRATE`, `READ_USER_DICTIONARY` and the app's own
`tribixbite.cleverkeys.permission.SET_DEBUG_MODE` (there is no `INTERNET` permission either).
No `READ_/WRITE_EXTERNAL_STORAGE` is requested on any API level: every file import/export
(backups, dictionaries, language packs, GIF packs, swipe data, clipboard) goes through the
Storage Access Framework from the handlers in `ui/settings/io/` (`CreateBackupDocument` is the
`ActivityResultContract` for backups).

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

`Config` is not a `SharedPreferences.OnSharedPreferenceChangeListener` and there is no
`ConfigurationManager.applyTheme`. The real flow (`ConfigurationManager.kt`, verified 2026-10-23):

```kotlin
class ConfigurationManager(...) : SharedPreferences.OnSharedPreferenceChangeListener {
    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        refresh(context.resources)          // every key goes through the same path
    }

    fun refresh(res: Resources) {
        // remembers prevTheme / prevThemeName, then:
        config.refresh(res, foldStateTracker.isUnfolded())
        for (listener in listeners) listener.onConfigChanged(config)      // each guarded by try/catch(Throwable)
        val themeChanged = prevTheme != config.theme || prevThemeName != config.themeName
        if (themeChanged) for (listener in listeners) listener.onThemeChanged(prevTheme, config.theme)
    }
}
```

There is no per-key `when (key)` dispatch: the whole `Config` is re-read from prefs on every
change, and consumers react in `onConfigChanged` (keyboard height, opacity, …) or
`onThemeChanged` (view recreation).

### Collapsible Sections Pattern

Settings UI uses collapsible sections (not hierarchical navigation):

The real composable is `SettingsActivity.CollapsibleSettingsSection` (`SettingsControls.kt:37`);
the shape below is illustrative:

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

## Continuous swipe setting (October 6 development)

`continuous_swipe_enabled` is a Boolean, default `false`, exposed in the Swipe Typing
section only while swipe typing is enabled. Config's live value and immutable snapshot,
Settings state/persistence, searchable title and typed backup/default/reset map use the
same key. Turning off swipe typing suspends this behavior without rewriting its stored
preference. Imported non-Boolean values are rejected by normal strict backup validation.

The user pauses 280 ms inside the inner 80% of the physical spacebar to end a segment.
This opt-in feature refuses password/inline panel editors and requires readable collapsed
selection and editor state. Gesture/FIFO ownership is specified in the paired public
swipe-typing specification. Human timing and cross-editor validation remain pending.
