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

Backspace follows the **same priority chain** in `key_up()` for `KEYCODE_DEL` (the
`KeyValue.Kind.Keyevent` branch, `KeyEventHandler.kt:89` — note it is NOT `key_down()`;
`key_down()` only handles autocap stop and sliders).

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

### Step 2: Add routing in sendText() and key_up() (KeyEventHandler.kt)

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

In `key_up()` (the `Kind.Keyevent` branch), add KEYCODE_DEL handling in the same priority chain:

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

2. **Dynamic recovery property** (`activeEditingEditText`): A computed property that validates the cached reference via `visibility == VISIBLE` AND `windowToken != null`, then falls back to scanning live child views. All text manipulation methods use this instead of the raw field. **CRITICAL**: Must check visibility, not just windowToken. When `loadDataAsync()` completes during edit mode, `notifyDataSetChanged()` can recycle the cached editField to a different position (GONE) while it retains a valid windowToken. Without the visibility check, setText goes to the invisible view.

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

## Explicit templates and suffixes (October 6 development)

TEMPLATE is an explicit ActionType/KeyValue kind; existing TEXT remains literal.
All key, short-swipe and popover routes use KeyEventHandler.execute_template/execute_suffix.
Both refuse the five inline panel/search/edit modes rather than targeting the app behind
them; templates additionally refuse passwords and unreadable selection state. Failure
returns false, shows existing IME feedback, and never falls back to typing the payload.
Accepted template edits clear prior swipe/context ownership without learning the payload.
Continuous segment commits carry EditorCommitGuard through InputCoordinator →
SuggestionHandler, verifying actual insertion before learning/undo ownership. Forced
separators use SuggestionHandler's bounded callback operation and preserve the same
learning handle; they do not re-record the word.

## Editing commands: one implementation (todo 12, 2026-10-12)

Every `KeyValue.Kind.Editing` command (copy, cut, paste, pasteAsPlainText, selectAll,
shareText, undo, redo, delete_word, forward_delete_word, delete_last_word, clear,
selection_cancel, doc_home/doc_end, autofill, textAssist, replaceText, copy_private,
clear_clipboard, append_possessive/append_apostrophe) runs ONLY in
`KeyEventHandler.handleEditingKey`:

```
Layout key ............................ Pointers → KeyEventHandler.key_up → handleEditingKey
Custom short swipe / popover / palette  Pointers.executeCustomShortSwipe
  COMMAND mapping (catalogue name, or     → Keyboard2View.onCustomShortSwipe
  legacy COPY/PASTE/PRIVATE_COPY/…)        → CommandRouting.editingValue(mapping)
                                           → _config.handler.key_up(editingKey) → handleEditingKey
```

So a mapping gets what the key gets: clipboard inline-edit routing (paste/cut/selectAll/clear
go to the edit field; other edits are swallowed — edit mode is modal), terminal paste from
the system clip, `recv.handle_non_text_input()` (typed-word end), copy/cut only with a
selection, and the text-action app chooser (`TextActionLauncher`; when the chooser cannot start,
Replace text falls back to the `android.R.id.replaceText` context-menu id and Text assist shows
"Action unavailable" — `e940262c`, because `android.R.id.textAssist` is handled by no editor).
`CustomShortSwipeExecutor` returns false for any editing name and keeps no copy;
`Keyboard2View.executeEditingCommand` no longer exists. Suffix commands keep their own
branch in `onCustomShortSwipe` only so the haptic fires on success; templates likewise.
`EditingCommandParityTest` (mock) runs every Editing value through both entry points under
{selection, none} × {inline edit open, closed} × {app, terminal} and requires identical
editor/receiver effects — add any new Editing value there (it fails on an uncovered one).

## Editor-menu text actions (2026-10-13)

`textAssist`/`replaceText` (Editing.ASSIST/REPLACE) keep the `TextActionLauncher` app
chooser. `replaceTextMenu` (Editing.REPLACE_MENU, "Replacement Suggestions (Editor)") sends
the bare `android.R.id.replaceText` context-menu action — the pre-7d1a3271 layout-key
behaviour — with no chooser and no selection precondition; stock EditText shows its
SuggestionSpan popup for the word at the cursor. Since `d1acb09b` it first reads the text around the selection
start WITH styles: no SuggestionSpan (with suggestions, or misspelled/grammar) touching it →
"Action unavailable" and nothing sent — the popup could not open in any editor (Compose and
Chrome never return spans); the connection's own answer is IPC success and proves nothing. Text label "rplc" (single-font rule).

**Removed command `textAssistMenu` (2026-10-17).** `android.R.id.textAssist` is handled by no
editor (EditText's `onTextContextMenuItem` has no case; Chrome, Compose neither), so it was a
silent no-op. It is out of `CommandRegistry` (palette, search) but `Editing.ASSIST_MENU` and
the KeyValue name `textAssistMenu` stay: saved mappings, backups and custom layouts still
resolve, and `handleEditingKey` refuses it FIRST (before inline routing and
`handle_non_text_input`) with `R.string.text_assist_menu_removed`; KeyLabels reads the same
string. Pinned by `EditingCommandParityTest.removedTextAssistMenuRefusesVisiblyAndSendsNothing`.
Convention for future removals: keep the enum value and name, refuse visibly, drop the
catalogue entry and its strings.

## Blind fields: terminal-style input (2026-10-13)

`TerminalUtils.editorKind(editorInfo, customPackages)` is the ONE detector: user list and
built-in terminals → TERMINAL; RustDesk `com.carriez.flutter_hbb` and any
`inputType == TYPE_NULL` editor → REMOTE; else TEXT. `isTerminalApp` (= not TEXT) gates every
no-rewrite branch. In `KeyEventHandler`, delete text before the cursor ONLY through
`deleteBeforeCursor(conn, n)` (KEYCODE_DEL × n in terminal-style editors) — RustDesk forwards
a length-only diff, so `deleteSurroundingText(n)` reaches the remote as ONE Backspace. Paste
and pasteAsPlainText commit the system clip there; `clear` and suffix commands are refused;
cursor sync and the #78 editor scan never read the field (RustDesk's hidden field reads
1024 '1' placeholders). REMOTE delete-last-word is Ctrl+Backspace, TERMINAL is Ctrl+W.
A new editor-writing feature must ask `isTerminalApp` before any in-place rewrite;
`BlindFieldPolicyTest` models RustDesk and asserts what the remote receives.

## Terminal and blind fields: suggestion taps append, never delete (2026-10-15/17)

`TerminalSuggestionPolicy` (pure) filters the bar for `TERMINAL`/`REMOTE` editors: only words
that EXTEND the tracked word (case-insensitive prefix) are offered, shown with the user's own
letters (`HEl` → `HEllo`); a tap commits the remainder only (`hel` + `hello` → `lo `). A
correction, a contraction (`dont` → `don't`) or a chip left over from a changed word is refused
and nothing is sent; with no extension the bar is cleared. The exact-add `+word` chip and the
space-time "Add to dictionary?" prompt are NOT offered there (`fd3eec40`): Termux applies its
toolbar CTRL inside `commitText`, so the tracked word can be stale (`foocbar`) and nothing the
IME can read bounds what is on the line. Why any deletion is unsafe: `docs/specs/
termux_integration.md` "Why taps may not delete" (Termux source table + the D2/D4 device
evidence). Pinned by `TerminalSuggestionPolicyTest` and `TerminalTypedWordTrackingTest`.
Ordinary editors are unchanged.

## Shortcuts and paste inside inline fields (2026-10-13)

A Ctrl/Alt/Meta letter is a key EVENT (KeyModifier.applyCtrl), so `sendText`'s routing chain
never sees it. `key_up`'s Keyevent branch sends it to `handleInlineShortcut` while any inline
field is active (`!canUseEditorActions()`): in the clipboard entry editor Ctrl+V/X/C/A paste,
cut, copy and select all there, Ctrl+Z undoes, Ctrl+Shift+Z and Ctrl+Y redo; in
search/tag/emoji/GIF fields Ctrl+V pastes the clip into the field; any other shortcut is
swallowed. `handleEditingKey` routes the editing keys the same way (UNDO/REDO/COPY included in
the editor) and, for the other fields, PASTE/PASTE_PLAIN via `sendText`, dropping every other
command, never touching the app behind the panel. Pinned by `InlineFieldShortcutTest` (Saga:
Ctrl+V leaked to the app; Pixel 2026-10-14: Ctrl+Z did nothing in the editor).

**Inline editor undo is OUR history, not the platform's.** Every edit routed into the editor
is `setText()` + `setSelection()` (IME-owned views), and `TextView.setText` calls
`Editor.forgetUndoRedo()` for an editable buffer — so `onTextContextMenuItem(android.R.id.undo)`
would always find an empty stack. `ClipboardHistoryView` records each edit in
`InlineEditHistory` (pure, bounded; cleared on session start/exit). Any NEW mutation path in
the editor must record a before/after snapshot or undo will skip it. Undo/redo/copy reach the
view through IReceiver → `KeyEventReceiverBridge` → `KeyboardReceiver` → `ClipboardManager`.

**App-facing undo/redo are Ctrl+Z / Ctrl+Shift+Z key events (2026-10-15)**, not
`performContextMenuAction(undo/redo)`: only a stock EditText honours those ids; Chrome/WebView
and Compose return false, while all three map the shortcuts
(`docs/research/2026-10-15-ime-editor-actions.md`). Never send both — an EditText would undo
twice. In a terminal-style field (`isTerminalStyleEditor()`) they show "Action unavailable":
Ctrl+Z there is SIGTSTP. Pinned by `EditingCommandParityTest`.

## Editor commands: case, share, process text, word/line (2026-10-17)

From docs/research/2026-10-15-ime-editor-actions.md §4.2, all in `handleEditingKey`:

- **case_cycle** (Editing.CASE_CYCLE): selection, else the word touching the caret, else the
  word before spaces/tabs (`hello |`). `CaseCycle` (pure, keyboard-language locale) gives
  lower → Title → UPPER → lower; mixed → lower; uncased → "could not change". Verified write:
  `EditorTextWindowReader` window → finishComposingText → identical re-read → select target →
  `EditorReadback` must read exactly the original → commit → `matchesReplacement` → re-select
  the result (UTF-16 length may change: ß → SS). Refused with feedback in terminal-style
  editors, without readback, and behind ANY inline field.
- **word_left/right, select_word_left/right, select_word, select_line**: `EditorTextNavigation`
  (pure; word = letters/digits/marks + inner apostrophe + a Persian half-space between
  Arabic-script letters, by code point) over a readback window
  (256 chars; 4096 for lines). A word/line touching a cut window edge is refused, never guessed.
  No readback window, `moveCursorForceFallback`, or a refused `setSelection` → Ctrl(+Shift)+DPAD
  key events for moves/extends, and select_word/select_line as the editor's OWN keys
  (`EditorTextNavigation.keyPlan`, decided from text read around the cursor, no offsets):
  word = [Left to collapse a selection] [Ctrl+Left unless already at a word start] then one
  Ctrl+Shift+Right per covered word (Blink on Android ends a word move right at the word END);
  line = Home, Shift+End — the VISUAL line in Blink/EditText (readback path: logical line); a
  selection holding `\n` is refused in key mode. All reads/decisions precede the first key, so a
  refusal sends nothing. terminal-style → moves as Ctrl+arrow keys only, every selection command
  refused, no events.
  **`moveCursorForceFallback` is TRUE in every Chrome/WebView text field**: its password test
  `(inputType & TYPE_MASK_VARIATION & TYPE_TEXT_VARIATION_PASSWORD) != 0` matches any variation
  with bit 0x80, and Chromium's ImeUtils.computeEditorInfo gives text inputs / textareas
  WEB_EDIT_TEXT (0xa0; also WEB_EMAIL 0xd0, FILTER 0xb0, PHONETIC 0xc0). So Chrome never gets
  `setSelection` from the cursor slider or these commands; case_cycle does not consult the flag
  and works there by readback (Pixel 2026-10-17). Before that fix select_word/select_line had
  no key route and answered "Action unavailable" in Chrome. Inline
  clipboard editor: `navigateClipboardEdit` (IReceiver → bridge → KeyboardReceiver →
  ClipboardManager → ClipboardHistoryView), same rules over the whole text. Legacy
  WORD_LEFT/WORD_RIGHT mappings now route here (CommandRouting).
- **shareText**: IME-built ACTION_SEND chooser (`TextActionLauncher.share`) from
  `getSelectedText`; selection required; never the `android.R.id.shareText` id (EditText-only),
  so nothing shares twice. Refused in terminal-style editors; swallowed in inline fields
  (private entries must not leave through share).
- **textAssist / replaceText**: one implementation, two names/chooser titles. The selection's
  `EditorReadback` + field identity is registered in `ProcessTextReturn`; `ProcessTextActivity`
  (transparent, not exported, own task, never noHistory) starts the chooser FOR A RESULT (the
  system chooser forwards it: FLAG_ACTIVITY_FORWARD_RESULT). The service delivers on the next
  `onStartInput` (not our own package) or 3 s after the result:
  `KeyEventHandler.deliverProcessTextResult` inserts only into the same package/field/inputType
  with the identical receipt, after finishing composition, and verifies the replacement;
  otherwise the result goes to the clipboard with feedback. Only the latest request counts.
  The trampoline is started with NEW_TASK | CLEAR_TASK (`TRAMPOLINE_LAUNCH_FLAGS`): with
  NEW_TASK alone a chooser left open by Home kept the task and, on API 34's IntentResolver, the
  next press only brought that stale chooser back — the new request was never answered.
  End-to-end proof: `ProcessTextResultInstrumentedTest` (androidTest; editor and processor are
  Java activities in the TEST APK's own process — kotlin-stdlib and app classes are absent
  there — so CleverKeys sees a foreign app and the onStartInput delivery path runs; the
  chooser is tapped with UiAutomator; SystemUI's clipboard overlay is switched off via
  `device_config ... clipboard_overlay_enabled` because it covers the chooser's first target).
- **SuggestionSpan on commit**: swipe auto-insert passes the bar words, a tap replacing a swipe
  word passes that word + the bar, typed autocorrect passes the typed word;
  `SuggestionSpanPolicy` (≤5 alternatives, ≤48 chars, text fields allowing suggestions only)
  and `SuggestionSpanCommit` (flags 0, LatinIME pattern). Never in password, terminal-style or
  blind fields. The commit guard compares characters, so spans do not affect readback.
- `textAssistMenu` removed 2026-10-17 (see "Editor-menu text actions").

## Swipe typing into inline fields (2026-10-20)

Maintainer report: swipes with the clipboard entry editor open landed in the app behind the
panel. Tap typing goes through `sendText`'s chain, but a swiped word is committed by
`SuggestionHandler` — the ONE swipe pipeline — so the inline check lives there:

```
swipe end → InputCoordinator → SuggestionHandler.handleSwipePredictionResults
    inlineTargetProvider() (= KeyEventHandler.activeInlineTarget, wired in KeyboardComponentGraph)
      non-null → handleInlineSwipeResults: bar shows the slate, top word →
                 InlineSwipeSession.insert(target) → IReceiver.replaceInInlineField
                 → KeyEventReceiverBridge → KeyboardReceiver → ClipboardManager /
                   ClipboardHistoryView.replaceEditRange / EmojiSearchManager / GIF input
      null     → the app path, unchanged
bar tap  → SuggestionBridge → onSuggestionSelected(isManualSelection) → same provider check →
           commitInlineSuggestionTap: replaces the owned word, else inserts a new one
backspace → key_up: inline field active && LearningHooks.undoInlineSwipe() → removes the
           whole insertion (spaces included, a replaced selection restored); else the field's
           ordinary backspace
```

- `activeInlineField()` uses the SAME priority as `sendText` (tag > edit > search > emoji > GIF).
  New IReceiver methods `inlineFieldState(field)` / `replaceInInlineField(field, start, end, text)`
  are delegated by the bridge (`KeyEventReceiverBridgeDelegationTest` ratchet + a behavioural
  check in `InlineSwipeRoutingTest`).
- **Spacing** (`InlineWordPlanner`, pure): read from the FIELD's text, never the app's. Leading
  space unless at the start or after whitespace; trailing space only when the next character
  is a letter/digit (a swipe into the middle of text); no trailing auto-space at the end (the
  inline fields have no smart-punctuation swallow, and a query keeps no stray space). A
  selection is replaced.
- **Tap after a swipe** (2026-10-28): with no trailing auto-space, the space is owed to the next
  tap. `sendText` asks `LearningHooks.inlineSpaceBeforeTap` before every inline route →
  `InlineSwipeSession.spaceBeforeTap`: " " when ownership is valid, the insertion ended with the
  word (no trail), the typed text starts with a letter/digit and `auto_space_after_suggestion`
  is on; else "" (punctuation attaches: `word.`).
- **Word deletion** (2026-10-28): `delete_last_word` (Backspace NW flick), `delete_word`
  (round-trip gesture, palette) and `forward_delete_word` in `handleEditingKey`, plus
  Ctrl+Backspace / Ctrl+Delete in `key_up` (checked BEFORE the inline backspace branches; they
  arrive as KEYCODE_DEL/FORWARD_DEL with Ctrl meta), go to `KeyEventHandler.deleteInlineWord` →
  `InlineWordDelete` (pure; `EditorTextNavigation` word boundaries, a selection is deleted as
  is) → `InlineTextTarget.replace(range, "")` — one `InlineEditHistory` step in the editor;
  append-only fields delete backward only. They used to be swallowed (`else -> {}`).
- **Ownership** (`InlineSwipeSession`, pure): valid only while field, session (editor:
  `editingSession`, bumped per `edit_entry`; others: the view's identity), whole text and
  collapsed caret are exactly what the swipe/tap left. Any typed char, backspace, caret move,
  paste or undo voids it — the next tap then inserts instead of rewriting.
- **Undo history**: `replaceEditRange` records each swipe insert, bar replacement and
  backspace undo as its own `InlineEditHistory` step (`EditKind.OTHER`).
- **Learning: none.** Tap typing in inline fields returns from `sendText` before
  `handle_text_typed`, so it is never learned; swipes match. The inline branch skips app
  autocap (only the shift latch at swipe start cases the word), rescoring, possessives, final
  autocorrect, next-word appends, ML capture (`SuggestionBridge` also skips capture while an
  inline field is active), swipe-correction records and every learn funnel, and clears
  `wasLastInputSwipe`.
- **The app's InputConnection is never touched** while an inline field is active (no reads,
  composing, commits or deletes) — pinned by `InlineSwipeRoutingTest.assertAppUntouched`.
- Continuous (multi-word) swipe already refuses to start while an inline field is active
  (`Keyboard2View` checks `canUseEditorActions()`), so it never reaches these fields.
- Limitations: special bar chips (add to dictionary, `+word`, swipe-preference offers) are
  refused while an inline field owns typing — they act on the APP's typed word. Append-only
  fields (tag, clipboard/emoji/GIF search) always append at their end, like their tap typing.
  The field is resolved when the decode RESULT arrives: a swipe whose result lands after the
  editor closed goes to the app.

## Word characters: the Persian half-space (2026-10-23)

U+200C is a word character ONLY between two Arabic-script letters (`WordChars`, pure; marks
skipped), plus the cursor edge (half-space just typed after an Arabic letter, nothing or a
non-letter/Arabic letter after the cursor). Every word scanner ORs that clause into its own
base rule — never add a scanner with a private ZWNJ rule; call `WordChars`. Typing U+200C
after an Arabic letter APPENDS to the composing word (no W7 stem reset); after anything else
it ends the word as before. The composing-word tracker used to keep U+200C in every script —
it now matches the other scanners. Call-site list and rationale:
`docs/specs/dictionary-and-language-system.md` "Persian half-space and Arabic-script lookup
fold"; tests `WordCharsTest`, `PersianHalfSpaceBoundaryTest` (pure), `PersianHalfSpaceTypingTest`
(mock).

## Voice input: dictation routing (2026-10-29)

`voice_input` (KeyValue.Event.VOICE_INPUT → KeyboardReceiver → `CleverKeysService.onVoiceInputKey`
→ `stt.VoiceInputController`) classifies the field ONCE at start through `VoiceInputBridge.fieldKind`,
with the keyboard's own rules: active inline field (`KeyEventHandler.activeInlineTarget`) → INLINE
(final words via `InlineTextTarget.replace`, the app untouched); `SuggestionBar.isPasswordField` →
PASSWORD (refused); `TerminalUtils.isTerminalApp` → BLIND (commitText of confirmed words only — no
composing span, no read); else TEXT (composing tail + `commitText` of stable words). The pure
`stt.VoiceTextPlanner` decides the ops (spacing/case from the text before the caret, read once;
cancel = ClearComposing only). `onStartInputView`/`onFinishInputView`/`onWindowHidden` cancel.
Dictation does not learn (it never enters `sendText`). Spec: `docs/specs/voice-input.md`.
