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
| **Access** | Settings > Activities > Per-Key Customization |
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
2. Navigate to **Activities** section
3. Tap **Per-Key Customization**

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
| **Copy/Cut/Paste** | Clipboard operations (terminal-aware — see below) |
| **Select All** | Select all text |
| **Timestamp** | Insert current date/time using a SimpleDateFormat pattern (see below) |

### Timestamp Action

The **Timestamp** action type inserts the current date and/or time, formatted via a [SimpleDateFormat](https://developer.android.com/reference/java/text/SimpleDateFormat) pattern. The pattern is the action value; at swipe time the current `Date` is formatted with the system default locale and the result is committed to the text field.

| Pattern | Example output |
|---------|----------------|
| `yyyy-MM-dd HH:mm` | `2026-05-22 14:30` |
| `yyyy-MM-dd` | `2026-05-22` |
| `HH:mm` | `14:30` |
| `yyyy-MM-dd'T'HH:mm:ssXXX` | `2026-05-22T14:30:00+00:00` (ISO 8601) |

Any valid SimpleDateFormat pattern works. The Command Palette dialog also offers a few preset chips covering the common date/time formats.

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

### Pinned Clipboard Actions

Insert pinned clipboard entries directly via swipe gestures, without opening the clipboard panel. Five positional commands are available:

| Command | Description |
|---------|-------------|
| `paste_pinned_1` | Insert 1st pinned clipboard entry |
| `paste_pinned_2` | Insert 2nd pinned clipboard entry |
| `paste_pinned_3` | Insert 3rd pinned clipboard entry |
| `paste_pinned_4` | Insert 4th pinned clipboard entry |
| `paste_pinned_5` | Insert 5th pinned clipboard entry |

Pinned entries are ordered **most-recently-pinned first**. If you request an index that exceeds the number of pinned items (e.g., `paste_pinned_3` with only 2 pins), a toast notification tells you how many are available instead of inserting anything.

To set up:

1. Go to **Settings > Activities > Per-Key Customization**
2. Select a key and direction
3. Choose **Command** as the action type
4. Search for "paste_pinned" or browse the **Clipboard** category
5. Select the desired slot (1-5)

> [!TIP]
> Pin your most-used text snippets (email signature, address, code boilerplate) and bind them to swipe directions for instant insertion.

### Terminal-Aware Actions

Some actions adapt their behavior when typing in terminal apps like Termux:

| Action | Standard Apps | Terminal Apps |
|--------|---------------|---------------|
| **Paste** | Android paste API | Ctrl+V key event |
| **Copy** | Android copy API | Standard |
| **Cut** | Android cut API | Standard |

This is automatic — the same paste customization works in both regular apps and terminals.

### Custom Text Input

When using the **Text Input** action type, you can enter up to **4096 characters** per action. This is useful for long templates, code snippets, or multi-line text blocks.

The text input field includes a **paste button** (icon in the trailing position) for pasting from the system clipboard. This is provided because Compose dialogs do not always receive paste commands from the IME reliably.

## Tips and Tricks

- **Start small**: Customize a few keys first, learn them, then add more
- **Muscle memory**: Keep frequently used actions in consistent positions
- **Backup**: Export your customizations before making major changes
- **Per-layout**: Customizations can be layout-specific

> [!TIP]
> Consider your typing patterns. If you frequently type certain symbols, put them in easy-to-swipe positions.

## Resetting Customizations

To restore defaults:

1. Go to **Settings > Activities > Backup & Restore**
2. Use the restore function to reset to default configuration

## Settings

| Setting | Location | Description |
|---------|----------|-------------|
| **Per-Key Customization** | Activities section | Visual subkey editor |
| **Backup & Restore** | Activities section | Save/restore customizations |

## Related Features

- [Short Swipes](../gestures/short-swipes.md) - How to trigger subkeys
- [Backup & Restore](../troubleshooting/backup-restore.md) - Save customizations
- [Extra Keys](extra-keys.md) - Add custom keys to bottom row

## Technical Details

See [Per-Key Actions Technical Specification](../specs/customization/per-key-actions-spec.md).
