---
title: Quick Settings Tile
description: Show, minimize or restore the keyboard from the notification shade; switch keyboards when CleverKeys is not active
category: Getting Started
difficulty: beginner
related_spec: ../specs/customization/quick-settings-tile-spec.md
---

# Quick Settings Tile

The CleverKeys tile in the notification shade toggles the keyboard: it shows a hidden
keyboard, minimizes a visible one, and restores a minimized one. When CleverKeys is not your
current keyboard, or no text field is focused, the same tap opens the system keyboard picker
instead.

## Adding the Tile

1. Pull down the notification shade (swipe down from the top of the screen)
2. Pull down again to expand Quick Settings
3. Tap the **pencil/edit icon** to customize tiles
4. Find **"CleverKeys"** in the available tiles
5. Drag it to your active tiles area
6. Tap outside to save

## What a Tap Does

The tile reads the keyboard's state when the shade opens and again after each tap. On
Android 10 and later the tile's subtitle names the action before you tap.

| CleverKeys is your keyboard? | Keyboard | Tap | Subtitle |
|------------------------------|----------|-----|----------|
| No | any | Opens the system keyboard picker | Switch keyboard |
| Yes | Shown at full size | Minimizes it, using the last minimized style you picked (a floating button until you pick one) | Minimize |
| Yes | Minimized | Restores it to full size | Restore |
| Yes | Hidden by the app a moment ago (within 10 seconds, not by you) | Brings it back in the other form: minimized if it was full, full if it was minimized | Minimize / Restore |
| Yes | Hidden earlier (you pressed Back, or long ago) while a text field is still focused | Shows it | Show |
| Yes | No text field focused (home screen, for example) | Opens the system keyboard picker | Switch keyboard |

Minimized styles are the ones the [Command Palette](../customization/command-palette.md)'s
minimize commands offer (floating button or bar).

## Things to Know

- **The shade stays open after a tap.** Closing it would take focus away from the app you
  were typing in, so the keyboard change becomes visible once you close the shade yourself.
- **Some apps hide the keyboard when the shade opens.** The tile keeps asking for the keyboard
  for up to 8 seconds after a tap, so it comes back in the form you chose once the shade
  closes.
- **Text boxes on web pages in Chrome** lose focus when the shade opens; after closing the
  shade, tap the field again. Native fields, including Chrome's address bar, work.
- **No extra permission.** Showing the keyboard again does not need "Display over other
  apps"; the tile talks to the keyboard directly.

## Requirements

- Android 7.0 (Nougat) or higher
- CleverKeys enabled as an input method in system settings

## Troubleshooting

### Tile not appearing in the available tiles?
- Ensure CleverKeys is installed
- Ensure CleverKeys is enabled in **Settings > System > Languages & Input > On-screen keyboard**

### The subtitle says "Switch keyboard" when I expected "Show"?
- Either CleverKeys is not the current keyboard (pick it in the picker that opens), or no text
  field is focused. The keyboard can only be shown for a focused field; tap into one first.

### Nothing seems to happen when I tap?
- Close the shade: the keyboard changes underneath it. If the field is a web page text box in
  Chrome, tap the field again.

## Technical Details

See [Quick Settings Tile Technical Specification](../specs/customization/quick-settings-tile-spec.md).
