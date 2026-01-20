# Suggestion Bar & Content Pane System

## Feature Overview
**Feature Name**: Suggestion Bar & Content Pane System
**Priority**: P0
**Status**: Complete (with known issues)
**Target Version**: v1.2.5

### Summary
The suggestion bar displays word predictions above the keyboard and can be replaced by emoji/clipboard panes using a ViewFlipper-based swap mechanism.

### Motivation
Users need word suggestions while typing, and the ability to quickly access emoji and clipboard without leaving the keyboard. The content pane system provides a unified container that swaps between different content types.

## Architecture

### View Hierarchy

```
inputViewContainer (LinearLayout, vertical)
├── viewFlipper (ViewFlipper) - swaps between suggestion bar and content pane
│   ├── scrollView (HorizontalScrollView) - index 0, default
│   │   └── SuggestionBar (LinearLayout)
│   └── contentPaneContainer (FrameLayout) - index 1
│       └── [emoji_pane OR clipboard_pane] (added dynamically)
└── keyboardView (Keyboard2View) - WRAP_CONTENT height, weight=0
```

### Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `SuggestionBarInitializer.kt` | `SuggestionBarInitializer` | Creates view hierarchy |
| `SuggestionBar.kt` | `SuggestionBar` | Displays word suggestions |
| `SuggestionBarPropagator.kt` | `SuggestionBarPropagator` | Passes view references |
| `PredictionViewSetup.kt` | `PredictionViewSetup` | Coordinates setup in onStartInputView |
| `KeyboardReceiver.kt` | `KeyboardReceiver` | Handles show/hide content pane |
| `res/layout/emoji_pane.xml` | - | Emoji picker layout |
| `res/layout/clipboard_pane.xml` | - | Clipboard manager layout |

### Component Responsibilities

- **SuggestionBarInitializer**: Creates the complete view hierarchy including ViewFlipper, scrollView, contentPaneContainer. Returns all references in `InitializationResult`.

- **SuggestionBar**: Custom LinearLayout that displays word predictions. Supports multiple modes: suggestions, password, emoji search results.

- **ViewFlipper**: Swaps between scrollView (suggestions) and contentPaneContainer (emoji/clipboard). Located at index 0 in inputViewContainer.

- **KeyboardReceiver**: Manages content pane visibility via `showContentPane()` and `hideContentPane()`. Toggles ViewFlipper's displayedChild and adjusts height.

- **PredictionViewSetup**: Orchestrates setup during `onStartInputView()`. Adds keyboardView to inputViewContainer with explicit LayoutParams.

## Technical Design

### Height Management

When showing content pane:
```kotlin
// KeyboardReceiver.showContentPane()
flipper.layoutParams = LinearLayout.LayoutParams(
    MATCH_PARENT,
    0  // height=0 with weight=1 fills remaining space
).apply {
    weight = 1f
}
flipper.displayedChild = 1
```

When hiding content pane:
```kotlin
// KeyboardReceiver.hideContentPane()
flipper.layoutParams = LinearLayout.LayoutParams(
    MATCH_PARENT,
    suggestionBarHeight  // fixed height (40dp)
)
flipper.displayedChild = 0
```

### Content Pane Addition

Emoji/clipboard panes are added dynamically:
```kotlin
contentPaneContainer.removeAllViews()
pane.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
contentPaneContainer.addView(pane)
showContentPane()
```

## Configuration

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `suggestion_bar_opacity` | Int | 90 | Suggestion bar opacity (0-100) |
| `clipboard_pane_height_percent` | Int | 30 | Content pane height as % of screen (fallback) |
| `word_prediction_enabled` | Boolean | true | Enable word predictions |
| `swipe_typing_enabled` | Boolean | true | Enable swipe typing |

## Known Issues

### Gap Between Content Pane and Keyboard
**Status**: Investigating

The content pane sometimes doesn't fill the entire space above the keyboard, leaving a visible gap. Current fix uses `weight=1` on ViewFlipper to let LinearLayout calculate the correct size.

**Root Cause**: Fixed height calculations don't account for all layout factors.

**Workaround**: Use layout_weight=1 instead of fixed height.

## State Management

```kotlin
// KeyboardReceiver state
private var isContentPaneShowing: Boolean = false
private var currentPaneType: PaneType = PaneType.NONE

enum class PaneType { NONE, EMOJI, CLIPBOARD }
```

### App Switch Handling

When the keyboard hides (app switch), content pane state is reset:
```kotlin
// Called from CleverKeysService.onFinishInputView()
fun resetContentPaneState() {
    if (isContentPaneShowing) {
        hideContentPane()
        currentPaneType = PaneType.NONE
    }
}
```

## Dependencies

### Internal Dependencies
- `Theme`: For suggestion bar styling
- `Config`: For opacity and height settings
- `EmojiPane`/`ClipboardPane`: Content views

### Android Dependencies
- `ViewFlipper`: View swapping
- `HorizontalScrollView`: Scrollable suggestions
- `LinearLayout.LayoutParams`: Weight-based sizing

## Future Enhancements

1. **Inline Transformation**: Transform suggestion bar directly into emoji/clipboard picker instead of swapping views (simpler, more reliable).

2. **Dynamic Height Calculation**: Calculate exact available height at runtime using `parent.height - keyboard.height`.

3. **Animation**: Add smooth transition animations when swapping content.

---

**Created**: 2026-01-20
**Last Updated**: 2026-01-20
**Owner**: tribixbite
