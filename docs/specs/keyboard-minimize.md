# Minimize the keyboard (bar / floating button)

**Status:** implemented 2026-10-01; unit-tested; Seeker device checks partial (2026-10-05). **Issue:** gh #175 part 1
("hide and summon the keyboard … an optional floating button").

## Behaviour

Two catalogue commands (Events category), assignable to any short swipe, popover slot or layout key:

| command | key value | effect |
|---|---|---|
| `minimize_bar` | `Event.MINIMIZE_BAR` | The input view becomes a 30 dp full-width strip with an up chevron, in the keyboard background colour. The app is resized to sit above it. |
| `minimize_fab` | `Event.MINIMIZE_FAB` | The input view becomes a 52 dp round button with an up chevron at the end side (right in LTR, left in RTL), 12 dp from the edges. The app gets the whole screen. Touches anywhere except the button pass through to the app. |
| `hide_keyboard` | `Event.HIDE_KEYBOARD` | gh #175's explicit dismiss ("⌄"): hides the keyboard (`requestHideSelf(0)`). Not a minimized state; the next show is the full keyboard. |

- Tapping the bar or button restores the full keyboard (haptic tick).
- Minimizing lasts while the keyboard stays shown. When the keyboard is hidden (`onFinishInputView`), the full view is put back in the input window at once, so its next appearance is full size. (Until 2026-10-08 `reset()` only cleared the style: the bar or button stayed the live view with nothing to expand, and a tap on it did nothing until the next `onStartInputView`.)
- Both pad themselves for the navigation bar under edge-to-edge.
- The talkback label is "Show keyboard" (`short_swipe_show_keyboard`).
- Out of scope for the minimized button: it lives in the input window, so it is gone once the keyboard is hidden. The Quick Settings tile below brings a hidden keyboard back with `requestShowSelf`, which needs no extra permission (checked 2026-10-22, "Overlay permission check"). Only a button that stays on screen after the input window is hidden needs an overlay (`SYSTEM_ALERT_WINDOW`; roadmap 4.3).

## Quick Settings tile (2026-10-20)

`KeyboardTileService` toggles the keyboard instead of always opening the picker. The decision
is the pure `KeyboardTileDecision.actionFor(isCurrentIme, CleverKeysService.tileSnapshot())`.

**Some apps hide the keyboard when the shade opens.** On the Saga (Android 14) with Chrome,
expanding the shade hid the IME (`mInputShown=false`, `mWindowVisible=false`) and it did not come
back after the shade collapsed (device report, 2026-10-20). The 2026-10-22 check found that hide
was Chrome's own (`ORIGIN_CLIENT_HIDE_SOFT_INPUT` from `com.android.chrome`): the shade itself
does not take IME focus, did not hide the keyboard over CleverKeys' Settings field, and
`requestShowSelf` sent under the open shade showed the keyboard in 7 ms ("Overlay permission
check" below). The retry loop covers apps that hide the keyboard themselves. The service remembers the form the
window had when it was last hidden (`_formAtFinishView`, captured in `onFinishInputView` before
the minimizer reset, recorded in `onWindowHidden`) and the hide time, and a tap acts on that
form when the hide is recent.

"Visible" needs both `onWindowShown`/`onWindowHidden` and `isInputViewShown()`: on the home
screen the Saga reported `mIsInputViewShown=true` with `mDecorViewVisible=false`. A "real
editor" is a typed field (`inputType` class ≠ `TYPE_NULL`), or a `TYPE_NULL` field (terminal,
remote desktop) only when it is the field the keyboard was last shown over (same package and
`fieldId`): Android 14 binds a `TYPE_NULL` fallback connection for windows without an editor,
such as the launcher, and the EditorInfo cannot tell the two apart. A hide is the user's when
Back was pressed while the keyboard showed, or the `hide_keyboard` key was used.

| CleverKeys selected? | window | tap | subtitle (API 29+) |
|---|---|---|---|
| no, or service not running | any | input-method picker | Switch keyboard |
| yes | visible, full | minimize, to `KeyboardMinimizer.lastStyle` (FAB until a minimize command picks a style) | Minimize |
| yes | visible, minimized | `expand()` | Restore |
| yes | hidden ≤ 10 s ago (`RECENT_HIDE_MS`), not by the user, input still in that app, unbound, or SystemUI; was full | deferred show, landing minimized | Minimize |
| yes | same, was minimized | deferred show, landing full | Restore |
| yes | otherwise, real editor bound | deferred show, landing full | Show |
| yes | otherwise (e.g. home screen fallback connection) | input-method picker | Switch keyboard |

**Deferred show** (`TileShowSession`, pure; driven by the service on its main `Handler`):
- the tap arms a target (full / minimized + style) bound to the editor's package, and the
  service calls `requestShowSelf(0)` (API 28+; `showSoftInputFromInputMethod(token, 0)` below)
  at once and then every 250 ms for up to 8 s;
- `onWindowShown` stops the loop and applies the target once (`minimizeKeyboard(style)` or
  `expand()`), so the user lands in the requested form after the shade collapses;
- cancelled by `onStartInput` for another package, a hardware key (`onKeyDown`), a tile tap
  acting on a visible keyboard, the deadline, and `onDestroy` (which also removes the callback).
  The first show inside the window counts as the requested one: `requestShowSelf` is
  asynchronous with no token, so a show the user triggers by tapping the same app's field in
  those seconds lands in the form the tile asked for.

- **No editor:** an IME can only show itself for a focused client. The picker is kept as the
  useful fallback; a Toast would sit under the open shade. The subtitle names it before the tap.
- **Shade stays open.** `startActivityAndCollapse` and `showDialog` collapse it but take window
  focus from the app, so its editor loses the keyboard. `StatusBarManager.collapsePanels` is
  hidden API with `EXPAND_STATUS_BAR` and is blocked for apps targeting Android 12+. The tile
  re-reads its state 300 ms and 1 s after a tap.
- **Lifecycle:** the tile reaches the service through `CleverKeysService.getInstance()`, set in
  `onCreate` and cleared in `onDestroy`; the tile keeps no reference between callbacks.
- **Web editors (known limitation):** a page's textarea in Chrome loses editor focus when the
  shade opens (Chrome hides the IME itself, `ORIGIN_CLIENT_HIDE_SOFT_INPUT`). The deferred show
  lands (`SHOW_SOFT_INPUT_FROM_IME`, onShown) but the system hides it about 0.3 s later with
  `HIDE_SAME_WINDOW_FOCUSED_WITHOUT_EDITOR`. That is outside the IME's control: such fields
  need a tap after the shade closes. Native editors, including Chrome's address bar, pass.
- **Diagnostics:** verbose builds (`LOCAL_BUILD=true`) log every tile decision with all its
  inputs, each retry and its outcome, and the window callbacks under the tag `CleverKeys/Tile`
  (`adb logcat -s CleverKeys/Tile`).
- Tests: `KeyboardTileActionTest` (pure: every decision branch, the session state machine,
  strings in all locales), `KeyboardTileServiceTest` (mock, source-level `onClick` routing),
  `KeyboardMinimizerTest` (`lastStyle`).
- Device check owed: the shade flow (`cmd statusbar expand-settings` → `click-tile` →
  `collapse`) for full, minimized and Back-dismissed keyboards.

### Overlay permission check (2026-10-22)

Question (`memory/todo.md` item 15): does bringing the keyboard back from the tile after the
system hid it need `SYSTEM_ALERT_WINDOW`? **No.** Research only; no code changed. Sources: the
LineageOS mirrors of AOSP (`lineage-16.0` = Android 9, `18.1` = 11, `21.0` = 14, `22.2` = 15,
`23.0` = 16), the 2026-10-20 Saga logs (`build/oct20f-saga/tile.log`, `build/oct20e-saga/log*.txt`)
and a Saga (Android 14) run on 2026-10-22 (CleverKeys 2.0.0 verbose build, tile added with
`cmd statusbar add-tile` and removed afterwards, `sysui_qs_tiles` byte-identical before and after).

- **Mechanism (source).** `InputMethodService.requestShowSelf` → `mPrivOps.showMySoftInput`
  (API 28+; below 28 `InputMethodManager.showSoftInputFromInputMethod(token, flags)` reaches the
  same binder call and is deprecated in favour of the IMS method). IMMS `showMySoftInput` checks
  only `calledWithValidTokenLocked` (the caller is the current IME) and calls
  `showCurrentInputLocked`: Android 9 takes no window and shows for the current client; 14 and 15
  pass `mLastImeTargetWindow`, the window input was last started for (`reportStartInput`); 15 also
  requires `mCurClient.mCurSession` when `deferShowSoftInputUntilSessionCreation` is on. Then
  `DefaultImeVisibilityApplier.performShowIme` → IME `showWindow` → `applyImeVisibilityAsync` → WMS
  `showImePostLayout(window)` → `ImeInsetsSourceProvider.scheduleShowImePostLayout`, which runs
  once the requesting window is the IME layering, input or control target. `SYSTEM_ALERT_WINDOW`
  appears only in `PhoneWindowManager.checkAddPermission` for alert-window types;
  `TYPE_INPUT_METHOD` is in its "window manager will check these" list and needs the IME token
  (`WindowManagerService.addWindow`), not the overlay op. Android 16 keeps `requestShowSelf`; with
  `refactor_insets_controller` on, `showMySoftInputLocked` goes through `mCurClient` and the focused
  window's client (`setImeVisibilityOnFocusedWindowClient`) instead, so a bound client is still the
  only condition. The flag's value is not readable from the mirror.
- **What the shade does (device + source).** The expanded shade is `TYPE_NOTIFICATION_SHADE` with
  `FLAG_ALT_FOCUSABLE_IM` (Saga `dumpsys window windows`; SystemUI `applyFocusableFlag` on 14 and
  15). `mayUseInputMethod` is false for it, so `ImeFocusController` never reports its focus to IMMS:
  with the shade open IMMS still listed the app as `mCurFocusedWindow`/`mCurClient`, and WM's IME
  layering, input and control targets stayed the app window. The shade is layer 17 and the IME
  layer 13 (`WindowManagerPolicy.getWindowLayerFromTypeLw`), so a shown IME is only covered. Over
  CleverKeys' own Settings search field, `expand-notifications` did NOT hide the keyboard
  (`mInputShown=true` throughout, no ImeTracker hide between 04:55:18 and 04:55:25); `click-tile`
  with the shade open took the MINIMIZE branch at once; after `collapse` the minimized keyboard
  was on screen. The 2026-10-20 "the shade hides the keyboard" came from Chrome: the only hide in
  those logs at shade time is `ORIGIN_CLIENT_HIDE_SOFT_INPUT reason HIDE_SOFT_INPUT` from
  `com.android.chrome`, and in `tile.log` the tap under the open shade on a Back-dismissed Chrome
  keyboard logged `requestShowSelf sent` at 16:32:50.364 and `onWindowShown` at .371 (7 ms,
  attempt 1). The system does not ignore `requestShowSelf` while the shade is open; the retry loop
  only covers apps that hide the keyboard themselves on focus loss.
- **Why the keyboard does not return by itself after such a hide.** `computeState` restores only
  when `WindowManagerService.shouldRestoreImeVisibility` agrees (`ActivityRecord.mLastImeShown`,
  recorded when the activity becomes invisible or relaunches, or a task snapshot with an IME
  surface); the shade causes neither, and an app-initiated hide is simply a hide.
- **Alternatives, and why they are not needed.** `TileService.startActivityAndCollapse` (Intent
  below 34; `PendingIntent` from 34, the Intent form throws `UnsupportedOperationException`) and
  `showDialog` move focus away from the editor. A transparent trampoline activity becomes the IME
  target with no editor (`HIDE_UNSPECIFIED_WINDOW`) unless it sets `FLAG_ALT_FOCUSABLE_IM`, and
  then it changes nothing the shade has not already left in place; reasoned from source, not run.
  An overlay window (`TYPE_APPLICATION_OVERLAY`, layer 11) sits below both the IME and the shade
  and cannot request a show for another app: only the IME (its token) or the served view's own
  process can (`InputMethodManager.showSoftInput` requires `hasServedByInputMethodLocked(view)`).
- **Other keyboards.** Hacker's Keyboard's "show keyboard" notification calls
  `imm.showSoftInputFromInputMethod(mIME.mToken, SHOW_FORCED)` and its manifest requests no overlay
  permission (klausw/hackerskeyboard, master). Gboard on the Saga requests no
  `SYSTEM_ALERT_WINDOW` (`dumpsys package`); its floating mode is the IME window with a touchable
  region, the `TOUCHABLE_INSETS_REGION` mechanism our FAB uses, and LeanType documents the same
  ("zero `SYSTEM_ALERT_WINDOW` permission overhead"). Apps that do need "Display over other apps"
  are non-IME helpers drawing their own button (e.g. the "No Keyboard: Hideable keyboard" popup
  remote, Play listing), which is the roadmap 4.3 floating button, not the tile.
- **Follow-ups.** Done the same day: the `TileShowSession` KDoc and the "Out of scope" and
  shade paragraphs above now say Chrome hides its own keyboard and only a button that outlives
  the IME window needs the overlay.

## Architecture

- `minimize/KeyboardMinimizer<V>` (pure, generic over the view type):
  - `resolve(requested)` gets every view the service asks to show; it remembers that view as the full one and returns the minimized view while minimized;
  - `minimize(style, prepare)` and `expand()`;
  - `reset()`, called on hide: `expand()` (shows the full view when minimized, no-op otherwise).
- `CleverKeysService.setInputView` routes through `resolve`, so `onStartInputView` re-showing the prediction container, or a theme change re-inflating the key view, cannot un-minimize behind the user's back; the newest full view is what `expand()` restores.
- `minimize/MinimizedKeyboardView`: drawn directly from `Theme` colours, with no per-frame allocation. `touchableArea()` reports the bar's or the button's bounds in window coordinates.
- `FabSide.isRtl(context)` gathers the three sources; the decision itself is `FabSidePolicy.isRtl` (pure, lazy sources).
- `CleverKeysService.onComputeInsets` applies `MinimizedInsets.plan(style, attached, windowHeight)` (pure), FAB only:
  - `contentTopInsets` and `visibleTopInsets` are set to the window height (the app is not resized);
  - `touchableInsets` is `TOUCHABLE_INSETS_REGION` with the button's rectangle.
- Events reach `KeyboardReceiver.handle_event_key`, which calls `CleverKeysService.minimizeKeyboard(style)`. Custom mappings reach it through `Keyboard2View.onCustomShortSwipe`'s Event branch.

## Tests

- `minimize/KeyboardMinimizerTest` (pure):
  - minimize then expand;
  - views shown while minimized are remembered;
  - reset on hide;
  - the view is created once and restyled;
  - minimize with nothing to come back to does nothing;
  - the catalogue entries resolve to the events;
  - a reset while minimized shows the full view again;
  - `FabSidePolicy`: any of the three sources RTL puts the button left, read lazily in order;
  - `MinimizedInsets`: only an attached FAB changes the insets.
- `CommandRoutingTest` covers that both commands have an execution path.
- Device check owed:
  - assign `minimize_fab` to a slot in Chrome;
  - the app scrolls and taps around the button;
  - the button expands the keyboard;
  - the bar resizes the app;
  - RTL places the button on the left.

### Seeker evidence (October 3–5)

FAB minimize/expand and bar resize/expand pass. Typing resumes after bar expansion,
and hiding/reopening restores full size. Evidence screenshots are under ignored
`build/oct5-bar-*.jpg`. A temporary mapping was used; see `memory/todo.md` for cleanup.
October 5 follow-up on `5f07936e`: portrait and landscape FAB minimize/expand pass.
A landscape swipe starting inside the transparent IME strip scrolls launcher content
while the FAB remains visible (`build/oct5-fab-landscape-scroll-down.jpg`). Portrait
launcher had no available scroll range, so its unchanged scroll attempt proves nothing.
Temporary Hebrew app locale did not change the IME to RTL; restored locale/rotation.
TODO: RTL placement and cross-app pass-through checks. Final reconnection cleanup
removed the temporary t/South FAB mapping, verified original two mappings, cleared
scratch UI XML and restored original launcher/expanded Quick Settings. These results
do not claim a complete cross-app release pass.

### Native regression coverage (October 6)

`Keyboard2ViewCustomMappingRenderTest` exercises the actual minimized view: FAB bounds
in both LTR and RTL, rejection of outside touches, cancel without expansion, one
expansion on a valid tap, and bar height including the navigation inset. Native RTL
view coverage does not establish service-window RTL placement or cross-app touch
pass-through; those device checks above remain owed. Full cloud results are in
[testing strategy](testing-strategy.md).

### RTL side (2026-10-07)

The button goes left when the system locale, CleverKeys' per-app locale (Android 13+) or the
IME service's configuration is right-to-left (`FabSide.isRtl`). On the Saga, neither the view's
resolved direction nor the service configuration reflected an Arabic system locale (no Arabic
translation, so Android resolves the app to English) or a Persian per-app locale. Device
re-check owed for both cases.

