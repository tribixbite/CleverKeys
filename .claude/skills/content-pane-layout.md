# Content Pane Layout Skill

Use this skill when working on the emoji/clipboard panel layout, view swapping, height management, or the suggestion bar replacement system.

## Key Files

### View Management
| File | Purpose |
|------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/SuggestionBarInitializer.kt` | Creates view hierarchy, mode switching methods |
| `src/main/kotlin/tribixbite/cleverkeys/KeyboardReceiver.kt` | Show/hide content pane, state management |
| `src/main/kotlin/tribixbite/cleverkeys/PredictionViewSetup.kt` | Orchestrates setup in onStartInputView |
| `src/main/kotlin/tribixbite/cleverkeys/SuggestionBarPropagator.kt` | Passes view references to managers |
| `src/main/kotlin/tribixbite/cleverkeys/CleverKeysService.kt` | Persists view references across app switches |

### Layouts
| File | Purpose |
|------|---------|
| `res/layout/emoji_pane.xml` | Emoji picker layout |
| `res/layout/clipboard_pane.xml` | Clipboard manager layout |
| `res/layout/suggestion_bar.xml` | Word prediction suggestions |

## Architecture

### View Hierarchy
```
inputViewContainer (LinearLayout, VERTICAL)
├── topPane (FrameLayout) - holds ONE child at a time
│   └── scrollView (HorizontalScrollView) - DEFAULT mode
│       └── SuggestionBar (LinearLayout)
│   OR
│   └── contentPaneContainer (FrameLayout) - CONTENT mode
│       └── [emoji_pane OR clipboard_pane]
└── keyboardView (Keyboard2View)
```

### Mode Switching

**Suggestion Bar Mode** (default):
```kotlin
SuggestionBarInitializer.switchToSuggestionBarMode(topPane, contentPane, scrollView, height)
// Removes contentPane from topPane
// Adds scrollView to topPane
// Resizes topPane to suggestion bar height
```

**Content Pane Mode** (emoji/clipboard):
```kotlin
SuggestionBarInitializer.switchToContentPaneMode(topPane, contentPane, scrollView, height)
// Removes scrollView from topPane
// Adds contentPane to topPane
// Resizes topPane to content pane height
```

### State Management
```kotlin
// KeyboardReceiver.kt
private var isContentPaneShowing: Boolean = false
private var currentPaneType: PaneType = PaneType.NONE

enum class PaneType { NONE, EMOJI, CLIPBOARD }
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

## Height Configuration

| Setting | Default | Description |
|---------|---------|-------------|
| `clipboard_pane_height_percent` | 30 | Content pane height as % of screen |
| Suggestion bar height | 40dp | Fixed height for suggestion bar |

Height calculation:
```kotlin
// SuggestionBarInitializer.calculateContentPaneHeight()
val displayHeight = context.resources.displayMetrics.heightPixels
val percent = config.clipboard_pane_height_percent
return (displayHeight * percent / 100)
```

## Common Tasks

### Changing Content Pane Height
1. Modify `Config.kt` default for `clipboard_pane_height_percent`
2. Or change calculation in `SuggestionBarInitializer.calculateContentPaneHeight()`

### Adding a New Content Pane Type
1. Add enum value to `KeyboardReceiver.PaneType`
2. Create layout XML in `res/layout/`
3. Add event handler in `KeyboardReceiver.handle_event_key()`
4. Follow pattern from SWITCH_EMOJI/SWITCH_CLIPBOARD:
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

## Testing Checklist

- [ ] Emoji pane opens (no gap)
- [ ] Clipboard pane opens (no gap)
- [ ] Toggle behavior works (tap emoji key twice closes pane)
- [ ] Switch between emoji/clipboard works
- [ ] App switch resets state (panels not empty on return)
- [ ] Keyboard remains visible below content pane
- [ ] Suggestion bar returns correctly when closing pane
- [ ] Height scales with screen size

## Bug History

### Gap Between Content Pane and Keyboard
**Fixed**: v1.2.5
**Cause**: ViewFlipper with MATCH_PARENT heights
**Solution**: Simple topPane FrameLayout with explicit heights

### Panels Empty After App Switch
**Fixed**: v1.2.6
**Cause**: View reference extraction failed when content pane was showing
**Solution**: Pass existing view refs instead of re-extracting; reset state flags unconditionally

## Related Documentation
- Spec: `docs/specs/suggestion-bar-content-pane.md`
- Wiki: `docs/wiki/clipboard/clipboard-history.md`
