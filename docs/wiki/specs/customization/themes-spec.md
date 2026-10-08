---
title: Themes - Technical Specification
description: Built-in theme catalog, the custom-theme (DIY) pipeline, and how every Theme Creator field reaches the renderer.
user_guide: /wiki/customization/themes/
status: implemented
version: v1.6.0
---

# Themes Technical Specification

## Overview

The theme system resolves a theme id (preference key `theme`) into a runtime `Theme` object consumed by the keyboard view and suggestion bar. Built-in themes are XML styles; decorative and custom (DIY) themes are runtime `KeyboardColorScheme` values. Since the H-4 wiring (2026-09-08, commit `c9939571`), **all** Theme Creator fields — including per-role key backgrounds, the activated border, ripple, the three suggestion-bar colors, and the keyboard surface — persist, round-trip through JSON, and render; edits to the active theme apply live via broadcast, without a process restart.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| Theme | `src/main/kotlin/tribixbite/cleverkeys/Theme.kt` | Runtime theme model + precomputed paints (`Theme.Computed`) |
| ThemeProvider | `src/main/kotlin/tribixbite/cleverkeys/theme/ThemeProvider.kt` | Resolves theme id → `Theme`; fallback on unknown/dangling ids |
| KeyboardColorScheme | `src/main/kotlin/tribixbite/cleverkeys/theme/KeyboardColorScheme.kt` | Immutable semantic color tokens (20 fields) for runtime themes |
| PredefinedThemes | `src/main/kotlin/tribixbite/cleverkeys/theme/PredefinedThemes.kt` | Decorative built-in scheme catalog (`ThemeInfo` entries) |
| CustomThemeManager | `src/main/kotlin/tribixbite/cleverkeys/theme/CustomThemeManager.kt` | Custom-theme CRUD, JSON persistence, reactive `StateFlow` list |
| CustomThemePrefPolicy | `src/main/kotlin/tribixbite/cleverkeys/theme/CustomThemePrefPolicy.kt` | Pref coherence on save/delete of custom themes (H-2/H-4) |
| ThemeSettingsActivity | `src/main/kotlin/tribixbite/cleverkeys/activities/ThemeSettingsActivity.kt` | Theme Manager UI + the DIY Theme Creator (Compose) |
| Keyboard2View | `src/main/kotlin/tribixbite/cleverkeys/Keyboard2View.kt` | Draw loop; selects per-role key frames |
| SuggestionBar | `src/main/kotlin/tribixbite/cleverkeys/SuggestionBar.kt` | Consumes suggestion text/background/high-confidence, ripple, surface |

## Data Model

### KeyboardColorScheme (runtime themes)

`KeyboardColorScheme` (`theme/KeyboardColorScheme.kt:21`) is an `@Immutable data class` of semantic color tokens:

- Key backgrounds: `keyDefault`, `keyActivated`, `keyLocked`, `keyModifier`, `keySpecial`
- Labels: `keyLabel`, `keySubLabel`, `keySecondaryLabel`
- Borders: `keyBorder`, `keyBorderActivated`
- Interactive: `swipeTrail`, `ripple`
- Suggestion bar: `suggestionText`, `suggestionBackground`, `suggestionHighConfidence`
- Container: `keyboardBackground`, `keyboardSurface`

`lightKeyboardColorScheme()` / `darkKeyboardColorScheme()` build the base palettes; Material You (Monet) themes feed dynamic primary/secondary colors into them.

### Theme (runtime model)

`Theme` (`Theme.kt:23`) has two constructors:

- **XML attrs** — built-in styles. The Theme-Creator fields default to the exact colors their consumers used before the wiring, so built-ins render pixel-identically (`Theme.kt:146-154`: `colorKeyLocked = colorKeyActivated`, `colorKeyModifier = colorKey`, `rippleColor = 0` meaning platform-default, etc.).
- **KeyboardColorScheme** — decorative and custom themes. Per-role key backgrounds are pre-composited over `keyDefault` (`Theme.kt:203-208`, `compositeOver`) because the shipped schemes express roles as translucent tints while the paint's alpha channel is owned by the user's key-opacity setting.

### Theme.Computed — per-role key frames

`Theme.Computed` (`Theme.kt:251`) precomputes a key frame (background + border paints) per `KeyRole`:

```kotlin
// Theme.kt:325
enum class KeyRole { NORMAL, ACTIVATED, LOCKED, MODIFIER, SPECIAL }
```

- `keyForRole(role)` (`Theme.kt:328`) returns the frame; the ACTIVATED frame's border paint takes `keyBorderColorActivated`.
- `roleOf(kind, isKeyDown, isLocked)` (`Theme.kt:421`) is a pure mapping used by the draw loop (`Keyboard2View.kt:1583-1584`).

### SuggestionBar consumers

`SuggestionBar` reads `suggestionTextColor` (`SuggestionBar.kt:159`), `suggestionBackgroundColor`, `suggestionHighConfidenceColor`, renders the provenance sheet on `colorKeyboardSurface` (`SuggestionBar.kt:449`), and applies a themed `RippleDrawable` from `rippleColor` to chips and icon buttons (`SuggestionBar.kt:163`, `:1091`). A `0` value keeps the platform/default behavior in every consumer.

## Custom Theme Storage

`CustomThemeManager` persists custom themes as a JSON array under key `themes` in the `custom_keyboard_themes` SharedPreferences file (`CustomThemeManager.kt:63-64`), using Device Encrypted storage on API 24+ for Direct Boot compatibility. Theme ids are `custom_<uuid>`; `getCustomTheme` accepts the id with or without the prefix (`CustomThemeManager.kt:126-127`). All editor fields serialize through the JSON round-trip (pinned by `ThemeCreatorFieldWiringTest`).

### Pref coherence (CustomThemePrefPolicy)

The `theme` / `swipe_trail_color` prefs and the custom-theme store live in different pref files, so mutations route through `CustomThemePrefPolicy`:

- **Delete the active custom theme** → the `theme` pref is reset to `ThemeProvider.FALLBACK_THEME_ID` (`"cleverkeysdark"`, `ThemeProvider.kt:331`) *before* the delete, so no dangling-id window exists (H-2; a dangling id previously crash-looped the IME).
- **Save the active custom theme** → `swipe_trail_color` is re-synced (H-4 mechanical half; previously edits to the active theme's trail were a silent no-op until re-selection).
- Writes use `commit()` because the caller surface kills its process right after theme-selection writes.

## Theme Creator Preview (roadmap 4.1)

The creator dialog shows `ThemeKeyboardPreview(colors)` (`theme/ThemeEditorPreview.kt`), a Compose wrapper around `ThemeKeyboardPreviewView`. It reuses the real renderers instead of drawing look-alikes:

- **Renderer**: a `Keyboard2View` switched into preview mode with `enterThemePreviewMode()` — touch and per-key accessibility activation off, `Config.handler` callbacks suppressed (the settings activity runs in the IME's process), window insets and the `KeyAdjacency` layout push skipped — and a `SuggestionBar(context, theme)` stacked above it at the IME's 40dp bar height.
- **Theme**: `ThemeEditorPreview.themeFor(context, scheme)` is the same `Theme(context, scheme)` runtime constructor `ThemeProvider` uses for applied custom themes; `applyPreviewTheme` swaps it in and evicts the cached `Theme.Computed` frames. The trail colour uses `ThemeEditorPreview.trailColorFor`, the same conversion `CustomThemePrefPolicy` syncs into `swipe_trail_color`.
- **Layout and scale**: the user's first layout through `LayoutModifier.modify_layout` (bottom row, extra keys), laid out at the device width so key/label geometry, opacity and border settings match, then uniformly scaled to the dialog width and a height cap (40% of screen height, at most 260dp).
- **States** (`ThemePreviewScene`, pure): Shift latched → ACTIVATED frame; the first of ctrl/fn/alt/meta locked → LOCKED frame; modifier and action keys at rest show MODIFIER/SPECIAL; a static trail through the first localized sample word's letter keys (left/middle/right fallback). A disabled or `none` trail is drawn `solid` so its colour stays judgeable.
- **Not visible statically**: ripple (press feedback) and keyboard surface (provenance popup).
- The compact `ThemePreview` sample remains on the theme-list cards.

## Live Apply

`ThemeSettingsActivity` mutates the `ThemeProvider`'s **own** `CustomThemeManager` instance (a private twin previously left the provider's in-memory store stale) and, when the save/delete touches the active theme, fires `CleverKeysService.ACTION_THEME_CHANGED` (package-restricted broadcast, `ThemeSettingsActivity.kt:249-252`). The IME rebuilds its keyboard view on receipt, so every edited color applies immediately. Theme *selection* additionally restarts the settings process for clean Compose re-theming.

## Configuration

| Setting | Key | Default | Source |
|---------|-----|---------|--------|
| **Theme** | `theme` | `"cleverkeysdark"` | `ThemeProvider.kt:331` (`FALLBACK_THEME_ID`) |
| **Swipe trail color** | `swipe_trail_color` | per-theme | synced by `CustomThemePrefPolicy` |

Opacity (keyboard/key/suggestion-bar) lives in the Appearance section, not the theme — see [Appearance](../settings/appearance-spec.md).

## Test Coverage

| Suite | File | Cases |
|-------|------|-------|
| Pure JVM | `src/test/kotlin/tribixbite/cleverkeys/theme/ThemeCreatorFieldWiringTest.kt` | 9 (field surfacing, composite blend, computed frames, role mapping, consumer wiring, JSON round-trip) |
| Pure JVM | `src/test/kotlin/tribixbite/cleverkeys/theme/CustomThemePrefPolicyTest.kt` | delete/save pref coherence |
| Pure JVM | `src/test/kotlin/tribixbite/cleverkeys/theme/ThemeProviderFallbackTest.kt` | dangling-id fallback |
| Pure JVM | `src/test/kotlin/tribixbite/cleverkeys/theme/MonetDynamicColorGateTest.kt` | Monet API gating |
| Mock JVM | `src/test/kotlin/tribixbite/cleverkeys/theme/ThemeEditorPreviewMappingTest.kt` | every editable colour reaches exactly its renderer field in the preview |
| Pure JVM | `src/test/kotlin/tribixbite/cleverkeys/theme/ThemePreviewSceneTest.kt` | preview trail effect, sample words, sample trail |
| Instrumented | `src/androidTest/kotlin/tribixbite/cleverkeys/theme/ThemeKeyboardPreviewViewTest.kt` | key/background pixels follow an edit; preview never calls the IME handler |

## Related Specifications

- [Appearance](../settings/appearance-spec.md) - Opacity, borders, sizing
