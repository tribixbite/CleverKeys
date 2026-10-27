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

### Familiar Looks

A look changes more than colours: key shapes, gaps, the colour of function keys and Enter,
key shadows and the suggestion bar. Each has a light and a dark version that follows your
system's dark-mode setting.

| Look | What it copies |
|------|----------------|
| **iOS-style** | iPhone (iOS 26): one key colour for every key, rounded keys with wide gaps, a blue Enter in Go / Search / Send fields |
| **Gboard-style** | Gboard with Material You: white letter keys, tinted shift, delete, ?123, comma, period and Enter; on Android 12 and later its colours follow your wallpaper like Gboard's |
| **SwiftKey-style** | SwiftKey's Fluent look: almost identical letter and function keys with a soft shadow |
| **Samsung-style** | Samsung Keyboard (One UI 8): darker function keys, a blue shift glyph when locked, blue auto-replace suggestion |
| **iOS 18-style** | the previous iPhone keyboard: grey function keys and a thin shadow under every key |

While a look is in use, the key gaps, corner radius and borders it defines replace your
**Appearance** settings for those items (including a custom border); everything else stays
yours, and the label size stays relative to your label-size setting. A look also draws only
the corner hints the real keyboard shows (Gboard-style: the top-row digits; the others: none)
and Gboard-style draws ?123 and Enter as pills; the swipe gestures behind hidden hints still
work. Turn on **Appearance > Show gesture hints on familiar looks** to draw every hint; they are
drawn in the look's own hint style (smaller, in its hint colour, clear of rounded corners). Your settings are not
changed: pick another theme and they apply again.

When you press a letter, a look shows the pressed character the way its keyboard does: a tall
balloon above the key on iOS-style, a rounded bubble on Gboard-style, a key-wide column on
SwiftKey-style and a plate on Samsung-style. Turn it off with **Appearance > Key-press preview
on familiar looks**. The long-press popup of alternative characters also takes the look's
colours. On the top row the preview is drawn lower, because a keyboard cannot draw above its
own window. The four familiar-style layouts (see
[Adding Layouts](../layouts/adding-layouts.md#familiar-style-layouts)) use their look
automatically.

Looks are drawn with CleverKeys' own keys and the phone's standard font; no fonts, icons or
artwork from those keyboards are included. CleverKeys is not affiliated with or endorsed by
Apple, Google, Microsoft or Samsung.

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
The familiar-style layouts come with their look already chosen, so with them enabled a swipe on
the space bar turns the keyboard from Gboard-style to SwiftKey-style to iOS-style; set their
**Theme** chip to **Follow global theme** to keep your own theme on them.

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
| **Keyboard Look** (optional) | Look colors (Enter and action keys, function-key labels, Caps Lock label, key shadow, suggestion separator, pop-ups), separate Light Mode and Dark Mode colors, key shape (gaps, corner radius, outline, shadow, label size and weight), gesture hints, key-press preview and key roles (pill-shaped keys, emoji key like a letter, function characters, wallpaper colors). Anything left on **Use keyboard setting** / **Not set** follows your settings. |

### Creating Custom Theme

1. Open **Theme Manager**
2. Scroll to **Custom Theme** or **DIY** section
3. Tap each color element to customize
4. Use the color picker or enter hex codes
5. The preview at the top of the editor is the real keyboard, scaled down and read-only: every color change re-renders it immediately. It shows Shift active, a locked modifier (Ctrl on the default layout), modifier and action keys at rest, sub-labels, the suggestion bar and a sample swipe trail, using your own opacity, border and swipe-trail settings

### From a layout, and back

A layout file can carry its own theme (see the [Layout XML Reference](../layouts/layout-xml-reference.md)).
In **Layout Manager**, **Save theme as custom theme** on such a layout adds it here under Custom,
named after the layout, with everything it defined; open it in the creator to change it. The
share button on a custom theme or a familiar look, **Export as layout XML**, saves it as a
`<theme>` element you can paste into any layout.

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
