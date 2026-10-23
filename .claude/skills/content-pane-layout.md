# Content Pane Layout Skill

Use this skill when working on the emoji/clipboard/GIF panel layout, view swapping, height
management, or the suggestion-bar replacement system. Re-derived from HEAD on 2026-10-23
(the January text named `SuggestionBarInitializer.kt`, which no longer exists).

## Key Files

### View Management
| File | Purpose |
|------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/wiring/KeyboardComponentGraph.kt` | `object SuggestionBarPane` (formerly `SuggestionBarInitializer`): builds the view hierarchy (`initialize`), `calculateContentPaneHeight`, `switchToContentPaneMode` / `switchToSuggestionBarMode` |
| `src/main/kotlin/tribixbite/cleverkeys/KeyboardReceiver.kt` | Show/hide content pane, `PaneType` state, the `SWITCH_EMOJI` / `SWITCH_CLIPBOARD` / `SWITCH_GIF` / `SWITCH_BACK_CLIPBOARD` handlers |
| `src/main/kotlin/tribixbite/cleverkeys/PredictionViewSetup.kt` | `setupPredictionViews(existingSuggestionBar, existingInputViewContainer, existingContentPaneContainer, existingTopPane, existingScrollView)` — orchestrates setup in `onStartInputView` |
| `src/main/kotlin/tribixbite/cleverkeys/SuggestionBarPropagator.kt` | Passes view references to managers |
| `src/main/kotlin/tribixbite/cleverkeys/CleverKeysService.kt` | Persists view references across app switches (`_topPane`, `_scrollView`, `_contentPaneContainer`, `_inputViewContainer`, `_suggestionBar`); `installPredictionViews(info)` |
| `src/main/kotlin/tribixbite/cleverkeys/minimize/KeyboardMinimizer.kt` | Every `setInputView` goes through `resolve()`, so a minimized keyboard stays minimized when the container is rebuilt (see `keyboard-tile-and-minimize.md`) |

### Layouts
| File | Purpose |
|------|---------|
| `res/layout/emoji_pane.xml` | Emoji picker layout |
| `res/layout/clipboard_pane.xml` | Clipboard manager layout |
| `res/layout/gif_pane.xml` | GIF panel layout |

The suggestion bar itself is built in code (`SuggestionBar(context, theme)`); there is no
`suggestion_bar.xml`.

## Architecture

### View Hierarchy
```
inputViewContainer (LinearLayout, VERTICAL)
├── topPane (FrameLayout) - holds ONE child at a time
│   └── scrollView (HorizontalScrollView) - DEFAULT mode
│       └── SuggestionBar (LinearLayout)
│   OR
│   └── contentPaneContainer (FrameLayout) - CONTENT mode
│       └── [emoji_pane OR clipboard_pane OR gif_pane]
└── keyboardView (Keyboard2View)
```

No ViewFlipper: views are swapped by removing/adding `topPane`'s child with explicit pixel
heights (the v1.2.5 gap bug was `MATCH_PARENT` inside a ViewFlipper).

### Mode Switching (`SuggestionBarPane`, `wiring/KeyboardComponentGraph.kt`)

```kotlin
SuggestionBarPane.initialize(context, theme, opacity, clipboardPaneHeightPercent)
// -> InitializationResult(inputViewContainer, suggestionBar, contentPaneContainer, scrollView, topPane)

SuggestionBarPane.switchToSuggestionBarMode(topPane, contentPane, scrollView, height)
// Removes contentPane from topPane, adds scrollView, resizes topPane to the bar height

SuggestionBarPane.switchToContentPaneMode(topPane, contentPane, scrollView, height)
// Removes scrollView from topPane, adds contentPane, resizes topPane to the pane height
```

Both log under the tag `SuggestionBarInit` (kept from the old class name).

### State Management
```kotlin
// KeyboardReceiver.kt
private var isContentPaneShowing: Boolean = false
private var currentPaneType: PaneType = PaneType.NONE

private enum class PaneType { NONE, EMOJI, CLIPBOARD, GIF }
```

### Critical: State Reset on App Switch
```kotlin
// Called from CleverKeysService.onFinishInputView()
fun resetContentPaneState() {
    if (isContentPaneShowing) {
        hideContentPane()  // Resets isContentPaneShowing FIRST
    }
    currentPaneType = PaneType.NONE  // ALWAYS reset
    emojiSearchManager?.onPaneClosed()
    clipboardManager.resetSearchOnHide()
}
```

## Height and Opacity Configuration

| Setting | Default | Range (`SettingsRanges`) | Description |
|---------|---------|--------------------------|-------------|
| `clipboard_pane_height_percent` | 30 | 10..50 | Content pane height; `calculateContentPaneHeight` applies it to `displayMetrics.heightPixels` (screen height), although the Config KDoc says "percent of the keyboard area" |
| `suggestion_bar_opacity` | 80 (`Defaults.SUGGESTION_BAR_OPACITY`, Config.kt ~199) | 0..100 | Passed to `SuggestionBarPane.initialize` → `suggestionBar.setOpacity` |
| Suggestion bar height | 40dp | — | Fixed |

```kotlin
// SuggestionBarPane.calculateContentPaneHeight(context, heightPercent)
val screenHeight = context.resources.displayMetrics.heightPixels
return (screenHeight * heightPercent) / 100
```

Landscape: the default pane is only ~120dp tall — separate 40dp search, 48dp result and 32dp
pagination rows leave no entries; keep a full 48dp entry viewport and share search/results
horizontally on wide panes (Seeker, 2026-10-06; CLAUDE.md testing policy).

## Common Tasks

### Changing Content Pane Height
1. Change `Defaults.CLIPBOARD_PANE_HEIGHT_PERCENT` / `SettingsRanges.CLIPBOARD_PANE_HEIGHT_PERCENT` in `Config.kt`
2. Or change `SuggestionBarPane.calculateContentPaneHeight()`

### Adding a New Content Pane Type
1. Add the enum value to `KeyboardReceiver.PaneType`
2. Create the layout XML in `res/layout/`
3. Add the event handler in `KeyboardReceiver.handle_event_key()`; follow `SWITCH_GIF`
   (the newest one — it also refuses to open with no `contentPaneContainer`, gh #148):
   ```kotlin
   KeyValue.Event.SWITCH_NEW_PANE -> {
       // Toggle behavior
       if (currentPaneType == PaneType.NEW && isContentPaneShowing) {
           handle_event_key(KeyValue.Event.SWITCH_BACK_CLIPBOARD)
           return
       }
       // Inflate and add pane
       val newPane = keyboard2.inflate_view(R.layout.new_pane)
       contentPaneContainer?.let { container ->
           container.removeAllViews()
           (newPane?.parent as? ViewGroup)?.removeView(newPane)
           newPane?.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, contentPaneHeight)
           container.addView(newPane)
           showContentPane()
       }
       currentPaneType = PaneType.NEW
   }
   ```
4. Route its keys: see `ime-key-routing.md` (search mode / edit mode chains)

### Debugging Gap Issues
If a gap appears between content pane and keyboard:
1. Check `topPane.layoutParams.height` is set correctly
2. Verify `contentPane.layoutParams.height` matches
3. Ensure no `MATCH_PARENT` heights (use explicit pixel values)
4. Check `inputViewContainer` is `LinearLayout` with `VERTICAL` orientation

### View Reference Persistence
View references MUST persist across `onStartInputView` calls:
```kotlin
// CleverKeysService fields
private var _topPane: FrameLayout? = null
private var _scrollView: HorizontalScrollView? = null
private var _contentPaneContainer: FrameLayout? = null

// Pass existing refs to setup, save new refs from result
val result = setupPredictionViews(_suggestionBar, _inputViewContainer,
                                  _contentPaneContainer, _topPane, _scrollView)
_topPane = result.topPane
_scrollView = result.scrollView
```

**Theme change exception** (Saga, 2026-10-07): `onThemeChanged` inflates a NEW `Keyboard2View`.
When the existing container does not hold THIS view, `setupPredictionViews` rebuilds the whole
hierarchy (`setupPredictionViews(null, null, null, null, null)`) — reusing the old container kept
showing the old keyboard until the IME process restarted. The bar is themed too, so it is rebuilt.

## Testing Checklist

- [ ] Emoji pane opens (no gap)
- [ ] Clipboard pane opens (no gap)
- [ ] GIF pane opens (no gap)
- [ ] Toggle behavior works (tap emoji key twice closes pane)
- [ ] Switch between emoji/clipboard/GIF works
- [ ] App switch resets state (panels not empty on return)
- [ ] Keyboard remains visible below content pane
- [ ] Suggestion bar returns correctly when closing pane
- [ ] Height scales with screen size
- [ ] A pane screenshot includes the keyboard: panes appear ABOVE the unchanged keyboard, so a
      bottom-only crop cannot confirm opening/closing (Seeker, 2026-10-06)

## Bug History

### Gap Between Content Pane and Keyboard
**Fixed**: v1.2.5
**Cause**: ViewFlipper with MATCH_PARENT heights
**Solution**: Simple topPane FrameLayout with explicit heights

### Panels Empty After App Switch
**Fixed**: v1.2.6
**Cause**: View reference extraction failed when content pane was showing
**Solution**: Pass existing view refs instead of re-extracting; reset state flags unconditionally

### Theme Switch Invisible Until Process Restart
**Fixed**: 2026-10-07
**Cause**: the container built around the OLD keyboard view was reused after `onThemeChanged`
**Solution**: rebuild the hierarchy when the container does not hold the current view

## Related Documentation
- Spec: `docs/specs/suggestion-bar-content-pane.md`
- Wiki: `docs/wiki/clipboard/clipboard-history.md`
- Skills: `clipboard-panel-architecture.md`, `emoji-panel.md`, `ime-key-routing.md`,
  `keyboard-tile-and-minimize.md`
