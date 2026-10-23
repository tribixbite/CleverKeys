# Short Swipe Customization

> **Status (2026-10-23):** API blocks below re-transcribed from `customization/` at HEAD
> (6 `ActionType`s, `CustomShortSwipeExecutor(context)`, 240 commands / 21 categories / 12 presets).
>
> Canonical public documentation: [per-key actions guide](../wiki/customization/per-key-actions.md)
> and [paired specification](../wiki/specs/customization/per-key-actions-spec.md).
> This internal reference retains older implementation examples; current action metadata,
> routing regressions, and verified suffix/template behavior are maintained in the paired spec.


> **Note:** As of v1.4.0, the canonical version of this specification lives at
> [`docs/wiki/specs/customization/per-key-actions-spec.md`](../wiki/specs/customization/per-key-actions-spec.md)
> and renders at <https://cleverkeys.app/specs/customization/per-key-actions-spec/>.
> This file is preserved for cross-references but may not be kept in sync.

## Overview

Short Swipe Customization allows users to fully customize the 8-direction swipe gestures for every key on the keyboard. Each key has 8 subkey positions (N, NE, E, SE, S, SW, W, NW) that can be customized with text input, commands, key events, Android Intents, timestamps or dynamic templates (`ActionType`, six values) through a dedicated settings UI.

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
| `src/main/kotlin/tribixbite/cleverkeys/customization/CommandRegistry.kt` | `CommandRegistry` | `ALL_COMMANDS`: 240 commands in 21 `Category` values (2026-10-20) |
| `src/main/kotlin/tribixbite/cleverkeys/customization/CommandRouting.kt` | `CommandRouting.editingValue` | which COMMAND names are editing keys owned by `KeyEventHandler.handleEditingKey` |
| `src/main/kotlin/tribixbite/cleverkeys/customization/XmlAttributeMapper.kt` | `XmlAttributeMapper` | XML round-trip for all action types incl. INTENT |
| `src/main/kotlin/tribixbite/cleverkeys/KeyValueParser.kt` | `parseIntentKeydef()` | Parses `intent:'json'` from layout XML |
| `src/main/kotlin/tribixbite/cleverkeys/Pointers.kt` | short-swipe branch of `onTouchUp` (`:534-544`), `resolveShortSwipe` scan (`:920-930`), `executeCustomShortSwipe` (`:948`) | `_customSwipeManager.getMapping(keyCode, dir)` is checked before built-in subkeys |
| `src/main/kotlin/tribixbite/cleverkeys/Keyboard2View.kt` | `onCustomShortSwipe(mapping)` (`:1088`), `drawCustomSubLabel` (`:2135`) | routes editing commands / templates through `KeyEventHandler`, everything else to the executor; draws the custom sublabel |
| `src/main/kotlin/tribixbite/cleverkeys/KeyEventHandler.kt` | `handleEditingKey`, `handlePaste`, `execute_template` (`:1330`) | the one implementation of editing commands and templates |

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
| Pointers.onTouchUp short-swipe   | -- _customSwipeManager.getMapping() first
+----------------------------------+
       |
       v (if custom found)
+----------------------------------+
| Keyboard2View.onCustomShortSwipe | -- editing COMMANDs + TEMPLATE -> KeyEventHandler;
|  -> CustomShortSwipeExecutor     |    TEXT/other COMMAND/KEY_EVENT/INTENT/TIMESTAMP
| .execute()                       |    -> executor
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

```kotlin
// customization/ActionType.kt — labels are string resources, not literals
enum class ActionType(@StringRes val displayNameRes: Int, @StringRes val descriptionRes: Int) {
    TEXT,       // actionValue = literal text to commit
    COMMAND,    // actionValue = command name (CommandRegistry / legacy AvailableCommand)
    KEY_EVENT,  // actionValue = key event code
    INTENT,     // actionValue = JSON-serialized IntentDefinition
    TIMESTAMP,  // actionValue = SimpleDateFormat pattern, formatted at execution
    TEMPLATE,   // actionValue = dynamic template; run by KeyEventHandler.execute_template, never by the executor
}
```

(`AvailableCommand`, defined in the same file, is the legacy enum of built-in editing commands —
`COPY`, `PRIVATE_COPY`, `PASTE`, … — not an action type.)

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
    }
  }
}
```

## Available Commands

`CommandRegistry.ALL_COMMANDS` contains 240 commands in 21 `Category` values (counted at HEAD 2026-10-20; the README and wiki palette page carry the same number):

### Core Categories

| Category | Example Commands |
|----------|------------------|
| `CLIPBOARD` | copy, paste, cut, paste_plain, paste_pinned_1..5 |
| `EDITING` | undo, redo, select_all |
| `CURSOR` | cursor_left, cursor_right, home, end |
| `NAVIGATION` | page_up, page_down, doc_home, doc_end |
| `SELECTION` | select_all, selection_mode |
| `DELETE` | delete_word, forward_delete_word |
| `MODIFIERS` | shift, ctrl, alt, meta, fn |
| `FUNCTION_KEYS` | f1-f12 |
| `SPECIAL_KEYS` | escape, tab, insert, print_screen |
| `EVENTS` | config, change_method, action, caps_lock |
| `MEDIA` | media_play_pause, volume_up, volume_down |
| `SYSTEM` | search, calculator, calendar, brightness_up |
| `SPACES` | space, nbsp, nnbsp, zwj |
| `TEXT` | blt, bgt (literal text keys) |
| `TEXT_ACTIONS` | textAssist, replaceText, replaceTextMenu, suggestions |
| `LANGUAGE` | primaryLangToggle, secondaryLangToggle |
| `TIMESTAMP` | timestamp_date, timestamp_time, timestamp_datetime, timestamp_time_seconds |

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
// customization/ShortSwipeCustomizationManager.kt — process singleton
ShortSwipeCustomizationManager.getInstance(context)
ShortSwipeCustomizationManager.isMappableKeyCode(code: String): Boolean

suspend fun loadMappings()
fun getMapping(keyCode: String, direction: SwipeDirection): ShortSwipeMapping?
fun getMappingsForKey(keyCode: String): Map<SwipeDirection, ShortSwipeMapping>
fun getAllMappings(): List<ShortSwipeMapping>
suspend fun setMapping(mapping: ShortSwipeMapping)
suspend fun removeMapping(keyCode: String, direction: SwipeDirection): Boolean
suspend fun removeMappingsForKey(keyCode: String): Int
suspend fun resetAll()
fun exportToJson(): String
suspend fun importFromJson(json: String, merge: Boolean = false): Int
suspend fun importFromMappings(mappings: List<ShortSwipeMapping>, merge: Boolean = false): Int
fun addChangeListener(listener: () -> Unit); fun removeChangeListener(listener: () -> Unit)
```

Storage is `filesDir/short_swipe_customizations.json`; writes are suspend functions and the
in-memory cache is what `Pointers` reads on every short swipe (see `settings-layout-integration.md`
for the cache + change-listener model).

### CustomShortSwipeExecutor

```kotlin
// customization/CustomShortSwipeExecutor.kt
class CustomShortSwipeExecutor(private val context: Context) {
    fun execute(mapping: ShortSwipeMapping, inputConnection: InputConnection?, editorInfo: EditorInfo?): Boolean =
        when (mapping.actionType) {
            ActionType.TEXT -> executeTextInput(mapping.actionValue, inputConnection)
            ActionType.COMMAND -> executeCommandByName(mapping.actionValue, inputConnection, editorInfo)
            ActionType.KEY_EVENT -> executeKeyEvent(mapping.getKeyEventCode(), inputConnection)
            ActionType.INTENT -> executeIntent(mapping.actionValue)
            ActionType.TIMESTAMP -> executeTimestamp(mapping.actionValue, inputConnection)
            ActionType.TEMPLATE -> false   // run by KeyEventHandler.execute_template before this is reached
        }
}
```

`executeCommandByName` refuses editing commands (`CommandRouting.editingValue(name) != null` →
`false`): their one implementation is `KeyEventHandler.handleEditingKey`, which
`Keyboard2View.onCustomShortSwipe` reaches first (todo 12, 2026-10-12). Everything else is
resolved through the legacy `AvailableCommand` enum, then `CommandRegistry.getByName`.

### Terminal-Aware Paste

`paste` is an editing command, so a custom short swipe reaches `KeyEventHandler.handlePaste`
(`KeyEventHandler.kt:~757`), the same path as the paste key. There is no Ctrl+V: terminal-style
editors (Termux, ConnectBot, and BLIND remote fields such as RustDesk — `isTerminalStyleEditor()`,
backed by `TerminalUtils.editorKind`) get `pasteFromSystemClipboard()`, which reads the OS clip
and commits its text through the `InputConnection`; every other editor gets
`sendContextMenuAction(android.R.id.paste)`. A Ctrl+V key event would paste the REMOTE clipboard
in RustDesk (2026-10-13), which is why committed text is the one route.

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
    enum class Category(@StringRes val labelRes: Int, val sortOrder: Int)   // 21 values
    data class Command(
        val name: String,                 // internal id used by KeyValue.getKeyByName()
        @StringRes val nameRes: Int,      // `cmd_<id>`, localized
        @StringRes val descriptionRes: Int, // `cmd_<id>_desc`
        val category: Category,
        val symbol: String? = null,       // keyboard-font glyph
        val keywords: List<String> = emptyList(),  // English search aids
    )
    val ALL_COMMANDS: List<Command>
    fun getByCategory(): Map<Category, List<Command>>
    fun getByCategory(category: Category): List<Command>
    fun getByName(name: String): Command?
    fun getKeyValue(commandName: String): KeyValue?
    fun getDisplayInfo(commandName: String): CommandDisplayInfo
    fun upgradeLegacyDefaultLabel(...)
}
```

Search over names, localized descriptions and keywords is done by the palette UI
(`CommandPaletteDialog`), not by a `CommandRegistry.search` function.

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

### Intent Presets (12 built-in, `IntentDefinition.PRESET_ENTRIES`)

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
| Termux Command (Background) | SERVICE | `RUN_COMMAND` | Execute a Termux CLI command in the background |
| Termux Tab (Visible) | SERVICE | `RUN_COMMAND` | Run a Termux command in a visible session |

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
// Pointers.kt:534-544 (onTouchUp, short-swipe branch) — abridged
val swipeDir = directionToSwipeDirection(direction)          // 16 → 8 directions
val keyCode = ptr.key.keys[0]?.getString() ?: ""
val customMapping = _customSwipeManager.getMapping(keyCode, swipeDir)
if (customMapping != null) {
    // Custom mappings work even with shift active - this is intentional
    executeCustomShortSwipe(ptr, customMapping)               // → Keyboard2View.onCustomShortSwipe
    return
}
// otherwise: built-in subkey via getNearestKeyAtDirection (±1 of 16 directions)
```

The forgiving ±1 scan that resolves built-in subkeys (`resolveShortSwipe`, `Pointers.kt:920-930`)
also checks a custom mapping at each scanned direction, in the same 0, −1, +1 order.

### Modifier Key Support

Custom mappings fire regardless of latched modifiers (the check above happens before any
modifier gating), which is deliberate; built-in subkeys go through `modifyKey(…, ptr.modifiers)`.

### Icon Font Rendering

Custom mappings can use `special_font.ttf` for icon display:

`Keyboard2View.drawCustomSubLabel(…, useKeyFont, …)` (`Keyboard2View.kt:2135`) uses the same base
size and factors as layout sublabels (`drawSubLabel`); `useKeyFont` only selects the paint
(`tc_key.sublabel_paint(useKeyFont, color, textSize, alpha)`), i.e. `special_font.ttf` for icons.

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

## Dynamic templates (development)

The canonical [per-key guide](../wiki/customization/per-key-actions.md) and
[paired spec](../wiki/specs/customization/per-key-actions-spec.md) describe the explicit
TEMPLATE action (`ActionType.TEMPLATE`; executed by `KeyEventHandler.execute_template`, reached from `Keyboard2View.onCustomShortSwipe` before the executor, whose own branch returns `false`). TEXT stays literal. Template expansion is bounded/nonrecursive,
uses verified editor readback and UTF-16 caret positions, and is guarded by the shared
key router against hidden-editor writes. A rejected template never retries via the
custom-action fallback. Native execution and parser coverage are in DynamicTemplateTest
and KeyValueParserTest; final validation remains in testing-strategy.md.
