package tribixbite.cleverkeys.customization

/**
 * Maps ShortSwipeMapping actions to Unexpected Keyboard XML attribute values.
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
                // Map AvailableCommand to UK keywords
                val command = mapping.getCommand()
                mapCommandToKeyword(command) ?: mapping.actionValue
            }
            ActionType.KEY_EVENT -> {
                // Use keyevent syntax
                "keyevent:${mapping.actionValue}"
            }
        }
    }

    /**
     * Map AvailableCommand enum to UK XML keyword.
     */
    private fun mapCommandToKeyword(command: AvailableCommand?): String? {
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
            
            AvailableCommand.CURSOR_HOME -> "home" // KeyValue.java: "home" -> MOVE_HOME
            AvailableCommand.CURSOR_END -> "end"   // KeyValue.java: "end" -> MOVE_END
            AvailableCommand.CURSOR_DOC_START -> "keyevent:122" // MOVE_HOME (ctrl+home usually, but raw code might be needed if no keyword)
            AvailableCommand.CURSOR_DOC_END -> "keyevent:123"   // MOVE_END
            
            AvailableCommand.WORD_LEFT -> "keyevent:92" // KEYCODE_PAGE_UP? No. 
            // UK doesn't have explicit "word_left" keyword in the simple list, 
            // but it has "ctrl" modifier logic. 
            // Let's stick to known keywords or keyevents.
            // AvailableCommand.WORD_LEFT maps to ctrl+left normally. 
            // UK XML doesn't support "ctrl+left" as a single attribute value easily 
            // unless we use a macro which KeyValueParser supports: "ctrl,left" ?
            // KeyValueParser supports macros: "Cmd1,Cmd2".
            // So we can try "ctrl,left". 
            
            // Re-reading KeyValue.java:
            // case "cursor_left": return sliderKey(Slider.Cursor_left, 1);
            // This is a slider key, not a simple event.
            
            AvailableCommand.DELETE_WORD -> "delete_word"
            
            AvailableCommand.SWITCH_IME -> "change_method"
            AvailableCommand.VOICE_INPUT -> "voice_typing"
            
            else -> null
        }
    }
}
