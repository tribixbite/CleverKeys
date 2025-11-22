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

