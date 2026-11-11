---
title: Quick Settings Tile
description: Show or hide the keyboard from the notification shade; switch keyboards when CleverKeys is not active
category: Getting Started
difficulty: beginner
related_spec: ../specs/customization/quick-settings-tile-spec.md
---

# Quick Settings Tile

The CleverKeys tile in the notification shade shows and hides the keyboard: a tap hides it
when it is on screen and shows it when it is hidden. When CleverKeys is not your current
keyboard, the same tap opens the system keyboard picker so you can switch to it.

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
| Yes | On screen (full size or minimized) | Hides it | Toggle |
| Yes | Was on screen until you opened the shade (some phones hide it then) | Keeps it hidden | Toggle |
| Yes | Hidden | Shows it at full size | Toggle |

The tile acts on the keyboard as you saw it before pulling the shade down, so on phones where
opening the shade hides the keyboard, a tap still means "hide" and the next tap shows it.

## Things to Know

- **The shade stays open after a tap.** Closing it would take focus away from the app you
  were typing in, so the keyboard change becomes visible once you close the shade yourself.
- **Some apps hide the keyboard when the shade opens.** The tile keeps asking for the keyboard
  for up to 8 seconds after a tap, so it comes back once the shade closes.
- **No text field focused?** The tile still asks for the keyboard; Android decides whether it can
  show over that screen (on the home screen there is nothing to type into).
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
- CleverKeys is not the current keyboard. Pick it in the picker that opens.

### Nothing seems to happen when I tap?
- Close the shade: the keyboard changes underneath it. If no text field is focused, tap into
  one. If the field is a web page text box in Chrome, tap the field again.

## Technical Details

See [Quick Settings Tile Technical Specification](../specs/customization/quick-settings-tile-spec.md).
