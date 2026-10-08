---
title: Switching Layouts - Technical Specification
description: Layout cycling, the narrow/wide layout selection, and language changes on bound layouts
user_guide: /wiki/layouts/switching-layouts/
status: implemented
version: v2.0.0 development
---

# Switching Layouts Technical Specification

## Overview

The enabled text layouts are the ordered list `Config.layouts` (Layout Manager order).
`switch_forward` and `switch_backward` cycle through that list; special layouts (numeric,
emoji, Greek/math) are shown on top of the current text layout without changing the
selection. The selected index is stored separately for narrow and wide screens. Since
GH #186/#61 a layout may carry a language binding, so a layout switch can also be a
language switch; see [Multi-Language Input](./multi-language-spec.md).

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| KeyboardReceiver | `KeyboardReceiver.kt` (`handle_event_key`) | Handles `SWITCH_FORWARD`, `SWITCH_BACKWARD`, `SWITCH_GREEKMATH` events |
| LayoutManager | `LayoutManager.kt` | Current text/special layout; `incrTextLayout`, `setTextLayout`, `current_layout_unmodified` |
| LayoutBridge | `wiring/LayoutBridge.kt` | Service-facing wrapper that also applies the layout to the view |
| Config | `Config.kt` | `layouts`, `layout_languages`, `get_current_layout`, `set_current_layout` |
| LayoutLanguageBinding | `LayoutLanguageBinding.kt` | Resolves the active languages for the selected layout |
| ActiveLanguageSync | `ActiveLanguageSync.kt` | Reloads dictionaries/contractions and re-warms swipe when the active language changes |

## Cycling

```kotlin
// KeyboardReceiver.kt
KeyValue.Event.SWITCH_FORWARD -> {
    if (layoutManager.getLayoutCount() > 1) {
        keyboardView.setKeyboard(layoutManager.incrTextLayout(1))
    }
}
```

```kotlin
// LayoutManager.kt
fun incrTextLayout(delta: Int): KeyboardData {
    val s = config.layouts.size
    val newIndex = (config.get_current_layout() + delta + s) % s
    return setTextLayout(newIndex)
}
```

`setTextLayout` calls `Config.set_current_layout`, clears any special layout and returns the
current layout with modifiers applied. With one enabled layout the switch events do nothing.
An index past the end of the list resolves to layout 0 (`current_layout_unmodified`); a
`null` entry is the System layout and resolves to the locale text layout.

## Narrow and Wide Selection

`Config.wide_screen` is true when the screen is at least 600 dp wide
(`WIDE_DEVICE_THRESHOLD`). `get_current_layout()` returns `current_layout_wide` or
`current_layout_narrow` accordingly, and `set_current_layout` writes the one in use. Both
are persisted (`current_layout_landscape`, `current_layout_portrait`), so rotating can
change the selected layout.

## Language Change on Switch

`set_current_layout` resolves the active languages immediately and re-publishes the config
snapshot when they changed, so gesture paths never read the old language:

```kotlin
// Config.kt
// GH #186/#61: a layout switch is a language switch when either layout is bound. Resolve
// now and re-publish the snapshot (gesture hot paths read primary_language from it), so
// nothing reads the old language between this call and the preference-listener refresh
// that follows the write below and drives ActiveLanguageSync.
if (recomputeActiveLanguages()) edit { }
```

The preference write that follows triggers a Config refresh; `CleverKeysService.onConfigChanged`
hands the active languages to `ActiveLanguageSync`, which reloads only what changed.

| Switch | Effect on languages |
|--------|---------------------|
| Unbound → unbound | None; nothing reloads, no message |
| Unbound → bound to X | Primary becomes X, secondary unloaded; bar shows "Language: X" (or "(no dictionary installed)") |
| Bound to X → bound to Y | Primary becomes Y; bar shows "Language: Y" |
| Bound → unbound | Back to the user's Multi-Language settings; no message |

A rotation that changes the narrow/wide selection follows the same path.

## Configuration

| Setting | Key | Default | Description |
|---------|-----|---------|-------------|
| **Layouts** | `layouts` | Built-in default list | Ordered enabled layouts; each entry may carry a `language` binding |
| **Portrait selection** | `current_layout_portrait` | 0 | Selected index when not wide |
| **Landscape selection** | `current_layout_landscape` | 0 | Selected index when wide (≥ 600 dp) |

## Test Coverage

| Suite | File |
|-------|------|
| Pure JVM | `src/test/kotlin/tribixbite/cleverkeys/LayoutLanguageBindingTest.kt` (includes a three-language cycle) |
| Pure JVM | `src/test/kotlin/tribixbite/cleverkeys/ActiveLanguageSyncTest.kt` (unbound switches reload nothing) |
| Mock | `src/test/kotlin/tribixbite/cleverkeys/LayoutLanguageBindingConfigTest.kt` (`set_current_layout` onto a bound layout) |

## Related Specifications

- [Multi-Language Input](./multi-language-spec.md) - Active languages and per-layout binding
- [Adding Layouts](adding-layouts-spec.md) - Layout management
- [Layout System](https://github.com/tribixbite/CleverKeys/blob/main/docs/specs/layout-system.md) - Full architecture
