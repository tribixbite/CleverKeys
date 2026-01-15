---
title: Themes
description: Change keyboard appearance and colors
category: Customization
difficulty: beginner
related_spec: ../specs/customization/themes-spec.md
---

# Themes

Customize the appearance of your keyboard with different color themes, from dark modes to vibrant colors.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Change keyboard colors and appearance |
| **Access** | Settings > Theme |
| **Options** | Built-in themes + custom colors |

## Built-in Themes

CleverKeys includes several pre-designed themes:

| Theme | Description |
|-------|-------------|
| **CleverKeys Dark** | Default dark purple theme |
| **Midnight** | Pure black with subtle accents |
| **Ocean** | Deep blue tones |
| **Forest** | Green nature-inspired |
| **Sunset** | Warm orange and red |
| **Snow** | Light theme for bright environments |
| **System** | Follows Android dark/light mode |

## How to Change Theme

### Step 1: Open Theme Settings

1. Open CleverKeys Settings (gear icon)
2. Tap **Theme** or **Appearance**

### Step 2: Preview Themes

1. Scroll through available themes
2. Each theme shows a preview
3. Tap to select

### Step 3: Apply

The theme applies immediately. No restart required.

## Custom Colors

For fine-tuned control:

### Background Color

1. Go to Theme settings
2. Tap **Background Color**
3. Use the color picker or enter hex code
4. Tap **Apply**

### Key Colors

| Element | Description |
|---------|-------------|
| **Key Background** | Main key color |
| **Key Text** | Letter color |
| **Key Border** | Optional border |
| **Accent** | Highlights and active states |

### Prediction Bar

| Element | Description |
|---------|-------------|
| **Bar Background** | Suggestion bar color |
| **Suggestion Text** | Prediction text color |
| **Active Suggestion** | Selected prediction |

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

- **OLED screens**: Use pure black themes to save battery
- **Readability**: Ensure good contrast between keys and text
- **Eye strain**: Dark themes are easier on eyes in low light
- **Consistency**: Match your system theme for visual harmony

> [!TIP]
> The "System" theme automatically switches between light and dark based on your Android settings.

## Opacity Settings

Adjust transparency:

| Setting | Range | Description |
|---------|-------|-------------|
| **Key Opacity** | 0-100% | Transparency of key backgrounds |
| **Background Opacity** | 0-100% | Overall keyboard transparency |

## Settings

| Setting | Location | Description |
|---------|----------|-------------|
| **Theme** | Appearance | Select theme |
| **Custom Colors** | Theme > Custom | Individual colors |
| **Key Opacity** | Appearance | Transparency |
| **Border Style** | Appearance | Key borders |

## Common Questions

### Q: Can I create my own theme?
A: Yes, use Custom Colors to define each element's color.

### Q: How do I share my theme?
A: Export your profile (Settings > Profiles > Export) which includes theme settings.

### Q: Why does my keyboard look different in some apps?
A: Some apps force their own keyboard styling. CleverKeys tries to maintain your theme, but app overrides may occur.

## Related Features

- [Appearance Settings](../settings/appearance.md) - Height, margins
- [Profiles](../layouts/profiles.md) - Save theme with settings
- [Accessibility](../settings/accessibility.md) - High contrast options

## Technical Details

See [Themes Technical Specification](../specs/customization/themes-spec.md).
