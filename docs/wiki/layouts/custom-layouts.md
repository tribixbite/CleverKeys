---
title: Custom Layouts
description: Create keyboard layouts using XML format
category: Layouts
difficulty: advanced
---

# Custom Layouts

Create custom keyboard layouts using XML syntax.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Define custom keyboard layouts |
| **Access** | Settings > Layouts preference |
| **Format** | XML-based layout definition |

## How Custom Layouts Work

CleverKeys supports custom layout definitions in XML format. This is an advanced feature requiring knowledge of the layout XML syntax.

### Layout XML Syntax

Custom layouts use the same XML format as the bundled layouts. A layout is defined by rows of keys, each key having:

- **Primary character** (`c="a"`)
- **Subkeys** by direction (`ne="1"` for northeast)
- **Width** (`width="2.0"` for wider keys)
- **Shift variants** (`shift="0.5"` for indent)

### Example Layout Row

```xml
<row>
  <key c="q" ne="1"/>
  <key c="w" ne="2"/>
  <key c="e" ne="3"/>
  <key c="r" ne="4"/>
  <key c="t" ne="5"/>
</row>
```

## Accessing Custom Layouts

1. Open Settings
2. Find the **Layouts** preference
3. Use the text entry dialog to specify layout XML

The dialog includes:
- Multi-line text entry with line numbers
- Real-time validation for syntax errors
- Option to remove custom layout

## Layout Structure

A complete layout consists of:

| Element | Description |
|---------|-------------|
| **Rows** | Horizontal groups of keys |
| **Keys** | Individual key definitions |
| **Modkeys** | Modified key layers |

### Key Properties

| Property | Example | Description |
|----------|---------|-------------|
| `c` | `c="a"` | Primary character |
| `ne`, `nw`, `se`, `sw`, `n`, `s`, `e`, `w` | `ne="1"` | Subkey directions |
| `width` | `width="1.5"` | Key width multiplier |
| `shift` | `shift="0.5"` | Left margin/indent |

## For Developers

Custom layouts use the same XML format as `src/main/layouts/*.xml` in the source code. Review existing layouts for syntax examples.

## Limitations

| Feature | Status |
|---------|--------|
| **Visual editor** | Not available |
| **Layout import** | Via text entry only |
| **Sharing** | Copy/paste XML text |

## Common Questions

### Q: Can I create layouts with a visual editor?

A: No, layouts are defined using XML text entry. Review existing layout files for examples.

### Q: Where can I find layout examples?

A: Check the `src/main/layouts/` directory in the source code for XML layout examples.

### Q: How do I share a custom layout?

A: Copy the XML text and share it. Recipients paste it into their custom layout dialog.

## Related Features

- [Adding Layouts](adding-layouts.md) - Built-in layout options
- [Per-Key Actions](../customization/per-key-actions.md) - Customize subkeys
- [Language Packs](language-packs.md) - Download language support
