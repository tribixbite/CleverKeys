---
title: Short Swipes
description: Quick flick gestures to access subkeys
category: Gestures
difficulty: intermediate
featured: true
related_spec: ../specs/gestures/short-swipes-spec.md
---

# Short Swipes

Short swipes let you quickly access additional characters by flicking in 8 directions from any key. This is faster than long-pressing for common characters.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Quick access to subkeys via directional flick |
| **Gesture** | Quick flick from key in any of 8 directions |
| **Directions** | N, NE, E, SE, S, SW, W, NW |

## How It Works

Each key has up to 8 subkeys arranged around it:

```
    NW   N   NE
      \  |  /
   W -- KEY -- E
      /  |  \
    SW   S   SE
```

A quick flick in any direction types that subkey character.

## How to Use

### Step 1: Touch the Key

Touch the key you want to access subkeys from (don't tap - hold briefly).

### Step 2: Flick in a Direction

While still touching, quickly slide your finger in the direction of the subkey you want.

### Step 3: Release

Lift your finger. The subkey character appears.

### Example: Typing Numbers

On QWERTY layout, numbers are on the NORTHEAST (up-right) direction:

| Key | Flick NE | Result |
|-----|----------|--------|
| Q | ↗ | 1 |
| W | ↗ | 2 |
| E | ↗ | 3 |
| R | ↗ | 4 |
| ... | ... | ... |

> [!TIP]
> Think "start from the SW corner of the key" to access the NE subkey.

## Common Subkey Layouts

### Letters (Top Row)
- **Northeast**: Numbers (1-0)
- **Northwest**: Symbols (~, @, #, $, etc.)
- **Southeast**: Escape (on Q)

### Backspace
- **West**: Delete entire word
- **Short swipe + hold**: Selection delete mode

> [!NOTE]
> Subkey assignments vary by layout. Check Per-Key Customization to see and modify your layout's subkeys.

## Calibrating Short Swipes

Adjust sensitivity in Settings:

1. Go to Settings > Activities > **Short Swipe Calibration**
2. Adjust **Minimum Distance** (shorter = more sensitive)
3. Practice in the test area

| Setting | Effect |
|---------|--------|
| **Min Distance** | How far to swipe before triggering |
| **Max Distance** | Beyond this becomes a long swipe |

## Tips and Tricks

- **Practice direction**: North is straight up, not diagonal
- **Speed matters**: Quick flicks work best
- **Visual feedback**: The trail shows your swipe direction
- **Customize**: Change subkey actions in Settings > Activities > Per-Key Customization

> [!TIP]
> If short swipes accidentally trigger, increase the minimum distance setting.

## Difference from Holding a Key

| | Short Swipe | Hold (Subkey Popover on) | Hold (Subkey Popover off) |
|---|---|---|---|
| **Speed** | Instant | Wait for the long-press timeout (600 ms by default), then slide | Wait for the long-press timeout |
| **Result** | Types the subkey in that direction | Shows all 8 subkeys; slide to one and let go | Key repeat, if enabled for that key |
| **Assign/edit** | Settings | Let go on an empty slot, or rest 3 s on an assigned one | Settings |

With the [Subkey Popover](subkey-popover.md) on (the default for new installs), holding a
character key shows the same subkeys a short swipe would type, so you can see them before
you choose. It replaces key repeat on those keys. With the popover off, holding a letter
repeats it only when **Key Repeat Enabled** is on and **Backspace Only Repeat** is off
(Settings > Input Behavior); by default only Backspace and navigation keys repeat.

## Settings

| Setting | Location | Description |
|---------|----------|-------------|
| **Min Distance** | Gesture Tuning | Minimum swipe length |
| **Max Distance** | Gesture Tuning | Maximum swipe length |
| **Enable Short Gestures** | Gesture Tuning | Toggle feature on/off |
| **Subkey popover on hold** | Gesture Tuning | Show the subkeys when you hold a key |

## Terminal App Support

When using CleverKeys in terminal emulators (Termux, ConnectBot, JuiceSSH, Termius and
others), a **Paste** short swipe reads the current clipboard text and types it directly,
because terminals usually do not handle Android's paste action. Copy and Cut behave as in
other apps.

Common terminal apps are detected automatically. If yours is not, add its package name
under **Settings > Advanced > Custom terminal packages**; see
[Advanced Settings](../settings/advanced.md).

## Related Features

- [Cursor Navigation](cursor-navigation.md) - Move cursor with gestures
- [Selection Delete](selection-delete.md) - Select text with backspace
- [Per-Key Actions](../customization/per-key-actions.md) - Customize subkeys
- [Subkey Popover](subkey-popover.md) - Hold a key to see and pick its subkeys

## Technical Details

See [Short Swipes Technical Specification](../specs/gestures/short-swipes-spec.md).
