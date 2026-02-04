package tribixbite.cleverkeys.customization

import tribixbite.cleverkeys.KeyValue

/**
 * Maps ShortSwipeMapping actions to Unexpected Keyboard XML attribute values.
 *
 * Command names from CommandRegistry match KeyValue.getSpecialKeyByName() and can be
 * used directly as XML attribute values. This enables full round-trip compatibility:
 * - User customizes key via UI → stored in JSON
 * - Export to XML → command name used as attribute value
 * - Import XML → KeyValue.getKeyByName() parses it correctly
 */
object XmlAttributeMapper {

    /**
     * Convert a ShortSwipeMapping to an XML attribute value string.
     *
     * @param mapping The mapping to convert
     * @return The string value for the XML attribute (e.g., "'Hello'", "copy", "keyevent:66")
     */
    fun toXmlValue(mapping: ShortSwipeMapping): String {
        return when (mapping.actionType) {
            ActionType.TEXT -> {
                // Wrap in single quotes and escape existing single quotes
                "'${mapping.actionValue.replace("'", "'\'")}'"
            }
            ActionType.COMMAND -> {
                // Command names from CommandRegistry match KeyValue.getSpecialKeyByName()
                // so they can be used directly as XML attribute values
                val commandName = mapping.actionValue

                // Verify it's a valid KeyValue name (optional but helpful for debugging)
                if (KeyValue.getKeyByName(commandName) != null) {
                    commandName
                } else {
                    // Try legacy AvailableCommand mapping as fallback
                    val legacyCommand = mapping.getCommand()
                    mapLegacyCommandToKeyword(legacyCommand) ?: commandName
                }
            }
            ActionType.KEY_EVENT -> {
                // Use keyevent syntax
                "keyevent:${mapping.actionValue}"
            }
            ActionType.INTENT -> {
                // Export intent JSON with quoted syntax for KeyValueParser compatibility
                // Escape single quotes in JSON and wrap in single quotes
                val escapedJson = mapping.actionValue.replace("'", "\\'")
                "intent:'$escapedJson'"
            }
        }
    }

    /**
     * Map legacy AvailableCommand enum to XML keyword (for backwards compatibility).
     */
    private fun mapLegacyCommandToKeyword(command: AvailableCommand?): String? {
        return when (command) {
            AvailableCommand.COPY -> "copy"
            AvailableCommand.PASTE -> "paste"
            AvailableCommand.CUT -> "cut"
            AvailableCommand.SELECT_ALL -> "selectAll"
            AvailableCommand.UNDO -> "undo"
            AvailableCommand.REDO -> "redo"
            AvailableCommand.CURSOR_LEFT -> "cursor_left"
            AvailableCommand.CURSOR_RIGHT -> "cursor_right"
            AvailableCommand.CURSOR_UP -> "cursor_up"
            AvailableCommand.CURSOR_DOWN -> "cursor_down"
            AvailableCommand.CURSOR_HOME -> "home"
            AvailableCommand.CURSOR_END -> "end"
            AvailableCommand.CURSOR_DOC_START -> "doc_home"
            AvailableCommand.CURSOR_DOC_END -> "doc_end"
            AvailableCommand.WORD_LEFT -> "cursor_left" // Slider handles word movement via repeat
            AvailableCommand.WORD_RIGHT -> "cursor_right"
            AvailableCommand.DELETE_WORD -> "delete_word"
            AvailableCommand.SWITCH_IME -> "change_method"
            AvailableCommand.VOICE_INPUT -> "voice_typing"
            AvailableCommand.SWITCH_FORWARD -> "switch_forward"
            AvailableCommand.SWITCH_BACKWARD -> "switch_backward"
            else -> null
        }
    }
}
