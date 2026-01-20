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
inputViewContainer (ConstraintLayout)
├── viewFlipper (ViewFlipper) - swaps between suggestion bar and content pane
│   ├── scrollView (HorizontalScrollView) - index 0, default
│   │   └── SuggestionBar (LinearLayout)
│   └── contentPaneContainer (FrameLayout) - index 1
│       └── [emoji_pane OR clipboard_pane] (added dynamically)
└── keyboardView (Keyboard2View) - pinned to bottom, wrap_content height
```

### ConstraintLayout Design

Uses ConstraintLayout instead of LinearLayout to ensure:
- **Keyboard pinned to bottom**: Always stays at the bottom of the IME window
- **ViewFlipper above keyboard**: Constrained between top of parent and top of keyboard
- **No gap**: Direct constraint connection between ViewFlipper bottom and keyboard top
- **Configurable max height**: Content pane respects user's height setting via `constraintHeight_max`

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

### Height Management with ConstraintLayout

When showing content pane:
```kotlin
// SuggestionBarInitializer.switchToContentPaneMode()
val constraintSet = ConstraintSet()
constraintSet.clone(container)
// ViewFlipper: expand to fill space between top and keyboard, with max height
constraintSet.connect(VIEW_FLIPPER_ID, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
constraintSet.constrainHeight(VIEW_FLIPPER_ID, ConstraintSet.MATCH_CONSTRAINT)
constraintSet.constrainMaxHeight(VIEW_FLIPPER_ID, maxHeight)
constraintSet.connect(VIEW_FLIPPER_ID, ConstraintSet.BOTTOM, KEYBOARD_VIEW_ID, ConstraintSet.TOP)
constraintSet.applyTo(container)
flipper.displayedChild = 1
```

When hiding content pane:
```kotlin
// SuggestionBarInitializer.switchToSuggestionBarMode()
val constraintSet = ConstraintSet()
constraintSet.clone(container)
// ViewFlipper: fixed height, remove top constraint
constraintSet.clear(VIEW_FLIPPER_ID, ConstraintSet.TOP)
constraintSet.constrainHeight(VIEW_FLIPPER_ID, suggestionBarHeight)
constraintSet.connect(VIEW_FLIPPER_ID, ConstraintSet.BOTTOM, KEYBOARD_VIEW_ID, ConstraintSet.TOP)
constraintSet.applyTo(container)
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
**Status**: Fixed (ConstraintLayout approach)

The content pane previously didn't fill the entire space above the keyboard, leaving a visible gap.

**Root Cause**: LinearLayout weight-based sizing wasn't reliable across different IME window sizes.

**Solution**: Migrated to ConstraintLayout with explicit constraints:
- Keyboard pinned to bottom of parent
- ViewFlipper constrained to sit directly above keyboard (bottom→keyboard top)
- Max height constraint respects user's configured height percentage

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
- `ConstraintLayout`: Root container for precise constraint-based layout
- `ConstraintSet`: Programmatic constraint manipulation
- `ViewFlipper`: View swapping
- `HorizontalScrollView`: Scrollable suggestions

## Future Enhancements

1. **Inline Transformation**: Transform suggestion bar directly into emoji/clipboard picker instead of swapping views (simpler, more reliable).

2. **Dynamic Height Calculation**: Calculate exact available height at runtime using `parent.height - keyboard.height`.

3. **Animation**: Add smooth transition animations when swapping content.

---

**Created**: 2026-01-20
**Last Updated**: 2026-01-20
**Owner**: tribixbite
