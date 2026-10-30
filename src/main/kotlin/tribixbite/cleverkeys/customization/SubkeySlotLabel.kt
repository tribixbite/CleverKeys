package tribixbite.cleverkeys.customization

/**
 * The leading label of a direction row in the per-key Customize dialog: the subkey as the key
 * draws it. Pure (`SubkeySlotLabelTest`).
 *
 * Saga 2026-10-30: the row cut every label to 4 UTF-16 chars, so Enter's NE row (the layout's
 * `action` key, label "Action") read "Acti", and an emoji sequence could be split mid-character.
 * A label is never cut now: one longer than a key glyph ([GLYPH_CHARS]) is shown whole and the
 * row lets it wrap ([Text.wraps]); the row's description column names the action in words.
 */
object SubkeySlotLabel {

    /** Longest label (UTF-16 chars) kept on one line like a key glyph — custom labels' own cap. */
    const val GLYPH_CHARS = ShortSwipeMapping.MAX_DISPLAY_LENGTH

    /**
     * @property text the whole label, never truncated
     * @property keyFont draw it in the keyboard's icon font
     * @property wraps longer than a glyph: give it a bounded width and let it wrap
     */
    data class Text(val text: String, val keyFont: Boolean, val wraps: Boolean)

    /** The label to show for [label] (drawn in the key font when [keyFont]); null when blank. */
    fun of(label: String, keyFont: Boolean): Text? {
        if (label.isBlank()) return null
        // An icon-font glyph is one character whatever its code; never wrap it.
        if (keyFont) return Text(label, keyFont = true, wraps = false)
        return Text(label, keyFont = false, wraps = label.length > GLYPH_CHARS)
    }
}
