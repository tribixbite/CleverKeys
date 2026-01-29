---
title: Special Characters
description: Type symbols, accents, and currency
category: Typing
difficulty: intermediate
related_spec: ../specs/typing/special-characters-spec.md
---

# Special Characters

Access symbols, accented letters, diacritics, and currency symbols through long-press menus and short swipe gestures.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | Type characters not on main keyboard |
| **Access** | Short swipe on keys or symbol keyboard |
| **Types** | Symbols, accents, currency, punctuation |

## Methods to Access Special Characters

### Method 1: Short Swipe (Primary)

Quick flick gestures on keys to access subkeys:

1. **Touch** the key
2. **Flick** in the direction of the subkey
3. The character types immediately

Each key has up to 8 subkey positions (N, NE, E, SE, S, SW, W, NW). The available subkeys depend on the keyboard layout (QWERTY, AZERTY, etc.) and are defined in the layout XML files.

Example on 'e' key (QWERTY):
- **Swipe NE**: Number (3)
- **Swipe S/SW/SE**: Accented variants (é, è, ê, etc.)

> [!NOTE]
> CleverKeys does not use long-press popups for special characters. Long-press triggers key repeat instead. All special characters are accessed via short swipes.

### Method 2: Symbol Keyboard

For extensive symbols:

1. Tap **?123** to switch to numbers/symbols
2. Tap **=\<** for more symbols
3. Find mathematical, currency, and special symbols

## Common Special Characters

### Punctuation

| Character | How to Type |
|-----------|-------------|
| **"** (curly quotes) | Subkey on " key |
| **—** (em dash) | Subkey on - key |
| **–** (en dash) | Subkey on - key |
| **…** (ellipsis) | Subkey on . key |
| **¿** | Subkey on ? key |
| **¡** | Subkey on ! key |

### Currency Symbols

| Character | Location |
|-----------|----------|
| **$** | Main symbol keyboard |
| **€** | Subkey on $ or symbol keyboard |
| **£** | Subkey on $ or symbol keyboard |
| **¥** | Subkey on $ or symbol keyboard |
| **₹** | Symbol keyboard |

### Mathematical Symbols

| Character | Location |
|-----------|----------|
| **×** | Subkey on * |
| **÷** | Subkey on / |
| **±** | Symbol keyboard |
| **≠** | Symbol keyboard |
| **≤ ≥** | Symbol keyboard |

## Accented Characters (Diacritics)

For languages requiring diacritics:

### Acute Accent (é)
- Short swipe on the base letter in the appropriate direction

### Grave Accent (è)
- Short swipe on the base letter in the appropriate direction

### Circumflex (ê, î, ô, û, â)

**Method 1 - Subkey (all layouts):**
- Short swipe on the base letter (e, i, o, u, a) in the direction of the circumflex variant

**Method 2 - Dead key (AZERTY):**
- Swipe north on **j** key to access circumflex dead key (^)
- Then type the base letter (e, i, o, u, a)
- The combined character appears (ê, î, ô, û, â)

### Umlaut/Diaeresis (ë)
- Short swipe on the base letter in the appropriate direction

### Tilde (ñ)
- Short swipe on n or a/o

### Cedilla (ç)
- Short swipe on c

## Tips and Tricks

- **Learn subkeys**: Check which characters are in which direction
- **Speed**: Short swipes are faster than long-press once learned
- **Customize**: You can change subkey assignments in Settings
- **Language packs**: Some accents only appear with language pack installed
- **Dead keys (AZERTY)**: Use j-north for ^, d-north for `, d-south for ´, then type the letter
- **All layouts supported**: Long-press works on QWERTY, AZERTY, Dvorak, etc.

> [!TIP]
> If you frequently use certain special characters, consider customizing subkey positions to place them in easier-to-reach directions.

## Settings

| Setting | Location | Description |
|---------|----------|-------------|
| **Long-Press Timeout** | Input Behavior | Time before key repeat starts (default: 600ms) |
| **Short Swipe Distance** | Gesture Tuning | Sensitivity for flick gestures |

## Related Features

- [Short Swipes](../gestures/short-swipes.md) - Gesture-based character access
- [Per-Key Actions](../customization/per-key-actions.md) - Customize subkeys
- [Emoji](emoji.md) - Emoji and emoticons

## Technical Details

See [Special Characters Technical Specification](../specs/typing/special-characters-spec.md).
