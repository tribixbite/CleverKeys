# CleverKeys v2.1 Testing Checklist

**Build Date**: November 20, 2025, 7:42 PM (Updated)
**APK Location**: `build/outputs/apk/debug/tribixbite.keyboard2.debug.apk`
**APK Size**: 53MB
**Features to Test**: 3 Priority 1 + 1 Priority 2 = 4 features total

---

## 📦 Installation

```bash
# Install APK (choose one method)
adb install -r build/outputs/apk/debug/tribixbite.keyboard2.debug.apk

# OR use build script
./build-and-install.sh
```

**Prerequisites**:
- Enable CleverKeys in Android Settings → System → Languages & Input → Virtual Keyboard
- Set CleverKeys as default keyboard

---

## ✅ Feature #1: Emoji Picker System

### Access Method
1. Open any text field (SMS, notes app, etc.)
2. CleverKeys keyboard should appear
3. Look for emoji button on keyboard (🙂 icon or similar)
4. Tap emoji button to open picker

### Test Cases

#### TC1.1: Emoji Categories
- [ ] **PASS/FAIL**: All 9 categories visible (Recent, Smileys, People, Animals, Food, Travel, Activities, Objects, Symbols, FLAGS)
- [ ] **PASS/FAIL**: Category icons display correctly
- [ ] **PASS/FAIL**: Tapping category switches emoji grid
- [ ] **PASS/FAIL**: Smooth category transitions

#### TC1.2: Emoji Selection
- [ ] **PASS/FAIL**: Tap emoji inserts into text field
- [ ] **PASS/FAIL**: Emoji appears immediately in text
- [ ] **PASS/FAIL**: Can insert multiple emojis consecutively
- [ ] **PASS/FAIL**: Emoji picker remains open after selection

#### TC1.3: Search Functionality
- [ ] **PASS/FAIL**: Search bar visible at top
- [ ] **PASS/FAIL**: Typing filters emojis by keyword
- [ ] **PASS/FAIL**: Search matches emoji descriptions (e.g., "smile" shows 😀)
- [ ] **PASS/FAIL**: Search is case-insensitive
- [ ] **PASS/FAIL**: Empty search shows all emojis

#### TC1.4: Recently Used Emojis
- [ ] **PASS/FAIL**: Recent category initially empty
- [ ] **PASS/FAIL**: Selected emojis appear in Recent category
- [ ] **PASS/FAIL**: Most recent emoji shows first
- [ ] **PASS/FAIL**: Recents persist after closing picker
- [ ] **PASS/FAIL**: Max 30 recents displayed

#### TC1.5: UI/UX
- [ ] **PASS/FAIL**: Material 3 design (rounded corners, proper colors)
- [ ] **PASS/FAIL**: Emojis display in scrollable grid
- [ ] **PASS/FAIL**: Smooth scrolling performance
- [ ] **PASS/FAIL**: Close/back button dismisses picker
- [ ] **PASS/FAIL**: Returns to keyboard after dismiss

**Emoji Picker Score**: ___/20 tests passed

---

## ✅ Feature #2: Swipe-to-Dismiss Suggestions

### Access Method
1. Open text field with keyboard visible
2. Type a word with suggestions (e.g., "hel" → "hello", "help", "held")
3. Suggestion bar should appear above keyboard

### Test Cases

#### TC2.1: Swipe Gesture Detection
- [ ] **PASS/FAIL**: Can swipe suggestion left (left-to-right drag)
- [ ] **PASS/FAIL**: Can swipe suggestion right (right-to-left drag)
- [ ] **PASS/FAIL**: Swipe moves suggestion horizontally
- [ ] **PASS/FAIL**: Suggestion follows finger during drag

#### TC2.2: Dismissal Behavior
- [ ] **PASS/FAIL**: Swipe >150px dismisses suggestion
- [ ] **PASS/FAIL**: Dismissed suggestion disappears from bar
- [ ] **PASS/FAIL**: Remaining suggestions shift position
- [ ] **PASS/FAIL**: Smooth dismissal animation (200ms)

#### TC2.3: Cancellation Behavior
- [ ] **PASS/FAIL**: Swipe <150px returns suggestion to position
- [ ] **PASS/FAIL**: Spring-back animation is smooth
- [ ] **PASS/FAIL**: Suggestion bounces slightly on return
- [ ] **PASS/FAIL**: Released suggestion remains tappable

#### TC2.4: Visual Feedback
- [ ] **PASS/FAIL**: Suggestion fades during swipe (alpha reduction)
- [ ] **PASS/FAIL**: Fade effect proportional to swipe distance
- [ ] **PASS/FAIL**: Fully opaque when at rest
- [ ] **PASS/FAIL**: No visual glitches during animation

#### TC2.5: Multi-Suggestion Testing
- [ ] **PASS/FAIL**: Can dismiss multiple suggestions sequentially
- [ ] **PASS/FAIL**: Each suggestion swipes independently
- [ ] **PASS/FAIL**: Dismissing first suggestion works
- [ ] **PASS/FAIL**: Dismissing last suggestion works

**Swipe-to-Dismiss Score**: ___/17 tests passed

---

## ✅ Feature #3: Layout Test Interface

### Access Method
1. Open Android app drawer
2. Find "CleverKeys" app icon
3. Tap to open (should show CustomLayoutEditor)
4. Tap "🧪 Test" button on toolbar

### Test Cases

#### TC3.1: Test Dialog Display
- [ ] **PASS/FAIL**: Test dialog opens successfully
- [ ] **PASS/FAIL**: Dialog title shows "🧪 Test Layout - Interactive Mode"
- [ ] **PASS/FAIL**: Dialog is ~95% screen width
- [ ] **PASS/FAIL**: Instructions visible: "Tap keys to test layout behavior"

#### TC3.2: Feedback Display
- [ ] **PASS/FAIL**: Feedback area shows "Tap a key to see output..."
- [ ] **PASS/FAIL**: Feedback area is gray background, centered text
- [ ] **PASS/FAIL**: Feedback updates when key pressed
- [ ] **PASS/FAIL**: Shows key type (Char/String/Event/Modifier)

#### TC3.3: Interactive Keyboard Preview
- [ ] **PASS/FAIL**: Keyboard layout renders correctly (3 rows visible)
- [ ] **PASS/FAIL**: Keys display correct labels (q, w, e, r, t, y, etc.)
- [ ] **PASS/FAIL**: Keys have rounded corners (8dp)
- [ ] **PASS/FAIL**: Key borders visible and clean

#### TC3.4: Touch Detection
- [ ] **PASS/FAIL**: Tapping key highlights it (darker gray)
- [ ] **PASS/FAIL**: Highlight appears immediately on touch
- [ ] **PASS/FAIL**: Highlight removes when finger lifts
- [ ] **PASS/FAIL**: Can tap multiple keys in sequence

#### TC3.5: Haptic Feedback
- [ ] **PASS/FAIL**: Phone vibrates on key press (20ms)
- [ ] **PASS/FAIL**: Vibration feels tactile and responsive
- [ ] **PASS/FAIL**: Each key press triggers vibration
- [ ] **PASS/FAIL**: No vibration lag or delay

#### TC3.6: Layout Statistics
- [ ] **PASS/FAIL**: Footer shows "Layout: 3 rows, 30 keys" (or similar)
- [ ] **PASS/FAIL**: Statistics are accurate
- [ ] **PASS/FAIL**: Text is readable (gray color)

#### TC3.7: Dialog Controls
- [ ] **PASS/FAIL**: "Close" button visible
- [ ] **PASS/FAIL**: Close button dismisses dialog
- [ ] **PASS/FAIL**: Can reopen test dialog after closing
- [ ] **PASS/FAIL**: No memory leaks or crashes

**Layout Test Interface Score**: ___/25 tests passed

---

## ✅ Feature #4: Word Info Dialog (Priority 2)

### Access Method
1. Open text field with keyboard visible
2. Type a word to show suggestions (e.g., "hel" → "hello", "help")
3. **Long-press** (tap and hold) any suggestion chip
4. Word info dialog should appear

### Test Cases

#### TC4.1: Dialog Display
- [ ] **PASS/FAIL**: Dialog opens on long-press
- [ ] **PASS/FAIL**: Dialog title shows "Word Information"
- [ ] **PASS/FAIL**: Info icon visible in header
- [ ] **PASS/FAIL**: Dialog positioned centrally on screen

#### TC4.2: Word Display
- [ ] **PASS/FAIL**: Large word display in card
- [ ] **PASS/FAIL**: Word text is readable and clear
- [ ] **PASS/FAIL**: Card has proper background color
- [ ] **PASS/FAIL**: Word matches long-pressed suggestion

#### TC4.3: Information Display
- [ ] **PASS/FAIL**: Confidence score shows (if available)
- [ ] **PASS/FAIL**: Confidence displayed as percentage (0-100%)
- [ ] **PASS/FAIL**: Source label shows "Neural Prediction"
- [ ] **PASS/FAIL**: Word length shows correctly (character count)

#### TC4.4: Dialog Actions
- [ ] **PASS/FAIL**: "Insert Word" button visible
- [ ] **PASS/FAIL**: "Close" button visible
- [ ] **PASS/FAIL**: Tapping "Insert Word" inserts suggestion
- [ ] **PASS/FAIL**: Dialog closes after inserting word
- [ ] **PASS/FAIL**: "Close" button dismisses dialog
- [ ] **PASS/FAIL**: Can reopen dialog after closing

#### TC4.5: Material 3 Design
- [ ] **PASS/FAIL**: Proper elevation and shadows
- [ ] **PASS/FAIL**: Material 3 color scheme
- [ ] **PASS/FAIL**: Smooth open/close animations
- [ ] **PASS/FAIL**: Responsive touch interactions

**Word Info Dialog Score**: ___/18 tests passed

---

## 📊 Overall Test Results

**Feature #1 (Emoji Picker)**: ___/20 passed (___%)
**Feature #2 (Swipe-to-Dismiss)**: ___/17 passed (___%)
**Feature #3 (Layout Test Interface)**: ___/25 passed (___%)
**Feature #4 (Word Info Dialog)**: ___/18 passed (___%)

**Total Score**: ___/80 tests passed (___%)

---

## 🐛 Bug Report Template

If any test fails, document here:

### Bug #___: [Short Description]
- **Feature**: Emoji Picker / Swipe-to-Dismiss / Layout Test Interface / Word Info Dialog
- **Test Case**: TC#.#
- **Expected**: [What should happen]
- **Actual**: [What actually happened]
- **Steps to Reproduce**:
  1. Step one
  2. Step two
  3. Step three
- **Severity**: Critical / High / Medium / Low
- **Screenshots**: [If applicable]

---

## ✅ Testing Complete Checklist

- [ ] All 80 test cases executed
- [ ] Test scores calculated
- [ ] Bug reports documented (if any)
- [ ] APK tested on device (not emulator)
- [ ] Results shared with development team

---

**Tester Name**: _________________________
**Device Model**: _________________________
**Android Version**: _____________________
**Test Date**: ___________________________
**Test Duration**: _______________________

---

*v2.1 Features Testing Checklist (Priority 1 + Priority 2)*
*Generated: November 20, 2025*
*Updated: November 20, 2025, 7:42 PM*
