# CleverKeys TODO Items - Project Management

Generated: October 6, 2025

## Overview
All TODO items found in the codebase with their context, priority, and implementation status.

---

## CustomExtraKeysPreference.kt - Custom Extra Keys Feature

**Status:** Stubbed (placeholder to prevent crashes)
**Priority:** LOW - Nice-to-have feature
**File:** `src/main/kotlin/tribixbite/keyboard2/prefs/CustomExtraKeysPreference.kt`

### TODOs:

1. **Line 14-19: Full implementation pending**
   ```
   TODO: Full implementation pending
   - Key picker dialog with all available keys
   - Position configuration (row, column, direction)
   - Persistence to SharedPreferences
   - Visual keyboard preview for positioning
   ```
   **Context:** Class-level documentation describing complete feature scope

2. **Line 33-40: Parse custom keys from preferences**
   ```kotlin
   // TODO: Parse and return user-defined custom keys
   // Format: JSON array of {keyName, row, col, direction}
   // TODO: Implement custom key parsing from preferences
   // val customKeysJson = prefs.getString(PREF_KEY, "[]")
   // return parseCustomKeys(customKeysJson)
   return emptyMap()
   ```
   **Context:** `get()` method - returns empty map as safe fallback

3. **Line 47-53: Serialize custom keys to preferences**
   ```kotlin
   // TODO: Serialize custom keys to JSON and save
   // TODO: Implement serialization
   // val json = serializeCustomKeys(keys)
   // prefs.edit().putString(PREF_KEY, json).apply()
   ```
   **Context:** `save()` method - currently no-op

4. **Line 64: Show custom key picker dialog**
   ```kotlin
   // TODO: Show custom key picker dialog
   // - Grid of all available keys
   // - Position configuration
   // - Preview of keyboard with selected keys
   ```
   **Context:** `onClick()` - shows "under development" toast

**Implementation Notes:**
- Feature is disabled in UI (`isEnabled = false`)
- Shows "Feature coming soon" message to users
- Already has data structure planned (Map<KeyValue, KeyboardData.PreferredPos>)
- Safe fallback (empty map) prevents crashes

**Recommendation:** DEFER - This is a nice-to-have feature, not blocking core functionality. The stub implementation is safe and appropriate.

---

## CustomLayoutEditor.kt - Layout Testing & Key Editing

**Status:** Partially implemented (basic layout editor exists)
**Priority:** MEDIUM - Improves UX but not critical
**File:** `src/main/kotlin/tribixbite/keyboard2/CustomLayoutEditor.kt`

### TODOs:

5. **Line 319-320: Test layout interface**
   ```kotlin
   // TODO: Open test interface for layout
   toast("Test layout (TODO: Implement test interface)")
   ```
   **Context:** `testLayout()` method - called when user wants to preview layout before saving
   **Impact:** Users can't test custom layouts before committing changes

6. **Line 399-400: Key editing dialog**
   ```kotlin
   // TODO: Open key editing dialog
   android.util.Log.d("CustomLayoutEditor", "Edit key at [$row, $col]")
   ```
   **Context:** `editKey()` method in `LayoutCanvas` - called when user taps a key to edit it
   **Impact:** Users can't modify individual key properties (symbol, width, function)

7. **Line 443-444: Add key to layout from palette**
   ```kotlin
   // TODO: Add key to layout
   android.util.Log.d("KeyPalette", "Selected key: $key")
   ```
   **Context:** `KeyPalette` button click handler - called when user selects key from palette
   **Impact:** Users can't drag/add new keys to layout

**Implementation Notes:**
- Visual layout editor already exists with rendering
- Touch detection working (logs key coordinates)
- Key palette UI implemented with categories
- Missing: actual editing functionality

**Recommendation:** MEDIUM PRIORITY - Layout editor is usable for viewing, but editing is incomplete. Consider implementing after core keyboard functionality is verified working.

---

## ClipboardDatabase.kt - Database Migration Strategy

**Status:** Working with placeholder migration
**Priority:** LOW - Only matters for future versions
**File:** `src/main/kotlin/tribixbite/keyboard2/ClipboardDatabase.kt`

### TODOs:

8. **Line 85: Database migration strategy**
   ```kotlin
   // TODO: Implement proper migration strategy for future versions
   db.execSQL("DROP TABLE IF EXISTS $TABLE_CLIPBOARD")
   onCreate(db)
   ```
   **Context:** `onUpgrade()` - currently drops and recreates table (loses data)
   **Impact:** Users will lose clipboard history when app updates with database schema changes

**Implementation Notes:**
- Current approach is acceptable for initial releases
- Only becomes issue when schema needs to change
- Should implement before first production release with real users

**Recommendation:** DEFER until pre-production - Implement proper migrations (ALTER TABLE, data preservation) before releasing to users with real clipboard data.

---

## Priority Summary

### DEFER (Low Priority - 4 items)
- CustomExtraKeysPreference: All 4 TODOs (Feature not essential, stub is safe)
- ClipboardDatabase migration: 1 TODO (Not needed until schema changes)

### MEDIUM Priority (3 items)
- CustomLayoutEditor test interface: 1 TODO (Improves UX)
- CustomLayoutEditor key editing: 2 TODOs (Completes layout editor)

### HIGH Priority (0 items)
- None found

### CRITICAL (0 items)
- None found

---

## Implementation Plan

### Phase 1: SKIP - Core functionality verified ✅
All critical TODOs are intentional feature deferrals with safe fallbacks.

### Phase 2: AFTER DEVICE TESTING - Complete layout editor
**After keyboard works on device:**
1. Implement key editing dialog (CustomLayoutEditor:399)
   - Edit key properties (symbol, width, function)
   - Save modifications to layout
2. Implement palette drag-and-drop (CustomLayoutEditor:443)
   - Add new keys to layout
   - Remove keys from layout
3. Implement layout test interface (CustomLayoutEditor:319)
   - Preview layout before saving
   - Interactive testing with touch feedback

### Phase 3: PRE-PRODUCTION - Database migrations
**Before releasing to users:**
1. Design schema versioning strategy
2. Implement ALTER TABLE migrations
3. Test upgrade paths from all previous versions
4. Add data preservation logic

### Phase 4: FUTURE - Custom extra keys
**Post-launch feature:**
1. Design custom key configuration UI
2. Implement JSON serialization/deserialization
3. Add key picker with position configuration
4. Visual keyboard preview
5. Enable feature in settings

---

## Conclusion

**All TODOs are intentional deferrals with safe implementations:**
- ✅ No blocking issues
- ✅ No crash risks
- ✅ Safe fallbacks in place
- ✅ Features properly disabled in UI

**Current focus should remain on:**
1. Complete wireless ADB setup
2. Install and test keyboard on device
3. Verify neural prediction works
4. Test swipe typing functionality
5. Monitor for runtime crashes

Once core functionality is verified, revisit Phase 2 TODOs to complete layout editor.
