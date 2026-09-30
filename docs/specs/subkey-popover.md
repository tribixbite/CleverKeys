# Subkey popover (hold-then-select)

**Status:** implemented 2026-09-30 (device feel check pending). **Owner request:** "the more traditional hold-then-select
subkey popover found in mainstream keyboards".

## Behaviour

1. **Open.** Holding a character key past `longpress_timeout` without moving (the same 15 px
   stillness rule that separates a hold from a swipe) opens a popover. It is a 3×3 grid centred
   on the finger (clamped inside the keyboard, see Architecture): the 8 subkey slots around a
   neutral centre that shows the held key. Each cell is one key wide and one row tall, so the
   finger reaches a slot by moving about one key.
2. **Slots.** Each slot shows what a short swipe in that direction would do:
   - the user's custom mapping if there is one;
   - otherwise the layout's subkey, with the current modifiers applied (shift gives `!` over `1` where the layout says so);
   - otherwise nothing ("empty", drawn as a faint `+`).

   A slot mapped to the `removed` placeholder counts as empty.
3. **Select.** Finger displacement from the grid centre picks the slot:
   - inside the neutral rectangle (width and height are percentages of the key's width and height, set by the user): no slot;
   - outside it: the 45° sector of the displacement, with dx and dy normalised by the cell size so the sector borders run along the grid diagonals.

   The selected slot scales up further (from 1.0 to 1.3, spring) and a light haptic tick marks each change.
4. **Release.**
   - In the neutral zone: nothing happens.
   - On a default subkey: it is emitted exactly as a short swipe would emit it (shift capitalisation of word labels, dead keys latch).
   - On a custom mapping: it is executed.
   - On an empty slot: the popover closes and the **assign** screen opens for (key, direction).
5. **Dwell to edit.** Resting on an assigned slot (default or custom) for 3 s closes the popover and opens the **edit** screen for that slot; the release that follows does nothing. A progress ring fills around the slot from 0.8 s so the user can see it coming and move away.
6. **Scope.** Only keys whose main value is a Char or String and not special. Keys that already
   own the hold keep their behaviour:
   - modifiers latch or lock;
   - backspace repeats or selects;
   - keys with navigation subkeys (space) enter TrackPoint;
   - `modify_long_press` remaps (voice/IME switch).

   On eligible keys the popover **replaces key repeat** while it is enabled; repeating a letter by holding is what it gives up.

## Assign / edit screens

- `SubkeyAssignActivity` is a translucent dialog activity started from the IME with a new task and excluded from recents. Finishing it returns to the app that was being typed in.
- It **reuses** the per-key customisation pieces:
  - `CommandPaletteDialog` picks the action;
  - the shared `ShortSwipeAssignment.apply(...)` saves it and shows the confirmation toast; `ShortSwipeCustomizationActivity` now calls the same helper;
  - `ShortSwipeCustomizationManager` stores the mapping.
- **Assign:** opens the palette directly.
- **Edit:** shows the slot's current action and offers:
  - *Reassign* (the palette);
  - *Remove*: a custom mapping over an empty slot is deleted; a default subkey, or a custom mapping over one, is replaced by the `removed` placeholder;
  - *Restore default*: only when a custom mapping hides a default subkey.
- Removing uses the existing `removed` command, so no storage change.
- An activity rather than an in-IME dialog, because the palette has a search field. The IME cannot type into its own window, and nothing hosts Compose in the IME window today.

## Settings

| key | type | default | notes |
|---|---|---|---|
| `subkey_popover_enabled` | Boolean | `true` | Existing installs are seeded `false` by the v5 migration (`SubkeyPopoverMigration`), unless they have already chosen. |
| `subkey_popover_neutral_width` | Int % of key width | 60 | 20–150 |
| `subkey_popover_neutral_height` | Int % of key height | 60 | 20–150 |

They live under Gesture tuning, beside the other hold and short-swipe settings. Every setting is
read through `ConfigSnapshot`, captured at touch-down (ARC-072).

## Architecture

- `SubkeyPopoverGeometry` (pure): `slotAt(dx, dy, cellW, cellH, neutralW, neutralH): SwipeDirection?`.
- `SubkeyPopoverSlots` (pure): the eight `Slot`s for a key, given a custom-mapping lookup and a modifier function.
- `Pointers`:
  - `FLAG_P_POPOVER_MODE`, plus a `popover` state on the pointer (origin, slots, active slot, dwell timer).
  - A branch in `handleLongPress` opens it.
  - `onTouchMove` updates it and returns early (no swipe path, no slider).
  - `onTouchUp` resolves it through the extracted `emitSubkeyValue` (shared with the short-swipe path).
  - `onTouchCancel` dismisses it.
- `IPointerEventHandler`: `onSubkeyPopoverShow / Update / Dismiss` and `onSubkeyAssign(keyCode, dir, mode)`. All have default no-op bodies, so test fakes need no changes.
- `Keyboard2View` draws the open popover last in `onDraw` through `SubkeyPopoverRenderer` (theme key paints; open, selection-scale and dwell-ring animation; no per-frame allocation).
  - It is drawn inside the key view, not in a `PopupWindow`, because touch coordinates in an IME window are clamped to the view: a slot outside the view could never be reached.
  - So the grid is clamped inside the view (`SubkeyPopoverGeometry.gridCentre`) and hit-tested in its drawn coordinates.
  - A clamped grid can leave the resting finger over a slot, so selection waits until the finger moves (`isArmed`, 15 % of a cell) — an unmoved release never types anything.

## Tests

- `SubkeyPopoverGeometryTest` and `SubkeyPopoverSlotsTest` (pure).
- `SubkeyPopoverMigrationTest`.
- `PointersSubkeyPopoverTest` (mock tier, built like `PointersShortSwipeCustomOverrideTest`): open on hold; a neutral release emits nothing; a slot release emits the default or custom mapping; an empty slot asks for assign; the 3 s dwell asks for edit; repeat is not started.
- Drift guards: `SettingsDefaultsDriftTest`, `SettingsResetPolicyTest`, `ConfigSnapshotFixture`.
