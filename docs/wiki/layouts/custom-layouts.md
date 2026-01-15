---
title: Custom Layouts
description: Create and edit your own keyboard layouts
category: Layouts
difficulty: advanced
---

# Custom Layouts

Design your own keyboard layouts from scratch or modify existing layouts to match your preferences.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Create personalized layouts |
| **Access** | Settings > Layouts > Custom Layouts |
| **Features** | Visual editor, templates, import/export |

## Creating a Custom Layout

### Step 1: Open Custom Layouts

1. Open **Settings > Layouts**
2. Tap **Custom Layouts**
3. Tap **Create New Layout**

### Step 2: Choose Starting Point

| Option | Description |
|--------|-------------|
| **Blank** | Start from scratch |
| **Template** | QWERTY, AZERTY, etc. |
| **Clone** | Copy existing layout |

### Step 3: Design Layout

The visual editor shows:

```
┌─────────────────────────────────────────┐
│ Layout Name: [My Custom Layout    ]     │
├─────────────────────────────────────────┤
│ ┌───┐ ┌───┐ ┌───┐ ┌───┐ ┌───┐ ...      │
│ │ Q │ │ W │ │ E │ │ R │ │ T │           │
│ └───┘ └───┘ └───┘ └───┘ └───┘           │
│   ↑ Tap to edit key                     │
├─────────────────────────────────────────┤
│ [Add Row] [Remove Row] [Key Width]      │
└─────────────────────────────────────────┘
```

### Step 4: Edit Individual Keys

Tap any key to edit:

| Setting | Description |
|---------|-------------|
| **Primary** | Main character |
| **Shifted** | Shift character |
| **Subkeys** | 8-direction subkeys |
| **Width** | Key width multiplier |

### Step 5: Save and Test

1. Tap **Save Layout**
2. Tap **Test Layout** to preview
3. Make adjustments as needed
4. Tap **Done** when satisfied

## Layout Elements

### Row Configuration

| Row | Typical Contents |
|-----|------------------|
| **Row 1** | Number row (optional) |
| **Row 2** | QWERTY top row |
| **Row 3** | Home row (ASDF...) |
| **Row 4** | Bottom row (ZXCV...) |
| **Row 5** | Space row |

### Key Types

| Type | Examples | Properties |
|------|----------|------------|
| **Letter** | A, B, C | Has shift variant |
| **Number** | 1, 2, 3 | Symbol on shift |
| **Symbol** | @, #, $ | May have shift variant |
| **Action** | Shift, Backspace | Special behavior |
| **Space** | Spacebar | Wide key |

### Key Width

| Width | Use Case |
|-------|----------|
| **0.5x** | Narrow keys |
| **1.0x** | Standard letters |
| **1.5x** | Shift, Tab |
| **2.0x** | Enter key |
| **5.0x** | Spacebar |

## Advanced Features

### Subkey Configuration

For each key, define 8 subkeys:

```
    [1]   [2]   [3]
      \   |   /
 [4] -- [Key] -- [5]
      /   |   \
    [6]   [7]   [8]
```

### Key Actions

Assign special actions to keys:

| Action | Description |
|--------|-------------|
| **Switch Layout** | Change to specific layout |
| **Emoji** | Open emoji picker |
| **Symbols** | Open symbol keyboard |
| **Voice** | Start voice input |
| **Macro** | Execute key sequence |

## Import and Export

### Export Layout

1. Open your custom layout
2. Tap **Export**
3. Choose format (JSON or XML)
4. Share or save file

### Import Layout

1. Go to **Custom Layouts**
2. Tap **Import**
3. Select file to import
4. Layout appears in list

### Share with Others

1. Export your layout
2. Share the file
3. Recipients import via CleverKeys

## Tips and Tricks

- **Start with template**: Easier than blank canvas
- **Test frequently**: Use the test feature often
- **Small changes**: Make incremental adjustments
- **Backup first**: Export before major changes

> [!TIP]
> Clone a layout you like and modify just the keys you want to change.

## Examples

### Programming Layout

Optimize for coding with symbols accessible:

- Add `{` `}` `[` `]` as subkeys
- Place `=` and `;` in easy positions
- Add Tab key in convenient location

### One-Handed Layout

Compact layout for single-hand typing:

- All keys within thumb reach
- Larger key targets
- Essential keys only

## Settings

| Setting | Location | Description |
|---------|----------|-------------|
| **Custom Layouts** | Layouts | Manage custom layouts |
| **Edit Layout** | Custom Layouts | Open editor |
| **Export/Import** | Custom Layouts | Share layouts |

## Common Questions

### Q: Can I make a layout with fewer rows?

A: Yes, you can remove rows for a more compact layout. Minimum is 2 rows.

### Q: What happens if I delete a custom layout I'm using?

A: CleverKeys switches to the next available layout automatically.

### Q: Can I share layouts between devices?

A: Yes, export on one device and import on another.

## Related Features

- [Adding Layouts](adding-layouts.md) - Add custom layouts to keyboard
- [Per-Key Actions](../customization/per-key-actions.md) - Customize subkeys
- [Profiles](profiles.md) - Save layout configurations
