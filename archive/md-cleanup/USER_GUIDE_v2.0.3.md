# CleverKeys User Guide

**Version**: 2.0.3  
**Last Updated**: November 20, 2025

Welcome to CleverKeys! This guide will help you get started and make the most of your new keyboard.

---

## 📱 Getting Started

### Installation

1. **Install the APK**
   - Download `tribixbite.keyboard2.debug.apk`
   - Tap to install (enable "Install from Unknown Sources" if needed)
   - Or use ADB: `adb install tribixbite.keyboard2.debug.apk`

2. **Enable CleverKeys**
   - Go to Android Settings
   - Navigate to: System → Languages & input → On-screen keyboard
   - Enable "CleverKeys"

3. **Set as Default**
   - Open any app with text input
   - Tap in a text field
   - Tap "Choose input method" notification
   - Select "CleverKeys"

---

## ⌨️ Basic Typing

### Standard Keys
- **Tap** any key to type that character
- **Long press** for alternate characters (e.g., hold 'e' for 'é', 'ê', etc.)
- **Swipe up** on keys for numbers (row 1)
- **Swipe down** on keys for symbols

### Special Keys
- **Shift**: Capitalize next letter (tap twice for CAPS LOCK)
- **Space**: Insert space (swipe left/right to move cursor)
- **Enter**: New line or submit
- **Backspace**: Delete character (swipe left to delete word)

---

## 🔤 Keyboard Modes

### ABC Mode (Default)
Standard QWERTY keyboard for typing text.

**Switch from**:
- Tap **123+** button (bottom-left)
- OR swipe **↙ (down-left)** on **Ctrl** key

### 123+ Mode (Numeric)
Numbers, symbols, and punctuation.

**Switch from**:
- Tap **ABC** button (bottom-left)  
- OR swipe **↙ (down-left)** on **Ctrl** key

---

## 👆 Gesture Features (NEW in v2.0.3)

### Directional Gestures

CleverKeys uses a 9-position gesture system:

```
   NW   N   NE      ↖  ↑  ↗
   W    ·    E      ←  ·  →
   SW   S   SE      ↙  ↓  ↘
```

### Clipboard History
**Gesture**: Swipe **↗ (up-right)** on **Ctrl** key

**Features**:
- View all copied text
- Tap any item to paste
- Pin important items
- Search clipboard history
- Delete items you don't need

**Swipe back** to return to keyboard

### Quick Numeric Switch
**Gesture**: Swipe **↙ (down-left)** on **Ctrl** key

Instantly switch between ABC and 123+ modes without tapping buttons.

### Settings Access
**Gesture**: Swipe **↘ (down-right)** on **Fn** key  

Quick access to keyboard settings and preferences.

---

## 🧠 Smart Features

### Swipe Typing
Glide your finger across letters to type words quickly.

**How to use**:
1. Place finger on first letter
2. Slide to each letter in the word
3. Lift finger - word appears!

**Tips**:
- Don't worry about precision - AI corrects automatically
- Lift finger between words
- Swipe speed doesn't matter

### Word Predictions
Suggestions appear above the keyboard as you type.

**How to use**:
- Tap any suggestion to insert it
- Swipe left on suggestions to see more
- Predictions learn from your typing

### Auto-Correction
Mistakes are automatically corrected as you type.

**Customize**:
- Settings → Auto-correction strength
- Add words to dictionary to prevent correction

---

## 📋 Clipboard Management

### Copy & Paste
- **Copy**: Select text → Copy button
- **Paste**: Tap paste button OR use clipboard gesture
- **History**: All copies saved automatically

### Clipboard Features
- **Search**: Find old copies quickly
- **Pin**: Keep important items at top
- **Delete**: Swipe left to remove
- **Clear All**: Settings → Clear clipboard history

---

## 🎨 Customization

### Themes
Settings → Appearance → Theme
- Light, Dark, or System default
- Custom colors available

### Key Height
Settings → Keyboard size → Key height  
Adjust for comfort (smaller/larger keys)

### Vibration
Settings → Haptic feedback
- Enable/disable vibration
- Adjust vibration strength

### Sound Effects
Settings → Key sound
- Enable/disable key sounds
- Adjust volume

---

## 🌍 Languages

### Supported Languages
- English, Spanish, French, German, Italian
- Portuguese, Russian, Chinese, Japanese, Korean
- Arabic, Hebrew, Hindi, Thai, Greek
- Turkish, Polish, Dutch, Swedish, Danish
- And more!

### Language Switching
- **Auto-detection**: Types detect language after 3-4 words
- **Manual**: Swipe on spacebar to cycle languages
- **Settings**: Settings → Languages → Add languages

### RTL Support
Full right-to-left support for:
- Arabic (العربية)
- Hebrew (עברית)
- Persian (فارسی)
- Urdu (اردو)

---

## ♿ Accessibility

### TalkBack Support
Full screen reader compatibility:
- Keys announced on focus
- Suggestions read aloud
- Navigation hints provided

### Switch Access
For users with motor disabilities:
- 5 scanning modes available
- Customizable scan speed
- Visual highlights

### Mouse Keys
Control cursor with keyboard:
- Arrow keys move cursor
- Crosshair shows position
- Click simulation

### High Contrast
Settings → Accessibility → High contrast mode
Better visibility for low vision users

---

## 🔧 Settings Guide

### Quick Settings
Tap **Fn** key, then tap **⚙️** (gear icon)

### Full Settings
Swipe **↘ (down-right)** on **Fn** key

### Important Settings

**Keyboard Behavior**:
- Auto-capitalization
- Auto-spacing after punctuation
- Double-space for period
- Swipe typing sensitivity

**Appearance**:
- Theme (Light/Dark/System)
- Key borders
- Key shadows
- Suggestion bar visibility

**Advanced**:
- Terminal mode (for Termux/terminal apps)
- Custom layouts
- Backup & restore
- Debug logging

---

## 💾 Backup & Restore

### Export Your Data
Settings → Backup → Export
- **Settings**: Your preferences
- **Dictionary**: Custom words
- **Clipboard**: History with pins

### Import Data
Settings → Backup → Import
- Select backup file
- Non-destructive merge (adds to existing)
- Statistics shown after import

### What's Saved
✅ All keyboard settings
✅ Custom dictionary words
✅ Clipboard history (with timestamps and pins)
✅ Disabled words (blacklist)
✅ Layout preferences

---

## 📚 Dictionary Management

### Add Custom Words
1. Type a word
2. Tap "Add to dictionary" in suggestions
3. OR: Settings → Dictionary → User words → Add

### View Dictionary
Settings → Dictionary → Built-in dictionary
- 49,000 words included
- Search functionality
- Language-specific

### Disable Corrections
Settings → Dictionary → Disabled words
Add words you don't want auto-corrected

---

## ❓ Troubleshooting

### Keyboard Doesn't Appear
1. Ensure CleverKeys is enabled (Settings → Languages & input)
2. Select CleverKeys as input method
3. Restart the app you're typing in

### Gestures Not Working
1. Ensure you're starting on the correct key
2. Swipe distance should be ~1-2 cm
3. Try a slightly slower swipe
4. Check Settings → Gestures → Sensitivity

### Predictions Not Accurate
1. Type more - predictions improve over time
2. Add custom words to dictionary
3. Check language is set correctly
4. Settings → Predictions → Reset learning data (if needed)

### Performance Issues
1. Settings → Performance → Hardware acceleration (enable)
2. Clear clipboard history if very large
3. Reduce key animation duration
4. Restart device

---

## 🎯 Tips & Tricks

### Faster Typing
- Learn swipe typing for common words
- Use predictions instead of typing full words
- Enable auto-spacing after punctuation
- Customize key positions for your hands

### Power User Features
- **Quick Settings**: Fn + ⚙️
- **Clipboard Search**: Use search bar in clipboard view
- **Pin Frequently Used**: Pin clipboard items you use often
- **Terminal Mode**: Auto-enables in terminal apps

### Gesture Mastery
- Practice gestures in a notes app
- Start gesture on key, swipe confidently  
- No need to be pixel-perfect
- Faster swipes work better than slow ones

---

## 🐛 Known Limitations

### Current Limitations
- Gesture features require manual testing (v2.0.3)
- Some emoji categories not yet available (planned v2.1)
- Layout editor in development (planned v2.1)

### Planned Features (v2.1)
- Emoji picker with categories
- Swipe-to-dismiss suggestions
- Word information dialogs
- Theme customization improvements
- Layout testing interface

See `V2.1_ROADMAP.md` for complete roadmap.

---

## 📞 Support

### Get Help
- **Documentation**: `docs/` directory
- **Issues**: https://github.com/tribixbite/CleverKeys/issues
- **Testing Guide**: `READY_FOR_TESTING.md`

### Report Bugs
1. Note the exact steps to reproduce
2. Include Android version and device model
3. Capture screenshots if possible
4. Submit to GitHub Issues

### Feature Requests
We love feedback! Submit requests via GitHub Issues with:
- Clear description of desired feature
- Use case / why it's valuable
- Any examples from other keyboards

---

## 🎓 Advanced Features

### Terminal Mode
Automatically activates in terminal emulators:
- Ctrl, Meta, Alt keys available
- PageUp/Down, Home/End keys
- Optimized for CLI usage
- No need to manually enable

**Supported Apps**:
- Termux
- Android Terminal Emulator
- Any app with "term", "shell", or "console" in package name

### Custom Layouts
Create your own keyboard layouts:
1. Settings → Layouts → Custom layout
2. Edit XML file
3. Test layout (v2.1 feature)

**Layout Format**:
Standard XML format compatible with Unexpected-Keyboard layouts.

See `docs/specs/layout-system.md` for documentation.

---

## 📊 Statistics

### Current Version (v2.0.3)
- **Languages**: 20 supported
- **Dictionary**: 49,000 words
- **Layouts**: 84 built-in
- **Gestures**: 9-position system
- **Themes**: Light, Dark, Custom

### Keyboard Features
- ✅ Tap typing
- ✅ Swipe typing (ONNX neural prediction)
- ✅ Word predictions
- ✅ Auto-correction
- ✅ Clipboard history
- ✅ Multi-language support
- ✅ RTL languages
- ✅ Accessibility features
- ✅ Custom layouts
- ✅ Backup & restore

---

## 🚀 Quick Reference

### Essential Shortcuts
| Action | Method |
|--------|--------|
| Clipboard | Swipe ↗ on Ctrl |
| Numeric Mode | Swipe ↙ on Ctrl |
| Settings | Swipe ↘ on Fn |
| Caps Lock | Double-tap Shift |
| Delete Word | Swipe ← on Backspace |
| Move Cursor | Swipe ←/→ on Space |

### Swipe Directions
```
Ctrl Key Gestures:
↗ NE = Clipboard
↙ SW = Numeric keyboard

Fn Key Gestures:
↘ SE = Settings
```

---

## 💡 Getting the Most from CleverKeys

### Day 1
- Enable keyboard
- Try basic typing
- Test predictions

### Day 2-3
- Learn swipe typing for common words
- Add custom words to dictionary
- Customize theme and appearance

### Week 1
- Master gesture shortcuts
- Set up clipboard workflow
- Configure preferred settings

### Ongoing
- Use clipboard history regularly
- Pin frequently pasted items
- Adjust settings as needed
- Provide feedback for improvements

---

## 🎉 Welcome to CleverKeys!

Thank you for using CleverKeys. We've worked hard to create a powerful, privacy-focused keyboard with modern features.

**Key Principles**:
- ��� **Privacy First**: 100% local processing
- 🧠 **AI-Powered**: Neural predictions
- 🎨 **Beautiful**: Material 3 design
- ♿ **Accessible**: Full accessibility support
- 🔓 **Open Source**: Complete transparency

Enjoy typing! 🎊

---

**Version**: v2.0.3 Build 58  
**Date**: November 20, 2025  
**Status**: Production Ready (pending verification)

*For technical documentation, see `docs/` directory.*  
*For testing instructions, see `READY_FOR_TESTING.md`.*
