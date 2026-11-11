---
title: Quick Settings Tile — Technical Specification
description: KeyboardTileService shows or hides the keyboard; the input-method picker opens only when CleverKeys is not the current keyboard
user_guide: /wiki/getting-started/quick-settings/
status: implemented
version: v2.0.0
---

# Quick Settings Tile

## Overview

`KeyboardTileService` is a `TileService` whose tap shows or hides the keyboard (show/hide since
2026-11-11; from 2026-10-20 it minimized and restored instead). The decision is pure:
`KeyboardTileDecision.actionFor(isCurrentIme, CleverKeysService.tileSnapshot())`. The picker
opens only when CleverKeys is not the current IME or the service is not running; an app cannot
select an IME itself. Engineering history, device logs and the overlay-permission research live
in `docs/specs/keyboard-minimize.md` ("Quick Settings tile" and "Overlay permission check").

## Key Files

| File | Class / member | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/KeyboardTileService.kt` | `KeyboardTileService` | `TileService`; reads state in `onStartListening`, acts in `onClick`, re-reads the tile 300 ms and 1 s after a tap |
| `src/main/kotlin/tribixbite/cleverkeys/KeyboardTileAction.kt` | `KeyboardTileAction` (enum), `KeyboardTileDecision.actionFor`, `TileWindowSnapshot`, `EditorRef`, `TileShowSession` | Pure decision table and the deferred-show state machine |
| `src/main/kotlin/tribixbite/cleverkeys/CleverKeysService.kt` | `tileSnapshot()`, `getInstance()`, `hideFromTile()`, `showFromTile()`, `onWindowShown` / `onWindowHidden` | Hide bookkeeping the tile reads; `requestHideSelf` / `requestShowSelf` |
| `res/values/strings.xml` | `tile_subtitle_*`, `tile_descr_*` | Subtitle (API 29+) and content description per action |
| `AndroidManifest.xml` | `<service>` with `BIND_QUICK_SETTINGS_TILE` | Tile registration |

## Actions

```kotlin
// KeyboardTileAction.kt
enum class KeyboardTileAction {
    /** Another keyboard is selected (or CleverKeys is not running): open the picker. */
    PICK_KEYBOARD,
    /** The keyboard is on screen, or was until the shade opened over it: hide it. */
    HIDE,
    /** The keyboard is hidden: show it, full size (asked for even with no editor focused). */
    SHOW,
}
```

## Decision Table

"Visible" needs both the `onWindowShown` / `onWindowHidden` bookkeeping and
`isInputViewShown()` (on the home screen a Saga reported `mIsInputViewShown=true` with
`mDecorViewVisible=false`). A hide is the user's when Back was pressed while the keyboard
showed, the `hide_keyboard` key was used, or the tile hid it.

| CleverKeys selected? | Window | Tap | Subtitle (API 29+) |
|---|---|---|---|
| no, or service not running | any | input-method picker | Switch keyboard |
| yes | visible, full or minimized | hide (`requestHideSelf`) | Toggle |
| yes | hidden ≤ 10 s ago (`RECENT_HIDE_MS`), not by the user, over an editor, input still in that app, unbound, or SystemUI | stays hidden; the hide now counts as the user's, so the next tap shows | Toggle |
| yes | otherwise | deferred show, full size | Toggle |

## Deferred Show

`TileShowSession` is pure; the service drives it on its main `Handler`:

- the tap arms a request bound to the editor's package; the service calls `requestShowSelf(0)`
  (API 28+; `showSoftInputFromInputMethod(token, 0)` below) at once and then every 250 ms for up
  to 8 s, with or without an editor focused;
- `onWindowShown` stops the loop and reports the requested show once; the service expands to
  the full keyboard;
- cancelled by `onStartInput` for another package, a hardware key (`onKeyDown`), a tile hide,
  the deadline, and `onDestroy`. The first show inside the window counts as the requested one:
  `requestShowSelf` is asynchronous with no token.

## Constraints

- **No editor:** the show is asked for anyway, never the picker; the system decides whether it
  can show over a window with no text field.
- **Shade stays open.** `startActivityAndCollapse` and `showDialog` collapse it but take
  window focus from the app, so its editor loses the keyboard. `StatusBarManager.collapsePanels`
  is hidden API behind `EXPAND_STATUS_BAR` and blocked for apps targeting Android 12+.
- **Lifecycle:** the tile reaches the service through `CleverKeysService.getInstance()`, set in
  `onCreate` and cleared in `onDestroy`; the tile keeps no reference between callbacks.
- **Web editors (known limitation):** a page's textarea in Chrome loses editor focus when the
  shade opens (Chrome hides the IME itself, `ORIGIN_CLIENT_HIDE_SOFT_INPUT`). The deferred show
  lands but the system hides it about 0.3 s later (`HIDE_SAME_WINDOW_FOCUSED_WITHOUT_EDITOR`).
  Such fields need a tap after the shade closes. Native editors, including Chrome's address
  bar, pass.
- **Diagnostics:** verbose builds (`LOCAL_BUILD=true`) log every decision, retry and window
  callback under the tag `CleverKeys/Tile` (`adb logcat -s CleverKeys/Tile`).

## Overlay Permission

Bringing the keyboard back from the tile does **not** need `SYSTEM_ALERT_WINDOW` (checked
2026-10-22 against AOSP 9–16 sources and on a Saga running Android 14). `requestShowSelf`
reaches `InputMethodManagerService.showMySoftInput`, which checks only that the caller is the
current IME and then shows for the current client; the IME window is `TYPE_INPUT_METHOD`,
admitted by the IME token, not by the overlay app-op. The expanded shade is
`TYPE_NOTIFICATION_SHADE` with `FLAG_ALT_FOCUSABLE_IM`: it never takes IME focus, so the app
stays the IME target and a shown keyboard is only covered, not hidden. Only a button that must
stay on screen after the input window is gone would need an overlay (roadmap item, unbuilt).

## AndroidManifest Registration

```xml
<service
    android:name="tribixbite.cleverkeys.KeyboardTileService"
    android:label="@string/app_name"
    android:icon="@mipmap/ic_launcher"
    android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"
    android:exported="true"
    tools:targetApi="24">
  <intent-filter>
    <action android:name="android.service.quicksettings.action.QS_TILE"/>
  </intent-filter>
</service>
```

## Requirements

- Android 7.0 (API 24) or higher (`TileService` introduced in API 24); subtitles on API 29+
- CleverKeys installed; show and hide additionally need it selected as the current IME
- User must add the tile to the Quick Settings panel manually

## Test Coverage

| Suite | File | Scope |
|-------|------|-------|
| Pure JVM | `src/test/kotlin/tribixbite/cleverkeys/KeyboardTileActionTest.kt` | Every decision branch, the `TileShowSession` state machine, subtitle strings in all locales |
| Mock | `src/test/kotlin/tribixbite/cleverkeys/KeyboardTileServiceTest.kt` | Source-level `onClick` routing |

Owed device check: the shade flow (`cmd statusbar expand-settings` → `click-tile` → `collapse`)
for a shown, a minimized, a Back-dismissed and a shade-hidden keyboard, and a tap on the home
screen.

## Related Specifications

- [Command Palette](command-palette-spec.md) — the `hide_keyboard` command, whose hide path the tile shares
