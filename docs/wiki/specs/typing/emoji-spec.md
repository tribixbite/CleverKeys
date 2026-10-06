---
title: Emoji - Technical Specification
description: Native emoji search, font support filtering, and usage history
user_guide: /wiki/typing/emoji/
status: implemented
version: v2.0.0 development
---

# Emoji Technical Specification

The emoji panel uses native views and the emoji data bundled in `res/raw/emojis.txt`.
Search and category browsing share the same font-support filter, so entries the actual
button font cannot draw are hidden rather than shown as empty boxes.

## Components

| Component | Responsibility |
|-----------|----------------|
| `Emoji` | Resource groups, names, and search results |
| `EmojiKeywordIndex` | Indexed keyword lookup |
| `EmojiGridView` | Adapter, glyph filtering, insertion, and usage counts |
| `EmojiSearchManager` | Visible search field and keyboard routing while searching |
| `EmojiGroupButtonsBar` | Usage-history button and resource category buttons |
| `EmojiTooltipManager` | Long-press name tooltip |

These are the production classes under `src/main/kotlin/tribixbite/cleverkeys/`.
There is no separate `EmojiCategory` enum or EmojiCompat dependency.

## Search and insertion

`Emoji.searchByName` trims and lowercases the query, requests up to 60 indexed
matches, and supplements sparse results with legacy name matching. Results are
deduplicated and capped at 100 before the grid applies font filtering. The displayed
count therefore reflects renderable results, rather than every keyword match.

The panel has a visible search field with clear and close controls. Keyboard text
and deletion are routed to the active emoji search instead of the app editor.
Selecting a result temporarily leaves that routing mode to send its string through
`Config.handler.key_up`, then restores the panel's routing and saves usage.

## Font support

The support check uses the `Paint` of a real `EmojiView` themed with
`R.style.emojiGridButton`. Ordinary emoji sequences require `Paint.hasGlyph` for the
whole sequence. The final resource group contains text emoticons: its visible
characters are checked individually, ignoring whitespace, format/control characters,
and variation selectors. Treating `:)` as one emoji ligature would incorrectly hide it.
Results are cached within the grid instance. Device font and Android version can
change which entries are visible; this is not a downloadable font installation.

## Categories and usage history

The first category button opens usage history. Other buttons come from the resource
groups and use a representative entry as their icon. History is ordered by descending
use count, rather than strictly by last-use time.

Preferences file `emoji_last_use` contains a string set under the same key, with
`count-string` entries. Saving is debounced by 500 ms. There is no JSON recent-list
format or fixed 50-entry history cap. Long-press displays the entry's name in a tooltip
that dismisses after two seconds and is hidden when scrolling. A global skin-tone
picker is not implemented.

## Verification

`EmojiSearchTest` exercises real resource search and the native themed grid, including
supported category/search results and preservation of text faces. Pure
`EmojiGlyphSupport` tests protect the sequence/visible-character rules. Test totals
and the current full cloud verdict live in the internal
[testing strategy](https://github.com/tribixbite/CleverKeys/blob/main/docs/specs/testing-strategy.md).

[User guide](../../typing/emoji.md)
