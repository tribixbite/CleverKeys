# CleverKeys Feature Test Checklist

**Date**: 2025-11-19
**Version**: 1.1.x (57MB APK with 49k dictionary)

---

## Prerequisites
- [ ] APK installed (`tribixbite.keyboard2.debug.apk`)
- [ ] Keyboard enabled in Settings > System > Languages & input
- [ ] CleverKeys selected as active keyboard

---

## Core Keyboard Tests

### 1. Basic Typing
- [ ] **Key tap**: Tap individual keys, letters appear correctly
- [ ] **Backspace**: Delete characters
- [ ] **Space**: Add spaces between words
- [ ] **Enter**: New line in multiline fields
- [ ] **Shift**: Uppercase letters (single tap = one letter, double = caps lock)
- [ ] **Numbers/Symbols**: Access via shift or long-press

### 2. Suggestion Bar
- [ ] **Predictions appear**: Suggestions show while typing
- [ ] **Tap suggestion**: Word is inserted
- [ ] **Updates dynamically**: Suggestions change with each keystroke
- [ ] **Shows confidence**: Higher confidence words should appear first

### 3. Swipe Typing (Glide)
- [ ] **Swipe gesture recognized**: Draw path across keys
- [ ] **Word prediction**: Correct word predicted from swipe
- [ ] **Multiple suggestions**: Shows top candidates
- [ ] **Tap to select**: Select from suggestions

---

## Dictionary Tests

### 4. Dictionary Manager (Settings > Dictionary)
- [ ] **Opens without crash**: Activity loads correctly
- [ ] **Tab 1 - User Words**: Add/remove custom words
- [ ] **Tab 2 - Built-in**: Shows ~49,000 words (not 10k)
- [ ] **Tab 3 - Disabled**: Manage blacklisted words
- [ ] **Search works**: Filter words in each tab

### 5. User Dictionary
- [ ] **Add word**: Custom word added successfully
- [ ] **Word appears in suggestions**: Custom words show up while typing
- [ ] **Remove word**: Word removed from dictionary
- [ ] **Persists after restart**: Words saved permanently

### 6. Disabled Words
- [ ] **Disable built-in word**: Word no longer appears in suggestions
- [ ] **Re-enable word**: Word appears again
- [ ] **Clear all**: Remove all disabled words

---

## Layout Tests

### 7. Keyboard Layouts
- [ ] **Default QWERTY**: Keys in correct positions
- [ ] **Layout switch**: Can change layouts in settings
- [ ] **Custom layout**: Can add custom XML layout
- [ ] **Multiple layouts**: Can have multiple layouts enabled

### 8. Visual Display
- [ ] **Keys visible**: All keys render correctly
- [ ] **Dark theme**: Dark mode appearance
- [ ] **Key labels**: Letters/symbols visible
- [ ] **Key highlights**: Visual feedback on tap

---

## Settings Tests

### 9. Preferences
- [ ] **Settings open**: No crash on opening
- [ ] **Theme selection**: Can change keyboard theme
- [ ] **Height adjustment**: Keyboard height configurable
- [ ] **Vibration**: Haptic feedback toggle
- [ ] **Sound**: Key click toggle

### 10. Backup/Restore
- [ ] **Export settings**: Can export configuration
- [ ] **Import settings**: Can import configuration
- [ ] **User words included**: Custom dictionary backed up

---

## Performance Tests

### 11. Responsiveness
- [ ] **No lag on typing**: Keys respond instantly
- [ ] **Smooth scrolling**: Dictionary lists scroll smoothly
- [ ] **Quick startup**: Keyboard appears quickly when invoked

### 12. Stability
- [ ] **No crashes**: Extended use without crashes
- [ ] **Memory stable**: No increasing memory usage
- [ ] **Works in all apps**: Compatible with various text fields

---

## Lifecycle Tests

### 13. App Lifecycle
- [ ] **Rotate device**: Keyboard recovers correctly
- [ ] **Switch apps**: Keyboard state preserved
- [ ] **Lock/unlock**: Keyboard works after unlock
- [ ] **Background/foreground**: No issues on app switching

---

## Test Results

### Summary
- **Pass**: ___
- **Fail**: ___
- **Blocked**: ___
- **Total**: 35 test items

### Issues Found
1.
2.
3.

### Notes


---

## Quick Test Script (3 minutes)

1. Open any text app (Notes, Browser search, etc.)
2. Type "hello world" - verify keys work
3. Swipe "the" - verify swipe works
4. Tap a suggestion - verify it inserts
5. Go to Settings > Dictionary
6. Verify "Built-in Dictionary" tab shows ~49k words
7. Add a custom word "mytest123"
8. Go back to typing - verify "mytest123" appears in suggestions
9. Done!

---

**Tested by**: _______________
**Date**: _______________
**Build**: _______________
