# IME Key Routing Skill

Use this skill when adding new inline input modes to the keyboard (search boxes, edit fields, tag inputs, or any view that needs to receive typed text within the IME's own UI). This is the single most important pattern for clipboard and content pane features.

## The Core Problem

Android IMEs **cannot type into their own views**. When the user taps a key on CleverKeys, `KeyEventHandler.sendText()` sends the text to the **target app's InputConnection** (e.g., a text field in Chrome). There is no way to redirect this to an EditText or TextView inside the keyboard's own UI using standard Android APIs.

**Solution**: Intercept key events in `KeyEventHandler.sendText()` and route them to the correct internal view based on the current mode.

## The Routing Chain

```
Key press → KeyEventHandler.sendText(text)
    ↓
    Checks modes in PRIORITY ORDER:
    1. recv.isClipboardTagMode()    → recv.insertToClipboardTag(text)
    2. recv.isClipboardEditMode()   → recv.insertToClipboardEdit(text)
    3. recv.isClipboardSearchMode() → recv.appendToClipboardSearch(text)
    4. recv.isEmojiPaneOpen()       → recv.appendToEmojiSearch(text)
    5. recv.isGifPaneOpen()         → recv.appendToGifSearch(text)
    6. (none active)                → send to target app InputConnection
```

Backspace follows the **same priority chain** in `key_down()` for `KEYCODE_DEL`.

## File Responsibilities

| Layer | File | Role |
|-------|------|------|
| **Router** | `KeyEventHandler.kt` | Checks mode flags, calls appropriate insert/backspace methods |
| **Interface** | `KeyEventHandler.IReceiver` | Defines mode check + text manipulation methods with default no-ops |
| **Bridge** | `KeyboardReceiver.kt` | Implements `IReceiver`, delegates to managers |
| **Manager** | `ClipboardManager.kt` | Owns clipboard mode state, delegates to views |
| **View** | `ClipboardHistoryView.kt` | Owns the actual EditText/TextView and performs text manipulation |

## How to Add a New Input Mode

### Step 1: Define IReceiver methods (KeyEventHandler.kt)

Add to the `IReceiver` interface at the correct priority position:

```kotlin
interface IReceiver {
    // ... existing methods ...

    // NEW: My custom input mode
    fun isMyCustomMode(): Boolean = false
    fun insertToMyCustomMode(text: String) {}
    fun backspaceMyCustomMode() {}
}
```

**Default implementations return false/no-op** so existing IReceiver implementors don't break.

### Step 2: Add routing in sendText() and key_down() (KeyEventHandler.kt)

In `sendText()`, add the check **at the correct priority position**:

```kotlin
private fun sendText(text: CharSequence, isKeyRepeat: Boolean = false) {
    // Higher priority modes checked first
    if (recv.isClipboardTagMode()) { ... }
    if (recv.isClipboardEditMode()) { ... }

    // NEW: Insert before search mode if it should take priority over search
    if (recv.isMyCustomMode()) {
        recv.insertToMyCustomMode(text.toString())
        return
    }

    if (recv.isClipboardSearchMode()) { ... }
    // ...
}
```

In `key_down()`, add KEYCODE_DEL handling in the same priority chain:

```kotlin
if (key.getKeyevent() == KeyEvent.KEYCODE_DEL && recv.isMyCustomMode()) {
    recv.backspaceMyCustomMode()
}
```

### Step 3: Implement in KeyboardReceiver.kt

```kotlin
override fun isMyCustomMode(): Boolean {
    return clipboardManager.isInMyCustomMode()
}

override fun insertToMyCustomMode(text: String) {
    clipboardManager.insertToMyCustom(text)
}

override fun backspaceMyCustomMode() {
    clipboardManager.backspaceFromMyCustom()
}
```

### Step 4: Add manager methods (ClipboardManager.kt)

```kotlin
// Mode state
private var myCustomMode = false
private var myCustomEditText: EditText? = null  // or TextView

fun isInMyCustomMode(): Boolean = myCustomMode

fun insertToMyCustom(text: String) {
    myCustomEditText?.let { et ->
        val editable = et.text ?: return
        val start = et.selectionStart.coerceIn(0, editable.length)
        val end = et.selectionEnd.coerceIn(start, editable.length)
        editable.replace(start, end, text)
    }
}

fun backspaceFromMyCustom() {
    myCustomEditText?.let { et ->
        val editable = et.text ?: return
        val start = et.selectionStart.coerceIn(0, editable.length)
        val end = et.selectionEnd.coerceIn(0, editable.length)
        if (start != end) {
            editable.delete(minOf(start, end), maxOf(start, end))
        } else if (start > 0) {
            editable.delete(start - 1, start)
        }
    }
}
```

### Step 5: EditText/TextView setup

**Critical**: Set `inputType = InputType.TYPE_NULL` on any EditText within the IME:

```kotlin
val editText = EditText(context).apply {
    inputType = InputType.TYPE_NULL  // Prevents Android soft keyboard
    // We ARE the keyboard — input routed via KeyEventHandler
}
```

For `TextView` (like the search bar), use `text` property directly since it's not editable.

## Priority Rules

1. **Tag mode > Edit mode > Search mode** — Tag and edit are modal (one entry at a time). Search is ambient (filters while browsing).
2. **Clipboard modes > Emoji/GIF modes** — These are mutually exclusive panes, but the check order matters for safety.
3. **Mutual exclusion**: Entering edit mode clears search mode. Entering tag mode should block edit mode.
4. **Guard clicks**: All click handlers in getView() check `if (!isEditing())` to prevent conflicting states.

## The Inline View Pattern (NOT AlertDialog)

**NEVER use AlertDialog for input within the IME.**

AlertDialog creates a separate window (`TYPE_APPLICATION_ATTACHED_DIALOG`) that:
- Flickers and teleports (window positioning fights with IME window)
- Cannot reliably receive key events from the keyboard below
- Obscures the keyboard, breaking the user experience

**Instead, use inline views** embedded in the clipboard pane's own view hierarchy:
- **Search bar**: `TextView` in `clipboard_pane.xml`, always visible in the header row
- **Edit field**: `EditText` in `clipboard_history_entry.xml`, shown per-entry when editing
- **Tag panel**: `LinearLayout` in `clipboard_pane.xml`, replaces entry list when active

These views live inside the IME's own view tree, so:
- No separate window = no flicker/teleport
- Key routing works identically through the chain above
- The keyboard remains visible below the panel

## Existing Modes Reference

### Search Mode (ambient)
- **State**: `ClipboardManager.searchMode: Boolean`
- **View**: `TextView` (`clipboard_search`) in `clipboard_pane.xml`
- **Activation**: Tap search box → `searchMode = true`
- **Deactivation**: Tap X button or `clearSearch()`
- **Text ops**: `appendToSearch()`, `deleteFromSearch()` (character-level)

### Edit Mode (modal, per-entry)
- **State**: `ClipboardHistoryView.editingOriginalContent: String?` (non-null = active)
- **View**: `EditText` (`clipboard_entry_edit_field`) in `clipboard_history_entry.xml`
- **Activation**: Tap edit button → `edit_entry(pos)`
- **Deactivation**: Save or cancel → `cancelEdit()`
- **Text ops**: `insertEditText()`, `backspaceEditText()`, `pasteToEditText()`, etc.
- **Lifecycle**: `onEditModeEntered` / `onEditModeExited` callbacks to lock UI

### Tag Mode (modal, pane-level)
- **State**: `ClipboardManager.tagMode: Boolean` (or `tagEditText != null`)
- **View**: Inline panel in `clipboard_pane.xml` (replaces entry list)
- **Activation**: Tap tags button → show tag panel
- **Deactivation**: Tap close → hide tag panel
- **Text ops**: `insertToTag()`, `backspaceFromTag()` (into tag name EditText)

## Common Gotchas

- **inputType = TYPE_NULL**: Always set this on EditText within IME views. Without it, Android will try to show its own soft keyboard (which IS the current keyboard — infinite recursion).
- **Cursor clamping**: Always use `.coerceIn(0, editable.length)` when accessing `selectionStart`/`selectionEnd`. View recycling can leave stale cursor positions.
- **View recycling**: In `BaseAdapter.getView()`, use content-identity matching (not position) for tracking which entry is being edited. List positions shift when entries are added/deleted.
- **TextWatcher accumulation**: When using `EditText` in a recycled view, remove the old `TextWatcher` before adding a new one. Otherwise watchers accumulate on each `getView()` call.
- **PopupWindow in IME**: Must set `isFocusable = false` to prevent panel dismiss.
- **View.post() for detached views**: Use `Handler(Looper.getMainLooper()).post()` instead of `view.post()` — detached views silently drop runnables.

## Testing

To verify a new input mode works:
1. Open the keyboard in a text field
2. Open the clipboard/emoji pane
3. Activate the new mode (tap the relevant UI element)
4. Type on the keyboard below
5. Verify text appears in the correct view (not in the target app's text field)
6. Verify backspace works
7. Deactivate the mode
8. Verify typing goes back to the target app

## Related Skills
- `content-pane-layout.md` — View hierarchy and pane switching
- `clipboard-panel-architecture.md` — Clipboard-specific tabs, filtering, pagination
