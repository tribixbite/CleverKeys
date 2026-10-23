# Quick Settings Tile and Minimize Skill

Use this skill for the Quick Settings tile, "show / summon the keyboard", minimize to a bar or
floating button (FAB), `hide_keyboard`, the overlay permission question (`SYSTEM_ALERT_WINDOW`),
gh #175 part 1 and roadmap 4.3. As built at HEAD, 2026-10-23. The full behaviour, device
evidence and the overlay research are in `docs/specs/keyboard-minimize.md`; this is the
working summary. (`docs/specs/quick-settings-tile.md` is a short summary rewritten 2026-10-23;
before that it still said "the tile opens the input-method picker".)

## Minimize (gh #175 part 1; `01b6212d`, `17db49da`)

- Commands (Events category, assignable to any short swipe, popover slot or layout key):
  `minimize_bar` → `Event.MINIMIZE_BAR`, `minimize_fab` → `Event.MINIMIZE_FAB`,
  `hide_keyboard` → `Event.HIDE_KEYBOARD`. `KeyboardReceiver.handle_event_key` routes the first
  two to `CleverKeysService.minimizeKeyboard(style)` and the third to `hideKeyboardByUser()`
  (= `noteUserDismiss()` + `requestHideSelf(0)`; a hide, not a minimized state).
- `minimize/KeyboardMinimizer<V>` (pure, generic over the view type): `resolve(requested)` wraps
  EVERY `setInputView`, remembers the newest full view and returns the minimized view while
  minimized; `minimize(style, prepare)` is ignored until a full view has been shown; `expand()`;
  `reset()` = `expand()`, called from `onFinishInputView`. `lastStyle` (FAB before any minimize)
  is what the tile minimizes to.
- `minimize/MinimizedKeyboardView`: BAR = 30 dp full-width strip (the app is resized above it);
  FAB = 52 dp round button at the end side, 12 dp from the edges (the app gets the whole screen,
  touches outside the button pass through). Drawn straight from `Theme` colours, nothing
  allocated per frame; pads itself for the navigation bar; `touchableArea()` in window
  coordinates; TalkBack label `short_swipe_show_keyboard`.
- Insets: `MinimizedInsets.plan(style, attached, windowHeight)` (pure) — FAB only:
  `contentTopInsets` = `visibleTopInsets` = window height and `touchableInsets =
  TOUCHABLE_INSETS_REGION` with the button's rectangle; BAR and the full keyboard keep the
  platform defaults. Applied in `CleverKeysService.onComputeInsets`.
- Side: `FabSide.isRtl(context)` → `FabSidePolicy.isRtl` (pure): left when the IME service's
  configuration is RTL, OR the system locale is (`Resources.getSystem()`), OR CleverKeys' per-app
  locale is (API 33+), read lazily in that order. Three sources because neither the view's
  resolved direction nor the service configuration followed an Arabic system locale (no `ar`
  translation) or a Persian per-app locale on the Saga (2026-10-07).

## Quick Settings tile (`f47d5f3f`, `4f70eb7e`, 2026-10-20)

- `KeyboardTileService` (manifest: `BIND_QUICK_SETTINGS_TILE`, exported, API 24+; the subtitle
  needs API 29). A tap computes `KeyboardTileDecision.actionFor(isCurrentIme,
  service?.tileSnapshot())` (pure) → `KeyboardTileAction`:

  | CleverKeys selected? | window | action | subtitle |
  |---|---|---|---|
  | no, or service not running | any | `PICK_KEYBOARD` (`showInputMethodPicker`) | Switch keyboard |
  | yes | visible, full | `MINIMIZE` → `minimizeFromTile()` (to `lastStyle`) | Minimize |
  | yes | visible, minimized | `RESTORE` → `restoreFromTile()` | Restore |
  | yes | hidden ≤ 10 s (`RECENT_HIDE_MS`), not by the user, same app / unbound / SystemUI, was full | `SHOW_MINIMIZED` (deferred show, lands minimized) | Minimize |
  | yes | same, was minimized | `SHOW_RESTORED` (deferred show, lands full) | Restore |
  | yes | otherwise, a real editor is bound | `SHOW` (deferred show, full) | Show |
  | yes | otherwise (e.g. launcher fallback connection) | `PICK_KEYBOARD` | Switch keyboard |

- The tile keeps NO reference to the service between callbacks: it calls
  `CleverKeysService.getInstance()` (set in `onCreate`, cleared in `onDestroy`) each time.
  `tileStateFor` is a package-substring test on `DEFAULT_INPUT_METHOD` (weaker than
  `IMEStatusHelper.isDefaultIME`, on purpose). The tile re-reads itself 300 ms and 1 s after a
  tap and after `onStartListening` (`TILE_REFRESH_DELAYS_MS`).
- **Visible** = `onWindowShown`/`onWindowHidden` flag AND `isInputViewShown()`
  (`isTileWindowVisible`): on the Saga's home screen the platform reported
  `mIsInputViewShown=true` with `mDecorViewVisible=false`.
- **Which form was hidden**: `hiddenForm()` = the live minimized style, else `_formAtFinishView`
  (captured in `onFinishInputView` BEFORE the minimizer's `reset()`, because `hideWindow` runs
  `onFinishInputView` first), else Full.
- **Real editor** (`EditorRef(packageName, fieldId, typeNull)`): any typed field, or a
  `TYPE_NULL` field (terminal, remote desktop) only when it is the field the keyboard was last
  shown over — Android 14 binds a `TYPE_NULL` fallback connection for windows without an editor
  (the launcher) and the EditorInfo cannot tell them apart. A hide is the user's when Back was
  pressed while the keyboard showed (`onKeyDown`) or `hide_keyboard` ran.
- **Deferred show** (`TileShowSession`, pure, driven on the service's main `Handler`):
  `request(target, packageName, now)` arms a one-shot target; `requestShowFromTile()` runs at once
  and then every `RETRY_INTERVAL_MS` = 250 ms for `RETRY_WINDOW_MS` = 8 s (`requestShowSelf(0)` on
  API 28+, `imm.showSoftInputFromInputMethod(token, 0)` below); `onWindowShown()` returns the
  target to apply once (`minimizeKeyboard(style)` or `expand()`). Cancelled by `onStartInput` for
  another package, any hardware key (`onUserInput`), a tap acting on a visible keyboard,
  `onDestroy`, and the deadline. The first show inside the window counts as the requested one
  (`requestShowSelf` carries no token).
- **The shade stays open** after a keyboard action: `startActivityAndCollapse` and `showDialog`
  take window focus from the app (its editor loses the keyboard), `StatusBarManager.collapsePanels`
  is hidden API behind `EXPAND_STATUS_BAR` and blocked for targetSdk 31+. The picker fallback
  (`ACTION_INPUT_METHOD_SETTINGS`) must use the `PendingIntent` overload on API 34+ — the
  `Intent` overload throws `UnsupportedOperationException` there.
- **Known limitation**: a web textarea in Chrome loses editor focus when the shade opens
  (Chrome hides its own IME, `ORIGIN_CLIENT_HIDE_SOFT_INPUT`); the deferred show lands and the
  system hides it ~0.3 s later (`HIDE_SAME_WINDOW_FOCUSED_WITHOUT_EDITOR`). Native editors,
  including Chrome's address bar, pass. Such fields need a tap after the shade closes.
- **Diagnostics**: verbose builds (`LOCAL_BUILD=true`) log every decision with its inputs, each
  retry and the window callbacks under `CleverKeys/Tile` (`adb logcat -s CleverKeys/Tile`).
  Device recipe: `cmd statusbar add-tile <component>` → `expand-settings` → `click-tile` →
  `collapse`; remove the tile afterwards (`sysui_qs_tiles` must be byte-identical — leave no trace).

## No overlay permission is needed to bring the keyboard back (2026-10-22, `be586c84`)

Question answered in `docs/specs/keyboard-minimize.md` "Overlay permission check" (AOSP source
via the LineageOS mirrors, Saga logs, a 2026-10-22 Saga run). The facts to carry:

- `requestShowSelf` → IMMS `showMySoftInput` checks only that the caller is the current IME
  (its token) and shows for the current client; `SYSTEM_ALERT_WINDOW` is checked only for
  alert-window types, never for `TYPE_INPUT_METHOD`.
- The expanded shade (`TYPE_NOTIFICATION_SHADE`, `FLAG_ALT_FOCUSABLE_IM`) never takes IME
  focus; the app stays `mCurClient`. The 2026-10-20 "the shade hides the keyboard" was Chrome's
  own hide; `requestShowSelf` sent under the open shade showed the keyboard in 7 ms. The retry
  loop exists for apps that hide their own keyboard, not for the shade.
- An overlay window (`TYPE_APPLICATION_OVERLAY`) sits BELOW the IME and the shade and cannot
  request a show for another app anyway. Hacker's Keyboard and Gboard request no overlay.
- Only something that stays on screen after the IME window is gone needs the permission: the
  roadmap **4.3 floating quick-action button** (`7e010989`; maintainer request 2026-10-22,
  docketed for a release after 2.0.0, not started). Its rule: OFF by default, ask for "Display
  over other apps" only when the user turns it on, no other feature may depend on it, and the
  setting turns itself off (and says why) if the permission is revoked.
- Therefore: do NOT add `SYSTEM_ALERT_WINDOW` to the manifest for the tile or the minimize
  button, and do not describe summon-after-hide as "needs the overlay permission" (older rows in
  `docs/audit/gh-issue-resolution.md` #175 and todo item 15 said so before this check).

## Traps

- `KeyboardTileService`'s lifecycle callbacks all begin with a `super` call into an
  unimplementable android.jar stub: keep every decision in `KeyboardTileDecision` /
  `TileShowSession` (pure) and test the service at source level (`KeyboardTileServiceTest`).
- `reset()` must put the FULL view back, not just clear the style — until 2026-10-08 the bar or
  button stayed the live view with nothing to expand, and a tap on it did nothing.
- A theme change re-inflates the key view while minimized: only `resolve()`'s remembered newest
  full view makes `expand()` restore the right one (`content-pane-layout.md`, `theme-system.md`).
- `tile.subtitle` is API 29+; guard it.
- Bar height includes the navigation inset under edge-to-edge
  (`Keyboard2ViewCustomMappingRenderTest`).
- The `_formAtFinishView` capture is cleared by `onStartInputView`, so a field switch cannot reuse
  an older capture.

## File map

| File | Role |
|------|------|
| `KeyboardTileService.kt` | the `TileService`; picker fallback; refresh timing; `tileStateFor` |
| `KeyboardTileAction.kt` | `VisibleForm`, `EditorRef`, `TileWindowSnapshot`, `KeyboardTileAction`, `ShowTarget`, `KeyboardTileDecision`, `TileShowSession` (all pure) |
| `CleverKeysService.kt` (~876–1085) | `minimizeKeyboard`, `_tileShow`, `tileSnapshot`, `minimizeFromTile` / `restoreFromTile` / `showFromTile`, `requestShowFromTile`, `hideKeyboardByUser`, `onWindowShown` / `onWindowHidden` / `onKeyDown`, `logTileDecision`, `TILE_TAG` |
| `minimize/KeyboardMinimizer.kt`, `minimize/MinimizedKeyboardView.kt` (+ `FabSide`, `FabSidePolicy`, `MinimizedInsets`, `MinimizedStyle`) | the minimized state machine and view |
| `KeyboardReceiver.kt` (~560) | `MINIMIZE_BAR` / `MINIMIZE_FAB` / `HIDE_KEYBOARD` events |
| `AndroidManifest.xml` | the tile `<service>` |
| `res/values/strings.xml` `tile_subtitle_*`, `tile_descr_*`, `short_swipe_show_keyboard` | tile and view strings (all 21 locales) |
| `docs/specs/keyboard-minimize.md` | the spec, device evidence and the overlay research |
| `docs/plans/2026-10-05-recommended-features-and-gaps.md` §4.3 | the floating button roadmap entry |

## Tests

- Pure: `KeyboardTileActionTest` (20: every decision branch, the session state machine, strings
  in all locales), `minimize/KeyboardMinimizerTest` (13: minimize/expand, remembered views,
  reset on hide, `lastStyle`, `FabSidePolicy`, `MinimizedInsets`), `CommandRoutingTest` (both
  commands have an execution path).
- Mock: `KeyboardTileServiceTest` (5, source-level `onClick` routing).
- Instrumented: `Keyboard2ViewCustomMappingRenderTest` (FAB bounds in LTR and RTL, outside
  touches rejected, one expansion per valid tap, bar height with the navigation inset).
- Device: Seeker bar/FAB minimize, expand and landscape pass-through PASS (2026-10-03..05); Saga
  tile in a native field and the home-screen picker PASS (2026-10-20); RTL FAB left for system
  `ar-XB` and per-app `fa` PASS (`c6fe3594`, 2026-10-07). Still owed: cross-app pass-through, the
  shade flow for full / minimized / Back-dismissed keyboards, the web-editor limitation on
  another browser.
