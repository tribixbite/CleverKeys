# Testing Guide - Next Steps for CleverKeys v1.0 with Fixes

**Date**: November 16, 2025
**APK Status**: ✅ **INSTALLED SUCCESSFULLY** (51MB, Nov 16 @ 1:18 PM)
**Build Includes**: Bug #471 (Clipboard Search) + Bug #472 (Dictionary UI)

---

## ✅ INSTALLATION CONFIRMED

**Package**: `tribixbite.keyboard2.debug`
**Installed APK**: `/data/app/.../base.apk` (51MB, timestamp matches new build)
**Installation Method**: termux-open → Android Package Installer → User approved

---

## 📱 MANUAL TESTING REQUIRED (On Physical Device)

### **Step 1: Enable CleverKeys Keyboard** (5 minutes)

1. Open **Settings** app on your Android device
2. Navigate to **System** → **Languages & input** → **On-screen keyboard** (path may vary by device)
3. Tap **Manage on-screen keyboards**
4. Enable **CleverKeys** (toggle switch to ON)
5. Return to home screen
6. Open any app with text input (e.g., Messages, Notes, Chrome)
7. Long-press the spacebar or tap keyboard switcher icon
8. Select **CleverKeys** from the keyboard list

**Expected**: CleverKeys keyboard appears with Material 3 design

---

### **Step 2: Basic Smoke Test** (5 minutes)

**In any text field with CleverKeys active:**

- [ ] Tap letters (a-z) → letters appear
- [ ] Tap numbers/symbols → correct characters appear
- [ ] Tap backspace → deletes characters
- [ ] Tap space bar → adds space
- [ ] Swipe across letters → word prediction appears
- [ ] No immediate crashes or freezes

**Expected**: All basic typing functionality works

---

### **Step 3: Test Bug #471 Fix - Clipboard Search** (15-20 minutes)

**CRITICAL: This feature was MISSING before this build**

#### Setup:
1. Copy 10+ different text snippets from various apps to build clipboard history
2. Open any text input field with CleverKeys keyboard
3. Tap the clipboard icon/button on the keyboard

#### Test Cases:

**3.1 Search Field Presence**
- [ ] Search field appears at TOP of clipboard view
- [ ] Placeholder text shows: "Search clipboard…"
- [ ] Field is editable and accepts input

**3.2 Real-Time Filtering**
- [ ] Copy these items first:
  - "Hello World"
  - "Testing 123"
  - "CleverKeys keyboard"
  - "Android development"
  - "Bug fix verification"
- [ ] In search field, type: **"test"** (lowercase)
- [ ] **EXPECTED**: Only "Testing 123" shown
- [ ] Clear search field
- [ ] **EXPECTED**: All 5 items shown again

**3.3 Case-Insensitive Matching**
- [ ] Type in search: **"HELLO"** (uppercase)
- [ ] **EXPECTED**: "Hello World" found and shown
- [ ] Type: **"world"** (lowercase)
- [ ] **EXPECTED**: "Hello World" found and shown
- [ ] Type: **"KeYbOaRd"** (mixed case)
- [ ] **EXPECTED**: "CleverKeys keyboard" found and shown

**3.4 No Results Message**
- [ ] Type in search: **"xyzabc999nonsense"**
- [ ] **EXPECTED**: "No matching items found" message appears
- [ ] **EXPECTED**: Empty list (no items shown)

**3.5 Search Performance**
- [ ] Add 20+ clipboard items
- [ ] Type quickly in search field
- [ ] **EXPECTED**: Filtering is instant (<100ms)
- [ ] **EXPECTED**: No lag, keyboard remains responsive

#### Bug #471 Result: ⬜ **PASS** / ⬜ **FAIL**

**Notes** (if FAIL, describe what's broken):


---

### **Step 4: Test Bug #472 Fix - Dictionary Management UI** (30-45 minutes)

**CRITICAL: This feature was COMPLETELY MISSING before this build**

#### 4.1 Access Dictionary Manager

1. Open **CleverKeys Settings** (long-press keyboard, tap Settings icon, OR open from app list)
2. Scroll down to find: **"📖 Dictionary"** section
3. Section should show description: *"Add custom words to improve predictions..."*
4. Tap button: **"Manage Custom Words"**

**Expected**: New screen opens with title "Custom Dictionary"

**4.1 Result**: ⬜ PASS / ⬜ FAIL

---

#### 4.2 Empty State (First Time)

**What you should see**:
- [ ] Message: "No custom words yet"
- [ ] Description: "Add words to improve predictions..."
- [ ] Button: "Add Your First Word"
- [ ] FAB (Floating Action Button) with **+** icon at bottom right
- [ ] Word count: "0 custom words"

**4.2 Result**: ⬜ PASS / ⬜ FAIL

---

#### 4.3 Add Word - Validation Testing

**Test Case 1: Empty Word**
- [ ] Tap FAB or "Add Your First Word"
- [ ] Leave field empty, tap "Add"
- [ ] **EXPECTED**: Red error message: "Word cannot be empty"

**Test Case 2: Too Short**
- [ ] Type: **"A"** (1 character), tap "Add"
- [ ] **EXPECTED**: Red error: "Word must be at least 2 characters"

**Test Case 3: Valid Word**
- [ ] Type: **"Anthropic"**, tap "Add"
- [ ] **EXPECTED**: Dialog closes
- [ ] **EXPECTED**: Toast message: "Added 'Anthropic' to dictionary"
- [ ] **EXPECTED**: "Anthropic" appears in word list
- [ ] **EXPECTED**: Word count updates to "1 custom words"

**Test Case 4: Duplicate Word**
- [ ] Tap FAB again
- [ ] Type: **"Anthropic"** (same word), tap "Add"
- [ ] **EXPECTED**: Red error: "This word is already in your dictionary"

**4.3 Result**: ⬜ PASS / ⬜ FAIL

---

#### 4.4 Word List Display

**Add these words** (tap FAB, add each):
- "Kubernetes"
- "PostgreSQL"
- "Docker"
- "React"
- "TypeScript"

**Verify**:
- [ ] All 5 words appear in the list
- [ ] Words are **alphabetically sorted** (Docker, Kubernetes, PostgreSQL, React, TypeScript)
- [ ] Each word shows:
  - [ ] Word text on LEFT side
  - [ ] Red trash icon (delete button) on RIGHT side
- [ ] Word count shows: "6 custom words" (including Anthropic from before)
- [ ] List scrolls smoothly if needed

**4.4 Result**: ⬜ PASS / ⬜ FAIL

---

#### 4.5 Delete Word

- [ ] Tap the trash icon next to **"Anthropic"**
- [ ] **EXPECTED**: Word removed from list **immediately** (no confirmation dialog)
- [ ] **EXPECTED**: Toast message: "Removed 'Anthropic' from dictionary"
- [ ] **EXPECTED**: Word count decreases to "5 custom words"
- [ ] **EXPECTED**: List re-sorted alphabetically

**4.5 Result**: ⬜ PASS / ⬜ FAIL

---

#### 4.6 Integration with Predictions (MOST CRITICAL TEST!)

**This verifies the dictionary actually WORKS with the keyboard**

**Test Case 1: Custom Word Appears in Predictions**
1. Add custom word: **"CleverKeys"** (tap FAB, type, add)
2. Tap "Back" button to return to Settings
3. Close Settings, open any text input field (e.g., Notes app)
4. Make sure CleverKeys keyboard is active
5. Type: **"Clever"** or **"Clev"** (partial match)
6. **EXPECTED**: **"CleverKeys"** appears in the suggestion/prediction bar at top of keyboard
7. **EXPECTED**: Tapping the suggestion inserts "CleverKeys"

**Test Case 2: Deleted Word Removed from Predictions**
1. Return to CleverKeys Settings → Dictionary Manager
2. Delete **"CleverKeys"** from the word list (tap trash icon)
3. Return to text input field
4. Clear the text field
5. Type: **"Clever"** again
6. **EXPECTED**: **"CleverKeys"** does NOT appear in predictions anymore
7. **EXPECTED**: Only default dictionary words shown

**4.6 Result**: ⬜ PASS / ⬜ FAIL

**Notes** (if FAIL, describe what happened):


---

#### 4.7 Navigation & Persistence

**Navigation Test**:
- [ ] Tap "Back" arrow in Dictionary Manager → returns to Settings
- [ ] Reopen Dictionary Manager → words still present
- [ ] System back button works (same as Back arrow)

**Persistence Test**:
1. Add 3 new words: "Android", "Kotlin", "Termux"
2. Close Dictionary Manager
3. Close Settings app completely (swipe away from recent apps)
4. **Optional but recommended**: Reboot device
5. Reopen Settings → CleverKeys → Dictionary Manager
6. **EXPECTED**: All 8 words still present (Docker, Kubernetes, PostgreSQL, React, TypeScript, Android, Kotlin, Termux)

**4.7 Result**: ⬜ PASS / ⬜ FAIL

---

#### 4.8 Performance Test (Optional, but recommended)

**Add 50 words** (use FAB repeatedly, or add quickly):
- "Word01", "Word02", "Word03", ... "Word50"
- OR technical terms: library names, API names, etc.

**Verify**:
- [ ] List renders quickly (<500ms with 50 words)
- [ ] Scrolling remains smooth (60fps target)
- [ ] Add/delete operations remain instant
- [ ] No lag in UI

**4.8 Result**: ⬜ PASS / ⬜ FAIL

---

### **Bug #472 Overall Result**: ⬜ **PASS** / ⬜ **FAIL**

---

## 📊 TESTING SUMMARY

**Fill out after completing all tests:**

### Test Results
- **Bug #471 (Clipboard Search)**: ⬜ PASS / ⬜ FAIL
- **Bug #472 (Dictionary UI)**: ⬜ PASS / ⬜ FAIL
- **Basic Functionality (Smoke Test)**: ⬜ PASS / ⬜ FAIL

### Bugs Found (if any)
**If any tests FAILED, describe here:**

1. **Bug Title**:
   - **Severity**: Critical / High / Medium / Low
   - **Steps to Reproduce**:
   - **Expected**:
   - **Actual**:
   - **Screenshot** (if possible):

2. (Add more if needed)

---

## ✅ WHAT TO DO NEXT

### If ALL Tests PASS:
1. Update `DEVICE_TESTING_SESSION_LOG.md` with PASS results
2. Mark Bug #471 and #472 as **VERIFIED FIXED** on device
3. Create git tag: `v1.0-verified`
4. Consider this build **PRODUCTION READY**
5. Proceed to full Phase 2-5 testing (multi-language, emoji, stress tests, etc.)

### If ANY Test FAILS:
1. Document the failure in `DEVICE_TESTING_SESSION_LOG.md`
2. Report the issue (file path, expected vs actual behavior)
3. Return to code, fix the bug
4. Rebuild APK, reinstall, retest

---

## 📝 QUICK REFERENCE

**Most Critical Tests** (Must Pass for v1.0):
1. ✅ Keyboard installs and enables
2. ✅ Basic typing works (tap, swipe, backspace)
3. ✅ Clipboard search field exists and filters items
4. ✅ Dictionary Manager UI opens from Settings
5. ✅ Custom words can be added/deleted
6. **✅ Custom words appear in predictions** ← **MOST IMPORTANT**

**Testing Duration Estimate**:
- Step 1: Enable keyboard (5 min)
- Step 2: Smoke test (5 min)
- Step 3: Clipboard search (15-20 min)
- Step 4: Dictionary UI (30-45 min)
- **Total**: ~60-75 minutes

---

**Document Created**: November 16, 2025
**Status**: Ready for manual device testing
**Tester**: (Your name/initials after testing)

---

**End of Testing Guide**
