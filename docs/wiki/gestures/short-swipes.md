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
| **Purpose** | Quick access to subkeys without long-press |
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
- **Customize**: Change subkey actions in Settings > Customization

> [!TIP]
> If short swipes accidentally trigger, increase the minimum distance setting.

## Difference from Long Press

| Action | Short Swipe | Long Press |
|--------|-------------|------------|
| **Speed** | Fast (instant) | Slow (wait for popup) |
| **Access** | 8 directions | Popup menu |
| **Characters** | Common ones | All variations |

## Settings

| Setting | Location | Description |
|---------|----------|-------------|
| **Min Distance** | Gesture Tuning | Minimum swipe length |
| **Max Distance** | Gesture Tuning | Maximum swipe length |
| **Enable Short Swipes** | Input Behavior | Toggle feature on/off |

## Related Features

- [Cursor Navigation](cursor-navigation.md) - Move cursor with gestures
- [Selection Delete](selection-delete.md) - Select text with backspace
- [Per-Key Actions](../customization/per-key-actions.md) - Customize subkeys

## Technical Details

See [Short Swipes Technical Specification](../specs/gestures/short-swipes-spec.md).
