---
title: Command Palette - Technical Specification
description: Localized command search and assignment for custom gestures
user_guide: /wiki/customization/command-palette/
status: implemented
version: v2.0.0 development
---

# Command Palette Technical Specification

`customization/CommandPaletteDialog.kt` is the command assignment dialog used by
per-key customization. It opens when assigning a slot or short-swipe action.
Selecting a command configures that action; execution happens when the assigned
keyboard gesture is used.

## Registry and search

`customization/CommandRegistry.kt` exposes immutable `ALL_COMMANDS` entries. Each
`Command` has a persisted `name`, localized `nameRes` and `descriptionRes`, `category`,
optional keyboard `symbol`, and search `keywords`. Names remain stable because
mappings and backups store them. There are 21 categories, including Clipboard,
Editing, Cursor, Selection, Events, System, Text Actions, and Timestamp.

`getByCategory` groups entries for browsing. `searchRanked` searches localized titles
and descriptions alongside English aliases and keywords. `getKeyValue` resolves
keyboard actions, while `getDisplayInfo` supplies assignment labels. There are no
registry-owned executable lambdas or separate `PaletteSearch`/`PaletteView` classes.

## Assignment and execution

The dialog supports command selection, custom literal text, explicit dynamic templates, and timestamp formats.
The label confirmation step stores a `ShortSwipeMapping` through
`ShortSwipeCustomizationManager`. `CustomShortSwipeExecutor`, keyboard dispatch, and
`KeyEventHandler` route the selected action when the user performs the gesture.

Custom single ASCII or curly apostrophes use ordinary punctuation routing so they can
attach to a pending automatic space under the ordinary punctuation checks. Multi-character literal text remains literal.
The Editing category includes `append_possessive` and `append_apostrophe`; both use
verified word receipts and support suffix-only undo. The shared template editor
validates the four supported tokens before assignment and preserves TEMPLATE through
label confirmation, JSON, backup and XML. Both per-key and popover assignment use it.

Current commands include `selectAll`, `clear_clipboard`, `minimize_bar`, and
`minimize_fab`, `append_possessive`, and `append_apostrophe`. Clearing the system clipboard preserves CleverKeys' saved history,
pins, todos, and media. Minimize commands change the visible IME while it is active;
they do not restore a keyboard the system has already hidden.

## Verification

Registry localization/search and persistence tests protect stable names and translated
labels. `Keyboard2ViewCustomSwipeDispatchTest` exercises real view/executor/key-handler
routing. `PointersGestureRoutingTest` verifies a persisted gesture mapping is loaded
on a cold start with swipe typing disabled. The native settings tests exercise
assignment/search UI, including `DynamicTemplateAssignmentTest` for both contexts. Full suite results are recorded in the internal
[testing strategy](https://github.com/tribixbite/CleverKeys/blob/main/docs/specs/testing-strategy.md).

[User guide](../../customization/command-palette.md)
