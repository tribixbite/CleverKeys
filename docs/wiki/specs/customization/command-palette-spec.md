---
title: Command Palette - Technical Specification
user_guide: ../../customization/command-palette.md
status: implemented
version: v1.2.7
---

# Command Palette Technical Specification

## Overview

The command palette provides a searchable interface for quick access to keyboard actions, settings, and features. It's triggered by long-pressing the settings key or via a configured shortcut.

## Key Components

| Component | File | Purpose |
|-----------|------|---------|
| CommandPalette | `CommandPalette.kt` | Main palette logic |
| CommandRegistry | `CommandRegistry.kt` | Available commands |
| PaletteView | `CommandPaletteView.kt` | UI rendering |
| SearchEngine | `PaletteSearch.kt` | Fuzzy search |
| Config | `Config.kt` | Palette settings |

## Data Model

### Command Definition

```kotlin
// CommandRegistry.kt
data class Command(
    val id: String,
    val displayName: String,
    val description: String,
    val category: CommandCategory,
    val keywords: List<String>,
    val action: () -> Unit,
    val isEnabled: () -> Boolean = { true },
    val icon: Int? = null
)

enum class CommandCategory {
    TEXT,       // Copy, paste, select
    NAVIGATION, // Cursor, home, end
    INPUT,      // Layout, language, emoji
    SETTINGS,   // Toggles, preferences
    CLIPBOARD   // History, clear, pin
}
```

### Command Registry

```kotlin
// CommandRegistry.kt
object CommandRegistry {
    private val commands = mutableListOf<Command>()

    init {
        // Text actions
        register(Command(
            id = "copy",
            displayName = "Copy",
            description = "Copy selected text",
            category = CommandCategory.TEXT,
            keywords = listOf("copy", "clipboard", "selection"),
            action = { inputConnection?.performContextMenuAction(android.R.id.copy) }
        ))

        register(Command(
            id = "paste",
            displayName = "Paste",
            description = "Paste from clipboard",
            category = CommandCategory.TEXT,
            keywords = listOf("paste", "clipboard", "insert"),
            action = { inputConnection?.performContextMenuAction(android.R.id.paste) },
            isEnabled = { clipboardManager.hasPrimaryClip() }
        ))

        // Navigation actions
        register(Command(
            id = "home",
            displayName = "Home",
            description = "Jump to line start",
            category = CommandCategory.NAVIGATION,
            keywords = listOf("home", "start", "beginning", "line"),
            action = { sendKeyEvent(KeyEvent.KEYCODE_MOVE_HOME) }
        ))

        // ... 30+ more commands
    }
}
```

## Activation Flow

```
Long-press settings key (>500ms)
        ↓
Detect long press in Pointers.kt
        ↓
CommandPalette.show()
        ↓
PaletteView inflates overlay
        ↓
Focus search input
        ↓
User types search query
        ↓
PaletteSearch.search(query)
        ↓
Filter and rank results
        ↓
Display matched commands
        ↓
User taps command
        ↓
Execute command.action()
        ↓
CommandPalette.hide()
```

## Search Implementation

### Fuzzy Search

```kotlin
// PaletteSearch.kt
class PaletteSearch {
    fun search(query: String, commands: List<Command>): List<SearchResult> {
        if (query.isEmpty()) {
            return getRecentCommands() + commands.sortedBy { it.category }
        }

        val normalizedQuery = query.lowercase()

        return commands
            .mapNotNull { cmd -> scoreCommand(cmd, normalizedQuery) }
            .sortedByDescending { it.score }
    }

    private fun scoreCommand(cmd: Command, query: String): SearchResult? {
        var score = 0

        // Exact match in name
        if (cmd.displayName.lowercase().contains(query)) {
            score += 100
        }

        // Keyword match
        cmd.keywords.forEach { keyword ->
            if (keyword.contains(query)) {
                score += 50
            }
        }

        // Fuzzy match
        val fuzzyScore = fuzzyMatch(cmd.displayName, query)
        score += fuzzyScore

        // Boost recent commands
        if (isRecent(cmd.id)) {
            score += 30
        }

        return if (score > 20) SearchResult(cmd, score) else null
    }

    private fun fuzzyMatch(text: String, query: String): Int {
        // Levenshtein distance-based scoring
        val distance = levenshteinDistance(text.lowercase(), query)
        val maxLen = maxOf(text.length, query.length)
        return ((1 - distance.toFloat() / maxLen) * 50).toInt()
    }
}
```

## UI Rendering

### Palette View

```kotlin
// CommandPaletteView.kt
class CommandPaletteView : FrameLayout {
    private lateinit var searchInput: EditText
    private lateinit var resultsList: RecyclerView
    private lateinit var categoryTabs: TabLayout

    fun show() {
        visibility = VISIBLE
        searchInput.requestFocus()
        showKeyboard()

        // Show recent commands initially
        displayResults(search.getRecentCommands())
    }

    private fun onSearchQueryChanged(query: String) {
        val results = search.search(query, registry.getAllCommands())
        displayResults(results)
    }

    private fun onCommandSelected(command: Command) {
        if (command.isEnabled()) {
            recordRecent(command.id)
            hide()
            command.action()
        }
    }
}
```

### Layout Structure

```
┌────────────────────────────────────────┐
│ ┌────────────────────────────────────┐ │
│ │ 🔍 Search actions...              X│ │ ← Search input
│ └────────────────────────────────────┘ │
├────────────────────────────────────────┤
│ [All] [Text] [Nav] [Input] [Settings]  │ ← Category tabs
├────────────────────────────────────────┤
│ ┌────────────────────────────────────┐ │
│ │ 📋 Copy         Copy selected text │ │ ← Command item
│ │ ✂️ Cut          Cut selected text  │ │
│ │ 📄 Paste        Paste from clipbo..│ │
│ │ ☑️ Select All   Select all text    │ │
│ └────────────────────────────────────┘ │
└────────────────────────────────────────┘
```

## Recent Commands

```kotlin
// CommandPalette.kt
private val recentCommands = LinkedHashMap<String, Long>(16, 0.75f, true)

fun recordRecent(commandId: String) {
    recentCommands[commandId] = System.currentTimeMillis()

    // Keep max 10 recent
    while (recentCommands.size > 10) {
        recentCommands.remove(recentCommands.keys.first())
    }

    saveRecentToPrefs()
}

fun getRecentCommands(): List<Command> {
    return recentCommands.keys
        .reversed()
        .take(5)
        .mapNotNull { registry.getCommand(it) }
}
```

## Customization

### Favorites

```kotlin
// CommandPalette.kt
private val favoriteCommands = mutableSetOf<String>()

fun toggleFavorite(commandId: String) {
    if (favoriteCommands.contains(commandId)) {
        favoriteCommands.remove(commandId)
    } else {
        favoriteCommands.add(commandId)
    }
    saveFavoritesToPrefs()
}
```

### Hidden Commands

```kotlin
// CommandPalette.kt
private val hiddenCommands = mutableSetOf<String>()

fun getVisibleCommands(): List<Command> {
    return registry.getAllCommands()
        .filter { !hiddenCommands.contains(it.id) }
}
```

## Configuration

| Setting | Key | Default | Description |
|---------|-----|---------|-------------|
| **Trigger** | `palette_trigger` | "long_press_settings" | Activation method |
| **Show Recent** | `palette_show_recent` | true | Show recent commands |
| **Show Categories** | `palette_show_categories` | true | Show category tabs |
| **Max Recent** | `palette_max_recent` | 5 | Number of recent items |

## Related Specifications

- [Gesture System](../../../specs/gesture-system.md) - Long-press detection
- [Settings System](../../../specs/settings-system.md) - Quick toggles
- [Per-Key Actions](per-key-actions-spec.md) - Action definitions
