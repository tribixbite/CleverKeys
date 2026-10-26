package tribixbite.cleverkeys.clipboard

/**
 * Bounded display text for clipboard rows (pure; Saga 2026-10-26, todo 22 a).
 *
 * A row used to bind the entry's FULL text. Even a one-line, ellipsized TextView line-breaks
 * all of it (`StaticLayout` → `LineBreaker.computeLineBreaks`), so three ~0.5 MB entries blocked
 * the keyboard's main thread 46–58 s on pane open and again on entering Select mode, and a tap
 * in that window was an input-dispatch ANR. Every list binding (History, Pinned, To-dos, search
 * results, Select mode) now goes through [forRow]; the full text is used only for paste, copy
 * and the stored entry itself.
 *
 * Cuts are made on a code-point boundary (a surrogate pair is never split). Grapheme clusters
 * (ZWJ emoji sequences, combining marks) may still be cut between code points; the ellipsis
 * marks the cut. The cost of a cut is bounded by the limit, not by the entry's length.
 */
object ClipboardPreviewPolicy {
    /** Collapsed rows show one line (two for media); a few hundred chars fill any pane width. */
    const val COLLAPSED_MAX_CHARS = 300
    const val COLLAPSED_MAX_LINES = 2

    /**
     * Expanded rows: enough to read a long entry, small enough to lay out quickly. The same
     * bound decides whether the inline editor is offered ([isEditableInline]): the editor
     * re-sets its whole text on every keystroke, so an entry the expanded row cannot show
     * whole is pasted or copied, not edited in the pane.
     */
    const val EXPANDED_MAX_CHARS = 20_000
    const val EXPANDED_MAX_LINES = 1_000

    const val ELLIPSIS = "…"

    /**
     * @property text what the row binds: the kept prefix, plus [ELLIPSIS] when [truncated]
     * @property truncated whether anything of the entry was left out
     * @property shownLength UTF-16 length of the kept prefix of the entry (no ellipsis)
     */
    data class Preview(val text: String, val truncated: Boolean, val shownLength: Int)

    /** The preview a collapsed or expanded row binds for [content]. */
    fun forRow(content: CharSequence, expanded: Boolean): Preview =
        if (expanded) cut(content, EXPANDED_MAX_CHARS, EXPANDED_MAX_LINES)
        else cut(content, COLLAPSED_MAX_CHARS, COLLAPSED_MAX_LINES)

    /**
     * Keep at most [maxChars] UTF-16 units and [maxLines] lines of [content]. Reads only the
     * first `maxChars + 1` chars. A cut text never ends in a line break (the newline and a CR
     * before it are dropped, so the ellipsis ends the last kept line); a single newline that
     * ends the whole text is not a truncation.
     */
    fun cut(content: CharSequence, maxChars: Int, maxLines: Int): Preview {
        require(maxChars >= 0 && maxLines >= 1) { "maxChars=$maxChars maxLines=$maxLines" }
        val length = content.length
        val scanEnd = minOf(length, maxChars)
        var cut = scanEnd
        var lines = 1
        var i = 0
        while (i < scanEnd) {
            if (content[i] == '\n') {
                if (lines == maxLines) {
                    // A newline that ends the text adds no visible line: keep it, uncut.
                    if (i == length - 1) break
                    cut = if (i > 0 && content[i - 1] == '\r') i - 1 else i
                    break
                }
                lines++
            }
            i++
        }
        // Never keep a high surrogate without its low half.
        if (cut in 1 until length && content[cut - 1].isHighSurrogate() && content[cut].isLowSurrogate()) {
            cut--
        }
        if (cut >= length) return Preview(content.toString(), truncated = false, shownLength = length)
        // A cut right after a line break would put the ellipsis (and the row's metadata) alone
        // on an extra line: end the kept text before that break.
        if (cut > 0 && content[cut - 1] == '\n') {
            cut--
            if (cut > 0 && content[cut - 1] == '\r') cut--
        }
        return Preview(content.subSequence(0, cut).toString() + ELLIPSIS, truncated = true, shownLength = cut)
    }

    /** (shown, total) code points of [content] for a "first N of M characters" note. */
    fun codePointCounts(content: String, preview: Preview): Pair<Int, Int> =
        content.codePointCount(0, preview.shownLength) to content.codePointCount(0, content.length)

    /** The inline editor is offered only for entries the expanded row shows whole. */
    fun isEditableInline(content: CharSequence): Boolean = !forRow(content, expanded = true).truncated
}
