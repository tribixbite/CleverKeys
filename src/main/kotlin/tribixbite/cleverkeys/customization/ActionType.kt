package tribixbite.cleverkeys.customization

/**
 * Types of actions that can be executed by a short swipe gesture.
 */
enum class ActionType(
    /** Display name for UI */
    val displayName: String,
    /** Description for settings UI */
    val description: String
) {
    /**
     * Insert text string directly into the text field.
     * The actionValue contains the text to insert (up to 100 characters).
     */
    TEXT(
        displayName = "Text Input",
        description = "Insert text directly (up to 100 characters)"
    ),

    /**
     * Execute an editing command like copy, paste, cursor movement, etc.
     * The actionValue contains the command name from AvailableCommand enum.
     */
    COMMAND(
        displayName = "Command",
        description = "Execute keyboard command (copy, paste, cursor, etc.)"
    ),

    /**
     * Send a specific key event (for advanced use cases).
     * The actionValue contains the key event code.
     */
    KEY_EVENT(
        displayName = "Key Event",
        description = "Send raw key event (advanced)"
    ),

    /**
     * Send an Android Intent (start activity, service, or broadcast).
     * The actionValue contains the JSON-serialized IntentDefinition.
     */
    INTENT(
        displayName = "Send Intent",
        description = "Send Android Intent (advanced)"
    );

    companion object {
        /**
         * Get ActionType from string name (case-insensitive).
         * @return The matching ActionType or TEXT as default
         */
        fun fromString(name: String): ActionType {
            return entries.find { it.name.equals(name, ignoreCase = true) } ?: TEXT
        }
    }
}

/**
 * Available commands that can be executed via COMMAND action type.
 * These map to editing operations in KeyEventHandler.
 */
enum class AvailableCommand(
    /** Display name for UI */
    val displayName: String,
    /** Description for settings UI */
    val description: String,
    /** Icon resource name (optional) */
    val iconName: String? = null
) {
    // Clipboard operations
    COPY("Copy", "Copy selected text to clipboard", "content_copy"),
    PASTE("Paste", "Paste from clipboard", "content_paste"),
    CUT("Cut", "Cut selected text to clipboard", "content_cut"),
    SELECT_ALL("Select All", "Select all text in field", "select_all"),

    // Undo/Redo
    UNDO("Undo", "Undo last action", "undo"),
    REDO("Redo", "Redo last undone action", "redo"),

    // Cursor movement - character
    CURSOR_LEFT("Cursor Left", "Move cursor one character left", "keyboard_arrow_left"),
    CURSOR_RIGHT("Cursor Right", "Move cursor one character right", "keyboard_arrow_right"),
    CURSOR_UP("Cursor Up", "Move cursor one line up", "keyboard_arrow_up"),
    CURSOR_DOWN("Cursor Down", "Move cursor one line down", "keyboard_arrow_down"),

    // Cursor movement - line
    CURSOR_HOME("Line Start", "Move cursor to line start", "first_page"),
    CURSOR_END("Line End", "Move cursor to line end", "last_page"),

    // Cursor movement - document
    CURSOR_DOC_START("Document Start", "Move cursor to document start", "vertical_align_top"),
    CURSOR_DOC_END("Document End", "Move cursor to document end", "vertical_align_bottom"),

    // Cursor movement - word
    WORD_LEFT("Word Left", "Move cursor one word left", "keyboard_double_arrow_left"),
    WORD_RIGHT("Word Right", "Move cursor one word right", "keyboard_double_arrow_right"),

    // Delete operations
    DELETE_WORD("Delete Word", "Delete word before cursor", "backspace"),

    // Input switching
    SWITCH_IME("Switch Keyboard", "Show input method picker", "keyboard"),
    VOICE_INPUT("Voice Input", "Activate voice input", "mic"),

    // Layout switching
    SWITCH_FORWARD("Next Layout", "Switch to next keyboard layout", "keyboard_arrow_right"),
    SWITCH_BACKWARD("Previous Layout", "Switch to previous keyboard layout", "keyboard_arrow_left"),

    // Text processing
    TEXT_ASSIST("Text Assist", "Process selected text with AI assistants", "smart_toy"),
    REPLACE_TEXT("Replace Text", "Replace selected text with alternatives", "find_replace"),
    SHOW_TEXT_MENU("Show Text Menu", "Show cut/copy/paste/translate menu by selecting word", "menu"),

    // Language switching
    PRIMARY_LANG_TOGGLE("Toggle Primary Language", "Switch between two primary languages", "language"),
    SECONDARY_LANG_TOGGLE("Toggle Secondary Language", "Switch between two secondary languages", "translate");

    companion object {
        /**
         * Get command from string name (case-insensitive).
         * @return The matching command or null if not found
         */
        fun fromString(name: String): AvailableCommand? {
            return entries.find { it.name.equals(name, ignoreCase = true) }
        }

        /**
         * Get commands grouped by category for UI display.
         */
        fun groupedByCategory(): Map<String, List<AvailableCommand>> = mapOf(
            "Clipboard" to listOf(COPY, PASTE, CUT, SELECT_ALL),
            "Edit" to listOf(UNDO, REDO),
            "Cursor" to listOf(CURSOR_LEFT, CURSOR_RIGHT, CURSOR_UP, CURSOR_DOWN),
            "Navigation" to listOf(CURSOR_HOME, CURSOR_END, CURSOR_DOC_START, CURSOR_DOC_END),
            "Words" to listOf(WORD_LEFT, WORD_RIGHT, DELETE_WORD),
            "Layout" to listOf(SWITCH_FORWARD, SWITCH_BACKWARD),
            "Text" to listOf(TEXT_ASSIST, REPLACE_TEXT, SHOW_TEXT_MENU),
            "Language" to listOf(PRIMARY_LANG_TOGGLE, SECONDARY_LANG_TOGGLE),
            "System" to listOf(SWITCH_IME, VOICE_INPUT)
        )
    }
}
