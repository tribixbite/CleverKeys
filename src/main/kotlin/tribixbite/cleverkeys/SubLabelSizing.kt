package tribixbite.cleverkeys

import tribixbite.cleverkeys.customization.ActionType
import tribixbite.cleverkeys.customization.CommandRegistry
import tribixbite.cleverkeys.customization.ShortSwipeMapping

/**
 * One size rule for every key sublabel, whether it comes from the layout (a subkey
 * [KeyValue]) or from a user's short-swipe mapping, and wherever it is drawn: the keyboard
 * ([Keyboard2View]), the per-key customization magnifier (`KeyMagnifierView`) and the subkey
 * popover (`SubkeyPopoverRenderer`).
 *
 * Before this (Seeker report, 2026-10-08) the custom-mapping path sized a command's default
 * label differently from the same label on a layout subkey: [KeyValue.FLAG_SMALLER_FONT] was
 * only honoured when the mapping used the key font, colour emoji were drawn at the full letter
 * size, and nothing bounded a label's width, so a 2-glyph emoji label ("🔒⎘", Private Copy)
 * ran across the key's main letter.
 *
 * The size of a sublabel is `base * scale(...)`, then shrunk with [fitToWidth] when the label is
 * still wider than its slot ([maxWidth]). Pure JVM: no Android types, so the rule is pinned by
 * `SubLabelSizingTest` and every draw path calls the same functions.
 */
object SubLabelSizing {

    /** The factor [KeyValue.FLAG_SMALLER_FONT] has always applied to a label. */
    const val SMALLER_FONT_SCALE = 0.75f

    /**
     * Key-font icons and colour emoji are drawn at this factor (never compounded with
     * [SMALLER_FONT_SCALE]). Both FILL the em box — the special font's Material icons span
     * about 0.92 em (uniE030: -159..759 of 1000), a colour emoji spans ascent to descent at
     * ~1.2 em wide — while a text sublabel's capitals are ~0.7 em and its multi-letter labels
     * already carry the 0.75 smaller-font factor. At 0.75 an icon's ink is ~0.69 em: the same
     * visual size as the text sublabels around it, instead of the 1.3x (icons) / ~2x (emoji)
     * they used to read as.
     */
    const val GLYPH_SCALE = 0.75f

    /** Corner slots (NW/NE/SW/SE) may use up to half the key's width. */
    const val CORNER_WIDTH_FRACTION = 0.5f

    /**
     * W/E slots share the vertical band of the key's centred main label: at most the outer
     * third of the key, and never closer to the main label than [SIDE_GAP_FRACTION].
     */
    const val SIDE_WIDTH_FRACTION = 0.34f

    /** Minimum clear space between a W/E sublabel and the main label, as a key-width fraction. */
    const val SIDE_GAP_FRACTION = 0.04f

    /** N/S slots are centred between the two corner slots of their row. */
    const val CENTRE_WIDTH_FRACTION = 0.6f

    /** Size factor for a layout subkey. */
    fun scaleFor(kv: KeyValue): Float =
        scale(kv.hasFlagsAny(KeyValue.FLAG_SMALLER_FONT), kv.hasFlagsAny(KeyValue.FLAG_KEY_FONT), kv.getString())

    /**
     * Size factor for a custom mapping's label. A mapping showing its command's DEFAULT label
     * is drawn exactly like that command's [KeyValue] on a layout (including
     * [KeyValue.FLAG_SMALLER_FONT]); a label the user typed is plain text at the normal size
     * (or the glyph size, when it is an emoji).
     */
    fun scaleFor(mapping: ShortSwipeMapping): Float =
        scale(showsSmallerDefault(mapping), mapping.useKeyFont, mapping.displayText)

    /**
     * Size factor: [SMALLER_FONT_SCALE] for flagged labels, [GLYPH_SCALE] for key-font icons
     * and colour emoji, else 1.
     */
    fun scale(smallerFont: Boolean, keyFont: Boolean, text: CharSequence): Float = when {
        smallerFont -> SMALLER_FONT_SCALE
        keyFont || containsColourEmoji(text) -> GLYPH_SCALE
        else -> 1f
    }

    /**
     * Text size for a label PREVIEW shown beside [textSp]-sized text in settings UI (the command
     * palette's label dialog, the subkey assign badge, the mapping list): the same factor the
     * keyboard applies, so an icon or emoji preview matches the text next to it.
     */
    fun previewSp(textSp: Float, keyFont: Boolean, text: CharSequence): Float =
        textSp * scale(false, keyFont, text)

    /**
     * Whether [mapping] shows its command's default label and that command's key carries
     * [KeyValue.FLAG_SMALLER_FONT]. Cached per command: command → (label, flag) is static.
     */
    fun showsSmallerDefault(mapping: ShortSwipeMapping): Boolean {
        if (mapping.actionType != ActionType.COMMAND) return false
        val default = defaults.getOrPut(mapping.actionValue) {
            val kv = KeyValue.getSpecialKeyByName(mapping.actionValue)
            CommandRegistry.getDisplayInfo(mapping.actionValue).displayText to
                (kv?.hasFlagsAny(KeyValue.FLAG_SMALLER_FONT) == true)
        }
        return default.second && default.first == mapping.displayText
    }

    private val defaults = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Boolean>>()

    /**
     * Widest a label may be in sublabel slot [subIndex] (Keyboard2View numbering: 1 NW, 2 NE,
     * 3 SW, 4 SE, 5 W, 6 E, 7 N, 8 S) of a key [keyWidth] wide with [padding] at its edges.
     * [mainLabelWidth] is the measured width of the key's centred main label (0 when unknown):
     * a W/E label stops [SIDE_GAP_FRACTION] short of it, so a wide letter ("w", "m") narrows
     * the side slots further.
     */
    fun maxWidth(keyWidth: Float, padding: Float, subIndex: Int, mainLabelWidth: Float = 0f): Float {
        val budget = when (subIndex) {
            5, 6 -> minOf(
                keyWidth * SIDE_WIDTH_FRACTION - padding,
                (keyWidth - mainLabelWidth) / 2f - padding - keyWidth * SIDE_GAP_FRACTION
            )
            7, 8 -> keyWidth * CENTRE_WIDTH_FRACTION
            else -> keyWidth * CORNER_WIDTH_FRACTION - padding
        }
        return budget.coerceAtLeast(0f)
    }

    /** [size], shrunk proportionally when text measuring [measuredWidth] at it exceeds [maxWidth]. */
    fun fitToWidth(size: Float, measuredWidth: Float, maxWidth: Float): Float =
        if (measuredWidth <= maxWidth || measuredWidth <= 0f || maxWidth <= 0f) size
        else size * maxWidth / measuredWidth

    /**
     * Whether [text] contains a code point Android draws with the colour emoji font: the
     * supplementary pictographic blocks (U+1F000–U+1FAFF) and the BMP media/clock symbols the
     * catalogue uses (U+231A–U+231B, U+23E9–U+23FA), which the system text font lacks and
     * NotoColorEmoji supplies.
     */
    fun containsColourEmoji(text: CharSequence): Boolean {
        var i = 0
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            if (cp in 0x1F000..0x1FAFF || cp in 0x231A..0x231B || cp in 0x23E9..0x23FA) return true
            i += Character.charCount(cp)
        }
        return false
    }
}
