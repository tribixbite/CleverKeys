package tribixbite.cleverkeys.clipboard

/**
 * One caret step in the clipboard pane's inline editor (pure). The arrow keys and the space-bar
 * slider move the editor's caret through `ClipboardHistoryView.dispatchKeyToEditText`, one step
 * per slider unit (maintainer 2026-11-26: "one slider step is one character", wrapped or not).
 *
 * A step is one CODE POINT, never one UTF-16 unit: a unit step stops between the two halves of a
 * surrogate pair (most emoji, many CJK extension ideographs), and a character typed there splits
 * it into two broken halves. A soft wrap changes nothing here: it is drawing only, the offsets on
 * both sides of it are consecutive. Grapheme clusters (an emoji with a skin tone or a ZWJ
 * sequence, a letter with combining marks) are still crossed one code point at a time.
 * TODO: step by grapheme cluster (java.text.BreakIterator.getCharacterInstance) if that is wanted;
 * no stop inside a cluster corrupts text, unlike one inside a surrogate pair.
 */
object ClipboardEditCaret {

    /** The offset one character before [offset] in [text] (clamped to the text). */
    fun stepBack(text: CharSequence, offset: Int): Int {
        val at = offset.coerceIn(0, text.length)
        if (at == 0) return 0
        // A low surrogate after its high surrogate is one character with it. A caret already
        // between a pair's halves (an older edit put it there) leaves the pair by one unit.
        return if (at >= 2 && Character.isLowSurrogate(text[at - 1]) && Character.isHighSurrogate(text[at - 2])) {
            at - 2
        } else {
            at - 1
        }
    }

    /** The offset one character after [offset] in [text] (clamped to the text). */
    fun stepForward(text: CharSequence, offset: Int): Int {
        val at = offset.coerceIn(0, text.length)
        if (at == text.length) return at
        return if (at + 1 < text.length && Character.isHighSurrogate(text[at]) && Character.isLowSurrogate(text[at + 1])) {
            at + 2
        } else {
            at + 1
        }
    }
}
