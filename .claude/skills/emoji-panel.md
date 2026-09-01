# Emoji Panel Development Skill

Use this skill when making changes to the emoji picker, emoji search, emoji categories, or emoji display.

## Key Files

### Core Emoji Logic
| File | Purpose |
|------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/emoji/Emoji.kt` | Emoji data model, name mappings, search by name |
| `src/main/kotlin/tribixbite/cleverkeys/emoji/EmojiGridView.kt` | Grid display, cell rendering, long-press handling |
| `src/main/kotlin/tribixbite/cleverkeys/emoji/EmojiGroupButtonsBar.kt` | Category tabs at top of emoji pane |
| `src/main/kotlin/tribixbite/cleverkeys/emoji/EmojiSearchManager.kt` | Search logic, context word detection |
| `src/main/kotlin/tribixbite/cleverkeys/emoji/EmojiKeywordIndex.kt` | Trie-based keyword search (9800+ keywords) |
| `src/main/kotlin/tribixbite/cleverkeys/emoji/EmojiTooltipManager.kt` | Long-press tooltip (PopupWindow) |

### Data Files
| File | Purpose |
|------|---------|
| `res/raw/emojis.txt` | Emoji list (4072 entries, last line is group indices) |
| `src/main/assets/emoji_keywords.tsv` | Keyword → emoji mappings for search |

### Layouts
| File | Purpose |
|------|---------|
| `res/layout/emoji_pane.xml` | Main emoji picker layout (search bar, category buttons, grid) |

## Architecture

### Emoji Data Flow
```
emojis.txt → Emoji.kt (parseEmojiList) → EmojiGridView (adapter)
                ↓
emoji_keywords.tsv → EmojiKeywordIndex (Trie) → search results
                ↓
nameToEmoji map → emojiToName map → tooltip display
```

### View Hierarchy
```
emoji_pane.xml (LinearLayout, VERTICAL)
├── emoji_search_bar (LinearLayout, HORIZONTAL)
│   ├── emoji_search_input (EditText)
│   ├── emoji_search_clear (ImageButton)
│   └── emoji_close_button (ImageButton)
├── emoji_group_buttons (EmojiGroupButtonsBar)
├── divider (View)
└── EmojiGridView (GridView, numColumns=auto_fit)
    └── EmojiView (TextView per cell)
```

### Long-Press Tooltip
The tooltip uses programmatic view creation (not XML) to avoid resource lookup issues in IME context:
```kotlin
// EmojiTooltipManager.kt
tooltipView = LinearLayout(context)  // container
emojiCharView = TextView(context)    // emoji display
emojiNameView = TextView(context)    // name display
popupWindow = PopupWindow(tooltipView, WRAP_CONTENT, WRAP_CONTENT)
popupWindow.showAsDropDown(anchor, offsetX, offsetY)  // reliable in IME
```

## Common Tasks

### Adding New Emoji Names
Edit `Emoji.kt` in the `nameToEmoji` map:
```kotlin
// In initNameMap()
val nameToEmoji = mapOf(
    "new name" to "🆕",
    // ...
)
```

### Adding Search Keywords
Append to `src/main/assets/emoji_keywords.tsv`:
```
keyword<TAB>emoji1,emoji2,emoji3
```
Format: keyword, tab character, comma-separated emojis

### Adding Emoticons
1. Add to `res/raw/emojis.txt` before the last line (group indices)
2. Update the last line's group indices if adding a new category
3. Add keywords to `emoji_keywords.tsv`
4. Add name mappings in `Emoji.kt` (emoticons section)

### Changing Grid Appearance
Edit `res/layout/emoji_pane.xml`:
- `android:columnWidth="45sp"` - cell width
- `android:verticalSpacing="4dp"` - row gap
- `android:horizontalSpacing="2dp"` - column gap

Edit `EmojiGridView.EmojiView`:
- `minHeight` - minimum cell height
- `padding` - cell padding
- Text size scaling in `setEmoji()` for emoticons

## Testing Checklist

- [ ] Emoji search works (type while emoji pane open)
- [ ] Category switching works (tap category icons)
- [ ] Long-press shows tooltip with correct name
- [ ] Emoticons display without overlap
- [ ] Recent emoji updates after selection
- [ ] Emoji pane opens/closes correctly (toggle behavior)
- [ ] Scroll dismisses tooltip
- [ ] Search clears when closing/reopening pane

## Related Documentation
- Spec: `docs/specs/suggestion-bar-content-pane.md`
- Wiki: `docs/wiki/typing/emoji.md`
