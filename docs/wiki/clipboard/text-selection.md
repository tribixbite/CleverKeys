---
title: Text Selection
description: Select text efficiently with gestures
category: Clipboard
difficulty: intermediate
related_spec: ../specs/clipboard/text-selection-spec.md
---

# Text Selection

Select text efficiently using CleverKeys gestures, including selection-delete mode and trackpoint navigation.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Select text for copy/cut/delete |
| **Methods** | Selection-delete, trackpoint, shortcuts |
| **Features** | Word/line/all selection |

## Selection Methods

### Method 1: Selection-Delete Mode

Select text while preparing to delete:

1. **Short swipe** on backspace (any direction)
2. **Hold** (don't release)
3. Wait for haptic feedback
4. **Move finger** like joystick:
   - Left/Right: Select characters
   - Up/Down: Select lines
5. **Release** to delete selected text

See [Selection Delete](../gestures/selection-delete.md) for details.

### Method 2: TrackPoint Mode

For precise cursor positioning:

1. **Long-press the nav key** (between spacebar and enter)
2. Wait for haptic activation
3. **Move finger** to position cursor
4. Hold **Shift** while moving to select
5. Release to exit mode

See [TrackPoint Mode](../gestures/trackpoint-mode.md) for details.

### Method 3: Keyboard Shortcuts

Use modifier keys:

| Shortcut | Action |
|----------|--------|
| **Ctrl+A** | Select all |
| **Shift+Arrow** | Extend selection |
| **Ctrl+Shift+Arrow** | Select word |

## Quick Selection Actions

### Select All

1. Open command palette (long-press settings)
2. Select **Select All**
3. Or use Ctrl+A shortcut

### Select Word

1. **Double-tap** on a word (in the text field)
2. Word is selected
3. Or use Selection-Delete with minimal movement

### Select Line

1. In Selection-Delete mode, swipe **up** or **down**
2. Entire line gets selected
3. Continue for multiple lines

## Working with Selected Text

Once text is selected:

### Copy Selection

| Method | How |
|--------|-----|
| **Ctrl+C** | If Ctrl key enabled |
| **Command palette** | Long-press settings > Copy |
| **App menu** | Use app's copy function |

### Cut Selection

| Method | How |
|--------|-----|
| **Ctrl+X** | If Ctrl key enabled |
| **Command palette** | Long-press settings > Cut |
| **App menu** | Use app's cut function |

### Delete Selection

| Method | How |
|--------|-----|
| **Backspace** | Press backspace key |
| **Selection-Delete** | Release finger |
| **Type anything** | Replace with new text |

## Selection Visual Feedback

When text is selected:

```
┌─────────────────────────────────────┐
│ Text before [selected text] after   │
│              ↑ Blue highlight       │
│                                     │
│ ┌───────────────────────────────┐   │
│ │    Selection handles (○)      │   │
│ └───────────────────────────────┘   │
└─────────────────────────────────────┘
```

- Selected text is highlighted
- Selection handles appear at edges
- Keyboard shows selection-aware actions

## Tips and Tricks

- **Practice Selection-Delete**: Most efficient method once learned
- **Modifier keys**: Add Ctrl as extra key for shortcuts
- **Word selection**: Double-tap in text is often faster
- **Line selection**: Vertical swipe in Selection-Delete mode

> [!TIP]
> Selection-Delete mode selects AND deletes in one gesture - great for editing.

## Selection Settings

| Setting | Location | Description |
|---------|----------|-------------|
| **Selection Haptics** | Haptics | Feedback during selection |
| **Vertical Threshold** | Gestures | Line selection sensitivity |
| **Selection Speed** | Gestures | Character selection speed |

## Common Selection Patterns

### Deleting a Word

1. Short swipe backspace, hold
2. Swipe right to select word
3. Release to delete

### Copying a Paragraph

1. Enter TrackPoint mode
2. Position at paragraph start
3. Hold Shift, move to end
4. Open command palette, Copy

### Replacing Text

1. Select text to replace
2. Start typing
3. Selection is replaced

## Common Questions

### Q: How do I select without deleting?

A: Use TrackPoint mode with Shift, or use the app's native selection.

### Q: Why is selection slow/fast?

A: Adjust selection speed in Settings > Gestures.

### Q: Can I select across multiple lines?

A: Yes, use vertical movement in Selection-Delete or TrackPoint mode.

## Related Features

- [Selection Delete](../gestures/selection-delete.md) - Selection-delete gesture
- [TrackPoint Mode](../gestures/trackpoint-mode.md) - Cursor navigation
- [Clipboard History](clipboard-history.md) - Manage copied text

## Technical Details

See [Text Selection Technical Specification](../specs/clipboard/text-selection-spec.md).
