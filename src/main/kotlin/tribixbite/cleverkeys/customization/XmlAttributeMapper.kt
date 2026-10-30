package tribixbite.cleverkeys.customization

import tribixbite.cleverkeys.KeyValue

/**
 * Writes a [ShortSwipeMapping]'s action as a layout subkey value — the ONE serializer behind
 * both places a short swipe goes into layout XML (docs/specs/profile_system_restoration.md
 * "Export XML values"):
 * - Export XML (`XmlLayoutExporter.injectMappings`) bakes the custom mappings into a file;
 * - the per-key dialog and the subkey popover's assign screen write a layout's own subkey into
 *   its modified copy (`LayoutSubkeyEditor`).
 *
 * Every value it writes is read back by the layout parser (`KeyValue.getKeyByName`, as
 * `KeyboardData.parse_key_attr` reads a subkey attribute) as the SAME action; `XmlActionRoundTripTest`
 * and `XmlLayoutExporterRoundTripTest` pin that for every action type.
 */
object XmlAttributeMapper {

    /** Fallback labels of actions that have no text of their own to show. */
    private const val KEY_EVENT_LABEL = "⌨"
    private const val TEMPLATE_LABEL = "{}"
    private const val TIMESTAMP_LABEL = "📅"
    private const val INTENT_LABEL = "↗"

    /**
     * The value to write into layout XML for [mapping], in the syntax the layout parser reads back
     * as the same action, or null when the action cannot live in a layout:
     * - a catalogue command that only the custom-swipe executor knows by name (`paste_pinned_1`,
     *   `primaryLangToggle` …) would be read as a string key that TYPES its own name;
     * - a `removed` blank is not an action (Export XML removes the slot's attributes instead);
     * - empty text, a non-numeric key event, or a character XML 1.0 cannot carry (a control
     *   character other than tab and line breaks, an unpaired surrogate).
     *
     * Commands are written as their key name (`copy`; a legacy AvailableCommand spelling such as
     * `SELECT_ALL` as its layout keyword `selectAll`). Everything else takes the `label:action`
     * form — `label:'text'`, `label:keyevent:66`, `label:template:'…'`, `label:timestamp:'…'`,
     * `label:intent:'{json}'` — because the parser reads an action only after a label: a bare
     * `'hello'` is a key typing `'hello'` with its quotes, which is what Export XML wrote before
     * 2026-10-30. Inside the quotes `\` and `'` are escaped with a backslash.
     */
    fun layoutValue(mapping: ShortSwipeMapping): String? {
        if (mapping.isRemoval) return null
        val value = mapping.actionValue
        if (!xmlCanCarry(value)) return null
        return when (mapping.actionType) {
            ActionType.COMMAND -> layoutCommandName(mapping)
            ActionType.TEXT ->
                if (value.isEmpty()) null
                else "${layoutLabel(mapping, labelPrefix(value, ShortSwipeMapping.MAX_DISPLAY_LENGTH))}:${quotedPayload(value)}"
            ActionType.KEY_EVENT -> value.toIntOrNull()?.let { "${layoutLabel(mapping, KEY_EVENT_LABEL)}:keyevent:$it" }
            ActionType.TEMPLATE -> "${layoutLabel(mapping, TEMPLATE_LABEL)}:template:${quotedPayload(value)}"
            ActionType.TIMESTAMP -> "${layoutLabel(mapping, TIMESTAMP_LABEL)}:timestamp:${quotedPayload(value)}"
            ActionType.INTENT -> {
                // The intent's own name is a better label than the arrow, when it can be one.
                val named = IntentDefinition.parseFromGson(value)?.name
                    ?.let { labelPrefix(it, ShortSwipeMapping.MAX_DISPLAY_LENGTH) }
                    ?.takeIf(::usableLabel)
                "${layoutLabel(mapping, named ?: INTENT_LABEL)}:intent:${quotedPayload(value)}"
            }
        }
    }

    /**
     * The layout key name of a COMMAND mapping: the catalogue name when the layout parser knows
     * it as a key, else — for a legacy AvailableCommand spelling (`SELECT_ALL`) — the editing key
     * the custom swipe itself runs (`CommandRouting.legacyEditingName`), else null. Other legacy
     * commands (cursor moves, IME switches) run executor code that no layout key matches exactly.
     */
    private fun layoutCommandName(mapping: ShortSwipeMapping): String? {
        val name = mapping.actionValue
        if (KeyValue.getSpecialKeyByName(name) != null) return name
        return CommandRouting.legacyEditingName(name)?.takeIf { KeyValue.getSpecialKeyByName(it) != null }
    }

    /**
     * The key label for a `label:action` layout value: the mapping's own label when a layout can
     * carry it ([usableLabel]; never a key-font glyph, which a layout label cannot select the
     * font for), else [fallback] under the same rule, else "…".
     */
    private fun layoutLabel(mapping: ShortSwipeMapping, fallback: String): String {
        val own = mapping.displayText.takeIf { !mapping.useKeyFont && usableLabel(it) }
        return own ?: fallback.takeIf(::usableLabel) ?: "…"
    }

    /**
     * Whether [label] survives as the text before the first `:` of a layout value: not blank, no
     * `:` (it would end the label), no control or private-use (key-font) character, nothing XML
     * cannot carry, and no leading `loc ` (the layout parser strips that as the "localized key"
     * marker, `KeyboardData.parse_key_attr`).
     */
    private fun usableLabel(label: String): Boolean =
        label.isNotBlank() && ':' !in label && !label.startsWith("loc ") && xmlCanCarry(label) &&
            label.none { Character.isISOControl(it) || it.code in 0xE000..0xF8FF }

    /**
     * The longest prefix of [text] of at most [maxChars] UTF-16 units (a mapping label's limit,
     * [ShortSwipeMapping.MAX_DISPLAY_LENGTH]) that does not end in half a surrogate pair.
     */
    private fun labelPrefix(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        var end = 0
        while (end < text.length) {
            val next = end + Character.charCount(text.codePointAt(end))
            if (next > maxChars) break
            end = next
        }
        return text.substring(0, end)
    }

    /**
     * Whether every character of [text] is allowed in an XML 1.0 document (tab, line feed and
     * carriage return are, as `&#9;` `&#10;` `&#13;`; other C0 controls and unpaired surrogates
     * are not, and a file holding them would not load).
     */
    internal fun xmlCanCarry(text: String): Boolean {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                Character.isHighSurrogate(c) -> {
                    if (i + 1 >= text.length || !Character.isLowSurrogate(text[i + 1])) return false
                    i++
                }
                Character.isLowSurrogate(c) -> return false
                c.code < 0x20 && c != '\t' && c != '\n' && c != '\r' -> return false
                c == '\uFFFE' || c == '\uFFFF' -> return false
            }
            i++
        }
        return true
    }

    /** A quoted payload: backslashes and single quotes escaped (`KeyValueParser` unescapes both). */
    private fun quotedPayload(value: String): String =
        "'${value.replace("\\", "\\\\").replace("'", "\\'")}'"
}
