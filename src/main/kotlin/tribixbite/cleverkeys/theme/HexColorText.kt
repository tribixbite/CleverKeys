package tribixbite.cleverkeys.theme

import java.util.Locale

/**
 * Text rules for the Theme Creator's hex colour field (#93).
 *
 * The field accepts `#RRGGBB` (opaque) and `#AARRGGBB`, with or without `#`, in either case.
 * It applies a colour only when the text is a complete valid value; while the user is typing an
 * incomplete one, nothing changes.
 *
 * Pure (no Compose, no Android), so the rules are unit-tested.
 */
object HexColorText {

    private val PATTERN = Regex("#?([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})")

    /**
     * The ARGB colour [text] denotes, or null when it is not a complete valid value.
     * Surrounding whitespace is ignored; 6 digits are opaque.
     */
    fun parse(text: String): Int? {
        val hex = PATTERN.matchEntire(text.trim())?.groupValues?.get(1) ?: return null
        val value = hex.toLong(16).toInt()
        return if (hex.length == 6) (0xFF000000.toInt() or value) else value
    }

    /** The canonical spelling the field shows for [argb]: `#AARRGGBB`, upper case. */
    fun format(argb: Int): String = String.format(Locale.ROOT, "#%08X", argb)

    /**
     * [argb] for a read-only label: `#AARRGGBB`, or `#RRGGBB` when [withAlpha] is false.
     *
     * Led by U+200E LEFT-TO-RIGHT MARK so the `#` stays in front in a right-to-left UI: on its
     * own, a `#` before a letter (`#E8EEF4`) took the paragraph's direction and was drawn last
     * (`E8EEF4#`, Saga 2026-10-27, Persian). Not for text the user edits ([format]).
     */
    fun display(argb: Int, withAlpha: Boolean = true): String =
        "\u200E" + if (withAlpha) format(argb) else String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF)

    /**
     * The text the field must show now that the colour is [argb], or null to keep [text].
     *
     * [text] is kept whenever it already denotes [argb] in any accepted spelling (6 digits,
     * lower case, no `#`, …). Comparing against the canonical 8-digit spelling instead rewrote a
     * just-typed `#FF0000` to `#FFFF0000` mid-entry, with the cursor left inside the new text
     * (Saga, 2026-10-25).
     */
    fun resyncedText(text: String, argb: Int): String? =
        if (parse(text) == argb) null else format(argb)
}
