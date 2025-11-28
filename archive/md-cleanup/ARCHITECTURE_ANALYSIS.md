# CleverKeys Architecture Analysis

## Style & Background System: ✅ MODULAR & COMPOSABLE

### Theme System Architecture

**Location:** `src/main/kotlin/tribixbite/keyboard2/Theme.kt`

#### Modularity Features:

1. **Composable Theme System**
   - ✅ Separate `Theme` class for theming logic
   - ✅ `Theme.Computed` inner class for runtime-computed values
   - ✅ Factory pattern: `Theme.createFromContext()`
   - ✅ System theme integration: `Theme.getSystemThemeData()`

2. **Dynamic Theme Properties**
   ```kotlin
   // Core colors (modular)
   - colorKey: Int
   - colorKeyActivated: Int
   - lockedColor: Int
   - activatedColor: Int
   - labelColor: Int
   - subLabelColor: Int
   - secondaryLabelColor: Int
   - greyedLabelColor: Int

   // Border properties (modular)
   - keyBorderRadius: Float
   - keyBorderWidth: Float
   - keyBorderWidthActivated: Float
   - keyBorderColorLeft/Top/Right/Bottom: Int
   ```

3. **Opacity Control (Composable)**
   ```kotlin
   // Config.kt - All independently configurable
   - keyboardOpacity: 0-255 (Int)
   - keyOpacity: 0-255 (Int)
   - keyActivatedOpacity: 0-255 (Int)
   - suggestion_bar_opacity: 0-100 (Int)
   ```

4. **Theme Selection**
   ```kotlin
   // Configurable via preferences
   - theme = R.style.Dark / R.style.Light / R.style.AltBlack / etc.
   - Auto system theme detection
   - Material You dynamic colors support
   ```

### Background System

**Modularity Level: ✅ EXCELLENT**

1. **Multi-Layer Background Control**
   - Keyboard background: `Config.keyboardOpacity`
   - Individual key background: `Config.keyOpacity`
   - Activated key background: `Config.keyActivatedOpacity`
   - System integration: `Theme.colorNavBar`

2. **Paint Object Caching** (Performance optimization)
   - Theme.Computed caches Paint objects
   - Prevents GC churn during rendering
   - Recomputed only when theme changes

3. **Custom Font Loading**
   ```kotlin
   Theme.getKeyFont(context) // Loads special_font.ttf
   - Fallback to system font if missing
   - Singleton pattern for memory efficiency
   ```

### Architecture Score: 9/10
- ✅ Fully modular theme system
- ✅ Composable color/opacity properties
- ✅ System theme integration
- ✅ Paint caching for performance
- ✅ Custom font support
- ✅ Reactive updates via Config

---

## Swipe Symbol Configuration: ✅ FULLY CONFIGURABLE

### Directional Key System

**Location:** `src/main/kotlin/tribixbite/keyboard2/KeyboardData.kt`

#### Architecture:

1. **9-Directional Key Model** (Lines 206-285)
   ```kotlin
   data class Key(
       val keys: Array<KeyValue?>, // Index 0-8 for 9 positions
       val anticircle: KeyValue? = null,
       ...
   )

   Layout:
    1 7 2    (NW, N, NE)
    5 0 6    (W, CENTER, E)
    3 8 4    (SW, S, SE)
   ```

2. **Each Direction Independently Configurable**
   - Index 0: Center tap
   - Index 1-8: Swipe directions (NW, NE, SW, SE, N, E, S, W)
   - Each can have different `KeyValue` types:
     - `CharKey` - single character
     - `StringKey` - multi-character string
     - `KeyEventKey` - Android key event
     - `EventKey` - custom keyboard event
     - `ModifierKey` - shift/ctrl/alt
     - `SliderKey` - continuous input

3. **XML Layout Definition**
   - Layouts stored in: `res/xml/*.xml`
   - Each key defines all 9 directional values
   - Parsed by `KeyboardData.load()`
   - Example: QWERTY US layout has configurable swipe symbols

4. **Slider System** (Continuous swipe input)
   ```kotlin
   data class Slider(
       val increment: Float,
       val precision: Int = 2
   )

   // Configurable in Config.kt:
   - sliderSensitivity: 0-100 (affects slide_step_px)
   ```

### KeyValue System (Symbol Types)

**Location:** `src/main/kotlin/tribixbite/keyboard2/KeyValue.kt`

#### Supported Symbol Types:

1. **CharKey** - Single character output
2. **StringKey** - Multi-character string
3. **KeyEventKey** - Android KeyEvent (arrows, delete, etc.)
4. **EventKey** - Custom keyboard events
5. **ModifierKey** - Shift, Ctrl, Alt, Meta
6. **SliderKey** - Continuous numeric/directional input

#### Configuration Methods:

1. **Static Configuration (XML)**
   - Edit layout files: `res/xml/latn_qwerty_us.xml`
   - Define symbols for each swipe direction
   - Loaded at runtime

2. **Dynamic Configuration (Code)**
   ```kotlin
   key.withKeyValue(index: Int, keyValue: KeyValue)
   // Allows runtime modification of any direction
   ```

3. **Custom Layout Editor** (UI-based)
   - File: `CustomLayoutEditor.kt`
   - Allows visual editing of keys and swipe symbols
   - Saves custom layouts to preferences

### Configuration Score: 10/10
- ✅ 9 independent directional values per key
- ✅ Multiple key value types supported
- ✅ XML-based static configuration
- ✅ Runtime dynamic modification
- ✅ Visual layout editor
- ✅ Custom layout persistence
- ✅ Slider system for continuous input

---

## Summary

### Style & Background: ✅ YES - Modular & Composable
- Fully modular Theme system
- Composable opacity controls (keyboard, key, activated key)
- System theme integration
- Custom fonts
- Paint caching for performance

### Swipe Symbols: ✅ YES - Fully Configurable
- 9-directional key model
- Multiple KeyValue types (char, string, event, modifier, slider)
- XML-based configuration
- Runtime modification API
- Visual layout editor
- Custom layout persistence

### Architecture Quality: EXCELLENT
Both systems follow modern Android/Kotlin best practices:
- Separation of concerns
- Composition over inheritance
- Immutable data classes
- Factory patterns
- Reactive updates
- Performance optimization (caching, pooling)

### User Configurability:
- **Themes:** Multiple built-in themes + system theme
- **Colors:** All theme colors configurable
- **Opacity:** Keyboard, keys, and suggestion bar
- **Swipe Symbols:** Every key, every direction, via XML or UI editor
- **Layouts:** Built-in layouts + custom layout creation
