package tribixbite.cleverkeys.clipboard

/**
 * Where a tap on a soft-wrapped line of the clipboard editor puts the caret (pure).
 *
 * Maintainer 2026-11-26: "tapping the end of a line puts the cursor BEFORE the last character of
 * that line (for wraparounds like urls)". That is the platform's rule: `Layout.getOffsetForHorizontal`
 * never returns a line's end offset unless it is the LAST line (AOSP Layout.java, `max =
 * tl.getOffsetToLeftRightOf(lineEnd - lineStart, …)`), because on a soft-wrapped line that offset
 * is also the next line's first, where TextView draws the caret (it has no caret affinity). A line
 * that wraps at a space loses nothing: the space hangs past the edge, so "before the last
 * character" is right after the visible text. A line that wraps INSIDE a word — a URL, a long
 * word, CJK, which breaks between any two ideographs — loses its last character: a tap at its end
 * landed one character early and a Backspace there deleted the wrong one.
 *
 * Here a tap past the midpoint of such a line's last character goes after it ([LineEnd.lineEnd]),
 * so typing and Backspace act where the user tapped. TextView still DRAWS that caret at the start
 * of the next line (the same logical position). `ClipboardEditField.getOffsetForPosition` feeds
 * this from the field's Layout; the drag paths (a dragged caret or handle) use the platform's
 * package-private `getOffsetAtCoordinate` and keep its rule.
 */
object SoftWrapTap {

    /**
     * The end of one laid-out line, as `ClipboardEditField` reads it from the field's Layout.
     *
     * @property lineEnd `Layout.getLineEnd(line)`: the first offset NOT on this line; for a
     *   soft-wrapped line it is also the next line's first offset.
     * @property isLastLine the field's last line (Layout lets a tap there reach [lineEnd]).
     * @property lastChar the line's last character, `text[lineEnd - 1]`.
     * @property clampOffset the last offset a tap on this line can reach in the platform: the start
     *   of the line's last character (grapheme cluster), `Layout.getOffsetForHorizontal` past the
     *   line's end.
     * @property leadingX horizontal position of that character's leading edge
     *   (`Layout.getPrimaryHorizontal(clampOffset)`).
     * @property trailingX horizontal position of its trailing edge, the line's visible end
     *   (`Layout.getLineRight` in a left-to-right paragraph, `getLineLeft` in a right-to-left one).
     * @property rtl the paragraph runs right to left.
     * @property directionsAgree the last character runs in the paragraph's direction.
     */
    data class LineEnd(
        val lineEnd: Int,
        val isLastLine: Boolean,
        val lastChar: Char,
        val clampOffset: Int,
        val leadingX: Float,
        val trailingX: Float,
        val rtl: Boolean,
        val directionsAgree: Boolean,
    )

    /**
     * The caret offset for a tap at local horizontal position [tapX] on the line [end] describes,
     * where the platform chose [platformOffset]. Only the platform's clamp at the end of a line that
     * wraps inside a word changes, and only when the tap is past the last character's midpoint in
     * the reading direction. Everything else keeps the platform's choice: the last line, a line
     * that ends with whitespace or a line break, and a line end whose last character runs against
     * the paragraph (which edge is "past" it then depends on the bidi runs).
     */
    fun offsetForTap(platformOffset: Int, tapX: Float, end: LineEnd): Int {
        if (end.isLastLine || platformOffset != end.clampOffset || end.clampOffset >= end.lineEnd) return platformOffset
        if (!end.directionsAgree || hangsOrBreaks(end.lastChar)) return platformOffset
        val midpoint = (end.leadingX + end.trailingX) / 2f
        val pastMidpoint = if (end.rtl) tapX <= midpoint else tapX >= midpoint
        return if (pastMidpoint) end.lineEnd else platformOffset
    }

    /**
     * Whitespace hangs past the line's edge (a no-break space included) and a line or paragraph
     * break ends the line: the offset before either is already right after the visible text.
     */
    private fun hangsOrBreaks(c: Char): Boolean = Character.isWhitespace(c) || Character.isSpaceChar(c)
}
