---
title: Per-Key Actions — Technical Specification
description: Full short-swipe customization system covering all 8 directions per key with TEXT/COMMAND/KEY_EVENT/INTENT/TIMESTAMP/TEMPLATE action types and 200+ command registry
user_guide: /wiki/customization/per-key-actions/
status: implemented
version: development
---

# Per-Key Actions Technical Specification


## Overview

Short Swipe Customization allows users to fully customize the 8-direction swipe gestures for every key on the keyboard. Each key has 8 subkey positions (N, NE, E, SE, S, SW, W, NW) that can be customized with text input, commands, key events, or Android Intents through a dedicated settings UI.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/customization/SwipeDirection.kt` | `SwipeDirection` | Enum for 8 directions |
| `src/main/kotlin/tribixbite/cleverkeys/customization/ActionType.kt` | `ActionType` | Enum for action types |
| `src/main/kotlin/tribixbite/cleverkeys/customization/ShortSwipeMapping.kt` | `ShortSwipeMapping` | Data model for custom mapping |
| `src/main/kotlin/tribixbite/cleverkeys/customization/ShortSwipeCustomizationManager.kt` | `ShortSwipeCustomizationManager` | JSON persistence, CRUD operations |
| `src/main/kotlin/tribixbite/cleverkeys/customization/CustomShortSwipeExecutor.kt` | `CustomShortSwipeExecutor` | Executes commands via InputConnection |
| `src/main/kotlin/tribixbite/cleverkeys/customization/IntentDefinition.kt` | `IntentDefinition`, `IntentTargetType` | Intent data model, presets, Gson-safe parsing |
| `src/main/kotlin/tribixbite/cleverkeys/customization/IntentEditorDialog.kt` | `IntentEditorDialog` | UI for configuring intents (presets, manual fields, extras) |
| `src/main/kotlin/tribixbite/cleverkeys/customization/CommandRegistry.kt` | `CommandRegistry` | 200+ searchable commands |
| `src/main/kotlin/tribixbite/cleverkeys/customization/XmlAttributeMapper.kt` | `XmlAttributeMapper` | XML round-trip for all action types incl. INTENT |
| `src/main/kotlin/tribixbite/cleverkeys/KeyValueParser.kt` | `parseIntentKeydef()` | Parses `intent:'json'` from layout XML |
| `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt` | `handleShortGesture()` | Checks custom mappings first |
| `src/main/kotlin/tribixbite/cleverkeys/KeyEventHandler.kt` | Integration | Executes custom commands |

## Architecture

```
Settings UI
       |
       v
+----------------------------------+
| ShortSwipeCustomization          | -- User selects key, direction, action
| Activity                         |
+----------------------------------+
       |
       v
+----------------------------------+
| ShortSwipeCustomization          | -- CRUD for mappings
| Manager                          | -- JSON persistence
+----------------------------------+
       |
       v (on gesture)
+----------------------------------+
| Pointers.handleShortGesture()    | -- Check custom mapping first
+----------------------------------+
       |
       v (if custom found)
+----------------------------------+
| CustomShortSwipeExecutor         | -- Execute TEXT/COMMAND/KEY_EVENT/INTENT
| .execute()                       |
+----------------------------------+
       |
       v (if INTENT)
+----------------------------------+
| IntentDefinition.parseFromGson() | -- Null-safe JSON deserialization
| validateIntent()                 | -- URI scheme, package existence
| context.startActivity/Service/   | -- Dispatch by IntentTargetType
|   sendBroadcast                  |
+----------------------------------+
```

## Data Model

### SwipeDirection

```kotlin
enum class SwipeDirection {
    N,   // North (up)
    NE,  // Northeast
    E,   // East (right)
    SE,  // Southeast
    S,   // South (down)
    SW,  // Southwest
    W,   // West (left)
    NW   // Northwest
}
```

### ActionType

`ActionType` persists the stable enum names `TEXT`, `COMMAND`, `KEY_EVENT`,
`INTENT`, and `TIMESTAMP`. Its user-visible labels and descriptions are string
resources (`displayNameRes` and `descriptionRes`), resolved at the UI boundary.
Literal TEXT does not interpret a timestamp pattern or a dynamic template.

### ShortSwipeMapping

```kotlin
data class ShortSwipeMapping(
    val keyCode: String,           // Key identifier (e.g., "a", "e", "shift")
    val direction: SwipeDirection, // One of 8 directions
    val displayText: String,       // Max 4 chars for visual display
    val actionType: ActionType,    // TEXT, COMMAND, KEY_EVENT, or INTENT
    val actionValue: String,       // Text content, command name, keycode, or JSON IntentDefinition
    val useKeyFont: Boolean = false // Use special_font.ttf for icons
)
```

## Storage Format

File: `short_swipe_customizations.json`

```json
{
  "version": 2,
  "mappings": {
    "a": {
      "N": { "displayText": "@", "actionType": "TEXT", "actionValue": "@", "useKeyFont": false },
      "NE": { "displayText": "sel", "actionType": "COMMAND", "actionValue": "select_all", "useKeyFont": false }
    },
    "e": {
      "NW": { "displayText": "", "actionType": "COMMAND", "actionValue": "home", "useKeyFont": true }
    },
    "t": {
      "N": {
        "displayText": "term",
        "actionType": "INTENT",
        "actionValue": "{\"name\":\"Termux Command\",\"targetType\":\"SERVICE\",\"action\":\"com.termux.RUN_COMMAND\",\"packageName\":\"com.termux\",\"className\":\"com.termux.app.RunCommandService\",\"extras\":{\"com.termux.RUN_COMMAND_PATH\":\"/data/data/com.termux/files/usr/bin/echo\",\"com.termux.RUN_COMMAND_ARGUMENTS\":\"Hello\",\"com.termux.RUN_COMMAND_BACKGROUND\":\"true\"}}",
        "useKeyFont": false
      }
    },
    "d": { "N": { "displayText": "date", "actionType": "TIMESTAMP", "actionValue": "yyyy-MM-dd", "useKeyFont": false } }
  }
}
```

## Available Commands

CommandRegistry contains 200+ commands in 21 categories:

### Core Categories

| Category | Example Commands |
|----------|------------------|
| `CLIPBOARD` | copy, copy_private, clear_clipboard, paste, cut, pasteAsPlainText |
| `EDITING` | undo, redo, clear |
| `CURSOR` | cursor_left, cursor_right, home, end |
| `NAVIGATION` | page_up, page_down, doc_home, doc_end |
| `SELECTION` | selection_cursor_left, selection_cursor_right, selection_cancel |
| `DELETE` | delete_word, forward_delete_word |
| `MODIFIERS` | shift, ctrl, alt, meta, fn |
| `FUNCTION_KEYS` | f1-f12 |
| `SPECIAL_KEYS` | escape, tab, insert, print_screen |
| `EVENTS` | config, change_method, action, caps_lock |
| `MEDIA` | media_play_pause, volume_up, volume_down |
| `SYSTEM` | search, calculator, calendar, brightness_up |

### Diacritics Categories

| Category | Example Commands |
|----------|------------------|
| `DIACRITICS` | combining_grave, combining_acute |
| `DIACRITICS_SLAVONIC` | combining_titlo, combining_palatalization |
| `DIACRITICS_ARABIC` | arabic_fatha, arabic_kasra, arabic_sukun |
| `HEBREW` | hebrew_dagesh, hebrew_qamats, hebrew_tsere |

## Public API

### ShortSwipeCustomizationManager

```kotlin
class ShortSwipeCustomizationManager(context: Context) {
    // Get mapping for specific key and direction
    fun getMapping(keyCode: String, direction: SwipeDirection): ShortSwipeMapping?

    // Save or update a mapping
    fun saveMapping(mapping: ShortSwipeMapping)

    // Delete a mapping
    fun deleteMapping(keyCode: String, direction: SwipeDirection)

    // Get all mappings for a key
    fun getMappingsForKey(keyCode: String): Map<SwipeDirection, ShortSwipeMapping>

    // Reset all customizations
    fun resetAll()

    // Export for backup
    fun exportToJson(): String

    // Import from backup
    fun importFromJson(json: String)
}
```

### CustomShortSwipeExecutor

```kotlin
class CustomShortSwipeExecutor(
    private val inputConnection: InputConnection,
    private val keyEventHandler: KeyEventHandler
) {
    fun execute(mapping: ShortSwipeMapping, inputConnection: InputConnection?, editorInfo: EditorInfo?): Boolean {
        return when (mapping.actionType) {
            ActionType.TEXT -> executeTextInput(mapping.actionValue, inputConnection)
            ActionType.COMMAND -> executeCommandByName(mapping.actionValue, inputConnection, editorInfo)
            ActionType.KEY_EVENT -> executeKeyEvent(mapping.getKeyEventCode(), inputConnection)
            ActionType.INTENT -> executeIntent(mapping.actionValue)
            ActionType.TIMESTAMP -> executeTimestamp(mapping.actionValue, inputConnection)
        }
    }
}
```

### Single-apostrophe text actions

For TEXT mappings whose entire value is ASCII apostrophe or U+2019, Keyboard2View
uses the ordinary KeyEventHandler route with empty modifiers before invoking the
custom executor. Smart Punctuation can then reclaim only a verified automatic space;
manual whitespace and opening-quote behavior retain ordinary-key semantics. The same
route supports inline search/edit fields and typed-text bookkeeping. Successful actions
retain the normal custom-swipe haptic.

Other text macros remain literal. A multi-character apostrophe-s macro is not a possessive
transaction and does not provide suffix-only undo. Explicit suffix commands require
separate verified attachment and undo state; see the October 5 feature roadmap.

### Timestamp Action Type

The TIMESTAMP action type formats the current `Date` with a [SimpleDateFormat](https://developer.android.com/reference/java/text/SimpleDateFormat) pattern stored in `actionValue` and commits the result via `InputConnection.commitText`. The current system default `Locale` is used.

```kotlin
private fun executeTimestamp(pattern: String, ic: InputConnection?): Boolean {
    if (ic == null) return false
    return try {
        val formatter = SimpleDateFormat(pattern, Locale.getDefault())
        val formatted = formatter.format(Date())
        ic.commitText(formatted, 1)
    } catch (e: IllegalArgumentException) {
        // Malformed pattern (e.g. unclosed quote, unknown letter)
        false
    } catch (e: Exception) {
        false
    }
}
```

Invalid patterns are caught and result in a no-op (`false` return). Preset chips in `CommandPaletteDialog` provide the common patterns (`yyyy-MM-dd`, `HH:mm`, `yyyy-MM-dd HH:mm`, ISO 8601, etc.); users can also enter any custom pattern.

### Terminal-Aware Paste

Both the `AvailableCommand.PASTE` and `CommandRegistry` "paste" paths use `handlePaste()` which detects terminal apps and sends Ctrl+V instead of `performContextMenuAction(android.R.id.paste)`:

```kotlin
// CustomShortSwipeExecutor.kt
private fun handlePaste(inputConnection: InputConnection, editorInfo: EditorInfo?): Boolean {
    return if (TerminalUtils.isTerminalApp(editorInfo)) {
        sendKeyEventWithModifier(inputConnection, KeyEvent.KEYCODE_V,
            KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
    } else {
        inputConnection.performContextMenuAction(android.R.id.paste)
    }
}
```

This mirrors the same logic in `KeyEventHandler.handlePaste()` for the regular paste key. Without this, paste via custom short swipe does nothing in Termux because terminal apps don't implement the Android context menu protocol.

### Pinned Clipboard Commands (paste_pinned_N)

Five commands (`paste_pinned_1` through `paste_pinned_5`) allow direct insertion of pinned clipboard entries by position, without opening the clipboard panel.

**CommandRegistry registration:**

```kotlin
// Category.CLIPBOARD entries in CommandRegistry
Command("paste_pinned_1", "Paste Pin #1", "Insert 1st pinned clipboard entry", Category.CLIPBOARD,
    keywords = listOf("pin", "pinned", "clipboard", "paste", "1", "first")),
// ... through paste_pinned_5
```

**Execution in CustomShortSwipeExecutor:**

```kotlin
private fun executePastePinned(commandName: String, inputConnection: InputConnection): Boolean {
    val index = commandName.removePrefix("paste_pinned_").toIntOrNull()
    if (index == null || index < 1) return false

    val db = ClipboardDatabase.getInstance(context)
    val pinnedEntries = db.getPinnedEntries()

    if (index > pinnedEntries.size) {
        showToast("Pinned entry #$index not found (${pinnedEntries.size} pinned)")
        return false
    }

    val entry = pinnedEntries[index - 1]
    inputConnection.commitText(entry.content, 1)
    return true
}
```

Key implementation details:
- Uses `ClipboardDatabase.getInstance(context)` singleton to access the clipboard database
- `getPinnedEntries()` returns entries ordered by timestamp DESC (most recently pinned first)
- Index is 1-based in the command name, converted to 0-based for list access
- Graceful failure: shows a toast with the actual pinned count if the index exceeds available entries
- All exceptions caught and surfaced as a toast ("Failed to read pinned entry"), never crashes

### Text Limit (MAX_ACTION_LENGTH)

The `actionValue` field on `ShortSwipeMapping` is capped at `MAX_ACTION_LENGTH` (4096 characters). This limit applies to all action types — TEXT input content, COMMAND names, KEY_EVENT codes, and serialized INTENT JSON.

```kotlin
companion object {
    const val MAX_ACTION_LENGTH = 4096
}
```

The previous limit was 100 characters, which was insufficient for long text snippets, multi-line templates, and serialized intent JSON payloads.

### Paste Button in Custom Text Input

The `CommandPaletteDialog` text input field includes a trailing paste `IconButton`. This exists because Jetpack Compose `Dialog` does not reliably receive `performContextMenuAction(android.R.id.paste)` from the IME — the paste action is silently dropped.

The workaround reads directly from `ClipboardManager`:

```kotlin
trailingIcon = {
    IconButton(onClick = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val pasteText = clip.getItemAt(0).coerceToText(context).toString()
            if (pasteText.isNotEmpty()) {
                val combined = text + pasteText
                onTextChange(combined.take(ShortSwipeMapping.MAX_ACTION_LENGTH))
            }
        }
    }) {
        Icon(imageVector = Icons.Filled.Create, contentDescription = "Paste from clipboard")
    }
}
```

The pasted content is appended to existing text and truncated to `MAX_ACTION_LENGTH`.

### CommandRegistry

```kotlin
object CommandRegistry {
    // Get all commands grouped by category
    fun getAllCommands(): Map<Category, List<Command>>

    // Search commands by keyword
    fun search(query: String): List<Command>

    // Get display info (icon + useKeyFont flag)
    fun getDisplayInfo(commandName: String): DisplayInfo?

    data class Command(
        val name: String,
        val category: Category,
        val keywords: List<String>,
        val description: String
    )
}
```

## Intent Action Type

The INTENT action type allows users to fire Android Intents directly from a swipe gesture. This enables launching apps, starting services, sending broadcasts, and integrating with automation tools like Termux.

### IntentDefinition

```kotlin
data class IntentDefinition(
    val name: String = "",                        // Human-readable label
    val targetType: IntentTargetType = ACTIVITY,  // ACTIVITY, SERVICE, or BROADCAST
    val action: String? = null,                   // e.g., "android.intent.action.VIEW"
    val data: String? = null,                     // URI, e.g., "https://google.com"
    val type: String? = null,                     // MIME type, e.g., "text/plain"
    val packageName: String? = null,              // Target package
    val className: String? = null,                // Target component class
    val extras: Map<String, String>? = null       // Key-value extras (String values only)
)

enum class IntentTargetType {
    ACTIVITY,   // Start an activity (most common)
    SERVICE,    // Start a foreground/background service
    BROADCAST   // Send a broadcast intent
}
```

### Gson-Safe Parsing

Gson uses `sun.misc.Unsafe` to instantiate Kotlin data classes, bypassing the constructor. This means non-nullable fields with Kotlin defaults (`name: String = ""`) become `null` when the JSON key is absent. `IntentDefinition.parseFromGson()` re-applies Kotlin defaults after deserialization:

```kotlin
companion object {
    fun parseFromGson(json: String): IntentDefinition? {
        val raw = Gson().fromJson(json, IntentDefinition::class.java) ?: return null
        return IntentDefinition(
            name = raw.name ?: "",
            targetType = raw.targetType ?: IntentTargetType.ACTIVITY,
            // nullable fields pass through unchanged
            action = raw.action, data = raw.data, type = raw.type,
            packageName = raw.packageName, className = raw.className,
            extras = raw.extras
        )
    }
}
```

### Intent Validation

Before execution, `CustomShortSwipeExecutor.validateIntent()` checks:

| Check | Rule |
|-------|------|
| Action or package | Must have at least one of `action` or `packageName` |
| Package installed | If `packageName` set, verify via `PackageManager.getPackageInfo()` |
| URI scheme | If `data` set, `Uri.parse()` result must have a non-blank scheme |

Note: `Uri.parse()` on Android never throws; it silently produces an opaque URI. The scheme check catches malformed URIs like `"not a uri"`.

### Intent Execution Flow

When both `data` and `type` are set, `Intent.setDataAndType()` must be used. Calling `Intent.setData()` clears the type and vice versa — this is an Android API pitfall that was caught in code review.

```kotlin
when {
    hasData && hasType -> intent.setDataAndType(Uri.parse(data), type)
    hasData -> intent.data = Uri.parse(data)
    hasType -> intent.type = type
}
```

Dispatch by target type:
- **ACTIVITY**: `context.startActivity(intent)` with `FLAG_ACTIVITY_NEW_TASK`. Pre-checks `resolveActivity()` unless an explicit component is set.
- **SERVICE**: `context.startService(intent)`.
- **BROADCAST**: `context.sendBroadcast(intent)`.

### Intent Presets (11 built-in)

| Preset | Target | Action | Use Case |
|--------|--------|--------|----------|
| Open Browser | ACTIVITY | `VIEW` | Open URL in browser |
| Share Text | ACTIVITY | `SEND` | Share text via share sheet |
| Dial Phone | ACTIVITY | `DIAL` | Open phone dialer |
| Send Email | ACTIVITY | `SENDTO` | Open email composer |
| Open Settings | ACTIVITY | `SETTINGS` | System settings |
| Wi-Fi Settings | ACTIVITY | `WIFI_SETTINGS` | Wi-Fi settings page |
| Bluetooth Settings | ACTIVITY | `BLUETOOTH_SETTINGS` | Bluetooth settings page |
| Open Camera | ACTIVITY | `IMAGE_CAPTURE` | Launch camera |
| Open Maps | ACTIVITY | `VIEW` (geo:) | Open maps with query |
| Web Search | ACTIVITY | `WEB_SEARCH` | Web search |
| Termux Command | SERVICE | `RUN_COMMAND` | Execute Termux CLI command |

### XML Round-Trip (Layout Import/Export)

Intent mappings stored in layout XML use the `__intent__:` prefix:

```
intent:'{"name":"Open Browser","action":"android.intent.action.VIEW","data":"https://google.com"}'
```

`KeyValueParser.parseIntentKeydef()` reads this format and produces a KeyValue string with the `IntentDefinition.INTENT_PREFIX` (`__intent__:`) prepended. Profile import/export strips this prefix for round-trip compatibility.

`XmlAttributeMapper` handles serialization of all action types to/from XML attributes, including proper single-quote escaping for TEXT values.

### ShortSwipeMapping Factory & Accessor

```kotlin
// Factory method
ShortSwipeMapping.intent(keyCode, direction, displayText, intentJson, useKeyFont)

// Accessor — uses parseFromGson for null safety
fun getIntentDefinition(): IntentDefinition? {
    return if (actionType == ActionType.INTENT) {
        IntentDefinition.parseFromGson(actionValue)
    } else null
}
```

The `actionValue` for INTENT mappings is a JSON string capped at `MAX_ACTION_LENGTH` (4096 chars).

## Implementation Details

### Integration with Pointers.kt

Custom mappings are checked before built-in subkeys:

```kotlin
private fun handleShortGesture(ptr: Pointer, direction: SwipeDirection) {
    // Check custom mapping first
    val customMapping = customizationManager.getMapping(ptr.key.name, direction)
    if (customMapping != null) {
        customExecutor.execute(customMapping)
        return
    }

    // Fall back to built-in subkey
    val subkey = ptr.key.getSubkey(direction)
    if (subkey != null) {
        handleKeyPress(subkey)
    }
}
```

### Modifier Key Support

Custom mappings work even with Shift/Ctrl/Alt active:

```kotlin
private fun shouldBlockBuiltInGesture(): Boolean {
    // Built-in gestures blocked when modifiers active
    return modifierState != 0
}

// But custom mappings always execute
if (customMapping != null) {
    customExecutor.execute(customMapping)  // Executes regardless of modifiers
    return
}

// Built-in check happens after
if (shouldBlockBuiltInGesture()) {
    return  // Block built-in subkey
}
```

### Icon Font Rendering

Custom mappings can use `special_font.ttf` for icon display:

```kotlin
// In Keyboard2View
private fun drawCustomSubLabel(canvas: Canvas, mapping: ShortSwipeMapping, x: Float, y: Float) {
    val paint = if (mapping.useKeyFont) {
        sublabelPaint.apply { typeface = specialFont }
    } else {
        sublabelPaint.apply { typeface = Typeface.DEFAULT }
    }
    canvas.drawText(mapping.displayText, x, y, paint)
}
```

### Direction Zone Colors (UI)

The customization UI uses distinct colors for each direction:

| Direction | Color |
|-----------|-------|
| NW | Red (#FF6B6B) |
| N | Teal (#4ECDC4) |
| NE | Yellow (#FFE66D) |
| W | Mint (#95E1D3) |
| E | Coral (#F38181) |
| SW | Purple (#AA96DA) |
| S | Cyan (#72D4E8) |
| SE | Pink (#FCBAD3) |

### Performance

- Custom mapping lookup: < 1ms (HashMap)
- UI response time: < 16ms (60fps)
- JSON storage load: < 100ms

## October 6 routing regressions

A custom single ASCII or curly apostrophe uses ordinary key punctuation routing.
With readable selection data, it can reclaim an owned automatic space only at a
collapsed selection with a matching cursor stamp. Reported ranges and manual spaces
are preserved; editors without selection data retain the existing text/ownership fallback.
Multi-character TEXT actions remain literal. `SmartAutoSpaceTest` and
`Keyboard2ViewCustomSwipeDispatchTest` protect these boundaries.

`PointersGestureRoutingTest` loads an actual persisted custom mapping on a cold start
with swipe typing disabled, protecting GH #145 without requiring a model load.
Full validation is recorded in [testing strategy](https://github.com/tribixbite/CleverKeys/blob/main/docs/specs/testing-strategy.md).
Explicit suffix transactions and dynamic templates are implemented below.

## Explicit dynamic templates (October 6 development work)

`ActionType.TEMPLATE` and `KeyValue.Kind.Template` hold a validated template payload.
`DynamicTemplate` parses doubled brace escapes and the four fixed tokens in one pass.
Provider values are memoized per invocation and never parsed recursively. Bounds are
UTF-16 units: 4,096 input, 65,536 expanded. Existing TEXT values bypass this parser.

The shared `KeyEventHandler.execute_template` guards all five inline modes and the
editor identity/start epoch. `EditorReadback` captures a bounded prefix/suffix,
absolute selection endpoints and exact selected text. Resolution and validation happen
before one `commitText`, including absolute-offset overflow checks; replacement readback verifies the collapsed end and surrounding
text. A cursor marker then uses `setSelection` with a UTF-16 offset and verifies it.
False/throwing/partial writes and failed cursor moves return failure without retries or
compensating deletions. Batch editing would not make the operation atomic.

Only plain `ClipData.Item.text` is read, only when requested; content coercion/network
reads are absent. Password fields fail closed. Editing invalidates old word/space/undo
and prediction ownership through `LearningHooks.onExplicitEdit`; payload data does not
enter the learning funnel. Failure feedback uses the suggestion bar. The custom-gesture
path returns immediately after TEMPLATE handling, including failure, so a rejected
payload cannot fall through as an ordinary named command or literal string.

Both assignment contexts use `CommandPaletteDialog` and its template editor. JSON,
backup and XML preserve TEMPLATE. `KeyValueParser` supports both quoted template
syntaxes; prefixed template, timestamp and intent payloads share quote parsing that
preserves escaped apostrophes and backslashes.
Coverage lives in `KeyValueParserTest` and native `DynamicTemplateTest`. Final run
counts belong to the [testing strategy source](https://github.com/tribixbite/CleverKeys/blob/main/docs/specs/testing-strategy.md).

## Verified suffix transactions and learning

`append_possessive` / `append_apostrophe` map to Editing keys and are included in the
command/Extra Keys catalogs, labels and accessibility descriptions. The shared key
handler guards inline priority and invokes SuggestionHandler. Custom gestures route
these commands directly with success-only feedback rather than executing raw TEXT.

SuggestionHandler captures WordEditReceipt after a successful candidate commit or
verified typed-word boundary: editor identity/session, Config generation/language,
exact VerifiedSuffixEdit and the latest opaque LearningCommit. At invocation it
revalidates collapsed editor readback and full word+owned separator. VerifiedSuffixEdit
requires acknowledged composition completion and unchanged readback before selecting
only the owned tail, writing the exact suffix/space, and reading back. A false or
throwing result is reconciled only when the desired edit is observed exactly, without
retry. Suffix-only undo precedes legacy word undo and consumes an attempted failed undo.

Predictor's typed receipt API validates instance/commit/epoch, prior context, immutable
learning gates, language and store mutation versions. ContextModel aggregates receipts
from actual writes; disabled/no-op sinks produce none. Replacement validates under
bigram→trigram→vocabulary locks (never held across InputConnection), consumes the old
identity, reverses owned increments, restores prior context and records the full new
word with a fresh handle. Config/field permission/language away-and-back and store
reset/import/cleanup expire handles permanently. Timestamps/cap evictions are not rewound;
persistence is not an atomic storage transaction. The existing word-based rollback
API remains separate; suffix replacement never uses its spelling fallback.

### Editor callback limitation

Android selection callbacks contain no operation ID. Suffix writes accept their own
expected original-caret/tail-selection/final-caret callbacks only inside a bounded, next-main-loop
ledger with the same session and exact editor readback. Real ranged selections or
outside-window movement invalidate ownership. The shared selection gate keeps UI
notifications unconditional, while consumed owned events bypass continuous cancellation
and manual cursor synchronization; otherwise stale coordinates would erase the new
auto-space stamp. An identical manual away/back sequence
inside that brief window can be indistinguishable from delayed owned callbacks.
This limitation requires cross-editor device testing; it is not an absolute guarantee
that every cursor excursion can be identified.

Template execution finishes composition and verifies the same editor/selection before
its single text write. Refusal to finish composition aborts before insertion. A partial
write or rejected caret move is reported without repeating or compensating the text.
