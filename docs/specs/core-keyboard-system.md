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

- **CleverKeysService**: Extends `InputMethodService`, manages IME lifecycle, handles `onCreateInputView()`, exposes the `LayoutBridge` layout surface (actual switching is dispatched by `KeyboardReceiver.handle_event_key` — emoji/clipboard/GIF are content panes, not layouts)
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

Layout switching is EVENT-driven, not method-per-layout (verified 2026-08-21 — there is no
`switchLayout`/`switchToNumeric`/`Keyboard2` anywhere). A layout key carries a
`KeyValue.Kind.Event` value which `KeyEventHandler.key_up` routes to the receiver:

```
User releases a layout key (e.g. SWITCH_NUMERIC, SWITCH_FORWARD)
    → KeyEventHandler.key_up() — Kind.Event → recv.handle_event_key(ev)
    → KeyboardReceiver.handle_event_key(ev) dispatches:
        SWITCH_TEXT      → keyboardView.setKeyboard(layoutManager.clearSpecialLayout())
        SWITCH_NUMERIC   → keyboardView.setKeyboard(layoutManager.loadNumpad(R.raw.numeric))
        SWITCH_GREEKMATH → keyboardView.setKeyboard(layoutManager.loadNumpad(R.xml.greekmath))
        SWITCH_FORWARD / SWITCH_BACKWARD
                         → keyboardView.setKeyboard(layoutManager.incrTextLayout(±1))
        SWITCH_EMOJI / SWITCH_CLIPBOARD / SWITCH_GIF
                         → open a CONTENT PANE above the keyboard (not a layout change)
    → Keyboard2View.setKeyboard() re-renders
```

`LayoutManager` owns the layout list/state; `CleverKeysService` also exposes thin
delegators to `LayoutBridge` (see Public API below) used by subtype/config plumbing.

## Configuration

> Verified against `Config.kt` on 2026-08-21. Three keys previously listed here —
> `keyboard_height_percent`, `key_vibration_enabled`, `swipe_enabled` — do not exist under those
> names; the real keys are below. Copying the old names into code would have silently read the
> default forever, since `getBoolean`/`getInt` on an absent key cannot fail.

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `keyboard_height` | Int (percent) | 27 portrait / 40 landscape | Keyboard height. `keyboard_height_unfolded` is used instead when the device is a folded-open foldable (`Config.kt:673`) |
| `hardware_acceleration` | Boolean | true | Enable GPU rendering (AndroidManifest) |
| `longpress_timeout` | Int (ms) | 600 | Milliseconds before long-press triggers |
| `vibration_enabled` | Boolean | true | Master haptic toggle; read into `Config.haptic_enabled` (`Config.kt:698`) |
| `swipe_typing_enabled` | Boolean | true | Enable swipe typing |

## Public API

> Transcribed from live code 2026-08-21. Earlier revisions of this section listed
> `switchLayout(layoutId: String)`, `switchToNumeric()`, `switchToEmoji()`,
> `switchToMain()`, `getCurrentLayoutId()`, `handleKeyDown(key, modifiers): Boolean`,
> `handleKeyUp(key): Boolean`, `isModifierActive(...)` and `getModifierState()` —
> **none of those methods exist**. Numeric/emoji/main switching is event-driven via
> `KeyboardReceiver.handle_event_key` (see Layout Switching Flow above).

### CleverKeysService (layout surface — all delegate to `LayoutBridge`)

```kotlin
// CleverKeysService.kt (verified signatures)
fun current_layout(): KeyboardData             // layout currently visible
fun current_layout_unmodified(): KeyboardData  // before per-editor modification
fun setTextLayout(l: Int)                      // select text layout by index
fun incrTextLayout(delta: Int)                 // cycle to next/previous text layout
fun setSpecialLayout(l: KeyboardData)          // e.g. numeric/pinentry replacement
fun loadLayout(layout_id: Int): KeyboardData?  // load a layout from resources
fun loadNumpad(layout_id: Int): KeyboardData?  // load a numpad-bearing layout
fun loadPinentry(layout_id: Int): KeyboardData?
```

### KeyEventHandler

```kotlin
// Implements Config.IKeyEventHandler (Config.kt:1112); driven by Pointers.
override fun key_down(key: KeyValue?, isSwipe: Boolean)                        // KeyEventHandler.kt:65
override fun key_up(key: KeyValue?, mods: Pointers.Modifiers, isKeyRepeat: Boolean)  // :89
override fun mods_changed(mods: Pointers.Modifiers)                            // :144

// Lifecycle hooks called by the service
fun started(info: EditorInfo)                                  // editing began
fun selection_updated(oldSelStart: Int, newSelStart: Int)      // cursor moved

// Raw key-event emission
fun send_key_down_up(keyCode: Int)                 // applies current system metaState
fun send_key_down_up(keyCode: Int, metaState: Int) // explicit meta state
```

Nothing here returns `Boolean` — key handling has no consumed/not-consumed contract;
`key_down`/`key_up` are fire-and-forget from `Pointers`.

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

### View Initialization

`Keyboard2View` reads the global config eagerly in its `init` block — there is no lazy
wrapper and no `Config.defaultConfig()` fallback (no such factory exists; verified
2026-08-21). `Config.globalConfig()` throws if the config was never initialized, so the
view requires `Config.initGlobalConfig(...)` to have run first (the service does this in
`onCreate`):

```kotlin
// Keyboard2View.kt init block (abridged)
_config = Config.globalConfig()
_theme = if (_config.isRuntimeTheme()) {
    ThemeProvider.getInstance(context).getTheme(_config.themeName)
} else {
    Theme(getContext(), attrs)
}
```

### Modifier Key Handling

There is no custom `META_SHIFT`/`modifierState` bitmask (an earlier revision of this doc
invented one). `KeyEventHandler` tracks two pieces of state (verified 2026-08-21):

```kotlin
// KeyEventHandler.kt:31,36
private var mods: Pointers.Modifiers = Pointers.Modifiers.EMPTY  // logical modifier set
private var metaState = 0   // Android KeyEvent.META_* flags, kept consistent with mods
```

`updateMetaState(newMods)` diffs the old and new modifier sets and, for each changed
CTRL/ALT/SHIFT/META modifier, sends a synthetic `KEYCODE_{CTRL,ALT,SHIFT,META}_LEFT`
down/up event while or-ing/clearing the matching `KeyEvent.META_*_LEFT_ON | META_*_ON`
flags in `metaState` (`sendMetaKeyForModifier` / `sendMetaKey`, KeyEventHandler.kt:226-279).
`key_up` brackets each key action with `updateMetaState(mods)` before and
`updateMetaState(oldMods)` after, so system modifiers are held only for the duration of the
key event they modify. `mods_changed` applies modifier changes with no key attached.

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
