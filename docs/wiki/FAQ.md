# CleverKeys FAQ

Frequently asked questions about using CleverKeys keyboard.

---

## Typing Numbers & Symbols (Subkeys / Short Swipes)

**Q: How do I type numbers and symbols quickly?**

A: Use **short swipes** (subkeys) on letter keys. Each key has up to 8 subkeys mapped to the cardinal directions N, NE, E, SE, S, SW, W, NW. For example, swipe NORTHEAST on Q for '1' (hint: start in the SW corner). Use **Settings → Activities → Per-Key Customization** to add your own subkey assignments.

**Q: How do I see what subkeys are available?**

A: Go to **Settings → Activities → Per-Key Customization** to view and modify all subkey assignments. There is no long-press popup - subkeys are triggered by direct short swipes.

---

## Cursor Control & Navigation

**Q: How do I move the cursor without tapping the screen?**

A: **Swipe on the spacebar** - cursor movement speed is proportional to how far you swipe. For precision navigation, long-press the **nav key** (between spacebar and enter) to enter TrackPoint mode, then move your finger like a joystick.

---

## Text Selection & Deletion

**Q: How do I select and delete text?**

A: For **Selection-Delete mode**: short swipe on backspace then HOLD - move your finger like a joystick to select text (left/right for characters, up/down for lines). Release to delete selected text. Swipe left on backspace deletes the word before cursor.

---

## Language & Input Options

**Q: How do I quickly switch between languages?**

A: Add the **primary and/or secondary language toggle subkeys**. Set your languages in **Settings → Multi-Language** (Primary and Secondary). The toggle subkeys cycle between them, and both languages contribute to swipe predictions when Multi-Language mode is enabled.

---

## Emoji & Clipboard

**Q: How do I access emojis?**

A: **Swipe SOUTHWEST on the Fn key** to open the emoji picker. Search will auto-populate with nearby text. The picker includes categories, recents, search, and 119 text emoticons (kaomoji). You can search by keyword or emoji name.

**Q: How do I use the clipboard?**

A: **Swipe SOUTHWEST on the Ctrl key** to open clipboard history. The panel has tabs for History, Pinned, and Todos. Tap an item's content to expand it; use the icon buttons to paste, move to pinned, or copy as todo. Note: re-copying text already in history won't duplicate or reorder it (tip: use search instead). Password manager and 'sensitive' flagged clippings are excluded by default.

---

## Swipe Typing (Glide Typing)

**Q: How does swipe typing work?**

A: Touch the first letter of your word, slide your finger through each letter without lifting, then release on the last letter. Faster may yield better results.

**Tip:** Increase 'Length Penalty (Alpha)' for English (~1.5), decrease for other languages (and increase Vocab Frequency Weight).

**Q: Can I swipe other languages?**

A: Yes, but this currently requires using the qwerty latin layout and manually tuning several settings to achieve useable output - see FAQ entry above and Prefix Boost settings (these are very sensitive; try small changes).

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
