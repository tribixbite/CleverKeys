# Core Keyboard System

## Overview

The core keyboard system is the foundation of CleverKeys, handling fundamental operations including view initialization, key event processing, layout management, service integration, and input connection handling. It implements Android's `InputMethodService` framework with a custom view hierarchy for rendering and touch handling.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/CleverKeysService.kt` | `CleverKeysService` | Main IME service, lifecycle management, layout switching |
| `src/main/kotlin/tribixbite/cleverkeys/Keyboard2View.kt` | `Keyboard2View` | Custom view rendering, touch event dispatch |
| `src/main/kotlin/tribixbite/cleverkeys/KeyboardData.kt` | `KeyboardData` | Key layout model, key positioning (there is no `Keyboard2.kt`) |
| `src/main/kotlin/tribixbite/cleverkeys/KeyEventHandler.kt` | `KeyEventHandler` | Key press processing, modifier tracking, text editing via `InputConnection` |
| `src/main/kotlin/tribixbite/cleverkeys/KeyboardReceiver.kt` | `KeyboardReceiver` | Implements `KeyEventHandler.IReceiver`; hands out `CleverKeysService.currentInputConnection` |
| `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt` | `Pointers` | Multi-touch handling, gesture recognition |
| `src/main/kotlin/tribixbite/cleverkeys/Config.kt` | `Config` | Keyboard configuration, user preferences |

## Architecture

```
CleverKeysService (InputMethodService)
    ├── Keyboard2View (Custom View)
    │   ├── Config (keyboard configuration)
    │   ├── KeyboardData (key layout model)
    │   └── Pointers (touch handling)
    ├── KeyEventHandler (key press processing + text insertion)
    ├── KeyboardReceiver (IReceiver: InputConnection access, event routing)
    └── ConfigurationManager (runtime config)
```

> **Note (2026-08-21)**: there is no `InputConnectionManager` class — a dead file of that
> name was pruned on 2026-07-17 (`21616bbb`). Nothing wraps `InputConnection`: consumers
> obtain the framework object directly. `KeyEventHandler` gets it via its `IReceiver`
> interface (`KeyEventHandler.kt:970` — implemented by `KeyboardReceiver`, which returns
> `CleverKeysService.currentInputConnection`); `SuggestionHandler` (the single commit
> engine) and `InputCoordinator` (cursor sync + swipe decode replay) receive the
> `InputConnection`/`EditorInfo` pair captured at event time.

### Component Responsibilities

- **CleverKeysService**: Extends `InputMethodService`, manages IME lifecycle, handles `onCreateInputView()`, coordinates layout switching between main/numeric/emoji keyboards
- **Keyboard2View**: Custom `View` subclass that renders keys, handles `onTouchEvent()`, delegates to `Pointers` for multi-touch
- **KeyboardData**: Immutable layout model — which keys exist, their positions/widths/rows
- **KeyEventHandler**: Processes key activations, tracks modifier state (Shift, Ctrl, Alt), handles compose key sequences, and performs text insertion/deletion directly on the `InputConnection` from its `IReceiver`
- **KeyboardReceiver**: Implements `KeyEventHandler.IReceiver`; routes events and exposes `getCurrentInputConnection()`

## Dual Prediction Pipeline

CleverKeys has two independent prediction pipelines that both target the same SuggestionBar:

```
┌─────────────────────────────────────────────────────────────┐
│  Typing Path (SuggestionHandler)                             │
│  handleRegularTyping() → updatePredictionsForCurrentWord()  │
│  Has: contractions, exact_add, I-word capitalization         │
│  Trigger: each keystroke via commitText()                    │
├─────────────────────────────────────────────────────────────┤
│  Cursor Sync Path (InputCoordinator)                         │
│  onCursorMoved() → synchronizeWithCursor() →                │
│  triggerPredictionsForPrefix()                               │
│  Has: contractions, exact_add, I-word capitalization         │
│  Trigger: onUpdateSelection (100ms debounce)                 │
├─────────────────────────────────────────────────────────────┤
│                    SuggestionBar                              │
│  setSuggestionsWithScores() — deduplicates identical content │
│  Last pipeline to post wins; dedup prevents re-render flicker│
└─────────────────────────────────────────────────────────────┘
```

**Pipeline symmetry rule**: Both pipelines MUST produce identical results for the same input. Any feature added to SuggestionHandler (contractions, exact_add, capitalization, prefix guards) must also exist in InputCoordinator's `triggerPredictionsForPrefix()`. Without this, the cursor sync path overwrites the typing path's results ~100ms later, causing visible flicker.

**Key safety mechanisms**:
- `contextTracker.clearAll()` in `onFinishInputView()` prevents cross-app text leaking
- `Handler(Looper.getMainLooper()).post{}` instead of `View.post{}` — detached views silently drop runnables
- Paired contraction injection requires prefix >= 3 chars (prevents "t" → "t's" outranking "the")

## Data Flow

```
Touch Event → Keyboard2View.onTouchEvent()
    → Pointers.onTouchEvent() (gesture classification)
    → KeyEventHandler (key activation)
    → recv.getCurrentInputConnection().commitText() / sendKeyEvent()
      (recv = KeyboardReceiver → CleverKeysService.currentInputConnection)
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

### Text Editing (no wrapper class)

Text editing calls go straight to the framework `InputConnection` — there is no
`InputConnectionManager` wrapper (see the note under Architecture). The access seam is:

```kotlin
// KeyEventHandler.kt:970 — the interface KeyEventHandler edits text through
interface IReceiver {
    fun getCurrentInputConnection(): InputConnection?
    // ... event routing callbacks
}

// Typical call sites inside KeyEventHandler:
val conn = recv.getCurrentInputConnection() ?: return
conn.commitText(textToCommit, 1)
conn.deleteSurroundingText(charsToDelete, 0)
```

Suggestion commits flow through `SuggestionHandler` (the single commit engine), and
swipe-decode results replay the `InputConnection`/`EditorInfo` captured at swipe time
(`InputCoordinator`, staleness-guarded).

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

### Editing Keys (copy/paste/cut/select-all)

Editing operations are dedicated `KeyValue.Editing` keys, handled in
`KeyEventHandler.handleEditingKey` by sending context-menu actions directly on the
`InputConnection` (no wrapper class):

```kotlin
// KeyEventHandler.kt (abridged from handleEditingKey / sendContextMenuAction)
when (ev) {
    KeyValue.Editing.COPY -> if (isSelectionNotEmpty()) sendContextMenuAction(android.R.id.copy)
    KeyValue.Editing.PASTE -> handlePaste()   // #113: terminal-aware fallback
    KeyValue.Editing.CUT -> if (isSelectionNotEmpty()) sendContextMenuAction(android.R.id.cut)
    KeyValue.Editing.SELECT_ALL -> sendContextMenuAction(android.R.id.selectAll)
    KeyValue.Editing.UNDO -> sendContextMenuAction(android.R.id.undo)
    // ...
}
private fun sendContextMenuAction(id: Int) {
    val conn = recv.getCurrentInputConnection() ?: return
    conn.performContextMenuAction(id)
}
```

In clipboard inline-edit mode the same keys are rerouted to the clipboard edit field via
`IReceiver` (`pasteToClipboardEdit()`, `selectAllClipboardEdit()`, ...).

### Hardware Acceleration

Hardware acceleration is enabled at both manifest and application level for 60fps rendering:

```xml
<!-- AndroidManifest.xml -->
<manifest android:hardwareAccelerated="true">
    <application android:hardwareAccelerated="true">
```

This is critical for smooth keyboard rendering and ONNX model compatibility.
