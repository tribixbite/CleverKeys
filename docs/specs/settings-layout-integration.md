# Settings-Layout Integration

## Overview

Integration between GUI customization options, settings storage, and the keyboard layout system. Covers three primary customization systems: Per-Key Actions, Extra Keys, and Keyboard Layouts.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/customization/ShortSwipeCustomizationManager.kt` | JSON persistence | Per-key actions storage |
| `src/main/kotlin/tribixbite/cleverkeys/prefs/ExtraKeysPreference.kt` | `getExtraKeys()` | Extra key definitions |
| `src/main/kotlin/tribixbite/cleverkeys/prefs/LayoutsPreference.kt` | Layout serialization | Layout persistence |
| `src/main/kotlin/tribixbite/cleverkeys/ConfigurationManager.kt` | Preference listener | Settings change handling |
| `src/main/kotlin/tribixbite/cleverkeys/ConfigPropagator.kt` | `propagateConfig()` | Updates all managers |
| `src/main/kotlin/tribixbite/cleverkeys/LayoutManager.kt` | `incrTextLayout()` | Layout switching |

## Architecture

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
            │                           └───────────────┬───────────────────┘
            │                                           │
            │                                           ▼
            │                           ┌───────────────────────────────────┐
            │                           │       ConfigPropagator            │
            │                           │   - layoutManager.setConfig()     │
            │                           │   - keyboardView.reset()          │
            │                           └───────────────────────────────────┘
            │                                           │
            ▼                                           ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           KEYBOARD RUNTIME                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│ ShortSwipeCustomizationManager  │ Config.extra_keys_param │ LayoutManager  │
│ (Loaded on each gesture check)  │ (Map<KeyValue, Pos>)    │ (Layout list)  │
└─────────────────────────────────┴─────────────────────────┴────────────────┘
```

## Short Swipe Customization

### Storage
- **File**: `short_swipe_customizations.json`
- **Hot-Reload**: YES - loaded fresh on each gesture check
- **Priority**: Custom mappings override built-in sublabel gestures

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

## Extra Keys Configuration

### Storage
- **Location**: SharedPreferences (DirectBootAware)
- **Keys**: `extra_key_<keyname>` (boolean)

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
LayoutModifier.modify_layout() adds extra keys
    ↓
KeyboardView.reset()
```

## Layout Management

### Storage
- **Location**: SharedPreferences (DirectBootAware)
- **Key**: `layouts` (JSON array)
- **Current Layout**: `current_layout_portrait`, `current_layout_landscape`

### Layout Types
- **SystemLayout**: Device's default keyboard layout
- **NamedLayout**: Pre-defined layouts (qwerty_us, dvorak, etc.)
- **CustomLayout**: User-defined XML layouts

### Layout Switching Flow

```
User presses switch_forward (or swipes N on space)
    ↓
KeyboardReceiver handles SWITCH_FORWARD event
    ↓
layoutManager.incrTextLayout(1)
    ↓
newIndex = (currentIndex + 1) % layoutCount
    ↓
config.set_current_layout(newIndex)
    ↓
keyboardView.setKeyboard(newLayout)
```

## Hot-Reload Chain

1. `SharedPreferences.OnSharedPreferenceChangeListener` fires
2. `ConfigurationManager.onSharedPreferenceChanged()` called
3. `Config.refresh(resources)` reloads ALL settings
4. `ConfigPropagator.propagateConfig()` updates all managers
5. `KeyboardView.reset()` redraws the keyboard

## Critical Implementation Detail

**All settings activities MUST use `DirectBootAwarePreferences.get_shared_preferences(context)`** to ensure they read/write the same preferences file as the keyboard service.

## Layout Switching Prerequisites

For switch_forward/switch_backward to work:
1. Multiple layouts configured in Layout Manager
2. Short gestures enabled (default: true)
3. Swipe gesture on space bar (N = forward, S = backward) OR extra key enabled
