# Quick Settings Tile

> **Status (2026-10-23):** as built. The tile became a show/minimize/restore toggle on
> 2026-10-20 (`f47d5f3f`…`be586c84`); the full behaviour, the device evidence and the
> overlay-permission check live in [`keyboard-minimize.md`](keyboard-minimize.md)
> "Quick Settings tile (2026-10-20)". This page is the short engineering summary. The wiki copy
> at `docs/wiki/specs/customization/quick-settings-tile-spec.md` is maintained separately.

## Overview

CleverKeys registers an Android Quick Settings tile. A tap acts on the keyboard itself: it
minimizes a visible keyboard, restores a minimized one, brings back a keyboard the shade (or
the app) just hid, and only falls back to the system input-method picker when CleverKeys is
not the selected IME or no editor is focused. The tile's subtitle (Android 10+) names the
action the next tap will take.

## Key files

| File | Symbol | Purpose |
|------|--------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/KeyboardTileService.kt` | `KeyboardTileService` | `TileService`; reads state, dispatches the tap |
| `src/main/kotlin/tribixbite/cleverkeys/KeyboardTileAction.kt` | `KeyboardTileDecision.actionFor(isCurrentIme, window)` | pure decision table (tap → `KeyboardTileAction`) |
| same | `KeyboardTileAction` | `PICK_KEYBOARD`, `MINIMIZE`, `RESTORE`, `SHOW_MINIMIZED`, `SHOW_RESTORED`, `SHOW` |
| same | `TileWindowSnapshot`, `EditorRef`, `VisibleForm` | what the service reports about its window and editor |
| same | `TileShowSession` | pure state machine for the deferred show (retry every 250 ms for up to 8 s) |
| `src/main/kotlin/tribixbite/cleverkeys/CleverKeysService.kt` | `tileSnapshot()`, `minimizeFromTile()`, `restoreFromTile()`, `showFromTile(action)`, `logTileDecision(…)` | the service side |
| `src/main/kotlin/tribixbite/cleverkeys/minimize/KeyboardMinimizer.kt` | `lastStyle` | the style a tile minimize uses (floating button until a minimize command picks another) |
| `AndroidManifest.xml` | `<service android:name="tribixbite.cleverkeys.KeyboardTileService" … BIND_QUICK_SETTINGS_TILE …>` | tile registration (`tools:targetApi="24"`) |

## Decision table

`KeyboardTileService.onClick` computes `isCleverKeysCurrent()` (package substring of
`Settings.Secure.DEFAULT_INPUT_METHOD`, via `tileStateFor`), asks the running service for a
`tileSnapshot()` through `CleverKeysService.getInstance()`, and routes on
`KeyboardTileDecision.actionFor`:

| CleverKeys selected? | window | tap → action | subtitle |
|---|---|---|---|
| no, or service not running | any | `PICK_KEYBOARD`: `InputMethodManager.showInputMethodPicker()`; if no IMM, `startActivityAndCollapse` to `ACTION_INPUT_METHOD_SETTINGS` (PendingIntent overload on API 34+) | Switch keyboard |
| yes | visible, full | `MINIMIZE` → `minimizeFromTile()` (style `KeyboardMinimizer.lastStyle`) | Minimize |
| yes | visible, minimized | `RESTORE` → `restoreFromTile()` (`expand()`) | Restore |
| yes | hidden ≤ 10 s ago (`RECENT_HIDE_MS`), not dismissed by the user, input still in that app / unbound / SystemUI; was full | `SHOW_MINIMIZED` → deferred show landing minimized | Minimize |
| yes | same, was minimized | `SHOW_RESTORED` → deferred show landing full | Restore |
| yes | otherwise, a real editor is bound (`isRealEditor`) | `SHOW` → deferred show landing full | Show |
| yes | otherwise (e.g. the launcher's `TYPE_NULL` fallback connection) | `PICK_KEYBOARD` | Switch keyboard |

Details that matter when reading the code:

- **Deferred show.** `showFromTile` arms a `TileShowSession` target and calls
  `requestShowSelf(0)` (API 28+; `showSoftInputFromInputMethod` below) at once and every
  `RETRY_INTERVAL_MS` (250 ms) for `RETRY_WINDOW_MS` (8 s), because the system ignores the show
  while the shade holds focus. `onWindowShown` applies the target once; `onStartInput` for
  another package, a hardware key, a tap on a visible keyboard, the deadline and `onDestroy`
  cancel it.
- **No overlay permission.** `requestShowSelf` needs nothing beyond being the current IME; the
  2026-10-22 check in `keyboard-minimize.md` "Overlay permission check" documents why
  `SYSTEM_ALERT_WINDOW` is only relevant to a floating button that outlives the input window
  (roadmap 4.3, not built).
- **The shade stays open** after a keyboard action: `startActivityAndCollapse` / `showDialog`
  would take focus from the app's editor, and `StatusBarManager.collapsePanels` is hidden API.
  The tile re-reads its state 300 ms and 1 s after a tap (`TILE_REFRESH_DELAYS_MS`) and again
  after `onStartListening`, since opening the shade can itself hide the keyboard.
- **Tile state.** `STATE_ACTIVE` when CleverKeys is the selected IME, else `STATE_INACTIVE`;
  label is the app name; `contentDescription` comes from
  `KeyboardTileDecision.contentDescriptionFor(isCurrentIme, action)`.
- **Diagnostics.** Verbose builds (`LOCAL_BUILD=true`) log every decision with its inputs under
  `CleverKeys/Tile` (`adb logcat -s CleverKeys/Tile`).

## Requirements

- Android 7.0 (API 24) or higher (`TileService`); subtitle on Android 10 (API 29) or higher.
- The user adds the tile from the shade's edit mode (or, for tests, `cmd statusbar add-tile`).

## Tests

- `KeyboardTileActionTest` (pure): every `actionFor` branch, the `TileShowSession` state
  machine, the subtitle/description strings in all locales.
- `KeyboardTileServiceTest` (mock): source-level `onClick` routing.
- `KeyboardMinimizerTest`: `lastStyle`.

## Known limitation

A web page's textarea in Chrome loses editor focus when the shade opens (Chrome hides the IME
itself); the deferred show lands and the system hides it again ~0.3 s later. Native editors,
including Chrome's address bar, pass. See `keyboard-minimize.md`.
