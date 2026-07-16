# UI Layer — Verification & Remediation

Adversarial re-verification of the prior UI audit. Every claim below was re-checked
against current source on branch `main`. Paths are under
`src/main/kotlin/tribixbite/cleverkeys/` unless noted. Note: the package is
`tribixbite.cleverkeys`, **not** `tribixbite.keyboard2` as the prior audit wrote.

## Verification Results

| # | Finding | Verdict | Evidence (file:line) |
|---|---------|---------|----------------------|
| 1 | Keyboard invisible to TalkBack — zero a11y in custom-drawn view | **CONFIRMED** | `Keyboard2View.kt` is `class Keyboard2View … : View(context, attrs), View.OnTouchListener, Pointers.IPointerEventHandler` (`Keyboard2View.kt:66-69`), 1790 lines. `rg` for `AccessibilityNodeProvider\|ExploreByTouchHelper\|announceForAccessibility\|sendAccessibilityEvent\|AccessibilityEvent\|AccessibilityNodeInfo\|getAccessibilityNodeProvider\|dispatchHoverEvent\|onInitializeAccessibility\|importantForAccessibility\|contentDescription` → **NONE** in `Keyboard2View.kt`. Same query → **NONE** in `CleverKeysService.kt`. `rg -l 'ExploreByTouchHelper\|AccessibilityNodeProvider\|announceForAccessibility\|onPopulateNodeForVirtualView' src/main/kotlin` → **NONE ANYWHERE**. There is no base view; `Keyboard2View` extends `android.view.View` directly, so it inherits the default no-op a11y node (one flat node for the whole keyboard). No virtual view tree, no per-key or per-row a11y path exists. |
| 2 | 143 `mutableStateOf` fields on SettingsActivity; extension-fn composables; only 2 `@Preview`; ~37 fields in SettingsViewModel | **CONFIRMED** (counts exact; one nuance) | `rg -c 'by mutableStateOf' SettingsActivity.kt` = **143**, all `internal var … by mutableStateOf(...)` class fields starting at `SettingsActivity.kt:223` (`beamWidth`, `maxLength`, …). `remember { mutableStateOf` count = **0** (none are local). File is 754 lines. Composable is an **extension function**: `internal fun SettingsActivity.SettingsScreen()` at `ui/settings/SettingsScreen.kt:67-68`. `rg '@Preview' -g '*.kt' src/main/kotlin` = **2**, both in `theme/KeyboardTheme.kt`. `SettingsViewModel.kt` = 100 lines, `rg -c 'mutableStateOf\|MutableStateFlow'` = **37**. Nuance: prior audit's "223+" refers to the start line (223), not a count. |
| 3 | Side effect during composition: `mainScrollState = scrollState`; `composeScope = rememberCoroutineScope()` assigned to activity fields in composable body, no `SideEffect{}` | **CONFIRMED** | `ui/settings/SettingsScreen.kt:69-73`: `val scrollState = rememberScrollState()` then `mainScrollState = scrollState` and `composeScope = rememberCoroutineScope()` — both write activity fields directly in the composition body, no `SideEffect{}` / `LaunchedEffect` guard. |
| 4 | Per-frame allocs in `onDraw`: `drawCustomMappings` per key per draw → `mainKey.getString().lowercase()` + `getMappingsForKey` doing `.values.filter{}.associateBy{}`; full-view `invalidate()` on 9 sites | **CONFIRMED** | `onDraw` at `Keyboard2View.kt:1352`; nested `for (row) { for (k in row.keys) { … drawCustomMappings(canvas, k, …) } }` calls it **once per key per frame** (`Keyboard2View.kt:1379`). `drawCustomMappings` (`:1560`) calls `mainKey.getString().lowercase()` (`:1571`, allocates a String each call) then `_shortSwipeManager.getMappingsForKey(keyCode)` (`:1577`). `getMappingsForKey` at `customization/ShortSwipeCustomizationManager.kt:143-148`: `mappingCache.values.filter{ it.keyCode == normalizedKey }.associateBy{ it.direction }` — a full scan of the map + two new collections **per key per frame** (`mappingCache` is a `ConcurrentHashMap`, `:31`). Nine `invalidate()` sites, all full-view (no `invalidate(Rect)`): `Keyboard2View.kt:376,385,400,411,449,457,467,482,502`. `rg 'invalidate\([^)]'` → none, confirming every redraw is whole-view. |
| 5 | SuggestionBar rebuilds all TextViews per post (`removeAllViews()` + `TextView(context)` per suggestion) per keystroke; hardcoded English "Add '$x' to dictionary?"; stringly-typed `dict_add:`/`exact_add:` protocol | **CONFIRMED** | `SuggestionBar : LinearLayout` (`SuggestionBar.kt:32`), 971 lines, fully imperative. In `setSuggestionsWithScores`: `removeAllViews()` + `suggestionViews.clear()` (`:260-261`), then `currentSuggestions.forEachIndexed { … createSuggestionView(context, i) … addView(textView) }` (`:265-329`). `createSuggestionView` returns `TextView(context)` **freshly allocated each call** (`:112-113`) — no recycling/pooling. Hardcoded English literal `"Add '$wordToAdd' to dictionary?"` at `:281-282`. Stringly-typed protocol: `suggestion.startsWith("dict_add:")` (`:272`), `.startsWith("exact_add:")` (`:275`), `.removePrefix("dict_add:")` (`:281`), `.removePrefix("exact_add:")` (`:286`). |
| 6 | Three parallel theming systems (`Theme.kt`, `theme/` Compose, `MaterialThemeManager`) + each settings activity re-rolls default M3; no shared `CleverKeysTheme` | **CONFIRMED** | (a) Legacy `Theme.kt` (Canvas rendering theme, XML + `KeyboardColorScheme` constructors, `Theme.kt:1-25`). (b) Compose theme package `theme/` incl. `KeyboardTheme.kt`, `KeyboardColorScheme.kt`, `KeyboardTypography.kt`, `KeyboardShapes.kt`, `ThemeProvider.kt`, `PredefinedThemes.kt`, `CustomThemeManager.kt`. (c) `theme/MaterialThemeManager.kt` — "Central manager for Material 3 theming" with its own `StateFlow`/prefs. Activities re-roll raw M3 defaults: `ShortSwipeCustomizationActivity.kt:70-72` `MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme())`; identical block at `ExtraKeysConfigActivity.kt:40-42`; also `ThemeSettingsActivity.kt`. `rg 'fun CleverKeysTheme\|CleverKeysTheme('` → **NO shared CleverKeysTheme** wrapper exists. |
| 7 | UI paradigm sprawl: 48 Compose + SuggestionBar 971 imperative + ListView/GridView + 22 XML + 8 onDraw views | **PARTIAL — corrected counts** | Compose files: `rg -l '@Composable'` = **36 files** (113 `@Composable` annotations); prior "48" is an overcount. `setContent {` surfaces = **10 activities**. `override fun onDraw` = **7 files** (`Keyboard2View`, `LauncherActivity`, `CustomLayoutEditDialog`, `TemplateBrowserActivity`, `SwipeCalibrationActivity`, `customization/KeyMagnifierView`, `customization/KeyboardPreviewView`) — prior "8 onDraw views" is off by one (likely counted `SuggestionBar` which is `onLayout`/imperative, not `onDraw`). XML layouts = **22** (confirmed exact). ListView/GridView imperative views: **confirmed present** (`ClipboardHistoryView`, `ClipboardPinView`, `MaxHeightListView`, `NonScrollListView`, `gif/GifGridView`, `EmojiGridView`, `TemplateBrowserActivity`, `DictionaryManagerActivity`, etc.). `SuggestionBar` 971 lines imperative `LinearLayout`: confirmed. Overall thrust (paradigm sprawl) holds; two counts corrected. |

Summary: **1 P1 CONFIRMED, 5 P2 CONFIRMED, 1 P3 PARTIAL (counts corrected, thrust holds).**

---

## Remediation Steps (severity-ordered)

### R1 — [P1] Add TalkBack support via `ExploreByTouchHelper` (finding #1)
**Files:** new `a11y/KeyboardAccessibilityHelper.kt`; wire into `Keyboard2View.kt`.
**Change:** Implement an `ExploreByTouchHelper` (from `androidx.customview.widget`, already
transitively available via AndroidX) that exposes one virtual view per visible key.
Full skeleton in the section below. Wire: in `Keyboard2View`'s init, create the helper and
call `ViewCompat.setAccessibilityDelegate(this, helper)`; override
`dispatchHoverEvent`/`onFocusChanged`/`dispatchKeyEvent` to forward to it; call
`helper.invalidateRoot()` whenever the layout or shift/mods change (i.e. beside the existing
`invalidate()` calls at `Keyboard2View.kt:376,385,400,411`).
**Test:** JVM: unit-test the pure geometry mapping `keyIndex ↔ Rect` and the
`KeyValue → contentDescription` labeller (see R1 skeleton `describe()`), which have no Android
deps once extracted. Instrumented (ew-cli, Pixel7 API 34): enable TalkBack via
`UiAutomation`/settings, assert `AccessibilityNodeInfo` count == visible-key count and that
`performAction(ACTION_CLICK)` on a virtual node emits the same `key_down`/`key_up` as a tap.
**Risk:** Medium. Hover events must not interfere with the swipe pipeline — gate the helper so
that when TalkBack is **off** (`AccessibilityManager.isTouchExplorationEnabled == false`) the
helper returns `HOST_ID` for `getVirtualViewAt` and does nothing, leaving today's fast path
untouched. Only when touch-exploration is active does per-key routing engage.

### R2 — [P2] Kill per-frame allocations in `onDraw` (finding #4)
**Files:** `customization/ShortSwipeCustomizationManager.kt:143-148`; `Keyboard2View.kt:1560-1580,1352-1385`.
**Change:**
1. Pre-index `mappingCache` into a derived `Map<String, Map<SwipeDirection, ShortSwipeMapping>>`
   keyed by `keyCode`, rebuilt once whenever mappings change (in `loadMappings()` and the
   add/remove/clear paths at `:164,:179,:196,:211,:244,:270`). Replace `getMappingsForKey` body
   with a single `indexByKeyCode[normalizedKey] ?: emptyMap()` — O(1), zero allocation.
2. Cache the per-key lowercased code. Store `keyCodeLower` once when the keyboard layout is set
   (in the `_keyboard` setter) instead of `mainKey.getString().lowercase()` every frame
   (`Keyboard2View.kt:1571`). A `WeakHashMap<KeyboardData.Key, String>` or a field on a
   render-model wrapper works; simplest is an `IdentityHashMap` rebuilt in the layout setter.
3. Add an early-out: skip the entire `drawCustomMappings` call at `Keyboard2View.kt:1379` when
   `_shortSwipeManager.hasAnyMappings` (`ShortSwipeCustomizationManager.kt:303`) is false —
   avoids a call + map lookup per key when the feature is unused (the common case).
**Test:** JVM: assert the pre-indexed map equals the old `filter/associateBy` result for a fixed
mapping set; benchmark not required. Behavioral instrumented test that custom sublabels still
render (screenshot diff via existing calibration harness if present).
**Risk:** Low. Pure caching; invalidation points are the existing mutation methods.

### R3 — [P2] Recycle SuggestionBar TextViews + de-stringify protocol + i18n (finding #5)
**Files:** `SuggestionBar.kt:112-113,260-329,281-282,272-286`.
**Change:**
1. **Recycle**, don't rebuild. Keep the existing `suggestionViews: MutableList<TextView>` as a
   pool. In `setSuggestionsWithScores`, do NOT `removeAllViews()`; instead reuse the first N
   children, `addView` only the shortfall, and `child.visibility = GONE` (or detach) the surplus.
   Set `text`/`typeface`/`color` on reused views. This removes N `TextView(context)` allocations
   and a full re-layout per keystroke.
2. **De-stringify** the `dict_add:`/`exact_add:` protocol (`:272-286`). Replace `List<String>`
   with a `sealed interface Suggestion { data class Word(val text:String, val score:Float?);
   data class AddToDictionary(val word:String); data class ExactAdd(val word:String) }`. The
   `when` on type replaces `startsWith`/`removePrefix` string parsing.
3. **i18n**: move `"Add '%s' to dictionary?"` to `res/values/strings.xml` as
   `R.string.suggestion_add_to_dictionary` and resolve via `context.getString(..., word)`
   (`SuggestionBar.kt:281-282`).
**Test:** JVM: test the sealed-type mapping (parsing/formatting) purely. Instrumented: post the
same suggestion list twice, assert child count stable and no new TextView instances (via tag
identity). Existing `SuggestionBarAutofillTest` (15) should stay green.
**Risk:** Medium — the stringly protocol is a cross-module contract (`SuggestionHandler`
produces these prefixes). Change producers and `SuggestionBarPropagator`/`SuggestionBarInitializer`
in the same commit; the `contractionAliases`/autofill path must keep working.

### R4 — [P2] Hoist SettingsActivity state into ViewModel; make composables top-level (findings #2, #3)
**Files:** `SettingsActivity.kt:223-…` (143 fields), `SettingsViewModel.kt`, `ui/settings/SettingsScreen.kt:67-73` and the `sections/` composables.
**Change (state-hoisting):**
1. Move the 143 `internal var … by mutableStateOf(...)` fields off the Activity and into a
   `SettingsUiState` data class exposed by `SettingsViewModel` (either grouped
   `MutableStateFlow<SettingsUiState>` or, to keep granular recomposition, a set of
   `mutableStateOf` held **in the ViewModel** — ViewModels may hold Compose state). Prefer a
   handful of cohesive sub-state objects (`NeuralState`, `TrailState`, `PrivacyState`, …) over
   one 143-field god-object.
2. Make composables **top-level** functions with explicit params instead of
   `SettingsActivity.SettingsScreen()` receiver extensions
   (`SettingsScreen.kt:68`). Signature becomes
   `@Composable fun SettingsScreen(state: SettingsUiState, onEvent: (SettingsEvent)->Unit)`.
   Section composables in `ui/settings/sections/` take their slice + callbacks. This decouples
   preview/testability from the Activity and unblocks `@Preview`.
3. **Fix the composition side-effect (finding #3)**: `mainScrollState = scrollState` /
   `composeScope = rememberCoroutineScope()` (`SettingsScreen.kt:71-73`) must not write Activity
   fields during composition. Instead surface a scroll intent through state: keep
   `scrollTargetKey` in the ViewModel and drive `scrollState.animateScrollTo` from a
   `LaunchedEffect(scrollTargetKey)`. If the Activity genuinely needs the `ScrollState` handle,
   assign it inside `SideEffect { activity.mainScrollState = scrollState }`.
4. Add `@Preview` composables for the top-level sections (raises coverage above the current 2).
**Test:** JVM/Robolectric or Compose UI test on the now-hoisted `SettingsScreen` with a fake
state. `SettingsSearchTest (5)` (scroll-to-setting regression) must stay green — the scroll
refactor in step 3 is the risk point for it.
**Risk:** High (largest blast radius, 143 fields + all sections). Do it section-by-section,
one `sections/*Section.kt` per commit, keeping the Activity fields as a thin façade until the
last section migrates.

### R5 — [P2] Unify theming under one `CleverKeysTheme` (finding #6)
**Files:** new `theme/CleverKeysTheme.kt`; `ShortSwipeCustomizationActivity.kt:70-72`, `ExtraKeysConfigActivity.kt:40-42`, `ThemeSettingsActivity.kt`, remaining `setContent` activities.
**Change:** Introduce a single `@Composable fun CleverKeysTheme(content: @Composable ()->Unit)`
that owns the M3 `ColorScheme` (delegating to `MaterialThemeManager.getColorScheme(darkTheme)`),
typography (`KeyboardTypography`), and shapes (`KeyboardShapes`). Replace every inline
`MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme())`
with `CleverKeysTheme { … }`. Keep legacy `Theme.kt` **only** as the Canvas renderer's data
holder (it is not a Compose theme and can't be merged), but have both read the same
`KeyboardColorScheme` source so the keyboard and settings stay visually consistent.
**Test:** Screenshot/instrumented smoke that each converted screen still renders in dark mode.
**Risk:** Low–Medium; mechanical, but touches ~10 activities.

### R6 — [P3] Consolidate imperative list views (finding #7)
**Files:** the ListView/GridView views (`ClipboardHistoryView`, `EmojiGridView`, `gif/GifGridView`, `DictionaryManagerActivity`, `TemplateBrowserActivity`, …).
**Change:** No wholesale rewrite. Where a screen already runs inside `setContent`, migrate its
`ListView`/`GridView` to `LazyColumn`/`LazyVerticalGrid` opportunistically; leave the
performance-critical **keyboard-embedded** views (`SuggestionBar`, GIF/emoji grids inside the IME
window) as imperative Views — Compose-in-IME has real cost. Document the intended split so the
paradigm mix is deliberate, not accidental.
**Test:** Existing per-panel tests (`GifTest`, `EmojiGridView` paths) stay green.
**Risk:** Low; incremental and optional.

---

## TalkBack / ExploreByTouchHelper Implementation Plan

**Virtual-view model:** one virtual view per **visible key** in the laid-out keyboard. The
keyboard is `keyboard.rows` → each `row.keys` (see the `onDraw` walk at `Keyboard2View.kt:1360-1382`).
Virtual view IDs are a stable flat index assigned during the same geometry walk the renderer
already does.

**Bounds ↔ ID mapping:** reuse the exact geometry the renderer/hit-tester uses. `onDraw`
(`Keyboard2View.kt:1360-1382`) and `getKeyAtPosition` (`:1116-1160`) already compute per-key
`x,y,keyW,keyH`. Extract that layout math into a single
`fun computeKeyRects(): List<KeyRect>` (data: `virtualId: Int, key: KeyboardData.Key, rect: Rect`)
so both `onDraw`, `getKeyAtPosition`, and the a11y helper share one source of truth (removes
the current duplication between `:1116` and `:1684 getKeyAt`). `getVirtualViewAt(x,y)` binary/linear
searches these rects (same order as `getKeyAtPosition`); `getVisibleVirtualViews` fills all ids.

**Content from `KeyValue`:** a pure `fun describe(kv: KeyValue): String` maps
`KeyValue.getKind()` (`KeyValue.kt:116-129`) to a spoken label:
`Char` → the character (uppercased letters spoken as "A"); `Event` (`getEvent()`, `:185`) →
localized names for Backspace/Enter/Space/Shift/etc. via `strings.xml`; `Modifier` → e.g.
"Shift"; `Editing`/`Slider` (cursor arrows, `:131-140`) → "move cursor left" etc.;
`String`/`Macro`/`Timestamp` → their symbol/`toString()`. Fall back to `kv.getString()`
(`KeyValue.kt:182`). This labeller is pure-JVM and unit-testable.

**Click actions:** advertise `ACTION_CLICK` on each node. In `onPerformActionForVirtualView`,
translate a click into the existing handler contract used by taps —
`_config.handler?.key_down(kv, isSwipe=false)` then `key_up(kv, mods)` (the same calls
`onPointerDown`/`onPointerUp` make at `Keyboard2View.kt:448,455`). Send
`AccessibilityEvent.TYPE_VIEW_CLICKED` for the node afterward.

**Wiring into `Keyboard2View`:**
```kotlin
// a11y/KeyboardAccessibilityHelper.kt
package tribixbite.cleverkeys.a11y

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import tribixbite.cleverkeys.KeyValue

/** Rect + KeyValue for one virtual key node. */
data class KeyRect(val virtualId: Int, val kv: KeyValue, val rect: Rect)

class KeyboardAccessibilityHelper(
    private val host: View,
    /** Returns current per-key rects; recomputed on layout/shift/mod change. */
    private val rectsProvider: () -> List<KeyRect>,
    /** Same contract taps use: handler.key_down then key_up. */
    private val onKeyActivate: (KeyValue) -> Unit,
    private val describe: (KeyValue) -> String,   // pure, testable
) : ExploreByTouchHelper(host) {

    private fun rects() = rectsProvider()

    override fun getVirtualViewAt(x: Float, y: Float): Int {
        val ix = x.toInt(); val iy = y.toInt()
        rects().forEach { if (it.rect.contains(ix, iy)) return it.virtualId }
        return HOST_ID   // fall through to default (fast path when off a key)
    }

    override fun getVisibleVirtualViews(ids: MutableList<Int>) {
        rects().forEach { ids.add(it.virtualId) }
    }

    override fun onPopulateNodeForVirtualView(
        virtualViewId: Int, node: AccessibilityNodeInfoCompat
    ) {
        val kr = rects().firstOrNull { it.virtualId == virtualViewId }
        if (kr == null) {                       // stale id during relayout
            node.contentDescription = ""
            node.setBoundsInParent(Rect(0, 0, 1, 1))
            return
        }
        node.contentDescription = describe(kr.kv)
        node.className = "android.inputmethodservice.Keyboard\$Key"
        node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        node.isClickable = true
        node.setBoundsInParent(kr.rect)
    }

    override fun onPerformActionForVirtualView(
        virtualViewId: Int, action: Int, arguments: Bundle?
    ): Boolean {
        if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
        val kr = rects().firstOrNull { it.virtualId == virtualViewId } ?: return false
        onKeyActivate(kr.kv)                    // → handler.key_down + key_up
        sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_CLICKED)
        return true
    }
}
```
Host wiring in `Keyboard2View.kt`:
```kotlin
private val a11yHelper = KeyboardAccessibilityHelper(
    host = this,
    rectsProvider = ::computeKeyRects,                 // extracted shared geometry
    onKeyActivate = { kv ->                             // mirrors onPointerDown/onPointerUp
        _config.handler?.key_down(kv, false)
        _config.handler?.key_up(kv, _mods)
    },
    describe = KeyLabels::describe,
).also { ViewCompat.setAccessibilityDelegate(this, it) }

override fun dispatchHoverEvent(e: MotionEvent) =
    a11yHelper.dispatchHoverEvent(e) || super.dispatchHoverEvent(e)

override fun onFocusChanged(g: Boolean, dir: Int, prev: Rect?) {
    super.onFocusChanged(g, dir, prev); a11yHelper.onFocusChanged(g, dir, prev)
}
// After any layout/shift/modifier change (beside existing invalidate() at :376,:385,:400,:411):
a11yHelper.invalidateRoot()
```
**Fast-path guard:** if `!AccessibilityManager.isTouchExplorationEnabled`, keep swipe/typing
100% unchanged (helper's `dispatchHoverEvent` returns false because no hover events arrive when
touch-exploration is off, so the existing `onTouch` path at `Keyboard2View.kt:1065` is untouched).

---

## Refutations / Corrections

- **Package name**: prior audit cited `tribixbite/keyboard2/…`. Actual package is
  `tribixbite.cleverkeys` and files live under `src/main/kotlin/tribixbite/cleverkeys/`. All
  its line-number claims still line up with the real files.
- **Finding #2 "223+"**: that is the **start line** of the 143 fields (`SettingsActivity.kt:223`),
  not a count of 223 fields. The 143 figure is exact and correct.
- **Finding #7 "48 Compose"**: overcount. Actual = **36 files** containing `@Composable`
  (113 annotations), **10** `setContent` activities.
- **Finding #7 "8 onDraw views"**: actual = **7** files with `override fun onDraw`. The 8th was
  likely `SuggestionBar`, which is imperative `LinearLayout` but does **not** override `onDraw`.
- All other numbers (143, 2 `@Preview`, 971 lines, 22 XML, 9 `invalidate()` sites, 37 VM fields)
  verified exact.

---

## Effort Estimate to Reach "A" Grade

| Item | Effort | Priority |
|------|--------|----------|
| R1 TalkBack `ExploreByTouchHelper` (incl. geometry extraction + tests) | 2–3 days | must-have (P1 blocker for A) |
| R2 `onDraw` pre-indexed cache + code-lower cache + early-out | 0.5 day | high |
| R3 SuggestionBar recycling + sealed protocol + i18n | 1–1.5 days | high |
| R4 SettingsActivity state-hoisting (per-section) + composition side-effect fix + previews | 3–5 days | medium (largest, do incrementally) |
| R5 unified `CleverKeysTheme` | 0.5–1 day | medium |
| R6 opportunistic list-view consolidation + document paradigm split | 0.5 day + ongoing | low |

**Total to A: ~8–12 engineering days.** Ship order: R1 (accessibility is the one true grade
gate) → R2/R3 (cheap, high-value perf) → R5 (mechanical) → R4 (biggest, incremental) → R6.
