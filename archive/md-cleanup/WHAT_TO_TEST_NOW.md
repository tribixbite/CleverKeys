# What To Test Now - CleverKeys v2.0.2

**Date**: November 20, 2025, 2:35 PM
**Build**: v2.0.2 Build 57 (53MB APK)
**Status**: ✅ All code complete - Ready for manual testing

---

## 🚀 **Quick Start** (3-5 Minutes)

You need to test **3 gestures** that were fixed/documented today:

1. **Clipboard swipe** (Bug #473 fix v2)
2. **Numeric keyboard** (Bug #468 fix)
3. **Settings swipe** (your question answered)

**What you need**: Any text app (Messages, Notes, etc.)

---

## 📋 **Test 1: Clipboard Swipe (Bug #473)**

### What You Reported:
> "short swipe for clip board does nothing."

### What Was Fixed:
- **Problem**: Clipboard view was never added to view hierarchy
- **Fix v2**: Added clipboard view to container during initialization
- **Changed**: `CleverKeysService.kt` - view hierarchy corrected

### How to Test:
1. Open any text app
2. Tap in text field to show keyboard
3. **Swipe NE (up-right ↗) on Ctrl key** (bottom-left corner)
   - Start from key center, swipe diagonally up and right
4. **Expected**: Clipboard history view appears (overlays keyboard)
5. Tap any clipboard item
6. **Expected**: Text is inserted, keyboard returns
7. **Verify**: The text you selected was inserted correctly

### What Success Looks Like:
- ✅ Keyboard disappears
- ✅ Clipboard view appears with your clipboard history
- ✅ Tapping an item inserts it
- ✅ Keyboard returns automatically
- ✅ Inserted text is correct

### If It Doesn't Work:
- Take a screenshot showing the keyboard
- Report: "Clipboard test failed: [describe what happened]"
- I'll debug the view hierarchy

---

## 🔢 **Test 2: Numeric Keyboard (Bug #468)**

### What Was Fixed:
- **Problem**: Users trapped in numeric mode, no ABC button to return
- **Fix**: Bidirectional ABC ↔ 123+ switching
- **Changed**: `bottom_row.xml`, `numeric.xml`, `CleverKeysService.kt`

### How to Test:
1. Open any text app
2. Tap in text field to show keyboard (should be in ABC mode)
3. **Swipe SW (down-left ↙) on Ctrl key** (bottom-left corner)
   - Start from key center, swipe diagonally down and left
4. **Expected**: Switches to numeric keyboard (123+ mode)
5. **Verify**: ABC button is visible (to return to letters)
6. Tap ABC button
7. **Expected**: Switches back to letter keyboard

### What Success Looks Like:
- ✅ ABC → 123+: Keyboard shows numbers and symbols
- ✅ ABC button visible in numeric mode (top-left or bottom-left)
- ✅ 123+ → ABC: Returns to letter keyboard
- ✅ Can switch back and forth multiple times

### If It Doesn't Work:
- Report which step failed:
  - "Can't switch to numeric" OR
  - "No ABC button in numeric mode" OR
  - "ABC button doesn't work"

---

## ⚙️ **Test 3: Settings Swipe**

### What You Asked:
> "wheres the short swipe to settings"

### Answer:
- **Fn key** (2nd from left on bottom row)
- **Swipe SE (down-right ↘)**

### How to Test:
1. Open any text app
2. Tap in text field to show keyboard
3. **Swipe SE (down-right ↘) on Fn key** (2nd from left, bottom row)
   - Start from key center, swipe diagonally down and right
4. **Expected**: Settings screen opens

### What Success Looks Like:
- ✅ Settings app/screen appears
- ✅ Shows keyboard configuration options

### If It Doesn't Work:
- Report: "Settings swipe failed: [describe what happened]"

---

## 🎯 **Gesture Quick Reference**

### Bottom Row Key Layout:
```
[Ctrl] [Fn] [___Spacebar___] [Arrow] [Enter]
```

### Swipe Directions:
```
   NW   N   NE
   (↖)  (↑)  (↗)

   W    C    E
   (←)  (·)  (→)

   SW   S   SE
   (↙)  (↓)  (↘)
```

### Essential Gestures:
| What | Key | Direction | Visual |
|------|-----|-----------|--------|
| **Clipboard** | Ctrl | NE | Up-right ↗ |
| **Numeric** | Ctrl | SW | Down-left ↙ |
| **Settings** | Fn | SE | Down-right ↘ |

**Tip**: Short swipes work best - you don't need long sweeping gestures.

---

## ✅ **Reporting Results**

### If All Tests Pass:
Just reply: **"All 3 tests pass"**

### If Any Test Fails:
Report which test and what happened:
```
Test 1 (Clipboard): [PASS or FAIL - description]
Test 2 (Numeric): [PASS or FAIL - description]
Test 3 (Settings): [PASS or FAIL - description]
```

### Helpful Details:
- Which gesture didn't work
- What happened instead
- Any error messages
- Screenshots if possible

---

## 📖 **Full Documentation**

If you want more details, see:

1. **GESTURE_REFERENCE.md** - Complete gesture guide (26 gestures, all keys)
2. **BUG_473_CLIPBOARD_SWIPE.md** - Clipboard fix investigation (575 lines)
3. **SESSION_BUG473_NOV_20_2025.md** - Complete session log (540 lines)
4. **PROJECT_STATUS.md** - Overall project status

---

## 🎯 **What Happens Next**

### If All Tests Pass:
- ✅ Production score → 100/100 (Grade A+)
- ✅ v2.0.2 declared production-ready
- ✅ Can proceed with v2.1 planning (accessibility + visual polish)

### If Any Tests Fail:
- I'll debug and fix the issues
- Rebuild APK
- Retest (usually quick)
- Iterate until all pass

---

## 💡 **Tips**

1. **Swipe Direction**: Make sure you swipe in the right direction (see arrows above)
2. **Key Highlight**: Key should highlight when you touch it (shows gesture recognized)
3. **Short Swipes**: Short directional swipes work better than long sweeps
4. **Practice**: If unsure, try the gesture a few times to get the feel
5. **Fallback**: If gestures don't work, we can add visual gesture hints in v2.1

---

**Bottom Line**:
- 3 gestures to test: clipboard, numeric, settings
- 3-5 minutes total
- Report pass/fail
- If all pass → 100/100 production score ✅

**Ready when you are!**

---

**Last Updated**: November 20, 2025, 2:35 PM
**Build**: v2.0.2 Build 57 (53MB, installed via termux-open)
**Status**: ⏳ Awaiting your testing
