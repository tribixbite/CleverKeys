---
title: Extra Keys
description: Enable additional keyboard actions, modifiers and characters
category: Customization
difficulty: intermediate
related_spec: ../specs/customization/extra-keys-spec.md
---

# Extra Keys

Enable additional keyboard actions, modifiers and characters. Their positions follow your layout's available slots and each key's preferred position; they are not restricted to the bottom row.

## How to Configure

1. Open **CleverKeys Settings**.
2. Expand **Activities** and tap **Configure Extra Keys**. The same screen is available from **Input Behavior**.
3. Search by a key's displayed title or identifier, or scroll through the categories.
4. Toggle a key's checkbox to enable or disable it. Changes save immediately.
5. Return to a text field to check its placement on your selected layout.

Search, the enabled-key summary, **Reset to Defaults** and key rows scroll together, so rows remain reachable in landscape and on shorter screens. Search text survives rotation. **Reset to Defaults** restores every predefined key to its default enabled state.

## Available Categories

| Category | Examples |
|----------|----------|
| Layout Switching | Next Layout, Previous Layout, Greek/Math |
| System | Alt, Meta, Compose, voice typing, clipboard panel, Autofill |
| Navigation | Tab, Escape, Page Up/Down, Home, End |
| Editing | Copy, private copy, paste, cut, selection, undo/redo, delete word, Clear system clipboard |
| Formatting | Superscript, subscript |
| Accents | Accent transformations |
| Symbols | Currency and special symbols |
| Special Characters | Joiners and nonbreaking spaces |
| Combining Characters | Combining marks |
| Function Keys | Function-key placeholders, Menu, Scroll Lock |

The screen currently lists 110 predefined keys. The enabled count describes saved choices; placement depends on the layout and available slots. Next/Previous Layout keys are omitted from the keyboard when only one layout is enabled.

**Autofill** appears under System. **Clear system clipboard** appears under Editing and is disabled by default; it clears Android's current clip rather than deleting CleverKeys' saved history.

## Placement and Customization

The screen enables predefined keys; it has no left/right position selector. Keys already present on a layout are not added again. Missing keys use preferred neighboring or row/column/direction positions, then try an available slot elsewhere.

Use [Per-Key Actions](per-key-actions.md) for custom text, commands and direction assignments. Test the keyboard after changing extra keys because available slots vary across layouts.

## Related Features

- [Short Swipes](../gestures/short-swipes.md) - Use keys' directional actions
- [Profiles](../troubleshooting/backup-restore.md) - Save keyboard configuration

## Technical Details

See [Extra Keys Technical Specification](../specs/customization/extra-keys-spec.md).

### Explicit apostrophe suffix keys (development build)

The editing group includes **Append 's** (`append_possessive`) and **Append apostrophe**
(`append_apostrophe`). They use verified word ownership and suffix-only undo; see
[per-key actions](per-key-actions.md#explicit-apostrophe-suffix-commands-development-build).
The current catalog contains 110 keys; enabled counts depend on your selections.
