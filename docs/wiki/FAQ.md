# CleverKeys FAQ

Frequently asked questions about using CleverKeys keyboard.

---

## Typing Numbers & Symbols (Subkeys / Short Swipes)

**Q: How do I type numbers and symbols quickly?**

A: Use **short swipes** (also called subkeys) on letter keys. Each key has up to 8 hidden characters accessible by swiping in different directions (N, NE, E, SE, S, SW, W, NW):
- **Swipe NORTHEAST** on `Q` for `1`, on `W` for `2`, etc.
- Each direction on each key can trigger a different character or action
- Directions are determined by the angle of your swipe using atan2 calculation

**Q: How do I see what subkeys are available?**

A: Go to **Settings → Activities → Per-Key Customization** to view and modify all subkey assignments. There is no long-press popup - subkeys are triggered by direct short swipes.

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

**Q: How do I use Selection-Delete mode?**

A: Selection-Delete allows selecting and deleting text in one gesture:
1. **Short swipe on the backspace key** to initiate
2. **Keep your finger held down** after the swipe
3. **Move your finger like a joystick** to extend selection
4. Release to delete the selected text

**Q: How do I delete words?**

A: Several options depending on your layout configuration:
- **Swipe on backspace** in the direction configured for word-delete
- Use backspace subkeys for various delete modes
- Check your layout's backspace subkey configuration

---

## Language & Input Options

**Q: How do I switch between languages?**

A: Use the **primary/secondary language toggle subkeys**:
1. Set your languages in **Settings → Multi-Language** (Primary and Secondary)
2. The toggle subkeys cycle between them
3. Both languages contribute to swipe predictions when Multi-Language mode is enabled

You can also configure layouts in **Settings → Activities → Layout Manager**.

**Q: How do I enable additional languages?**

A: Go to **Settings → Multi-Language** to:
- Set primary and secondary languages
- Both languages contribute to swipe predictions simultaneously

---

## Emoji & Clipboard

**Q: How do I access emojis?**

A: **Swipe SOUTHWEST on the Fn key** to open the emoji picker:
- Categories tab with all emoji groups
- Recents for quick access to frequently used
- Search by keyword or emoji name
- 119 text emoticons (kaomoji) also included

**Q: How do I use the clipboard?**

A: **Swipe SOUTHWEST on the Ctrl key** to open clipboard history:
- **History tab**: Recent clipboard items
- **Pinned tab**: Items you've pinned for quick access
- **Todos tab**: Items marked as todos
- Tap any item to paste it
- Long-press to pin/unpin items
- Password manager apps (KeePassDX, Chrome, Firefox, Edge) are excluded by default

**Q: What is contextual emoji search?**

A: When the emoji panel is open, you can search by typing:
- Type keyword like "happy" to find matching emojis
- Emoticons are also searchable (type "shrug" to find ¯\_(ツ)_/¯)
- Flag emojis searchable by country name

---

## Swipe Typing (Glide Typing)

**Q: How does swipe typing work?**

A: CleverKeys uses **pure ONNX neural prediction**:
1. Touch the first letter of your word
2. **Slide your finger** through each letter without lifting
3. Release on the last letter
4. Neural network with beam search predicts your word

The prediction uses a coordinate-based neural model (7.2MB) trained on swipe patterns, not path matching.

**Q: How do I improve swipe typing accuracy?**

A: Tips:
- **Start and end precisely** - first/last letters anchor the prediction
- Use **Settings → Activities → Short Swipe Calibration** to adjust gesture thresholds
- Add commonly used words to your personal dictionary
- Check **Settings → Debug Settings** for raw beam predictions if troubleshooting

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

**Q: Keys are not registering taps correctly.**

A: Try **Settings → Activities → Short Swipe Calibration** to adjust minimum distance thresholds.

---

## Privacy & Security

**Q: Does CleverKeys send my typing data anywhere?**

A: **No.** CleverKeys is fully offline:
- All prediction runs on-device via ONNX Runtime
- **No INTERNET permission** in the manifest
- Your personal dictionary stays on your device
- Open source: verify the code on GitHub

**Q: What about clipboard history?**

A: Clipboard history is stored locally only. You can:
- Set history limit (0-500, where 0 = unlimited) in Settings → Clipboard
- Exclude password managers from history (KeePassDX, Chrome, Firefox, Edge supported)
- Enable IS_SENSITIVE flag (Android 13+) for sensitive input fields
- Clear history and pinned items anytime

---

For more detailed documentation, visit: https://tribixbite.github.io/CleverKeys/wiki
