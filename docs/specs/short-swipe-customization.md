# Short Swipe Customization

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

```kotlin
enum class ActionType(val displayName: String, val description: String) {
    TEXT("Text Input", "Insert text directly (up to 100 characters)"),
    COMMAND("Command", "Execute keyboard command (copy, paste, cursor, etc.)"),
    KEY_EVENT("Key Event", "Send raw key event (advanced)"),
    INTENT("Send Intent", "Send Android Intent (advanced)")
}
```

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

CommandRegistry contains 200+ commands in 18 categories:

### Core Categories

| Category | Example Commands |
|----------|------------------|
| `CLIPBOARD` | copy, paste, cut, paste_plain |
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
        }
    }
}
```

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
