# Bug #473: Clipboard Swipe Gesture Not Working
**Reported**: November 20, 2025, 11:45 AM (during Bug #468 testing)
**Reporter**: User manual testing
**Severity**: P1 (High - Core functionality broken)
**Status**: ⏳ INVESTIGATING

---

## 🐛 Issue Description

**Problem**: Short swipe gesture to open clipboard does nothing

**Expected Behavior**:
- Swipe NW on Ctrl key (bottom-left key)
- Clipboard history view should appear
- User can select from clipboard history

**Actual Behavior**:
- Swipe gesture is recognized (key highlights)
- Nothing happens (no clipboard view)
- No error messages in logs

---

## 🔍 Investigation Findings

### 1. Keyboard Layout Configuration ✅ CORRECT

**File**: `res/xml/bottom_row.xml:3`
```xml
<key width="1.7" key0="ctrl" key1="loc meta" key2="loc switch_clipboard" key3="switch_numeric" key4="loc switch_greekmath"/>
```

**Gesture Mapping**:
- key0 (center): ctrl
- key1 (NW): meta
- key2 (NW): **switch_clipboard** ← DEFINED HERE
- key3 (SE): switch_numeric
- key4 (SW): switch_greekmath

**Status**: ✅ Layout is correct, clipboard is mapped to key2

---

### 2. Event Definition ✅ EXISTS

**File**: `src/main/kotlin/tribixbite/keyboard2/KeyValue.kt`

**Line 40**: `SWITCH_CLIPBOARD` event defined in Event enum
```kotlin
enum class Event {
    // ... other events
    SWITCH_CLIPBOARD,      // Line 40
    SWITCH_BACK_CLIPBOARD, // Line 41
    // ...
}
```

**Line 588**: Event mapped to key name
```kotlin
namedKeys["switch_clipboard"] = makeEventKey(0xE017, Event.SWITCH_CLIPBOARD)
```

**Status**: ✅ Event exists and is properly defined

---

### 3. Event Handler ❌ MISSING

**File**: `src/main/kotlin/tribixbite/keyboard2/CleverKeysService.kt:3929-3954`

**Current handleSpecialKey Implementation**:
```kotlin
private fun handleSpecialKey(event: KeyValue.Event) {
    when (event) {
        KeyValue.Event.CONFIG -> {
            logD("Opening configuration")
        }
        KeyValue.Event.SWITCH_TEXT -> {
            logD("Switching to text mode (ABC)")
            switchToTextLayout()
        }
        KeyValue.Event.SWITCH_NUMERIC -> {
            logD("Switching to numeric mode (123+)")
            switchToNumericLayout()
        }
        KeyValue.Event.SWITCH_EMOJI -> {
            logD("Switching to emoji mode")
        }
        else -> {
            logD("Unhandled special event: $event")  // ← CLIPBOARD FALLS HERE
        }
    }
}
```

**Status**: ❌ **NO HANDLER FOR SWITCH_CLIPBOARD**

---

### 4. Clipboard View Code ✅ EXISTS

**Files Found**:
- `ClipboardHistoryView.kt` - UI for displaying clipboard history
- `ClipboardHistoryService.kt` - Background service for clipboard management
- `ClipboardDatabase.kt` - Persistent storage for clipboard items
- `ClipboardEntry.kt` - Data model for clipboard items
- `ClipboardSyncManager.kt` - Sync management
- `ClipboardPinView.kt` - UI for pinned items
- `ClipboardSettingsActivity.kt` - Settings UI

**ClipboardHistoryView** features:
- Displays list of clipboard items
- Search/filter functionality (Bug #471 fix)
- Item selection callback
- Clear all button
- Pin functionality

**Status**: ✅ Clipboard UI exists and is complete

---

### 5. Integration Status ❌ NOT INTEGRATED

**Check**: Is ClipboardHistoryView used in CleverKeysService?
```bash
grep "ClipboardHistoryView" src/main/kotlin/tribixbite/keyboard2/CleverKeysService.kt
# Result: No matches found
```

**Status**: ❌ **CLIPBOARD VIEW NOT INTEGRATED INTO KEYBOARD SERVICE**

---

## 🎯 Root Cause

**Problem**: ClipboardHistoryView exists but is never instantiated or shown by CleverKeysService

**Missing Components**:
1. Instance of ClipboardHistoryView in CleverKeysService
2. Handler for SWITCH_CLIPBOARD event
3. Logic to show/hide clipboard view
4. Handler for SWITCH_BACK_CLIPBOARD event (return to keyboard)

**Similar Pattern**: SWITCH_EMOJI has the same issue (stub handler, not implemented)

---

## 🔧 Fix Design

### Option 1: Overlay Pattern (Recommended)

Show clipboard view as an overlay above the keyboard:

```kotlin
// In CleverKeysService
private var clipboardView: ClipboardHistoryView? = null
private var isClipboardMode: Boolean = false

private fun switchToClipboardView() {
    if (clipboardView == null) {
        clipboardView = ClipboardHistoryView(this).apply {
            setOnItemSelectedListener { text ->
                // Insert selected clipboard item
                currentInputConnection?.commitText(text, 1)
                switchBackFromClipboard()
            }
        }
    }

    // Hide keyboard, show clipboard
    keyboardView?.visibility = View.GONE
    clipboardView?.visibility = View.VISIBLE
    isClipboardMode = true

    logD("✅ Switched to clipboard view")
}

private fun switchBackFromClipboard() {
    // Show keyboard, hide clipboard
    clipboardView?.visibility = View.GONE
    keyboardView?.visibility = View.VISIBLE
    isClipboardMode = false

    logD("✅ Switched back from clipboard")
}

// In handleSpecialKey
KeyValue.Event.SWITCH_CLIPBOARD -> {
    logD("Switching to clipboard view")
    switchToClipboardView()
}
KeyValue.Event.SWITCH_BACK_CLIPBOARD -> {
    logD("Switching back from clipboard")
    switchBackFromClipboard()
}
```

**Pros**:
- Simple show/hide logic
- Clipboard view has full screen space
- Easy to implement

**Cons**:
- Need to manage view hierarchy
- Requires view container setup

---

### Option 2: PopupWindow Pattern

Show clipboard as a popup window:

```kotlin
private var clipboardPopup: PopupWindow? = null

private fun showClipboardPopup() {
    val clipboardView = ClipboardHistoryView(this).apply {
        setOnItemSelectedListener { text ->
            currentInputConnection?.commitText(text, 1)
            clipboardPopup?.dismiss()
        }
    }

    clipboardPopup = PopupWindow(
        clipboardView,
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        isFocusable = true
        showAtLocation(keyboardView, Gravity.BOTTOM, 0, 0)
    }
}
```

**Pros**:
- Independent of keyboard view
- Clean separation
- Standard Android pattern

**Cons**:
- More complex lifecycle management
- Popup dismiss handling needed

---

### Option 3: Fragment Pattern

Use a Fragment to manage clipboard view:

**Pros**:
- Proper lifecycle management
- Can use Android Fragment APIs

**Cons**:
- IME services can't use Fragments directly
- Requires custom FragmentActivity hosting

**Status**: NOT RECOMMENDED for IME

---

## 📋 Implementation Plan

### Step 1: Add Clipboard View to Service
```kotlin
// In CleverKeysService class properties
private var clipboardView: ClipboardHistoryView? = null
private var isClipboardMode: Boolean = false
```

### Step 2: Initialize Clipboard View
```kotlin
// In onCreateInputView or similar
private fun initializeClipboardView() {
    clipboardView = ClipboardHistoryView(this).apply {
        visibility = View.GONE  // Hidden by default
        setOnItemSelectedListener { text ->
            handleClipboardSelection(text)
        }
    }
}
```

### Step 3: Implement Switch Methods
```kotlin
private fun switchToClipboardView() {
    serviceScope.launch {
        try {
            // Save current keyboard state if needed
            if (!isClipboardMode) {
                // Initialization
                clipboardView?.let { view ->
                    // Add to view hierarchy if needed
                    addClipboardViewToHierarchy(view)
                }
            }

            // Toggle visibility
            keyboardView?.visibility = View.GONE
            clipboardView?.visibility = View.VISIBLE
            isClipboardMode = true

            logD("✅ Switched to clipboard view")
        } catch (e: Exception) {
            logE("Error switching to clipboard", e)
        }
    }
}

private fun switchBackFromClipboard() {
    try {
        clipboardView?.visibility = View.GONE
        keyboardView?.visibility = View.VISIBLE
        isClipboardMode = false

        logD("✅ Switched back from clipboard")
    } catch (e: Exception) {
        logE("Error switching back from clipboard", e)
    }
}
```

### Step 4: Handle Clipboard Selection
```kotlin
private fun handleClipboardSelection(text: String) {
    try {
        currentInputConnection?.commitText(text, 1)
        switchBackFromClipboard()
        logD("✅ Inserted clipboard text: ${text.take(50)}...")
    } catch (e: Exception) {
        logE("Error inserting clipboard text", e)
    }
}
```

### Step 5: Add Event Handlers
```kotlin
// In handleSpecialKey method
KeyValue.Event.SWITCH_CLIPBOARD -> {
    logD("Switching to clipboard view")
    switchToClipboardView()
}
KeyValue.Event.SWITCH_BACK_CLIPBOARD -> {
    logD("Switching back from clipboard")
    switchBackFromClipboard()
}
```

### Step 6: Cleanup
```kotlin
// In onDestroy
override fun onDestroy() {
    clipboardView = null
    // ... other cleanup
}
```

---

## 🧪 Testing Strategy

### Manual Test (Primary)
1. Open any text app
2. Tap text field to show keyboard
3. **Swipe NW on Ctrl key** (bottom-left key, swipe up-left)
4. **Expected**: Clipboard history view appears
5. Tap a clipboard item
6. **Expected**: Item is inserted into text field, keyboard returns
7. **Verify**: Text was inserted correctly

### Edge Cases to Test
1. Empty clipboard (no items)
2. Many clipboard items (scrolling)
3. Long clipboard items (truncation/wrapping)
4. Special characters in clipboard
5. Return to keyboard without selecting (back button / ABC button)
6. Clipboard after screen rotation
7. Clipboard with search/filter

### Integration Testing
1. Switch keyboard → clipboard → keyboard (multiple times)
2. Switch keyboard → clipboard → numeric → keyboard
3. Type text → clipboard → select item → continue typing
4. Clipboard → home → return to app

---

## ⚠️ Implementation Risks

### Risk 1: View Hierarchy Management
**Risk**: Clipboard view might not integrate properly with IME view hierarchy
**Mitigation**: Test view addition/removal thoroughly, use proper ViewGroup

### Risk 2: Lifecycle Management
**Risk**: View might leak or not cleanup properly
**Mitigation**: Proper null checks, cleanup in onDestroy

### Risk 3: State Management
**Risk**: isClipboardMode flag might get out of sync
**Mitigation**: Centralized state management, proper boolean flags

### Risk 4: Performance
**Risk**: Loading large clipboard history might lag
**Mitigation**: Pagination, lazy loading (already implemented in ClipboardHistoryView)

---

## 📊 Priority Assessment

### Severity: P1 (High)
**Rationale**:
- Core functionality advertised in UI (key has gesture mapped)
- User expectation set (gesture exists)
- Clipboard is common use case
- Complete code exists, just needs integration

### Impact: High
**Affects**:
- All users trying to access clipboard
- Productivity (need clipboard for copy/paste workflows)
- User trust (feature appears broken)

### Effort: Medium (4-6 hours)
**Tasks**:
1. Integrate ClipboardHistoryView (1-2 hours)
2. Implement switch methods (1 hour)
3. Testing (2-3 hours)
4. Documentation (1 hour)

---

## 🎯 Next Steps

### Immediate
- [ ] Confirm issue reproduced (user already reported)
- [ ] Implement fix (Option 1 recommended)
- [ ] Build and install APK
- [ ] Test manually

### Follow-Up
- [ ] Test emoji switching (same pattern, might be broken too)
- [ ] Document clipboard usage in USER_MANUAL.md
- [ ] Add to CHANGELOG as Bug #473

---

## 📖 Related

**Files**:
- `res/xml/bottom_row.xml` - Gesture mapping
- `src/main/kotlin/tribixbite/keyboard2/KeyValue.kt` - Event definition
- `src/main/kotlin/tribixbite/keyboard2/CleverKeysService.kt` - Event handling
- `src/main/kotlin/tribixbite/keyboard2/ClipboardHistoryView.kt` - UI component

**Related Bugs**:
- Bug #468: Numeric keyboard switching (FIXED)
- Bug #471: Clipboard search (FIXED)

---

**Investigation Date**: November 20, 2025, 11:45 AM
**Status**: ⏳ INVESTIGATING - Root cause identified, fix designed
**Next**: Implement Option 1 (overlay pattern)
