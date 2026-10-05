# Minimize the keyboard (bar / floating button)

**Status:** implemented 2026-10-01; unit-tested; Seeker device checks partial (2026-10-05). **Issue:** gh #175 part 1
("hide and summon the keyboard … an optional floating button").

## Behaviour

Two catalogue commands (Events category), assignable to any short swipe, popover slot or layout key:

| command | key value | effect |
|---|---|---|
| `minimize_bar` | `Event.MINIMIZE_BAR` | The input view becomes a 30 dp full-width strip with an up chevron, in the keyboard background colour. The app is resized to sit above it. |
| `minimize_fab` | `Event.MINIMIZE_FAB` | The input view becomes a 52 dp round button with an up chevron at the end side (right in LTR, left in RTL), 12 dp from the edges. The app gets the whole screen. Touches anywhere except the button pass through to the app. |

- Tapping the bar or button restores the full keyboard (haptic tick).
- Minimizing lasts while the keyboard stays shown. When the keyboard is hidden (`onFinishInputView`), its next appearance is full size.
- Both pad themselves for the navigation bar under edge-to-edge.
- The talkback label is "Show keyboard" (`short_swipe_show_keyboard`).
- Out of scope: summoning a keyboard the system has already hidden. That needs an overlay outside the input window (`SYSTEM_ALERT_WINDOW`); this feature keeps the input window and only shrinks it.

## Architecture

- `minimize/KeyboardMinimizer<V>` (pure, generic over the view type):
  - `resolve(requested)` gets every view the service asks to show; it remembers that view as the full one and returns the minimized view while minimized;
  - `minimize(style, prepare)` and `expand()`;
  - `reset()`, called on hide.
- `CleverKeysService.setInputView` routes through `resolve`, so `onStartInputView` re-showing the prediction container, or a theme change re-inflating the key view, cannot un-minimize behind the user's back; the newest full view is what `expand()` restores.
- `minimize/MinimizedKeyboardView`: drawn directly from `Theme` colours, with no per-frame allocation. `touchableArea()` reports the bar's or the button's bounds in window coordinates.
- `CleverKeysService.onComputeInsets`, FAB only:
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
  - the catalogue entries resolve to the events.
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
TODO: RTL placement and cross-app pass-through checks. Device dropped during cleanup;
remove only the temporary t/South FAB mapping after reconnection. These results do
not claim a complete cross-app release pass.
