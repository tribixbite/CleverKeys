---
title: Switching Layouts
description: Change between keyboard layouts
category: Layouts
difficulty: beginner
---

# Switching Layouts

Switch between installed keyboard layouts using key actions or swipe gestures.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Change active keyboard layout |
| **Methods** | Assigned key swipes, Shift key swipe |
| **Layouts** | Between all enabled layouts |

## Switching Methods

### Method 1: Shift Key Swipes (Default)

On the default layout, the Shift key has layout switching:

1. **Swipe right on Shift** → Next layout (`switch_forward`)
2. **Swipe left on Shift** → Previous layout (`switch_backward`)

> [!NOTE]
> The exact swipe directions depend on your layout. Check the subkeys on your Shift key.

### Method 2: Custom Key Assignment

Assign layout switching to any key via Per-Key Customization:

1. Go to **Settings > Per-Key Customization** (in Activities section)
2. Select any key
3. Assign `switch_forward` or `switch_backward` to a swipe direction
4. Save your customization

### Method 3: Extra Keys

Add dedicated layout switching keys:

1. Go to **Settings > Extra Keys** (in Activities section)
2. Add `switch_forward` or `switch_backward` to your extra keys row
3. Tap the key to switch layouts

## Layout Indicator

When you switch layouts, the current layout name appears briefly on the spacebar.

```
┌─────────────────────────────────────┐
│  ┌───┐ ┌───┐ ┌───┐ ┌───┐ ┌───┐     │
│  │ Q │ │ W │ │ E │ │ R │ │ T │     │
│  └───┘ └───┘ └───┘ └───┘ └───┘     │
│           ...                       │
│  ┌─────────────────────────────────┐│
│  │         QWERTY EN               ││ ← Layout shown on spacebar
│  └─────────────────────────────────┘│
└─────────────────────────────────────┘
```

## Managing Layouts

### Enable/Disable Layouts

Control which layouts appear in rotation:

1. Go to **Settings** and scroll to **Multi-Language** section
2. Configure your enabled languages/layouts
3. Only enabled layouts are included in switching

### Immediate vs Delayed Switching

| Setting | Behavior |
|---------|----------|
| **Immediate** | Layout switches instantly on key action |
| **Delayed** | Layout changes after releasing the key |

Configure in Settings under the layout/input behavior options.

## Tips and Tricks

- **Two languages**: If you only use 2 layouts, switching always toggles between them
- **Learn your subkeys**: Check Shift key subkeys for default switch actions
- **Custom placement**: Put switch keys where they're most convenient for you
- **Visual feedback**: Watch the spacebar for current layout confirmation

> [!TIP]
> For bilingual typing, consider using Multi-Language mode instead of switching layouts. It suggests words from both languages simultaneously.

## Multi-Language Alternative

Instead of switching layouts, you can type in multiple languages on one layout:

1. Go to **Settings > Multi-Language** section
2. Enable a **Secondary Language**
3. Both language dictionaries work simultaneously
4. See [Multi-Language](multi-language.md) for details

## Available Actions

| Action | Description |
|--------|-------------|
| `switch_forward` | Switch to next layout |
| `switch_backward` | Switch to previous layout |
| `switch_text` | Switch to text keyboard |

## Settings

| Setting | Location | Description |
|---------|----------|-------------|
| **Primary Language** | Multi-Language section | Main language |
| **Secondary Language** | Multi-Language section | Additional dictionary |
| **Switch Input Immediate** | Input settings | Instant vs delayed switch |

## Common Questions

### Q: How do I quickly switch between two languages?

A: If you only have 2 layouts enabled, any switch action toggles between them. Alternatively, enable Multi-Language mode for simultaneous bilingual typing.

### Q: Where are the layout switch keys?

A: By default, check the Shift key subkeys (swipe to see). You can also assign switch actions to any key via Per-Key Customization.

### Q: Can I have different layouts per app?

A: Per-app layouts are not currently supported. The active layout is global across all apps.

## Related Features

- [Adding Layouts](adding-layouts.md) - Install new layouts
- [Multi-Language](multi-language.md) - Type in multiple languages simultaneously
- [Per-Key Actions](../customization/per-key-actions.md) - Customize switch key placement
