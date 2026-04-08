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
| **Delegation Bridge** | `KeyEventReceiverBridge.kt` | Proxies `IReceiver` calls to `KeyboardReceiver` — **KeyEventHandler's `recv` is THIS, not KeyboardReceiver** |
| **Receiver** | `KeyboardReceiver.kt` | Full `IReceiver` impl, delegates to managers |
| **Manager** | `ClipboardManager.kt` | Owns clipboard mode state, delegates to views |
| **View** | `ClipboardHistoryView.kt` | Owns the actual EditText/TextView and performs text manipulation |

> **⚠ CRITICAL**: `KeyEventHandler.recv` → `KeyEventReceiverBridge` → `KeyboardReceiver` → Manager.
> If you add methods to `IReceiver` + `KeyboardReceiver` but forget `KeyEventReceiverBridge`,
> calls silently fall through to `IReceiver` defaults (false/no-op). No crash, no log.
> This bug has occurred TWICE: emoji search (#41 v7) and tag mode.

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

### Step 3: Add delegation in KeyEventReceiverBridge.kt

**This step is mandatory. Skipping it = silent no-op (the bug that hit emoji search and tag mode).**

The bridge proxies all `IReceiver` calls from `KeyEventHandler` to `KeyboardReceiver`. New methods need explicit delegation:

```kotlin
// In KeyEventReceiverBridge.kt — add alongside existing mode delegations
override fun isMyCustomMode(): Boolean {
    return receiver?.isMyCustomMode() ?: false
}

override fun insertToMyCustomMode(text: String) {
    receiver?.insertToMyCustomMode(text)
}

override fun backspaceMyCustomMode() {
    receiver?.backspaceMyCustomMode()
}
```

### Step 4: Implement in KeyboardReceiver.kt

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

### Step 5: Add manager methods (ClipboardManager.kt)

```kotlin
// Mode state
private var myCustomMode = false
private var myCustomEditText: EditText? = null  // or TextView

fun isInMyCustomMode(): Boolean = myCustomMode

// ⚠ ALWAYS use setText + setSelection — NOT editable.replace() or EditText.append().
// Those methods don't reliably work when the IME owns the view.
// See KeyboardReceiver.kt:752 comment and GIF/emoji search implementations.
fun insertToMyCustom(text: String) {
    val et = myCustomEditText ?: return
    val current = et.text?.toString() ?: ""
    val newText = current + text
    et.setText(newText)
    et.setSelection(newText.length)
}

fun backspaceFromMyCustom() {
    val et = myCustomEditText ?: return
    val current = et.text?.toString() ?: ""
    if (current.isNotEmpty()) {
        val newText = current.dropLast(1)
        et.setText(newText)
        et.setSelection(newText.length)
    }
}
```

### Step 6: EditText/TextView setup

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

- **KeyEventReceiverBridge is the #1 cause of "input goes to app instead of panel"**. When adding new `IReceiver` methods, you must add delegation in THREE files: `IReceiver` (interface), `KeyEventReceiverBridge` (delegation), `KeyboardReceiver` (implementation). Missing the bridge = silent no-op with no crash or log. This has happened twice.
- **setText + setSelection, NEVER editable.replace() or append()**: `EditText.append()` and `Editable.replace()` don't reliably work when the IME owns the view. Always use `et.setText(newText); et.setSelection(newText.length)`.
- **inputType = TYPE_NULL**: Always set this on EditText within IME views. Without it, Android will try to show its own soft keyboard (which IS the current keyboard — infinite recursion). `setSelection()` and `selectionStart/End` operate on Editable spans and work correctly regardless of inputType.
- **Cursor clamping**: Always use `.coerceIn(0, editable.length)` when accessing `selectionStart`/`selectionEnd`. View recycling can leave stale cursor positions.
- **View recycling**: In `BaseAdapter.getView()`, use content-identity matching (not position) for tracking which entry is being edited. List positions shift when entries are added/deleted.
- **TextWatcher accumulation**: When using `EditText` in a recycled view, remove the old `TextWatcher` before adding a new one. Otherwise watchers accumulate on each `getView()` call. Use `view.getTag()`/`setTag()` to track the active TextWatcher per view instance.
- **PopupWindow in IME**: Must set `isFocusable = false` to prevent panel dismiss.
- **View.post() for detached views**: Use `Handler(Looper.getMainLooper()).post()` instead of `view.post()` — detached views silently drop runnables.

## ListView Scrap View Architecture (Critical for EditText in Adapters)

When a `BaseAdapter`'s `getView()` renders an `EditText` whose height can change (e.g., multiline after Enter), ListView calls `measureHeightOfChildren()` which invokes `getView()` with a **scrap view** — a detached, invisible view used only for measurement. This has severe consequences:

**The Problem**: If you cache a reference to the scrap view's EditText (e.g., `editingEditText = editField`), all subsequent key routing goes to an invisible detached view. The user presses keys but nothing appears to happen.

**Required Architecture** (implemented in `ClipboardHistoryView.getView()`):

1. **Scrap-safe reference caching**: Only cache `editingEditText = editField` when the reference is null (first render). Use `if (editingEditText == null) { editingEditText = editField }`.

2. **Dynamic recovery property** (`activeEditingEditText`): A computed property that validates the cached reference via `windowToken` (null = detached) and falls back to scanning live child views. All text manipulation methods use this instead of the raw field.

3. **Setup OUTSIDE the guard**: Everything except the reference cache must run on every `getView()` call, because ListView may provide a different view instance for the same position:
   - `setSelection(cursorPos)` — `setText()` resets cursor to 0; must restore after every setText
   - `setOnClickListener` — new button widgets need their handlers
   - TextWatcher (with tag-based cleanup to prevent accumulation)

4. **Direct state sync**: Text manipulation methods must update `editingInProgressText` directly (not rely solely on TextWatcher), because the TextWatcher may be on a different view instance.

```
getView() call flow for editing row:
┌──────────────────────────────────────────────┐
│ ALWAYS (every getView call):                 │
│  • editField.setText(displayText)            │
│  • editField.setSelection(cursorPos)         │
│  • saveButton.setOnClickListener(...)        │
│  • cancelButton.setOnClickListener(...)      │
│  • deleteButton.setOnClickListener(...)      │
│  • TextWatcher (tag-based: remove old, add)  │
├──────────────────────────────────────────────┤
│ GUARD (if editingEditText == null):          │
│  • editingEditText = editField               │
│  (prevents scrap view reference theft)       │
└──────────────────────────────────────────────┘
```

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
