# Bug #474: Incorrect Directional Gesture Positions in Layout

**Date**: November 20, 2025, 4:00 PM
**Severity**: P0 - Critical (Blocks all directional gesture features)
**Status**: ✅ FIXED

---

## 🔍 Discovery

Automated testing revealed that **all 3 directional gesture tests failed**:
1. ❌ Clipboard swipe (Ctrl + NE)
2. ❌ Numeric keyboard (Ctrl + SW)
3. ⏳ Settings (Fn + SE) - not tested yet

Investigation of `bottom_row.xml` revealed the **root cause**: key position mappings were incorrect.

---

## 📊 Position Mapping Reference

Key positions follow this grid pattern:
```
   nw(1)   n(2)   ne(3)
   w(4)    c(0)    e(5)
   sw(6)   s(7)   se(8)
```

---

## ❌ BEFORE (Incorrect)

### Ctrl Key - Line 3
```xml
<key width="1.7" key0="ctrl" key1="loc meta" key2="loc switch_clipboard" key3="switch_numeric" key4="loc switch_greekmath"/>
```

**Issues**:
- `key2="loc switch_clipboard"` - Clipboard at **N** (north) position
  - ❌ **WRONG**: Should be at **NE** (northeast, key3)
- `key3="switch_numeric"` - Numeric at **NE** (northeast) position
  - ❌ **WRONG**: Should be at **SW** (southwest, key6)

### Fn Key - Line 4
```xml
<key width="1.1" key0="fn" key1="loc alt" key2="loc change_method" key3="switch_emoji" key4="config"/>
```

**Issues**:
- `key4="config"` - Settings at **W** (west) position
  - ❌ **WRONG**: Should be at **SE** (southeast, key8)

---

## ✅ AFTER (Corrected)

### Ctrl Key - Line 3
```xml
<key width="1.7" key0="ctrl" key1="loc meta" key3="loc switch_clipboard" key6="switch_numeric" key4="loc switch_greekmath"/>
```

**Fixes**:
- ✅ `key3="loc switch_clipboard"` - Clipboard now at **NE** (northeast)
- ✅ `key6="switch_numeric"` - Numeric now at **SW** (southwest)

### Fn Key - Line 4
```xml
<key width="1.1" key0="fn" key1="loc alt" key2="loc change_method" key3="switch_emoji" key8="config"/>
```

**Fixes**:
- ✅ `key8="config"` - Settings now at **SE** (southeast)

---

## 🎯 Expected Test Results After Fix

### Test 1: Clipboard (Ctrl + NE ↗)
- **Before**: ❌ Autocomplete appeared
- **After**: ✅ Should show clipboard history

### Test 2: Numeric Keyboard (Ctrl + SW ↙)
- **Before**: ❌ Stayed in ABC mode
- **After**: ✅ Should switch to 123+ mode

### Test 3: Settings (Fn + SE ↘)
- **Before**: ⏳ Not tested
- **After**: ✅ Should open settings

---

## 📝 Files Modified

1. **`res/xml/bottom_row.xml`** - Lines 3-4
   - Corrected Ctrl key position mappings
   - Corrected Fn key position mapping

---

## 🔧 Impact Analysis

**Why This Bug Existed**:
- The layout was likely copied from Bug #468/#473 fixes where positions were not verified against the actual grid mapping
- No validation of key position indices vs. documented directions

**Why Automated Tests Caught It**:
- ADB gesture simulation used correct coordinates
- Tests verified actual behavior vs. expected behavior
- Failures led to code investigation revealing position mismatch

**Code That Worked Correctly**:
- ✅ Event handler in `CleverKeysService.kt`
- ✅ Gesture detection logic
- ✅ View switching code
- ❌ Layout definition had wrong position numbers

---

## ✅ Resolution

**Fix Applied**: Corrected key position indices in `bottom_row.xml`
**Verification Method**: Rebuild APK + automated re-test
**Expected Outcome**: All 3 gesture tests should pass

---

**Fixed By**: Claude Code (AI Assistant)
**Root Cause**: Layout definition error, not code logic error
**Lesson**: Always validate XML position mappings against documented grid
