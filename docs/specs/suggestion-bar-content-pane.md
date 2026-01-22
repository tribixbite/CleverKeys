# Suggestion Bar & Content Pane System

## Feature Overview
**Feature Name**: Suggestion Bar & Content Pane System
**Priority**: P0
**Status**: Complete
**Target Version**: v1.2.6

### Summary
The suggestion bar displays word predictions above the keyboard and can be replaced by emoji/clipboard panes using a simple view-swap mechanism in a topPane container.

### Motivation
Users need word suggestions while typing, and the ability to quickly access emoji and clipboard without leaving the keyboard. The content pane system provides a unified container that swaps between different content types.

## Architecture

### View Hierarchy

```
inputViewContainer (LinearLayout, VERTICAL)
├── topPane (FrameLayout) - contains EITHER scrollView OR contentPaneContainer
│   └── scrollView (HorizontalScrollView) - default, contains SuggestionBar
│       └── SuggestionBar (LinearLayout)
│   OR
│   └── contentPaneContainer (FrameLayout) - when emoji/clipboard shown
│       └── [emoji_pane OR clipboard_pane] (added dynamically)
└── keyboardView (Keyboard2View) - wrap_content height
```

### Simple TopPane Design

Uses simple FrameLayout swap instead of ViewFlipper for reliability:
- **topPane**: FrameLayout that holds ONE child at a time (either scrollView or contentPaneContainer)
- **View swap**: Child views are removed/added directly, no animation complexity
- **Height managed**: topPane's LayoutParams.height is updated when switching modes
- **No gap**: Direct LinearLayout stacking ensures no gap between topPane and keyboard

### Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `SuggestionBarInitializer.kt` | `SuggestionBarInitializer` | Creates view hierarchy, mode switching |
| `SuggestionBar.kt` | `SuggestionBar` | Displays word suggestions |
| `SuggestionBarPropagator.kt` | `SuggestionBarPropagator` | Passes view references to managers |
| `PredictionViewSetup.kt` | `PredictionViewSetup` | Coordinates setup in onStartInputView |
| `KeyboardReceiver.kt` | `KeyboardReceiver` | Handles show/hide content pane |
| `CleverKeysService.kt` | `CleverKeysService` | Tracks view references across app switches |
| `res/layout/emoji_pane.xml` | - | Emoji picker layout |
| `res/layout/clipboard_pane.xml` | - | Clipboard manager layout |

### Component Responsibilities

- **SuggestionBarInitializer**: Creates the complete view hierarchy. Provides static methods `switchToContentPaneMode()` and `switchToSuggestionBarMode()` for swapping.

- **SuggestionBar**: Custom LinearLayout that displays word predictions. Supports multiple modes: suggestions, password, emoji search status.

- **topPane**: Simple FrameLayout that holds either scrollView (suggestion bar) or contentPaneContainer (emoji/clipboard). Views are swapped by removing one and adding the other.

- **KeyboardReceiver**: Manages content pane visibility via `showContentPane()` and `hideContentPane()`. Tracks state with `isContentPaneShowing` and `currentPaneType`.

- **PredictionViewSetup**: Orchestrates setup during `onStartInputView()`. Returns all view references in `SetupResult`.

- **CleverKeysService**: Persists view references (`_topPane`, `_scrollView`, `_contentPaneContainer`) across app switches.

## Technical Design

### View Swapping

When showing content pane:
```kotlin
// SuggestionBarInitializer.switchToContentPaneMode()
fun switchToContentPaneMode(topPane: FrameLayout, contentPane: FrameLayout,
                            scrollView: HorizontalScrollView, height: Int) {
    // Remove scrollView if present
    if (scrollView.parent == topPane) {
        topPane.removeView(scrollView)
    }

    // Resize topPane
    topPane.layoutParams.height = height
    topPane.layoutParams = topPane.layoutParams

    // Add contentPane with explicit height
    contentPane.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, height)
    if (contentPane.parent != topPane) {
        (contentPane.parent as? ViewGroup)?.removeView(contentPane)
        topPane.addView(contentPane)
    }

    topPane.requestLayout()
}
```

When hiding content pane:
```kotlin
// SuggestionBarInitializer.switchToSuggestionBarMode()
fun switchToSuggestionBarMode(topPane: FrameLayout, contentPane: FrameLayout,
                              scrollView: HorizontalScrollView, height: Int) {
    // Remove contentPane if present
    if (contentPane.parent == topPane) {
        topPane.removeView(contentPane)
    }

    // Resize topPane
    topPane.layoutParams.height = height
    topPane.layoutParams = topPane.layoutParams

    // Add scrollView with explicit height
    scrollView.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, height)
    if (scrollView.parent != topPane) {
        (scrollView.parent as? ViewGroup)?.removeView(scrollView)
        topPane.addView(scrollView)
    }

    topPane.requestLayout()
}
```

### Content Pane Addition

Emoji/clipboard panes are added dynamically:
```kotlin
// KeyboardReceiver.SWITCH_EMOJI
emojiPane = keyboard2.inflate_view(R.layout.emoji_pane) as ViewGroup
contentPaneContainer?.let { container ->
    container.removeAllViews()
    (pane?.parent as? ViewGroup)?.removeView(pane)
    pane?.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, contentPaneHeight)
    container.addView(pane)
    showContentPane()
}
currentPaneType = PaneType.EMOJI
```

## Configuration

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `suggestion_bar_opacity` | Int | 90 | Suggestion bar opacity (0-100) |
| `clipboard_pane_height_percent` | Int | 30 | Content pane height as % of screen |
| `word_prediction_enabled` | Boolean | true | Enable word predictions |
| `swipe_typing_enabled` | Boolean | true | Enable swipe typing |

## State Management

```kotlin
// KeyboardReceiver state
private var isContentPaneShowing: Boolean = false
private var currentPaneType: PaneType = PaneType.NONE

enum class PaneType { NONE, EMOJI, CLIPBOARD }
```

### Critical: State Reset on App Switch

When the keyboard hides (app switch), state flags MUST be reset unconditionally:
```kotlin
// Called from CleverKeysService.onFinishInputView()
fun resetContentPaneState() {
    val wasShowing = isContentPaneShowing

    if (wasShowing) {
        hideContentPane()  // Resets isContentPaneShowing
    }

    // CRITICAL: Always reset pane type to prevent toggle issues
    currentPaneType = PaneType.NONE
    emojiSearchManager?.onPaneClosed()
    clipboardManager.resetSearchOnHide()
}

private fun hideContentPane() {
    // CRITICAL: Reset state flag FIRST, before checking null views
    isContentPaneShowing = false

    val top = topPane
    val content = contentPaneContainer
    val scroll = scrollView

    if (top == null || content == null || scroll == null) {
        return  // Views null, but state already reset
    }

    SuggestionBarInitializer.switchToSuggestionBarMode(top, content, scroll, suggestionBarHeight)
}
```

### View Reference Persistence

View references must persist across `onStartInputView` calls:
```kotlin
// CleverKeysService fields
private var _topPane: FrameLayout? = null
private var _scrollView: HorizontalScrollView? = null
private var _contentPaneContainer: FrameLayout? = null

// Passed to setupPredictionViews and saved from result
val result = PredictionViewSetup.create(...).setupPredictionViews(
    _suggestionBar, _inputViewContainer, _contentPaneContainer, _topPane, _scrollView
)
_topPane = result.topPane
_scrollView = result.scrollView
```

## Bug Fixes History

### Gap Between Content Pane and Keyboard
**Fixed**: v1.2.5

**Root Cause**: ViewFlipper with MATCH_PARENT heights didn't measure correctly in IME window.

**Solution**: Replaced ViewFlipper with simple topPane FrameLayout. Views swapped directly with explicit heights.

### Panels Empty After App Switch
**Fixed**: v1.2.6

**Root Cause**: When extracting `scrollView` from hierarchy after content pane was showing, `topPane.getChildAt(0)` returned `contentPaneContainer` not `scrollView`, resulting in null `scrollView`. The `hideContentPane()` then returned early without resetting `isContentPaneShowing`, causing toggle logic to malfunction.

**Solution**:
1. Pass existing view references to `setupPredictionViews` instead of re-extracting from hierarchy
2. Reset `isContentPaneShowing = false` FIRST in `hideContentPane()`, before null checks
3. Always reset `currentPaneType = NONE` in `resetContentPaneState()` unconditionally

## Dependencies

### Internal Dependencies
- `Theme`: For suggestion bar styling
- `Config`: For opacity and height settings
- `EmojiPane`/`ClipboardPane`: Content views

### Android Dependencies
- `LinearLayout`: Root container (vertical orientation)
- `FrameLayout`: topPane and contentPaneContainer
- `HorizontalScrollView`: Scrollable suggestions

## Recent Enhancements (v1.2.6)

### Emoji Long-Press Tooltip (Implemented)
Long-pressing an emoji displays its name in a PopupWindow positioned above the pressed cell:
- Uses `showAsDropDown()` for reliable IME positioning
- Programmatic view creation (no XML inflation issues)
- Auto-dismisses on scroll or after 2.5 seconds
- Shows Unicode names for unmapped emojis
- Shows country names for flag emojis (260+ mappings)
- Shows descriptive names for emoticons (100+ mappings)

Key files: `EmojiTooltipManager.kt`, `EmojiGridView.kt`, `Emoji.kt`

### Emoticons Category
Text emoticons added as final emoji category:
- 119 text emoticons (smileys, kaomoji)
- Searchable via keyword index ("shrug", "lenny", "tableflip")
- Length-based text scaling for proper cell fit
- Grid spacing prevents overlap

## Future Enhancements

1. **Animation**: Add smooth crossfade when swapping content.

2. **Gesture Navigation**: Swipe up/down to show/hide content pane.

3. **Emoji Favorites**: Pin frequently used emoji to a favorites bar.

---

**Created**: 2026-01-20
**Last Updated**: 2026-01-22
**Owner**: tribixbite
