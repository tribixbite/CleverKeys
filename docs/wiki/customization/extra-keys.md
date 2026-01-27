---
title: Extra Keys
description: Add custom keys to the bottom row
category: Customization
difficulty: intermediate
related_spec: ../specs/customization/extra-keys-spec.md
---

# Extra Keys

Add custom keys to the bottom row of your keyboard for quick access to frequently used actions, characters, or modifiers.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Add custom keys to bottom row |
| **Access** | Settings > Activities > Extra Keys |
| **Position** | Left or right of spacebar |

## What Are Extra Keys?

Extra keys appear on the bottom row alongside the spacebar. They provide quick access to:

- Modifier keys (Ctrl, Alt, Meta)
- Action keys (Tab, Escape)
- Custom characters
- Navigation keys

## How to Add Extra Keys

### Step 1: Open Settings

1. Open CleverKeys Settings (gear icon)
2. Navigate to **Activities** section
3. Tap **Extra Keys**

### Step 2: Select Position

Choose where to add keys:

- **Left of spacebar**: Keys appear on the left
- **Right of spacebar**: Keys appear on the right

### Step 3: Choose Keys

Available extra keys:

| Key | Description |
|-----|-------------|
| **Ctrl** | Control modifier |
| **Alt** | Alt modifier |
| **Meta** | Meta/Windows key |
| **Tab** | Tab key |
| **Escape** | Escape key |
| **Arrows** | Navigation arrows |
| **Fn** | Function key |

### Step 4: Arrange Order

Drag keys to reorder them. Keys closest to spacebar are easiest to reach.

## Use Cases

### Terminal/SSH Users

Add Ctrl, Alt, Tab, and Escape for terminal commands:

```
[Ctrl] [Alt] [Tab] [Space] [Esc] [Arrows]
```

### Developers

Add keys for code editing shortcuts:

```
[Tab] [Ctrl] [Space] [Fn]
```

### Power Users

Full modifier access:

```
[Esc] [Ctrl] [Alt] [Meta] [Space] [Tab]
```

## Tips and Tricks

- **Fewer is better**: Too many extra keys shrink the spacebar
- **Modifiers first**: Put modifiers you hold (Ctrl, Alt) on edges
- **Action keys center**: Put tap keys (Tab, Esc) near spacebar
- **Test reach**: Ensure you can reach keys comfortably

> [!TIP]
> Start with just Ctrl and Tab. Add more only if you need them.

## Extra Keys vs Subkeys

| Feature | Extra Keys | Subkeys |
|---------|------------|---------|
| **Access** | Direct tap | Swipe gesture |
| **Position** | Bottom row | On any key |
| **Visibility** | Always visible | Hidden until swipe |
| **Best for** | Modifiers | Characters |

## Keyboard Layout Impact

Extra keys affect bottom row layout:

| Extra Keys | Spacebar Width |
|------------|----------------|
| **0** | Maximum |
| **1-2** | Slightly reduced |
| **3-4** | Moderately reduced |
| **5+** | Significantly reduced |

## Settings

| Setting | Location | Description |
|---------|----------|-------------|
| **Extra Keys** | Activities section | Add/remove keys |
| **Key Order** | Extra Keys | Drag to reorder |
| **Key Size** | Extra Keys | Auto-sized |

## Common Questions

### Q: Can I add custom characters as extra keys?
A: Currently, extra keys are limited to predefined actions. For custom characters, use subkey customization.

### Q: Why is my spacebar so small?
A: Too many extra keys. Remove some to restore spacebar size.

### Q: Do extra keys have subkeys?
A: Most extra keys have subkeys. Long-press or short swipe to access.

## Related Features

- [Per-Key Actions](per-key-actions.md) - Customize subkeys
- [Short Swipes](../gestures/short-swipes.md) - Access extra key subkeys
- [Profiles](../troubleshooting/backup-restore.md) - Save extra key configuration

## Technical Details

See [Extra Keys Technical Specification](../specs/customization/extra-keys-spec.md).
