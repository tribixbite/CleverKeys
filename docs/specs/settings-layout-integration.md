# Settings-Layout Integration Specification

**Feature**: GUI Mapping, Settings Storage, and Layout System Integration
**Status**: Analysis Complete, Improvements Applied
**Priority**: P1
**Last Updated**: 2025-12-11
**Created**: 2025-12-11

---

## Overview

This document specifies how GUI customization options integrate with settings storage and the keyboard layout system. It covers three primary customization systems:

1. **Customize Per-Key Actions** (Short Swipe Customization)
2. **Configure Extra Keys** (ExtraKeysPreference)
3. **Keyboard Layouts** (LayoutManagerActivity)

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              USER INTERFACE                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│ ShortSwipeCustomization   │ ExtraKeysConfigActivity │ LayoutManagerActivity │
│ Activity                  │                         │                       │
│ (Per-Key Actions)         │ (Extra Keys Config)     │ (Layout Manager)      │
└───────────┬───────────────┴───────────┬─────────────┴───────────┬───────────┘
            │                           │                         │
            ▼                           ▼                         ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              STORAGE LAYER                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│ short_swipe_customizations.json   │   SharedPreferences (DirectBootAware)   │
│ (JSON file in app storage)        │   - extra_key_* booleans                │
│                                   │   - layouts (JSON array)                │
│                                   │   - current_layout_portrait/landscape   │
└───────────┬───────────────────────┴───────────────────┬─────────────────────┘
            │                                           │
            │                                           ▼
            │                           ┌───────────────────────────────────┐
            │                           │     ConfigurationManager          │
            │                           │ OnSharedPreferenceChangeListener  │
            │                           └───────────────┬───────────────────┘
            │                                           │
            │                                           ▼
            │                           ┌───────────────────────────────────┐
            │                           │         Config.refresh()          │
            │                           │   - Reloads ALL settings          │
            │                           │   - Reloads layouts list          │
            │                           │   - Reloads extra_keys_param      │
            │                           └───────────────┬───────────────────┘
            │                                           │
            │                                           ▼
            │                           ┌───────────────────────────────────┐
            │                           │       ConfigPropagator            │
            │                           │   - layoutManager.setConfig()     │
            │                           │   - clipboardManager.setConfig()  │
            │                           │   - keyboardView.reset()          │
            │                           └───────────────┬───────────────────┘
            │                                           │
            ▼                                           ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           KEYBOARD RUNTIME                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│ ShortSwipeCustomizationManager  │ Config.extra_keys_param │ LayoutManager  │
│ (Loaded on each gesture check)  │ (Map<KeyValue, Pos>)    │ (Layout list)  │
│                                 │                         │                │
│ Pointers.kt checks mappings     │ Applied during layout   │ incrTextLayout │
│ BEFORE built-in sublabel keys   │ loading via addExtraKey │ setTextLayout  │
└─────────────────────────────────┴─────────────────────────┴────────────────┘
```

---

## 1. Short Swipe Customization (Per-Key Actions)

### Storage
- **File**: `short_swipe_customizations.json` in app-specific storage
- **Format**: JSON with version and mappings
- **Hot-Reload**: YES - loaded fresh on each gesture check

### Integration Flow
```
User edits in ShortSwipeCustomizationActivity
    ↓
ShortSwipeCustomizationManager.saveMapping()
    ↓
JSON file written to storage
    ↓
On next gesture (Pointers.kt):
    ShortSwipeCustomizationManager.getCustomMapping() loads fresh
    ↓
Custom mapping executed BEFORE built-in sublabel check
```

### Key Points
- **Priority**: Custom mappings override built-in sublabel gestures
- **Modifier Support**: Custom mappings work even with Shift active (v1.32.926)
- **No Service Restart Required**: Mappings are loaded on-demand

---

## 2. Extra Keys Configuration

### Storage
- **Location**: SharedPreferences (DirectBootAwarePreferences)
- **Keys**: `extra_key_<keyname>` (boolean)
- **Example**: `extra_key_switch_forward = true`

### Integration Flow
```
User toggles in ExtraKeysConfigActivity
    ↓
prefs.edit().putBoolean("extra_key_<name>", enabled).apply()
    ↓
ConfigurationManager.onSharedPreferenceChanged()
    ↓
Config.refresh() → ExtraKeysPreference.getExtraKeys(prefs)
    ↓
Config.extra_keys_param = Map<KeyValue, PreferredPos>
    ↓
LayoutModifier.modify_layout() adds extra keys to keyboard
    ↓
KeyboardView.reset() triggers redraw
```

### Key Points
- **Hot-Reload**: YES - Config.refresh() reloads on preference change
- **Position Hints**: Extra keys use PreferredPos to specify placement
- **Available Keys**: 95+ keys including switch_forward/switch_backward

---

## 3. Keyboard Layouts (Layout Manager)

### Storage
- **Location**: SharedPreferences (DirectBootAwarePreferences)
- **Key**: `layouts` (JSON array of Layout objects)
- **Current Layout**: `current_layout_portrait`, `current_layout_landscape`

### Layout Types
- **SystemLayout**: Device's default keyboard layout
- **NamedLayout**: Pre-defined layouts (qwerty_us, dvorak, etc.)
- **CustomLayout**: User-defined XML layouts

### Integration Flow
```
User reorders/adds layouts in LayoutManagerActivity
    ↓
ListGroupPreference.saveToPreferences() writes JSON
    ↓
ConfigurationManager.onSharedPreferenceChanged()
    ↓
Config.refresh() → LayoutsPreference.loadFromPreferences()
    ↓
Config.layouts = List<KeyboardData?> (loaded layouts)
    ↓
ConfigPropagator.propagateConfig()
    ↓
LayoutManager.setConfig(config)
    ↓
KeyboardView.reset()
```

### Layout Switching
```
User presses switch_forward key (or swipes N on space)
    ↓
KeyboardReceiver handles SWITCH_FORWARD event
    ↓
layoutManager.incrTextLayout(1)
    ↓
newIndex = (currentIndex + 1 + layoutCount) % layoutCount
    ↓
config.set_current_layout(newIndex) - persists to prefs
    ↓
keyboardView.setKeyboard(newLayout)
```

### Key Points
- **Hot-Reload**: YES - layouts reloaded on preference change
- **Persistence**: Current layout index saved per orientation
- **Requires Multiple Layouts**: switch_forward only works with 2+ layouts

---

## 4. Critical Implementation Details

### SharedPreferences File Consistency
**CRITICAL**: All settings activities MUST use `DirectBootAwarePreferences.get_shared_preferences(context)` to ensure they read/write the same preferences file as the keyboard service.

**Fixed (2025-12-11)**: LayoutManagerActivity and ExtraKeysConfigActivity were using `"cleverkeys_prefs"` instead of the DirectBootAware preferences, causing settings to not be read by the service.

### Hot-Reload Chain
1. `SharedPreferences.OnSharedPreferenceChangeListener` fires
2. `ConfigurationManager.onSharedPreferenceChanged()` called
3. `Config.refresh(resources)` reloads ALL settings
4. `ConfigPropagator.propagateConfig()` updates all managers
5. `KeyboardView.reset()` redraws the keyboard

### Layout Switching Prerequisites
For switch_forward/switch_backward to work:
1. **Multiple layouts configured** in Layout Manager
2. **Short gestures enabled** (default: true)
3. **Swipe gesture on space bar** (N = forward, S = backward)
4. OR **Extra key enabled** for switch_forward/switch_backward

---

## 5. Known Issues & Solutions

### Issue: switch_forward key not working
**Root Cause 1**: Only one layout configured (nothing to switch to)
**Solution**: Add multiple layouts in Layout Manager

**Root Cause 2**: Short gesture not triggering
**Solution**: Swipe UP (not just tap) on space bar; increase gesture sensitivity if needed

**Root Cause 3**: Extra key not available
**Solution**: Enable "Next Layout" in Configure Extra Keys (added 2025-12-11)

### Issue: Layout reorder not taking effect
**Root Cause**: Wrong SharedPreferences file
**Solution**: Fixed by using DirectBootAwarePreferences (2025-12-11)

### Issue: Extra keys not appearing on keyboard
**Root Cause**: ExtraKeysPreference not loading from correct prefs
**Solution**: Use DirectBootAwarePreferences consistently

### Issue: switch_forward/switch_backward not visible in Extra Keys Config
**Root Cause**: Keys added to ExtraKeysPreference but not categorized in ExtraKeysConfigActivity
**Solution**: Added "Layout Switching" category to categorizedKeys map (2025-12-11)

### Issue: Short swipe customization not triggering layout switch
**Root Cause**: SWITCH_FORWARD/SWITCH_BACKWARD not in AvailableCommand enum or CommandRegistry
**Solution**: Added commands to AvailableCommand enum, handling in CustomShortSwipeExecutor (returns false for service handling), and execution in Keyboard2View.onCustomShortSwipe() via CleverKeysService.triggerKeyboardEvent() (2025-12-11)

### Issue: Custom per-key mappings not loading on keyboard startup
**Root Cause**: ShortSwipeCustomizationManager.loadMappings() only called in ShortSwipeCustomizationActivity
**Solution**: Added init block in Pointers.kt to call loadMappings() via coroutine on keyboard startup (2025-12-11)

### Issue: "Enable Neural Prediction" toggle does nothing (FIXED)
**Root Cause**: `neural_prediction_enabled` preference was loaded from storage but never checked in prediction logic. Neural engine always initialized when `swipe_typing_enabled` was true.
**Solution**: Removed the non-functional toggle from UI (2025-12-11). Neural settings now show directly when "Enable Swipe Typing" is enabled. All advanced neural settings (beam width, max length, confidence, beam search config, model config) remain intact.

### Issue: Swipe prediction alternates don't follow shift/caps-lock state (FIXED)
**Root Cause**: In `InputCoordinator.handlePredictionResults()`, shift/caps-lock transformation was only applied to the auto-inserted top prediction in `onSuggestionSelected()`, but alternates displayed in suggestion bar were shown lowercase.
**Solution**: Added `applyShiftTransformation()` helper and applied it to ALL predictions before displaying in suggestion bar (2025-12-11). Now when shift is active, all alternates show capitalized first letter; when caps-lock is active, all alternates show ALL CAPS.

---

## 6. Testing Checklist

### Short Swipe Customization
- [ ] Create custom mapping, verify it works immediately
- [ ] Custom mapping with Shift key active
- [ ] Reset mapping to default
- [ ] Export/import via backup

### Extra Keys
- [ ] Enable switch_forward, verify it appears on keyboard
- [ ] Disable key, verify it disappears
- [ ] Multiple extra keys positions don't conflict

### Layout Manager
- [ ] Add second layout, verify switch_forward works
- [ ] Reorder layouts, verify order in keyboard
- [ ] Remove layout, verify switch cycling updates
- [ ] Custom XML layout loads correctly

### Integration
- [ ] Changes take effect without service restart
- [ ] Settings persist across app restart
- [ ] Portrait/landscape layout indices independent

---

## 7. Files Reference

| Component | File | Purpose |
|-----------|------|---------|
| Per-Key Actions UI | `ui/customization/ShortSwipeCustomizationActivity.kt` | Edit custom mappings |
| Per-Key Storage | `customization/ShortSwipeCustomizationManager.kt` | JSON persistence |
| Per-Key Executor | `customization/CustomShortSwipeExecutor.kt` | 137 commands |
| Extra Keys UI | `ExtraKeysConfigActivity.kt` | Toggle extra keys |
| Extra Keys Preference | `prefs/ExtraKeysPreference.kt` | Key definitions, positions |
| Layout UI | `LayoutManagerActivity.kt` | Manage layouts |
| Layout Preference | `prefs/LayoutsPreference.kt` | Layout serialization |
| Config Manager | `ConfigurationManager.kt` | Preference listener |
| Config | `Config.kt` | Settings singleton |
| Config Propagator | `ConfigPropagator.kt` | Updates all managers |
| Layout Manager | `LayoutManager.kt` | Layout switching |
| Gesture Handler | `Pointers.kt` | Short gesture detection |
| Keyboard View | `Keyboard2View.kt` | Rendering |

---

**Created**: 2025-12-11
**Owner**: CleverKeys Development Team
