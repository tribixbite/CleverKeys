---
title: Switching Layouts
description: Change between keyboard layouts
category: Layouts
difficulty: beginner
related_spec: ../specs/layouts/switching-layouts-spec.md
---

# Switching Layouts

Quickly switch between your installed keyboard layouts using gestures, buttons, or shortcuts.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Change active keyboard layout |
| **Methods** | Gesture, button, shortcut |
| **Layouts** | Between all installed layouts |

## Switching Methods

### Method 1: Globe Key

The globe/language key on the bottom row:

1. **Tap** to cycle to next layout
2. **Long-press** to show layout picker
3. **Swipe** for quick access to specific layouts

### Method 2: Swipe Gesture

Use the spacebar gesture:

1. **Swipe left** on spacebar → Previous layout
2. **Swipe right** on spacebar → Next layout

### Method 3: Layout Picker

Access the full layout list:

1. **Long-press** the globe key
2. Layout picker overlay appears
3. **Tap** any layout to switch

### Method 4: From Command Palette

1. Open command palette (long-press settings)
2. Type "layout" or "switch"
3. Select **Switch Layout**
4. Choose target layout

## Quick Switch vs Full Switch

| Type | Behavior |
|------|----------|
| **Quick Switch** | Tap globe - cycles through active layouts |
| **Full Switch** | Long-press globe - shows all layouts |

## Layout Indicator

When you switch layouts, a brief indicator shows:

```
┌─────────────────────────────────────┐
│         ┌───────────────┐           │
│         │   QWERTY      │           │ ← Layout indicator
│         │   English     │           │
│         └───────────────┘           │
│                                     │
│  ┌───┐ ┌───┐ ┌───┐ ┌───┐ ┌───┐     │
│  │ Q │ │ W │ │ E │ │ R │ │ T │     │
└─────────────────────────────────────┘
```

## Active Layout Order

Layouts cycle in the order set in Settings:

1. Go to **Settings > Layouts**
2. Drag layouts to reorder
3. Layouts at top cycle first

## Per-App Layouts

CleverKeys can remember layout preferences per app:

1. Enable **Settings > Layouts > Per-App Layout**
2. Switch to desired layout in each app
3. CleverKeys remembers your choice

## Tips and Tricks

- **Pin favorites**: Move frequently used layouts to top
- **Hide unused**: Disable layouts you rarely use
- **Learn gestures**: Spacebar swipe is fastest
- **Visual cue**: Watch for layout indicator when switching

> [!TIP]
> Double-tap the globe key to toggle between your two most recently used layouts.

## Globe Key Behavior

| Action | Result |
|--------|--------|
| **Tap** | Next layout |
| **Double-tap** | Toggle last two layouts |
| **Long-press** | Show layout picker |
| **Swipe up** | Show layout picker |

## Spacebar Gestures

| Gesture | Result |
|---------|--------|
| **Swipe left** | Previous layout |
| **Swipe right** | Next layout |
| **Long swipe** | Skip to first/last layout |

## Settings

| Setting | Location | Description |
|---------|----------|-------------|
| **Layout Order** | Layouts | Set cycle order |
| **Show Globe** | Layouts | Show/hide globe key |
| **Per-App Layout** | Layouts | Remember per app |
| **Show Indicator** | Layouts | Layout change indicator |

## Common Questions

### Q: How do I quickly switch between two languages?

A: Double-tap the globe key to toggle between your two most recently used layouts.

### Q: Can I disable certain layouts from quick switch?

A: Yes, in Settings > Layouts, uncheck "Include in Quick Switch" for layouts you want to access only via the full picker.

### Q: Why doesn't the globe key appear?

A: You need at least 2 layouts installed. Go to Settings > Layouts > Add Layout.

## Related Features

- [Adding Layouts](adding-layouts.md) - Install new layouts
- [Multi-Language](multi-language.md) - Use multiple languages
- [Profiles](profiles.md) - Save layout configurations

## Technical Details

See [Switching Layouts Technical Specification](../specs/layouts/switching-layouts-spec.md).
