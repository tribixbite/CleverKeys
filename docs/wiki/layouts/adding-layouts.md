---
title: Adding Layouts
description: Install and manage keyboard layouts
category: Layouts
difficulty: beginner
---

# Adding Layouts

Install new keyboard layouts to support different languages and keyboard styles.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Add new keyboard layouts |
| **Access** | Layouts are bundled or added via language packs |
| **Options** | QWERTY variants, language-specific layouts |

## Built-in Layouts

CleverKeys includes several pre-installed layouts:

| Layout | Description |
|--------|-------------|
| **QWERTY** | Standard US English layout |
| **AZERTY** | French keyboard layout |
| **QWERTZ** | German keyboard layout |
| **Dvorak** | Alternative English layout |
| **Colemak** | Ergonomic alternative layout |

## Adding Language Layouts

### Via Language Pack Import

To add layouts for other languages:

1. Build or obtain a language pack (see [Language Packs](language-packs.md))
2. Go to **Settings > Activities > Backup & Restore**
3. Tap **Import** and select the language pack file
4. The layout and dictionary are installed together

See [Language Packs](language-packs.md) for details.

### Bundled Languages

Some languages come pre-bundled:

| Language | Layout | Dictionary |
|----------|--------|------------|
| English | QWERTY | Included |
| Spanish | QWERTY (ES) | Included |
| French | AZERTY, QWERTY (FR) | Included |
| German | QWERTZ | Included |
| Portuguese | QWERTY (PT) | Included |
| Italian | QWERTY (IT) | Included |

## Managing Layouts

### View Available Layouts

The keyboard uses the layout associated with your configured language:

1. Go to **Settings** and scroll to **Multi-Language** section
2. Your primary language determines the default layout
3. Secondary language can use the same or different layout

### Enabling Multiple Layouts

To switch between layouts:

1. Configure languages in **Multi-Language** section
2. Use `switch_forward` / `switch_backward` commands to cycle
3. See [Switching Layouts](switching-layouts.md) for details

## Layout Types

### Language Layouts

Optimized for specific languages:

| Type | Examples |
|------|----------|
| **Latin** | QWERTY, AZERTY, QWERTZ |
| **Extended** | Nordic, Spanish, Portuguese accents |
| **Alternative** | Dvorak, Colemak |

### Specialized Layouts

For specific use cases:

| Layout | Use Case |
|--------|----------|
| **Number Pad** | Numeric input |
| **Emoji** | Emoji keyboard |

## Tips and Tricks

- **Start simple**: Enable 1-2 layouts maximum for easy switching
- **Match language**: Use layouts designed for your language's characters
- **Learn subkeys**: Each layout has character subkeys for accents and symbols
- **Multi-Language**: For bilingual typing, consider Multi-Language mode instead of switching

> [!TIP]
> You don't need separate layouts for bilingual typing. Enable Multi-Language mode to get predictions from both languages on one layout.

## Limitations

| Feature | Status |
|---------|--------|
| **Custom layout creation** | Not currently available |
| **Layout import** | Via language packs only |
| **Per-app layouts** | Not supported |

## Common Questions

### Q: How do I get layouts for my language?

A: Build a language pack using the provided Python scripts, then import via Settings > Activities > Backup & Restore. It includes both the layout and dictionary.

### Q: Can I create custom layouts?

A: Custom layout creation is not currently available in the app. Layouts are provided via language packs.

### Q: Why can't I find my language's layout?

A: Check if a language pack is available. If not, request it via GitHub issues or create one using the build scripts (see [Language Packs](language-packs.md)).

### Q: Do I need different layouts for different languages?

A: Not always. Many Latin-alphabet languages work on QWERTY with subkeys for accented characters. Multi-Language mode provides predictions for multiple languages on one layout.

## Related Features

- [Switching Layouts](switching-layouts.md) - Change between layouts
- [Language Packs](language-packs.md) - Download language support
- [Multi-Language](multi-language.md) - Type in multiple languages
- [Backup & Restore](../troubleshooting/backup-restore.md) - Save your configuration
