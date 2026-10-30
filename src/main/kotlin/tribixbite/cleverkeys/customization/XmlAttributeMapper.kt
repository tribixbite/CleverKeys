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
                // Wrap in single quotes, escaping ' as \' to match QUOTED_PAT regex
                "'${mapping.actionValue.replace("'", "\\'")}'"
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
            ActionType.INTENT -> "intent:${quotedPayload(mapping.actionValue)}"
            ActionType.TEMPLATE -> "template:${quotedPayload(mapping.actionValue)}"
            ActionType.TIMESTAMP -> "timestamp:${quotedPayload(mapping.actionValue)}"
        }
    }

    /**
     * The value to write into a layout's own XML for [mapping] (a modified copy, see
     * `LayoutSubkeyEditor`), in the syntax `KeyValueParser` reads back as the same key, or null
     * when the action cannot live in a layout:
     * - a catalogue command that only the custom-swipe executor knows by name (`paste_pinned_1`,
     *   `primaryLangToggle` …) would be read as a string key that TYPES its own name;
     * - an intent is read as a string key typing `__intent__:{…}` (only profile importers know
     *   that prefix); a `removed` blank is not an action; text with a backslash has no escape.
     *
     * Text, key events, templates and timestamps take the `label:action` form: a bare `'text'`
     * (what [toXmlValue] writes for profile export) is read back as the literal string with its
     * quotes. # TODO: Export XML (`toXmlValue`) still writes the bare form for TEXT, INTENT,
     * TEMPLATE and TIMESTAMP — those exported subkeys type their quoted source on import; its
     * output is pinned by ShortSwipeIntentTest/ShortSwipeMappingTest, so it is a separate fix.
     */
    fun layoutValue(mapping: ShortSwipeMapping): String? {
        if (mapping.isRemoval) return null
        val value = mapping.actionValue
        return when (mapping.actionType) {
            ActionType.COMMAND -> value.takeIf { KeyValue.getSpecialKeyByName(it) != null }
            ActionType.TEXT ->
                if (value.isEmpty() || '\\' in value) null
                else "${layoutLabel(mapping, value.take(ShortSwipeMapping.MAX_DISPLAY_LENGTH))}:'${value.replace("'", "\\'")}'"
            ActionType.KEY_EVENT -> value.toIntOrNull()?.let { "${layoutLabel(mapping, "⌨")}:keyevent:$it" }
            ActionType.TEMPLATE -> "${layoutLabel(mapping, "{}")}:template:${quotedPayload(value)}"
            ActionType.TIMESTAMP -> "${layoutLabel(mapping, "📅")}:timestamp:${quotedPayload(value)}"
            ActionType.INTENT -> null
        }
    }

    /**
     * The key label for a `label:action` layout value: the mapping's own label when it is plain
     * text the parser can carry (no `:`, which ends the label; no key-font glyph, which a layout
     * label cannot select the font for), else [fallback] under the same rule, else "…".
     */
    private fun layoutLabel(mapping: ShortSwipeMapping, fallback: String): String {
        fun usable(label: String) = label.isNotBlank() && ':' !in label &&
            label.none { Character.isISOControl(it) || it.code in 0xE000..0xF8FF }
        val own = mapping.displayText.takeIf { !mapping.useKeyFont && usable(it) }
        return own ?: fallback.takeIf(::usable) ?: "…"
    }

    /** Match prefixed action parsing: escape backslashes before quote delimiters. */
    private fun quotedPayload(value: String): String =
        "'${value.replace("\\", "\\\\").replace("'", "\\'")}'"

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
