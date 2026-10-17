# IME editor actions: what `performContextMenuAction` really does, and what to add

Date: 2026-10-15. Research only, no code changes.

Sources were fetched on 2026-10-15. Line numbers refer to those snapshots:

- **AOSP**: LineageOS mirror, branch `lineage-22.2` (tip `3f0e0b8b`). Prefix
  `https://raw.githubusercontent.com/LineageOS/android_frameworks_base/lineage-22.2/core/java/`.
  Files read: `android/widget/TextView.java`, `Editor.java`, `SpellChecker.java`,
  `android/view/inputmethod/InputConnection.java`, `BaseInputConnection.java`,
  `com/android/internal/inputmethod/EditableInputConnection.java`,
  `android/view/ViewRootImpl.java`, and `android/text/method/{BaseKeyListener,BaseMovementMethod,ArrowKeyMovementMethod}.java`.
- **Chromium**: `chromium/chromium` branch `main` (tip `ca5201eb`), files
  `content/public/android/java/src/org/chromium/content/browser/input/{ImeAdapterImpl,ThreadedInputConnection}.java`
  and `third_party/blink/renderer/core/editing/editing_behavior.cc`.
- **Compose**: `androidx/androidx` branch `androidx-main` (tip `f90d246d`), files
  `compose/foundation/foundation/src/androidMain/.../text/input/internal/StatelessInputConnection.android.kt`
  and `compose/foundation/foundation/src/commonMain/.../text/KeyMapping.kt`.
- **API levels**: developer.android.com reference pages for `InputConnection` and `android.R.id`.
- **PAL**: one consultation with `gemini-3.8-flash`. Its claims about what Gboard and SwiftKey
  do internally can't be checked against source, so they're marked UNVERIFIED wherever used.

Tags: **VERIFIED** means checked in the source or docs cited. **UNVERIFIED** means inference
or second-hand.

---

## 1. `performContextMenuAction(id)` per id

### 1.1 Stock `EditText`

- **Dispatch.** `EditableInputConnection.performContextMenuAction` (L180–186) wraps
  `mTextView.onTextContextMenuItem(id)` in `beginBatchEdit()`/`endBatchEdit()` and always
  returns `true`, whatever the TextView did. **VERIFIED.** So an IME can never learn whether an
  id was handled. `BaseInputConnection`'s default implementation does nothing and returns
  `false` (L714–716). **VERIFIED.**
- **The full switch.** The switch in `TextView.onTextContextMenuItem` (L15182–15268) handles
  these ids and nothing else. **VERIFIED.**

| id (API) | Behaviour in stock EditText |
|---|---|
| `selectAll` (1) | `selectAllText()`, refreshes the action mode if one is open (L15194). |
| `undo` (23) | `mEditor.undo()`, which undoes one UndoManager step. Returns true even when nothing was undone (L15201–15205). |
| `redo` (23) | `mEditor.redo()` (L15207–15211). |
| `paste` (1) / `pasteAsPlainText` (23) | `paste(withFormatting = true/false)` (L15213–15219). |
| `cut` (1) | Copies the transformed `[min,max]` range, then deletes it. min/max is the selection only while the view is focused. Otherwise it is the whole text range (L15183–15190, L15221–15230). |
| `copy` (1) | Copies the selection and stops the action mode (L15232–15246). |
| `replaceText` (23) | `mEditor.replace()` (L15248–15252). See §1.2. |
| `shareText` (23) | `ACTION_SEND` chooser of the selection, then collapses the selection to its end. No-op with an empty selection (L15254, `shareSelectedText` L15732–15743). |
| `autofill` (26) | `requestAutofill()` + `stopTextActionMode()` (L15257–15260). |
| **anything else** | `return false`, so nothing happens. |

**Not handled, and therefore no-ops in EditText** (all **VERIFIED** by their absence from the
switch):

- `textAssist` (API 26)
- `copyUrl`, `switchInputMethod`, `startSelectingText`, `stopSelectingText`, `addToDictionary`
  (all API 3)

`InputConnection`'s own Javadoc still lists `startSelectingText`, `stopSelectingText`,
`copyUrl` and `switchInputMethod` as valid ids (L863–870). That list is stale.

**`textAssist` is not handled at all.** `TextView.ID_ASSIST` is declared (L15170), but it is
used only as a *menu group id*:

- Editor adds TextClassifier "smart action" items (Call, Open map, and so on) under that group
  (Editor L4566–4595).
- Those items are dispatched inside the floating toolbar's `onActionItemClicked` through
  `mHelper.onAssistMenuItemClicked(item)` (Editor L3159, L4851). They never pass through
  `onTextContextMenuItem`.

**VERIFIED.** This matches the device result: `textAssistMenu` did nothing in an EditText.

### 1.2 Why `replaceTextMenu` only cleared the selection

`Editor.replace()` (L689–698) does two things:

1. Calls `SuggestionsPopupWindow.show()`.
2. **Unconditionally** collapses the selection to its midpoint:
   `Selection.setSelection(text, (selStart + selEnd) / 2)`.

`show()` (L4336–4350) only shows the popup when `updateSuggestions()` returns true. That needs
`SuggestionSpan`s under the cursor: either suggestions (`mNumberOfSuggestions > 0`) or a
misspelled span (L4422–4428). **VERIFIED.**

So in practice:

- **Correctly spelled word, no spans:** no popup, and the selection still collapses. This is
  exactly what the device showed.
- **A popup appears only when one of these is true:**
  - The system spell checker has flagged the word. `SuggestionSpan.FLAG_MISSPELLED` produces
    "Add to dictionary"/"Delete" plus any spelling suggestions.
  - The IME committed the word with a `SuggestionSpan` that carries alternatives (the
    LatinIME/Gboard pattern; that those keyboards do this is UNVERIFIED).
- **When the spell checker runs at all:** the field must be `isSuggestionsEnabled()`, meaning
  `TYPE_CLASS_TEXT`, no `TYPE_TEXT_FLAG_NO_SUGGESTIONS`, and a variation of normal, email
  subject, long or short message, or web-edit (TextView L15342–15355). It also must not be
  suppressed by `isInputMethodSuppressingSpellChecker()` (Editor L1066–1072). **VERIFIED.**
- **The toolbar's own "Replace" item** appears only under the same conditions
  (`updateReplaceItem`, Editor L4827–4837, using `shouldOfferToShowSuggestions` L2660+).
  **VERIFIED.**

CleverKeys attaches **no** `SuggestionSpan`s to text it commits (`rg SuggestionSpan
src/main/kotlin` finds nothing). So our own swipe or autocorrect commits never make
`replaceText` useful.

### 1.3 Chrome / WebView

- **Context-menu ids.** `ImeAdapterImpl.performContextMenuAction` (L1283–1301) handles **only**
  `selectAll`, `cut`, `copy` and `paste`. Everything else returns `false`: `undo`, `redo`,
  `pasteAsPlainText`, `shareText`, `replaceText`, `textAssist`, `autofill`.
  `ThreadedInputConnection.performContextMenuAction` (L455–457) only posts the call to the UI
  thread. **VERIFIED.**
- **Key shortcuts do work.** Blink maps them as commands (`editing_behavior.cc` L174–181,
  non-Mac):

  | Keys | Command |
  |---|---|
  | Ctrl+C / Ctrl+X | Copy / Cut |
  | Ctrl+V | Paste |
  | Ctrl+Shift+V | PasteAndMatchStyle |
  | Ctrl+A | SelectAll |
  | Ctrl+Z | Undo |
  | Ctrl+Shift+Z or Ctrl+Y | Redo |

  Blink also maps:
  - Ctrl+Backspace/Delete to DeleteWordBackward/Forward (L145–146)
  - Ctrl+Home/End to document start/end (L132–140)
  - Ctrl+Left to MoveWordLeft (L97)

  **VERIFIED.**

**Consequence: our `UNDO`/`REDO` commands are silent no-ops in every Chrome/WebView field.**

### 1.4 Compose text fields (BasicTextField / TextFieldState path)

- **Context-menu ids.** `StatelessInputConnection.performContextMenuAction` (L413–430) does
  three things and always returns `false`:
  - `selectAll` becomes `session.setSelection(0, len)`.
  - `cut`/`copy`/`paste` become synthesized `KEYCODE_CUT`/`COPY`/`PASTE` key events.
  - Everything else, including undo and redo, is "not supported".

  **VERIFIED.**
- **Key shortcuts.** `KeyMapping.kt` maps:
  - Ctrl+Z to UNDO, and both Ctrl+Y and Ctrl+Shift+Z to REDO (L36–51)
  - Ctrl+Backspace to DELETE_PREV_WORD (L118–124)
  - KEYCODE_COPY/PASTE/CUT (L99–101)

  **VERIFIED.**

---

## 2. Undo/redo

### 2.1 Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y as key events in a stock EditText: **works**

- **How the event gets there.** `BaseInputConnection.sendKeyEvent` calls
  `InputMethodManager.dispatchKeyEventFromInputMethod` (L820–823), which reaches
  `ViewRootImpl.dispatchKeyFromIme` (L10807).
- **Shortcut interpretation.** `ViewPostImeInputStage.processKeyEvent` calls
  `dispatchKeyShortcutEvent` for any ACTION_DOWN that has a modifier and repeat count 0
  (ViewRootImpl L8061–8072). **VERIFIED.**
- **What TextView does with it.** `TextView.onKeyShortcut` (L13594–13644):
  - Ctrl+Z: `canUndo()`, then `ID_UNDO`
  - Ctrl+Y: `canRedo()`, then `ID_REDO`
  - Ctrl+Shift+Z: `ID_REDO`
  - Ctrl+Shift+V: `ID_PASTE_AS_PLAIN_TEXT`
  - Ctrl+A/X/C/V likewise

  `hasModifiers` is an exact match, so Ctrl+Shift+Z falls through to the second branch.
  **VERIFIED.**
- **Plain clipboard keys.** `KEYCODE_CUT`/`COPY`/`PASTE` with no modifiers are handled in
  `TextView.doKeyDown` (L9796–9818). **VERIFIED.**

The event must carry `META_CTRL_ON`. CleverKeys' `send_key_down_up(code, META_CTRL_ON|…)` is
already used this way for DELETE_WORD and CURSOR_DOC_START.

### 2.2 `performContextMenuAction(undo/redo)`

- **EditText:** works, through `Editor.undo()`/`redo()`, which act on one step of the
  `UndoManager` (Editor L673–687). **VERIFIED.**
- **Chrome and Compose:** a no-op (§1.3, §1.4). **VERIFIED.**

### 2.3 Are IME and programmatic edits recorded?

**Yes. Every `Editable` mutation passes through `Editor.UndoInputFilter`.**

**How the filter is installed and what it records:**

- `TextView.setFilters(Editable, …)` always appends `mEditor.mUndoInputFilter` (L8555–8567).
- `filter()` records each valid, non-no-op change unless undo is disabled (`android:allowUndo`,
  TextView L1504–1506) or an undo is in progress (Editor L7560–7710). **VERIFIED.**

**IME commits:**

- `BaseInputConnection.replaceText` (used by `commitText`/`setComposingText`) calls
  `beginBatchEdit()` (L929–935).
- `EditableInputConnection.beginBatchEdit` forwards to `TextView.beginBatchEdit`, then
  `Editor.beginBatchEdit`, then `mUndoInputFilter.beginBatchEdit()`, which sets
  `mIsUserEdit = true` (Editor L1866, L7547–7551).
- So IME commits are recorded as *user* edits and are merged into word-sized steps.
- Composition edits are tracked specially (`isComposition`, `mHasComposition`).

**VERIFIED.**

**Programmatic `Editable.replace` / `insert` / `delete`:**

- These are recorded too. Outside a batch edit they count as **non-user** edits: each one
  commits the previous state and starts a new, unmerged operation (Editor L7650–7655).
  **VERIFIED.**
- One catch: `Editor.beginBatchEdit` only reaches `mUndoInputFilter.beginBatchEdit()` when
  `mInputMethodState != null` (Editor L1848–1868). That state is created in
  `onCreateInputConnection` (TextView L10059–10062).
- An EditText *inside the IME's own window* is probably never an input target. If so,
  `et.beginBatchEdit()` would not mark its edits as user edits, and each edit would stay a
  separate undo step. (UNVERIFIED that our inline EditText never gets an InputConnection.
  The consequence follows from the code.)

**`setText()` erases history:**

- `TextView.setText` calls `mEditor.forgetUndoRedo()` whenever the view is editable, i.e. it
  has a KeyListener or `BufferType.EDITABLE` (L7373–7377). `forgetUndoRedo` drops all undos and
  redos (Editor L657–661). **VERIFIED.**

### 2.4 Our inline clipboard EditText: why Ctrl+Z cannot work today

`ClipboardHistoryView` rebuilds the string and calls **`et.setText(newText)`** on every
keystroke:

- `insertEditText` (L868)
- `backspaceEditText` (≈L906)
- `dispatchKeyToEditText` (≈L962)

Per §2.3, each call wipes the UndoManager, so the history is always empty.

`KeyEventHandler.handleEditingKey` also makes UNDO and REDO an explicit no-op in clipboard
edit mode (L807–818).

**Fix options** (for implementation later):

- **(a) Use the framework's undo.** Mutate in place with `et.text.replace(lo, hi, text)` /
  `et.text.delete(lo, hi)` instead of `setText`, then route UNDO/REDO to
  `et.onTextContextMenuItem(android.R.id.undo / redo)`. That method is public, and
  `onTextContextMenuItem` also works when unfocused.
  - Granularity would be one step per inserted string or deletion, unmerged, unless
    `mInputMethodState` exists (§2.3).
  - A TextWatcher on the field force-merges edits made inside it (`isInTextWatcher`,
    Editor L7615).
- **(b) Keep our own small undo stack.** Store (text, selection) snapshots in
  `ClipboardHistoryView`, with word-boundary coalescing. This is deterministic and testable on
  the pure JVM, and it doesn't depend on hidden framework state.
- **Recommendation:** (a) for the least code, or (b) if word-sized steps matter. Either way,
  stop calling `setText` for edits; it also restarts input-method state
  (`scheduleRestartInputForSetText`, L7378).

---

## 3. Other editor-facing capabilities

### 3.1 What CleverKeys already has

From `CommandRegistry.kt`, `KeyValue.Editing`, `KeyEventHandler.handleEditingKey` and
`TextActionLauncher.kt`:

- **Clipboard:** copy, cut, paste, pasteAsPlainText, selectAll, shareText, autofill,
  copy_private, clear_clipboard, paste_pinned_1–5, and clear.
- **Undo/redo:** via `performContextMenuAction` only.
- **Deletion:** delete_word / forward_delete_word as Ctrl+Del/Backspace key events, and
  delete_last_word.
- **Movement:** doc_home/doc_end as Ctrl+Home/End; cursor and selection arrows;
  selection_mode; selection_cancel; page_up/down.
- **Text actions:**
  - `textAssist`/`replaceText`: an `ACTION_PROCESS_TEXT` chooser
  - `textAssistMenu`/`replaceTextMenu`: raw context-menu ids
  - `showTextMenu`: selects the word at the cursor
- **Other:** timestamps, append_possessive/apostrophe, and media/system keys.
- **Not used anywhere in `src/main/kotlin`:** `performSpellCheck`, `performHandwritingGesture`,
  `requestTextBoundsInfo`, IC `replaceText`, `setImeConsumesInput`, `commitCorrection`,
  `performPrivateCommand`, `requestCursorUpdates`, `SuggestionSpan`, and
  `KEYCODE_COPY`/`PASTE`/`CUT`.

**Defect found in the existing `textAssist`/`replaceText` commands.**

- `TextActionLauncher` sends `EXTRA_PROCESS_TEXT_READONLY = false`, which tells the chosen app
  it may return replacement text. But it launches with `startActivity` from the IME service.
- The framework only consumes a result when the *editor's* activity started the handler with
  `startActivityForResult(…, PROCESS_TEXT_REQUEST_CODE)`. See Editor L8120 and
  `TextView.onActivityResult` L2379–2398, which inserts the result through
  `performReceiveContent`. **VERIFIED.**
- So any replacement text a handler returns to us is **silently discarded**. The two commands
  differ only in the chooser title.

### 3.2 Capability table

| Capability | API | What it does (source) | EditText | Chrome/WebView | Compose | Terminals | Recommendation |
|---|---|---|---|---|---|---|---|
| Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y **key events** | 1 (handled since undo arrived in API 23) | §2.1 | yes (onKeyShortcut) | yes (Blink) | yes (KeyMapping) | **sends ^Z (SIGTSTP) / ^Y**, so must be excluded | **ADD as the undo/redo path** for non-terminal editors, replacing or falling back from `performContextMenuAction`. Don't send both: EditText would undo twice. |
| `KEYCODE_COPY/PASTE/CUT` | 11 (KeyEvent) | TextView.doKeyDown L9796–9818 | yes | Compose yes; Chrome UNVERIFIED | yes | app-specific | SKIP: no gain over the existing ids |
| Ctrl+Backspace / Ctrl+Del (word delete) | 1 | BaseKeyListener L335–344; Blink L145–146; Compose L118–124 | yes | yes | yes | — | **ALREADY HAVE** (DELETE_WORD) |
| Ctrl+Left/Right (word move) and Ctrl+Shift (word select) | 1 | BaseMovementMethod L160–185 → ArrowKeyMovementMethod.leftWord L223–237; Blink L97–98 | move yes; shift-extend UNVERIFIED (`isSelecting` reads the buffer meta state, L34–37) | yes | yes | — | ADD `word_left/right` and `select_word_left/right` commands (cheap) |
| `performSpellCheck()` | 31 | EditableIC L203–206 → SpellChecker.onPerformSpellCheck L219–227 (full-text check) | yes, if a spell checker is active | forwarded to native (ImeAdapterImpl L2274–2277) | not overridden (UNVERIFIED) | — | LOW: useful only paired with `replaceTextMenu` on a misspelled word |
| Commit with `SuggestionSpan` alternates | 14 | enables Editor.replace popup (§1.2) | yes | UNVERIFIED | UNVERIFIED | — | MEDIUM: makes "Replace" show our swipe alternates |
| `commitCorrection(CorrectionInfo)` | 11 | TextView.onCommitCorrection → Editor flash-highlight (EditableIC L164–170); Chromium uses it for autocorrect underline (ImeAdapterImpl L2279+) | yes | yes | yes (L474) | — | MEDIUM-LOW: visual feedback after autocorrect, not a command |
| `performHandwritingGesture` (Select/SelectRange/Delete/DeleteRange/Insert/RemoveSpace/JoinOrSplit/InsertMode) | 34 | EditableIC L297–325 → TextView.performHandwriting* | yes | yes (ThreadedIC L846) | yes (L357+) | — | SKIP: gestures take screen-coordinate areas, built for stylus; a key IME has no geometry for them |
| `previewHandwritingGesture` | 34 | EditableIC L327–331 | yes | yes | — | — | SKIP |
| `requestTextBoundsInfo` | 34 | character bounds in a rect | yes | — | — | — | SKIP: no use without gestures |
| IC `replaceText(start,end,text,…)` | 34 | default = finishComposing + setSelection + commitText in one batch (IC L1449–1462); Chromium overrides (ThreadedIC L416) | default impl | native | UNVERIFIED | — | INTERNAL: could simplify atomic suffix/explicit edits on API 34+; not a user command |
| `setImeConsumesInput` | 31 | hides the editor cursor while the IME captures input (IC L1385–1399; EditableIC L289–295) | yes | UNVERIFIED | UNVERIFIED | — | INTERNAL: call while an inline clipboard/emoji/GIF search field owns typing |
| `performPrivateCommand` | 3 | app-defined; TextView default returns false (L10360) | app-specific | app-specific | delegate | — | SKIP |
| `requestCursorUpdates` | 21 (filter overload 33) | CursorAnchorInfo stream | yes | yes | yes | — | SKIP for commands; only relevant for floating or stylus UI |
| `ACTION_PROCESS_TEXT` with a **returned result** | 23 | needs `startActivityForResult` (§3.1) | via trampoline | via trampoline | via trampoline | no | **ADD, carefully** (see below) |
| Translate / define / look up | — | no system API; usually a PROCESS_TEXT handler | — | — | — | — | Covered by the PROCESS_TEXT chooser (no INTERNET permission) |
| Share | 23 id | `shareText` (EditText only) | yes | **no** | no | — | IMPROVE: build `ACTION_SEND` from `getSelectedText` ourselves so it works everywhere |
| Case cycle of the selection or word (lower → Title → UPPER) | — | `getSelectedText` + `commitText` + `setSelection` in a batch | yes | yes | yes | no | **ADD**: common, pure-JVM testable |
| Select word / line at cursor | — | `getTextBefore/AfterCursor` + `setSelection` | yes | yes | yes | no | ADD (`showTextMenu` already contains select-word logic) |

**PROCESS_TEXT trampoline.** The PAL `gemini-3.8-flash` design is UNVERIFIED but plausible:

1. A translucent activity in the IME package calls `startActivityForResult`.
2. It hands the result back to the service.
3. The service commits the text once the editor has re-bound
   (`onStartInput(restarting = true)`).

Risks:

- **Selection loss on unfocus.** The host editor may drop or move its selection when it loses
  window focus.
- **A stale InputConnection while away.** We must hold the result until a fresh connection
  arrives and verify the original text is still at the original range. This matches the
  CLAUDE.md "revalidate exact readback before explicit writes" rule.

Until that exists, `TextActionLauncher` should set `READONLY = true`, so we stop promising a
replacement we can't deliver.

---

## 4. Recommendations

### 4.1 `textAssistMenu` and `replaceTextMenu`

- **`textAssistMenu`: REMOVE from the catalogue.**
  - It is a no-op in EditText (no case in `onTextContextMenuItem`), Chrome and Compose. The
    IME also can't detect the failure, because EditableInputConnection returns true.
  - Keep `Editing.ASSIST_MENU` in the enum to preserve ordinals. Map it to a "not supported by
    this app" suggestion-bar message so existing user mappings don't fail silently.
- **`replaceTextMenu`: KEEP, but rename and re-describe.**
  - New name: "Spelling suggestions (editor)". Description: "Opens the app's own suggestion
    popup for a misspelled or auto-corrected word. Stock text fields only. Collapses the
    selection."
  - Optionally call `performSpellCheck()` first on API 31+, so the word under the cursor has
    spans.
  - It becomes more useful once we attach `SuggestionSpan`s on commit (item 6 below).
- **`textAssist` / `replaceText` (chooser): MERGE into one "Process text…" command.**
  - Set `READONLY = true` until the trampoline in item 4 exists.

### 4.2 New commands, in priority order

1. **Undo/redo via Ctrl+Z / Ctrl+Shift+Z key events** (excluding terminal-style editors). This
   makes the existing commands work in Chrome, WebView and Compose, where they are silent
   no-ops today.
2. **Inline clipboard EditText undo/redo.** Replace the `setText` rebuilds with in-place
   `Editable` edits, then use `onTextContextMenuItem(undo/redo)` or a local stack. This is the
   maintainer's stated need.
3. **Case cycle** (selection, or the word at the cursor: lower → Title → UPPER). A frequently
   requested editing command that works in every non-terminal editor.
4. **Process text with a returned result** (trampoline activity). Makes translate, rewrite and
   other replacement apps actually replace the text.
5. **Universal Share.** Build `ACTION_SEND` from `getSelectedText` ourselves; `shareText` works
   only in stock EditText.
6. **Commit `SuggestionSpan` alternates with swipe/autocorrect words.** Gives the editor's own
   Replace popup, and `replaceTextMenu`, our candidates. Needs device verification of how long
   the spans persist.
7. **Word navigation and word selection commands** (Ctrl+Left/Right, Ctrl+Shift+Left/Right).
   Cheap, and handled by EditText, Blink and Compose. Verify shift-extend in EditText on a
   device.
8. **Select word / select line at cursor** as standalone commands, factored out of
   `showTextMenu`.

Not worth adding:

- handwriting gestures, `previewHandwritingGesture` and `requestTextBoundsInfo` (stylus
  geometry)
- `performPrivateCommand` (app-specific)
- the `copyUrl`, `switchInputMethod`, `startSelectingText`, `stopSelectingText` and
  `addToDictionary` ids (unhandled since modern TextView)
- separate `KEYCODE_COPY`/`PASTE`/`CUT` commands

Internal (non-command) follow-ups worth a todo:

- `setImeConsumesInput(true)` while an inline search field owns typing (API 31+)
- IC `replaceText` for atomic explicit edits on API 34+

---

## 5. Implementation status (2026-10-17)

Implemented from §4.2 (maintainer decision): item 3 case cycle (`case_cycle`), item 4
process text with the result written back (`ProcessTextActivity` + `ProcessTextReturn`;
`textAssist`/`replaceText` kept as two names over one implementation, results editable),
item 5 universal share (`shareText` now the IME's ACTION_SEND chooser), item 6 SuggestionSpan
alternates on swipe/autocorrect commits (flags 0), items 7–8 `word_left`/`word_right`,
`select_word_left`/`select_word_right`, `select_word`, `select_line`. §4.1 DONE 2026-10-17
(maintainer decision): `textAssistMenu` left the catalogue; `Editing.ASSIST_MENU` keeps its
ordinal and the name still resolves, so a saved mapping shows a "no longer supported" bar
message. `replaceTextMenu` is now "Replacement Suggestions (Editor)" (not "Spelling
suggestions": it also offers the swipe alternatives of item 6). How each behaves where:
`.claude/skills/ime-key-routing.md` "Editor commands: case, share, process text, word/line".
Still unverified on a device: chooser result forwarding on OEM choosers, selection survival
across the trampoline, Ctrl+Shift+arrow extension in EditText, SuggestionSpan in Chrome and
Compose.
