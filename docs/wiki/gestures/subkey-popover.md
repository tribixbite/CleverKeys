---
title: Subkey Popover
description: Hold a key to see its subkeys, slide to one and let go to type it
category: Gestures
difficulty: beginner
related_spec: ../specs/gestures/subkey-popover-spec.md
---

# Subkey Popover

Hold a character key and a small grid of its subkeys opens around your finger. Slide to
the one you want and let go to type it. This is the same set of characters and actions a
[short swipe](short-swipes.md) gives you, but you can see them before you choose.

## Quick Summary

| What | Description |
|------|-------------|
| **Purpose** | See and pick a key's subkeys without memorising swipe directions |
| **Gesture** | Hold a key still, slide to a slot, let go |
| **Setting** | Settings > Gesture Tuning > Hold for Subkeys > **Subkey popover on hold** |
| **Default** | On for new installs; off if you upgraded from an earlier version |

## How It Works

1. **Hold** a character key without moving. After the long-press delay (Settings >
   Input Behavior > **Long Press Timeout**, 600 ms by default) a 3×3 grid opens, centred
   on your finger.
2. The middle cell shows the key you are holding. The eight cells around it show what a
   short swipe in that direction does on this key: your own assignment if you made one,
   otherwise the layout's subkey (with Shift applied where the layout defines it). A cell
   with nothing in it shows a faint **+**.
3. **Slide** toward a cell. The selected cell grows and you feel a light tick each time
   the selection changes.
4. **Let go**:
   - on a subkey: it is typed or run, exactly as the short swipe would do it;
   - in the middle (the neutral zone): nothing happens, so this is how you cancel;
   - on an empty cell: the popover closes and the assign screen opens (see below).

The grid stays inside the keyboard. Near an edge it is shifted so every cell can be
reached, which can leave your finger over a cell when it opens; nothing is selected until
you move, so letting go without moving never types anything.

## Assigning and Editing Slots

**Empty slot.** Letting go on an empty cell opens the command palette for that key and
direction. Pick a command, custom text, a dynamic template, an intent or a timestamp; it
is saved like any [per-key action](../customization/per-key-actions.md). If the cell is
empty because you removed the layout's subkey, you first get a short screen with
**Choose an action** and **Restore default**.

**Edit an assigned slot.** Rest your finger on an assigned cell. After 0.8 seconds a
progress line starts running around the popover; at 3 seconds the popover closes and the
edit screen opens for that slot. Move away before then if you only meant to pause. The
edit screen shows what the slot does now and offers:

| Button | What it does |
|--------|--------------|
| **Edit** | Opens your custom action in its own editor, already filled in (custom text, template, intent, timestamp pattern or command label). Not shown for layout subkeys or raw key events. |
| **Reassign** | Opens the full command palette to choose a different action |
| **Restore default** | Removes your custom action so the layout's own subkey shows again. Only shown when your action covers a layout subkey. |
| **Remove** | Clears the slot. A layout subkey is hidden rather than deleted; letting go on that empty cell later offers **Restore default**. |

Changes made here are the same mappings you see in Settings > Activities > **Customize
Per-Key Actions**, and the same ones short swipes use.

## Which Keys Open It

The popover opens on keys that type text (letters, digits, punctuation). Keys that already
do something when held keep that behaviour:

| Key | Holding it still |
|-----|------------------|
| Shift, Ctrl, Alt, Fn and other modifiers | Locks the modifier |
| Backspace | Repeats, or selects with a short swipe then hold ([Selection-Delete](selection-delete.md)) |
| Space and other keys with arrow subkeys | [TrackPoint mode](trackpoint-mode.md) |
| Keys with their own long-press action (for example voice input or keyboard switch) | That action |

While the popover is on, it **replaces key repeat** on the keys it applies to: holding a
letter no longer repeats it. Backspace still repeats.

With TalkBack's explore-by-touch on, holding a key keeps its previous behaviour.

## Settings

All three are in **Settings > Gesture Tuning**, under **Hold for Subkeys**:

| Setting | Default | Range | Description |
|---------|---------|-------|-------------|
| **Subkey popover on hold** | On (new installs) | On/Off | Turns the popover on or off |
| **Neutral zone width** | 60% | 20–150% of the key width | Width of the middle area where letting go does nothing |
| **Neutral zone height** | 60% | 20–150% of the key height | Height of that area |

The two sliders appear only while the popover is on. A larger neutral zone makes it
easier to cancel; a smaller one lets a shorter slide reach a slot.

> [!NOTE]
> If you upgraded from a version without the popover, it starts **off** so holding a letter
> keeps doing what it did before. Turn it on in Gesture Tuning. A choice restored from a
> backup is kept.

## Tips

- Use the popover to learn a key's subkeys, then switch to short swipes for speed. Both
  read the same assignments.
- If you open the popover by accident while pausing mid-word, let go in the middle.
- To change how long you must hold before it opens, adjust **Long Press Timeout** in
  Input Behavior.

## Related Features

- [Short Swipes](short-swipes.md) - The same subkeys, by flicking
- [Per-Key Actions](../customization/per-key-actions.md) - Assign actions to subkey slots
- [Command Palette](../customization/command-palette.md) - The action list the assign screen opens
- [Input Behavior](../settings/input-behavior.md) - Long press timeout and key repeat

## Technical Details

See [Subkey Popover Technical Specification](../specs/gestures/subkey-popover-spec.md).
