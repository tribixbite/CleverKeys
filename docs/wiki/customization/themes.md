---
title: Themes
description: Change keyboard appearance and colors
category: Customization
difficulty: beginner
---

# Themes

Customize the appearance of your keyboard with different color themes, from dark modes to vibrant colors.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Change keyboard colors and appearance |
| **Access** | Settings > Theme Manager (in Activities section) |
| **Options** | 35+ built-in themes + custom DIY colors |

> [!NOTE]
> Keyboard themes only affect the keyboard itself. The settings screens follow your **system** light/dark mode setting.

## Built-in Themes

CleverKeys includes many pre-designed themes:

### Dark Themes

| Theme | Description |
|-------|-------------|
| **CleverKeys Dark** | Default - deep purple with silver accents |
| **Dark** | Classic dark gray |
| **Black** | Pure black for OLED screens |
| **Alt Black** | Alternative black styling |
| **ePaper Black** | High contrast e-ink dark |
| **Cobalt** | Blue-tinted dark theme |
| **Pine** | Dark green nature theme |
| **Rose Pine** | Muted rose and pine colors |

### Light Themes

| Theme | Description |
|-------|-------------|
| **CleverKeys Light** | Silver keys with purple accents |
| **Light** | Classic light gray |
| **White** | Pure white background |
| **ePaper** | High contrast e-ink style |
| **Everforest Light** | Soft green nature theme |
| **Desert** | Warm sandy tones |
| **Jungle** | Tropical teal theme |
| **Jewel** | Vibrant colored theme |

### System-Following Themes (Android 12+)

| Theme | Description |
|-------|-------------|
| **Monet** | Material You - follows system colors |
| **Monet Light** | Material You - forced light |
| **Monet Dark** | Material You - forced dark |

> [!NOTE]
> Monet themes require Android 12+ for dynamic color extraction. On older devices, these themes fall back to default colors.

## How to Change Theme

### Step 1: Open Theme Manager

1. Open CleverKeys Settings (gear icon)
2. Scroll to **Activities** section
3. Tap **Theme Manager**

### Step 2: Select Theme

1. Scroll through available themes
2. Each theme shows its name and preview colors
3. Tap to select

### Step 3: Apply

The theme applies immediately. No restart required.

## A Theme per Layout

A layout can carry its own theme, so switching layouts switches the look too — for example
a dark theme for your English layout and a light one for Persian.

1. Open **Settings > Layout Manager**
2. Tap the **Theme** chip on a layout
3. Pick a theme (search by name), or **Follow global theme**

While that layout is active the keyboard uses the chosen theme; switching back to a layout
that follows the global theme restores the theme selected here in Theme Manager. Choosing a
theme for a layout never changes your global theme. If a layout's theme is deleted, or a
restored backup names a DIY theme that is not on this device, the layout uses the global theme
and Layout Manager shows the missing theme. Custom layouts can also declare a default theme in
their XML (`theme="rosepine"`); see [Custom Layouts](../layouts/custom-layouts.md#default-theme-theme).

## DIY Theme Creator

CleverKeys includes a powerful theme creator for custom colors. Every field in the editor is rendered by the keyboard:

### Accessible Colors

| Element | Description |
|---------|-------------|
| **Key Colors** | Key Default, Key Activated, Key Locked, Key Modifier, Key Special backgrounds |
| **Label Colors** | Key Label, Sub Label, Secondary Label |
| **Border Colors** | Key Border, Border Activated (the pressed key's outline) |
| **Swipe Trail** | Swipe trail color and style |
| **Ripple Effect** | Press feedback color on suggestion-bar chips and buttons |
| **Suggestion Bar** | Suggestion Text, Suggestion Background, High Confidence accent |
| **Background** | Keyboard Background, Keyboard Surface (elevated panels) |

### Creating Custom Theme

1. Open **Theme Manager**
2. Scroll to **Custom Theme** or **DIY** section
3. Tap each color element to customize
4. Use the color picker or enter hex codes
5. The preview at the top of the editor is the real keyboard, scaled down and read-only: every color change re-renders it immediately. It shows Shift active, a locked modifier (Ctrl on the default layout), modifier and action keys at rest, sub-labels, the suggestion bar and a sample swipe trail, using your own opacity, border and swipe-trail settings

> [!NOTE]
> Editing the theme you're currently using updates the live keyboard immediately — no restart needed. Saved edits to a non-active custom theme take effect when you select it.

## Theme Elements

What each theme affects:

```
┌─────────────────────────────────┐
│   Prediction Bar Background     │ ← Bar color
│   [word1] [word2] [word3]       │ ← Suggestion colors
├─────────────────────────────────┤
│ ┌───┐ ┌───┐ ┌───┐ ┌───┐ ┌───┐ │
│ │ Q │ │ W │ │ E │ │ R │ │ T │ │ ← Key bg + text
│ └───┘ └───┘ └───┘ └───┘ └───┘ │
│         Keyboard Background     │ ← Main background
└─────────────────────────────────┘
```

## Tips and Tricks

- **OLED screens**: Use Black or ePaper Black themes to save battery
- **Readability**: Ensure good contrast between keys and text
- **Eye strain**: Dark themes are easier on eyes in low light
- **Material You**: Use Monet theme on Android 12+ for system color matching

> [!TIP]
> The **Monet** theme on Android 12+ automatically uses your wallpaper's accent colors.

## Opacity Settings

Adjust transparency in the **Appearance** section:

| Setting | Range | Description |
|---------|-------|-------------|
| **Keyboard Opacity** | 0-100% | Overall keyboard transparency |
| **Key Opacity** | 0-100% | Transparency of key backgrounds |
| **Suggestion Bar Opacity** | 0-100% | Prediction bar transparency |

## Settings Locations

| Setting | Location | Description |
|---------|----------|-------------|
| **Theme Selection** | Activities > Theme Manager | Select theme |
| **Keyboard Opacity** | Appearance section | Overall transparency |
| **Key Opacity** | Appearance section | Key transparency |
| **Border Style** | Appearance section | Key borders |

## Common Questions

### Q: Can I create my own theme?

A: Yes! Use the DIY Theme Creator in Theme Manager to customize every color.

### Q: How do I backup my custom theme?

A: Export your settings, or make a full backup, in **Settings > Backup & Restore**. Since 2.0.0
both include your DIY themes and which theme is selected; importing shows each theme and lets you
Skip, Merge (keep your other themes) or Replace. Older backups carry the selection only, so a
restored DIY theme was missing and the keyboard fell back to CleverKeys Dark.

### Q: Why does Monet theme look different than expected?

A: Monet uses Android's Material You color extraction. Results depend on your wallpaper and Android version. Requires Android 12+.

### Q: The keyboard background is transparent!

A: Increase **Keyboard Opacity** in the Appearance section (Settings > Appearance).

## Related Features

- [Appearance Settings](../settings/appearance.md) - Height, margins, opacity
- [Backup & Restore](../troubleshooting/backup-restore.md) - Save settings including theme
- [Accessibility](../settings/accessibility.md) - Contrast options
