package tribixbite.cleverkeys.clipboard

/**
 * Paged inline editing of clipboard entries too long to edit whole (pure; todo 22, 2026-10-26).
 *
 * The inline editor re-sets its WHOLE text on every keystroke (IME-owned EditText,
 * ClipboardHistoryView), so a 0.5 MB entry cannot sit in the field. Such an entry is edited
 * one page at a time: the field holds only the current page, the rest lives in [Session]'s
 * working copy, and Save splices the page back and writes the full text in ONE update.
 *
 * A page is at most [PAGE_MAX_CHARS] UTF-16 units and [PAGE_MAX_LINES] lines — the same bounds
 * an expanded row shows whole ([ClipboardPreviewPolicy]), so an entry is paged exactly when
 * [ClipboardPreviewPolicy.isEditableInline] is false and short entries keep the single-page
 * editor. A page that hits the char limit ends after the last line break in the final quarter
 * of its window, else after the last whitespace there, else at the limit; a cut never splits
 * a surrogate pair or a CRLF. Finding one page end reads at most one window, so the cost is
 * bounded per page, never O(entry) per keystroke.
 */
object ClipboardEditPages {
    const val PAGE_MAX_CHARS = ClipboardPreviewPolicy.EXPANDED_MAX_CHARS
    const val PAGE_MAX_LINES = ClipboardPreviewPolicy.EXPANDED_MAX_LINES

    /** A char-limited page looks for a break in the last 1/[BREAK_SEARCH_DIVISOR] of its window. */
    private const val BREAK_SEARCH_DIVISOR = 4

    /** Whether [text] is edited in pages (it does not fit one page). */
    fun needsPaging(text: CharSequence): Boolean = !ClipboardPreviewPolicy.isEditableInline(text)

    /**
     * End offset (exclusive) of the page of [text] that starts at [start], considering only
     * text before [limit]. Returns [limit] when the rest fits in one page.
     */
    fun pageEnd(
        text: CharSequence,
        start: Int,
        limit: Int = text.length,
        maxChars: Int = PAGE_MAX_CHARS,
        maxLines: Int = PAGE_MAX_LINES,
    ): Int {
        require(maxChars >= 2 && maxLines >= 1) { "maxChars=$maxChars maxLines=$maxLines" }
        require(start in 0..limit && limit <= text.length) { "start=$start limit=$limit length=${text.length}" }
        val windowEnd = minOf(limit, start + maxChars)
        // Line limit: the page ends right after its last allowed line break.
        var lines = 1
        for (i in start until windowEnd) {
            if (text[i] == '\n') {
                if (lines == maxLines) return i + 1
                lines++
            }
        }
        if (windowEnd == limit) return limit
        // Char limit: prefer a line break, then whitespace, in the last part of the window.
        val floor = windowEnd - maxOf(1, maxChars / BREAK_SEARCH_DIVISOR)
        for (j in windowEnd - 1 downTo floor) {
            if (text[j] == '\n') return j + 1
        }
        for (j in windowEnd - 1 downTo floor) {
            if (text[j].isWhitespace() && !splitsUnit(text, j + 1, limit)) return j + 1
        }
        var cut = windowEnd
        if (splitsUnit(text, cut, limit)) cut--
        return cut
    }

    /** Page start offsets of `text[from, until)`; the first is [from]. One page when it is empty. */
    fun pageStarts(
        text: CharSequence,
        from: Int = 0,
        until: Int = text.length,
        maxChars: Int = PAGE_MAX_CHARS,
        maxLines: Int = PAGE_MAX_LINES,
    ): IntArray {
        val starts = ArrayList<Int>()
        var s = from
        do {
            starts += s
            s = pageEnd(text, s, until, maxChars, maxLines)
        } while (s < until)
        return starts.toIntArray()
    }

    /** Whether a cut at [cut] would separate a surrogate pair or a CR from its LF. */
    private fun splitsUnit(text: CharSequence, cut: Int, limit: Int): Boolean {
        if (cut <= 0 || cut >= limit) return false
        val before = text[cut - 1]
        val after = text[cut]
        return (before.isHighSurrogate() && after.isLowSurrogate()) || (before == '\r' && after == '\n')
    }

    /**
     * One paged edit session. [original] is the entry as opened (Save is keyed on it, so a row
     * that changed meanwhile is a conflict, not an overwrite). Edits of earlier pages are kept
     * in [working]; every navigation first splices the field's text ([editedPage]) into it.
     *
     * Only the edited page is re-split after a splice: pages before it are untouched and pages
     * after it keep their boundaries (shifted), so a page the user has seen stays the same page.
     * An emptied page disappears. Navigation is by index: next/previous are ±1 in the working
     * copy (after an edit that overflowed a page, "next" shows the overflow).
     */
    class Session(
        val original: String,
        private val maxChars: Int = PAGE_MAX_CHARS,
        private val maxLines: Int = PAGE_MAX_LINES,
    ) {
        /** The full text with every page edit committed so far (not the current field). */
        var working: String = original
            private set
        private var starts: IntArray = pageStarts(original, 0, original.length, maxChars, maxLines)

        /** Index of the page in the field, 0-based. */
        var pageIndex: Int = 0
            private set

        val pageCount: Int get() = starts.size

        /** Whether everything outside the current page is whitespace (Save needs some text). */
        var othersBlank: Boolean = computeOthersBlank()
            private set

        private fun startOf(i: Int) = starts[i]
        private fun endOf(i: Int) = if (i + 1 < starts.size) starts[i + 1] else working.length

        /** The current page of the working copy (what the field is given). */
        fun pageText(): String = working.substring(startOf(pageIndex), endOf(pageIndex))

        /** The full text Save writes: [working] with the current page replaced by [editedPage]. */
        fun fullText(editedPage: String): String {
            val s = startOf(pageIndex)
            val e = endOf(pageIndex)
            return buildString(working.length - (e - s) + editedPage.length) {
                append(working, 0, s).append(editedPage).append(working, e, working.length)
            }
        }

        /** Move to the following page. False when there is none and nothing changed. */
        fun next(editedPage: String): Boolean = move(editedPage) { idx, spanPages, _ -> if (spanPages == 0) idx else idx + 1 }

        /** Move to the preceding page. False when there is none and nothing changed. */
        fun previous(editedPage: String): Boolean = move(editedPage) { idx, _, _ -> idx - 1 }

        fun first(editedPage: String): Boolean = move(editedPage) { _, _, _ -> 0 }

        fun last(editedPage: String): Boolean = move(editedPage) { _, _, count -> count - 1 }

        /** Go to page [index] (0-based, counted after the splice). False when out of range and nothing changed. */
        fun goTo(index: Int, editedPage: String): Boolean = move(editedPage) { _, _, _ -> index }

        /**
         * Splice [editedPage] in, re-split it, then go to the page [target] picks from the
         * spliced page's index, how many pages it became (0 when emptied) and the new page
         * count. Out-of-range
         * targets keep the current index (clamped). True when the page or its text changed.
         */
        private inline fun move(editedPage: String, target: (idx: Int, spanPages: Int, count: Int) -> Int): Boolean {
            val oldIndex = pageIndex
            val idx = pageIndex
            val s = startOf(idx)
            val e = endOf(idx)
            val changed = working.regionMatches(s, editedPage, 0, editedPage.length).not() || e - s != editedPage.length
            var spanPages = 1
            if (changed) {
                working = buildString(working.length - (e - s) + editedPage.length) {
                    append(working, 0, s).append(editedPage).append(working, e, working.length)
                }
                val delta = editedPage.length - (e - s)
                val span = if (editedPage.isEmpty()) IntArray(0)
                    else pageStarts(working, s, s + editedPage.length, maxChars, maxLines)
                spanPages = span.size
                val after = IntArray(starts.size - idx - 1) { starts[idx + 1 + it] + delta }
                var merged = starts.copyOfRange(0, idx) + span + after
                if (merged.isEmpty()) merged = intArrayOf(0)  // the whole entry was emptied
                starts = merged
            }
            val wanted = target(idx, spanPages, starts.size)
            val inRange = wanted in 0 until starts.size
            pageIndex = if (inRange) wanted else pageIndex.coerceIn(0, starts.size - 1)
            othersBlank = computeOthersBlank()
            return changed || (inRange && pageIndex != oldIndex)
        }

        private fun computeOthersBlank(): Boolean {
            val s = startOf(pageIndex)
            val e = endOf(pageIndex)
            for (i in 0 until s) if (!working[i].isWhitespace()) return false
            for (i in e until working.length) if (!working[i].isWhitespace()) return false
            return true
        }
    }
}
