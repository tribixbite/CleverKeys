---
title: Quick Settings Tile — Technical Specification
description: KeyboardTileService toggles show / minimize / restore; the input-method picker is the fallback when CleverKeys is not current or no editor is bound
user_guide: /wiki/getting-started/quick-settings/
status: implemented
version: v2.0.0
---

# Quick Settings Tile

## Overview

`KeyboardTileService` is a `TileService` whose tap toggles the keyboard instead of always
opening the picker (2026-10-20). The decision is pure:
`KeyboardTileDecision.actionFor(isCurrentIme, CleverKeysService.tileSnapshot())`. The picker
remains the fallback when CleverKeys is not the current IME, the service is not running, or
no real editor is bound (an IME can only show itself for a focused client). Engineering
history, device logs and the overlay-permission research live in
`docs/specs/keyboard-minimize.md` ("Quick Settings tile" and "Overlay permission check").

## Key Files

| File | Class / member | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/KeyboardTileService.kt` | `KeyboardTileService` | `TileService`; reads state in `onStartListening`, acts in `onClick`, re-reads the tile 300 ms and 1 s after a tap |
| `src/main/kotlin/tribixbite/cleverkeys/KeyboardTileAction.kt` | `KeyboardTileAction` (enum), `KeyboardTileDecision.actionFor`, `TileWindowSnapshot`, `VisibleForm`, `EditorRef`, `ShowTarget`, `TileShowSession` | Pure decision table and the deferred-show state machine |
| `src/main/kotlin/tribixbite/cleverkeys/CleverKeysService.kt` | `tileSnapshot()`, `getInstance()`, `onWindowShown` / `onWindowHidden`, `onFinishInputView` | Window-form bookkeeping the tile reads; `requestShowSelf` for the deferred show |
| `src/main/kotlin/tribixbite/cleverkeys/minimize/` | `KeyboardMinimizer.lastStyle`, `minimizeKeyboard(style)`, `expand()` | The minimize / restore the tile triggers |
| `res/values/strings.xml` | `tile_subtitle_*`, `tile_descr_*` | Subtitle (API 29+) and content description per action |
| `AndroidManifest.xml` | `<service>` with `BIND_QUICK_SETTINGS_TILE` | Tile registration |

## Actions

```kotlin
// KeyboardTileAction.kt:58
enum class KeyboardTileAction {
    /** Open the system input-method picker (the tile's original and fallback action). */
    PICK_KEYBOARD,
    /** The full keyboard is on screen: minimize it. */
    MINIMIZE,
    /** The minimized keyboard is on screen: expand it. */
    RESTORE,
    /** The shade hid a full keyboard a moment ago: bring it back minimized ... */
    SHOW_MINIMIZED,
    /** The shade hid a minimized keyboard a moment ago: bring it back full size. */
    SHOW_RESTORED,
    /** The keyboard was dismissed earlier (Back, or long ago) over a bound editor: show it. */
    SHOW,
}
```

## Decision Table

"Visible" needs both the `onWindowShown` / `onWindowHidden` bookkeeping and
`isInputViewShown()` (on the home screen a Saga reported `mIsInputViewShown=true` with
`mDecorViewVisible=false`). A "real editor" is a typed field (`inputType` class ≠
`TYPE_NULL`), or a `TYPE_NULL` field (terminal, remote desktop) only when it is the field the
keyboard was last shown over (same package and `fieldId`), because Android 14 binds a
`TYPE_NULL` fallback connection for windows without an editor. A hide is the user's when Back
was pressed while the keyboard showed, or the `hide_keyboard` key was used.

| CleverKeys selected? | Window | Tap | Subtitle (API 29+) |
|---|---|---|---|
| no, or service not running | any | input-method picker | Switch keyboard |
| yes | visible, full | minimize, to `KeyboardMinimizer.lastStyle` (FAB until a minimize command picks a style) | Minimize |
| yes | visible, minimized | `expand()` | Restore |
| yes | hidden ≤ 10 s ago (`RECENT_HIDE_MS`), not by the user, input still in that app, unbound, or SystemUI; was full | deferred show, landing minimized | Minimize |
| yes | same, was minimized | deferred show, landing full | Restore |
| yes | otherwise, real editor bound | deferred show, landing full | Show |
| yes | otherwise (e.g. home screen fallback connection) | input-method picker | Switch keyboard |

## Deferred Show

`TileShowSession` is pure; the service drives it on its main `Handler`:

- the tap arms a target (full / minimized + style) bound to the editor's package, and the
  service calls `requestShowSelf(0)` (API 28+; `showSoftInputFromInputMethod(token, 0)`
  below) at once and then every 250 ms for up to 8 s;
- `onWindowShown` stops the loop and applies the target once (`minimizeKeyboard(style)` or
  `expand()`), so the user lands in the requested form after the shade collapses;
- cancelled by `onStartInput` for another package, a hardware key (`onKeyDown`), a tile tap
  acting on a visible keyboard, the deadline, and `onDestroy`. The first show inside the
  window counts as the requested one: `requestShowSelf` is asynchronous with no token.

## Constraints

- **No editor:** the picker is kept as the useful fallback; a Toast would sit under the open
  shade. The subtitle names it before the tap.
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
- CleverKeys installed; the toggle actions additionally need it selected as the current IME
- User must add the tile to the Quick Settings panel manually

## Test Coverage

| Suite | File | Scope |
|-------|------|-------|
| Pure JVM | `src/test/kotlin/tribixbite/cleverkeys/KeyboardTileActionTest.kt` | Every decision branch, the `TileShowSession` state machine, subtitle strings in all locales |
| Mock | `src/test/kotlin/tribixbite/cleverkeys/KeyboardTileServiceTest.kt` | Source-level `onClick` routing |
| Pure JVM | `KeyboardMinimizerTest` | `lastStyle` |

Owed device check: the shade flow (`cmd statusbar expand-settings` → `click-tile` → `collapse`)
for full, minimized and Back-dismissed keyboards.

## Related Specifications

- [Command Palette](command-palette-spec.md) — the minimize and hide commands whose style the tile reuses
