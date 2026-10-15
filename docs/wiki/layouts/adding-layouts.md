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

### Familiar-style layouts

If you are coming from another phone keyboard, three US-English QWERTY layouts copy the
arrangement of its default letter pane, so your thumbs find keys where they expect them.
Add one from **Settings > Layout Manager > Add Layout**.

| Layout | Number row | Letter hints | Bottom row (key widths) |
|--------|------------|--------------|-------------------------|
| **QWERTY (iOS-style)** | none | none | 123 (1.25) · emoji (1.25) · space (5) · return (2.5) |
| **QWERTY (Gboard-style)** | none | digits 1–0 on the top row | ?123 (1.5) · comma, emoji above (1) · space (5) · period (1) · enter (1.5) |
| **QWERTY (SwiftKey-style)** | 1–0 | one symbol per letter (`% ^ ~ \| [ ] < > { }`, `@ # & * - + = ( )`, `_ $ " ' : ; /`) | 123 (1) · emoji (1) · comma, mic above (1) · space (4.5) · period, `? !` (1) · enter (1.5) |

All three keep the half-key indent of the second row and have all 26 letters as keys, so
both swipe engines serve them as they serve standard QWERTY. Their own bottom row replaces
CleverKeys' standard one, so the standard row's keys moved to corners: Ctrl and Fn on the
123 key; settings, clipboard, GIF and the keyboard picker on the emoji key (Gboard-style: on
the comma); arrows, Home/End of document and dictation on enter (SwiftKey-style: dictation
on the comma); and the space bar still moves the cursor when you slide along it.

What they cannot copy:

- **Symbol pages.** 123 / ?123 opens CleverKeys' own number pane, not the other keyboard's
  symbol pages.
- **Press-and-hold menus.** Hints are swipe corners here: swipe toward the hint or, with
  the subkey popover on, hold the key. iOS-style accents come from your language's extra
  keys.
- **Keys drawn outside the keyboard**, such as the iPhone's globe and microphone buttons
  below the keys: these are the keyboard-picker and dictation corners instead.
- **Extra Keys.** Keys enabled under **Settings > Extra Keys** (for example cut/copy/paste
  on Z/X/C/V) are still added as on every layout; turn them off there for a cleaner board.

These layouts describe a key arrangement only. CleverKeys is not affiliated with or
endorsed by Apple, Google or Microsoft; iOS, Gboard and SwiftKey are their owners'
trademarks.

## Adding Language Layouts

### Via Language Pack Import

To add layouts for other languages:

1. Build or obtain a language pack (see [Language Packs](language-packs.md))
2. Go to **Settings > 🌐 Multi-Language**
3. Tap **Import Pack** and select the language pack `.zip`
4. The dictionary is installed and the language becomes selectable (its layout ships with the app)

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

A: Build a language pack using the provided Python scripts, then import via Settings > 🌐 Multi-Language > Import Pack. The pack provides the dictionary; the matching layout must exist in the app's layout list.

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
