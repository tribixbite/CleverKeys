---
title: Full Trackpad Mode — Design Specification
description: Planned feature converting the entire keyboard surface into a virtual trackpad for cursor navigation. Design proposal in response to issue #143.
status: planning
version: v1.4.0+
---

# Full Trackpad Mode — Design Specification

> **Status:** Planning. This document is a design proposal in response to
> [GitHub issue #143](https://github.com/tribixbite/CleverKeys/issues/143).
> It is NOT implemented yet. Linked from `docs/ROADMAP.md` for tracking.

## Motivation

The existing [TrackPoint Mode](trackpoint-mode-spec.md) (`FLAG_P_TRACKPOINT_MODE`) converts the area around a single navigation key into a joystick when the user holds that key. Issue #143 reports that this is "inconvenient due to space restraint" — the user wants to use their thumb across the FULL keyboard surface for finer cursor control, not just the area of one key.

This spec describes a `FULL_TRACKPAD_MODE` that:

- Activates on command (mapped to a short-swipe via Per-Key Actions, OR a dedicated key event)
- Suppresses all normal key input
- Treats the entire keyboard panel as a single touch surface for cursor movement
- Exits via a clearly-distinguishable gesture so the user never gets stuck

The legacy per-key TrackPoint stays in place; this is an additive mode for users who want the wider gesture area.

## User Flow

```
[normal typing]
     |
     | user invokes full_trackpad_mode command
     | (mapped to any short-swipe direction or key tap)
     ↓
┌────────────────────────────────────────────────────┐
│  FULL TRACKPAD MODE                                │
│  - All key labels dimmed to 40% alpha              │
│  - Brief 2-second toast: "Quick-tap anywhere to exit" │
│  - Any finger drag → cursor moves with speed scaling │
│  - Quick tap (< 200ms, < 10px) → exit mode         │
│  - Two-finger drag → scroll (Phase 2)              │
└────────────────────────────────────────────────────┘
     |
     | user quick-taps
     ↓
[normal typing resumed]
```

## Activation

A new command `full_trackpad_mode` registered in `CommandRegistry.kt` under `Category.CURSOR`. Users assign it to:

- A short-swipe direction on any key (via Per-Key Actions → Action Type: Command → "Toggle Full Trackpad Mode")
- A dedicated layout key via `command:'full_trackpad_mode'` in the layout XML
- A future quick-setting tile (out of scope for v1)

Programmatic activation also exposed for accessibility tools.

## Touch Handling

`Keyboard2View.onTouch(v, event)` (currently dispatching to `_pointers.onTouchDown/Move/Up/Cancel`) gains a check at the top:

```kotlin
override fun onTouch(v: View?, event: MotionEvent?): Boolean {
    if (_fullTrackpadActive) {
        return handleFullTrackpadTouch(event ?: return false)
    }
    // ... existing dispatch ...
}
```

`handleFullTrackpadTouch` is a new method whose state machine:

| Event | Behavior |
|-------|----------|
| `ACTION_DOWN` | Record `originX = event.x`, `originY = event.y`, `downTimeMs = event.eventTime`. Reset `maxDisplacement = 0`. |
| `ACTION_MOVE` | `dx = event.x - originX`, `dy = event.y - originY`. If `\|dx\|` or `\|dy\|` exceeds `TRACKPOINT_DEAD_ZONE`, emit cursor-movement keys via `_handler.onPointerDown/Up` using the same axis-independent + delay-scaling formula as `Pointers.handleTrackPointRepeat()`. Track `maxDisplacement = max(maxDisplacement, hypot(dx, dy))`. |
| `ACTION_UP` | If `(eventTime - downTimeMs < 200ms) && (maxDisplacement < 10px)`, treat as exit-tap: `_fullTrackpadActive = false`, refresh visual state. Otherwise no action. |
| `ACTION_CANCEL` | No state change; mode stays active. |

The cursor-movement math is borrowed directly from `Pointers.kt:1083-1157`:

```kotlin
val maxDistance = (height * 0.5f).coerceAtLeast(1f)
val normalized = (displacement / maxDistance).coerceIn(0f, 1f)
val repeatDelay = TRACKPOINT_MAX_DELAY -
    (normalized * (TRACKPOINT_MAX_DELAY - TRACKPOINT_MIN_DELAY))
```

So speed scales 0 → max from dead zone to keyboard edge.

## Visual Treatment

The keyboard panel dims its key labels to 40% alpha when `_fullTrackpadActive` is true. Implementation:

- `Keyboard2View.onDraw()` checks the flag and applies `paint.alpha = 0.4 * 255` to text glyphs (NOT background) when the mode is active.
- A brief toast or in-IME overlay (per `ime-visual-feedback.md` — toasts are invisible behind the IME panel, so use an in-view fadeable TextView) shows: "TRACKPAD MODE — quick-tap anywhere to exit".
- The overlay fades after 2 seconds. The dimming persists until exit.

Optional phase-2 additions:
- Crosshair indicator at the touch point
- Subtle border accent in the active color

## Exit

Primary: **quick-tap anywhere**. Defined as `ACTION_UP - ACTION_DOWN < 200ms` AND `maxDisplacement < 10px`. This is the user's gesture for "I'm done."

Backup: a small "✕" icon shown in the top-right of the keyboard while mode is active (touchable). Phase-2 polish.

Hard exit: any `ACTION_CANCEL` from system (e.g., `onFinishInputView()` fires) automatically exits the mode so the user doesn't get stuck.

## Configuration

New `Config.kt` keys (all in the "Gestures" section):

| Key | Default | Range | Purpose |
|-----|---------|-------|---------|
| `full_trackpad_mode_enabled` | `true` | bool | Master toggle; if false, the `full_trackpad_mode` command is a no-op |
| `full_trackpad_speed_multiplier` | `1.0f` | 0.5-3.0 | Scales the cursor-movement rate; user-tunable |
| `full_trackpad_dim_keys` | `true` | bool | Whether to dim key labels in mode (some users prefer to see keys) |
| `full_trackpad_show_hint` | `true` | bool | Whether to show the 2s exit hint overlay (suppressible after user knows the gesture) |

All readable via `SafePreferences` per the project pattern. Settings UI section under Gestures or Cursor Navigation.

## Out of Scope (Phase 2)

- Two-finger drag for scrolling (PgUp/PgDn or smooth scroll keys)
- Two-finger tap for selection-toggle (Shift+arrow mode)
- Pinch-to-zoom (rare in text fields, low value)
- Configurable activation gestures beyond command assignment

## File Touch List (when implemented)

| File | Change |
|------|--------|
| `src/main/kotlin/tribixbite/cleverkeys/customization/CommandRegistry.kt` | Add `Command("full_trackpad_mode", "Toggle Full Trackpad Mode", ...)` |
| `src/main/kotlin/tribixbite/cleverkeys/customization/CustomShortSwipeExecutor.kt` | Add `"full_trackpad_mode"` branch in `executeCommandByName()` |
| `src/main/kotlin/tribixbite/cleverkeys/Keyboard2View.kt` | Add `_fullTrackpadActive` flag, `handleFullTrackpadTouch()` method, `onTouch()` interception, `onDraw()` dimming |
| `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | Add 4 new pref keys to Defaults + Config fields + refresh() reads |
| `src/main/kotlin/tribixbite/cleverkeys/SettingsActivity.kt` | New section under Gestures with the 4 toggles |
| `src/main/kotlin/tribixbite/cleverkeys/backup/SettingsValidation.kt` | Classify the 4 new keys |
| `src/main/kotlin/tribixbite/cleverkeys/backup/SettingsDefaults.kt` | Add defaults to `SETTINGS_DEFAULTS` map |
| `src/test/kotlin/tribixbite/cleverkeys/FullTrackpadModeTest.kt` | New pure-JVM tests for the touch math (dead zone, speed scaling, quick-tap exit) |
| `src/androidTest/kotlin/tribixbite/cleverkeys/FullTrackpadIntegrationTest.kt` | Optional ew-cli test that simulates touch sequences and asserts cursor key events fire |
| `docs/wiki/gestures/full-trackpad-mode.md` | User guide (write at implementation time) |
| `docs/wiki/specs/gestures/full-trackpad-mode-spec.md` | This file — update `status: implemented` and refresh implementation details at implementation time |

Estimated effort: ~250 LOC main + ~80 LOC tests + ~150 LOC docs. Single-session implementation if UX decisions are pre-made.

## UX Decisions To Confirm Before Implementation

These need owner sign-off (no autonomous choice):

1. **Exit gesture choice**: quick-tap (proposed) vs. two-finger tap vs. long-press-and-hold a corner vs. dedicated visible "✕" button. Each has tradeoffs:
   - Quick-tap: discoverable but conflicts with potential "tap-to-cursor-position" semantics.
   - Two-finger tap: rare false-positive but requires two hands.
   - Long-press corner: invisible, easy to miss.
   - Visible ✕: discoverable, takes screen space.
2. **Should tap-down-then-release without movement also place the cursor at that position?** This is what a real laptop trackpad does (tap = click). If yes, the "quick-tap exits" gesture needs a different signature (e.g., two consecutive quick-taps).
3. **What's the activation default?** Should there be a default key mapping (e.g., long-press space) or strictly require user opt-in via Per-Key Actions?
4. **Visual indicator strength**: full overlay vs. just label dimming vs. nothing. Some users may find the overlay distracting.

## Related Specifications

- [TrackPoint Mode](./trackpoint-mode-spec.md) — Per-key trackpad; the model this expands on
- [Cursor Navigation](./cursor-navigation-spec.md) — Slider math + key repeat acceleration
- [Per-Key Actions](../customization/per-key-actions-spec.md) — Command assignment system
- [Selection-Delete Mode](./selection-delete-spec.md) — Similar mode-flag pattern
