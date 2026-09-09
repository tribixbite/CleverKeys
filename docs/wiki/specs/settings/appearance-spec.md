---
title: Appearance Settings - Technical Specification
user_guide: ../../settings/appearance.md
status: implemented
version: v1.5.0
---

# Appearance Settings Technical Specification

## Source Location Reference

| Fact | Source File | Line(s) | Value |
|------|------------|---------|-------|
| Default theme | `Config.kt` | 21 | `THEME = "cleverkeysdark"` |
| Portrait height default | `Config.kt` | 24 | `KEYBOARD_HEIGHT_PORTRAIT = 27` (percent of screen height) |
| Landscape height default | `Config.kt` | 25 | `KEYBOARD_HEIGHT_LANDSCAPE = 40` |
| Border defaults | `Config.kt` | 43-45 | `BORDER_CONFIG = false`, `CUSTOM_BORDER_RADIUS = 0`, `CUSTOM_BORDER_LINE_WIDTH = 0` |
| Preference reads | `Config.kt` | 743-839 | height, margins, opacities, border, label sizes |
| Row height + margins | `Theme.kt` | 294-308 | `Theme.Computed.init` |
| Per-key paints | `Theme.kt` | 362-390 | `Theme.Computed.Key.init` |
| Key frame drawing | `Keyboard2View.kt` | 1729-1738 | `drawKeyFrame` |
| Settings UI section | `ui/settings/sections/AppearanceSection.kt` | whole file | every control listed below |

## Overview

The appearance system manages keyboard dimensions, margins, key opacity/border styling and
label sizing. It has three layers:

1. **`Config`** reads the raw preferences and converts them into render-ready units —
   percentages become pixels, 0-100 opacities become 0-255 alphas, dp values become px.
2. **`Theme.Computed`** derives per-layout geometry (row height, key margins) and builds the
   `Paint` objects for each key role from the active theme plus those `Config` values.
3. **`Keyboard2View`** draws with them.

There is no separate dimension calculator, animation controller, key-popup view or
prediction-bar view — those are the responsibility of the three types above plus
`SuggestionBar`.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| `Keyboard2View` | `Keyboard2View.kt` | Main rendering (key frames, labels, swipe trail) |
| `Theme.Computed` | `Theme.kt` | Row/key geometry and per-role `Paint` construction |
| `Config` | `Config.kt` | Reads appearance preferences, converts to render units |
| `AppearanceSection` | `ui/settings/sections/AppearanceSection.kt` | The Settings UI |

## Dimension Calculation

### Height

Keyboard height is a **percentage of screen height**, not a preset enum. `Config` picks the
portrait or landscape key by orientation (and the `_unfolded` variant on a foldable that
reports itself unfolded):

```kotlin
// Config.kt:746-760
if (orientation_landscape) {
    keyboardHeightPercent = safeGetInt(
        _prefs,
        if (this.foldable_unfolded) "keyboard_height_landscape_unfolded" else "keyboard_height_landscape",
        Defaults.KEYBOARD_HEIGHT_LANDSCAPE
    )
    characterSizeScale = 1.25f
} else {
    keyboardHeightPercent = safeGetInt(
        _prefs,
        if (this.foldable_unfolded) "keyboard_height_unfolded" else "keyboard_height",
        Defaults.KEYBOARD_HEIGHT_PORTRAIT
    )
}
```

Row height then divides that by a fixed `3.95` layout-unit constant, so a layout with more
rows makes the keyboard taller rather than squashing the rows — except for numeric layouts
with `scale_numpad_height` on, which divide by the layout's own height so the rows stretch to
fill (#58):

```kotlin
// Theme.kt:294-302
val heightDivisor = if (config.scale_numpad_height && !layout.bottom_row) {
    layout.keysHeight
} else {
    3.95f
}
row_height = min(
    config.screenHeightPixels * config.keyboardHeightPercent / 100 / heightDivisor,
    config.screenHeightPixels / layout.keysHeight
)
```

### Margins

Outer margins are percentages of the screen dimension, resolved per orientation with the same
`_portrait`/`_landscape`/`_unfolded` suffix scheme (`Config.get_percent_pref_oriented_width`
and `..._height`, `Config.kt:1190-1224`). Bottom margins clamp to 0-30%, left/right to 0-45%
each; the Settings sliders additionally cap left+right at 90% combined so the keyboard can
never be squeezed to nothing.

Per-key margins are separate and multiplicative against the computed cell:

```kotlin
// Theme.kt:303-308
vertical_margin = config.key_vertical_margin * row_height
horizontal_margin = config.key_horizontal_margin * keyWidth
margin_top = config.marginTop + vertical_margin / 2
margin_left = horizontal_margin / 2
```

## Key Rendering

### Key frame

Every key is a rounded rect plus an optional border, from a per-role `Theme.Computed.Key`:

```kotlin
// Keyboard2View.kt:1729-1738
private fun drawKeyFrame(canvas: Canvas, x: Float, y: Float, keyW: Float, keyH: Float, tc: Theme.Computed.Key) {
    val r = tc.border_radius
    val w = tc.border_width
    val padding = w / 2f
    _tmpRect.set(x + padding, y + padding, x + keyW - padding, y + keyH - padding)
    canvas.drawRoundRect(_tmpRect, r, r, tc.bg_paint)
    if (w > 0f) {
        canvas.drawRoundRect(_tmpRect, r, r, tc.border_paint)
    }
}
```

There is no key-shape preference. The corner radius comes from the theme unless
`border_config` is on, in which case both radius and line width come from the two custom
sliders — the radius being a **fraction of key width** (the stored 0-100 int is divided by
100 at `Config.kt:834`), not a dp value:

```kotlin
// Theme.kt:369-377
if (config.borderConfig) {
    border_radius = config.customBorderRadius * keyWidth
    border_width = config.customBorderLineWidth
} else {
    border_radius = theme.keyBorderRadius
    border_width = if (activated) theme.keyBorderWidthActivated else theme.keyBorderWidth
}

bg_paint.alpha = if (activated) config.keyActivatedOpacity else config.keyOpacity
```

`Theme.Computed.roleOf` (`Keyboard2View.kt:1584`) selects one of five prebuilt frames —
NORMAL, ACTIVATED, LOCKED, MODIFIER, SPECIAL — from the key's kind plus pointer state.

### Press feedback

Press feedback is a **frame swap, not an animation**: a held key renders with the ACTIVATED
frame, whose background alpha is `key_activated_opacity` (default 80%, deliberately below the
100% at-rest `key_opacity` so the state change reads as press feedback rather than a pure
colour shift — see the `KEY_ACTIVATED_OPACITY` comment at `Config.kt:29-32`). There is no
scale/highlight animation setting and no key-preview popup above the key.

### Opacity and label brightness

The four 0-100 percentage preferences are converted to 0-255 alpha once, at read time:

```kotlin
// Config.kt:828-831
labelBrightness = safeGetInt(_prefs, "label_brightness", Defaults.LABEL_BRIGHTNESS) * 255 / 100
keyboardOpacity = safeGetInt(_prefs, "keyboard_opacity", Defaults.KEYBOARD_OPACITY) * 255 / 100
keyOpacity = safeGetInt(_prefs, "key_opacity", Defaults.KEY_OPACITY) * 255 / 100
keyActivatedOpacity = safeGetInt(_prefs, "key_activated_opacity", Defaults.KEY_ACTIVATED_OPACITY) * 255 / 100
```

`keyboardOpacity` is applied to the view background (`Keyboard2View.kt:1548`); the other
three land on the per-role paints (`Theme.kt:377`, `Theme.kt:389`), with label brightness
packed into the label paint's alpha bits so it multiplies every label colour uniformly.

## Prediction Bar

The suggestion bar (`SuggestionBar.kt`) sizes itself from its content — its row height comes
from Android layout and fixed dp padding, **not** from a height preference, and the number of
visible suggestions is not capped by a preference either (the bar scrolls horizontally). Its
one appearance preference is opacity, and it lives in the Input Behavior section's Word
Prediction block rather than here:

- `suggestion_bar_opacity` (default `80`, range 0-100) — read at `Config.kt:901`, applied
  via `PreferenceUIUpdateHandler.kt:62` / `PredictionViewSetup.kt:101`.

See [Input Behavior](./input-behavior-spec.md) for that control.

## Configuration

Every row below is a preference key the app actually reads. "Range" gives the Settings slider
bound; where the import validator (`backup/SettingsValidation.kt`) accepts a wider band, both
are shown.

| Setting | Key | Default | Range |
|---------|-----|---------|-------|
| **Theme** | `theme` | `cleverkeysdark` | theme id — see [Themes](../customization/themes-spec.md) |
| **Portrait Height** | `keyboard_height` | 27 | slider 20-60 (% of screen height); validator 10-100 |
| **Landscape Height** | `keyboard_height_landscape` | 40 | slider 20-60; validator 20-65 |
| **Scale Numpad Height** | `scale_numpad_height` | true | bool — numeric layouts (`bottom_row=false`) stretch rows to the full keyboard height (`Config.kt:512`, consumer `Theme.kt:294`; switch added 2026-09-08, F-8/#58) |
| **Bottom Margin (Portrait)** | `margin_bottom_portrait` | 0 | 0-30 (% of screen height) |
| **Bottom Margin (Landscape)** | `margin_bottom_landscape` | 0 | 0-30 |
| **Left Margin (Portrait)** | `margin_left_portrait` | 1 | 0-45 (% of screen width); UI caps left+right at 90 combined |
| **Right Margin (Portrait)** | `margin_right_portrait` | 1 | 0-45, same combined cap |
| **Left Margin (Landscape)** | `margin_left_landscape` | 5 | 0-45, same combined cap |
| **Right Margin (Landscape)** | `margin_right_landscape` | 5 | 0-45, same combined cap |
| **Label Brightness** | `label_brightness` | 100 | 0-100% |
| **Keyboard Opacity** | `keyboard_opacity` | 100 | 0-100% |
| **Key Opacity** | `key_opacity` | 100 | 0-100% |
| **Activated Key Opacity** | `key_activated_opacity` | 80 | 0-100% |
| **Character Size** | `character_size` | 1.18 | 0.5-2.0 (`SettingsRanges.CHARACTER_SIZE`; UI slider 50%-200%) |
| **Secondary Label Size** | `secondary_label_size_scale` | 1.0 (100% = unchanged) | 0.5-2.0 (UI slider 50%-200%) |
| **Key Vertical Margin** | `key_vertical_margin` | 1.5 | stored 0-5 (UI slider 0-500, divided by 100) |
| **Key Horizontal Margin** | `key_horizontal_margin` | 2.0 | stored 0-5 (UI slider 0-500, divided by 100) |
| **Custom Border** | `border_config` | false | bool — gates the two rows below |
| **Border Radius** | `custom_border_radius` | 0 | slider 0-20; validator 0-100. Stored value / 100 = fraction of key width |
| **Border Line Width** | `custom_border_line_width` | 0 | 0-10 dp (`SettingsRanges.CUSTOM_BORDER_LINE_WIDTH`) |

On a foldable that reports itself unfolded, `Config` reads `_unfolded` variants of the height
and margin keys (`keyboard_height_unfolded`, `margin_left_portrait_unfolded`, …) with the same
defaults and ranges. No Settings control writes them; they arrive through settings import.

### Secondary Label Size (#133, v1.5.0)

Independent scale for the small corner (short-swipe/flick) labels, decoupled from Character
Size. Default `SECONDARY_LABEL_SIZE_SCALE = 1.0f` (`Config.kt:40`); applied multiplicatively
against the theme's `SUBLABEL_TEXT_SIZE_FACTOR` in `Keyboard2View.kt:1480`:

```kotlin
_subLabelSize = labelBaseSize * snap.sublabelTextSize * snap.secondary_label_size_scale
```

Turn it down when a large Character Size makes sublabels crowd the main label.

## Related Specifications

- [Themes](../customization/themes-spec.md) - Color system
- [Settings System](../../../specs/settings-system.md) - Preferences
- [Layout System](../../../specs/layout-system.md) - Key layout
