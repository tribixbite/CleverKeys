# TalkBack Support for Keyboard2View — Implementation Plan

**Date:** 2026-07-18 · **Target:** audit finding #3 (P1), UI accessibility subscore 1/10 —
the single biggest overall grade lever. **Status:** planned, not yet implemented.
**Base:** grounded in source at HEAD; corrects the `docs/audit/remediation/4-ui-layer.md:141-256`
skeleton in three places (marked ⚠ below).

## Ground truth
- `Keyboard2View` (`Keyboard2View.kt`, ~1829 lines) is a plain `View` (`:66`), inflated from
  `res/layout/keyboard.xml` + programmatically (`customization/KeyboardPreviewHost.kt:112`).
  Overrides neither `dispatchHoverEvent` nor `getAccessibilityNodeProvider`; installs no delegate.
- Touch path: `onTouch` (`:1104`) → `getKeyAtPosition` (`:1155-1207`) → `Pointers.onTouchDown/onTouchUp`
  → `onPointerDown/onPointerUp` (`:446-458`) → `Config.handler.key_down/key_up` (`IKeyEventHandler`,
  `Config.kt:1125`, impl `KeyEventHandler.kt:65`).
- **Latching lives entirely in `Pointers`.** `KeyEventHandler.key_up` is a no-op for
  `Kind.Modifier` (`KeyEventHandler.kt:134`) — so accessibility clicks MUST go through Pointers.
- Three duplicated geometry walks: `onDraw` (`:1391-1429`), `getRowAtPosition`+`getKeyAtPosition`
  (`:1133-1207`), `getRealKeyPositions` (`:1056-1102`); dead strict `getKeyAt` (`:1723-1750`, 0 callers).
- Label sources: `res/values/strings.xml:136-171` (`key_descr_*`, ~36 entries, 25 locales) and
  `ExtraKeysPreference.keyDescription` (name-keyed, `:189-246` — reuse its string ids, not the fn).
- `androidx.customview` (home of `ExploreByTouchHelper`) is transitive today; add explicit dep.

## Architecture
New files: `a11y/KeyboardGeometry.kt` (pure — use a `KeyBounds(l,t,r,b: Float)` data class, NOT
`Rect`, to stay pure-JVM), `a11y/KeyLabels.kt` (pure labeller, resource lookup injected as
`(Int)->String`), `a11y/KeyboardAccessibilityHelper.kt` (`ExploreByTouchHelper` subclass).

Wiring in `Keyboard2View`:
- Construct helper in `init` (after `:147`), install via `ViewCompat.setAccessibilityDelegate`.
- ⚠ **Do NOT override `View.getAccessibilityNodeProvider`** — `ExploreByTouchHelper` supplies it
  through the delegate; `setAccessibilityDelegate` bridges it. Mandatory View overrides are only:
  `dispatchHoverEvent` (→ helper, guard with `isTouchExplorationEnabled`), `dispatchKeyEvent`
  (switch-access; skeleton omitted this), `onFocusChanged`.
- `_a11yHelper.invalidateRoot()` at end of `setKeyboard` (`:369`), in `onMeasure` after `_tc`
  recompute (`:1297`), and in `updateFlags` (`:473` — latched Shift changes every letter's label).
  Cheap no-op when no a11y service is enabled.
- `build.gradle`: add `implementation "androidx.customview:customview:1.1.0"` (don't rely on transitive).
- Virtual IDs: flat row-major index from the single geometry walk; regenerate on `invalidateRoot()`;
  stale-ID lookups return a 1×1 dummy node (avoids the known ExploreByTouchHelper relayout crash).

## Geometry extraction (`KeyboardGeometry`)
`computeKeyRects(keyboard, Params): List<KeyRect>` + `keyAt(keyboard, Params, x, y): Key?`.
`Params(keyWidth, rowHeight, marginTop=_config.marginTop, marginLeft=maxOf(config.margin_left,
insetsLeft))`.
- `keyAt` = verbatim transplant of `getRowAtPosition`+`getKeyAtPosition` incl. all three slop rules
  (`:1180` left-of-row→'a', `:1194` gap→next, `:1202` right→last) and the a/l-row special case;
  `Keyboard2View.getKeyAtPosition` becomes a 3-line wrapper. Callers at `:479`/`:1117` unchanged.
- ⚠ **`computeKeyRects` uses the HIT-TEST cell geometry (full `key.width*keyWidth`, y from
  `marginTop`), NOT the `onDraw` visual-inset cells** — a11y bounds must equal the tappable area.
  (Skeleton said reuse `onDraw`; that would announce bounds smaller than reality.)
- Delete dead `getKeyAt` (`:1723`). Leave `isPointWithinKeyWithTolerance`/`getRealKeyPositions` alone
  (swipe-adjacency tolerance semantics; folding in adds regression surface for no a11y benefit).
- Risk: slop/dynamic-margin drift. Mitigation: verbatim move, no cleanup in same commit; a pure
  dense-grid parity test; existing swipe suites (SwipeLayoutSupport 66, …Instrumented 21,
  TypingSimulation 62) as canaries (swipe tracing runs through `getKeyAtPosition`).

## Labeller (`KeyLabels.describe(kv, getString)`)
Dispatch on `kv.getKind()`. Char→the char (space/enter/tab/nbsp/zwj/combining special-cased);
Keyevent→keycode map (Backspace/arrows/Home/End/…); Event→panel switches (Letters/Numbers/Emoji/
Clipboard/Settings/…); Modifier→Shift/Function/… + dead-key accents; Editing→`key_descr_*`
(Copy/Paste/Cut/COPY_PRIVATE=key_descr_copy_private/…); Slider→cursor-move phrases; String/Macro/
Timestamp→payload; Compose_pending→key_descr_compose.
**Hard rule:** if `kv.hasFlagsAny(FLAG_KEY_FONT)` and no explicit mapping matched, NEVER fall back to
`getString()` — those are PUA glyphs (0xE000-range, e.g. shift 0xE00A) that TTS reads as garbage;
fall back to the Kind+value name. ~12 new `R.string` beside `key_descr_*` (Shift/Function/Backspace/
Enter/arrows/cursor sliders/panels). Pure-JVM testable via injected resolver.

## Node population & ACTION_CLICK
Per KeyRect: `contentDescription = describe(modifyKey(kr.kv, _mods) ?: kr.kv, resolver)` (live
modifier transform so latched Shift announces "A"); bounds from the hit-test rect; className
`Keyboard.Key`, `isClickable`, `addAction(ACTION_CLICK)`; Shift/CapsLock also `isCheckable`/`isChecked`
from latch state. Exclude null/Placeholder keys (no virtual view).
⚠ **ACTION_CLICK routes through Pointers, NOT the handler directly** (skeleton bug: direct
`handler.key_up` no-ops modifiers → Shift could never latch):
```kotlin
private fun activateKeyForAccessibility(kr: KeyRect) {
    val cx = (kr.left+kr.right)/2f; val cy = (kr.top+kr.bottom)/2f
    _pointers.onTouchDown(cx, cy, A11Y_POINTER_ID, kr.key)  // Pointers.kt:647
    _pointers.onTouchUp(A11Y_POINTER_ID)                     // Pointers.kt:160
}
```
`A11Y_POINTER_ID = -2` (must not collide with real ids ≥0 or the `-1` fake-latch pointer at
`Pointers.kt:506`). Gives byte-identical zero-movement-tap behavior: latching, haptics, key_down/up,
modifier application — all free. Then `sendEventForVirtualView(id, TYPE_VIEW_CLICKED)`.

**Zero overhead when TalkBack OFF:** `onTouch` is never touched (helper only sees hover events, which
the platform synthesizes only when `isTouchExplorationEnabled`); wrap `dispatchHoverEvent` with an
explicit `isTouchExplorationEnabled` gate for greppability; `onDraw` untouched; `invalidateRoot()` is
a manager-enabled-guarded event-send (nanoseconds when off).

## Swipe coexistence
- OFF: nothing changes — gesture pipeline is `onTouch`→`Pointers`; helper sees only (nonexistent)
  hover events; shared state is the read-only rect cache. Proof: parity test + swipe suites.
- ON: single-finger drag = explore-by-touch (announces key under finger, same slop as a tap);
  double-tap = ACTION_CLICK → dispatch; swipe-typing still works via the OS two-finger pass-through
  gesture (delivers real ACTION_DOWN/MOVE/UP to `onTouch`, unmodified). No code needed; just don't
  break `onTouch` (we don't).

## Dead AccessibilitySection toggles — REMOVE
`sticky_keys_enabled`/`sticky_keys_timeout_ms`/`voice_guidance_enabled` are written by
`AccessibilitySection.kt:36/49/61` and read ONLY by settings plumbing (SettingsPersistence,
SettingsActivity state, ResetPresets, SettingsDefaults) — no IME/runtime reader (Config/Pointers/
KeyEventHandler: 0 hits). Remove both: sticky-keys is a no-op duplicate of shipped modifier latching;
voice-guidance is redundant once TalkBack support lands. Move the 3 keys SETTINGS_DEFAULTS→
DEPRECATED_KEYS (drift-test enforced). `strings.xml:339` "TalkBack support is always enabled" becomes
true — ship it in the same release only.

## Test strategy
Pure JVM: `KeyboardGeometryTest` (fixed KeyboardData; rect count == non-placeholder keys;
`keyAt(center)===key`; slop parity for left/gap/right; disjoint+row-major; marginLeft shift
consistency) and `KeyLabelsTest` (fake resolver; every default-layout special key non-empty +
non-PUA; shift glyph NOT described as its glyph; modifyKey-shifted 'a'→"A"; payload fallbacks).
ew-cli: `KeyboardAccessibilityInstrumentedTest` (node tree count + non-empty descriptions + bounds;
ACTION_CLICK→recorded key_down/up with `assertEquals` on the concrete KeyValue, shift-twice→latch
toggle — the test that catches the skeleton's direct-handler bug; hover routing on/off; swipe
untouched). Full TalkBack exploration = one-time MANUAL user test (project policy: no local ADB).

## Sequencing (green at every step)
1. Geometry extraction + wrappers + delete dead `getKeyAt` + `KeyboardGeometryTest`. *(Highest risk —
   drift breaks tap+swipe for ALL users; verbatim-move only.)*
2. Labeller + strings + `KeyLabelsTest` (0 runtime callers; ~0 risk).
3. Helper class + `androidx.customview` dep (compiles standalone, unwired; ~0 risk).
4. Wire: init install, 3 dispatch overrides, `invalidateRoot()` sites, `activateKeyForAccessibility`.
   Verify `KeyboardPreviewHost` preview still renders.
5. Settings cleanup + `SettingsDefaultsDriftTest`.
6. Final ew-cli gate incl. new instrumented test; then manual TalkBack pass by user.

**Residual risks:** `dispatchKeyEvent` override must not swallow IME hardware-key events (helper only
consumes d-pad while a virtual view has a11y focus — verify); `A11Y_POINTER_ID=-2` audited against
every `Pointers` `-1` fake-pointer special-case; `invalidateRoot()` in `updateFlags` fires per
modifier change during normal typing — confirmed cheap-when-disabled, keep out of `onDraw`/`onTouch`.
