# Testing Two Theories - November 21, 2025

## We Now Have 2 Test APKs Ready

Both APKs are in your Downloads folder. Test them in order.

---

## Theory #1: Remove .debug Suffix (70% Confidence)

**APK:** `CleverKeys_TEST_NO_DEBUG_SUFFIX.apk` (51MB)
**Change:** Package name changed from `tribixbite.keyboard2.debug` → `tribixbite.keyboard2`
**Theory:** The `.debug` suffix breaks InputMethodService binding

### Test Steps:
1. Uninstall old CleverKeys
2. Install `CleverKeys_TEST_NO_DEBUG_SUFFIX.apk`
3. Enable "Minimal Test Keyboard"
4. Tap text field - does keyboard appear?

### If It Works:
🎉 Problem solved! The suffix was the issue.

### If It Fails:
Move to Theory #2.

---

## Theory #2: Remove directBootAware (40% Confidence)

**APK:** `CleverKeys_THEORY2_NO_DIRECTBOOT.apk` (51MB)  
**Change:** Removed `android:directBootAware="true"` from services
**Theory:** directBootAware requires special setup we don't have

### Test Steps:
1. Uninstall Theory #1 APK (if installed)
2. Install `CleverKeys_THEORY2_NO_DIRECTBOOT.apk`
3. Enable "Minimal Test Keyboard"
4. Tap text field - does keyboard appear?

### If It Works:
🎉 Problem solved! directBootAware was the issue.

### If It Fails:
We have 3 more theories ready (ProGuard, MultiDex, Dependencies).

---

## Quick Comparison

| Theory | APK Name | Confidence | What Changed |
|--------|----------|------------|--------------|
| #1 | CleverKeys_TEST_NO_DEBUG_SUFFIX.apk | 70% | No .debug suffix |
| #2 | CleverKeys_THEORY2_NO_DIRECTBOOT.apk | 40% | No directBootAware |

---

## What To Report

For each APK you test, just tell me:
1. **APK name** (Theory 1 or Theory 2)
2. **Did keyboard appear?** (YES/NO)

That's all I need!

---

## If Both Fail

We'll move to Theory #3 (ProGuard keep rules) and build another APK.
I have 3 more theories documented and ready to implement.

**Confidence:** 95% one of the 5 theories will solve it.

---

**Status:** 2 theories built and ready for testing  
**Time:** Takes 5 minutes to test each theory  
**Next:** Test Theory #1 first, then Theory #2 if needed
