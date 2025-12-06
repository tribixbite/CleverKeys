package tribixbite.cleverkeys.customization

import android.view.KeyEvent
import tribixbite.cleverkeys.KeyValue

/**
 * Comprehensive registry of ALL available keyboard commands.
 *
 * This registry enumerates every command from KeyValue.kt's getSpecialKeyByName() function,
 * organized into searchable categories. Used by the Short Swipe Customization UI to present
 * the COMPLETE list of available actions to users.
 *
 * Categories:
 * - Modifiers (shift, ctrl, alt, meta, fn)
 * - Events (switch layouts, config, etc.)
 * - Key Events (esc, enter, arrows, function keys, etc.)
 * - Editing (copy, paste, undo, cursor movement, etc.)
 * - Characters (special chars, spaces, bidi markers)
 * - Diacritics (combining marks, dead keys)
 */
object CommandRegistry {

    /**
     * Represents a single command that can be bound to a short swipe.
     */
    data class Command(
        /** Internal name used in KeyValue.getKeyByName() */
        val name: String,
        /** Human-readable display name */
        val displayName: String,
        /** Short description of what this command does */
        val description: String,
        /** Category for grouping in UI */
        val category: Category,
        /** Symbol shown on keyboard (from KeyValue font) */
        val symbol: String? = null,
        /** Search keywords for filtering */
        val keywords: List<String> = emptyList()
    )

    /**
     * Command categories for UI organization.
     */
    enum class Category(val displayName: String, val sortOrder: Int) {
        CLIPBOARD("Clipboard", 0),
        EDITING("Editing", 1),
        CURSOR("Cursor Movement", 2),
        NAVIGATION("Navigation", 3),
        SELECTION("Selection", 4),
        DELETE("Delete", 5),
        EVENTS("Keyboard Events", 6),
        MODIFIERS("Modifiers", 7),
        FUNCTION_KEYS("Function Keys", 8),
        SPECIAL_KEYS("Special Keys", 9),
        SPACES("Spaces & Formatting", 10),
        DIACRITICS("Diacritics", 11),
        TEXT("Text Input", 12)
    }

    /**
     * Complete list of ALL available commands.
     * Extracted from KeyValue.getSpecialKeyByName() in KeyValue.kt
     */
    val ALL_COMMANDS: List<Command> = listOf(
        // ========== CLIPBOARD ==========
        Command("copy", "Copy", "Copy selected text to clipboard", Category.CLIPBOARD,
            keywords = listOf("copy", "clipboard", "ctrl+c")),
        Command("paste", "Paste", "Paste from clipboard", Category.CLIPBOARD,
            keywords = listOf("paste", "clipboard", "ctrl+v")),
        Command("cut", "Cut", "Cut selected text to clipboard", Category.CLIPBOARD,
            keywords = listOf("cut", "clipboard", "ctrl+x")),
        Command("selectAll", "Select All", "Select all text in field", Category.CLIPBOARD,
            keywords = listOf("select", "all", "ctrl+a")),
        Command("pasteAsPlainText", "Paste Plain", "Paste as plain text (no formatting)", Category.CLIPBOARD,
            keywords = listOf("paste", "plain", "text", "no format")),
        Command("shareText", "Share", "Share selected text", Category.CLIPBOARD,
            keywords = listOf("share", "send")),

        // ========== EDITING ==========
        Command("undo", "Undo", "Undo last action", Category.EDITING,
            keywords = listOf("undo", "ctrl+z", "back", "revert")),
        Command("redo", "Redo", "Redo last undone action", Category.EDITING,
            keywords = listOf("redo", "ctrl+y", "forward")),
        Command("delete_word", "Delete Word", "Delete word before cursor", Category.DELETE,
            keywords = listOf("delete", "word", "backspace", "ctrl+backspace")),
        Command("forward_delete_word", "Forward Delete Word", "Delete word after cursor", Category.DELETE,
            keywords = listOf("delete", "word", "forward", "ctrl+delete")),
        Command("delete_last_word", "Delete Last Word", "Smart delete last auto-inserted or typed word", Category.DELETE,
            keywords = listOf("delete", "word", "last", "smart")),
        Command("backspace", "Backspace", "Delete character before cursor", Category.DELETE,
            keywords = listOf("backspace", "delete", "back")),
        Command("delete", "Delete", "Delete character after cursor", Category.DELETE,
            keywords = listOf("delete", "forward", "del")),

        // ========== CURSOR MOVEMENT ==========
        Command("cursor_left", "Cursor Left", "Move cursor one character left", Category.CURSOR,
            keywords = listOf("cursor", "left", "arrow", "move")),
        Command("cursor_right", "Cursor Right", "Move cursor one character right", Category.CURSOR,
            keywords = listOf("cursor", "right", "arrow", "move")),
        Command("cursor_up", "Cursor Up", "Move cursor one line up", Category.CURSOR,
            keywords = listOf("cursor", "up", "arrow", "move")),
        Command("cursor_down", "Cursor Down", "Move cursor one line down", Category.CURSOR,
            keywords = listOf("cursor", "down", "arrow", "move")),
        Command("left", "Arrow Left", "Send left arrow key event", Category.CURSOR,
            keywords = listOf("left", "arrow", "dpad")),
        Command("right", "Arrow Right", "Send right arrow key event", Category.CURSOR,
            keywords = listOf("right", "arrow", "dpad")),
        Command("up", "Arrow Up", "Send up arrow key event", Category.CURSOR,
            keywords = listOf("up", "arrow", "dpad")),
        Command("down", "Arrow Down", "Send down arrow key event", Category.CURSOR,
            keywords = listOf("down", "arrow", "dpad")),

        // ========== NAVIGATION ==========
        Command("home", "Home", "Move cursor to line start", Category.NAVIGATION,
            keywords = listOf("home", "line", "start", "beginning")),
        Command("end", "End", "Move cursor to line end", Category.NAVIGATION,
            keywords = listOf("end", "line", "end")),
        Command("doc_home", "Document Start", "Move cursor to document start (Ctrl+Home)", Category.NAVIGATION,
            keywords = listOf("home", "document", "start", "beginning", "top")),
        Command("doc_end", "Document End", "Move cursor to document end (Ctrl+End)", Category.NAVIGATION,
            keywords = listOf("end", "document", "bottom")),
        Command("page_up", "Page Up", "Scroll/move one page up", Category.NAVIGATION,
            keywords = listOf("page", "up", "scroll")),
        Command("page_down", "Page Down", "Scroll/move one page down", Category.NAVIGATION,
            keywords = listOf("page", "down", "scroll")),

        // ========== SELECTION ==========
        Command("selection_cursor_left", "Extend Selection Left", "Extend selection to the left", Category.SELECTION,
            keywords = listOf("select", "left", "extend", "shift")),
        Command("selection_cursor_right", "Extend Selection Right", "Extend selection to the right", Category.SELECTION,
            keywords = listOf("select", "right", "extend", "shift")),
        Command("selection_cancel", "Cancel Selection", "Cancel current selection", Category.SELECTION,
            keywords = listOf("select", "cancel", "deselect", "esc")),

        // ========== KEYBOARD EVENTS ==========
        Command("config", "Settings", "Open keyboard settings", Category.EVENTS,
            keywords = listOf("settings", "config", "configure", "options")),
        Command("switch_text", "Switch to Text", "Switch to text keyboard layout", Category.EVENTS,
            keywords = listOf("switch", "text", "abc", "letters")),
        Command("switch_numeric", "Switch to Numbers", "Switch to numeric/symbol keyboard", Category.EVENTS,
            keywords = listOf("switch", "numbers", "numeric", "123", "symbols")),
        Command("switch_emoji", "Switch to Emoji", "Switch to emoji keyboard", Category.EVENTS,
            keywords = listOf("switch", "emoji", "emoticon", "smiley")),
        Command("switch_back_emoji", "Back from Emoji", "Return from emoji keyboard", Category.EVENTS,
            keywords = listOf("switch", "back", "emoji", "abc")),
        Command("switch_clipboard", "Clipboard History", "Open clipboard history", Category.EVENTS,
            keywords = listOf("clipboard", "history", "paste", "recent")),
        Command("switch_back_clipboard", "Back from Clipboard", "Return from clipboard", Category.EVENTS,
            keywords = listOf("switch", "back", "clipboard", "abc")),
        Command("switch_forward", "Next Layout", "Switch to next keyboard layout", Category.EVENTS,
            keywords = listOf("switch", "next", "layout", "forward")),
        Command("switch_backward", "Previous Layout", "Switch to previous keyboard layout", Category.EVENTS,
            keywords = listOf("switch", "previous", "layout", "back")),
        Command("switch_greekmath", "Greek/Math", "Switch to Greek/Math symbols", Category.EVENTS,
            keywords = listOf("greek", "math", "symbols", "pi")),
        Command("change_method", "Change Keyboard", "Show input method picker", Category.EVENTS,
            keywords = listOf("change", "keyboard", "input", "method", "picker", "switch")),
        Command("change_method_prev", "Previous Keyboard", "Switch to previous input method", Category.EVENTS,
            keywords = listOf("change", "keyboard", "previous", "auto")),
        Command("action", "Action", "Editor action (Go/Search/Send)", Category.EVENTS,
            keywords = listOf("action", "go", "search", "send", "enter")),
        Command("capslock", "Caps Lock", "Toggle caps lock", Category.EVENTS,
            keywords = listOf("caps", "lock", "uppercase", "capital")),
        Command("voice_typing", "Voice Typing", "Activate voice input", Category.EVENTS,
            keywords = listOf("voice", "speech", "dictate", "microphone")),
        Command("voice_typing_chooser", "Voice Typing Picker", "Choose voice input method", Category.EVENTS,
            keywords = listOf("voice", "speech", "picker", "choose")),

        // ========== MODIFIERS ==========
        Command("shift", "Shift", "Shift modifier (uppercase/symbols)", Category.MODIFIERS,
            keywords = listOf("shift", "uppercase", "capital")),
        Command("ctrl", "Ctrl", "Control modifier", Category.MODIFIERS,
            keywords = listOf("ctrl", "control", "modifier")),
        Command("alt", "Alt", "Alt modifier", Category.MODIFIERS,
            keywords = listOf("alt", "alternate", "modifier")),
        Command("meta", "Meta", "Meta/Windows modifier", Category.MODIFIERS,
            keywords = listOf("meta", "windows", "super", "modifier")),
        Command("fn", "Fn", "Function modifier", Category.MODIFIERS,
            keywords = listOf("fn", "function", "modifier")),

        // ========== FUNCTION KEYS ==========
        Command("f1", "F1", "Function key F1", Category.FUNCTION_KEYS,
            keywords = listOf("f1", "function", "help")),
        Command("f2", "F2", "Function key F2", Category.FUNCTION_KEYS,
            keywords = listOf("f2", "function", "rename")),
        Command("f3", "F3", "Function key F3", Category.FUNCTION_KEYS,
            keywords = listOf("f3", "function", "find")),
        Command("f4", "F4", "Function key F4", Category.FUNCTION_KEYS,
            keywords = listOf("f4", "function", "close")),
        Command("f5", "F5", "Function key F5", Category.FUNCTION_KEYS,
            keywords = listOf("f5", "function", "refresh")),
        Command("f6", "F6", "Function key F6", Category.FUNCTION_KEYS,
            keywords = listOf("f6", "function")),
        Command("f7", "F7", "Function key F7", Category.FUNCTION_KEYS,
            keywords = listOf("f7", "function", "spell")),
        Command("f8", "F8", "Function key F8", Category.FUNCTION_KEYS,
            keywords = listOf("f8", "function")),
        Command("f9", "F9", "Function key F9", Category.FUNCTION_KEYS,
            keywords = listOf("f9", "function")),
        Command("f10", "F10", "Function key F10", Category.FUNCTION_KEYS,
            keywords = listOf("f10", "function", "menu")),
        Command("f11", "F11", "Function key F11", Category.FUNCTION_KEYS,
            keywords = listOf("f11", "function", "fullscreen")),
        Command("f12", "F12", "Function key F12", Category.FUNCTION_KEYS,
            keywords = listOf("f12", "function", "devtools")),

        // ========== SPECIAL KEYS ==========
        Command("esc", "Escape", "Escape key", Category.SPECIAL_KEYS,
            keywords = listOf("esc", "escape", "cancel", "close")),
        Command("enter", "Enter", "Enter/Return key", Category.SPECIAL_KEYS,
            keywords = listOf("enter", "return", "newline")),
        Command("tab", "Tab", "Tab key", Category.SPECIAL_KEYS,
            keywords = listOf("tab", "indent", "next")),
        Command("menu", "Menu", "Context menu key", Category.SPECIAL_KEYS,
            keywords = listOf("menu", "context", "right click")),
        Command("insert", "Insert", "Insert key (toggle overwrite)", Category.SPECIAL_KEYS,
            keywords = listOf("insert", "ins", "overwrite")),
        Command("scroll_lock", "Scroll Lock", "Scroll lock key", Category.SPECIAL_KEYS,
            keywords = listOf("scroll", "lock")),
        Command("compose", "Compose", "Compose key (for accents)", Category.SPECIAL_KEYS,
            keywords = listOf("compose", "accent", "dead key")),
        Command("compose_cancel", "Cancel Compose", "Cancel compose sequence", Category.SPECIAL_KEYS,
            keywords = listOf("compose", "cancel")),

        // ========== SPACES & FORMATTING ==========
        Command("space", "Space", "Regular space character", Category.SPACES,
            keywords = listOf("space", "blank")),
        Command("nbsp", "Non-Breaking Space", "Non-breaking space (no line wrap)", Category.SPACES,
            keywords = listOf("nbsp", "space", "non-breaking", "no wrap")),
        Command("nnbsp", "Narrow NBSP", "Narrow non-breaking space", Category.SPACES,
            keywords = listOf("nnbsp", "narrow", "space", "thin")),
        Command("\\t", "Tab Char", "Tab character", Category.SPACES,
            keywords = listOf("tab", "character", "indent")),
        Command("\\n", "Newline", "Newline character", Category.SPACES,
            keywords = listOf("newline", "line break", "enter")),
        Command("zwj", "ZWJ", "Zero-width joiner (ligature)", Category.SPACES,
            keywords = listOf("zwj", "zero width", "joiner", "ligature")),
        Command("zwnj", "ZWNJ", "Zero-width non-joiner (halfspace)", Category.SPACES,
            keywords = listOf("zwnj", "zero width", "non joiner", "halfspace")),
        Command("lrm", "LRM", "Left-to-right mark", Category.SPACES,
            keywords = listOf("lrm", "left to right", "bidi", "direction")),
        Command("rlm", "RLM", "Right-to-left mark", Category.SPACES,
            keywords = listOf("rlm", "right to left", "bidi", "direction")),

        // ========== DIACRITICS (Dead Keys) ==========
        Command("accent_aigu", "Acute Accent", "Dead key for acute accent (é)", Category.DIACRITICS,
            keywords = listOf("accent", "acute", "aigu", "diacritic")),
        Command("accent_grave", "Grave Accent", "Dead key for grave accent (è)", Category.DIACRITICS,
            keywords = listOf("accent", "grave", "diacritic")),
        Command("accent_circonflexe", "Circumflex", "Dead key for circumflex (ê)", Category.DIACRITICS,
            keywords = listOf("accent", "circumflex", "hat", "diacritic")),
        Command("accent_tilde", "Tilde", "Dead key for tilde (ñ)", Category.DIACRITICS,
            keywords = listOf("accent", "tilde", "diacritic")),
        Command("accent_trema", "Umlaut", "Dead key for umlaut/diaeresis (ë)", Category.DIACRITICS,
            keywords = listOf("accent", "umlaut", "trema", "diaeresis", "diacritic")),
        Command("accent_cedille", "Cedilla", "Dead key for cedilla (ç)", Category.DIACRITICS,
            keywords = listOf("accent", "cedilla", "diacritic")),
        Command("accent_caron", "Caron", "Dead key for caron/háček (č)", Category.DIACRITICS,
            keywords = listOf("accent", "caron", "hacek", "diacritic")),
        Command("accent_macron", "Macron", "Dead key for macron (ā)", Category.DIACRITICS,
            keywords = listOf("accent", "macron", "bar", "diacritic")),
        Command("accent_ring", "Ring", "Dead key for ring (å)", Category.DIACRITICS,
            keywords = listOf("accent", "ring", "circle", "diacritic")),
        Command("accent_ogonek", "Ogonek", "Dead key for ogonek (ą)", Category.DIACRITICS,
            keywords = listOf("accent", "ogonek", "tail", "diacritic")),
        Command("accent_dot_above", "Dot Above", "Dead key for dot above (ż)", Category.DIACRITICS,
            keywords = listOf("accent", "dot", "above", "diacritic")),
        Command("accent_dot_below", "Dot Below", "Dead key for dot below (ḍ)", Category.DIACRITICS,
            keywords = listOf("accent", "dot", "below", "diacritic")),
        Command("accent_double_aigu", "Double Acute", "Dead key for double acute (ő)", Category.DIACRITICS,
            keywords = listOf("accent", "double", "acute", "diacritic")),
        Command("accent_breve", "Breve", "Dead key for breve (ă)", Category.DIACRITICS,
            keywords = listOf("accent", "breve", "short", "diacritic")),
        Command("accent_slash", "Slash", "Dead key for slash (ø)", Category.DIACRITICS,
            keywords = listOf("accent", "slash", "stroke", "diacritic")),
        Command("accent_bar", "Bar", "Dead key for bar (đ)", Category.DIACRITICS,
            keywords = listOf("accent", "bar", "stroke", "diacritic")),
        Command("accent_horn", "Horn", "Dead key for horn (ơ)", Category.DIACRITICS,
            keywords = listOf("accent", "horn", "vietnamese", "diacritic")),
        Command("accent_hook_above", "Hook Above", "Dead key for hook above (ả)", Category.DIACRITICS,
            keywords = listOf("accent", "hook", "above", "vietnamese", "diacritic")),
        Command("accent_double_grave", "Double Grave", "Dead key for double grave (ȁ)", Category.DIACRITICS,
            keywords = listOf("accent", "double", "grave", "diacritic")),
        Command("accent_arrow_right", "Arrow Right", "Dead key for rightward arrow (a⃗)", Category.DIACRITICS,
            keywords = listOf("accent", "arrow", "vector", "diacritic")),
        Command("superscript", "Superscript", "Modifier for superscript (¹²³)", Category.DIACRITICS,
            keywords = listOf("superscript", "sup", "exponent", "power")),
        Command("subscript", "Subscript", "Modifier for subscript (₁₂₃)", Category.DIACRITICS,
            keywords = listOf("subscript", "sub", "index")),
        Command("ordinal", "Ordinal", "Modifier for ordinal indicators (º)", Category.DIACRITICS,
            keywords = listOf("ordinal", "ord", "degree")),
        Command("arrows", "Arrows", "Modifier for arrow symbols", Category.DIACRITICS,
            keywords = listOf("arrows", "modifier")),
        Command("box", "Box Drawing", "Modifier for box drawing characters", Category.DIACRITICS,
            keywords = listOf("box", "drawing", "lines"))
    )

    /**
     * Get all commands grouped by category.
     */
    fun getByCategory(): Map<Category, List<Command>> {
        return ALL_COMMANDS.groupBy { it.category }
            .toSortedMap(compareBy { it.sortOrder })
    }

    /**
     * Search commands by query string.
     * Matches against name, displayName, description, and keywords.
     */
    fun search(query: String): List<Command> {
        if (query.isBlank()) return ALL_COMMANDS

        val lowerQuery = query.lowercase().trim()
        return ALL_COMMANDS.filter { cmd ->
            cmd.name.lowercase().contains(lowerQuery) ||
            cmd.displayName.lowercase().contains(lowerQuery) ||
            cmd.description.lowercase().contains(lowerQuery) ||
            cmd.keywords.any { it.lowercase().contains(lowerQuery) }
        }
    }

    /**
     * Search commands with ranking.
     * Returns commands sorted by relevance (exact matches first, then partial).
     */
    fun searchRanked(query: String): List<Command> {
        if (query.isBlank()) return ALL_COMMANDS

        val lowerQuery = query.lowercase().trim()

        return ALL_COMMANDS
            .map { cmd ->
                val score = calculateScore(cmd, lowerQuery)
                cmd to score
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    private fun calculateScore(cmd: Command, query: String): Int {
        var score = 0

        // Exact name match (highest)
        if (cmd.name.lowercase() == query) score += 100
        else if (cmd.name.lowercase().startsWith(query)) score += 50
        else if (cmd.name.lowercase().contains(query)) score += 20

        // Display name match
        if (cmd.displayName.lowercase() == query) score += 80
        else if (cmd.displayName.lowercase().startsWith(query)) score += 40
        else if (cmd.displayName.lowercase().contains(query)) score += 15

        // Description match
        if (cmd.description.lowercase().contains(query)) score += 10

        // Keyword match
        cmd.keywords.forEach { keyword ->
            if (keyword == query) score += 60
            else if (keyword.startsWith(query)) score += 30
            else if (keyword.contains(query)) score += 10
        }

        return score
    }

    /**
     * Get a command by its internal name.
     */
    fun getByName(name: String): Command? {
        return ALL_COMMANDS.find { it.name == name }
    }

    /**
     * Get the KeyValue for a command name.
     * Returns null if the command doesn't exist.
     */
    fun getKeyValue(commandName: String): KeyValue? {
        return try {
            KeyValue.getKeyByName(commandName)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get all commands in a specific category.
     */
    fun getByCategory(category: Category): List<Command> {
        return ALL_COMMANDS.filter { it.category == category }
    }

    /**
     * Get total count of available commands.
     */
    val totalCount: Int get() = ALL_COMMANDS.size
}
