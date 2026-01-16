# Short Swipe Customization

## Overview

Short Swipe Customization allows users to fully customize the 8-direction swipe gestures for every key on the keyboard. Each key has 8 subkey positions (N, NE, E, SE, S, SW, W, NW) that can be customized with text input, commands, or key events through a dedicated settings UI.

## Key Files

| File | Class/Function | Purpose |
|------|----------------|---------|
| `src/main/kotlin/tribixbite/cleverkeys/customization/SwipeDirection.kt` | `SwipeDirection` | Enum for 8 directions |
| `src/main/kotlin/tribixbite/cleverkeys/customization/ActionType.kt` | `ActionType` | Enum for action types |
| `src/main/kotlin/tribixbite/cleverkeys/customization/ShortSwipeMapping.kt` | `ShortSwipeMapping` | Data model for custom mapping |
| `src/main/kotlin/tribixbite/cleverkeys/customization/ShortSwipeCustomizationManager.kt` | `ShortSwipeCustomizationManager` | JSON persistence, CRUD operations |
| `src/main/kotlin/tribixbite/cleverkeys/customization/CustomShortSwipeExecutor.kt` | `CustomShortSwipeExecutor` | Executes commands via InputConnection |
| `src/main/kotlin/tribixbite/cleverkeys/customization/CommandRegistry.kt` | `CommandRegistry` | 200+ searchable commands |
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
| CustomShortSwipeExecutor         | -- Execute TEXT/COMMAND/KEY_EVENT
| .execute()                       |
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
enum class ActionType {
    TEXT,      // Insert text string (up to 100 chars)
    COMMAND,   // Execute editing command from CommandRegistry
    KEY_EVENT  // Send Android KeyEvent
}
```

### ShortSwipeMapping

```kotlin
data class ShortSwipeMapping(
    val keyCode: String,           // Key identifier (e.g., "a", "e", "shift")
    val direction: SwipeDirection, // One of 8 directions
    val displayText: String,       // Max 4 chars for visual display
    val actionType: ActionType,    // TEXT, COMMAND, or KEY_EVENT
    val actionValue: String,       // Text content or command name
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
    fun execute(mapping: ShortSwipeMapping): Boolean {
        return when (mapping.actionType) {
            ActionType.TEXT -> {
                inputConnection.commitText(mapping.actionValue, 1)
                true
            }
            ActionType.COMMAND -> {
                executeCommand(mapping.actionValue)
            }
            ActionType.KEY_EVENT -> {
                sendKeyEvent(mapping.actionValue.toIntOrNull())
            }
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
