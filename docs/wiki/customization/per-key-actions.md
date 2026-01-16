---
title: Per-Key Actions
description: Customize what each swipe direction does
category: Customization
difficulty: advanced
related_spec: ../specs/customization/per-key-actions-spec.md
---

# Per-Key Actions

Customize what happens when you swipe in each of the 8 directions from any key. Assign characters, actions, or macros to each swipe direction.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Customize subkey actions per direction |
| **Access** | Settings > Customization > Per-Key Actions |
| **Directions** | 8 directions (N, NE, E, SE, S, SW, W, NW) |

## Understanding Subkeys

Each key has up to 8 subkey positions:

```
    NW   N   NE
      \  |  /
   W -- KEY -- E
      /  |  \
    SW   S   SE
```

When you short swipe in a direction, the corresponding subkey is activated.

## How to Customize

### Step 1: Open Customization

1. Open CleverKeys Settings (gear icon)
2. Navigate to **Customization**
3. Select **Per-Key Actions** or **Subkey Editor**

### Step 2: Select a Key

1. The keyboard layout is displayed
2. **Tap the key** you want to customize
3. A detail panel opens showing all 8 directions

### Step 3: Edit a Direction

1. Tap the direction you want to change
2. Choose from:
   - **Character**: Type a letter, symbol, or emoji
   - **Action**: Select from built-in actions
   - **Remove**: Clear the subkey

### Step 4: Save Changes

Changes are saved automatically. Tap **Done** to return.

## Available Actions

| Action | Description |
|--------|-------------|
| **Characters** | Any Unicode character |
| **Delete Word** | Delete previous word |
| **Cursor Left/Right** | Move cursor |
| **Home/End** | Jump to line start/end |
| **Tab** | Insert tab character |
| **Escape** | Send escape key |
| **Undo/Redo** | Undo or redo action |
| **Copy/Cut/Paste** | Clipboard operations |
| **Select All** | Select all text |

## Common Customizations

### Adding Frequently Used Symbols

Place symbols you use often in easy-to-reach positions:

| Key | Direction | Suggestion |
|-----|-----------|------------|
| **e** | South | @ (email) |
| **s** | East | $ (currency) |
| **p** | North | % (percent) |

### Programming Shortcuts

For developers:

| Key | Direction | Action |
|-----|-----------|--------|
| **Shift** | NW | Escape |
| **Shift** | SE | Tab |
| **a** | NW | Home |
| **a** | SW | End |

### Navigation Optimization

Put navigation actions where you can reach them:

| Key | Direction | Action |
|-----|-----------|--------|
| **Backspace** | West | Delete Word |
| **Space** | Subkeys | Cursor movement |

## Tips and Tricks

- **Start small**: Customize a few keys first, learn them, then add more
- **Muscle memory**: Keep frequently used actions in consistent positions
- **Backup**: Export your customizations before making major changes
- **Per-layout**: Customizations can be layout-specific

> [!TIP]
> Consider your typing patterns. If you frequently type certain symbols, put them in easy-to-swipe positions.

## Resetting Customizations

To restore defaults for a key:

1. Open Per-Key Actions
2. Select the key
3. Tap **Reset to Default**

To reset all customizations:

1. Go to Settings > Reset
2. Choose **Reset Customizations Only**

## Import/Export

Save and share your customizations:

1. Go to Settings > **Profiles**
2. Select **Export Profile**
3. Choose to include customizations

See [Profiles](../troubleshooting/backup-restore.md) for details.

## Settings

| Setting | Location | Description |
|---------|----------|-------------|
| **Per-Key Editor** | Customization | Visual subkey editor |
| **Reset Key** | Per-key detail | Reset single key |
| **Reset All** | Settings > Reset | Reset all customizations |

## Related Features

- [Short Swipes](../gestures/short-swipes.md) - How to trigger subkeys
- [Profiles](../troubleshooting/backup-restore.md) - Save customizations
- [Extra Keys](extra-keys.md) - Add custom keys to bottom row

## Technical Details

See [Per-Key Actions Technical Specification](../specs/customization/per-key-actions-spec.md).
