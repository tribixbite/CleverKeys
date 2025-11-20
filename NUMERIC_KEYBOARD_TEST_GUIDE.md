# Numeric Keyboard Testing Guide
## Manual Testing for Bug #468 Fix

**Date**: 2025-11-20
**Build**: tribixbite.keyboard2.debug.apk (53MB)
**Commit**: ad345b16
**Status**: ✅ **BUILT & INSTALLED** - Ready for Manual Testing

---

## 🎯 What Was Fixed

This build implements the complete numeric keyboard switching functionality:

1. **Bottom Row Corrected**: Ctrl is now primary (center tap), 123+ is at SE corner (swipe)
2. **Numeric Layout Added**: Complete 30+ key numeric/symbol keyboard
3. **ABC Return Button**: Working `switch_text` key to return from numeric mode
4. **Bidirectional Switching**: Full ABC ↔ 123+ switching

---

## ⚡ Quick Test (2 minutes)

### Test 1: Switch to Numeric Keyboard
1. Open any app with a text field (browser, messaging, notes)
2. Tap the text field to show CleverKeys keyboard
3. **Swipe SE** (bottom-right diagonal) on the **leftmost bottom key** (Ctrl key)
4. **Expected**: Keyboard switches to numeric/symbol layout

### Test 2: Verify ABC Return Button
1. While in numeric mode (from Test 1)
2. Look at the **bottom-left key**
3. **Expected**: Should show "ABC" label
4. Tap the ABC key
5. **Expected**: Returns to letter keyboard

### Test 3: Repeat Switching
1. Switch to numeric (SE swipe on Ctrl)
2. Return to ABC (tap ABC button)
3. Repeat 3-5 times
4. **Expected**: Smooth bidirectional switching, no crashes, no keyboard trapping

---

## 📋 Detailed Testing Checklist

### Part 1: Bottom Row Layout Verification
- [ ] Open keyboard in any app
- [ ] Locate leftmost bottom row key
- [ ] **Center tap** should produce: **Ctrl**
- [ ] **SE swipe** (bottom-right diagonal) should trigger: **123+ mode**
- [ ] Verify other bottom keys:
  - Second key: Fn (center)
  - Middle key: Space (center), cursor arrows (swipe)
  - Fourth key: Arrow navigation
  - Fifth key: Enter (center)

### Part 2: Numeric Keyboard Layout
After switching to numeric mode (SE swipe on Ctrl):

**Row 1** (Top Row):
- [ ] Esc key visible (left side)
- [ ] Numbers: 7, 8, 9
- [ ] Operators: *, /
- [ ] Special symbols: (, ), ~, !, <, >, π, ∞, √, ×, ÷, #, %

**Row 2** (Second Row):
- [ ] Tab key visible (left side)
- [ ] Numbers: 4, 5, 6
- [ ] Operators: +, -
- [ ] Special symbols: ), [, ], {, }, |, \, $, ^
- [ ] Box drawing characters
- [ ] Arrow keys (on key 5)

**Row 3** (Third Row):
- [ ] πλ∇¬ key (Greek/math switch)
- [ ] Shift key
- [ ] Numbers: 1, 2, 3
- [ ] Backspace/Delete
- [ ] Superscript, Subscript, Ordinal modifiers
- [ ] Fn, Alt modifiers

**Row 4** (Bottom Row):
- [ ] **ABC** key visible (leftmost) ← **CRITICAL**
- [ ] 0 key
- [ ] Period (.) with variants (: , ;)
- [ ] Space with variants (" ' _ compose)
- [ ] Enter with variants (± action =)

### Part 3: All Keys Functional
While in numeric mode:
- [ ] Tap each number key (0-9) - should type numbers
- [ ] Tap operator keys (+ - * / =) - should type operators
- [ ] Tap symbol keys (parentheses, brackets, etc.) - should type symbols
- [ ] Try corner swipes on keys - should access alternate symbols
- [ ] Backspace works
- [ ] Space works
- [ ] Enter works

### Part 4: Return to ABC Mode
- [ ] In numeric mode, tap the **ABC** button (bottom-left)
- [ ] **Expected**: Immediate return to letter keyboard
- [ ] **Verify**: QWERTY layout visible
- [ ] **Verify**: All letter keys functional
- [ ] **Verify**: No lag or delay

### Part 5: Stress Testing
- [ ] Switch ABC → 123+ → ABC (10 times rapidly)
- [ ] Type some text in ABC mode
- [ ] Switch to 123+, type some numbers
- [ ] Return to ABC, type more text
- [ ] **Expected**: No crashes, no freezing, no keyboard trapping

### Part 6: Edge Cases
- [ ] Close and reopen keyboard → Should return to ABC mode
- [ ] Switch apps while in numeric mode → Reopen → Should be in ABC mode
- [ ] Long-press keys in numeric mode → Should show alternate symbols
- [ ] Try all modifier keys (Shift, Fn, Alt) in numeric mode

---

## ❌ Known Issues to Watch For

If any of these occur, report immediately:

1. **Keyboard Trapping**: Cannot return from numeric mode
   - Symptoms: ABC button missing or non-functional
   - Impact: P0 Blocker (this was the bug we fixed)

2. **Missing Keys**: Some numeric/symbol keys not visible
   - Should have 30+ keys in numeric mode
   - Check all 4 rows completely

3. **Crashes**: App crashes when switching modes
   - Check logcat for errors

4. **Layout Corruption**: Keys in wrong positions or overlapping
   - Take screenshot if this occurs

5. **No Response**: Keys don't respond to taps
   - Test multiple keys to verify

---

## 📸 Screenshot Checklist

Capture these screenshots for verification:

1. **ABC Keyboard** - Show bottom row with 123+ visible
2. **Numeric Keyboard** - Show full numeric layout
3. **ABC Button** - Close-up of ABC button in numeric mode
4. **Repeated Switching** - Show it working after multiple switches

Save to: `~/storage/shared/DCIM/Screenshots/`

---

## 🐛 How to Report Issues

If you find any problems:

1. **Describe the issue**:
   - What did you do?
   - What did you expect?
   - What actually happened?

2. **Provide evidence**:
   - Screenshot showing the problem
   - Logcat output: `adb logcat -s CleverKeysService:* > issue_log.txt`

3. **Reproduction steps**:
   - Exact sequence of actions
   - Can you reproduce it consistently?

4. **Context**:
   - Android version
   - Device model
   - Other keyboards installed?

---

## ✅ Success Criteria

The fix is **SUCCESSFUL** if:

1. ✅ Ctrl is primary on leftmost bottom key
2. ✅ 123+ accessible via SE swipe on Ctrl
3. ✅ Numeric keyboard shows all 30+ keys
4. ✅ ABC button visible and functional in numeric mode
5. ✅ Can switch ABC ↔ 123+ bidirectionally
6. ✅ No keyboard trapping occurs
7. ✅ No crashes or freezes
8. ✅ All keys functional in both modes

---

## 📊 Test Results Template

Copy and fill this out after testing:

```
## Test Results - 2025-11-20

**Tester**: [Your Name]
**Device**: [Model]
**Android**: [Version]

### Quick Tests:
- [ ] Test 1: Switch to Numeric - PASS / FAIL / NOTES
- [ ] Test 2: ABC Return Button - PASS / FAIL / NOTES
- [ ] Test 3: Repeat Switching - PASS / FAIL / NOTES

### Detailed Tests:
- Bottom Row Layout: __ / 5 checks passed
- Numeric Layout: __ / 30+ keys visible
- All Keys Functional: __ / 15 key groups tested
- Return to ABC: __ / 4 checks passed
- Stress Testing: __ / 5 scenarios passed
- Edge Cases: __ / 6 scenarios passed

### Issues Found:
[List any issues discovered]

### Screenshots:
- ABC Keyboard: [filename]
- Numeric Keyboard: [filename]
- ABC Button: [filename]

### Overall Result:
✅ PASS - All tests successful, numeric keyboard working perfectly
❌ FAIL - Critical issues found, see details above
⚠️  PARTIAL - Minor issues, mostly working
```

---

## 🎓 Technical Implementation Details

For reference, here's what was implemented:

### Files Modified:
1. `res/xml/bottom_row.xml` - Fixed key positions (Ctrl primary, 123+ at SE)
2. `src/main/layouts/numeric.xml` - Complete numeric keyboard layout
3. `src/main/kotlin/tribixbite/keyboard2/KeyboardLayoutLoader.kt` - Added numeric layout registration
4. `src/main/kotlin/tribixbite/keyboard2/CleverKeysService.kt` - Implemented switching logic

### Key Components:
- `switchToNumericLayout()`: Loads numeric.xml and saves ABC layout
- `switchToTextLayout()`: Restores saved ABC layout
- `handleSpecialKey()`: Routes SWITCH_NUMERIC and SWITCH_TEXT events
- `mainTextLayout` variable: Stores ABC layout for return
- `isNumericMode` flag: Tracks current mode

### Event Flow:
```
User swipes SE on Ctrl
  ↓
Pointers detects SWITCH_NUMERIC event
  ↓
handleSpecialKey(SWITCH_NUMERIC)
  ↓
switchToNumericLayout()
  ↓
Loads numeric.xml via KeyboardLayoutLoader
  ↓
Sets currentLayout and updates view
  ↓
Numeric keyboard displayed

User taps ABC button
  ↓
Pointers detects SWITCH_TEXT event
  ↓
handleSpecialKey(SWITCH_TEXT)
  ↓
switchToTextLayout()
  ↓
Restores mainTextLayout
  ↓
ABC keyboard displayed
```

---

## 📝 Additional Notes

- The numeric layout is a **copy** of the original Unexpected-Keyboard numeric.xml
- All key definitions use the same XML format as text layouts
- The implementation follows the original Java architecture
- State management ensures proper layout restoration
- Zero compilation errors, zero crashes during build

---

**Next Steps**: Manual testing by user to verify real-world functionality

**Build Location**: `build/outputs/apk/debug/tribixbite.keyboard2.debug.apk`

**Installation**: Already installed via ADB on connected device

---

**🎉 Ready for Testing!** Please run through the checklist and report results.
