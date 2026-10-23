# TrackPoint Navigation Mode

> **Status (2026-10-23):** as built; re-transcribed from `Pointers.kt`, `Config.kt` and
> `VibratorCompat.kt` at HEAD. The wiki copy at `docs/wiki/specs/gestures/trackpoint-mode-spec.md`
> is maintained separately.

## Overview

TrackPoint mode turns any key that carries arrow subkeys (W/E/N/S positions) into a joystick:
hold the key past the long-press timeout and the finger's offset from where it rested at
activation drives repeated arrow key events. Each axis is handled independently, so a finger
in the north-east quadrant sends both Up and Right; the repeat rate on each tick is set by the
larger offset. The same timer and constants drive Selection-Delete Mode
(`selection-delete-mode.md`).

## Key files

| File | Symbol | Purpose |
|------|--------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt` | `FLAG_P_TRACKPOINT_MODE = 1 shl 10` (`Pointers.kt:2374`) | pointer flag for the mode |
| same | `handleLongPress` (`:1539-1578`) | activation: `keyrepeat_enabled && hasNavigationSubkeys(ptr)` |
| same | `startTrackPointRepeat` / `stopTrackPointRepeat` (`:1267-1276`), `handleTrackPointRepeat` (`:1463-1537`) | the joystick timer |
| same | `hasNavigationSubkeys` (`:1938`), `isNavigationKey` (`:1907`) | nav-subkey detection: `key.keys[5..8]` holding a `Keyevent` with `KEYCODE_DPAD_*` |
| same | `onTouchMove` branch (`:1005-1010`), `onTouchUp` branch (`:263-270`) | moves only update `lastX/lastY`; release stops the timer |
| same | `TRACKPOINT_DEAD_ZONE = 15f`, `TRACKPOINT_INITIAL_DELAY = 50L`, `TRACKPOINT_MIN_DELAY = 30L`, `TRACKPOINT_MAX_DELAY = 200L`, `TRACKPOINT_MOVEMENT_THRESHOLD = 15f` (`:2385-2398`) | timing constants |
| `src/main/kotlin/tribixbite/cleverkeys/VibratorCompat.kt` | `HapticEvent.TRACKPOINT_ACTIVATE` → `HapticFeedbackConstants.CLOCK_TICK` (custom-duration fallback 15 ms) | activation haptic |
| `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | `haptic_trackpoint_activate` (`Defaults.HAPTIC_TRACKPOINT_ACTIVATE = true`, `:825`, read `:1162`) | the haptic toggle |
| `src/main/kotlin/tribixbite/cleverkeys/ui/settings/sections/AccessibilitySection.kt` | "TrackPoint activation" haptic switch (`:145`) | settings UI |

## Behaviour

### Activation (`handleLongPress`)

Keys with arrow subkeys are deferred on touch-down (`FLAG_P_DEFERRED_DOWN`) so a quick
swipe-and-release stays a short gesture. When the long-press timer fires for such a pointer:

```kotlin
// Pointers.kt:1562-1577 (abridged)
if (snap.keyrepeat_enabled && hasNavSubkeys) {
    ptr.flags = (ptr.flags and FLAG_P_DEFERRED_DOWN.inv()) or FLAG_P_TRACKPOINT_MODE
    ptr.keyCenterX = ptr.lastX          // joystick centre = where the finger IS now,
    ptr.keyCenterY = ptr.lastY          // not the original touch point
    _handler.onPointerFlagsChanged(HapticEvent.TRACKPOINT_ACTIVATE)
    startTrackPointRepeat(ptr)          // first tick after TRACKPOINT_INITIAL_DELAY (50 ms)
    return
}
```

Movement before the timeout does **not** prevent activation (the user may slide to a
comfortable spot and then hold); what decides between "short gesture" and "TrackPoint" is
whether the finger lifts before or after the long-press timeout. Already-swipe-typing pointers
(`FLAG_P_SWIPE_TYPING`) and pointers already in the mode are skipped. Key repeat must be enabled
(`keyrepeat_enabled`); with it off the key behaves as a plain subkey key.

### Joystick tick (`handleTrackPointRepeat`)

```kotlin
val dx = ptr.lastX - ptr.keyCenterX;  val dy = ptr.lastY - ptr.keyCenterY
val maxDistance = _handler.getKeyHypotenuse(ptr.key) * 0.5f     // half the key diagonal = "edge"

if (abs(dx) > TRACKPOINT_DEAD_ZONE) {
    val horizKey = _handler.modifyKey(if (dx > 0) key.keys[6] /* E */ else key.keys[5] /* W */, ptr.modifiers)
    if (isNavigationKey(horizKey)) { onPointerDown(horizKey, false); onPointerUp(horizKey, ptr.modifiers); moved = true }
}
if (abs(dy) > TRACKPOINT_DEAD_ZONE) {
    val vertKey = _handler.modifyKey(if (dy > 0) key.keys[8] /* S */ else key.keys[7] /* N */, ptr.modifiers)
    if (isNavigationKey(vertKey)) { … same …; moved = true }
}
val repeatDelay = if (moved)
    (TRACKPOINT_MAX_DELAY - maxNormalizedDist * (TRACKPOINT_MAX_DELAY - TRACKPOINT_MIN_DELAY)).toLong()
else TRACKPOINT_MAX_DELAY                                           // in the dead zone: re-check in 200 ms
```

- The events sent are the key's **own** subkeys (after `modifyKey` with the live modifiers), so
  Shift+arrow selection and Ctrl+arrow word jumps work when those modifiers are latched.
- `maxNormalizedDist = max over moved axes of min(|d| / maxDistance, 1)`: delay is linear from
  200 ms just outside the 15 px dead zone to 30 ms at the key's edge (about 5 to 33 events/s).
- There is no 8-sector angle classification and no `TrackPointDirection` enum; diagonals are
  simply both axes passing their dead zone in the same tick.

### Move and release

`onTouchMove` only refreshes `ptr.lastX/lastY` and returns (no swipe path, slider or short
gesture logic runs). `onTouchUp` calls `stopTrackPointRepeat`, removes the pointer and clears
the key highlight (`onPointerFlagsChanged(null)`); no key event is sent on release.

## Configuration

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `haptic_trackpoint_activate` | Boolean | `true` | CLOCK_TICK on activation (also used by Selection-Delete activation); gated by the master `haptic_enabled` |
| `keyrepeat_enabled` | Boolean | `true` | prerequisite: TrackPoint uses the key-repeat/long-press machinery |
| `longpress_timeout` | Int (ms) | 600 | the hold time before activation |

`short_gesture_min_distance` (default **28, % of the key diagonal**, not pixels) governs short
gestures on the same keys and the Selection-Delete activation gate; it is not a TrackPoint
threshold. The dead zone and delays above are compile-time constants, not preferences.

## Tests

- `PointersFollowFingerSelectionTest` and `PointersSubkeyPopoverTest` (pure JVM) exercise the
  shared long-press/TrackPoint routing; `VibratorCompatTest` pins the `TRACKPOINT_ACTIVATE` →
  `CLOCK_TICK` mapping and its pref gate; `ConfigDefaultsTest` pins the default.
