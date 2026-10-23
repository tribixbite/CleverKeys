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
| **Methods** | Spacebar swipes, assigned key swipes, extra keys |
| **Layouts** | Between all enabled layouts |

## Switching Methods

### Method 1: Spacebar Swipes (Default)

The default bottom row puts layout switching on the spacebar:

1. **Swipe up on the spacebar** → Next layout (`switch_forward`)
2. **Swipe down on the spacebar** → Previous layout (`switch_backward`)

> [!NOTE]
> These two spacebar swipes are extra keys that are on by default, and they are hidden
> automatically while only one layout is enabled. If a custom layout defines its own bottom
> row, check that layout's subkeys instead.

### Method 2: Custom Key Assignment

Assign layout switching to any key via Per-Key Customization:

1. Go to **Settings > Activities > Short Swipe Calibration > Customize Per-Key Actions**
2. Select any key
3. Assign `switch_forward` or `switch_backward` to a swipe direction
4. Save your customization

### Method 3: Extra Keys

Add dedicated layout switching keys:

1. Go to **Settings > Activities > Configure Extra Keys**
2. Enable **Next Layout** (`switch_forward`) or **Previous Layout** (`switch_backward`)
3. The keys are placed in free subkey slots of the current layout; swipe to them to switch

## Managing Layouts

### Enable/Disable Layouts

Control which layouts appear in rotation:

1. Go to **Settings** and scroll to **Multi-Language** section
2. Configure your enabled languages/layouts
3. Only enabled layouts are included in switching

### Switching Keyboards (Input Methods)

Layout switching is instant and has no timing option. The **Immediate Keyboard Switching**
switch under **Settings > Input Behavior** is about something else: it makes the *Switch
Keyboard* key (`change_method`) jump straight to the next input method instead of opening
Android's keyboard picker.

## Tips and Tricks

- **Two languages**: If you only use 2 layouts, switching always toggles between them
- **Learn your subkeys**: On the default bottom row, swipe up or down on the spacebar
- **Custom placement**: Put switch keys where they're most convenient for you

> [!TIP]
> For bilingual typing, consider using Multi-Language mode instead of switching layouts. It suggests words from both languages simultaneously.

## Switching Language with the Layout

Each layout can have its own language (**Settings > Layout Manager**, the **Language** chip on
the layout). Switching to such a layout switches predictions, autocorrect and swipe typing to
that language only; switching to a layout left on **Follow Multi-Language settings** returns
to your Primary/Secondary languages. See
[Multi-Language: One Language per Layout](multi-language.md#one-language-per-layout).

## Switching Theme with the Layout

Each layout can also have its own theme (**Settings > Layout Manager**, the **Theme** chip).
Switching to that layout redraws the keyboard in its theme; switching to a layout left on
**Follow global theme** returns to the theme chosen in Theme Manager. The keyboard is only
redrawn when the theme actually changes. See [Themes: A Theme per Layout](../customization/themes.md#a-theme-per-layout).

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
| **Immediate Keyboard Switching** | Input Behavior section | Switch Keyboard key changes input method directly instead of showing the picker |

## Common Questions

### Q: How do I quickly switch between two languages?

A: If you only have 2 layouts enabled, any switch action toggles between them. Give each layout its language in Layout Manager and the language switches with the layout. Alternatively, enable Multi-Language mode for simultaneous bilingual typing on one layout.

### Q: Where are the layout switch keys?

A: By default, check the Shift key subkeys (swipe to see). You can also assign switch actions to any key via Per-Key Customization.

### Q: Can I have different layouts per app?

A: Per-app layouts are not currently supported. The active layout is global across all apps.

## Related Features

- [Adding Layouts](adding-layouts.md) - Install new layouts
- [Multi-Language](multi-language.md) - Type in multiple languages simultaneously
- [Per-Key Actions](../customization/per-key-actions.md) - Customize switch key placement
