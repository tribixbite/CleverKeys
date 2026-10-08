---
title: Subkey Popover - Technical Specification
description: Hold-then-select popover of a key's eight subkey slots, with assign and edit screens
user_guide: /wiki/gestures/subkey-popover/
status: implemented
version: v2.0.0 development
---

# Subkey Popover Technical Specification

## Overview

Holding an eligible key past the long-press timeout, without moving, opens a 3×3 grid
centred on the finger: the eight subkey slots around a neutral centre that shows the held
key. Releasing on a slot emits it through the same path as a short swipe; releasing on an
empty slot opens an assign screen; resting on an assigned slot for 3 s opens an edit screen.
The internal engineering note is
[subkey-popover.md](https://github.com/tribixbite/CleverKeys/blob/main/docs/specs/subkey-popover.md).

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| SubkeyPopoverGeometry | `popover/SubkeyPopoverGeometry.kt` | Pure: slot hit-test (`slotAt`) and grid clamping inside the view (`gridCentre`) |
| SubkeyPopoverSlots | `popover/SubkeyPopoverSlots.kt` | Pure: resolves the eight slots from custom mappings and modified layout subkeys |
| SubkeyPopoverState | `popover/SubkeyPopoverState.kt` | Per-pointer state; `DWELL_EDIT_MS = 3_000L`, `DWELL_RING_START_MS = 800L` |
| SubkeyPopoverRenderer | `popover/SubkeyPopoverRenderer.kt` | Draws the open popover last in `Keyboard2View.onDraw` |
| SubkeyAssignActivity | `popover/SubkeyAssignActivity.kt` | Translucent dialog activity for assign/edit |
| Pointers | `Pointers.kt` (`handleLongPress`, `isSubkeyPopoverKey`, `tryOpenSubkeyPopover`) | Opens, tracks and resolves the popover |
| SubkeyPopoverMigration | `Config.kt` | Seeds the setting OFF once for upgraded installs (config v5) |
| GestureTuningSection | `ui/settings/sections/GestureTuningSection.kt` | Toggle and the two neutral-zone sliders |

## Behaviour

1. **Open.** In `handleLongPress`, after the TrackPoint and backspace selection-delete
   branches, the popover is tried when `subkey_popover_enabled` is set and the finger moved
   no more than the hold-stillness threshold:

   ```kotlin
   // Hold-then-select subkey popover: replaces key repeat on the keys it applies to.
   if (snap.subkey_popover_enabled && movementDist <= HOLD_STILLNESS_PX && tryOpenSubkeyPopover(ptr, snap)) {
       return
   }
   ```

2. **Eligible keys** (`isSubkeyPopoverKey`): a Char or String value that is not blank and
   not special, not a modifier (latchable or latched), not backspace, no navigation
   subkeys, and not remapped by `KeyModifier.modify_long_press`. A key with no mappable key
   code and only empty slots keeps its old hold behaviour.
3. **Slots.** Each slot is the user's custom mapping if any, else the layout subkey with the
   current modifiers applied, else empty. A mapping to the `removed` placeholder counts as
   empty.
4. **Selection.** Displacement from the grid centre, inside the neutral rectangle (width and
   height as % of the key), selects nothing. Outside it, the 45° sector of the displacement,
   normalised by the cell size, selects a slot. Selection waits until the finger has moved
   (15 % of a cell), so an unmoved release never emits anything.
5. **Release.** Neutral: nothing. Default subkey: emitted as a short swipe would emit it.
   Custom mapping: executed. Empty slot: assign screen.
6. **Dwell.** From 0.8 s on an assigned slot a progress border runs around the whole
   popover; at 3 s the popover closes and the edit screen opens; the following release does
   nothing.
7. **Key repeat.** On eligible keys the popover replaces key repeat while enabled.

## Assign / Edit Screens

`SubkeyAssignActivity` reuses `CommandPaletteDialog`, the shared
`ShortSwipeAssignment.apply(...)` and `ShortSwipeCustomizationManager`, so popover slots
and short-swipe mappings are one store.

| Screen | Contents |
|--------|----------|
| Assign, plain empty slot | Opens the palette directly |
| Assign, layout subkey removed | Slot details, **Choose an action**, **Restore default** |
| Edit | Slot details; **Edit** (custom mappings except raw key events; palette opens in that action's editor via `initialMapping`), **Reassign**, **Restore default** (custom mapping over a layout subkey), **Remove** (error colour) |

**Remove** deletes a custom mapping over an empty slot; over a layout subkey it stores the
`removed` placeholder.

## Configuration

| Setting | Key | Default | Range | Source |
|---------|-----|---------|-------|--------|
| **Subkey popover on hold** | `subkey_popover_enabled` | `true` (upgrades seeded `false`) | Boolean | `Config.kt` `Defaults.SUBKEY_POPOVER_ENABLED` |
| **Neutral zone width** | `subkey_popover_neutral_width` | 60 | 20–150 % of key width | `Defaults.SUBKEY_POPOVER_NEUTRAL_WIDTH` |
| **Neutral zone height** | `subkey_popover_neutral_height` | 60 | 20–150 % of key height | `Defaults.SUBKEY_POPOVER_NEUTRAL_HEIGHT` |

`SubkeyPopoverMigration.seedsOff` returns true for a non-fresh install whose saved config
version is below 5 and that has no explicit value for the key (a value restored from a
backup is kept). Settings are read through `ConfigSnapshot`, captured at touch-down.

## Test Coverage

| Suite | File |
|-------|------|
| Pure JVM | `src/test/kotlin/tribixbite/cleverkeys/popover/SubkeyPopoverGeometryTest.kt` |
| Pure JVM | `src/test/kotlin/tribixbite/cleverkeys/popover/SubkeyPopoverSlotsTest.kt` |
| Pure JVM | `src/test/kotlin/tribixbite/cleverkeys/SubkeyPopoverMigrationTest.kt` |
| Mock | `src/test/kotlin/tribixbite/cleverkeys/PointersSubkeyPopoverTest.kt` |

## Related Specifications

- [Short Swipes](./short-swipes-spec.md) - The shared subkey emission path
- [Per-Key Actions](../customization/per-key-actions-spec.md) - Mapping storage and the command palette
