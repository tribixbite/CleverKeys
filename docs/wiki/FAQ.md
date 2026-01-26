# CleverKeys FAQ

Frequently asked questions about using CleverKeys keyboard.

---

## Typing Numbers & Symbols (Subkeys / Short Swipes)

**Q: How do I type numbers and symbols quickly?**

A: Use **short swipes** (also called subkeys) on letter keys. Each key has up to 8 hidden characters accessible by swiping in different directions:
- **Swipe up** on a key to access the primary subkey (often numbers on top row)
- **Swipe in any of 8 directions** (up, down, left, right, and diagonals) for different symbols
- Example: Swipe up on `Q` for `1`, swipe up on `W` for `2`, etc.

To see all available subkeys for a key, long-press to reveal the popup.

**Q: How do I customize what short swipes do?**

A: Go to **Settings → Activities → Per-Key Customization** to assign custom actions (characters, symbols, or commands) to each direction on any key.

---

## Cursor Control & Navigation

**Q: How do I move the cursor without tapping the screen?**

A: **Swipe on the spacebar** to move the cursor:
- **Swipe left/right** on spacebar to move cursor left/right by one character
- The cursor follows your finger movement for precise positioning

**Q: What is Trackpoint Mode?**

A: Trackpoint mode provides laptop-style cursor navigation. **Long-press the spacebar** to activate:
- **Swipe in any direction** to move the cursor continuously
- Release to exit trackpoint mode
- Great for navigating large documents

---

## Text Selection & Deletion

**Q: How do I select text?**

A: Use **Selection Delete** gestures:
1. Hold **Shift** (or enable selection mode)
2. Swipe on spacebar to extend selection left/right
3. Alternatively, use trackpoint mode with Shift held for multi-directional selection

**Q: How do I delete selected text or words?**

A: Several options:
- **Backspace** deletes selected text or one character
- **Swipe left on backspace** to delete the entire word before cursor
- **Swipe right on backspace** to delete forward (varies by layout)

---

## Language & Input Options

**Q: How do I switch between languages?**

A: Add language toggle to a **subkey**:
1. Go to **Settings → Activities → Per-Key Customization**
2. Select a key (e.g., globe/world key or a convenient letter)
3. Assign the **Language Toggle** action to a direction
4. Now swipe in that direction to cycle through enabled languages

You can also configure multiple layouts in **Settings → Activities → Layout Manager**.

**Q: How do I enable additional languages?**

A: Go to **Settings → Multi-Language** to:
- Download additional language packs
- Enable/disable installed languages
- Set language switching behavior

---

## Emoji & Special Input

**Q: How do I access emojis?**

A: Multiple methods:
- **Long-press the Enter key** to open emoji picker
- Use **contextual emoji search**: type a word and see related emoji suggestions
- Swipe on the emoji key (if present in your layout)

**Q: What is contextual emoji search?**

A: As you type, CleverKeys analyzes your text and suggests relevant emojis:
- Type "happy" → see 😊 🎉 😁 in suggestions
- Type "food" → see 🍕 🍔 🍜 in suggestions
- Tap an emoji suggestion to insert it

---

## Swipe Typing (Glide Typing)

**Q: How does swipe typing work?**

A: Instead of tapping each letter:
1. Touch the first letter of your word
2. **Slide your finger** through each letter without lifting
3. Release on the last letter
4. CleverKeys uses AI to predict your word

**Q: How do I improve swipe typing accuracy?**

A: Several tips:
- **Be deliberate** about hitting each letter, especially the first and last
- Check **Settings → Neural Prediction** for tuning parameters
- Use **Settings → Gesture Tuning** to calibrate swipe sensitivity
- Add commonly used words to your personal dictionary

---

## Gestures Reference

| Gesture | Action |
|---------|--------|
| **Tap** | Type the key's main character |
| **Short swipe (any direction)** | Type the subkey character for that direction |
| **Long press** | Show popup with all subkeys |
| **Swipe on spacebar** | Move cursor left/right |
| **Long-press spacebar** | Enter trackpoint mode |
| **Swipe left on backspace** | Delete word |
| **Circle gesture (clockwise)** | Redo |
| **Circle gesture (counter-clockwise)** | Undo |

---

## Troubleshooting

**Q: Swipe typing isn't working.**

A: Check these settings:
1. **Settings → Input Behavior → Swipe Typing** must be enabled
2. Ensure neural model is loaded (check Settings → Neural Prediction)
3. Try increasing gesture distance thresholds in Gesture Tuning

**Q: The keyboard is too tall/small.**

A: Adjust in **Settings → Appearance**:
- **Keyboard Height** slider for portrait mode
- **Keyboard Height Landscape** for landscape mode
- Or use **Settings → Activities → Keyboard Calibration** for guided setup

**Q: Keys are not registering taps correctly.**

A: Try **Settings → Activities → Keyboard Calibration** to adjust hit detection zones for your device.

---

## Privacy & Security

**Q: Does CleverKeys send my typing data anywhere?**

A: **No.** CleverKeys is fully offline:
- All text prediction happens on-device using local AI
- No network connections for typing features
- Your personal dictionary stays on your device
- Open source: verify the code yourself on GitHub

**Q: What about clipboard history?**

A: Clipboard history is stored locally only. You can:
- Disable it entirely in Settings → Clipboard
- Exclude password managers from history
- Clear clipboard history at any time

---

For more detailed documentation, visit: https://tribixbite.github.io/CleverKeys/wiki
