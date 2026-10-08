package tribixbite.cleverkeys.autocorrect

import tribixbite.cleverkeys.KeyboardData
import tribixbite.cleverkeys.a11y.KeyboardGeometry
import tribixbite.cleverkeys.swipe.KeyLetter

/**
 * The letter keys of a layout as centre points in KEY-GRID units: x in standard key widths,
 * y in row heights — the same units as [KeyAdjacency]'s built-in QWERTY table, so one key
 * pitch on any board costs what one pitch costs on QWERTY.
 *
 * Grid units (not pixels) on purpose: they depend only on the layout definition, so the
 * adjacency a letter gets does not move with screen width, orientation, keyboard height or
 * margins, and the result is computable off-device from the shipped layout XML.
 *
 * Geometry comes from the one shared hit-test geometry ([KeyboardGeometry.computeKeyRects])
 * and the letter of a key from the one shared centre-letter rule ([KeyLetter.centreLetterOf]),
 * so autocorrect sees exactly the letter keys swipe typing and TalkBack see. Corner values
 * are not positioned: they have no key of their own.
 */
object LayoutLetterGrid {

    /** Unit geometry: one key width and one row height are both 1, no margins. */
    private val GRID = KeyboardGeometry.Params(keyWidth = 1f, rowHeight = 1f, marginTop = 0f, marginLeft = 0f)

    /**
     * Centre of every centre-letter key of [keyboard], keyed by its lowercase letter. A letter
     * that appears on two keys keeps the first one in row-major order.
     */
    fun of(keyboard: KeyboardData): Map<Char, Pair<Float, Float>> {
        val out = LinkedHashMap<Char, Pair<Float, Float>>()
        for (rect in KeyboardGeometry.computeKeyRects(keyboard, GRID)) {
            val letter = KeyLetter.centreLetterOf(rect.kv) ?: continue
            if (letter in out) continue
            val b = rect.bounds
            out[letter] = ((b.left + b.right) / 2f) to ((b.top + b.bottom) / 2f)
        }
        return out
    }
}
