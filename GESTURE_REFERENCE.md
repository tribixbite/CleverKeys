# Gesture Reference - CleverKeys Keyboard

**Date**: November 20, 2025
**Status**: Complete documentation of key gesture system

---

## 📍 **Key Position Mapping (key0-key8)**

Every key in CleverKeys supports **9 directional positions** for gestures:

```
Position Layout:
   1   7   2
   5   0   6
   3   8   4

Direction Names:
   NW  N  NE
   W   C  E
   SW  S  SE
```

### Position Index Reference

| Index | Position | Direction | How to Activate |
|-------|----------|-----------|-----------------|
| **0** | Center | **C** | Tap key |
| **1** | Northwest | **NW** | Swipe up-left |
| **2** | Northeast | **NE** | Swipe up-right |
| **3** | Southwest | **SW** | Swipe down-left |
| **4** | Southeast | **SE** | Swipe down-right |
| **5** | West | **W** | Swipe left |
| **6** | East | **E** | Swipe right |
| **7** | North | **N** | Swipe up |
| **8** | South | **S** | Swipe down |

**Source**: `src/main/kotlin/tribixbite/keyboard2/KeyboardData.kt:236-240`

---

## ⌨️ **Bottom Row Gesture Mapping**

From `res/xml/bottom_row.xml` (lines 2-6):

### Key 1: Ctrl (Bottom-left corner)
```xml
<key width="1.7" key0="ctrl" key1="loc meta" key2="loc switch_clipboard"
     key3="switch_numeric" key4="loc switch_greekmath"/>
```

| Position | Gesture | Function | Description |
|----------|---------|----------|-------------|
| key0 (C) | Tap | **ctrl** | Control modifier |
| key1 (NW) | Swipe up-left | **meta** | Meta/Windows key |
| key2 (NE) | Swipe up-right | **switch_clipboard** | **Open clipboard history** |
| key3 (SW) | Swipe down-left | **switch_numeric** | Switch to numeric keyboard |
| key4 (SE) | Swipe down-right | **switch_greekmath** | Switch to Greek/Math keyboard |

**Most Used**:
- 🔥 **Clipboard**: Swipe NE (up-right) on Ctrl key
- 🔥 **Numeric mode**: Swipe SW (down-left) on Ctrl key

---

### Key 2: Fn (Second from left)
```xml
<key width="1.1" key0="fn" key1="loc alt" key2="loc change_method"
     key3="switch_emoji" key4="config"/>
```

| Position | Gesture | Function | Description |
|----------|---------|----------|-------------|
| key0 (C) | Tap | **fn** | Function modifier |
| key1 (NW) | Swipe up-left | **alt** | Alt key |
| key2 (NE) | Swipe up-right | **change_method** | Switch keyboard/input method |
| key3 (SW) | Swipe down-left | **switch_emoji** | Switch to emoji keyboard |
| key4 (SE) | Swipe down-right | **config** | **Open settings** |

**Most Used**:
- ⚙️ **Settings**: Swipe SE (down-right) on Fn key
- 😀 **Emoji**: Swipe SW (down-left) on Fn key

---

### Key 3: Spacebar (Center, wide key)
```xml
<key width="4.4" key0="space" key7="switch_forward" key8="switch_backward"
     key5="cursor_left" key6="cursor_right"/>
```

| Position | Gesture | Function | Description |
|----------|---------|----------|-------------|
| key0 (C) | Tap | **space** | Space character |
| key5 (W) | Swipe left | **cursor_left** | Move cursor left |
| key6 (E) | Swipe right | **cursor_right** | Move cursor right |
| key7 (N) | Swipe up | **switch_forward** | Next keyboard layout |
| key8 (S) | Swipe down | **switch_backward** | Previous keyboard layout |

**Most Used**:
- ← **Cursor left**: Swipe left on spacebar
- → **Cursor right**: Swipe right on spacebar

---

### Key 4: Arrow Keys (Second from right)
```xml
<key width="1.1" key0="loc compose" key7="up" key6="right" key5="left" key8="down"
     key1="loc home" key2="loc page_up" key3="loc end" key4="loc page_down"/>
```

| Position | Gesture | Function | Description |
|----------|---------|----------|-------------|
| key0 (C) | Tap | **compose** | Compose key |
| key1 (NW) | Swipe up-left | **home** | Home key |
| key2 (NE) | Swipe up-right | **page_up** | Page Up |
| key3 (SW) | Swipe down-left | **end** | End key |
| key4 (SE) | Swipe down-right | **page_down** | Page Down |
| key5 (W) | Swipe left | **left** | Arrow left |
| key6 (E) | Swipe right | **right** | Arrow right |
| key7 (N) | Swipe up | **up** | Arrow up |
| key8 (S) | Swipe down | **down** | Arrow down |

**Most Used**:
- ↑↓←→ **Arrow keys**: Swipe in direction on arrow key
- Home/End: Swipe NW/SW on arrow key

---

### Key 5: Enter (Bottom-right corner)
```xml
<key width="1.7" key0="enter" key1="loc voice_typing"/>
```

| Position | Gesture | Function | Description |
|----------|---------|----------|-------------|
| key0 (C) | Tap | **enter** | Enter/return key |
| key1 (NW) | Swipe up-left | **voice_typing** | Voice input |

**Most Used**:
- ↩️ **Enter**: Tap on Enter key
- 🎤 **Voice input**: Swipe NW (up-left) on Enter key

---

## 🚀 **Quick Reference Card**

### Essential Gestures

| What You Want | Key | Gesture | Direction |
|---------------|-----|---------|-----------|
| **Clipboard history** | Ctrl (bottom-left) | Swipe NE | Up-right ↗ |
| **Settings** | Fn (2nd from left) | Swipe SE | Down-right ↘ |
| **Numeric keyboard** | Ctrl (bottom-left) | Swipe SW | Down-left ↙ |
| **Emoji keyboard** | Fn (2nd from left) | Swipe SW | Down-left ↙ |
| **Cursor left** | Spacebar | Swipe W | Left ← |
| **Cursor right** | Spacebar | Swipe E | Right → |
| **Arrow up** | Arrow key | Swipe N | Up ↑ |
| **Arrow down** | Arrow key | Swipe S | Down ↓ |
| **Voice input** | Enter (bottom-right) | Swipe NW | Up-left ↖ |

---

## 🎯 **Testing Checklist**

### Bottom Row Tests (All gestures)

**Ctrl Key** (5 functions):
- [ ] Tap → ctrl modifier
- [ ] Swipe NW → meta key
- [ ] Swipe NE → clipboard history (Bug #473 fix)
- [ ] Swipe SW → numeric keyboard (Bug #468 fix)
- [ ] Swipe SE → Greek/math keyboard

**Fn Key** (5 functions):
- [ ] Tap → fn modifier
- [ ] Swipe NW → alt key
- [ ] Swipe NE → change input method
- [ ] Swipe SW → emoji keyboard
- [ ] Swipe SE → settings

**Spacebar** (5 functions):
- [ ] Tap → space character
- [ ] Swipe W → cursor left
- [ ] Swipe E → cursor right
- [ ] Swipe N → next layout
- [ ] Swipe S → previous layout

**Arrow Key** (9 functions):
- [ ] Tap → compose key
- [ ] Swipe NW → home
- [ ] Swipe NE → page up
- [ ] Swipe SW → end
- [ ] Swipe SE → page down
- [ ] Swipe W → arrow left
- [ ] Swipe E → arrow right
- [ ] Swipe N → arrow up
- [ ] Swipe S → arrow down

**Enter Key** (2 functions):
- [ ] Tap → enter/return
- [ ] Swipe NW → voice input

**Total**: 26 gestures across 5 keys

---

## 📋 **Known Issues**

### Bug #473: Clipboard Swipe (FIXED v2 - Nov 20, 2:10 PM)
- **Issue**: Clipboard swipe (Ctrl + NE) did nothing
- **Root Cause**: ClipboardView not added to view hierarchy
- **Fix**: Added clipboard view to container in onCreateInputView()
- **Status**: ✅ FIXED - Ready for testing

### Bug #468: Numeric Keyboard Switching (FIXED - Nov 20, 8:10 AM)
- **Issue**: ABC ↔ 123+ switching broken
- **Fix**: Implemented bidirectional layout switching
- **Status**: ✅ FIXED - Ready for testing

---

## 🔍 **Implementation Details**

### Code Locations

**Gesture Position Mapping**:
- `src/main/kotlin/tribixbite/keyboard2/KeyboardData.kt:236-240`
- Key class with 9 directional positions (index 0-8)

**Bottom Row Layout**:
- `res/xml/bottom_row.xml:2-6`
- Defines key0-key8 mappings for each key

**Event Handling**:
- `src/main/kotlin/tribixbite/keyboard2/CleverKeysService.kt:3977-4025`
- handleSpecialKey() method processes events

**Event Definitions**:
- `src/main/kotlin/tribixbite/keyboard2/KeyValue.kt:34-50`
- Event enum with all keyboard events

---

## 📖 **Related Documentation**

- **BUG_473_CLIPBOARD_SWIPE.md** - Clipboard gesture fix details
- **NUMERIC_KEYBOARD_ISSUE.md** - Numeric keyboard switching (Bug #468)
- **res/xml/bottom_row.xml** - Bottom row gesture definitions
- **src/main/layouts/*.xml** - All keyboard layout files

---

**Last Updated**: November 20, 2025, 2:20 PM
**Status**: Complete documentation
**Next**: User testing of all bottom row gestures

---

## 💡 **Tips for Users**

1. **Swipe Direction Matters**: Each key supports up to 9 different gestures based on swipe direction
2. **Short Swipes**: You don't need long swipes - short directional swipes work best
3. **Visual Feedback**: Key will highlight when gesture is recognized
4. **Practice**: Most common gestures are clipboard (Ctrl + up-right) and settings (Fn + down-right)
5. **Fallback**: If gesture doesn't work, try tapping the key and using alternate method

---

**Bottom Line**: CleverKeys uses a 9-position gesture system (center + 8 directions) allowing each key to have up to 9 functions. The bottom row alone has 26 different gestures across 5 keys.
