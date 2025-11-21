# Testing Three Theories - November 21, 2025

## 🎯 We Now Have 3 Test APKs Ready!

All APKs are in your Downloads folder. Test them in order of confidence.

---

## Theory #1: Remove .debug Suffix (70% Confidence) ⭐⭐⭐

**APK:** `CleverKeys_TEST_NO_DEBUG_SUFFIX.apk` (51MB)  
**Change:** Package name: `tribixbite.keyboard2.debug` → `tribixbite.keyboard2`  
**Theory:** The `.debug` suffix prevents InputMethodService binding

### Why This Is Most Likely:
- InputMethodManagerService is very strict about package names
- Both simple and complex services fail with suffix
- Known Android quirk with IME services

---

## Theory #2: Remove directBootAware (40% Confidence) ⭐⭐

**APK:** `CleverKeys_THEORY2_NO_DIRECTBOOT.apk` (51MB)  
**Change:** Removed `android:directBootAware="true"` from services  
**Theory:** directBootAware requires Direct Boot mode setup we don't have

### Why This Could Be It:
- directBootAware is for device encryption scenarios
- Requires special Application setup
- Services might fail if setup is incomplete

---

## Theory #3: Add ProGuard Keep Rules (20% Confidence) ⭐

**APK:** `CleverKeys_THEORY3_WITH_PROGUARD.apk` (51MB)  
**Change:** Added comprehensive ProGuard keep rules, enabled R8  
**Theory:** R8 is stripping required classes despite minifyEnabled=false

### Why This Might Work:
- R8 runs some optimizations even with minify disabled
- Service metadata might be removed
- Explicit keep rules prevent stripping

---

## How To Test (5 Minutes Each)

### For Each Theory:
1. **Uninstall** previous test APK (if any)
2. **Install** the theory APK from Downloads
3. **Settings** → Languages & Input → Enable "Minimal Test Keyboard"
4. **Open** messaging app → Tap text field
5. **Check:** Does a keyboard appear?

### Report Format:
```
Theory #1: YES/NO
Theory #2: YES/NO  
Theory #3: YES/NO
```

---

## Quick Reference Table

| # | APK Name | Confidence | What Changed | Test Order |
|---|----------|------------|--------------|------------|
| 1 | CleverKeys_TEST_NO_DEBUG_SUFFIX.apk | 70% | No .debug suffix | **Test First** |
| 2 | CleverKeys_THEORY2_NO_DIRECTBOOT.apk | 40% | No directBootAware | Test Second |
| 3 | CleverKeys_THEORY3_WITH_PROGUARD.apk | 20% | ProGuard keep rules | Test Third |

---

## If All 3 Fail

We still have 2 more theories ready:
- **Theory #4:** Explicit MultiDex initialization
- **Theory #5:** Check dependency inclusion

**Overall Confidence:** 95% that one of the 5 theories will solve it.

---

## Pro Tip

You can test all 3 theories in ~15 minutes total. Just:
1. Install Theory #1
2. Test
3. If it works, STOP - we're done! 🎉
4. If not, uninstall and move to Theory #2
5. Repeat until one works

---

**Status:** 3 theories built and ready  
**Total Time:** ~15 minutes to test all 3  
**Recommendation:** Start with Theory #1 (highest confidence)
