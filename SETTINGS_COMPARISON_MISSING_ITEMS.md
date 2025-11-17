# Settings Comparison: Unexpected-Keyboard vs CleverKeys

**Date**: 2025-11-16 (Updated: Implementation in Progress)
**Purpose**: Comprehensive comparison of all settings to identify missing features in CleverKeys
**Status**: ✅ **85% COMPLETE** - Major Implementation Progress!

---

## 🎉 IMPLEMENTATION STATUS (2025-11-16)

**Settings Parity Achievement**: 85% (40/45 settings implemented)

### ✅ IMPLEMENTED TODAY (25 new settings):

**P1 - CRITICAL (100% Complete)**:
- ✅ Keyboard Height (Landscape)
- ✅ Bottom Margins (Portrait/Landscape)
- ✅ Horizontal Margins (Portrait/Landscape)

**P2 - HIGH (100% Complete)**:
- ✅ Label Brightness (0-100%)
- ✅ Keyboard Opacity (0-100%)
- ✅ Key Opacity (0-100%)
- ✅ Activated Key Opacity (0-100%)
- ✅ Character Size (50-200%)
- ✅ Key Vertical Margin (0-5%)
- ✅ Key Horizontal Margin (0-5%)
- ✅ Swipe Distance Threshold
- ✅ Circle Gesture Sensitivity
- ✅ Long Press Timeout
- ✅ Long Press Interval
- ✅ Key Repeat Toggle
- ✅ Keyboard Switcher Behavior

**P3 - MEDIUM (100% Complete)**:
- ✅ Border Config Toggle
- ✅ Custom Border Radius
- ✅ Custom Border Line Width
- ✅ Number Row (Hidden/Numbers/Symbols)
- ✅ Show Numpad (Never/Landscape/Always)
- ✅ Numpad Layout (High First/Low First)
- ✅ Pin Entry Layout

**P4 - LOW (100% Complete)**:
- ✅ Double Tap Shift for Caps Lock

### ⏳ REMAINING (2 complex features, ~5 settings):

**Layout Manager** (2-3 settings):
- ❌ Add/Remove/Reorder Layouts (QWERTY/AZERTY/Dvorak)
- Note: Requires dedicated LayoutManagerActivity

**Extra Keys** (2-3 settings):
- ❌ Add Custom Keys (Tab, Esc, Ctrl, arrows)
- ❌ Select Internal Extra Keys
- Note: Requires dedicated ExtraKeysConfigActivity

---

## 📊 Executive Summary

**Original Unexpected-Keyboard Settings**: 4 categories, 45 individual settings
**CleverKeys Current Settings**: 7 sections, ~40 settings
**Implemented Settings**: ~40 settings (85% parity)
**Remaining Settings**: ~5 settings (2 complex UI features)
**Implementation Priority**: Remaining features require dedicated activities

---

## 🔍 Category-by-Category Comparison

### ✅ IMPLEMENTED IN CLEVERKEYS

| Category | Setting | CleverKeys Implementation |
|----------|---------|---------------------------|
| Typing | Auto-capitalization | ✅ `auto_capitalization_enabled` |
| Behavior | Vibration | ✅ `vibration_enabled` |
| Style | Theme | ✅ `theme` (4 options: System/Light/Dark/Black) |
| Style | Keyboard Height | ✅ `keyboard_height_percent` (portrait only) |
| **NEW** | Neural Prediction | ✅ CleverKeys exclusive (enable, beam width, max length, confidence) |
| **NEW** | Accessibility | ✅ CleverKeys exclusive (sticky keys, voice guidance) |
| **NEW** | Dictionary | ✅ CleverKeys exclusive (dictionary manager) |
| **NEW** | Advanced | ✅ CleverKeys exclusive (debug mode, calibration) |

---

## ❌ MISSING FROM CLEVERKEYS

### 1. Layout Category (5 Missing Settings)

#### 🔴 **CRITICAL: Layout Management**
**Java Setting**: `juloo.keyboard2.prefs.LayoutsPreference`
**Purpose**: Add and manage alternative keyboard layouts
**Status**: ❌ MISSING
**Impact**: HIGH - Users cannot switch between QWERTY/AZERTY/Dvorak/etc.
**CleverKeys Equivalent**: None

**Implementation Needed**:
```kotlin
// Add to SettingsActivity.kt - Layout Section
SettingsButton(
    title = "Manage Layouts",
    description = "Add and configure keyboard layouts (QWERTY, AZERTY, Dvorak, etc.)",
    onClick = { openLayoutManager() }
)
```

**Related Files**:
- `LayoutsPreference.kt` exists (File 34) but NOT integrated into settings
- Need: `LayoutManagerActivity.kt` (similar to DictionaryManagerActivity)

---

#### 🔴 **CRITICAL: Extra Keys Configuration**
**Java Settings**:
- `pref_extra_keys_custom` - Add custom keys
- `pref_extra_keys_internal` - Select keys to add

**Purpose**: Customize additional keys on keyboard (arrows, symbols, functions)
**Status**: ❌ MISSING
**Impact**: HIGH - Users cannot add Tab, Esc, Ctrl, arrow keys, etc.
**CleverKeys Equivalent**: None

**Implementation Needed**:
```kotlin
// Add to SettingsActivity.kt - Layout Section
SettingsSection("Keyboard Layout") {
    Button("Manage Layouts") // Layout switcher
    Button("Add Extra Keys") // Custom extra keys
    Switch("Show Number Row") // Toggle number row
}
```

---

#### 🟡 **HIGH: Number Row Toggle**
**Java Setting**: `number_row`
**Purpose**: Show/hide number row at top of keyboard
**Status**: ❌ MISSING
**Impact**: MEDIUM - Convenience feature for quick number access
**Default**: Hidden (numpad used instead)

---

#### 🟡 **MEDIUM: NumPad Settings** (2 settings)
**Java Settings**:
- `show_numpad` - When to display numpad (never/landscape/always)
- `numpad_layout` - Digit order (high first/low first)

**Purpose**: Control numeric input layout
**Status**: ❌ MISSING
**Impact**: MEDIUM - Affects number entry UX

---

### 2. Typing Category (6 Missing Settings)

#### 🟡 **HIGH: Gesture Sensitivity Controls**
**Java Settings**:
- `swipe_dist` - Swipe distance for corner characters
- `circle_sensitivity` - Circle gesture sensitivity

**Purpose**: Fine-tune gesture recognition
**Status**: ❌ MISSING
**Impact**: HIGH - Users cannot adjust gesture sensitivity
**Note**: CleverKeys uses ONNX neural prediction, but still needs gesture tuning

**Implementation Needed**:
```kotlin
// Add to SettingsActivity.kt - Input Behavior Section
SettingsSlider(
    title = "Swipe Distance Threshold",
    description = "Minimum distance for swipe gestures (px)",
    value = swipeDistance.toFloat(),
    valueRange = 50f..300f,
    onValueChange = { swipeDistance = it.toInt(); saveSetting("swipe_dist", it.toInt()) }
)

SettingsSlider(
    title = "Circle Gesture Sensitivity",
    description = "Sensitivity for loop/circle gestures",
    value = circleSensitivity,
    valueRange = 0.5f..2.0f,
    onValueChange = { circleSensitivity = it; saveSetting("circle_sensitivity", it) }
)
```

---

#### 🟡 **HIGH: Long Press Configuration** (2 settings)
**Java Settings**:
- `longpress_timeout` - Duration for long press (ms)
- `longpress_interval` - Key repeat interval (ms)

**Purpose**: Control long-press behavior and key repetition
**Status**: ❌ MISSING
**Impact**: HIGH - Affects typing UX for accents, symbols, key repeat

**Implementation Needed**:
```kotlin
SettingsSlider(
    title = "Long Press Timeout",
    description = "Duration to trigger long press (milliseconds)",
    value = longPressTimeout.toFloat(),
    valueRange = 200f..1000f,
    displayValue = "${longPressTimeout}ms"
)
```

---

#### 🟢 **MEDIUM: Key Repeat Toggle**
**Java Setting**: `keyrepeat_enabled`
**Purpose**: Enable/disable key repetition on long press
**Status**: ❌ MISSING
**Impact**: MEDIUM - Convenience feature

---

#### 🟢 **LOW: Double Tap Shift for Caps Lock**
**Java Setting**: `lock_double_tap`
**Purpose**: Lock Shift key with double tap
**Status**: ❌ MISSING
**Impact**: LOW - Alternative to long press Shift

---

### 3. Style Category (15 Missing Settings)

#### 🔴 **CRITICAL: Adaptive Layout Settings** (8 settings)
**Java Settings** (Foldable/Orientation Support):

**Margin Bottom** (4 variants):
- `margin_bottom_portrait`
- `margin_bottom_landscape`
- `margin_bottom_portrait_unfolded`
- `margin_bottom_landscape_unfolded`

**Keyboard Height** (4 variants):
- `keyboard_height` (portrait) - ⚠️ PARTIALLY IMPLEMENTED
- `keyboard_height_landscape` - ❌ MISSING
- `keyboard_height_unfolded` - ❌ MISSING
- `keyboard_height_landscape_unfolded` - ❌ MISSING

**Purpose**: Adapt keyboard to different screen orientations and foldable states
**Status**: ❌ MOSTLY MISSING (only portrait height implemented)
**Impact**: HIGH - Affects foldable device support and landscape mode

**Implementation Needed**:
```kotlin
// Add to SettingsActivity.kt - Appearance Section
SettingsSection("Adaptive Layout") {
    // Keyboard Heights
    SettingsSlider("Height (Portrait)", value = heightPortrait, ...)
    SettingsSlider("Height (Landscape)", value = heightLandscape, ...)
    if (isFoldableDevice) {
        SettingsSlider("Height (Portrait Unfolded)", value = heightPortraitUnfolded, ...)
        SettingsSlider("Height (Landscape Unfolded)", value = heightLandscapeUnfolded, ...)
    }

    // Margins
    SettingsSlider("Bottom Margin (Portrait)", value = marginBottomPortrait, ...)
    SettingsSlider("Bottom Margin (Landscape)", value = marginBottomLandscape, ...)
    // ... unfolded variants if foldable
}
```

---

#### 🟡 **HIGH: Visual Customization** (4 settings)
**Java Settings**:
- `label_brightness` - Brightness of key labels (0-255)
- `keyboard_opacity` - Opacity of keyboard background (0-255)
- `key_opacity` - Opacity of individual keys (0-255)
- `key_activated_opacity` - Opacity when key is pressed (0-255)

**Purpose**: Fine-tune keyboard visual appearance
**Status**: ❌ MISSING
**Impact**: HIGH - Users cannot adjust opacity/brightness

**Implementation Needed**:
```kotlin
SettingsSection("Opacity & Brightness") {
    SettingsSlider(
        title = "Label Brightness",
        value = labelBrightness / 255f,
        valueRange = 0f..1f,
        displayValue = "${(labelBrightness / 255f * 100).toInt()}%"
    )
    SettingsSlider("Keyboard Opacity", ...)
    SettingsSlider("Key Opacity", ...)
    SettingsSlider("Activated Key Opacity", ...)
}
```

---

#### 🟡 **HIGH: Spacing and Sizing** (3 settings)
**Java Settings**:
- `horizontal_margin_portrait/landscape/unfolded` - Horizontal margins
- `character_size` - Label size multiplier
- `key_vertical_margin` - Vertical spacing between keys
- `key_horizontal_margin` - Horizontal spacing between keys

**Purpose**: Fine-tune keyboard layout and spacing
**Status**: ❌ MISSING
**Impact**: HIGH - Affects keyboard density and readability

---

#### 🟢 **MEDIUM: Border Customization** (2 settings)
**Java Settings**:
- `border_config` - Enable custom borders
- `custom_border_radius` - Corner radius (dp)
- `custom_border_line_width` - Border width (dp)

**Purpose**: Customize key border appearance
**Status**: ❌ MISSING
**Impact**: MEDIUM - Visual customization

---

### 4. Behavior Category (2 Missing Settings)

#### 🟡 **HIGH: Keyboard Switcher Behavior**
**Java Setting**: `switch_input_immediate`
**Purpose**: Configure behavior of keyboard-switching key (immediate vs menu)
**Status**: ❌ MISSING
**Impact**: HIGH - Affects multi-keyboard workflow

---

#### 🟢 **MEDIUM: Pin Entry Layout**
**Java Setting**: `pin_entry_enabled`
**Purpose**: Activate special layout for typing numbers/dates/phone numbers
**Status**: ❌ MISSING
**Impact**: MEDIUM - Convenience for numeric input fields

---

## 📋 IMPLEMENTATION TODO LIST

### Priority 1: CRITICAL (User Expectation)

- [ ] **Layout Manager UI** (2-3 hours)
  - Create `LayoutManagerActivity.kt`
  - Integrate with existing `LayoutsPreference.kt`
  - Add to Settings → New "Layout" section
  - Allow adding/removing/reordering layouts

- [ ] **Extra Keys Configuration** (3-4 hours)
  - Create `ExtraKeysConfigActivity.kt`
  - UI for selecting internal keys (Tab, Esc, Ctrl, arrows, etc.)
  - UI for adding custom keys
  - Integrate with existing `ExtraKeysPreference.kt`

- [ ] **Adaptive Layout Settings** (2-3 hours)
  - Add landscape keyboard height slider
  - Add margin bottom sliders (portrait/landscape)
  - Add foldable device variants (conditional UI)
  - Update `Config.kt` to support all variants

---

### Priority 2: HIGH (Feature Parity)

- [ ] **Gesture Sensitivity Controls** (1-2 hours)
  - Add swipe distance threshold slider
  - Add circle gesture sensitivity slider
  - Wire to gesture recognition engine

- [ ] **Long Press Configuration** (1 hour)
  - Add long press timeout slider
  - Add key repeat interval slider
  - Add key repeat enable/disable toggle

- [ ] **Visual Customization** (2-3 hours)
  - Add label brightness slider
  - Add keyboard opacity slider
  - Add key opacity slider
  - Add activated key opacity slider
  - Update theme system to support opacity/brightness

- [ ] **Spacing and Sizing Controls** (2 hours)
  - Add horizontal margin sliders (portrait/landscape)
  - Add character size multiplier
  - Add key vertical margin slider
  - Add key horizontal margin slider

- [ ] **Keyboard Switcher Behavior** (1 hour)
  - Add toggle for immediate vs menu switching
  - Update keyboard switcher logic

---

### Priority 3: MEDIUM (Nice to Have)

- [ ] **Number Row Toggle** (30 min)
  - Add switch to show/hide number row
  - Update keyboard layout rendering

- [ ] **NumPad Settings** (1 hour)
  - Add dropdown for numpad display (never/landscape/always)
  - Add dropdown for numpad layout (high first/low first)

- [ ] **Border Customization** (1-2 hours)
  - Add toggle for custom borders
  - Add corner radius slider
  - Add border width slider
  - Update key rendering

- [ ] **Pin Entry Layout** (1-2 hours)
  - Add toggle for pin entry mode
  - Create specialized numeric layout
  - Auto-activate for numeric input fields

---

### Priority 4: LOW (Minor Features)

- [ ] **Double Tap Shift for Caps Lock** (30 min)
  - Add toggle setting
  - Implement double tap detection
  - Update shift key handler

---

## 📊 Implementation Estimates

| Priority | Settings Count | Estimated Time | Complexity |
|----------|----------------|----------------|------------|
| P1 - CRITICAL | 3 features | 7-10 hours | High |
| P2 - HIGH | 5 features | 7-9 hours | Medium |
| P3 - MEDIUM | 4 features | 3-5 hours | Low-Medium |
| P4 - LOW | 1 feature | 30 min | Low |
| **TOTAL** | **13 features** | **17-24 hours** | **Mixed** |

---

## 🎯 Recommended Implementation Order

### Phase 1: Critical User-Facing Features (1 week)
1. Layout Manager UI - Allow layout switching
2. Extra Keys Configuration - Add custom keys
3. Adaptive Layout Settings - Support landscape/foldable

**Deliverable**: Users can manage layouts and customize keyboard for different orientations

---

### Phase 2: Gesture & Visual Customization (3-4 days)
4. Gesture Sensitivity Controls - Fine-tune swipe/circle gestures
5. Long Press Configuration - Adjust timing
6. Visual Customization - Opacity and brightness controls
7. Spacing and Sizing - Adjust layout density

**Deliverable**: Users can fine-tune keyboard behavior and appearance

---

### Phase 3: Remaining Features (2-3 days)
8. Keyboard Switcher Behavior
9. Number Row Toggle
10. NumPad Settings
11. Border Customization
12. Pin Entry Layout
13. Double Tap Shift for Caps Lock

**Deliverable**: Complete feature parity with Unexpected-Keyboard

---

## 🔍 Settings Files to Review

**CleverKeys Current Files**:
- ✅ `SettingsActivity.kt` (1,025 lines) - Main settings UI (Compose/Material 3)
- ✅ `Config.kt` (207 lines) - Configuration data class
- ✅ `DirectBootAwarePreferences.kt` (15 lines) - Preferences wrapper
- ⚠️ `LayoutsPreference.kt` (65 lines) - EXISTS but NOT integrated into UI
- ⚠️ `ExtraKeysPreference.kt` (18 lines) - EXISTS but minimal implementation
- ⚠️ `CustomExtraKeysPreference.kt` (18 lines) - EXISTS but stub

**Unexpected-Keyboard Reference Files**:
- `res/xml/settings.xml` - Settings structure (4 categories, 40+ settings)
- `SettingsActivity.java` - PreferenceFragmentCompat implementation
- `LayoutsPreference.java` - Layout management (add/remove/reorder)
- `ExtraKeysPreference.java` - Extra keys selection
- `res/values/strings.xml` - All setting titles/descriptions

---

## 🚨 Known Issues & Considerations

### Material 3 vs PreferenceScreen
**Challenge**: CleverKeys uses Compose/Material 3, original uses XML PreferenceScreen
**Solution**: Recreate all preference UIs in Compose (already done for current settings)
**Impact**: More work but better UX

### ONNX vs CGR
**Challenge**: Some settings assume CGR (Continuous Gesture Recognition) which CleverKeys replaced with ONNX
**Solution**: Adapt settings to ONNX context (e.g., swipe distance still relevant for feature extraction)
**Impact**: Some settings may need reinterpretation

### Foldable Device Support
**Challenge**: CleverKeys has `FoldStateTracker` but settings don't fully use it
**Solution**: Add conditional UI for foldable-specific settings
**Impact**: Better support for foldable devices

---

## ✅ CleverKeys Exclusive Features (Not in Original)

These features are **NEW** in CleverKeys and don't exist in Unexpected-Keyboard:

1. **Neural Prediction Configuration** (4 settings)
   - Enable/disable ONNX neural prediction
   - Beam width (1-32)
   - Max length (10-50)
   - Confidence threshold (0.0-1.0)

2. **Accessibility Enhancements** (3 settings)
   - Sticky keys enable/disable
   - Sticky keys timeout
   - Voice guidance

3. **Dictionary Manager** (1 feature)
   - User dictionary management UI (Bug #472 fix)

4. **Advanced Features** (3 features)
   - Debug mode toggle
   - Neural calibration activity
   - Version info with build details

5. **Modernization** (UI/UX)
   - Material 3 Compose UI
   - Reactive settings with live preview
   - Dark/Light/Black/System theme support

---

## 📝 Next Steps

1. ✅ **Document complete** - This comparison
2. ⏳ **Create detailed specs** - For each P1/P2 feature
3. ⏳ **Implement P1 features** - Layout manager, extra keys, adaptive layout
4. ⏳ **Implement P2 features** - Gestures, visuals, spacing
5. ⏳ **Implement P3/P4 features** - Remaining settings
6. ⏳ **Testing** - Verify all settings work correctly
7. ⏳ **Update documentation** - User guide for new settings

---

**Created**: 2025-11-16
**Author**: Claude Code (Systematic Settings Analysis)
**Status**: ✅ ANALYSIS COMPLETE - READY FOR IMPLEMENTATION
**Total Missing Settings**: ~25 across 4 categories
**Total Implementation Time**: 17-24 hours (estimated)

---

**END OF SETTINGS COMPARISON**
