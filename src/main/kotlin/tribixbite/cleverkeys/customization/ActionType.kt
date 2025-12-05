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
    /** Icon resource name (optional) */
    val iconName: String? = null
) {
    // Clipboard operations
    COPY("Copy", "content_copy"),
    PASTE("Paste", "content_paste"),
    CUT("Cut", "content_cut"),
    SELECT_ALL("Select All", "select_all"),

    // Undo/Redo
    UNDO("Undo", "undo"),
    REDO("Redo", "redo"),

    // Cursor movement - character
    CURSOR_LEFT("Cursor Left", "keyboard_arrow_left"),
    CURSOR_RIGHT("Cursor Right", "keyboard_arrow_right"),
    CURSOR_UP("Cursor Up", "keyboard_arrow_up"),
    CURSOR_DOWN("Cursor Down", "keyboard_arrow_down"),

    // Cursor movement - line
    CURSOR_HOME("Line Start", "first_page"),
    CURSOR_END("Line End", "last_page"),

    // Cursor movement - document
    CURSOR_DOC_START("Document Start", "vertical_align_top"),
    CURSOR_DOC_END("Document End", "vertical_align_bottom"),

    // Cursor movement - word
    WORD_LEFT("Word Left", "keyboard_double_arrow_left"),
    WORD_RIGHT("Word Right", "keyboard_double_arrow_right"),

    // Delete operations
    DELETE_WORD("Delete Word", "backspace"),

    // Input switching
    SWITCH_IME("Switch Keyboard", "keyboard"),
    VOICE_INPUT("Voice Input", "mic");

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
            "System" to listOf(SWITCH_IME, VOICE_INPUT)
        )
    }
}
