# Core Keyboard System

## Overview

The core keyboard system is the foundation of CleverKeys, handling fundamental operations including view initialization, key event processing, layout management, service integration, and input connection handling. It implements Android's `InputMethodService` framework with a custom view hierarchy for rendering and touch handling.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/CleverKeysService.kt` | `CleverKeysService` | Main IME service, lifecycle management, layout switching |
| `src/main/kotlin/tribixbite/cleverkeys/Keyboard2View.kt` | `Keyboard2View` | Custom view rendering, touch event dispatch |
| `src/main/kotlin/tribixbite/cleverkeys/Keyboard2.kt` | `Keyboard2` | Key layout logic, state management, key positioning |
| `src/main/kotlin/tribixbite/cleverkeys/KeyEventHandler.kt` | `KeyEventHandler` | Key press processing, modifier tracking, compose sequences |
| `src/main/kotlin/tribixbite/cleverkeys/InputConnectionManager.kt` | `InputConnectionManager` | Text editing, cursor control, clipboard operations |
| `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt` | `Pointers` | Multi-touch handling, gesture recognition |
| `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | `Config` | Keyboard configuration, user preferences |

## Architecture

```
CleverKeysService (InputMethodService)
    ├── Keyboard2View (Custom View)
    │   ├── Config (keyboard configuration)
    │   ├── Keyboard2 (key layout logic)
    │   └── Pointers (touch handling)
    ├── KeyEventHandler (key press processing)
    ├── InputConnectionManager (text insertion)
    └── ConfigurationManager (runtime config)
```

### Component Responsibilities

- **CleverKeysService**: Extends `InputMethodService`, manages IME lifecycle, handles `onCreateInputView()`, coordinates layout switching between main/numeric/emoji keyboards
- **Keyboard2View**: Custom `View` subclass that renders keys, handles `onTouchEvent()`, delegates to `Pointers` for multi-touch
- **Keyboard2**: Manages which keys are displayed, their positions, and state (pressed, locked, shifted)
- **KeyEventHandler**: Processes key activations, tracks modifier state (Shift, Ctrl, Alt), handles compose key sequences
- **InputConnectionManager**: Wraps `InputConnection`, provides text insertion/deletion, cursor movement, selection handling

## Data Flow

```
Touch Event → Keyboard2View.onTouchEvent()
    → Pointers.onTouchEvent() (gesture classification)
    → KeyEventHandler.handleKeyDown/Up()
    → InputConnectionManager.commitText() / sendKeyEvent()
    → Target App receives text
```

### Layout Switching Flow

```
User triggers layout switch (key or API)
    → CleverKeysService.switchLayout(layoutId)
    → Keyboard2.loadLayout(layoutId)
    → Keyboard2View.invalidate() (re-render)
```

## Configuration

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `keyboard_height_percent` | Float | 0.30 | Keyboard height as fraction of screen |
| `hardware_acceleration` | Boolean | true | Enable GPU rendering (AndroidManifest) |
| `longpress_timeout` | Int | 600 | Milliseconds before long-press triggers |
| `key_vibration_enabled` | Boolean | true | Haptic feedback on key press |
| `swipe_enabled` | Boolean | true | Enable swipe typing |

## Public API

### CleverKeysService

```kotlin
// Switch to a specific layout
fun switchLayout(layoutId: String)

// Get current layout ID
fun getCurrentLayoutId(): String

// Switch to numeric layout
fun switchToNumeric()

// Switch to emoji layout
fun switchToEmoji()

// Return to main layout
fun switchToMain()
```

### KeyEventHandler

```kotlin
// Process a key activation
fun handleKeyDown(key: KeyValue, modifiers: Int): Boolean

// Release a key
fun handleKeyUp(key: KeyValue): Boolean

// Check if modifier is active
fun isModifierActive(modifier: Int): Boolean

// Get current modifier state
fun getModifierState(): Int
```

### InputConnectionManager

```kotlin
// Commit text at cursor
fun commitText(text: CharSequence)

// Delete characters before cursor
fun deleteSurroundingText(beforeLength: Int, afterLength: Int)

// Move cursor
fun moveCursor(offset: Int)

// Get text around cursor
fun getTextAroundCursor(before: Int, after: Int): CharSequence?
```

## Implementation Details

### View Initialization Safety

`Keyboard2View` uses lazy initialization for `Config` to safely handle creation in different contexts (service, preview, standalone):

```kotlin
private val config: Config by lazy {
    Config.globalConfig() ?: Config.defaultConfig()
}
```

### Modifier Key Handling

Modifier state is tracked as a bitmask in `KeyEventHandler`:

```kotlin
const val META_SHIFT = 0x01
const val META_CTRL = 0x02
const val META_ALT = 0x04

private var modifierState: Int = 0

fun handleModifier(key: KeyValue, pressed: Boolean) {
    val mask = when (key.kind) {
        KeyValue.Kind.Shift -> META_SHIFT
        KeyValue.Kind.Ctrl -> META_CTRL
        KeyValue.Kind.Alt -> META_ALT
        else -> return
    }
    modifierState = if (pressed) modifierState or mask else modifierState and mask.inv()
}
```

### Keyboard Shortcuts

Ctrl+key shortcuts are processed in `KeyEventHandler`:

```kotlin
fun handleKeyDown(key: KeyValue, modifiers: Int): Boolean {
    if (modifiers and META_CTRL != 0) {
        when (key.char) {
            'a' -> return inputManager.selectAll()
            'c' -> return inputManager.copy()
            'v' -> return inputManager.paste()
            'x' -> return inputManager.cut()
            'z' -> return inputManager.undo()
        }
    }
    // ... normal key handling
}
```

### Hardware Acceleration

Hardware acceleration is enabled at both manifest and application level for 60fps rendering:

```xml
<!-- AndroidManifest.xml -->
<manifest android:hardwareAccelerated="true">
    <application android:hardwareAccelerated="true">
```

This is critical for smooth keyboard rendering and ONNX model compatibility.
