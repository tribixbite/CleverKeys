# Selection-Delete Mode

> **Status (2026-10-23):** as built; re-transcribed from `Pointers.kt` and `Config.kt` at HEAD.
> The wiki copy at `docs/wiki/specs/gestures/selection-delete-spec.md` is maintained separately.

## Overview

Selection-Delete Mode is a gesture on the backspace key: short-swipe, then hold without lifting.
While held, finger movement extends a text selection with Shift+arrow key events (horizontal =
characters, vertical = lines), and releasing the finger sends one DEL, which deletes the
selection. Two drive modes exist since GH #190 (`selection_delete_mode`):

- **speed** (default, the historical behaviour): a rate-controlled joystick. The further the
  finger sits from the activation point, the faster Shift+arrow repeats.
- **follow**: the selection length is proportional to the finger's travel from the activation
  point; moving back shrinks it (HeliBoard/FlorisBoard style). No timer: every MOVE recomputes
  the selection.

## Key files

| File | Symbol | Purpose |
|------|--------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt` | `FLAG_P_SELECTION_DELETE_MODE = 1 shl 11` (`Pointers.kt:2377`) | pointer flag for the mode |
| same | activation inside the long-press handler (`Pointers.kt:~1580-1600`) | short swipe + hold on backspace → sets the flag |
| same | `startSelectionDeleteRepeat` / `stopSelectionDeleteRepeat` / `handleSelectionDeleteRepeat` (`:1280-1410`) | speed mode (timer-driven) |
| same | `updateFollowFingerSelection` / `sendSelectionSteps` (`:1412-1460`), `followFingerSteps` / `followFingerLines` (companion, `:2414-2430`) | follow mode (move-driven) |
| same | `onTouchUp` branch (`:272-285`) | release → one `KEYCODE_DEL` |
| same | `SELECTION_ARROW_LEFT/RIGHT/UP/DOWN` (`:2402-2405`) | the Shift+arrow `KeyValue`s |
| same | `TRACKPOINT_DEAD_ZONE = 15f`, `TRACKPOINT_INITIAL_DELAY = 50L`, `TRACKPOINT_MIN_DELAY = 30L`, `TRACKPOINT_MAX_DELAY = 200L` | timing shared with TrackPoint mode |
| `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | `selection_delete_vertical_threshold`, `selection_delete_vertical_speed`, `selection_delete_follow_finger`, `selection_delete_step_px` (`:1008-1013`; read at `:1343-1349`) | live values, also in `ConfigSnapshot` |
| same | `Defaults.SELECTION_DELETE_*`, `SettingsRanges.SELECTION_DELETE_STEP_DP = 4..40` | defaults and the shared range |
| `src/main/kotlin/tribixbite/cleverkeys/ui/settings/sections/GestureTuningSection.kt` | sliders + mode dropdown (`:170-250`) | settings UI |

## Behaviour

### Activation (`Pointers.kt`, long-press timeout handler)

1. The pointer is on a backspace key (`isBackspaceKey(ptr.value)`) and key repeat is enabled
   (`snap.keyrepeat_enabled`). Backspace presses are deferred (`FLAG_P_DEFERRED_DOWN`) so the
   gesture can be told apart from a plain press.
2. At the long-press timeout, if the finger has moved at least the short-gesture minimum
   (`movementDist >= shortGestureMinDistancePx(ptr.key, snap)`, i.e. `short_gesture_min_distance`
   as % of the key diagonal), the mode activates:
   `ptr.flags = (ptr.flags and FLAG_P_DEFERRED_DOWN.inv()) or FLAG_P_SELECTION_DELETE_MODE`.
   No movement + hold is ordinary backspace repeat instead.
3. The current finger position becomes the activation centre (`ptr.keyCenterX/Y = ptr.lastX/Y`),
   `HapticEvent.TRACKPOINT_ACTIVATE` fires (the "haptic_trackpoint_activate" preference), and
   either the repeat timer starts (`startSelectionDeleteRepeat`, first tick after
   `TRACKPOINT_INITIAL_DELAY`) or, in follow mode, nothing is scheduled: `onTouchMove` calls
   `updateFollowFingerSelection` on every move.

### Speed mode (`handleSelectionDeleteRepeat`)

Each tick reads `dx = lastX - keyCenterX`, `dy = lastY - keyCenterY`, with
`keyHypotenuse = getKeyHypotenuse(key)`, `keyHeight = keyHypotenuse * 0.7f`,
`maxDistance = keyHypotenuse * 0.5f` and
`verticalDeadZone = keyHeight * selection_delete_vertical_threshold / 100`.

- Horizontal: when `|dx| > TRACKPOINT_DEAD_ZONE` (15 px) send one Shift+DPAD_RIGHT (dx > 0) or
  Shift+DPAD_LEFT through `_handler.onPointerDown(arrowKey, false)` / `onPointerUp(arrowKey,
  shiftedMods)`, where `shiftedMods = ptr.modifiers.with_extra_mod(makeInternalModifier(SHIFT))`.
- Vertical: when `|dy| > verticalDeadZone` send one Shift+DPAD_DOWN / Shift+DPAD_UP the same way.
  Both axes are checked independently in one tick, so diagonal selection works.
- Next delay: linear in the normalised distance from `TRACKPOINT_MAX_DELAY` (200 ms) down to
  `TRACKPOINT_MIN_DELAY` (30 ms); the vertical delay is divided by
  `selection_delete_vertical_speed` (so 0.4 is 2.5× slower). With both axes moving the shorter
  delay wins; inside both dead zones the timer re-checks after 200 ms.

### Follow mode (`updateFollowFingerSelection`)

- `targetX = followFingerSteps(dx, selection_delete_step_px)` = `trunc(dx / step)` characters,
  signed; `selection_delete_step_px` is `selection_delete_step_dp` converted to px.
- `targetY = followFingerLines(dy, verticalDeadZone, keyHeight)`: 0 inside the vertical dead
  zone, one line as soon as the finger leaves it, one more per further key height.
- The pointer keeps `selectionStepsX/Y` (steps already sent); `sendSelectionSteps` sends only
  the difference as repeated Shift+arrow presses, in the opposite direction when the target
  shrinks. Horizontal is sent before vertical.

### Release (`onTouchUp`)

`stopSelectionDeleteRepeat(ptr)`, then one `KeyValue.keyeventKey(0xE003, KEYCODE_DEL, 0)`
through `onPointerDown`/`onPointerUp` with the pointer's own modifiers; the pointer is removed
and the key highlight cleared (`onPointerFlagsChanged(null)`). Deleting the selection is what
the editor does with DEL over a non-empty selection; CleverKeys does not read the selection.

## Configuration

| Key | Type | Default | Range (slider) | Description |
|-----|------|---------|----------------|-------------|
| `selection_delete_vertical_threshold` | Int | 40 | 20–80, step 5 | vertical dead zone as % of key height |
| `selection_delete_vertical_speed` | Float | 0.4 | 0.1–1.0, step 0.05 | divides the vertical repeat delay (speed mode only) |
| `selection_delete_mode` | String | `"speed"` | `speed` \| `follow` (dropdown; the stored value is the id, never the label) | GH #190 drive mode |
| `selection_delete_step_dp` | Int | 12 | `SettingsRanges.SELECTION_DELETE_STEP_DP` = 4..40, step 1 | finger travel per character (follow mode only; slider shown only when follow is selected) |

All four are part of the typed backup/default/reset maps; imported out-of-range values are
clamped by Config (`SELECTION_DELETE_STEP_DP` is shared by slider, validator and clamp).

## Tests

Pure JVM (`src/test/kotlin/tribixbite/cleverkeys/`, verified 2026-10-23):

- `PointersFollowFingerSelectionTest` — follow-mode step/line maths, shrink-on-return, the
  speed/follow routing.
- `ReleaseClaimGestureModesTest` — the release-record claims for the gesture modes.
- `ConfigDefaultsTest`, `DefaultsSingleDefinitionDriftTest`, `SettingsRangeDriftTest` — the
  defaults and `SettingsRanges.SELECTION_DELETE_STEP_DP` shared by slider, validator and clamp.
