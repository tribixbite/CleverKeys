# Quick Settings Tile

> **Status (2026-11-11):** as built. The tile is a show/hide toggle (maintainer, 2026-11-11:
> "supposed to show kb not open kb picker… if already shown then hide"); from 2026-10-20 until
> then it minimized and restored the keyboard and fell back to the picker with no editor
> (`f47d5f3f`…`be586c84`). The device evidence and the overlay-permission check live in
> [`keyboard-minimize.md`](keyboard-minimize.md) "Quick Settings tile". This page is the short
> engineering summary. The wiki copy at `docs/wiki/specs/customization/quick-settings-tile-spec.md`
> is maintained separately.

## Overview

CleverKeys registers an Android Quick Settings tile. With CleverKeys as the selected IME a tap
hides a keyboard that is on screen (full or minimized), keeps hidden a keyboard the shade just
hid, and shows a hidden one full size. The system input-method picker opens only when another
keyboard is selected (or the service is not running), since an app cannot select an IME
itself. The tile's subtitle (Android 10+) names the action the next tap will take.

## Key files

| File | Symbol | Purpose |
|------|--------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/KeyboardTileService.kt` | `KeyboardTileService` | `TileService`; reads state, dispatches the tap |
| `src/main/kotlin/tribixbite/cleverkeys/KeyboardTileAction.kt` | `KeyboardTileDecision.actionFor(isCurrentIme, window)` | pure decision table (tap → `KeyboardTileAction`) |
| same | `KeyboardTileAction` | `PICK_KEYBOARD`, `HIDE`, `SHOW` |
| same | `TileWindowSnapshot`, `EditorRef` | what the service reports about its window and editor |
| same | `TileShowSession` | pure state machine: last hide (time, editor, whose) and the deferred show (every 250 ms for up to 8 s) |
| `src/main/kotlin/tribixbite/cleverkeys/CleverKeysService.kt` | `tileSnapshot()`, `hideFromTile()`, `showFromTile()`, `logTileDecision(…)` | the service side |
| `AndroidManifest.xml` | `<service android:name="tribixbite.cleverkeys.KeyboardTileService" … BIND_QUICK_SETTINGS_TILE …>` | tile registration (`tools:targetApi="24"`) |

## Decision table

`KeyboardTileService.onClick` computes `isCleverKeysCurrent()` (package substring of
`Settings.Secure.DEFAULT_INPUT_METHOD`, via `tileStateFor`), asks the running service for a
`tileSnapshot()` through `CleverKeysService.getInstance()`, and routes on
`KeyboardTileDecision.actionFor`:

| CleverKeys selected? | window | tap → action | subtitle |
|---|---|---|---|
| no, or service not running | any | `PICK_KEYBOARD`: `InputMethodManager.showInputMethodPicker()`; if no IMM, `startActivityAndCollapse` to `ACTION_INPUT_METHOD_SETTINGS` (PendingIntent overload on API 34+) | Switch keyboard |
| yes | visible, full or minimized | `HIDE` → `hideFromTile()` → `hideKeyboardByUser()` (`requestHideSelf`, marked as the user's) | Hide |
| yes | hidden ≤ 10 s ago (`RECENT_HIDE_MS`), not by the user, over an editor, input still in that app / unbound / SystemUI (`wasShownBeforeTheShade`) | `HIDE` → `hideFromTile()`: stays hidden, and `markHiddenByUser()` makes the next tap a show | Hide |
| yes | otherwise | `SHOW` → `showFromTile()`: deferred show, full size | Show |

Details that matter when reading the code:

- **Deferred show.** `showFromTile` arms a `TileShowSession` request bound to the last editor's
  package and ticks at once and every `RETRY_INTERVAL_MS` (250 ms) for `RETRY_WINDOW_MS` (8 s).
  A tick sends `requestShowSelf(0)` (API 28+; `showSoftInputFromInputMethod` below) only while
  a real editor is bound (`isRealEditor`: a typed field, or the `TYPE_NULL` field the keyboard
  was last shown over); with the launcher's `TYPE_NULL` fallback connection it waits
  (`Tick.WAIT`), so a show never lands over the home screen. `onWindowShown` reports the
  requested show once and the service expands to the full keyboard; `onStartInput` for another
  package, a hardware key, the deadline and `onDestroy` cancel it.
- **Hide.** `hideFromTile` cancels any armed show; it calls `hideKeyboardByUser()` when the
  window is visible (minimized included: the minimizer resets to full on hide), else marks the
  shade's hide as the user's.
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
  machine (including the wait for an editor and the re-attributed shade hide), the
  subtitle/description strings in all locales.
- `KeyboardTileServiceTest` (mock): source-level `onClick` routing.

## Known limitation

A web page's textarea in Chrome loses editor focus when the shade opens (Chrome hides the IME
itself); the deferred show lands and the system hides it again ~0.3 s later. Native editors,
including Chrome's address bar, pass. See `keyboard-minimize.md`.
