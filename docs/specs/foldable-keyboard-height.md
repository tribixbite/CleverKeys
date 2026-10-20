# Foldable keyboard height (inner / outer screen)

Status: implemented 2026-10-20. Source: user email report, "keyboard height always changes to
default when using the inner screen", which points to Unexpected Keyboard's folded/unfolded
height options. Maintainer: yes.

## Problem

`FoldStateTracker` (androidx.window `FoldingFeature`) reports the inner, unfolded screen, and
`Config.refresh()` then reads `keyboard_height_unfolded` / `keyboard_height_landscape_unfolded`,
a scheme inherited from Unexpected Keyboard. CleverKeys' Settings never wrote those keys, so on
the inner screen the keyboard always used the built-in default height, whatever the user had
set. `KeyboardDimensionsHelper` duplicated the key choice, with hard-coded defaults (35 / 50)
that differed from `Defaults`.

## Behaviour

- Four stored heights: portrait and landscape, each for the outer screen and the inner screen.
  Key names are unchanged (`keyboard_height`, `keyboard_height_landscape`, `…_unfolded`).
- **An inner-screen height that was never set follows the outer-screen height of the same
  orientation.** This alone fixes the report for users who never touch the new controls.
- Settings → Appearance adds "Keyboard Height (Inner Screen)" and "Landscape Height (Inner
  Screen)". They show the effective value, so they match the outer sliders until changed. On
  devices without a hinge sensor (`FEATURE_SENSOR_HINGE_ANGLE`) they are visible but disabled,
  with "No foldable screen detected on this device."
- Reset Settings removes the inner keys (they are in `NON_DEFAULTED_KEYS`, never seeded), so they
  go back to following the outer screen. Backup ranges were already registered
  (`SettingsValidation`, 10–100).

## Implementation

`KeyboardHeightPrefs` (one rule: `percent(landscape, unfolded)`) is used by `Config.refresh`,
both `KeyboardDimensionsHelper` readers and the Settings state (`refreshUnfoldedHeights`). A fold
change already triggers `Config.refresh` through `ConfigurationManager`'s fold callback.

## Tests

- `KeyboardHeightPrefsTest` (pure): defaults, the follow-outer rule, independence of the inner
  values and the orientations (#161), and the stored key names.
- `KeyboardHeightOrientationDriftTest`: Config resolves through the shared rule with the fold
  state, the helper has no key literals, and the four sliders write four distinct keys.
- Device: no foldable test phone exists. The disabled-state rendering was checked on a Saga; the
  fold path itself is covered by the rule test and by the unchanged `FoldStateTracker` wiring.
