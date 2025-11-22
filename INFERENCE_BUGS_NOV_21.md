## Critical Issue Found - Nov 21, 2025

### Problem
CleverKeys keyboard service initializes successfully but keyboard view doesn't render.

### Root Cause Analysis
1. **onCreate() was in test mode**: Had ULTRA-MINIMAL stub that did no initialization
2. **Fixed**: Restored proper initialization (lifecycle + config + layout loading)
3. **New issue**: onCreateInputView() is never being called by Android system

### Evidence
```
Service initialization (WORKING):
✅ CleverKeys service starting...
✅ Lifecycle initialized
✅ Configuration loaded
✅ Default layout loaded
✅ CleverKeys initialization complete
✅ Input started: package=com.microsoft.emmx

View creation (NOT WORKING):
❌ onCreateInputView() - never called
❌ Keyboard view - not rendered
❌ No keyboard visible on screen
```

### Hypothesis
The Android InputMethodService may be caching a null view from previous failed attempts, or there's an issue with how the service declares its capabilities.

### Next Steps
1. Check if onStartInputView() is being called
2. Investigate InputMethodService lifecycle methods
3. May need to check AndroidManifest.xml for proper IME declaration
4. Consider if the service needs to override additional lifecycle methods

### Commits
- 38d74db2: Restored onCreate + added debug logging
- 83e045b9: Added branding to Keyboard2View (spacebar)


---

## Update 2 - 20:40

### Additional Investigation

**Added**:
1. `onEvaluateInputViewShown()` method - logs when called
2. Enhanced `onStartInput()` logging with inputType and imeOptions

**Findings**:
```
Input started: package=com.google.android.apps.messaging, restarting=false
  inputType=147457, imeOptions=1073741828    <-- VALID TEXT INPUT
  initialSelStart=0, initialSelEnd=0
```

**Still NOT Called**:
- ❌ `onEvaluateInputViewShown()` - never appears in logs
- ❌ `onCreateInputView()` - never appears in logs
- ❌ `onStartInputView()` - never appears in logs

**Verified**:
- ✅ CleverKeys IS the active IME (`tribixbite.keyboard2/.CleverKeysService`)
- ✅ Service initializes successfully
- ✅ `onStartInput()` fires with valid inputType
- ✅ Text field is focused (inputType shows it's a text input)

### Mystery
The Android system is:
1. Starting the service ✅
2. Calling `onStartInput()` with valid text input ✅
3. But NOT asking for the input view ❌

This suggests either:
1. The service is telling Android it doesn't want to show (but how?)
2. There's a lifecycle method we're missing that gates view creation
3. The AndroidManifest.xml might be declaring something incorrectly

### Next Steps
1. Check if there's an `onEvaluateFullscreenMode()` issue
2. Verify all InputMethodService abstract methods are implemented
3. Compare manifest with working Unexpected-Keyboard
4. Check if window flags are preventing display


---

## BREAKTHROUGH - 20:41

### ROOT CAUSE FOUND!

**Missing Method**: `onEvaluateFullscreenMode()`

The original Unexpected-Keyboard has this:
```java
@Override
public boolean onEvaluateFullscreenMode() {
    /* Entirely disable fullscreen mode. */
    return false;
}
```

**Without this method**:
- Android's InputMethodService defaults to fullscreen mode for landscape
- When fullscreen mode is attempted but not properly supported, Android doesn't show the keyboard
- This explains why `onCreateInputView()` was never called!

**The Fix**:
```kotlin
override fun onEvaluateFullscreenMode(): Boolean {
    logD("onEvaluateFullscreenMode() returning false (fullscreen disabled)")
    return false
}
```

This simple override should allow the keyboard to display properly!

### How User Found It
User correctly identified: "probably the manifest and layout generation. source original java used a python script to generate it"

This led to comparing the original Java implementation, where we found the missing `onEvaluateFullscreenMode()` method.

### Status
- ✅ Method added to CleverKeysService
- ✅ APK rebuilt
- ⏳ Waiting for device to reconnect for testing
- 🎯 This should fix the keyboard rendering issue!


---

## ✅ SOLUTION CONFIRMED - 23:15

### THE FIX WORKS!

**Proof**: Screenshot showing CleverKeys Neural Swipe Calibration screen
- Neural Swipe Calibration interface (unique to CleverKeys)
- Swipe trail rendering visible (cyan line)
- Neural performance metrics displayed
- Full keyboard rendered at bottom
- This screen does NOT exist in Unexpected-Keyboard

**Final Commits**:
- `4b2c3a90` - fix: add onEvaluateFullscreenMode to enable keyboard rendering

### Summary

**Problem**: CleverKeys service initialized but keyboard didn't render

**Root Cause**: Missing `onEvaluateFullscreenMode()` method
- Android defaults to fullscreen mode
- Fullscreen attempt fails → onCreateInputView() never called
- Service runs but view never displays

**Solution**: One method override
```kotlin
override fun onEvaluateFullscreenMode(): Boolean {
    return false  // Disable fullscreen mode
}
```

**Total Investigation Time**: ~3.5 hours
**Lines of Code Changed**: 6 lines
**Impact**: 100% fix - keyboard now renders

### Lessons Learned

1. **Compare with original**: When porting, methodically compare ALL lifecycle methods
2. **Default behaviors matter**: InputMethodService has defaults that may not work for all cases
3. **User intuition was right**: "probably the manifest and layout generation" led us to compare implementations
4. **Simple fixes, hard to find**: The fix was trivial once we found the missing method

