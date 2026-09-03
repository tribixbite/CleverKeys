# CleverKeys FAQ

Frequently asked questions about using CleverKeys keyboard.

---

## Typing Numbers & Symbols (Subkeys / Short Swipes)

**Q: How do I type numbers and symbols quickly?**

A: Use **short swipes** (subkeys) on letter keys. Each key has up to 8 subkeys mapped to the cardinal directions N, NE, E, SE, S, SW, W, NW:
- **Swipe NORTHEAST** on `Q` for `1` (hint: start in the SW corner)
- Each direction on each key can trigger a different character or action
- Directions are determined by the angle of your swipe

**Q: How do I customize what short swipes do?**

A: Go to **Settings → Activities → Per-Key Customization** to assign custom actions (characters, symbols, or commands) to each of the 8 directions on any key.

---

## Cursor Control & Navigation

**Q: How do I move the cursor without tapping the screen?**

A: **Swipe on the spacebar** to move the cursor:
- Cursor movement is **proportional to your swipe speed and distance**
- Faster/longer swipes move further; slow precise swipes give fine control
- Works in both directions

**Q: What is TrackPoint Mode?**

A: TrackPoint mode provides laptop-style continuous cursor navigation:
- **Long-press the nav key** (between spacebar and enter) to enter trackpoint mode
- Move your finger like a joystick to control cursor direction
- Release to exit trackpoint mode

---

## Text Selection & Deletion

**Q: How do I select and delete text?**

A: For **Selection-Delete mode**:
1. **Short swipe on the backspace key** to initiate
2. **Keep your finger held down** after the swipe
3. **Move your finger like a joystick** to select text (left/right for characters, up/down for lines)
4. Release to delete the selected text

Swipe left on backspace deletes the word before cursor.

---

## Language & Input Options

**Q: How do I quickly switch between languages?**

A: Use the **primary/secondary language toggle subkeys**:
1. Set your languages in **Settings → Multi-Language** (Primary and Secondary)
2. The toggle subkeys cycle between them
3. Both languages contribute to swipe predictions when Multi-Language mode is enabled

---

## Emoji & Clipboard

**Q: How do I access emojis?**

A: **Swipe SOUTHWEST on the Fn key** to open the emoji picker:
- Search will auto-populate with nearby text
- Categories tab with all emoji groups
- Recents for quick access to frequently used
- Search by keyword or emoji name
- 119 text emoticons (kaomoji) also included

**Q: How do I use the clipboard?**

A: **Swipe SOUTHWEST on the Ctrl key** to open clipboard history:
- **History tab**: Recent clipboard items (text + media)
- **Pinned tab**: Items you've saved for quick access
- **Todos tab**: Items marked as todos
- Tap an item's content to expand it (text) or paste it (media)
- Images, videos, and PDFs show thumbnails in the panel
- Use the icon buttons to paste, move to pinned, or copy as todo
- Re-copying text already in history moves the existing entry back to the top (no duplicate is created)
- Password manager and 'sensitive' flagged clippings are excluded by default

**Q: How do I paste images from clipboard?**

A: Copy an image in any app (Gallery, Chrome, etc.) — it appears in clipboard history with a thumbnail. Tap the entry to paste it into the current app via `commitContent`. Most messaging apps support this.

---

## Swipe Typing (Glide Typing)

**Q: How does swipe typing work?**

A: Touch the first letter of your word, slide your finger through each letter without lifting, then release on the last letter. Faster may yield better results.

**Tip:** There is nothing to tune. The default **CTC** engine ships with a decoder preset chosen per lexicon, and swipe typing works out of the box on every supported language — no per-language settings to adjust.

**Q: Can I swipe other languages?**

A: Yes, and it needs no tuning and no particular layout. The CTC engine serves the 7 bundled Latin languages (English, French, German, Spanish, Italian, Portuguese, Swedish) on any Latin layout with all 26 letters — QWERTY, AZERTY, QWERTZ, Dvorak, Colemak all work. It also serves 6 non-Latin languages on their own layouts and encoders — Russian, Ukrainian, Bulgarian, Macedonian (Cyrillic), Greek, and Hebrew — plus imported Latin packs whose words are typeable on an a–z board (Dutch, Indonesian, Malay, Tagalog, Swahili).

To add a language beyond the bundled seven, go to **Settings > 🌐 Multi-Language > Import Pack** and import that language's pack; prebuilt zips are on the [langpacks release](https://github.com/tribixbite/CleverKeys/releases/tag/langpacks). Set it as your primary (or secondary) language and swipe normally.

Turkish is the one language CleverKeys routes away from CTC on purpose: dotless `ı` has no a–z spelling, so a quarter of the vocabulary would be unswipeable. It uses the geometric engine instead, which decodes over the board's real keys. Geometric is likewise the automatic fallback for every other language and layout, so swipe typing is never disabled — see [Swipe Typing](./typing/swipe-typing.md) for the full engine-routing table and what accuracy evidence exists per language.

**Q: Why did the space disappear when I typed a period after swiping a word?**

A: Swiping adds a trailing space automatically, and smart punctuation removes that automatic space so the period attaches to the word (`word.` instead of `word .`). Spaces you type yourself are never removed — press spacebar manually before the punctuation to keep it. Similarly, swiping a word right after an opening bracket or quote skips the leading space (`("` + swipe → `("word`). See [Smart Punctuation](./typing/smart-punctuation.md).

---

## Gestures Reference

| Gesture | Action |
|---------|--------|
| **Tap** | Type the key's main character |
| **Short swipe (8 directions)** | Trigger subkey for that direction |
| **Swipe on spacebar** | Move cursor (proportional to swipe speed) |
| **Long-press nav key** | Enter TrackPoint mode |
| **Short swipe + hold on backspace** | Selection-Delete mode |
| **Long swipe across keys** | Swipe typing (glide input) |
| **SW on Fn** | Open emoji picker |
| **SW on Ctrl** | Open clipboard |

---

## Troubleshooting

**Q: Swipe typing isn't working.**

A: Check these settings:
1. Ensure swipe typing is enabled in input settings
2. ONNX model should load automatically on first use
3. Try adjusting gesture distance thresholds in Short Swipe Calibration

**Q: The keyboard is too tall/small.**

A: Adjust in **Settings → Appearance**:
- **Keyboard Height** percentage slider
- Separate slider for landscape mode

**Q: Autocorrect stopped working while I was typing a web address.**

A: That's intentional. Autocorrect stays out of the way while you type URLs, email addresses, and file paths — any token containing characters like `. / : @ # ? & = %` or digits is left alone, so domains and pasted-then-edited links don't get corrupted. It resumes automatically on the next normal word. Relatedly, tapping a suggestion in a browser URL bar replaces the partial word you typed rather than appending to it.

---

## Privacy & Security

**Q: Does CleverKeys send my typing data anywhere?**

A: **No.** CleverKeys is fully offline:
- All prediction runs on-device via ONNX Runtime
- **No INTERNET permission** in the manifest
- Your personal dictionary stays on your device
- Open source: verify the code on GitHub

---

For more detailed documentation, visit the other wiki pages.
